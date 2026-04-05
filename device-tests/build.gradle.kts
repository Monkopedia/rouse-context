plugins {
    alias(libs.plugins.kotlin.android) apply false
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()

    // Pass repo root to tests
    systemProperty("repo.root", rootDir.absolutePath)

    // Allow longer test timeouts for device tests
    systemProperty("device.test.timeout.ms", "120000")

    // Skip if SKIP_DEVICE_TESTS env is set
    val skipDevice = System.getenv("SKIP_DEVICE_TESTS")
    if (skipDevice != null) {
        exclude("**/*")
    }
}
