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
class WifiRecovery internal constructor(
    private val context: Context,
    private val sleep: (Long) -> Unit
) {

    constructor(context: Context) : this(context, Thread::sleep)

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
    fun softToggle(): Boolean {
        if (!WifiPower.prepareForReset(appContext)) return false
        var restored = false
        return try {
            EventLog.add(appContext, EventLevel.ACTION, "Soft Wi-Fi toggle (off -> on)")
            try {
                if (!WifiPower.disable(appContext)) return false
                sleep(SOFT_OFF_MS)
            } finally {
                restored = restoreWifi()
            }
            restored
        } catch (e: InterruptedException) {
            try {
                EventLog.add(appContext, EventLevel.WARN, "Soft toggle interrupted; Wi-Fi restored=$restored")
            } finally {
                Thread.currentThread().interrupt()
            }
            false
        } catch (e: SecurityException) {
            EventLog.add(appContext, EventLevel.ERROR, "Soft toggle failed: ${e.message}")
            false
        }
    }

    /**
     * Top rung: a genuine driver unload and reload. Requires
     * `WRITE_SECURE_SETTINGS`; without it this degrades to [softToggle].
     *
     * The previous value of `wifi_scan_always_enabled` is always restored, even
     * if the toggle throws part-way through.
     */
    fun hardReset(): Boolean {
        if (!Prefs(appContext).hardResetEnabled) {
            EventLog.add(appContext, EventLevel.INFO, "Hard reset disabled — using soft toggle")
            return softToggle()
        }
        if (!hasSecureSettingsPermission()) {
            EventLog.add(
                appContext,
                EventLevel.WARN,
                "Hard reset unavailable (WRITE_SECURE_SETTINGS not granted) — using soft toggle"
            )
            return softToggle()
        }
        if (!WifiPower.prepareForReset(appContext)) return false

        val resolver = appContext.contentResolver
        val prefs = Prefs(appContext)

        // Never read the live value as the restore target. This app sets it to
        // zero for the duration of the reset, so a reset that follows an
        // interrupted one would read its own leftover and make it permanent.
        // The remembered value survives a kill; with nothing remembered, the
        // platform default is the right answer - a wall display has no reason to
        // have background scanning switched off by hand.
        val restoreTo = prefs.scanAlwaysRestore.takeIf { it >= 0 }
            ?: Settings.Global.getInt(resolver, SCAN_ALWAYS, 1).takeIf { it == 1 }
            ?: 1

        var scanRestored = false
        var wifiRestored = false
        return try {
            EventLog.add(
                appContext,
                EventLevel.ACTION,
                "Hard reset: unloading Wi-Fi driver (scan_always -> 0, will restore $restoreTo)"
            )
            prefs.scanAlwaysRestore = restoreTo
            try {
                if (!writeScanAlways(0)) return false
                sleep(SETTLE_MS)

                if (!WifiPower.disable(appContext)) return false
                sleep(HARD_OFF_MS)

                // Restore before re-enabling so the radio comes back in its normal mode.
                scanRestored = writeScanAlways(restoreTo)
                if (scanRestored) sleep(SETTLE_MS)
            } finally {
                try {
                    if (!scanRestored) scanRestored = writeScanAlways(restoreTo)
                    if (scanRestored) prefs.scanAlwaysRestore = -1
                } finally {
                    // Stopping the service interrupts sleeps, but must never
                    // interrupt the obligation to turn the radio back on.
                    wifiRestored = restoreWifi()
                }
            }
            val ok = scanRestored && wifiRestored
            EventLog.add(
                appContext,
                if (ok) EventLevel.ACTION else EventLevel.ERROR,
                if (ok) "Hard reset complete — radio re-enabled" else "Hard reset: restoration incomplete"
            )
            ok
        } catch (e: InterruptedException) {
            try {
                EventLog.add(appContext, EventLevel.WARN, "Hard reset interrupted; Wi-Fi restored=$wifiRestored")
            } finally {
                Thread.currentThread().interrupt()
            }
            false
        } catch (e: SecurityException) {
            EventLog.add(appContext, EventLevel.ERROR, "Hard reset failed: ${e.message}")
            false
        }
    }

    private fun restoreWifi(): Boolean {
        var interrupted = Thread.interrupted()
        var restored = false
        try {
            restored = WifiPower.enable(appContext)
            return restored
        } catch (e: InterruptedException) {
            interrupted = true
            EventLog.add(appContext, EventLevel.WARN, "Wi-Fi restoration interrupted — will retry")
            return false
        } catch (e: SecurityException) {
            EventLog.add(appContext, EventLevel.ERROR, "Could not restore Wi-Fi: ${e.message}")
            return false
        } finally {
            try {
                if (!restored) AirplaneMode.scheduleRestore(appContext)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun writeScanAlways(value: Int): Boolean = try {
        val ok = Settings.Global.putInt(appContext.contentResolver, SCAN_ALWAYS, value)
        if (!ok) EventLog.add(appContext, EventLevel.ERROR, "Could not set background Wi-Fi scanning to $value")
        ok
    } catch (e: SecurityException) {
        EventLog.add(appContext, EventLevel.ERROR, "Could not set background Wi-Fi scanning: ${e.message}")
        false
    }

    /**
     * Puts `wifi_scan_always_enabled` back if a reset was interrupted before it
     * could, and repairs a display that an earlier version already left switched
     * off.
     *
     * Called on start rather than only after a reset, because the run that has to
     * clean up is by definition not the run that broke it.
     */
    fun restoreScanAlways() {
        val resolver = appContext.contentResolver
        val prefs = Prefs(appContext)
        val pending = prefs.scanAlwaysRestore
        val current = runCatching { Settings.Global.getInt(resolver, SCAN_ALWAYS, 1) }.getOrNull() ?: return

        val wanted = when {
            // A reset did not finish. Its remembered target is authoritative.
            pending >= 0 -> pending
            // Nothing in flight and the setting is off. Earlier builds read the
            // restore value live and could make their own zero permanent, so this
            // heals a display that was left that way.
            current == 0 -> 1
            else -> return
        }
        if (current == wanted) {
            if (pending >= 0) prefs.scanAlwaysRestore = -1
            return
        }
        if (!hasSecureSettingsPermission()) return

        val ok = writeScanAlways(wanted)
        if (ok) prefs.scanAlwaysRestore = -1
        EventLog.add(
            appContext,
            if (ok) EventLevel.ACTION else EventLevel.ERROR,
            if (ok) "Background Wi-Fi scanning was left switched off — turned it back on"
            else "Could not turn background Wi-Fi scanning back on"
        )
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
        if (AirplaneMode.cycle(appContext, dwellMs)) return true
        if (Thread.currentThread().isInterrupted) {
            EventLog.add(appContext, EventLevel.WARN, "Airplane cycle interrupted — skipping further recovery")
            return false
        }

        // The ladder has already counted this rung as taken, so returning here
        // would mean the heaviest step did nothing at all and the device simply
        // waited out the backoff. A cycle can fail for reasons that do not stop
        // the driver being reloaded, so do that rather than nothing.
        EventLog.add(
            appContext,
            EventLevel.WARN,
            "Airplane cycle did not complete — falling back to hard reset"
        )
        return hardReset()
    }

    companion object {
        private const val SCAN_ALWAYS = "wifi_scan_always_enabled"
        private const val SOFT_OFF_MS = 3_000L
        private const val HARD_OFF_MS = 8_000L
        private const val SETTLE_MS = 1_500L
    }
}
