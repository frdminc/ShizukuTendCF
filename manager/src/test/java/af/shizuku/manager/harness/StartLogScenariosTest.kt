package af.shizuku.manager.harness

import af.shizuku.manager.receiver.HeadlessStartStopReceiver
import af.shizuku.manager.utils.HeadlessLogger
import android.app.Application
import androidx.work.WorkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A release build logs nothing through Timber and keeps headless.log where `adb shell` cannot read
 * it, so a start that fails must leave its reason where HEADLESS_LOG returns it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartLogScenariosTest {
    private lateinit var world: FakeWorld

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // Plain saved-port mode, the path FakeAdbd models (see OnePromptScenariosTest).
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .commit()
        clearLog()
    }

    @After
    fun tearDown() = world.close()

    private fun clearLog() {
        val path = checkNotNull(HeadlessLogger.getLogPath()) { "HeadlessLogger has no log file" }
        File(path).delete()
        File("$path.1").delete()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    @Test
    fun `failed-boot-start-reason-in-headless-log`() =
        scenario {
            boot()
            keyOffered()
            dialogRejected()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                val log = headlessLog()
                assertEquals("HEADLESS_LOG result code", 0, log.code)
                val text = log.data.orEmpty()
                listOf(
                    "Boot: received BOOT_COMPLETED",
                    "Starter: start: adb, queueing the start worker",
                    "StartWorker: run attempt=0",
                    "StartWorker: using the saved port ${adbd.port}",
                    "AdbStarter: key not yet authorised; adbd is asking",
                    "AdbStarter: failed on port ${adbd.port}: AdbAuthTimeoutException",
                    "StartWorker: FAILURE authorisation dialog not accepted, not retrying (attempt=0)",
                ).forEach { assertTrue("headless log has \"$it\":\n$text", it in text) }
                assertEquals("lines extra", text.lines().size, log.extras?.getInt(HeadlessStartStopReceiver.EXTRA_LINES))
                assertEquals(false, log.extras?.getBoolean("truncated"))

                val last = headlessLog(lines = 1)
                assertEquals(1, last.extras?.getInt(HeadlessStartStopReceiver.EXTRA_LINES))
                assertFalse("one line", last.data.orEmpty().contains('\n'))
                assertTrue("the newest line is the outcome: ${last.data}", "StartWorker: FAILURE" in last.data.orEmpty())
            }
        }

    @Test
    fun `headless-log-without-a-log`() =
        scenario {
            val log = headlessLog()
            check {
                assertEquals(HeadlessStartStopReceiver.RESULT_NO_LOG, log.code)
                assertEquals("NO_LOG", log.data)
                assertEquals(0, log.extras?.getInt(HeadlessStartStopReceiver.EXTRA_LINES))
            }
        }
}
