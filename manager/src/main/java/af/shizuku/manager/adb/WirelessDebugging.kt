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
 * debugging on this network?" prompt), so the start stops; [WirelessDebugging.armResume] resumes
 * it when Wi-Fi connects or the network changes, and a start by hand tries again at once.
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
 * not up yet), when Wi-Fi goes away or changes network, and when the network is not trusted for
 * wireless debugging, in which case it shows "Allow wireless debugging on this network?" and only
 * "Always allow" trusts it (AOSP AdbDebuggingManager). A paired or "Always allow" key in adb_keys
 * authorises the TLS connection after a reboot, so nothing needs pairing again.
 */
object WirelessDebugging {
    private const val LOG = "WirelessDebugging"
    private const val SETTING = "adb_wifi_enabled"

    // The restore's own notice. Not the start notification (ShizukuReceiverStarter renders that
    // from WorkInfo); this one says what only the user can do.
    const val NOTICE_ID = 1453
    private const val CHANNEL_ID = "AdbStartWorker"

    private const val KEY_BLOCKED = "wadb_restore_blocked"
    private const val KEY_BLOCKED_NETWORK = "wadb_restore_blocked_network"

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

    /** How long a start waits for the user (an unlock, or allowing the network). */
    @Volatile
    internal var userWaitMs = 300_000L

    /** How long mDNS may take to find the port once wireless debugging is on. */
    @Volatile
    internal var discoveryMs = 15_000L

    /** How long to let Wi-Fi settle after a start had to wait for it to connect. */
    @Volatile
    internal var wifiSettleMs = 2_000L

    @Volatile
    internal var pollMs = 250L

    @Volatile
    internal var toggleGapMs = 500L

    @Volatile
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    internal fun resetForTesting() {
        discovery = PortDiscovery(::startMdns)
        wifiWaitMs = 120_000L
        userWaitMs = 300_000L
        discoveryMs = 15_000L
        wifiSettleMs = 2_000L
        pollMs = 250L
        toggleGapMs = 500L
        elapsedMs = { SystemClock.elapsedRealtime() }
    }

    fun setting(context: Context): Int = runCatching { Settings.Global.getInt(context.contentResolver, SETTING, 0) }.getOrDefault(-1)

    private fun put(
        context: Context,
        value: Int,
    ) {
        Settings.Global.putInt(context.contentResolver, SETTING, value)
    }

    // Wi-Fi.

    /** A connected Wi-Fi network, whether or not it has internet or is metered (null: none). */
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrNull()
    }

    // Any Wi-Fi. Not NOT_METERED or VALIDATED (WorkManager's UNMETERED/CONNECTED constraints imply
    // them), nor INTERNET: wireless debugging works on a metered Wi-Fi and on one with no internet.
    private fun wifiRequest(): NetworkRequest =
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
            return withTimeoutOrNull(timeoutMs) { available.await() }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    // What stopped the last restore, durable so that the watchdog, the boot retry and WorkManager's
    // re-runs stand down at once instead of waiting (or prompting) again.

    private fun prefs() = ShizukuSettings.getPreferences()

    fun blocked(): Blocked? =
        runCatching {
            prefs().getString(KEY_BLOCKED, null)?.let { name -> Blocked.values().firstOrNull { it.name == name } }
        }.getOrNull()

    private fun blockedNetwork(): Long = runCatching { prefs().getLong(KEY_BLOCKED_NETWORK, 0L) }.getOrDefault(0L)

    internal fun block(
        reason: Blocked,
        network: Network?,
    ) {
        prefs()
            .edit()
            .putString(KEY_BLOCKED, reason.name)
            .putLong(KEY_BLOCKED_NETWORK, network?.networkHandle ?: 0L)
            .apply()
    }

    /** The restore may go ahead: forgets the block, its notice and its Wi-Fi trigger. */
    fun clearBlock(context: Context) {
        if (blocked() != null) {
            prefs()
                .edit()
                .remove(KEY_BLOCKED)
                .remove(KEY_BLOCKED_NETWORK)
                .apply()
            disarmResume(context)
        }
        cancelNotice(context)
    }

    /**
     * Why a start nobody made by hand should not try wireless debugging now, or null if it should:
     * the block still holds (still no Wi-Fi; still the untrusted network with wireless debugging off).
     */
    fun standDownReason(context: Context): String? =
        when (blocked()) {
            null -> null
            Blocked.NO_WIFI ->
                if (wifiNetwork(context) == null) "still no Wi-Fi; waiting for Wi-Fi to connect before turning wireless debugging on" else null
            Blocked.UNTRUSTED_NETWORK -> {
                val wifi = wifiNetwork(context)
                if (wifi != null && wifi.networkHandle == blockedNetwork() && setting(context) != 1) {
                    "this Wi-Fi network is still not trusted for wireless debugging; waiting for another network or the user"
                } else {
                    null
                }
            }
        }

    // The resume trigger: a network callback with a PendingIntent outlives this process, so Wi-Fi
    // connecting (or the network changing) restarts the restore even if nothing else is running.

    private fun resumeIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WifiRestoreReceiver::class.java),
            // The system adds the network to the intent it sends, so it must be mutable; it is
            // explicit, so nothing else can receive it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )

    fun armResume(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerNetworkCallback(wifiRequest(), resumeIntent(context)) }
            .onSuccess { HeadlessLogger.i(LOG, "will resume when Wi-Fi connects or the network changes") }
            .onFailure { HeadlessLogger.w(LOG, "cannot watch for Wi-Fi: ${HeadlessLogger.brief(it)}") }
    }

    private fun disarmResume(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(resumeIntent(context)) }
    }

    /**
     * A Wi-Fi event reached [WifiRestoreReceiver]. True if the restore should start again: the
     * block no longer holds. The callback also fires for the network that is already connected
     * when it is armed, which for an untrusted network changes nothing.
     */
    fun onWifiEvent(context: Context): Boolean {
        val reason = blocked()
        if (reason == null) {
            disarmResume(context)
            return false
        }
        if (standDownReason(context) != null) return false
        HeadlessLogger.i(
            LOG,
            when (reason) {
                Blocked.NO_WIFI -> "Wi-Fi connected; resuming the ADB restore"
                Blocked.UNTRUSTED_NETWORK -> "the Wi-Fi network changed; resuming the ADB restore"
            },
        )
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
     * One background start's use of wireless debugging. [foreground] keeps the worker alive while
     * it waits for Wi-Fi or the user. [restore] puts adb_wifi_enabled back as this start found it.
     */
    class Session(
        private val context: Context,
        private val note: (String) -> Unit,
        private val warn: (String) -> Unit,
        private val foreground: suspend () -> Unit,
    ) {
        // adb_wifi_enabled before this start first touched it; null until then.
        private var initial: Int? = null

        private sealed interface Round {
            data class Found(
                val port: Int,
            ) : Round

            object NetworkChanged : Round
        }

        /** The wireless debugging TLS port, or a [WirelessDebuggingBlockedException] / [TimeoutException]. */
        suspend fun findPort(): Int {
            var network = requireWifi()
            repeat(MAX_ROUNDS) {
                when (val round = round(network)) {
                    is Round.Found -> return round.port
                    Round.NetworkChanged -> network = requireWifi()
                }
            }
            warn("Wi-Fi kept changing while wireless debugging was turned on ($MAX_ROUNDS rounds)")
            throw TimeoutException("Wi-Fi kept changing while wireless debugging was turned on")
        }

        /** If this start turned wireless debugging on, turns it off again. */
        fun restore() {
            if (initial == 0 && setting(context) == 1) {
                put(context, 0)
                note("wireless debugging was off before this start; turned it off again (adb_wifi_enabled=${setting(context)})")
            }
        }

        private suspend fun requireWifi(): Network {
            wifiNetwork(context)?.let { return it }
            note("no Wi-Fi; waiting up to ${wifiWaitMs / 1000} s for Wi-Fi (metered or without internet is fine)")
            foreground()
            val network = awaitWifi(context, wifiWaitMs)
            if (network == null) {
                warn("no Wi-Fi after ${wifiWaitMs / 1000} s; wireless debugging cannot come up without it")
                block(Blocked.NO_WIFI, null)
                notifyNoWifi(context)
                throw WirelessDebuggingBlockedException(Blocked.NO_WIFI, "no Wi-Fi to turn wireless debugging on")
            }
            // The system's wireless debugging checks the BSSID through WifiManager, which can lag
            // the network callback; turned on before it knows the BSSID, it turns itself off again.
            note("Wi-Fi connected (network $network); settling $wifiSettleMs ms before turning wireless debugging on")
            cancelNotice(context)
            delay(wifiSettleMs)
            return network
        }

        private suspend fun round(network: Network): Round {
            val before = setting(context)
            if (initial == null) initial = before
            if (before != 1) {
                put(context, 1)
                note("turning wireless debugging on: adb_wifi_enabled $before -> ${setting(context)}")
            } else {
                note("wireless debugging is already on; discovering its port")
            }
            // Already on but announcing nothing: turn it off and on once for a fresh announcement.
            var mayToggle = before == 1
            var unlockUsed = false
            val found = AtomicInteger(0)
            val stop = discovery.start(context) { p -> found.compareAndSet(0, p) }
            try {
                var deadline = elapsedMs() + discoveryMs
                while (true) {
                    val port = found.get()
                    if (port > 0) {
                        note("mDNS found port $port")
                        return Round.Found(port)
                    }
                    if (setting(context) == 0) {
                        val wifi = wifiNetwork(context)
                        when {
                            wifi != network -> {
                                warn("adb_wifi_enabled went to 0: Wi-Fi ${if (wifi == null) "disconnected" else "changed to network $wifi"}")
                                return Round.NetworkChanged
                            }
                            locked() && !unlockUsed -> {
                                // Untrusted or not, a locked user cannot answer the system's prompt.
                                note("wireless debugging went off while locked; waiting up to ${userWaitMs / 1000} s for an unlock")
                                foreground()
                                if (!awaitUnlock()) {
                                    warn("not unlocked within ${userWaitMs / 1000} s")
                                    throw TimeoutException("Not unlocked while waiting to turn wireless debugging on")
                                }
                                unlockUsed = true
                                put(context, 1)
                                note("unlocked; turned wireless debugging on again (adb_wifi_enabled=${setting(context)})")
                                deadline = elapsedMs() + discoveryMs
                            }
                            else -> {
                                untrusted(network)
                                deadline = elapsedMs() + discoveryMs
                            }
                        }
                    } else if (elapsedMs() >= deadline) {
                        if (!mayToggle) {
                            warn("mDNS discovery timed out after ${discoveryMs / 1000} s (adb_wifi_enabled=${setting(context)})")
                            throw TimeoutException("Timed out during mDNS port discovery")
                        }
                        mayToggle = false
                        note("mDNS found nothing in ${discoveryMs / 1000} s although wireless debugging is on; turning it off and on once")
                        put(context, 0)
                        delay(toggleGapMs)
                        put(context, 1)
                        note("wireless debugging toggled: adb_wifi_enabled=${setting(context)}")
                        deadline = elapsedMs() + discoveryMs
                    }
                    delay(pollMs)
                }
            } finally {
                stop()
            }
        }

        // adb_wifi_enabled went back to 0 while the same Wi-Fi stays connected: the network is not
        // trusted for wireless debugging. Writing 1 again would only raise the system's prompt
        // again, so ask the user once and wait for them; returns once wireless debugging is on.
        private suspend fun untrusted(network: Network) {
            warn("adb_wifi_enabled went back to 0 with Wi-Fi connected: this network is not trusted for wireless debugging")
            notifyUntrusted(context)
            foreground()
            note("asked the user to allow wireless debugging on this network; waiting up to ${userWaitMs / 1000} s")
            val deadline = elapsedMs() + userWaitMs
            while (elapsedMs() < deadline) {
                if (setting(context) == 1) {
                    note("wireless debugging is on again: the network was allowed")
                    cancelNotice(context)
                    return
                }
                if (wifiNetwork(context) != network) {
                    // The round's next check sees the change and starts a new round on it.
                    note("the Wi-Fi network changed while waiting for the user")
                    cancelNotice(context)
                    return
                }
                delay(pollMs)
            }
            warn("the network was not allowed within ${userWaitMs / 1000} s; not retrying until the network changes or the user acts")
            block(Blocked.UNTRUSTED_NETWORK, network)
            throw WirelessDebuggingBlockedException(Blocked.UNTRUSTED_NETWORK, "this Wi-Fi network is not trusted for wireless debugging")
        }

        private fun locked(): Boolean = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true

        private suspend fun awaitUnlock(): Boolean {
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
                return withTimeoutOrNull(userWaitMs) { unlocked.await() } != null
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }
}
