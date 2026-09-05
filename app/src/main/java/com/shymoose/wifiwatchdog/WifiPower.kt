package com.shymoose.wifiwatchdog

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

/**
 * Wi-Fi power control shared by every recovery rung, including airplane cleanup.
 * Android 13 can accept an enable request but wait indefinitely for user consent.
 */
internal object WifiPower {
    private const val RESOURCES_PACKAGE = "com.android.wifi.resources"
    const val TIMEOUT_MS = 20_000L

    @Volatile
    var pendingPrompt: WifiEnablePrompt? = null
        private set

    private fun wifi(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private fun resources(context: Context): Resources? = try {
        context.packageManager.getResourcesForApplication(RESOURCES_PACKAGE)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun confirmationRequired(context: Context): Boolean {
        val res = resources(context) ?: return Build.VERSION.SDK_INT >= 33
        val id = res.getIdentifier(
            "config_showConfirmationDialogForThirdPartyAppsEnablingWifi", "bool", RESOURCES_PACKAGE
        )
        return if (id == 0) Build.VERSION.SDK_INT >= 33 else res.getBoolean(id)
    }

    private fun prompt(context: Context): WifiEnablePrompt? {
        val res = resources(context) ?: return null
        fun text(name: String, vararg args: Any): String? {
            val id = res.getIdentifier(name, "string", RESOURCES_PACKAGE)
            return if (id == 0) null else res.getString(id, *args)
        }
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        return WifiEnablePrompt(
            text("wifi_enable_request_dialog_title", label) ?: return null,
            text("wifi_enable_request_dialog_message") ?: return null,
            text("wifi_enable_request_dialog_positive_button") ?: return null,
            SystemClock.elapsedRealtime() + TIMEOUT_MS
        )
    }

    /** Never switch Wi-Fi off when we already know we cannot bring it back. */
    fun prepareForReset(context: Context): Boolean {
        if (!confirmationRequired(context)) return true
        if (context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            EventLog.add(context, EventLevel.ERROR, "Wi-Fi recovery blocked: a secured lock screen could hide the enable confirmation")
            return false
        }
        if (prompt(context) == null) {
            EventLog.add(context, EventLevel.ERROR, "Wi-Fi recovery blocked: system confirmation dialog is unsupported")
            return false
        }
        if (InstallAutoClickService.bound != null) return true
        return when (AccessibilityBinding.enable(context)) {
            SetupResult.ALREADY_ON, SetupResult.ENABLED -> true
            SetupResult.NEEDS_MANUAL, SetupResult.FAILED -> {
                EventLog.add(
                    context, EventLevel.ERROR,
                    "Wi-Fi recovery blocked: enable Kiosk Watchdog's accessibility service to confirm Wi-Fi requests"
                )
                false
            }
        }
    }

    @Synchronized
    fun enable(context: Context): Boolean {
        if (wifi(context).wifiState == WifiManager.WIFI_STATE_ENABLED) return true
        if (!prepareForReset(context)) return false
        val confirmation = confirmationRequired(context)
        if (confirmation && !WifiRecoveryActivity.prepare(context)) return false
        pendingPrompt = if (confirmation) prompt(context) else null
        return try {
            change(context, true)
        } finally {
            pendingPrompt = null
        }
    }

    fun disable(context: Context): Boolean = change(context, false)

    @Suppress("DEPRECATION")
    private fun change(context: Context, enabled: Boolean): Boolean {
        val manager = wifi(context)
        val ok = try {
            WifiStateChange(
                request = { manager.setWifiEnabled(it) },
                isInState = {
                    manager.wifiState == if (it) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
                },
                now = SystemClock::elapsedRealtime,
                pause = Thread::sleep,
                poll = { if (enabled) InstallAutoClickService.sweepWifiIfBound() }
            ).apply(enabled, TIMEOUT_MS)
        } catch (e: SecurityException) {
            EventLog.add(context, EventLevel.ERROR, "Wi-Fi power request denied: ${e.message}")
            return false
        }
        if (!ok) {
            EventLog.add(
                context, EventLevel.ERROR,
                "Wi-Fi did not turn ${if (enabled) "on" else "off"}: request rejected or state confirmation timed out (state=${manager.wifiState})"
            )
        }
        return ok
    }
}
