package com.jaac.avoqado_tpv.features.permissions.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Permissions Repository
 *
 * Manages staff permissions with local caching for performance.
 * Permissions are fetched from backend and cached for 5 minutes.
 *
 * **Permission Sources (Backend merges these):**
 * 1. Base permissions: DEFAULT_PERMISSIONS by role
 * 2. Custom permissions: VenueRolePermission (dashboard-configured)
 * 3. Implicit permissions: Permission dependencies
 *
 * **Caching Strategy:**
 * - 5-minute TTL (Time-To-Live)
 * - Fetch on login → Cache
 * - Clear on logout
 * - Force refresh available for critical operations
 *
 * **Usage:**
 * ```kotlin
 * @Inject lateinit var permissionsRepository: PermissionsRepository
 *
 * // Check permission before showing UI
 * val canAccessSettings = permissionsRepository.hasPermission("tpv-terminal:settings")
 * if (canAccessSettings) {
 *     // Show settings button
 * }
 *
 * // Force refresh after permission change in dashboard
 * permissionsRepository.getPermissions(forceRefresh = true)
 * ```
 *
 * @param apiService API service for fetching permissions
 * @param secureStorage Secure storage for caching permissions
 */
@Singleton
class PermissionsRepository @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val CACHE_EXPIRY_MS = 5 * 60 * 1000L  // 5 minutes
        private const val CACHE_KEY_PERMISSIONS = "permissions_cache"
        private const val CACHE_KEY_TIMESTAMP = "permissions_cache_timestamp"
    }

    /**
     * Get staff permissions with caching
     *
     * **Flow:**
     * 1. Check cache validity (< 5 min old)
     * 2. If valid → return cached permissions
     * 3. If invalid or forceRefresh → fetch from backend
     * 4. Cache new permissions
     * 5. Return permissions
     *
     * **Error Handling:**
     * - Network error → Return cached permissions if available (stale-while-revalidate)
     * - No cache + network error → Return failure
     *
     * @param forceRefresh Skip cache and fetch fresh data
     * @return Result with permissions set or error
     */
    suspend fun getPermissions(forceRefresh: Boolean = false): Result<Set<String>> {
        // 1. Check cache if not forcing refresh
        if (!forceRefresh) {
            getCachedPermissions()?.let { cachedPerms ->
                Timber.d("🔐 Using cached permissions (${cachedPerms.size} permissions)")
                return Result.success(cachedPerms)
            }
        }

        // 2. Fetch from backend
        Timber.d("🔐 Fetching permissions from backend (forceRefresh=$forceRefresh)")
        return try {
            val response = apiService.getStaffPermissions()

            if (response.isSuccessful && response.body()?.success == true) {
                val permissionsData = response.body()!!.data
                val permissions = permissionsData.permissions.toSet()

                // Cache permissions
                cachePermissions(permissions)

                Timber.i("✅ Permissions fetched successfully (${permissions.size} permissions, role: ${permissionsData.role})")
                Result.success(permissions)
            } else {
                val errorCode = response.code()
                val errorMsg = response.message()
                Timber.e("❌ Failed to fetch permissions: $errorCode $errorMsg")

                // Fallback to cached permissions if available (stale-while-revalidate)
                getCachedPermissions(ignoreExpiry = true)?.let { stalePerms ->
                    Timber.w("⚠️ Using stale cached permissions due to fetch failure")
                    Result.success(stalePerms)
                } ?: Result.failure(Exception("Failed to fetch permissions: $errorCode $errorMsg"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Network error fetching permissions")

            // Fallback to cached permissions (even if stale)
            getCachedPermissions(ignoreExpiry = true)?.let { stalePerms ->
                Timber.w("⚠️ Using stale cached permissions due to network error")
                Result.success(stalePerms)
            } ?: Result.failure(e)
        }
    }

    /**
     * Check if staff has specific permission
     *
     * Convenience method for UI checks.
     * Uses cached permissions if available (doesn't trigger fetch).
     *
     * **Usage:**
     * ```kotlin
     * if (permissionsRepository.hasPermission("payments:refund")) {
     *     // Show refund button
     * }
     * ```
     *
     * @param permission Permission to check (e.g., "tpv-terminal:settings")
     * @return true if permission exists in cache, false otherwise
     */
    suspend fun hasPermission(permission: String): Boolean {
        val permissions = getPermissions(forceRefresh = false).getOrNull() ?: return false
        val hasIt = permissions.contains(permission)
        Timber.v("🔐 Permission check: $permission → $hasIt")
        return hasIt
    }

    /**
     * Clear permissions cache
     *
     * Called on logout to ensure next login fetches fresh permissions.
     */
    fun clearCache() {
        secureStorage.remove(CACHE_KEY_PERMISSIONS)
        secureStorage.remove(CACHE_KEY_TIMESTAMP)
        Timber.d("🗑️ Permissions cache cleared")
    }

    /**
     * Get cached permissions if valid
     *
     * @param ignoreExpiry Return cached permissions even if expired (for fallback)
     * @return Cached permissions set or null if cache invalid/expired
     */
    private fun getCachedPermissions(ignoreExpiry: Boolean = false): Set<String>? {
        val timestamp = secureStorage.getLong(CACHE_KEY_TIMESTAMP, 0L)
        val age = System.currentTimeMillis() - timestamp

        // Check expiry
        if (!ignoreExpiry && age > CACHE_EXPIRY_MS) {
            Timber.v("⏰ Permission cache expired (age: ${age}ms)")
            return null
        }

        // Get cached permissions
        val permissionsStr = secureStorage.getString(CACHE_KEY_PERMISSIONS, null)
        return permissionsStr?.split(",")?.filter { it.isNotBlank() }?.toSet()?.also {
            Timber.v("📦 Cache hit (${it.size} permissions, age: ${age}ms)")
        }
    }

    /**
     * Cache permissions to secure storage
     *
     * Stores permissions as comma-separated string with timestamp.
     *
     * @param permissions Set of permissions to cache
     */
    private fun cachePermissions(permissions: Set<String>) {
        val permissionsStr = permissions.joinToString(",")
        secureStorage.putString(CACHE_KEY_PERMISSIONS, permissionsStr)
        secureStorage.putLong(CACHE_KEY_TIMESTAMP, System.currentTimeMillis())
        Timber.d("💾 Permissions cached (${permissions.size} permissions)")
    }
}
