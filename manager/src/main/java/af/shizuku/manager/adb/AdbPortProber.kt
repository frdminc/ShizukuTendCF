package af.shizuku.manager.adb

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object AdbPortProber {
    /**
     * Resolves all candidate network host addresses to attempt when connecting to ADB or pairing:
     * 1. [preferredHost] (e.g. from mDNS resolution or intent extra)
     * 2. Loopback addresses: 127.0.0.1 and ::1
     * 3. Local network interface addresses (e.g. wlan0)
     */
    fun getCandidateHosts(preferredHost: String = "127.0.0.1"): List<String> {
        val ipv4Hosts = LinkedHashSet<String>()
        val ipv6Hosts = LinkedHashSet<String>()

        val cleanPreferred = preferredHost.substringBefore('%').trim()
        if (cleanPreferred.isNotBlank()) {
            if (cleanPreferred.contains(':')) {
                ipv6Hosts.add(cleanPreferred)
            } else {
                ipv4Hosts.add(cleanPreferred)
            }
        }
        ipv4Hosts.add("127.0.0.1")
        ipv6Hosts.add("::1")

        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    val raw = addr.hostAddress ?: continue
                    val clean = raw.substringBefore('%')
                    if (clean.isNotBlank()) {
                        if (clean.contains(':')) {
                            ipv6Hosts.add(clean)
                        } else {
                            ipv4Hosts.add(clean)
                        }
                    }
                }
            }
        }
        return (ipv4Hosts + ipv6Hosts).toList()
    }

    /**
     * Rapidly probes whether a given port is actively accepting TCP connections on [host].
     * Uses a short timeout (150ms default) to ensure UI responsiveness.
     * When host is "127.0.0.1", also tries "::1" (IPv6 loopback) if IPv4 connection is refused.
     */
    fun isPortOpen(
        port: Int,
        timeoutMs: Int = 150,
        host: String = "127.0.0.1",
    ): Boolean {
        if (port !in 1..65535) return false
        val cleanHost = host.substringBefore('%')
        if (probeAddress(cleanHost, port, timeoutMs)) return true
        if (cleanHost == "127.0.0.1") {
            return probeAddress("::1", port, timeoutMs)
        }
        return false
    }

    private fun probeAddress(
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }

    /**
     * The first of [ports] that accepts a loopback connection, or -1. Safe on the main thread (a
     * broadcast receiver's): the connects run on a thread of their own, since a socket opened on
     * the main thread throws NetworkOnMainThreadException in a release build.
     */
    fun firstListening(
        ports: Iterable<Int>,
        timeoutMs: Int = 150,
    ): Int {
        val candidates = ports.filter { it in 1..65535 }.distinct()
        if (candidates.isEmpty()) return -1
        val probe = FutureTask { candidates.firstOrNull { isPortOpen(it, timeoutMs) } ?: -1 }
        Thread(probe, "AdbPortProber").apply { isDaemon = true }.start()
        return try {
            probe.get(timeoutMs.toLong() * candidates.size + 500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Checks candidate ports on loopback (127.0.0.1 / ::1) in order of likelihood:
     * 1. EnvironmentUtils.getAdbTcpPort() (Active system-reported port from service.adb.tcp.port)
     * 2. 5555 (Standard ADB TCP/IP port - works offline / over 5G)
     * 3. ShizukuSettings.getTcpPort() (Configured custom TCP port)
     * 4. ShizukuSettings.getLastPort() (Previously successful session port)
     *
     * When adbd reports its port (1), only that port is probed. Otherwise the remaining candidates
     * are probed in parallel for minimal latency and the highest-priority open port is returned,
     * or -1 if none (or when the loopback probe is switched off in settings).
     */
    suspend fun findActiveLoopbackPort(context: Context? = null): Int =
        withContext(Dispatchers.IO) {
            if (!ShizukuSettings.isAdbLoopbackProbeEnabled()) return@withContext -1

            // When adbd reports the TCP port it is listening on, use only that. A fixed candidate
            // such as 5555 could be held by another app while adbd listens elsewhere, and that app
            // could relay adbd's auth challenge to this app's authorised key.
            val reported = EnvironmentUtils.getAdbTcpPort()
            if (reported in 1..65535) {
                return@withContext if (isPortOpen(reported, 150)) reported else -1
            }

            val candidates = LinkedHashSet<Int>()
            candidates.add(5555)
            val tcpPort = ShizukuSettings.getTcpPort()
            if (tcpPort in 1..65535) {
                candidates.add(tcpPort)
            }
            val lastPort = ShizukuSettings.getLastPort()
            if (lastPort in 1..65535) {
                candidates.add(lastPort)
            }

            coroutineScope {
                candidates
                    .map { port ->
                        async { port to isPortOpen(port, 150) }
                    }.awaitAll()
            }.firstOrNull { it.second }?.first ?: -1
        }
}
