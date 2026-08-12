package com.shymoose.wifiwatchdog

import android.os.Bundle
import android.text.InputType
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

        listOf(
            Prefs.KEY_PROBE_HOST,
            Prefs.KEY_PROBE_PORT,
            Prefs.KEY_INTERVAL,
            Prefs.KEY_T_REASSOCIATE,
            Prefs.KEY_T_SOFT,
            Prefs.KEY_T_HARD,
            Prefs.KEY_WEBHOOK
        ).forEach { key ->
            findPreference<EditTextPreference>(key)?.summaryProvider =
                EditTextPreference.SimpleSummaryProvider.getInstance()
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
        return super.onPreferenceTreeClick(preference)
    }
}
