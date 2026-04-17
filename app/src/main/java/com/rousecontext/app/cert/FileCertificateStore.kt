package com.rousecontext.app.cert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.rousecontext.tunnel.CertificateStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [CertificateStore] implementation that stores PEM certs in filesDir
 * and the private key in Android Keystore (hardware-backed HSM).
 *
 * The [encryptionKeyProvider] indirection exists so tests running under
 * Robolectric (where `AndroidKeyStore` is unavailable) can substitute a
 * software-generated AES key. Production code must rely on the default,
 * which binds the key to the hardware-backed Android Keystore.
 */
class FileCertificateStore(
    private val context: Context,
    private val encryptionKeyProvider: (() -> SecretKey)? = null
) : CertificateStore {

    private val filesDir get() = context.filesDir
    private val certFile get() = File(filesDir, CERT_PEM_FILE)
    private val subdomainFile get() = File(filesDir, SUBDOMAIN_FILE)
    private val integrationSecretsFile get() = File(filesDir, INTEGRATION_SECRETS_FILE)
    private val legacySecretPrefixFile get() = File(filesDir, LEGACY_SECRET_PREFIX_FILE)
    private val fingerprintsFile get() = File(filesDir, FINGERPRINTS_FILE)
    private val keyMigrationMarkerFile get() = File(filesDir, KEY_MIGRATION_MARKER_FILE)

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

    override suspend fun storePrivateKey(pemKey: String) {
        val encryptionKey = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(pemKey.toByteArray(Charsets.UTF_8))
        // Write IV length (1 byte), IV, then ciphertext
        val keyFile = File(filesDir, KEY_PEM_FILE)
        keyFile.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(ciphertext)
        }
    }

    override suspend fun getPrivateKey(): String? {
        val keyFile = File(filesDir, KEY_PEM_FILE)
        if (!keyFile.exists()) return null
        val data = keyFile.readBytes()
        if (data.isEmpty()) return null

        // Issue #204: the first byte of a valid AES-GCM blob is the IV length
        // (a small integer like 12), never '-'. A leading '-' therefore can
        // only mean plaintext PEM. If migration has NOT yet run we accept it
        // once; otherwise we refuse, since a post-migration plaintext PEM in
        // this file can only arrive via tampering or substitution.
        if (data[0] == PEM_LEADING_BYTE) {
            return handlePlaintextKeyFile(keyFile, data)
        }
        return decryptEncryptedKeyBlob(data)
    }

    private suspend fun handlePlaintextKeyFile(keyFile: File, data: ByteArray): String? {
        if (keyMigrationMarkerFile.exists()) {
            Log.e(
                TAG,
                "Plaintext PEM found in key file after migration completed; refusing to load"
            )
            return null
        }
        val text = String(data, Charsets.UTF_8)
        if (!text.contains("BEGIN") || !text.contains("PRIVATE KEY")) {
            Log.e(TAG, "Key file starts with '-' but is not a valid PEM; refusing to load")
            return null
        }
        Log.w(
            TAG,
            "Migrating plaintext private key to encrypted storage: ${keyFile.absolutePath}"
        )
        storePrivateKey(text)
        markKeyMigrated()
        return text
    }

    private fun decryptEncryptedKeyBlob(data: ByteArray): String? {
        val ivLen = data[0].toInt() and 0xFF
        if (ivLen == 0 || data.size < 1 + ivLen + GCM_TAG_BYTES) {
            Log.e(
                TAG,
                "Key file too short to contain a valid AES-GCM blob " +
                    "(size=${data.size}, ivLen=$ivLen); refusing to load"
            )
            return null
        }
        val iv = data.copyOfRange(1, 1 + ivLen)
        val ciphertext = data.copyOfRange(1 + ivLen, data.size)
        return try {
            val encryptionKey = getOrCreateEncryptionKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // GCM tag authentication failed: the ciphertext, IV, or key have
            // been tampered with. NEVER fall back to treating the raw bytes as
            // plaintext PEM -- that was the silent-accept bug in issue #204.
            Log.e(TAG, "AES-GCM tag authentication failed for private key; refusing to load", e)
            null
        } catch (e: BadPaddingException) {
            // AES-GCM surfaces both tag failures and generic padding failures as
            // BadPaddingException on some providers. Treat identically.
            Log.e(TAG, "AES-GCM padding/tag failure for private key; refusing to load", e)
            null
        } catch (e: IllegalBlockSizeException) {
            Log.e(TAG, "AES-GCM block-size failure for private key; refusing to load", e)
            null
        } catch (e: GeneralSecurityException) {
            // Anything else from the JCA: refuse rather than silently adopt.
            Log.e(TAG, "Unexpected crypto failure decrypting private key; refusing to load", e)
            null
        }
    }

    /**
     * Record that we have completed the one-shot plaintext-to-encrypted
     * migration (issue #204). Once present, this marker makes `getPrivateKey`
     * refuse any plaintext PEM found in the key file -- that can only happen
     * via tampering, so silently accepting it would defeat the AES-GCM
     * authentication we already rely on.
     */
    private fun markKeyMigrated() {
        try {
            keyMigrationMarkerFile.writeText("1")
        } catch (e: IOException) {
            // Non-fatal: if we cannot create the marker, the worst case is a
            // repeated WARN-log migration on next boot. We still return the
            // migrated key to the caller.
            Log.w(TAG, "Failed to write key migration marker", e)
        }
    }

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

    override suspend fun clear() {
        clearCertificates()
        subdomainFile.delete()
        integrationSecretsFile.delete()
        legacySecretPrefixFile.delete()
    }

    override suspend fun clearCertificates() {
        // Narrow rollback for cert-provisioning failures. Must NOT touch the
        // subdomain or integration-secrets files -- those represent completed
        // onboarding state that outlives any individual cert-provisioning
        // attempt (issue #163).
        certFile.delete()
        File(filesDir, CLIENT_CERT_PEM_FILE).delete()
        File(filesDir, RELAY_CA_PEM_FILE).delete()
        File(filesDir, KEY_PEM_FILE).delete()
        keyMigrationMarkerFile.delete()
        fingerprintsFile.delete()
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
        if (keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS)
        }
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

    private fun getOrCreateEncryptionKey(): SecretKey {
        encryptionKeyProvider?.let { return it() }
        val keyStore = androidKeyStore()
        val existingKey = keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .build()

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun ensureKeyPairExists() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setKeySize(EC_KEY_SIZE)
            .build()

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
    }

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
        private const val KEY_PEM_FILE = "rouse_key.pem"
        private const val KEY_MIGRATION_MARKER_FILE = "rouse_key_migrated.flag"
        private const val SUBDOMAIN_FILE = "rouse_subdomain.txt"
        private const val INTEGRATION_SECRETS_FILE = "rouse_integration_secrets.json"
        private const val LEGACY_SECRET_PREFIX_FILE = "rouse_secret_prefix.txt"
        private const val FINGERPRINTS_FILE = "rouse_fingerprints.txt"
        private const val KEY_ALIAS = "rouse_device_key"
        private const val ENCRYPTION_KEY_ALIAS = "rouse_key_encryption_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8
        private const val AES_KEY_SIZE = 256
        private const val PEM_LEADING_BYTE: Byte = '-'.code.toByte()
        private const val LINE_LENGTH = 64
        private const val EC_KEY_SIZE = 256

        /** Compute SHA-256 fingerprint of a DER-encoded certificate. */
        fun sha256Fingerprint(derBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(derBytes)
                .joinToString(":") { "%02X".format(it) }
        }
    }
}
