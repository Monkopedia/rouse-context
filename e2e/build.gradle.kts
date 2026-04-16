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

// Run with: ./gradlew :e2e:e2eTest
tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Run end-to-end MCP tests against a real device"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()

    // Pass device URL via system property
    // ./gradlew :e2e:e2eTest -Dmcp.url=https://foo-test.device.rousecontext.com/mcp
    systemProperty("mcp.url", System.getProperty("mcp.url", ""))
    systemProperty("mcp.integration", System.getProperty("mcp.integration", "test"))
    systemProperty("adb.host", System.getProperty("adb.host", "adolin.lan"))
    systemProperty("adb.serial", System.getProperty("adb.serial", ""))
}
