package com.rousecontext.tunnel.integration

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 * `readLine` against a tight per-socket [Socket.setSoTimeout]
 * (5s / 10s / 15s / 30s). Under CI load the relay <-> device round trip
 * routinely exceeded those read timeouts and surfaced as a
 * `java.net.SocketTimeoutException`, red-ing the "Integration tests (relay)"
 * job on unrelated changes (#498, #499, #501, #504).
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
 * ## Choosing a `@Timeout` thread mode (#600)
 *
 * A bounded read timeout only bounds a *blocked* read. It does nothing for a
 * loop that is busy rather than waiting, and the failure this module has
 * actually produced is a CPU spin -- the #563 `BUFFER_UNDERFLOW` spin in
 * `TlsAcceptor`. A default `SAME_THREAD` `@Timeout` is checked only AFTER the
 * test method returns, so against a spin it yields an unbounded hang, not a
 * red. Only `threadMode = SEPARATE_THREAD` reports the failure and writes XML
 * while the spinning thread is still running.
 *
 * The #501/#504 output-store hazard is real but **per-test, not module-wide**.
 * It requires output reaching Gradle's per-test output store from a code path
 * that is still running after the ceiling has abandoned the test. So the
 * question to ask of any given ceiling is *"can this test still emit output
 * after its ceiling fires?"* -- not *"is `SEPARATE_THREAD` dangerous?"*.
 *
 * Measured on a full green `:core:tunnel:integrationTest` run, per class, from
 * the `system-out`/`system-err` in the JUnit XML: seven of the twelve classes
 * emit **zero bytes** and contain no print statement at all. Those seven take
 * `SEPARATE_THREAD`.
 *
 * A measurement, though, is evidence about the runs that were *observed*, not a
 * property of the code. The seven rest on three different kinds of guarantee,
 * and the difference is the whole reason this note is longer than one line:
 *
 *  1. **Closed by construction.** Framework logging cannot appear behind their
 *     back on any branch, taken or not: no production code on this path logs or
 *     uses kotlin-logging, and `slf4j-api` sits on `jvmTestRuntimeClasspath`
 *     with **no provider**, so Ktor's internal logging is a no-op. This one
 *     needs no run to believe.
 *
 *  2. **Closed by `IntegrationScope`** (in `:core:tunnel`'s `jvmTest`, beside
 *     the classes that use it). A bare `CoroutineScope(Dispatchers.IO)` is a
 *     ROOT scope, so an uncaught throw out of one of `TunnelClientImpl`'s
 *     unguarded `scope.launch` sites has nowhere to propagate and ends at the
 *     default handler -- which writes a stack trace to `System.err` **from a
 *     background thread**, on the failure path, which is exactly when a ceiling
 *     fires. No `println` grep finds it. The seven converted classes build that
 *     scope through `integrationScope(...)`, which routes such throws to a
 *     recorded list. That is what turns "this class emits nothing" from an
 *     observation into a property.
 *
 *  3. **Still coverage-dependent, and deliberately left so.** One path remains.
 *     `TestRelayFixture.stop()` prints ~100 bytes to `System.err` if the relay
 *     subprocess survives `destroyForcibly()` plus a 3 s wait, and
 *     `EndToEndSessionTest` calls `stop()` from inside a test body rather than
 *     only from teardown -- so an abandoned thread can reach it. That needs a
 *     kernel-level stall AND the ceiling to fire in the same window, for ~100
 *     bytes; and the behaviour it replaces is the unbounded hang, which loses
 *     the entire run rather than one file. Recorded rather than guarded,
 *     because a guard here would cost more clarity than the compound-rare case
 *     is worth.
 *
 * The classes that DO emit keep the default `SAME_THREAD`, each for a named
 * emitter: `CertRotationIntegrationTest` (~15 KB) and
 * `RelayApiClientIntegrationTest` (~76 KB) dump the relay subprocess's captured
 * output to stderr in teardown, which is exactly the path an abandoned test
 * thread runs into; `AbruptDisconnectTest` and `HalfOpenDetectionTest` `println`
 * timings from the test body; and `ClaudeFullFlowEndToEndTest` shares
 * `@BeforeAll` collectors across eight `@TestMethodOrder`-ordered tests, so
 * abandoning one method leaks class-scoped state into the rest -- a structural
 * hazard independent of logging.
 *
 * The bounded read timeout below remains the first line of defence for blocked
 * I/O in every class, whichever thread mode it uses.
 *
 * ## Byte-exact HTTP framing (#523)
 *
 * The tests issue many sequential requests over a single keep-alive socket.
 * #518 reused one `BufferedReader` per stream so bytes buffered ahead from a
 * coalesced TCP segment carried over to the next [readResponse] instead of
 * being dropped. That narrowed the flake but did not close it, because a
 * char-oriented `BufferedReader` is the wrong tool for HTTP framing:
 * **`Content-Length` counts BYTES while a `BufferedReader` counts CHARS**.
 * Reading `contentLength` *chars* off a UTF-8 decoder consumes a different
 * number of *bytes* whenever the body is non-ASCII, and the decoder also pulls
 * whole 8 KiB byte blocks off the socket — so the reader's internal char buffer
 * and the true byte stream drift apart. Once they diverge the next
 * [readResponse] desyncs and every subsequent read on that keep-alive socket
 * blocks until [SOCKET_READ_TIMEOUT_MS] elapses (the cascade seen in #523).
 *
 * The fix is byte-exact framing on ONE reused [BufferedInputStream] per
 * underlying [InputStream] (identity-keyed, mirroring #518 but bytes not chars):
 *
 *  - Status line and header lines are read as BYTES up to each CRLF and decoded
 *    as ISO-8859-1, which is byte-exact (1 byte <-> 1 char) for HTTP framing.
 *  - The body is read as EXACTLY `Content-Length` BYTES (loop until N bytes), or
 *    chunked is decoded by exact byte reads of each hex-sized chunk. Only after
 *    the exact bytes are in hand is the body decoded to a String as UTF-8.
 *  - The buffered byte stream is reused per connection, so bytes buffered ahead
 *    from a coalesced TCP segment carry to the next call (preserving #518's
 *    win).
 *
 * Tests are single-threaded per socket; the cache is synchronized only so
 * distinct connections in concurrent tests stay isolated.
 */
object IntegrationHttpSupport {

    /**
     * One buffered byte stream per underlying [InputStream], keyed by identity.
     * Reusing it preserves any bytes it buffered ahead from a previous response
     * (see the class kdoc, #523/#518). Entries are never evicted: a test's
     * sockets are short-lived and the suite creates a bounded number of them.
     */
    private val streams: MutableMap<InputStream, BufferedInputStream> =
        Collections.synchronizedMap(IdentityHashMap())

    private fun streamFor(input: InputStream): BufferedInputStream = synchronized(streams) {
        streams.getOrPut(input) { BufferedInputStream(input) }
    }

    /**
     * Drop the cached buffered stream for [input], if any. Optional: tests may
     * call this when they are finished with a socket so the identity cache does
     * not retain a reference for the rest of the suite. Not calling it is safe
     * -- the suite creates a bounded number of short-lived sockets.
     */
    fun release(input: InputStream) {
        synchronized(streams) { streams.remove(input) }
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
     * [input], framing it BYTE-exactly (see class kdoc, #523).
     */
    @Suppress("LoopWithTooManyJumpStatements")
    fun readResponse(input: InputStream): HttpResponse {
        // Reuse one buffered byte stream per socket so bytes buffered ahead from
        // a prior (coalesced) response are not dropped (see class kdoc).
        val stream = streamFor(input)

        val statusLine = readLine(stream) ?: error("No status line received")
        require(statusLine.startsWith("HTTP/1.1")) {
            "Expected HTTP response, got: $statusLine"
        }
        val statusCode = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        var contentLength = -1L
        var chunked = false
        while (true) {
            val line = readLine(stream) ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(":").trim().toLong()
            }
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true
            }
        }

        val bodyBytes = when {
            contentLength > 0 -> readFixedBody(stream, contentLength)
            chunked -> readChunkedBody(stream)
            else -> ByteArray(0)
        }
        return HttpResponse(statusCode, headers, String(bodyBytes, Charsets.UTF_8))
    }

    /**
     * Read one CRLF-terminated line as BYTES and decode it as ISO-8859-1 (a
     * byte-exact 1:1 mapping, correct for HTTP framing). Returns `null` at EOF
     * before any byte. A bare LF is tolerated; a trailing CR is stripped.
     */
    private fun readLine(stream: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = stream.read()
            if (b == -1) {
                return if (sawAny) decodeLine(out) else null
            }
            sawAny = true
            if (b == '\n'.code) {
                return decodeLine(out)
            }
            out.write(b)
        }
    }

    private fun decodeLine(out: ByteArrayOutputStream): String {
        val bytes = out.toByteArray()
        // Strip a single trailing CR (CRLF line ending).
        val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
            bytes.size - 1
        } else {
            bytes.size
        }
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }

    /** Read exactly [length] bytes (blocking until they arrive or EOF). */
    private fun readFixedBody(stream: BufferedInputStream, length: Long): ByteArray {
        val buf = ByteArray(length.toInt())
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n == -1) break
            read += n
        }
        return if (read == buf.size) buf else buf.copyOf(read)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun readChunkedBody(stream: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(stream) ?: break
            // A chunk-size line may carry chunk extensions (";ext=..."); the
            // size is the leading hex token.
            val chunkSize = sizeLine.substringBefore(';').trim().toInt(16)
            if (chunkSize == 0) {
                readLine(stream) // trailing CRLF after the final chunk
                break
            }
            out.write(readFixedBody(stream, chunkSize.toLong()))
            readLine(stream) // CRLF terminating this chunk's data
        }
        return out.toByteArray()
    }
}
