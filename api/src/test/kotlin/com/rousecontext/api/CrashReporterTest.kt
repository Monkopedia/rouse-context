package com.rousecontext.api

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Sanity checks for the [CrashReporter.NoOp] fallback. The NoOp is used both
 * as a default constructor argument in several feature-module classes and as
 * a safety net inside `CertRenewalWorker` when Koin isn't started
 * (unit-test path), so it must stay stable and callable without side effects.
 */
class CrashReporterTest {

    @Test
    fun `NoOp swallows exceptions without throwing`() {
        CrashReporter.NoOp.logCaughtException(RuntimeException("should not crash"))
        CrashReporter.NoOp.log("breadcrumb")
        CrashReporter.NoOp.setCollectionEnabled(true)
        CrashReporter.NoOp.setCollectionEnabled(false)
    }

    @Test
    fun `NoOp is a shared singleton`() {
        assertSame(CrashReporter.NoOp, CrashReporter.NoOp)
    }
}
