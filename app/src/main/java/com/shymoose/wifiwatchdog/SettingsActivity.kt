package com.shymoose.wifiwatchdog

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen

class SettingsActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    /**
     * Opens a nested screen as its own fragment.
     *
     * Tapping a nested PreferenceScreen does nothing on its own; the library
     * leaves the navigation to the host so it can decide how the screen is
     * presented. Re-rooting a fresh fragment at the tapped key keeps back
     * behaviour and the title in step with the rest of the app.
     */
    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        screen: PreferenceScreen
    ): Boolean {
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment.forScreen(screen.key))
            .addToBackStack(screen.key)
            .commit()
        return true
    }

    /** Keeps the return-to-kiosk timer from firing mid-configuration. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        KioskReturn.noteInteraction()
    }

    override fun onSupportNavigateUp(): Boolean {        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        finish()
        return true
    }
}
