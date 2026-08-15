package com.shymoose.wifiwatchdog

import android.content.Context
import android.os.SystemClock

/**
 * Puts the kiosk app back in front when this app has been left showing.
 *
 * A wall display is supposed to show one thing. This app can end up in front of
 * it without anyone meaning to: updating itself leaves the installer offering
 * OPEN, which relaunches the watchdog rather than the kiosk, and a stray tap
 * during maintenance does the same. Either way the display then sits on a
 * settings screen until somebody notices.
 *
 * Only this app's own windows count. Deliberately not "anything that is not the
 * kiosk" - that would fight a person standing at the display trying to use
 * system settings, and the point is to undo the app's own intrusions.
 *
 * Detection rides on the accessibility service, which already knows which
 * window is in front, so this needs no additional permission. Without that
 * service bound the feature simply does not run.
 */
object KioskReturn {

    @Volatile
    private var ourWindowSince = 0L

    fun check(context: Context) {
        val prefs = Prefs(context)
        val target = prefs.kioskPackage
        if (target.isEmpty()) return

        val afterMs = prefs.kioskReturnAfterMin * 60_000L
        if (afterMs <= 0L) return

        val front = InstallAutoClickService.foregroundPackage()
        if (front == null || front != context.packageName) {
            ourWindowSince = 0L
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (ourWindowSince == 0L) {
            ourWindowSince = now
            return
        }
        if (now - ourWindowSince < afterMs) return

        ourWindowSince = 0L
        launch(context, target)
    }

    private fun launch(context: Context, target: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(target)
        if (intent == null) {
            // Nothing to go back to. Said at INFO rather than as a warning: a
            // display that does not run this kiosk is a normal configuration.
            EventLog.add(context, EventLevel.INFO, "Kiosk app \"$target\" is not installed")
            return
        }
        runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            EventLog.add(context, EventLevel.ACTION, "Returned the display to the kiosk app")
        }.onFailure {
            EventLog.add(context, EventLevel.ERROR, "Could not return to the kiosk app: ${it.message}")
        }
    }
}
