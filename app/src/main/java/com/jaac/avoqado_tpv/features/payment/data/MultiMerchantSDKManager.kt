package com.jaac.avoqado_tpv.features.payment.data

import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-Merchant SDK Manager - Orchestrates payment routing to different merchant accounts
 *
 * **Business Problem:**
 * A single physical PAX terminal needs to process payments for multiple merchant accounts.
 * Example: Restaurant with main venue + ghost kitchen, each needing separate accounting.
 *
 * **Technical Challenge:**
 * Blumon SDK ties credentials (OAuth token, DUKPT keys) to terminal serial number.
 * Switching merchants requires:
 * 1. Changing the serial number (device identity)
 * 2. Re-initializing SDK with new credentials (3-5 seconds)
 * 3. Ensuring no credential conflicts
 *
 * **Solution:**
 * This manager provides atomic merchant switching:
 * - Thread-safe (Mutex prevents concurrent switches)
 * - Updates TerminalConfig (runtime serial number)
 * - Triggers full SDK re-initialization
 * - Returns success/failure with user-friendly messages
 *
 * **Flow:**
 * ```
 * User selects "Account B" in payment screen
 *   ↓
 * MultiMerchantSDKManager.switchMerchant(accountB)
 *   ↓
 * 1. Check if already on Account B (no-op if true)
 * 2. Update TerminalConfig.serialNumber = "2841548418"
 * 3. Call InitializationManager.forceReinitialize()
 *    - Fetches OAuth token for serial 2841548418
 *    - Downloads DUKPT keys for serial 2841548418
 *    - Overwrites SDK database with new InitData
 *   ↓
 * 4. Return success → Payment can proceed
 * ```
 *
 * **Usage:**
 * ```kotlin
 * // In PaymentViewModel
 * val result = multiMerchantSDKManager.switchMerchant(selectedAccount)
 * if (result.isSuccess) {
 *     // Proceed with payment on new merchant account
 *     processSaleTransaction()
 * } else {
 *     // Show error to user
 *     _state.value = PaymentState.Error(result.exceptionOrNull()?.message)
 * }
 * ```
 *
 * **Performance:**
 * - Same merchant: ~0ms (no-op)
 * - Different merchant: ~3-5 seconds (OAuth + key download + re-init)
 *
 * **Thread Safety:**
 * Mutex ensures only one merchant switch can happen at a time.
 * Concurrent requests will queue and wait.
 *
 * @param initializationManager Handles SDK re-initialization
 */
@Singleton
class MultiMerchantSDKManager @Inject constructor(
    private val initializationManager: InitializationManager
) {
    // Track currently active merchant account
    @Volatile
    private var currentMerchant: MerchantAccount? = null

    // Mutex for thread-safe merchant switching
    private val switchMutex = Mutex()

    /**
     * Get currently active merchant account
     *
     * Returns null if no merchant has been explicitly set yet.
     * In that case, the terminal is using the default BuildConfig serial.
     */
    fun getCurrentMerchant(): MerchantAccount? = currentMerchant

    /**
     * Check if a merchant account is currently active
     *
     * @param account Merchant account to check
     * @return true if this account is currently active
     */
    fun isMerchantActive(account: MerchantAccount): Boolean {
        return account.serialNumber == TerminalConfig.serialNumber
    }

    /**
     * Switch to a different merchant account (atomic, thread-safe)
     *
     * **Behavior:**
     * - If already on target merchant → No-op, returns success immediately
     * - If different merchant → Performs full switch (3-5 seconds)
     *
     * **Steps:**
     * 1. Acquire mutex lock (thread-safe)
     * 2. Check if already on target merchant
     * 3. Update TerminalConfig.serialNumber
     * 4. Call InitializationManager.forceReinitialize()
     *    - Fetches OAuth token for new serial
     *    - Downloads RSA/DUKPT keys for new serial
     *    - Overwrites SDK database
     * 5. Update currentMerchant tracking
     * 6. Release mutex lock
     *
     * **Error Handling:**
     * - OAuth failure: Returns failure with message "No se pudo autenticar con la cuenta seleccionada"
     * - Network timeout: Returns failure with message "Timeout al cambiar de cuenta"
     * - Generic error: Returns failure with exception message
     *
     * @param targetAccount Merchant account to switch to
     * @return Result.success if switch succeeded, Result.failure if error occurred
     */
    suspend fun switchMerchant(targetAccount: MerchantAccount): Result<Unit> {
        return switchMutex.withLock {
            try {
                Timber.i("🔄 [MultiMerchantSDKManager] Switching to merchant: ${targetAccount.displayName}")
                Timber.d("   Current serial: ${TerminalConfig.serialNumber}")
                Timber.d("   Target serial: ${targetAccount.serialNumber}")

                // Step 1: Check if already on target merchant (optimization)
                if (isMerchantActive(targetAccount)) {
                    Timber.d("   ✅ Already on target merchant - skipping switch")
                    currentMerchant = targetAccount
                    return@withLock Result.success(Unit)
                }

                // Step 2: Validate account is active
                if (!targetAccount.isActive) {
                    Timber.e("   ❌ Target merchant is inactive: ${targetAccount.displayName}")
                    return@withLock Result.failure(
                        IllegalStateException("La cuenta '${targetAccount.displayName}' está inactiva")
                    )
                }

                // Step 3: Update TerminalConfig (runtime serial number)
                val previousSerial = TerminalConfig.serialNumber
                Timber.d("   [Step 1/2] Updating TerminalConfig...")
                TerminalConfig.updateSerial(targetAccount.serialNumber)
                Timber.i("   ✅ TerminalConfig updated: $previousSerial → ${targetAccount.serialNumber}")

                // Step 4: Force SDK re-initialization with new credentials
                // CRITICAL: Pass posId from MerchantAccount to fix multi-merchant switching bug
                // GetInitDataUseCase returns stale cache after multiple switches, causing wrong posId
                // This follows the same pattern as PaymentViewModel.performOnlineAuthorization()
                Timber.d("   [Step 2/2] Re-initializing Blumon SDK with posId: ${targetAccount.posId}...")
                val initResult = initializationManager.forceReinitialize(merchantPosId = targetAccount.posId)

                if (initResult.isFailure) {
                    // Rollback TerminalConfig on failure
                    Timber.e("   ❌ SDK re-initialization failed - rolling back TerminalConfig")
                    TerminalConfig.updateSerial(previousSerial)

                    val error = initResult.exceptionOrNull()
                    Timber.e(error, "   ❌ [TECHNICAL] SDK init error details")  // Log technical details

                    // Translate technical error to user-friendly message
                    val userMessage = when {
                        error?.message?.contains("timeout", ignoreCase = true) == true ||
                        error?.message?.contains("timed out", ignoreCase = true) == true -> {
                            "El cambio de cuenta tardó demasiado.\n\n" +
                            "Posibles causas:\n" +
                            "• Conexión a internet lenta\n" +
                            "• Servidor Blumon no disponible\n\n" +
                            "Por favor, verifique su conexión e intente nuevamente."
                        }
                        error?.message?.contains("network", ignoreCase = true) == true ||
                        error?.message?.contains("no connection", ignoreCase = true) == true ||
                        error?.message?.contains("unreachable", ignoreCase = true) == true -> {
                            "No se pudo conectar al servidor.\n\n" +
                            "Posibles causas:\n" +
                            "• Sin conexión a internet\n" +
                            "• Servidor Blumon no disponible\n\n" +
                            "Verifique su conexión a internet e intente nuevamente."
                        }
                        error?.message?.contains("unauthorized", ignoreCase = true) == true ||
                        error?.message?.contains("authentication", ignoreCase = true) == true ||
                        error?.message?.contains("oauth", ignoreCase = true) == true -> {
                            "No se pudo autenticar con la cuenta '${targetAccount.displayName}'.\n\n" +
                            "Posibles causas:\n" +
                            "• Cuenta inactiva en servidor Blumon\n" +
                            "• Credenciales incorrectas\n\n" +
                            "Contacte a soporte técnico para verificar la cuenta."
                        }
                        else -> {
                            "No se pudo cambiar a la cuenta '${targetAccount.displayName}'.\n\n" +
                            "Posibles causas:\n" +
                            "• Sin conexión a internet\n" +
                            "• Cuenta inactiva\n" +
                            "• Problema con servidor Blumon\n\n" +
                            "Intente nuevamente o contacte soporte."
                        }
                    }

                    return@withLock Result.failure(Exception(userMessage))
                }

                // Step 5: Update current merchant tracking
                currentMerchant = targetAccount
                Timber.i("✅ [MultiMerchantSDKManager] Successfully switched to: ${targetAccount.displayName}")
                Timber.i("   Serial: ${targetAccount.serialNumber}")
                Timber.i("   Environment: ${targetAccount.environment}")

                Result.success(Unit)

            } catch (e: Exception) {
                Timber.e(e, "❌ [TECHNICAL] Unexpected error during merchant switch")  // Log technical details

                // Translate exception to user-friendly message
                val userMessage = when {
                    e is IllegalStateException -> {
                        // Already handled above for inactive accounts
                        e.message ?: "La cuenta seleccionada no está disponible."
                    }
                    e.message?.contains("mutex", ignoreCase = true) == true ||
                    e.message?.contains("lock", ignoreCase = true) == true -> {
                        "Ya hay un cambio de cuenta en progreso.\n\n" +
                        "Por favor espere a que termine la operación actual."
                    }
                    else -> {
                        "Error inesperado al cambiar de cuenta.\n\n" +
                        "Posibles soluciones:\n" +
                        "• Cierre y vuelva a abrir la app\n" +
                        "• Verifique su conexión a internet\n" +
                        "• Contacte a soporte si el problema persiste"
                    }
                }

                Result.failure(Exception(userMessage))
            }
        }
    }

    /**
     * Reset to default merchant (from BuildConfig)
     *
     * Useful for:
     * - Testing (return to known state)
     * - Error recovery (rollback to default)
     * - App logout (clear merchant selection)
     *
     * @return Result.success if reset succeeded
     */
    suspend fun resetToDefault(): Result<Unit> {
        Timber.i("🔄 [MultiMerchantSDKManager] Resetting to default merchant...")
        TerminalConfig.reset()
        currentMerchant = null

        val initResult = initializationManager.forceReinitialize()
        return if (initResult.isSuccess) {
            Timber.i("✅ Reset to default merchant (serial: ${TerminalConfig.serialNumber})")
            Result.success(Unit)
        } else {
            Timber.e("❌ Failed to reset to default merchant")
            Result.failure(initResult.exceptionOrNull() ?: Exception("Reset failed"))
        }
    }

    /**
     * Get formatted status message for logging/debugging
     */
    fun getStatusMessage(): String {
        val current = currentMerchant
        return if (current != null) {
            "Active Merchant: ${current.displayName} (${current.serialNumber})"
        } else {
            "Default Merchant (${TerminalConfig.serialNumber})"
        }
    }
}
