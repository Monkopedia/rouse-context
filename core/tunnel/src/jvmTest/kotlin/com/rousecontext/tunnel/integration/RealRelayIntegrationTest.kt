package com.rousecontext.tunnel.integration

import com.rousecontext.tunnel.MuxCodec
import com.rousecontext.tunnel.MuxFrame
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

/**
 * Integration tests that start the real Rust relay binary as a subprocess
 * and connect from Kotlin over WebSocket with mTLS.
 *
 * These tests are skipped (via Assume) if the relay binary has not been built.
 * Build it with: cd relay && cargo build
 */
class RealRelayIntegrationTest {

    companion object {
        private const val RELAY_HOSTNAME = "localhost"
        private const val RELAY_STARTUP_TIMEOUT_MS = 10_000L
        private const val WS_TIMEOUT_SECS = 10L
        private const val STORE_PASS = "changeit"
    }

    private lateinit var relayBinary: File
    private lateinit var tempDir: File
    private lateinit var caCert: X509Certificate
    private lateinit var deviceKeyStore: KeyStore
    private var relayProcess: Process? = null

    @Before
    fun setUp() {
        relayBinary = findRelayBinary()
        assumeTrue(
            "Relay binary not found. Build with: cd relay && cargo build",
            relayBinary.exists() && relayBinary.canExecute()
        )

        tempDir = File.createTempFile("relay-integration-", "")
        tempDir.delete()
        tempDir.mkdirs()

        generateCertificatesWithKeytool()
    }

    @After
    fun tearDown() {
        relayProcess?.let {
            it.destroyForcibly()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Scenario 1: Connect with valid client cert, verify mux session established.
     */
    @Test
    fun `connect with valid mTLS cert establishes WebSocket session`() {
        val port = findFreePort()
        writeRelayConfig(port)
        relayProcess = startRelay(port)

        val sslContext = buildMtlsSslContext()
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val listener = CollectingListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Send OPEN + DATA + CLOSE
        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 1u, payload = "hello relay".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Close(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(listener.errors.isEmpty(), "No errors: ${listener.errors}")
        assertTrue(true, "mTLS WebSocket connection succeeded")
    }

    /**
     * Scenario 2: Send OPEN + DATA + CLOSE for multiple streams sequentially.
     */
    @Test
    fun `OPEN DATA CLOSE frame sequence processes without error`() {
        val port = findFreePort()
        writeRelayConfig(port)
        relayProcess = startRelay(port)

        val sslContext = buildMtlsSslContext()
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val listener = CollectingListener()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Open stream, send several messages, close stream
        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        for (i in 1..5) {
            ws.sendBinary(
                ByteBuffer.wrap(
                    MuxCodec.encode(
                        MuxFrame.Data(
                            streamId = 1u,
                            payload = "message-$i".toByteArray()
                        )
                    )
                ),
                true
            ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        }

        ws.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Close(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(listener.errors.isEmpty(), "No errors: ${listener.errors}")
    }

    /**
     * Scenario 3: Connect without client cert - TLS handshake should fail
     * because the relay requires client certificates.
     */
    @Test
    fun `connect without client cert is rejected`() {
        val port = findFreePort()
        writeRelayConfig(port)
        relayProcess = startRelay(port)

        val sslContext = buildNoCertSslContext()
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        var rejected = false
        try {
            client.newWebSocketBuilder()
                .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), CollectingListener())
                .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            rejected = true
        }

        assertTrue(rejected, "Connection without client cert should be rejected")
    }

    /**
     * Scenario 4: Multiple concurrent WebSocket connections from different "devices".
     */
    @Test
    fun `multiple concurrent WebSocket connections are independent`() {
        val port = findFreePort()
        writeRelayConfig(port)
        relayProcess = startRelay(port)

        val sslContext = buildMtlsSslContext()

        val client1 = HttpClient.newBuilder().sslContext(sslContext).build()
        val client2 = HttpClient.newBuilder().sslContext(sslContext).build()

        val listener1 = CollectingListener()
        val listener2 = CollectingListener()

        val ws1 = client1.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener1)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        val ws2 = client2.newWebSocketBuilder()
            .buildAsync(URI.create("wss://$RELAY_HOSTNAME:$port/ws"), listener2)
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        // Send frames on both connections
        ws1.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 1u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws2.sendBinary(
            ByteBuffer.wrap(MuxCodec.encode(MuxFrame.Open(streamId = 2u))),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws1.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 1u, payload = "conn1".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws2.sendBinary(
            ByteBuffer.wrap(
                MuxCodec.encode(
                    MuxFrame.Data(streamId = 2u, payload = "conn2".toByteArray())
                )
            ),
            true
        ).get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done")
            .get(WS_TIMEOUT_SECS, TimeUnit.SECONDS)

        assertTrue(
            listener1.errors.isEmpty(),
            "Connection 1 errors: ${listener1.errors}"
        )
        assertTrue(
            listener2.errors.isEmpty(),
            "Connection 2 errors: ${listener2.errors}"
        )
    }

    // --- WebSocket listener ---

    private class CollectingListener : WebSocket.Listener {
        val binaryMessages = CopyOnWriteArrayList<ByteArray>()
        val errors = CopyOnWriteArrayList<Throwable>()

        override fun onBinary(
            webSocket: WebSocket,
            data: ByteBuffer,
            last: Boolean
        ): CompletionStage<*> {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            binaryMessages.add(bytes)
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String
        ): CompletionStage<*> {
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            errors.add(error)
        }
    }

    // --- Certificate generation ---

    @Suppress("LongMethod")
    private fun generateCertificatesWithKeytool() {
        val caKs = File(tempDir, "ca.p12")
        val relayKs = File(tempDir, "relay.p12")
        val deviceKs = File(tempDir, "device.p12")
        val caCertFile = File(tempDir, "ca-cert.pem")
        val csrFile = File(tempDir, "relay.csr")
        val signedCertFile = File(tempDir, "relay-signed.pem")
        val deviceCsrFile = File(tempDir, "device.csr")
        val deviceSignedCertFile = File(tempDir, "device-signed.pem")
        val relayCertFile = File(tempDir, "relay-cert.pem")
        val relayKeyFile = File(tempDir, "relay-key.pem")
        val deviceDomain = "test-device.$RELAY_HOSTNAME"

        // --- CA ---
        keytool(
            "-genkeypair", "-alias", "ca",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=Test CA",
            "-ext", "bc:c",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-exportcert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-rfc",
            "-file", caCertFile.absolutePath
        )

        // --- Relay server cert ---
        keytool(
            "-genkeypair", "-alias", "relay",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$RELAY_HOSTNAME",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-certreq", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", csrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-infile", csrFile.absolutePath,
            "-outfile", signedCertFile.absolutePath,
            "-ext", "san=dns:$RELAY_HOSTNAME",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", signedCertFile.absolutePath
        )

        // Export relay cert (just the signed cert, not the full chain)
        relayCertFile.writeText(signedCertFile.readText())

        // Export relay private key via openssl
        extractPrivateKey(relayKs, relayKeyFile)

        // --- Device client cert ---
        keytool(
            "-genkeypair", "-alias", "device",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$deviceDomain",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-keypass", STORE_PASS
        )
        keytool(
            "-certreq", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", deviceCsrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", STORE_PASS,
            "-infile", deviceCsrFile.absolutePath,
            "-outfile", deviceSignedCertFile.absolutePath,
            "-ext", "san=dns:$deviceDomain",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", STORE_PASS,
            "-file", deviceSignedCertFile.absolutePath
        )

        // Load for use in tests
        val caStore = KeyStore.getInstance("PKCS12")
        caKs.inputStream().use { caStore.load(it, STORE_PASS.toCharArray()) }
        caCert = caStore.getCertificate("ca") as X509Certificate

        deviceKeyStore = KeyStore.getInstance("PKCS12")
        deviceKs.inputStream().use {
            deviceKeyStore.load(it, STORE_PASS.toCharArray())
        }
    }

    private fun extractPrivateKey(keystoreFile: File, keyFile: File) {
        // Try with -legacy first (OpenSSL 3.x), fall back without
        for (args in listOf(
            listOf(
                "openssl", "pkcs12",
                "-in", keystoreFile.absolutePath,
                "-nocerts", "-nodes",
                "-passin", "pass:$STORE_PASS",
                "-legacy"
            ),
            listOf(
                "openssl",
                "pkcs12",
                "-in",
                keystoreFile.absolutePath,
                "-nocerts",
                "-nodes",
                "-passin",
                "pass:$STORE_PASS"
            )
        )) {
            val proc = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            val keyStart = output.indexOf("-----BEGIN PRIVATE KEY-----")
            val keyEndMarker = "-----END PRIVATE KEY-----"
            val keyEnd = output.indexOf(keyEndMarker)
            if (keyStart >= 0 && keyEnd > keyStart) {
                keyFile.writeText(
                    output.substring(keyStart, keyEnd + keyEndMarker.length) + "\n"
                )
                return
            }
        }
        fail("Failed to extract private key from ${keystoreFile.name}")
    }

    private fun keytool(vararg args: String) {
        val process = ProcessBuilder("keytool", *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            fail("keytool failed (exit $exitCode): $output\nArgs: ${args.toList()}")
        }
    }

    // --- Relay config and process management ---

    private fun writeRelayConfig(port: Int) {
        val config = """
            [server]
            bind_addr = "127.0.0.1:$port"
            relay_hostname = "$RELAY_HOSTNAME"

            [tls]
            cert_path = "${tempDir.absolutePath}/relay-cert.pem"
            key_path = "${tempDir.absolutePath}/relay-key.pem"
            ca_cert_path = "${tempDir.absolutePath}/ca-cert.pem"

            [limits]
            max_streams_per_device = 8
            wake_rate_limit = 60
        """.trimIndent()

        File(tempDir, "relay.toml").writeText(config)
    }

    private fun startRelay(port: Int): Process {
        val configPath = File(tempDir, "relay.toml").absolutePath
        val pb = ProcessBuilder(relayBinary.absolutePath, configPath)
            .redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "info"

        val process = pb.start()
        val deadline = System.currentTimeMillis() + RELAY_STARTUP_TIMEOUT_MS

        val outputCapture = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputCapture) { outputCapture.appendLine(line) }
                }
            } catch (_: Exception) {
                // Process killed
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        var started = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = synchronized(outputCapture) { outputCapture.toString() }
                fail(
                    "Relay process died during startup. Output:\n$output"
                )
            }
            try {
                java.net.Socket("127.0.0.1", port).use { started = true }
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }

        if (!started) {
            process.destroyForcibly()
            val output = synchronized(outputCapture) { outputCapture.toString() }
            fail(
                "Relay did not start within ${RELAY_STARTUP_TIMEOUT_MS}ms. Output:\n$output"
            )
        }

        Thread.sleep(200)
        return process
    }

    // --- SSL context ---

    private fun buildMtlsSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(deviceKeyStore, STORE_PASS.toCharArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    private fun buildNoCertSslContext(): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx
    }

    // --- Utility ---

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun findRelayBinary(): File {
        val repoRoot = System.getProperty("repo.root")
        if (repoRoot != null) {
            val candidate = File(repoRoot, "relay/target/debug/rouse-relay")
            if (candidate.exists() && candidate.canExecute()) return candidate
        }

        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val candidate = File(dir, "relay/target/debug/rouse-relay")
            if (candidate.exists() && candidate.canExecute()) return candidate
            dir = dir.parentFile
        }

        return File("/nonexistent/rouse-relay")
    }
}
