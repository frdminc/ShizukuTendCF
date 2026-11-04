package af.shizuku.manager.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WifiNetworksTest {
    @Test
    fun savedNetworksKeepSpacesAndDropQuotes() {
        val output =
            """
            Network Id      SSID                         Security type
            0            Home Wifi 5G                  wpa2-psk
            1            "Cafe"                        open
            12           2.4 Ghz                       wpa3-sae^wpa2-psk
            """.trimIndent()
        assertEquals(listOf("2.4 Ghz", "Cafe", "Home Wifi 5G"), WifiNetworks.parseSavedNetworks(output))
    }

    @Test
    fun noSavedNetworksIsEmpty() {
        assertEquals(emptyList<String>(), WifiNetworks.parseSavedNetworks("Network Id      SSID                         Security type\n"))
    }

    @Test
    fun statusReadsConnectedName() {
        assertEquals("Home Wifi", WifiNetworks.parseStatus("Wifi is enabled\nWifi is connected to \"Home Wifi\"\nWifiInfo: SSID: \"Home Wifi\", BSSID: x"))
        assertEquals("Office", WifiNetworks.parseStatus("WifiInfo: SSID: \"Office\", BSSID: 02:00:00:00:00:00"))
    }

    @Test
    fun statusWhenNotConnectedIsNull() {
        assertNull(WifiNetworks.parseStatus("Wifi is enabled\nWifi is not connected"))
        assertNull(WifiNetworks.parseStatus("WifiInfo: SSID: \"<unknown ssid>\", BSSID: x"))
    }
}
