package com.shymoose.wifiwatchdog

import android.content.Intent
import android.os.Bundle
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
            Prefs.KEY_HEARTBEAT_INTERVAL
            // Deliberately not the password — the summary is rendered on screen.
        ).forEach { key ->
            findPreference<EditTextPreference>(key)?.summaryProvider =
                EditTextPreference.SimpleSummaryProvider.getInstance()
        }

        findPreference<EditTextPreference>(Prefs.KEY_NTFY_PASSWORD)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.setSelection(it.text?.length ?: 0)
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
        return super.onPreferenceTreeClick(preference)
    }
}
