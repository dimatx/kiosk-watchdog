package com.shymoose.wifiwatchdog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingLaneTest {
    @Test
    fun `busy reporter never queues automatic work and bounds manual requests`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manualDone = CountDownLatch(1)
        ReportingLane("reporting-test").use { lane ->
            try {
                assertTrue(lane.submit {
                    started.countDown()
                    release.await()
                })
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertFalse(lane.submit { error("automatic work must not queue") })
                assertTrue(lane.submit(manual = true) { manualDone.countDown() })
                assertFalse(lane.submit(manual = true) { error("manual queue must be bounded") })
                release.countDown()
                assertTrue(manualDone.await(2, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `blocked notifications do not block a heartbeat or the submitting thread`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heartbeat = CountDownLatch(1)
        ReportingLane("blocked-notifications").use { notifications ->
            ReportingLane("independent-heartbeat").use { heartbeats ->
                try {
                    assertTrue(notifications.submit {
                        started.countDown()
                        release.await()
                    })
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    assertTrue(heartbeats.submit { heartbeat.countDown() })
                    assertTrue(heartbeat.await(2, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun `closed reporter rejects new work`() {
        val lane = ReportingLane("closed-reporting")
        lane.close()
        assertFalse(lane.submit(manual = true) { error("closed reporter ran work") })
    }
}
