package com.rousecontext.tunnel

import kotlin.coroutines.cancellation.CancellationException

/**
 * Composite [CtLogFetcher] that tries [primary] (crt.sh) first and falls back to
 * [fallback] (Certspotter) on any failure. crt.sh frequently returns 502/503 for
 * minutes at a time -- longer than [HttpCtLogFetcher]'s retry window -- which used
 * to surface a false "Could not reach CT log service" warning even though the
 * certificate set was unchanged. Querying a second, independent CT source on
 * primary failure turns those crt.sh outages into silent successful checks while
 * preserving real security coverage.
 *
 * If BOTH sources fail, the PRIMARY's exception is rethrown so the monitor's
 * existing "Could not reach CT log service: <crt.sh message>" warning text stays
 * meaningful. Coroutine cancellation is never swallowed.
 */
class CompositeCtLogFetcher(private val primary: CtLogFetcher, private val fallback: CtLogFetcher) :
    CtLogFetcher {

    override suspend fun fetch(domain: String): String = try {
        primary.fetch(domain)
    } catch (e: CancellationException) {
        throw e
    } catch (primaryError: Exception) {
        try {
            fallback.fetch(domain)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Both sources failed: surface the primary (crt.sh) error so the
            // monitor's warning wording stays accurate.
            throw primaryError
        }
    }
}
