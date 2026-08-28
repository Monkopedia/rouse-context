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
// that inherit the JVM default zone — `SimpleDateFormat(pattern,
// Locale.getDefault())` and `ZoneId.systemDefault()`. Device-local time is the
// correct product behaviour there and those formatters are deliberately left
// alone, but it makes a recorded golden host-dependent: the same tree recorded
// in `America/New_York` renders `06:40` where a UTC CI runner renders `10:40`,
// so `verifyRoborazziDebug` fails for reasons unrelated to UI correctness.
// The goldens are deterministic per host and host-dependent across hosts, which
// is why re-recording never fixed it — see #633.
//
// Pinned as a JVM system property rather than a per-suite JUnit rule on
// purpose. The affected formatters are statics (`DETAIL_TIMESTAMP_FORMAT` in
// `AuditDetailScreen.kt`, `TIME_FORMAT` in `AuditHistoryViewModel`'s companion)
// and `SimpleDateFormat` captures the default zone at *construction*, i.e. at
// class-initialization time. A rule setting `TimeZone.setDefault` from `@Before`
// runs after those classes may already have been initialized by an earlier test
// in the same JVM, so the pin would silently depend on test ordering.
// `-Duser.timezone` is in effect before any class loads, so it cannot be
// out-ordered — and it cannot be forgotten by the next screenshot test either.
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
