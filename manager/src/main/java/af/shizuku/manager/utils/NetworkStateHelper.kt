package af.shizuku.manager.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

object NetworkStateHelper {
    /**
     * Checks if the device is currently connected to an active Wi-Fi or Ethernet network.
     */
    @JvmStatic
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        fun isLan(network: Network?): Boolean {
            val capabilities = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() } ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        // Not only the default network: with mobile data up, a Wi-Fi without internet (a LAN-only
        // network, a captive portal) is connected but not the default, and wireless debugging
        // works over it all the same (WirelessDebugging.wifiNetwork looks the same way).
        if (isLan(runCatching { cm.activeNetwork }.getOrNull())) return true
        @Suppress("DEPRECATION")
        return runCatching { cm.allNetworks.any { isLan(it) } }.getOrDefault(false)
    }

    /**
     * Checks if the device has Mobile Hotspot (Tethering AP) enabled.
     * When hotspot is active on Android, the local Wi-Fi interface (e.g. ap0/wlan0) is up,
     * which satisfies Android's requirement for Wireless Debugging even on 5G/cellular data.
     */
    @JvmStatic
    fun isHotspotEnabled(context: Context): Boolean =
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val method = wm?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            method?.isAccessible = true
            (method?.invoke(wm) as? Boolean) == true
        } catch (_: Exception) {
            false
        }

    /**
     * Returns true if network conditions allow standard Android mDNS Wireless Debugging
     * (either active Wi-Fi connection or local Mobile Hotspot on non-Samsung devices).
     */
    @JvmStatic
    fun isWirelessAdbSupportedNetwork(context: Context): Boolean {
        // On Samsung One UI, Mobile Hotspot disables/blocks Wireless Debugging.
        if (EnvironmentUtils.isSamsung()) {
            return isWifiConnected(context)
        }
        return isWifiConnected(context) || isHotspotEnabled(context)
    }
}
