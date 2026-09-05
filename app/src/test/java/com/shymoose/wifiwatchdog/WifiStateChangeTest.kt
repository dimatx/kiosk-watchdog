package com.shymoose.wifiwatchdog

import org.junit.Assert.*
import org.junit.Test

class WifiStateChangeTest {
    private var time = 0L
    private var enabled = false
    private var requests = 0
    private var polls = 0

    private fun controller(
        accepted: Boolean = true,
        onPoll: () -> Unit = {}
    ) = WifiStateChange(
        request = { requests++; accepted },
        isInState = { enabled == it },
        now = { time },
        pause = { time += it },
        poll = { polls++; onPoll() }
    )

    @Test fun `accepted request without state change fails`() {
        assertFalse(controller().apply(true, 1_000L))
        assertEquals(1, requests)
        assertEquals(1_000L, time)
    }

    @Test fun `confirmation that enables wifi completes the request`() {
        assertTrue(controller(onPoll = { enabled = true }).apply(true, 1_000L))
        assertEquals(1, requests)
        assertEquals(1, polls)
    }

    @Test fun `already enabled does not open another dialog`() {
        enabled = true
        assertTrue(controller().apply(true, 1_000L))
        assertEquals(0, requests)
        assertEquals(0, polls)
    }

    @Test fun `rejected request fails immediately`() {
        assertFalse(controller(accepted = false).apply(true, 1_000L))
        assertEquals(0L, time)
        assertEquals(0, polls)
    }

    @Test fun `disable also requires a real state change`() {
        enabled = true
        assertFalse(controller().apply(false, 1_000L))
    }

    @Test fun `confirmed disable completes`() {
        enabled = true
        assertTrue(controller(onPoll = { enabled = false }).apply(false, 1_000L))
    }

    @Test fun `state change at deadline is recognized`() {
        assertTrue(controller(onPoll = {
            if (time == 750L) enabled = true
        }).apply(true, 1_000L))
    }
}
