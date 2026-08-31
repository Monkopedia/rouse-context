import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.kover)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        android.set(true)
        outputColorName.set("RED")
    }
}

// Pin the default timezone of every test JVM (issue #633).
//
// Screenshot goldens render wall-clock timestamps through *display* formatters
// that follow the JVM default zone (`DisplayDateFormat`, which reads
// `ZoneId.systemDefault()` and `Locale.getDefault()` on every call). Device-local
// time is the correct product behaviour there and is deliberately preserved, but
// it makes a recorded golden host-dependent: the same tree recorded in
// `America/New_York` renders `06:40` where a UTC CI runner renders `10:40`,
// so `verifyRoborazziDebug` fails for reasons unrelated to UI correctness.
// The goldens are deterministic per host and host-dependent across hosts, which
// is why re-recording never fixed it — see #633.
//
// Pinned as a JVM system property rather than a per-suite JUnit rule on purpose.
// `-Duser.timezone` is in effect before any class loads and applies to every
// test task in the tree, so it cannot be out-ordered by class initialization and
// it cannot be forgotten by the next screenshot test either. That ordering point
// used to be load-bearing: until #635 the affected formatters were
// `SimpleDateFormat` statics that captured the default zone at *construction*,
// so a `TimeZone.setDefault` from `@Before` could run after an earlier test in
// the same JVM had already initialized them. #635 replaced them with immutable
// `DateTimeFormatter`s that resolve zone and locale per call, so the ordering
// hazard is gone — but the pin is not redundant, because the goldens still
// render device-local time and would still track the recorder's host without it.
//
// Applied to every test task in every module rather than only the screenshot
// suites: CI already runs the whole suite under UTC and it is green, so this
// adds no new failure mode — it only makes every other host reproduce CI. A
// test that genuinely needs a particular zone can still set one explicitly
// (`TimeZone.setDefault`, Robolectric `@Config`); what no test may rely on is
// the *host's* ambient zone, which is precisely the defect being closed.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("user.timezone", "UTC")

        // Make a failing test's MESSAGE reach the job log (issue #641).
        //
        // `testLogging.exceptionFormat` was unset repo-wide, so Gradle's
        // default `SHORT` applied and every CI failure logged
        //
        //     java.lang.AssertionError at SomeTest.kt:14
        //
        // -- the file and line, and nothing about what was actually asserted.
        // `FULL` logs the exception's message and its stack trace instead.
        //
        // Why the log rather than (only) the XML artifact #631 added: the job
        // log is the ONE record that survives every failure mode. Gradle writes
        // no XML, no HTML and no `results.bin` for a Test task killed by its own
        // `timeout.set(...)` -- the actual cause of run 33135432024's
        // undiagnosable `:app:integrationTest` failures -- and artifacts expire
        // while logs stay with the run.
        //
        // Configured HERE, on `tasks.withType<Test>` in every subproject,
        // because the test tasks are created in three different places and no
        // single one of them reaches all three: AGP's variant unit tests
        // (`testDebugUnitTest`), the Kotlin JVM plugin's `jvmTest`, and the
        // hand-registered `integrationTest` tasks in `:app` and `:core:tunnel`.
        // `android.testOptions.unitTests.all` reaches only the first -- the same
        // trap `robolectric.graphicsMode` and #633's timezone pin above both had
        // to work around by hand.
        //
        // `events(FAILED)` is Gradle's own default at the lifecycle log level
        // and is restated only so the exception format has something to attach
        // to; nothing is logged for a passing or skipped test, so a green run's
        // log volume is unchanged.
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStackTraces = true
            showCauses = true
        }
    }
}

// Kover aggregation. The root project collects coverage from every production
// module listed below; each module also applies the plugin (see their
// build.gradle.kts) so Kover can instrument their test source sets. This
// includes all current JVM / Android unit test source sets plus the
// `:core:tunnel:integrationTest` task, which exercises real relay code paths
// (e.g. `LazyMtlsKeyManager.chooseClientAlias`) that unit tests don't cover.
//
// No `koverVerify` rule — report-only for now per issue #248.
val coveredProjects = listOf(
    ":app",
    ":core:tunnel",
    ":core:mcp",
    ":core:bridge",
    ":api",
    ":notifications",
    ":work",
    ":integrations",
)

dependencies {
    coveredProjects.forEach { path ->
        kover(project(path))
    }
}

// Exclude Android `testReleaseUnitTest` tasks from Kover. CI only runs debug
// unit tests (release variant tests have never been configured to work, e.g.
// Roborazzi screenshot tests in :notifications), so Kover's default behaviour
// of triggering both debug + release unit test tasks would fail. The debug
// variant's instrumented test results are sufficient since production code is
// identical between variants for coverage purposes.
subprojects {
    plugins.withId("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension>("kover") {
            currentProject {
                instrumentation {
                    // Release-variant unit tests have never been configured to
                    // work (e.g. Roborazzi screenshot tests). Every module —
                    // including `:app`, now that distribution is a flag rather
                    // than a flavor (#467) — exposes the plain
                    // `testReleaseUnitTest` task again, so a single exclusion
                    // covers them all.
                    disabledForTestTasks.add("testReleaseUnitTest")
                }
            }
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                // Generated Android build metadata
                classes("*.BuildConfig")
                classes("*.BuildConfig\$*")
                // Compose singletons + lambda classes generated by the compose compiler
                classes("*ComposableSingletons*")
                classes("*_ComposableKt*")
                classes("*.*\$ComposeLambda*")
                // kotlinx.serialization generated serializers/companions
                classes("*\$\$serializer")
                classes("*\$\$serializer\$*")
                // Hilt / Koin generated glue (future-proof; Hilt isn't wired today)
                classes("*_HiltModules*")
                classes("Hilt_*")
                classes("*_Factory")
                classes("*_Factory\$*")
                classes("*_MembersInjector")
                classes("*_MembersInjector\$*")
                classes("*ComponentManager*")
                // Room generated DAOs / databases
                classes("*_Impl")
                classes("*_Impl\$*")
                // Debug-only ContentProvider exposing adb-driven test hooks. Built
                // only in debug variants; not exercised by unit/integration tests.
                classes("com.rousecontext.app.testing.*")
                // `debug` flavor source set — debug-only receivers, Koin modules,
                // and stubs. Not present in release builds.
                classes("com.rousecontext.app.debug.*")
                // Compose screen destination glue. Testing requires an Espresso /
                // Paparazzi / Roborazzi harness, out of scope for JVM-tier coverage.
                classes("com.rousecontext.app.ui.navigation.destinations.*")
                // Trivial Firebase static-call wrappers introduced solely to make
                // callers testable by hiding FirebaseAuth / FirebaseMessaging
                // singletons behind interfaces. The wrappers themselves are the
                // Firebase boundary and can't be meaningfully tested at the JVM tier.
                classes("com.rousecontext.app.auth.Firebase*")
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/config/detekt/baseline.xml")
    source.setFrom(
        subprojects.map { "${it.projectDir}/src" }
    )
}
