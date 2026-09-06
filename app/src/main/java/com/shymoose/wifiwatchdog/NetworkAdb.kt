package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/** Opt-in boot restoration using Lineage's native network-debugging setting only. */
object NetworkAdb {
    const val PERMISSION = "lineageos.permission.WRITE_SECURE_SETTINGS"
    internal const val AUTHORITY = "lineagesettings"
    internal const val PROVIDER_PACKAGE = "org.lineageos.lineagesettings"
    internal const val PORT_KEY = "adb_port"
    internal const val DEFAULT_PORT = 5555
    private val URI = Uri.parse("content://$AUTHORITY/secure")

    enum class Availability { READY, UNSUPPORTED, NEEDS_GRANT }
    enum class Result { DISABLED, UNAVAILABLE, VERIFIED, FAILED }

    fun availability(context: Context): Availability {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0)
        if (provider == null || provider.packageName != PROVIDER_PACKAGE ||
            provider.applicationInfo == null ||
            provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
        ) return Availability.UNSUPPORTED
        return if (context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            Availability.READY
        } else {
            Availability.NEEDS_GRANT
        }
    }

    fun unavailableReason(context: Context): String? = when (availability(context)) {
        Availability.READY -> null
        Availability.UNSUPPORTED -> context.getString(R.string.pref_network_adb_unsupported)
        Availability.NEEDS_GRANT -> context.getString(R.string.pref_network_adb_needs_grant, context.packageName)
    }

    /**
     * Called off the main thread, only for BOOT_COMPLETED. No polling, sockets,
     * shell/property writes, USB debugging changes, or ADB key/authentication changes.
     *
     * Lineage 15.1's provider GET_secure returns {"value": string}; PUT_secure
     * consumes that same bundle key and returns null even on a successful write.
     * Consequently a successful call alone is not proof: read the setting back.
     */
    fun restoreIfEnabled(context: Context): Result {
        if (!Prefs(context).restoreNetworkAdb) return Result.DISABLED
        return try {
            val reason = unavailableReason(context)
            if (reason != null) {
                EventLog.add(context, EventLevel.WARN, "Network ADB restore skipped: $reason")
                return Result.UNAVAILABLE
            }
            val resolver = context.contentResolver
            val before = resolver.call(URI, "GET_secure", PORT_KEY, null)
            if (before == null || !before.containsKey("value")) {
                return failed(context, "native setting could not be read")
            }
            val existingPort = before.getString("value")?.toIntOrNull()
            // Lineage's SystemServer initializes this before BOOT_COMPLETED.
            // A missing/invalid value is not evidence that this ROM supports the API.
            if (existingPort == null || existingPort !in -1..65535) {
                return failed(context, "native adb_port is unavailable or invalid")
            }
            // Leave a user's already-configured, valid network port alone.
            val port = existingPort.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            if (existingPort != port) {
                resolver.call(URI, "PUT_secure", PORT_KEY, Bundle().apply {
                    putString("value", port.toString())
                })
            }
            val after = resolver.call(URI, "GET_secure", PORT_KEY, null)?.getString("value")?.toIntOrNull()
            if (after != port) {
                failed(context, "native setting readback did not match")
            } else {
                EventLog.add(
                    context, EventLevel.INFO,
                    "Network ADB restore: Lineage adb_port=$port verified; TCP listener not verified. " +
                        "USB debugging and ADB authentication unchanged."
                )
                Result.VERIFIED
            }
        } catch (_: SecurityException) {
            failed(context, "Lineage secure-settings permission denied")
        } catch (error: RuntimeException) {
            failed(context, "native provider failed (${error.javaClass.simpleName})")
        }
    }

    private fun failed(context: Context, reason: String): Result {
        EventLog.add(context, EventLevel.ERROR, "Network ADB restore failed: $reason")
        return Result.FAILED
    }
}
