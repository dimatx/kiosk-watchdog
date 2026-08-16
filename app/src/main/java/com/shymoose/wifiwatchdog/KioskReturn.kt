package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock

/**
 * Puts the kiosk app back in front when the display has stopped showing it.
 *
 * A wall display is supposed to show one thing, and there are two ways it stops.
 * This app can end up in front without anyone meaning to - updating itself
 * leaves the installer offering OPEN, which relaunches the watchdog rather than
 * the kiosk, and a stray tap during maintenance does the same. Or the kiosk
 * simply goes away: it crashes, or is stopped, and the display drops to the
 * launcher and sits there.
 *
 * Two cases, two different delays, because they mean different things:
 *
 *  - This app in front is ambiguous. Somebody may be standing there using it, so
 *    it waits out the configured delay and measures that delay from their last
 *    touch rather than from when the window appeared.
 *  - The launcher in front is not ambiguous. Nothing is being used, so it needs
 *    only a short grace period - long enough not to fight somebody who pressed
 *    Home on their way to opening something.
 *
 * Anything else in front is left strictly alone. Recovering from "not the kiosk"
 * in general would fight a person using system settings, and there is no way to
 * tell that from a display that has drifted.
 *
 * The kiosk having died is not detected directly: from API 26 a process listing
 * returns only the caller's own processes. It does not need to be, since a dead
 * kiosk lands on the launcher, which is the case above.
 *
 * Detection rides on the accessibility service, which already knows which window
 * is in front, so this needs no additional permission. Without that service
 * bound the feature simply does not run.
 */
object KioskReturn {

    /** Long enough to let somebody press Home and open something themselves. */
    private const val LAUNCHER_GRACE_MS = 60_000L

    /** Owns the status bar and keyguard, which overlay whatever is really running. */
    private const val SYSTEM_UI = "com.android.systemui"

    @Volatile
    private var awaySince = 0L

    @Volatile
    private var lastTouchAt = 0L

    /** Resolved lazily and kept: the home app does not change on these displays. */
    @Volatile
    private var launcherPackage: String? = null

    /** Stops a display with no kiosk installed repeating the same complaint. */
    @Volatile
    private var reportedMissing = false

    /**
     * Records that somebody is actually using this app.
     *
     * The delay is measured from the last interaction rather than from when the
     * window appeared, so configuring a display for longer than the timeout does
     * not get interrupted halfway through. Held in memory: it only needs to
     * outlive the visit, and writing on every touch would be absurd.
     */
    fun noteInteraction() {
        lastTouchAt = SystemClock.elapsedRealtime()
    }

    fun check(context: Context) {
        val prefs = Prefs(context)
        val target = prefs.kioskPackage
        if (target.isEmpty()) return

        val afterMs = prefs.kioskReturnAfterMin * 60_000L
        if (afterMs <= 0L) return

        // Nothing to correct on a dark screen, and the window in front of one is
        // the system's, not the app's. Held rather than reset: the wait carries on
        // while the display sleeps, so walking up to a display that was left on
        // the wrong screen snaps it back rather than starting the clock again.
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == false) return

        val front = InstallAutoClickService.foregroundPackage()
        // A null reading is "not known", not "not the kiosk". The accessibility
        // service may be unbound or between windows, and acting on that would
        // relaunch the kiosk on top of whatever is genuinely showing.
        if (front == null) return
        // The status bar and keyguard sit in front of whatever is actually
        // running, so they say nothing about it either way.
        if (front == SYSTEM_UI) return

        if (front == target) {
            awaySince = 0L
            reportedMissing = false
            return
        }

        val ours = front == context.packageName
        val waitMs = when {
            ours -> afterMs
            front == launcherPackage(context) -> LAUNCHER_GRACE_MS
            // Somebody else's app, or a system screen. Not ours to close.
            else -> {
                awaySince = 0L
                return
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (awaySince == 0L) {
            awaySince = now
            return
        }
        if (now - awaySince < waitMs) return
        // Somebody is still working in this app; the clock restarts from their
        // last touch. Only meaningful for our own windows - touches elsewhere are
        // invisible to us, which is the other reason the launcher case is short.
        if (ours && lastTouchAt != 0L && now - lastTouchAt < waitMs) return

        awaySince = 0L
        launch(context, target)
    }

    private fun launcherPackage(context: Context): String? {
        launcherPackage?.let { return it }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = runCatching {
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        launcherPackage = resolved
        return resolved
    }

    private fun launch(context: Context, target: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(target)
        if (intent == null) {
            // Nothing to go back to. Said at INFO rather than as a warning: a
            // display that does not run this kiosk is a normal configuration,
            // and said once, because otherwise it repeats for as long as the
            // launcher is showing.
            if (!reportedMissing) {
                reportedMissing = true
                EventLog.add(context, EventLevel.INFO, "Kiosk app \"$target\" is not installed")
            }
            return
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            EventLog.add(context, EventLevel.ACTION, "Returned the display to the kiosk app")
        }.onFailure {
            EventLog.add(context, EventLevel.ERROR, "Could not return to the kiosk app: ${it.message}")
        }
    }
}
