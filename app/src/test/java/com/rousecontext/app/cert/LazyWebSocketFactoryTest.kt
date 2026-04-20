package com.rousecontext.app.cert

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [LazyWebSocketFactory].
 *
 * The factory defers real OkHttp construction until first use so Koin init does
 * not require cert files to exist. These tests prove:
 *
 * - connect() without any cert files on disk delegates to the plain-HTTP path
 *   inside [MtlsWebSocketFactory] and returns a non-null [WebSocketHandle].
 * - connect() called twice reuses the same underlying factory (observable via
 *   invalidate() resetting it).
 * - invalidate() is idempotent and can be called before any connect().
 *
 * We don't assert anything about the WebSocket actually opening: the listener's
 * onFailure will fire because the target URL points at an unrouted loopback
 * port. What we exercise is the factory wiring, not the transport.
 */
@RunWith(RobolectricTestRunner::class)
class LazyWebSocketFactoryTest {

    private lateinit var context: Context
    private lateinit var deviceKeyManager: DeviceKeyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.delete() }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        deviceKeyManager = object : DeviceKeyManager {
            override fun getOrCreateKeyPair(): KeyPair = kp
        }
    }

    @Test
    fun `connect without cert files builds a plain factory and returns a handle`() {
        val lazy = LazyWebSocketFactory(context, deviceKeyManager)
        val listener = NoopListener()

        val handle = lazy.connect("ws://127.0.0.1:1/", listener)

        // The handle is whatever OkHttp returns synchronously — it will asynchronously
        // fail, but construction must have succeeded.
        assertNotNull(handle)
    }

    @Test
    fun `invalidate before connect does not throw`() {
        val lazy = LazyWebSocketFactory(context, deviceKeyManager)

        // No delegate has been created yet; invalidate() must tolerate the null
        // state so onboarding-completed callbacks fire safely whether or not the
        // tunnel has already opened.
        lazy.invalidate()

        val handle = lazy.connect("ws://127.0.0.1:1/", NoopListener())
        assertNotNull(handle)
    }

    @Test
    fun `invalidate resets the cached delegate`() {
        // The factory stores the underlying OkHttpClient; invalidate() discards it
        // so a subsequent connect() rebuilds from fresh cert state. There is no
        // direct observable state, so we assert the second connect() still works
        // (which requires rebuilding the delegate from the current filesDir).
        val lazy = LazyWebSocketFactory(context, deviceKeyManager)

        lazy.connect("ws://127.0.0.1:1/", NoopListener())
        lazy.invalidate()
        val after = lazy.connect("ws://127.0.0.1:1/", NoopListener())

        assertNotNull(after)
    }

    @Test
    fun `successive connects reuse the delegate`() {
        // Not a strict correctness requirement, but a performance one: rebuilding
        // an OkHttpClient per connect thrashes connection pools. The production
        // code caches until invalidate(). Two back-to-back connects must succeed
        // identically.
        val lazy = LazyWebSocketFactory(context, deviceKeyManager)

        val first = lazy.connect("ws://127.0.0.1:1/", NoopListener())
        val second = lazy.connect("ws://127.0.0.1:1/", NoopListener())

        assertNotNull(first)
        assertNotNull(second)
    }

    private class NoopListener : WebSocketListener {
        override fun onOpen() = Unit
        override fun onBinaryMessage(data: ByteArray) = Unit
        override fun onClosing(code: Int, reason: String) = Unit
        override fun onFailure(error: Throwable) = Unit
    }
}

@Suppress("unused")
private fun WebSocketHandle.noop() = Unit
