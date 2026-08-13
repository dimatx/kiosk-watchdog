package com.shymoose.wifiwatchdog

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Who is reporting. Several of these displays can publish to the same ntfy topic,
 * and the interesting events all happen while the link is down — when the IP and
 * MAC are no longer readable — so whatever is discovered while the link is up is
 * cached and reused.
 */
object DeviceIdentity {

    private const val ANONYMISED_MAC = "02:00:00:00:00:00"

    data class Snapshot(
        val hostname: String,
        val ip: String?,
        val mac: String?,
        /** True when ip/mac came from the cache rather than the live interface. */
        val stale: Boolean
    ) {
        /** Single line for a notification header, e.g. "starfire · 192.168.27.227 · 94:08:…". */
        fun oneLine(): String {
            val parts = mutableListOf(hostname)
            ip?.let { parts.add(it) }
            mac?.let { parts.add(it) }
            val joined = parts.joinToString(" · ")
            return if (stale && (ip != null || mac != null)) "$joined (last known)" else joined
        }
    }

    /**
     * Reads the current identity, refreshing the cache when the link is up and
     * falling back to the cached values when it is not.
     */
    fun snapshot(context: Context): Snapshot {
        val prefs = Prefs(context)
        val liveIp = currentIpv4(context)
        val liveMac = currentMac(context)

        if (liveIp != null) prefs.lastIp = liveIp
        if (liveMac != null) prefs.lastMac = liveMac

        val ip = liveIp ?: prefs.lastIp.ifEmpty { null }
        val mac = liveMac ?: prefs.lastMac.ifEmpty { null }
        return Snapshot(
            hostname = hostname(context),
            ip = ip,
            mac = mac,
            stale = liveIp == null && ip != null
        )
    }

    fun hostname(context: Context): String {
        val name = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "Android")
    }

    /** Prefers the interface address; the DHCP lease is the fallback. */
    private fun currentIpv4(context: Context): String? {
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()?.let { return it }

        @Suppress("DEPRECATION")
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val raw = wifi.connectionInfo?.ipAddress ?: 0
            if (raw == 0) null else formatIpv4(raw)
        }.getOrNull()
    }

    /**
     * Android returns a placeholder MAC to unprivileged apps, so sysfs and the
     * interface itself are tried first and the placeholder is rejected outright.
     */
    private fun currentMac(context: Context): String? {
        for (name in listOf("wlan0", "eth0")) {
            runCatching { File("/sys/class/net/$name/address").readText().trim() }
                .getOrNull()
                ?.let { if (isRealMac(it)) return it.lowercase() }
        }

        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .mapNotNull { iface -> iface.hardwareAddress?.let { format(it) } }
                .firstOrNull { isRealMac(it) }
        }.getOrNull()?.let { return it.lowercase() }

        @Suppress("DEPRECATION")
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.connectionInfo?.macAddress?.takeIf { isRealMac(it) }?.lowercase()
        }.getOrNull()
    }

    private fun isRealMac(value: String): Boolean =
        value.length == 17 &&
            value.contains(':') &&
            !value.equals(ANONYMISED_MAC, ignoreCase = true) &&
            value.any { it != '0' && it != ':' }

    private fun format(bytes: ByteArray): String =
        bytes.joinToString(":") { String.format("%02x", it) }

    private fun formatIpv4(raw: Int): String =
        listOf(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff)
            .joinToString(".")
}
