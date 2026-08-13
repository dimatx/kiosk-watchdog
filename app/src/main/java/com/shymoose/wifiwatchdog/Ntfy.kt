package com.shymoose.wifiwatchdog

import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private const val TIMEOUT_MS = 5_000
    private const val DELAYED_THRESHOLD_MS = 90_000L

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
        val array = readOutbox(sp)
        array.put(
            JSONObject()
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

    fun pendingCount(context: Context): Int =
        readOutbox(PreferenceManager.getDefaultSharedPreferences(context)).length()

    /**
     * Attempts to deliver everything queued, oldest first. Stops at the first
     * failure so ordering is preserved, and backs off so a reachable gateway
     * with no internet does not stall every tick.
     */
    fun flush(context: Context, force: Boolean = false) {
        val prefs = Prefs(context)
        if (!prefs.ntfyConfigured) return

        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        var array = readOutbox(sp)
        if (array.length() == 0) return

        val now = System.currentTimeMillis()
        if (!force && now < sp.getLong(KEY_NEXT_ATTEMPT, 0L)) return

        var delivered = 0
        var failed = false

        while (array.length() > 0) {
            val item = array.optJSONObject(0)
            if (item == null) {
                array = drop(array)
                continue
            }
            // Expired entries are discarded rather than retried forever.
            if (now - item.optLong("at", now) > MAX_AGE_MS) {
                array = drop(array)
                continue
            }
            val ok = runCatching { publish(prefs, item) }.getOrDefault(false)
            if (!ok) {
                failed = true
                break
            }
            delivered++
            array = drop(array)
        }

        val editor = sp.edit().putString(KEY_OUTBOX, array.toString())
        if (failed) {
            editor.putLong(KEY_NEXT_ATTEMPT, now + RETRY_BACKOFF_MS)
        } else {
            editor.remove(KEY_NEXT_ATTEMPT)
        }
        editor.apply()

        if (delivered > 0) {
            EventLog.add(
                context,
                EventLevel.INFO,
                if (delivered == 1) "Sent 1 ntfy notification"
                else "Sent $delivered queued ntfy notifications"
            )
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

    /** @return true when ntfy accepted the message. */
    private fun publish(prefs: Prefs, item: JSONObject): Boolean {
        val base = prefs.ntfyUrl.trimEnd('/')
        val topic = prefs.ntfyTopic
        val target = URL("$base/$topic")

        val conn = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            authHeader(prefs)?.let { setRequestProperty("Authorization", it) }
            header("X-Title", item.optString("title"))?.let { setRequestProperty("X-Title", it) }
            header("X-Tags", item.optString("tags"))?.let { setRequestProperty("X-Tags", it) }
            val priority = item.optInt("priority", PRIORITY_DEFAULT)
            setRequestProperty("X-Priority", priority.coerceIn(1, 5).toString())
        }

        return try {
            conn.outputStream.use { out: OutputStream ->
                out.write(body(item).toByteArray(Charsets.UTF_8))
            }
            conn.responseCode in 200..299
        } finally {
            runCatching { conn.disconnect() }
        }
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
