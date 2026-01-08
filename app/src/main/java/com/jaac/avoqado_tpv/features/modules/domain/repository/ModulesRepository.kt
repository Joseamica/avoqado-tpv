package com.jaac.avoqado_tpv.features.modules.domain.repository

import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleConfig
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for venue modules.
 *
 * Modules are configuration-driven features that adapt the TPV to different industries.
 * This repository handles fetching modules from backend and caching them locally.
 *
 * Usage Flow:
 * 1. App startup (SplashScreen) → fetchAndCache() is called after confirming device activation
 *    This ensures modules are available from the beginning, before login
 * 2. Throughout session → Use cached data via getModuleConfig(), isModuleEnabled()
 * 3. Logout → clearCache() is called
 *
 * @see VenueModule
 */
interface ModulesRepository {

    /**
     * StateFlow of cached modules.
     * Emits when modules are fetched or cache is cleared.
     * Use this in Compose to observe module changes.
     */
    val modules: StateFlow<List<VenueModule>>

    /**
     * Fetch modules from backend and cache them locally.
     * Called at app startup (SplashScreen) after device activation is confirmed.
     *
     * @return Result with list of enabled modules or error
     */
    suspend fun fetchAndCache(): Result<List<VenueModule>>

    /**
     * Get all cached modules.
     * Returns empty list if no modules are cached.
     *
     * @return List of cached VenueModule
     */
    fun getCachedModules(): List<VenueModule>

    /**
     * Check if a specific module is enabled.
     *
     * @param moduleCode Module code to check (e.g., "SERIALIZED_INVENTORY")
     * @return true if module is enabled, false otherwise
     */
    fun isModuleEnabled(moduleCode: String): Boolean

    /**
     * Get the configuration for a specific module.
     *
     * @param moduleCode Module code (e.g., "SERIALIZED_INVENTORY")
     * @return ModuleConfig or null if module not found
     */
    fun getModuleConfig(moduleCode: String): ModuleConfig?

    /**
     * Get module by code.
     *
     * @param moduleCode Module code (e.g., "SERIALIZED_INVENTORY")
     * @return VenueModule or null if not found
     */
    fun getModule(moduleCode: String): VenueModule?

    /**
     * Clear cached modules.
     * Should be called on logout.
     */
    fun clearCache()

    companion object {
        // Module codes as constants for type-safety
        const val MODULE_SERIALIZED_INVENTORY = "SERIALIZED_INVENTORY"
        const val MODULE_ATTENDANCE_TRACKING = "ATTENDANCE_TRACKING"
    }
}
