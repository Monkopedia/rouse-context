plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(libs.mcp.sdk)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.io.core)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.test.host)
        }
    }
}
