package af.shizuku.manager.harness

import af.shizuku.manager.R
import af.shizuku.manager.adb.LocalNetworkPermission
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Android 17 gates the mDNS discovery of wireless debugging behind local network access and, for
 * an app without it, refuses a no-picker discovery outright (#25). A start that nobody can grant
 * the permission from (boot, the watchdog, HEADLESS_START) must say so and stop before it turns
 * wireless debugging on, rather than wait for a port that cannot come or spend this boot's one
 * network prompt. Wireless debugging is the transport here: no saved port, no TCP port.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalNetworkScenariosTest {
    private lateinit var world: FakeWorld

    @Before
    fun setUp() {
        world = FakeWorld(RuntimeEnvironment.getApplication()).also { it.install() }
        world.prefs
            .edit()
            .putBoolean("tcp_mode", false)
            .putBoolean("force_start_wadb", true)
            .putInt("last_adb_port", 0)
            .commit()
        val path = checkNotNull(HeadlessLogger.getLogPath()) { "HeadlessLogger has no log file" }
        File(path).delete()
        File("$path.1").delete()
    }

    @After
    fun tearDown() {
        LocalNetworkPermission.resetForTesting()
        world.close()
    }

    private fun scenario(block: Scenario.() -> Unit) = world.scenario(block)

    private fun Scenario.assertLogHas(vararg lines: String) {
        val text = log()
        lines.forEach { assertTrue("headless log has \"$it\":\n$text", it in text) }
    }

    private fun android17(granted: Boolean) {
        LocalNetworkPermission.sdkInt = 37
        if (granted) shadowOf(world.app).grantPermissions(ACCESS_LOCAL_NETWORK) else shadowOf(world.app).denyPermissions(ACCESS_LOCAL_NETWORK)
        assertEquals(granted, LocalNetworkPermission.granted(world.app))
    }

    @Test
    fun `android-17-headless-start-without-local-network-access-stops-and-says-so`() =
        scenario {
            android17(granted = false)
            headlessStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertFalse("no server", serverRunning)
                // Stopped before touching wireless debugging: no write, no prompt to spend.
                assertEquals("wireless debugging untouched", 0, adbWifiEnabled)
                assertEquals(0, turnOns)
                assertEquals(0, trustPrompts)
                assertEquals(string(R.string.wadb_local_network_title), restoreNoticeTitle)
                assertLogHas(
                    "StartWorker: local network access (android.permission.ACCESS_LOCAL_NETWORK) is not granted; this Android refuses mDNS discovery without it",
                    "StartWorker: FAILURE (attempt=0): local network access",
                )
            }
        }

    @Test
    fun `android-17-headless-start-with-local-network-access-starts`() =
        scenario {
            android17(granted = true)
            headlessStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertTrue("server up", serverRunning)
                assertNull("no notice", restoreNoticeTitle)
                assertEquals(0, allOffers)
            }
        }

    @Test
    fun `android-16-without-nearby-devices-access-still-tries-and-warns`() =
        scenario {
            LocalNetworkPermission.sdkInt = 36
            shadowOf(world.app).denyPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            headlessStart()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.SUCCEEDED, lastWork)
                assertTrue("server up", serverRunning)
                assertNull("no notice", restoreNoticeTitle)
                assertLogHas("StartWorker: local network access (android.permission.NEARBY_WIFI_DEVICES) is not granted; mDNS discovery may find nothing")
            }
        }

    @Test
    fun `boot-start-without-local-network-access-posts-the-notice-once`() =
        scenario {
            android17(granted = false)
            boot()
            awaitStartWork()
            check {
                assertEquals(WorkInfo.State.FAILED, lastWork)
                assertEquals(string(R.string.wadb_local_network_title), restoreNoticeTitle)
                assertEquals(0, adbWifiEnabled)
            }
        }

    private companion object {
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
