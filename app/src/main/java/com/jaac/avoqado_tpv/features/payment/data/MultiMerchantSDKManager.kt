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
                TerminalConfig.serialNumber = targetAccount.serialNumber
                TerminalConfig.brand = com.jaac.avoqado_tpv.BuildConfig.TERMINAL_BRAND
                TerminalConfig.model = com.jaac.avoqado_tpv.BuildConfig.TERMINAL_MODEL
                Timber.i("   ✅ TerminalConfig updated: $previousSerial → ${targetAccount.serialNumber}")

                // Step 4: Force SDK re-initialization with new credentials
                Timber.d("   [Step 2/2] Re-initializing Blumon SDK...")
                val initResult = initializationManager.forceReinitialize()

                if (initResult.isFailure) {
                    // Rollback TerminalConfig on failure
                    Timber.e("   ❌ SDK re-initialization failed - rolling back TerminalConfig")
                    TerminalConfig.serialNumber = previousSerial

                    val error = initResult.exceptionOrNull()
                    Timber.e(error, "   SDK init error details")

                    return@withLock Result.failure(
                        Exception(
                            "No se pudo cambiar a la cuenta '${targetAccount.displayName}'.\n\n" +
                            "Error: ${error?.message ?: "Error desconocido"}\n\n" +
                            "Intente nuevamente o contacte soporte."
                        )
                    )
                }

                // Step 5: Update current merchant tracking
                currentMerchant = targetAccount
                Timber.i("✅ [MultiMerchantSDKManager] Successfully switched to: ${targetAccount.displayName}")
                Timber.i("   Serial: ${targetAccount.serialNumber}")
                Timber.i("   Environment: ${targetAccount.environment}")

                Result.success(Unit)

            } catch (e: Exception) {
                Timber.e(e, "❌ [MultiMerchantSDKManager] Unexpected error during merchant switch")
                Result.failure(
                    Exception(
                        "Error inesperado al cambiar de cuenta.\n\n" +
                        "Por favor intente nuevamente.\n\n" +
                        "Error técnico: ${e.message}"
                    )
                )
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
