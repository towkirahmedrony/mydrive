package com.mydrive.app.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for Private Vault media.
 *
 * Design, and why:
 *  - **AES-256-GCM** from the platform (`javax.crypto`), never a hand-rolled
 *    construction. GCM authenticates as well as encrypts, so a tampered vault file
 *    fails to decrypt instead of yielding corrupt media.
 *  - **The key lives in the Android Keystore** and is never exported: no key
 *    material is written to Room, to preferences, to Supabase or to a log. This is
 *    the same pattern the project already uses for the Telegram bot token
 *    (`TelegramSettingsStore`), so the vault adds no new cryptographic dependency
 *    and no new dependency at all.
 *  - **Streamed**, not buffered: a multi-hundred-megabyte video must not be loaded
 *    into memory, so encryption goes through `CipherInputStream`/`CipherOutputStream`
 *    with one cipher context per file.
 *  - **App-private storage**: files live under `filesDir/vault/`, which is not
 *    DCIM/Pictures/Movies/Downloads or any shared location, and is excluded from
 *    backup by the app's data-extraction rules.
 *  - Each file carries its own random 96-bit IV in a small self-describing header,
 *    so a vault file stays decryptable even if the metadata row is lost, and the
 *    same (key, IV) pair is never reused.
 *
 * NOTE on the authentication model: the Keystore key is not bound to
 * `setUserAuthenticationRequired`, because the vault must be openable by EITHER
 * biometric OR the vault PIN. Gating decryption behind a biometric-bound key would
 * make the PIN fallback impossible without adding a dependency. Access is instead
 * gated by [VaultSession]; the key's job is to protect the bytes at rest (device
 * theft, file extraction, adb pull of app data on a locked device).
 */
class VaultCrypto(context: Context) {

    private val vaultDir: File = File(context.filesDir, VAULT_DIR_NAME).apply { mkdirs() }

    /**
     * Where a decrypted preview may live *transiently* while the vault is open.
     * It sits under the app's private cache (never a public/shared directory,
     * never `cacheDir`'s top level where an image library could adopt it), is
     * emptied whenever the vault locks, and is not read by the normal
     * Photos/Albums thumbnail pipeline.
     */
    private val previewDir: File = File(context.cacheDir, PREVIEW_DIR_NAME).apply { mkdirs() }

    /** The app-private directory holding ciphertext. Never a public/shared path. */
    fun vaultDirectory(): File = vaultDir

    /** Absolute file for a vault item's ciphertext. */
    fun ciphertextFile(vaultItemId: String): File = File(vaultDir, "$vaultItemId$FILE_SUFFIX")

    /**
     * Destination for a decrypted preview / temporary restore file. Only ever used
     * while the vault session is unlocked, and purged by [clearTransientPreviews].
     */
    fun previewFile(vaultItemId: String): File = File(previewDir, "$vaultItemId$PREVIEW_SUFFIX")

    /**
     * Deletes every decrypted transient file. Called on lock, on background and on
     * vault exit so no readable vault copy survives the unlocked session.
     */
    fun clearTransientPreviews() {
        runCatching {
            previewDir.listFiles()?.forEach { file -> file.delete() }
        }
        runCatching {
            vaultDir.listFiles()
                ?.filter { it.name.endsWith(PART_SUFFIX) || it.name.endsWith(PREVIEW_SUFFIX) }
                ?.forEach { it.delete() }
        }
    }

    /**
     * Encrypts [plaintext] into the vault, returns the ciphertext metadata.
     *
     * Writes to a temporary `.part` file and only renames it into place once the
     * stream is closed successfully, so an interrupted run can never be mistaken
     * for a complete vault copy.
     */
    fun encryptToVault(
        vaultItemId: String,
        plaintext: InputStream,
        onProgress: ((bytesWritten: Long) -> Unit)? = null
    ): VaultWriteResult {
        val target = ciphertextFile(vaultItemId)
        val part = File(vaultDir, "$vaultItemId$FILE_SUFFIX$PART_SUFFIX")
        var written = 0L
        try {
            // Android Keystore AES-GCM forbids a caller-supplied IV when
            // setRandomizedEncryptionRequired(true). Let the Keystore generate it.
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv?.copyOf() ?: return VaultWriteResult.Failed("missing_iv")
            part.outputStream().use { rawOut ->
                rawOut.write(HEADER_MAGIC)
                rawOut.write(HEADER_VERSION)
                rawOut.write(iv.size)
                rawOut.write(iv)
                CipherOutputStream(rawOut, cipher).use { out ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val read = plaintext.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buffer, 0, read)
                        written += read
                        onProgress?.invoke(written)
                    }
                    out.flush()
                }
            }
            if (!part.renameTo(target)) {
                return VaultWriteResult.Failed("ciphertext_rename_failed")
            }
            val size = target.length()
            if (size <= HEADER_BYTES) {
                target.delete()
                return VaultWriteResult.Failed("ciphertext_empty")
            }
            return VaultWriteResult.Success(
                fileName = target.name,
                ciphertextBytes = size,
                ciphertextSha256 = sha256(target)
            )
        } catch (error: Exception) {
            // Never leave a partial ciphertext behind: it must not be mistakable
            // for a verified vault copy.
            runCatching { part.delete() }
            runCatching { target.delete() }
            return VaultWriteResult.Failed(error.javaClass.simpleName)
        }
    }

    /**
     * Decrypts a vault file to [destination] (a private temporary file used for
     * restore or in-memory viewing). Fails safely on a missing/corrupt file.
     */
    fun decryptFromVault(vaultItemId: String, destination: File): VaultWriteResult {
        val source = ciphertextFile(vaultItemId)
        if (!source.isFile || source.length() <= HEADER_BYTES) {
            return VaultWriteResult.Failed("ciphertext_missing")
        }
        var written = 0L
        try {
            source.inputStream().use { rawIn ->
                val magic = ByteArray(HEADER_MAGIC.size)
                if (rawIn.read(magic) != magic.size || !magic.contentEquals(HEADER_MAGIC)) {
                    return VaultWriteResult.Failed("ciphertext_bad_header")
                }
                val version = rawIn.read()
                if (version != HEADER_VERSION_INT) {
                    return VaultWriteResult.Failed("ciphertext_unsupported_version")
                }
                val ivLength = rawIn.read()
                if (ivLength <= 0 || ivLength > MAX_IV_BYTES) {
                    return VaultWriteResult.Failed("ciphertext_bad_iv")
                }
                val iv = ByteArray(ivLength)
                if (rawIn.read(iv) != iv.size) {
                    return VaultWriteResult.Failed("ciphertext_truncated_iv")
                }
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                destination.outputStream().use { fileOut ->
                    CipherInputStream(rawIn, cipher).use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            fileOut.write(buffer, 0, read)
                            written += read
                        }
                        fileOut.flush()
                    }
                }
            }
            // A GCM tag mismatch surfaces as an exception above; reaching here
            // means the ciphertext was authentic.
            return VaultWriteResult.Success(
                fileName = destination.name,
                ciphertextBytes = written,
                ciphertextSha256 = null
            )
        } catch (error: Exception) {
            runCatching { destination.delete() }
            return VaultWriteResult.Failed(error.javaClass.simpleName)
        }
    }

    /** True when a verified-looking ciphertext exists for the item. */
    fun vaultCopyExists(vaultItemId: String): Boolean {
        val file = ciphertextFile(vaultItemId)
        return file.isFile && file.length() > HEADER_BYTES
    }

    /** Removes the ciphertext. Idempotent: deleting a missing file is not an error. */
    fun deleteVaultCopy(vaultItemId: String): Boolean =
        runCatching { !ciphertextFile(vaultItemId).exists() || ciphertextFile(vaultItemId).delete() }
            .getOrDefault(false)

    /**
     * Seals a short string (the vault PIN verifier) under the same Keystore key,
     * so no credential material is readable in preferences even if the app's data
     * directory is copied. Duplicating the Keystore/GCM code elsewhere is avoided
     * by keeping it in this one place.
     */
    fun sealString(plaintext: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv ?: return null
        listOf(iv, cipherText).joinToString(SEAL_SEPARATOR) {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        null
    }

    /** Opens a value produced by [sealString]; null when it cannot be trusted. */
    fun openString(sealed: String?): String? {
        if (sealed.isNullOrBlank()) return null
        return try {
            val (iv, body) = splitSealed(sealed) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun splitSealed(sealed: String): Pair<ByteArray, ByteArray>? {
        val parts = sealed.split(SEAL_SEPARATOR)
        if (parts.size == 2) {
            val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
            val body = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            if (iv.isEmpty() || body.isEmpty()) return null
            return iv to body
        }
        val bytes = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
        if (bytes.size <= GCM_IV_BYTES) return null
        return bytes.copyOfRange(0, GCM_IV_BYTES) to bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
    }

    /** SHA-256 of a file, used to verify a vault copy against its stored hash. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verifies an existing vault copy: present, non-empty and hash-matching. */
    fun verifyVaultCopy(vaultItemId: String, expectedSha256: String?): Boolean {
        val file = ciphertextFile(vaultItemId)
        if (!file.isFile || file.length() <= HEADER_BYTES) return false
        if (expectedSha256.isNullOrBlank()) return true
        return sha256(file).equals(expectedSha256, ignoreCase = true)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    sealed class VaultWriteResult {
        data class Success(
            val fileName: String,
            val ciphertextBytes: Long,
            val ciphertextSha256: String?
        ) : VaultWriteResult()

        /** [reason] is a short, non-sensitive diagnostic code (never key material). */
        data class Failed(val reason: String) : VaultWriteResult()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Versioned so a future key/format change can be migrated deliberately. */
        private const val KEY_ALIAS = "mydrive_vault_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        private const val KEY_SIZE_BITS = 256
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val VAULT_DIR_NAME = "vault"
        private const val PREVIEW_DIR_NAME = "vault_previews"
        private const val FILE_SUFFIX = ".vault"
        private const val PART_SUFFIX = ".part"
        private const val PREVIEW_SUFFIX = ".preview"
        private val HEADER_MAGIC = byteArrayOf(0x4D, 0x56, 0x4C, 0x54) // "MVLT"
        private const val HEADER_VERSION = 1
        private const val HEADER_VERSION_INT = 1
        private const val MAX_IV_BYTES = 32
        private const val HEADER_BYTES = 4 + 1 + 1 + 12
        private const val SEAL_SEPARATOR = ":"
    }
}
