package com.rousecontext.tunnel.integration

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Self-tests for the byte-exact HTTP framing in [IntegrationHttpSupport] (#523).
 *
 * No relay, no device, no real socket: a [ByteArrayInputStream] feeds canned
 * response bytes, so these run in the ordinary `test` task. The point is to
 * pin the property the de-flake hinges on -- the read path frames responses by
 * BYTES, so it can never char/byte-desync the way the old `BufferedReader`
 * (chars) path could when a `Content-Length`-byte body contained non-ASCII.
 */
class IntegrationHttpSupportTest {

    private val opened = mutableListOf<InputStream>()

    private fun feed(bytes: ByteArray): InputStream =
        ByteArrayInputStream(bytes).also { opened.add(it) }

    @AfterEach
    fun cleanup() {
        opened.forEach { IntegrationHttpSupport.release(it) }
        opened.clear()
    }

    /**
     * A body with multi-byte UTF-8 characters whose BYTE length exceeds its CHAR
     * length. The old char-counting path would read `Content-Length` *chars*,
     * over-reading past the body and into whatever followed -- here, the next
     * response -- desyncing the socket. Byte-exact framing reads exactly the
     * advertised bytes, so the body round-trips and the FOLLOWING response on
     * the same stream still parses.
     */
    @Test
    fun `non-ASCII body does not over-read into the next response`() {
        val body1 = "héllo-é€-😀" // accents, euro sign, emoji
        val body1Bytes = body1.toByteArray(Charsets.UTF_8)
        // BYTE length must exceed CHAR length, else there is nothing to prove.
        assert(body1Bytes.size > body1.length)

        val body2 = """{"ok":true}"""
        val raw = response(body1Bytes) + response(body2.toByteArray(Charsets.UTF_8))
        val stream = feed(raw)

        val first = IntegrationHttpSupport.readResponse(stream)
        assertEquals(200, first.statusCode)
        assertEquals(body1, first.body)

        // The decisive assertion: the second response on the SAME stream is not
        // desynced by the first's multi-byte body.
        val second = IntegrationHttpSupport.readResponse(stream)
        assertEquals(200, second.statusCode)
        assertEquals(body2, second.body)
    }

    /**
     * Two complete responses delivered in one coalesced TCP segment (one read).
     * Reusing the buffered byte stream per #523/#518 must carry the bytes
     * buffered ahead into the second [IntegrationHttpSupport.readResponse].
     */
    @Test
    fun `coalesced responses both parse from one buffered stream`() {
        val a = "first"
        val b = "second"
        val raw = response(a.toByteArray()) + response(b.toByteArray())
        val stream = feed(raw)

        assertEquals(a, IntegrationHttpSupport.readResponse(stream).body)
        assertEquals(b, IntegrationHttpSupport.readResponse(stream).body)
    }

    /** Chunked transfer-encoding, including a non-ASCII chunk, decodes exactly. */
    @Test
    fun `chunked body decodes byte-exactly`() {
        val part1 = "abé" // 'é' is 2 bytes in UTF-8
        val part2 = "€cd" // '€' is 3 bytes
        val part1Bytes = part1.toByteArray(Charsets.UTF_8)
        val part2Bytes = part2.toByteArray(Charsets.UTF_8)

        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Transfer-Encoding: chunked\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)

        val out = java.io.ByteArrayOutputStream()
        out.write(head)
        out.write("${Integer.toHexString(part1Bytes.size)}\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(part1Bytes)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write("${Integer.toHexString(part2Bytes.size)}\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(part2Bytes)
        out.write("\r\n0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))

        // Follow with a normal response to prove the chunked terminator left the
        // stream aligned.
        out.write(response("after".toByteArray()))

        val stream = feed(out.toByteArray())
        assertEquals(part1 + part2, IntegrationHttpSupport.readResponse(stream).body)
        assertEquals("after", IntegrationHttpSupport.readResponse(stream).body)
    }

    @Test
    fun `headers are parsed case-insensitively and trimmed`() {
        val raw = response(
            "x".toByteArray(),
            extraHeaders = listOf("X-Custom:  spaced-value  ", "Content-Type: text/plain")
        )
        val resp = IntegrationHttpSupport.readResponse(feed(raw))
        assertEquals("spaced-value", resp.headers["x-custom"])
        assertEquals("text/plain", resp.headers["content-type"])
    }

    private fun response(
        bodyBytes: ByteArray,
        extraHeaders: List<String> = emptyList()
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Length: ${bodyBytes.size}\r\n")
        extraHeaders.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        // Header text is ASCII; the body is appended as raw bytes after it.
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + bodyBytes
    }
}
