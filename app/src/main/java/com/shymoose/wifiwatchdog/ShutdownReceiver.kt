package com.shymoose.wifiwatchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Marks a shutdown as deliberate.
 *
 * Without this there is no way to tell a reboot someone asked for from a device
 * that froze: both look identical at the next boot. Android sends this broadcast
 * on its way down, so receiving it means the stop was orderly and nothing needs
 * reporting.
 */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_SHUTDOWN) return
        Prefs(context).cleanShutdown = true
    }
}
