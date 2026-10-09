package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>,
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null

    /**
     * Host the discovered adb service resolved to, for consumers to connect to. Normalized so
     * loopback stays "127.0.0.1" (byte-identical to the previous hard-coded behavior for the common
     * on-device case); only a non-loopback *local-interface* address — as can happen under Android
     * 16+ Local Network Protection, where the adb daemon may advertise a real interface rather than
     * loopback — is surfaced, so pairing connects to the actual host instead of failing on 127.0.0.1.
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set

    /**
     * Told the NSD error code when a discovery could not be started (#25). On Android 17 a
     * discovery made without ACCESS_LOCAL_NETWORK fails with
     * [NsdManager.FAILURE_PERMISSION_DENIED] instead of showing a picker; a consumer that would
     * otherwise wait for a port that cannot come stops waiting on this.
     */
    @Volatile
    var onDiscoveryFailed: ((Int) -> Unit)? = null

    private val appContext = context.applicationContext
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restartJob: Job? = null
    private var restartScheduled = false
    private var attempts = 0

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            discover()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartJob?.cancel()
        mdnsScope.cancel()
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).e(e, "listener not registered when stopping service discovery")
            }
            registered = false
        }
    }

    /**
     * Every discovery goes through here so each one carries the no-picker flag on Android 17 (#25):
     * without it the system shows "Choose a device to connect" to an app that lacks
     * ACCESS_LOCAL_NETWORK, and a discovery nobody is watching (boot, the watchdog, a headless
     * start) would wait on a picker nobody sees. With the flag such a discovery fails with
     * [NsdManager.FAILURE_PERMISSION_DENIED], which [onDiscoveryFailed] reports.
     */
    private fun discover() {
        if (Build.VERSION.SDK_INT >= 37) {
            discoverNoPicker()
        } else {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @RequiresApi(37)
    private fun discoverNoPicker() {
        val request =
            DiscoveryRequest
                .Builder(serviceType)
                .setFlags(DiscoveryRequest.FLAG_NO_PICKER)
                .build()
        // The main executor keeps the callbacks where the legacy overload delivered them.
        nsdManager.discoverServices(request, appContext.mainExecutor, listener)
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onStartDiscoveryFailed(errorCode: Int) {
        registered = false
        // Nothing is discovering any more, so a later start() must try again rather than return
        // at `if (running)`: a pairing dialog reopened after the user granted local network
        // access in Settings would otherwise never find the port (#25). Cleared before the
        // consumer is told, so a consumer that retries from the callback really retries.
        running = false
        if (errorCode == NsdManager.FAILURE_PERMISSION_DENIED) {
            Timber.tag(TAG).w("discovery of $serviceType refused: local network access is not granted")
        }
        onDiscoveryFailed?.invoke(errorCode)
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val hostAddress = resolvedService.host.hostAddress
        val isLocal = hostAddress == "127.0.0.1" || hostAddress == "::1" || resolvedService.host.isLoopbackAddress

        if (running &&
            (
                isLocal ||
                    NetworkInterface
                        .getNetworkInterfaces()
                        .asSequence()
                        .any { networkInterface ->
                            networkInterface.inetAddresses
                                .asSequence()
                                .any { hostAddress == it.hostAddress }
                        }
            ) &&
            isPortAvailable(resolvedService.host, resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            resolvedHost = if (isLocal || hostAddress == null) "127.0.0.1" else hostAddress
            observer.onChanged(resolvedService.port)
        } else if (running && ShizukuSettings.isAutoReconnectMdnsEnabled() && attempts < 5 && !restartScheduled) {
            attempts++
            restartScheduled = true
            val delayMs = attempts * 1000L
            restartJob =
                mdnsScope.launch {
                    delay(delayMs)
                    if (registered) {
                        try {
                            nsdManager.stopServiceDiscovery(listener)
                        } catch (e: IllegalArgumentException) {
                            Timber.tag(TAG).e(e, "listener not registered when restarting service discovery")
                        }
                    }
                    delay(100L)
                    if (!registered) discover()
                    restartScheduled = false
                }
        }
    }

    /**
     * Checks if the ADB service port is actively in use / responding on this device (issue #559).
     *
     * In upstream Shizuku, this only tested binding to `127.0.0.1:port`. On modern Android (15/16)
     * and OEM ROMs (e.g., Honor MagicOS, Xiaomi HyperOS, OnePlus OxygenOS), wireless debugging
     * binds directly to the network interface IP (e.g. wlan0) rather than loopback. A loopback bind
     * succeeds in that case, falsely reporting the port as "not in use" and getting stuck searching forever.
     */
    private fun isPortAvailable(
        host: InetAddress?,
        port: Int,
    ): Boolean {
        // Step 1: If binding to 127.0.0.1 throws IOException (EADDRINUSE), adbd is bound on loopback or 0.0.0.0
        try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
            }
        } catch (_: IOException) {
            return true
        }

        // Step 2: adbd bound to the resolved interface IP only (e.g. wlan0) — the loopback bind
        // above succeeds in that case, so also try binding the exact address mDNS resolved to.
        if (host != null && !host.isLoopbackAddress) {
            try {
                ServerSocket().use {
                    it.bind(InetSocketAddress(host, port), 1)
                }
            } catch (_: IOException) {
                return true
            }
        }

        // Deliberately no active connect() probe: opening a raw TCP connection to adbd's TLS
        // pairing port without completing the handshake can be treated as a failed pairing attempt.
        return false
    }

    internal class DiscoveryListener(
        private val adbMdns: AdbMdns,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Timber.tag(TAG).v("onStartDiscoveryFailed: $serviceType, $errorCode")

            adbMdns.onStartDiscoveryFailed(errorCode)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            Timber.tag(TAG).v("onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(
            nsdServiceInfo: NsdServiceInfo,
            i: Int,
        ) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
