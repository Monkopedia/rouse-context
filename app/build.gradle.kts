import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kover)
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
            "assembleRelease",
            "packageRelease",
            "bundleRelease"
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
        versionCode = 1
        versionName = "0.1.0"

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

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)

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
// aggregated coverage report (issue #248).
private val integrationPackage = "com.rousecontext.app.integration"

tasks.withType<Test>().configureEach {
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

    // Reuse everything about `testDebugUnitTest` (Robolectric runtime,
    // compiled test classes, android resources, JVM args) — we just want a
    // different filter applied to the same test task classpath.
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
