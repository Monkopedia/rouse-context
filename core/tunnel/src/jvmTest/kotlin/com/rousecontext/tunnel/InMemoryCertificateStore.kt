package com.rousecontext.tunnel

/**
 * In-memory implementation of [CertificateStore] for testing.
 */
class InMemoryCertificateStore : CertificateStore {

    private var certificate: String? = null
    private var subdomain: String? = null
    private var privateKey: String? = null

    var storeCallCount = 0
        private set

    var throwOnStore: Exception? = null

    override suspend fun storeCertificate(pemChain: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        certificate = pemChain
    }

    override suspend fun getCertificate(): String? = certificate

    override suspend fun storeSubdomain(subdomain: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        this.subdomain = subdomain
    }

    override suspend fun getSubdomain(): String? = subdomain

    override suspend fun storePrivateKey(pemKey: String) {
        throwOnStore?.let { throw it }
        storeCallCount++
        privateKey = pemKey
    }

    override suspend fun getPrivateKey(): String? = privateKey

    override suspend fun clear() {
        certificate = null
        subdomain = null
        privateKey = null
        storeCallCount = 0
    }
}
