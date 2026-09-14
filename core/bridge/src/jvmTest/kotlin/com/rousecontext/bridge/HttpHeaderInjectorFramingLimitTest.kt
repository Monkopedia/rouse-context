package com.rousecontext.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Regression test for the F-Droid audit finding (reported by @andrewpozdnakov7
 * on fdroiddata!42096): the pre-Ktor header scanner buffered the entire header
 * block with no cap on total bytes, line length, or line count, and emitted
 * nothing downstream until it saw `\r\n\r\n`. A peer that never terminates the
 * header block therefore grew `headerBuf` without bound, and the local Ktor
 * server never saw a byte it could have rejected.
 *
 * Measured on the unfixed tree: 16 793 629 bytes buffered, 0 forwarded.
 *
 * Every assertion here is positive -- a specific exception type, a specific
 * byte count -- rather than "something went wrong". The first draft of this
 * test wrapped the feed in `runCatching` and passed whenever *anything* was
 * thrown, which meant the `OutOfMemoryError` this finding is about would have
 * made it green.
 */
class HttpHeaderInjectorFramingLimitTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private fun retainedHeaderChars(injector: HttpHeaderInjector): Int =
        readStringBuilder(injector, "headerBuf").length

    private fun retainedChunkSizeChars(injector: HttpHeaderInjector): Int =
        readStringBuilder(injector, "chunkSizeBuf").length

    private fun readStringBuilder(injector: HttpHeaderInjector, name: String): StringBuilder {
        val f = HttpHeaderInjector::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(injector) as StringBuilder
    }

    @Test
    fun `header block with no terminator is capped and the session is aborted`() {
        val injector = HttpHeaderInjector(TOKEN_HEADER)
        var emitted = 0L
        var fed = 0L

        // One never-ending header block: a request line, then well-formed
        // header lines forever, with no blank line to close it. Every line is
        // individually under the per-line limit, so only the block-level cap
        // can stop this.
        val prologue = "POST /mcp HTTP/1.1\r\nHost: x\r\n".toByteArray(Charsets.ISO_8859_1)
        val pad = ("X-Pad: " + "a".repeat(1016) + "\r\n").toByteArray(Charsets.ISO_8859_1)
        assertTrue(pad.size < HttpHeaderInjector.MAX_HEADER_LINE_BYTES, "pad line must be legal")

        assertFailsWith<HttpFramingLimitExceededException> {
            injector.feed(prologue, 0, prologue.size) { _, _, len -> emitted += len }
            fed += prologue.size
            // 16 MiB of header bytes, still no terminator. The cap must fire
            // long before this loop finishes.
            repeat(16 * 1024) {
                injector.feed(pad, 0, pad.size) { _, _, len -> emitted += len }
                fed += pad.size
            }
        }

        println(
            "[measured] header block: fed=$fed bytes emitted=$emitted bytes " +
                "retained=${retainedHeaderChars(injector)} chars"
        )
        assertEquals(0L, emitted, "no byte of an unterminated header block may reach Ktor")
        assertTrue(
            fed <= HttpHeaderInjector.MAX_HEADER_BLOCK_BYTES + pad.size,
            "cap must fire within one feed of the limit, but $fed bytes were accepted"
        )
        assertEquals(0, retainedHeaderChars(injector), "buffer must be released on abort")
    }

    @Test
    fun `single header line longer than Ktor would accept is capped`() {
        val injector = HttpHeaderInjector(TOKEN_HEADER)
        var emitted = 0L
        val prologue = "POST /mcp HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1)
        // One header line that never ends. Under the block cap alone this would
        // run to 64 KiB; the per-line cap is what stops it at 8 KiB.
        val run = ("X-Pad: " + "a".repeat(4089)).toByteArray(Charsets.ISO_8859_1)

        assertFailsWith<HttpFramingLimitExceededException> {
            injector.feed(prologue, 0, prologue.size) { _, _, len -> emitted += len }
            repeat(16) { injector.feed(run, 0, run.size) { _, _, len -> emitted += len } }
        }

        assertEquals(0L, emitted, "nothing forwarded")
    }

    @Test
    fun `chunk size line with no terminator is capped`() {
        val injector = HttpHeaderInjector(TOKEN_HEADER)
        var emitted = 0L
        val headers = (
            "POST /mcp HTTP/1.1\r\n" +
                "Host: x\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        injector.feed(headers, 0, headers.size) { _, _, len -> emitted += len }
        val afterHeaders = emitted

        // A chunk-size line of hex digits that never ends with \n.
        val digits = "a".repeat(4096).toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<HttpFramingLimitExceededException> {
            repeat(4 * 1024) {
                injector.feed(digits, 0, digits.size) { _, _, len -> emitted += len }
            }
        }

        val forwardedDigits = emitted - afterHeaders
        println(
            "[measured] chunk-size line: forwarded=$forwardedDigits bytes " +
                "retained=${retainedChunkSizeChars(injector)} chars"
        )
        assertEquals(0, retainedChunkSizeChars(injector), "buffer must be released on abort")
        // Chunk-size bytes are emitted as they are scanned, so a few do get
        // through; the point is that the count is bounded by the cap rather
        // than by what the peer felt like sending.
        assertTrue(
            forwardedDigits <= HttpHeaderInjector.MAX_CHUNK_SIZE_LINE_BYTES + 1L,
            "forwarded $forwardedDigits chunk-size bytes, cap is " +
                "${HttpHeaderInjector.MAX_CHUNK_SIZE_LINE_BYTES}"
        )
    }

    /**
     * The abort must be terminal. Falling through to `PASSTHROUGH` -- the
     * injector's existing escape hatch for malformed framing -- would forward
     * the peer's bytes *without* the `X-Internal-Token` header, which fails
     * closed at the Ktor guard only after the bytes have been consumed and
     * copied.
     */
    @Test
    fun `an aborted injector never forwards another byte`() {
        val injector = HttpHeaderInjector(TOKEN_HEADER)
        val pad = ("X-Pad: " + "a".repeat(1016) + "\r\n").toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<HttpFramingLimitExceededException> {
            repeat(16 * 1024) { injector.feed(pad, 0, pad.size) { _, _, _ -> } }
        }

        // A perfectly well-formed request on the same connection, after the abort.
        val good = "GET /mcp HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<HttpFramingLimitExceededException> {
            injector.feed(good, 0, good.size) { _, _, len ->
                fail("forwarded $len bytes after the framing abort")
            }
        }
    }

    private companion object {
        const val TOKEN_HEADER = "X-Internal-Token: secret-abc"
    }
}
