package af.shizuku.manager.harness

import af.shizuku.manager.receiver.HeadlessStartStopReceiver
import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "At most one adbd authorisation dialog per boot or per fresh user gesture", driven end to end:
 * receivers, the shared starter, WorkManager, AdbStartWorker, AdbStarter and AdbClient all run for
 * real against [FakeWorld]'s adbd. Test names are the scenario catalogue's ids (A2 §3).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: ShizukuApplication's onCreate (Sentry, Koin) is not part
// of the start path and cannot run twice in one JVM. FakeWorld.install() sets up what the path needs.
@Config(sdk = [35], application = Application::class)
class OnePromptScenariosTest {
    private lateinit var world: FakeWorld

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // tcp_mode defaults to true, which makes AdbStarter treat the saved port as a
        // wireless-debugging port: it answers an accepted dialog with "tcpip:<tcp_port>"
        // (5555 by default) and reconnects there, where FakeAdbd listens on nothing.
        // Plain saved-port mode is the path FakeAdbd models.
        world.prefs.edit().putBoolean("tcp_mode", false).commit()
    }

    @After
    fun tearDown() = world.close()

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    @Test
    fun `boot-cold-start-accepted`() =
        scenario {
            boot()
            keyOffered()
            dialogAccepted()
            check {
                expectOffers(1)
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertNull("marker after acceptance", marker)
                assertTrue("server up", serverRunning)
                assertEquals(1, headlessStart().code)
            }
        }

    @Test
    fun `boot-rejected-key`() =
        scenario {
            boot()
            keyOffered()
            dialogRejected()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertNotNull("marker after a rejected key", marker)
                assertEquals("boot retry stops", ListenableWorker.Result.success(), bootRetryTick())
                assertEquals("no further start ran", 1, startsRun)
                expectOffers(1)
            }
        }

    @Test
    fun `boot-clears-stale-marker`() =
        scenario {
            markerSetAt(now)
            boot()
            keyOffered()
            dialogAccepted()
            check {
                expectOffers(1)
                assertNull("marker after acceptance", marker)
                assertTrue("server up", serverRunning)
            }
        }

    @Test
    fun `headless-plain-respects-marker`() =
        scenario {
            markerSetAt(now)
            val result = headlessStart(force = false)
            check {
                assertEquals(HeadlessStartStopReceiver.RESULT_AUTH_UNANSWERED, result.code)
                assertEquals("no start enqueued", null, awaitStartWork())
                assertNotNull("marker kept", marker)
                expectOffers(0)
            }
        }

    @Test
    fun `headless-force-clears-offers-once`() =
        scenario {
            val first = now
            markerSetAt(first)
            advanceTime(1_000)
            assertEquals("forced start accepted", 0, headlessStart(force = true).code)
            keyOffered()
            dialogTimedOut()
            check {
                expectOffers(1)
                assertTrue("the new offer re-marked", (marker ?: 0L) > first)
                assertEquals(
                    "a plain start after the forced one is withheld again",
                    HeadlessStartStopReceiver.RESULT_AUTH_UNANSWERED,
                    headlessStart(force = false).code,
                )
                expectOffers(1)
            }
        }

    @Test
    fun `tile-tap-older-marker`() =
        scenario {
            markerSetAt(now)
            advanceTime(1_000)
            // The tile's request is stamped now; its dialog is recorded later.
            adbd.onHello = { world.advanceTime(1_000) }
            val requestedAt = now
            tileTap()
            keyOffered()
            dialogTimedOut()
            check {
                expectOffers(1)
                assertTrue("marker newer than the tile's request", (marker ?: 0L) > requestedAt)
                assertEquals("rerun of the tile request refused", ListenableWorker.Result.failure(), workerRerun())
                expectOffers(1)
            }
        }

    @Test
    fun `process-death-mid-wait`() =
        scenario {
            boot()
            keyOffered()
            processDeath()
            check {
                assertTrue("marker committed before the key went out", markerOnDisk)
                assertEquals("rerun refused", ListenableWorker.Result.failure(), workerRerun())
                assertEquals("boot retry stops", ListenableWorker.Result.success(), bootRetryTick())
                expectOffers(1)
            }
        }

    // SD-1: markUnanswered() commit()s but clearUnanswered() only apply()s, so a process killed
    // before the OS flushes the clear comes back believing an accepted key went unanswered.
    // Expected to fail until the clear is made durable.
    @Test
    fun `accepted-then-killed-before-flush`() =
        scenario {
            boot()
            keyOffered()
            dialogAccepted()
            processDeath()
            val status = headlessStatus()
            check {
                expectOffers(1)
                assertFalse(
                    "HEADLESS_STATUS auth_unanswered after the key was accepted (clear lost with the process)",
                    status.extras?.getBoolean("auth_unanswered") ?: true,
                )
                assertNull("marker after acceptance survives process death", marker)
            }
        }

    // SD-2: isUnanswered() fails open where unansweredStamp() fails closed. With storage unreadable
    // the boot retry loop must stop (and offer nothing) rather than start and stand down forever.
    // Expected to fail until isUnanswered() fails closed.
    @Test
    fun `prefs-unreadable-fail-closed`() =
        scenario {
            world.prefs.failOnRead = true
            val tick = runCatching { bootRetryTick() }
            awaitStartWork()
            check {
                expectOffers(0)
                assertEquals(
                    "boot retry with unreadable storage (threw: ${tick.exceptionOrNull()})",
                    ListenableWorker.Result.success(),
                    tick.getOrNull(),
                )
            }
        }
}
