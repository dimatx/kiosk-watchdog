package com.shymoose.wifiwatchdog

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigHttpTransportTest {
    private class Endpoint(
        lifetime: Long = 3_000,
        idle: Int = 2_000,
        mutationLock: ReentrantLock = ReentrantLock(),
        handle: (ConfigHttpRequest, ConfigHttpTransport.Connection) -> Unit = { _, client ->
            client.socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
    ) : Closeable {
        val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val failures = CopyOnWriteArrayList<Exception>()
        val transport = ConfigHttpTransport(
            listener, lifetime, idle, { failures.add(it) }, mutationLock, handle
        )
        private val sockets = CopyOnWriteArrayList<Socket>()
        init { transport.start() }
        fun connect(): Socket = Socket(InetAddress.getLoopbackAddress(), listener.localPort).apply {
            soTimeout = 3_000
            sockets.add(this)
        }
        override fun close() {
            transport.close()
            sockets.forEach { it.close() }
        }
    }

    private fun await(test: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!test() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(test())
    }

    private fun closed(socket: Socket) {
        try {
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: SocketException) {
            // A reset is also a valid immediate rejection/forced close.
        }
    }

    @Test fun `normal request completes and removes its deadline`() {
        Endpoint().use { server ->
            repeat(20) {
                server.connect().use { socket ->
                    socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                }
            }
            await { server.transport.clientCount == 0 && server.transport.pendingDeadlines == 0 }
            assertTrue(server.failures.isEmpty())
        }
    }

    @Test fun `overload closes excess sockets without blocking accept and stop closes queued clients`() {
        Endpoint().use { server ->
            val held = (1..12).map { server.connect() }
            await { server.transport.clientCount == 12 }
            repeat(3) { closed(server.connect()) }
            assertEquals(12, server.transport.clientCount)
            assertTrue(server.transport.pendingDeadlines <= 12)
            server.transport.close()
            held.forEach { closed(it) }
            assertEquals(0, server.transport.clientCount)
            assertEquals(0, server.transport.pendingDeadlines)
        }
    }

    @Test fun `idle timeout rejects incomplete requests without mutation`() {
        val calls = AtomicInteger()
        Endpoint(idle = 100) { _, _ -> calls.incrementAndGet() }.use { server ->
            val socket = server.connect()
            socket.getOutputStream().write("POST /save HTTP/1.1\r\nContent-Length: 5\r\n\r\nx".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 400"))
            assertEquals(0, calls.get())
        }
    }

    @Test fun `slow drip cannot extend total lifetime`() {
        val calls = AtomicInteger()
        Endpoint(lifetime = 350, idle = 200) { _, _ -> calls.incrementAndGet() }.use { server ->
            val socket = server.connect()
            val started = System.nanoTime()
            val sender = Thread {
                try {
                    repeat(40) {
                        socket.getOutputStream().write('G'.code)
                        Thread.sleep(30)
                    }
                } catch (_: IOException) {
                    // The deadline closes the peer while it is still sending.
                }
            }.apply { isDaemon = true; start() }
            closed(socket)
            socket.close()
            sender.join(2_000)
            assertTrue(!sender.isAlive)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertEquals(0, calls.get())
            await { server.transport.pendingDeadlines == 0 && server.transport.clientCount == 0 }
        }
    }

    @Test fun `deadline includes queue time and removes expired queued jobs`() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        Endpoint(lifetime = 500) { _, _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
        }.use { server ->
            try {
                repeat(4) {
                    server.connect().getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                }
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val queued = server.connect()
                queued.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                await { server.transport.clientCount == 5 }
                closed(queued)
                await { server.transport.clientCount == 0 && server.transport.pendingDeadlines == 0 }
                assertEquals(4, calls.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `deadline closes a blocked response write`() {
        val completed = CountDownLatch(1)
        Endpoint(lifetime = 350) { _, client ->
            client.socket.sendBufferSize = 1_024
            try {
                val block = ByteArray(64 * 1024)
                repeat(512) { client.socket.getOutputStream().write(block) }
            } finally {
                completed.countDown()
            }
        }.use { server ->
            val socket = server.connect()
            socket.receiveBufferSize = 1_024
            socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            // Never read the response: the write must unblock by closing the server socket.
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            await { server.transport.clientCount == 0 && server.transport.pendingDeadlines == 0 }
            assertTrue(server.failures.isEmpty())
        }
    }

    @Test fun `parsed request cannot mutate after its socket lifetime expires`() {
        val parsed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val mutations = AtomicInteger()
        Endpoint(lifetime = 300) { _, client ->
            parsed.countDown()
            release.await(3, TimeUnit.SECONDS)
            client.whileActive { mutations.incrementAndGet() }
            finished.countDown()
        }.use { server ->
            try {
                val socket = server.connect()
                socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(parsed.await(2, TimeUnit.SECONDS))
                closed(socket)
                release.countDown()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertEquals(0, mutations.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `stopped parsed request cannot mutate or use a restarted transport`() {
        val parsed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val mutations = AtomicInteger()
        Endpoint { _, client ->
            parsed.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                // Simulate parsing finishing as shutdown interrupts an old worker.
            }
            client.whileActive { mutations.incrementAndGet() }
            finished.countDown()
        }.use { old ->
            val client = old.connect()
            client.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(parsed.await(2, TimeUnit.SECONDS))
            old.transport.close()
            closed(client)
            Endpoint().use { fresh ->
                val socket = fresh.connect()
                socket.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
                assertTrue(finished.await(2, TimeUnit.SECONDS))
                assertEquals(0, mutations.get())
                assertEquals(0, old.transport.clientCount)
            }
        }

    }

    @Test fun `stop returns promptly while an admitted mutation is blocked and restart preserves write order`() {
        val gate = ReentrantLock()
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newWrite = CountDownLatch(1)
        val closer = Executors.newSingleThreadExecutor()
        Endpoint(mutationLock = gate) { _, client ->
            client.whileActive {
                writing.countDown()
                var released = false
                while (!released) {
                    try {
                        release.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Model a SettingsProvider Binder call that ignores cancellation.
                    }
                }
            }
        }.use { old ->
            try {
                old.connect().getOutputStream().write("POST /save HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                assertTrue(writing.await(2, TimeUnit.SECONDS))
                closer.submit { old.transport.close() }.get(1, TimeUnit.SECONDS)
                Endpoint(mutationLock = gate) { _, client ->
                    client.whileActive { newWrite.countDown() }
                }.use { fresh ->
                    fresh.connect().getOutputStream().write("POST /save HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    assertFalse(newWrite.await(100, TimeUnit.MILLISECONDS))
                    release.countDown()
                    assertTrue(newWrite.await(2, TimeUnit.SECONDS))
                }
            } finally {
                release.countDown()
                closer.shutdownNow()
            }
        }
    }
}
