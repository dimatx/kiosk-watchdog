package com.shymoose.wifiwatchdog

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.provider.Settings

/**
 * Keeps Bluetooth off on a display that does not use it.
 *
 * Wi-Fi and Bluetooth share one chip and one firmware on this hardware, so
 * anything Bluetooth does is work the Wi-Fi side has to arbitrate around.
 * Continuous BLE scanning is the worst of it, and it is easy to leave on by
 * accident: turning Bluetooth off in the UI does not stop it, because the
 * scanning is governed by a separate location setting. A display in that state
 * reports Bluetooth as off while the radio sits in BLE_ON, scanning forever.
 *
 * Play Services re-enables the adapter on its own - observed doing exactly that
 * minutes after it was switched off - so this is re-asserted on every check
 * rather than set once.
 *
 * Off by default: a display that genuinely uses Bluetooth should keep it.
 */
object BluetoothGuard {

    private const val BLE_SCAN_ALWAYS = "ble_scan_always_enabled"

    @Volatile
    private var reported = false

    fun enforce(context: Context) {
        if (!Prefs(context).keepBluetoothOff) {
            reported = false
            return
        }

        var acted = false

        // The scanning setting is the half that survives the UI toggle, and the
        // half that keeps the radio busy.
        if (AirplaneMode.hasPermission(context)) {
            val scanning = runCatching {
                Settings.Global.getInt(context.contentResolver, BLE_SCAN_ALWAYS, 0)
            }.getOrDefault(0)
            if (scanning != 0) {
                runCatching {
                    Settings.Global.putInt(context.contentResolver, BLE_SCAN_ALWAYS, 0)
                    acted = true
                }
            }
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && adapter.isEnabled) {
            @Suppress("DEPRECATION", "MissingPermission")
            runCatching { adapter.disable() }.onSuccess { acted = true }
        }

        // Said once per occurrence rather than every check: something turning
        // Bluetooth back on repeatedly is worth seeing, a steady state is not.
        if (acted && !reported) {
            reported = true
            EventLog.add(
                context,
                EventLevel.ACTION,
                "Bluetooth had been switched back on — turned it off again"
            )
        } else if (!acted) {
            reported = false
        }
    }
}
