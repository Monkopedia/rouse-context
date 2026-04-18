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
}
