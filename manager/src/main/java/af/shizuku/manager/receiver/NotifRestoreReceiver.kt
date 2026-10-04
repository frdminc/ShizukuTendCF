package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotifRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Puts back exactly what was swiped while it is still current; an attempt that is over
        // (or a process that has restarted since) leaves nothing to restore.
        ShizukuReceiverStarter.restoreNotification()
    }
}
