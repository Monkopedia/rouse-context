package com.rousecontext.device

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wraps ADB commands for controlling a connected Android device.
 *
 * All operations are synchronous and block the calling thread.
 * The ADB binary location defaults to the Android SDK platform-tools.
 */
class DeviceController(
    private val adbPath: String = "/home/jmonk/Android/Sdk/platform-tools/adb",
    private val packageName: String = "com.rousecontext.debug"
) {
    companion object {
        private const val ADB_TIMEOUT_SECS = 30L
        private const val LOGCAT_POLL_INTERVAL_MS = 500L
    }

    /**
     * Check if any device is connected via ADB.
     * Returns true if at least one device shows "device" status.
     */
    fun isDeviceConnected(): Boolean {
        val result = executeAdb("devices")
        return result.output.lines()
            .drop(1) // Skip header line
            .any { line -> line.contains("\tdevice") }
    }

    /**
     * Install an APK on the connected device. Replaces existing installation.
     */
    fun installApk(apkPath: String): AdbResult {
        return executeAdb("install", "-r", apkPath)
    }

    /**
     * Launch the app's main activity.
     */
    fun launchApp(): AdbResult {
        return executeAdb(
            "shell", "am", "start",
            "-n", "$packageName/com.rousecontext.app.MainActivity",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER"
        )
    }

    /**
     * Force stop the app.
     */
    fun forceStop(): AdbResult {
        return executeAdb("shell", "am", "force-stop", packageName)
    }

    /**
     * Take a screenshot and pull it to a local file.
     * Returns the local path of the screenshot.
     */
    fun screenshot(outputDir: File): File {
        val devicePath = "/sdcard/device-test-screenshot.png"
        val localFile = File(outputDir, "screenshot-${System.currentTimeMillis()}.png")

        executeAdb("shell", "screencap", "-p", devicePath)
        executeAdb("pull", devicePath, localFile.absolutePath)
        executeAdb("shell", "rm", devicePath)

        return localFile
    }

    /**
     * Get filtered logcat output for a specific tag.
     * Clears logcat first to get only recent entries.
     */
    fun logcat(tag: String, maxLines: Int = 100): String {
        val result = executeAdb(
            "shell",
            "logcat",
            "-d",
            "-t",
            maxLines.toString(),
            "$tag:V",
            "*:S"
        )
        return result.output
    }

    /**
     * Clear the logcat buffer.
     */
    fun clearLogcat(): AdbResult {
        return executeAdb("shell", "logcat", "-c")
    }

    /**
     * Check if the app process is currently running on the device.
     */
    fun isAppRunning(): Boolean {
        val result = executeAdb("shell", "pidof", packageName)
        return result.output.trim().isNotEmpty() && result.exitCode == 0
    }

    /**
     * Poll logcat until a line matching [pattern] appears or [timeoutMs] elapses.
     * Returns true if the pattern was found, false on timeout.
     */
    fun waitForLog(pattern: String, timeoutMs: Long = 30_000, tag: String = ""): Boolean {
        val regex = Regex(pattern)
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val logcatArgs = if (tag.isNotEmpty()) {
                arrayOf("shell", "logcat", "-d", "$tag:V", "*:S")
            } else {
                arrayOf("shell", "logcat", "-d")
            }
            val result = executeAdb(*logcatArgs)
            if (result.output.lines().any { regex.containsMatchIn(it) }) {
                return true
            }
            Thread.sleep(LOGCAT_POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Send a broadcast to the device, simulating an FCM wake message.
     */
    fun sendWakeBroadcast(): AdbResult {
        return executeAdb(
            "shell", "am", "broadcast",
            "-n",
            "$packageName/com.google.firebase.iid.FirebaseInstanceIdReceiver",
            "-a", "com.google.android.c2dm.intent.RECEIVE",
            "--es", "type", "wake"
        )
    }

    /**
     * Execute an ADB command and return the result.
     */
    fun executeAdb(vararg args: String): AdbResult {
        val command = listOf(adbPath) + args.toList()
        val pb = ProcessBuilder(command)
            .redirectErrorStream(true)

        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val completed = proc.waitFor(ADB_TIMEOUT_SECS, TimeUnit.SECONDS)

        if (!completed) {
            proc.destroyForcibly()
            return AdbResult(
                exitCode = -1,
                output = "ADB command timed out after ${ADB_TIMEOUT_SECS}s: ${command.joinToString(
                    " "
                )}"
            )
        }

        return AdbResult(
            exitCode = proc.exitValue(),
            output = output
        )
    }
}

/**
 * Result of an ADB command execution.
 */
data class AdbResult(
    val exitCode: Int,
    val output: String
) {
    val isSuccess: Boolean get() = exitCode == 0

    fun requireSuccess(context: String = ""): AdbResult {
        require(isSuccess) {
            val prefix = if (context.isNotEmpty()) "$context: " else ""
            "${prefix}ADB command failed (exit $exitCode): $output"
        }
        return this
    }
}
