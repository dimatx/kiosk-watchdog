package com.shymoose.wifiwatchdog

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Automatic work never waits behind old work; at most one manual request may wait. */
internal class ReportingLane(name: String) : AutoCloseable {
    private val lock = Any()
    private var outstanding = 0
    private val worker = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, name).apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    fun submit(manual: Boolean = false, task: () -> Unit): Boolean = synchronized(lock) {
        if (worker.isShutdown || outstanding >= 2 || (!manual && outstanding > 0)) return false
        outstanding++
        try {
            worker.execute {
                try {
                    task()
                } finally {
                    synchronized(lock) { outstanding-- }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            outstanding--
            false
        }
    }

    override fun close() {
        synchronized(lock) {
            worker.shutdownNow()
        }
    }
}
