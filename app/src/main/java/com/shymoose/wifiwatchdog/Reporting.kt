package com.shymoose.wifiwatchdog

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped lanes avoid overlapping reporters when Android recreates the service. */
internal object Reporting {
    private val notifications = ReportingLane("watchdog-ntfy")
    private val heartbeats = ReportingLane("watchdog-heartbeat")
    private val online = AtomicBoolean(false)

    fun updateConnectivity(reachable: Boolean) {
        online.set(reachable)
    }

    fun onHealthyProbe(context: Context, rttMs: Long) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.ntfyConfigured) notifications.submit {
            withWakeLock(app) {
                Ntfy.flush(app, canSend = { online.get() && Prefs(app).enabled })
            }
        }
        if (prefs.heartbeatConfigured) heartbeats.submit {
            withWakeLock(app) {
                val prefs = Prefs(app)
                if (online.get() && prefs.enabled) Heartbeat.maybePing(app, prefs, rttMs)
            }
        }
    }

    fun testNotification(context: Context) {
        val app = context.applicationContext
        if (!notifications.submit(manual = true) { withWakeLock(app) { Ntfy.flush(app, force = true) } }) {
            EventLog.add(app, EventLevel.WARN, "Notification delivery is busy; the test remains in the outbox")
        }
    }

    fun testHeartbeat(context: Context) {
        val app = context.applicationContext
        if (!heartbeats.submit(manual = true) { withWakeLock(app) { Heartbeat.pingNow(app, Prefs(app), 0) } }) {
            EventLog.add(app, EventLevel.WARN, "Heartbeat delivery is busy; test not queued, try again shortly")
        }
    }

    private inline fun withWakeLock(context: Context, block: () -> Unit) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiWatchdog::reporting")
        try {
            wake.acquire(Ntfy.FLUSH_BUDGET_MS + 5_000L)
            block()
        } finally {
            if (wake.isHeld) wake.release()
        }
    }
}
