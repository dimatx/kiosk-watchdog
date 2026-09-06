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
                request.body?.let { post(it.toRequestBody("text/plain; charset=utf-8".toMediaType())) }
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
                else DeliveryResult.Failed("HTTP ${response.code}")
            }
        } catch (_: IOException) {
            DeliveryResult.Failed("network error or request deadline exceeded")
        } catch (_: SecurityException) {
            DeliveryResult.Failed("network access denied")
        }
    }
}
