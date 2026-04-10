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

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
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
