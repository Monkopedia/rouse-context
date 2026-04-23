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
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
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

    /**
     * Thread-safe list of `/rotate-secret` request bodies observed by the
     * OkHttp interceptor installed on the harness [RelayApiClient] ([buildFixtureMtlsHttpClient]).
     *
     * Each entry is the `integrations` array from the POST body — the ordered
     * list of integration IDs the app pushed in that call. Tests for issues
     * #274/#275/#276 consult this to assert which integrations were included
     * in each rotate payload; the relay's test-mode admin only surfaces call
     * counts, not bodies, so payload-level assertions need this client-side
     * capture instead.
     *
     * Backed by a synchronized list so reads from the test thread see writes
     * from the OkHttp dispatcher threads without extra locking.
     */
    private val capturedRotateSecretPayloads: MutableList<List<String>> =
        Collections.synchronizedList(mutableListOf())

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

    /**
     * Snapshot of the `integrations` payloads observed on every `/rotate-secret`
     * request, in arrival order. Issue #274/#275/#276 tests use this to verify
     * the exact list the app pushed (the relay's `/test/stats` only surfaces
     * counts, not bodies).
     */
    fun capturedRotateSecretPayloads(): List<List<String>> =
        synchronized(capturedRotateSecretPayloads) {
            capturedRotateSecretPayloads.map { it.toList() }
        }

    /** Clear any previously captured `/rotate-secret` payloads. */
    fun clearCapturedRotateSecretPayloads() {
        synchronized(capturedRotateSecretPayloads) {
            capturedRotateSecretPayloads.clear()
        }
    }

    // ---- Lifecycle ----

    /**
     * Prepare the test CA, start the relay subprocess with `--test-mode`, and
     * boot Koin with `appModule + testOverrides`.
     *
     * Skips the whole test (via [assumeTrue]) if the relay binary is missing,
     * matching the rest of the integration suite.
     */
    fun start() {
        // Capture the pre-swap provider list snapshot (see [stop]).
        snapshotSecurityProviders()

        // Insert BouncyCastle JSSE + Prov at JCA priority 1 for the
        // duration of this harness. Robolectric registers Conscrypt as the
        // default JSSE, and Conscrypt's outbound `SSLSocket` silently drops
        // the SNI extension from client ClientHellos regardless of how it
        // is configured — issue #262. BC JSSE honours
        // `SSLParameters.serverNames`, so raw `SSLSocket` code paths
        // (synthetic AI client in `ToolCallViaSniPassthroughTest`) actually
        // reach the relay's SNI router.
        //
        // Must happen before any `SSLContext.getInstance("TLS")` call
        // inside `start()` — in particular before OkHttp builds the
        // relay mTLS client, and before the TunnelClient opens its
        // WebSocket.
        //
        // [FreshSslContextSocketFactory] explicitly falls back to the
        // highest-priority non-BC TLS provider so the OkHttp mTLS
        // `DelegatingX509KeyManager` path keeps the same JSSE it was
        // validated against (Conscrypt under Robolectric). BC's TLS 1.3
        // client-auth handshake does not interoperate with that
        // delegating key manager; scoping BC to raw SSLSockets avoids
        // dragging every OkHttp handshake into BC-land.
        installBouncyCastleJsseProvider()

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

        synchronized(capturedRotateSecretPayloads) { capturedRotateSecretPayloads.clear() }

        bootKoin(ca = newCa, relayPort = newPort)
    }

    /**
     * Tear down the Koin graph, harness coroutine scope, and in-memory fake
     * health repository, then re-boot Koin against the SAME running relay and
     * the SAME `context.filesDir` contents. Used by scenarios that simulate a
     * client-side crash between persistent-state writes: the device loses its
     * in-memory state (Koin singletons, DeviceKeyManager, registration
     * status flow) while the relay's Firestore record and the on-disk
     * [com.rousecontext.app.cert.FileCertificateStore] both survive.
     *
     * Inverse of [clearPersistentState] — that one is for "`pm clear`" style
     * scenarios (#271), this one is for "process died, OS will relaunch us"
     * scenarios (#272). Callers must have a currently-running harness (call on
     * an instance where [start] was invoked and [stop] was not); calling twice
     * in a row is fine.
     *
     * Concretely:
     *   - Stops Koin and cancels the harness scope so no callbacks from the
     *     prior graph can race with the new one.
     *   - Builds a fresh [SoftwareDeviceKeyManager] via the normal Koin
     *     override module. That mirrors production where a process restart
     *     would re-resolve the Android Keystore key (same alias, same key),
     *     but the harness's key manager is purely in-memory — a fresh
     *     keypair is actually the stricter analogue of "the in-memory
     *     handle is gone and must be re-obtained".
     *   - Does NOT touch: the relay subprocess, the test CA, the temp dir,
     *     `context.filesDir` (CertificateStore + DataStore persist there),
     *     Room databases, or JCA provider registrations.
     */
    fun restartKoinPreservingState() {
        checkNotNull(koinInstance) {
            "restartKoinPreservingState() requires a running harness (call start() first)"
        }
        val currentCa = checkNotNull(ca) { "harness CA missing" }
        val currentPort = relayPortOrNegative
        check(currentPort > 0) { "harness relay port invalid" }

        runCatching { stopKoin() }
        koinInstance = null

        harnessScope?.cancel()
        harnessScope = null
        fakeHealth = null

        // On purpose: we do NOT clear capturedRotateSecretPayloads. Callers
        // inspecting payloads across a restart expect to see the cumulative
        // timeline, matching how the relay stats accumulate on the preserved
        // subprocess.

        bootKoin(ca = currentCa, relayPort = currentPort)
    }

    /**
     * Simulate an Android cold start: process died, framework spawned a fresh
     * one via FCM, on-disk state survives, **and the Android Keystore key
     * survives** (because the AOSP Keystore HAL persists keys across process
     * lifetimes).
     *
     * Use this for cold-start-via-FCM scenarios (issue #317) where a
     * provisioned device must come back to `CONNECTED` without re-provisioning.
     * The previously-provisioned client cert on disk is bound to a specific
     * device public key — if we rebuilt the [DeviceKeyManager] with a fresh
     * keypair the mTLS handshake would fail (cert's SPKI no longer matches the
     * private key), which is *not* what happens on a real cold start: the
     * `rouse_device_key` alias in the Android Keystore resolves to the same
     * hardware-backed key as before.
     *
     * Contrast [restartKoinPreservingState], which mints a fresh
     * [DeviceKeyManager] and is deliberately stricter — the "onboarding
     * interrupted mid-`/register/certs`" scenario (#272) re-runs cert
     * provisioning against the fresh key, so a fresh key is correct there.
     *
     * Everything else behaves identically to [restartKoinPreservingState]:
     *   - Koin is stopped and rebooted.
     *   - The harness coroutine scope is cancelled and replaced.
     *   - The fake health repo is re-instantiated.
     *   - `context.filesDir`, the relay subprocess, and captured
     *     `/rotate-secret` payloads all persist.
     *
     * Callers must have a currently-running harness (call on an instance where
     * [start] was invoked and [stop] was not). The harness does NOT retain a
     * reference across a full [stop]/[start] pair — the keystore analogy only
     * holds within the same relay lifetime.
     */
    fun simulateColdStart() {
        checkNotNull(koinInstance) {
            "simulateColdStart() requires a running harness (call start() first)"
        }
        val currentCa = checkNotNull(ca) { "harness CA missing" }
        val currentPort = relayPortOrNegative
        check(currentPort > 0) { "harness relay port invalid" }

        // Capture the live DeviceKeyManager BEFORE stopping Koin — once Koin
        // is stopped its singletons are GC-eligible and `koin.get()` would
        // fail. The captured instance survives as a local here and is fed
        // back into the replacement override module below.
        val preservedDeviceKeyManager: DeviceKeyManager = koin.get()

        runCatching { stopKoin() }
        koinInstance = null

        harnessScope?.cancel()
        harnessScope = null
        fakeHealth = null

        // Same intentional skip as restartKoinPreservingState: the accumulated
        // `/rotate-secret` capture reflects the relay's own accumulated state,
        // which didn't restart.

        bootKoin(
            ca = currentCa,
            relayPort = currentPort,
            deviceKeyManagerOverride = preservedDeviceKeyManager
        )
    }

    /**
     * Shared Koin-startup path used by [start] (fresh relay),
     * [restartKoinPreservingState] (existing relay, fresh DeviceKeyManager),
     * and [simulateColdStart] (existing relay, preserved DeviceKeyManager).
     * Builds a new scope, new fake health repo, and re-runs
     * `startKoin { modules(appModule + overrides) }`.
     *
     * [deviceKeyManagerOverride], when non-null, replaces the default fresh
     * [SoftwareDeviceKeyManager] in the override module — used by
     * [simulateColdStart] to carry the provisioned keypair across the Koin
     * reboot (matching the Android Keystore's persistence semantics).
     */
    private fun bootKoin(
        ca: TestCertificateAuthority,
        relayPort: Int,
        deviceKeyManagerOverride: DeviceKeyManager? = null
    ) {
        val newHarnessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        harnessScope = newHarnessScope

        val newFakeHealth = HarnessFakeHealthConnectRepository()
        fakeHealth = newFakeHealth

        val captureSink: (List<String>) -> Unit = { ids ->
            synchronized(capturedRotateSecretPayloads) { capturedRotateSecretPayloads.add(ids) }
        }

        val appContext: Context = ApplicationProvider.getApplicationContext<Application>()
        koinInstance = startKoin {
            androidContext(appContext)
            modules(
                appModule,
                buildTestOverrides(
                    ca = ca,
                    relayPort = relayPort,
                    relayHostname = relayHostname,
                    baseDomain = baseDomain,
                    harnessScope = newHarnessScope,
                    fakeHealth = newFakeHealth,
                    integrations = integrationsFactory(),
                    rotateSecretCapture = captureSink,
                    deviceKeyManagerOverride = deviceKeyManagerOverride
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

        // Restore the JCA provider list to whatever it was before
        // [start] so the next test class in the same JVM is not affected
        // by BC's position.
        restoreSecurityProviders()
    }

    private var snapshotProviderNames: List<String>? = null

    private fun snapshotSecurityProviders() {
        snapshotProviderNames = Security.getProviders().map { it.name }
    }

    private fun restoreSecurityProviders() {
        // Remove any provider that is not in the pre-start snapshot.
        val keep = snapshotProviderNames ?: return
        Security.getProviders().toList().forEach { p ->
            if (p.name !in keep) {
                Security.removeProvider(p.name)
            }
        }
        snapshotProviderNames = null
    }

    /**
     * Simulate an Android `pm clear` on the app-under-test: drop every
     * persistent artefact the app stores, so the next [start] observes a fresh
     * install. Call this between a [stop] and the following [start].
     *
     * Wiped:
     *   - Everything under `context.filesDir` — that's where
     *     [com.rousecontext.app.cert.FileCertificateStore] persists the device
     *     cert + client cert + relay CA + subdomain + integration secrets, and
     *     where every `preferencesDataStore(name = …)` DataStore writes its
     *     `datastore/<name>.preferences_pb` file.
     *   - Every Room database declared by the app graph (tokens, audit,
     *     notifications). Uses `Context.deleteDatabase(...)` so the file,
     *     the `-shm` / `-wal` companions and any open references are all
     *     tracked together (matches what `pm clear` does at the framework
     *     level).
     *   - The `rouse_device_key` alias in the Android Keystore, if the
     *     provider is available in the test runtime. Robolectric does not
     *     ship `AndroidKeyStore` by default (see `AppIntegrationTestHarness`'s
     *     class docs), so this branch is a no-op in typical runs — the
     *     harness's [SoftwareDeviceKeyManager] is already refreshed by the
     *     subsequent [start] (a new instance is constructed in
     *     `buildTestOverrides`). Clearing the alias is defensive so the call
     *     is still correct on a runtime where the provider is wired up.
     *
     * Does NOT touch the relay subprocess (it's already shut down by [stop]).
     * The next [start] spins up a fresh relay, meaning Firestore / subdomain
     * pool / reservations are all empty — the same observable state the
     * device would see if it were re-onboarded under a fresh Google account.
     * Regression guard for issue #271.
     */
    fun clearPersistentState() {
        check(koinInstance == null) {
            "clearPersistentState() must be called between stop() and start()"
        }

        val appContext: Context = ApplicationProvider.getApplicationContext<Application>()

        // filesDir: FileCertificateStore + every preferencesDataStore file
        // (datastore/<name>.preferences_pb).
        appContext.filesDir?.listFiles()?.forEach { it.deleteRecursively() }

        // Room DBs. deleteDatabase is a no-op for unknown names, but naming
        // them explicitly catches the case where a future module adds a new
        // Room DB and forgets to wire it in here.
        listOf(ROOM_DB_TOKENS, ROOM_DB_AUDIT, ROOM_DB_NOTIFICATIONS)
            .forEach { runCatching { appContext.deleteDatabase(it) } }

        // AndroidKeyStore alias. Robolectric omits the provider by default, so
        // `KeyStore.getInstance("AndroidKeyStore")` throws
        // `NoSuchAlgorithmException` — swallow it: the harness's
        // software-backed DeviceKeyManager is regenerated on the next start().
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_NAME)
            keyStore.load(null)
            if (keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
                keyStore.deleteEntry(DEVICE_KEY_ALIAS)
            }
        }
    }

    private companion object {
        // Keep these values in sync with:
        //   app/token/TokenDatabase (DB_NAME)
        //   notifications/audit/AuditDatabase (DB_NAME)
        //   integrations/notifications/NotificationDatabase (DB_NAME)
        // App under test owns the names; duplicating them here is safe because
        // a rename would force this wipe to be updated alongside the schema.
        const val ROOM_DB_TOKENS = "rouse_tokens.db"
        const val ROOM_DB_AUDIT = "rouse_audit.db"
        const val ROOM_DB_NOTIFICATIONS = "rouse_notifications.db"

        // Matches [com.rousecontext.app.cert.AndroidKeystoreDeviceKeyManager.KEY_ALIAS]
        // and the legacy `FileCertificateStore` alias.
        const val DEVICE_KEY_ALIAS = "rouse_device_key"
        const val ANDROID_KEYSTORE_NAME = "AndroidKeyStore"
    }
}

/**
 * Return the highest-priority JCA provider that advertises a service of
 * [serviceType] (e.g. `"SSLContext"`, `"TrustManagerFactory"`) whose
 * algorithm [algorithmFilter] accepts, excluding BouncyCastle.
 *
 * Callers use this to route specific JCA lookups around the global BC
 * provider that the harness installs at priority 1. The mTLS OkHttp
 * client (`DelegatingX509KeyManager` / PKCS12 / `SunX509` stack) was
 * validated against Conscrypt's JSSE, and BC's TLS 1.3 client-auth path
 * does not interoperate cleanly with that setup.
 */
private fun pickNonBouncyCastleProviderOffering(
    serviceType: String,
    algorithmFilter: (String) -> Boolean
): java.security.Provider {
    for (p in Security.getProviders()) {
        if (p.name == "BCJSSE" || p.name == "BC") continue
        val supports = p.services.any { svc ->
            svc.type == serviceType && algorithmFilter(svc.algorithm)
        }
        if (supports) return p
    }
    error(
        "No non-BouncyCastle JCA provider offers $serviceType matching the " +
            "requested algorithm filter — harness cannot build its mTLS stack"
    )
}

/**
 * Alias for [pickNonBouncyCastleProviderOffering] restricted to the default
 * [TrustManagerFactory] algorithm. Used by [buildFixtureMtlsHttpClient] to
 * ensure the trust manager comes from the same JSSE implementation as the
 * mTLS SSLContext.
 */
private fun pickNonBouncyCastleTrustProvider(): java.security.Provider {
    val defaultAlgo = TrustManagerFactory.getDefaultAlgorithm()
    return pickNonBouncyCastleProviderOffering("TrustManagerFactory") {
        it == defaultAlgo
    }
}

/**
 * Insert BouncyCastle's JSSE + crypto providers at JCA priority 1 so
 * `SSLContext.getInstance("TLS")` resolves to BC by default. Loaded
 * reflectively to keep BC out of the app module's production classpath —
 * the libraries are declared `testImplementation` only. Idempotent:
 * skipped if already registered.
 */
private fun installBouncyCastleJsseProvider() {
    if (Security.getProvider("BC") == null) {
        val bcProv = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            .getDeclaredConstructor()
            .newInstance() as java.security.Provider
        Security.insertProviderAt(bcProv, 1)
    }
    if (Security.getProvider("BCJSSE") == null) {
        val bcJsse = Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider")
            .getDeclaredConstructor()
            .newInstance() as java.security.Provider
        Security.insertProviderAt(bcJsse, 1)
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
    integrations: List<McpIntegration>,
    rotateSecretCapture: (List<String>) -> Unit,
    deviceKeyManagerOverride: DeviceKeyManager? = null
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
    //
    // When [deviceKeyManagerOverride] is non-null, reuse the passed-in
    // instance instead of minting a new one — [simulateColdStart] uses this
    // to carry the provisioned keypair across a Koin reboot, matching the
    // Android Keystore's persistence across process lifetimes. Issue #317.
    single<DeviceKeyManager> { deviceKeyManagerOverride ?: SoftwareDeviceKeyManager() }

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
            },
            rotateSecretCapture = rotateSecretCapture
        )
        RelayApiClient(
            baseUrl = "https://$relayHostname:$relayPort",
            httpClient = httpClient
        )
    }

    // --- CertProvisioningFlow override ---
    // Pins defaultBaseDomain to the fixture base domain; the production
    // definition reads BuildConfig.BASE_DOMAIN which is always the real
    // `rousecontext.com` (or whatever -Pdomain was passed at build time).
    // Declared before OnboardingFlow so the chained dep resolves through
    // this override (Koin picks the *last* definition per type).
    single {
        CertProvisioningFlow(
            csrGenerator = get(),
            relayApiClient = get(),
            certificateStore = get(),
            deviceKeyManager = get(),
            defaultBaseDomain = baseDomain
        )
    }

    // --- OnboardingFlow override ---
    // Mirrors production wiring (#389): chains CertProvisioningFlow so
    // onboarding lands with all three PEMs. Tests that want to simulate a
    // crash between `/register` and `/register/certs` (e.g.
    // OnboardingInterruptedResumableTest) construct their own OnboardingFlow
    // without a provisioning flow instead of leaning on the Koin-supplied
    // one.
    single {
        OnboardingFlow(
            relayApiClient = get(),
            certificateStore = get(),
            integrationIds = get<List<McpIntegration>>().map { it.id },
            certProvisioningFlow = get<CertProvisioningFlow>()
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
    keyManagerSource: () -> javax.net.ssl.X509KeyManager?,
    rotateSecretCapture: (List<String>) -> Unit
): HttpClient {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
    trustStore.load(null, null)
    trustStore.setCertificateEntry("ca", caCert)
    // Pin the trust-manager factory to a non-BC provider so its trust
    // manager pairs cleanly with the non-BC `SSLContext` built in
    // [FreshSslContextSocketFactory]. Mixing a BC trust manager with a
    // Conscrypt SSLContext throws "Unsupported server authType: GENERIC"
    // on TLS 1.3 handshakes — the two providers use different authType
    // coding.
    val tmf = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm(),
        pickNonBouncyCastleTrustProvider()
    )
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
                addInterceptor(RotateSecretBodyCaptureInterceptor(rotateSecretCapture))
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
        // Pin this factory to the first non-BouncyCastle TLS provider
        // (Conscrypt under Robolectric, SunJSSE under plain JVM). The
        // harness installs BC JSSE at priority 1 to fix raw-SSLSocket SNI
        // emission for #262, but BC's TLS 1.3 client-auth path does not
        // interoperate with the `DelegatingX509KeyManager` / PKCS12 /
        // `SunX509` stack this OkHttp mTLS client uses — the handshake
        // completes without a client cert and the relay 401s. Scoping BC
        // to raw SSLSockets (in `ToolCallViaSniPassthroughTest`) keeps
        // every OkHttp path on whichever JSSE it was validated against.
        val sslContext = SSLContext.getInstance("TLS", pickNonBouncyCastleTlsProvider())
        sslContext.init(
            arrayOf(DelegatingX509KeyManager(keyManagerSource)),
            trustManagers,
            null
        )
        sslContext.clientSessionContext?.sessionCacheSize = 1
        sslContext.clientSessionContext?.sessionTimeout = 1
        return sslContext.socketFactory
    }

    private fun pickNonBouncyCastleTlsProvider(): java.security.Provider =
        pickNonBouncyCastleProviderOffering("SSLContext") { it == "TLS" || it == "Default" }

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

/**
 * OkHttp interceptor that peeks at `POST /rotate-secret` request bodies and
 * feeds the `integrations` array into [sink]. Every other request is passed
 * through unchanged. Runs before the network layer so the captured list is
 * exactly what the device attempted to push, independent of whether the
 * relay accepted it.
 *
 * The relay's test-mode admin surfaces `/rotate-secret` call counts but not
 * bodies. Scenarios in issues #274/#275/#276 need to assert which integrations
 * were included in each payload; capturing here is cheaper than extending the
 * relay admin protocol and keeps the coupling inside the harness.
 */
private class RotateSecretBodyCaptureInterceptor(private val sink: (List<String>) -> Unit) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method.equals("POST", ignoreCase = true) &&
            request.url.encodedPath.endsWith("/rotate-secret")
        ) {
            val body = request.body
            if (body != null) {
                val buffer = Buffer()
                body.writeTo(buffer)
                val raw = buffer.readUtf8()
                val parsed = runCatching {
                    val root = Json.parseToJsonElement(raw)
                    root.jsonObject["integrations"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?: emptyList()
                }.getOrElse { emptyList() }
                sink(parsed)
            } else {
                sink(emptyList())
            }
        }
        return chain.proceed(request)
    }
}

// Shorter than production timeouts — integration tests hit a local subprocess
// so multi-minute waits would only mask hangs.
private const val FIXTURE_REQUEST_TIMEOUT_MS = 30_000L
private const val FIXTURE_CONNECT_TIMEOUT_MS = 10_000L
private const val FIXTURE_SOCKET_TIMEOUT_MS = 30_000L
