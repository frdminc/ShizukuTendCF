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
 * TCP mode after something closed adbd's TCP port (a reboot, `adb usb`): the app opens it again
 * itself through wireless debugging, in the attempt that found it closed. Wireless debugging needs
 * Wi-Fi, and a network trusted for it; FakeWorld runs both as the system does. The manager's key
 * is already authorised (paired, or "Always allow"), so no scenario here raises adbd's dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TcpRestoreScenariosTest {
    private lateinit var world: FakeWorld
    private val tcpPort get() = world.closedTcpPort

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        // TCP mode (the default) on a closed port; the last port used is that port, as on a device.
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

    private fun Scenario.assertRestored() {
        assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
        assertTrue("server up", serverRunning)
        assertTrue("tcp port open again", tcpPortOpen)
        assertEquals("no adbd dialog", 0, allOffers)
        assertNull("no restore notice left", restoreNoticeTitle)
    }

    @Test
    fun `tcp-port-closed-restores-in-the-same-attempt`() =
        scenario {
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertEquals("wireless debugging back off, as the start found it", 0, adbWifiEnabled)
                assertLogHas(
                    "StartWorker: tcp fast path: port $tcpPort closed",
                    "restoring it through wireless debugging",
                    "StartWorker: turning wireless debugging on: adb_wifi_enabled 0 -> 1",
                    "StartWorker: mDNS found port ${world.wirelessAdbd.port}",
                    "AdbStarter: connected on ${world.wirelessAdbd.port}; switching adbd to tcp port $tcpPort (tcpip)",
                    "AdbStarter: starter ran on port $tcpPort",
                    "StartWorker: wireless debugging was off before this start; turned it off again",
                    "StartWorker: SUCCESS",
                )
                // The closed port is probed once, never dialled by the start's connect retries.
                assertLogLacks("connect 1/8 to port $tcpPort", "RETRY")
            }
        }

    @Test
    fun `tcp-port-closed-waits-for-wifi-without-internet`() =
        scenario {
            wifiDrops()
            boot()
            startWaitsForWifi()
            // No VALIDATED and no NOT_METERED: a Wi-Fi with no internet, metered, is enough.
            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertLogHas(
                    "StartWorker: no Wi-Fi; waiting up to",
                    "StartWorker: Wi-Fi connected",
                    "StartWorker: SUCCESS",
                )
            }
        }

    @Test
    fun `tcp-port-closed-stale-wireless-debugging-toggled-once`() =
        scenario {
            world.staleWirelessDebuggingOn()
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("one run, no retry", 1, startsRun)
                assertEquals("left on, as the start found it", 1, adbWifiEnabled)
                assertLogHas(
                    "StartWorker: wireless debugging is already on; discovering its port",
                    "StartWorker: mDNS found nothing in",
                    "turning it off and on once",
                    "StartWorker: mDNS found port ${world.wirelessAdbd.port}",
                )
            }
        }

    @Test
    fun `untrusted-network-asks-once-and-stops-retrying`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals("the system asked once; no rewrite loop", 1, trustPrompts)
                assertEquals(0, adbWifiEnabled)
                assertEquals(string(R.string.wadb_restore_untrusted_title), restoreNoticeTitle)
                assertEquals(string(R.string.wadb_restore_untrusted_text), restoreNoticeText)
                assertLogHas(
                    "StartWorker: adb_wifi_enabled went back to 0 with Wi-Fi connected: this network is not trusted for wireless debugging",
                    "not retrying until the network changes or the user acts",
                    "StartWorker: FAILURE",
                )
                assertLogLacks("RETRY")
            }

            // The watchdog or the boot retry: stands down without prompting again.
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(2, startsRun)
                assertEquals("no second prompt", 1, trustPrompts)
                assertLogHas("this Wi-Fi network is still not trusted for wireless debugging")
            }

            // The user starts by hand: that tries again, prompting once more.
            tileTap()
            awaitStartWork()
            check {
                assertEquals(3, startsRun)
                assertEquals(2, trustPrompts)
            }
        }

    @Test
    fun `untrusted-network-allowed-while-waiting`() =
        scenario {
            wifiConnects(trusted = false)
            boot()
            startAsksToAllowNetwork()
            userAllowsThisNetwork()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(1, startsRun)
                assertEquals(1, trustPrompts)
                assertLogHas("StartWorker: wireless debugging is on again: the network was allowed")
            }
        }

    @Test
    fun `untrusted-network-then-another-network-resumes`() =
        scenario {
            WirelessDebugging.userWaitMs = 1_500
            wifiConnects(trusted = false)
            boot()
            awaitStartWork()
            assertEquals(WorkInfo.State.FAILED, lastWork)

            wifiConnects(trusted = true)
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(2, startsRun)
                assertLogHas("WirelessDebugging: the Wi-Fi network changed; resuming the ADB restore")
            }
        }

    @Test
    fun `no-wifi-notifies-and-resumes-when-wifi-connects`() =
        scenario {
            WirelessDebugging.wifiWaitMs = 1_500
            wifiDrops()
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(string(R.string.wadb_restore_no_wifi_title), restoreNoticeTitle)
                assertEquals(string(R.string.wadb_restore_no_wifi_text), restoreNoticeText)
                assertLogHas("StartWorker: no Wi-Fi after", "StartWorker: FAILURE")
                assertLogLacks("RETRY")
            }

            // A start nobody asked for while there is still no Wi-Fi does not wait again.
            backgroundStart()
            awaitStartWork()
            check {
                assertEquals(2, startsRun)
                assertLogHas("still no Wi-Fi; waiting for Wi-Fi to connect")
                assertEquals("one wait only", 1, Regex("no Wi-Fi; waiting up to").findAll(log()).count())
            }

            wifiConnects()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals(3, startsRun)
                assertLogHas("WirelessDebugging: Wi-Fi connected; resuming the ADB restore")
            }
        }

    @Test
    fun `tcp-port-open-uses-it-directly`() =
        scenario {
            world.openTcpPort(tcpPort)
            boot()
            awaitStartWork()
            check {
                assertRestored()
                assertEquals("wireless debugging untouched", 0, adbWifiEnabled)
                assertLogHas("StartWorker: tcp fast path: port $tcpPort open", "StartWorker: SUCCESS via tcp fast path")
                assertLogLacks("turning wireless debugging on", "mDNS")
            }
        }
}
