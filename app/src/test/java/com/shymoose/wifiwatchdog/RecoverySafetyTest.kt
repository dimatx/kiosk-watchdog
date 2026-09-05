package com.shymoose.wifiwatchdog

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.preference.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWifiManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28], shadows = [RecoverySafetyTest.RejectableWifiManager::class])
class RecoverySafetyTest {
    private lateinit var app: Application
    private lateinit var wifi: WifiManager
    private lateinit var prefs: Prefs
    private lateinit var alarms: AlarmManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs = Prefs(app)
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_ENABLED)
        Settings.Global.putInt(app.contentResolver, SCAN_ALWAYS, 1)
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
    }

    @After
    fun clearInterrupt() {
        Thread.interrupted()
    }

    @Test
    fun `soft toggle retains off dwell and restores wifi`() {
        val pauses = mutableListOf<Long>()
        val recovery = WifiRecovery(app) {
            pauses.add(it)
            assertFalse(wifi.isWifiEnabled)
        }

        assertTrue(recovery.softToggle())
        assertEquals(listOf(3_000L), pauses)
        assertTrue(wifi.isWifiEnabled)
        assertFalse(prefs.airplanePending)
    }

    @Test
    fun `stopping during soft toggle restores wifi and preserves cancellation`() {
        val recovery = WifiRecovery(app) {
            assertFalse(wifi.isWifiEnabled)
            throw InterruptedException("service stopped")
        }

        assertFalse(recovery.softToggle())
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `hard reset retains driver unload sequence and restores scanning`() {
        val pauses = mutableListOf<Long>()
        val recovery = WifiRecovery(app) {
            pauses.add(it)
            assertEquals(if (pauses.size == 3) 1 else 0, scanAlways())
            if (pauses.size > 1) assertFalse(wifi.isWifiEnabled)
        }

        assertTrue(recovery.hardReset())
        assertEquals(listOf(1_500L, 8_000L, 1_500L), pauses)
        assertTrue(wifi.isWifiEnabled)
        assertEquals(1, scanAlways())
        assertEquals(-1, prefs.scanAlwaysRestore)
    }

    @Test
    fun `stopping at every hard reset dwell restores wifi and scanning`() {
        for (stopAt in 1..3) {
            var pauses = 0
            val recovery = WifiRecovery(app) {
                if (++pauses == stopAt) throw InterruptedException("service stopped")
            }

            assertFalse("stop at dwell $stopAt", recovery.hardReset())
            assertTrue(Thread.interrupted())
            assertTrue("Wi-Fi at dwell $stopAt", wifi.isWifiEnabled)
            assertEquals(1, scanAlways())
            assertEquals(-1, prefs.scanAlwaysRestore)
        }
    }

    @Test
    fun `disabled hard reset only runs a soft toggle even with permission`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(Prefs.KEY_HARD_ENABLED, false).commit()
        val pauses = mutableListOf<Long>()
        val recovery = WifiRecovery(app) {
            pauses.add(it)
            assertEquals(1, scanAlways())
            assertEquals(-1, prefs.scanAlwaysRestore)
        }

        assertTrue(recovery.hardReset())
        assertEquals(listOf(3_000L), pauses)
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `unavailable airplane fallback honors disabled hard reset`() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(Prefs.KEY_HARD_ENABLED, false).commit()
        val pauses = mutableListOf<Long>()
        val recovery = WifiRecovery(app) { pauses.add(it) }

        assertTrue(recovery.airplaneCycle())
        assertEquals(listOf(3_000L), pauses)
        assertEquals(1, scanAlways())
    }

    @Test
    fun `hard reset without privileged permission still restores using soft toggle`() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val pauses = mutableListOf<Long>()

        assertTrue(WifiRecovery(app) { pauses.add(it) }.hardReset())
        assertEquals(listOf(3_000L), pauses)
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `failed wifi cleanup schedules a durable retry even when monitoring is stopped`() {
        prefs.enabled = false
        val recovery = WifiRecovery(app) {
            Shadow.extract<RejectableWifiManager>(wifi).rejectEnable = true
        }

        assertFalse(recovery.softToggle())
        assertFalse(wifi.isWifiEnabled)
        assertTrue(prefs.airplanePending)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test
    fun `failed airplane cleanup preserves pending flag and rearms one failsafe`() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        prefs.enabled = false

        repeat(2) {
            assertFalse(AirplaneMode.ensureOff(app))
            assertTrue(prefs.airplanePending)
            assertTrue(AirplaneMode.isOn(app))
            assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        }
    }

    @Test
    fun `successful cleanup restores wifi and clears the retry`() {
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        AirplaneMode.scheduleRestore(app)

        assertTrue(AirplaneMode.ensureOff(app))
        assertTrue(wifi.isWifiEnabled)
        assertFalse(prefs.airplanePending)
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test
    fun `wifi failure after airplane is off retains the failsafe`() {
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        Shadow.extract<RejectableWifiManager>(wifi).rejectEnable = true

        assertFalse(AirplaneMode.ensureOff(app))
        assertTrue(prefs.airplanePending)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
    }

    @Test
    fun `cleanup does not discard an existing cancellation`() {
        shadowOf(wifi).setWifiState(WifiManager.WIFI_STATE_DISABLED)
        Thread.currentThread().interrupt()

        assertTrue(AirplaneMode.ensureOff(app))
        assertTrue(Thread.currentThread().isInterrupted)
        Thread.interrupted()
        assertTrue(wifi.isWifiEnabled)
        assertFalse(prefs.airplanePending)
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 unsupported confirmation prevents a destructive toggle`() {
        var paused = false

        assertFalse(WifiRecovery(app) { paused = true }.softToggle())
        assertFalse(paused)
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `common worker wake budget covers maximum dwell and recovery overhead`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Prefs.KEY_AIRPLANE_DWELL, "300").commit()

        val dwellAndRestoration = prefs.airplaneDwellSec * 1_000L +
            2 * WifiPower.TIMEOUT_MS + 2 * 20_000L + 12_000L + 60_000L
        assertTrue(WatchdogService.WAKE_TIMEOUT_MS >= dwellAndRestoration)
    }

    private fun scanAlways(): Int = Settings.Global.getInt(app.contentResolver, SCAN_ALWAYS, -1)

    @Implements(WifiManager::class)
    class RejectableWifiManager : ShadowWifiManager() {
        var rejectEnable = false

        @Implementation
        override fun setWifiEnabled(enabled: Boolean): Boolean =
            if (enabled && rejectEnable) false else super.setWifiEnabled(enabled)
    }

    private companion object {
        const val SCAN_ALWAYS = "wifi_scan_always_enabled"
    }
}
