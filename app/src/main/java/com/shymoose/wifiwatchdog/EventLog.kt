package com.shymoose.wifiwatchdog

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class EventLevel { INFO, WARN, ACTION, ERROR }

data class LogEvent(
    val timestamp: Long,
    val level: EventLevel,
    val message: String
) {
    fun formattedTime(): String =
        // Built per call rather than held in a static: a device that changes locale
        // or time zone while running would otherwise keep formatting with the old one,
        // and these displays stay up for months.
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Small persistent ring buffer. The watchdog service and the UI live in the same
 * process, so a static listener is enough to keep the screen live.
 */
object EventLog {

    private const val PREFS = "event_log"
    private const val KEY = "events"
    private const val MAX_EVENTS = 250
    private const val TAG = "WifiWatchdog"

    private val listeners = mutableSetOf<() -> Unit>()

    /**
     * Guards the whole read-modify-write below.
     *
     * Entries arrive from the watchdog's worker thread and from the accessibility
     * callback on the main thread, and the log is stored as one serialised blob.
     * Without this, two overlapping writes both read the same array and the
     * second one to finish discards the first one's entry - losing exactly the
     * kind of entry that gets logged when two things are happening at once.
     */
    private val writeLock = Any()

    fun add(context: Context, level: EventLevel, message: String) {
        Log.i(TAG, "[$level] $message")
        synchronized(writeLock) { append(context, level, message) }
        notifyListeners()
    }

    private fun append(context: Context, level: EventLevel, message: String) {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = readArray(sp.getString(KEY, null))

        val obj = JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("l", level.name)
            put("m", message)
        }
        array.put(obj)

        val trimmed = if (array.length() > MAX_EVENTS) {
            JSONArray().also { out ->
                for (i in (array.length() - MAX_EVENTS) until array.length()) out.put(array.get(i))
            }
        } else {
            array
        }

        // Anything that is not routine is written through to disk. Shared
        // preferences are otherwise flushed on their own schedule, and a device
        // that freezes loses whatever had not landed - which is reliably the
        // handful of entries that would have explained it. Routine chatter stays
        // asynchronous so the common path is not paying for this.
        val editor = sp.edit().putString(KEY, trimmed.toString())
        if (level == EventLevel.INFO) editor.apply() else editor.commit()
    }

    /** Newest first, for direct display in the list. */
    fun read(context: Context): List<LogEvent> {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = readArray(sp.getString(KEY, null))
        val out = ArrayList<LogEvent>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(
                LogEvent(
                    timestamp = o.optLong("t"),
                    level = runCatching { EventLevel.valueOf(o.optString("l")) }.getOrDefault(EventLevel.INFO),
                    message = o.optString("m")
                )
            )
        }
        out.reverse()
        return out
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it.invoke() } }
    }

    private fun readArray(raw: String?): JSONArray =
        if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
}
