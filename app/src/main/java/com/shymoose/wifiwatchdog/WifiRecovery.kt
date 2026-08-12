package com.shymoose.wifiwatchdog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * The recovery ladder.
 *
 * Measured on the ThinkSmart View (LineageOS 15.1 / Android 8.1):
 *
 *  - `setWifiEnabled(false)` with `wifi_scan_always_enabled = 1` (the default)
 *    only reaches `StaDisabledWithScanState`. `wlan0` stays up and
 *    `wpa_supplicant` keeps the same pid — a soft disconnect that does not clear
 *    a wedged driver.
 *  - Setting `wifi_scan_always_enabled = 0` first makes the same call perform a
 *    real teardown: `wlan0` disappears entirely and `wpa_supplicant` is killed
 *    and respawned with a new pid. That is the equivalent of an airplane-mode
 *    cycle for the Wi-Fi radio.
 *
 * `WifiController` observes that setting through a `ContentObserver`, so no
 * protected broadcast is involved and a normal app holding only
 * `WRITE_SECURE_SETTINGS` can drive it.
 */
class WifiRecovery(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifi: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun hasSecureSettingsPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Cheapest rung: ask the supplicant to re-associate with the current AP. */
    @Suppress("DEPRECATION")
    fun reassociate(): Boolean = runCatching {
        if (!wifi.isWifiEnabled) return@runCatching false
        val ok = wifi.reassociate()
        EventLog.add(appContext, EventLevel.ACTION, "Re-associate requested (accepted=$ok)")
        ok
    }.getOrElse {
        EventLog.add(appContext, EventLevel.ERROR, "Re-associate failed: ${it.message}")
        false
    }

    /**
     * Middle rung: plain Wi-Fi off/on. Clears the connection state machine but,
     * with scan-always on, leaves the driver loaded.
     */
    @Suppress("DEPRECATION")
    fun softToggle(): Boolean = runCatching {
        EventLog.add(appContext, EventLevel.ACTION, "Soft Wi-Fi toggle (off -> on)")
        wifi.isWifiEnabled = false
        Thread.sleep(SOFT_OFF_MS)
        val ok = wifi.setWifiEnabled(true)
        if (!ok) EventLog.add(appContext, EventLevel.ERROR, "setWifiEnabled(true) was rejected")
        ok
    }.getOrElse {
        EventLog.add(appContext, EventLevel.ERROR, "Soft toggle failed: ${it.message}")
        false
    }

    /**
     * Top rung: a genuine driver unload and reload. Requires
     * `WRITE_SECURE_SETTINGS`; without it this degrades to [softToggle].
     *
     * The previous value of `wifi_scan_always_enabled` is always restored, even
     * if the toggle throws part-way through.
     */
    @Suppress("DEPRECATION")
    fun hardReset(): Boolean {
        if (!hasSecureSettingsPermission()) {
            EventLog.add(
                appContext,
                EventLevel.WARN,
                "Hard reset unavailable (WRITE_SECURE_SETTINGS not granted) — using soft toggle"
            )
            return softToggle()
        }

        val resolver = appContext.contentResolver
        val previousScanAlways = Settings.Global.getInt(resolver, SCAN_ALWAYS, 1)

        return try {
            EventLog.add(
                appContext,
                EventLevel.ACTION,
                "Hard reset: unloading Wi-Fi driver (scan_always $previousScanAlways -> 0)"
            )
            Settings.Global.putInt(resolver, SCAN_ALWAYS, 0)
            Thread.sleep(SETTLE_MS)

            wifi.isWifiEnabled = false
            Thread.sleep(HARD_OFF_MS)

            // Restore before re-enabling so the radio comes back in its normal mode.
            Settings.Global.putInt(resolver, SCAN_ALWAYS, previousScanAlways)
            Thread.sleep(SETTLE_MS)

            val ok = wifi.setWifiEnabled(true)
            EventLog.add(
                appContext,
                EventLevel.ACTION,
                if (ok) "Hard reset complete — radio re-enabled" else "Hard reset: re-enable was rejected"
            )
            ok
        } catch (t: Throwable) {
            EventLog.add(appContext, EventLevel.ERROR, "Hard reset failed: ${t.message}")
            false
        } finally {
            // Belt and braces: never leave scan-always turned off.
            runCatching { Settings.Global.putInt(resolver, SCAN_ALWAYS, previousScanAlways) }
        }
    }

    /**
     * Last rung: a real airplane-mode cycle, driven through the assistant proxy.
     * See [AirplaneMode]. Falls back to [hardReset] when unavailable.
     */
    fun airplaneCycle(): Boolean {
        if (!AirplaneMode.isAvailable(appContext)) {
            EventLog.add(
                appContext,
                EventLevel.WARN,
                "Airplane cycle unavailable — falling back to hard reset"
            )
            return hardReset()
        }
        val dwellMs = Prefs(appContext).airplaneDwellSec * 1000L
        return AirplaneMode.cycle(appContext, dwellMs)
    }

    companion object {
        private const val SCAN_ALWAYS = "wifi_scan_always_enabled"
        private const val SOFT_OFF_MS = 3_000L
        private const val HARD_OFF_MS = 8_000L
        private const val SETTLE_MS = 1_500L
    }
}
