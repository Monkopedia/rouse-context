package com.rousecontext.tunnel

import java.io.File
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * In-memory certificate store for testing. Generates a self-signed cert using keytool.
 */
class TestCertificateStore {
    val certificate: X509Certificate
    val keyStore: KeyStore
    val sslContext: SSLContext
    val trustingSslContext: SSLContext

    init {
        // Generate a self-signed cert using keytool (available in all JDKs)
        val tempFile = File.createTempFile("test-keystore-", ".p12")
        tempFile.deleteOnExit()
        tempFile.delete() // keytool needs the file to not exist

        val process =
            ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", "CN=test.rousecontext.com",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", tempFile.absolutePath,
                "-storepass", PASS_STR,
                "-keypass", PASS_STR,
            )
                .redirectErrorStream(true)
                .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        require(exitCode == 0) { "keytool failed (exit $exitCode): $output" }

        keyStore = KeyStore.getInstance("PKCS12")
        tempFile.inputStream().use { keyStore.load(it, PASS) }
        tempFile.delete()

        certificate = keyStore.getCertificate("test") as X509Certificate

        // Server SSL context (device side)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASS)
        sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)

        // Client SSL context that trusts the self-signed cert
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, PASS)
        trustStore.setCertificateEntry("test", certificate)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        trustingSslContext = SSLContext.getInstance("TLS")
        trustingSslContext.init(null, tmf.trustManagers, null)
    }

    companion object {
        private const val PASS_STR = "test123"
        private val PASS = PASS_STR.toCharArray()
    }
}
