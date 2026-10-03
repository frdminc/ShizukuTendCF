package af.shizuku.manager.receiver

import af.shizuku.common.util.EnvironmentUtils
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import rikka.shizuku.Shizuku

/**
 * ADB-shell / root only control surface for fleet automation: start, stop and query Shizuku
 * without opening the UI. Gated in the manifest by INTERACT_ACROSS_USERS_FULL, which ordinary
 * apps cannot hold (shell, root, system and platform-signed apps can; so, in effect, can any
 * client Shizuku has already authorised, since it can run `am` as shell). Results are returned
 * through ordered-broadcast result codes/data/extras. Since API 26 the broadcast must name the
 * package or it is dropped: `adb shell am broadcast -p <pkg> -a <pkg>.HEADLESS_STATUS`.
 */
class HeadlessStartStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        HeadlessLogger.init(context)
        when (intent.action) {
            ACTION_HEADLESS_START -> {
                HeadlessLogger.i("Start", "Headless start requested (version ${BuildConfig.VERSION_NAME})")
                // Same guards the shared starter applies; keep them rather than forcing past them.
                if (UserHandleCompat.myUserId() > 0) {
                    HeadlessLogger.w("Start", "Rejected: not the primary user")
                    setResult(3, "UNSUPPORTED_USER", null)
                    return
                }
                when (ShizukuStateMachine.get()) {
                    ShizukuStateMachine.State.RUNNING -> {
                        HeadlessLogger.i("Start", "Already running")
                        setResult(1, "ALREADY_RUNNING", null)
                        return
                    }
                    ShizukuStateMachine.State.STARTING -> {
                        HeadlessLogger.i("Start", "Start already in progress")
                        setResult(0, "STARTING", null)
                        return
                    }
                    else -> Unit
                }
                val launchMode = ShizukuSettings.getLastLaunchMode()
                if (launchMode == LaunchMethod.ROOT) {
                    HeadlessLogger.i("Start", "Launch mode=ROOT, delegating to ShizukuReceiverStarter")
                } else {
                    // ADB, or UNKNOWN on a fresh install. Enabling wireless debugging exposes adbd
                    // on the network, so it is on by default for the fleet case but can be opted
                    // out with --ez enable_wireless_adb false. UNKNOWN is persisted as ADB because
                    // the shared starter treats UNKNOWN as "background start not supported".
                    HeadlessLogger.i("Start", "Launch mode=$launchMode, attempting ADB start")
                    if (intent.getBooleanExtra(EXTRA_ENABLE_WIRELESS_ADB, true)) {
                        tryEnsureWirelessAdb(context)
                    }
                    if (launchMode != LaunchMethod.ADB) {
                        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
                    }
                    HeadlessLogger.i("Start", "Starting via ADB (TCP port ${ShizukuSettings.getTcpPort()})")
                }
                ShizukuReceiverStarter.start(context)
                setResult(0, "STARTING", null)
            }
            ACTION_HEADLESS_STOP -> {
                HeadlessLogger.i("Stop", "Headless stop requested")
                if (!ShizukuStateMachine.isRunning()) {
                    HeadlessLogger.w("Stop", "Server not running, nothing to stop")
                    setResult(2, "NOT_RUNNING", null)
                    return
                }
                ShizukuReceiverStarter.stop()
                HeadlessLogger.i("Stop", "Server stop command sent")
                setResult(0, "STOPPING", null)
            }
            ACTION_HEADLESS_STATUS -> {
                val state = ShizukuStateMachine.get()
                val stateLabel = state.name
                val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

                val adbTcpPort = EnvironmentUtils.getAdbTcpPort()
                val adbWifi = runCatching {
                    Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
                }.getOrDefault(0)
                val adbUsb = runCatching {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                }.getOrDefault(0)

                val adbParts = mutableListOf<String>()
                if (adbUsb != 0) adbParts.add("USB:on")
                if (adbWifi != 0) adbParts.add("WiFi:${if (adbTcpPort > 0) adbTcpPort else "?"}")
                if (adbParts.isEmpty()) adbParts.add("off")
                val adbSummary = adbParts.joinToString(" ")

                val summary = "$stateLabel (binder=$binderAlive, ADB: $adbSummary, v${BuildConfig.VERSION_NAME})"
                val logPath = HeadlessLogger.getLogPath() ?: "unavailable"

                val extras = Bundle().apply {
                    putString("state", stateLabel)
                    putBoolean("binder_alive", binderAlive)
                    putInt("adb_tcp_port", adbTcpPort)
                    putInt("configured_tcp_port", ShizukuSettings.getTcpPort())
                    putInt("adb_wifi_enabled", adbWifi)
                    putInt("adb_enabled", adbUsb)
                    putString("version_name", BuildConfig.VERSION_NAME)
                    putInt("version_code", BuildConfig.VERSION_CODE)
                    putString("log_path", logPath)
                }

                HeadlessLogger.i("Status", summary)
                setResult(state.ordinal, summary, extras)
            }
        }
    }

    private fun tryEnsureWirelessAdb(context: Context) {
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            HeadlessLogger.w("Start", "WRITE_SECURE_SETTINGS not granted, cannot enable wireless ADB")
            return
        }
        try {
            val cr = context.contentResolver
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                HeadlessLogger.i("Start", "Enabled USB ADB")
            }
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 0) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                HeadlessLogger.i("Start", "Enabled wireless ADB")
            }
        } catch (e: SecurityException) {
            HeadlessLogger.w("Start", "WRITE_SECURE_SETTINGS denied")
        } catch (e: Exception) {
            HeadlessLogger.e("Start", "Failed to enable wireless ADB", e)
        }
    }

    companion object {
        val ACTION_HEADLESS_START = "${BuildConfig.APPLICATION_ID}.HEADLESS_START"
        val ACTION_HEADLESS_STOP = "${BuildConfig.APPLICATION_ID}.HEADLESS_STOP"
        val ACTION_HEADLESS_STATUS = "${BuildConfig.APPLICATION_ID}.HEADLESS_STATUS"
        const val EXTRA_ENABLE_WIRELESS_ADB = "enable_wireless_adb"
    }
}
