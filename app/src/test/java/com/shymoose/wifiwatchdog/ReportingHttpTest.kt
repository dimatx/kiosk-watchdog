package com.shymoose.wifiwatchdog

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingHttpTest {
    @Test
    fun `success does not buffer or wait for a large streaming response`() {
        Endpoint { socket, finished ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 100000000\r\n\r\n".toByteArray())
                flush()
            }
            finished.await()
        }.use { endpoint ->
            val started = System.nanoTime()
            val result = ReportingHttp.send(ReportingRequest(endpoint.url), 1_000)
            assertEquals(DeliveryResult.Delivered, result)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        }
    }

    @Test
    fun `total deadline stops a server that continuously drips response headers`() {
        Endpoint { socket, _ ->
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nX-Drip: ".toByteArray())
            repeat(100) {
                output.write('x'.code)
                output.flush()
                Thread.sleep(40)
            }
        }.use { endpoint ->
            val started = System.nanoTime()
            val result = ReportingHttp.send(ReportingRequest(endpoint.url), 250)
            assertTrue(result is DeliveryResult.Failed)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        }
    }

    @Test
    fun `HTTP failures preserve status without exposing request credentials`() {
        Endpoint { socket, _ ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n".toByteArray())
                flush()
            }
        }.use { endpoint ->
            assertEquals(
                DeliveryResult.Failed("HTTP 401"),
                ReportingHttp.send(ReportingRequest(endpoint.url + "?token=private"))
            )
        }
    }

    @Test
    fun `invalid URLs produce an explicit failure`() {
        assertEquals(
            DeliveryResult.Failed("invalid HTTP request"),
            ReportingHttp.send(ReportingRequest("file:///not-an-http-endpoint"))
        )
    }

    @Test
    fun `an explicit Content-Type header reaches the wire exactly, with no charset appended`() {
        // Some real OTLP receivers (OpenObserve) 400 a request the instant a
        // charset parameter shows up on Content-Type, so this has to be exact -
        // not just "starts with application/json".
        Endpoint { socket, _ ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                flush()
            }
        }.use { endpoint ->
            ReportingHttp.send(
                ReportingRequest(endpoint.url, body = "{}", headers = mapOf("Content-Type" to "application/json"))
            )
            assertTrue(
                "expected an exact application/json Content-Type with no charset, got: ${endpoint.capturedHeaders}",
                endpoint.capturedHeaders.any { it.equals("Content-Type: application/json", ignoreCase = true) }
            )
        }
    }

    @Test
    fun `a request with no Content-Type header still defaults to text-plain`() {
        Endpoint { socket, _ ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                flush()
            }
        }.use { endpoint ->
            ReportingHttp.send(ReportingRequest(endpoint.url, body = "hello"))
            assertTrue(
                "expected a text/plain Content-Type, got: ${endpoint.capturedHeaders}",
                endpoint.capturedHeaders.any { it.startsWith("Content-Type: text/plain", ignoreCase = true) }
            )
        }
    }

    internal class Endpoint(script: (Socket, CountDownLatch) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val accepted = AtomicReference<Socket>()
        private val stopped = AtomicBoolean(false)
        private val finished = CountDownLatch(1)
        private val worker = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${listener.localPort}/"

        /** Raw request-header lines seen before the blank line, for tests that assert on them. */
        val capturedHeaders = java.util.Collections.synchronizedList(mutableListOf<String>())

        init {
            worker.submit {
                try {
                    listener.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 2_000
                        val input = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            capturedHeaders.add(line)
                        }
                        script(socket, finished)
                    }
                } catch (e: SocketException) {
                    if (!stopped.get()) throw e
                } catch (e: InterruptedException) {
                    if (!stopped.get()) throw e
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun release() {
            finished.countDown()
        }

        override fun close() {
            stopped.set(true)
            finished.countDown()
            listener.close()
            accepted.get()?.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
