package com.jaac.avoqado_tpv.core.domain.repository

import com.jaac.avoqado_tpv.core.domain.models.ActivationResult
import com.jaac.avoqado_tpv.core.domain.models.Result

/**
 * Activation Repository Interface
 *
 * Domain layer contract for terminal activation.
 * Implemented in data layer with API integration.
 *
 * **Clean Architecture:**
 * - Domain layer defines WHAT (interface)
 * - Data layer defines HOW (implementation)
 * - No Android dependencies in this interface
 *
 * **Similar to:**
 * - Square POS device activation pattern
 * - Toast POS terminal registration
 */
interface ActivationRepository {

    /**
     * Activate terminal with activation code
     *
     * Flow:
     * 1. Sends serialNumber + activationCode to backend
     * 2. Backend validates code (checks expiration, attempts)
     * 3. Returns venueId and venue info
     * 4. Implementation stores venueId in SecureStorage
     *
     * @param serialNumber Device serial number (format: AVQD-{androidId})
     * @param activationCode 6-character alphanumeric code (case-insensitive)
     * @return Result with ActivationResult on success, ApiException on failure
     *
     * **Error Handling:**
     * - Invalid code → 401 with attempts remaining
     * - Code expired → 400 error
     * - Terminal locked → 401 error
     * - Already activated → 400 error
     * - Network error → NetworkError exception
     */
    suspend fun activateTerminal(
        serialNumber: String,
        activationCode: String
    ): Result<ActivationResult>
}
