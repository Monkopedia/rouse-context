import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
}

// The Firebase Gradle plugins (`google-services`, `firebase-crashlytics`) are
// applied only when the `google` product flavor is part of the build. A
// foss-only build skips them entirely so that:
//   * `google-services.json` is NOT required to assemble the foss variant, and
//   * no Play-Services / Firebase Gradle processing runs for foss.
// Detection is by requested task name (CI invokes the flavored tasks directly,
// e.g. `assembleFossDebug` / `assembleGoogleDebug`). A build that mentions
// neither flavor (e.g. `ktlintCheck`, bare `assemble`) keeps the plugins, which
// matches the pre-flavor behaviour. See issue #461.
val requestedTaskNames = gradle.startParameter.taskNames
val isFossOnlyBuild = requestedTaskNames.isNotEmpty() &&
    requestedTaskNames.any { it.contains("Foss", ignoreCase = true) } &&
    requestedTaskNames.none { it.contains("Google", ignoreCase = true) }
val applyFirebasePlugins = !isFossOnlyBuild
if (applyFirebasePlugins) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Loads a value from `.signing/release.properties` if that file exists.
// The file is gitignored; see `.signing/release.properties.example` for format.
val releaseSigningProps: Properties? = run {
    val f = rootProject.file(".signing/release.properties")
    if (f.exists()) {
        Properties().apply { f.inputStream().use { load(it) } }
    } else {
        null
    }
}

fun loadFromSigningProperties(key: String): String? = releaseSigningProps?.getProperty(key)

android {
    namespace = "com.rousecontext.app"
    compileSdk = 36

    // Release passwords are resolved lazily so non-release tasks (lint, tests,
    // debug builds) don't require the env vars or .properties file.
    val releaseStorePassword = System.getenv("ROUSE_RELEASE_STORE_PASSWORD")
        ?: loadFromSigningProperties("storePassword")
    val releaseKeyPassword = System.getenv("ROUSE_RELEASE_KEY_PASSWORD")
        ?: loadFromSigningProperties("keyPassword")

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/.signing/release.keystore")
            storePassword = releaseStorePassword
            keyAlias = "release"
            keyPassword = releaseKeyPassword
        }
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/.signing/debug.keystore")
            storePassword = "rousecontext"
            keyAlias = "debug"
            keyPassword = "rousecontext"
        }
    }

    // Fail fast on release builds if passwords are missing, with a clear message.
    // Restricted to the exact signing-triggering tasks so unrelated Gradle targets
    // (e.g. `koverHtmlReport`, which transitively schedules `packageReleaseResources`
    // and `packageReleaseUnitTestForUnitTest`) don't spuriously require the keys.
    gradle.taskGraph.whenReady {
        val releaseSigningTasks = setOf(
            "assembleGoogleRelease",
            "packageGoogleRelease",
            "bundleGoogleRelease",
            "assembleFossRelease",
            "packageFossRelease",
            "bundleFossRelease"
        )
        val needsReleaseSigning = allTasks.any { task ->
            task.project == project && task.name in releaseSigningTasks
        }
        if (needsReleaseSigning) {
            check(releaseStorePassword != null) {
                "Release signing requires ROUSE_RELEASE_STORE_PASSWORD env var " +
                    "or storePassword in .signing/release.properties " +
                    "(see .signing/release.properties.example)."
            }
            check(releaseKeyPassword != null) {
                "Release signing requires ROUSE_RELEASE_KEY_PASSWORD env var " +
                    "or keyPassword in .signing/release.properties " +
                    "(see .signing/release.properties.example)."
            }
        }
    }

    defaultConfig {
        applicationId = "com.rousecontext"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Base domain. Override with `-Pdomain=yourdomain.example` for self-hosting forks.
        // All relay/device/OAuth hostnames derive from this.
        val baseDomain = project.findProperty("domain")?.toString() ?: "rousecontext.com"
        // Relay host defaults to "relay.{baseDomain}" but can be overridden directly.
        val relayHost = project.findProperty("relay.host")?.toString() ?: "relay.$baseDomain"
        val relayPort = project.findProperty("relay.port")?.toString() ?: "443"
        val relayScheme = project.findProperty("relay.scheme")?.toString() ?: "wss"
        buildConfigField("String", "BASE_DOMAIN", "\"$baseDomain\"")
        buildConfigField("String", "RELAY_HOST", "\"$relayHost\"")
        buildConfigField("int", "RELAY_PORT", "$relayPort")
        buildConfigField("String", "RELAY_SCHEME", "\"$relayScheme\"")
    }

    // Distribution flavor split (issue #461). `google` is the unchanged
    // Firebase/FCM/Crashlytics build; `foss` compiles with no Firebase /
    // google-services / Play-Services dependencies. Both flavors ship the same
    // `applicationId` (com.rousecontext) — the foss build is the same app,
    // Firebase-free, not a separate package.
    flavorDimensions += "distribution"
    productFlavors {
        create("google") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            // No mapping upload in debug — the Crashlytics plugin runs even here
            // (for the `google` flavor) because the library is linked in that
            // variant. We still skip the slow symbol upload work to keep debug
            // assembly fast for iteration. Guarded so foss-only builds, which
            // never apply the Crashlytics plugin, don't reference a missing
            // extension.
            if (applyFirebasePlugins) {
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    mappingFileUploadEnabled = false
                    nativeSymbolUploadEnabled = false
                }
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release builds ship R8-obfuscated code; upload the mapping so
            // Crashlytics deobfuscates stacks. We have no NDK code, so native
            // symbol upload stays off. Guarded as above for foss-only builds.
            if (applyFirebasePlugins) {
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    mappingFileUploadEnabled = true
                    nativeSymbolUploadEnabled = false
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
}

dependencies {
    implementation(project(":core:tunnel"))
    implementation(project(":core:mcp"))
    implementation(project(":core:bridge"))
    implementation(project(":api"))
    implementation(project(":integrations"))
    implementation(project(":notifications"))
    implementation(project(":work"))
    implementation(libs.workmanager)

    // OkHttp (mTLS WebSocket client for tunnel — JSSE properly presents client certs)
    implementation(libs.okhttp)

    implementation(libs.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)

    // Koin
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Firebase — scoped to the `google` flavor only (issue #461). The `foss`
    // flavor compiles with zero firebase-* dependencies; its Koin seams bind
    // NoOp/stub implementations instead (see app/src/foss).
    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.auth)
    "googleImplementation"(libs.firebase.messaging)
    "googleImplementation"(libs.firebase.crashlytics)

    // Health Connect (for HealthConnectIntegration availability check)
    implementation(libs.health.connect)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp)
    // WorkManager test helpers — `WorkManagerTestInitHelper`, `SynchronousExecutor`,
    // `TestDriver`. Used by the integration-tier scheduling test for
    // [com.rousecontext.work.CertRenewalWorker] (issue #277) to drive periodic
    // / one-time work through to completion without real time passing.
    testImplementation(libs.workmanager.testing)
    // Ktor client used by the integration-test harness to speak to the fixture
    // relay (mirrors the production `createMtlsRelayHttpClient` setup so the
    // real RelayApiClient singleton can run end-to-end against loopback).
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.kotlinx.serialization.json)
    // Shared fixture for integration-tier tests that boot the real Rust relay
    // binary. Hosts `TestRelayFixture`, originally from `:core:tunnel:jvmTest`,
    // now extracted so `:app` integration tests (issue #250) can use it without
    // pulling in `:core:tunnel`'s unrelated JUnit5 integration classes.
    testImplementation(project(":core:testfixtures"))
    // The fixture uses `kotlin.test.fail`; surface it here so the integration
    // smoke tests can pattern-match against the same failure messages.
    testImplementation(kotlin("test"))
    // BouncyCastle JSSE — test-scope only. See the comment in
    // `gradle/libs.versions.toml` / issue #262. `AppIntegrationTestHarness`
    // registers the provider at position 1 so synthetic AI-client
    // `SSLSocket`s correctly emit SNI (Conscrypt, Robolectric's default,
    // silently omits it).
    testImplementation(libs.bouncycastle.jsse)
    testImplementation(libs.bouncycastle.prov)
}

// Integration-tier tests that boot the real relay binary and exercise the
// full Koin graph (umbrella issue #247, scaffold issue #250). These tests
// live in the standard Android unit-test source set under the
// `com.rousecontext.app.integration` package but take tens of seconds to run
// and depend on `relay/target/debug/rouse-relay` being built with
// `--features test-mode`.
//
// They are excluded from the default `testGoogleDebugUnitTest` run (CI's "unit
// tests" step stays fast) and fronted by a dedicated `:app:integrationTest`
// task so CI can gate them separately. Both tasks are Android `Test` tasks
// on the debug variant, so Kover's auto-discovery picks them up for the
// aggregated coverage report (issue #248).
private val integrationPackage = "com.rousecontext.app.integration"

tasks.withType<Test>().configureEach {
    // Per-flavor debug unit-test tasks (testGoogleDebugUnitTest /
    // testFossDebugUnitTest) keep the fast unit-test run lean by excluding the
    // slow relay-backed integration scenarios, which run via `integrationTest`.
    if (name == "testGoogleDebugUnitTest" || name == "testFossDebugUnitTest") {
        filter {
            excludeTestsMatching("$integrationPackage.*")
            isFailOnNoMatchingTests = false
        }
    }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description =
        "Runs `$integrationPackage.*` tests against the real relay binary. " +
        "Requires `cd relay && cargo build --features test-mode` first."

    // Reuse everything about `testGoogleDebugUnitTest` (Robolectric runtime,
    // compiled test classes, android resources, JVM args) — we just want a
    // different filter applied to the same test task classpath. The integration
    // scenarios exercise the Firebase-backed `google` variant.
    val source = tasks.named<Test>("testGoogleDebugUnitTest").get()
    testClassesDirs = source.testClassesDirs
    classpath = source.classpath
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    // Mirror Robolectric graphics config from `testOptions.unitTests.all`.
    systemProperty("robolectric.graphicsMode", "NATIVE")

    filter {
        includeTestsMatching("$integrationPackage.*")
        isFailOnNoMatchingTests = false
    }

    useJUnit()

    // Integration tests are I/O-heavy (relay subprocess + real TLS + cert
    // generation) so fail if something hangs instead of wedging CI.
    timeout.set(Duration.ofMinutes(10L))
}

tasks.register<Test>("fossIntegrationTest") {
    group = "verification"
    description =
        "Runs `$integrationPackage.*` tests against the real relay binary under the " +
        "FOSS variant (keypair device identity, zero Firebase). " +
        "Requires `cd relay && cargo build --features test-mode` first."

    // Mirror `integrationTest`, but reuse `testFossDebugUnitTest` so the FOSS
    // `distributionModule` (KeypairDeviceCredentialProvider /
    // KeypairRenewalAuthProvider) is the flavor module live in the harness
    // (issue #469). The keypair round-trip scenario lives in the
    // `app/src/testFoss` source set so it only compiles/runs under this
    // classpath, never under the google `integrationTest` above.
    val source = tasks.named<Test>("testFossDebugUnitTest").get()
    testClassesDirs = source.testClassesDirs
    classpath = source.classpath
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
    // Mirror Robolectric graphics config from `testOptions.unitTests.all`.
    systemProperty("robolectric.graphicsMode", "NATIVE")

    filter {
        includeTestsMatching("$integrationPackage.*")
        isFailOnNoMatchingTests = false
    }

    useJUnit()

    // Same hang-guard as `integrationTest`.
    timeout.set(Duration.ofMinutes(10L))
}
