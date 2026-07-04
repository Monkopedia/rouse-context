// =============================================================================
// Google/Firebase-only Gradle configuration — applied ONLY under `-Pgoogle`.
// =============================================================================
//
// This file holds EVERY Firebase / Google-services build coordinate: the two
// Gradle plugins, the Crashlytics per-build-type config, and the Firebase
// runtime dependencies. It is applied from app/build.gradle.kts exclusively
// when the `-Pgoogle` credentialed build is selected:
//
//     if (googleBuild) { apply(from = "$rootDir/gradle/google-services.gradle.kts") }
//
// The bare FOSS build never applies it, so the FOSS build compiles ZERO
// Firebase/Google-services: no plugins, no deps, no Crashlytics config.
//
// It lives in the repo-root gradle/ dir (not app/) on purpose: Android Lint's
// build-script visitor (lintVitalRelease) crashes trying to build a FIR
// light-class for a standalone apply(from=...) script inside an Android module.
// The root project has no Android lint task, so keeping it here sidesteps that
// upstream crash while the FOSS release build (which runs lintVitalRelease)
// stays green — it never applies this file.
//
// F-Droid: the FOSS build never needs this file, and the fdroiddata recipe
// removes it in `prebuild:` (rm -f gradle/google-services.gradle.kts) BEFORE the
// source scanner runs, so the scanner never sees any Firebase coordinate. This
// is why the coordinates below are written as explicit Maven strings rather
// than `libs.` version-catalog accessors — version-catalog type-safe accessors
// are NOT available inside an `apply(from = ...)` script, and keeping the
// coordinates here (not in libs.versions.toml usage within a surviving build
// file) is what makes the FOSS build genuinely, statically Firebase-free.
//
// Versions are mirrored from gradle/libs.versions.toml — keep them in sync:
//   * firebase-bom = 34.12.0  (all firebase-* artifacts are versioned by the BOM)
//   * firebase-auth / firebase-messaging / firebase-crashlytics (no explicit
//     version; resolved by the BOM platform above)
// The plugin versions (google-services 4.4.4, firebase-crashlytics 3.0.6) are
// declared `apply false` in the root build.gradle.kts so the `apply(plugin=...)`
// calls below can resolve them.
// =============================================================================

apply(plugin = "com.google.gms.google-services")
apply(plugin = "com.google.firebase.crashlytics")

// Crashlytics per-build-type config, reaching into the already-configured
// android extension. Mirrors the guarded inline config that previously lived in
// app/build.gradle.kts `buildTypes { debug { ... }; release { ... } }`.
//
// An `apply(from = ...)` script does NOT have the AGP / firebase-crashlytics
// Gradle plugin types on its compile classpath (unlike the main build file), so
// the typed `configure<ApplicationExtension>` / `configure<CrashlyticsExtension>`
// DSL does not resolve here. We instead reach the build types dynamically and
// set the two upload toggles by reflection, which stays type-agnostic.
//
//   * debug   — no mapping/symbol upload (keep debug assembly fast).
//   * release — upload the R8 mapping so Crashlytics deobfuscates stacks; no
//               NDK code, so native symbol upload stays off.
// The firebase-crashlytics plugin registers a `firebaseCrashlytics` extension
// (CRASHLYTICS_EXTENSION_NAME) on every build type (each build type is
// ExtensionAware). We toggle mapping/symbol upload per build type via that
// named extension.
val crashlyticsMappingUpload = mapOf("debug" to false, "release" to true)
val androidExt = extensions.getByName("android")
@Suppress("UNCHECKED_CAST")
val buildTypes = androidExt.javaClass
    .getMethod("getBuildTypes")
    .invoke(androidExt) as org.gradle.api.NamedDomainObjectContainer<Any>
crashlyticsMappingUpload.forEach { (buildTypeName, uploadMapping) ->
    val buildType = buildTypes.getByName(buildTypeName) as org.gradle.api.plugins.ExtensionAware
    val crashlytics = buildType.extensions.getByName("firebaseCrashlytics")
    // CrashlyticsExtension exposes boxed-Boolean setters
    // (set{Mapping,NativeSymbol}UploadEnabled(java.lang.Boolean)).
    crashlytics.javaClass
        .getMethod("setMappingFileUploadEnabled", java.lang.Boolean::class.java)
        .invoke(crashlytics, uploadMapping)
    crashlytics.javaClass
        .getMethod("setNativeSymbolUploadEnabled", java.lang.Boolean::class.java)
        .invoke(crashlytics, false)
}

dependencies {
    // FCM wake + Firebase anonymous auth + Crashlytics. Only the `-Pgoogle`
    // build links these; the FOSS build uses UnifiedPush + ACRA instead.
    add("implementation", platform("com.google.firebase:firebase-bom:34.12.0"))
    add("implementation", "com.google.firebase:firebase-auth")
    add("implementation", "com.google.firebase:firebase-messaging")
    add("implementation", "com.google.firebase:firebase-crashlytics")
}
