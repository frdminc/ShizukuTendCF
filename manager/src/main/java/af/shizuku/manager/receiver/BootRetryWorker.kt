package af.shizuku.manager.receiver

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.common.util.UserHandleCompat
import android.content.Context
import timber.log.Timber
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class BootRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BootRetry"
        private const val VERIFY_DELAY_MS = 3000L
        const val WORK_NAME = "boot_retry"

        /**
         * Schedules indefinite boot-start retry via WorkManager with exponential backoff.
         * Constraints: NOT_ROAMING network constraint.
         * Initial delay: 10 seconds.
         * Backoff: 10 seconds exponential backoff (retries indefinitely until Shizuku is running
         * or start-on-boot is disabled).
         */
        @JvmStatic
        fun schedule(context: Context) {
            // The starter refuses to run outside the primary user, so a worker there would
            // only ever spin.
            if (UserHandleCompat.myUserId() > 0) return
            // Root mode needs no network at all; ADB mode cannot succeed without one, so wait
            // for connectivity rather than burning attempts offline.
            val network =
                if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT) {
                    NetworkType.NOT_REQUIRED
                } else {
                    NetworkType.CONNECTED
                }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(network)
                .build()

            val retry = OneTimeWorkRequestBuilder<BootRetryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS,
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, retry)
        }

        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    // No attempt cap: a device blocked on something only a human can do at boot (unlock for FBE,
    // authorize a new network, pair over ADB) may not get that human's attention for hours or
    // days. Giving up after a handful of quick retries (the previous 5-attempt cap exhausted
    // within ~5 minutes of boot, via WorkManager's own exponential backoff) meant Shizuku would
    // never try again until the next full reboot, no matter how long the wait — confirmed live
    // against issue #43's CLOSED_NO_SHELL soak on hd8, where Shizuku itself doesn't even start
    // until the device is physically unlocked. WorkManager's own backoff still applies here
    // (EXPONENTIAL from BootCompleteReceiver's 10s base, capped at its ~5h internal maximum), so
    // this keeps trying indefinitely at a reasonable cadence rather than hammering the device.
    override suspend fun doWork(): Result {
        // Re-check on every attempt, not just at schedule time: a human (or FleetProfileApplier)
        // may turn start-on-boot off *while* this indefinite retry loop is already in flight —
        // without this check it would keep calling ShizukuReceiverStarter.start() forever despite
        // the human's explicit "stop trying" signal, which is exactly the kind of not-respecting-
        // human-intent bug removing the attempt cap must not introduce.
        if (!ShizukuSettings.getStartOnBoot(applicationContext)) {
            Timber.tag(TAG).i("start-on-boot disabled, stopping retry")
            return Result.success()
        }

        // One authorisation dialog per boot: the first attempt of a boot's loop may raise it, but
        // once a start has ended with the dialog unanswered only a human can make progress, and
        // every further attempt would raise another dialog on an unattended device.
        if (runAttemptCount == 0) {
            af.shizuku.manager.adb.AdbAuthWait.clearUnanswered()
        } else if (af.shizuku.manager.adb.AdbAuthWait.isUnanswered()) {
            Timber.tag(TAG).i("adbd authorisation was not accepted, stopping retry until the next explicit start")
            return Result.success()
        }

        ShizukuStateMachine.update()
        if (ShizukuStateMachine.isRunning()) {
            Timber.tag(TAG).i("Shizuku already running (attempt $runAttemptCount)")
            return Result.success()
        }

        Timber.tag(TAG).i("Retrying Shizuku start (attempt $runAttemptCount)")
        ShizukuReceiverStarter.start(applicationContext)

        delay(VERIFY_DELAY_MS)
        ShizukuStateMachine.update()
        if (ShizukuStateMachine.isRunning()) {
            Timber.tag(TAG).i("Start succeeded (attempt $runAttemptCount)")
            return Result.success()
        }

        Timber.tag(TAG).w("Start not yet running, will retry (attempt $runAttemptCount)")
        return Result.retry()
    }
}
