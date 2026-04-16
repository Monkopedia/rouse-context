package com.rousecontext.bridge

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the byte-level HTTP header injector used by [SessionHandler] to
 * add `X-Internal-Token` before forwarding requests into the local Ktor
 * server. See issue #177.
 */
class HttpHeaderInjectorTest {

    private val header = "X-Internal-Token: secret-abc"

    private fun feedAll(injector: HttpHeaderInjector, chunks: List<ByteArray>): String {
        val out = ByteArrayOutputStream()
        for (c in chunks) {
            injector.feed(c, 0, c.size) { buf, off, len ->
                out.write(buf, off, len)
            }
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun `injects header into simple GET request`() {
        val injector = HttpHeaderInjector(header)
        val request = (
            "GET /authorize HTTP/1.1\r\n" +
                "Host: test.example.com\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val result = feedAll(injector, listOf(request))

        assertTrue(
            result.contains("X-Internal-Token: secret-abc"),
            "Injected line must be present. Got: $result"
        )
        assertTrue(
            result.startsWith("GET /authorize HTTP/1.1\r\n"),
            "Request line must be preserved. Got: $result"
        )
        assertTrue(
            result.contains("Host: test.example.com"),
            "Original Host header must be preserved. Got: $result"
        )
        assertTrue(
            result.endsWith("\r\n\r\n"),
            "Must end with blank line before body. Got: $result"
        )
    }

    @Test
    fun `preserves request body exactly`() {
        val injector = HttpHeaderInjector(header)
        val body = """{"grant_type":"password"}"""
        val request = (
            "POST /token HTTP/1.1\r\n" +
                "Host: test.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.ISO_8859_1)

        val result = feedAll(injector, listOf(request))

        assertTrue(result.contains("X-Internal-Token: secret-abc"))
        assertTrue(
            result.endsWith(body),
            "Body must be preserved intact. Got: $result"
        )
    }

    @Test
    fun `handles chunked reads - bytes split mid-headers`() {
        val injector = HttpHeaderInjector(header)
        val body = """{"a":1}"""
        val raw = "POST /mcp HTTP/1.1\r\n" +
            "Host: test.example.com\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "\r\n" +
            body
        // Feed one byte at a time to stress the state machine.
        val chunks = raw.toByteArray(Charsets.ISO_8859_1).map { byteArrayOf(it) }

        val result = feedAll(injector, chunks)
        assertTrue(result.contains("X-Internal-Token: secret-abc"))
        assertTrue(result.endsWith(body))
    }

    @Test
    fun `handles multiple keep-alive requests on one connection`() {
        val injector = HttpHeaderInjector(header)
        val body1 = """{"id":1}"""
        val body2 = """{"id":2}"""
        val req1 = "POST /mcp HTTP/1.1\r\n" +
            "Host: test.example.com\r\n" +
            "Content-Length: ${body1.length}\r\n" +
            "\r\n" +
            body1
        val req2 = "POST /mcp HTTP/1.1\r\n" +
            "Host: test.example.com\r\n" +
            "Content-Length: ${body2.length}\r\n" +
            "\r\n" +
            body2

        val result = feedAll(
            injector,
            listOf((req1 + req2).toByteArray(Charsets.ISO_8859_1))
        )
        // Both requests must have the injected header.
        val firstIdx = result.indexOf("X-Internal-Token")
        assertTrue(firstIdx >= 0, "Expected first injected header")
        val secondIdx = result.indexOf("X-Internal-Token", firstIdx + 1)
        assertTrue(
            secondIdx > firstIdx,
            "Expected second injected header for keep-alive. Got: $result"
        )
        // Both bodies must be intact.
        assertTrue(result.contains(body1))
        assertTrue(result.contains(body2))
    }

    @Test
    fun `handles GET with no body followed by POST with body`() {
        val injector = HttpHeaderInjector(header)
        val body = """{"x":1}"""
        val req1 = "GET /.well-known/oauth-authorization-server HTTP/1.1\r\n" +
            "Host: test.example.com\r\n" +
            "\r\n"
        val req2 = "POST /token HTTP/1.1\r\n" +
            "Host: test.example.com\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "\r\n" +
            body

        val result = feedAll(
            injector,
            listOf((req1 + req2).toByteArray(Charsets.ISO_8859_1))
        )
        val firstIdx = result.indexOf("X-Internal-Token")
        assertTrue(firstIdx >= 0, "Expected first injected header")
        val secondIdx = result.indexOf("X-Internal-Token", firstIdx + 1)
        assertTrue(
            secondIdx > firstIdx,
            "Expected second injected header. Got: $result"
        )
        assertTrue(result.contains(body))
    }

    @Test
    fun `total bytes emitted equals input plus injected header per request`() {
        val injector = HttpHeaderInjector(header)
        val body = "xyz"
        val request = (
            "POST /token HTTP/1.1\r\n" +
                "Host: test.example.com\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "\r\n" +
                body
            ).toByteArray(Charsets.ISO_8859_1)

        val result = feedAll(injector, listOf(request))
        // The injected header line is "X-Internal-Token: secret-abc\r\n"
        val injectedLen = (header + "\r\n").toByteArray(Charsets.ISO_8859_1).size
        assertEquals(
            request.size + injectedLen,
            result.toByteArray(Charsets.ISO_8859_1).size
        )
    }
}
