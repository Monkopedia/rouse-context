package com.rousecontext.bridge

/**
 * Stateful HTTP/1.1 byte stream processor that inserts a fixed header line
 * into every request's header block as bytes stream through.
 *
 * The bridge receives plaintext HTTP over TLS from the external client and
 * copies it to the local MCP server on loopback. To distinguish legitimate
 * bridge-forwarded traffic from a same-device app dialing loopback directly,
 * we inject a secret `X-Internal-Token` header on each request. The local
 * Ktor server then rejects any request missing the header. See issue #177.
 *
 * The injector sits on the TLS -> socket path and operates entirely on
 * byte buffers: it never parses or mutates request bodies, only headers.
 *
 * HTTP/1.1 persistent connections are handled -- after a request body is
 * fully forwarded (via Content-Length or chunked encoding), the injector
 * loops back to header-scanning for the next request.
 *
 * Any deviation from HTTP/1.1 header framing -- for example, a request with
 * neither `Content-Length` nor `Transfer-Encoding: chunked` on a method that
 * may carry a body -- falls through to passthrough, which means the next
 * request on the same connection will be misframed. This is acceptable
 * because the MCP clients we bridge do comply with HTTP/1.1 framing, and the
 * local Ktor server will close the connection on any malformed request.
 *
 * Not thread-safe: construct one per connection; feed bytes via [feed] in
 * a single coroutine.
 */
internal class HttpHeaderInjector(private val headerLine: String) {

    private enum class State { HEADERS, BODY_FIXED, BODY_CHUNKED, PASSTHROUGH }

    private var state: State = State.HEADERS
    private val headerBuf = StringBuilder()
    private var bodyRemaining: Long = 0
    private var chunkRemaining: Long = -1
    private var chunkSizeBuf = StringBuilder()

    private val injectedBytes: ByteArray = (headerLine + "\r\n").toByteArray(Charsets.ISO_8859_1)

    /**
     * Consume [input] of length [length] and emit the transformed stream via
     * [emit]. A single feed may emit zero or more byte chunks.
     */
    fun feed(input: ByteArray, offset: Int, length: Int, emit: (ByteArray, Int, Int) -> Unit) {
        var i = offset
        val end = offset + length
        while (i < end) {
            when (state) {
                State.HEADERS -> i = consumeHeaderBytes(input, i, end, emit)
                State.BODY_FIXED -> i = consumeFixedBody(input, i, end, emit)
                State.BODY_CHUNKED -> i = consumeChunkedBody(input, i, end, emit)
                State.PASSTHROUGH -> {
                    emit(input, i, end - i)
                    i = end
                }
            }
        }
    }

    private fun consumeHeaderBytes(
        input: ByteArray,
        start: Int,
        end: Int,
        emit: (ByteArray, Int, Int) -> Unit
    ): Int {
        // Scan forward until we either find the \r\n\r\n boundary or run out
        // of bytes in this chunk. Buffer everything seen so far as ASCII.
        var i = start
        while (i < end) {
            headerBuf.append((input[i].toInt() and 0xFF).toChar())
            i++
            val idx = indexOfHeaderEnd(headerBuf)
            if (idx >= 0) {
                // Full header block captured. Emit it with the injected line.
                emitInjectedHeaders(headerBuf.substring(0, idx), emit)
                val rawHeaders = headerBuf.toString()
                transitionAfterHeaders(rawHeaders)
                headerBuf.setLength(0)
                return i
            }
        }
        return i
    }

    private fun emitInjectedHeaders(fullHeaderBlock: String, emit: (ByteArray, Int, Int) -> Unit) {
        // fullHeaderBlock ends just before the final blank line (i.e. at the
        // second \r\n of the last header). We emit:
        //   <original request line + headers>\r\n
        //   <injected header>\r\n
        //   \r\n
        val startBytes = fullHeaderBlock.toByteArray(Charsets.ISO_8859_1)
        emit(startBytes, 0, startBytes.size)
        emit(CRLF, 0, CRLF.size)
        emit(injectedBytes, 0, injectedBytes.size)
        emit(CRLF, 0, CRLF.size)
    }

    private fun transitionAfterHeaders(rawHeaders: String) {
        val contentLength = parseContentLength(rawHeaders)
        val chunked = isChunked(rawHeaders)
        when {
            chunked -> {
                state = State.BODY_CHUNKED
                chunkRemaining = -1
                chunkSizeBuf.setLength(0)
            }
            contentLength != null && contentLength > 0 -> {
                state = State.BODY_FIXED
                bodyRemaining = contentLength
            }
            else -> {
                // Zero-length body or missing framing: the next thing on the
                // wire will be the next request's start line (or connection
                // close). Go straight back to HEADERS.
                state = State.HEADERS
            }
        }
    }

    private fun consumeFixedBody(
        input: ByteArray,
        start: Int,
        end: Int,
        emit: (ByteArray, Int, Int) -> Unit
    ): Int {
        val available = (end - start).toLong()
        val take = minOf(available, bodyRemaining).toInt()
        if (take > 0) {
            emit(input, start, take)
            bodyRemaining -= take
        }
        if (bodyRemaining == 0L) {
            state = State.HEADERS
        }
        return start + take
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun consumeChunkedBody(
        input: ByteArray,
        start: Int,
        end: Int,
        emit: (ByteArray, Int, Int) -> Unit
    ): Int {
        var i = start
        while (i < end) {
            if (chunkRemaining < 0) {
                // Read chunk size line.
                val b = input[i]
                emit(input, i, 1)
                i++
                if (b == '\n'.code.toByte()) {
                    val line = chunkSizeBuf.toString().trim()
                    chunkSizeBuf.setLength(0)
                    val sizeStr = line.substringBefore(';').trim()
                    val size = runCatching { sizeStr.toLong(HEX_RADIX) }.getOrNull()
                    if (size == null) {
                        // Malformed framing; fall through to passthrough so we
                        // don't silently drop data. The local Ktor server will
                        // reject the request.
                        state = State.PASSTHROUGH
                        return i
                    }
                    if (size == 0L) {
                        // Last chunk. Trailer may follow; simplest is to switch
                        // to HEADERS-scanning, which will pick up the trailing
                        // CRLF and then the next request line.
                        chunkRemaining = TRAILER_MODE
                    } else {
                        chunkRemaining = size
                    }
                } else if (b != '\r'.code.toByte()) {
                    chunkSizeBuf.append((b.toInt() and 0xFF).toChar())
                }
            } else if (chunkRemaining == TRAILER_MODE) {
                // We've seen size=0; consume any trailer headers + the final
                // CRLF until we see \r\n\r\n, then reset to HEADERS. Cheap
                // implementation: reuse the header scanner.
                state = State.HEADERS
                // Push the remaining bytes back through by returning i so the
                // outer loop dispatches to HEADERS.
                return i
            } else {
                val available = (end - i).toLong()
                val take = minOf(available, chunkRemaining).toInt()
                if (take > 0) {
                    emit(input, i, take)
                    chunkRemaining -= take
                    i += take
                }
                if (chunkRemaining == 0L) {
                    // CRLF terminator after chunk data.
                    chunkRemaining = -1
                }
            }
        }
        return i
    }

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private const val HEX_RADIX = 16
        private const val TRAILER_MODE = Long.MIN_VALUE

        /**
         * Return the index of the character just before the `\r\n\r\n`
         * boundary in [buf], or -1 if not yet present.
         */
        private fun indexOfHeaderEnd(buf: StringBuilder): Int {
            if (buf.length < HEADER_TERMINATOR_LEN) return -1
            val last = buf.length - 1
            // Inline substring comparison against "\r\n\r\n" avoids a detekt
            // ComplexCondition flag on the chained `&&` version.
            var ok = true
            for (j in 0 until HEADER_TERMINATOR_LEN) {
                if (buf[last - j] != HEADER_TERMINATOR_REV[j]) {
                    ok = false
                    break
                }
            }
            // Return index excluding the final \r\n so we can splice in the
            // new header BEFORE the blank line.
            return if (ok) last - (HEADER_TERMINATOR_LEN - 1) else -1
        }

        private const val HEADER_TERMINATOR_LEN = 4

        // Reverse order so buf[last - j] matches HEADER_TERMINATOR_REV[j].
        private val HEADER_TERMINATOR_REV = charArrayOf('\n', '\r', '\n', '\r')

        private fun parseContentLength(headers: String): Long? {
            for (line in headers.split("\r\n")) {
                val colon = line.indexOf(':')
                if (colon > 0 &&
                    line.substring(0, colon).trim()
                        .equals("Content-Length", ignoreCase = true)
                ) {
                    return runCatching {
                        line.substring(colon + 1).trim().toLong()
                    }.getOrNull()
                }
            }
            return null
        }

        private fun isChunked(headers: String): Boolean {
            for (line in headers.split("\r\n")) {
                val colon = line.indexOf(':')
                if (colon > 0 &&
                    line.substring(0, colon).trim()
                        .equals("Transfer-Encoding", ignoreCase = true)
                ) {
                    val v = line.substring(colon + 1).trim().lowercase()
                    if (v.contains("chunked")) return true
                }
            }
            return false
        }
    }
}
