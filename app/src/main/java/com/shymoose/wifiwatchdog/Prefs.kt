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

    val probeHost: String
        get() = sp.getString(KEY_PROBE_HOST, DEFAULT_HOST)!!.trim().ifEmpty { DEFAULT_HOST }

    val probePort: Int
        get() = sp.getString(KEY_PROBE_PORT, DEFAULT_PORT)!!.toIntOrNull()?.coerceIn(1, 65535)
            ?: DEFAULT_PORT.toInt()

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

    val webhookUrl: String
        get() = sp.getString(KEY_WEBHOOK, "")!!.trim()

    /** Persisted so a reboot mid-outage does not reset the escalation history. */
    var lastGoodAtMillis: Long
        get() = sp.getLong(KEY_LAST_GOOD, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_GOOD, value).apply()

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
        const val KEY_WEBHOOK = "webhook_url"
        private const val KEY_LAST_GOOD = "last_good_at"
        private const val KEY_AIRPLANE_PENDING = "airplane_pending"
        private const val KEY_PREV_ASSISTANT = "previous_assistant"

        const val DEFAULT_HOST = "192.168.27.40"
        const val DEFAULT_PORT = "8123"
        const val DEFAULT_INTERVAL = 20
        const val DEFAULT_T_REASSOCIATE = 60
        const val DEFAULT_T_SOFT = 120
        const val DEFAULT_T_HARD = 240
        const val DEFAULT_T_AIRPLANE = 360
        const val DEFAULT_AIRPLANE_DWELL = 15
    }
}
