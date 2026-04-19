package com.rousecontext.app.cert

import android.content.Context
import android.util.Log
import com.rousecontext.tunnel.CertificateStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [CertificateStore] implementation that stores PEM certs and ancillary onboarding
 * state in `context.filesDir`.
 *
 * Issue #200: the device identity keypair is owned by
 * [com.rousecontext.tunnel.DeviceKeyManager] (hardware-backed Android Keystore, see
 * `AndroidKeystoreDeviceKeyManager`). FileCertificateStore is not in the key path --
 * it only holds PEM certs, subdomain metadata, integration secrets, and
 * fingerprints. The historical PEM-key hooks on [CertificateStore] inherit
 * deprecated no-op / null defaults.
 */
class FileCertificateStore(private val context: Context) : CertificateStore {

    private val filesDir get() = context.filesDir
    private val certFile get() = File(filesDir, CERT_PEM_FILE)
    private val subdomainFile get() = File(filesDir, SUBDOMAIN_FILE)
    private val integrationSecretsFile get() = File(filesDir, INTEGRATION_SECRETS_FILE)
    private val legacySecretPrefixFile get() = File(filesDir, LEGACY_SECRET_PREFIX_FILE)
    private val fingerprintsFile get() = File(filesDir, FINGERPRINTS_FILE)
    private val fingerprintBootstrapMarkerFile get() =
        File(filesDir, FINGERPRINT_BOOTSTRAP_MARKER_FILE)

    override suspend fun storeCertificate(pemChain: String) {
        certFile.writeText(pemChain)
        // Record leaf fingerprint so SelfCertVerifier can confirm the relay is
        // presenting our provisioned certificate. Production provisioning and
        // renewal both call this method directly (not storeCertChain), so this
        // is the required hook point to keep the fingerprints file populated.
        val leaf = parsePemCertificates(pemChain).firstOrNull()
        if (leaf != null) {
            storeFingerprint(sha256Fingerprint(leaf.encoded))
        }
    }

    override suspend fun getCertificate(): String? =
        if (certFile.exists()) certFile.readText() else null

    override suspend fun storeClientCertificate(pemChain: String) {
        File(filesDir, CLIENT_CERT_PEM_FILE).writeText(pemChain)
    }

    override suspend fun getClientCertificate(): String? {
        val f = File(filesDir, CLIENT_CERT_PEM_FILE)
        return if (f.exists()) f.readText() else null
    }

    override suspend fun storeRelayCaCert(pem: String) {
        File(filesDir, RELAY_CA_PEM_FILE).writeText(pem)
    }

    override suspend fun getRelayCaCert(): String? {
        val f = File(filesDir, RELAY_CA_PEM_FILE)
        return if (f.exists()) f.readText() else null
    }

    override suspend fun storeSubdomain(subdomain: String) {
        atomicWrite(subdomainFile, subdomain)
    }

    override suspend fun getSubdomain(): String? {
        if (!subdomainFile.exists()) return null
        // A blank file indicates either a partially-written state from a previous
        // crash or an intentional clear. Either way it is NOT a valid subdomain,
        // so treat it as "unregistered" and let the caller retry onboarding
        // rather than silently returning "" (issue #163).
        val text = subdomainFile.readText().trim()
        return text.ifBlank { null }
    }

    override suspend fun storeIntegrationSecrets(secrets: Map<String, String>) {
        val json = JsonObject(secrets.mapValues { (_, v) -> JsonPrimitive(v) })
        atomicWrite(integrationSecretsFile, json.toString())
    }

    override suspend fun getIntegrationSecrets(): Map<String, String>? {
        if (integrationSecretsFile.exists()) {
            return try {
                val text = integrationSecretsFile.readText().trim()
                val obj = Json.parseToJsonElement(text).jsonObject
                obj.mapValues { (_, v) -> v.jsonPrimitive.content }
            } catch (_: Exception) {
                null
            }
        }
        // Migration: read legacy secret_prefix file and synthesize a map.
        // The old format was "{adjective}-{noun}" where noun is the integration name.
        // Without knowing which integrations exist, return the raw prefix for all
        // lookups -- callers will match by integration name suffix.
        if (legacySecretPrefixFile.exists()) {
            val prefix = legacySecretPrefixFile.readText().trim()
            if (prefix.isNotEmpty()) {
                return mapOf("_legacy" to prefix)
            }
        }
        return null
    }

    // Issue #200: the device identity key is owned by DeviceKeyManager (Android
    // Keystore, StrongBox/TEE). storePrivateKey/getPrivateKey inherit the
    // deprecated default no-op/null implementations on the interface.

    override suspend fun getCertChain(): List<ByteArray>? {
        val pem = getCertificate() ?: return null
        return parsePemCertificates(pem).map { it.encoded }
    }

    override suspend fun getPrivateKeyBytes(): ByteArray? {
        // Android Keystore does not allow exporting private key bytes.
        // Return null to signal that the key is hardware-bound.
        return null
    }

    override suspend fun storeCertChain(chain: List<ByteArray>) {
        val factory = CertificateFactory.getInstance("X.509")
        val pemBuilder = StringBuilder()
        for (der in chain) {
            val cert = factory.generateCertificate(der.inputStream())
            pemBuilder.append("-----BEGIN CERTIFICATE-----\n")
            pemBuilder.append(
                Base64.getMimeEncoder(LINE_LENGTH, "\n".toByteArray())
                    .encodeToString(cert.encoded)
            )
            pemBuilder.append("\n-----END CERTIFICATE-----\n")
        }
        // storeCertificate records the leaf fingerprint, so chain fingerprint
        // recording is handled there. During renewal, both old and new
        // fingerprints accumulate in the file (no pruning) — at ~1 entry per
        // 90-day renewal this is not a concern.
        storeCertificate(pemBuilder.toString())
    }

    override suspend fun getCertExpiry(): Long? {
        val pem = getCertificate() ?: return null
        val certs = parsePemCertificates(pem)
        return certs.firstOrNull()?.notAfter?.time
    }

    override suspend fun getKnownFingerprints(): Set<String> {
        if (!fingerprintsFile.exists()) return emptySet()
        return fingerprintsFile.readText()
            .lines()
            .filter { it.isNotBlank() }
            .toSet()
    }

    override suspend fun storeFingerprint(fingerprint: String) {
        fingerprintsFile.appendText("$fingerprint\n")
    }

    override suspend fun hasFingerprintBootstrapMarker(): Boolean =
        fingerprintBootstrapMarkerFile.exists()

    override suspend fun writeFingerprintBootstrapMarker() {
        // Best-effort: if we cannot persist the marker we log and continue.
        // A missing marker would allow another one-shot backfill on the next
        // run, which is strictly less safe but no worse than the pre-#210
        // behaviour; failing the verify outright would force a 503 lockout
        // and wipe out the backfilled fingerprint we just wrote.
        try {
            fingerprintBootstrapMarkerFile.writeText("1")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write fingerprint bootstrap marker", e)
        }
    }

    override suspend fun clear() {
        clearCertificates()
        subdomainFile.delete()
        integrationSecretsFile.delete()
        legacySecretPrefixFile.delete()
        // Full reset: drop the hardware-backed device identity too. This is ONLY
        // appropriate on a full wipe (user factory-resets or explicitly
        // reregisters) -- cert rollback uses clearCertificates(), which keeps
        // the Keystore alias so the relay's pinned public key stays valid.
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        if (keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS)
        }
    }

    override suspend fun clearCertificates() {
        // Narrow rollback for cert-provisioning failures. Must NOT touch the
        // subdomain or integration-secrets files -- those represent completed
        // onboarding state that outlives any individual cert-provisioning
        // attempt (issue #163).
        certFile.delete()
        File(filesDir, CLIENT_CERT_PEM_FILE).delete()
        File(filesDir, RELAY_CA_PEM_FILE).delete()
        // Issue #200: the hardware-backed device identity key lives in the
        // Android Keystore and MUST survive cert rotation/rollback -- the relay
        // pins its public half at registration time and rejects any CSR signed
        // by a different key. We therefore leave the Keystore alias intact here.
        fingerprintsFile.delete()
        // The bootstrap marker is tied to the current install's fingerprint
        // state. When we roll back cert provisioning we also reset this so a
        // fresh install on the same filesDir can legitimately one-shot
        // backfill again (issue #210).
        fingerprintBootstrapMarkerFile.delete()
    }

    /**
     * Atomically write text to [target] using a temp-file + rename dance.
     *
     * `File.writeText` truncates the target first; if the process dies between
     * truncation and the actual write, the file is left empty and the caller
     * loses data. Writing to a sibling `.tmp` and renaming on completion means
     * the target either contains the previous value (rename never happened) or
     * the new complete value (rename succeeded) -- never a zero-byte torso.
     * This is what the "device bounced to onboarding" bug in issue #163 looks
     * like in the wild.
     *
     * Device-crash durability (issue #165): the temp file's bytes are fsynced
     * before the rename, and the parent directory is fsynced after the rename.
     * Without these, a kernel panic / battery pull between rename and writeback
     * can surface a zero-length or partial file under [target] on next boot --
     * catastrophic for the cert and subdomain files since the device would need
     * to re-register via ACME, burning cert quota.
     *
     * Stale `.tmp` cleanup (issue #166): prior failed writes can leave
     * `<name>.tmp` siblings. We sweep them at the start of every write so
     * repeated partial failures do not accumulate orphan tmps over time.
     */
    private fun atomicWrite(target: File, content: String) {
        val parent = target.parentFile ?: error("target has no parent: $target")
        val tmp = File(parent, "${target.name}.tmp")
        reapStaleTmpSiblings(parent, target, tmp)

        // Write + fsync the tmp file BEFORE renaming, so the rename promotes
        // durable bytes rather than page-cache bytes that could still vanish.
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        val renamed = try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }

        if (!renamed) {
            fallbackRename(tmp, target, content)
        }

        fsyncDirectory(parent)
    }

    /**
     * Fallback rename path when ATOMIC_MOVE isn't available or fails. Rename
     * can also fail on some filesystems if the target already exists. We accept
     * a slightly wider window here -- rare in practice on ext4 / f2fs used by
     * Android -- but still fsync the last-resort write so we honor durability.
     */
    private fun fallbackRename(tmp: File, target: File, content: String) {
        target.delete()
        if (tmp.renameTo(target)) return

        // Last-resort copy: still better than leaving nothing.
        target.writeText(content)
        try {
            FileOutputStream(target, true).use { it.fd.sync() }
        } catch (e: IOException) {
            Log.w(TAG, "fsync of last-resort write failed for $target", e)
        }
        tmp.delete()
    }

    /**
     * fsync the parent directory so the rename itself is durable. On POSIX
     * ext4 / f2fs this is what makes the new dirent survive a crash. Some
     * JVM/filesystem combos do not support opening a directory via
     * FileChannel -- log WARN and continue rather than failing the write.
     */
    private fun fsyncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { ch ->
                ch.force(true)
            }
        } catch (e: IOException) {
            Log.w(TAG, "parent dir fsync not supported for $dir", e)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "parent dir fsync not supported for $dir", e)
        }
    }

    /**
     * Delete any `<target-name>.tmp*` files that are not the temp file we are
     * about to write. A stale `.tmp` means a prior write aborted without
     * cleanup; it contains nothing we want to keep (the real value is in
     * [target], or was never successfully written). Per-write cleanup is
     * simpler than startup cleanup because it lives on the already-hot write
     * path and guarantees forward progress without wiring into init (#166).
     */
    private fun reapStaleTmpSiblings(parent: File, target: File, inFlightTmp: File) {
        val prefix = "${target.name}.tmp"
        val stale = parent.listFiles { f ->
            f.name.startsWith(prefix) && f.absolutePath != inFlightTmp.absolutePath
        } ?: return
        for (f in stale) {
            if (!f.delete()) {
                Log.w(TAG, "failed to delete stale tmp sibling ${f.absolutePath}")
            }
        }
    }

    // Issue #200: device-identity key generation lives in AndroidKeystoreDeviceKeyManager
    // (StrongBox-first with TEE fallback). The previous AES encryption-key path (used only
    // to encrypt the software PEM on disk) is gone too -- the store no longer holds any
    // private-key material.

    private fun parsePemCertificates(pem: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val regex = Regex(
            "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in regex.findAll(pem)) {
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val der = Base64.getDecoder().decode(base64)
            val cert = factory.generateCertificate(der.inputStream()) as X509Certificate
            certs.add(cert)
        }
        return certs
    }

    private fun androidKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore
    }

    companion object {
        private const val TAG = "FileCertificateStore"
        private const val CERT_PEM_FILE = "rouse_cert.pem"
        private const val CLIENT_CERT_PEM_FILE = "rouse_client_cert.pem"
        private const val RELAY_CA_PEM_FILE = "rouse_relay_ca.pem"
        private const val SUBDOMAIN_FILE = "rouse_subdomain.txt"
        private const val INTEGRATION_SECRETS_FILE = "rouse_integration_secrets.json"
        private const val LEGACY_SECRET_PREFIX_FILE = "rouse_secret_prefix.txt"
        private const val FINGERPRINTS_FILE = "rouse_fingerprints.txt"
        private const val FINGERPRINT_BOOTSTRAP_MARKER_FILE = "rouse_fingerprint_bootstrapped"
        private const val KEY_ALIAS = "rouse_device_key"
        private const val ENCRYPTION_KEY_ALIAS = "rouse_key_encryption_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LINE_LENGTH = 64

        /** Compute SHA-256 fingerprint of a DER-encoded certificate. */
        fun sha256Fingerprint(derBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(derBytes)
                .joinToString(":") { "%02X".format(it) }
        }
    }
}
