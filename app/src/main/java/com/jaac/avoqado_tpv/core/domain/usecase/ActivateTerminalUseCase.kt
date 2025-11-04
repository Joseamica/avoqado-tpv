package com.jaac.avoqado_tpv.core.domain.usecase

import com.jaac.avoqado_tpv.core.domain.models.ActivationResult
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.repository.ActivationRepository
import javax.inject.Inject

/**
 * Activate Terminal Use Case
 *
 * Business logic for terminal activation.
 * Encapsulates single responsibility: activating a terminal with a code.
 *
 * **Clean Architecture:**
 * - ViewModel calls UseCase
 * - UseCase calls Repository
 * - Repository calls API
 *
 * **Benefits:**
 * - Testable (mock repository)
 * - Reusable across screens
 * - Single Responsibility Principle
 *
 * **Usage:**
 * ```kotlin
 * class ActivationViewModel @Inject constructor(
 *     private val activateTerminalUseCase: ActivateTerminalUseCase,
 *     private val deviceInfoManager: DeviceInfoManager
 * ) : ViewModel() {
 *     fun activate(code: String) {
 *         viewModelScope.launch {
 *             val serialNumber = deviceInfoManager.getSerialNumber()
 *             when (val result = activateTerminalUseCase(serialNumber, code)) {
 *                 is Result.Success -> handleSuccess(result.data)
 *                 is Result.Error -> handleError(result.exception)
 *             }
 *         }
 *     }
 * }
 * ```
 */
class ActivateTerminalUseCase @Inject constructor(
    private val activationRepository: ActivationRepository
) {

    /**
     * Execute activation
     *
     * @param serialNumber Device serial number (AVQD-{androidId})
     * @param activationCode 6-character alphanumeric code
     * @return Result with ActivationResult or error
     */
    suspend operator fun invoke(
        serialNumber: String,
        activationCode: String
    ): Result<ActivationResult> {
        // Input validation
        require(serialNumber.isNotBlank()) {
            "Serial number cannot be blank"
        }

        require(activationCode.length == 6) {
            "Activation code must be exactly 6 characters"
        }

        require(activationCode.matches(Regex("^[A-Z0-9]{6}$", RegexOption.IGNORE_CASE))) {
            "Activation code must contain only letters and numbers"
        }

        // Delegate to repository
        return activationRepository.activateTerminal(
            serialNumber = serialNumber,
            activationCode = activationCode.uppercase() // Normalize to uppercase
        )
    }
}
