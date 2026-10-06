package af.shizuku.manager.worker

import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.utils.HeadlessLogger
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * What a restore stopped for an untrusted network waits for ([WirelessDebugging.watch]):
 * adb_wifi_enabled changing (a content-URI trigger, so it outlives this process), and on One UI a
 * quiet retry while locked. It also runs the bounded rechecks of the TCP port after a no-Wi-Fi stop
 * ([WirelessDebugging.armPortRecheck]). Each run decides from this boot's records whether anything is still
 * needed, and queues the next one itself.
 */
class WirelessDebuggingWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        HeadlessLogger.init(applicationContext)
        when (inputData.getString(KEY_KIND)) {
            KIND_QUIET -> WirelessDebugging.onQuietRetry(applicationContext)
            KIND_PORT -> WirelessDebugging.onPortRecheck(applicationContext, inputData.getInt(KEY_PORT, -1), inputData.getInt(KEY_CHECK, 0))
            else -> WirelessDebugging.onWatchFired(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KIND_WATCH = "watch"
        const val KIND_QUIET = "quiet"

        // A recheck of the TCP port after a no-Wi-Fi stop: the port, and which recheck this is.
        const val KIND_PORT = "port"
        const val KEY_PORT = "port"
        const val KEY_CHECK = "check"
    }
}
