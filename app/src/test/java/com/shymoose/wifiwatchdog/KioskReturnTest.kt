package com.shymoose.wifiwatchdog

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.PowerManager
import androidx.preference.PreferenceManager
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The display must never be left on this app. The accessibility service is not
 * bound under Robolectric, which is exactly the "front window unknown" reading a
 * Fire OS 7 tablet gives while the service is bound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KioskReturnTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val kiosk = Prefs.DEFAULT_KIOSK_PACKAGE

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(kiosk),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = kiosk
                    name = "$kiosk.MainActivity"
                }
            }
        )
        while (shadowOf(app).nextStartedActivity != null) Unit
    }

    @After
    fun tearDown() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        KioskReturn.noteHidden(app)
    }

    @Test
    fun returnsFromOwnScreenEvenWhenFrontWindowIsUnknown() {
        KioskReturn.noteShown()

        KioskReturn.check(app)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(Prefs.DEFAULT_KIOSK_RETURN_MIN.toLong()).plusSeconds(1))
        KioskReturn.check(app)

        assertEquals(kiosk, shadowOf(app).nextStartedActivity?.component?.packageName)
    }

    @Test
    fun ownScreenLeftOnBeforeSleepReturnsPromptlyOnWake() {
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        KioskReturn.noteShown()
        power.setIsInteractive(false)
        // Switching the display off pauses the activity; that must not count as leaving it.
        KioskReturn.noteHidden(app)

        KioskReturn.check(app)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(Prefs.DEFAULT_KIOSK_RETURN_MIN.toLong()).plusSeconds(1))
        power.setIsInteractive(true)
        KioskReturn.check(app)

        assertEquals(kiosk, shadowOf(app).nextStartedActivity?.component?.packageName)
    }

    @Test
    fun leavesUnknownForegroundAloneWhenNoOwnScreenIsShown() {
        KioskReturn.check(app)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(Prefs.DEFAULT_KIOSK_RETURN_MIN.toLong()).plusSeconds(1))
        KioskReturn.check(app)

        assertNull(shadowOf(app).nextStartedActivity)
    }
}
