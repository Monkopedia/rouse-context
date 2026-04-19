package com.rousecontext.app.integration.harness

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.McpIntegration
import com.rousecontext.app.di.appModule
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertRenewalFlow
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.CsrGenerator
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.integration.TestCertificateAuthority
import com.rousecontext.tunnel.integration.TestRelayManager
import com.rousecontext.tunnel.integration.findFreePort
import com.rousecontext.tunnel.integration.findRelayBinary
import com.rousecontext.work.CertRenewalWorker
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import org.junit.Assume.assumeTrue
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Boots the real [appModule] Koin graph against a [TestRelayManager] running
 * with `--test-mode` so integration-tier tests can exercise production code
 * paths (cert provisioning, secrets push, etc.) end-to-end without talking
 * to real Firebase / ACME / Firestore.
 *
 * # What's real vs. what's faked
 *
 * Real:
 *   - Every singleton in [appModule] — [CertProvisioningFlow],
 *     [CertRenewalFlow], [OnboardingFlow], [CertificateStore], the full
 *     provider registry, audit pipeline, Koin graph, Room + DataStore.
 *   - The relay: actual `rouse-relay` subprocess, actual TLS 1.3 handshake,
 *     actual mTLS client-cert presentation on `/rotate-secret` etc.
 *   - Cryptography: real JDK `Signature` / CSR generation / ASN.1 encoding.
 *
 * Faked (by [buildTestOverrides] — each fake is documented inline below):
 *   - ACME / Firestore / FCM (relay-side): the relay runs with in-memory
 *     stubs via `--test-mode` + `disableFirebaseVerification = true`, so
 *     `/register` / `/register/certs` issue from a test CA and skip ACME.
 *   - [DeviceKeyManager]: software-backed EC keypair in memory. The
 *     production `AndroidKeystoreDeviceKeyManager` uses the `AndroidKeyStore`
 *     JCA provider which is not available on a pure JVM (Robolectric's
 *     Android runtime stubs don't ship it), so tests would crash at
 *     `KeyPairGenerator.getInstance(EC, AndroidKeyStore)`.
 *   - [RelayApiClient]: rebuilt to point at `https://<testHost>:<port>`
 *     (the fixture's loopback relay) with a custom OkHttp DNS override,
 *     trusting the test CA.
 *   - [HealthConnectRepository]: [HarnessFakeHealthConnectRepository]. The real
 *     implementation calls into `androidx.health.connect` which requires a
 *     Play Services-backed service on the device.
 *   - `BuildConfig.BASE_DOMAIN` / `BuildConfig.RELAY_HOST`: effectively
 *     overridden by replacing the singletons that read them ([RelayApiClient],
 *     [CertProvisioningFlow], `named("relayUrl")`, the [CertRenewalWorker]
 *     base-domain qualifier, and [com.rousecontext.tunnel.CtLogMonitor]).
 *     `BuildConfig` itself is baked at build time and cannot be mutated.
 *
 * # Lifecycle
 *
 * Intended pattern:
 * ```
 * val harness = AppIntegrationTestHarness()
 *
 * @Before fun setUp() { harness.start() }
 * @After  fun tearDown() { harness.stop() }
 *
 * @Test fun `my scenario`() = runBlocking {
 *     val flow = harness.koin.get<CertProvisioningFlow>()
 *     ...
 *     assertEquals(1, harness.fixture.registerCertsCalls())
 * }
 * ```
 *
 * [start] returns early with a JUnit assumption-skip if `rouse-relay` was
 * not built (mirrors every other integration test's behaviour) so local
 * `:app:testDebugUnitTest` runs are never blocked.
 *
 * Safe to call [start] / [stop] repeatedly — each call is paired with its
 * own Koin instance and relay subprocess.
 *
 * Issue #250 (umbrella #247). Thread-safety is NOT a goal: tests run
 * sequentially.
 */
class AppIntegrationTestHarness(
    /**
     * Subdomain used when generating the test CA's initial device cert.
     * Overridden in-place once real onboarding runs and the relay hands
     * back the actual subdomain (tests that provision a cert should
     * rebuild their own [KeyStore] via [certificateAuthority] afterwards).
     */
    private val initialSubdomain: String = "smoke-device",
    private val relayHostname: String = "relay.test.local",
    private val baseDomain: String = "relay.test.local",
    /**
     * Integrations to register in place of the production list. Issue #252
     * scenarios need at least one integration wired into the Koin graph
     * (the default `emptyList()` leaves `ProviderRegistry` empty, so the
     * MCP session has no tools to call). Callers provide a lightweight
     * in-test integration like `TestEchoMcpIntegration` here.
     */
    private val integrationsFactory: () -> List<McpIntegration> = { emptyList() }
) {
    // ---- Lifecycle state ----
    // All set by [start], cleared by [stop]. The backing storage is intentionally
    // mutable — tests call [start] / [stop] pairs that replace these in place.
    // The public read-only accessors below throw if accessed before [start].
    private var tempDir: File? = null
    private var ca: TestCertificateAuthority? = null
    private var relayManager: TestRelayManager? = null
    private var relayPortOrNegative: Int = -1
    private var koinInstance: Koin? = null
    private var harnessScope: CoroutineScope? = null
    private var fakeHealth: HarnessFakeHealthConnectRepository? = null

    // ---- Public inspectors ----

    /** Koin instance booted with `appModule` + test overrides. */
    val koin: Koin
        get() = koinInstance ?: error("Harness not started — call start() first")

    /** The test relay fixture. Use `.killActiveWebsocket()`, `.sendFcmWake()`, `.stats()` etc. */
    val fixture: TestRelayManager
        get() = relayManager ?: error("Harness not started — call start() first")

    /** Port the relay is listening on. */
    val relayPort: Int
        get() = relayPortOrNegative.also { require(it > 0) { "Harness not started" } }

    /** Access to the test CA; useful for building additional device keystores. */
    val certificateAuthority: TestCertificateAuthority
        get() = ca ?: error("Harness not started — call start() first")

    /** Fake `HealthConnectRepository` registered in the override module. */
    val fakeHealthConnectRepository: HarnessFakeHealthConnectRepository
        get() = fakeHealth ?: error("Harness not started — call start() first")

    /**
     * Shortcut to the real [CertificateStore] singleton. Tests that want to
     * assert something like "subdomain is persisted" reach here rather than
     * through `koin.get<CertificateStore>()`.
     */
    val certStore: CertificateStore
        get() = koin.get<CertificateStore>()

    /** Shortcut to the overridden [RelayApiClient]. */
    val relayApiClient: RelayApiClient
        get() = koin.get<RelayApiClient>()

    // ---- Lifecycle ----

    /**
     * Prepare the test CA, start the relay subprocess with `--test-mode`, and
     * boot Koin with `appModule + testOverrides`.
     *
     * Skips the whole test (via [assumeTrue]) if the relay binary is missing,
     * matching the rest of the integration suite.
     */
    fun start() {
        val relayBinary = findRelayBinary()
        assumeTrue(
            "Relay binary not found. Build with: cd relay && cargo build --features test-mode",
            relayBinary.exists() && relayBinary.canExecute()
        )

        val newTempDir = File.createTempFile("app-integration-", "")
        newTempDir.delete()
        newTempDir.mkdirs()
        tempDir = newTempDir

        val newCa = TestCertificateAuthority(newTempDir, relayHostname, initialSubdomain)
        newCa.generate()
        ca = newCa

        // Hand the test CA to the relay as its device-cert issuer so the
        // certs it signs during `/register/certs` chain to the same root
        // our tests already trust.
        val deviceCaKeyFile = File(newTempDir, "device-ca-key.pem")
        newCa.writeCaKey(deviceCaKeyFile)
        val deviceCaCertFile = newCa.caCertPemFile()

        val newRelayManager = TestRelayManager(
            tempDir = newTempDir,
            relayHostname = relayHostname,
            deviceCaPaths = deviceCaKeyFile to deviceCaCertFile,
            disableFirebaseVerification = true,
            baseDomainOverride = baseDomain,
            enableTestMode = true
        )
        val newPort = findFreePort()
        relayPortOrNegative = newPort
        newRelayManager.start(newPort)
        relayManager = newRelayManager

        // Stop any stale Koin from a previous test in the same JVM — see the
        // same idempotent pattern in AuthApprovalReceiverTest.
        if (GlobalContext.getOrNull() != null) {
            runCatching { stopKoin() }
        }

        val newHarnessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harnessScope = newHarnessScope

        val newFakeHealth = HarnessFakeHealthConnectRepository()
        fakeHealth = newFakeHealth

        val appContext: Context = ApplicationProvider.getApplicationContext<Application>()
        koinInstance = startKoin {
            androidContext(appContext)
            modules(
                appModule,
                buildTestOverrides(
                    ca = newCa,
                    relayPort = newPort,
                    relayHostname = relayHostname,
                    baseDomain = baseDomain,
                    harnessScope = newHarnessScope,
                    fakeHealth = newFakeHealth,
                    integrations = integrationsFactory()
                )
            )
        }.koin
    }

    /**
     * Tear down Koin, stop the relay subprocess, cancel the harness scope,
     * and wipe the temp directory. Safe to call multiple times; subsequent
     * calls are no-ops.
     */
    fun stop() {
        runCatching { if (GlobalContext.getOrNull() != null) stopKoin() }
        koinInstance = null

        harnessScope?.cancel()
        harnessScope = null

        relayManager?.let { relay ->
            // Dump relay stdout/stderr on teardown — the fixture itself does
            // not log, so a silent failure in the rust side would be opaque
            // without this. Matches the RelayApiClientIntegrationTest habit.
            System.err.println("--- relay stdout/stderr ---")
            System.err.println(relay.capturedOutput())
            System.err.println("--- end relay output ---")
            relay.stop()
        }
        relayManager = null
        relayPortOrNegative = -1
        ca = null
        fakeHealth = null

        tempDir?.takeIf { it.exists() }?.deleteRecursively()
        tempDir = null
    }
}

/**
 * Assemble the Koin module that overrides the handful of `appModule`
 * singletons that would otherwise reach for real Firebase / Firestore /
 * Android Keystore / production URLs.
 *
 * Each override is as surgical as possible: we re-declare exactly the
 * singleton definition from `appModule` but swap its arguments. Production
 * wiring (CertProvisioningFlow, OnboardingFlow, ...) stays intact — those
 * types are still constructed from the real classes.
 *
 * `@Suppress("LongMethod")` — split into sub-functions would obscure the
 * parallel structure with [appModule].
 */
@Suppress("LongMethod", "LongParameterList")
private fun buildTestOverrides(
    ca: TestCertificateAuthority,
    relayPort: Int,
    relayHostname: String,
    baseDomain: String,
    harnessScope: CoroutineScope,
    fakeHealth: HarnessFakeHealthConnectRepository,
    integrations: List<McpIntegration>
) = module {
    // --- appScope override ---
    // Production supplies a main-dispatcher scope from RouseApplication. We
    // run Koin without an Application, so wire our own supervised scope
    // that matches the coroutine conventions in .claude/rules/coroutines.md.
    single<CoroutineScope>(named("appScope")) { harnessScope }

    // --- HealthConnectRepository ---
    // Real RealHealthConnectRepository goes through `HealthConnectClient`
    // which needs the Play Services Health Connect provider. Robolectric
    // lacks it; tests point at the in-memory fake.
    single<HealthConnectRepository> { fakeHealth }

    // --- Integrations list override ---
    // The production list pulls in `NotificationIntegration`, which Koin
    // resolves eagerly (`ProviderRegistry` is `createdAtStart = true`).
    // NotificationIntegration needs `FieldEncryptor`, whose constructor
    // hits `KeyStore.getInstance("AndroidKeyStore")` — that provider is
    // not shipped in Robolectric's SDK 33 runtime, so Koin start would
    // cascade-fail with `NoSuchAlgorithmException: AndroidKeyStore`.
    //
    // Tests that need providers wired into the MCP session pass them via
    // `AppIntegrationTestHarness(integrationsFactory = { ... })`; the
    // default is the empty list (onboarding / cert-provisioning scenarios
    // don't talk to the MCP server and don't need providers).
    single<List<McpIntegration>> { integrations }

    // --- DeviceKeyManager ---
    // AndroidKeystoreDeviceKeyManager requires the AndroidKeyStore JCA
    // provider (not available on JVM/Robolectric). Swap in a software EC
    // keypair that speaks the same interface; production code paths never
    // notice because the interface contract is "return a KeyPair".
    single<DeviceKeyManager> { SoftwareDeviceKeyManager() }

    // --- RelayApiClient override ---
    // Production [appModule] builds one of these with BuildConfig.RELAY_HOST.
    // BuildConfig is baked at build time so we cannot mutate it; instead,
    // we replace the singleton with one pointed at the fixture loopback.
    //
    // The mTLS identity is served by [DynamicPkcs12CertSource], which
    // rebuilds a fresh `SunX509`-compatible `X509KeyManager` from the
    // current contents of [CertificateStore] + [DeviceKeyManager] on every
    // TLS handshake. This lets the harness exercise the same "switch from
    // unauthenticated to mTLS mid-lifecycle" shape that is the regression
    // guard for issue #237 in production code (`createMtlsRelayHttpClient` +
    // `LazyMtlsKeyManager` + `FileMtlsCertSource`): before provisioning
    // writes a cert we present none, after we present one. The difference
    // is only in *how* the key manager is built (PKCS12-backed vs
    // PEM+LazyManager) — a detail dictated by Conscrypt's TLS stack on
    // Robolectric silently ignoring custom `X509ExtendedKeyManager` alias
    // chooser callbacks. Production's `LazyMtlsKeyManager` path is
    // regression-guarded separately at the JVM-test layer in
    // `RelayApiClientIntegrationTest`.
    //
    // The OkHttp engine is configured with a DNS override so `$relayHostname`
    // resolves to 127.0.0.1 (the fixture runs on loopback) and a loose
    // hostname verifier because the test relay cert uses the literal
    // hostname as SAN — the default OkHttp verifier rejects non-FQDN
    // SANs during TLS.
    single {
        val ctx = androidContext()
        val deviceKeyManager: DeviceKeyManager = get()
        val certStoreRef: CertificateStore = get()
        val httpClient = buildFixtureMtlsHttpClient(
            caCert = ca.caCert,
            keyManagerSource = {
                buildKeyManagerFromDiskIfProvisioned(ctx, deviceKeyManager, certStoreRef)
            }
        )
        RelayApiClient(
            baseUrl = "https://$relayHostname:$relayPort",
            httpClient = httpClient
        )
    }

    // --- OnboardingFlow override ---
    // appModule pins `OnboardingFlow.integrationIds` from the Koin
    // List<McpIntegration>, which still resolves correctly through the
    // base module. We just need to re-declare it so the override module
    // wins for the `RelayApiClient` dep (Koin picks the *last* definition
    // per type, and re-declaring ensures the overridden RelayApiClient is
    // threaded through).
    single {
        OnboardingFlow(
            relayApiClient = get(),
            certificateStore = get(),
            integrationIds = get<List<McpIntegration>>().map { it.id }
        )
    }

    // --- CertProvisioningFlow override ---
    // Pins defaultBaseDomain to the fixture base domain; the production
    // definition reads BuildConfig.BASE_DOMAIN which is always the real
    // `rousecontext.com` (or whatever -Pdomain was passed at build time).
    single {
        CertProvisioningFlow(
            csrGenerator = get(),
            relayApiClient = get(),
            certificateStore = get(),
            deviceKeyManager = get(),
            defaultBaseDomain = baseDomain
        )
    }

    // --- CertRenewalFlow override ---
    // Same reason as CertProvisioningFlow: picks up the overridden
    // RelayApiClient + DeviceKeyManager.
    single {
        CertRenewalFlow(
            csrGenerator = get<CsrGenerator>(),
            relayApiClient = get(),
            certificateStore = get(),
            deviceKeyManager = get()
        )
    }

    // --- CertRenewalWorker base domain ---
    // Production binds this qualified String to BuildConfig.BASE_DOMAIN.
    single<String>(named(CertRenewalWorker.KOIN_BASE_DOMAIN_NAME)) { baseDomain }

    // --- Tunnel relay URL ---
    // `wss://<fixture>:<port>/ws` — the device-side TunnelClient in the
    // production graph reads this qualifier to pick the relay endpoint.
    single<String>(named("relayUrl")) {
        "wss://$relayHostname:$relayPort/ws"
    }

    // --- AuditListener override (no FieldEncryptor) ---
    // Production's `RoomAuditListener` encrypts sensitive fields with a
    // [com.rousecontext.notifications.FieldEncryptor] backed by the
    // AndroidKeyStore JCA provider, which isn't shipped in Robolectric's
    // SDK 33 runtime. `RoomAuditListener` already takes a nullable
    // [FieldEncryptor], so we just re-bind the AuditListener with `null`.
    single<com.rousecontext.mcp.core.AuditListener> {
        com.rousecontext.notifications.audit.RoomAuditListener(
            dao = get(),
            fieldEncryptor = null,
            perCallObserver = get(),
            mcpRequestDao = get()
        )
    }
}

// ----------------------------------------------------------------------
// Supporting fakes
// ----------------------------------------------------------------------

/**
 * Software-only [DeviceKeyManager] using the JVM's `SunEC` provider.
 *
 * The production implementation (`AndroidKeystoreDeviceKeyManager`) generates
 * the key inside the secure element (StrongBox / TEE); that code path cannot
 * run on a pure JVM because the `AndroidKeyStore` JCA provider is Android
 * framework code. Robolectric does not ship it.
 *
 * Interface-identical: returns a [KeyPair] whose `PrivateKey` can drive a
 * `SHA256withECDSA` `Signature`. Everything downstream (CSR generation,
 * mTLS key manager) is oblivious.
 *
 * The keypair is generated lazily on first call and cached for the
 * lifetime of this instance, matching the "idempotent getOrCreate" shape
 * of the production API.
 */
private class SoftwareDeviceKeyManager : DeviceKeyManager {
    @Volatile
    private var cached: KeyPair? = null

    override fun getOrCreateKeyPair(): KeyPair = cached ?: synchronized(this) {
        cached ?: KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().also { cached = it }
    }
}

/**
 * Ktor [HttpClient] that presents the device mTLS identity on every handshake.
 *
 * [keyManagerSource] is invoked per-handshake (via a
 * [DelegatingX509KeyManager] wrapper) so that the harness can point at the
 * live on-disk [CertificateStore] state: before `/register/certs` writes a
 * cert the source returns `null` and we present nothing (unauthenticated
 * onboarding), and once provisioning lands we present the freshly-issued
 * cert. That "switch from no-cert to with-cert on a long-lived client" is
 * exactly the regression shape #237 fixed in production.
 *
 * Production-side [createMtlsRelayHttpClient] works fine on real Android and
 * plain JVM via `LazyMtlsKeyManager`. Robolectric loads Conscrypt as the
 * default TLS provider, and Conscrypt's `OpenSSLContextImpl` does not call
 * custom `X509ExtendedKeyManager.chooseEngineClientAlias` for TLS 1.2/1.3
 * client auth — the handshake completes but the Certificate message sent
 * to the relay is empty. We side-step that here by wrapping a
 * `SunX509`-compatible [X509KeyManager] built from a PKCS12 `KeyStore`
 * (the JCA contract Conscrypt does honour for this case). Production's
 * `LazyMtlsKeyManager` path stays regression-guarded at the JVM-test layer
 * in `RelayApiClientIntegrationTest`.
 *
 * Test-side customisations beyond the PKCS12-backed KeyManager:
 *   1. A test trust manager that trusts the fixture CA (production trusts
 *      the JVM default store, which is fine for real GTS certs but rejects
 *      the fixture's `CN=Test CA` root).
 *   2. A DNS override that collapses `$relayHostname` to 127.0.0.1 (the
 *      fixture binds to loopback) and a loose hostname verifier because
 *      OkHttp's default verifier rejects non-FQDN SANs like `relay.test.local`.
 */
private fun buildFixtureMtlsHttpClient(
    caCert: X509Certificate,
    keyManagerSource: () -> javax.net.ssl.X509KeyManager?
): HttpClient {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
    trustStore.load(null, null)
    trustStore.setCertificateEntry("ca", caCert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(trustStore)
    val trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
        ?: error("No X509TrustManager")

    // Build a fresh SSLContext per-TLS-handshake so we never hit Conscrypt's
    // TLS 1.3 session resumption cache (which bypasses client-auth
    // renegotiation and presents no cert on resumed connections).
    val freshContextFactory = FreshSslContextSocketFactory(
        trustManagers = tmf.trustManagers,
        keyManagerSource = keyManagerSource
    )

    return HttpClient(OkHttp) {
        engine {
            config {
                sslSocketFactory(freshContextFactory, trustManager)
                connectionPool(
                    ConnectionPool(
                        /* maxIdleConnections = */
                        0,
                        /* keepAliveDuration = */
                        1L,
                        /* timeUnit = */
                        TimeUnit.SECONDS
                    )
                )
                hostnameVerifier { _, _ -> true }
                dns(LoopbackDns)
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = FIXTURE_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = FIXTURE_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = FIXTURE_SOCKET_TIMEOUT_MS
        }
    }
}

/**
 * Load the current on-disk device cert + relay CA cert and combine them
 * with the device private key into a PKCS12-backed [javax.net.ssl.X509KeyManager].
 *
 * Returns `null` when no client cert is on disk yet (pre-provisioning); the
 * TLS engine then presents no client certificate, which is correct for
 * unauthenticated onboarding endpoints.
 */
private fun buildKeyManagerFromDiskIfProvisioned(
    context: Context,
    deviceKeyManager: DeviceKeyManager,
    @Suppress("UNUSED_PARAMETER") certStoreRef: com.rousecontext.tunnel.CertificateStore
): javax.net.ssl.X509KeyManager? {
    val clientCertFile = File(context.filesDir, "rouse_client_cert.pem")
    val relayCaFile = File(context.filesDir, "rouse_relay_ca.pem")
    if (!clientCertFile.exists() || !relayCaFile.exists()) return null

    val factory = java.security.cert.CertificateFactory.getInstance("X.509")
    val clientCert = factory.generateCertificate(
        clientCertFile.inputStream()
    ) as X509Certificate
    val relayCa = factory.generateCertificate(
        relayCaFile.inputStream()
    ) as X509Certificate

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry(
        "device",
        deviceKeyManager.getOrCreateKeyPair().private,
        PKCS12_PASS.toCharArray(),
        arrayOf(clientCert, relayCa)
    )
    val kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509")
    kmf.init(ks, PKCS12_PASS.toCharArray())
    return kmf.keyManagers.firstOrNull { it is javax.net.ssl.X509KeyManager }
        as? javax.net.ssl.X509KeyManager
}

/**
 * Wraps a lazily-supplied [X509KeyManager] so each TLS handshake re-consults
 * the source. Extends [javax.net.ssl.X509ExtendedKeyManager] because
 * Conscrypt's TLS stack (Robolectric's default) ignores plain
 * `X509KeyManager` implementations entirely and presents no client cert.
 *
 * All `choose*Alias` calls are forwarded to the current snapshot; when
 * `source` returns `null` (no cert provisioned yet) every alias call
 * returns `null`, which tells the TLS engine to present no client cert —
 * matching the production behaviour before `/register/certs`.
 */
private class DelegatingX509KeyManager(private val source: () -> javax.net.ssl.X509KeyManager?) :
    javax.net.ssl.X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?) =
        source()?.getClientAliases(keyType, issuers)

    override fun chooseClientAlias(
        keyType: Array<String>?,
        issuers: Array<java.security.Principal>?,
        socket: java.net.Socket?
    ) = source()?.chooseClientAlias(keyType, issuers, socket)

    override fun chooseEngineClientAlias(
        keyType: Array<String>?,
        issuers: Array<java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?
    ) = source()?.chooseClientAlias(keyType, issuers, null)

    override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?) =
        source()?.getServerAliases(keyType, issuers)

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<java.security.Principal>?,
        socket: java.net.Socket?
    ) = source()?.chooseServerAlias(keyType, issuers, socket)

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<java.security.Principal>?,
        engine: javax.net.ssl.SSLEngine?
    ) = source()?.chooseServerAlias(keyType, issuers, null)

    override fun getCertificateChain(alias: String?) = source()?.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?) = source()?.getPrivateKey(alias)
}

private const val PKCS12_PASS = "harness"

/**
 * [javax.net.ssl.SSLSocketFactory] that builds a fresh [SSLContext] +
 * [DelegatingX509KeyManager] on every `createSocket` call. This defeats
 * Conscrypt's TLS 1.3 session-resumption cache, which on the Robolectric
 * Android runtime will otherwise reuse a pre-cert session for subsequent
 * calls and skip client-auth negotiation entirely — the Certificate
 * message goes out empty and the relay returns 401.
 *
 * Each handshake re-consults [keyManagerSource], mirroring the per-handshake
 * lookup production's `LazyMtlsKeyManager` performs.
 */
private class FreshSslContextSocketFactory(
    private val trustManagers: Array<javax.net.ssl.TrustManager>,
    private val keyManagerSource: () -> javax.net.ssl.X509KeyManager?
) : javax.net.ssl.SSLSocketFactory() {

    private fun freshFactory(): javax.net.ssl.SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            arrayOf(DelegatingX509KeyManager(keyManagerSource)),
            trustManagers,
            null
        )
        sslContext.clientSessionContext?.sessionCacheSize = 1
        sslContext.clientSessionContext?.sessionTimeout = 1
        return sslContext.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = freshFactory().defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = freshFactory().supportedCipherSuites

    override fun createSocket(): java.net.Socket = freshFactory().createSocket()

    override fun createSocket(
        s: java.net.Socket?,
        host: String?,
        port: Int,
        autoClose: Boolean
    ): java.net.Socket = freshFactory().createSocket(s, host, port, autoClose)

    override fun createSocket(host: String?, port: Int): java.net.Socket =
        freshFactory().createSocket(host, port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: java.net.InetAddress?,
        localPort: Int
    ): java.net.Socket = freshFactory().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
        freshFactory().createSocket(host, port)

    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int
    ): java.net.Socket = freshFactory().createSocket(address, port, localAddress, localPort)
}

/**
 * Collapse every DNS lookup to 127.0.0.1. Safe because the harness only ever
 * runs one relay and the hostname-based TLS SNI is separately validated via
 * the test CA.
 *
 * Collocated with the integration tests in `:core:tunnel` that already use
 * an identical loopback DNS (see `Ipv4OnlyDns` there).
 */
private object LoopbackDns : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByName("127.0.0.1"))
}

// Shorter than production timeouts — integration tests hit a local subprocess
// so multi-minute waits would only mask hangs.
private const val FIXTURE_REQUEST_TIMEOUT_MS = 30_000L
private const val FIXTURE_CONNECT_TIMEOUT_MS = 10_000L
private const val FIXTURE_SOCKET_TIMEOUT_MS = 30_000L
