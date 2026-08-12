package com.shymoose.wifiwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        val app = context.applicationContext

        // If we were interrupted mid-cycle the radios may still be off, and
        // nothing else is going to turn them back on.
        if (prefs.airplanePending || AirplaneMode.isOn(app)) {
            val pending = goAsync()
            Thread {
                try {
                    EventLog.add(app, EventLevel.WARN, "Airplane mode was still on at boot — clearing")
                    AirplaneMode.ensureOff(app)
                } finally {
                    pending.finish()
                }
            }.start()
        }

        if (!prefs.enabled) return
        EventLog.add(context, EventLevel.INFO, "Auto-start after $action")
        WatchdogService.start(context)
    }
}
