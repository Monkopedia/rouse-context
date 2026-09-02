package com.rousecontext.app.cert

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #662: pins the property that makes `OnboardingFlow`'s two-write
 * sequence (`storeSubdomain` then `storeIntegrationSecrets`) safe to run
 * without a cancellation rollback.
 *
 * Both methods are declared `suspend` on `CertificateStore`, but
 * [FileCertificateStore] delegates them to a non-suspend `atomicWrite` and has
 * no `withContext` anywhere, so there is no suspension point *between* the two
 * writes and therefore no way for cancellation to land between them.
 *
 * That safety lives in this file, while the code that depends on it lives in
 * `OnboardingFlow` (`:core:tunnel`) -- and `OnboardingFlow` does not self-heal:
 * its onboarded gate is `getSubdomain() != null`, so a device with a subdomain
 * and no secrets stays that way. Moving `atomicWrite` onto `Dispatchers.IO`
 * would be an entirely ordinary refactor that silently opens that window, so
 * this test fails if it happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.rousecontext.app.TestApplication::class)
class OnboardingHalfWriteWindowTest {

    @Test
    fun `an already-cancelled caller still lands both onboarding writes`() = runBlocking {
        val store = FileCertificateStore(ApplicationProvider.getApplicationContext<Application>())
        val secrets = mapOf("health" to "brisk-health")
        var reachedEnd = false

        val scope = CoroutineScope(Job())
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Cancel ourselves before the pair runs. Cancellation is only
            // *delivered* at a real suspension point, so with a non-suspend
            // atomicWrite both writes must still complete. If either store
            // method gains one (a `withContext`, a `delay`, an actual async
            // hop), the first write throws here and the second never happens --
            // exactly the half-onboarded state OnboardingFlow cannot repair.
            coroutineContext.cancel()
            store.storeSubdomain("device")
            store.storeIntegrationSecrets(secrets)
            reachedEnd = true
        }
        job.join()

        assertTrue(
            "OnboardingFlow's store sequence no longer runs to completion under " +
                "cancellation: FileCertificateStore gained a suspension point. " +
                "Wrap the pair in withContext(NonCancellable) or add a repair " +
                "path for 'subdomain present, secrets absent' (see #662).",
            reachedEnd
        )
        assertEquals("device", store.getSubdomain())
        assertEquals(secrets, store.getIntegrationSecrets())
    }
}
