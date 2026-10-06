package af.shizuku.manager.adb

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.NotifAttemptActivity
import af.shizuku.manager.receiver.WifiRestoreReceiver
import af.shizuku.manager.utils.HeadlessLogger
import af.shizuku.manager.utils.SettingsPage
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wireless debugging cannot come up without the user: there is no Wi-Fi, or the Wi-Fi network is
 * not trusted for it. Retrying would only repeat the same wait (or the system's "Allow wireless
 * debugging on this network?" prompt), so the start stops. Wi-Fi connecting resumes a start that
 * had none ([WirelessDebugging.armResume]); a refused network gets one automatic prompt per boot.
 * A start by hand always tries again.
 */
class WirelessDebuggingBlockedException(
    val reason: WirelessDebugging.Blocked,
    message: String,
) : Exception(message)

/**
 * Brings wireless debugging up for a background start and finds its TLS port, the order a user
 * would follow by hand: Wi-Fi first, then adb_wifi_enabled=1, then mDNS.
 *
 * The system writes adb_wifi_enabled back to 0 when Wi-Fi is not connected (at boot it is usually
 * not up yet) or WifiManager does not know its BSSID yet, when Wi-Fi goes away or changes network,
 * and when the network is not trusted for wireless debugging, in which case it shows "Allow
 * wireless debugging on this network?" and only "Always allow" trusts it (AOSP
 * AdbDebuggingManager). A paired or "Always allow" key in adb_keys authorises the TLS connection
 * after a reboot, so nothing needs pairing again.
 *
 * At most one automatic prompt per boot: the moment a write of 1 comes back 0 on the same Wi-Fi
 * the prompt is recorded (durably, with the boot count), and from then on only a start by hand
 * made after it, HEADLESS_START with force, the user turning wireless debugging on, or the next
 * boot write 1 again. Without location permission no Wi-Fi network has a stable identity (each
 * connection gets a new handle, and handles restart with every boot), so the record is per boot.
 */
object WirelessDebugging {
    private const val LOG = "WirelessDebugging"
    private const val SETTING = "adb_wifi_enabled"

    // The restore's own notice. Not the start notification (ShizukuReceiverStarter renders that
    // from WorkInfo); this one says what only the user can do.
    const val NOTICE_ID = 1453
    private const val CHANNEL_ID = "AdbStartWorker"

    // This boot's prompt: the boot count, and when (wall clock, AdbAuthWait.clockMs) it was raised.
    private const val KEY_PROMPTED_BOOT = "wadb_restore_prompted_boot"
    private const val KEY_PROMPTED_AT = "wadb_restore_prompted_at"

    // A write of 1 made this boot whose outcome no run saw (the run died): counts as the prompt.
    private const val KEY_WRITE_PENDING_BOOT = "wadb_restore_write_pending_boot"

    // A restore stopped for want of Wi-Fi this boot (resumed when Wi-Fi connects).
    private const val KEY_NO_WIFI_BOOT = "wadb_restore_no_wifi_boot"

    // Set (committed) before a start that found wireless debugging off turns it on, cleared when it
    // is put back: a run that dies in between leaves it, and the next one then knows it was off.
    private const val KEY_TURNED_ON = "wadb_restore_turned_on"

    // When WifiRestoreReceiver resumed a restore on Wi-Fi that had just connected (elapsedMs).
    private const val KEY_WIFI_SEEN_AT = "wadb_restore_wifi_seen_at"
    private const val KEY_WIFI_SEEN_BOOT = "wadb_restore_wifi_seen_boot"

    // Earlier builds' keys, forgotten at boot.
    private val OLD_KEYS = listOf("wadb_restore_blocked", "wadb_restore_blocked_boot", "wadb_restore_blocked_network")

    // Rounds of "Wi-Fi, turn it on, discover", each started again when Wi-Fi changes under it.
    private const val MAX_ROUNDS = 3

    enum class Blocked { NO_WIFI, UNTRUSTED_NETWORK }

    /** Starts mDNS discovery of the wireless debugging TLS port; the returned function stops it. */
    fun interface PortDiscovery {
        fun start(
            context: Context,
            onPort: (Int) -> Unit,
        ): () -> Unit
    }

    // Test seams (FakeWorld). Real defaults.
    @Volatile
    internal var discovery: PortDiscovery = PortDiscovery(::startMdns)

    private fun startMdns(
        context: Context,
        onPort: (Int) -> Unit,
    ): () -> Unit {
        // No wireless debugging before Android 11: nothing to find.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return {}
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p -> if (p > 0) onPort(p) }
        mdns.start()
        return { mdns.stop() }
    }

    /** How long a start waits for Wi-Fi to connect. */
    @Volatile
    internal var wifiWaitMs = 120_000L

    /** How long a start waits for the user, an unlock and the answer together. */
    @Volatile
    internal var userWaitMs = 300_000L

    // Everything one start may wait before it connects. WorkManager stops a background job at 10
    // minutes, and the connection after this may wait AdbAuthWait.TIMEOUT_MS (5 minutes) for
    // adbd's dialog, so a run that is not a foreground worker keeps to 10 - 5 - 1 minutes; a
    // stopped run is re-run, and would wait again. A foreground run has no such limit.
    @Volatile
    internal var backgroundBudgetMs = 240_000L

    @Volatile
    internal var foregroundBudgetMs = 480_000L

    /** How long mDNS may take to find the port once wireless debugging is on. */
    @Volatile
    internal var discoveryMs = 15_000L

    /**
     * How old a Wi-Fi network must be before wireless debugging is turned on. Turned on before
     * WifiManager knows the BSSID, the system turns it off again with no prompt, which would look
     * like a refusal; after this, a 0 counts as one.
     */
    @Volatile
    internal var wifiSettleMs = 5_000L

    @Volatile
    internal var pollMs = 250L

    @Volatile
    internal var toggleGapMs = 500L

    @Volatile
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /** Time since boot: no Wi-Fi network is older. */
    @Volatile
    internal var uptimeMs: () -> Long = { SystemClock.elapsedRealtime() }

    internal fun resetForTesting() {
        discovery = PortDiscovery(::startMdns)
        wifiWaitMs = 120_000L
        userWaitMs = 300_000L
        backgroundBudgetMs = 240_000L
        foregroundBudgetMs = 480_000L
        discoveryMs = 15_000L
        wifiSettleMs = 5_000L
        pollMs = 250L
        toggleGapMs = 500L
        elapsedMs = { SystemClock.elapsedRealtime() }
        uptimeMs = { SystemClock.elapsedRealtime() }
    }

    /** Wireless debugging can restore the TCP port here: Android 11+ with TLS adb. */
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching {
                af.shizuku.manager.utils.EnvironmentUtils
                    .isTlsSupported()
            }.getOrDefault(false)

    fun setting(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, SETTING, 0) }.getOrDefault(-1)

    private fun put(
        context: Context,
        value: Int,
    ) {
        Settings.Global.putInt(context.contentResolver, SETTING, value)
    }

    private fun bootCount(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)

    private fun prefs() = ShizukuSettings.getPreferences()

    private fun thisBoot(
        context: Context,
        key: String,
    ): Boolean = runCatching { prefs().getInt(key, -1) == bootCount(context) }.getOrDefault(false)

    // Wi-Fi.

    /**
     * A connected Wi-Fi network, whether or not it has internet or is metered (null: none). Not a
     * VPN that runs over Wi-Fi.
     */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull {
                val caps = cm.getNetworkCapabilities(it)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        }.getOrNull()
    }

    // Any Wi-Fi. Not NOT_METERED or VALIDATED (WorkManager's UNMETERED/CONNECTED constraints imply
    // them), nor INTERNET: wireless debugging works on a metered Wi-Fi and on one with no internet.
    // The builder's defaults keep NOT_VPN, TRUSTED and NOT_RESTRICTED.
    internal fun wifiRequest(): NetworkRequest =
        NetworkRequest
            .Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

    /** Waits up to [timeoutMs] for Wi-Fi with a network callback; null if none connected. */
    suspend fun awaitWifi(
        context: Context,
        timeoutMs: Long,
    ): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val available = CompletableDeferred<Network>()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available.complete(network)
                }
            }
        cm.registerNetworkCallback(wifiRequest(), callback)
        try {
            // Registered first, so a network that connects in between is not missed.
            wifiNetwork(context)?.let { return it }
            return withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) { available.await() }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    // This boot's prompt and the no-Wi-Fi stop, durable so that the watchdog, the boot retry,
    // HEADLESS_START and WorkManager's re-runs stand down at once instead of waiting or prompting.

    /** When this boot's automatic network prompt was raised (AdbAuthWait.clockMs), or null. */
    fun promptedAt(context: Context): Long? =
        if (thisBoot(context, KEY_PROMPTED_BOOT)) runCatching { prefs().getLong(KEY_PROMPTED_AT, 0L) }.getOrNull() else null

    private fun markPrompted(context: Context) {
        runCatching {
            prefs()
                .edit()
                .putInt(KEY_PROMPTED_BOOT, bootCount(context))
                .putLong(KEY_PROMPTED_AT, AdbAuthWait.clockMs())
                .remove(KEY_WRITE_PENDING_BOOT)
                .commit()
        }
    }

    /** A start by hand made after the prompt, or HEADLESS_START with force: it may ask again. */
    fun clearPrompted(context: Context) {
        runCatching {
            prefs()
                .edit()
                .remove(KEY_PROMPTED_BOOT)
                .remove(KEY_PROMPTED_AT)
                .remove(KEY_WRITE_PENDING_BOOT)
                .commit()
        }
        cancelNotice(context)
    }

    private fun setWritePending(
        context: Context,
        pending: Boolean,
    ) {
        runCatching {
            val edit = prefs().edit()
            if (pending) edit.putInt(KEY_WRITE_PENDING_BOOT, bootCount(context)) else edit.remove(KEY_WRITE_PENDING_BOOT)
            edit.commit()
        }
    }

    private fun noWifiThisBoot(context: Context): Boolean = thisBoot(context, KEY_NO_WIFI_BOOT)

    private fun markNoWifi(context: Context) {
        runCatching {
            prefs()
                .edit()
                .putInt(KEY_NO_WIFI_BOOT, bootCount(context))
                .apply()
        }
    }

    /**
     * A start succeeded, or one may go ahead: forgets the no-Wi-Fi stop, its Wi-Fi trigger and the
     * restore's notice. This boot's prompt stays (see [clearPrompted]).
     */
    fun clearNoWifi(context: Context) {
        if (runCatching { prefs().contains(KEY_NO_WIFI_BOOT) }.getOrDefault(false)) {
            runCatching {
                prefs()
                    .edit()
                    .remove(KEY_NO_WIFI_BOOT)
                    .apply()
            }
            disarmResume(context)
        }
        cancelNotice(context)
    }

    /** BOOT_COMPLETED: everything here is per boot. */
    fun onBoot(context: Context) {
        runCatching {
            val edit = prefs().edit()
            (OLD_KEYS + listOf(KEY_PROMPTED_BOOT, KEY_PROMPTED_AT, KEY_WRITE_PENDING_BOOT, KEY_NO_WIFI_BOOT, KEY_WIFI_SEEN_AT, KEY_WIFI_SEEN_BOOT))
                .forEach { edit.remove(it) }
            edit.apply()
        }
        disarmResume(context)
        cancelNotice(context)
    }

    /** What stops a start that has not been allowed to ask (see [standDownReason]); null: nothing. */
    fun blocked(context: Context): Blocked? =
        when {
            promptedAt(context) != null && setting(context) != 1 -> Blocked.UNTRUSTED_NETWORK
            noWifiThisBoot(context) -> Blocked.NO_WIFI
            else -> null
        }

    /**
     * Why a start should not try wireless debugging now, or null if it should. Unattended starts
     * ([explicit] false) stand down after this boot's prompt while wireless debugging is still off,
     * and while there is still no Wi-Fi after a no-Wi-Fi stop. A start by hand may ask again,
     * unless it is a WorkManager re-run of a request made before the prompt ([requestedAt]).
     */
    fun standDownReason(
        context: Context,
        explicit: Boolean,
        requestedAt: Long,
    ): Pair<Blocked, String>? {
        val prompted = promptedAt(context)
        if (prompted != null && setting(context) != 1) {
            if (!explicit) {
                return Blocked.UNTRUSTED_NETWORK to
                    "this Wi-Fi network is still not trusted for wireless debugging (one automatic prompt per boot); " +
                    "waiting for a start by hand, wireless debugging turned on, or the next boot"
            }
            if (requestedAt <= prompted) {
                return Blocked.UNTRUSTED_NETWORK to
                    "this start by hand was requested before this boot's network prompt and has asked already; waiting for a new start by hand"
            }
        }
        if (!explicit && noWifiThisBoot(context) && wifiNetwork(context) == null) {
            return Blocked.NO_WIFI to "still no Wi-Fi; waiting for Wi-Fi to connect before turning wireless debugging on"
        }
        return null
    }

    /**
     * A TCP-mode start succeeded without this start touching wireless debugging: if an earlier run
     * turned it on and died before turning it off, turn it off now.
     */
    fun afterTcpStart(
        context: Context,
        note: (String) -> Unit,
    ) {
        if (!runCatching { prefs().getBoolean(KEY_TURNED_ON, false) }.getOrDefault(false)) return
        if (setting(context) == 1) {
            put(context, 0)
            note("an earlier start turned wireless debugging on and did not turn it off; turned it off (adb_wifi_enabled=${setting(context)})")
        }
        runCatching { prefs().edit().remove(KEY_TURNED_ON).commit() }
    }

    // The resume trigger: a network callback with a PendingIntent outlives this process, so Wi-Fi
    // connecting restarts a restore that had none even if nothing else is running.

    private fun resumeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WifiRestoreReceiver::class.java),
            // The system adds the network to the intent it sends, so it must be mutable; it is
            // explicit, so nothing else can receive it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    /** Only for a no-Wi-Fi stop with no prompt this boot (a prompted boot never resumes by itself). */
    fun armResume(context: Context) {
        if (!noWifiThisBoot(context) || promptedAt(context) != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerNetworkCallback(wifiRequest(), resumeIntent(context)) }
            .onSuccess { HeadlessLogger.i(LOG, "will resume when Wi-Fi connects") }
            .onFailure { HeadlessLogger.w(LOG, "cannot watch for Wi-Fi: ${HeadlessLogger.brief(it)}") }
    }

    private fun disarmResume(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(resumeIntent(context)) }
    }

    /**
     * A Wi-Fi event reached [WifiRestoreReceiver]. True if the restore should start again: it
     * stopped for want of Wi-Fi, nothing has prompted this boot, and there is Wi-Fi now.
     */
    fun onWifiEvent(context: Context): Boolean {
        if (!noWifiThisBoot(context) || promptedAt(context) != null) {
            disarmResume(context)
            return false
        }
        if (wifiNetwork(context) == null) return false
        // This Wi-Fi has only just connected: the restore lets it settle before turning anything on.
        runCatching {
            prefs()
                .edit()
                .putLong(KEY_WIFI_SEEN_AT, elapsedMs())
                .putInt(KEY_WIFI_SEEN_BOOT, bootCount(context))
                .apply()
        }
        HeadlessLogger.i(LOG, "Wi-Fi connected; resuming the ADB restore")
        disarmResume(context)
        return true
    }

    // Notices.

    private fun postNotice(
        context: Context,
        title: Int,
        text: Int,
        contentIntent: Intent,
        action: Pair<Int, Intent>? = null,
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.wadb_notification_title), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // "Attempt now" is the start notification's explicit start (one-prompt rules included).
            val attempt =
                PendingIntent.getActivity(
                    context,
                    NOTICE_ID,
                    Intent(context, NotifAttemptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    flags,
                )
            val builder =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle(context.getString(title))
                    .setContentText(context.getString(text))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
                    .setContentIntent(PendingIntent.getActivity(context, NOTICE_ID, contentIntent, flags))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
            action?.let { (label, intent) ->
                builder.addAction(0, context.getString(label), PendingIntent.getActivity(context, NOTICE_ID + 1, intent, flags))
            }
            builder.addAction(0, context.getString(R.string.wadb_notification_attempt_now), attempt)
            nm.notify(NOTICE_ID, builder.build())
        }.onFailure { HeadlessLogger.w(LOG, "could not post the notice: ${HeadlessLogger.brief(it)}") }
    }

    fun notifyNoWifi(context: Context) =
        postNotice(
            context,
            R.string.wadb_restore_no_wifi_title,
            R.string.wadb_restore_no_wifi_text,
            SettingsPage.InternetPanel.buildIntent(context),
        )

    fun notifyUntrusted(context: Context) {
        val wireless = SettingsPage.Developer.WirelessDebugging.buildIntent(context)
        postNotice(
            context,
            R.string.wadb_restore_untrusted_title,
            R.string.wadb_restore_untrusted_text,
            wireless,
            R.string.wadb_restore_open_wireless_debugging to wireless,
        )
    }

    fun cancelNotice(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
    }

    /**
     * One background start's use of wireless debugging. [foreground] tries to make the worker a
     * foreground one while it waits for Wi-Fi or the user, and says whether it is. With
     * [restoresState], [restore] puts adb_wifi_enabled back as this start found it (TCP mode);
     * without, wireless debugging is the transport and stays on.
     */
    class Session(
        private val context: Context,
        private val note: (String) -> Unit,
        private val warn: (String) -> Unit,
        private val foreground: suspend () -> Boolean,
        private val restoresState: Boolean = false,
    ) {
        // adb_wifi_enabled before this start (or a run of it that died) first touched it.
        private var initial: Int? = null

        private val startedAt = elapsedMs()
        private var isForeground = false

        // Set when this start first waits for the user: an unlock and the answer share the wait.
        private var userDeadline: Long? = null

        // When this start saw Wi-Fi appear (it waited for it, or it differs from the last round's).
        private var wifiSeenAt: Long? = null

        // The Wi-Fi network the last round used.
        private var lastNetwork: Network? = null

        private sealed interface Round {
            data class Found(
                val port: Int,
            ) : Round

            object NetworkChanged : Round
        }

        private fun sessionDeadline(): Long = startedAt + if (isForeground) foregroundBudgetMs else backgroundBudgetMs

        private fun remaining(): Long = sessionDeadline() - elapsedMs()

        private suspend fun goForeground() {
            if (!isForeground && foreground()) isForeground = true
        }

        private fun userRemaining(): Long {
            val deadline = userDeadline ?: (elapsedMs() + userWaitMs).also { userDeadline = it }
            return minOf(deadline, sessionDeadline()) - elapsedMs()
        }

        /** How long ago Wi-Fi appeared, as far as anything here can tell; boot is the upper bound. */
        private fun wifiAgeMs(): Long {
            val now = elapsedMs()
            val seenByReceiver =
                if (thisBoot(context, KEY_WIFI_SEEN_BOOT)) {
                    runCatching { prefs().getLong(KEY_WIFI_SEEN_AT, Long.MIN_VALUE) }.getOrDefault(Long.MIN_VALUE)
                } else {
                    Long.MIN_VALUE
                }
            return listOfNotNull(
                uptimeMs(),
                wifiSeenAt?.let { now - it },
                seenByReceiver.takeIf { it != Long.MIN_VALUE && it <= now }?.let { now - it },
            ).min()
        }

        private fun untrustedStop(why: String): WirelessDebuggingBlockedException = WirelessDebuggingBlockedException(Blocked.UNTRUSTED_NETWORK, why)

        /** The wireless debugging TLS port, or a [WirelessDebuggingBlockedException] / [TimeoutException]. */
        suspend fun findPort(): Int {
            // A run that wrote 1 this boot and died before it saw the outcome may have raised the
            // prompt: count it as raised.
            if (thisBoot(context, KEY_WRITE_PENDING_BOOT)) {
                if (setting(context) != 1) {
                    warn("an earlier start turned wireless debugging on and stopped before it saw the outcome; counting that as this boot's network prompt")
                    markPrompted(context)
                    throw untrustedStop("an earlier start may have raised this boot's network prompt")
                }
                setWritePending(context, false)
            }
            var network = requireWifi()
            repeat(MAX_ROUNDS) {
                when (val round = round(network)) {
                    is Round.Found -> return round.port
                    Round.NetworkChanged -> {
                        if (promptedAt(context) != null && setting(context) != 1) {
                            warn("Wi-Fi changed after this boot's network prompt; not turning wireless debugging on again")
                            throw untrustedStop("Wi-Fi changed after this boot's network prompt")
                        }
                        network = requireWifi()
                    }
                }
            }
            warn("Wi-Fi kept changing while wireless debugging was turned on ($MAX_ROUNDS rounds); waiting for Wi-Fi to connect again")
            markNoWifi(context)
            throw WirelessDebuggingBlockedException(Blocked.NO_WIFI, "Wi-Fi kept changing while wireless debugging was turned on")
        }

        /** If this start (or a run of it that died) turned wireless debugging on, turns it off again. */
        fun restore() {
            // Still 1 at the end of the run: the system accepted the write, which raised no
            // prompt, however the run ended (an mDNS timeout, the budget, Cancel, a stop).
            if (setting(context) == 1) setWritePending(context, false)
            if (!restoresState || initial == null) return
            if (initial == 0 && setting(context) == 1) {
                put(context, 0)
                note("wireless debugging was off before this start; turned it off again (adb_wifi_enabled=${setting(context)})")
            }
            // This boot's prompt may still be on screen: a late "Allow" turns wireless debugging
            // on, and whichever start comes next must know that a restore turned it on.
            val promptMayStillBeAnswered = initial == 0 && promptedAt(context) != null && setting(context) != 1
            if (!promptMayStillBeAnswered) runCatching { prefs().edit().remove(KEY_TURNED_ON).commit() }
        }

        private suspend fun requireWifi(): Network {
            val network =
                wifiNetwork(context) ?: run {
                    val waitMs = minOf(wifiWaitMs, remaining())
                    note("no Wi-Fi; waiting up to ${waitMs / 1000} s for Wi-Fi (metered or without internet is fine)")
                    goForeground()
                    val connected = awaitWifi(context, waitMs)
                    if (connected == null) {
                        warn("no Wi-Fi after ${waitMs / 1000} s; wireless debugging cannot come up without it")
                        markNoWifi(context)
                        notifyNoWifi(context)
                        throw WirelessDebuggingBlockedException(Blocked.NO_WIFI, "no Wi-Fi to turn wireless debugging on")
                    }
                    wifiSeenAt = elapsedMs()
                    note("Wi-Fi connected (network $connected)")
                    cancelNotice(context)
                    connected
                }
            if (lastNetwork != null && network != lastNetwork) {
                // Another network than the last round's: its BSSID may not be known yet either.
                wifiSeenAt = elapsedMs()
                note("Wi-Fi is now network $network")
            }
            lastNetwork = network
            val age = wifiAgeMs()
            if (age < wifiSettleMs) {
                note("Wi-Fi appeared $age ms ago; settling ${wifiSettleMs - age} ms before turning wireless debugging on")
                delay(wifiSettleMs - age)
            }
            return network
        }

        private fun turnOn() {
            // Committed before the write: a run that dies right after it leaves the record.
            setWritePending(context, true)
            put(context, 1)
        }

        private suspend fun round(network: Network): Round {
            val before = setting(context)
            if (initial == null) {
                val leftOn = restoresState && runCatching { prefs().getBoolean(KEY_TURNED_ON, false) }.getOrDefault(false)
                if (leftOn && before == 1) note("an earlier start turned wireless debugging on and did not turn it off; it counts as off")
                initial = if (leftOn) 0 else before
            }
            var wroteThisRound = false
            if (before != 1) {
                if (promptedAt(context) != null) throw untrustedStop("this boot's network prompt has been raised already")
                // Wi-Fi may have moved while this round settled: the system would check the new
                // network, which has not settled.
                if (wifiNetwork(context) != network) {
                    note("Wi-Fi changed before wireless debugging was turned on; not writing")
                    return Round.NetworkChanged
                }
                wroteThisRound = true
                if (restoresState && initial == 0) runCatching { prefs().edit().putBoolean(KEY_TURNED_ON, true).commit() }
                turnOn()
                note("turning wireless debugging on: adb_wifi_enabled $before -> ${setting(context)}")
            } else {
                note("wireless debugging is already on; discovering its port")
            }
            // Already on but announcing nothing: turn it off and on once for a fresh announcement.
            var mayToggle = before == 1
            val found = AtomicInteger(0)
            val stop = discovery.start(context) { p -> found.compareAndSet(0, p) }
            try {
                var deadline = elapsedMs() + discoveryMs
                while (true) {
                    val port = found.get()
                    if (port > 0) {
                        setWritePending(context, false)
                        note("mDNS found port $port")
                        return Round.Found(port)
                    }
                    if (setting(context) == 0) {
                        val wifi = wifiNetwork(context)
                        if (wifi != network) {
                            if (wroteThisRound && wifi != null) {
                                // This round's write met another network, which the system checked
                                // and may have asked about: count it as this boot's prompt.
                                markPrompted(context)
                                notifyUntrusted(context)
                                warn("adb_wifi_enabled went to 0 after the write with Wi-Fi now on network $wifi; counting that as this boot's network prompt")
                            } else {
                                setWritePending(context, false)
                                warn("adb_wifi_enabled went to 0: Wi-Fi ${if (wifi == null) "disconnected" else "changed to network $wifi"}")
                            }
                            return Round.NetworkChanged
                        }
                        // The same, settled Wi-Fi: the system refused (and asked, if the user can
                        // see it). Recorded before anything else, so no run asks again this boot.
                        if (promptedAt(context) == null) {
                            markPrompted(context)
                            warn("adb_wifi_enabled went back to 0 with Wi-Fi connected: this network is not trusted for wireless debugging; this boot's one network prompt")
                        }
                        if (locked()) awaitUnlock()
                        untrusted(network)
                        deadline = elapsedMs() + discoveryMs
                    } else if (elapsedMs() >= minOf(deadline, sessionDeadline())) {
                        if (!mayToggle || elapsedMs() >= sessionDeadline()) {
                            // On all this time: the write was accepted, no prompt.
                            if (setting(context) == 1) setWritePending(context, false)
                            warn("mDNS discovery timed out after ${discoveryMs / 1000} s (adb_wifi_enabled=${setting(context)})")
                            throw TimeoutException("Timed out during mDNS port discovery")
                        }
                        mayToggle = false
                        note("mDNS found nothing in ${discoveryMs / 1000} s although wireless debugging is on; turning it off and on once")
                        put(context, 0)
                        delay(toggleGapMs)
                        wroteThisRound = true
                        turnOn()
                        note("wireless debugging toggled: adb_wifi_enabled=${setting(context)}")
                        deadline = elapsedMs() + discoveryMs
                    }
                    delay(pollMs)
                }
            } finally {
                stop()
            }
        }

        // Off while locked: the system's prompt waits for the user, who cannot answer it locked.
        // Nothing is written again (that would queue a second prompt).
        private suspend fun awaitUnlock() {
            val waitMs = userRemaining()
            note("wireless debugging went off while locked; waiting up to ${waitMs / 1000} s for an unlock")
            notifyUntrusted(context)
            goForeground()
            if (!awaitUnlock(waitMs)) {
                warn("not unlocked within ${waitMs / 1000} s; not trying again this boot until a start by hand or wireless debugging is turned on")
                throw untrustedStop("locked while the system asked to allow wireless debugging on this network")
            }
            note("unlocked; waiting for the system's prompt to be answered")
        }

        // Waits for the user to allow wireless debugging on this network (the prompt was raised by
        // the write). Returns once it is on, or the network changed (the round's next check sees
        // that).
        private suspend fun untrusted(network: Network) {
            notifyUntrusted(context)
            goForeground()
            val waitMs = userRemaining()
            note("asked the user to allow wireless debugging on this network; waiting up to ${waitMs / 1000} s")
            val deadline = elapsedMs() + waitMs
            while (elapsedMs() < deadline) {
                if (setting(context) == 1) {
                    note("wireless debugging is on again: the network was allowed")
                    cancelNotice(context)
                    return
                }
                if (wifiNetwork(context) != network) {
                    note("the Wi-Fi network changed while waiting for the user")
                    return
                }
                delay(pollMs)
            }
            warn("the network was not allowed within ${waitMs / 1000} s; not trying again this boot until a start by hand or wireless debugging is turned on")
            throw untrustedStop("this Wi-Fi network is not trusted for wireless debugging")
        }

        private fun locked(): Boolean = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

        private suspend fun awaitUnlock(waitMs: Long): Boolean {
            val unlocked = CompletableDeferred<Unit>()
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        if (intent.action == Intent.ACTION_USER_PRESENT) unlocked.complete(Unit)
                    }
                }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                if (!locked()) return true
                return withTimeoutOrNull(waitMs.coerceAtLeast(0L)) { unlocked.await() } != null
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
