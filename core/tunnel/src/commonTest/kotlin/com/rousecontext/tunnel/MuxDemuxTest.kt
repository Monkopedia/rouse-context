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
}
