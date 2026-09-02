package com.rousecontext.tunnel.integration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Pins the one thing [IntegrationScope] exists to do (#600): keep an uncaught
 * throw out of a background thread's `System.err`, where it would race Gradle's
 * per-test output store after a `SEPARATE_THREAD` ceiling has abandoned a test.
 *
 * Deliberately NOT `@Tag("integration")` -- it needs no relay binary, so it
 * belongs in the fast `jvmTest` tier that gates every build.
 *
 * The control matters as much as the assertion. On its own, "the handler
 * received the throwable" would not establish that anything was *diverted* -- a
 * bare scope might never have reached the default handler to begin with. So the
 * second test pins the mechanism being diverted and the first pins the
 * diversion; if the control ever goes green-by-default the first test has
 * stopped proving anything.
 */
class IntegrationCoroutineSupportTest {

    @Test
    fun `IntegrationScope records an uncaught throw instead of letting it escape`() = runBlocking {
        val reachedDefaultHandler = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> reachedDefaultHandler.countDown() }
        try {
            val integration = IntegrationScope(Dispatchers.IO)
            integration.scope.launch { error("boom from a launched coroutine") }.join()

            assertEquals(1, integration.uncaught.size, "handler should have recorded the throw")
            assertEquals(
                "boom from a launched coroutine",
                integration.uncaught.single().message
            )
            assertTrue(
                !reachedDefaultHandler.await(2, TimeUnit.SECONDS),
                "throw must NOT reach the default handler -- that is the System.err path"
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `control - a bare root scope does reach the default handler`() = runBlocking {
        val reachedDefaultHandler = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> reachedDefaultHandler.countDown() }
        try {
            CoroutineScope(Dispatchers.IO).launch { error("boom") }.join()

            assertTrue(
                reachedDefaultHandler.await(5, TimeUnit.SECONDS),
                "a bare CoroutineScope(Dispatchers.IO) is a ROOT scope, so an uncaught throw " +
                    "must reach the default handler; if this control ever fails, the hazard " +
                    "IntegrationScope guards has changed shape and the sibling test is vacuous"
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
