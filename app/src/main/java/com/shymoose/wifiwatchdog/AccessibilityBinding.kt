package com.shymoose.wifiwatchdog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

/**
 * Keeps the install auto-click accessibility service registered and bound.
 *
 * This is not the service. The service reads windows and clicks buttons; this
 * deals with the entirely separate problem of persuading the framework to run it
 * at all, which on this platform is most of the work.
 *
 * Three things go wrong, and none of them announce themselves:
 *
 *  - `am force-stop` tears the service down and the framework does not bring it
 *    back. When ours is the only enabled accessibility service, Android also
 *    empties the enabled list and clears the global switch, so the settings stop
 *    claiming it is on.
 *  - Replacing the app can leave the accessibility manager believing a bind for
 *    that component is still in flight. The record is keyed by component name
 *    and nothing clears it, so that name is skipped forever after - by this app
 *    and by the system's own Accessibility screen alike. Hence a second identity
 *    under a different name.
 *  - The setting can read as enabled while nothing is bound, so the setting is
 *    never trusted on its own; the bind callback is the only proof.
 *
 * All of it needs WRITE_SECURE_SETTINGS, the same grant the app already holds
 * for airplane mode. Without it there is nothing to do but send the user to the
 * system screen.
 */
object AccessibilityBinding {

    /** How long to wait for the framework to actually bind after the write. */
    private const val BIND_TIMEOUT_MS = 8_000L
    private const val POLL_MS = 250L

    /** Floor between rebind attempts, so a refusal cannot become a log stream. */
    private const val REPAIR_MIN_INTERVAL_MS = 5 * 60_000L

    /** How long to wait for the framework to bind after rewriting the setting. */
    private const val REPAIR_CONFIRM_MS = 3_000L

    /** Time for the package change to reach the accessibility manager. */
    private const val STANDBY_SETTLE_MS = 1_500L

    /**
     * How long after process start to leave the framework alone.
     *
     * A package replace restarts this process and rebinds the service shortly
     * after; interfering inside that window fights a bind that was going to
     * succeed.
     */
    private const val BIND_GRACE_MS = 30_000L

    @Volatile
    private var nextRepairAt = 0L

    /** Whether the current outage has already been reported once. */
    @Volatile
    private var repairReported = false

    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, InstallAutoClickService::class.java.name)

    /** The standby identity, used when the primary component is being skipped. */
    fun altComponent(context: Context): ComponentName =
        ComponentName(context.packageName, InstallAutoClickServiceAlt::class.java.name)

    /** Both identities, primary first: only one is ever enabled at a time. */
    private fun components(context: Context): List<ComponentName> =
        listOf(component(context), altComponent(context))

    fun isEnabled(context: Context): Boolean = listed(context, components(context))

    /** Whether the enabled list currently names the standby identity. */
    private fun isStandbyListed(context: Context): Boolean =
        listed(context, listOf(altComponent(context)))

    private fun listed(context: Context, wanted: List<ComponentName>): Boolean {
        val names = wanted.flatMap { listOf(it.flattenToString(), it.flattenToShortString()) }
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { it in names }
    }

    /**
     * Opens the system accessibility screen so the service can be turned on by
     * hand. This is the only route on a device that was never reachable over adb
     * - a Fire tablet, say - where WRITE_SECURE_SETTINGS cannot be granted at all.
     */
    fun openAccessibilitySettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        EventLog.add(context, EventLevel.ERROR, "Could not open accessibility settings: ${it.message}")
        false
    }

    /**
     * Turns the service on using the WRITE_SECURE_SETTINGS grant the app holds
     * for airplane mode, so no adb round-trip is needed.
     *
     * Without that grant the setting is not writable by any means, so the caller
     * is told to send the user to the system screen instead.
     *
     * Blocks for up to [BIND_TIMEOUT_MS] waiting for the framework to actually
     * bind - the write silently no-ops in some states, so the callback is the
     * only real proof. Call this off the main thread.
     */
    fun enable(context: Context): SetupResult {
        if (isEnabled(context) && InstallAutoClickService.bound != null) return SetupResult.ALREADY_ON
        if (!AirplaneMode.hasPermission(context)) return SetupResult.NEEDS_MANUAL

        // Primary first, then the standby identity: see repairIfUnbound for why
        // the primary can be permanently skipped by the framework.
        if (rewriteAndAwaitBind(context, component(context), BIND_TIMEOUT_MS)) {
            hideStandby(context)
            EventLog.add(context, EventLevel.ACTION, "Enabled install auto-click service")
            return SetupResult.ENABLED
        }
        if (showStandby(context) &&
            rewriteAndAwaitBind(context, altComponent(context), BIND_TIMEOUT_MS)
        ) {
            EventLog.add(context, EventLevel.ACTION, "Enabled install auto-click service")
            return SetupResult.ENABLED
        }
        EventLog.add(
            context,
            EventLevel.ERROR,
            "Auto-install service did not bind after the settings write"
        )
        return SetupResult.FAILED
    }

    /**
     * Rebinds the service when the setting still lists it but nothing is running.
     *
     * [Prefs.autoInstallServiceEverOn] is what separates a torn-down service from
     * a user who deliberately switched it off, since the setting alone cannot
     * tell the two apart. Turning off "Confirm update dialogs" is the way to stop
     * this for good.
     */
    fun repairIfUnbound(context: Context) {
        if (InstallAutoClickService.bound != null) {
            // Healthy: re-arm, so a later outage is retried and reported again.
            nextRepairAt = 0L
            repairReported = false
            return
        }
        val prefs = Prefs(context)
        if (!prefs.autoInstallEnabled) return
        if (!isEnabled(context) && !prefs.autoInstallServiceEverOn) return
        if (!AirplaneMode.hasPermission(context)) return

        // The framework rebinds on its own after a package replace, and a tick
        // can land while that is still in flight. Rewriting the setting then
        // tears down a service that was about to come up - and, when the standby
        // is the working identity, swaps it out for the primary that is already
        // known to be stuck.
        val sinceStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        if (sinceStart < BIND_GRACE_MS) return

        // The framework can decline to bind for reasons nothing here can fix, and
        // the watchdog ticks every few seconds. Without a floor between attempts
        // this turns into a permanent stream of identical events.
        val now = SystemClock.elapsedRealtime()
        if (now < nextRepairAt) return
        nextRepairAt = now + REPAIR_MIN_INTERVAL_MS

        // Whichever identity the setting already names goes first: it is the one
        // the framework last accepted, so it is the likeliest to work and the
        // least disruptive to re-assert.
        val primary = component(context)
        val standby = altComponent(context)
        val first = if (isStandbyListed(context)) standby else primary
        val second = if (first == primary) standby else primary

        if (first == standby) showStandby(context)
        if (tryBind(context, first, primary, "Auto-install service had stopped — restarted it")) return

        // The stale bind record above is keyed by component name, so the other
        // identity is unaffected by it. Behaviour is identical.
        if (second == standby && !showStandby(context)) {
            reportStuck(context)
            return
        }
        if (tryBind(
                context,
                second,
                primary,
                "Auto-install service was blocked by the system — switched identity"
            )
        ) {
            return
        }

        reportStuck(context)
    }

    private fun tryBind(
        context: Context,
        wanted: ComponentName,
        primary: ComponentName,
        success: String
    ): Boolean {
        if (!rewriteAndAwaitBind(context, wanted, REPAIR_CONFIRM_MS)) return false
        repairReported = false
        if (wanted == primary) hideStandby(context)
        EventLog.add(context, EventLevel.ACTION, success)
        return true
    }

    /**
     * Said once per outage. Repeating it every attempt would bury the events that
     * actually describe what the watchdog is doing.
     *
     * Recommends a restart rather than the Accessibility screen: once both
     * identities are being skipped, that screen cannot turn either back on either
     * - it reports the service as enabled while nothing is bound.
     */
    private fun reportStuck(context: Context) {
        if (repairReported) return
        repairReported = true
        EventLog.add(
            context,
            EventLevel.WARN,
            "Auto-install service will not start — restart the device to clear it"
        )
    }

    /**
     * Publishes the standby component so the framework will consider binding it.
     *
     * It ships disabled, which is what keeps a duplicate row out of the
     * Accessibility list on the devices that never need it. Changing the state of
     * a component this app owns needs no permission, and
     * [PackageManager.DONT_KILL_APP] leaves the running watchdog alone.
     */
    private fun showStandby(context: Context): Boolean = setStandbyEnabled(context, true)

    /** Puts the standby back out of sight once the primary is working again. */
    private fun hideStandby(context: Context) {
        if (InstallAutoClickService.bound is InstallAutoClickServiceAlt) return
        setStandbyEnabled(context, false)
    }

    private fun setStandbyEnabled(context: Context, enabled: Boolean): Boolean = runCatching {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val pm = context.packageManager
        val alt = altComponent(context)
        if (pm.getComponentEnabledSetting(alt) == state) return@runCatching true
        pm.setComponentEnabledSetting(alt, state, PackageManager.DONT_KILL_APP)
        if (enabled) Thread.sleep(STANDBY_SETTLE_MS)
        true
    }.getOrElse {
        EventLog.add(context, EventLevel.ERROR, "Could not switch the standby copy: ${it.message}")
        false
    }

    /**
     * Writes the enabled list, then waits for the bind callback that proves it took.
     *
     * The value has to genuinely change or SettingsProvider drops an identical
     * write without notifying, so the component is removed and written straight
     * back. Other services stay listed throughout and are not disturbed.
     */
    private fun rewriteAndAwaitBind(
        context: Context,
        wanted: ComponentName,
        timeoutMs: Long
    ): Boolean {
        val written = runCatching {
            val resolver = context.contentResolver
            // Every identity of this service is dropped from the list first, so
            // the two can never end up enabled at the same time.
            val ours = components(context).flatMap {
                listOf(it.flattenToString(), it.flattenToShortString())
            }
            val others = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
                .split(':')
                .filter { it.isNotEmpty() && it !in ours }

            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                others.joinToString(":")
            )
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + wanted.flattenToString()).joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.isSuccess

        if (!written) {
            if (!repairReported) {
                repairReported = true
                EventLog.add(context, EventLevel.ERROR, "Could not rewrite the accessibility setting")
            }
            return false
        }

        // Writing the setting is not the same as being bound, so wait for the
        // callback rather than announcing a repair that did not happen.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (InstallAutoClickService.bound == null && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS)
        }
        return InstallAutoClickService.bound != null
    }
}
