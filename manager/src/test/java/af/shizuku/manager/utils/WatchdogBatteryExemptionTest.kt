package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine.State
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.Settings
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * A new install's Watchdog default turns the Watchdog on, and the app tells the user if Android's
 * battery optimization still restricts it. These tests drive the real post-RUNNING block in
 * [ShizukuStateMachine]: they set the settings state, then transition to RUNNING and wait for the
 * post-RUNNING work to finish, so each proves the order the production code guarantees (the
 * exemption check runs after Device Hardening's pass, only for the new-install default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WatchdogBatteryExemptionTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        setAppContext(app)
        ShizukuSettings.initialize(app)
        ShizukuSettings
            .getPreferences()
            .edit()
            .clear()
            .commit()
        ActivityLogManager.initialize(app, ActivityLogSettingsImpl())
        ActivityLogManager.clear()
        DeviceOptimizer.onAppWhitelistedForTesting = null
        DeviceOptimizer.runCommandOutputForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = null
        VendorBatteryRestrictions.resolveActivityForTesting = null
        ShizukuStateMachine.resetForTesting()
    }

    @After
    fun tearDown() {
        DeviceOptimizer.onAppWhitelistedForTesting = null
        DeviceOptimizer.runCommandOutputForTesting = null
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = null
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = null
        VendorBatteryRestrictions.resolveActivityForTesting = null
        ShizukuStateMachine.resetForTesting()
    }

    private fun setAppContext(application: Application) {
        try {
            val field = ShizukuApplication.Companion::class.java.getDeclaredField("appContext")
            field.isAccessible = true
            field.set(ShizukuApplication.Companion, application)
            return
        } catch (_: NoSuchFieldException) {
            // Fall through to the outer-class field below.
        }
        val field = ShizukuApplication::class.java.getDeclaredField("appContext")
        field.isAccessible = true
        field.set(null, application)
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitUntil(
        what: () -> String,
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out after ${timeoutMs}ms waiting for ${what()}" }
            pump()
            Thread.sleep(10)
        }
    }

    private fun appStarts(upgraded: Boolean) {
        ShizukuSettings.decideWatchdogDefault(upgraded)
    }

    private fun serverStarted() {
        val latch = CountDownLatch(1)
        ShizukuStateMachine.postRunningLatchForTesting = latch
        ShizukuStateMachine.set(State.RUNNING)
        waitUntil({ "the post-RUNNING work finished" }) { latch.count == 0L }
    }

    private fun serverKilled() {
        ShizukuStateMachine.setDead()
        waitUntil({ "the state machine to settle" }) { ShizukuStateMachine.get() != State.RUNNING }
    }

    private fun setExempt(exempt: Boolean) {
        SettingsHelper.isIgnoringBatteryOptimizationsForTesting = exempt
    }

    private fun setDeviceHardening(enabled: Boolean) {
        ShizukuSettings
            .getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.Keys.KEY_DEVICE_HARDENING_ENABLED, enabled)
            .commit()
    }

    private fun notificationManager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun getActiveNotification(id: Int): Notification? =
        notificationManager().activeNotifications.firstOrNull { it.id == id }?.notification

    private fun gapLines(): Int =
        ActivityLogManager.logs.value.count { it.action == BatteryOptimizationGuard.GAP_LOG_ACTION }

    @Test
    fun `new-install-not-exempt-posts-notification-1454-and-logs-gap`() {
        setExempt(false)
        setDeviceHardening(false)
        appStarts(upgraded = false)
        val before = gapLines()
        serverStarted()
        waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        val notification = getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID)
        assertNotNull("battery-exemption notification posted", notification)

        val contentIntent = notification!!.contentIntent
        assertTrue("content intent immutable", shadowOf(contentIntent).isImmutable)
        val saved = shadowOf(contentIntent).savedIntent
        assertEquals(
            "AOSP exemption action",
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            saved.action,
        )
        assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
    }

    @Test
    fun `new-install-already-exempt-posts-nothing`() {
        setExempt(true)
        setDeviceHardening(false)
        appStarts(upgraded = false)
        val before = gapLines()
        serverStarted()

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        assertEquals("no gap line", before, gapLines())
        assertNull("no battery-exemption notification", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
    }

    @Test
    fun `new-install-hardening-grants-exemption-posts-nothing`() {
        setExempt(false)
        setDeviceHardening(true)
        // The whitelist command is what grants the exemption: simulate its effect so the check
        // (which must run after the pass) observes the grant and posts nothing. If the check ran
        // before the pass, it would still see "not exempt" and post - this test fails then.
        DeviceOptimizer.onAppWhitelistedForTesting = { setExempt(true) }
        appStarts(upgraded = false)
        val before = gapLines()
        serverStarted()

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        assertEquals("no gap line", before, gapLines())
        assertNull("no battery-exemption notification", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
    }

    @Test
    fun `later-start-does-not-post-again`() {
        setExempt(false)
        setDeviceHardening(false)
        appStarts(upgraded = false)
        val before = gapLines()
        serverStarted()
        waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
        assertNotNull("notification posted on the first start", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))

        notificationManager().cancel(BatteryOptimizationGuard.NOTIFICATION_ID)
        serverKilled()
        serverStarted()

        assertEquals("still one gap line after a later start", before + 1, gapLines())
        assertNull("no second notification", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
    }

    @Test
    fun `update-keeping-user-setting-posts-nothing`() {
        ShizukuSettings
            .getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.Keys.KEY_WATCHDOG, true)
            .commit()
        setExempt(false)
        setDeviceHardening(false)
        appStarts(upgraded = true)
        val before = gapLines()
        serverStarted()

        assertTrue("watchdog setting kept", ShizukuSettings.getWatchdog())
        assertEquals("no gap line", before, gapLines())
        assertNull("no battery-exemption notification", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
    }

    @Test
    fun `notifications-blocked-logs-once-and-retries-on-a-later-start`() {
        setExempt(false)
        setDeviceHardening(false)
        appStarts(upgraded = false)
        shadowOf(notificationManager()).setNotificationsEnabled(false)
        val before = gapLines()

        serverStarted()
        waitUntil({ "the gap to be recorded in the activity log" }) { gapLines() == before + 1 }
        assertNull("no notification while disabled", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
        assertTrue("pending flag still set", ShizukuSettings.isWatchdogExemptionPending())

        // A second start while still blocked (a Watchdog restart) does not log the gap again.
        serverKilled()
        serverStarted()
        assertTrue("pending flag still set", ShizukuSettings.isWatchdogExemptionPending())

        // Notifications enabled; the next start posts and clears the flag.
        shadowOf(notificationManager()).setNotificationsEnabled(true)
        serverKilled()
        serverStarted()
        waitUntil({ "the notification to be posted" }) {
            getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID) != null && gapLines() >= before + 2
        }

        assertEquals("the gap is logged once while blocked and once when posted", before + 2, gapLines())
        assertNotNull("notification posted", getActiveNotification(BatteryOptimizationGuard.NOTIFICATION_ID))
        assertFalse("pending flag cleared", ShizukuSettings.isWatchdogExemptionPending())
    }
}
