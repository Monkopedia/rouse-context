package com.rousecontext.tunnel.integration

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.fail

/**
 * Generates a test CA, relay cert, and device cert using keytool.
 *
 * After [generate], provides:
 * - [caCert] the CA certificate
 * - [deviceKeyStore] a PKCS12 keystore with the device cert + key
 * - [deviceCert] the device X.509 certificate
 *
 * Also writes `relay-cert.pem`, `relay-key.pem`, and `ca-cert.pem` into [tempDir]
 * so the relay binary can load them.
 */
class TestCertificateAuthority(
    private val tempDir: File,
    private val relayHostname: String,
    private val deviceSubdomain: String,
    private val storePass: String = STORE_PASS
) {
    lateinit var caCert: X509Certificate
        private set

    lateinit var deviceKeyStore: KeyStore
        private set

    lateinit var deviceCert: X509Certificate
        private set

    @Suppress("LongMethod")
    fun generate() {
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
        val deviceDomain = "$deviceSubdomain.$relayHostname"

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
            "-storepass", storePass,
            "-keypass", storePass
        )
        keytool(
            "-exportcert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", storePass,
            "-rfc",
            "-file", caCertFile.absolutePath
        )

        // --- Relay server cert ---
        keytool(
            "-genkeypair", "-alias", "relay",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$relayHostname",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", relayKs.absolutePath,
            "-storepass", storePass,
            "-keypass", storePass
        )
        keytool(
            "-certreq", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", storePass,
            "-file", csrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", storePass,
            "-infile", csrFile.absolutePath,
            "-outfile", signedCertFile.absolutePath,
            "-ext", "san=dns:$relayHostname",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", relayKs.absolutePath,
            "-storepass", storePass,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "relay",
            "-keystore", relayKs.absolutePath,
            "-storepass", storePass,
            "-file", signedCertFile.absolutePath
        )

        relayCertFile.writeText(signedCertFile.readText())
        extractPrivateKey(relayKs, relayKeyFile)

        // --- Device client cert (CN = device subdomain) ---
        keytool(
            "-genkeypair", "-alias", "device",
            "-keyalg", "RSA", "-keysize", "2048",
            "-sigalg", "SHA256withRSA",
            "-dname", "CN=$deviceDomain",
            "-validity", "365",
            "-storetype", "PKCS12",
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-keypass", storePass
        )
        keytool(
            "-certreq", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-file", deviceCsrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", storePass,
            "-infile", deviceCsrFile.absolutePath,
            "-outfile", deviceSignedCertFile.absolutePath,
            "-ext", "san=dns:$deviceDomain",
            "-rfc",
            "-validity", "365"
        )
        keytool(
            "-importcert", "-alias", "ca",
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-file", caCertFile.absolutePath,
            "-noprompt"
        )
        keytool(
            "-importcert", "-alias", "device",
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-file", deviceSignedCertFile.absolutePath
        )

        // Load for use in tests
        val caStore = KeyStore.getInstance("PKCS12")
        caKs.inputStream().use { caStore.load(it, storePass.toCharArray()) }
        caCert = caStore.getCertificate("ca") as X509Certificate

        deviceKeyStore = KeyStore.getInstance("PKCS12")
        deviceKs.inputStream().use {
            deviceKeyStore.load(it, storePass.toCharArray())
        }
        deviceCert = deviceKeyStore.getCertificate("device") as X509Certificate
    }

    private fun extractPrivateKey(keystoreFile: File, keyFile: File) {
        for (args in listOf(
            listOf(
                "openssl", "pkcs12",
                "-in", keystoreFile.absolutePath,
                "-nocerts", "-nodes",
                "-passin", "pass:$storePass",
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
                "pass:$storePass"
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

    companion object {
        const val STORE_PASS = "changeit"
    }
}

/**
 * Manages the real Rust relay binary as a subprocess.
 */
class TestRelayManager(
    private val tempDir: File,
    private val relayHostname: String
) {
    var process: Process? = null
        private set

    /**
     * Write relay.toml config and start the relay process.
     * Blocks until the relay is accepting connections on [port].
     */
    fun start(port: Int): Process {
        writeRelayConfig(port)
        val relay = startRelay(port)
        process = relay
        return relay
    }

    fun stop() {
        process?.let {
            it.destroyForcibly()
            it.waitFor(5, TimeUnit.SECONDS)
        }
        process = null
    }

    private fun writeRelayConfig(port: Int) {
        val config = """
            [server]
            bind_addr = "127.0.0.1:$port"
            relay_hostname = "$relayHostname"

            [tls]
            cert_path = "${tempDir.absolutePath}/relay-cert.pem"
            key_path = "${tempDir.absolutePath}/relay-key.pem"
            ca_cert_path = "${tempDir.absolutePath}/ca-cert.pem"

            [limits]
            max_streams_per_device = 8
            wake_rate_limit = 60
            fcm_wakeup_timeout_secs = 5
        """.trimIndent()

        File(tempDir, "relay.toml").writeText(config)
    }

    private fun startRelay(port: Int): Process {
        val relayBinary = findRelayBinary()
        val configPath = File(tempDir, "relay.toml").absolutePath
        val pb = ProcessBuilder(relayBinary.absolutePath, configPath)
            .redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "debug"

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
                fail("Relay process died during startup. Output:\n$output")
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

    companion object {
        private const val RELAY_STARTUP_TIMEOUT_MS = 10_000L
    }
}

/**
 * Factory methods for test [SSLContext] instances.
 */
object TestSslContexts {

    /** mTLS SSL context: presents device cert, trusts relay CA. */
    fun buildMtls(
        deviceKeyStore: KeyStore,
        caCert: X509Certificate,
        storePass: String = TestCertificateAuthority.STORE_PASS
    ): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(deviceKeyStore, storePass.toCharArray())

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        return ctx
    }

    /** Device TLS server context: uses device keypair, no client auth. */
    fun buildDeviceServer(
        deviceKeyStore: KeyStore,
        storePass: String = TestCertificateAuthority.STORE_PASS
    ): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(deviceKeyStore, storePass.toCharArray())

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }

    /** AI client socket factory: trusts device cert (via CA). */
    fun buildAiClientSocketFactory(
        caCert: X509Certificate,
        deviceCert: X509Certificate
    ): SSLSocketFactory {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("device-ca", caCert)
        trustStore.setCertificateEntry("device", deviceCert)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx.socketFactory
    }

    /** SSL context with no client cert (for testing cert rejection). */
    fun buildNoCert(caCert: X509Certificate): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx
    }
}

/**
 * Builds a synthetic TLS 1.2 ClientHello with an SNI extension.
 * Used to trigger relay routing without a real TLS handshake.
 */
@Suppress("MagicNumber")
fun buildSyntheticClientHello(sniHostname: String): ByteArray {
    val hostBytes = sniHostname.toByteArray(Charsets.US_ASCII)

    // SNI extension: type(0x0000) + length + server_name_list
    val sniEntry = ByteArray(3 + hostBytes.size) // type(1) + length(2) + name
    sniEntry[0] = 0x00 // host_name type
    sniEntry[1] = (hostBytes.size shr 8).toByte()
    sniEntry[2] = hostBytes.size.toByte()
    hostBytes.copyInto(sniEntry, 3)

    val sniList = ByteArray(2 + sniEntry.size)
    sniList[0] = (sniEntry.size shr 8).toByte()
    sniList[1] = sniEntry.size.toByte()
    sniEntry.copyInto(sniList, 2)

    val sniExt = ByteArray(4 + sniList.size)
    sniExt[0] = 0x00 // extension type high byte
    sniExt[1] = 0x00 // extension type low byte (SNI = 0x0000)
    sniExt[2] = (sniList.size shr 8).toByte()
    sniExt[3] = sniList.size.toByte()
    sniList.copyInto(sniExt, 4)

    val extensions = ByteArray(2 + sniExt.size)
    extensions[0] = (sniExt.size shr 8).toByte()
    extensions[1] = sniExt.size.toByte()
    sniExt.copyInto(extensions, 2)

    // ClientHello body: version(2) + random(32) + session_id_len(1) +
    // cipher_suites_len(2) + cipher(2) + comp_len(1) + comp(1) + extensions
    val random = ByteArray(32) { 0x42 }
    val cipherSuites = byteArrayOf(0x00, 0x02, 0x00, 0x2F)
    val compression = byteArrayOf(0x01, 0x00)

    val helloBody = ByteArray(
        2 + 32 + 1 + cipherSuites.size + compression.size + extensions.size
    )
    var pos = 0
    helloBody[pos++] = 0x03 // TLS 1.2
    helloBody[pos++] = 0x03.toByte()
    random.copyInto(helloBody, pos)
    pos += 32
    helloBody[pos++] = 0x00 // session_id length = 0
    cipherSuites.copyInto(helloBody, pos)
    pos += cipherSuites.size
    compression.copyInto(helloBody, pos)
    pos += compression.size
    extensions.copyInto(helloBody, pos)

    // Handshake header: type(1) + length(3)
    val handshake = ByteArray(4 + helloBody.size)
    handshake[0] = 0x01 // ClientHello
    handshake[1] = (helloBody.size shr 16).toByte()
    handshake[2] = (helloBody.size shr 8).toByte()
    handshake[3] = helloBody.size.toByte()
    helloBody.copyInto(handshake, 4)

    // TLS record header: type(1) + version(2) + length(2)
    val record = ByteArray(5 + handshake.size)
    record[0] = 0x16 // Handshake
    record[1] = 0x03 // TLS 1.0 record version
    record[2] = 0x01
    record[3] = (handshake.size shr 8).toByte()
    record[4] = handshake.size.toByte()
    handshake.copyInto(record, 5)

    return record
}

/** Find a free TCP port. */
fun findFreePort(): Int {
    val socket = java.net.ServerSocket(0)
    val port = socket.localPort
    socket.close()
    return port
}

/** Locate the relay binary by walking up from cwd. */
fun findRelayBinary(): File {
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
