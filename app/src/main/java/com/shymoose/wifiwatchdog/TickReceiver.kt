package com.shymoose.wifiwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm target — wakes the service for the next connectivity check. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs(context).enabled) return
        WatchdogService.start(context)
    }
}
