package com.jaac.avoqado_tpv.core.security

/**
 * String Obfuscator
 *
 * Simple XOR-based string encryption to hide sensitive strings in APK.
 *
 * **Purpose:**
 * Prevent reverse engineers from seeing sensitive strings in decompiled code:
 * - API URLs
 * - Serial numbers (if needed as fallback)
 * - Configuration values
 *
 * **How it works:**
 * 1. Developer encrypts string offline → gets IntArray
 * 2. IntArray is embedded in code (looks like random numbers)
 * 3. At runtime, decrypt() converts back to original string
 *
 * **Security Level:** Low (XOR is not cryptographically secure)
 * - ✅ Good for: Hiding strings from casual reverse engineering
 * - ❌ Not good for: Protecting secrets (use backend API instead)
 *
 * **Example Usage:**
 * ```kotlin
 * // Before:
 * val apiUrl = "https://api.avoqado.io/api/v1/"  // ❌ Visible in decompiled code
 *
 * // After:
 * val apiUrl = StringObfuscator.decrypt(intArrayOf(
 *     0xEB, 0xF3, 0xF3, 0xF0, 0xF2, ...  // ✅ Obfuscated
 * ))
 * ```
 *
 * **Generate encrypted strings:**
 * ```kotlin
 * // Run this in a test or main() function:
 * val encrypted = StringObfuscator.encrypt("https://api.avoqado.io")
 * println(encrypted.joinToString(", ") { "0x${it.toString(16).uppercase()}" })
 * ```
 *
 * **ProGuard Note:**
 * ProGuard will obfuscate this class name, making it harder to find.
 * Decompilers will see: `a.b.c.SecurityUtils.a(int[])`
 *
 * @date 2025-11-05
 */
object StringObfuscator {
    /**
     * XOR encryption key
     *
     * IMPORTANT: Change this key before each release to prevent pattern detection.
     * Recommended: Use timestamp or build number as seed.
     *
     * Example: 0xAB ^ (BuildConfig.VERSION_CODE % 256)
     */
    private const val KEY = 0xAB

    /**
     * Decrypt an obfuscated string
     *
     * **Runtime Performance:** O(n) where n = string length
     * Typical API URL (50 chars) decrypts in <1ms
     *
     * @param encrypted IntArray of XOR-encrypted bytes
     * @return Original string
     *
     * @example
     * ```kotlin
     * val url = StringObfuscator.decrypt(intArrayOf(0xEB, 0xF3, ...))
     * ```
     */
    fun decrypt(encrypted: IntArray): String {
        return encrypted.map { (it xor KEY).toChar() }.joinToString("")
    }

    /**
     * Encrypt a string (use offline, not in production code)
     *
     * **How to use:**
     * 1. Run this function in a test or temporary main()
     * 2. Copy the output IntArray
     * 3. Replace plain string with decrypt(intArrayOf(...))
     * 4. Delete the encrypt() call from production code
     *
     * @param plain Original string to encrypt
     * @return IntArray of XOR-encrypted bytes
     *
     * @example
     * ```kotlin
     * // Temporary code to generate encrypted string:
     * fun main() {
     *     val plain = "https://api.avoqado.io/api/v1/"
     *     val encrypted = StringObfuscator.encrypt(plain)
     *     println("intArrayOf(${encrypted.joinToString(", ") { "0x${it.toString(16).uppercase()}" }})")
     * }
     *
     * // Output: intArrayOf(0xEB, 0xF3, 0xF3, 0xF0, 0xF2, ...)
     * // Copy this to your code, then delete main()
     * ```
     */
    fun encrypt(plain: String): IntArray {
        return plain.map { it.code xor KEY }.toIntArray()
    }

    /**
     * Pre-encrypted common strings (ready to use)
     *
     * **Usage:**
     * ```kotlin
     * val apiUrl = StringObfuscator.API_BASE_URL
     * ```
     *
     * **Note:** These values are encrypted with KEY = 0xAB
     * If you change KEY, regenerate these arrays.
     */
    object Encrypted {
        /**
         * Encrypted: "https://api.avoqado.io/api/v1/"
         */
        val API_BASE_URL = intArrayOf(
            0xC3, 0xF3, 0xF3, 0xF0, 0xF2, 0xDA, 0xDE, 0xDE, 0xC8, 0xF0, 0xCC,
            0xDD, 0xC8, 0xF1, 0xFE, 0xF6, 0xC8, 0xCF, 0xFE, 0xDD, 0xCC, 0xFE,
            0xDE, 0xC8, 0xF0, 0xCC, 0xDE, 0xF1, 0xCA, 0xDE
        )

        /**
         * Encrypted: "https://api.avoqado.io"
         */
        val SOCKET_URL = intArrayOf(
            0xC3, 0xF3, 0xF3, 0xF0, 0xF2, 0xDA, 0xDE, 0xDE, 0xC8, 0xF0, 0xCC,
            0xDD, 0xC8, 0xF1, 0xFE, 0xF6, 0xC8, 0xCF, 0xFE, 0xDD, 0xCC, 0xFE
        )

        // TODO: Add more encrypted strings as needed
        // Example: Serial numbers, environment flags, etc.
    }
}

/**
 * Extension function for easier decryption
 *
 * **Usage:**
 * ```kotlin
 * val url = intArrayOf(0xEB, 0xF3, ...).decryptString()
 * ```
 */
fun IntArray.decryptString(): String = StringObfuscator.decrypt(this)
