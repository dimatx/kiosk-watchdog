package com.shymoose.wifiwatchdog

import android.app.Application
import androidx.preference.PreferenceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class ReportingDeliveryTest {
    private lateinit var app: Application
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        prefs = Prefs(app)
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putString(Prefs.KEY_NTFY_TOPIC, "test-topic")
            .putString(Prefs.KEY_HEARTBEAT_URL, "https://example.invalid/push?ping={ping}")
            .commit()
    }

    @Test
    fun `outbox preserves FIFO and acknowledges each success`() {
        enqueue("first")
        enqueue("second")
        val requests = mutableListOf<ReportingRequest>()
        Ntfy.flush(app, deliver = { request, _ ->
            requests.add(request)
            DeliveryResult.Delivered
        })
        assertEquals(listOf("first", "second"), requests.map { it.body })
        assertEquals(0, Ntfy.pendingCount(app))
    }

    @Test
    fun `failed delivery retains current and later messages and backs off`() {
        enqueue("first")
        enqueue("second")
        var attempts = 0
        val fail: (ReportingRequest, Long) -> DeliveryResult = { _, _ ->
            attempts++
            DeliveryResult.Failed("HTTP 503")
        }
        Ntfy.flush(app, deliver = fail)
        Ntfy.flush(app, deliver = fail)
        assertEquals(1, attempts)
        assertEquals(2, Ntfy.pendingCount(app))

        val delivered = mutableListOf<String?>()
        Ntfy.flush(app, force = true, deliver = { request, _ ->
            delivered.add(request.body)
            DeliveryResult.Delivered
        })
        assertEquals(listOf("first", "second"), delivered)
        assertEquals(0, Ntfy.pendingCount(app))
    }

    @Test
    fun `enqueue remains nonblocking during a send and its message is not overwritten`() {
        enqueue("first")
        val sending = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = mutableListOf<String?>()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val flushing = workers.submit {
                Ntfy.flush(app, deliver = { request, _ ->
                    delivered.add(request.body)
                    if (request.body == "first") {
                        sending.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    DeliveryResult.Delivered
                })
            }
            assertTrue(sending.await(3, TimeUnit.SECONDS))
            workers.submit { enqueue("second") }.get(2, TimeUnit.SECONDS)
            release.countDown()
            flushing.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("first", "second"), delivered)
            assertEquals(0, Ntfy.pendingCount(app))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `acknowledging an evicted in-flight item does not remove another item`() {
        enqueue("in-flight")
        var online = true
        Ntfy.flush(app, canSend = { online }, deliver = { _, _ ->
            repeat(26) { enqueue("new-$it") }
            online = false
            DeliveryResult.Delivered
        })
        assertEquals(25, Ntfy.pendingCount(app))
        val remaining = mutableListOf<String?>()
        Ntfy.flush(app, deliver = { request, _ ->
            remaining.add(request.body)
            DeliveryResult.Delivered
        })
        assertEquals((1..25).map { "new-$it" }, remaining)
    }

    @Test
    fun `flush budget limits the next request to remaining time and retains unsent messages`() {
        repeat(3) { enqueue("item-$it") }
        var elapsed = 0L
        val timeouts = mutableListOf<Long>()
        Ntfy.flush(app, nanoTime = { elapsed }, deliver = { _, timeout ->
            timeouts.add(timeout)
            elapsed += if (timeouts.size == 1) 25_000_000_000L else 5_000_000_000L
            DeliveryResult.Delivered
        })
        assertEquals(listOf(10_000L, 5_000L), timeouts)
        assertEquals(1, Ntfy.pendingCount(app))
    }

    @Test
    fun `legacy outbox entries survive migration and expired entries are discarded`() {
        val now = System.currentTimeMillis()
        val outbox = JSONArray()
            .put(JSONObject().put("body", "expired").put("at", now - 25 * 60 * 60_000L))
            .put(JSONObject().put("body", "legacy").put("at", now))
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString("ntfy_outbox", outbox.toString()).commit()
        val bodies = mutableListOf<String?>()

        Ntfy.flush(app, deliver = { request, _ ->
            bodies.add(request.body)
            DeliveryResult.Delivered
        })
        assertEquals(listOf("legacy"), bodies)
        assertEquals(0, Ntfy.pendingCount(app))
    }

    @Test
    fun `known offline state does not send queued notifications`() {
        enqueue("pending")
        Ntfy.flush(app, canSend = { false }, deliver = { _, _ -> error("sent while offline") })
        assertEquals(1, Ntfy.pendingCount(app))
    }

    @Test
    fun `notification authentication and metadata remain intact`() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Prefs.KEY_NTFY_USER, "user")
            .putString(Prefs.KEY_NTFY_PASSWORD, "password").commit()
        Ntfy.enqueue(app, Ntfy.Message("Title", "Body", 4, "warning"))

        Ntfy.flush(app, deliver = { request, _ ->
            assertEquals("https://ntfy.sh/test-topic", request.url)
            assertEquals("Basic dXNlcjpwYXNzd29yZA==", request.headers["Authorization"])
            assertEquals("Title", request.headers["X-Title"])
            assertEquals("warning", request.headers["X-Tags"])
            assertEquals("4", request.headers["X-Priority"])
            DeliveryResult.Delivered
        })
    }

    @Test
    fun `successful heartbeat retains cadence and expands ping without a body`() {
        var sent = 0
        val deliver: (ReportingRequest, Long) -> DeliveryResult = { request, _ ->
            sent++
            assertEquals("https://example.invalid/push?ping=42", request.url)
            assertEquals(null, request.body)
            DeliveryResult.Delivered
        }
        Heartbeat.maybePing(app, prefs, 42, deliver)
        assertTrue(prefs.heartbeatLastAt > 0)
        Heartbeat.maybePing(app, prefs, 42, deliver)
        assertEquals(1, sent)
    }

    @Test
    fun `failed heartbeat never advances success timestamp`() {
        assertFalse(Heartbeat.pingNow(app, prefs, 0) { _, _ -> DeliveryResult.Failed("HTTP 500") })
        assertEquals(0L, prefs.heartbeatLastAt)
    }

    @Test
    fun `manual heartbeat bypasses cadence`() {
        prefs.heartbeatLastAt = System.currentTimeMillis()
        var sent = false
        assertTrue(Heartbeat.pingNow(app, prefs, 0) { _, _ ->
            sent = true
            DeliveryResult.Delivered
        })
        assertTrue(sent)
    }

    private fun enqueue(body: String) = Ntfy.enqueue(app, Ntfy.Message("Title", body))
}
