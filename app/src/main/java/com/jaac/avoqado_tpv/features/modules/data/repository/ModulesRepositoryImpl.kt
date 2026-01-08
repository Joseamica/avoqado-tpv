package com.jaac.avoqado_tpv.features.modules.data.repository

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.network.ApiService
import com.jaac.avoqado_tpv.features.modules.data.dto.toDomain
import com.jaac.avoqado_tpv.features.modules.data.dto.toDto
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleConfig
import com.jaac.avoqado_tpv.features.modules.domain.model.VenueModule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ModulesRepository.
 *
 * Handles fetching modules from backend API and caching them locally.
 *
 * Flow:
 * 1. Login success → Call fetchAndCache()
 * 2. UI checks → Use getModuleConfig(), isModuleEnabled() (from cache)
 * 3. Logout → Call clearCache()
 */
@Singleton
class ModulesRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val secureStorage: SecureStorage
) : ModulesRepository {

    // StateFlow for reactive access (Compose can observe changes)
    private val _modules = MutableStateFlow<List<VenueModule>>(emptyList())
    override val modules: StateFlow<List<VenueModule>> = _modules.asStateFlow()

    init {
        // Load from persistent cache on init
        loadFromPersistentCache()
    }

    private fun loadFromPersistentCache() {
        try {
            val dtos = secureStorage.getCachedModules()
            _modules.value = dtos.map { it.toDomain() }
            Timber.d("📦 Loaded ${_modules.value.size} modules from persistent cache")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to load modules from persistent cache")
            _modules.value = emptyList()
        }
    }

    override suspend fun fetchAndCache(): Result<List<VenueModule>> = withContext(Dispatchers.IO) {
        try {
            // Get venueId from activation (stored in SecureStorage)
            val venueId = secureStorage.getVenueId()
            if (venueId.isNullOrEmpty()) {
                Timber.w("📦 No venueId found - device not activated")
                return@withContext Result.failure(Exception("Device not activated"))
            }

            Timber.d("📦 Fetching modules for venue: $venueId")

            // Pass venueId as header for semi-public endpoint (works before login)
            val response = apiService.getModules(venueId)

            if (response.isSuccessful && response.body() != null) {
                val moduleDtos = response.body()!!.modules
                val modules = moduleDtos.map { it.toDomain() }

                // Update both in-memory (StateFlow) and persistent cache
                _modules.value = modules
                secureStorage.saveModules(moduleDtos)

                Timber.i("✅ Fetched and cached ${modules.size} modules: ${modules.map { it.moduleCode }}")

                // Log module configs for debugging
                modules.forEach { module ->
                    Timber.d("📦 Module ${module.moduleCode}:")
                    Timber.d("   - UI: simplifiedOrderFlow=${module.config.ui.simplifiedOrderFlow}, skipTip=${module.config.ui.skipTipScreen}, skipReview=${module.config.ui.skipReviewScreen}")
                    Timber.d("   - Attendance: clockInPhoto=${module.config.attendance.requireClockInPhoto}, clockInGps=${module.config.attendance.requireClockInGps}")
                }

                Result.success(modules)
            } else {
                val errorMsg = parseErrorMessage(response.errorBody()?.string(), response.code())
                Timber.e("❌ Failed to fetch modules: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error fetching modules")
            Result.failure(e)
        }
    }

    override fun getCachedModules(): List<VenueModule> {
        val currentModules = _modules.value.ifEmpty {
            // Try loading from persistent cache if in-memory is empty
            Timber.d("📦 getCachedModules: in-memory cache empty, loading from persistent")
            loadFromPersistentCache()
            _modules.value
        }
        Timber.d("📦 getCachedModules: returning ${currentModules.size} modules: ${currentModules.map { it.moduleCode }}")
        return currentModules
    }

    override fun isModuleEnabled(moduleCode: String): Boolean {
        val enabled = getCachedModules().any { it.moduleCode == moduleCode && it.active }
        Timber.d("📦 isModuleEnabled($moduleCode) = $enabled")
        return enabled
    }

    override fun getModuleConfig(moduleCode: String): ModuleConfig? {
        val module = getModule(moduleCode)
        Timber.d("📦 getModuleConfig($moduleCode): module=${module?.moduleCode}, found=${module != null}")
        if (module != null) {
            Timber.d("📦   - attendance.requireClockInPhoto=${module.config.attendance.requireClockInPhoto}")
            Timber.d("📦   - attendance.requireClockInGps=${module.config.attendance.requireClockInGps}")
        }
        return module?.config
    }

    override fun getModule(moduleCode: String): VenueModule? {
        return getCachedModules().find { it.moduleCode == moduleCode }
    }

    override fun clearCache() {
        _modules.value = emptyList()
        secureStorage.clearCachedModules()
        Timber.d("📦 Module cache cleared")
    }

    private fun parseErrorMessage(errorBody: String?, statusCode: Int): String {
        return try {
            if (errorBody.isNullOrBlank()) {
                "Error $statusCode: Unknown error"
            } else {
                // Try to extract message from JSON error response
                val regex = """"(?:message|error)"\s*:\s*"([^"]+)"""".toRegex()
                val match = regex.find(errorBody)
                match?.groupValues?.get(1) ?: "Error $statusCode: $errorBody"
            }
        } catch (e: Exception) {
            "Error $statusCode: ${errorBody ?: "Unknown error"}"
        }
    }
}
