package com.rousecontext.app.auth

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cancellation clearance documented on
 * [com.rousecontext.app.ui.viewmodels.OnboardingViewModel] and
 * `IntegrationSetupViewModel.fetchCredential` (issues #667 / #679).
 *
 * Those comments argue that `Task.await()` cannot deliver a bare
 * `CancellationException` into the `google` credential / FCM paths. The argument
 * is not a property of our code — it is a measurement of the artifacts Gradle
 * resolves, and it expires the moment those change. `firebase-bom` bumps land
 * without a single line of `app/src/main` moving, so nothing else in the tree
 * would go red.
 *
 * The three checks below are deliberately different in kind:
 *
 *  1. [versions] fails when the resolved artifacts are no longer the ones the
 *     clearance was measured against. It is *supposed* to fire on a bump: the
 *     fix is to re-derive the argument in the KDoc and move the pin.
 *  2. [noCancellationMachinery] re-runs the measurement itself, with positive
 *     controls so an empty result is a real absence and not a scan that missed.
 *  3. [scannerFindsRealProducers] is the detector's own control: the same scan,
 *     over jars on this same classpath that DO contain a
 *     `setException(CancellationException)` producer, must find them. Without
 *     it, check 2 could pass because the scanner reads nothing.
 *
 * What this cannot pin is *reachability* — that recaptcha's bridge only serves
 * reCAPTCHA Enterprise and that `zacc` is Activity-bound. That derivation lives
 * in the KDoc, and check 1 exists to force a human back to it.
 */
class FirebaseCancellationClearanceTest {

    @Test
    fun versions() {
        PINNED.forEach { (artifact, pinned) ->
            assertEquals(
                "$artifact moved off the version the cancellation clearance in " +
                    "OnboardingViewModel's KDoc was measured against. Re-derive that " +
                    "argument against the new artifact before moving this pin (#679).",
                pinned,
                resolvedVersionOf(artifact)
            )
        }
    }

    @Test
    fun noCancellationMachinery() {
        listOf("firebase-auth", "firebase-messaging").forEach { artifact ->
            val classes = classFilesOf(artifact)
            assertTrue(
                "positive control: found no TaskCompletionSource reference in " +
                    "$artifact, so the scan below proves nothing",
                classes.count { it.contains(TASK_COMPLETION_SOURCE) } > 0
            )
            CANCELLATION_MARKERS.forEach { marker ->
                assertEquals(
                    "$artifact now references '$marker'. The clearance in " +
                        "OnboardingViewModel's KDoc no longer holds — re-derive it (#679).",
                    0,
                    classes.count { it.contains(marker) }
                )
            }
        }
        // Recorded because two of the four symbols originally scanned for were
        // dead letters: TaskCompletionSource has never had a cancel setter, so
        // grepping for one can never hit. If that ever changes, the KDoc's
        // "in any version" claim needs revisiting.
        val tasks = classFilesOf("play-services-tasks")
        listOf("setCancelled", "trySetCancelled", "setCanceled", "trySetCanceled").forEach {
            assertEquals(
                "play-services-tasks now exposes '$it'; the KDoc claims no version does",
                0,
                tasks.count { c -> c.contains(it) }
            )
        }
    }

    @Test
    fun scannerFindsRealProducers() {
        listOf("recaptcha", "play-services-base").forEach { artifact ->
            assertTrue(
                "the scanner found no CancellationException in $artifact, which is " +
                    "known to complete a Task with one. The scan in " +
                    "noCancellationMachinery is therefore not measuring anything.",
                classFilesOf(artifact).count { it.contains(CANCELLATION_EXCEPTION) } > 0
            )
        }
    }

    private companion object {
        /**
         * Resolved `-Pgoogle` runtime artifacts, not version-catalog names.
         * `firebase-bom` 34.12.0 is a BOM and never appears on a classpath; it
         * is pinned here through the two artifacts it selects.
         */
        val PINNED = linkedMapOf(
            "firebase-auth" to "24.0.1",
            "firebase-messaging" to "25.0.1",
            "recaptcha" to "18.6.1",
            "play-services-base" to "18.1.0",
            "play-services-tasks" to "18.4.0",
            "kotlinx-coroutines-play-services" to "1.10.2"
        )

        const val CANCELLATION_EXCEPTION = "CancellationException"
        const val TASK_COMPLETION_SOURCE = "com/google/android/gms/tasks/TaskCompletionSource"

        /**
         * `CancellationToken` covers `CancellationTokenSource` by prefix;
         * `forCanceled` is the only public factory for a cancelled `Task`.
         */
        val CANCELLATION_MARKERS =
            listOf(CANCELLATION_EXCEPTION, "CancellationToken", "forCanceled")

        /**
         * Classpath entries keyed by the `<artifact>-<version>` coordinate baked
         * into their path. AGP contributes each AAR twice — once as
         * `<coord>-runtime.jar`, once as `<coord>/jars/classes.jar` — with
         * identical contents; module jars appear as `<coord>.jar`.
         */
        val entriesByCoordinate: Map<String, String> by lazy {
            checkNotNull(System.getProperty("java.class.path"))
                .split(File.pathSeparator)
                .mapNotNull { entry -> coordinateOf(File(entry))?.let { it to entry } }
                .toMap()
        }

        fun coordinateOf(file: File): String? {
            val jars = file.parentFile
            return when {
                file.name == "classes.jar" && jars?.name == "jars" -> jars.parentFile?.name
                file.name.endsWith(".jar") ->
                    file.name.removeSuffix(".jar").removeSuffix("-runtime")
                else -> null
            }
        }

        /**
         * Splits `<artifact>-<version>` on the first `-` followed by a digit, so
         * `play-services-base` does not swallow `play-services-basement` and
         * `firebase-auth` does not swallow `firebase-auth-interop`.
         */
        fun versionIn(coordinate: String, artifact: String): String? {
            val rest = coordinate.removePrefix("$artifact-")
            return rest.takeIf {
                coordinate.length != rest.length && it.firstOrNull()?.isDigit() == true
            }
        }

        fun resolvedVersionOf(artifact: String): String {
            val found = entriesByCoordinate.keys.mapNotNull { versionIn(it, artifact) }.distinct()
            assertEquals(
                "expected exactly one resolved version of $artifact on the unit-test " +
                    "classpath, found $found",
                1,
                found.size
            )
            return found.single()
        }

        fun jarFor(artifact: String): String {
            val match = entriesByCoordinate.entries
                .firstOrNull { versionIn(it.key, artifact) != null }
            return requireNotNull(match) { "$artifact is not on the unit-test classpath" }.value
        }

        /** Raw bytes of every `.class` in [artifact]'s jar, as Latin-1 text. */
        fun classFilesOf(artifact: String): List<String> = ZipFile(jarFor(artifact)).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .map { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    String(bytes, Charsets.ISO_8859_1)
                }
                .toList()
        }
    }
}
