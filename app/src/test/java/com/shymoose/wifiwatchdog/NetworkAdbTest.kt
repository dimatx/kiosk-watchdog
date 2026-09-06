package com.shymoose.wifiwatchdog

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.preference.PreferenceManager
import androidx.preference.Preference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@LooperMode(LooperMode.Mode.PAUSED)
class NetworkAdbTest {
    private lateinit var app: Application
    private lateinit var provider: NativeSettings

    class NativeSettings : ContentProvider() {
        var value: String? = "-1"
        var ignoreWrites = false
        var failWrite = false
        var missingRead = false
        val calls = CopyOnWriteArrayList<String>()
        val threadNames = CopyOnWriteArrayList<String>()
        val readback = CountDownLatch(1)
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            assertEquals("adb_port", arg)
            calls.add(method)
            threadNames.add(Thread.currentThread().name)
            return when (method) {
                "GET_secure" -> {
                    if (calls.size >= 3) readback.countDown()
                    if (missingRead) null else Bundle().apply { putString("value", value) }
                }
                "PUT_secure" -> {
                    if (failWrite) throw SecurityException("Denied")
                    assertEquals(setOf("value"), extras!!.keySet())
                    if (!ignoreWrites) value = extras.getString("value")
                    null // This is the native provider's successful PUT response.
                }
                else -> error("Unexpected provider operation: $method")
            }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("No query fallback")
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No insert fallback")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No deletes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No updates")
    }

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(Prefs.KEY_ENABLED, false).commit()
        EventLog.clear(app)
        provider = NativeSettings()
        shadowOf(app).denyPermissions(NetworkAdb.PERMISSION)
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
    }

    private fun installNativeProvider(system: Boolean = true, packageName: String = NetworkAdb.PROVIDER_PACKAGE) {
        val info = ProviderInfo().apply {
            authority = NetworkAdb.AUTHORITY
            this.packageName = packageName
            name = NativeSettings::class.java.name
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        provider.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal(NetworkAdb.AUTHORITY, provider)
    }

    private fun optIn() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(Prefs.KEY_RESTORE_NETWORK_ADB, true).commit()
    }

    private fun ready() {
        installNativeProvider()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        optIn()
    }

    @Test fun `opt in defaults off even on a granted Lineage device`() {
        installNativeProvider()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        assertFalse(Prefs(app).restoreNetworkAdb)
        assertEquals(NetworkAdb.Result.DISABLED, NetworkAdb.restoreIfEnabled(app))
        assertTrue(provider.calls.isEmpty())
        assertTrue(EventLog.read(app).isEmpty())
    }

    @Test fun `unsupported platform does not write even with restored opt in and grant`() {
        optIn()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        assertEquals(NetworkAdb.Availability.UNSUPPORTED, NetworkAdb.availability(app))
        assertEquals(NetworkAdb.Result.UNAVAILABLE, NetworkAdb.restoreIfEnabled(app))
        assertTrue(provider.calls.isEmpty())
        assertTrue(EventLog.read(app).first().message.contains("skipped"))
    }

    @Test fun `non-system lookalike provider is not accepted as Lineage`() {
        installNativeProvider(system = false)
        optIn()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        assertEquals(NetworkAdb.Result.UNAVAILABLE, NetworkAdb.restoreIfEnabled(app))
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun `other provider package is not accepted as Lineage`() {
        installNativeProvider(packageName = "other.provider")
        optIn()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        assertEquals(NetworkAdb.Availability.UNSUPPORTED, NetworkAdb.availability(app))
        assertEquals(NetworkAdb.Result.UNAVAILABLE, NetworkAdb.restoreIfEnabled(app))
    }

    @Test fun `Android secure grant cannot substitute for Lineage secure grant`() {
        installNativeProvider()
        optIn()
        shadowOf(app).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        assertEquals(NetworkAdb.Availability.NEEDS_GRANT, NetworkAdb.availability(app))
        assertEquals(NetworkAdb.Result.UNAVAILABLE, NetworkAdb.restoreIfEnabled(app))
        assertTrue(NetworkAdb.unavailableReason(app)!!.contains(NetworkAdb.PERMISSION))
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun `native null PUT response succeeds only after readback without modifying Android ADB`() {
        ready()
        Settings.Global.putInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putInt(app.contentResolver, "development_settings_enabled", 1)
        assertEquals(NetworkAdb.Result.VERIFIED, NetworkAdb.restoreIfEnabled(app))
        assertEquals("5555", provider.value)
        assertEquals(listOf("GET_secure", "PUT_secure", "GET_secure"), provider.calls)
        assertEquals(0, Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED))
        assertEquals(1, Settings.Global.getInt(app.contentResolver, "development_settings_enabled"))
        val event = EventLog.read(app).first()
        assertEquals(EventLevel.INFO, event.level)
        assertTrue(event.message.contains("TCP listener not verified"))
    }

    @Test fun `existing valid native network port is preserved`() {
        ready()
        provider.value = "5566"
        assertEquals(NetworkAdb.Result.VERIFIED, NetworkAdb.restoreIfEnabled(app))
        assertEquals("5566", provider.value)
        assertEquals(listOf("GET_secure", "GET_secure"), provider.calls)
    }

    @Test fun `native read failure prevents writes`() {
        ready()
        provider.missingRead = true
        assertEquals(NetworkAdb.Result.FAILED, NetworkAdb.restoreIfEnabled(app))
        assertEquals(listOf("GET_secure"), provider.calls)
        assertEquals(EventLevel.ERROR, EventLog.read(app).first().level)
    }

    @Test fun `missing or malformed native port is not blindly created`() {
        ready()
        for (value in listOf(null, "", "invalid", "-2", "65536")) {
            provider.value = value
            provider.calls.clear()
            assertEquals(NetworkAdb.Result.FAILED, NetworkAdb.restoreIfEnabled(app))
            assertEquals(listOf("GET_secure"), provider.calls)
        }
    }

    @Test fun `ignored write never claims restoration success`() {
        ready()
        provider.ignoreWrites = true
        assertEquals(NetworkAdb.Result.FAILED, NetworkAdb.restoreIfEnabled(app))
        assertEquals("-1", provider.value)
        assertTrue(EventLog.read(app).first().message.contains("readback did not match"))
    }

    @Test fun `permission revoked between check and write logs failure`() {
        ready()
        provider.failWrite = true
        assertEquals(NetworkAdb.Result.FAILED, NetworkAdb.restoreIfEnabled(app))
        assertEquals("-1", provider.value)
        assertTrue(EventLog.read(app).first().message.contains("permission denied"))
    }

    @Test fun `package replacement never restores network ADB`() {
        ready()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun `boot restores on background receiver work even while monitoring is paused`() {
        ready()
        assertFalse(Prefs(app).enabled)
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).apply {
            component = ComponentName(app, BootReceiver::class.java)
        })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(provider.readback.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (EventLog.read(app).isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(EventLog.read(app).first().message.contains("adb_port=5555 verified"))
        assertEquals(listOf("GET_secure", "PUT_secure", "GET_secure"), provider.calls)
        assertTrue(provider.threadNames.all { it == "boot-restoration" })
    }

    @Test fun `web settings reject unsupported opt in and export its default`() {
        val field = ConfigServer::class.java.getDeclaredMethod(
            "field", Context::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        val result = field.invoke(ConfigServer, app, Prefs.KEY_RESTORE_NETWORK_ADB, "true") as String
        assertTrue(result.contains("Unavailable"))
        assertFalse(Prefs(app).restoreNetworkAdb)
        val export = ConfigServer::class.java.getDeclaredMethod("exportJson", Context::class.java)
            .apply { isAccessible = true }.invoke(ConfigServer, app) as String
        assertFalse(JSONObject(export).getBoolean(Prefs.KEY_RESTORE_NETWORK_ADB))
        val page = ConfigServer::class.java.getDeclaredMethod("page", Context::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(ConfigServer, app, null) as String
        assertTrue(page.contains("name=\"restore_network_adb\" disabled"))
        assertTrue(page.contains("Not supported on stock Android or Fire OS"))
    }

    @Test fun `supported web opt in persists without performing native write before boot`() {
        installNativeProvider()
        shadowOf(app).grantPermissions(NetworkAdb.PERMISSION)
        val field = ConfigServer::class.java.getDeclaredMethod(
            "field", Context::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        assertEquals("Saved", field.invoke(ConfigServer, app, Prefs.KEY_RESTORE_NETWORK_ADB, "true"))
        assertTrue(Prefs(app).restoreNetworkAdb)
        assertTrue(provider.calls.isEmpty())
        assertNotNull(app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.firstOrNull { it == NetworkAdb.PERMISSION })
    }

    @Test fun `unsupported imports cannot opt in and disabled form controls preserve stored choice`() {
        val importer = ConfigServer::class.java.getDeclaredMethod("importJson", Context::class.java, String::class.java)
            .apply { isAccessible = true }
        val raw = JSONObject().put(Prefs.KEY_RESTORE_NETWORK_ADB, true).toString()
        assertTrue((importer.invoke(ConfigServer, app, raw) as String).contains("Unavailable"))
        assertFalse(Prefs(app).restoreNetworkAdb)
        optIn() // A previously configured device whose grant/provider is now absent.
        val save = ConfigServer::class.java.getDeclaredMethod("save", Context::class.java, Map::class.java)
            .apply { isAccessible = true }
        save.invoke(ConfigServer, app, emptyMap<String, String>())
        assertTrue(Prefs(app).restoreNetworkAdb)
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun `Android preference is disabled with unsupported platform explanation`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val preference = fragment.findPreference<Preference>(Prefs.KEY_RESTORE_NETWORK_ADB)!!
            assertFalse(preference.isEnabled)
            assertTrue(preference.summary.toString().contains("Not supported on stock Android or Fire OS"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `Android preference explains missing Lineage grant independently of Android grant`() {
        installNativeProvider()
        shadowOf(app).grantPermissions("android.permission.WRITE_SECURE_SETTINGS")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val preference = fragment.findPreference<Preference>(Prefs.KEY_RESTORE_NETWORK_ADB)!!
            assertFalse(preference.isEnabled)
            assertTrue(preference.summary.toString().contains(NetworkAdb.PERMISSION))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
