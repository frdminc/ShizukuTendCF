package af.shizuku.manager.harness

import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbAuthWait
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
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .commit()
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

    // adbd never reports a denied dialog: the connection that offered the key stays open and
    // unauthorised. Once the user has had time to see and deny it, "Ask again" offers the key again
    // on the same connection (one new dialog) instead of waiting out the deadline, and accepting
    // it completes the original start.
    @Test
    fun `attempt-now-reoffers-after-deny`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            adbd.reject()
            advanceTime(AdbAuthWait.REOFFER_MIN_AGE_MS + 1L)
            attemptNow()
            keyOffered()
            dialogAccepted()
            check {
                expectOffers(2)
                assertEquals("the key was offered again on the held connection", 1, adbd.connections)
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertNull("marker after acceptance", marker)
                assertTrue("server up", serverRunning)
            }
        }

    // While a wait is held the start notification's button asks for the dialog again; it is
    // "Attempt now" everywhere else.
    @Test
    fun `ask-again-label-while-held`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            check { assertEquals(string(R.string.wadb_notification_ask_again), attemptAction) }
            dialogAccepted()
            check { assertEquals(WorkInfo.State.SUCCEEDED, lastWork) }
        }

    // A tap right after the offer: the user cannot have answered yet, and a re-offer would only
    // queue a second dialog behind the first. The tap says the dialog is awaited instead.
    @Test
    fun `ask-again-too-soon-toasts`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            attemptNow()
            check { assertEquals(string(R.string.wadb_notification_awaiting_auth), lastToast) }
            dialogTimedOut()
            world.settle()
            check {
                expectOffers(1)
                assertEquals(1, adbd.connections)
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals("the notice offers a fresh start", string(R.string.wadb_notification_attempt_now), attemptAction)
            }
        }

    // At most one re-offer per held wait, however long it lasts: a second tap, even after another
    // full guard period, says the dialog is awaited.
    @Test
    fun `ask-again-once-per-wait`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            adbd.reject()
            advanceTime(AdbAuthWait.REOFFER_MIN_AGE_MS + 1L)
            attemptNow()
            keyOffered()
            advanceTime(AdbAuthWait.REOFFER_MIN_AGE_MS + 1L)
            attemptNow()
            check { assertEquals(string(R.string.wadb_notification_awaiting_auth), lastToast) }
            dialogAccepted()
            check {
                expectOffers(2)
                assertEquals(1, adbd.connections)
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
            }
        }

    // adbd queues a prompt per offer and shows it even after the key was accepted, so a tap while
    // the first dialog is still up must not offer again: accepting that dialog would bring up
    // another for a key that is already allowed.
    @Test
    fun `ask-again-while-pending-then-accept`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            attemptNow()
            dialogAccepted()
            // Long enough for a re-offer written on another thread to reach adbd.
            Thread.sleep(300)
            check {
                expectOffers(1)
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertTrue("server up", serverRunning)
            }
        }

    // A re-offer restarts the deadline; the renewed wait still ends there, released and marked.
    @Test
    fun `ask-again-deadline-still-fires`() =
        scenario {
            authTimeout(REOFFER_AUTH_TIMEOUT_MS)
            boot()
            keyOffered()
            waitHeld()
            adbd.reject()
            advanceTime(AdbAuthWait.REOFFER_MIN_AGE_MS + 1L)
            attemptNow()
            keyOffered()
            dialogTimedOut()
            check {
                expectOffers(2)
                assertEquals(1, adbd.connections)
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertFalse("wait released", AdbAuthWait.isWaiting())
                assertNotNull("marker after an unanswered re-offer", marker)
                assertEquals(HeadlessStartStopReceiver.RESULT_AUTH_UNANSWERED, headlessStart(force = false).code)
                expectOffers(2)
            }
        }

    private companion object {
        // Real milliseconds: long enough that the deadline cannot fire between a scenario's taps on
        // a loaded machine.
        const val REOFFER_AUTH_TIMEOUT_MS = 8_000
    }
}
