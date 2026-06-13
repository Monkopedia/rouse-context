package com.rousecontext.app.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit rule that installs [dispatcher] as `Dispatchers.Main` for the duration
 * of a test and unconditionally restores the original Main afterwards.
 *
 * Pairing `setMain`/`resetMain` in a rule (rather than ad-hoc `@Before`/`@After`
 * methods) makes the reset bulletproof: the rule's teardown runs in a `finally`
 * around the whole test even if the body throws, so the Main dispatcher can
 * never be left overridden and leak into a subsequent test in the same JVM.
 *
 * Unit tests run in a single JVM (`testOptions.unitTests` sets no `forkEvery`),
 * so a leaked Main override surfaces as an `IllegalStateException` from
 * `kotlinx.coroutines.test`'s internal `TestMainDispatcher` in a *random later*
 * test's `setMain` — the moving-target flake tracked in issue #376.
 *
 * The rule's teardown only resets Main; it does NOT join coroutines. A test that
 * routes Main to a *real* (thread-backed) dispatcher must ensure its own
 * coroutines are cancelled and drained to quiescence in its `@After` (which runs
 * before this rule's teardown) so nothing is still dispatching on Main when the
 * reset fires.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) :
    TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                Dispatchers.setMain(dispatcher)
                try {
                    base.evaluate()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
}
