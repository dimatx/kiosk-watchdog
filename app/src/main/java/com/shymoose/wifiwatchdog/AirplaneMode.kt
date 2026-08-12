package com.shymoose.wifiwatchdog

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Drives a real airplane-mode cycle: the heaviest recovery available without
 * root, and the one that has actually rescued this device by hand.
 *
 * Everything here is blocking and must run off the main thread.
 *
 * Safety matters more than usual: airplane mode kills Wi-Fi, which kills
 * adb-over-TCP, so a cycle that gets stuck half-way strands the device. Two
 * guards cover that:
 *
 *  1. [Prefs.airplanePending] is written *before* the radio goes down, and is
 *     checked on boot and on service start so a crash or reboot mid-cycle is
 *     always cleaned up.
 *  2. An exact alarm is armed for shortly after the intended dwell, so even if
 *     this process is killed the [AirplaneRestoreReceiver] still turns it off.
 */
object AirplaneMode {

    private const val POLL_MS = 500L
    private const val CLAIM_TIMEOUT_MS = 12_000L
    private const val TOGGLE_TIMEOUT_MS = 20_000L
    private const val SETTLE_MS = 2_000L
    private const val FAILSAFE_MARGIN_MS = 30_000L
    private const val FAILSAFE_REQUEST = 4711

    private const val SECURE_VOICE_INTERACTION = "voice_interaction_service"

    // ------------------------------------------------------------------ state

    fun isOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun ourComponent(context: Context): String =
        ComponentName(context.packageName, AssistantService::class.java.name).flattenToShortString()

    fun currentAssistant(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, SECURE_VOICE_INTERACTION)

    fun isAssistantOwned(context: Context): Boolean =
        currentAssistant(context) == ourComponent(context)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True when a cycle can be attempted at all. */
    fun isAvailable(context: Context): Boolean = hasPermission(context)

    // -------------------------------------------------------- assistant slot

    /**
     * Registers this app as the current voice interaction service, which is what
     * grants permission to launch Settings' airplane-mode voice activity.
     *
     * The previous value is remembered so it can be handed back.
     */
    fun claimAssistant(context: Context): Boolean {
        if (isAssistantOwned(context) && AssistantService.instance != null) return true
        if (!hasPermission(context)) {
            EventLog.add(context, EventLevel.ERROR, "Cannot claim assistant — WRITE_SECURE_SETTINGS missing")
            return false
        }

        val prefs = Prefs(context)
        if (!isAssistantOwned(context)) {
            prefs.previousAssistant = currentAssistant(context) ?: ""
            val ok = runCatching {
                Settings.Secure.putString(context.contentResolver, SECURE_VOICE_INTERACTION, ourComponent(context))
            }.isSuccess
            if (!ok) {
                EventLog.add(context, EventLevel.ERROR, "Could not write voice_interaction_service")
                return false
            }
            EventLog.add(context, EventLevel.ACTION, "Claimed assistant slot for airplane control")
        }

        // The system rebinds asynchronously after the setting changes.
        val deadline = SystemClock.elapsedRealtime() + CLAIM_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (AssistantService.instance != null) return true
            Thread.sleep(POLL_MS)
        }
        EventLog.add(context, EventLevel.ERROR, "Assistant slot claimed but service never bound")
        return false
    }

    /** Hands the assistant slot back to whatever held it before. */
    fun releaseAssistant(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val previous = Prefs(context).previousAssistant
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                SECURE_VOICE_INTERACTION,
                previous.ifEmpty { null }
            )
            EventLog.add(context, EventLevel.INFO, "Released assistant slot")
            true
        }.getOrElse { false }
    }

    // ---------------------------------------------------------------- toggling

    private fun request(context: Context, enable: Boolean): Boolean {
        val service = AssistantService.instance
        if (service == null) {
            EventLog.add(context, EventLevel.ERROR, "Assistant not bound — cannot toggle airplane mode")
            return false
        }
        if (!service.requestAirplane(enable)) return false

        val deadline = SystemClock.elapsedRealtime() + TOGGLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isOn(context) == enable) return true
            Thread.sleep(POLL_MS)
        }
        EventLog.add(
            context,
            EventLevel.ERROR,
            "Airplane mode did not turn ${if (enable) "on" else "off"} within ${TOGGLE_TIMEOUT_MS / 1000}s"
        )
        return false
    }

    /**
     * Full cycle: airplane on, dwell, airplane off, then make sure Wi-Fi is back.
     *
     * @param dwellMs how long to stay in airplane mode.
     */
    @Suppress("DEPRECATION")
    fun cycle(context: Context, dwellMs: Long): Boolean {
        val app = context.applicationContext
        if (!isAvailable(app)) {
            EventLog.add(app, EventLevel.WARN, "Airplane cycle skipped — WRITE_SECURE_SETTINGS not granted")
            return false
        }
        if (!claimAssistant(app)) return false

        val prefs = Prefs(app)
        prefs.airplanePending = true
        armFailsafe(app, dwellMs + FAILSAFE_MARGIN_MS)

        try {
            EventLog.add(
                app,
                EventLevel.ACTION,
                "Airplane cycle: radios down for ${WatchdogService.formatDuration(dwellMs / 1000)}"
            )
            if (!request(app, true)) {
                // Never leave it half-done.
                ensureOff(app)
                return false
            }

            Thread.sleep(dwellMs)

            if (!request(app, false)) {
                ensureOff(app)
                return false
            }

            Thread.sleep(SETTLE_MS)
            restoreWifi(app)
            EventLog.add(app, EventLevel.ACTION, "Airplane cycle complete")
            return true
        } catch (t: Throwable) {
            EventLog.add(app, EventLevel.ERROR, "Airplane cycle failed: ${t.message}")
            ensureOff(app)
            return false
        } finally {
            prefs.airplanePending = false
            cancelFailsafe(app)
        }
    }

    /**
     * Idempotent cleanup. Safe to call on boot, on service start, or from the
     * failsafe alarm.
     */
    fun ensureOff(context: Context): Boolean {
        val app = context.applicationContext
        Prefs(app).airplanePending = false
        cancelFailsafe(app)
        if (!isOn(app)) {
            restoreWifi(app)
            return true
        }
        EventLog.add(app, EventLevel.WARN, "Airplane mode still on — forcing it off")
        if (!hasPermission(app)) return false
        if (!claimAssistant(app)) return false
        val ok = request(app, false)
        if (ok) {
            Thread.sleep(SETTLE_MS)
            restoreWifi(app)
        }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun restoreWifi(context: Context) {
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) wm.setWifiEnabled(true)
        }
    }

    // ---------------------------------------------------------------- failsafe

    private fun failsafeIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        FAILSAFE_REQUEST,
        Intent(context, AirplaneRestoreReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun armFailsafe(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = SystemClock.elapsedRealtime() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, failsafeIntent(context))
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, failsafeIntent(context))
        }
    }

    private fun cancelFailsafe(context: Context) {
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(failsafeIntent(context))
        }
    }
}
