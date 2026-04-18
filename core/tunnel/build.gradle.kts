plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    @Suppress("OPT_IN_USAGE")
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Pass repo root to JVM tests so integration tests can find the relay binary
    tasks.named<Test>("jvmTest") {
        systemProperty("repo.root", rootProject.projectDir.absolutePath)
        useJUnitPlatform {
            excludeTags("integration")
        }
    }

    // Runs only the `@Tag("integration")` tests. Each test uses
    // `Assumptions.assumeTrue` to skip gracefully when the real Rust relay
    // binary is absent, so this task degrades to skips rather than hard
    // failures on machines where `relay/target/debug/rouse-relay` hasn't
    // been built (see relay/README.md for the build command).
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Runs @Tag(\"integration\") tests against the real relay binary."
        val jvmTest = tasks.named<Test>("jvmTest").get()
        testClassesDirs = jvmTest.testClassesDirs
        classpath = jvmTest.classpath
        systemProperty("repo.root", rootProject.projectDir.absolutePath)
        useJUnitPlatform {
            includeTags("integration")
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.ktor.client.cio)
                // OkHttp engine for relay REST mTLS (issue #237). Ktor CIO
                // has historically dropped client certs; OkHttp's JSSE TLS
                // stack presents them correctly.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.okhttp)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.junit.jupiter.api)
                runtimeOnly(libs.junit.jupiter.engine)
                // Integration test: MCP and bridge modules for end-to-end tests
                implementation(project(":core:mcp"))
                implementation(project(":core:bridge"))
            }
        }
    }
}
