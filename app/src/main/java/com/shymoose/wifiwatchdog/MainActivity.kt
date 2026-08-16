package com.shymoose.wifiwatchdog

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.shymoose.wifiwatchdog.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: EventAdapter
    private lateinit var recovery: WifiRecovery
    private lateinit var probe: NetProbe

    private val handler = Handler(Looper.getMainLooper())
    private val logListener: () -> Unit = { handler.post { refreshLog() } }
    private val ticker = object : Runnable {
        override fun run() {
            // Only the status card: it carries elapsed-time text that goes stale on
            // its own. The event list is pushed by EventLog instead, because
            // re-reading it here would re-parse the whole log twice a second.
            refreshStatus()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Kiosk Satellite keeps the panel lit with FLAG_KEEP_SCREEN_ON, which only
        // applies while its window is the focused one. Whenever this activity is in
        // front that protection lapses and the OS idle timeout turns the display off
        // — on a wall-mounted kiosk it then stays dark until someone touches it, and
        // the return-to-kiosk timer is usually longer than the timeout, so the panel
        // dies before the kiosk comes back. Hold the flag for as long as we are the
        // one on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        recovery = WifiRecovery(this)
        probe = NetProbe(this)

        adapter = EventAdapter()
        binding.eventList.layoutManager = LinearLayoutManager(this)
        binding.eventList.adapter = adapter

        binding.versionLabel.text =
            getString(R.string.version_footer, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        binding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.enabled) return@setOnCheckedChangeListener
            prefs.enabled = checked
            if (checked) {
                WatchdogService.start(this)
            } else {
                WatchdogService.stop(this)
            }
            refresh()
        }

        binding.recoverButton.setOnClickListener { confirmManualRecovery() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.copyCommandButton.setOnClickListener { copyGrantCommand() }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.configButton.setOnClickListener {
            ConfigServer.start(this)
            refresh()
        }

        requestLocationIfNeeded()

        if (prefs.enabled) WatchdogService.start(this)
    }

    override fun onStart() {
        super.onStart()
        EventLog.addListener(logListener)
        // Draw the log once here: from now on it is pushed by EventLog, and the
        // ticker only keeps the status card's elapsed-time text current.
        refresh()
        handler.post(ticker)
        // Opens a short LAN-only window so the wall-mounted display can be configured
        // from a desktop browser. Bringing the app back to the front re-arms it, which
        // is the only affordance the user has on a device with no real keyboard.
        ConfigServer.start(this)
    }

    override fun onStop() {
        super.onStop()
        EventLog.removeListener(logListener)
        handler.removeCallbacks(ticker)
    }

    /**
     * The airplane cycle hands the foreground to the system voice activity, which can
     * leave this window visible but with its onStart-scoped ticker already torn down.
     * Re-arming here guarantees the log is live whenever the window is interactive.
     */
    override fun onResume() {
        super.onResume()
        EventLog.addListener(logListener)
        refresh()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Keeps the return-to-kiosk timer from firing while someone is using this. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        KioskReturn.noteInteraction()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Both of these are built on WRITE_SECURE_SETTINGS. Without it there is
        // nothing the user could do on-device to make them work, so hide them
        // rather than offer an action that can only fail.
        val privileged = AirplaneMode.hasPermission(this)
        menu.findItem(R.id.action_airplane_cycle)?.isVisible = privileged
        menu.findItem(R.id.action_release_assistant)?.isVisible = privileged
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }

        R.id.action_airplane_cycle -> {
            confirmAirplaneCycle(); true
        }

        R.id.action_release_assistant -> {
            releaseAssistant(); true
        }

        R.id.action_clear_log -> {
            EventLog.clear(this); refresh(); true
        }

        R.id.action_exit -> {
            ConfigServer.stop()
            Toast.makeText(this, R.string.exit_toast, Toast.LENGTH_SHORT).show()
            finish(); true
        }

        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------- UI

    private fun refresh() {
        refreshStatus()
        refreshLog()
    }

    private fun refreshStatus() {
        val state = WatchdogService.State
        val enabled = prefs.enabled

        // The listener set in onCreate ignores a value that already matches the
        // pref, so this cannot loop back on itself.
        if (binding.enabledSwitch.isChecked != enabled) binding.enabledSwitch.isChecked = enabled

        val online = state.online
        val statusText = when {
            !enabled -> getString(R.string.status_paused)
            online -> getString(R.string.status_online)
            else -> getString(R.string.status_offline)
        }
        val color = when {
            !enabled -> R.color.status_paused
            online -> R.color.status_ok
            else -> R.color.status_bad
        }
        binding.statusText.text = statusText
        binding.statusDot.setColorFilter(ContextCompat.getColor(this, color))

        binding.statusDetail.text = if (!enabled) {
            getString(R.string.status_detail_paused)
        } else {
            val target = WatchdogService.describeTarget(this, probe.resolveTarget(prefs))
            if (online) {
                getString(R.string.status_detail_online, target)
            } else {
                val down = if (prefs.lastGoodAtMillis > 0) {
                    (System.currentTimeMillis() - prefs.lastGoodAtMillis) / 1000
                } else 0
                getString(R.string.status_detail_offline_full, target, WatchdogService.formatDuration(down))
            }
        }

        val wifi = state.wifi
        binding.wifiInfo.text = when {
            wifi == null -> getString(R.string.wifi_unknown)
            !wifi.wifiEnabled -> getString(R.string.wifi_off)
            wifi.ssid == null -> getString(R.string.wifi_not_associated)
            else -> getString(
                R.string.wifi_details,
                wifi.ssid,
                wifi.rssi?.toString() ?: "?",
                wifi.linkSpeedMbps?.toString() ?: "?"
            )
        }

        binding.lastCheck.text = if (state.lastCheckAt == 0L) {
            getString(R.string.last_check_never)
        } else {
            val ago = (System.currentTimeMillis() - state.lastCheckAt) / 1000
            getString(R.string.last_check, WatchdogService.formatDuration(ago))
        }

        val needsGrant = !recovery.hasSecureSettingsPermission()
        binding.permissionCard.visibility = if (needsGrant) View.VISIBLE else View.GONE

        val dozed = !BatteryOptimization.isWhitelisted(this)
        binding.batteryCard.visibility = if (dozed) View.VISIBLE else View.GONE

        refreshConfigCard()
    }

    /**
     * Re-renders the event list.
     *
     * Reading the log parses every stored event, so this runs only when something
     * has actually been logged — [EventLog] pushes that through [logListener] —
     * rather than on the status ticker.
     */
    private fun refreshLog() {
        adapter.submit(EventLog.read(this))
        binding.emptyLabel.visibility =
            if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun refreshConfigCard() {
        val url = ConfigServer.url(this)
        if (ConfigServer.isRunning && url != null) {
            binding.configAddress.text = url
            binding.configStatus.text = getString(
                R.string.config_closing,
                WatchdogService.formatDuration(ConfigServer.secondsRemaining())
            )
            binding.configButton.setText(R.string.config_extend)
        } else {
            binding.configAddress.text = getString(R.string.config_hint)
            binding.configStatus.text = getString(R.string.config_stopped)
            binding.configButton.setText(R.string.config_open)
        }
    }

    private fun confirmManualRecovery() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recover_title)
            .setMessage(R.string.recover_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recover_confirm) { _, _ ->
                WatchdogService.forceHardReset(this)
                Snackbar.make(binding.root, R.string.recover_started, Snackbar.LENGTH_LONG).show()
            }
            .show()
    }

    private fun confirmAirplaneCycle() {
        if (!AirplaneMode.isAvailable(this)) {
            Snackbar.make(binding.root, R.string.airplane_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.airplane_title)
            .setMessage(getString(R.string.airplane_message, prefs.airplaneDwellSec))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.airplane_confirm) { _, _ ->
                WatchdogService.forceAirplaneCycle(this)
                Snackbar.make(binding.root, R.string.airplane_started, Snackbar.LENGTH_LONG).show()
            }
            .show()
    }

    private fun releaseAssistant() {
        if (!AirplaneMode.isAssistantOwned(this)) {
            Snackbar.make(binding.root, R.string.assistant_not_owned, Snackbar.LENGTH_SHORT).show()
            return
        }
        val released = AirplaneMode.releaseAssistant(this)
        val msg = if (released) R.string.assistant_released else R.string.assistant_release_failed
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    private fun copyGrantCommand() {
        val cmd = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        copyToClipboard(cmd)
        Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * The card stays visible until [refresh] observes the exemption, so no result callback is
     * needed — the 2 s ticker picks it up as soon as the user comes back.
     */
    private fun requestBatteryExemption() {
        if (BatteryOptimization.request(this)) return
        copyToClipboard(BatteryOptimization.adbCommand(this))
        Snackbar.make(binding.root, R.string.battery_unavailable, Snackbar.LENGTH_LONG).show()
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("adb", text))
    }

    private fun requestLocationIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) refresh()
    }

    companion object {
        private const val REQ_LOCATION = 42
    }
}
