import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
    alias(libs.plugins.test.retry)
}

// Distribution is selected by a build flag, not a product flavor (issue #467).
// A bare build is the FOSS distribution (no secrets, buildable by anyone /
// F-Droid); `-Pgoogle` opts into the credentialed Firebase build. This encodes
// the real relationship — FOSS is the universal base, Google a credentialed
// superset — instead of the symmetric flavor framing that made the safe build
// non-default and forced task-name sniffing to re-derive "is this credentialed?".
val googleBuild = project.hasProperty("google")

// The Firebase Gradle plugins (`google-services`, `firebase-crashlytics`) are
// applied only for the Google build. The FOSS build skips them entirely so that:
//   * `google-services.json` is NOT required to assemble it, and
//   * no Play-Services / Firebase Gradle processing runs.
// Keeping the plugins behind the flag preserves the google-services plugin's
// hard-fail-on-missing-credentials — a `-Pgoogle` build with no
// `google-services.json` fails loudly rather than producing a broken APK.
if (googleBuild) {
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

    // AGP 8.1+ embeds a "Dependency metadata" block in the APK signing scheme by
    // default. F-Droid's scanner rejects APKs containing it, so drop it from both
    // APK and AAB outputs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release passwords are resolved lazily so non-release tasks (lint, tests,
    // debug builds) don't require the env vars or .properties file.
    val releaseStorePassword = System.getenv("ROUSE_RELEASE_STORE_PASSWORD")
        ?: loadFromSigningProperties("storePassword")
    val releaseKeyPassword = System.getenv("ROUSE_RELEASE_KEY_PASSWORD")
        ?: loadFromSigningProperties("keyPassword")

    // Resolves to null when the (gitignored) keystore is absent, e.g. a clean
    // F-Droid checkout.
    val releaseKeystore = file("${rootProject.projectDir}/.signing/release.keystore")
        .takeIf { it.exists() }

    // Release signing only happens when the keystore AND both passwords are
    // present (CI, env or .signing/release.properties). When any are missing —
    // a clean F-Droid checkout — the release signingConfig is left off the
    // buildType (below) and AGP builds the release UNSIGNED rather than
    // aborting. F-Droid then verifies the unsigned APK reproduces our published
    // signed asset and ships our signature via the reproducible-builds path
    // (see fdroid/com.rousecontext.yml). Mirrors health-disconnect's proven
    // F-Droid setup; see issue #465.
    val canSignRelease = releaseKeystore != null &&
        releaseStorePassword != null &&
        releaseKeyPassword != null

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
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

    // When release signing credentials are absent (env vars and
    // .signing/release.properties both missing), storePassword/keyPassword
    // resolve to null above and AGP builds the release UNSIGNED rather than
    // hard-failing. This mirrors health-disconnect's proven F-Droid setup: a
    // clean F-Droid checkout has neither the (gitignored) keystore nor the
    // passwords, so it builds the unsigned APK, verifies it reproduces our
    // published signed asset, and ships our signature via the reproducible-builds
    // path. A build WITH credentials (CI, env or .signing/release.properties)
    // still produces a SIGNED APK. See issue #465.

    defaultConfig {
        applicationId = "com.rousecontext"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"

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

    // Distribution code is selected by the `-Pgoogle` flag (issue #467) instead
    // of product-flavor source sets. The FOSS sources are the default; `-Pgoogle`
    // swaps in the Firebase-backed sources. The directories themselves are
    // unchanged from the former `google`/`foss` flavor source sets — they are
    // just no longer registered as flavor source sets. Both distributions ship
    // the same `applicationId` (com.rousecontext); the FOSS build is the same
    // app, Firebase-free, not a separate package.
    //
    // The matching `test{Google,Foss}` source sets carry the distribution's
    // unit tests (Firebase-backed vs ACRA/keypair), selected the same way so the
    // tests only compile/run against the classpath that has their deps.
    sourceSets["main"].java.srcDir(
        if (googleBuild) "src/google/java" else "src/foss/java"
    )
    sourceSets["test"].java.srcDir(
        if (googleBuild) "src/testGoogle/java" else "src/testFoss/java"
    )

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            // No mapping upload in debug — the Crashlytics plugin runs even here
            // (under `-Pgoogle`) because the library is linked in that build. We
            // still skip the slow symbol upload work to keep debug assembly fast
            // for iteration. Guarded so FOSS builds, which never apply the
            // Crashlytics plugin, don't reference a missing extension.
            if (googleBuild) {
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    mappingFileUploadEnabled = false
                    nativeSymbolUploadEnabled = false
                }
            }
        }
        release {
            // Only sign when credentials are present; otherwise build unsigned
            // (the F-Droid reproducible-builds path). See canSignRelease above.
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // AGP embeds VCS/commit info in the release APK by default, which is
            // non-reproducible. Drop it so F-Droid reproducible builds succeed.
            vcsInfo.include = false
            // Release builds ship R8-obfuscated code; upload the mapping so
            // Crashlytics deobfuscates stacks. We have no NDK code, so native
            // symbol upload stays off. Guarded as above for FOSS builds.
            if (googleBuild) {
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

// Overlay the distribution-specific manifest (FcmReceiver for Google,
// UnifiedPushReceiver for FOSS) by flag, using AGP's Variant Sources API
// (issue #467). `addStaticManifestFile` feeds the overlay into the standard
// manifest merger as a highest-priority source — no module split, no
// duplication of the shared manifest body. Path is relative to the :app dir.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.manifests.addStaticManifestFile(
            if (googleBuild) "src/google/AndroidManifest.xml" else "src/foss/AndroidManifest.xml"
        )
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

    // Distribution-specific dependencies, selected by the `-Pgoogle` flag
    // (issue #467). The Google build links Firebase (FCM wake + Crashlytics);
    // the FOSS build links UnifiedPush (distributor-based wake) + ACRA (crash
    // reporting via the relay's `POST /crash`) and zero firebase-* artifacts.
    // Both distributions sit behind the same distribution-agnostic Koin seams
    // (BackgroundDelivery, CrashReporter, device-identity providers — see
    // app/src/{google,foss}).
    if (googleBuild) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.auth)
        implementation(libs.firebase.messaging)
        implementation(libs.firebase.crashlytics)
    } else {
        implementation(libs.unifiedpush.connector)
        implementation(libs.acra.core)
        implementation(libs.acra.http)
    }

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
// They are excluded from the default `testDebugUnitTest` run (CI's "unit
// tests" step stays fast) and fronted by a dedicated `:app:integrationTest`
// task so CI can gate them separately. Both tasks are Android `Test` tasks
// on the debug variant, so Kover's auto-discovery picks them up for the
// aggregated coverage report (issue #248). With the distribution flag (issue
// #467) `integrationTest` covers whichever distribution is selected — CI runs
// it once bare (FOSS, incl. the keypair round-trip from src/testFoss) and once
// with `-Pgoogle` (Firebase-backed), so both are exercised end-to-end.
private val integrationPackage = "com.rousecontext.app.integration"

tasks.withType<Test>().configureEach {
    // Cheap backstop for the stubborn single-JVM `TestMainDispatcher` leak flake
    // (#376): retry only the FAILED tests in a fresh JVM, so an intermittent
    // leak-induced failure passes on the isolated retry while a real
    // deterministic failure still fails every attempt (no masking). Only kicks
    // in on failure, so the green path is unaffected — far cheaper than the ~7x
    // `forkEvery = 1` isolation tax.
    retry {
        maxRetries.set(2)
        failOnPassedAfterRetry.set(false)
    }
    // The fast `testDebugUnitTest` run stays lean by excluding the slow
    // relay-backed integration scenarios, which run via `integrationTest`.
    if (name == "testDebugUnitTest") {
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

    // Reuse everything about `testDebugUnitTest` (Robolectric runtime, compiled
    // test classes, android resources, JVM args) — we just want a different
    // filter applied to the same test task classpath. The selected distribution
    // (issue #467) determines which `DistributionModule` and distribution test
    // source set are live: bare = FOSS (keypair device identity, zero Firebase,
    // incl. the keypair round-trip from src/testFoss); `-Pgoogle` = Firebase.
    val source = tasks.named<Test>("testDebugUnitTest").get()
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

// Disable baseline profile ArtProfile tasks. Compose pulls in profileinstaller
// transitively, which adds non-deterministic baseline.prof generation that
// breaks F-Droid reproducible builds.
tasks.matching { it.name.contains("ArtProfile") }.configureEach {
    enabled = false
}
