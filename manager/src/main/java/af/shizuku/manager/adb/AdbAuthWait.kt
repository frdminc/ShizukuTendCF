package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide marker for "an AdbClient has sent its public key and is holding its one connection
 * open until the user answers adbd's "Allow USB debugging?" dialog".
 *
 * adbd raises a new dialog for every connection that offers an unknown key, so while this is set
 * nothing may open another connection: [af.shizuku.manager.receiver.ShizukuReceiverStarter.start],
 * [af.shizuku.manager.worker.AdbStartWorker.enqueue] (which would otherwise REPLACE, i.e. cancel,
 * the waiting worker) and the headless start receiver all check [isWaiting]. Those checks are
 * advisory early-outs; the binding claim is [tryBegin], a single compare-and-set taken immediately
 * before the key is offered, so two starts racing past the advisory checks still cannot raise two
 * dialogs.
 */
object AdbAuthWait {
    /** How long a single connection waits for the dialog to be answered. */
    const val TIMEOUT_MS = 150_000

    private val waiting = AtomicInteger(0)

    fun isWaiting(): Boolean = waiting.get() > 0

    /**
     * Atomically claims the one authorisation-wait slot. Check and claim are one compare-and-set,
     * so of two connections racing here exactly one wins; the loser must abandon its start
     * without offering a key (see [AdbAuthPendingException]).
     */
    internal fun tryBegin(): Boolean =
        waiting.compareAndSet(0, 1).also { if (it) starts.begin() }

    internal fun end() {
        notifications.waitEnded()
        if (waiting.compareAndSet(1, 0)) starts.end()
    }

    /** Start work in flight in this process; what the quick-settings tile supervises. */
    val starts = StartsInFlight()

    const val NO_ATTEMPT = StartNotificationLedger.NO_ATTEMPT

    private const val PREF_LAST_ATTEMPT = "adb_start_last_attempt"

    // The notification background starts share, as a function of the ledger's slots; see the
    // ledger. Nothing posts, replaces or cancels ShizukuReceiverStarter.NOTIFICATION_ID except
    // through the functions below. Attempt numbers are committed synchronously: the worker carrying
    // one is persisted by WorkManager right after, and must never outlive the record of its number.
    // Neither lambda swallows a failure; the ledger refuses to number a start it cannot record.
    private val notifications =
        StartNotificationLedger(
            lastIssued = { ShizukuSettings.getPreferences().getLong(PREF_LAST_ATTEMPT, NO_ATTEMPT) },
            recordIssued = { attempt -> ShizukuSettings.getPreferences().edit().putLong(PREF_LAST_ATTEMPT, attempt).commit() },
        )

    /**
     * Numbers a background start and runs [submit] (enqueueUniqueWork) as one step against every
     * other start, cancel and adoption. Returns [NO_ATTEMPT], without running [submit], when the
     * number could not be durably recorded. Nothing is posted for the attempt until WorkManager
     * confirms the enqueue ([attemptEnqueued]) or its worker runs.
     */
    internal fun scheduleAttempt(submit: (attempt: Long) -> Unit): Long = notifications.schedule(submit)

    /**
     * WorkManager confirmed [attempt]'s enqueue: [show] becomes its pending status, unless the
     * attempt has meanwhile run, been replaced or been cancelled.
     */
    internal fun attemptEnqueued(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.enqueued(attempt, show, hide)

    /** The attempt a worker that just started running belongs to, or [NO_ATTEMPT] if it may not post progress. */
    internal fun adoptAttempt(attempt: Long): Long = notifications.adopt(attempt)

    internal fun hideStartProgress(attempt: Long) = notifications.hideProgress(attempt)

    internal fun postAuthPrompt(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.postPrompt(attempt, show, hide)

    internal fun postStartNotification(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.post(attempt, show, hide)

    internal fun finishStartNotification(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.finish(attempt, show, hide)

    internal fun retryStartNotification(
        attempt: Long,
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.retrying(attempt, show, hide)

    internal fun clearStartNotification(
        attempt: Long,
        hide: () -> Unit,
    ) = notifications.clear(attempt, hide)

    internal fun withdrawStartNotification(
        attempt: Long,
        hide: () -> Unit,
    ) = notifications.withdraw(attempt, hide)

    /** Ends every attempt issued so far, removes the notification, then runs [cancelWork], as one step. */
    internal fun cancelAttempts(
        hide: () -> Unit,
        cancelWork: () -> Unit,
    ) = notifications.cancel(hide, cancelWork)

    internal fun postStartNotice(
        show: () -> Unit,
        hide: () -> Unit,
    ) = notifications.notice(show, hide)

    internal fun restoreStartNotification() = notifications.restore()

    private const val PREF_UNANSWERED_AT = "adb_auth_unanswered_at"

    /**
     * Set when a background start ended because the dialog was not accepted. The boot retry loop
     * and background (non-forced) starts stop while it is set, so an unattended device gets one
     * dialog per boot or explicit start rather than one per retry. Cleared by a successful start,
     * at the start of each boot's loop, and by explicit start paths (headless, token-authenticated
     * broadcast, the notification's "Attempt now").
     */
    fun isUnanswered(): Boolean = runCatching { ShizukuSettings.getPreferences().contains(PREF_UNANSWERED_AT) }.getOrDefault(false)

    fun markUnanswered() {
        runCatching { ShizukuSettings.getPreferences().edit().putLong(PREF_UNANSWERED_AT, System.currentTimeMillis()).apply() }
    }

    fun clearUnanswered() {
        runCatching { ShizukuSettings.getPreferences().edit().remove(PREF_UNANSWERED_AT).apply() }
    }
}

/**
 * The key offered to adbd was not accepted: the "Allow USB debugging?" dialog was not answered within
 * [AdbAuthWait.TIMEOUT_MS], was rejected, or the connection dropped while it was showing.
 */
class AdbAuthTimeoutException(
    message: String,
) : SocketTimeoutException(message)

/**
 * Another connection already holds the one authorisation wait, so this start stood down before
 * offering a key — no second dialog was raised and nothing about the pending start changed.
 * Callers report it (or fail quietly) instead of retrying, marking the dialog unanswered, or
 * touching the state machine: all of those belong to the start that holds the wait.
 */
class AdbAuthPendingException(
    message: String,
) : IOException(message)
