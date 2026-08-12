package com.shymoose.wifiwatchdog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class WifiStatus(
    val wifiEnabled: Boolean,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val linkSpeedMbps: Int?
)

/**
 * "Is the link actually usable" check. A Wi-Fi association alone is not enough —
 * the failure mode we are chasing leaves the device associated but unable to move
 * packets, so the authoritative test is a TCP connect to a known host.
 */
class NetProbe(private val context: Context) {

    private val wifi: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** True when Android believes a Wi-Fi transport is connected. Cheap, but not trusted alone. */
    fun hasWifiTransport(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Opens a TCP socket to [host]:[port]. Blocking — never call on the main thread. */
    fun canReach(host: String, port: Int, timeoutMs: Int = 5_000): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    @Suppress("DEPRECATION")
    fun status(): WifiStatus {
        val enabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val rawSsid = info?.ssid?.trim('"')
        val ssid = rawSsid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
        return WifiStatus(
            wifiEnabled = enabled,
            ssid = ssid,
            bssid = info?.bssid?.takeIf { it != "00:00:00:00:00:00" },
            rssi = info?.rssi?.takeIf { it != -127 && ssid != null },
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 && ssid != null }
        )
    }
}
