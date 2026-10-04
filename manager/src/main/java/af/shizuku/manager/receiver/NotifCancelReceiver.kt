package af.shizuku.manager.receiver

import af.shizuku.manager.worker.AdbStartWorker
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class NotifCancelReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Ends every attempt before cancelling the worker, so the worker cannot post its "will
        // retry" state over the removal, and as one step against scheduling, so a start enqueued
        // concurrently is either cancelled with its notification or not at all.
        try {
            AdbStartWorker.cancel(context)
        } catch (e: Throwable) {
            // WorkManager may throw NoSuchMethodError / NoSuchMethodException / LinkageError or IllegalStateException when
            // called from a BroadcastReceiver context before the app process is fully
            // initialized (e.g. direct boot, process re-creation for receiver only).
            Timber.tag("NotifCancelReceiver").w("WorkManager unavailable: ${e.message}")
        }
    }
}
