package com.shymoose.wifiwatchdog

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * Generic outbound heartbeat: a URL that gets hit on a fixed cadence for as
 * long as connectivity is healthy.
 *
 * This is deliberately the mirror image of [Ntfy]. Notifications describe
 * events and therefore have to survive an outage in a queue; a heartbeat is
 * only meaningful live, because its whole purpose is that *silence* is the
 * signal. Nothing is ever queued or retried here — if the ping cannot go out,
 * the monitor on the other end is supposed to notice.
 *
 * Written against Uptime Kuma push monitors but URL-shaped and unopinionated,
 * so anything that accepts a plain GET works.
 */
object Heartbeat {

    private const val TIMEOUT_MS = 5_000

    /** Logged only on transitions so a long outage cannot flood the event log. */
    private var failing = false

    /**
     * Fires if the configured interval has elapsed since the last successful
     * ping. Safe to call on every tick.
     *
     * The next deadline is anchored to the *scheduled* time rather than to the
     * moment the request completed. The service only ticks every
     * [Prefs.checkIntervalSec] seconds and the request itself takes a while, so
     * measuring from the send would push every period past its nominal length
     * and let the error accumulate. A monitor configured to expect a ping every
     * N seconds then misses roughly every other window.
     *
     * @param rttMs round-trip time of the probe that proved the link is up.
     */
    fun maybePing(context: Context, prefs: Prefs, rttMs: Long) {
        if (!prefs.heartbeatConfigured) return
        val now = System.currentTimeMillis()
        val periodMs = prefs.heartbeatIntervalSec * 1000L
        val last = prefs.heartbeatLastAt
        val due = last + periodMs
        if (last != 0L && now < due) return

        // Only keep the old cadence when we are at most one period late. A
        // longer gap means an outage, a clock change or a settings change, and
        // the schedule should restart from now instead of firing a burst to
        // catch up.
        val anchor = if (last != 0L && now - due < periodMs) due else now
        send(context, prefs, rttMs, manual = false, stampAt = anchor)
    }

    /** Ignores the schedule. Used by the settings screen to prove the URL works. */
    fun pingNow(context: Context, prefs: Prefs, rttMs: Long): Boolean =
        send(context, prefs, rttMs, manual = true, stampAt = System.currentTimeMillis())

    private fun send(
        context: Context,
        prefs: Prefs,
        rttMs: Long,
        manual: Boolean,
        stampAt: Long,
    ): Boolean {
        val url = expand(context, prefs.heartbeatUrl, rttMs)
        val ok = runCatching { get(url) }.getOrDefault(false)

        if (ok) {
            prefs.heartbeatLastAt = stampAt
            if (manual) {
                EventLog.add(context, EventLevel.INFO, context.getString(R.string.log_heartbeat_test_ok))
            } else if (failing) {
                EventLog.add(context, EventLevel.INFO, context.getString(R.string.log_heartbeat_ok))
            }
            failing = false
        } else {
            if (manual || !failing) {
                EventLog.add(context, EventLevel.WARN, context.getString(R.string.log_heartbeat_failed))
            }
            failing = true
        }
        return ok
    }

    private fun get(url: String): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }
        return try {
            // The body is irrelevant, but it has to be drained for connection reuse.
            runCatching { conn.inputStream.use { it.readBytes() } }
            conn.responseCode in 200..299
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Substitutes the placeholders documented on the settings screen. Values are
     * percent-encoded because they land in a query string.
     */
    private fun expand(context: Context, template: String, rttMs: Long): String {
        if (!template.contains('{')) return template
        val id = DeviceIdentity.snapshot(context)
        return template
            .replace("{ping}", rttMs.coerceAtLeast(0).toString())
            .replace("{device}", encode(id.hostname))
            .replace("{ip}", encode(id.ip))
            .replace("{mac}", encode(id.mac))
    }

    private fun encode(value: String?): String =
        runCatching { java.net.URLEncoder.encode(value.orEmpty(), "UTF-8") }.getOrDefault("")
}
