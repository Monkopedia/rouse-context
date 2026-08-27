package com.rousecontext.tunnel

import java.nio.ByteBuffer
import java.security.KeyManagementException
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * Test harness for driving [TlsAcceptor] against a *scripted* [SSLEngine].
 *
 * ## Why a fake engine rather than a real handshake
 *
 * The three states covered by #565 -- `BUFFER_OVERFLOW` during the handshake
 * pump, `NEED_UNWRAP_AGAIN`, and `NEED_TASK` after the handshake -- are all
 * states SunJSSE does not produce on a healthy TLS 1.3 exchange over this
 * transport:
 *
 * - `BUFFER_OVERFLOW` during the pump requires `appIn` to be smaller than a
 *   record's plaintext, and the pump sizes it from `session.applicationBufferSize`.
 * - `NEED_UNWRAP_AGAIN` is a DTLS-oriented status; the TLS code path in SunJSSE
 *   does not return it.
 * - a post-handshake `NEED_TASK` requires the engine to defer work after
 *   `FINISHED`, which SunJSSE does not do for the cipher suites here.
 *
 * That is exactly the point of the issue: the states are unhandled *and*
 * unreachable-by-construction today, so the only thing that keeps the fallback
 * honest is a test that supplies the state directly. `SSLEngine` is an abstract
 * class and `SSLContext` takes a pluggable [SSLContextSpi], so the production
 * loop can be driven verbatim with a scripted engine behind it -- no production
 * seam, no reflection.
 *
 * See [TlsAcceptorEngineStateTest] for the scripts.
 */
@Suppress("TooManyFunctions")
internal abstract class ScriptedSslEngine(private val scriptedSession: SSLSession) : SSLEngine() {

    private var clientMode: Boolean = false

    /** Number of `unwrap` calls the production loop has made. */
    var unwrapCalls: Int = 0
        protected set

    override fun beginHandshake() = Unit

    override fun closeInbound() = Unit

    override fun closeOutbound() = Unit

    override fun isInboundDone(): Boolean = false

    override fun isOutboundDone(): Boolean = false

    override fun getSupportedCipherSuites(): Array<String> = emptyArray()

    override fun getEnabledCipherSuites(): Array<String> = emptyArray()

    override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit

    override fun getSupportedProtocols(): Array<String> = emptyArray()

    override fun getEnabledProtocols(): Array<String> = emptyArray()

    override fun setEnabledProtocols(protocols: Array<out String>?) = Unit

    override fun getSession(): SSLSession = scriptedSession

    override fun setUseClientMode(mode: Boolean) {
        clientMode = mode
    }

    override fun getUseClientMode(): Boolean = clientMode

    override fun setNeedClientAuth(need: Boolean) = Unit

    override fun getNeedClientAuth(): Boolean = false

    override fun setWantClientAuth(want: Boolean) = Unit

    override fun getWantClientAuth(): Boolean = false

    override fun setEnableSessionCreation(flag: Boolean) = Unit

    override fun getEnableSessionCreation(): Boolean = true

    override fun getDelegatedTask(): Runnable? = null

    // TlsAcceptor.createServerEngine() round-trips SSLParameters to set ALPN.
    // The scripted engine has no real parameters, so accept and discard them.
    override fun getSSLParameters(): SSLParameters = SSLParameters()

    override fun setSSLParameters(params: SSLParameters?) = Unit

    override fun wrap(
        srcs: Array<out ByteBuffer>?,
        offset: Int,
        length: Int,
        dst: ByteBuffer?
    ): SSLEngineResult = error("scripted engine: unexpected wrap()")

    final override fun unwrap(
        src: ByteBuffer?,
        dsts: Array<out ByteBuffer>?,
        offset: Int,
        length: Int
    ): SSLEngineResult {
        unwrapCalls++
        return scriptedUnwrap(requireNotNull(src), requireNotNull(dsts)[offset])
    }

    /** Scripted body for a single `unwrap(src, dst)` call. */
    protected abstract fun scriptedUnwrap(src: ByteBuffer, dst: ByteBuffer): SSLEngineResult

    companion object {
        /**
         * A real, handshake-less [SSLSession] -- used only for its
         * `applicationBufferSize` / `packetBufferSize`, which the production
         * code reads when sizing its buffers.
         */
        fun nullSession(): SSLSession = SSLContext.getInstance("TLS")
            .apply { init(null, null, null) }
            .createSSLEngine()
            .session
    }
}

/**
 * An [SSLContext] whose `createSSLEngine()` hands back a scripted engine, so
 * `TlsAcceptor.accept` drives the fake without any production seam.
 */
internal class ScriptedSslContext(engine: SSLEngine) :
    SSLContext(ScriptedSslContextSpi(engine), null, "SCRIPTED")

private class ScriptedSslContextSpi(private val engine: SSLEngine) : SSLContextSpi() {
    @Throws(KeyManagementException::class)
    override fun engineInit(
        km: Array<out KeyManager>?,
        tm: Array<out TrustManager>?,
        sr: SecureRandom?
    ) = Unit

    override fun engineGetSocketFactory(): SSLSocketFactory = error("scripted context: unused")

    override fun engineGetServerSocketFactory(): SSLServerSocketFactory =
        error("scripted context: unused")

    override fun engineCreateSSLEngine(): SSLEngine = engine

    override fun engineCreateSSLEngine(host: String?, port: Int): SSLEngine = engine

    override fun engineGetClientSessionContext(): SSLSessionContext =
        error("scripted context: unused")

    override fun engineGetServerSessionContext(): SSLSessionContext =
        error("scripted context: unused")
}
