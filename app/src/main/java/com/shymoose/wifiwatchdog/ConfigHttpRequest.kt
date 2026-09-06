package com.shymoose.wifiwatchdog

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** One request per connection; limits are byte counts, not decoded character counts. */
internal data class ConfigHttpRequest(val method: String, val target: String, val body: String) {
    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADERS = 64

        fun read(input: InputStream): ConfigHttpRequest {
            var headerBytes = 0
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) {
                    val next = input.read()
                    if (next < 0) throw InvalidConfigRequest(400)
                    if (++headerBytes > MAX_HEADER_BYTES) throw InvalidConfigRequest(431)
                    if (next == '\r'.code) {
                        if (input.read() != '\n'.code) throw InvalidConfigRequest(400)
                        if (++headerBytes > MAX_HEADER_BYTES) throw InvalidConfigRequest(431)
                        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
                    }
                    if (next == '\n'.code || next == 0) throw InvalidConfigRequest(400)
                    if (bytes.size == MAX_LINE_BYTES) throw InvalidConfigRequest(431)
                    bytes.add(next.toByte())
                }
            }

            val parts = line().split(' ')
            if (parts.size != 3 || !isToken(parts[0]) ||
                !parts[1].startsWith("/") || parts[1].any { it.code <= 32 || it.code >= 127 } ||
                parts[2] !in listOf("HTTP/1.0", "HTTP/1.1")
            ) throw InvalidConfigRequest(400)

            var length: Int? = null
            var count = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (++count > MAX_HEADERS) throw InvalidConfigRequest(431)
                val colon = header.indexOf(':')
                if (colon <= 0 || !isToken(header.substring(0, colon)) ||
                    header.any { (it.code < 32 && it != '\t') || it.code == 127 }
                ) throw InvalidConfigRequest(400)
                val value = header.substring(colon + 1).trim(' ', '\t')
                when (header.substring(0, colon).lowercase()) {
                    // No decoding is implemented; never treat a chunk prefix as a form.
                    "transfer-encoding" -> throw InvalidConfigRequest(400)
                    "content-length" -> {
                        if (length != null || value.isEmpty() || value.any { it !in '0'..'9' }) {
                            throw InvalidConfigRequest(400)
                        }
                        val parsed = value.toLongOrNull() ?: throw InvalidConfigRequest(413)
                        if (parsed > MAX_BODY_BYTES) throw InvalidConfigRequest(413)
                        length = parsed.toInt()
                    }
                }
            }
            // A missing length must not become an empty /save that clears checkboxes.
            if (parts[0] == "POST" && length == null) throw InvalidConfigRequest(400)
            val bytes = ByteArray(length ?: 0)
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read <= 0) throw InvalidConfigRequest(400)
                offset += read
            }
            val body = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                throw InvalidConfigRequest(400)
            }
            return ConfigHttpRequest(parts[0], parts[1], body)
        }

        private fun isToken(value: String): Boolean = value.isNotEmpty() &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }
    }
}

internal class InvalidConfigRequest(val status: Int) : IOException("Invalid HTTP request ($status)")
