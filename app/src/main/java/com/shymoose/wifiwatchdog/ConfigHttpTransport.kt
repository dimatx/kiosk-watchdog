package com.shymoose.wifiwatchdog

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Each window owns its listener, workers and clients, including queued clients.
 * At most 12 clients/timers are admitted: 4 executing and 8 queued. A 15-second
 * accept-to-close deadline bounds queueing, slow-drip reads and blocked writes;
 * idle reads also time out after 10 seconds. Excess connections are closed
 * immediately without making the accept thread write an overload response.
 */
internal class ConfigHttpTransport(
    private val listener: ServerSocket,
    private val lifetimeMs: Long = CONNECTION_LIFETIME_MS,
    private val idleReadMs: Int = IDLE_READ_MS,
    private val onFailure: (Exception) -> Unit,
    private val mutationLock: ReentrantLock = ReentrantLock(),
    private val handle: (ConfigHttpRequest, Connection) -> Unit
) : Closeable {
    companion object {
        const val WORKER_THREADS = 4
        const val QUEUED_CLIENTS = 8
        const val CONNECTION_LIFETIME_MS = 15_000L
        const val IDLE_READ_MS = 10_000
    }

    private val lifecycle = Any()
    @Volatile private var running = true
    private val clients = ConcurrentHashMap.newKeySet<Connection>()
    private val workers = ThreadPoolExecutor(
        WORKER_THREADS, WORKER_THREADS, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUED_CLIENTS),
        { task -> Thread(task, "config-server-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "config-server-deadlines").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    internal val clientCount: Int get() = clients.size
    internal val pendingDeadlines: Int get() = deadlines.queue.size

    fun start() {
        Thread({ serve() }, "config-server").apply { isDaemon = true }.start()
    }

    private fun serve() {
        while (running) {
            val socket = try {
                listener.accept()
            } catch (error: IOException) {
                if (running) onFailure(error)
                break
            }
            val client = Connection(socket, System.nanoTime())
            synchronized(lifecycle) {
                if (!running || clients.size >= WORKER_THREADS + QUEUED_CLIENTS) {
                    client.close()
                } else {
                    clients.add(client)
                    try {
                        client.deadline = deadlines.schedule(
                            { client.close() }, client.remainingNanos(), TimeUnit.NANOSECONDS
                        )
                        workers.execute(client)
                    } catch (_: RejectedExecutionException) {
                        client.close()
                    }
                }
            }
        }
    }

    inner class Connection internal constructor(
        val socket: Socket,
        private val acceptedAt: Long
    ) : Runnable, Closeable {
        @Volatile internal var deadline: ScheduledFuture<*>? = null

        internal fun remainingNanos(): Long =
            TimeUnit.MILLISECONDS.toNanos(lifetimeMs) - (System.nanoTime() - acceptedAt)

        /** New windows serialize behind admitted writes without making shutdown wait for Binder. */
        fun <T> whileActive(action: () -> T): T? {
            val remaining = remainingNanos()
            if (!running || socket.isClosed || remaining <= 0) return null
            if (!mutationLock.tryLock(remaining, TimeUnit.NANOSECONDS)) return null
            return try {
                if (running && !socket.isClosed && remainingNanos() > 0) action() else null
            } finally {
                mutationLock.unlock()
            }
        }

        override fun run() {
            try {
                if (!running || socket.isClosed || remainingNanos() <= 0) return
                socket.soTimeout = idleReadMs
                val request = ConfigHttpRequest.read(BufferedInputStream(socket.getInputStream()))
                if (running && !socket.isClosed && remainingNanos() > 0) handle(request, this)
            } catch (error: InvalidConfigRequest) {
                reject(error.status)
            } catch (_: SocketTimeoutException) {
                reject(400)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                if (running && !socket.isClosed) onFailure(error)
            } catch (error: IOException) {
                if (running && !socket.isClosed) onFailure(error)
            } catch (error: Exception) {
                onFailure(error)
            } finally {
                close()
            }
        }

        private fun reject(status: Int) {
            val reason = when (status) {
                413 -> "Payload Too Large"
                431 -> "Request Header Fields Too Large"
                else -> "Bad Request"
            }
            try {
                socket.getOutputStream().write(
                    "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
            } catch (error: IOException) {
                if (running && !socket.isClosed) onFailure(error)
            }
        }

        override fun close() {
            try {
                socket.close()
            } catch (error: IOException) {
                onFailure(error)
            } finally {
                deadline?.cancel(false)
                workers.remove(this)
                clients.remove(this)
            }
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            running = false
            try {
                listener.close()
            } catch (error: IOException) {
                onFailure(error)
            }
            clients.toList().forEach { it.close() }
            workers.shutdownNow()
            deadlines.shutdownNow()
        }
        // An admitted SettingsProvider/Binder call may not be interruptible.
        // It finishes on its worker; never join it from the app's main thread.
    }
}
