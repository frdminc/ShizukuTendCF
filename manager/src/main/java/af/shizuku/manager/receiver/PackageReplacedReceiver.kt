package af.shizuku.manager.receiver

import af.shizuku.manager.utils.HeadlessLogger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Hands an in-place upgrade to [ServerUpgradeWorker]: the server is a separate long-lived
 * process, so `adb install -r` leaves it serving the old code until something restarts it, and
 * [BootCompleteReceiver]'s start does nothing while it answers.
 *
 * Separate from [BootCompleteReceiver] because the start-on-boot toggle disables that component,
 * and with it this broadcast. Nothing enables or disables this one. Not exported, and
 * MY_PACKAGE_REPLACED is a protected broadcast, so no other app can trigger it.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        HeadlessLogger.init(appContext)
        try {
            ServerUpgradeWorker.enqueue(appContext)
        } catch (e: Exception) {
            HeadlessLogger.e("Upgrade", "Cannot schedule the server check after the upgrade", e)
        }
    }
}
