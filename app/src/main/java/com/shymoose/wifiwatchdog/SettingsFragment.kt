package com.shymoose.wifiwatchdog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        numeric(Prefs.KEY_PROBE_PORT)
        numeric(Prefs.KEY_INTERVAL)
        numeric(Prefs.KEY_T_REASSOCIATE)
        numeric(Prefs.KEY_T_SOFT)
        numeric(Prefs.KEY_T_HARD)
        numeric(Prefs.KEY_HEARTBEAT_INTERVAL)

        listOf(
            Prefs.KEY_PROBE_HOST,
            Prefs.KEY_PROBE_PORT,
            Prefs.KEY_INTERVAL,
            Prefs.KEY_T_REASSOCIATE,
            Prefs.KEY_T_SOFT,
            Prefs.KEY_T_HARD,
            Prefs.KEY_NTFY_URL,
            Prefs.KEY_NTFY_TOPIC,
            Prefs.KEY_NTFY_USER,
            Prefs.KEY_HEARTBEAT_URL,
            Prefs.KEY_HEARTBEAT_INTERVAL,
            Prefs.KEY_AUTO_INSTALL_ALLOWLIST
            // Deliberately not the password — the summary is rendered on screen.
        ).forEach { key ->
            findPreference<EditTextPreference>(key)?.summaryProvider =
                EditTextPreference.SimpleSummaryProvider.getInstance()
        }

        findPreference<EditTextPreference>(Prefs.KEY_NTFY_PASSWORD)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setSelection(it.text?.length ?: 0)
        }

        disableUnavailable()
    }

    /**
     * Hard reset and airplane mode both need WRITE_SECURE_SETTINGS, which is
     * `signature|privileged|development` — it can only ever be granted over adb.
     * On a device without it there is nothing the user could do here, so the
     * controls are disabled rather than left looking operable.
     */
    private fun disableUnavailable() {
        if (AirplaneMode.hasPermission(requireContext())) return

        val reason = getString(R.string.pref_needs_adb)
        listOf(
            Prefs.KEY_HARD_ENABLED,
            Prefs.KEY_AIRPLANE_ENABLED,
            Prefs.KEY_AIRPLANE_DWELL
        ).forEach { key ->
            findPreference<Preference>(key)?.apply {
                isEnabled = false
                summaryProvider = null
                summary = reason
            }
        }
    }

    private fun numeric(key: String) {
        findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_NUMBER
            it.setSelection(it.text?.length ?: 0)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "restart_service") {
            WatchdogService.start(requireContext())
            return true
        }
        if (preference.key == Prefs.KEY_NTFY_TEST) {
            val context = requireContext()
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchdogService::class.java)
                    .setAction(WatchdogService.ACTION_SEND_TEST)
            )
            Toast.makeText(context, R.string.toast_ntfy_test, Toast.LENGTH_SHORT).show()
            return true
        }
        if (preference.key == Prefs.KEY_HEARTBEAT_TEST) {
            val context = requireContext()
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchdogService::class.java)
                    .setAction(WatchdogService.ACTION_SEND_HEARTBEAT)
            )
            Toast.makeText(context, R.string.toast_heartbeat_test, Toast.LENGTH_SHORT).show()
            return true
        }
        if (preference.key == Prefs.KEY_AUTO_INSTALL_SETUP) {
            val context = requireContext().applicationContext
            val handler = Handler(Looper.getMainLooper())
            // enable() waits for the framework to bind, so it cannot run here.
            Thread {
                val result = InstallAutoClickService.enable(context)
                handler.post {
                    val message = when (result) {
                        SetupResult.ALREADY_ON -> R.string.toast_auto_install_already
                        SetupResult.ENABLED -> R.string.toast_auto_install_on
                        SetupResult.FAILED -> R.string.toast_auto_install_failed
                        // No WRITE_SECURE_SETTINGS, so hand off to the system
                        // screen. The toast has to name the row to look for,
                        // because that screen gives no hint about what sent the
                        // user there.
                        SetupResult.NEEDS_MANUAL ->
                            if (InstallAutoClickService.openAccessibilitySettings(context)) {
                                R.string.toast_auto_install_manual
                            } else {
                                R.string.toast_auto_install_failed
                            }
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }.start()
            return true
        }
        if (preference.key == Prefs.KEY_AUTO_INSTALL_TEST) {
            val context = requireContext().applicationContext
            val handler = Handler(Looper.getMainLooper())
            // Copies the APK, so it cannot run on the main thread.
            Thread {
                val error = InstallSelfTest.run(context)
                handler.post {
                    val message = error ?: getString(R.string.toast_auto_install_test)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }.start()
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
