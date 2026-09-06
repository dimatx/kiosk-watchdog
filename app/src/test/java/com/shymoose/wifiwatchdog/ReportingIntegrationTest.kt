package com.shymoose.wifiwatchdog

import android.app.Application
import androidx.preference.PreferenceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class ReportingIntegrationTest {
    @Test
    fun `healthy probe returns immediately and heartbeat proceeds while ntfy is stalled`() {
        val app: Application = RuntimeEnvironment.getApplication()
        val ntfyStarted = CountDownLatch(1)
        val heartbeatSent = CountDownLatch(1)
        ReportingHttpTest.Endpoint { socket, release ->
            ntfyStarted.countDown()
            release.await()
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { ntfy ->
            ReportingHttpTest.Endpoint { socket, _ ->
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                heartbeatSent.countDown()
            }.use { heartbeat ->
                PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
                    .putString(Prefs.KEY_NTFY_URL, ntfy.url.trimEnd('/'))
                    .putString(Prefs.KEY_NTFY_TOPIC, "topic")
                    .putString(Prefs.KEY_HEARTBEAT_URL, heartbeat.url)
                    .commit()
                Ntfy.enqueue(app, Ntfy.Message("Test", "Pending"))
                Reporting.updateConnectivity(true)

                val started = System.nanoTime()
                Reporting.onHealthyProbe(app, 42)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
                assertTrue(ntfyStarted.await(3, TimeUnit.SECONDS))
                assertTrue(heartbeatSent.await(3, TimeUnit.SECONDS))
                assertEquals(1, Ntfy.pendingCount(app))

                ntfy.release()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (Ntfy.pendingCount(app) != 0 && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(0, Ntfy.pendingCount(app))
            }
        }
    }
}
