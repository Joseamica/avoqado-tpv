package com.jaac.avoqado_tpv.core.util

import android.util.Base64
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credentials Decryption Utility
 *
 * Provides AES-256-CBC decryption matching backend encryption.
 * Used to decrypt merchant credentials received from backend API.
 *
 * **Backend Encryption (Node.js):**
 * ```typescript
 * ALGORITHM = 'aes-256-cbc'
 * ENCRYPTION_KEY = SHA256(ENCRYPTION_KEY_RAW)  // 32 bytes
 * IV = crypto.randomBytes(16)
 * encrypted = cipher.update(JSON.stringify(credentials), 'utf8', 'hex')
 * ```
 *
 * **Android Decryption (Kotlin):**
 * ```kotlin
 * val credentials = CredentialsDecryption.decrypt(encryptedData, encryptionKey)
 * ```
 *
 * @author Avoqado Team
 * @date 2025-11-06
 */
object CredentialsDecryption {

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"
    private const val HASH_ALGORITHM = "SHA-256"

    /**
     * Decrypt credentials encrypted by backend
     *
     * @param encryptedData JSON object with { encrypted: "hex", iv: "hex" }
     * @param encryptionKeyRaw Raw encryption key (will be hashed with SHA-256 to match backend)
     * @return Decrypted credentials as JSON string
     * @throws Exception if decryption fails
     */
    fun decrypt(encryptedData: JSONObject, encryptionKeyRaw: String): String {
        try {
            // Extract encrypted data and IV
            val encryptedHex = encryptedData.getString("encrypted")
            val ivHex = encryptedData.getString("iv")

            Timber.d("[CredentialsDecryption] Starting decryption")
            Timber.d("[CredentialsDecryption] IV length: ${ivHex.length / 2} bytes")
            Timber.d("[CredentialsDecryption] Encrypted data length: ${encryptedHex.length / 2} bytes")

            // Convert hex strings to byte arrays
            val encryptedBytes = hexToBytes(encryptedHex)
            val ivBytes = hexToBytes(ivHex)

            // Derive 32-byte key using SHA-256 (matching backend)
            val keyBytes = deriveKey(encryptionKeyRaw)

            // Create cipher specs
            val secretKeySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
            val ivParameterSpec = IvParameterSpec(ivBytes)

            // Decrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)

            val decrypted = String(decryptedBytes, Charsets.UTF_8)

            Timber.i("[CredentialsDecryption] ✅ Decryption successful")
            return decrypted
        } catch (e: Exception) {
            Timber.e(e, "[CredentialsDecryption] ❌ Decryption failed")
            throw Exception("Failed to decrypt credentials: ${e.message}", e)
        }
    }

    /**
     * Derive 32-byte encryption key using SHA-256
     * Matches backend logic: crypto.createHash('sha256').update(key).digest()
     */
    private fun deriveKey(keyRaw: String): ByteArray {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val keyBytes = digest.digest(keyRaw.toByteArray(Charsets.UTF_8))

        Timber.d("[CredentialsDecryption] Key derived using SHA-256: ${keyBytes.size} bytes")
        return keyBytes // SHA-256 always produces 32 bytes
    }

    /**
     * Convert hex string to byte array
     * Matches backend hex encoding: Buffer.toString('hex')
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Check if credentials are encrypted (backend format)
     *
     * @param credentials JSONObject to check
     * @return true if credentials have { encrypted, iv } structure
     */
    fun isEncrypted(credentials: JSONObject): Boolean {
        return credentials.has("encrypted") && credentials.has("iv")
    }
}
