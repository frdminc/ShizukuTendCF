package af.shizuku.manager.harness

import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.receiver.BootCompleteReceiver
import af.shizuku.manager.receiver.HeadlessStartStopReceiver
import af.shizuku.manager.receiver.NotifAttemptReceiver
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.worker.AdbStartWorker
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.robolectric.shadows.ShadowToast
import rikka.shizuku.Shizuku

/**
 * One-prompt scenarios in the event vocabulary of the scenario catalogue (A2 §2). Each event drives
 * the production entry point that the event stands for; adbd's side is [adbd].
 */
class Scenario(
    val world: FakeWorld,
) {
    data class Headless(
        val code: Int,
        val data: String?,
        val extras: Bundle?,
    )

    val adbd: FakeAdbd get() = world.adbd
    private val app get() = world.app

    /** How the start work last observed by [awaitStartWork] ended. */
    var lastWork: WorkInfo.State? = null
        private set

    // Boot and its unprotected look-alikes.

    fun boot() = bootAction(Intent.ACTION_BOOT_COMPLETED)

    fun bootQuickboot() = bootAction("android.intent.action.QUICKBOOT_POWERON")

    fun packageReplaced() = bootAction(Intent.ACTION_MY_PACKAGE_REPLACED)

    private fun bootAction(action: String) {
        BootCompleteReceiver().onReceive(app, Intent(action))
        world.settle()
    }

    fun bootRetryTick(): ListenableWorker.Result = world.bootRetryTick()

    // User and automation starts.

    fun tileTap() {
        AdbStartWorker.enqueue(app, explicit = true)
        world.settle()
    }

    fun attemptNow() {
        NotifAttemptReceiver.attempt(app)
        world.settle()
    }

    fun settingsForceStart() {
        ShizukuReceiverStarter.start(app, true)
        world.settle()
    }

    fun headlessStart(force: Boolean = false): Headless =
        headless(Intent(HeadlessStartStopReceiver.ACTION_HEADLESS_START).putExtra(HeadlessStartStopReceiver.EXTRA_FORCE, force))

    fun headlessStatus(): Headless = headless(Intent(HeadlessStartStopReceiver.ACTION_HEADLESS_STATUS))

    // Results travel through an ordered broadcast's PendingResult, which only the framework creates.
    private fun headless(intent: Intent): Headless {
        val receiver = HeadlessStartStopReceiver()
        val pending = createPendingResult.invoke(null, 0, null, null, true)
        setPendingResult.invoke(receiver, pending)
        receiver.onReceive(app, intent)
        world.settle()
        return Headless(receiver.resultCode, receiver.resultData, receiver.getResultExtras(false))
    }

    // adbd's dialog. As on a device, a rejection and a dialog nobody answers look the same to the
    // manager: adbd sends nothing and keeps the connection open, so both end at AdbClient's
    // deadline (shortened by FakeWorld) with the same AdbAuthTimeoutException.

    fun keyOffered() = world.awaitOffer()

    /** The connection holding the dialog is ready to offer its key again ("Ask again"). */
    fun waitHeld() {
        world.waitUntil({ "the held wait to accept a re-offer" }) { AdbAuthWait.reofferHeld() }
        world.settle()
    }

    fun dialogAccepted() {
        adbd.accept()
        awaitStartWork()
    }

    fun dialogRejected() {
        adbd.reject()
        awaitStartWork()
    }

    fun dialogTimedOut() {
        adbd.silent()
        awaitStartWork()
    }

    fun awaitStartWork(): WorkInfo.State? = world.awaitStartWork().also { lastWork = it }

    // Process and WorkManager lifecycle.

    fun processDeath() = world.processDeath()

    fun workerRerun(): ListenableWorker.Result = world.workerRerun()

    // State given before the scenario starts.

    /** An earlier dialog went unanswered at [stamp]. */
    fun markerSetAt(stamp: Long) {
        world.advanceTime(stamp - world.now)
        kotlin.check(AdbAuthWait.markUnanswered()) { "could not seed the unanswered marker" }
        world.settle()
    }

    fun advanceTime(ms: Long) = world.advanceTime(ms)

    /** AdbClient's deadline, in real milliseconds, for the waits this scenario starts. */
    fun authTimeout(ms: Int) {
        AdbAuthWait.timeoutMs = ms
    }

    // Observations.

    val now: Long get() = world.now

    /** The marker as this process sees it (null: none). Read past any read failure. */
    val marker: Long?
        get() = if (world.prefs.inMemory(MARKER)) withReadable { world.prefs.getLong(MARKER, 0L) } else null

    val markerOnDisk: Boolean get() = world.prefs.onDisk(MARKER)

    val serverRunning: Boolean get() = Shizuku.pingBinder()

    /** Start requests WorkManager actually ran. */
    val startsRun: Int get() = world.startInputs.size

    /** The text of the last toast shown (null: none). */
    val lastToast: String? get() = ShadowToast.getTextOfLatestToast()

    /** The label of the start notification's first action ("Attempt now" or "Ask again"). */
    val attemptAction: String?
        get() =
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications
                .firstOrNull { it.id == ShizukuReceiverStarter.NOTIFICATION_ID }
                ?.notification
                ?.actions
                ?.firstOrNull()
                ?.title
                ?.toString()

    fun string(id: Int): String = app.getString(id)

    fun expectOffers(n: Int) = assertEquals("key offers (adbd dialogs raised)", n, adbd.offers)

    fun check(block: Scenario.() -> Unit) = block()

    private fun <T> withReadable(block: () -> T): T {
        val failing = world.prefs.failOnRead
        world.prefs.failOnRead = false
        try {
            return block()
        } finally {
            world.prefs.failOnRead = failing
        }
    }

    private companion object {
        const val MARKER = "adb_auth_unanswered_at"

        val createPendingResult =
            Class
                .forName("org.robolectric.shadows.ShadowBroadcastPendingResult")
                .getDeclaredMethod(
                    "create",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Bundle::class.java,
                    Boolean::class.javaPrimitiveType,
                ).apply { isAccessible = true }

        val setPendingResult =
            BroadcastReceiver::class.java.getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
    }
}

fun FakeWorld.scenario(block: Scenario.() -> Unit) {
    Scenario(this).block()
}
