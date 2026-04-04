plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rousecontext.mcp.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(libs.mcp.sdk)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.io.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
