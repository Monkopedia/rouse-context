package com.rousecontext.app.cert

import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Test-only helper shared across `app/cert` unit tests.
 *
 * Shelling out to `keytool` (the approach used by the existing
 * [FileCertificateStoreTest]) is slow but produces real, parseable
 * X.509 certs without pulling BouncyCastle into every test's classpath
 * and without needing the Android Keystore JCA provider (which
 * Robolectric does not ship).
 */
internal object CertTestUtil {

    private const val LINE_LENGTH = 64

    /**
     * Build a self-signed RSA X.509 cert with [cn] as the CN. RSA (not EC)
     * because keytool's `-genkeypair -keyalg EC` path is less portable across
     * JDK vendors on CI.
     */
    fun generateSelfSignedCert(cn: String): X509Certificate {
        val tempFile = File.createTempFile("app-cert-test-", ".p12")
        tempFile.deleteOnExit()
        tempFile.delete()
        try {
            val process = ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-sigalg", "SHA256withRSA",
                "-dname", "CN=$cn",
                "-validity", "365",
                "-storetype", "PKCS12",
                "-keystore", tempFile.absolutePath,
                "-storepass", "test123",
                "-keypass", "test123"
            )
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            require(exitCode == 0) { "keytool failed (cn=$cn)" }

            val keyStore = KeyStore.getInstance("PKCS12")
            tempFile.inputStream().use { keyStore.load(it, "test123".toCharArray()) }
            return keyStore.getCertificate("test") as X509Certificate
        } finally {
            tempFile.delete()
        }
    }

    /** DER -> single-cert PEM, using the same 64-char line length the production code uses. */
    fun derToPem(der: ByteArray): String {
        val base64 = Base64.getMimeEncoder(LINE_LENGTH, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    /** PEM concatenation of a multi-cert chain in the given order. */
    fun chainToPem(certs: List<X509Certificate>): String =
        certs.joinToString("") { derToPem(it.encoded) }
}
