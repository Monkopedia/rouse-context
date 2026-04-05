package com.rousecontext.device

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Manages a Rust relay binary as a subprocess for device integration tests.
 *
 * Generates test certificates, writes relay.toml, starts the relay on a random port,
 * and waits for it to accept connections before returning.
 */
class RelayProcess(
    private val repoRoot: File
) {
    companion object {
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }

    val port: Int = findFreePort()
    lateinit var tempDir: File
        private set

    private var process: Process? = null
    private val outputCapture = StringBuilder()

    /**
     * Start the relay. Blocks until the relay is accepting TCP connections on [port].
     * Throws if the relay binary is not found or fails to start.
     */
    fun start() {
        val binary = findRelayBinary()
        require(binary.exists() && binary.canExecute()) {
            "Relay binary not found at ${binary.absolutePath}. Build with: cd relay && cargo build"
        }

        tempDir = File.createTempFile("device-test-relay-", "")
        tempDir.delete()
        tempDir.mkdirs()

        writeRelayConfig()

        val configPath = File(tempDir, "relay.toml").absolutePath
        val pb = ProcessBuilder(binary.absolutePath, configPath)
            .redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "info"

        val proc = pb.start()
        process = proc

        val readerThread = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputCapture) { outputCapture.appendLine(line) }
                }
            } catch (_: Exception) {
                // Process killed
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        waitForPort(proc)
    }

    /**
     * Stop the relay subprocess and clean up temp files.
     */
    fun stop() {
        process?.let {
            it.destroyForcibly()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        process = null
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    /** Returns captured stdout/stderr from the relay process. */
    fun capturedOutput(): String = synchronized(outputCapture) { outputCapture.toString() }

    private fun waitForPort(proc: Process) {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                error("Relay process died during startup. Output:\n${capturedOutput()}")
            }
            try {
                java.net.Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        proc.destroyForcibly()
        error("Relay did not start within ${STARTUP_TIMEOUT_MS}ms. Output:\n${capturedOutput()}")
    }

    private fun writeRelayConfig() {
        // No TLS for device tests -- the relay supports plain HTTP mode
        val config = """
            [server]
            bind_addr = "0.0.0.0:$port"
            relay_hostname = "relay.rousecontext.com"

            [tls]
            cert_path = ""
            key_path = ""
            ca_cert_path = ""

            [limits]
            max_streams_per_device = 8
            wake_rate_limit = 60
        """.trimIndent()

        File(tempDir, "relay.toml").writeText(config)
    }

    private fun findRelayBinary(): File {
        val candidate = File(repoRoot, "relay/target/debug/rouse-relay")
        if (candidate.exists() && candidate.canExecute()) return candidate

        // Also check release
        val release = File(repoRoot, "relay/target/release/rouse-relay")
        if (release.exists() && release.canExecute()) return release

        return candidate // Will fail the require() check
    }

    private fun findFreePort(): Int {
        val socket = ServerSocket(0)
        val p = socket.localPort
        socket.close()
        return p
    }
}
