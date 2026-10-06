package af.shizuku.manager.harness

import af.shizuku.manager.adb.AdbAuthWait
import af.shizuku.manager.adb.WirelessDebugging
import af.shizuku.manager.receiver.BootCompleteReceiver
import af.shizuku.manager.receiver.HeadlessStartStopReceiver
import af.shizuku.manager.receiver.NotifAttemptReceiver
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.worker.AdbStartWorker
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.robolectric.shadows.ShadowToast
import rikka.shizuku.Shizuku
import java.util.Locale

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

    /** HEADLESS_LOG, as `am broadcast ... --ei lines N` (null: the receiver's default). */
    fun headlessLog(lines: Int? = null): Headless =
        headless(
            Intent(HeadlessStartStopReceiver.ACTION_HEADLESS_LOG).apply {
                if (lines != null) putExtra(HeadlessStartStopReceiver.EXTRA_LINES, lines)
            },
        )

    // Results travel through an ordered broadcast's PendingResult, which only the framework creates.
    private fun headless(intent: Intent): Headless {
        val receiver = HeadlessStartStopReceiver()
        val pending = createPendingResult.invoke(null, 0, null, null, true)
        setPendingResult.invoke(receiver, pending)
        receiver.onReceive(app, intent)
        world.settle()
        return Headless(receiver.resultCode, receiver.resultData, receiver.getResultExtras(false))
    }

    /** A start nobody made by hand: the watchdog's, or the boot retry's. */
    fun backgroundStart() {
        ShizukuReceiverStarter.start(app)
        world.settle()
    }

    // Wi-Fi and wireless debugging (see FakeWorld).

    fun wifiConnects(trusted: Boolean = true) = world.connectWifi(trusted)

    fun wifiDrops() {
        world.dropWifi()
        world.settle()
    }

    /** The user answers the system's prompt with "Always allow on this network". */
    fun userAllowsThisNetwork() {
        world.allowWirelessDebuggingOnThisNetwork()
        world.settle()
    }

    /** Wireless debugging is turned on from outside the app (the user, or `settings put` from a shell). */
    fun wirelessDebuggingTurnedOn() {
        world.wirelessDebuggingOn()
        world.settle()
    }

    /** The phone moves to another access point of the same Wi-Fi network (see [FakeWorld.roamTo]). */
    fun roamsTo(trusted: Boolean) = world.roamTo(trusted)

    /** The restore's quiet retry comes due (see [FakeWorld.quietRetryDue]). */
    fun quietRetryDue() = world.quietRetryDue()

    /** The next recheck of the TCP port after a no-Wi-Fi stop comes due (see [FakeWorld.portRecheckDue]). */
    fun portRecheckDue() = world.portRecheckDue()

    /** adbd's TCP port listens again (adbd finished restarting, or `adb tcpip` from a computer). */
    fun tcpPortOpens(port: Int) = world.openTcpPort(port)

    fun lockScreen() = world.lockScreen()

    fun unlockScreen() = world.unlockScreen()

    /** The device restarts (then [boot] delivers BOOT_COMPLETED). */
    fun reboot() = world.reboot()

    fun awaitLog(line: String) = world.awaitLog(line)

    /** A start is waiting for Wi-Fi (its network callback is registered). */
    fun startWaitsForWifi() = world.awaitWifiWait()

    /** The restore has asked the user to allow wireless debugging on this network. */
    fun startAsksToAllowNetwork() =
        world.waitUntil({ "the notice asking to allow this network (notice: $restoreNoticeTitle)" }) {
            restoreNoticeTitle == string(af.shizuku.manager.R.string.wadb_restore_untrusted_title)
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

    fun string(
        id: Int,
        vararg args: Any,
    ): String = app.getString(id, *args)

    /** The start notification as posted (null: none showing). */
    val startNotification: Notification?
        get() =
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications
                .firstOrNull { it.id == ShizukuReceiverStarter.NOTIFICATION_ID }
                ?.notification

    /** The start notification's full (expanded) text. */
    val notificationText: String?
        get() =
            startNotification?.extras?.let {
                (it.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: it.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            }

    private val restoreNotice: Notification?
        get() =
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .activeNotifications
                .firstOrNull { it.id == WirelessDebugging.NOTICE_ID }
                ?.notification

    /** The title of the restore's own notice (no Wi-Fi, or a network to allow); null: none showing. */
    val restoreNoticeTitle: String? get() = restoreNotice?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    val restoreNoticeText: String?
        get() =
            restoreNotice?.extras?.let {
                (it.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: it.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            }

    val adbWifiEnabled: Int get() = world.adbWifiEnabled

    /** "Allow wireless debugging on this network?" prompts the system has raised. */
    val trustPrompts: Int get() = world.trustPrompts

    val tcpPortOpen: Boolean get() = world.tcpAdbd != null

    /** Writes of adb_wifi_enabled=1 the system refused without a prompt (One UI, locked). */
    val silentRefusals: Int get() = world.silentRefusals

    /** Times adb_wifi_enabled became 1, whoever wrote it. */
    val turnOns: Int get() = world.turnOns

    val watchingWirelessDebugging: Boolean get() = world.watchingWirelessDebugging

    val quietRetryQueued: Boolean get() = world.quietRetryQueued

    val portRecheckQueued: Boolean get() = world.portRecheckQueued

    /** Key offers on every adbd (classic, wireless and TCP): each would be a dialog. */
    val allOffers: Int get() = adbd.offers + world.wirelessAdbd.offers + (world.tcpAdbd?.offers ?: 0)

    /** The start log as HEADLESS_LOG returns it. */
    fun log(): String = headlessLog().data.orEmpty()

    /** [wallMs] as the device shows a time of day: hours, minutes and seconds in its locale and 12/24-hour setting. */
    fun clock(wallMs: Long): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(app)) "Hms" else "hms")
        return DateFormat.format(pattern, wallMs).toString()
    }

    /** Waits until the start notification's text contains every one of [parts]. */
    fun awaitNotificationText(vararg parts: String) {
        world.settle()
        world.waitUntil({ "the start notification to say ${parts.toList()} (it says: $notificationText)" }) {
            val text = notificationText
            text != null && parts.all { it in text }
        }
    }

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
