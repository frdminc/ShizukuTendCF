package af.shizuku.manager.receiver

import af.shizuku.common.util.EnvironmentUtils
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import rikka.shizuku.Shizuku
import af.shizuku.manager.utils.EnvironmentUtils as ManagerEnvironmentUtils

/**
 * ADB-shell / root only control surface for fleet automation: start, stop and query Shizuku
 * without opening the UI. Gated in the manifest by INTERACT_ACROSS_USERS_FULL, which ordinary
 * apps cannot hold (shell, root, system and platform-signed apps can; so, in effect, can any
 * client Shizuku has already authorised, since it can run `am` as shell). Results are returned
 * through ordered-broadcast result codes/data/extras. Since API 26 the broadcast must name the
 * package or it is dropped: `adb shell am broadcast -p <pkg> -a <pkg>.HEADLESS_STATUS`.
 *
 * HEADLESS_LOG returns the tail of [HeadlessLogger]'s file (the start path's decisions) as the
 * result data, since a release build logs nothing else and its private storage is closed to
 * `adb shell`: `adb shell am broadcast -p <pkg> -a <pkg>.HEADLESS_LOG --ei lines 100`.
 */
class HeadlessStartStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
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
                when (ShizukuStateMachine.update()) {
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
                if (AdbAuthWait.isWaiting()) {
                    HeadlessLogger.i("Start", "Start already in progress, waiting for the adbd authorisation dialog to be accepted")
                    setResult(0, "STARTING", null)
                    return
                }
                val launchMode = ShizukuSettings.getLastLaunchMode()
                // Decided before anything is changed on the device (wireless debugging below).
                // One dialog per boot or explicit start. This broadcast is sent by people and by
                // unattended repair loops alike, and a loop that finds the server down every few
                // minutes would otherwise raise a fresh dialog on each pass. So a plain request
                // respects an unanswered dialog, and only one that says so (--ez force true, for
                // an operator who is at the device) clears it and may raise a new one.
                if (launchMode != LaunchMethod.ROOT && AdbAuthWait.isUnanswered()) {
                    if (!intent.getBooleanExtra(EXTRA_FORCE, false)) {
                        HeadlessLogger.w("Start", "Withheld: the adbd authorisation dialog went unanswered; send --ez $EXTRA_FORCE true or tap Attempt now")
                        ShizukuReceiverStarter.refreshNotification(context)
                        setResult(RESULT_AUTH_UNANSWERED, "AUTH_UNANSWERED", null)
                        return
                    }
                    HeadlessLogger.i("Start", "Forced: clearing the unanswered authorisation marker")
                    AdbAuthWait.clearUnanswered()
                }
                if (launchMode == LaunchMethod.ROOT) {
                    HeadlessLogger.i("Start", "Launch mode=ROOT, delegating to ShizukuReceiverStarter")
                } else {
                    // ADB, or UNKNOWN on a fresh install. UNKNOWN is persisted as ADB because the
                    // shared starter treats UNKNOWN as "background start not supported".
                    HeadlessLogger.i("Start", "Launch mode=$launchMode, attempting ADB start")
                    val force = intent.getBooleanExtra(EXTRA_FORCE, false)
                    if (force && WirelessDebugging.blocked(context) != null) {
                        HeadlessLogger.i("Start", "Forced: clearing this boot's wireless debugging stop (network prompt or no Wi-Fi)")
                        WirelessDebugging.clearPrompted(context)
                        WirelessDebugging.clearNoWifi(context)
                    }
                    // Wireless debugging is the start worker's: it turns it on only when the start
                    // needs it, after Wi-Fi has settled, at most one network prompt per boot, and
                    // (in TCP mode) back off afterwards. Turned on here it raised a refused
                    // network's prompt on every pass of a repair loop. enable_wireless_adb is
                    // still accepted and changes nothing.
                    HeadlessLogger.i("Start", "Leaving wireless debugging to the start worker")
                    if (launchMode != LaunchMethod.ADB) {
                        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
                    }
                    // No port probe here: this receiver runs on the main thread, where a release
                    // build's socket throws NetworkOnMainThreadException (caught, so the port always
                    // looked closed and every start after this boot's network prompt was refused).
                    // The worker probes the TCP port first and only then stands down for an
                    // untrusted network, logging why.
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
                val adbWifi =
                    runCatching {
                        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
                    }.getOrDefault(0)
                // Android 17 QPR1 (API 37) hides adb_enabled from apps (it always reads 0), so
                // read it through the helper that knows that, not raw, or USB shows as off.
                val adbUsb =
                    runCatching {
                        if (ManagerEnvironmentUtils.isAdbEnabled()) 1 else 0
                    }.getOrDefault(0)

                val adbParts = mutableListOf<String>()
                if (adbUsb != 0) adbParts.add("USB:on")
                if (adbWifi != 0) adbParts.add("WiFi:${if (adbTcpPort > 0) adbTcpPort else "?"}")
                if (adbParts.isEmpty()) adbParts.add("off")
                val adbSummary = adbParts.joinToString(" ")

                val authUnanswered = AdbAuthWait.isUnanswered()
                val summary =
                    "$stateLabel (binder=$binderAlive, ADB: $adbSummary, v${BuildConfig.VERSION_NAME})" +
                        if (authUnanswered) " AUTH_UNANSWERED" else ""
                val logPath = HeadlessLogger.getLogPath() ?: "unavailable"

                val extras =
                    Bundle().apply {
                        putString("state", stateLabel)
                        putBoolean("binder_alive", binderAlive)
                        putBoolean("auth_unanswered", authUnanswered)
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
            ACTION_HEADLESS_LOG -> {
                // Not logged itself: reading the log should not add to it.
                val tail = HeadlessLogger.readTail(intent.getIntExtra(EXTRA_LINES, HeadlessLogger.DEFAULT_TAIL_LINES))
                val extras =
                    Bundle().apply {
                        putString("log_path", HeadlessLogger.getLogPath() ?: "unavailable")
                        putInt(EXTRA_LINES, tail?.lines ?: 0)
                        putBoolean("truncated", tail?.truncated ?: false)
                    }
                if (tail == null) {
                    setResult(RESULT_NO_LOG, "NO_LOG", extras)
                } else {
                    setResult(0, tail.text, extras)
                }
            }
        }
    }

    companion object {
        val ACTION_HEADLESS_START = "${BuildConfig.APPLICATION_ID}.HEADLESS_START"
        val ACTION_HEADLESS_STOP = "${BuildConfig.APPLICATION_ID}.HEADLESS_STOP"
        val ACTION_HEADLESS_STATUS = "${BuildConfig.APPLICATION_ID}.HEADLESS_STATUS"
        val ACTION_HEADLESS_LOG = "${BuildConfig.APPLICATION_ID}.HEADLESS_LOG"

        /** Accepted for old callers; wireless debugging is the start worker's (see onReceive). */
        const val EXTRA_ENABLE_WIRELESS_ADB = "enable_wireless_adb"

        /** Clears an unanswered authorisation dialog's marker, so this start may raise a new one. */
        const val EXTRA_FORCE = "force"

        /** Result code of a start withheld because an authorisation dialog went unanswered. */
        const val RESULT_AUTH_UNANSWERED = 4

        /** HEADLESS_LOG: how many of the newest log lines to return (capped; see [HeadlessLogger.tail]). */
        const val EXTRA_LINES = "lines"

        /** HEADLESS_LOG result code when there is no log to read. */
        const val RESULT_NO_LOG = 2
    }
}
