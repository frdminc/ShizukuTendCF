package af.shizuku.manager.harness

import af.shizuku.manager.R
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.utils.HeadlessLogger
import android.app.Application
import androidx.work.WorkInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * TCP mode, the port found closed, and no Wi-Fi to reopen it through wireless debugging: the port
 * comes back by itself (adbd restarting at the end of a deploy, `adb tcpip` from a computer) and
 * the app must use it without Wi-Fi.
 *
 * t2e (Titan 2), 2026-10-06 15:22: Wi-Fi off, on mobile data. adbd restarted at the end of a fleet
 * deploy; the start probed 5555 in that gap, waited 120 s for Wi-Fi, stopped with "no Wi-Fi", and
 * never looked at the port again although it was listening a few seconds later. Shizuku stayed
 * down until an external HEADLESS_START 15 minutes on; without one it would have stayed down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoWifiTcpPortBackScenariosTest {
    private lateinit var world: FakeWorld
    private val tcpPort get() = world.closedTcpPort

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        world.prefs
            .edit()
            .putBoolean("tcp_mode", true)
            .putString("tcp_port", tcpPort.toString())
            .putInt("last_adb_port", tcpPort)
            .commit()
        val path = checkNotNull(HeadlessLogger.getLogPath()) { "HeadlessLogger has no log file" }
        File(path).delete()
        File("$path.1").delete()
    }

    @After
    fun tearDown() = world.close()

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    private fun Scenario.assertLogHas(vararg lines: String) {
        val text = log()
        lines.forEach { assertTrue("headless log has \"$it\":\n$text", it in text) }
    }

    private fun Scenario.assertLogLacks(vararg lines: String) {
        val text = log()
        lines.forEach { assertFalse("headless log has no \"$it\":\n$text", it in text) }
    }

    /** Real time passes (the worker's waits are real), with the main looper kept running. */
    private fun Scenario.realTimePasses(ms: Long) {
        val until = System.currentTimeMillis() + ms
        world.waitUntil({ "$ms ms to pass" }) { System.currentTimeMillis() >= until }
    }

    private fun Scenario.awaitStarts(n: Int) = world.waitUntil({ "start $n to run (ran $startsRun)" }) { startsRun >= n }

    /** Started through the TCP port with wireless debugging never written, and nothing left behind. */
    private fun Scenario.assertStartedThroughTcpWithoutWirelessDebugging() {
        assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
        assertTrue("server up", serverRunning)
        assertTrue("tcp port open", tcpPortOpen)
        assertEquals("no adbd dialog", 0, allOffers)
        assertEquals("adb_wifi_enabled never written 1", 0, turnOns)
        assertEquals(0, adbWifiEnabled)
        assertEquals("no network prompt", 0, trustPrompts)
        assertNull("no restore notice left", restoreNoticeTitle)
        assertFalse("no turned-on marker", world.prefs.contains("wadb_restore_turned_on"))
        assertFalse("no write-pending marker", world.prefs.contains("wadb_restore_write_pending_boot"))
        assertFalse("no no-Wi-Fi stop recorded", world.prefs.contains("wadb_restore_no_wifi_boot"))
        // A success cancels the rechecks; WorkManager applies the cancel asynchronously.
        world.waitUntil({ "no recheck left queued" }) { !portRecheckQueued }
    }

    // (a) The t2e case, caught while the start still waits for Wi-Fi.
    @Test
    fun `no-wifi-tcp-port-back-during-the-wifi-wait-starts-through-it`() =
        scenario {
            wifiDrops()
            boot()
            startWaitsForWifi()
            awaitLog("no Wi-Fi; waiting up to")
            realTimePasses(3_000)
            tcpPortOpens(tcpPort)
            awaitStartWork()
            check {
                assertStartedThroughTcpWithoutWirelessDebugging()
                assertEquals("one run, the same attempt", 1, startsRun)
                assertLogHas(
                    "StartWorker: tcp fast path: port $tcpPort closed",
                    "StartWorker: no Wi-Fi; waiting up to",
                    "StartWorker: tcp port $tcpPort is open again; starting through it",
                    "StartWorker: SUCCESS via tcp fast path on port $tcpPort",
                )
                assertLogLacks("no Wi-Fi after", "FAILURE", "turning wireless debugging on", "RETRY")
            }
        }

    // The wait's own rechecks never saw it; the look before giving up does.
    @Test
    fun `no-wifi-tcp-port-back-just-before-giving-up-starts-through-it`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_500
            wifiDrops()
            boot()
            startWaitsForWifi()
            awaitLog("no Wi-Fi; waiting up to")
            realTimePasses(300)
            tcpPortOpens(tcpPort)
            awaitStartWork()
            check {
                assertStartedThroughTcpWithoutWirelessDebugging()
                assertEquals(1, startsRun)
                assertLogHas("StartWorker: tcp port $tcpPort is open again; starting through it")
                assertLogLacks("no Wi-Fi after", "FAILURE")
            }
        }

    // (b) Back only after the start gave up: the bounded recheck finds it.
    @Test
    fun `no-wifi-tcp-port-back-after-the-stop-is-started-by-the-recheck`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_000
            wifiDrops()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(string(R.string.wadb_restore_no_wifi_title), restoreNoticeTitle)
                assertTrue("the port is rechecked later", portRecheckQueued)
            }
            // One recheck finds it still closed and queues the next.
            portRecheckDue()
            check {
                assertEquals("nothing to start yet", 1, startsRun)
                assertTrue(portRecheckQueued)
            }
            tcpPortOpens(tcpPort)
            portRecheckDue()
            awaitStarts(2)
            awaitStartWork()
            check {
                assertStartedThroughTcpWithoutWirelessDebugging()
                assertEquals(2, startsRun)
                assertLogHas(
                    "WirelessDebugging: tcp port $tcpPort is open again; starting through it",
                    "StartWorker: SUCCESS via tcp fast path on port $tcpPort",
                )
            }
        }

    // (c) Never back: the stop is as before, the rechecks end, and nothing starts in a loop.
    @Test
    fun `no-wifi-tcp-port-never-back-rechecks-end-without-a-storm`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_000
            wifiDrops()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(string(R.string.wadb_restore_no_wifi_title), restoreNoticeTitle)
                assertLogHas("StartWorker: no Wi-Fi after", "StartWorker: FAILURE")
            }
            repeat(RECHECKS) {
                assertTrue("recheck ${it + 1} queued", portRecheckQueued)
                portRecheckDue()
            }
            check {
                assertFalse("the rechecks end", portRecheckQueued)
                assertEquals("no start while the port stayed closed", 1, startsRun)
                assertLogHas("WirelessDebugging: tcp port $tcpPort still closed after $RECHECKS checks")
            }
            // The watchdog or the boot retry stands down as before, and arms no new rechecks.
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(2, startsRun)
                assertLogHas("still no Wi-Fi; waiting for Wi-Fi to connect")
                assertFalse("no second round of rechecks", portRecheckQueued)
                assertEquals("one wait only", 1, Regex("no Wi-Fi; waiting up to").findAll(log()).count())
            }
            // Wi-Fi connecting still resumes the restore.
            wifiConnects()
            awaitStarts(3)
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertTrue(serverRunning)
                assertTrue(tcpPortOpen)
                assertFalse(portRecheckQueued)
            }
        }

    @Test
    fun `no-wifi-port-recheck-is-cancelled-at-boot`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_000
            wifiDrops()
            boot()
            awaitStartWork()
            assertTrue(portRecheckQueued)
            reboot()
            WirelessDebugging.onBoot(world.app)
            world.settle()
            assertFalse("a new boot starts its own", portRecheckQueued)
        }

    // (d) adbd still reports the port (service.adb.tcp.port) but it is closed for a moment (adbd
    // restarting): probed again for a few seconds before anything turns wireless debugging on.
    @Test
    fun `tcp-port-reported-by-adbd-but-briefly-closed-is-probed-again`() =
        scenario {
            world.adbdReportsTcpPort(tcpPort)
            boot()
            awaitLog("tcp fast path: port $tcpPort closed")
            realTimePasses(1_000)
            tcpPortOpens(tcpPort)
            awaitStartWork()
            check {
                assertStartedThroughTcpWithoutWirelessDebugging()
                assertEquals(1, startsRun)
                assertLogHas(
                    "StartWorker: tcp port $tcpPort is open again; starting through it",
                    "StartWorker: SUCCESS via tcp fast path on port $tcpPort",
                )
                assertLogLacks("restoring it through wireless debugging", "turning wireless debugging on")
            }
        }

    // adbd reports the port but it stays closed: after the short re-probe, the restore goes on.
    @Test
    fun `tcp-port-reported-by-adbd-and-still-closed-restores-through-wireless-debugging`() =
        scenario {
            world.adbdReportsTcpPort(tcpPort)
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertTrue(serverRunning)
                assertTrue(tcpPortOpen)
                assertEquals("wireless debugging back off", 0, adbWifiEnabled)
                assertLogHas(
                    "although adbd reports it",
                    "restoring it through wireless debugging",
                    "StartWorker: SUCCESS",
                )
            }
        }

    private companion object {
        // WirelessDebugging's rechecks of the TCP port after a no-Wi-Fi stop.
        const val RECHECKS = 5
    }
}
