package com.rousecontext.tunnel.integration

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * Shared, robust HTTP-over-socket helper for the relay integration suite.
 *
 * Historically every end-to-end test (`ClaudeFullFlowEndToEndTest`,
 * `OAuthEndToEndTest`, `TunnelMcpIntegrationTest`,
 * `MultiClientConcurrencyTest`, ...) carried its own copy of this
 * request-write / response-read logic, each pairing a blocking
 * [BufferedReader.readLine] against a tight per-socket
 * [Socket.setSoTimeout] (5s / 10s / 15s / 30s). Under CI load the
 * relay <-> device round trip routinely exceeded those read timeouts and
 * surfaced as a `java.net.SocketTimeoutException`, red-ing the
 * "Integration tests (relay)" job on unrelated changes (#498, #499, #501,
 * #504).
 *
 * The de-flake has two halves, applied consistently across the suite:
 *
 *  1. A GENEROUS socket read timeout ([SOCKET_READ_TIMEOUT_MS]) so a
 *     slow-but-progressing CI run never trips the per-read timer mid-exchange.
 *  2. A class-level `@Timeout(..., threadMode = SEPARATE_THREAD)` on each
 *     integration test as the real hard ceiling: a genuinely stuck read is
 *     abandoned and the test FAILS FAST instead of hanging the job for the
 *     full (default `SAME_THREAD`) class timeout, which can never interrupt a
 *     thread blocked in `Socket.read()` (the #499 ~35-minute hang).
 *
 * The read timeout is intentionally NOT the failure mechanism for hangs -- it
 * only guards against a permanently silent socket. The separate-thread test
 * timeout is what bounds wall-clock time, so a slow-but-progressing run
 * completes while a truly stuck one fails fast.
 */
object IntegrationHttpSupport {

    /**
     * Generous per-socket read timeout (ms). Large enough that normal CI
     * slowness in the relay/device round trip never trips it mid-exchange,
     * while still preventing a permanently dead socket from blocking forever.
     * The class-level `SEPARATE_THREAD` test timeout is the real fail-fast
     * ceiling.
     */
    const val SOCKET_READ_TIMEOUT_MS = 60_000

    /** Parsed HTTP response: status code, lower-cased header map, and body. */
    data class HttpResponse(val statusCode: Int, val headers: Map<String, String>, val body: String)

    /** Apply the shared generous read timeout to [socket]. */
    fun applyReadTimeout(socket: Socket) {
        socket.soTimeout = SOCKET_READ_TIMEOUT_MS
    }

    /**
     * Write [requestHead] (a complete HTTP request-line + headers block,
     * terminated by a blank line) followed by optional [body] bytes, flush,
     * then read and parse the full response.
     */
    fun exchange(
        input: InputStream,
        output: OutputStream,
        requestHead: String,
        body: ByteArray? = null
    ): HttpResponse {
        output.write(requestHead.toByteArray(Charsets.UTF_8))
        if (body != null) {
            output.write(body)
        }
        output.flush()
        return readResponse(input)
    }

    /**
     * Read and parse an HTTP/1.1 response (status line, headers, and either a
     * `Content-Length`-delimited or `Transfer-Encoding: chunked` body) from
     * [input].
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    fun readResponse(input: InputStream): HttpResponse {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: error("No status line received")
        require(statusLine.startsWith("HTTP/1.1")) {
            "Expected HTTP response, got: $statusLine"
        }
        val statusCode = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(":").trim().toInt()
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true
            }
        }

        val body = when {
            contentLength > 0 -> readFixedBody(reader, contentLength)
            chunked -> readChunkedBody(reader)
            else -> ""
        }
        return HttpResponse(statusCode, headers, body)
    }

    private fun readFixedBody(reader: BufferedReader, length: Int): String {
        val buf = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(buf, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(buf, 0, read)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun readChunkedBody(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val chunkSize = sizeLine.trim().toInt(16)
            if (chunkSize == 0) {
                reader.readLine() // trailing CRLF
                break
            }
            val buf = CharArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val n = reader.read(buf, read, chunkSize - read)
                if (n == -1) break
                read += n
            }
            sb.append(buf, 0, read)
            reader.readLine() // chunk trailing CRLF
        }
        return sb.toString()
    }
}
