package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * A throwaway HTTP server for editing settings from a desktop browser.
 *
 * These displays are wall-mounted and driven by a remote, so typing an ntfy topic
 * or a push URL on the device itself is miserable. The server binds only while the
 * app is in the foreground plus a short grace window, so nothing is listening for
 * the 99% of the time the watchdog is just sitting there.
 */
object ConfigServer {

    private const val TAG = "ConfigServer"

    /** How long the window stays open after the app is opened or the window is extended. */
    const val WINDOW_MS = 5 * 60 * 1000L

    /** First is preferred; the rest cover a clash with whatever else is on the device. */
    private val PORT_RANGE = 8080..8089

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var workers: ExecutorService? = null

    /** Enough to keep a browser's parallel connections moving, few enough to bound them. */
    private const val WORKER_THREADS = 4

    /** Readings returned by /vitals — a few hours at the default interval. */
    private const val VITALS_PAGE = 400

    @Volatile
    private var expiresAtMillis = 0L

    /** The port actually bound, or 0 when the server is not listening. */
    @Volatile
    var port: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { stop() }

    val isRunning: Boolean get() = server != null

    fun secondsRemaining(): Long =
        if (!isRunning) 0L else ((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)

    /** "http://10.0.0.42:8080", or null when the link is down or nothing is bound. */
    fun url(context: Context): String? {
        val ip = DeviceIdentity.snapshot(context).ip ?: return null
        return if (isRunning) "http://$ip:$port" else null
    }

    /**
     * Opens the window, or extends it when the server is already up. Safe to call
     * from every `onStart`.
     */
    @Synchronized
    fun start(context: Context) {
        expiresAtMillis = System.currentTimeMillis() + WINDOW_MS
        handler.removeCallbacks(expiry)
        handler.postDelayed(expiry, WINDOW_MS)

        if (server != null) return

        val app = context.applicationContext
        val socket = bind() ?: run {
            Log.w(TAG, "no free port in $PORT_RANGE")
            return
        }
        server = socket
        port = socket.localPort
        workers = Executors.newFixedThreadPool(WORKER_THREADS) { r ->
            Thread(r, "config-server-worker").apply { isDaemon = true }
        }
        Thread({ serve(app, socket) }, "config-server").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $port")
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(expiry)
        val socket = server ?: return
        server = null
        port = 0
        expiresAtMillis = 0L
        workers?.shutdownNow()
        workers = null
        // Unblocks accept(), which is how the serving thread learns to exit.
        runCatching { socket.close() }
    }

    private fun bind(): ServerSocket? {        for (candidate in PORT_RANGE) {
            try {
                return ServerSocket(candidate)
            } catch (_: BindException) {
                // Port taken; try the next one.
            } catch (e: Exception) {
                Log.w(TAG, "bind $candidate failed", e)
            }
        }
        return null
    }

    private fun serve(context: Context, socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break // Closed by stop(), or the interface went away.
            }
            // Off the accept loop: browsers routinely open a speculative
            // connection and send nothing on it, and handling inline meant that
            // socket held up every other request until its read timed out.
            val worker = workers
            if (worker == null || worker.isShutdown) {
                runCatching { client.close() }
                break
            }
            runCatching {
                worker.execute {
                    runCatching { handle(context, client) }
                        .onFailure { Log.w(TAG, "request failed", it) }
                    runCatching { client.close() }
                }
            }.onFailure { runCatching { client.close() } }
        }
        Log.i(TAG, "stopped")
    }

    // --------------------------------------------------------------- HTTP

    /**
     * Reads one CRLF-terminated line straight off the socket.
     *
     * Deliberately not a `BufferedReader`: a decoder pulls ahead into its own
     * buffer, which would swallow the first bytes of the body before it can be
     * read at its declared byte length.
     */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(c.toChar())
        }
    }

    private fun handle(context: Context, client: Socket) {
        client.soTimeout = 10_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = if (target.contains('?')) parse(target.substringAfter('?')) else emptyMap()

        var contentLength = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        // Content-Length counts bytes. Reading that many *characters* through a
        // decoder works only while the body is ASCII, and /import takes arbitrary
        // JSON — a single non-ASCII character left the read waiting for input that
        // was never coming, until the socket timed out and the body was truncated.
        val body = if (method == "POST" && contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buffer, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else ""

        val out = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
        when {
            method == "POST" && path == "/save" -> {
                val message = save(context, parse(body))
                redirect(out, message)
            }

            method == "POST" && path == "/field" -> {
                val form = parse(body)
                val result = field(context, form["k"].orEmpty(), form["v"].orEmpty())
                val bytes = result.toByteArray(Charsets.UTF_8).size
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: $bytes\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                )
                out.write(result)
                out.flush()
            }

            method == "POST" && path == "/action" -> {
                val message = action(context, parse(body)["a"].orEmpty())
                redirect(out, message)
            }

            // Plain text on purpose: this is read when a display has misbehaved,
            // often from a phone, and it should paste straight into a report.
            path == "/vitals" -> {
                val lines = Vitals.recent(context, VITALS_PAGE)
                val text = if (lines.isEmpty()) {
                    "No readings recorded yet.\n"
                } else {
                    lines.joinToString("\n") { it.describe() } + "\n"
                }
                val bytes = text.toByteArray(Charsets.UTF_8).size
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: $bytes\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                )
                out.write(text)
                out.flush()
            }

            method == "POST" && path == "/import" -> {
                // curl posts raw JSON; the browser form posts it urlencoded in "json".
                val trimmed = body.trimStart()
                val raw = if (trimmed.startsWith("{")) trimmed else parse(body)["json"].orEmpty()
                redirect(out, importJson(context, raw))
            }

            path == "/export" -> {
                val json = exportJson(context)
                val bytes = json.toByteArray(Charsets.UTF_8).size
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Disposition: attachment; filename=\"${exportFilename(context)}\"\r\n" +
                        "Content-Length: $bytes\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                )
                out.write(json)
                out.flush()
            }

            path == "/favicon.svg" || path == "/favicon.ico" -> {
                val bytes = FAVICON.toByteArray(Charsets.UTF_8).size
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: image/svg+xml; charset=utf-8\r\n" +
                        "Content-Length: $bytes\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n"
                )
                out.write(FAVICON)
                out.flush()
            }

            path == "/" -> respond(out, page(context, query["m"]))
            else -> {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                out.flush()
            }
        }
    }

    private fun respond(out: OutputStreamWriter, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8).size
        out.write(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: $bytes\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        )
        out.write(html)
        out.flush()
    }

    /**
     * Post/redirect/get so a browser refresh does not resubmit the form. The banner
     * rides along in the query string rather than in server state.
     */
    private fun redirect(out: OutputStreamWriter, message: String) {
        val target = "/?m=" + encode(message)
        out.write(
            "HTTP/1.1 303 See Other\r\n" +
                "Location: $target\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        )
        out.flush()
    }

    private fun parse(body: String): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (name.isEmpty()) null else decode(name) to decode(value)
            }
            .toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        value.replace(" ", "%20").replace("&", "%26").replace("#", "%23")

    // -------------------------------------------------------------- Actions

    private fun action(context: Context, which: String): String {
        val intent = Intent(context, WatchdogService::class.java)
        return when (which) {
            "ntfy" -> {
                ContextCompat.startForegroundService(
                    context, intent.setAction(WatchdogService.ACTION_SEND_TEST)
                )
                "Test notification queued."
            }

            "heartbeat" -> {
                ContextCompat.startForegroundService(
                    context, intent.setAction(WatchdogService.ACTION_SEND_HEARTBEAT)
                )
                "Heartbeat sent."
            }

            "restart" -> {
                WatchdogService.start(context)
                "Watchdog restarted."
            }

            else -> "Unknown action."
        }
    }

    // ---------------------------------------------------------- Writing values

    /**
     * What became of one attempted write.
     *
     * Three routes reach the preferences - the form's Save button, the browser's
     * autosave on a single field, and a config import - and they differ only in
     * how they report the outcome. Sharing the outcome lets them share the write.
     */
    private sealed class Written {
        object Applied : Written()

        /** A blank password: the stored secret is deliberately left alone. */
        object Skipped : Written()

        object Unknown : Written()
        class Rejected(val reason: String) : Written()
    }

    /**
     * Writes one key, whatever it is.
     *
     * This used to be open-coded three times, and the copies had drifted: the
     * import path accepted only "1" and "true" for a checkbox, so importing "yes"
     * or "on" silently stored false and still reported success. One
     * implementation means one set of rules.
     *
     * String values are written even for numeric fields. Every numeric preference
     * is stored as a String because the settings screen uses EditTextPreference,
     * and writing an Int here would make the matching Prefs.getString throw.
     *
     * [editor] is not committed here; the caller decides when.
     */
    private fun writeField(
        context: Context,
        editor: SharedPreferences.Editor,
        key: String,
        raw: String
    ): Written {
        val value = raw.trim()
        if (key.isEmpty()) return Written.Unknown

        // Not a preference at all - it lives in the system settings, so it is
        // written straight through rather than through the editor.
        if (key == "device_name") {
            if (value.isEmpty()) return Written.Rejected("Device name cannot be blank.")
            if (value == DeviceIdentity.hostname(context)) return Written.Skipped
            val ok = runCatching {
                Settings.Global.putString(context.contentResolver, "device_name", value)
            }.getOrDefault(false)
            return if (ok) Written.Applied
            else Written.Rejected("Device name needs the WRITE_SECURE_SETTINGS grant.")
        }

        // The master switch is on the main screen rather than in a section, so it
        // has no Field entry to look up.
        if (key == Prefs.KEY_ENABLED) {
            val on = parseBool(value) ?: return rejectBool(key, value)
            editor.putBoolean(key, on)
            return Written.Applied
        }

        // The companion checkbox that erases a stored secret.
        if (key.endsWith("_clear")) {
            val target = key.removeSuffix("_clear")
            if (FIELDS.none { it.key == target && it.kind == Kind.PASSWORD }) return Written.Unknown
            val on = parseBool(value) ?: return rejectBool(key, value)
            if (!on) return Written.Skipped
            editor.putString(target, "")
            return Written.Applied
        }

        val field = FIELDS.firstOrNull { it.key == key } ?: return Written.Unknown
        return when (field.kind) {
            Kind.BOOL -> {
                val on = parseBool(value) ?: return rejectBool(key, value)
                editor.putBoolean(key, on)
                Written.Applied
            }

            Kind.PASSWORD ->
                if (value.isEmpty()) Written.Skipped
                else {
                    editor.putString(key, value)
                    Written.Applied
                }

            else -> {
                editor.putString(key, value)
                Written.Applied
            }
        }
    }

    /** Null when the value is not recognisable either way, so it can be refused. */
    private fun parseBool(value: String): Boolean? = when (value.lowercase()) {
        "1", "true", "on", "yes" -> true
        "0", "false", "off", "no" -> false
        else -> null
    }

    private fun rejectBool(key: String, value: String) =
        Written.Rejected("Expected a yes or no value for '$key', got '$value'.")

    // ---------------------------------------------------------------- Save

    private fun save(context: Context, form: Map<String, String>): String {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        var note = ""

        fun write(key: String, value: String) {
            (writeField(context, editor, key, value) as? Written.Rejected)?.let { note = " " + it.reason }
        }

        for (field in FIELDS) {
            when (field.kind) {
                // An unchecked box is simply absent from the body, and the form
                // always renders every field, so absence means false.
                Kind.BOOL -> write(field.key, if (form.containsKey(field.key)) "1" else "0")

                Kind.PASSWORD ->
                    if (form.containsKey(field.key + "_clear")) write(field.key + "_clear", "1")
                    else write(field.key, form[field.key].orEmpty())

                else -> form[field.key]?.let { write(field.key, it) }
            }
        }

        val enabled = form.containsKey(Prefs.KEY_ENABLED)
        write(Prefs.KEY_ENABLED, if (enabled) "1" else "0")
        form["device_name"]?.let { write("device_name", it) }
        editor.apply()

        // Intervals and thresholds are read once at schedule time, so the service has
        // to be rebuilt for anything here to take effect.
        if (enabled) WatchdogService.start(context) else WatchdogService.stop(context)
        return "Saved.$note"
    }

    /**
     * Persists exactly one field, for the browser's autosave-on-change path.
     *
     * The service is rebuilt on a short debounce rather than per field: intervals
     * and thresholds are only read at schedule time, so a rebuild is required, but
     * doing it on every keystroke-blur would restart the alarm chain repeatedly
     * while someone tabs through a form.
     */
    private fun field(context: Context, key: String, raw: String): String {
        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        val result = writeField(context, editor, key, raw)
        return when (result) {
            is Written.Rejected -> result.reason
            Written.Unknown -> "Unknown field."
            else -> {
                editor.apply()
                scheduleRebuild(context)
                "Saved"
            }
        }
    }

    private val rebuild = Handler(Looper.getMainLooper())
    private var rebuildTask: Runnable? = null

    private fun scheduleRebuild(context: Context) {
        val app = context.applicationContext
        rebuildTask?.let { rebuild.removeCallbacks(it) }
        val task = Runnable {
            if (Prefs(app).enabled) WatchdogService.start(app) else WatchdogService.stop(app)
        }
        rebuildTask = task
        rebuild.postDelayed(task, 1500)
    }

    // ------------------------------------------------------------- Backup

    /** Bumped only if the key set ever changes shape enough to need migrating. */
    private const val FORMAT = 1

    /**
     * Every user-visible setting, and nothing else. Runtime bookkeeping (last-seen
     * timestamps, the ntfy outbox, the saved assistant slot) is deliberately left
     * out so a config file can be dropped onto a second display unchanged.
     */
    private fun exportJson(context: Context): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val json = JSONObject()
        json.put("_format", FORMAT)
        json.put("device_name", DeviceIdentity.hostname(context))
        json.put(Prefs.KEY_ENABLED, Prefs(context).enabled)
        for (field in FIELDS) {
            when (field.kind) {
                Kind.BOOL -> json.put(field.key, sp.getBoolean(field.key, field.def == "true"))
                else -> json.put(field.key, sp.getString(field.key, field.def).orEmpty())
            }
        }
        return json.toString(2)
    }

    private fun exportFilename(context: Context): String {
        val name = DeviceIdentity.hostname(context)
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-')
        return "kiosk-watchdog-" + name.ifEmpty { "config" } + ".json"
    }

    /**
     * Applies whatever it recognises and ignores the rest, so a file exported by a
     * newer build still imports cleanly. Numbers are written as strings on purpose:
     * the settings screen reads them back through `EditTextPreference`, which would
     * throw on a real Int.
     */
    private fun importJson(context: Context, raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "Nothing to import."
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return "That does not look like a config file."

        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
        var applied = 0
        var unknown = 0
        var note = ""

        for (key in json.keys()) {
            // Reserved for the export's own metadata.
            if (key.startsWith("_")) continue
            val value = json.opt(key)?.toString().orEmpty()
            when (val result = writeField(context, editor, key, value)) {
                Written.Applied -> applied++
                Written.Unknown -> unknown++
                // A blank password, or a device name already set to this value.
                Written.Skipped -> Unit
                is Written.Rejected -> note = " " + result.reason
            }
        }

        editor.apply()
        scheduleRebuild(context)

        val ignored = if (unknown == 0) "" else " Ignored $unknown unrecognised key${plural(unknown)}."
        return "Imported $applied setting${plural(applied)}.$ignored$note"
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    // ---------------------------------------------------------------- View

    private enum class Kind { TEXT, NUMBER, PASSWORD, BOOL }

    /**
     * [def] mirrors the fallback baked into [Prefs]. The app never calls
     * `setDefaultValues`, so an untouched key is genuinely absent from storage and
     * the form has to supply the same default the service would have used.
     */
    private class Field(
        val key: String,
        val label: String,
        val kind: Kind,
        val hint: String = "",
        val def: String = ""
    )

    private class Section(val tab: String, val title: String, val fields: List<Field>)

    private val SECTIONS = listOf(
        Section(
            "Probe", "Connectivity probe", listOf(
                Field(
                    Prefs.KEY_PROBE_HOST, "Probe host", Kind.TEXT,
                    "Blank follows the default gateway.", Prefs.DEFAULT_HOST
                ),
                Field(
                    Prefs.KEY_PROBE_PORT, "Probe port", Kind.NUMBER,
                    "TCP fallback when ICMP fails. Only used with a pinned host.",
                    Prefs.DEFAULT_PORT
                ),
                Field(
                    Prefs.KEY_INTERVAL, "Check interval (s)", Kind.NUMBER,
                    "10–600.", Prefs.DEFAULT_INTERVAL.toString()
                )
            )
        ),
        Section(
            "Recovery", "Escalation", listOf(
                Field(
                    Prefs.KEY_T_REASSOCIATE, "Reassociate after (s)", Kind.NUMBER,
                    "Rung 1: reconnect to the same AP.", Prefs.DEFAULT_T_REASSOCIATE.toString()
                ),
                Field(
                    Prefs.KEY_T_SOFT, "Soft toggle after (s)", Kind.NUMBER,
                    "Rung 2: Wi-Fi off and on.", Prefs.DEFAULT_T_SOFT.toString()
                ),
                Field(
                    Prefs.KEY_T_HARD, "Hard reset after (s)", Kind.NUMBER,
                    "Rung 3: full driver unload.", Prefs.DEFAULT_T_HARD.toString()
                ),
                Field(Prefs.KEY_HARD_ENABLED, "Enable hard reset", Kind.BOOL, "", "true"),
                Field(
                    Prefs.KEY_KEEP_BT_OFF, "Keep Bluetooth off", Kind.BOOL,
                    "Bluetooth shares a chip with Wi-Fi; scanning competes with the link.",
                    Prefs.DEFAULT_KEEP_BT_OFF.toString()
                )
            )
        ),
        Section(
            "Recovery", "Airplane cycle", listOf(
                Field(
                    Prefs.KEY_AIRPLANE_ENABLED, "Enable airplane cycle", Kind.BOOL,
                    "Rung 4: last resort.", "true"
                ),
                Field(
                    Prefs.KEY_T_AIRPLANE, "Airplane cycle after (s)", Kind.NUMBER,
                    "", Prefs.DEFAULT_T_AIRPLANE.toString()
                ),
                Field(
                    Prefs.KEY_AIRPLANE_DWELL, "Dwell in airplane mode (s)", Kind.NUMBER,
                    "5–300.", Prefs.DEFAULT_AIRPLANE_DWELL.toString()
                )
            )
        ),
        Section(
            "Reporting", "ntfy reporting", listOf(
                Field(
                    Prefs.KEY_NTFY_URL, "Server URL", Kind.TEXT,
                    "https://ntfy.sh or your own instance.", Prefs.DEFAULT_NTFY_URL
                ),
                Field(Prefs.KEY_NTFY_TOPIC, "Topic", Kind.TEXT, "Blank disables reporting."),
                Field(Prefs.KEY_NTFY_USER, "Username", Kind.TEXT, "Blank when using an access token."),
                Field(
                    Prefs.KEY_NTFY_PASSWORD, "Password or token", Kind.PASSWORD,
                    "Blank leaves the stored value alone."
                )
            )
        ),
        Section(
            "Reporting", "Heartbeat", listOf(
                Field(
                    Prefs.KEY_HEARTBEAT_URL, "Push URL", Kind.TEXT,
                    "Uptime Kuma push URL, or blank to disable."
                ),
                Field(
                    Prefs.KEY_HEARTBEAT_INTERVAL, "Interval (s)", Kind.NUMBER,
                    "30–86400. Keep it well under the monitor's own interval.",
                    Prefs.DEFAULT_HEARTBEAT_INTERVAL.toString()
                )
            )
        ),
        Section(
            "Recovery", "App update auto-confirm", listOf(
                Field(
                    Prefs.KEY_AUTO_INSTALL_ENABLED, "Confirm update dialogs", Kind.BOOL,
                    "Taps INSTALL for allowlisted apps. Needs the accessibility service " +
                        "enabled from the on-device settings screen.",
                    Prefs.DEFAULT_AUTO_INSTALL_ENABLED.toString()
                ),
                Field(
                    Prefs.KEY_AUTO_INSTALL_ALLOWLIST, "Allowed apps", Kind.TEXT,
                    "Comma-separated app names as shown in the install dialog.",
                    Prefs.DEFAULT_AUTO_INSTALL_ALLOWLIST
                ),
                Field(
                    Prefs.KEY_KIOSK_PACKAGE, "Kiosk app package", Kind.TEXT,
                    "Put back in front when this app is left showing. Blank turns it off.",
                    Prefs.DEFAULT_KIOSK_PACKAGE
                ),
                Field(
                    Prefs.KEY_KIOSK_RETURN_MIN, "Return to kiosk after (min)", Kind.NUMBER,
                    "1-240.", Prefs.DEFAULT_KIOSK_RETURN_MIN.toString()
                )
            )
        )
    )

    private val FIELDS = SECTIONS.flatMap { it.fields }

    private fun page(context: Context, message: String?): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        val identity = DeviceIdentity.snapshot(context)
        val state = WatchdogService.State
        val prefs = Prefs(context)

        val sb = StringBuilder(8192)
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">")
        sb.append("<title>Kiosk Watchdog — ").append(esc(identity.hostname)).append("</title>")
        sb.append("<style>").append(CSS).append("</style></head><body><main>")

        sb.append("<h1>Kiosk Watchdog</h1>")
        sb.append("<p class=\"sub\">").append(esc(identity.oneLine())).append("</p>")

        val online = state.online
        sb.append("<p class=\"pill ").append(if (!prefs.enabled) "paused" else if (online) "ok" else "bad")
            .append("\">")
            .append(if (!prefs.enabled) "Paused" else if (online) "Online" else "Offline")
            .append("</p>")

        if (message != null) sb.append("<p class=\"banner\">").append(esc(message)).append("</p>")

        val tabs = listOf("General", "Probe", "Recovery", "Reporting", "Tools")
        sb.append("<nav class=\"tabs\">")
        for ((index, tab) in tabs.withIndex()) {
            sb.append("<button type=\"button\" class=\"tab")
            if (index == 0) sb.append(" on")
            sb.append("\" data-tab=\"").append(index).append("\">").append(esc(tab)).append("</button>")
        }
        sb.append("</nav>")

        sb.append("<form method=\"post\" action=\"/save\">")

        sb.append("<div class=\"panel on\" data-panel=\"0\">")
        sb.append("<section><h2>Identity</h2>")
        sb.append(text("device_name", "Device name", identity.hostname, "Shown in every notification."))
        sb.append("</section>")
        sb.append("<section><h2>Watchdog</h2>")
        sb.append(checkbox(Prefs.KEY_ENABLED, "Watchdog enabled", prefs.enabled, ""))
        sb.append("</section>")
        sb.append("</div>")

        for ((index, tab) in tabs.withIndex()) {
            val sections = SECTIONS.filter { it.tab == tab }
            if (sections.isEmpty()) continue
            sb.append("<div class=\"panel\" data-panel=\"").append(index).append("\">")
            for (section in sections) {
                sb.append("<section><h2>").append(esc(section.title)).append("</h2>")
                for (field in section.fields) {
                    sb.append(
                        when (field.kind) {
                            Kind.BOOL -> checkbox(
                                field.key, field.label,
                                sp.getBoolean(field.key, field.def == "true"), field.hint
                            )

                            Kind.PASSWORD -> password(field)
                            else -> text(
                                field.key,
                                field.label,
                                sp.getString(field.key, field.def).orEmpty(),
                                field.hint,
                                numeric = field.kind == Kind.NUMBER
                            )
                        }
                    )
                }
                sb.append("</section>")
            }
            sb.append("</div>")
        }

        // Kept for browsers without scripting: autosave hides it on load.
        sb.append("<button type=\"submit\" id=\"saveall\">Save and restart watchdog</button></form>")

        sb.append("<div class=\"panel\" data-panel=\"").append(tabs.indexOf("Tools")).append("\">")
        sb.append("<section class=\"actions\"><h2>Test</h2>")
        sb.append(actionButton("ntfy", "Send test notification"))
        sb.append(actionButton("heartbeat", "Send heartbeat now"))
        sb.append(actionButton("restart", "Restart watchdog"))
        sb.append("</section>")
        sb.append("<section><h2>Backup</h2>")
        sb.append("<a class=\"btn\" href=\"/export\" download>Download config JSON</a>")
        sb.append("<small>Contains the ntfy password in plain text. ")
            .append("Change <code>device_name</code> before importing onto another display.</small>")
        sb.append("<form method=\"post\" action=\"/import\">")
        sb.append("<label><span>Restore from JSON</span>")
        sb.append("<input type=\"file\" id=\"pick\" accept=\".json,application/json\">")
        sb.append("<textarea id=\"paste\" name=\"json\" rows=\"7\" ")
            .append("placeholder=\"Choose a file above, or paste the JSON here\"></textarea>")
        sb.append("<small>Unrecognised keys are ignored. ")
            .append("Leave the password blank to keep the one already stored.</small></label>")
        sb.append("<button type=\"submit\">Import and restart watchdog</button></form>")
        sb.append("</section>")
        sb.append("</div>")

        sb.append("<p class=\"foot\">This page closes itself about ")
            .append(WINDOW_MS / 60000).append(" minutes after the app was last opened. ")
            .append("Reopen the app on the device to get it back.</p>")

        sb.append("<script>").append(JS).append("</script>")
        sb.append("</main></body></html>")
        return sb.toString()
    }

    private fun text(
        key: String,
        label: String,
        value: String,
        hint: String,
        numeric: Boolean = false
    ): String = buildString {
        append("<label><span>").append(esc(label)).append("</span>")
        append("<input name=\"").append(key).append("\" type=\"")
        append(if (numeric) "number" else "text")
        append("\" value=\"").append(esc(value)).append("\">")
        if (hint.isNotEmpty()) append("<small>").append(esc(hint)).append("</small>")
        append("</label>")
    }

    private fun password(field: Field): String = buildString {
        append("<label><span>").append(esc(field.label)).append("</span>")
        append("<input name=\"").append(field.key)
            .append("\" type=\"password\" placeholder=\"unchanged\" autocomplete=\"new-password\">")
        append("<small>").append(esc(field.hint)).append("</small></label>")
        append("<label class=\"check\"><input type=\"checkbox\" name=\"")
            .append(field.key).append("_clear\"><span>Clear the stored value</span></label>")
    }

    private fun checkbox(key: String, label: String, checked: Boolean, hint: String): String = buildString {
        append("<label class=\"check\"><input type=\"checkbox\" name=\"").append(key).append("\"")
        if (checked) append(" checked")
        append("><span>").append(esc(label)).append("</span></label>")
        if (hint.isNotEmpty()) append("<small class=\"under\">").append(esc(hint)).append("</small>")
    }

    private fun actionButton(action: String, label: String): String =
        "<form method=\"post\" action=\"/action\" class=\"inline\">" +
            "<input type=\"hidden\" name=\"a\" value=\"$action\">" +
            "<button type=\"submit\" class=\"ghost\">" + esc(label) + "</button></form>"

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Wi-Fi arcs on a rounded blue tile. Served at /favicon.svg and /favicon.ico. */
    private val FAVICON = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">
          <rect width="32" height="32" rx="7" fill="#2f81f7"/>
          <g fill="none" stroke="#ffffff" stroke-width="2.6" stroke-linecap="round">
            <path d="M7.5 14.2a12 12 0 0 1 17 0"/>
            <path d="M11.4 18.4a6.5 6.5 0 0 1 9.2 0"/>
          </g>
          <circle cx="16" cy="23.6" r="2.2" fill="#ffffff"/>
        </svg>
    """.trimIndent()

    private val CSS = """
        :root{color-scheme:dark light}
        *{box-sizing:border-box}
        body{margin:0;background:#111417;color:#e6e8ea;
             font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif}
        main{max-width:640px;margin:0 auto;padding:24px 16px 64px}
        h1{margin:0;font-size:24px}
        h2{margin:0 0 12px;font-size:13px;text-transform:uppercase;
           letter-spacing:.08em;color:#8b949e}
        .sub{margin:4px 0 12px;color:#8b949e;font-size:13px}
        .pill{display:inline-block;margin:0 0 16px;padding:3px 12px;border-radius:999px;
              font-size:13px;font-weight:600}
        .pill.ok{background:#12331f;color:#4ade80}
        .pill.bad{background:#3a1618;color:#f87171}
        .pill.paused{background:#2b2b2b;color:#9ca3af}
        .banner{margin:0 0 16px;padding:10px 14px;border-radius:10px;
                background:#12283d;color:#7dd3fc}
        section{margin:0 0 20px;padding:16px;border:1px solid #23282d;border-radius:14px}
        label{display:block;margin:0 0 14px}
        label:last-child{margin-bottom:0}
        label>span{display:block;margin:0 0 5px;font-size:13px;color:#c9d1d9}
        input[type=text],input[type=number],input[type=password]{
            width:100%;padding:9px 11px;border:1px solid #30363d;border-radius:9px;
            background:#0d1117;color:#e6e8ea;font-size:15px}
        input:focus{outline:2px solid #2f81f7;outline-offset:-1px}
        small{display:block;margin-top:5px;color:#7d8590;font-size:12px}
        small.under{margin:-9px 0 14px}
        .check{display:flex;align-items:center;gap:9px}
        .check>span{margin:0;font-size:15px;color:#e6e8ea}
        .check input{width:17px;height:17px;accent-color:#2f81f7}
        button{width:100%;padding:12px;border:0;border-radius:10px;background:#2f81f7;
               color:#fff;font-size:15px;font-weight:600;cursor:pointer}
        button.ghost{background:#21262d;color:#e6e8ea;font-weight:500}
        a.btn{display:block;padding:12px;border-radius:10px;background:#21262d;
              color:#e6e8ea;font-size:15px;font-weight:500;text-align:center;
              text-decoration:none}
        textarea{width:100%;padding:9px 11px;border:1px solid #30363d;border-radius:9px;
                 background:#0d1117;color:#e6e8ea;resize:vertical;
                 font:13px/1.45 ui-monospace,SFMono-Regular,Consolas,monospace}
        textarea:focus{outline:2px solid #2f81f7;outline-offset:-1px}
        input[type=file]{width:100%;margin:0 0 9px;color:#9ca3af;font-size:13px}
        code{font:12px/1 ui-monospace,SFMono-Regular,Consolas,monospace;color:#c9d1d9}
        .inline{margin:0 0 8px}
        .actions{margin-top:24px}
        .tabs{display:flex;flex-wrap:wrap;gap:6px;margin:0 0 18px}
        .tab{width:auto;padding:7px 14px;border-radius:999px;background:#21262d;
             color:#9ca3af;font-size:14px;font-weight:500}
        .tab.on{background:#2f81f7;color:#fff;font-weight:600}
        .panel{display:none}
        .panel.on{display:block}
        .flag{margin-left:8px;font-size:12px;font-weight:600;opacity:0;
              transition:opacity .15s}
        .flag.show{opacity:1}
        .flag.good{color:#4ade80}
        .flag.bad{color:#f87171}
        label>span{position:relative}
        .foot{color:#7d8590;font-size:12px;text-align:center;margin-top:24px}
    """.trimIndent()

    private val JS = """
        (function () {
          var tabs = document.querySelectorAll('.tab');
          var panels = document.querySelectorAll('.panel');
          function show(index) {
            for (var i = 0; i < tabs.length; i++) {
              tabs[i].classList.toggle('on', tabs[i].dataset.tab === index);
            }
            for (var j = 0; j < panels.length; j++) {
              panels[j].classList.toggle('on', panels[j].dataset.panel === index);
            }
            try { sessionStorage.setItem('ww-tab', index); } catch (e) {}
          }
          for (var t = 0; t < tabs.length; t++) {
            tabs[t].addEventListener('click', function () { show(this.dataset.tab); });
          }
          var saved = null;
          try { saved = sessionStorage.getItem('ww-tab'); } catch (e) {}
          if (saved !== null) show(saved);

          var button = document.getElementById('saveall');
          if (button) button.style.display = 'none';

          function flag(input, text, good) {
            var label = input.closest('label');
            if (!label) return;
            var span = label.querySelector('span');
            if (!span) return;
            var mark = span.querySelector('.flag');
            if (!mark) {
              mark = document.createElement('em');
              mark.className = 'flag';
              span.appendChild(mark);
            }
            mark.textContent = text;
            mark.className = 'flag show ' + (good ? 'good' : 'bad');
            if (good) {
              clearTimeout(mark.timer);
              mark.timer = setTimeout(function () { mark.className = 'flag'; }, 1600);
            }
          }

          var inputs = document.querySelectorAll('form input[name]');
          for (var k = 0; k < inputs.length; k++) {
            inputs[k].addEventListener('change', function () {
              var value = this.type === 'checkbox' ? (this.checked ? '1' : '0') : this.value;
              var body = 'k=' + encodeURIComponent(this.name) + '&v=' + encodeURIComponent(value);
              var input = this;
              fetch('/field', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: body
              }).then(function (response) {
                return response.text();
              }).then(function (text) {
                flag(input, text, text === 'Saved');
              }).catch(function () {
                flag(input, 'Not saved', false);
              });
            });
          }

          var pick = document.getElementById('pick');
          var paste = document.getElementById('paste');
          if (pick && paste && window.FileReader) {
            pick.addEventListener('change', function () {
              var file = this.files && this.files[0];
              if (!file) return;
              var reader = new FileReader();
              reader.onload = function () { paste.value = reader.result; };
              reader.readAsText(file);
            });
          }
        })();
    """.trimIndent()
}
