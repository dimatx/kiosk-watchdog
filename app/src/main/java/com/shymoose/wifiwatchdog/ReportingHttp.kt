package com.shymoose.wifiwatchdog

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class ReportingRequest(
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap()
)

internal sealed class DeliveryResult {
    object Delivered : DeliveryResult()
    data class Failed(val reason: String) : DeliveryResult()
}

/** One deadline covers redirects, connecting, writing, and reading response headers. */
internal object ReportingHttp {
    const val TIMEOUT_MS = 10_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followSslRedirects(false)
        .build()

    fun send(request: ReportingRequest, timeoutMs: Long = TIMEOUT_MS): DeliveryResult {
        require(timeoutMs > 0)
        val httpRequest = try {
            Request.Builder().url(request.url).apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                // OkHttp's BridgeInterceptor derives the wire Content-Type from the
                // body's media type and always overwrites whatever header() set
                // above, so a caller's explicit Content-Type (e.g. OTLP's
                // application/json) has to be threaded through here too - setting
                // it only via headers silently downgrades every such request back
                // to text/plain.
                //
                // The bytes-based toRequestBody() overload is used rather than the
                // String one deliberately: String.toRequestBody() auto-appends
                // "; charset=utf-8" whenever the media type does not already
                // declare one, and at least one real receiver (OpenObserve's OTLP
                // ingestion) does a literal string match on Content-Type and 400s
                // the moment a charset parameter shows up. Encoding to bytes
                // ourselves keeps the header exactly what the caller asked for.
                val mediaType = (request.headers["Content-Type"] ?: "text/plain; charset=utf-8").toMediaType()
                request.body?.let {
                    val bytes = it.toByteArray(Charsets.UTF_8)
                    post(bytes.toRequestBody(mediaType, 0, bytes.size))
                }
            }.build()
        } catch (_: IllegalArgumentException) {
            return DeliveryResult.Failed("invalid HTTP request")
        }
        val call = client.newCall(httpRequest)
        call.timeout().timeout(timeoutMs.coerceAtMost(TIMEOUT_MS), TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                // Only the status matters. Never buffer an arbitrary response
                // body or wait for a streaming endpoint to reach EOF.
                if (response.isSuccessful) DeliveryResult.Delivered
                else {
                    // Bounded preview of the server's own error text (peekBody
                    // reads at most this many bytes without buffering the rest),
                    // so a misconfigured endpoint can be root-caused from the
                    // event log instead of guessing from a bare status code.
                    val preview = runCatching { response.peekBody(1024).string().trim().take(200) }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                    DeliveryResult.Failed("HTTP ${response.code}" + (preview?.let { ": $it" } ?: ""))
                }
            }
        } catch (_: IOException) {
            DeliveryResult.Failed("network error or request deadline exceeded")
        } catch (_: SecurityException) {
            DeliveryResult.Failed("network access denied")
        }
    }
}
