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
    fun formattedTime(): String = TIME_FORMAT.format(Date(timestamp))

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    }
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

    fun add(context: Context, level: EventLevel, message: String) {
        Log.i(TAG, "[$level] $message")
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

        sp.edit().putString(KEY, trimmed.toString()).apply()
        notifyListeners()
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
