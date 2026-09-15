package com.shymoose.wifiwatchdog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ships the on-device event log to a central OTLP/HTTP logs endpoint — Vector,
 * an OpenTelemetry Collector, OpenObserve, or anything else that accepts the
 * standard `/v1/logs` JSON envelope. No OTLP/gRPC or protobuf dependency is
 * needed: the wire format both Vector's `opentelemetry` source and OpenObserve
 * accept at that path is plain JSON, built here with the same `org.json` this
 * app already uses everywhere else.
 *
 * Mirrors [Ntfy]'s "queue what happened, flush when reachable" shape, except
 * the queue is the event log itself: a persisted cursor ([Prefs.otlpLastExportedSeq])
 * stands in for a separate outbox, so nothing is duplicated across restarts.
 */
object OtlpLogExporter {

    /** Caps one request after a long outage; the rest follow on later ticks. */
    private const val MAX_BATCH = 100

    /** Logged only on transitions so a persistent outage cannot flood the very log being exported. */
    private var failing = false

    internal fun flush(
        context: Context,
        deliver: (ReportingRequest, Long) -> DeliveryResult = ReportingHttp::send
    ) {
        val prefs = Prefs(context)
        if (!prefs.otlpConfigured) return
        val batch = EventLog.readSince(context, prefs.otlpLastExportedSeq, MAX_BATCH)
        if (batch.isEmpty()) return

        when (val result = deliver(request(context, prefs, batch), ReportingHttp.TIMEOUT_MS)) {
            DeliveryResult.Delivered -> {
                prefs.otlpLastExportedSeq = batch.last().seq
                if (failing) EventLog.add(context, EventLevel.INFO, "Central log export recovered")
                failing = false
            }
            is DeliveryResult.Failed -> {
                if (!failing) {
                    EventLog.add(
                        context, EventLevel.WARN,
                        "Central log export failed (${result.reason}); will retry"
                    )
                }
                failing = true
            }
        }
    }

    /** Sends one synthetic record immediately, bypassing the cursor. Logs its own outcome. */
    internal fun sendTest(
        context: Context,
        deliver: (ReportingRequest, Long) -> DeliveryResult = ReportingHttp::send
    ): Boolean {
        val prefs = Prefs(context)
        if (!prefs.otlpConfigured) return false
        val record = LogEvent(System.currentTimeMillis(), EventLevel.ACTION, "Central log export test")
        val result = deliver(request(context, prefs, listOf(record)), ReportingHttp.TIMEOUT_MS)
        val ok = result == DeliveryResult.Delivered
        if (ok) {
            EventLog.add(context, EventLevel.INFO, "Central log export test delivered")
        } else if (result is DeliveryResult.Failed) {
            EventLog.add(context, EventLevel.WARN, "Central log export test failed (${result.reason})")
        }
        return ok
    }

    private fun request(context: Context, prefs: Prefs, events: List<LogEvent>): ReportingRequest {
        val headers = mutableMapOf("Content-Type" to "application/json")
        prefs.otlpAuthHeader.takeIf { it.isNotEmpty() }?.let { headers["Authorization"] = it }
        prefs.otlpStreamName.takeIf { it.isNotEmpty() }?.let { headers["stream-name"] = it }
        val url = prefs.otlpEndpoint.trimEnd('/') + "/v1/logs"
        return ReportingRequest(url, body(context, events), headers)
    }

    /** One `resourceLogs` entry per request: the resource (this device) never varies within a batch. */
    private fun body(context: Context, events: List<LogEvent>): String {
        val id = DeviceIdentity.snapshot(context)
        val resourceAttrs = JSONArray().apply {
            put(attr("service.name", "kiosk-watchdog"))
            put(attr("service.version", BuildConfig.VERSION_NAME))
            // Flat keys (not OTel's dotted host.name/etc.) to match the field names
            // this OpenObserve instance's other log streams already use - the
            // syslog-sourced ones ingest as `hostname` and `source_ip`, and
            // OpenObserve flattens OTLP resource attributes verbatim rather than
            // mapping them onto its own conventions. Consistent naming here is
            // what lets a dashboard filter both stream families the same way.
            put(attr("hostname", id.hostname))
            id.ip?.let { put(attr("source_ip", it)) }
            id.mac?.let { put(attr("mac", it)) }
        }
        val logRecords = JSONArray().apply { events.forEach { put(record(it)) } }
        val scopeLogs = JSONObject()
            .put("scope", JSONObject().put("name", "com.shymoose.wifiwatchdog"))
            .put("logRecords", logRecords)
        val resourceLogs = JSONObject()
            .put("resource", JSONObject().put("attributes", resourceAttrs))
            .put("scopeLogs", JSONArray().put(scopeLogs))
        return JSONObject().put("resourceLogs", JSONArray().put(resourceLogs)).toString()
    }

    private fun attr(key: String, value: String): JSONObject =
        JSONObject().put("key", key).put("value", JSONObject().put("stringValue", value))

    /**
     * `timeUnixNano` is a fixed64 in the OTLP schema, so it is written as a JSON
     * string rather than a number — a plain JSON number that size loses
     * precision in some parsers.
     */
    private fun record(event: LogEvent): JSONObject {
        val (number, text) = severity(event.level)
        return JSONObject()
            .put("timeUnixNano", (event.timestamp * 1_000_000L).toString())
            .put("severityNumber", number)
            .put("severityText", text)
            .put("body", JSONObject().put("stringValue", event.message))
    }

    /** OTLP severity numbers, per the spec's short-name enum. */
    private fun severity(level: EventLevel): Pair<Int, String> = when (level) {
        EventLevel.INFO -> 9 to "INFO"
        EventLevel.ACTION -> 10 to "ACTION"
        EventLevel.WARN -> 13 to "WARN"
        EventLevel.ERROR -> 17 to "ERROR"
    }
}
