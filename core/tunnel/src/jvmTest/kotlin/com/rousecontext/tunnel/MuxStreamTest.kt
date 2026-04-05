package com.rousecontext.tunnel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MuxStreamTest {

    @Test
    fun sendProducesDataFrame() = runBlocking {
        val sent = CompletableDeferred<MuxFrame>()
        val stream = MuxStreamImpl(id = 1u) { frame -> sent.complete(frame) }

        stream.send(byteArrayOf(0x01, 0x02))

        val frame = sent.await() as MuxFrame.Data
        assertEquals(1u, frame.streamId)
        assertContentEquals(byteArrayOf(0x01, 0x02), frame.payload)

        coroutineContext.cancelChildren()
    }

    @Test
    fun closeProducesCloseFrame() = runBlocking {
        val sent = CompletableDeferred<MuxFrame>()
        val stream = MuxStreamImpl(id = 5u) { frame -> sent.complete(frame) }

        stream.close()

        val frame = sent.await() as MuxFrame.Close
        assertEquals(5u, frame.streamId)

        coroutineContext.cancelChildren()
    }

    @Test
    fun closeSetsIsClosed() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }

        assertFalse(stream.isClosed)
        stream.close()
        assertTrue(stream.isClosed)
    }

    @Test
    fun receiveDataDeliversToIncoming() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }

        val dataReady = CompletableDeferred<ByteArray>()
        launch {
            dataReady.complete(stream.incoming.first())
        }

        stream.receiveData(byteArrayOf(0xAA.toByte()))

        assertContentEquals(byteArrayOf(0xAA.toByte()), dataReady.await())

        coroutineContext.cancelChildren()
    }

    @Test
    fun receiveCloseClosesStream() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }

        assertFalse(stream.isClosed)
        stream.receiveClose()
        assertTrue(stream.isClosed)
    }

    @Test
    fun receiveErrorClosesStream() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }

        assertFalse(stream.isClosed)
        stream.receiveError(MuxErrorCode.STREAM_RESET, "reset")
        assertTrue(stream.isClosed)
    }

    @Test
    fun multipleDataChunksArriveInOrder() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }

        val chunks = CompletableDeferred<List<ByteArray>>()
        launch {
            chunks.complete(stream.incoming.take(3).toList())
        }

        stream.receiveData(byteArrayOf(0x01))
        stream.receiveData(byteArrayOf(0x02))
        stream.receiveData(byteArrayOf(0x03))

        val result = chunks.await()
        assertEquals(3, result.size)
        assertContentEquals(byteArrayOf(0x01), result[0])
        assertContentEquals(byteArrayOf(0x02), result[1])
        assertContentEquals(byteArrayOf(0x03), result[2])

        coroutineContext.cancelChildren()
    }

    @Test
    fun concurrentWritesAllProduceFrames() = runBlocking {
        val frames = mutableListOf<MuxFrame>()
        val allSent = CompletableDeferred<Unit>()
        val stream = MuxStreamImpl(id = 1u) { frame ->
            synchronized(frames) { frames.add(frame) }
            if (synchronized(frames) { frames.size } == 3) {
                allSent.complete(Unit)
            }
        }

        launch { stream.send(byteArrayOf(0x01)) }
        launch { stream.send(byteArrayOf(0x02)) }
        launch { stream.send(byteArrayOf(0x03)) }

        allSent.await()

        assertEquals(3, frames.size)
        assertTrue(frames.all { it is MuxFrame.Data })

        coroutineContext.cancelChildren()
    }

    @Test
    fun sendAfterCloseThrows() = runBlocking {
        val stream = MuxStreamImpl(id = 1u) { }
        stream.close()

        var threw = false
        try {
            stream.send(byteArrayOf(0x01))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "Expected IllegalStateException when sending on closed stream")
    }
}
