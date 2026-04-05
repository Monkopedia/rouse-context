package com.rousecontext.device

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the debug APK with custom relay host/port configuration.
 *
 * Runs the Gradle build from the repo root, passing relay connection parameters
 * as project properties that get embedded into BuildConfig.
 */
class ApkBuilder(
    private val repoRoot: File
) {
    companion object {
        private const val BUILD_TIMEOUT_MINUTES = 5L
        private const val JAVA_HOME = "/usr/lib/jvm/java-21-openjdk"
        private const val ANDROID_SDK = "/home/jmonk/Android/Sdk"
    }

    /**
     * Build the debug APK configured to connect to the given relay host and port.
     *
     * @param relayHost The IP/hostname the device should connect to (e.g., "192.168.68.92")
     * @param relayPort The port the relay is listening on
     * @param relayScheme WebSocket scheme: "ws" for plain, "wss" for TLS (default: "ws")
     * @return The path to the built APK file
     */
    fun build(relayHost: String, relayPort: Int, relayScheme: String = "ws"): File {
        val gradlew = File(repoRoot, "gradlew")
        require(gradlew.exists() && gradlew.canExecute()) {
            "gradlew not found at ${gradlew.absolutePath}"
        }

        val command = listOf(
            gradlew.absolutePath,
            ":app:assembleDebug",
            "-Prelay.host=$relayHost",
            "-Prelay.port=$relayPort",
            "-Prelay.scheme=$relayScheme"
        )

        val pb = ProcessBuilder(command)
            .directory(repoRoot)
            .redirectErrorStream(true)
        pb.environment()["JAVA_HOME"] = JAVA_HOME
        pb.environment()["ANDROID_HOME"] = ANDROID_SDK

        println("Building APK: ${command.joinToString(" ")}")
        val proc = pb.start()

        val output = StringBuilder()
        val readerThread = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
                // Print build output for visibility during long builds
                println("[gradle] $line")
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val completed = proc.waitFor(BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!completed) {
            proc.destroyForcibly()
            error("APK build timed out after ${BUILD_TIMEOUT_MINUTES} minutes")
        }

        require(proc.exitValue() == 0) {
            "APK build failed (exit ${proc.exitValue()}):\n${synchronized(
                output
            ) { output.toString() }}"
        }

        val apk = File(repoRoot, "app/build/outputs/apk/debug/app-debug.apk")
        require(apk.exists()) {
            "APK not found at expected path: ${apk.absolutePath}"
        }

        println("APK built: ${apk.absolutePath} (${apk.length() / 1024} KB)")
        return apk
    }
}
