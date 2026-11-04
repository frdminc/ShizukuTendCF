package af.shizuku.manager.activitylog

import af.shizuku.manager.utils.VendorBatteryRestrictions
import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The log stores English and [ActivityLogText] maps it at display time. In English the mapped text
 * must match what is stored (or the deliberate rewording), and templates must carry their numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ActivityLogTextTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun shown(action: String) = ActivityLogText.localize(context, action)

    @Test
    fun englishEntriesShowAsStored() {
        listOf(
            "Connected to Shizuku",
            "Service started via root",
            "Connection: Shizuku service stopped by you",
            "Connection: start did not complete (crashed)",
            "Connection: Wi-Fi lost while Shizuku was running",
            "Watchdog: turned on with the first server start (the default for a new install)",
            VendorBatteryRestrictions.LOG_ACTION_REPORTED_SAMSUNG_DEEP_SLEEP,
            "Service started via ADB on port 5555",
            "Connection: connecting via TCP on port 5555",
            "Watchdog: restarting after crash #3",
            "Connection: ADB start failed on port 37000 (IOException: refused (x))",
            ActivityLogText.TOGGLE_GRANT,
        ).forEach { assertEquals(it, shown(it)) }
    }

    @Test
    fun templatesKeepTheirArguments() {
        assertEquals("Connection: restored after 7 s", shown("Connection: restored after 7s"))
        assertEquals("Service started via a TCP probe on port 5555", shown("Service started via force_start_wadb TCP probe on port 5555"))
        assertEquals("Swipe: App info", shown("Swipe: app_info"))
        assertEquals("Long-press: Unfreeze", shown("Long-press: unfreeze"))
    }

    @Test
    fun unknownEntriesPassThrough() {
        listOf("su id", "Swipe: something_new", "Custom server event").forEach { assertEquals(it, shown(it)) }
    }
}
