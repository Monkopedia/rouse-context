package com.rousecontext.tunnel

/**
 * In-memory implementation of [CertificateStore] for testing onboarding/renewal flows.
 */
class InMemoryCertificateStore : CertificateStore {

    private var certificate: String? = null
    private var subdomain: String? = null
    private var integrationSecrets: Map<String, String>? = null
    private var privateKey: String? = null
    private var certChain: List<ByteArray>? = null
    private val knownFingerprints: MutableSet<String> = mutableSetOf()
    private var fingerprintBootstrapMarker: Boolean = false

    var storeCallCount = 0
        private set

    private var clientCertificate: String? = null
    private var relayCaCert: String? = null

    var throwOnStore: Exception? = null

    // --- PEM access (onboarding/renewal) ---

    override suspend fun storeCertificate(pemChain: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        certificate = pemChain
    }

    override suspend fun getCertificate(): String? = certificate

    override suspend fun storeClientCertificate(pemChain: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        clientCertificate = pemChain
    }

    override suspend fun getClientCertificate(): String? = clientCertificate

    override suspend fun storeRelayCaCert(pem: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        relayCaCert = pem
    }

    override suspend fun getRelayCaCert(): String? = relayCaCert

    override suspend fun storeSubdomain(subdomain: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        this.subdomain = subdomain
    }

    override suspend fun getSubdomain(): String? = subdomain

    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        throwOnStore?.let { throw it }
        storeCallCount++
        integrationSecrets = secrets
    }

    override suspend fun getIntegrationSecrets(): Map<String, String>? = integrationSecrets

    override suspend fun storePrivateKey(pemKey: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        privateKey = pemKey
    }

    override suspend fun getPrivateKey(): String? = privateKey

    override suspend fun clear() {
        certificate = null
        clientCertificate = null
        relayCaCert = null
        subdomain = null
        integrationSecrets = null
        privateKey = null
        certChain = null
        knownFingerprints.clear()
        fingerprintBootstrapMarker = false
        storeCallCount = 0
    }

    override suspend fun clearCertificates() {
        // Narrow rollback: cert-related only, leaves onboarding state.
        certificate = null
        clientCertificate = null
        relayCaCert = null
        privateKey = null
        certChain = null
        knownFingerprints.clear()
        fingerprintBootstrapMarker = false
    }

    // --- Binary access (security monitoring) ---

    override suspend fun getCertChain(): List<ByteArray>? = certChain

    override suspend fun getPrivateKeyBytes(): ByteArray? = null

    override suspend fun storeCertChain(chain: List<ByteArray>) {
        certChain = chain
    }

    override suspend fun getCertExpiry(): Long? = null

    override suspend fun getKnownFingerprints(): Set<String> = knownFingerprints

    override suspend fun storeFingerprint(fingerprint: String) {
        knownFingerprints.add(fingerprint)
    }

    override suspend fun hasFingerprintBootstrapMarker(): Boolean = fingerprintBootstrapMarker

    override suspend fun writeFingerprintBootstrapMarker() {
        fingerprintBootstrapMarker = true
    }
}
