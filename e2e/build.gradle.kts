plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // E2E tests are run on-demand only, not as part of normal builds
    enabled = false
    useJUnitPlatform()
}

// Run with: ./gradlew :e2e:e2eTest -Dadb.host=<your-dev-host> [-Dadb.serial=<your-device-serial>]
tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Run end-to-end MCP tests against a real device"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()

    // Fail fast if adb.host is not provided. The e2e tests need to talk to a real
    // device over adb; there is no sensible default. See README for usage.
    doFirst {
        val adbHost = System.getProperty("adb.host", "")
        if (adbHost.isBlank()) {
            throw GradleException(
                "adb.host system property is required. " +
                    "Example: ./gradlew :e2e:e2eTest -Dadb.host=<your-dev-host> " +
                    "[-Dadb.serial=<your-device-serial>]"
            )
        }
    }

    // Pass device URL via system property
    // ./gradlew :e2e:e2eTest -Dmcp.url=https://foo-test.device.rousecontext.com/mcp
    systemProperty("mcp.url", System.getProperty("mcp.url", ""))
    systemProperty("mcp.integration", System.getProperty("mcp.integration", "test"))
    systemProperty("adb.host", System.getProperty("adb.host", ""))
    systemProperty("adb.serial", System.getProperty("adb.serial", ""))
}
