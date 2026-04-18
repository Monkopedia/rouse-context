plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Shared test fixtures for integration-tier tests that boot the real Rust
// relay binary. Originally lived in `:core:tunnel:jvmTest` alongside the
// tunnel integration suite (issue #249); extracted into its own module so
// `:app` integration tests (issue #250, umbrella #247) can reuse the fixture
// without pulling in the unrelated `:core:tunnel:jvmTest` test classes.
//
// Packaged under `src/main/kotlin` rather than `src/test/kotlin` so the code
// ships as the module's default artifact — downstream modules wire it in via
// `testImplementation(project(":core:testfixtures"))`.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `kotlin.test.fail` is used throughout the fixture for startup / parse
    // failures. Exposed as `api` because downstream assertions often reuse
    // the same `fail(...)` helper.
    api(kotlin("test"))
}
