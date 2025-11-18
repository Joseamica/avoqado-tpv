package com.jaac.avoqado_tpv.core.data.network.dto

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment

/**
 * Mapper extensions for TerminalConfigDto → Domain Models
 *
 * **Purpose:**
 * Convert backend API responses to domain models used by ViewModels and UseCases.
 * Follows Clean Architecture: data layer maps DTOs → domain models.
 */

/**
 * Convert MerchantAccountDto to MerchantAccount domain model
 *
 * **Field Mapping:**
 * - DTO.id → merchantAccountId (backend CUID - PRIMARY for API calls)
 * - DTO.id → id (kept for backwards compatibility)
 * - DTO.serialNumber → serialNumber (Blumon device serial)
 * - DTO.posId → posId (Momentum API position ID - CRITICAL)
 * - DTO.displayName → displayName (user-friendly name)
 * - DTO.environment → environment (SANDBOX/PRODUCTION enum)
 * - isActive = true (backend only returns active merchants)
 *
 * **Environment Parsing:**
 * - "SANDBOX" → MerchantEnvironment.SANDBOX
 * - "PRODUCTION" → MerchantEnvironment.PRODUCTION
 * - Any other value → defaults to SANDBOX (safe fallback)
 *
 * @return MerchantAccount domain model
 */
fun MerchantAccountDto.toDomain(): MerchantAccount {
    val env = when (environment.uppercase()) {
        "PRODUCTION" -> MerchantEnvironment.PRODUCTION
        else -> MerchantEnvironment.SANDBOX  // Default to sandbox for safety
    }

    return MerchantAccount(
        id = id,  // Keep for backwards compatibility
        merchantAccountId = id,  // ✅ FIX: Backend CUID for API payment recording
        serialNumber = serialNumber,
        posId = posId,
        displayName = displayName,
        description = null,  // Backend doesn't return description in this endpoint
        environment = env,
        isActive = true  // Backend only returns active merchants in config endpoint
    )
}

/**
 * Convert list of MerchantAccountDtos to domain models
 *
 * Convenience extension for mapping lists in repositories.
 *
 * @return List of MerchantAccount domain models
 */
fun List<MerchantAccountDto>.toDomain(): List<MerchantAccount> {
    return this.map { it.toDomain() }
}
