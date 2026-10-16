package com.rousecontext.bridge

import com.rousecontext.mcp.core.InMemoryProviderRegistry
import com.rousecontext.mcp.core.InMemoryTokenStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Regression gate for #778: a burst of connections whose client goes silent
 * mid-request -- no more bytes and no CLOSE frame -- parked one
 * `Dispatchers.IO` worker per connection in `SessionHandler.copyStreamToTls`
 * on a blocking `Socket.InputStream.read`, for as long as the session lived.
 * A partial request body is never timed out by Ktor, so that was forever; at
 * 64 the shared IO pool was full and every `withContext(Dispatchers.IO)` in
 * the app queued behind them.
 *
 * Measured directly: the tests count live `DefaultDispatcher-worker` threads
 * whose stack is inside [SessionHandler], rather than inferring from timing.
 */
class SessionHandlerAbandonedConnectionTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(TEST_TIMEOUT_SECONDS)

    private val certStore = TestCertHelper()
    private val tokenStore = InMemoryTokenStore()
    private val token = tokenStore.createTokenPair("test", "abandon-test-client").accessToken
    private val registry = InMemoryProviderRegistry().apply {
        register("test", EchoProvider())
        setEnabled("test", true)
    }

    /**
     * Client-side TLS work runs on its own threads so that the only
     * `DefaultDispatcher-worker` threads in play are the device side's.
     */
    private val clientPool: ExecutorService = Executors.newFixedThreadPool(CLIENT_THREADS) {
        Thread(it, "abandon-test-client").apply { isDaemon = true }
    }
    private val clientDispatcher = clientPool.asCoroutineDispatcher()

    /** One Ktor server shared by every stream, as in production. */
    private val mcpServer = runBlocking { KtorMcpSessionFactory(registry, tokenStore).create() }

    @AfterTest
    fun tearDown() {
        mcpServer.stop()
        clientPool.shutdownNow()
    }

    @Test
    fun `connections abandoned mid-request hold no IO worker threads`() = runBlocking {
        val bridged = AtomicInteger()
        val allBridged = CompletableDeferred<Unit>()
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = sharedFactory(
                onCreate = { if (bridged.incrementAndGet() == ABANDONED) allBridged.complete(Unit) }
            )
        )
        val baseline = workersInsideSessionHandler()

        val sessions = mutableListOf<Job>()
        val requestsSent = (1..ABANDONED).map { i ->
            val sent = CompletableDeferred<Unit>()
            val (serverStream, clientStream) = createMuxPipe(i.toUInt())
            sessions += launch { handler.handleStream(serverStream) }
            launch(clientDispatcher) {
                val (_, out) = tlsClientHandshake(certStore, clientStream)
                // Headers plus half the declared body, then silence: no more
                // bytes and no CLOSE. The relay cannot tell the device about a
                // client that vanished without a FIN.
                out.write(partialInitializeRequest())
                out.flush()
                sent.complete(Unit)
            }
            sent
        }
        withTimeout(SETTLE_TIMEOUT_MS) {
            requestsSent.awaitAll()
            allBridged.await()
        }
        // Let every bridge connect and reach its socket read.
        delay(SETTLE_DELAY_MS)

        val held = workersInsideSessionHandler() - baseline
        assertTrue(
            sessions.all { it.isActive },
            "precondition: the abandoned sessions are still open, so a zero below is not vacuous"
        )
        assertEquals(
            0,
            held,
            "$ABANDONED abandoned sessions are holding $held IO worker threads:\n" +
                sessionHandlerStacks()
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a session abandoned mid-request ends after the idle bound`() = runBlocking {
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = sharedFactory(),
            idleTimeout = SHORT_IDLE
        )
        val (serverStream, clientStream) = createMuxPipe(1u)
        val session = launch { handler.handleStream(serverStream) }
        launch(clientDispatcher) {
            val (_, out) = tlsClientHandshake(certStore, clientStream)
            out.write(partialInitializeRequest())
            out.flush()
        }

        withTimeout(SETTLE_TIMEOUT_MS) { session.join() }

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a completed request still gets its response`() = runBlocking {
        val handler = SessionHandler(certProvider = certStore, mcpSessionFactory = sharedFactory())
        val (serverStream, clientStream) = createMuxPipe(1u)
        val session = launch { handler.handleStream(serverStream) }

        val statusLine = withTimeout(SETTLE_TIMEOUT_MS) {
            async(clientDispatcher) {
                val (input, out) = tlsClientHandshake(certStore, clientStream)
                out.write(initializeRequest())
                out.flush()
                input.bufferedReader().readLine()
            }.await()
        }
        assertEquals("HTTP/1.1 200 OK", statusLine)

        // The client hanging up (the relay's CLOSE) still ends the session.
        clientStream.close()
        withTimeout(SETTLE_TIMEOUT_MS) { session.join() }

        coroutineContext.cancelChildren()
    }

    @Test
    fun `a request that keeps moving bytes outlives the idle bound`() = runBlocking {
        val handler = SessionHandler(
            certProvider = certStore,
            mcpSessionFactory = sharedFactory(),
            idleTimeout = SHORT_IDLE
        )
        val (serverStream, clientStream) = createMuxPipe(1u)
        launch { handler.handleStream(serverStream) }

        val statusLine = withTimeout(SETTLE_TIMEOUT_MS) {
            async(clientDispatcher) {
                val (input, out) = tlsClientHandshake(certStore, clientStream)
                // Dribble the request so it takes several idle periods in all,
                // but no single gap reaches one.
                val request = initializeRequest()
                val chunk = request.size / DRIBBLE_CHUNKS + 1
                for (start in request.indices step chunk) {
                    out.write(request, start, minOf(chunk, request.size - start))
                    out.flush()
                    delay(SHORT_IDLE / 3)
                }
                input.bufferedReader().readLine()
            }.await()
        }
        assertEquals("HTTP/1.1 200 OK", statusLine)

        coroutineContext.cancelChildren()
    }

    // -- helpers --

    private fun sharedFactory(onCreate: () -> Unit = {}) = object : McpSessionFactory {
        override suspend fun create(): McpSessionHandle {
            onCreate()
            return mcpServer.copy(stop = {})
        }
    }

    private fun initializeRequest(): ByteArray {
        val body = INITIALIZE_BODY.toByteArray()
        val head = "POST /mcp HTTP/1.1\r\n" +
            "Host: test.rousecontext.com\r\n" +
            "Content-Type: application/json\r\n" +
            "Accept: application/json\r\n" +
            "Authorization: Bearer $token\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        return head.toByteArray() + body
    }

    /** [initializeRequest] cut off halfway through its body. */
    private fun partialInitializeRequest(): ByteArray {
        val full = initializeRequest()
        return full.copyOf(full.size - INITIALIZE_BODY.length / 2)
    }

    private fun stacksInsideSessionHandler(): Map<Thread, Array<StackTraceElement>> =
        Thread.getAllStackTraces().filter { (thread, stack) ->
            thread.name.startsWith(IO_WORKER_PREFIX) &&
                stack.any { it.className.startsWith(SessionHandler::class.java.name) }
        }

    private fun workersInsideSessionHandler(): Int = stacksInsideSessionHandler().size

    private fun sessionHandlerStacks(): String = stacksInsideSessionHandler().values
        .firstOrNull()
        ?.take(STACK_DEPTH)
        ?.joinToString("\n") { "    at $it" }
        .orEmpty()

    private companion object {
        /** Well under the 64-thread IO ceiling, so the gate cannot wedge the test JVM. */
        const val ABANDONED = 16
        const val CLIENT_THREADS = 4
        const val SETTLE_TIMEOUT_MS = 15_000L
        const val SETTLE_DELAY_MS = 1_000L
        const val STACK_DEPTH = 12
        const val DRIBBLE_CHUNKS = 8
        val SHORT_IDLE = 600.milliseconds
        const val IO_WORKER_PREFIX = "DefaultDispatcher-worker"
        const val INITIALIZE_BODY =
            """{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2025-03-26",""" +
                """"capabilities":{},"clientInfo":{"name":"abandon-test","version":"1.0"}},"id":1}"""
    }
}
