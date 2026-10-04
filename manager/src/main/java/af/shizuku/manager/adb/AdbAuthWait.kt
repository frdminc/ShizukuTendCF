package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
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
        // Cleared before the slot is released, so it can never clear the next holder's prompt.
        heldPrompt = null
        if (waiting.compareAndSet(1, 0)) starts.end()
        ShizukuReceiverStarter.refreshNotification()
    }

    /** Start work in flight in this process; what the quick-settings tile supervises. */
    val starts = StartsInFlight()

    // Attempts are no longer numbered; only AdbStarter.startAdb's `attempt` parameter, which is
    // ignored, still names this.
    const val NO_ATTEMPT = 0L

    // The dialog the held wait is showing, for the shared start notification. In memory only: it
    // is true exactly while this process holds the connection, and a dead process holds none.
    @Volatile
    private var heldPrompt: StartNotificationState.Display.Prompt? = null

    internal fun prompt(): StartNotificationState.Display.Prompt? = heldPrompt

    /** The key has been offered and adbd's dialog is up; it shows over everything until [end]. */
    internal fun postAuthPrompt(fingerprint: String?) {
        if (!isWaiting()) return
        heldPrompt = StartNotificationState.Display.Prompt(fingerprint)
        ShizukuReceiverStarter.refreshNotification()
    }

    private const val PREF_UNANSWERED_AT = "adb_auth_unanswered_at"

    // The PREF_UNANSWERED_AT value whose notice the user dismissed. Durable, so a notice swiped
    // away (or cancelled) stays away across process restarts until a new dialog goes unanswered.
    private const val PREF_UNANSWERED_DISMISSED = "adb_auth_unanswered_dismissed"

    /**
     * Set when a background start ended because the dialog was not accepted. The boot retry loop
     * and background (non-forced) starts stop while it is set, so an unattended device gets one
     * dialog per boot or explicit start rather than one per retry. Cleared by a successful start,
     * at the start of each boot's loop, and by explicit start paths (headless, token-authenticated
     * broadcast, the notification's "Attempt now"). While set, and not dismissed, it is also the
     * shared start notification's "not answered" notice, so every clear removes that notice.
     */
    fun isUnanswered(): Boolean = runCatching { ShizukuSettings.getPreferences().contains(PREF_UNANSWERED_AT) }.getOrDefault(false)

    internal fun isUnansweredNoticeDue(): Boolean =
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            prefs.contains(PREF_UNANSWERED_AT) &&
                prefs.getLong(PREF_UNANSWERED_AT, 0L) != prefs.getLong(PREF_UNANSWERED_DISMISSED, Long.MIN_VALUE)
        }.getOrDefault(false)

    @Synchronized
    fun markUnanswered(): Boolean {
        runCatching { ShizukuSettings.getPreferences().edit().putLong(PREF_UNANSWERED_AT, System.currentTimeMillis()).apply() }
        ShizukuReceiverStarter.refreshNotification()
        return true
    }

    internal fun unansweredStamp(): Long = runCatching { ShizukuSettings.getPreferences().getLong(PREF_UNANSWERED_AT, 0L) }.getOrDefault(0L)

    @Synchronized
    fun clearUnanswered() {
        runCatching {
            ShizukuSettings
                .getPreferences()
                .edit()
                .remove(PREF_UNANSWERED_AT)
                .remove(PREF_UNANSWERED_DISMISSED)
                .apply()
        }
        ShizukuReceiverStarter.refreshNotification()
    }

    /** The user dismissed the "not answered" notice; the marker itself, and its start guard, stay. */
    @Synchronized
    internal fun dismissUnansweredNotice(stamp: Long? = null) {
        runCatching {
            val prefs = ShizukuSettings.getPreferences()
            if (prefs.contains(PREF_UNANSWERED_AT) && (stamp == null || stamp == prefs.getLong(PREF_UNANSWERED_AT, 0L))) {
                prefs.edit().putLong(PREF_UNANSWERED_DISMISSED, prefs.getLong(PREF_UNANSWERED_AT, 0L)).apply()
            }
        }
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
