package com.shymoose.wifiwatchdog

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that probes connectivity on a schedule and walks the
 * recovery ladder when the link stops passing traffic.
 */
class WatchdogService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private lateinit var prefs: Prefs
    private lateinit var probe: NetProbe
    private lateinit var recovery: WifiRecovery
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        probe = NetProbe(this)
        recovery = WifiRecovery(this)
        createChannel()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(State.summary, State.detail))

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                EventLog.add(this, EventLevel.INFO, "Watchdog stopped")
                cancelAlarm(this)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FORCE_HARD_RESET -> runOnWorker(coalesce = false) { forceHardReset() }

            ACTION_FORCE_AIRPLANE -> runOnWorker(coalesce = false, wakeMs = WAKE_TIMEOUT_LONG_MS) {
                forceAirplaneCycle()
            }

            else -> {
                if (State.startedAt == 0L) {
                    State.startedAt = System.currentTimeMillis()
                    EventLog.add(this, EventLevel.INFO, "Watchdog started")
                }
                // A reboot or crash during an airplane cycle would otherwise leave
                // the radios down with nothing left running to bring them back.
                if (prefs.airplanePending || AirplaneMode.isOn(this)) {
                    runOnWorker(coalesce = false, wakeMs = WAKE_TIMEOUT_LONG_MS) {
                        AirplaneMode.ensureOff(this)
                    }
                }
                runOnWorker { tick() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- ticking

    /**
     * Scheduled probes coalesce: if one is already running there is no point
     * queueing another. Anything the user asked for explicitly must never be
     * dropped, so it queues on the single-thread executor instead — which
     * already serialises work — and runs as soon as the current job finishes.
     */
    private fun runOnWorker(
        coalesce: Boolean = true,
        wakeMs: Long = WAKE_TIMEOUT_MS,
        block: () -> Unit
    ) {
        val claimed = busy.compareAndSet(false, true)
        if (!claimed && coalesce) return

        worker.execute {
            // Forced work may have queued behind a probe; take the flag now.
            if (!claimed) busy.set(true)
            val wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiWatchdog::tick")
            wake.acquire(wakeMs)
            try {
                block()
            } catch (t: Throwable) {
                EventLog.add(this, EventLevel.ERROR, "Task failed: ${t.message}")
            } finally {
                busy.set(false)
                runCatching { if (wake.isHeld) wake.release() }
                scheduleNext()
                updateNotification()
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (prefs.lastGoodAtMillis == 0L) prefs.lastGoodAtMillis = now

        val host = prefs.probeHost
        val port = prefs.probePort
        val reachable = probe.canReach(host, port)
        State.lastCheckAt = now
        State.wifi = probe.status()
        State.online = reachable

        if (reachable) {
            if (State.stage > 0 || State.consecutiveFailures > 0) {
                val downFor = (now - prefs.lastGoodAtMillis) / 1000
                EventLog.add(this, EventLevel.INFO, "Connectivity restored after ${formatDuration(downFor)}")
                report("recovered", downFor)
            }
            prefs.lastGoodAtMillis = now
            State.consecutiveFailures = 0
            State.stage = 0
            State.backoffSec = INITIAL_BACKOFF_SEC
            State.nextHardResetAt = 0L
            State.summary = getString(R.string.status_online)
            State.detail = getString(R.string.status_detail_online, host, port)
            return
        }

        State.consecutiveFailures++
        val downSec = (now - prefs.lastGoodAtMillis) / 1000
        State.summary = getString(R.string.status_offline)
        State.detail = getString(R.string.status_detail_offline, formatDuration(downSec))

        if (State.consecutiveFailures == 1) {
            EventLog.add(this, EventLevel.WARN, "Cannot reach $host:$port")
            report("lost", 0)
        }

        escalate(downSec, now)
    }

    private fun escalate(downSec: Long, now: Long) {
        when (State.stage) {
            0 -> if (downSec >= prefs.reassociateAfterSec) {
                recovery.reassociate()
                State.stage = 1
            }

            1 -> if (downSec >= prefs.softToggleAfterSec) {
                recovery.softToggle()
                State.stage = 2
            }

            2 -> if (downSec >= prefs.hardResetAfterSec) {
                if (prefs.hardResetEnabled) recovery.hardReset() else recovery.softToggle()
                State.stage = 3
                report("hard_reset", downSec)
            }

            3 -> if (downSec >= prefs.airplaneAfterSec) {
                // The heaviest rung: a real airplane-mode cycle, which is what has
                // actually brought this device back when nothing else did.
                if (airplaneUsable()) recovery.airplaneCycle() else lastResortReset()
                State.stage = 4
                State.backoffSec = INITIAL_BACKOFF_SEC
                State.nextHardResetAt = now + State.backoffSec * 1000L
                report("airplane_cycle", downSec)
            }

            else -> if (now >= State.nextHardResetAt) {
                if (airplaneUsable()) recovery.airplaneCycle() else lastResortReset()
                State.backoffSec = (State.backoffSec * 2).coerceAtMost(MAX_BACKOFF_SEC)
                State.nextHardResetAt = now + State.backoffSec * 1000L
                EventLog.add(
                    this,
                    EventLevel.INFO,
                    "Still down — next recovery attempt in ${formatDuration(State.backoffSec.toLong())}"
                )
                report("airplane_cycle", downSec)
            }
        }
    }

    private fun airplaneUsable(): Boolean = prefs.airplaneEnabled && AirplaneMode.isAvailable(this)

    private fun lastResortReset() {
        if (prefs.hardResetEnabled) recovery.hardReset() else recovery.softToggle()
    }

    private fun forceHardReset() {
        EventLog.add(this, EventLevel.ACTION, "Manual recovery triggered")
        if (prefs.hardResetEnabled) recovery.hardReset() else recovery.softToggle()
    }

    private fun forceAirplaneCycle() {
        EventLog.add(this, EventLevel.ACTION, "Manual airplane cycle triggered")
        if (!AirplaneMode.isAvailable(this)) {
            EventLog.add(this, EventLevel.ERROR, "Airplane cycle needs WRITE_SECURE_SETTINGS")
            return
        }
        recovery.airplaneCycle()
    }

    // ----------------------------------------------------------- HA reporting

    private fun report(event: String, downSeconds: Long) {
        val url = prefs.webhookUrl
        if (url.isEmpty()) return
        runCatching {
            val body = """{"event":"$event","down_seconds":$downSeconds,"stage":${State.stage}}"""
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.responseCode
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------- scheduling

    private fun scheduleNext() {
        if (!prefs.enabled) return
        val delayMs = prefs.checkIntervalSec * 1000L
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val pi = tickIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    // ---------------------------------------------------------- notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watchdog)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(State.summary, State.detail))
    }

    // ------------------------------------------------------------- wifi lock

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiWatchdog::lock")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /** Cross-instance state; the service may be recreated while the process lives on. */
    object State {
        var startedAt: Long = 0L
        var lastCheckAt: Long = 0L
        var online: Boolean = true
        var consecutiveFailures: Int = 0
        var stage: Int = 0
        var backoffSec: Int = INITIAL_BACKOFF_SEC
        var nextHardResetAt: Long = 0L
        var wifi: WifiStatus? = null
        var summary: String = "Watchdog"
        var detail: String = "Starting…"
    }

    companion object {
        const val ACTION_STOP = "com.shymoose.wifiwatchdog.STOP"
        const val ACTION_FORCE_HARD_RESET = "com.shymoose.wifiwatchdog.FORCE_HARD_RESET"
        const val ACTION_FORCE_AIRPLANE = "com.shymoose.wifiwatchdog.FORCE_AIRPLANE"

        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_TIMEOUT_MS = 90_000L

        /** Airplane cycles dwell for a while, so they need far more headroom. */
        private const val WAKE_TIMEOUT_LONG_MS = 10 * 60_000L
        private const val INITIAL_BACKOFF_SEC = 300
        private const val MAX_BACKOFF_SEC = 1800

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            cancelAlarm(context)
            context.startService(Intent(context, WatchdogService::class.java).setAction(ACTION_STOP))
        }

        fun forceHardReset(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
                .setAction(ACTION_FORCE_HARD_RESET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun forceAirplaneCycle(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
                .setAction(ACTION_FORCE_AIRPLANE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun cancelAlarm(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(tickIntent(context))
        }

        fun formatDuration(seconds: Long): String = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }
}
