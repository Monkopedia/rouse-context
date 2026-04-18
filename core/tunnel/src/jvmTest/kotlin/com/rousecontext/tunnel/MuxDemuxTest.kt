package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MuxDemuxTest {

    @Test
    fun openFrameCreatesStream() = runBlocking {
        val demux = MuxDemux()
        val streamReady = CompletableDeferred<MuxStreamImpl>()

        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))

        val stream = streamReady.await()
        assertEquals(1u, stream.id)

        coroutineContext.cancelChildren()
    }

    @Test
    fun dataFrameRoutesToCorrectStream() = runBlocking {
        val demux = MuxDemux()
        val streamReady = CompletableDeferred<MuxStreamImpl>()

        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        val stream = streamReady.await()

        val dataReady = CompletableDeferred<ByteArray>()
        launch {
            dataReady.complete(stream.incoming.first())
        }

        demux.handleFrame(MuxFrame.Data(streamId = 1u, payload = byteArrayOf(0xAA.toByte())))

        val received = dataReady.await()
        assertContentEquals(byteArrayOf(0xAA.toByte()), received)

        coroutineContext.cancelChildren()
    }

    @Test
    fun dataFrameToUnknownStreamIsIgnored() = runBlocking {
        val demux = MuxDemux()

        // Should not throw or crash
        demux.handleFrame(MuxFrame.Data(streamId = 999u, payload = byteArrayOf(0x01)))
    }

    @Test
    fun closeFrameTearsDownStream() = runBlocking {
        val demux = MuxDemux()
        val streamReady = CompletableDeferred<MuxStreamImpl>()

        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        val stream = streamReady.await()

        demux.handleFrame(MuxFrame.Close(streamId = 1u))

        assertTrue(stream.isClosed)

        coroutineContext.cancelChildren()
    }

    @Test
    fun closeFrameToUnknownStreamIsIgnored() = runBlocking {
        val demux = MuxDemux()

        // Should not throw or crash
        demux.handleFrame(MuxFrame.Close(streamId = 999u))
    }

    @Test
    fun errorFramePropagatesError() = runBlocking {
        val demux = MuxDemux()
        val streamReady = CompletableDeferred<MuxStreamImpl>()

        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        val stream = streamReady.await()

        demux.handleFrame(
            MuxFrame.Error(
                streamId = 1u,
                errorCode = MuxErrorCode.STREAM_RESET,
                message = "reset"
            )
        )

        assertTrue(stream.isClosed)

        coroutineContext.cancelChildren()
    }

    @Test
    fun errorFrameToUnknownStreamIsIgnored() = runBlocking {
        val demux = MuxDemux()

        // Should not throw or crash
        demux.handleFrame(
            MuxFrame.Error(
                streamId = 999u,
                errorCode = MuxErrorCode.INTERNAL_ERROR,
                message = "oops"
            )
        )
    }

    @Test
    fun multipleStreamsRouteIndependently() = runBlocking {
        val demux = MuxDemux()
        val streams = mutableListOf<MuxStreamImpl>()
        val twoStreamsReady = CompletableDeferred<Unit>()

        launch {
            demux.incomingStreams.take(2).toList().let {
                streams.addAll(it)
                twoStreamsReady.complete(Unit)
            }
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        demux.handleFrame(MuxFrame.Open(streamId = 2u))

        twoStreamsReady.await()

        val data1Ready = CompletableDeferred<ByteArray>()
        val data2Ready = CompletableDeferred<ByteArray>()

        launch { data1Ready.complete(streams[0].incoming.first()) }
        launch { data2Ready.complete(streams[1].incoming.first()) }

        demux.handleFrame(MuxFrame.Data(streamId = 2u, payload = byteArrayOf(0x22)))
        demux.handleFrame(MuxFrame.Data(streamId = 1u, payload = byteArrayOf(0x11)))

        assertContentEquals(byteArrayOf(0x11), data1Ready.await())
        assertContentEquals(byteArrayOf(0x22), data2Ready.await())

        coroutineContext.cancelChildren()
    }

    @Test
    fun closeAllClosesAllStreams() = runBlocking {
        val demux = MuxDemux()
        val streamReady = CompletableDeferred<MuxStreamImpl>()

        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        val stream = streamReady.await()

        demux.closeAll()
        assertTrue(stream.isClosed)

        coroutineContext.cancelChildren()
    }

    @Test
    fun openIncrementsActiveStreamCount() = runBlocking {
        val demux = MuxDemux()
        assertEquals(0, demux.activeStreamCount.value)

        launch { demux.incomingStreams.first() }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))

        // Wait until the counter reflects the Open
        kotlinx.coroutines.withTimeout(1000) {
            while (demux.activeStreamCount.value != 1) {
                kotlinx.coroutines.delay(5)
            }
        }
        assertEquals(1, demux.activeStreamCount.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun peerCloseDecrementsActiveStreamCount() = runBlocking {
        val demux = MuxDemux()
        launch { demux.incomingStreams.first() }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        kotlinx.coroutines.withTimeout(1000) {
            while (demux.activeStreamCount.value != 1) kotlinx.coroutines.delay(5)
        }
        assertEquals(1, demux.activeStreamCount.value)

        // Peer-initiated Close must decrement the counter via the demux path,
        // not just via the StreamCloseTracker wrapper (which previously missed this).
        demux.handleFrame(MuxFrame.Close(streamId = 1u))
        assertEquals(0, demux.activeStreamCount.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun peerErrorDecrementsActiveStreamCount() = runBlocking {
        val demux = MuxDemux()
        launch { demux.incomingStreams.first() }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        kotlinx.coroutines.withTimeout(1000) {
            while (demux.activeStreamCount.value != 1) kotlinx.coroutines.delay(5)
        }

        demux.handleFrame(
            MuxFrame.Error(
                streamId = 1u,
                errorCode = MuxErrorCode.STREAM_RESET,
                message = "boom"
            )
        )

        assertEquals(0, demux.activeStreamCount.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun localCloseDecrementsActiveStreamCount() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow */ }

        val streamReady = CompletableDeferred<MuxStreamImpl>()
        launch { streamReady.complete(demux.incomingStreams.first()) }

        demux.handleFrame(MuxFrame.Open(streamId = 7u))
        val stream = streamReady.await()
        kotlinx.coroutines.withTimeout(1000) {
            while (demux.activeStreamCount.value != 1) kotlinx.coroutines.delay(5)
        }

        // Close locally -- should also decrement the counter.
        stream.close()

        kotlinx.coroutines.withTimeout(1000) {
            while (demux.activeStreamCount.value != 0) kotlinx.coroutines.delay(5)
        }
        assertEquals(0, demux.activeStreamCount.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun pingTriggersPongEcho() = runBlocking {
        val sentFrames = mutableListOf<MuxFrame>()
        val demux = MuxDemux()
        demux.onOutgoingFrame = { f -> synchronized(sentFrames) { sentFrames.add(f) } }

        demux.handleFrame(MuxFrame.Ping(nonce = 0x1122334455667788UL))

        kotlinx.coroutines.withTimeout(1000) {
            while (synchronized(sentFrames) { sentFrames.isEmpty() }) {
                kotlinx.coroutines.delay(5)
            }
        }

        val pong = synchronized(sentFrames) { sentFrames.single() }
        assertTrue(pong is MuxFrame.Pong, "Expected Pong, got $pong")
        assertEquals(0x1122334455667788UL, (pong as MuxFrame.Pong).nonce)

        coroutineContext.cancelChildren()
    }

    @Test
    fun pingDoesNotOpenStream() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { }

        val streamSeen = CompletableDeferred<MuxStreamImpl>()
        launch {
            try {
                streamSeen.complete(demux.incomingStreams.first())
            } catch (_: Exception) {
                // channel may close, that's fine
            }
        }

        demux.handleFrame(MuxFrame.Ping(nonce = 1u))
        kotlinx.coroutines.delay(100)

        // No stream should have been emitted
        assertTrue(!streamSeen.isCompleted, "Ping must not open a stream")
        assertEquals(0, demux.activeStreamCount.value)

        coroutineContext.cancelChildren()
    }

    @Test
    fun awaitPongCompletesWhenMatchingPongArrives() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow */ }

        // Send a ping and race a Pong reply back through handleFrame.
        val result = CompletableDeferred<Boolean>()
        launch {
            result.complete(
                demux.sendPingAwaitPong(timeoutMillis = 1_000L, nonce = 0xABCDu.toULong())
            )
        }

        // Simulate the relay sending back a Pong.
        kotlinx.coroutines.delay(50)
        demux.handleFrame(MuxFrame.Pong(nonce = 0xABCDu.toULong()))

        assertTrue(result.await(), "Matching Pong should complete the wait")
        coroutineContext.cancelChildren()
    }

    @Test
    fun awaitPongReturnsFalseOnTimeout() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow */ }

        val result = demux.sendPingAwaitPong(timeoutMillis = 100L, nonce = 1u.toULong())
        assertEquals(false, result, "No Pong -> should time out")

        coroutineContext.cancelChildren()
    }

    @Test
    fun awaitPongIgnoresMismatchedNonce() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow */ }

        val result = CompletableDeferred<Boolean>()
        launch {
            result.complete(
                demux.sendPingAwaitPong(timeoutMillis = 300L, nonce = 100u.toULong())
            )
        }

        // Inject a Pong with a DIFFERENT nonce -- should not unblock.
        kotlinx.coroutines.delay(50)
        demux.handleFrame(MuxFrame.Pong(nonce = 999u.toULong()))

        assertEquals(false, result.await(), "Mismatched nonce must not satisfy the wait")
        coroutineContext.cancelChildren()
    }

    /**
     * Regression test for issue #230.
     *
     * When the underlying transport dies mid-healthCheck, [MuxDemux.closeAllQuietly]
     * tears everything down. Any in-flight [MuxDemux.sendPingAwaitPong] caller must
     * observe this as a *failed* health check (no Pong arrived), not a success.
     *
     * Previously, `closeAllQuietly` completed each pending ping waiter with `Unit`
     * *before* cancelling it. Completion wins: the waiter saw success, healthCheck
     * returned true, and `EndToEndSessionTest.healthCheck fails and state flips when
     * relay is killed` flaked on the "relay is dead, check must report dead" assertion.
     */
    @Test
    fun awaitPongReturnsFalseWhenTransportClosedQuietly() = runBlocking {
        val demux = MuxDemux()
        demux.onOutgoingFrame = { /* swallow, relay is "dead" */ }

        val result = CompletableDeferred<Boolean>()
        launch {
            result.complete(
                demux.sendPingAwaitPong(timeoutMillis = 10_000L, nonce = 0xDEADu.toULong())
            )
        }

        // Give the ping coroutine a chance to register its waiter before we tear down.
        kotlinx.coroutines.delay(50)
        demux.closeAllQuietly()

        assertEquals(
            false,
            result.await(),
            "Transport-died teardown must surface as failed health check, not success"
        )
        coroutineContext.cancelChildren()
    }

    @Test
    fun rejectedOpenInvokesLogLambda() = runBlocking {
        val captured = mutableListOf<Pair<LogLevel, String>>()
        val demux = MuxDemux(log = { level, msg -> captured.add(level to msg) })
        demux.maxStreams = 1
        demux.onOutgoingFrame = { /* swallow ERROR frame */ }

        val streamReady = CompletableDeferred<MuxStreamImpl>()
        launch {
            streamReady.complete(demux.incomingStreams.first())
        }

        demux.handleFrame(MuxFrame.Open(streamId = 1u))
        streamReady.await()

        demux.handleFrame(MuxFrame.Open(streamId = 2u))

        assertEquals(1, captured.size)
        assertEquals(LogLevel.WARN, captured[0].first)
        assertTrue(captured[0].second.contains("rejecting stream 2"))

        coroutineContext.cancelChildren()
    }
}
