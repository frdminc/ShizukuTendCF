package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ShizukuStateMachine.State
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowBuild
import java.util.concurrent.CountDownLatch

/**
 * At every start while the Watchdog is on, undo the vendor battery restrictions that can stop it
 * (Samsung's Deep sleeping apps, Xiaomi's autostart, and the universal background-activity and
 * standby-bucket restrictions), and tell the user once what cannot be undone. Each test runs the
 * real post-RUNNING block in [ShizukuStateMachine]; the shell reads and fixes are faked through
 * [DeviceOptimizer.runCommandOutputForTesting], so a finding a scenario cannot make reach the guard
 * is a real ordering bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VendorBatteryRestrictionTest {
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
        ShadowBuild.reset()
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

    private fun logLines(action: String): Int =
        ActivityLogManager.logs.value.count { it.action == action }

    private fun contentIntentOf(id: Int): android.content.Intent {
        val notification = checkNotNull(getActiveNotification(id)) { "no notification $id" }
        return shadowOf(checkNotNull(notification.contentIntent)).savedIntent
    }

    @Test
    fun `background-activity-ignore-fix-holds-logs-undid-and-posts-nothing`() {
        setExempt(true)
        setDeviceHardening(true)
        var appops = "ignore"
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("appops set") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> {
                    appops = "allow"
                    "ok"
                }
                cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: $appops"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        assertEquals(
            "the undo is recorded once",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY),
        )
        assertEquals("nothing reported", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
        assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `standby-bucket-45-fix-holds-logs-undid-and-posts-nothing`() {
        setExempt(true)
        setDeviceHardening(true)
        var bucket = "45"
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("set-standby-bucket") -> {
                    bucket = "5"
                    "ok"
                }
                cmd.contains("get-standby-bucket") -> bucket
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        assertEquals(
            "the undo is recorded once",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_STANDBY_BUCKET),
        )
        assertEquals("nothing reported", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_STANDBY_BUCKET))
        assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `background-activity-fix-does-not-hold-posts-notification-to-app-details`() {
        setExempt(true)
        setDeviceHardening(true)
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("appops set") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "ok"
                cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertEquals(
            "the still-restricted finding is recorded",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
        )
        val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
        assertEquals("app-details action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, saved.action)
        assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
    }

    @Test
    fun `device-hardening-off-logs-and-posts-without-changing`() {
        setExempt(true)
        setDeviceHardening(false)
        val calls = mutableListOf<String>()
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            calls += cmd
            when {
                cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertEquals(
            "the restriction is logged",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
        )
        assertNotNull("notification posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
        assertTrue(
            "no fix command ran",
            calls.none { it.contains("appops set") || it.contains("set-standby-bucket") || it.startsWith("pm enable") },
        )
    }

    @Test
    fun `watchdog-off-reads-changes-logs-nothing`() {
        setExempt(true)
        setDeviceHardening(false)
        val calls = mutableListOf<String>()
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            calls += cmd
            null
        }
        appStarts(upgraded = true)
        serverStarted()

        assertTrue("watchdog off", !ShizukuSettings.getWatchdog())
        assertTrue("nothing read", calls.isEmpty())
        assertEquals("no undo logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY))
        assertEquals("no report logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
        assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `nothing-restricted-posts-nothing`() {
        setExempt(true)
        setDeviceHardening(true)
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertTrue("watchdog on", ShizukuSettings.getWatchdog())
        assertEquals("no undo logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_BACKGROUND_ACTIVITY))
        assertEquals("no report logged", 0, logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY))
        assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `samsung-deep-sleep-fix-holds`() {
        ShadowBuild.setManufacturer("samsung")
        setExempt(true)
        setDeviceHardening(true)
        var enabledState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        val calls = mutableListOf<String>()
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            calls += cmd
            when {
                cmd.startsWith("pm enable") -> {
                    enabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    "ok"
                }
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
        appStarts(upgraded = false)
        serverStarted()

        assertTrue("pm enable ran", calls.any { it.startsWith("pm enable") })
        assertEquals(
            "the undo is recorded once",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_SAMSUNG_DEEP_SLEEP),
        )
        assertNull("no notification", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `samsung-fix-does-not-hold-notification-carries-never-sleeping-screen`() {
        ShadowBuild.setManufacturer("samsung")
        setExempt(true)
        setDeviceHardening(true)
        val enabledState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.startsWith("pm enable") -> "ok" // re-read still shows 4
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
        VendorBatteryRestrictions.resolveActivityForTesting = { _, intent -> intent.`package` == "com.samsung.android.lool" }
        appStarts(upgraded = false)
        serverStarted()

        val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
        assertEquals(
            "Samsung checkable-list action",
            "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
            saved.action,
        )
        assertEquals("Samsung lool package", "com.samsung.android.lool", saved.`package`)
        assertEquals("Never sleeping apps list", 2, saved.getIntExtra("activity_type", -1))
    }

    @Test
    fun `xiaomi-autostart-fix-runs-millet-missing-reported-never-written`() {
        ShadowBuild.setManufacturer("xiaomi")
        setExempt(true)
        setDeviceHardening(true)
        var autostart = "ignore"
        val calls = mutableListOf<String>()
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            calls += cmd
            when {
                cmd.contains("10008") && cmd.contains("appops set") -> {
                    autostart = "allow"
                    "ok"
                }
                cmd.contains("10008") && cmd.contains("appops get") -> "10008: $autostart"
                cmd.contains("MILLET_NO_RESTRICT_APP") -> "com.some.other,com.another"
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        VendorBatteryRestrictions.resolveActivityForTesting = { _, intent -> intent.component?.packageName == "com.miui.powerkeeper" }
        appStarts(upgraded = false)
        serverStarted()

        assertTrue("autostart fix ran", calls.any { it.contains("10008") && it.contains("appops set") })
        assertEquals(
            "autostart undone once",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_UNDID_XIAOMI_AUTOSTART),
        )
        assertEquals(
            "millet reported once",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT),
        )
        assertTrue(
            "MILLET is only read, never written",
            calls.filter { it.contains("MILLET_NO_RESTRICT_APP") }.all { it.contains("settings get") },
        )
        val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
        assertEquals(
            "Xiaomi battery saver screen",
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            saved.component,
        )
        assertEquals("package_name extra", app.packageName, saved.getStringExtra("package_name"))
    }

    @Test
    fun `vendor-screen-not-resolve-falls-back-to-app-details`() {
        ShadowBuild.setManufacturer("samsung")
        setExempt(true)
        setDeviceHardening(false)
        val enabledState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = { enabledState }
        VendorBatteryRestrictions.resolveActivityForTesting = { _, _ -> false }
        appStarts(upgraded = false)
        serverStarted()

        val saved = contentIntentOf(VendorBatteryRestrictions.NOTIFICATION_ID)
        assertEquals("app-details action", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, saved.action)
        assertEquals("package data", Uri.parse("package:" + app.packageName), saved.data)
    }

    @Test
    fun `same-restriction-unchanged-posts-no-second-notification`() {
        setExempt(true)
        setDeviceHardening(false)
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("appops get") && cmd.contains("RUN_ANY_IN_BACKGROUND") -> "RUN_ANY_IN_BACKGROUND: ignore"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()
        assertNotNull("first start posts", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))

        notificationManager().cancel(VendorBatteryRestrictions.NOTIFICATION_ID)
        serverKilled()
        serverStarted()

        assertNull(
            "no second notification for an unchanged restriction",
            getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID),
        )
        assertEquals(
            "an unchanged restriction is logged once, not at every start",
            1,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_BACKGROUND_ACTIVITY),
        )
    }

    @Test
    fun `xiaomi-without-millet-setting-reports-nothing`() {
        ShadowBuild.setManufacturer("xiaomi")
        setExempt(true)
        setDeviceHardening(true)
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("10008") && cmd.contains("appops get") -> "10008: allow"
                // `settings get` prints "null" for a setting that does not exist (older MIUI).
                cmd.contains("MILLET_NO_RESTRICT_APP") -> "null\n"
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertEquals(
            "a missing setting is not a missing exemption",
            0,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_NO_RESTRICT),
        )
        assertNull("nothing posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `xiaomi-autostart-no-operations-not-reported`() {
        ShadowBuild.setManufacturer("xiaomi")
        setExempt(true)
        setDeviceHardening(false)
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            when {
                cmd.contains("10008") && cmd.contains("appops get") -> "No operations.\n"
                cmd.contains("MILLET_NO_RESTRICT_APP") -> "null"
                cmd.contains("RUN_ANY_IN_BACKGROUND") && cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: allow"
                cmd.contains("get-standby-bucket") -> "5"
                else -> null
            }
        }
        appStarts(upgraded = false)
        serverStarted()

        assertEquals(
            "only an explicit ignore or deny is a restriction",
            0,
            logLines(VendorBatteryRestrictions.LOG_ACTION_REPORTED_XIAOMI_AUTOSTART),
        )
        assertNull("nothing posted", getActiveNotification(VendorBatteryRestrictions.NOTIFICATION_ID))
    }

    @Test
    fun `every-command-names-the-apps-own-user`() {
        ShadowBuild.setManufacturer("samsung")
        setExempt(true)
        setDeviceHardening(true)
        val calls = mutableListOf<String>()
        DeviceOptimizer.runCommandOutputForTesting = { cmd ->
            calls += cmd
            when {
                cmd.contains("appops get") -> "RUN_ANY_IN_BACKGROUND: ignore"
                cmd.contains("get-standby-bucket") -> "45"
                else -> "ok"
            }
        }
        VendorBatteryRestrictions.applicationEnabledSettingForTesting = {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        }
        appStarts(upgraded = false)
        serverStarted()

        val userCommands = calls.filter { it.startsWith("cmd appops") || it.startsWith("am ") || it.startsWith("pm ") }
        assertTrue("the pass ran appops, am and pm commands", userCommands.size >= 5)
        assertTrue("each names --user 0: $userCommands", userCommands.all { it.contains("--user 0 ") })
    }
}
