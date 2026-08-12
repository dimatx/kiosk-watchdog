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
    private val logListener: () -> Unit = { handler.post { refresh() } }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)
        recovery = WifiRecovery(this)
        probe = NetProbe(this)

        adapter = EventAdapter()
        binding.eventList.layoutManager = LinearLayoutManager(this)
        binding.eventList.adapter = adapter

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

        requestLocationIfNeeded()

        if (prefs.enabled) WatchdogService.start(this)
    }

    override fun onStart() {
        super.onStart()
        EventLog.addListener(logListener)
        handler.post(ticker)
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
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
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

        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------- UI

    private fun refresh() {
        val state = WatchdogService.State
        val enabled = prefs.enabled

        binding.enabledSwitch.setOnCheckedChangeListener(null)
        binding.enabledSwitch.isChecked = enabled
        binding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            if (checked) WatchdogService.start(this) else WatchdogService.stop(this)
            refresh()
        }

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

        // New events are prepended at position 0. RecyclerView keeps its scroll
        // anchor on the previous first item, which pushes fresh entries above the
        // viewport and makes the list look frozen. Re-pin to the top whenever the
        // user was already there.
        val layout = binding.eventList.layoutManager as? LinearLayoutManager
        val wasAtTop = (layout?.findFirstCompletelyVisibleItemPosition() ?: 0) <= 0
        val before = adapter.itemCount
        adapter.submit(EventLog.read(this))
        if (wasAtTop && adapter.itemCount != before) {
            binding.eventList.scrollToPosition(0)
        }
        binding.emptyLabel.visibility =
            if (adapter.itemCount == 0) View.VISIBLE else View.GONE
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
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("adb", cmd))
        Snackbar.make(binding.root, R.string.copied, Snackbar.LENGTH_SHORT).show()
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
