package com.rousecontext.tunnel.integration

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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

    /**
     * Extract the CA private key to [keyFile] as a PKCS#8 PEM. Useful for
     * handing to the relay as its device CA so it can sign client certs
     * backed by the same trust root the tests already trust.
     *
     * Must be called after [generate].
     */
    fun writeCaKey(keyFile: File) {
        val caKs = File(tempDir, "ca.p12")
        extractPrivateKey(caKs, keyFile)
    }

    /**
     * Absolute path to the CA certificate PEM written by [generate].
     */
    fun caCertPemFile(): File = File(tempDir, "ca-cert.pem")

    /**
     * Generate an *additional* device keypair + CA-signed cert with CN =
     * `$subdomain.$relayHostname`. Returns a PKCS12 keystore containing
     * that keypair; suitable for plugging into [TestSslContexts.buildMtls]
     * to exercise mTLS as the newly-minted device.
     *
     * Must be called after [generate]. Unlike [deviceKeyStore] (which is
     * fixed at construction time), this lets tests adapt to subdomains
     * chosen dynamically by the relay during registration.
     */
    @Suppress("LongMethod")
    fun createDeviceKeyStore(subdomain: String, alias: String = "device-$subdomain"): KeyStore {
        val caKs = File(tempDir, "ca.p12")
        val deviceKs = File(tempDir, "$alias.p12")
        val csrFile = File(tempDir, "$alias.csr")
        val signedFile = File(tempDir, "$alias-signed.pem")
        val caCertFile = File(tempDir, "ca-cert.pem")
        val deviceDomain = "$subdomain.$relayHostname"

        keytool(
            "-genkeypair", "-alias", alias,
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
            "-certreq", "-alias", alias,
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-file", csrFile.absolutePath
        )
        keytool(
            "-gencert", "-alias", "ca",
            "-keystore", caKs.absolutePath,
            "-storepass", storePass,
            "-infile", csrFile.absolutePath,
            "-outfile", signedFile.absolutePath,
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
            "-importcert", "-alias", alias,
            "-keystore", deviceKs.absolutePath,
            "-storepass", storePass,
            "-file", signedFile.absolutePath
        )

        val ks = KeyStore.getInstance("PKCS12")
        deviceKs.inputStream().use { ks.load(it, storePass.toCharArray()) }
        return ks
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
 *
 * [deviceCaPaths] optionally points at an on-disk device CA key + cert PEM so
 * the relay's [`DeviceCa::load_or_create`] loads them (and the resulting
 * client cert issuance actually works for `/register/certs`). When `null` the
 * relay falls back to its hardcoded system paths and `device_ca` ends up
 * `None`, which is fine for tests that only exercise the mux/WebSocket layer.
 *
 * [disableFirebaseVerification] sets `[firebase] verify_tokens = false` so
 * handlers that verify Firebase ID tokens accept the dummy strings used in
 * tests. Default `false` matches prior test behavior.
 */
class TestRelayManager(
    private val tempDir: File,
    private val relayHostname: String,
    private val deviceCaPaths: Pair<File, File>? = null,
    private val disableFirebaseVerification: Boolean = false,
    /**
     * If non-null, written to `[server] base_domain` in the relay config.
     * Useful when `relayHostname` doesn't follow the default `relay.<domain>`
     * convention (e.g. when tests use a non-literal local hostname so SNI
     * isn't suppressed).
     */
    private val baseDomainOverride: String? = null,
    /**
     * When `true`, start the relay with `--test-mode <port>` so the test-admin
     * HTTP surface (see [TestRelayAdmin]) is available for killing WebSockets,
     * recording synthetic FCM wakes, and reading per-endpoint counters.
     *
     * Requires the relay binary to have been built with the `test-mode` Cargo
     * feature. CI's `android-ci.yml` passes `--features test-mode` in the
     * `cargo build` step for that reason. See issue #249.
     */
    private val enableTestMode: Boolean = false
) {
    var process: Process? = null
        private set

    /**
     * Admin client for the test-mode HTTP surface, or `null` when test-mode
     * was disabled at construction. Populated by [start].
     */
    var admin: TestRelayAdmin? = null
        private set

    /**
     * Write relay.toml config and start the relay process.
     * Blocks until the relay is accepting connections on [port].
     */
    fun start(port: Int): Process {
        writeRelayConfig(port)
        val adminPort = if (enableTestMode) findFreePort() else null
        val relay = startRelay(port, adminPort)
        process = relay
        if (adminPort != null) {
            admin = TestRelayAdmin(adminPort)
            admin?.waitUntilReady()
        }
        return relay
    }

    /**
     * Gracefully stop the relay subprocess with a kill -9 fallback.
     *
     * We hit zombie subprocess issues in #223 when a parent holding the
     * `Process` exited before the child actually died. The sequence is:
     *   1. `destroy()` — SIGTERM; allow graceful shutdown
     *   2. wait up to 3s
     *   3. `destroyForcibly()` — SIGKILL; always succeeds on POSIX
     *   4. wait up to 3s — on rare kernel/fs stalls this is the last line
     *      of defense before we leak the handle
     */
    fun stop() {
        process?.let { p ->
            // Phase 1: gentle SIGTERM.
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                // Phase 2: SIGKILL-equivalent.
                p.destroyForcibly()
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    System.err.println(
                        "TestRelayManager: relay subprocess (pid=${runCatching {
                            p.pid()
                        }.getOrDefault(-1)}) " +
                            "did not exit after destroyForcibly(); leaking handle"
                    )
                }
            }
        }
        process = null
        admin = null
    }

    /** Snapshot of the relay subprocess's captured stdout/stderr so far. */
    fun capturedOutput(): String = synchronized(capturedStdout) { capturedStdout.toString() }

    /**
     * Drop the active mux WebSocket for [subdomain] on the relay side without
     * sending a close frame. Simulates a relay-side crash or network drop; the
     * device-side half-open detector should fire via ping timeout.
     *
     * Requires [enableTestMode] = true. Throws if test-mode is disabled.
     *
     * Returns `true` if a live session existed and was killed, `false` if no
     * session was registered.
     */
    fun killActiveWebsocket(subdomain: String): Boolean =
        requireAdmin().killActiveWebsocket(subdomain)

    /**
     * Push a synthetic mux OPEN frame to the device on the mux session for
     * [subdomain]. Used by the batch-C "stream failure" scenarios that need an
     * already-open inbound stream before injecting failure — see issue #266.
     *
     * `streamId` must not collide with an existing stream on the session. The
     * relay's `MuxDemux` on the device side will surface the new stream on
     * `TunnelClient.incomingSessions`.
     *
     * Requires [enableTestMode] = true. Returns true if the relay accepted the
     * request; false means no mux session is currently registered for the
     * subdomain.
     */
    fun openStream(subdomain: String, streamId: Int, sniHostname: String = ""): Boolean =
        requireAdmin().openStream(subdomain, streamId, sniHostname)

    /**
     * Push a synthetic mux ERROR frame onto an already-open stream. Tests use
     * this to exercise the "stream-level failure does not tear down the
     * tunnel" regression (issue #266). The device-side `MuxDemux` will tear
     * down the stream and propagate the error but MUST leave the tunnel
     * itself in [com.rousecontext.tunnel.TunnelState.CONNECTED] (or ACTIVE
     * if other streams are still open).
     *
     * [errorCode] is a `MuxErrorCode` value (1..=4); the default
     * `STREAM_RESET` (2) mirrors the most common relay-originated failure.
     *
     * Requires [enableTestMode] = true. Returns true if the relay accepted
     * the request.
     */
    fun emitStreamError(
        subdomain: String,
        streamId: Int,
        errorCode: Int = STREAM_RESET_CODE,
        message: String = ""
    ): Boolean = requireAdmin().emitStreamError(subdomain, streamId, errorCode, message)

    /**
     * Record a synthetic FCM wake for [subdomain] on the relay. This does NOT
     * actually wake a device — it populates the relay-side test metrics so
     * assertions in integration tests can confirm a wake was requested. The
     * app-wired harness (#250) is responsible for injecting the wake into the
     * device-side FCM receiver.
     *
     * Requires [enableTestMode] = true.
     */
    fun sendFcmWake(subdomain: String) {
        requireAdmin().sendFcmWake(subdomain)
    }

    /** Count of `/register/certs` calls observed by the relay. */
    fun registerCertsCalls(): Int = requireAdmin().registerCertsCalls()

    /** Count of `/rotate-secret` calls observed by the relay. */
    fun rotateSecretCalls(): Int = requireAdmin().rotateSecretCalls()

    /** Count of `/renew` calls observed by the relay. */
    fun renewCalls(): Int = requireAdmin().renewCalls()

    /**
     * Whether the most recent request to [endpoint] (e.g. `"/renew"`) carried
     * a valid mTLS client certificate. Returns `null` if no request to that
     * endpoint has been observed yet.
     */
    fun lastRequestHadClientCert(endpoint: String): Boolean? =
        requireAdmin().lastRequestHadClientCert(endpoint)

    private fun requireAdmin(): TestRelayAdmin = admin ?: fail(
        "TestRelayManager: test-mode admin not available — " +
            "construct with enableTestMode = true"
    )

    private val capturedStdout = StringBuilder()

    private fun writeRelayConfig(port: Int) {
        val deviceCaBlock = deviceCaPaths?.let { (keyFile, certFile) ->
            """

            [device_ca]
            ca_key_path = "${keyFile.absolutePath}"
            ca_cert_path = "${certFile.absolutePath}"
            """.trimIndent()
        } ?: ""

        val firebaseBlock = if (disableFirebaseVerification) {
            """

            [firebase]
            verify_tokens = false
            """.trimIndent()
        } else {
            ""
        }

        val baseDomainLine = baseDomainOverride?.let { "base_domain = \"$it\"" } ?: ""

        val config = """
            [server]
            bind_addr = "127.0.0.1:$port"
            relay_hostname = "$relayHostname"
            $baseDomainLine

            [tls]
            cert_path = "${tempDir.absolutePath}/relay-cert.pem"
            key_path = "${tempDir.absolutePath}/relay-key.pem"
            ca_cert_path = "${tempDir.absolutePath}/ca-cert.pem"

            [limits]
            max_streams_per_device = 8
            wake_rate_limit = 60
            fcm_wakeup_timeout_secs = 5
            # Tunnel integration tests connect a simulated device directly to
            # /ws without first calling /register -> /register/certs. That path
            # relies on the relay auto-creating a Firestore record on WS upgrade
            # when the device's mTLS cert validates. Production defaults this
            # flag to false; tests opt in explicitly. See issue #209 for the
            # security rationale and issue #225 for the test regression that
            # missing this opt-in caused.
            allow_ws_auto_create_device = true
            $deviceCaBlock
            $firebaseBlock
        """.trimIndent()

        File(tempDir, "relay.toml").writeText(config)
    }

    private fun startRelay(port: Int, testAdminPort: Int? = null): Process {
        val relayBinary = findRelayBinary()
        val configPath = File(tempDir, "relay.toml").absolutePath
        val cmd = mutableListOf(relayBinary.absolutePath, configPath)
        if (testAdminPort != null) {
            cmd += listOf("--test-mode", testAdminPort.toString())
        }
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "debug"

        val process = pb.start()
        val deadline = System.currentTimeMillis() + RELAY_STARTUP_TIMEOUT_MS

        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(capturedStdout) { capturedStdout.appendLine(line) }
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
                val output = synchronized(capturedStdout) { capturedStdout.toString() }
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
            val output = synchronized(capturedStdout) { capturedStdout.toString() }
            fail(
                "Relay did not start within ${RELAY_STARTUP_TIMEOUT_MS}ms. Output:\n$output"
            )
        }

        Thread.sleep(200)
        return process
    }

    companion object {
        private const val RELAY_STARTUP_TIMEOUT_MS = 10_000L

        /**
         * `MuxErrorCode.STREAM_RESET`, mirrored from the relay / Kotlin tunnel
         * side so tests don't have to import the tunnel module (the fixture
         * lives in `:core:testfixtures`, which intentionally does not depend
         * on `:core:tunnel`).
         */
        const val STREAM_RESET_CODE: Int = 2
    }
}

/**
 * Thin HTTP client against the relay's test-mode admin surface.
 *
 * The relay exposes these endpoints on a separate plain-HTTP listener bound to
 * `127.0.0.1:<port>` when launched with `--test-mode <port>` (requires the
 * `test-mode` Cargo feature). See `relay/src/test_mode.rs` for the server-side
 * handlers.
 *
 * This client is intentionally tiny — no OkHttp, no serialisation library,
 * just JDK URLConnection + naive JSON parsing — so it can be used without
 * adding test dependencies and won't interact with the OkHttp MockWebServer
 * instances that integration tests set up.
 *
 * Fixture lifetime mirrors the relay subprocess: created in [TestRelayManager.start],
 * cleared in [TestRelayManager.stop].
 *
 * Issue #249.
 */
@Suppress("TooManyFunctions")
class TestRelayAdmin(private val port: Int) {
    private val baseUrl = "http://127.0.0.1:$port"

    /**
     * Poll `/test/stats` until the admin server answers (or a short deadline
     * elapses). Called by [TestRelayManager.start] so fixture code can assume
     * the admin is ready as soon as `start()` returns.
     */
    fun waitUntilReady(deadlineMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + deadlineMs
        var lastErr: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                stats()
                return
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(READY_POLL_MS)
            }
        }
        fail("test-mode admin did not become ready within ${deadlineMs}ms: $lastErr")
    }

    /**
     * Abort the active mux WebSocket for [subdomain] without sending a close
     * frame. The device-side ping loop should notice the dead socket within
     * one ping interval.
     *
     * Returns `true` if the relay had a live session to kill, `false` if no
     * session was registered for that subdomain.
     */
    fun killActiveWebsocket(subdomain: String): Boolean {
        val body = post("/test/kill-ws?subdomain=${subdomain.urlEncoded()}", "")
        return parseBoolField(body, "killed")
    }

    /**
     * Record a synthetic FCM wake for [subdomain]. The relay accumulates the
     * call in its test metrics; callers that need to actually invoke the
     * device-side FCM receiver must supply their own `onWake` callback (this
     * hook just tells the relay-side fake that a wake was requested so
     * [stats] assertions see it).
     */
    fun sendFcmWake(subdomain: String) {
        post("/test/fcm-wake?subdomain=${subdomain.urlEncoded()}", "")
    }

    /**
     * Push a synthetic OPEN frame to the device on the mux session for
     * [subdomain]. Wraps `POST /test/open-stream`. See the [TestRelayManager]
     * counterpart for scenario notes.
     *
     * Returns true if the relay enqueued the frame, false if no session is
     * registered for the subdomain.
     */
    fun openStream(subdomain: String, streamId: Int, sniHostname: String): Boolean {
        val path = buildString {
            append("/test/open-stream?subdomain=")
            append(subdomain.urlEncoded())
            append("&stream_id=")
            append(streamId)
            if (sniHostname.isNotEmpty()) {
                append("&sni=")
                append(sniHostname.urlEncoded())
            }
        }
        val (code, body) = postExpectingAny(path)
        return when (code) {
            HTTP_OK -> parseBoolField(body, "opened")
            HTTP_NOT_FOUND -> false
            else -> fail("test-mode admin returned HTTP $code on open-stream: $body")
        }
    }

    /**
     * Push a synthetic ERROR frame onto an already-open mux stream. Wraps
     * `POST /test/emit-stream-error`.
     *
     * Returns true if the relay enqueued the frame, false if no session is
     * registered for the subdomain.
     */
    fun emitStreamError(
        subdomain: String,
        streamId: Int,
        errorCode: Int,
        message: String
    ): Boolean {
        val path = buildString {
            append("/test/emit-stream-error?subdomain=")
            append(subdomain.urlEncoded())
            append("&stream_id=")
            append(streamId)
            append("&code=")
            append(errorCode)
            if (message.isNotEmpty()) {
                append("&message=")
                append(message.urlEncoded())
            }
        }
        val (code, body) = postExpectingAny(path)
        return when (code) {
            HTTP_OK -> parseBoolField(body, "emitted")
            HTTP_NOT_FOUND -> false
            else -> fail(
                "test-mode admin returned HTTP $code on emit-stream-error: $body"
            )
        }
    }

    /** Number of `/register` calls observed since the relay started. */
    fun registerCalls(): Int = parseIntField(statsRaw(), "register_calls")

    /** Number of `/register/certs` calls observed since the relay started. */
    fun registerCertsCalls(): Int = parseIntField(statsRaw(), "register_certs_calls")

    /** Number of `/rotate-secret` calls observed since the relay started. */
    fun rotateSecretCalls(): Int = parseIntField(statsRaw(), "rotate_secret_calls")

    /** Number of `/renew` calls observed since the relay started. */
    fun renewCalls(): Int = parseIntField(statsRaw(), "renew_calls")

    /** Number of `/ws` upgrade requests observed since the relay started. */
    fun wsCalls(): Int = parseIntField(statsRaw(), "ws_calls")

    /**
     * Whether the most recent request to [endpoint] (e.g. `"/renew"`) carried
     * a valid mTLS client certificate. Returns `null` if no such request has
     * been observed.
     */
    fun lastRequestHadClientCert(endpoint: String): Boolean? {
        val body = statsRaw()
        val marker = "\"$endpoint\":"
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        val after = body.substring(idx + marker.length).trimStart()
        return when {
            after.startsWith("true") -> true
            after.startsWith("false") -> false
            else -> null
        }
    }

    /** Subdomains captured by synthetic `/test/fcm-wake` calls, in arrival order. */
    fun capturedWakes(): List<String> {
        val body = statsRaw()
        val marker = "\"captured_wakes\":"
        val idx = body.indexOf(marker)
        if (idx < 0) return emptyList()
        val open = body.indexOf('[', idx)
        val close = body.indexOf(']', open)
        if (open < 0 || close < 0) return emptyList()
        val inner = body.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    /** Structured stats snapshot. */
    data class Stats(
        val registerCalls: Int,
        val registerCertsCalls: Int,
        val rotateSecretCalls: Int,
        val renewCalls: Int,
        val wsCalls: Int
    )

    /** Convenience accessor that returns a typed snapshot. */
    fun stats(): Stats {
        val body = statsRaw()
        return Stats(
            registerCalls = parseIntField(body, "register_calls"),
            registerCertsCalls = parseIntField(body, "register_certs_calls"),
            rotateSecretCalls = parseIntField(body, "rotate_secret_calls"),
            renewCalls = parseIntField(body, "renew_calls"),
            wsCalls = parseIntField(body, "ws_calls")
        )
    }

    private fun statsRaw(): String = get("/test/stats")

    private fun get(path: String): String {
        val conn = openConnection(path).apply {
            requestMethod = "GET"
        }
        return readBody(conn)
    }

    private fun post(path: String, body: String): String {
        val conn = openConnection(path).apply {
            requestMethod = "POST"
            doOutput = true
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readBody(conn)
    }

    /**
     * Like [post] but returns both the HTTP status code and the response body
     * without failing on non-2xx responses. Used by admin handlers that
     * deliberately return 404 for "subdomain not registered" so callers can
     * distinguish that from a protocol error.
     */
    private fun postExpectingAny(path: String): Pair<Int, String> {
        val conn = openConnection(path).apply {
            requestMethod = "POST"
            doOutput = true
        }
        conn.outputStream.use { it.write(ByteArray(0)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    private fun openConnection(path: String): HttpURLConnection {
        val url: URL = URI.create(baseUrl + path).toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            fail("test-mode admin returned HTTP $code: $body")
        }
        return body
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun parseIntField(json: String, key: String): Int {
        val marker = "\"$key\":"
        val idx = json.indexOf(marker)
        if (idx < 0) fail("stats JSON missing field '$key': $json")
        val after = json.substring(idx + marker.length).trimStart()
        val end = after.indexOfAny(charArrayOf(',', '}', ' ', '\n', '\r'))
        val numStr = if (end < 0) after else after.substring(0, end)
        return numStr.trim().toInt()
    }

    private fun parseBoolField(json: String, key: String): Boolean {
        val marker = "\"$key\":"
        val idx = json.indexOf(marker)
        if (idx < 0) fail("JSON missing field '$key': $json")
        val after = json.substring(idx + marker.length).trimStart()
        return after.startsWith("true")
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 3_000
        private const val READY_POLL_MS = 50L
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
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
