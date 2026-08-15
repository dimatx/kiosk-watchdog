package com.shymoose.wifiwatchdog

/**
 * A second identity for [InstallAutoClickService].
 *
 * Replacing the APK can leave the accessibility manager believing a bind for the
 * original component is still in flight. That record is keyed by component name
 * and nothing clears it, so every later attempt to enable that component is
 * skipped - by this app, and by the system's own Accessibility screen. The only
 * other way out is a reboot, which is not something a wall display should need
 * after an app update.
 *
 * Enabling this component instead sidesteps the stale record, because it is a
 * different name. Behaviour is identical; only the manifest entry differs.
 */
class InstallAutoClickServiceAlt : InstallAutoClickService()
