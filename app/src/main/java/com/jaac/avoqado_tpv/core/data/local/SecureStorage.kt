package com.jaac.avoqado_tpv.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage wrapper using EncryptedSharedPreferences
 *
 * Provides type-safe methods to store and retrieve sensitive data:
 * - Session tokens
 * - Auth context (venueId, staffId, permissions)
 * - Blumon credentials
 * - User preferences
 *
 * **Security Features:**
 * - AES256-GCM encryption for values
 * - AES256-SIV encryption for keys
 * - Hardware-backed keystore on supported devices
 * - Automatic key rotation
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var secureStorage: SecureStorage
 *
 * // Save token
 * secureStorage.saveToken("jwt_token_here")
 *
 * // Get token
 * val token = secureStorage.getToken()
 * ```
 *
 * @param context Application context (injected by Hilt)
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "avoqado_secure_prefs"

        // Auth keys
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_VENUE_ID = "venue_id"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_NAME = "staff_name"
        private const val KEY_PERMISSIONS = "permissions"
        private const val KEY_VENUE_LOGO = "venue_logo"
        private const val KEY_VENUE_NAME = "venue_name"
        private const val KEY_VENUE_TYPE = "venue_type"

        // Blumon keys
        private const val KEY_BLUMON_MERCHANT_ID = "blumon_merchant_id"
        private const val KEY_BLUMON_TERMINAL_ID = "blumon_terminal_id"
        private const val KEY_BLUMON_USERNAME = "blumon_username"
        private const val KEY_BLUMON_PASSWORD = "blumon_password"
        private const val KEY_BLUMON_LAST_INIT_TIMESTAMP = "blumon_last_init_timestamp"

        // Settings keys
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        private const val KEY_IS_OFFLINE_MODE = "is_offline_mode"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"

        // Terminal activation keys
        private const val KEY_SERIAL_NUMBER = "serial_number"

        // TPV Settings keys (configurable payment flow screens)
        private const val KEY_TPV_SHOW_REVIEW = "tpv_show_review"
        private const val KEY_TPV_SHOW_TIP = "tpv_show_tip"
        private const val KEY_TPV_SHOW_RECEIPT = "tpv_show_receipt"
        private const val KEY_TPV_DEFAULT_TIP = "tpv_default_tip"
        private const val KEY_TPV_TIP_SUGGESTIONS = "tpv_tip_suggestions"
        private const val KEY_TPV_REQUIRE_PIN = "tpv_require_pin"
    }

    /**
     * Master key for encryption
     * Uses AES256-GCM scheme with hardware-backed keystore when available
     */
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Encrypted SharedPreferences instance
     * Keys and values are encrypted using AES256
     *
     * **Corruption Handling:**
     * If EncryptedSharedPreferences becomes corrupted (device key change, factory reset),
     * we delete the corrupted storage and create a fresh one.
     * This is safer than crashing the app.
     *
     * Pattern used by Square POS: Graceful degradation > Hard crashes
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPreferences()
        } catch (e: Exception) {
            Timber.e(e, "🔥 Encrypted storage corrupted - attempting recovery")

            // Delete corrupted storage files
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit()
                context.deleteSharedPreferences(PREFS_NAME)
                Timber.d("✅ Corrupted storage deleted")
            } catch (deleteError: Exception) {
                Timber.e(deleteError, "Failed to delete corrupted storage")
            }

            // Attempt to recreate
            try {
                val fresh = createEncryptedPreferences()
                Timber.d("✅ Fresh encrypted storage created after corruption")
                fresh
            } catch (recreateError: Exception) {
                Timber.e(recreateError, "💥 Cannot recover from storage corruption")
                throw SecurityException("Cannot initialize secure storage after corruption recovery attempt", recreateError)
            }
        }
    }

    /**
     * Create EncryptedSharedPreferences instance
     *
     * Separated into method for corruption recovery logic
     */
    private fun createEncryptedPreferences(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ========== Session Management ==========

    /**
     * Save authentication token
     * @param token JWT token from backend
     */
    fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_SESSION_TOKEN, token).apply()
        Timber.d("Session token saved securely")
    }

    /**
     * Get authentication token
     * @return JWT token or null if not authenticated
     */
    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_SESSION_TOKEN, null)
    }

    /**
     * Save refresh token
     * @param refreshToken Refresh token from backend
     */
    fun saveRefreshToken(refreshToken: String) {
        encryptedPrefs.edit().putString(KEY_REFRESH_TOKEN, refreshToken).apply()
        Timber.d("Refresh token saved securely")
    }

    /**
     * Get refresh token
     * @return Refresh token or null if not available
     */
    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Check if user is authenticated
     * @return true if session token exists
     */
    fun isAuthenticated(): Boolean {
        return getToken() != null
    }

    /**
     * Clear authentication session
     * Removes token and auth context
     *
     * ⚠️ IMPORTANT: venueId is NOT cleared because it's part of device activation,
     * not user session. The device remains activated to the venue even after logout.
     * Only the staff member's session (token, refreshToken, staffId, name, permissions) is cleared.
     */
    fun clearSession() {
        encryptedPrefs.edit().apply {
            remove(KEY_SESSION_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            // DO NOT remove KEY_VENUE_ID - device activation persists across logout!
            remove(KEY_STAFF_ID)
            remove(KEY_STAFF_NAME)
            remove(KEY_PERMISSIONS)
        }.apply()
        Timber.d("Session cleared (venueId preserved for device activation)")
    }

    // ========== Auth Context ==========

    /**
     * Save venue ID for multi-tenant isolation
     * @param venueId Venue identifier
     */
    fun saveVenueId(venueId: String) {
        encryptedPrefs.edit().putString(KEY_VENUE_ID, venueId).apply()
    }

    /**
     * Get current venue ID
     * @return Venue ID or null if not set
     */
    fun getVenueId(): String? {
        return encryptedPrefs.getString(KEY_VENUE_ID, null)
    }

    /**
     * Save staff member ID
     * @param staffId Staff identifier
     */
    fun saveStaffId(staffId: String) {
        encryptedPrefs.edit().putString(KEY_STAFF_ID, staffId).apply()
    }

    /**
     * Get current staff ID
     * @return Staff ID or null if not set
     */
    fun getStaffId(): String? {
        return encryptedPrefs.getString(KEY_STAFF_ID, null)
    }

    /**
     * Save staff member name
     * @param name Staff display name
     */
    fun saveStaffName(name: String) {
        encryptedPrefs.edit().putString(KEY_STAFF_NAME, name).apply()
    }

    /**
     * Get current staff name
     * @return Staff name or null if not set
     */
    fun getStaffName(): String? {
        return encryptedPrefs.getString(KEY_STAFF_NAME, null)
    }

    /**
     * Save venue logo URL
     * @param logoUrl Venue logo URL (cached for login screen)
     */
    fun saveVenueLogo(logoUrl: String?) {
        if (logoUrl != null) {
            encryptedPrefs.edit().putString(KEY_VENUE_LOGO, logoUrl).apply()
        } else {
            encryptedPrefs.edit().remove(KEY_VENUE_LOGO).apply()
        }
    }

    /**
     * Get cached venue logo URL
     * @return Venue logo URL or null if not set
     */
    fun getVenueLogo(): String? {
        return encryptedPrefs.getString(KEY_VENUE_LOGO, null)
    }

    /**
     * Save venue name
     * @param name Venue name (cached for UI display)
     */
    fun saveVenueName(name: String) {
        encryptedPrefs.edit().putString(KEY_VENUE_NAME, name).apply()
    }

    /**
     * Get cached venue name
     * @return Venue name or null if not set
     */
    fun getVenueName(): String? {
        return encryptedPrefs.getString(KEY_VENUE_NAME, null)
    }

    /**
     * Save venue type
     * @param type Venue type (RESTAURANT, BAR, CAFE, FAST_FOOD, RETAIL_STORE, etc.)
     */
    fun saveVenueType(type: String?) {
        if (type != null) {
            encryptedPrefs.edit().putString(KEY_VENUE_TYPE, type).apply()
        } else {
            encryptedPrefs.edit().remove(KEY_VENUE_TYPE).apply()
        }
    }

    /**
     * Get venue type
     * @return Venue type or null if not set
     */
    fun getVenueType(): String? {
        return encryptedPrefs.getString(KEY_VENUE_TYPE, null)
    }

    /**
     * Check if venue supports table service
     *
     * Table service is available for:
     * - RESTAURANT
     * - BAR
     * - CAFE
     * - FAST_FOOD
     *
     * NOT available for:
     * - FOOD_TRUCK
     * - RETAIL_STORE
     * - HOTEL_RESTAURANT (uses room service instead)
     * - FITNESS_STUDIO
     * - SPA
     * - OTHER
     *
     * @return true if venue supports table service
     */
    fun supportsTableService(): Boolean {
        val type = getVenueType() ?: return true  // Default to true if not set
        return type in listOf("RESTAURANT", "BAR", "CAFE", "FAST_FOOD")
    }

    /**
     * Save staff permissions (comma-separated)
     * @param permissions List of permission strings
     */
    fun savePermissions(permissions: List<String>) {
        val permissionsStr = permissions.joinToString(",")
        encryptedPrefs.edit().putString(KEY_PERMISSIONS, permissionsStr).apply()
    }

    /**
     * Get staff permissions
     * @return List of permission strings
     */
    fun getPermissions(): List<String> {
        val permissionsStr = encryptedPrefs.getString(KEY_PERMISSIONS, null)
        return permissionsStr?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    }

    /**
     * Check if staff has specific permission
     * @param permission Permission to check
     * @return true if permission exists
     */
    fun hasPermission(permission: String): Boolean {
        return getPermissions().contains(permission)
    }

    // ========== Blumon Credentials ==========

    /**
     * Save Blumon merchant ID
     * @param merchantId Blumon merchant identifier
     */
    fun saveBlumonMerchantId(merchantId: String) {
        encryptedPrefs.edit().putString(KEY_BLUMON_MERCHANT_ID, merchantId).apply()
        Timber.d("Blumon merchant ID saved")
    }

    /**
     * Get Blumon merchant ID
     * @return Merchant ID or null if not configured
     */
    fun getBlumonMerchantId(): String? {
        return encryptedPrefs.getString(KEY_BLUMON_MERCHANT_ID, null)
    }

    /**
     * Save Blumon terminal ID
     * @param terminalId Blumon terminal identifier
     */
    fun saveBlumonTerminalId(terminalId: String) {
        encryptedPrefs.edit().putString(KEY_BLUMON_TERMINAL_ID, terminalId).apply()
        Timber.d("Blumon terminal ID saved")
    }

    /**
     * Get Blumon terminal ID
     * @return Terminal ID or null if not configured
     */
    fun getBlumonTerminalId(): String? {
        return encryptedPrefs.getString(KEY_BLUMON_TERMINAL_ID, null)
    }

    /**
     * Save Blumon API credentials
     * @param username Blumon API username
     * @param password Blumon API password
     */
    fun saveBlumonCredentials(username: String, password: String) {
        encryptedPrefs.edit().apply {
            putString(KEY_BLUMON_USERNAME, username)
            putString(KEY_BLUMON_PASSWORD, password)
        }.apply()
        Timber.d("Blumon credentials saved securely")
    }

    /**
     * Get Blumon API username
     * @return Username or null if not configured
     */
    fun getBlumonUsername(): String? {
        return encryptedPrefs.getString(KEY_BLUMON_USERNAME, null)
    }

    /**
     * Get Blumon API password
     * @return Password or null if not configured
     */
    fun getBlumonPassword(): String? {
        return encryptedPrefs.getString(KEY_BLUMON_PASSWORD, null)
    }

    /**
     * Check if Blumon credentials are configured
     * @return true if both username and password exist
     */
    fun hasBlumonCredentials(): Boolean {
        return getBlumonUsername() != null && getBlumonPassword() != null
    }

    /**
     * Clear all Blumon credentials
     */
    fun clearBlumonCredentials() {
        encryptedPrefs.edit().apply {
            remove(KEY_BLUMON_MERCHANT_ID)
            remove(KEY_BLUMON_TERMINAL_ID)
            remove(KEY_BLUMON_USERNAME)
            remove(KEY_BLUMON_PASSWORD)
        }.apply()
        Timber.d("Blumon credentials cleared")
    }

    // ========== Settings ==========

    /**
     * Save last sync timestamp
     * @param timestamp Unix timestamp in milliseconds
     */
    fun saveLastSyncTimestamp(timestamp: Long) {
        encryptedPrefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp).apply()
    }

    /**
     * Get last sync timestamp
     * @return Unix timestamp or 0 if never synced
     */
    fun getLastSyncTimestamp(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
    }

    /**
     * Save offline mode setting
     * @param isOffline true if app is in offline mode
     */
    fun saveOfflineMode(isOffline: Boolean) {
        encryptedPrefs.edit().putBoolean(KEY_IS_OFFLINE_MODE, isOffline).apply()
    }

    /**
     * Get offline mode setting
     * @return true if app is in offline mode
     */
    fun isOfflineMode(): Boolean {
        return encryptedPrefs.getBoolean(KEY_IS_OFFLINE_MODE, false)
    }

    /**
     * Save selected language
     * @param languageCode ISO 639-1 language code (e.g., "es", "en")
     */
    fun saveLanguage(languageCode: String) {
        encryptedPrefs.edit().putString(KEY_SELECTED_LANGUAGE, languageCode).apply()
    }

    /**
     * Get selected language
     * @return Language code or "es" (default)
     */
    fun getLanguage(): String {
        return encryptedPrefs.getString(KEY_SELECTED_LANGUAGE, "es") ?: "es"
    }

    // ========== Terminal Activation ==========

    /**
     * Save terminal serial number (set during activation)
     * @param serialNumber Device serial number
     */
    fun saveSerialNumber(serialNumber: String) {
        encryptedPrefs.edit().putString(KEY_SERIAL_NUMBER, serialNumber).apply()
        Timber.d("Serial number saved securely")
    }

    /**
     * Get terminal serial number
     * @return Serial number or null if not activated
     */
    fun getSerialNumber(): String? {
        return encryptedPrefs.getString(KEY_SERIAL_NUMBER, null)
    }

    /**
     * Check if terminal is activated (has serial number)
     * @return true if serial number exists
     */
    fun isTerminalActivated(): Boolean {
        return getSerialNumber() != null
    }

    // ========== Utility Methods ==========

    /**
     * Clear ALL data from secure storage
     * ⚠️ USE WITH CAUTION - This will delete everything
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        Timber.w("All secure storage data cleared")
    }

    /**
     * Generic method to save string value
     * @param key Storage key
     * @param value String value to save
     */
    fun putString(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    /**
     * Generic method to get string value
     * @param key Storage key
     * @param defaultValue Default value if key doesn't exist
     * @return Stored value or default
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return encryptedPrefs.getString(key, defaultValue)
    }

    /**
     * Generic method to save boolean value
     * @param key Storage key
     * @param value Boolean value to save
     */
    fun putBoolean(key: String, value: Boolean) {
        encryptedPrefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Generic method to get boolean value
     * @param key Storage key
     * @param defaultValue Default value if key doesn't exist
     * @return Stored value or default
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return encryptedPrefs.getBoolean(key, defaultValue)
    }

    /**
     * Generic method to save long value
     * @param key Storage key
     * @param value Long value to save
     */
    fun putLong(key: String, value: Long) {
        encryptedPrefs.edit().putLong(key, value).apply()
    }

    /**
     * Generic method to get long value
     * @param key Storage key
     * @param defaultValue Default value if key doesn't exist
     * @return Stored value or default
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return encryptedPrefs.getLong(key, defaultValue)
    }

    /**
     * Check if key exists in storage
     * @param key Storage key
     * @return true if key exists
     */
    fun contains(key: String): Boolean {
        return encryptedPrefs.contains(key)
    }

    /**
     * Remove specific key from storage
     * @param key Storage key to remove
     */
    fun remove(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BLUMON SDK INITIALIZATION TIMESTAMP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Save last Blumon SDK initialization timestamp
     *
     * Used by InitializationManager to enforce "init only once every 24 hours" policy
     * per Edgardo's recommendation (2025-11-05)
     *
     * @param timestamp Unix epoch milliseconds (System.currentTimeMillis())
     */
    fun saveLastBlumonInitTimestamp(timestamp: Long) {
        encryptedPrefs.edit().putLong(KEY_BLUMON_LAST_INIT_TIMESTAMP, timestamp).apply()
        Timber.d("💾 Blumon last init timestamp saved: $timestamp")
    }

    /**
     * Get last Blumon SDK initialization timestamp
     *
     * Returns null if SDK has never been initialized (first run)
     *
     * @return Unix epoch milliseconds or null if never initialized
     */
    fun getLastBlumonInitTimestamp(): Long? {
        return if (encryptedPrefs.contains(KEY_BLUMON_LAST_INIT_TIMESTAMP)) {
            encryptedPrefs.getLong(KEY_BLUMON_LAST_INIT_TIMESTAMP, 0L)
        } else {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TPV SETTINGS (Configurable Payment Flow Screens)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Save TPV screen configuration settings
     *
     * Caches venue-specific payment flow settings for offline access.
     * Settings control which screens are shown during the payment process.
     *
     * @param settings TpvSettings domain model
     */
    fun saveTpvSettings(settings: TpvSettings) {
        encryptedPrefs.edit().apply {
            putBoolean(KEY_TPV_SHOW_REVIEW, settings.showReviewScreen)
            putBoolean(KEY_TPV_SHOW_TIP, settings.showTipScreen)
            putBoolean(KEY_TPV_SHOW_RECEIPT, settings.showReceiptScreen)
            if (settings.defaultTipPercentage != null) {
                putInt(KEY_TPV_DEFAULT_TIP, settings.defaultTipPercentage)
            } else {
                remove(KEY_TPV_DEFAULT_TIP)
            }
            putString(KEY_TPV_TIP_SUGGESTIONS, settings.tipSuggestions.joinToString(","))
            putBoolean(KEY_TPV_REQUIRE_PIN, settings.requirePinLogin)
        }.apply()
        Timber.d("💾 TPV settings saved: showReview=${settings.showReviewScreen}, showTip=${settings.showTipScreen}, showReceipt=${settings.showReceiptScreen}")
    }

    /**
     * Get TPV screen configuration settings
     *
     * Returns cached settings or defaults if not configured.
     * Defaults enable all screens for maximum compatibility.
     *
     * @return TpvSettings domain model
     */
    fun getTpvSettings(): TpvSettings {
        val tipSuggestionsStr = encryptedPrefs.getString(KEY_TPV_TIP_SUGGESTIONS, null)
        val tipSuggestions = tipSuggestionsStr
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(10, 15, 20)

        val defaultTip = if (encryptedPrefs.contains(KEY_TPV_DEFAULT_TIP)) {
            encryptedPrefs.getInt(KEY_TPV_DEFAULT_TIP, 15)
        } else {
            null
        }

        return TpvSettings(
            showReviewScreen = encryptedPrefs.getBoolean(KEY_TPV_SHOW_REVIEW, true),
            showTipScreen = encryptedPrefs.getBoolean(KEY_TPV_SHOW_TIP, true),
            showReceiptScreen = encryptedPrefs.getBoolean(KEY_TPV_SHOW_RECEIPT, true),
            defaultTipPercentage = defaultTip,
            tipSuggestions = tipSuggestions,
            requirePinLogin = encryptedPrefs.getBoolean(KEY_TPV_REQUIRE_PIN, true)
        )
    }

    /**
     * Clear TPV settings (useful when switching venues)
     */
    fun clearTpvSettings() {
        encryptedPrefs.edit().apply {
            remove(KEY_TPV_SHOW_REVIEW)
            remove(KEY_TPV_SHOW_TIP)
            remove(KEY_TPV_SHOW_RECEIPT)
            remove(KEY_TPV_DEFAULT_TIP)
            remove(KEY_TPV_TIP_SUGGESTIONS)
            remove(KEY_TPV_REQUIRE_PIN)
        }.apply()
        Timber.d("TPV settings cleared")
    }
}
