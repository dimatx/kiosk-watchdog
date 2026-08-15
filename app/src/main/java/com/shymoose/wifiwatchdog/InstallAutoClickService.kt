package com.shymoose.wifiwatchdog

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Outcome of asking for the accessibility service to be turned on.
 *
 * [NEEDS_MANUAL] is not a failure — it is the expected answer on a device the
 * app was installed on without adb, where the only way in is the system
 * accessibility screen.
 */
enum class SetupResult { ALREADY_ON, ENABLED, NEEDS_MANUAL, FAILED }

/**
 * Confirms the package installer's "update to this existing application?" dialog
 * so an app that updates itself can finish without someone walking to the wall.
 *
 * These displays run headless in fixed locations. Kiosk Satellite ships its own
 * updates, and Android 8.1 has no rootless silent install — [INSTALL_PACKAGES]
 * is signature|privileged and cannot be granted. Driving the dialog through an
 * accessibility service is the only path left.
 *
 * Two things keep that from being a blanket "click yes on anything":
 *
 *  1. Events are filtered to the installer package, so nothing else is ever
 *     touched.
 *  2. The dialog's app label must appear in [Prefs.autoInstallAllowlist]. The
 *     node tree exposes the human-readable label rather than the target
 *     package, so the label is what gets matched.
 *
 * After a successful install the completion screen is dismissed too, preferring
 * OPEN over DONE so the kiosk comes back up by itself.
 */
open class InstallAutoClickService : AccessibilityService() {

    /** Text on the button that starts the install. */
    private val confirmLabels = setOf("install", "update", "ok")

    /** Resource id suffixes AOSP has used for that button across 8.x variants. */
    private val confirmIds = listOf("ok_button", "button1", "install_confirm_question")

    /** Completion screen, most-preferred first: OPEN relaunches the kiosk. */
    private val doneLabels = listOf("open", "launch", "done")
    private val doneIds = listOf("launch_button", "done_button")

    /**
     * Guards against clicking the same screen repeatedly while the installer
     * emits a burst of content-changed events.
     */
    private var lastClickAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Prefs(this).autoInstallServiceEverOn = true
        EventLog.add(this, EventLevel.INFO, "Install auto-click service connected")
        sweepCurrentWindow()
    }

    /**
     * Handles whatever is already on screen at connect time.
     *
     * Accessibility events only fire on change, so a service that starts while a
     * dialog is already up would sit there staring at it. That is the normal
     * case after this app updates *itself*: the install kills the process, and
     * by the time the framework rebinds, the completion screen has long since
     * finished animating in.
     *
     * Retried a few times because [rootInActiveWindow] is often still null in
     * the first moments after a bind.
     */
    private fun sweepCurrentWindow() {
        val handler = android.os.Handler(mainLooper)
        SWEEP_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (!Prefs(this).autoInstallEnabled) return@postDelayed
                val root = rootInActiveWindow ?: return@postDelayed
                try {
                if (root.packageName?.toString()?.let { isInstaller(this, it) } == true) handle(root)
                } catch (t: Throwable) {
                    EventLog.add(this, EventLevel.ERROR, "Install auto-click sweep failed: ${t.message}")
                } finally {
                    root.recycle()
                }
            }, delay)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs(this).autoInstallEnabled) return

        val pkg = event.packageName?.toString() ?: return
        if (!isInstaller(this, pkg)) return

        if (SystemClock.elapsedRealtime() - lastClickAt < CLICK_COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        try {
            handle(root)
        } catch (t: Throwable) {
            EventLog.add(this, EventLevel.ERROR, "Install auto-click failed: ${t.message}")
        } finally {
            root.recycle()
        }
    }

    private fun handle(root: AccessibilityNodeInfo) {
        val texts = collectText(root)

        // Completion screen first: it has no allowlist-bearing prompt text, and
        // reaching it means we already approved whatever is on it. Recognised by
        // its buttons as well as its wording, since the wording is localised and
        // varies between ROMs while the view ids do not.
        val finished = texts.any { it in COMPLETION_MARKERS } ||
            findPreferred(root, emptyList(), doneIds) != null
        if (finished) {
            if (clickFirst(root, doneLabels, doneIds)) {
                EventLog.add(this, EventLevel.ACTION, "Dismissed install completion screen")
            }
            return
        }

        val allowlist = Prefs(this).autoInstallAllowlist
        val selfTest = SystemClock.elapsedRealtime() < selfTestUntil
        val effective = if (selfTest) allowlist + ownLabel() else allowlist
        val matched = effective.firstOrNull { allowed ->
            texts.any { it.equals(allowed, ignoreCase = true) }
        }
        if (matched == null) {
            // Only worth a line when a confirm button is actually on screen —
            // otherwise every installer window would log.
            if (findNode(root, confirmLabels, confirmIds) != null) {
                EventLog.add(
                    this,
                    EventLevel.WARN,
                    "Install dialog not auto-confirmed — no allowlist match. Labels: ${texts.take(6)}"
                )
            }
            return
        }

        if (clickFirst(root, confirmLabels.toList(), confirmIds)) {
            EventLog.add(this, EventLevel.ACTION, "Confirmed install of \"$matched\"")
        } else {
            EventLog.add(
                this,
                EventLevel.ERROR,
                "Allowlisted \"$matched\" but no clickable confirm button found. Nodes: ${dump(root)}"
            )
        }
    }

    // ------------------------------------------------------------------- nodes

    /** The name this app shows in the install dialog. */
    private fun ownLabel(): String =
        applicationInfo.loadLabel(packageManager).toString()

    private fun clickFirst(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        ids: List<String>
    ): Boolean {
        val node = findPreferred(root, labels, ids) ?: return false
        val clicked = clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        if (clicked) lastClickAt = SystemClock.elapsedRealtime()
        return clicked
    }

    /**
     * Finds a button honouring the order of [labels], then of [ids].
     *
     * A single walk returning the first tree-order match would ignore that
     * order, which matters on the completion screen: DONE is laid out before
     * OPEN, so the closest match wins and the display drops to the launcher
     * instead of returning to the kiosk. One pass per candidate keeps the
     * caller's preference authoritative. The trees involved are dialogs.
     */
    private fun findPreferred(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        ids: List<String>
    ): AccessibilityNodeInfo? {
        labels.forEach { label ->
            findNode(root, setOf(label), emptyList())?.let { return it }
        }
        ids.forEach { id ->
            findNode(root, emptySet(), listOf(id))?.let { return it }
        }
        return null
    }

    /**
     * Text match wins over id match: the ids differ between AOSP variants and
     * were never confirmed on this ROM, whereas the visible label is stable.
     */
    private fun findNode(
        root: AccessibilityNodeInfo,
        labels: Set<String>,
        ids: List<String>
    ): AccessibilityNodeInfo? {
        var byId: AccessibilityNodeInfo? = null
        walk(root) { node ->
            val text = node.text?.toString()?.trim()?.lowercase()
            if (text != null && text in labels && clickable(node) != null) return@walk node
            if (byId == null) {
                val viewId = node.viewIdResourceName
                if (viewId != null && ids.any { viewId.endsWith("id/$it") } && clickable(node) != null) {
                    byId = node
                }
            }
            null
        }?.let { return it }
        return byId
    }

    /** The text often sits on a non-clickable child of the real button. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 4) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun collectText(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        walk(root) { node ->
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            null
        }
        return out
    }

    private fun dump(root: AccessibilityNodeInfo): String {
        val out = mutableListOf<String>()
        walk(root) { node ->
            if (node.isClickable) {
                out.add("${node.viewIdResourceName ?: "?"}='${node.text ?: ""}'")
            }
            null
        }
        return out.joinToString(", ").take(400)
    }

    /** Depth-first walk; a non-null return from [visit] short-circuits. */
    private fun walk(
        node: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        visit(node)?.let { return it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visit)?.let { return it }
        }
        return null
    }

    companion object {
        private const val CLICK_COOLDOWN_MS = 1_500L

        /** How long to wait for the framework to actually bind after the write. */
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 250L

        /** Set once the framework binds; the only proof the write took effect. */
        @Volatile
        private var instance: InstallAutoClickService? = null

        private val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        @Volatile
        private var resolvedInstallers: Set<String>? = null

        /**
         * Whether [pkg] is the package installer on *this* device.
         *
         * The two AOSP names are only a starting point. A ROM without Google
         * Play Services has neither, and a vendor is free to ship its own — Fire
         * OS being the case in point. Guessing wrong is the worst kind of
         * failure here: every event is dropped, so nothing happens and nothing
         * is logged.
         *
         * So the real handler of ACTION_INSTALL_PACKAGE is resolved from the
         * package manager, with a name-shaped fallback for anything that calls
         * itself a package installer. Resolved once and cached; the answer
         * cannot change while the process lives.
         */
        fun isInstaller(context: Context, pkg: String): Boolean {
            if (pkg in INSTALLER_PACKAGES) return true
            if (pkg.endsWith("packageinstaller")) return true
            return pkg in resolveInstallers(context)
        }

        private fun resolveInstallers(context: Context): Set<String> {
            resolvedInstallers?.let { return it }
            val found = runCatching {
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(
                    Uri.parse("content://probe/probe.apk"),
                    "application/vnd.android.package-archive"
                )
                context.packageManager.queryIntentActivities(intent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
            }.getOrElse { emptySet() }

            val all = INSTALLER_PACKAGES + found
            resolvedInstallers = all
            val extra = found - INSTALLER_PACKAGES
            if (extra.isNotEmpty()) {
                EventLog.add(context, EventLevel.INFO, "Package installer on this device: $extra")
            }
            return all
        }

        /** Strings that only appear once the install has already run. */
        private val COMPLETION_MARKERS = setOf("App installed.", "App installed", "Installing…", "Installing...")

        /** When to look at an already-visible window after connecting. */
        private val SWEEP_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L)

        /** Floor between rebind attempts, so a refusal cannot become a log stream. */
        private const val REPAIR_MIN_INTERVAL_MS = 5 * 60_000L

        /** How long to wait for the framework to bind after rewriting the setting. */
        private const val REPAIR_CONFIRM_MS = 3_000L
        /** Time for the package change to reach the accessibility manager. */
        private const val STANDBY_SETTLE_MS = 1_500L

        @Volatile
        private var nextRepairAt = 0L

        /** Whether the current outage has already been reported once. */
        @Volatile
        private var repairReported = false

        /** How long a self-test keeps this app's own name allowlisted. */
        private const val SELF_TEST_WINDOW_MS = 120_000L

        @Volatile
        private var selfTestUntil = 0L

        /**
         * Temporarily treats this app's own name as allowlisted so the
         * self-test dialog gets confirmed. Kept to a short window rather than a
         * permanent entry, so a real self-update still needs a human.
         */
        fun armSelfTest() {
            selfTestUntil = SystemClock.elapsedRealtime() + SELF_TEST_WINDOW_MS
        }

        fun component(context: Context): ComponentName =
            ComponentName(context.packageName, InstallAutoClickService::class.java.name)

        /** The standby identity, used when the primary component is being skipped. */
        fun altComponent(context: Context): ComponentName =
            ComponentName(context.packageName, InstallAutoClickServiceAlt::class.java.name)

        /** Both identities, primary first: only one is ever enabled at a time. */
        private fun components(context: Context): List<ComponentName> =
            listOf(component(context), altComponent(context))

        fun isEnabled(context: Context): Boolean {
            val names = components(context).flatMap {
                listOf(it.flattenToString(), it.flattenToShortString())
            }
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it in names }
        }

        /**
         * Opens the system accessibility screen so the service can be turned on
         * by hand. This is the only route on a device that was never reachable
         * over adb — a Fire tablet, say — where WRITE_SECURE_SETTINGS cannot be
         * granted at all.
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
         * Rebinds the service when the setting still lists it but nothing is
         * actually running.
         *
         * `am force-stop` — a task killer, a "clear all", or a user poking the
         * app info screen — tears the service down and the framework does not
         * bring it back. Worse, when this is the *only* enabled accessibility
         * service, Android empties the list and sets `accessibility_enabled` to
         * 0, so the setting stops claiming it is on at all. Either way nothing
         * looks wrong while auto-install has silently stopped working.
         *
         * [Prefs.autoInstallServiceEverOn] is what separates that from a user
         * who deliberately switched the service off, since the setting alone
         * cannot tell the two apart. Turning off "Confirm update dialogs" is
         * the way to stop this for good.
         *
         * The value has to genuinely change or SettingsProvider drops an
         * identical write without notifying, so this removes the component and
         * writes it straight back. Other services stay listed throughout and
         * are not disturbed.
         *
         * No-op without WRITE_SECURE_SETTINGS: on a device set up by hand there
         * is nothing to do but let the user re-toggle it.
         */
        fun repairIfUnbound(context: Context) {
            if (instance != null) {
                // Healthy: re-arm, so a later outage is retried and reported again.
                nextRepairAt = 0L
                repairReported = false
                return
            }
            val prefs = Prefs(context)
            if (!prefs.autoInstallEnabled) return
            if (!isEnabled(context) && !prefs.autoInstallServiceEverOn) return
            if (!AirplaneMode.hasPermission(context)) return

            // The framework can decline to bind for reasons nothing here can fix,
            // and the watchdog ticks every few seconds. Without a floor between
            // attempts this turns into a permanent stream of identical events.
            val now = SystemClock.elapsedRealtime()
            if (now < nextRepairAt) return
            nextRepairAt = now + REPAIR_MIN_INTERVAL_MS

            if (rewriteAndAwaitBind(context, component(context))) {
                repairReported = false
                hideStandby(context)
                EventLog.add(
                    context,
                    EventLevel.ACTION,
                    "Auto-install service had stopped — restarted it"
                )
                return
            }

            // Replacing the app can leave the accessibility manager believing a
            // bind for this component is still in flight. That record is keyed by
            // component name and nothing clears it, so the component is skipped
            // forever after — by this app and by the system's own Accessibility
            // screen alike. Only a reboot clears it, which a wall display should
            // not need because an app updated.
            //
            // The standby identity is a different name, so the stale record does
            // not apply to it. Behaviour is identical.
            if (showStandby(context) && rewriteAndAwaitBind(context, altComponent(context))) {
                repairReported = false
                EventLog.add(
                    context,
                    EventLevel.ACTION,
                    "Auto-install service was blocked by the system — started the standby copy"
                )
                return
            }

            // Said once per outage. Repeating it every attempt would bury the
            // events that actually describe what the watchdog is doing.
            if (!repairReported) {
                repairReported = true
                EventLog.add(
                    context,
                    EventLevel.WARN,
                    "Auto-install service will not start — turn it on under " +
                        "Settings, Accessibility"
                )
            }
        }

        /**
         * Publishes the standby component so the framework will consider binding it.
         *
         * It ships disabled, which is what keeps a duplicate row out of the
         * Accessibility list on the devices that never need it. Changing the state
         * of a component this app owns needs no permission, and
         * [PackageManager.DONT_KILL_APP] leaves the running watchdog alone.
         */
        private fun showStandby(context: Context): Boolean =
            setStandbyEnabled(context, true)

        /** Puts the standby back out of sight once the primary is working again. */
        private fun hideStandby(context: Context) {
            if (isStandbyBound()) return
            setStandbyEnabled(context, false)
        }

        private fun isStandbyBound(): Boolean = instance is InstallAutoClickServiceAlt

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

        /** Writes the enabled list, then waits for the bind callback that proves it took. */
        private fun rewriteAndAwaitBind(context: Context, wanted: ComponentName): Boolean {
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
            val deadline = SystemClock.elapsedRealtime() + REPAIR_CONFIRM_MS
            while (instance == null && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(POLL_MS)
            }
            return instance != null
        }

        /**
         * Turns the service on using the WRITE_SECURE_SETTINGS grant the app
         * holds for airplane mode, so no adb round-trip is needed.
         *
         * Without that grant the setting is not writable by any means, so the
         * caller is told to send the user to the system screen instead. That is
         * the normal path on devices that cannot be reached over adb.
         *
         * The setting is a colon-separated list shared with every other
         * accessibility service, so it is appended to rather than replaced.
         *
         * Blocks for up to [BIND_TIMEOUT_MS] waiting for the framework to
         * actually bind — the write silently no-ops in some states, so the
         * callback is the only real proof. Call this off the main thread.
         */
        fun enable(context: Context): SetupResult {
            if (isEnabled(context) && instance != null) return SetupResult.ALREADY_ON
            if (!AirplaneMode.hasPermission(context)) return SetupResult.NEEDS_MANUAL

            // Primary first, then the standby identity: see repairIfUnbound for why
            // the primary can be permanently skipped by the framework.
            if (rewriteAndAwaitBind(context, component(context))) {
                hideStandby(context)
                EventLog.add(context, EventLevel.ACTION, "Enabled install auto-click service")
                return SetupResult.ENABLED
            }
            if (showStandby(context) && rewriteAndAwaitBind(context, altComponent(context))) {
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
    }
}
