plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rousecontext.api"
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
    implementation(project(":core:mcp"))

    implementation(libs.androidx.core.ktx)
<<<<<<< HEAD:api/build.gradle.kts
    implementation(libs.kotlinx.coroutines.core)
=======
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.acme4j.client)
>>>>>>> feat/integration-tunnel-relay:tunnel/build.gradle.kts

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
