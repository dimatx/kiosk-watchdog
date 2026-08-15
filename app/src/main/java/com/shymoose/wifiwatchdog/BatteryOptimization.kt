package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze exemption.
 *
 * The watchdog schedules its checks with [android.app.AlarmManager.setExactAndAllowWhileIdle],
 * which survives Doze but is rate-limited to roughly one firing every 9-15 minutes while the
 * device is idle. On a wall-mounted display that idles for hours, that turns a 60-second check
 * interval into a quarter-hour one and delays every heartbeat by the same amount.
 *
 * Being on the battery-optimization whitelist removes the throttle entirely.
 */
object BatteryOptimization {

    /** True when Doze cannot throttle this app's alarms or network access. */
    fun isWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Shows the system "allow this app to ignore battery optimizations?" dialog.
     *
     * Some stripped-down ROMs ship no handler for the direct request, so this falls back to the
     * full battery-optimization list, where the app can be exempted manually.
     *
     * @return false when neither screen exists — the adb command is the only route left.
     */
    fun request(context: Context): Boolean {
        if (isWhitelisted(context)) return true

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (start(context, direct)) return true

        return start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Equivalent to [request], for ROMs where neither settings screen is reachable. */
    fun adbCommand(context: Context): String =
        "adb shell dumpsys deviceidle whitelist +${context.packageName}"

    private fun start(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}
