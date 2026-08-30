package com.rousecontext.tunnel

import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Performs TLS server-side accept over a [MuxStream].
 *
 * The device acts as the TLS server (it holds the certificate for its subdomain).
 * The MCP client connecting through the relay is the TLS client.
 * After the handshake completes, plaintext bytes flow through the returned [TlsSession]
 * via suspend-native read/write calls -- no Java [java.io.InputStream]/[java.io.OutputStream]
 * is exposed, so no `runBlocking` bridge is required.
 */
class TlsAcceptor(private val sslContext: SSLContext) {
    /**
     * Result of a successful TLS accept: suspend-native plaintext I/O.
     *
     * Implementations are thread-safe for independent concurrent read and write,
     * but concurrent reads (or concurrent writes) are serialized internally.
     */
    interface TlsSession {
        /**
         * Reads plaintext bytes into [buf] starting at [off] for up to [len] bytes.
         *
         * @return number of bytes read, or -1 on EOF
         * @throws TunnelError.UnhandledTlsState if the engine reports a status this
         *   layer does not know how to handle. That is a defect, not end-of-stream,
         *   and reporting it as EOF is how such defects stayed invisible (#565).
         *   It carries its own type precisely so callers can let it through while
         *   still swallowing the `IOException`s an ordinary peer disconnect
         *   produces (#616).
         */
        suspend fun read(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int

        /**
         * Writes [len] plaintext bytes from [buf] starting at [off], encrypting them
         * to the underlying mux stream.
         *
         * @throws TunnelError.UnhandledTlsState if `wrap` reports a status this
         *   layer does not know how to handle. Symmetric with [read]: an ordinary
         *   peer disconnect leaves as a plain [java.io.IOException] that the copy
         *   loops swallow, so a defect needs its own type or it ends the session
         *   as a clean EOF and is never heard (#630).
         * @throws java.io.IOException if the peer went away -- routine, and
         *   deliberately quiet.
         */
        suspend fun write(buf: ByteArray, off: Int = 0, len: Int = buf.size - off)

        /**
         * Closes the TLS session and the underlying mux stream.
         */
        suspend fun close()
    }

    /**
     * Perform TLS server-side handshake over the given [MuxStream].
     * Returns a suspend-native [TlsSession] on success.
     *
     * @throws TunnelError.TlsHandshakeFailed if the handshake fails, including
     *   when the peer closes mid-handshake. It never returns a session over an
     *   engine that did not finish handshaking. See #618.
     * @throws TunnelError.UnhandledTlsState if the engine reports a status the
     *   handshake pump has no handling for -- a defect in this layer rather
     *   than anything the peer did.
     */
    suspend fun accept(stream: MuxStream): TlsSession = withContext(Dispatchers.IO) {
        try {
            val engine = createServerEngine()
            SuspendTlsSession(engine, stream, pumpHandshake(engine, stream))
        } catch (e: TunnelError) {
            throw e
        } catch (e: Exception) {
            throw TunnelError.TlsHandshakeFailed("TLS handshake failed", e)
        }
    }

    /**
     * Drive the server-side handshake to completion, returning the leftover
     * encrypted bytes (in "compact" mode) for the resulting session to consume.
     */
    private suspend fun pumpHandshake(engine: SSLEngine, stream: MuxStream): java.nio.ByteBuffer {
        val session = engine.session
        var appIn = java.nio.ByteBuffer.allocate(session.applicationBufferSize)
        val appOut = java.nio.ByteBuffer.allocate(0) // empty: no app data during handshake
        var netIn = java.nio.ByteBuffer.allocate(session.packetBufferSize)
        val netOut = java.nio.ByteBuffer.allocate(session.packetBufferSize)

        // Set when the previous unwrap reported BUFFER_UNDERFLOW: netIn holds a
        // PARTIAL TLS record, so the next iteration must pull another mux DATA
        // frame even though netIn is non-empty. See the NEED_UNWRAP note.
        var needMoreNetData = false

        engine.beginHandshake()
        var hsStatus = engine.handshakeStatus
        while (hsStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            hsStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            // The bug family this loop keeps hitting (#558, #563, #565) is a
            // no-progress spin with no suspension point, which is uncancellable
            // and therefore invisible to timeouts. Check explicitly.
            currentCoroutineContext().ensureActive()
            when (hsStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(appOut, netOut)
                    hsStatus = result.handshakeStatus
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        stream.write(drain(netOut))
                    }
                    // Classified AFTER the write, deliberately: a record the
                    // engine did produce (the responding close_notify is the
                    // case that matters) still reaches the peer, and a
                    // cancellation raised by the transport write wins over the
                    // classification rather than being reclassified as a defect.
                    classifyHandshakeWrapStatus(result.status)
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    // Read from the stream when netIn has nothing left to unwrap,
                    // OR when the last unwrap underflowed. Multiple TLS records
                    // may arrive in ONE mux DATA frame (so we must not read while
                    // netIn still holds whole records), and ONE TLS record may be
                    // SPLIT across two frames (so we must read when netIn holds
                    // only a partial record -- otherwise unwrap underflows forever
                    // on the same bytes, spinning at 100% CPU). See #558.
                    //
                    // NEED_UNWRAP_AGAIN is the explicit "I already have buffered
                    // data to process, do NOT hand me more network bytes" signal,
                    // so it never reads. It used to fall into `else -> break`,
                    // abandoning a handshake that was still progressing while the
                    // caller saw an ordinary return. See #565.
                    val needsNetData = hsStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP &&
                        (netIn.position() == 0 || needMoreNetData)
                    if (needsNetData) {
                        netIn = appendTo(netIn, stream.read())
                    }
                    netIn.flip()
                    val result = engine.unwrap(netIn, appIn)
                    netIn.compact()
                    hsStatus = result.handshakeStatus
                    // Two `if`s on `result.status` used to live here, which left
                    // {OK, CLOSED} unhandled -- and CLOSED silently produced a
                    // session over a closed engine. Both `when`s below are
                    // expressions with no `else`, so the residual is empty by
                    // construction. See #618.
                    val action = classifyHandshakeUnwrapStatus(result.status)
                    appIn = when (action) {
                        // appIn cannot hold the record's plaintext. Nothing was
                        // consumed, so without growing the buffer the next
                        // iteration re-unwraps the same bytes forever.
                        // SuspendTlsSession.read has always handled this; the two
                        // paths must agree about whether it can happen. See #565.
                        HandshakeUnwrapAction.GROW_APP_BUFFER -> growBuffer(appIn)
                        HandshakeUnwrapAction.PROCEED,
                        HandshakeUnwrapAction.PULL_MORE_NET_DATA -> appIn
                    }
                    needMoreNetData = action == HandshakeUnwrapAction.PULL_MORE_NET_DATA
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks(engine)
                    hsStatus = engine.handshakeStatus
                }
                // An unknown handshake status is a bug, not a completed handshake.
                // Breaking here returned a half-open session indistinguishable
                // from a good one -- exactly how #558 survived four filings.
                else -> throw TunnelError.TlsHandshakeFailed(
                    "Unhandled TLS handshake status: $hsStatus"
                )
            }
        }
        return netIn
    }

    /**
     * Decide what an `unwrap` status means *during the handshake*.
     *
     * `SSLEngineResult.Status` is a closed four-member enum (`OK`, `CLOSED`,
     * `BUFFER_OVERFLOW`, `BUFFER_UNDERFLOW`, verified with `javap` against the
     * JDK this module builds on), so this is decidable rather than arguable:
     *
     *  - `OK` -- a record was consumed; carry on.
     *  - `BUFFER_UNDERFLOW` -- `netIn` holds only part of a TLS record. That is
     *    the everyday case of one record split across two mux DATA frames, so it
     *    is ordinary: pull another frame. Re-unwrapping the same bytes instead
     *    is #558's 100%-CPU spin.
     *  - `BUFFER_OVERFLOW` -- `appIn` cannot hold the record's plaintext. Its
     *    size comes from `applicationBufferSize`, a hint rather than a bound, so
     *    growing and retrying is real recovery. `SuspendTlsSession.read` has
     *    always done this and the two paths must agree (#565).
     *  - `CLOSED` -- the peer sent `close_notify` mid-handshake. Ordinary peer
     *    behaviour, NOT a defect in this layer, so it must not wear
     *    [TunnelError.UnhandledTlsState]: that type exists so `SessionHandler`
     *    can stay quiet about disconnects while still surfacing our own defects
     *    (#616, #630), and filing routine peer behaviour under it trains whoever
     *    reads those reports to ignore the type. It must still fail the
     *    handshake. Falling through returned a session over an already-closed
     *    engine whose first `read` degrades to a clean EOF -- a half-open
     *    session indistinguishable from a good one, which is how #558 survived
     *    four filings. [TunnelError.TlsHandshakeFailed] is the type `accept`
     *    already documents and the one #615 chose for this loop's residual
     *    `else`. See #618.
     *
     * There is deliberately no `else`: the `when` is used as an expression, so
     * it must stay exhaustive. Should a future JDK add a fifth member, this
     * stops compiling instead of silently doing nothing with it.
     */
    private fun classifyHandshakeUnwrapStatus(
        status: SSLEngineResult.Status
    ): HandshakeUnwrapAction = when (status) {
        SSLEngineResult.Status.OK -> HandshakeUnwrapAction.PROCEED
        SSLEngineResult.Status.BUFFER_UNDERFLOW -> HandshakeUnwrapAction.PULL_MORE_NET_DATA
        SSLEngineResult.Status.BUFFER_OVERFLOW -> HandshakeUnwrapAction.GROW_APP_BUFFER
        SSLEngineResult.Status.CLOSED -> throw TunnelError.TlsHandshakeFailed(
            "TLS handshake failed: peer closed during the handshake (unwrap status CLOSED)"
        )
    }

    /**
     * Decide what a `wrap` status means *during the handshake*.
     *
     * This branch used to read only `result.handshakeStatus` and never look at
     * `result.status` at all -- residual 0 of 4, the widest in the file, and
     * invisible to an audit that only looks at `when`s and `if`s because a
     * never-read field is not a branch. See #618.
     *
     * The buffer statuses are classified the opposite way from the unwrap path
     * above, and the asymmetry is argued rather than inherited:
     *
     *  - `BUFFER_OVERFLOW` -- `netOut` is private to this pump, allocated at
     *    `session.packetBufferSize` and `clear()`ed before every `wrap`, so the
     *    destination always offers the engine its own advertised maximum for one
     *    record. Overflowing it means that bound was broken, not that we
     *    under-allocated; growing would only hide it. Left unclassified it also
     *    leaves `hsStatus` at `NEED_WRAP` with nothing produced, so the pump
     *    re-wraps at 100% CPU until the caller times out.
     *  - `BUFFER_UNDERFLOW` -- an `unwrap` concept ("the source holds less than
     *    a whole record"). A handshake `wrap` emits records from engine state and
     *    takes no application input at all: `appOut` here is deliberately
     *    zero-capacity. Reporting underflow from it is a contract violation.
     *
     * Both are therefore defects in this layer and get
     * [TunnelError.UnhandledTlsState], which `SessionHandler` catches and
     * rethrows and which reaches `Log.e` + `crashReporter.logCaughtException` at
     * the service boundary (#630). `CLOSED` is the engine shutting down
     * mid-handshake -- the same "not our defect, but still not a completed
     * handshake" case as on the unwrap side, and classified identically.
     *
     * No `else`, for the same reason as above: the `when` is an expression.
     */
    private fun classifyHandshakeWrapStatus(status: SSLEngineResult.Status) {
        val defect = when (status) {
            SSLEngineResult.Status.OK -> return
            SSLEngineResult.Status.CLOSED -> throw TunnelError.TlsHandshakeFailed(
                "TLS handshake failed: engine closed during the handshake (wrap status CLOSED)"
            )
            SSLEngineResult.Status.BUFFER_OVERFLOW,
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> status
        }
        throw TunnelError.UnhandledTlsState("Unhandled TLS handshake wrap status: $defect")
    }

    /**
     * Build the server-side [SSLEngine] with ALPN advertising HTTP/1.1.
     * Without ALPN, some HTTP clients complete TLS but never send HTTP
     * data because no application protocol was agreed.
     */
    private fun createServerEngine(): SSLEngine {
        val engine = sslContext.createSSLEngine()
        engine.useClientMode = false
        val sslParams = engine.sslParameters
        sslParams.applicationProtocols = arrayOf("http/1.1")
        engine.sslParameters = sslParams
        return engine
    }

    companion object {
        /**
         * Create a [TlsAcceptor] from a JVM [SSLContext].
         */
        fun create(sslContext: SSLContext): TlsAcceptor = TlsAcceptor(sslContext)

        /**
         * Create a [TlsAcceptor] from certificate and private key DER bytes.
         */
        fun fromCertAndKey(
            certDer: ByteArray,
            keyDer: ByteArray,
            keyAlgorithm: String = "RSA"
        ): TlsAcceptor {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(certDer.inputStream()) as X509Certificate

            val keyFactory = KeyFactory.getInstance(keyAlgorithm)
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyDer))

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry("device", privateKey, charArrayOf(), arrayOf(cert))

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, charArrayOf())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            return TlsAcceptor(sslContext)
        }

        /**
         * Create a [TlsAcceptor] from PEM-encoded certificate chain and private key.
         *
         * @param certPem PEM-encoded certificate chain (may contain multiple certs)
         * @param keyPem PEM-encoded PKCS#8 private key
         */
        fun fromPem(certPem: String, keyPem: String): TlsAcceptor {
            val certFactory = CertificateFactory.getInstance("X.509")
            val certs = parsePemCertificates(certFactory, certPem)
            require(certs.isNotEmpty()) { "No certificates found in PEM" }

            val privateKey = parsePemPrivateKey(keyPem)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                "device",
                privateKey,
                charArrayOf(),
                certs.toTypedArray()
            )

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, charArrayOf())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            return TlsAcceptor(sslContext)
        }

        private fun parsePemCertificates(
            factory: CertificateFactory,
            pem: String
        ): List<X509Certificate> {
            val regex = Regex(
                "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
                RegexOption.DOT_MATCHES_ALL
            )
            return regex.findAll(pem).map { match ->
                val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
                val der = java.util.Base64.getDecoder().decode(base64)
                factory.generateCertificate(der.inputStream()) as X509Certificate
            }.toList()
        }

        private fun parsePemPrivateKey(pem: String): java.security.PrivateKey {
            // Support PKCS#8 ("PRIVATE KEY") and EC/RSA-specific headers
            val pattern =
                "-----BEGIN (?:RSA |EC )?PRIVATE KEY-----" +
                    "(.+?)" +
                    "-----END (?:RSA |EC )?PRIVATE KEY-----"
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(pem)
                ?: throw IllegalArgumentException("No private key found in PEM")
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val keyBytes = java.util.Base64.getDecoder().decode(base64)

            // Try EC first (our ACME keys are EC P-256), then RSA
            return try {
                val keyFactory = KeyFactory.getInstance("EC")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            } catch (_: Exception) {
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            }
        }
    }
}

/**
 * What the handshake pump must do next after an `unwrap`, once its
 * `SSLEngineResult.Status` has been classified. The statuses that are not
 * recoverable do not appear here -- the classifier throws for them -- so every
 * member of this enum is an ordinary continuation.
 */
private enum class HandshakeUnwrapAction {
    /** The record was consumed; carry on with whatever the engine asks next. */
    PROCEED,

    /** `netIn` holds a partial record: pull one more mux DATA frame. */
    PULL_MORE_NET_DATA,

    /** `appIn` was too small for the record's plaintext: grow it and retry. */
    GROW_APP_BUFFER
}

private fun ensureCapacity(buffer: java.nio.ByteBuffer, additionalBytes: Int): java.nio.ByteBuffer {
    if (buffer.remaining() >= additionalBytes) return buffer
    val newBuffer = java.nio.ByteBuffer.allocate(buffer.position() + additionalBytes)
    buffer.flip()
    newBuffer.put(buffer)
    return newBuffer
}

/** Append [data] to a "compact"-mode buffer, growing it if necessary. */
private fun appendTo(buffer: java.nio.ByteBuffer, data: ByteArray): java.nio.ByteBuffer =
    ensureCapacity(buffer, data.size).also { it.put(data) }

/** Copy out a flipped buffer's readable region. */
private fun drain(buffer: java.nio.ByteBuffer): ByteArray =
    ByteArray(buffer.remaining()).also { buffer.get(it) }

/** Smallest buffer [growBuffer] will hand back, so a zero-capacity buffer still grows. */
private const val MIN_BUFFER_GROWTH = 1024

/**
 * Double a "fill"-mode buffer (position = write pointer), preserving what is
 * already written. Used by both the handshake pump and [SuspendTlsSession.read]
 * to recover from `BUFFER_OVERFLOW`: `unwrap` consumed nothing, so the retry only
 * terminates because the destination got bigger.
 */
private fun growBuffer(buffer: java.nio.ByteBuffer): java.nio.ByteBuffer {
    val grown = java.nio.ByteBuffer.allocate(
        (buffer.capacity() * 2).coerceAtLeast(MIN_BUFFER_GROWTH)
    )
    buffer.flip()
    grown.put(buffer)
    return grown
}

/**
 * Run every task the engine has deferred, on the IO dispatcher -- a delegated
 * task is blocking work (key agreement, and for some providers CRL/OCSP fetches).
 *
 * Shared by the handshake pump and [SuspendTlsSession.read]; the engine can ask
 * for a task after the handshake as well, and a read path that ignores it makes
 * no progress at all. See #565.
 */
private suspend fun runDelegatedTasks(engine: SSLEngine): Unit = withContext(Dispatchers.IO) {
    var task = engine.delegatedTask
    while (task != null) {
        task.run()
        task = engine.delegatedTask
    }
}

/**
 * A suspend-native [TlsAcceptor.TlsSession] that encrypts/decrypts plaintext against
 * an underlying [MuxStream]. Reads and writes are serialized with separate mutexes
 * so one direction may proceed while the other is suspended.
 */
@Suppress("TooManyFunctions")
private class SuspendTlsSession(
    private val engine: SSLEngine,
    private val stream: MuxStream,
    initialNetIn: java.nio.ByteBuffer
) : TlsAcceptor.TlsSession {

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    // Encrypted bytes received from the mux stream but not yet unwrapped.
    // Kept in "compact" mode (position = write pointer).
    private var netIn: java.nio.ByteBuffer = initialNetIn

    // Decrypted plaintext ready to hand back to the caller. Kept in "read" mode
    // (flipped -- position..limit is the readable region).
    private var appIn: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocate(engine.session.applicationBufferSize).also { it.flip() }

    private val netOut: java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocate(engine.session.packetBufferSize)

    @Volatile
    private var eof: Boolean = false

    @Suppress("LoopWithTooManyJumpStatements")
    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int = readMutex.withLock {
        if (eof) return@withLock -1
        if (appIn.hasRemaining()) {
            return@withLock takeFromAppIn(buf, off, len)
        }

        appIn.clear()
        // Set when the previous unwrap reported BUFFER_UNDERFLOW: netIn holds a
        // PARTIAL TLS record and the next iteration MUST pull another mux DATA
        // frame even though netIn is non-empty. Without this the loop unwraps the
        // same partial record forever at 100% CPU and the peer blocks until its
        // socket read timeout fires. See #558.
        var needMoreNetData = false
        while (true) {
            // A no-progress iteration here has no suspension point of its own, so
            // it would spin uncancellably (#558/#563). Check explicitly.
            currentCoroutineContext().ensureActive()
            if (!ensureNetData(forceRead = needMoreNetData)) {
                eof = true
                return@withLock -1
            }
            needMoreNetData = false
            netIn.flip()

            val result = engine.unwrap(netIn, appIn)
            netIn.compact()
            runTasksIfRequested(result)

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    appIn.flip()
                    if (appIn.hasRemaining()) {
                        return@withLock takeFromAppIn(buf, off, len)
                    }
                    appIn.clear()
                    // May need more data, loop
                }
                SSLEngineResult.Status.CLOSED -> {
                    eof = true
                    return@withLock -1
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // netIn holds only a partial record: force a stream read on
                    // the next iteration instead of re-unwrapping the same bytes.
                    needMoreNetData = true
                    appIn.clear()
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    // Grow the app buffer and retry unwrap immediately
                    // (netIn still has the data that caused overflow)
                    appIn = growBuffer(appIn)
                    continue
                }
                // An unknown unwrap status is a bug, not end-of-stream. Returning
                // -1 reported a clean EOF for it and hid the defect. See #565.
                // The type is TunnelError.UnhandledTlsState rather than a plain
                // IOException because the only caller has to swallow ordinary
                // disconnect IOExceptions and must still let THIS one out. See #616.
                else -> {
                    eof = true
                    throw TunnelError.UnhandledTlsState(
                        "Unhandled TLS unwrap status: ${result.status}"
                    )
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }

    /**
     * Make sure [netIn] holds bytes worth unwrapping, pulling one more mux DATA
     * frame when it is empty or when [forceRead] says the last unwrap underflowed
     * on a partial record. Multiple TLS records may arrive in ONE mux DATA frame
     * (so we must not read while netIn still holds whole records), and ONE TLS
     * record may be SPLIT across two frames (so we must read when netIn holds
     * only a partial one). See #558.
     *
     * @return false on EOF
     */
    private suspend fun ensureNetData(forceRead: Boolean): Boolean {
        if (netIn.position() != 0 && !forceRead) return true
        return pullNetData()
    }

    /**
     * Run whatever the engine deferred. The engine can ask for a delegated task
     * after the handshake too; the handshake pump has always run these, and
     * without the same branch here `read` re-unwraps the same bytes forever
     * because nothing else can advance the engine. See #565.
     */
    private suspend fun runTasksIfRequested(result: SSLEngineResult) {
        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            runDelegatedTasks(engine)
        }
    }

    /** Hand back up to [len] plaintext bytes already decoded into [appIn]. */
    private fun takeFromAppIn(buf: ByteArray, off: Int, len: Int): Int {
        val toRead = minOf(len, appIn.remaining())
        appIn.get(buf, off, toRead)
        return toRead
    }

    /** Pull one more mux DATA frame into [netIn]. Returns false on EOF/error. */
    private suspend fun pullNetData(): Boolean {
        val tlsData = try {
            stream.read()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        netIn = appendTo(netIn, tlsData)
        return true
    }

    override suspend fun write(buf: ByteArray, off: Int, len: Int) = writeMutex.withLock {
        val appOut = java.nio.ByteBuffer.wrap(buf, off, len)
        while (appOut.hasRemaining()) {
            netOut.clear()
            val result = engine.wrap(appOut, netOut)
            netOut.flip()
            if (netOut.hasRemaining()) {
                val data = ByteArray(netOut.remaining())
                netOut.get(data)
                try {
                    stream.write(data)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw java.io.IOException("TLS write failed: stream closed", e)
                }
            }
            classifyWrapStatus(result.status)
        }
    }

    /**
     * Decide what a `wrap` status means. `SSLEngineResult.Status` is a closed
     * four-member enum, so this is decidable rather than arguable -- and the two
     * kinds of non-OK outcome must NOT share an exception type:
     *
     *  - `CLOSED` is the peer hanging up mid-response. Routine on a bridge. It
     *    ends the copy loop quietly, as a plain [java.io.IOException] -- the same
     *    thing a dead transport a few lines above reports, and the same thing
     *    `SessionHandler.copyStreamToTls` deliberately swallows. Making routine
     *    disconnects noisy trains whoever reads the log to ignore it.
     *  - `BUFFER_OVERFLOW` and `BUFFER_UNDERFLOW` are defects in THIS layer, and
     *    a plain `IOException` cannot say so: the loop that catches it has to
     *    keep swallowing disconnect `IOException`s, so a defect wearing that type
     *    ends the session as a clean EOF and is never heard. That is exactly the
     *    hole #616 closed on the read side; #626 added the specific catch clause
     *    on this side but nothing here threw the type it keys off, leaving it
     *    inert. See #630.
     *
     * `netOut` is allocated at `session.packetBufferSize` and `clear()`ed before
     * every wrap, so the destination always offers the engine's own advertised
     * maximum for one record: `BUFFER_OVERFLOW` here means that bound was broken,
     * not that we under-allocated, so growing and retrying (which is the right
     * answer on the unwrap side, where `appIn` is drained incrementally by the
     * caller) would only hide it. `BUFFER_UNDERFLOW` is an unwrap concept -- it
     * means the source holds less than one whole TLS record -- and `wrap` has no
     * minimum input at all, so reporting it is a contract violation. The read
     * path treats both as ordinary for reasons that do not survive the trip here;
     * the asymmetry is deliberate.
     *
     * There is deliberately no `else`: the `when` is used as an expression, so it
     * must stay exhaustive. Should a future JDK add a fifth member, this stops
     * compiling instead of silently classifying it as success.
     */
    private fun classifyWrapStatus(status: SSLEngineResult.Status) {
        val defect = when (status) {
            SSLEngineResult.Status.OK -> return
            SSLEngineResult.Status.CLOSED ->
                throw java.io.IOException("TLS wrap failed: connection closed")
            SSLEngineResult.Status.BUFFER_OVERFLOW,
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> status
        }
        throw TunnelError.UnhandledTlsState("Unhandled TLS wrap status: $defect")
    }

    override suspend fun close() {
        eof = true
        try {
            stream.close()
        } catch (_: Exception) {
            // Best effort
        }
    }
}
