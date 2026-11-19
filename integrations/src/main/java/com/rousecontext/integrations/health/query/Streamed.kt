package com.rousecontext.integrations.health.query

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * One element of a bounded stream: a payload [Value], or the terminal
 * [CeilingReached] note.
 *
 * A [Flow] that simply completes cannot say *why* it completed. A consumer that
 * has to distinguish "that was everything" from "the producer stopped at a bound
 * of its own" — as a fold reporting whether its answer covers the whole range
 * must — otherwise sees two identical completions. Carrying the reason as a
 * terminal element keeps it inside the stream, so it travels through the usual
 * operators and needs no state shared between producer and consumer.
 */
sealed interface Streamed<out T> {

    /** A payload element. */
    data class Value<out T>(val value: T) : Streamed<T>

    /**
     * Terminal note: the producer stopped at a bound of its own, so the source
     * held more than this stream carried. Emitted last and only in that case —
     * a stream that ran its source dry ends without it.
     */
    data object CeilingReached : Streamed<Nothing>
}

/** Apply [mapper] to each [Streamed.Value], passing the terminal note through. */
fun <T, R> Flow<Streamed<T>>.mapValues(mapper: (T) -> R): Flow<Streamed<R>> = transform { item ->
    when (item) {
        is Streamed.Value -> emit(Streamed.Value(mapper(item.value)))
        Streamed.CeilingReached -> emit(Streamed.CeilingReached)
    }
}

/**
 * Expand each [Streamed.Value] into zero or more values via [mapper], passing
 * the terminal note through.
 *
 * A record expanding to *zero* values is exactly the case that makes the note
 * necessary: a consumer's own cap counts values, so it never trips, and only the
 * note tells it the producer stopped early.
 */
fun <T, R> Flow<Streamed<T>>.flatMapValues(mapper: (T) -> Iterable<R>): Flow<Streamed<R>> =
    transform { item ->
        when (item) {
            is Streamed.Value -> mapper(item.value).forEach { emit(Streamed.Value(it)) }
            Streamed.CeilingReached -> emit(Streamed.CeilingReached)
        }
    }
