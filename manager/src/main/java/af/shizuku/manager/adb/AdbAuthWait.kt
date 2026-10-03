package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide marker for "an AdbClient has sent its public key and is holding its one connection
 * open until the user answers adbd's "Allow USB debugging?" dialog".
 *
 * adbd raises a new dialog for every connection that offers an unknown key, so while this is set
 * nothing may open another connection: [af.shizuku.manager.receiver.ShizukuReceiverStarter.start],
 * [af.shizuku.manager.worker.AdbStartWorker.enqueue] (which would otherwise REPLACE, i.e. cancel,
 * the waiting worker) and the headless start receiver all check [isWaiting].
 */
object AdbAuthWait {
    /** How long a single connection waits for the dialog to be answered. */
    const val TIMEOUT_MS = 150_000

    private val waiting = AtomicInteger(0)

    fun isWaiting(): Boolean = waiting.get() > 0

    internal fun begin() {
        waiting.incrementAndGet()
    }

    internal fun end() {
        waiting.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    private const val PREF_UNANSWERED_AT = "adb_auth_unanswered_at"

    /**
     * Set when a background start ended because the dialog was not accepted. The boot retry loop
     * stops while it is set, so an unattended device gets one dialog per boot or explicit start
     * rather than one per retry. Cleared by a successful start and at the start of each boot's loop.
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
