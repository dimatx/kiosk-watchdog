package com.shymoose.wifiwatchdog

import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push notifications via ntfy (https://docs.ntfy.sh).
 *
 * Every event worth reporting except "recovered" happens while the link is
 * down, so publishing inline would always fail. Messages are queued in a small
 * persistent outbox instead and flushed once connectivity is back — which also
 * keeps the recovery ladder free of blocking network calls.
 */
object Ntfy {

    const val PRIORITY_DEFAULT = 3
    const val PRIORITY_HIGH = 4
    const val PRIORITY_URGENT = 5

    private const val KEY_OUTBOX = "ntfy_outbox"
    private const val KEY_NEXT_ATTEMPT = "ntfy_next_attempt_at"

    /** Enough to describe one long outage; older entries are worthless anyway. */
    private const val MAX_QUEUED = 25
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val RETRY_BACKOFF_MS = 60_000L
    internal const val FLUSH_BUDGET_MS = 30_000L
    private const val DELAYED_THRESHOLD_MS = 90_000L
    private val queueLock = Any()
    private val flushing = AtomicBoolean(false)
    private var failing = false

    data class Message(
        val title: String,
        val body: String,
        val priority: Int = PRIORITY_DEFAULT,
        val tags: String = "",
        val at: Long = System.currentTimeMillis()
    )

    // ------------------------------------------------------------------ queue

    /** Queues a message. Delivery happens on the next successful [flush]. */
    fun enqueue(context: Context, message: Message) {
        val prefs = Prefs(context)
        if (!prefs.ntfyConfigured) return

        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        synchronized(queueLock) {
            val array = readOutbox(sp)
            array.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("title", message.title)
                    .put("body", message.body)
                    .put("priority", message.priority)
                    .put("tags", message.tags)
                    .put("at", message.at)
            )
            // Drop from the front so the newest events always survive the cap.
            val trimmed = JSONArray()
            val start = (array.length() - MAX_QUEUED).coerceAtLeast(0)
            for (i in start until array.length()) trimmed.put(array.optJSONObject(i) ?: continue)
            sp.edit().putString(KEY_OUTBOX, trimmed.toString()).apply()
        }
    }

    fun pendingCount(context: Context): Int =
        readOutbox(PreferenceManager.getDefaultSharedPreferences(context)).length()

    /**
     * Attempts to deliver everything queued, oldest first. Stops at the first
     * failure so ordering is preserved, and backs off so a reachable gateway
     * with no internet does not stall every tick.
     */
    internal fun flush(
        context: Context,
        force: Boolean = false,
        canSend: () -> Boolean = { true },
        deliver: (ReportingRequest, Long) -> DeliveryResult = ReportingHttp::send,
        nanoTime: () -> Long = System::nanoTime
    ) {
        val prefs = Prefs(context)
        if (!prefs.ntfyConfigured) return
        if (!flushing.compareAndSet(false, true)) return
        var delivered = 0
        try {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            if (!force && System.currentTimeMillis() < sp.getLong(KEY_NEXT_ATTEMPT, 0L)) return
            val started = nanoTime()

            repeat(MAX_QUEUED) {
                val remaining = FLUSH_BUDGET_MS - (nanoTime() - started) / 1_000_000L
                if (remaining <= 0 || !canSend() || !prefs.ntfyConfigured) return
                val item = nextMessage(sp) ?: return
                when (val result = deliver(request(prefs, item), remaining.coerceAtMost(ReportingHttp.TIMEOUT_MS))) {
                    DeliveryResult.Delivered -> {
                        acknowledge(sp, item.getString("id"))
                        sp.edit().remove(KEY_NEXT_ATTEMPT).apply()
                        delivered++
                        failing = false
                    }
                    is DeliveryResult.Failed -> {
                        sp.edit().putLong(KEY_NEXT_ATTEMPT, System.currentTimeMillis() + RETRY_BACKOFF_MS).apply()
                        if (force || !failing) {
                            EventLog.add(context, EventLevel.WARN, "ntfy delivery failed (${result.reason}); queued for retry")
                        }
                        failing = true
                        return
                    }
                }
            }
        } finally {
            flushing.set(false)
            if (delivered > 0) {
                EventLog.add(
                    context,
                    EventLevel.INFO,
                    if (delivered == 1) "Sent 1 ntfy notification"
                    else "Sent $delivered queued ntfy notifications"
                )
            }
        }
    }

    private fun nextMessage(sp: android.content.SharedPreferences): JSONObject? = synchronized(queueLock) {
        var array = readOutbox(sp)
        val now = System.currentTimeMillis()
        var changed = false
        while (array.length() > 0) {
            val item = array.optJSONObject(0)
            if (item != null && now - item.optLong("at", now) <= MAX_AGE_MS) break
            array = drop(array)
            changed = true
        }
        val item = array.optJSONObject(0)
        // Older releases did not assign IDs. Persist one before releasing the
        // lock so an acknowledgement cannot erase a concurrently enqueued event.
        if (item != null && !item.has("id")) {
            item.put("id", UUID.randomUUID().toString())
            changed = true
        }
        if (changed) sp.edit().putString(KEY_OUTBOX, array.toString()).apply()
        item
    }

    private fun acknowledge(sp: android.content.SharedPreferences, id: String) {
        synchronized(queueLock) {
            val current = readOutbox(sp)
            val remaining = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                if (item.optString("id") != id) remaining.put(item)
            }
            sp.edit().putString(KEY_OUTBOX, remaining.toString()).apply()
        }
    }

    private fun drop(array: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 1 until array.length()) out.put(array.opt(i))
        return out
    }

    private fun readOutbox(sp: android.content.SharedPreferences): JSONArray =
        runCatching { JSONArray(sp.getString(KEY_OUTBOX, "[]")) }.getOrDefault(JSONArray())

    // ---------------------------------------------------------------- publish

    private fun request(prefs: Prefs, item: JSONObject): ReportingRequest {
        val base = prefs.ntfyUrl.trimEnd('/')
        val topic = prefs.ntfyTopic
        val headers = mutableMapOf<String, String>()
        authHeader(prefs)?.let { headers["Authorization"] = it }
        header("X-Title", item.optString("title"))?.let { headers["X-Title"] = it }
        header("X-Tags", item.optString("tags"))?.let { headers["X-Tags"] = it }
        headers["X-Priority"] = item.optInt("priority", PRIORITY_DEFAULT).coerceIn(1, 5).toString()
        return ReportingRequest("$base/$topic", body(item), headers)
    }

    /**
     * Queued messages can be delivered long after the fact, so a late delivery is
     * called out explicitly to avoid the timestamps looking wrong.
     */
    private fun body(item: JSONObject): String {
        val text = item.optString("body")
        val at = item.optLong("at", 0L)
        val delay = System.currentTimeMillis() - at
        if (at <= 0L || delay < DELAYED_THRESHOLD_MS) return text
        return text + "\nQueued while offline, delivered " + timestamp(System.currentTimeMillis())
    }

    /**
     * Basic auth when a username is present, bearer when only a token was given.
     * Anonymous publishing to a public topic is valid, so no header at all is
     * a legitimate outcome.
     */
    private fun authHeader(prefs: Prefs): String? {
        val user = prefs.ntfyUser
        val password = prefs.ntfyPassword
        return when {
            user.isNotEmpty() -> {
                val raw = "$user:$password".toByteArray(Charsets.UTF_8)
                "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
            }

            password.isNotEmpty() -> "Bearer $password"
            else -> null
        }
    }

    /**
     * ntfy headers are latin-1 on the wire and newlines break the framing, so
     * anything unsafe is stripped rather than risking a rejected request.
     */
    private fun header(name: String, value: String): String? {
        val clean = value.replace(Regex("[\\r\\n]"), " ").trim()
        if (clean.isEmpty()) return null
        val safe = clean.filter { it.code in 32..126 }
        return safe.ifEmpty { null }
    }

    // ----------------------------------------------------------------- helpers

    /** Queued messages can arrive much later, so events carry their own clock. */
    fun timestamp(at: Long): String =
        SimpleDateFormat("MMM d, h:mm a z", Locale.US).format(Date(at))
}
