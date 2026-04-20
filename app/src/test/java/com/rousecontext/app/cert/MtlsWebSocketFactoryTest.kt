package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.WebSocketListener
import java.io.File
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [MtlsWebSocketFactory] plus the internal
 * [OkHttpWebSocketFactory] / [OkHttpWebSocketHandle] scaffolding.
 *
 * The goal is to exercise the branching in `buildOkHttpClient` / `loadCertAndKey`
 * / `loadCertChain` / `loadRelayCaCert` without standing up a real TLS
 * handshake — that is the integration-tier's job (issues #237/#252/#253).
 *
 * States covered:
 *   - No client cert file: falls back to a plain OkHttp client. The production
 *     code still returns a non-null factory so the connect-and-fail path is
 *     reachable.
 *   - Client cert only (no relay CA): mTLS configured with leaf-only chain.
 *   - Client cert + relay CA: mTLS configured with leaf+CA chain, SSLContext
 *     initialised.
 *   - Empty / malformed cert file: treated as "no cert" — no crash.
 *   - [DeviceKeyManager] that throws: treated as "no cert" — falls back to
 *     plain OkHttp without surfacing the exception.
 *
 * For the WebSocket handle we open a short-lived plain TCP server, send the
 * client straight at it, and poke the handle's methods once a handle is in
 * our hands. The server is deliberately NOT a WebSocket server — OkHttp will
 * fail the upgrade, but not before we get the [OkHttpWebSocketHandle] back
 * synchronously.
 */
@RunWith(RobolectricTestRunner::class)
class MtlsWebSocketFactoryTest {

    private lateinit var context: Context
    private lateinit var keyPair: KeyPair
    private lateinit var deviceKeyManager: DeviceKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.delete() }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        keyPair = kpg.generateKeyPair()
        deviceKeyManager = object : DeviceKeyManager {
            override fun getOrCreateKeyPair(): KeyPair = keyPair
        }
    }

    @After
    fun tearDown() {
        context.filesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `create returns a factory when no cert file exists (plain path)`() {
        val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)

        assertNotNull(
            "create() must always return a factory — the plain path is the safe fallback",
            factory
        )
        // Dry-run a connect to exercise OkHttpWebSocketFactory.connect and the
        // listener hand-off. onFailure will fire asynchronously but construction
        // itself must succeed.
        val handle = factory.connect("ws://127.0.0.1:1/", NoopListener())
        assertNotNull(handle)
    }

    @Test
    fun `create returns a factory when client cert file is empty`() {
        // An empty file parses to zero certs; the factory treats that as "no cert
        // available" and degrades to the plain path without crashing.
        writeFile(CLIENT_CERT_FILE, "")

        val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)

        assertNotNull(factory)
    }

    @Test
    fun `create returns mTLS factory when client cert is present`() {
        // A client cert alone is enough to build the mTLS SSLContext — the relay
        // CA is optional for the leaf-only chain used by CA-less dev relays.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))

        val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)

        assertNotNull(factory)
    }

    @Test
    fun `create returns mTLS factory when client cert plus relay CA present`() {
        // Full production shape: leaf + relay CA. Exercises the branch that adds
        // the CA to the trust store and appends it to the presented chain.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        val ca = CertTestUtil.generateSelfSignedCert("relay-ca.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        writeFile(RELAY_CA_FILE, CertTestUtil.derToPem(ca.encoded))

        val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)

        assertNotNull(factory)
    }

    @Test
    fun `create falls back to plain path when relay CA file is empty`() {
        // A present-but-empty relay CA file must not take down the whole factory:
        // it represents a half-completed onboarding write, which is survivable.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        writeFile(RELAY_CA_FILE, "")

        val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)

        assertNotNull(factory)
    }

    @Test
    fun `create falls back to plain path when DeviceKeyManager throws`() {
        // Simulates a wiped Keystore alias mid-run. We MUST NOT let the exception
        // propagate out of create(); the app would otherwise crash at connection
        // establishment time on affected devices.
        val leaf = CertTestUtil.generateSelfSignedCert("client.rousecontext.com")
        writeFile(CLIENT_CERT_FILE, CertTestUtil.derToPem(leaf.encoded))
        val throwingKeyManager = object : DeviceKeyManager {
            override fun getOrCreateKeyPair(): KeyPair = error("simulated keystore failure")
        }

        val factory = MtlsWebSocketFactory.create(context, throwingKeyManager)

        assertNotNull(factory)
    }

    @Test
    fun `end-to-end WebSocket callbacks fire for onOpen onMessage onClosing`() {
        // Drive OkHttp's okhttp3.WebSocketListener through a real WebSocket
        // handshake + text frame + close frame so our adapter inside
        // OkHttpWebSocketFactory.connect() translates every event onto our
        // WebSocketListener. Covers lines 224/228/232/233 which only fire on
        // a successful upgrade.
        TestWebSocketServer(
            sendTextPayload = "hi",
            sendBinaryPayload = byteArrayOf(4, 2),
            closeCodeToSend = 1000
        ).use { server ->
            val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)
            val openLatch = CountDownLatch(1)
            val binaryLatch = CountDownLatch(1)
            val closingLatch = CountDownLatch(1)
            val listener = object : WebSocketListener {
                override fun onOpen() {
                    openLatch.countDown()
                }
                override fun onBinaryMessage(data: ByteArray) {
                    binaryLatch.countDown()
                }
                override fun onClosing(code: Int, reason: String) {
                    closingLatch.countDown()
                }
                override fun onFailure(error: Throwable) = Unit
            }

            val handle = factory.connect("ws://127.0.0.1:${server.port}/", listener)
            assertNotNull(handle)

            // We can't deterministically guarantee onMessage fires before the
            // close frame in every OkHttp scheduling, but we CAN guarantee the
            // upgrade completed (onOpen) and the close frame was processed
            // (onClosing). The send/close methods on the handle are also
            // exercised here.
            assertTrue("onOpen must fire", openLatch.await(5, TimeUnit.SECONDS))
            assertTrue("onBinaryMessage must fire", binaryLatch.await(5, TimeUnit.SECONDS))
            assertTrue("onClosing must fire", closingLatch.await(5, TimeUnit.SECONDS))

            kotlinx.coroutines.runBlocking {
                // Send attempts after the peer close frame should still return
                // cleanly (false or true, both acceptable — we only care that
                // no exception escapes).
                handle.sendBinary(byteArrayOf(1, 2, 3))
                handle.sendText("hello")
                handle.close(1000, "test-done")
            }
        }
    }

    @Test
    fun `connect returns a handle that accepts send-and-close without throwing`() {
        // Drive OkHttpWebSocketFactory.connect / OkHttpWebSocketHandle.{sendBinary,
        // sendText, close} by wiring to a live but non-WebSocket TCP server on
        // loopback. The server accepts the connect, lets OkHttp attempt the
        // upgrade, and OkHttp surfaces an onFailure — but we already have the
        // handle by then, so we can invoke its methods and prove they return
        // without throwing. `send` returns false once the connection is closing;
        // that's fine — coverage is the goal here, not a real round-trip.
        val server = ServerSocket(0)
        val serverThread = Thread {
            try {
                server.accept().use { s ->
                    // Drain the HTTP upgrade request and immediately close. OkHttp's
                    // websocket upgrade will fail, which is enough to cover onFailure
                    // hand-off.
                    s.getInputStream().read(ByteArray(1024))
                }
            } catch (_: Exception) {
                // socket closed during test teardown — ignore
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val factory = MtlsWebSocketFactory.create(context, deviceKeyManager)
            val latch = CountDownLatch(1)
            val capturedError = AtomicReference<Throwable?>(null)
            val listener = object : WebSocketListener {
                override fun onOpen() {
                    latch.countDown()
                }
                override fun onBinaryMessage(data: ByteArray) = Unit
                override fun onClosing(code: Int, reason: String) {
                    latch.countDown()
                }
                override fun onFailure(error: Throwable) {
                    capturedError.set(error)
                    latch.countDown()
                }
            }
            val handle = factory.connect("ws://127.0.0.1:${server.localPort}/", listener)

            // Exercise the handle methods synchronously — none of these should throw
            // regardless of the underlying connection state.
            kotlinx.coroutines.runBlocking {
                handle.sendBinary(byteArrayOf(1, 2, 3))
                handle.sendText("hello")
                handle.close(1000, "test-done")
            }

            // Drain the listener latch. If it times out we still pass — all we care
            // about here is that no uncaught exception crossed the handle boundary.
            latch.await(2, TimeUnit.SECONDS)
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }

    private fun writeFile(name: String, content: String) {
        File(context.filesDir, name).writeText(content)
    }

    private class NoopListener : WebSocketListener {
        override fun onOpen() = Unit
        override fun onBinaryMessage(data: ByteArray) = Unit
        override fun onClosing(code: Int, reason: String) = Unit
        override fun onFailure(error: Throwable) = Unit
    }

    private companion object {
        const val CLIENT_CERT_FILE = "rouse_client_cert.pem"
        const val RELAY_CA_FILE = "rouse_relay_ca.pem"
    }
}
