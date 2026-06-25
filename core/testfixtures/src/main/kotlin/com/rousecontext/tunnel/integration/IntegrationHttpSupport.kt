package com.rousecontext.tunnel.integration

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.Collections
import java.util.IdentityHashMap

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
 * The de-flake centres on a single GENEROUS socket read timeout
 * ([SOCKET_READ_TIMEOUT_MS]) shared by every test:
 *
 *  - A slow-but-progressing CI run never trips the per-read timer mid-exchange
 *    (the 5-30s timeouts it replaces were the source of the flake).
 *  - A genuinely stuck round trip still FAILS FAST: every blocking read is
 *    bounded, so a silent socket raises `SocketTimeoutException` in ~60s rather
 *    than wedging the job for the full class timeout (cf. the #499 ~35-minute
 *    hang, which a default `SAME_THREAD` class `@Timeout` could not interrupt).
 *
 * Note we deliberately do NOT lean on a class-level
 * `@Timeout(threadMode = SEPARATE_THREAD)` as the hard ceiling here: these
 * tests run long-lived background coroutines (device-session collectors) whose
 * logging spans the whole class, and a per-method separate-thread timeout makes
 * that background output race Gradle's per-test output store and corrupt it
 * ("Could not write XML test results ... EOFException / Kryo buffer underflow").
 * The bounded read timeout gives the same fail-fast guarantee without that
 * Gradle interaction. (`MultiClientConcurrencyTest` keeps a method-level
 * separate-thread ceiling on its single test, where the interaction does not
 * arise -- see #504.)
 *
 * ## Persistent per-connection reader (#518)
 *
 * The tests issue many sequential requests over a single keep-alive socket.
 * A `BufferedReader`/`InputStreamReader` reads from the underlying socket in
 * 8 KiB blocks, so when the relay coalesces two responses into one TCP segment
 * (or simply when the next response arrives before we finish parsing the
 * current one) the reader buffers bytes belonging to the *next* response.
 * Constructing a fresh `BufferedReader` per [readResponse] call therefore
 * silently discarded those buffered-ahead bytes: the next call built a new
 * reader whose underlying stream had nothing left to deliver, so its
 * [BufferedReader.readLine] blocked until [SOCKET_READ_TIMEOUT_MS] elapsed and
 * surfaced as the `OAuthEndToEndTest` `SocketTimeoutException` (#518). The
 * `OAuthEndToEndTest` flow is the most exposed because it makes a dozen
 * sequential requests on one socket.
 *
 * The fix is to keep exactly one `BufferedReader` per underlying
 * [InputStream] for the life of that stream, so buffered-ahead bytes carry
 * over to the next [readResponse] instead of being dropped. Tests are
 * single-threaded per socket; the cache is synchronized only so distinct
 * connections in concurrent tests stay isolated.
 */
object IntegrationHttpSupport {

    /**
     * One reader per underlying [InputStream], keyed by identity. Reusing the
     * reader preserves any bytes it buffered ahead from a previous response
     * (see the class kdoc, #518). Entries are never evicted: a test's sockets
     * are short-lived and the suite creates a bounded number of them.
     */
    private val readers: MutableMap<InputStream, BufferedReader> =
        Collections.synchronizedMap(IdentityHashMap())

    private fun readerFor(input: InputStream): BufferedReader = synchronized(readers) {
        readers.getOrPut(input) { BufferedReader(InputStreamReader(input, Charsets.UTF_8)) }
    }

    /**
     * Drop the cached reader for [input], if any. Optional: tests may call this
     * when they are finished with a socket so the identity cache does not
     * retain a reference for the rest of the suite. Not calling it is safe --
     * the suite creates a bounded number of short-lived sockets.
     */
    fun release(input: InputStream) {
        synchronized(readers) { readers.remove(input) }
    }

    /**
     * Generous per-socket read timeout (ms). Large enough that normal CI
     * slowness in the relay/device round trip never trips it mid-exchange,
     * while still bounding a permanently dead socket: a stuck read raises
     * `SocketTimeoutException` within this window, so the test fails fast
     * instead of hanging the CI job.
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
        // Reuse one reader per stream so bytes buffered ahead from a prior
        // response are not dropped (see class kdoc, #518).
        val reader = readerFor(input)
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
