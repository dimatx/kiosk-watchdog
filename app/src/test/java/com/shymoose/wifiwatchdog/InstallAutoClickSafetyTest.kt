package com.shymoose.wifiwatchdog

import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28])
class InstallAutoClickSafetyTest {
    private lateinit var service: WindowService
    private lateinit var button: AccessibilityNodeInfo

    @Before
    fun setUp() {
        service = Robolectric.buildService(WindowService::class.java).create().get()
        setEnabled(true)
        button = AccessibilityNodeInfo.obtain().apply {
            text = "Install"
            isClickable = true
            isEnabled = true
        }
        shadowOf(button).setOnPerformActionListener { _, _ -> true }
        val label = AccessibilityNodeInfo.obtain().apply { text = Prefs.DEFAULT_AUTO_INSTALL_ALLOWLIST }
        service.window = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.packageinstaller"
            shadowOf(this).addChild(label)
            shadowOf(this).addChild(button)
        }
    }

    @After
    fun tearDown() {
        setPendingWifiPrompt(null)
        service.onDestroy()
    }

    @Test
    fun `disabled periodic sweep does not read or click the installer`() {
        setEnabled(false)

        service.sweepNow()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, service.windowReads)
        assertTrue(shadowOf(button).performedActions.isEmpty())
    }

    @Test
    fun `enabled periodic sweep still confirms an allowlisted install`() {
        service.sweepNow()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(button).performedActions.contains(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `shared handler refuses installs after switch is disabled`() {
        setEnabled(false)
        val handle = InstallAutoClickService::class.java
            .getDeclaredMethod("handle", AccessibilityNodeInfo::class.java)
        handle.isAccessible = true

        handle.invoke(service, service.window)

        assertTrue(shadowOf(button).performedActions.isEmpty())
    }

    @Test
    fun `disabled event callback leaves install untouched`() {
        setEnabled(false)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.android.packageinstaller"
        }

        service.onAccessibilityEvent(event)

        assertEquals(0, service.windowReads)
        assertTrue(shadowOf(button).performedActions.isEmpty())
        event.recycle()
    }

    @Test
    fun `disabling install confirmation does not disable pending wifi consent handling`() {
        setEnabled(false)
        setPendingWifiPrompt(WifiEnablePrompt("Our Wi-Fi request", "Enable Wi-Fi", "Allow",
            SystemClock.elapsedRealtime() + 20_000L))
        service.window = AccessibilityNodeInfo.obtain().apply {
            packageName = WifiEnablePrompt.PACKAGE
            text = "An unrelated dialog"
        }

        service.sweepNow()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, service.windowReads)
        assertTrue(shadowOf(button).performedActions.isEmpty())
    }

    private fun setEnabled(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(service).edit()
            .putBoolean(Prefs.KEY_AUTO_INSTALL_ENABLED, enabled).commit()
    }

    private fun setPendingWifiPrompt(prompt: WifiEnablePrompt?) {
        WifiPower::class.java.getDeclaredField("pendingPrompt").apply {
            isAccessible = true
            set(null, prompt)
        }
    }

    class WindowService : InstallAutoClickService() {
        var window: AccessibilityNodeInfo? = null
        var windowReads = 0

        override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
            windowReads++
            return window
        }
    }
}
