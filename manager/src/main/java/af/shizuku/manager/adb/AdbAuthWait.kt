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
    internal fun tryBegin(): Boolean = waiting.compareAndSet(0, 1)

    internal fun end() {
        synchronized(notificationLock) { promptShowing = false }
        waiting.compareAndSet(1, 0)
    }

    // Background starts share one ongoing notification (ShizukuReceiverStarter.NOTIFICATION_ID)
    // for their progress and for the holder's authorisation prompt. Every check-then-post or
    // check-then-cancel on it below is one step under this lock, so a racing start can neither
    // cover the prompt nor remove something it did not post.
    private val notificationLock = Any()
    private val prompt = Any()

    // Guarded by notificationLock. promptShowing: the current wait's prompt is in the
    // notification (end() clears it before releasing the slot). notificationPoster: who last
    // posted it through here, or null once a start cleared it — anything showing then came from
    // outside this bookkeeping (e.g. ShizukuReceiverStarter.start's initial post).
    private var promptShowing = false
    private var notificationPoster: Any? = null

    internal fun postAuthPrompt(post: () -> Unit) {
        synchronized(notificationLock) {
            promptShowing = isWaiting()
            notificationPoster = prompt
            post()
        }
    }

    /** Posts progress as [poster], unless that would cover the prompt of a wait still held. */
    internal fun postStartNotification(
        poster: Any,
        post: () -> Unit,
    ) {
        synchronized(notificationLock) {
            if (promptShowing) return
            notificationPoster = poster
            post()
        }
    }

    /** A start that succeeded clears the notification whoever posted it. */
    internal fun clearStartNotification(cancel: () -> Unit) {
        synchronized(notificationLock) {
            notificationPoster = null
            cancel()
        }
    }

    /** A start that stood down removes the notification only if it is that start's own. */
    internal fun withdrawStartNotification(
        poster: Any,
        cancel: () -> Unit,
    ) {
        synchronized(notificationLock) {
            if (notificationPoster !== poster && notificationPoster != null) return
            notificationPoster = null
            cancel()
        }
    }

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
