package com.shymoose.wifiwatchdog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

data class WifiStatus(
    val wifiEnabled: Boolean,
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val linkSpeedMbps: Int?,
    /**
     * What the supplicant is doing.
     *
     * The one signal that separates "trying to join and failing" from "not
     * trying at all", which is the difference between a network the framework
     * has temporarily given up on and a radio that cannot see the access point.
     * Nothing else exposed to an app distinguishes those two.
     */
    val supplicant: String? = null,

    /**
     * How many access points the radio can currently see, or -1 when that cannot
     * be read.
     *
     * Zero is the strongest evidence available that the radio itself has failed
     * rather than the network. These displays sit permanently within range of
     * several access points, so an empty scan list while Wi-Fi is enabled is not
     * something a healthy radio produces - and it has been seen on a display
     * whose Wi-Fi picker showed no networks at all while everything around it was
     * still connected.
     */
    val scanCount: Int = -1
)

/** Where the reachability check is pointed, and why. */
data class ProbeTarget(val host: String, val port: Int, val source: Source) {
    enum class Source { GATEWAY, LAST_GATEWAY, CONFIGURED }

    override fun toString(): String = "$host:$port"
}

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

    /**
     * The default-route gateway of the active network, or null when there is no
     * usable route. Preferred over any fixed address: it follows the device onto
     * whatever network it joins and is the nearest host that can answer, so a
     * failure means the link itself is dead rather than some remote service.
     *
     * IPv4 only, deliberately. A dual-stack network publishes a default route for
     * each family, and the routes are not returned in any guaranteed order, so
     * taking the first one is a coin toss. The IPv6 default gateway is normally
     * the router's link-local address, which cannot be reached without also
     * carrying the interface zone - so picking it makes every probe fail and the
     * watchdog tears down a healthy radio, over and over. That is exactly what
     * happened on the first dual-stack network one of these displays met.
     *
     * The probe only has to answer "is the link carrying traffic", and the IPv4
     * gateway answers it on every network these run on.
     */
    fun gateway(): String? {
        runCatching {
            val network = connectivity.activeNetwork ?: return@runCatching null
            connectivity.getLinkProperties(network)
                ?.routes
                ?.asSequence()
                ?.filter { it.isDefaultRoute }
                ?.mapNotNull { it.gateway }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
                ?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        }.getOrNull()?.let { return it }

        // DHCP lease. Deprecated, but it survives cases where the route table has
        // already been torn down mid-failure.
        @Suppress("DEPRECATION")
        return runCatching {
            val raw = wifi.dhcpInfo?.gateway ?: 0
            if (raw == 0) null else formatIpv4(raw)
        }.getOrNull()
    }

    /**
     * True when packets provably reached [target]. ICMP is tried first because the
     * gateway rarely listens on a TCP port; a TCP connect is the backup. A refused
     * connection counts as success — the peer answered, which is the whole question.
     */
    /**
     * Whether to try the TCP connect before the ping.
     *
     * A ping means forking `/system/bin/ping` — measured at 56 ms on this
     * hardware, which is over half the cost of an entire check, paid every
     * twenty seconds forever. A TCP connect to a gateway one hop away is a
     * couple of syscalls and returns in milliseconds, so it is tried first.
     *
     * Not assumed, though: on a network that silently drops the probe port the
     * connect would burn its whole timeout before falling back, which is worse
     * than the fork. So the order is remembered and flipped whenever the
     * preferred method fails and the other one works. A device settles on
     * whichever is actually cheaper for its network within one check.
     */
    @Volatile
    private var preferTcp = true

    fun canReach(target: ProbeTarget, timeoutMs: Int = 5_000): Boolean {
        // Short, because the gateway is one hop away and this is only the first
        // attempt — anything slower is not worth waiting for when a fallback
        // exists.
        val firstTry = if (preferTcp) FAST_TIMEOUT_MS.coerceAtMost(timeoutMs) else timeoutMs

        if (preferTcp) {
            if (tcpReach(target.host, target.port, firstTry)) return true
            if (!ping(target.host, timeoutMs)) return false
            preferTcp = false
            return true
        }

        if (ping(target.host, firstTry)) return true
        if (!tcpReach(target.host, target.port, timeoutMs)) return false
        preferTcp = true
        return true
    }

    private fun ping(host: String, timeoutMs: Int): Boolean = try {
        val waitSec = (timeoutMs / 1000).coerceIn(1, 10)
        val process = ProcessBuilder(PING, "-n", "-c", "1", "-W", waitSec.toString(), host)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(waitSec.toLong() + 2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (e: IOException) {
        false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (e: SecurityException) {
        false
    }

    private fun tcpReach(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (e: ConnectException) {
        // "Connection refused" means the packet made the round trip.
        e.message?.contains("refused", ignoreCase = true) == true
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun formatIpv4(raw: Int): String =
        "${raw and 0xFF}.${raw shr 8 and 0xFF}.${raw shr 16 and 0xFF}.${raw shr 24 and 0xFF}"

    /**
     * Chooses what to probe. The live gateway wins; if discovery fails — which is
     * exactly what happens once the link drops — the last gateway seen is reused so
     * the watchdog keeps a meaningful target while offline. A host pinned in
     * settings overrides both. Null means no target is known yet.
     */
    fun resolveTarget(prefs: Prefs): ProbeTarget? {
        val configured = prefs.probeHostOverride
        if (configured.isNotEmpty()) {
            return ProbeTarget(configured, prefs.probePort, ProbeTarget.Source.CONFIGURED)
        }
        gateway()?.let { gw ->
            if (gw != prefs.lastGateway) prefs.lastGateway = gw
            return ProbeTarget(gw, GATEWAY_PORT, ProbeTarget.Source.GATEWAY)
        }
        return prefs.lastGateway
            // A build before this one could have stored an IPv6 gateway here, and
            // it would keep being used long after discovery stopped returning it.
            .takeIf { it.isNotEmpty() && !it.contains(':') }
            ?.let { ProbeTarget(it, GATEWAY_PORT, ProbeTarget.Source.LAST_GATEWAY) }
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
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 && ssid != null },
            supplicant = info?.supplicantState?.name,
            scanCount = if (enabled) scanCount() else -1
        )
    }

    /**
     * The size of the radio's current scan list, or -1 when it cannot be read.
     *
     * Needs a location permission and the location providers switched on, which
     * this app has; a refusal comes back as an exception and is reported as
     * unknown rather than as zero, so a permission problem can never be mistaken
     * for a blind radio.
     */
    private fun scanCount(): Int = runCatching { wifi.scanResults?.size ?: -1 }.getOrDefault(-1)

    /**
     * Asks for a fresh scan.
     *
     * The list is otherwise a cache of the last successful scan, and a stale
     * cache would mask exactly the failure being looked for. Android 8.1 does not
     * throttle this - the four-per-two-minutes limit arrived in Android 9 - so it
     * can be asked for on every check while the link is down.
     */
    fun requestScan(): Boolean =
        runCatching { wifi.isWifiEnabled && wifi.startScan() }.getOrDefault(false)

    companion object {
        private const val PING = "/system/bin/ping"

        /** TCP backup port for a gateway. Routers answer DNS far more often than HTTP. */
        const val GATEWAY_PORT = 53

        /**
         * Timeout for the first of the two reachability attempts.
         *
         * The target is the default gateway, so a healthy answer arrives in
         * single-digit milliseconds. This only needs to be long enough to not
         * misjudge a momentarily busy link, and short enough that falling back to
         * the other method is cheaper than waiting.
         */
        private const val FAST_TIMEOUT_MS = 1_500
    }
}
