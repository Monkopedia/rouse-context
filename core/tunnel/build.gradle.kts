plugins {
    alias(libs.plugins.kotlin.multiplatform)
<<<<<<< HEAD
<<<<<<< HEAD
    alias(libs.plugins.android.library)
=======
>>>>>>> feat/tunnel-websocket-tls
=======
    alias(libs.plugins.kotlin.serialization)
>>>>>>> feat/security-monitoring
}

kotlin {
    jvm()

<<<<<<< HEAD
<<<<<<< HEAD
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.coroutines.core)
=======
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
>>>>>>> feat/security-monitoring
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
<<<<<<< HEAD

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.service)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.acme4j.client)
        }
    }
}

android {
    namespace = "com.rousecontext.tunnel"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
=======
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
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
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }
    }
>>>>>>> feat/tunnel-websocket-tls
}
=======
    }
}
>>>>>>> feat/security-monitoring
