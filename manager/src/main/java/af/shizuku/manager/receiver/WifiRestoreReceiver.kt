package af.shizuku.manager.receiver

import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.utils.HeadlessLogger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The network callback [WirelessDebugging.armResume] registers with a PendingIntent: a restore of
 * the ADB TCP port that stopped for want of Wi-Fi starts again when Wi-Fi connects. Not exported;
 * only the system's network callback sends it.
 */
class WifiRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        HeadlessLogger.init(context)
        // An ordinary background start: the unanswered-dialog marker and the other one-prompt
        // guards apply as to the watchdog's.
        if (WirelessDebugging.onWifiEvent(context)) ShizukuReceiverStarter.start(context)
    }
}
