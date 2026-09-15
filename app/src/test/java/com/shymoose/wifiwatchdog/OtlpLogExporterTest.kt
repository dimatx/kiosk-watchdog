package com.shymoose.wifiwatchdog

import android.app.Application
import androidx.preference.PreferenceManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class OtlpLogExporterTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putString(Prefs.KEY_OTLP_ENDPOINT, "https://vector.example.invalid:4318")
            .commit()
        EventLog.clear(app)
    }

    @Test
    fun `unconfigured endpoint sends nothing`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        EventLog.add(app, EventLevel.INFO, "one")
        var sent = false
        OtlpLogExporter.flush(app) { _, _ -> sent = true; DeliveryResult.Delivered }
        assertFalse(sent)
    }

    @Test
    fun `flush batches unseen events oldest first and advances the cursor`() {
        EventLog.add(app, EventLevel.INFO, "first")
        EventLog.add(app, EventLevel.WARN, "second")

        var body: JSONObject? = null
        OtlpLogExporter.flush(app) { request, _ ->
            body = JSONObject(request.body!!)
            DeliveryResult.Delivered
        }

        val records = body!!.getJSONArray("resourceLogs").getJSONObject(0)
            .getJSONArray("scopeLogs").getJSONObject(0).getJSONArray("logRecords")
        assertEquals(2, records.length())
        assertEquals("first", records.getJSONObject(0).getJSONObject("body").getString("stringValue"))
        assertEquals("second", records.getJSONObject(1).getJSONObject("body").getString("stringValue"))
        assertEquals("WARN", records.getJSONObject(1).getString("severityText"))
        assertEquals(13, records.getJSONObject(1).getInt("severityNumber"))

        // Cursor advanced: a second flush with nothing new must not deliver again.
        var deliveredAgain = false
        OtlpLogExporter.flush(app) { _, _ -> deliveredAgain = true; DeliveryResult.Delivered }
        assertFalse(deliveredAgain)
    }

    @Test
    fun `a failed flush does not advance the cursor`() {
        EventLog.add(app, EventLevel.INFO, "one")
        OtlpLogExporter.flush(app) { _, _ -> DeliveryResult.Failed("HTTP 500") }

        var retried = false
        OtlpLogExporter.flush(app) { request, _ ->
            retried = true
            assertTrue(request.body!!.contains("\"one\""))
            DeliveryResult.Delivered
        }
        assertTrue(retried)
    }

    @Test
    fun `request carries the endpoint suffix, auth header, and stream name`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Prefs.KEY_OTLP_AUTH, "Basic dGVzdA==")
            .putString(Prefs.KEY_OTLP_STREAM, "android")
            .commit()
        EventLog.add(app, EventLevel.INFO, "one")

        OtlpLogExporter.flush(app) { request, _ ->
            assertEquals("https://vector.example.invalid:4318/v1/logs", request.url)
            assertEquals("Basic dGVzdA==", request.headers["Authorization"])
            assertEquals("android", request.headers["stream-name"])
            assertEquals("application/json", request.headers["Content-Type"])
            DeliveryResult.Delivered
        }
    }

    @Test
    fun `sendTest ignores the cursor and does not require prior unsent events`() {
        var sent = false
        val ok = OtlpLogExporter.sendTest(app) { request, _ ->
            sent = true
            assertTrue(request.body!!.contains("Central log export test"))
            DeliveryResult.Delivered
        }
        assertTrue(ok)
        assertTrue(sent)
    }

    @Test
    fun `a blank auth header or stream name is omitted rather than sent empty`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Prefs.KEY_OTLP_AUTH, "")
            .putString(Prefs.KEY_OTLP_STREAM, "")
            .commit()
        EventLog.add(app, EventLevel.INFO, "one")

        OtlpLogExporter.flush(app) { request, _ ->
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["stream-name"])
            DeliveryResult.Delivered
        }
    }
}
