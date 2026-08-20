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

    /** Last probe target, quoted in notifications raised from the escalation ladder. */
    private var lastTargetLabel: String = "?"

    /** Rate limit for asking the radio to scan while the link is healthy. */
    private var lastScanRequestAt: Long = 0L

    /** What the ongoing notification currently says, so identical updates are dropped. */
    private var lastNotifiedSummary: String? = null
    private var lastNotifiedDetail: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        probe = NetProbe(this)
        recovery = WifiRecovery(this)
        // Before anything else: an interrupted reset can leave background
        // scanning switched off, which is both a degradation in its own right and
        // the reason the radio cannot be seen to be working.
        worker.execute { runCatching { recovery.restoreScanAlways() } }
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

            ACTION_SEND_TEST -> runOnWorker(coalesce = false) { sendTestNotification() }

            ACTION_SEND_HEARTBEAT -> runOnWorker(coalesce = false) { sendTestHeartbeat() }

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

        // Everything below is guarded, because the alarm that drives the next
        // check is armed in the finally. Anything that escapes it stops the
        // watchdog for good while the process stays alive and the notification
        // still shows the last known state - a display that looks fine and is no
        // longer watching anything.
        val submitted = runCatching {
            worker.execute {
                // Forced work may have queued behind a probe; take the flag now.
                if (!claimed) busy.set(true)
                var wake: PowerManager.WakeLock? = null
                try {
                    // Inside the try: obtaining this involves a service lookup and
                    // a cast, and a throw here would skip the reschedule below.
                    wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiWatchdog::tick")
                    wake.acquire(wakeMs)
                    block()
                } catch (t: Throwable) {
                    EventLog.add(this, EventLevel.ERROR, "Task failed: ${t.message}")
                } finally {
                    busy.set(false)
                    runCatching { if (wake?.isHeld == true) wake.release() }
                    runCatching { scheduleNext() }
                    runCatching { updateNotification() }
                }
            }
        }.isSuccess

        // The executor refuses new work once it has been shut down. Without this
        // the claim above would stay taken and every later tick would coalesce
        // into a job that is never going to run.
        if (!submitted) {
            busy.set(false)
            runCatching { scheduleNext() }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (prefs.lastGoodAtMillis == 0L) prefs.lastGoodAtMillis = now

        // Written every check so that if the device stops responding, the last
        // value on disk says roughly when. Nothing else survives a hang: the
        // event log is rolled back with the rest of the unflushed writes, and
        // adb over the network does not come back after the power cycle.
        prefs.lastAliveAtMillis = now
        if (prefs.cleanShutdown) prefs.cleanShutdown = false

        AccessibilityBinding.repairIfUnbound(this)
        BluetoothGuard.enforce(this)
        KioskReturn.check(this)
        // An install dialog can appear while the display is asleep, where nothing
        // else will notice it. This alarm runs through Doze, so it is the one
        // thing that can.
        InstallAutoClickService.sweepIfBound()

        val target = probe.resolveTarget(prefs)
        val probeStartedAt = SystemClock.elapsedRealtime()
        val reachable = target != null && probe.canReach(target)
        val rttMs = SystemClock.elapsedRealtime() - probeStartedAt
        State.lastCheckAt = now
        State.wifi = probe.status()
        State.online = reachable
        val label = describeTarget(this, target)
        lastTargetLabel = label

        updateBlindness(reachable, now)

        // Recorded before any recovery runs, so the picture is of the device as
        // it was found rather than after the ladder started acting on it.
        Vitals.record(this, State.wifi, State.stage, reachable)

        if (reachable) {
            if (State.stage > 0 || State.consecutiveFailures > 0) {
                val downFor = observedDownSec(now)
                EventLog.add(this, EventLevel.INFO, "Connectivity restored after ${formatDuration(downFor)}")
                // Only worth telling anyone about if they were told it was down.
                // A stall that cleared before the ladder touched anything is not
                // a recovery, it is a link that was never actually broken.
                if (State.reportedLost) report("recovered", downFor)
            }
            // The link is up: this is the only moment queued notifications can go out.
            Ntfy.flush(this)
            // Heartbeats are deliberately never queued — silence is what makes the
            // monitor on the other end raise the alarm.
            Heartbeat.maybePing(this, prefs, rttMs)
            prefs.lastGoodAtMillis = now
            State.consecutiveFailures = 0
            State.stage = 0
            State.reportedLost = false
            State.backoffSec = INITIAL_BACKOFF_SEC
            State.nextHardResetAt = 0L
            State.summary = getString(R.string.status_online)
            State.detail = getString(R.string.status_detail_online, label)
            return
        }
        State.consecutiveFailures++
        val downSec = observedDownSec(now)
        State.summary = getString(R.string.status_offline)
        State.detail = getString(R.string.status_detail_offline, formatDuration(downSec))

        // Logged locally on the first miss, because the event log is where an
        // intermittent link gets diagnosed. Deliberately not sent anywhere: the
        // ladder has already decided a short outage is not worth acting on, so
        // reporting one is telling somebody about a non-event. The alert goes
        // out when the watchdog actually does something. See act().
        if (State.consecutiveFailures == 1) {
            EventLog.add(this, EventLevel.WARN, "Cannot reach $label")
        }

        escalate(downSec, now)
    }

    /**
     * Tracks whether the radio can see anything at all.
     *
     * A fresh scan is asked for on every failed check so the reading is live
     * rather than a cache of whatever was last seen; a stale cache would hide
     * precisely the failure this is looking for.
     */
    private fun updateBlindness(reachable: Boolean, now: Long) {
        val wifi = State.wifi
        if (wifi != null && wifi.scanCount > 0) State.everSawAccessPoints = true

        val settling = now - State.lastActionAt < ACTION_SETTLE_MS

        if (reachable || wifi == null || !wifi.wifiEnabled || settling) {
            State.blindChecks = 0
            // Kept warm while healthy, at a fraction of the check rate. Without
            // this the scan list on a connected display can stay empty for hours
            // simply because nothing asked, and an empty list that has never once
            // been non-empty is not usable as evidence of anything.
            if (reachable && now - lastScanRequestAt >= SCAN_REFRESH_MS) {
                lastScanRequestAt = now
                probe.requestScan()
            }
            return
        }

        // Down: ask on every check. The results land in time for the next one.
        lastScanRequestAt = now
        val scanAccepted = probe.requestScan()

        // A zero only means anything if a scan was actually going to happen.
        // The framework can decline outright - measured on a display that had
        // been asleep for hours, where every request was refused and the list
        // stayed empty on a radio that was working perfectly. Counting that as
        // a blind radio would send a merely-offline display straight to a
        // driver reload and an airplane cycle, skipping the gentler steps that
        // usually suffice. -1 is "could not read", which says nothing either.
        if (wifi.scanCount != 0 || !scanAccepted) {
            State.blindChecks = 0
            return
        }

        State.blindChecks++
        if (State.blindChecks == BLIND_CONFIRM_CHECKS && State.everSawAccessPoints) {
            EventLog.add(
                this,
                EventLevel.WARN,
                "Radio can see no access points at all — treating this as a failed radio"
            )
        }
    }

    /**
     * How long the link has been down *as observed by this run*.
     *
     * The last-good timestamp is persisted and only ever advanced by a successful
     * probe, so after any gap in which the watchdog was not running - an
     * overnight power-down, a building power cut, an app update, a process kill -
     * it reports hours or days. The escalation thresholds would all be satisfied
     * at once and the ladder would climb a rung per tick: at the default interval
     * that is a driver unload and a full airplane cycle within about eighty
     * seconds of boot.
     *
     * Which is precisely backwards for the most likely case. A power cut takes
     * the displays and the access point down together; the panels are up in
     * around thirty seconds and the access point takes minutes, so those first
     * failures are expected and temporary. Tearing the radio down then only
     * delays association further, and it fired an alert every time.
     *
     * Downtime this process did not witness is therefore not counted. The
     * configured delays mean "how long have I seen this down", which is the only
     * thing that can honestly be measured.
     */
    private fun observedDownSec(now: Long): Long {
        val sinceLastGood = (now - prefs.lastGoodAtMillis) / 1000
        val startedAt = State.startedAt
        if (startedAt <= 0L) return 0
        val sinceStart = (now - startedAt) / 1000
        return minOf(sinceLastGood, sinceStart).coerceAtLeast(0)
    }

    private fun escalate(downSec: Long, now: Long) {
        // A radio that can see nothing is not going to be talked round by asking
        // it to re-associate with an access point it cannot find. The cheap rungs
        // are there for a link that is merely unhappy; this one is broken, so it
        // goes straight to the rung that reloads the driver.
        if (State.blind && State.stage < 2) {
            EventLog.add(this, EventLevel.INFO, "Skipping the gentler steps — nothing to re-associate with")
            State.stage = 2
        }

        // The configured delays exist to avoid over-reacting to a link that might
        // still come back on its own. A blind radio is not going to, so the wait
        // is dropped - but only for the rungs that actually reload the driver.
        // Blindness is re-confirmed after every action, so this cannot run two
        // resets back to back: the settle window clears the count first.
        val blind = State.blind

        when (State.stage) {
            0 -> if (downSec >= prefs.reassociateAfterSec) {
                act(downSec) { recovery.reassociate() }
                State.stage = 1
            }

            1 -> if (downSec >= prefs.softToggleAfterSec) {
                act(downSec) { recovery.softToggle() }
                State.stage = 2
            }

            2 -> if (blind || downSec >= prefs.hardResetAfterSec) {
                act(downSec) { if (hardResetUsable()) recovery.hardReset() else recovery.softToggle() }
                State.stage = 3
                report("hard_reset", downSec)
            }

            3 -> if (blind || downSec >= prefs.airplaneAfterSec) {
                // The heaviest rung: a real airplane-mode cycle, which is what has
                // actually brought this device back when nothing else did.
                act(downSec) { if (airplaneUsable()) recovery.airplaneCycle() else lastResortReset() }
                State.stage = 4
                State.backoffSec = INITIAL_BACKOFF_SEC
                State.nextHardResetAt = now + State.backoffSec * 1000L
                report("airplane_cycle", downSec)
            }

            else -> if (now >= State.nextHardResetAt) {
                act(downSec) { if (airplaneUsable()) recovery.airplaneCycle() else lastResortReset() }
                val ceiling = if (blind) BLIND_MAX_BACKOFF_SEC else MAX_BACKOFF_SEC
                State.backoffSec = (State.backoffSec * 2).coerceAtMost(ceiling)
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

    /**
     * Runs a recovery action, announcing the outage the first time.
     *
     * The alert belongs here rather than at the first failed probe. The ladder's
     * whole premise is that a short outage is not worth acting on - that is what
     * the delays before each rung are for - so reporting one the moment a probe
     * misses says "something is wrong" about a link the app has already judged
     * fine. In practice that meant a wall tablet whose radio stalls for ten
     * seconds sent a pair of notifications every half hour, every one of them
     * ending at stage 0, having done nothing, because there was nothing to do.
     *
     * Reported once per outage, before the action rather than after, so the
     * message still goes out even if the action wedges.
     *
     * The timestamp is also what stops the radio's own recovery being read as
     * another failure: the scan list is empty for a while after a reload, and
     * without a settle window that would escalate straight into the next rung.
     */
    private inline fun act(downSec: Long, block: () -> Unit) {
        if (!State.reportedLost) {
            State.reportedLost = true
            report("lost", downSec)
        }
        try {
            block()
        } finally {
            State.lastActionAt = System.currentTimeMillis()
            State.blindChecks = 0
        }
    }

    private fun airplaneUsable(): Boolean = prefs.airplaneEnabled && AirplaneMode.isAvailable(this)

    /**
     * Hard reset needs WRITE_SECURE_SETTINGS to unload the driver. On a device
     * that was never set up over adb it can never work, so check here instead of
     * letting [WifiRecovery.hardReset] warn about it on every single escalation.
     */
    private fun hardResetUsable(): Boolean =
        prefs.hardResetEnabled && recovery.hasSecureSettingsPermission()

    private fun lastResortReset() {
        if (hardResetUsable()) recovery.hardReset() else recovery.softToggle()
    }

    private fun forceHardReset() {
        EventLog.add(this, EventLevel.ACTION, "Manual recovery triggered")
        if (hardResetUsable()) recovery.hardReset() else recovery.softToggle()
    }

    private fun forceAirplaneCycle() {
        EventLog.add(this, EventLevel.ACTION, "Manual airplane cycle triggered")
        if (!AirplaneMode.isAvailable(this)) {
            EventLog.add(this, EventLevel.ERROR, "Airplane cycle needs WRITE_SECURE_SETTINGS")
            return
        }
        recovery.airplaneCycle()
    }

    // -------------------------------------------------------- ntfy reporting

    /**
     * Every event is queued rather than sent inline: all of them except recovery
     * happen while the link is down, so an inline POST could never succeed and
     * would only stall the recovery ladder behind a socket timeout. The queue is
     * drained on the next successful probe.
     */
    private fun report(event: String, downSeconds: Long) {
        if (!prefs.ntfyConfigured) return
        val at = System.currentTimeMillis()
        val id = DeviceIdentity.snapshot(this)

        val (title, priority, tags) = when (event) {
            "lost" -> Triple(getString(R.string.ntfy_title_lost), Ntfy.PRIORITY_HIGH, "warning")
            "hard_reset" -> Triple(
                getString(R.string.ntfy_title_hard_reset),
                Ntfy.PRIORITY_HIGH,
                "arrows_counterclockwise"
            )
            "airplane_cycle" -> Triple(
                getString(R.string.ntfy_title_airplane),
                Ntfy.PRIORITY_URGENT,
                "rotating_light"
            )
            "recovered" -> Triple(
                getString(R.string.ntfy_title_recovered),
                Ntfy.PRIORITY_DEFAULT,
                "white_check_mark"
            )
            else -> Triple(
                getString(R.string.ntfy_title_test),
                Ntfy.PRIORITY_DEFAULT,
                "white_check_mark"
            )
        }

        val lines = mutableListOf(
            id.oneLine(),
            getString(R.string.ntfy_line_when, Ntfy.timestamp(at))
        )
        if (event == "recovered") {
            lines.add(getString(R.string.ntfy_line_down_for, formatDuration(downSeconds)))
        } else if (downSeconds > 0) {
            lines.add(getString(R.string.ntfy_line_down_since, formatDuration(downSeconds)))
        }
        if (event != "test") {
            lines.add(getString(R.string.ntfy_line_stage, State.stage))
            lines.add(getString(R.string.ntfy_line_target, lastTargetLabel))
            // Says why the ladder is where it is: without this an accelerated
            // escalation looks like the delays were ignored.
            if (State.blind) lines.add(getString(R.string.ntfy_line_blind))
        }

        Ntfy.enqueue(
            this,
            Ntfy.Message(
                title = "$title — ${id.hostname}",
                body = lines.joinToString("\n"),
                priority = priority,
                tags = tags,
                at = at
            )
        )
    }

    private fun sendTestNotification() {
        if (!prefs.ntfyConfigured) {
            EventLog.add(this, EventLevel.ERROR, getString(R.string.log_ntfy_unconfigured))
            return
        }
        EventLog.add(this, EventLevel.ACTION, getString(R.string.log_ntfy_test))
        report("test", 0)
        Ntfy.flush(this, force = true)
    }

    private fun sendTestHeartbeat() {
        if (!prefs.heartbeatConfigured) {
            EventLog.add(this, EventLevel.ERROR, getString(R.string.log_heartbeat_unconfigured))
            return
        }
        EventLog.add(this, EventLevel.ACTION, getString(R.string.log_heartbeat_test))
        Heartbeat.pingNow(this, prefs, 0)
    }

    // ------------------------------------------------------------- scheduling

    private fun scheduleNext() {
        if (!prefs.enabled) return
        val delayMs = prefs.checkIntervalSec * 1000L
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val pi = tickIntent(this)
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
    }

    // ---------------------------------------------------------- notifications

    private fun createChannel() {
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
        val summary = State.summary
        val detail = State.detail
        // The ladder re-posts on every probe, but the text only moves when the
        // link state does. Re-notifying identical content just wakes the
        // notification stack for nothing.
        if (summary == lastNotifiedSummary && detail == lastNotifiedDetail) return
        lastNotifiedSummary = summary
        lastNotifiedDetail = detail
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(summary, detail))
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

        /**
         * Whether this device has ever reported seeing an access point.
         *
         * Scan results are not readable everywhere: they need a location
         * permission and the location providers switched on, and when either is
         * missing the call returns an empty list rather than an error - which is
         * indistinguishable from a radio that genuinely sees nothing. Measured on
         * these displays, a perfectly healthy one reported zero.
         *
         * So a zero only counts once a positive reading has been seen at least
         * once. On a device where the reading is unavailable the signal is simply
         * never used, instead of declaring every healthy radio broken.
         */
        var everSawAccessPoints: Boolean = false

        /**
         * Consecutive checks on which the radio was enabled but could see no
         * access points at all. See [blind].
         */
        var blindChecks: Int = 0

        /** When the last recovery action ran, so its own aftermath is not counted. */
        var lastActionAt: Long = 0L

        /**
         * Whether this outage has been announced.
         *
         * Set when the ladder first acts, cleared on recovery. It is also what
         * decides whether a restored message is worth sending: a stall that
         * cleared before anything was done is not a recovery.
         */
        var reportedLost: Boolean = false

        /**
         * Whether the radio is confirmed blind: enabled, but seeing nothing.
         *
         * These displays are permanently in range of several access points, so an
         * empty scan list is not a state a working radio reaches. Confirmed over
         * consecutive checks rather than acted on immediately, because a scan
         * legitimately returns nothing for a few seconds after the driver reloads.
         */
        val blind: Boolean get() = everSawAccessPoints && blindChecks >= BLIND_CONFIRM_CHECKS
    }

    companion object {
        const val ACTION_STOP = "com.shymoose.wifiwatchdog.STOP"
        const val ACTION_FORCE_HARD_RESET = "com.shymoose.wifiwatchdog.FORCE_HARD_RESET"
        const val ACTION_FORCE_AIRPLANE = "com.shymoose.wifiwatchdog.FORCE_AIRPLANE"
        const val ACTION_SEND_TEST = "com.shymoose.wifiwatchdog.SEND_TEST"
        const val ACTION_SEND_HEARTBEAT = "com.shymoose.wifiwatchdog.SEND_HEARTBEAT"

        private const val CHANNEL_ID = "watchdog"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_TIMEOUT_MS = 90_000L

        /** Airplane cycles dwell for a while, so they need far more headroom. */
        private const val WAKE_TIMEOUT_LONG_MS = 10 * 60_000L
        private const val INITIAL_BACKOFF_SEC = 300
        private const val MAX_BACKOFF_SEC = 1800

        /**
         * Checks a radio must see nothing on before it is believed to be blind.
         *
         * A reload leaves the scan list empty for a few seconds, so one reading is
         * not enough; two consecutive ones at the default interval is about forty
         * seconds of seeing no access point anywhere.
         */
        private const val BLIND_CONFIRM_CHECKS = 2

        /**
         * How long after a recovery action to ignore an empty scan list.
         *
         * The radio has just been torn down and has not finished its first scan,
         * so counting that would have every reset immediately declare itself a
         * failure and escalate again.
         */
        private const val ACTION_SETTLE_MS = 45_000L

        /** How often to refresh the scan list while the link is healthy. */
        private const val SCAN_REFRESH_MS = 5 * 60_000L

        /**
         * Backoff ceiling while the radio is blind.
         *
         * The normal ceiling assumes the far end may be at fault and there is no
         * point hammering it. A blind radio is different: the device itself is
         * known to be broken, and there is no reason to leave it broken for half
         * an hour between attempts.
         */
        private const val BLIND_MAX_BACKOFF_SEC = 300

        fun start(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            cancelAlarm(context)
            context.startService(Intent(context, WatchdogService::class.java).setAction(ACTION_STOP))
        }

        fun forceHardReset(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
                .setAction(ACTION_FORCE_HARD_RESET)
            context.startForegroundService(intent)
        }

        fun forceAirplaneCycle(context: Context) {
            val intent = Intent(context, WatchdogService::class.java)
                .setAction(ACTION_FORCE_AIRPLANE)
            context.startForegroundService(intent)
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

        /** Human label for a probe target, e.g. "10.0.0.1 (gateway)". */
        fun describeTarget(context: Context, target: ProbeTarget?): String {
            if (target == null) return context.getString(R.string.probe_target_none)
            val source = context.getString(
                when (target.source) {
                    ProbeTarget.Source.GATEWAY -> R.string.probe_source_gateway
                    ProbeTarget.Source.LAST_GATEWAY -> R.string.probe_source_last_gateway
                    ProbeTarget.Source.CONFIGURED -> R.string.probe_source_configured
                }
            )
            return context.getString(R.string.probe_target, target.host, source)
        }
    }
}
