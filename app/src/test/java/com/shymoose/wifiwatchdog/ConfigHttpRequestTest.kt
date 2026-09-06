package com.shymoose.wifiwatchdog

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigHttpRequestTest {
    private fun read(raw: String) = ConfigHttpRequest.read(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    private fun rejects(status: Int, raw: String) {
        assertEquals(status, assertThrows(InvalidConfigRequest::class.java) { read(raw) }.status)
    }

    @Test fun `body limit is inclusive and checked before allocating or reading body`() {
        val body = "x".repeat(ConfigHttpRequest.MAX_BODY_BYTES)
        assertEquals(body, read("POST /import HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body").body)
        for (length in listOf("65537", "2147483647", "9223372036854775808")) {
            rejects(413, "POST /save HTTP/1.1\r\nContent-Length: $length\r\n\r\n")
        }
    }

    @Test fun `Unicode content length counts UTF8 bytes`() {
        val body = """{"ntfy_topic":"café-日本-😀"}"""
        val bytes = body.toByteArray(Charsets.UTF_8).size
        assertEquals(body, read("POST /import HTTP/1.1\r\nContent-Length: $bytes\r\n\r\n$body").body)
        rejects(400, "POST /import HTTP/1.1\r\nContent-Length: ${bytes + 1}\r\n\r\n$body")
    }

    @Test fun `malformed body encoding is not silently replaced`() {
        val header = "POST /save HTTP/1.1\r\nContent-Length: 1\r\n\r\n".toByteArray()
        assertEquals(400, assertThrows(InvalidConfigRequest::class.java) {
            ConfigHttpRequest.read(ByteArrayInputStream(header + byteArrayOf(0xc3.toByte())))
        }.status)
    }

    @Test fun `invalid ambiguous or unsupported framing is rejected`() {
        for (headers in listOf(
            "", "Content-Length: -1\r\n", "Content-Length: +1\r\n", "Content-Length: nope\r\n",
            "Content-Length: \r\n", "Content-Length: 1, 1\r\n",
            "Content-Length: 0\r\ncontent-length: 0\r\n",
            "Content-Length: 1\r\nContent-Length: 2\r\n",
            "Transfer-Encoding: chunked\r\n",
            "Content-Length: 0\r\nTransfer-Encoding: identity\r\n",
            "Content-Length : 0\r\n", " Content-Length: 0\r\n", "Invalid\r\n",
            "X-Test: a\u0001b\r\n"
        )) rejects(400, "POST /save HTTP/1.1\r\n$headers\r\n")
    }

    @Test fun `truncated request headers and bodies never dispatch`() {
        for (raw in listOf(
            "", "POST /save HTTP/1.1", "POST /save HTTP/1.1\r", "POST /save HTTP/1.1\n\n",
            "POST /save HTTP/1.1\r\nContent-Length: 0\r\n",
            "POST /save HTTP/1.1\r\nContent-Length: 2\r\n\r\nx",
            "GET / HTTP/1.1\r\nX: trailing",
            "GET /\r\n\r\n", "GET / HTTP/2.0\r\n\r\n", "GET  / HTTP/1.1\r\n\r\n"
        )) rejects(400, raw)
    }

    @Test fun `line length and header count are bounded`() {
        val allowedLine = "X:" + "a".repeat(ConfigHttpRequest.MAX_LINE_BYTES - 2)
        assertEquals("/", read("GET / HTTP/1.1\r\n$allowedLine\r\n\r\n").target)
        rejects(431, "GET / HTTP/1.1\r\n${allowedLine}a\r\n\r\n")
        rejects(431, "GET /${"a".repeat(ConfigHttpRequest.MAX_LINE_BYTES)} HTTP/1.1\r\n\r\n")
        val headers = "X:a\r\n".repeat(ConfigHttpRequest.MAX_HEADERS)
        assertEquals("/", read("GET / HTTP/1.1\r\n$headers\r\n").target)
        rejects(431, "GET / HTTP/1.1\r\n${headers}X:a\r\n\r\n")
    }

    @Test fun `aggregate header limit includes request line and CRLF`() {
        val start = "GET / HTTP/1.1\r\n"
        val first = "X:" + "a".repeat(ConfigHttpRequest.MAX_LINE_BYTES - 2) + "\r\n"
        val remaining = ConfigHttpRequest.MAX_HEADER_BYTES - start.length - first.length - 2
        val second = "Y:" + "b".repeat(remaining - 4) + "\r\n"
        assertEquals("/", read("$start$first$second\r\n").target)
        rejects(431, "$start$first${second.dropLast(2)}b\r\n\r\n")
    }

    @Test fun `GET and POST preserve route target and body`() {
        assertEquals(ConfigHttpRequest("GET", "/?m=saved", ""), read("GET /?m=saved HTTP/1.1\r\nHost: localhost\r\n\r\n"))
        assertEquals(ConfigHttpRequest("POST", "/field", "k=a"), read("POST /field HTTP/1.0\r\ncontent-length:\t3 \r\n\r\nk=a"))
    }
}
