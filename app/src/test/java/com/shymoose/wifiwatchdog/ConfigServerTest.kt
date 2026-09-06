package com.shymoose.wifiwatchdog

import android.app.Application
import android.os.Looper
import androidx.preference.PreferenceManager
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@LooperMode(LooperMode.Mode.PAUSED)
class ConfigServerTest {
    private lateinit var app: Application

    @Before fun start() {
        app = RuntimeEnvironment.getApplication()
        ConfigServer.stop()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(Prefs.KEY_ENABLED, false).commit()
        ConfigServer.start(app)
        assertTrue(ConfigServer.isRunning)
    }

    @After fun stop() { ConfigServer.stop() }

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), ConfigServer.port).apply {
        soTimeout = 3_000
    }

    private fun exchange(raw: String): String = connect().use { socket ->
        socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
        socket.shutdownOutput()
        socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
    }

    private fun get(path: String) = exchange("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n")
    private fun post(path: String, body: String) = exchange(
        "POST $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
    )

    @Test fun `normal page export vitals favicon and not-found routes stay available`() {
        val page = get("/?m=hello")
        assertTrue(page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("hello"))
        assertTrue(page.contains("/save"))
        assertTrue(page.contains("/field"))
        assertTrue(page.contains("/import"))
        assertTrue(get("/vitals").startsWith("HTTP/1.1 200"))
        assertTrue(get("/favicon.svg").contains("Content-Type: image/svg+xml"))
        assertTrue(get("/favicon.ico").contains("Content-Type: image/svg+xml"))
        val exported = get("/export")
        assertTrue(exported.contains("Content-Disposition: attachment"))
        assertEquals(1, JSONObject(exported.substringAfter("\r\n\r\n")).getInt("_format"))
        assertTrue(get("/missing").startsWith("HTTP/1.1 404"))
        assertTrue(post("/action", "a=unknown").startsWith("HTTP/1.1 303"))
    }

    @Test fun `autosave full form and raw and form Unicode imports retain behavior`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        assertTrue(post("/field", "k=${Prefs.KEY_NTFY_TOPIC}&v=autosaved").endsWith("Saved"))
        assertEquals("autosaved", prefs.getString(Prefs.KEY_NTFY_TOPIC, null))
        assertTrue(post("/save", "${Prefs.KEY_NTFY_TOPIC}=form-saved").startsWith("HTTP/1.1 303"))
        assertEquals("form-saved", prefs.getString(Prefs.KEY_NTFY_TOPIC, null))
        assertFalse(prefs.getBoolean(Prefs.KEY_ENABLED, true))

        val unicode = "café-日本-😀"
        val raw = JSONObject().put(Prefs.KEY_NTFY_TOPIC, unicode).toString()
        assertTrue(post("/import", raw).startsWith("HTTP/1.1 303"))
        assertEquals(unicode, prefs.getString(Prefs.KEY_NTFY_TOPIC, null))
        val form = "json=" + URLEncoder.encode(JSONObject().put(Prefs.KEY_NTFY_TOPIC, "$unicode-form").toString(), "UTF-8")
        assertTrue(post("/import", form).startsWith("HTTP/1.1 303"))
        assertEquals("$unicode-form", prefs.getString(Prefs.KEY_NTFY_TOPIC, null))
        val exported = get("/export")
        val body = exported.substringAfter("\r\n\r\n")
        val length = exported.lineSequence().first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
        assertEquals(body.toByteArray(Charsets.UTF_8).size, length)
        assertEquals("$unicode-form", JSONObject(body).getString(Prefs.KEY_NTFY_TOPIC))
    }

    @Test fun `invalid framing and partial bodies never mutate any preferences`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putString(Prefs.KEY_NTFY_TOPIC, "unchanged")
            .putBoolean(Prefs.KEY_ENABLED, true).putBoolean(Prefs.KEY_HARD_ENABLED, true).commit()
        val before = prefs.all.toMap()
        for (route in listOf("/save", "/field", "/import", "/action")) {
            val body = when (route) {
                "/field" -> "k=${Prefs.KEY_NTFY_TOPIC}&v=changed"
                "/import" -> JSONObject().put(Prefs.KEY_NTFY_TOPIC, "changed").toString()
                else -> "${Prefs.KEY_NTFY_TOPIC}=changed"
            }
            val invalid = listOf(
                "Content-Length: ${body.length + 1}\r\n" to 400,
                "Content-Length: -1\r\n" to 400,
                "Content-Length: invalid\r\n" to 400,
                "Content-Length: 65537\r\n" to 413,
                "Content-Length: 0\r\nContent-Length: ${body.length}\r\n" to 400,
                "Transfer-Encoding: chunked\r\n" to 400,
                "" to 400,
                "Content-Length: 0\r\nX: ${"x".repeat(8192)}\r\n" to 431
            )
            for ((headers, status) in invalid) {
                val response = exchange("POST $route HTTP/1.1\r\n$headers\r\n$body")
                assertTrue("$route expected $status, got ${response.take(40)}", response.startsWith("HTTP/1.1 $status"))
                assertEquals(before, prefs.all)
            }
            assertTrue(exchange("POST $route HTTP/1.1\r\nContent-Length: 0\r\n")
                .startsWith("HTTP/1.1 400"))
            assertEquals(before, prefs.all)
        }
    }

    @Test fun `stop and immediate restart close old active and queued requests`() {
        val held = (1..12).map { connect() }
        try {
            held.forEach { it.getOutputStream().write("POST /save HTTP/1.1\r\nContent-Length: 100\r\n\r\n".toByteArray()) }
            ConfigServer.stop()
            ConfigServer.start(app)
            held.forEach {
                try {
                    assertEquals(-1, it.getInputStream().read())
                } catch (_: SocketException) {
                    // Stop may reset unread queued bytes instead of sending FIN.
                }
            }
            assertTrue(get("/export").startsWith("HTTP/1.1 200"))
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test fun `five minute window extends without changing listener and then expires`() {
        val port = ConfigServer.port
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.MINUTES)
        assertTrue(ConfigServer.isRunning)
        ConfigServer.start(app)
        assertEquals(port, ConfigServer.port)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.MINUTES)
        assertTrue(ConfigServer.isRunning)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MINUTES)
        assertFalse(ConfigServer.isRunning)
        assertEquals(0, ConfigServer.port)
    }
}
