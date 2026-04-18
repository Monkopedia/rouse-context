package com.rousecontext.app.cert

import android.content.Context
import android.util.Log
import com.rousecontext.tunnel.DeviceKeyManager
import com.rousecontext.tunnel.MtlsCertSource
import com.rousecontext.tunnel.MtlsIdentity
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * [MtlsCertSource] backed by the PEM files [FileCertificateStore] writes to
 * `context.filesDir` plus the hardware-backed private key from
 * [DeviceKeyManager].
 *
 * Reads are performed on every call so the source transparently switches
 * from "not provisioned" (during early onboarding) to "provisioned" as
 * soon as `/register/certs` writes the client cert to disk. See issue
 * #237 and [com.rousecontext.tunnel.LazyMtlsKeyManager] for the rationale.
 *
 * The CLIENT cert file (`rouse_client_cert.pem`, issued by the relay CA
 * with `clientAuth` EKU) is used here — NOT the server cert file
 * (`rouse_cert.pem`, issued by Let's Encrypt with `serverAuth` EKU, which
 * is for AI clients connecting to the device). This matches the split
 * maintained by [MtlsWebSocketFactory] for the tunnel WebSocket path.
 */
class FileMtlsCertSource(context: Context, private val deviceKeyManager: DeviceKeyManager) :
    MtlsCertSource {

    private val clientCertFile: File = File(context.filesDir, CLIENT_CERT_PEM_FILE)
    private val relayCaCertFile: File = File(context.filesDir, RELAY_CA_PEM_FILE)

    override fun current(): MtlsIdentity? {
        if (!clientCertFile.exists()) return null
        val leaf = parsePemCertificates(clientCertFile.readText()).firstOrNull() ?: return null
        val caCert = if (relayCaCertFile.exists()) {
            parsePemCertificates(relayCaCertFile.readText()).firstOrNull()
        } else {
            null
        }
        val chain = if (caCert != null) listOf(leaf, caCert) else listOf(leaf)

        val privateKey = try {
            deviceKeyManager.getOrCreateKeyPair().private
        } catch (e: Exception) {
            Log.w(TAG, "DeviceKeyManager failed to produce a signing key; presenting no cert", e)
            return null
        }
        return MtlsIdentity(privateKey = privateKey, certChain = chain)
    }

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(pem).map { match ->
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(base64)
            factory.generateCertificate(der.inputStream()) as X509Certificate
        }.toList()
    }

    companion object {
        private const val TAG = "FileMtlsCertSource"
        private const val CLIENT_CERT_PEM_FILE = "rouse_client_cert.pem"
        private const val RELAY_CA_PEM_FILE = "rouse_relay_ca.pem"
    }
}
