package af.shizuku.manager.receiver

import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.AppConstants
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import io.sentry.Breadcrumb
import io.sentry.Sentry
import rikka.shizuku.Shizuku
import timber.log.Timber

object ShizukuReceiverStarter {
    const val NOTIFICATION_ID = 1447

    // The start worker's foreground notification. WorkManager posts it and removes it again on its
    // own schedule, asynchronously, so it must never be NOTIFICATION_ID: nothing could order those
    // writes against the ownership scheme's, and a late one would replace or remove its content.
    const val FOREGROUND_NOTIFICATION_ID = 1451
    private const val CHANNEL_ID = "AdbStartWorker"

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        AWAITING_DISCOVERY,
        AWAITING_AUTH,
        AUTH_TIMED_OUT,
        RUNNING,
        STOPPED,
    }

    fun start(
        context: Context,
        forceStart: Boolean = false,
    ) {
        if (!forceStart && (
                UserHandleCompat.myUserId() > 0 ||
                    ShizukuStateMachine.isRunning() ||
                    ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING
            )
        ) {
            return
        }

        // A connection is already holding adbd's "Allow USB debugging?" dialog open; any new
        // connection would queue another dialog (and enqueue() would cancel the waiting worker).
        if (ShizukuSettings.getLastLaunchMode() != LaunchMethod.ROOT && AdbAuthWait.isWaiting()) {
            Timber.tag(AppConstants.TAG).i("Start skipped: waiting for the adbd authorisation dialog to be answered")
            return
        }

        // One dialog per boot or explicit start: once a dialog has gone unanswered, background
        // triggers that any app can fire (QUICKBOOT_POWERON, the Tasker/Locale plugin) and the
        // watchdog must not be able to raise a fresh dialog every 150 s. Explicit paths pass
        // forceStart or clear the marker first (boot, headless, token-authenticated broadcast,
        // the notification's "Attempt now").
        if (!forceStart && ShizukuSettings.getLastLaunchMode() != LaunchMethod.ROOT && AdbAuthWait.isUnanswered()) {
            Timber.tag(AppConstants.TAG).i("Start skipped: adbd authorisation dialog went unanswered; waiting for an explicit start")
            return
        }

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                AdbStartWorker.enqueue(context)
            } else {
                showPermissionErrorNotification(context)
            }
        } else {
            Timber.tag(AppConstants.TAG).w("Background start not supported")
        }
    }

    /** Symmetric with [start]: stops a running service. Shared by the manual STOP broadcast
     *  receiver and the Tasker/Locale plugin's fire receiver. */
    fun stop() {
        if (!ShizukuStateMachine.isRunning()) return
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        runCatching { Shizuku.exit() }
            .onFailure { Timber.tag(AppConstants.TAG).w(it, "Shizuku.exit failed") }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_LOW,
                )
            nm.createNotificationChannel(channel)
        }
    }

    private fun cancelPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotifCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * For [FOREGROUND_NOTIFICATION_ID] only. The attempt's own progress, prompt and notice stay in
     * [NOTIFICATION_ID]; this one just keeps the worker alive while it waits for an unlock, so it
     * has no restore or "Attempt now" of its own.
     */
    fun buildForegroundNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_notification_close_24, context.getString(android.R.string.cancel), cancelPendingIntent(context))
            .build()
    }

    private fun buildNotification(
        context: Context,
        msg: String? = null,
    ): Notification {
        ensureChannel(context)
        val cancelPendingIntent = cancelPendingIntent(context)

        val attemptNowIntent = Intent(context, NotifAttemptReceiver::class.java)
        val attemptNowPendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                attemptNowIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val restoreIntent = Intent(context, NotifRestoreReceiver::class.java)
        val restorePendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                restoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val wifiIntent = SettingsPage.InternetPanel.buildIntent(context)
        val wifiPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                wifiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        // BigTextStyle so a long message (the authorisation wait carries a key fingerprint the
        // user is asked to compare) is readable in full when expanded.
        if (msg != null) nb.setContentText(msg).setStyle(NotificationCompat.BigTextStyle().bigText(msg))

        return nb
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_notification_server_restart, context.getString(R.string.wadb_notification_attempt_now), attemptNowPendingIntent)
            .addAction(R.drawable.ic_notification_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    // Every post or removal of NOTIFICATION_ID goes through AdbAuthWait's ownership scheme via the
    // functions below; nothing else touches it directly.

    /** WorkManager confirmed background start [attempt]'s enqueue: [state] is its pending status. */
    fun postPending(
        context: Context,
        attempt: Long,
        state: WorkerState,
    ) {
        val app = context.applicationContext
        AdbAuthWait.attemptEnqueued(attempt, { updateNotification(app, state) }, { cancel(app) })
    }

    /** [attempt]'s run ended but it will run again; [state] says what it is waiting for. */
    fun postRetrying(
        context: Context,
        attempt: Long,
        state: WorkerState,
    ) {
        val app = context.applicationContext
        AdbAuthWait.retryStartNotification(attempt, { updateNotification(app, state) }, { cancel(app) })
    }

    /** Progress of background start [attempt], from its running worker only. */
    fun postProgress(
        context: Context,
        attempt: Long,
        state: WorkerState,
    ) {
        val app = context.applicationContext
        AdbAuthWait.postStartNotification(attempt, { updateNotification(app, state) }, { cancel(app) })
    }

    /** adbd's dialog is up for background start [attempt]. */
    fun postAuthPrompt(
        context: Context,
        attempt: Long,
        fingerprint: String?,
    ) {
        val app = context.applicationContext
        AdbAuthWait.postAuthPrompt(attempt, { updateNotification(app, WorkerState.AWAITING_AUTH, fingerprint) }, { cancel(app) })
    }

    /** [attempt] is over; [state] says why and stays until the user acts on it. */
    fun finishWithNotice(
        context: Context,
        attempt: Long,
        state: WorkerState,
    ) {
        val app = context.applicationContext
        AdbAuthWait.finishStartNotification(attempt, { updateNotification(app, state) }, { cancel(app) })
    }

    /** [attempt] started the server. */
    fun clearProgress(
        context: Context,
        attempt: Long,
    ) {
        val app = context.applicationContext
        AdbAuthWait.clearStartNotification(attempt) { cancel(app) }
    }

    /** [attempt]'s worker stopped running for good: stood down, failed or cancelled. */
    fun withdrawProgress(
        context: Context,
        attempt: Long,
    ) {
        val app = context.applicationContext
        AdbAuthWait.withdrawStartNotification(attempt) { cancel(app) }
    }

    /** [attempt] is still under way, shown by its worker's foreground notification for now. */
    fun hideProgress(attempt: Long) = AdbAuthWait.hideStartProgress(attempt)

    /** The user cancelled background starts: ends them, then runs [cancelWork], as one step. */
    fun cancelNotification(
        context: Context,
        cancelWork: () -> Unit,
    ) {
        val app = context.applicationContext
        AdbAuthWait.cancelAttempts({ cancel(app) }, cancelWork)
    }

    /** The user swiped the notification away. */
    fun restoreNotification() = AdbAuthWait.restoreStartNotification()

    private fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun updateNotification(
        context: Context,
        state: WorkerState,
        detail: String? = null,
    ) {
        if (state == WorkerState.STOPPED) return
        val msgId =
            when (state) {
                WorkerState.AWAITING_WIFI -> R.string.wadb_notification_wifi_required
                WorkerState.AWAITING_RETRY -> R.string.wadb_notification_retry
                WorkerState.AWAITING_DISCOVERY -> R.string.wadb_notification_discovery_timeout
                WorkerState.AWAITING_AUTH -> R.string.wadb_notification_awaiting_auth
                WorkerState.AUTH_TIMED_OUT -> R.string.wadb_notification_auth_timed_out
                else -> null
            }
        val base = if (msgId != null) context.getString(msgId) else null
        val msg = if (base != null && detail != null) "$base. $detail" else base
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
    }

    private fun rootStart(context: Context) {
        Sentry.addBreadcrumb(Breadcrumb("Background Root start initiated").apply { category = "shizuku.starter" })
        if (!Shell.getShell().isRoot) {
            Sentry.addBreadcrumb(
                Breadcrumb("Background Root start failed - no root").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.WARNING
                },
            )
            // NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
            val result = Shell.cmd(Starter.internalCommand).exec()
            if (!result.isSuccess) {
                // libsu doesn't throw on a non-zero exit. Without this check the state machine is
                // left in STARTING, which update() preserves indefinitely while the binder is dead —
                // so the watchdog (which only reacts to CRASHED) never retries and the UI shows a
                // perpetual "Starting…".
                Sentry.addBreadcrumb(
                    Breadcrumb("Background Root start failed: starter exited ${result.code}").apply {
                        category = "shizuku.starter"
                        level = io.sentry.SentryLevel.ERROR
                    },
                )
                Timber.tag(AppConstants.TAG).e("Root starter exited with code ${result.code}")
                recoverFromFailedStart()
            }
        } catch (e: Exception) {
            Sentry.addBreadcrumb(
                Breadcrumb("Background Root start failed: ${e.message}").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.ERROR
                },
            )
            Timber.tag(AppConstants.TAG).e(e, "Failed to start Shizuku with root")
            recoverFromFailedStart()
        }
    }

    /** Clears a stuck STARTING state after a failed start attempt, then re-detects: if a previous
     *  server instance is actually still alive, update() flips the state back to RUNNING. */
    private fun recoverFromFailedStart() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        ShizukuStateMachine.update()
    }

    private fun showPermissionErrorNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context)

        val webpageIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/wiki#shizuku-isnt-starting-on-boot-for-me"))
        val pendingWebpageIntent =
            PendingIntent.getActivity(
                context,
                0,
                webpageIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val msg = context.getString(R.string.wadb_permission_error_notification_content)

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(context.getString(R.string.wadb_permission_error_notification_title))
                .setContentText(msg)
                .setSilent(true)
                .setContentIntent(pendingWebpageIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .build()

        AdbAuthWait.postStartNotice({ nm.notify(NOTIFICATION_ID, notification) }, { nm.cancel(NOTIFICATION_ID) })
    }
}
