package af.shizuku.manager.adb

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
}

/** The user did not answer the "Allow USB debugging?" dialog within [AdbAuthWait.TIMEOUT_MS]. */
class AdbAuthTimeoutException(
    message: String,
) : SocketTimeoutException(message)
