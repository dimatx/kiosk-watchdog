package com.shymoose.wifiwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Last line of defence. Armed before every airplane cycle and cancelled when it
 * finishes normally, so it only ever fires if the app was killed while the
 * radios were down. Without it, a crash at the wrong moment would leave the
 * device offline with no way back in.
 */
class AirplaneRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "airplane-restore") {
            try {
                EventLog.add(app, EventLevel.WARN, "Airplane failsafe fired")
                AirplaneMode.ensureOff(app)
                if (Prefs(app).enabled) WatchdogService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
