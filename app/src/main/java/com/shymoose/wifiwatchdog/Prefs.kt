package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Typed accessors over the shared preference store used by the settings screen. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Blank means "follow the default gateway"; anything else pins the probe. */
    val probeHostOverride: String
        get() = sp.getString(KEY_PROBE_HOST, DEFAULT_HOST)!!.trim()

    val probePort: Int
        get() = sp.getString(KEY_PROBE_PORT, DEFAULT_PORT)!!.toIntOrNull()?.coerceIn(1, 65535)
            ?: DEFAULT_PORT.toInt()

    /**
     * Last gateway seen while the link was up. Discovery returns nothing once the
     * route table is torn down, which is precisely when the watchdog needs a target.
     */
    var lastGateway: String
        get() = sp.getString(KEY_LAST_GATEWAY, "")!!
        set(value) = sp.edit().putString(KEY_LAST_GATEWAY, value).apply()

    val checkIntervalSec: Int
        get() = intPref(KEY_INTERVAL, DEFAULT_INTERVAL, 10, 600)

    val reassociateAfterSec: Int
        get() = intPref(KEY_T_REASSOCIATE, DEFAULT_T_REASSOCIATE, 15, 3600)

    val softToggleAfterSec: Int
        get() = intPref(KEY_T_SOFT, DEFAULT_T_SOFT, 30, 7200)

    val hardResetAfterSec: Int
        get() = intPref(KEY_T_HARD, DEFAULT_T_HARD, 60, 14400)

    val hardResetEnabled: Boolean
        get() = sp.getBoolean(KEY_HARD_ENABLED, true)

    val airplaneAfterSec: Int
        get() = intPref(KEY_T_AIRPLANE, DEFAULT_T_AIRPLANE, 120, 21600)

    val airplaneEnabled: Boolean
        get() = sp.getBoolean(KEY_AIRPLANE_ENABLED, true)

    val airplaneDwellSec: Int
        get() = intPref(KEY_AIRPLANE_DWELL, DEFAULT_AIRPLANE_DWELL, 5, 300)

    /**
     * Set while the radios are down. If this is still true at boot or at service
     * start, a cycle was interrupted and airplane mode must be forced back off.
     */
    var airplanePending: Boolean
        get() = sp.getBoolean(KEY_AIRPLANE_PENDING, false)
        set(value) = sp.edit().putBoolean(KEY_AIRPLANE_PENDING, value).commit().let { }

    /** Whatever held the assistant slot before we took it, so it can be handed back. */
    var previousAssistant: String
        get() = sp.getString(KEY_PREV_ASSISTANT, "")!!
        set(value) = sp.edit().putString(KEY_PREV_ASSISTANT, value).apply()

    /** ntfy server root, e.g. https://ntfy.sh or a self-hosted instance. */
    val ntfyUrl: String
        get() = sp.getString(KEY_NTFY_URL, DEFAULT_NTFY_URL)!!.trim()
            .ifEmpty { DEFAULT_NTFY_URL }

    val ntfyTopic: String
        get() = sp.getString(KEY_NTFY_TOPIC, "")!!.trim()

    val ntfyUser: String
        get() = sp.getString(KEY_NTFY_USER, "")!!.trim()

    /** Password for basic auth, or an access token when no username is set. */
    val ntfyPassword: String
        get() = sp.getString(KEY_NTFY_PASSWORD, "")!!

    /** The topic is what makes reporting possible; the server always has a default. */
    val ntfyConfigured: Boolean
        get() = ntfyTopic.isNotEmpty()

    /** Last IPv4 seen while the link was up, for reporting during an outage. */
    var lastIp: String
        get() = sp.getString(KEY_LAST_IP, "")!!
        set(value) = sp.edit().putString(KEY_LAST_IP, value).apply()

    /** Cached once discovered; the hardware address does not change. */
    var lastMac: String
        get() = sp.getString(KEY_LAST_MAC, "")!!
        set(value) = sp.edit().putString(KEY_LAST_MAC, value).apply()

    /** Persisted so a reboot mid-outage does not reset the escalation history. */
    var lastGoodAtMillis: Long
        get() = sp.getLong(KEY_LAST_GOOD, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_GOOD, value).apply()

    /** Full URL hit on a cadence while the link is healthy. Blank disables it. */
    val heartbeatUrl: String
        get() = sp.getString(KEY_HEARTBEAT_URL, "")!!.trim()

    val heartbeatIntervalSec: Int
        get() = intPref(KEY_HEARTBEAT_INTERVAL, DEFAULT_HEARTBEAT_INTERVAL, 30, 86400)

    val heartbeatConfigured: Boolean
        get() = heartbeatUrl.startsWith("http", ignoreCase = true)

    /** Persisted so restarts do not reset the cadence and double up on pings. */
    var heartbeatLastAt: Long
        get() = sp.getLong(KEY_HEARTBEAT_LAST, 0L)
        set(value) = sp.edit().putLong(KEY_HEARTBEAT_LAST, value).apply()

    private fun intPref(key: String, def: Int, min: Int, max: Int): Int =
        (sp.getString(key, def.toString())?.toIntOrNull() ?: def).coerceIn(min, max)

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PROBE_HOST = "probe_host"
        const val KEY_PROBE_PORT = "probe_port"
        const val KEY_INTERVAL = "check_interval_sec"
        const val KEY_T_REASSOCIATE = "t_reassociate_sec"
        const val KEY_T_SOFT = "t_soft_sec"
        const val KEY_T_HARD = "t_hard_sec"
        const val KEY_HARD_ENABLED = "hard_reset_enabled"
        const val KEY_T_AIRPLANE = "t_airplane_sec"
        const val KEY_AIRPLANE_ENABLED = "airplane_enabled"
        const val KEY_AIRPLANE_DWELL = "airplane_dwell_sec"
        const val KEY_NTFY_URL = "ntfy_url"
        const val KEY_NTFY_TOPIC = "ntfy_topic"
        const val KEY_NTFY_USER = "ntfy_user"
        const val KEY_NTFY_PASSWORD = "ntfy_password"
        const val KEY_NTFY_TEST = "ntfy_test"
        const val KEY_HEARTBEAT_URL = "heartbeat_url"
        const val KEY_HEARTBEAT_INTERVAL = "heartbeat_interval_sec"
        const val KEY_HEARTBEAT_TEST = "heartbeat_test"
        private const val KEY_HEARTBEAT_LAST = "heartbeat_last_at"
        private const val KEY_LAST_GOOD = "last_good_at"
        private const val KEY_AIRPLANE_PENDING = "airplane_pending"
        private const val KEY_PREV_ASSISTANT = "previous_assistant"
        private const val KEY_LAST_GATEWAY = "last_gateway"
        private const val KEY_LAST_IP = "last_ip"
        private const val KEY_LAST_MAC = "last_mac"

        /** Empty on purpose: auto-follow the gateway unless the user pins a host. */
        const val DEFAULT_HOST = ""
        /** Matches [NetProbe.GATEWAY_PORT] so a pinned host behaves like the gateway probe. */
        const val DEFAULT_PORT = "53"
        const val DEFAULT_NTFY_URL = "https://ntfy.sh"
        const val DEFAULT_INTERVAL = 20
        const val DEFAULT_T_REASSOCIATE = 60
        const val DEFAULT_T_SOFT = 120
        const val DEFAULT_T_HARD = 240
        const val DEFAULT_T_AIRPLANE = 360
        const val DEFAULT_AIRPLANE_DWELL = 15
        /**
         * Deliberately half of the 300s that an Uptime Kuma push monitor
         * defaults to. A push monitor fails the moment one ping is late, so the
         * sender has to run at a multiple of the monitor's rate to leave slack
         * for a dropped request.
         */
        const val DEFAULT_HEARTBEAT_INTERVAL = 120
    }
}
