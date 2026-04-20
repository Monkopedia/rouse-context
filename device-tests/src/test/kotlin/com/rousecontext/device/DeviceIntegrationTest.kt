package com.rousecontext.device

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end device integration tests.
 *
 * Orchestrates the full flow: relay process, fake FCM server, APK build/install,
 * and test scenario execution against a real connected Android device.
 *
 * These tests are skipped (via Assume) if:
 * - No Android device is connected via ADB
 * - The relay binary has not been built
 */
class DeviceIntegrationTest {

    companion object {
        /**
         * LAN IP that the device can reach this machine on. Must be provided via the
         * `lan.ip` system property (or `LAN_IP` env var). Example:
         * `./gradlew :device-tests:deviceTest -Dlan.ip=<your-lan-ip>`.
         */
        private val LAN_IP: String? =
            System.getProperty("lan.ip") ?: System.getenv("LAN_IP")

        /** How long to wait for the device to connect to the relay after a wake broadcast. */
        private const val WAKE_TIMEOUT_MS = 30_000L

        /** Interval between relay status polling attempts. */
        private const val STATUS_POLL_INTERVAL_MS = 500L
    }

    private lateinit var repoRoot: File
    private lateinit var relay: RelayProcess
    private lateinit var device: DeviceController
    private lateinit var fcmServer: FakeFcmServer
    private lateinit var apkBuilder: ApkBuilder

    @Before
    fun setUp() {
        repoRoot = findRepoRoot()

        assumeTrue(
            "lan.ip system property (or LAN_IP env var) not set; " +
                "pass -Dlan.ip=<your-lan-ip> reachable from the connected device",
            LAN_IP != null
        )

        device = DeviceController()
        assumeTrue(
            "No Android device connected via ADB",
            device.isDeviceConnected()
        )

        // Check relay binary exists before creating RelayProcess
        val relayBinary = findRelayBinary(repoRoot)
        assumeTrue(
            "Relay binary not found. Build with: cd relay && cargo build",
            relayBinary.exists() && relayBinary.canExecute()
        )

        relay = RelayProcess(repoRoot)
        fcmServer = FakeFcmServer(device)
        apkBuilder = ApkBuilder(repoRoot)
    }

    private fun findRelayBinary(root: File): File {
        val debug = File(root, "relay/target/debug/rouse-relay")
        if (debug.exists()) return debug
        val release = File(root, "relay/target/release/rouse-relay")
        if (release.exists()) return release
        return debug
    }

    @After
    fun tearDown() {
        if (::relay.isInitialized) {
            relay.stop()
        }
        if (::fcmServer.isInitialized) {
            fcmServer.stop()
        }
        if (::device.isInitialized && device.isDeviceConnected()) {
            device.forceStop()
        }
    }

    /**
     * Full wake flow:
     * 1. Start relay + fake FCM server
     * 2. Build APK with local relay URL, install on device
     * 3. Launch app on device
     * 4. Send wake broadcast via ADB (simulating FCM delivery)
     * 5. Verify relay's /status shows active connection
     */
    @Test
    fun `full wake flow - device connects to relay after wake broadcast`() {
        // 1. Start relay (plain HTTP, no TLS)
        relay.start()
        println("Relay started on port ${relay.port}")

        // Start fake FCM server (for future use when relay has real FCM client)
        fcmServer.start()
        println("Fake FCM server started on port ${fcmServer.port}")

        // 2. Build APK pointing at local relay
        val apk = apkBuilder.build(relayHost = requireNotNull(LAN_IP), relayPort = relay.port)

        // Install on device
        device.installApk(apk.absolutePath).requireSuccess("APK install")
        println("APK installed on device")

        // 3. Launch app
        device.clearLogcat()
        device.launchApp().requireSuccess("App launch")
        println("App launched")

        // Give the app a moment to initialize
        Thread.sleep(3_000)
        assertTrue("App should be running after launch", device.isAppRunning())

        // 4. Send wake broadcast via ADB (simulating what FCM would do)
        device.sendWakeBroadcast().requireSuccess("Wake broadcast")
        println("Wake broadcast sent")

        // 5. Wait for device to connect to relay
        val connected = waitForRelayConnection(
            relayPort = relay.port,
            timeoutMs = WAKE_TIMEOUT_MS
        )

        if (!connected) {
            // Dump diagnostics on failure
            println("=== RELAY OUTPUT ===")
            println(relay.capturedOutput())
            println("=== DEVICE LOGCAT ===")
            println(device.logcat("RouseContext", maxLines = 200))
            println("=== TUNNEL LOGCAT ===")
            println(device.logcat("TunnelService", maxLines = 200))
        }

        assertTrue("Device should connect to relay after wake broadcast", connected)
        println("Device connected to relay successfully")
    }

    /**
     * Verify that the relay /status endpoint reports correct state
     * when no devices are connected.
     */
    @Test
    fun `relay status shows zero connections when idle`() {
        relay.start()
        println("Relay started on port ${relay.port}")

        val status = getRelayStatus(relay.port)
        assertTrue(
            "Relay should show 0 active connections. Got: $status",
            status.contains("\"active_mux_connections\":0") ||
                status.contains("\"active_mux_connections\": 0")
        )
    }

    /**
     * Verify APK can be built with custom relay parameters and installed.
     */
    @Test
    fun `apk builds and installs with custom relay config`() {
        val apk = apkBuilder.build(relayHost = requireNotNull(LAN_IP), relayPort = 9999)
        assertTrue("APK should exist after build", apk.exists())
        assertTrue("APK should not be empty", apk.length() > 0)

        device.installApk(apk.absolutePath).requireSuccess("APK install")
        println("APK installed successfully")

        device.launchApp().requireSuccess("App launch")
        Thread.sleep(2_000)
        assertTrue("App should be running", device.isAppRunning())

        device.forceStop()
        Thread.sleep(500)
    }

    // --- Helpers ---

    /**
     * Poll the relay's /status endpoint until it shows at least 1 active mux connection.
     */
    private fun waitForRelayConnection(relayPort: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val status = getRelayStatus(relayPort)
                // Check for non-zero active_mux_connections
                val match = Regex("\"active_mux_connections\"\\s*:\\s*(\\d+)")
                    .find(status)
                if (match != null && match.groupValues[1].toInt() > 0) {
                    return true
                }
            } catch (_: Exception) {
                // Relay not responding yet
            }
            Thread.sleep(STATUS_POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * GET the relay's /status endpoint.
     */
    private fun getRelayStatus(relayPort: Int): String {
        val url = URL("http://127.0.0.1:$relayPort/status")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun findRepoRoot(): File {
        // Try system property first (set by Gradle task)
        val fromProp = System.getProperty("repo.root")
        if (fromProp != null) {
            val dir = File(fromProp)
            if (dir.exists() && File(dir, "settings.gradle.kts").exists()) return dir
        }

        // Walk up from working directory
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "relay").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }

        error("Could not find repo root")
    }
}
