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
        if (action == Intent.ACTION_BOOT_COMPLETED) reportUncleanStop(app, prefs)
        WatchdogService.start(context)
    }

    /**
     * Reports a device that stopped without shutting down.
     *
     * A hang leaves nothing behind to read: the event log loses its unflushed
     * writes, the system records no crash, and adb over the network does not
     * survive the power cycle needed to recover. The last liveness stamp is
     * written synchronously on every check precisely so this moment can be
     * reconstructed afterwards, and it is worth a notification because by the
     * time anyone looks at the display the evidence is gone.
     */
    private fun reportUncleanStop(app: Context, prefs: Prefs) {
        val lastAlive = prefs.lastAliveAtMillis
        val wasClean = prefs.cleanShutdown
        // Re-arm for this boot before any early return, so the next stop is judged
        // on its own evidence.
        prefs.cleanShutdown = false
        if (wasClean || lastAlive <= 0L) return

        val goneForSec = (System.currentTimeMillis() - lastAlive) / 1000
        // A normal reboot is quick; only flag a gap long enough to mean the
        // device was sitting there doing nothing.
        if (goneForSec < UNCLEAN_STOP_THRESHOLD_SEC) return

        val stoppedAt = LogEvent(lastAlive, EventLevel.WARN, "").formattedTime()
        val downFor = WatchdogService.formatDuration(goneForSec)
        EventLog.add(
            app,
            EventLevel.WARN,
            "Device stopped responding at $stoppedAt and was down for $downFor — " +
                "it did not shut down cleanly"
        )
        Ntfy.enqueue(
            app,
            Ntfy.Message(
                title = "Display stopped responding",
                body = "${DeviceIdentity.hostname(app)} was last alive at $stoppedAt and was " +
                    "down for $downFor. It did not shut down cleanly, so nothing was " +
                    "recorded on the device itself.",
                priority = 4,
                tags = "warning"
            )
        )
    }

    private companion object {
        /** Long enough that a deliberate reboot is not reported as a freeze. */
        const val UNCLEAN_STOP_THRESHOLD_SEC = 300L
    }
}
