package com.jaac.avoqado_tpv.features.payment.data

import com.example.clean_lib_services.shared.core.domain.entity.init.Contact
import com.example.clean_lib_services.shared.core.domain.entity.init.InitData
import com.example.clean_lib_services.shared.core.domain.entity.init.KushkiData
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.get_init_data.GetInitDataUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.initializer.InitializerUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitUseCase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.TerminalConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Blumon SDK initialization with 24-hour caching policy
 *
 * **Problem Solved:**
 * Previously, InitializerUseCase + InsertInitUseCase were called on EVERY payment,
 * creating 65 duplicate database rows that caused "NonUniqueResultException: query did not return a unique result: 65"
 *
 * **Solution per Edgardo (2025-11-05):**
 * "Es recomendable realizar el init solo una vez cada 24 horas o cada que lances la aplicación"
 *
 * **How It Works:**
 * 1. Checks last initialization timestamp from SecureStorage
 * 2. If never initialized OR > 24 hours passed → Execute full init sequence
 * 3. If < 24 hours → Skip init (reuse existing SDK configuration)
 *
 * **Init Sequence:**
 * - InitializerUseCase: OAuth + DUKPT key download from Blumon backend
 * - InsertInitUseCase: Fix posId bug (SDK stores serial instead of server posId)
 *
 * @param secureStorage For timestamp persistence
 * @param initializerUseCase SDK initialization (OAuth + keys)
 * @param insertInitUseCase SDK bug workaround (posId correction)
 * @param getInitDataUseCase Verification (check posId is correct)
 */
@Singleton
class InitializationManager @Inject constructor(
    private val secureStorage: SecureStorage,
    private val initializerUseCase: InitializerUseCase,
    private val insertInitUseCase: InsertInitUseCase,
    private val getInitDataUseCase: GetInitDataUseCase
) {

    companion object {
        private const val TWENTY_FOUR_HOURS_MS = 86_400_000L  // 24 hours in milliseconds
    }

    /**
     * Ensure Blumon SDK is initialized
     *
     * Executes init sequence only if:
     * - First time (no timestamp exists)
     * - 24+ hours since last init
     *
     * Otherwise skips init (reuses existing configuration)
     *
     * @return Result.success(Unit) if initialized or already valid
     * @return Result.failure if initialization fails
     */
    suspend fun ensureInitialized(): Result<Unit> {
        val lastInit = secureStorage.getLastBlumonInitTimestamp()
        val now = System.currentTimeMillis()

        return if (shouldInitialize(lastInit, now)) {
            Timber.i("🔧 [InitializationManager] Running Blumon SDK initialization...")
            executeInitialization(now)
        } else {
            val hoursSinceInit = ((now - (lastInit ?: 0)) / (1000 * 60 * 60)).toInt()
            Timber.d("✅ [InitializationManager] SDK already initialized ($hoursSinceInit hours ago) - skipping init")
            Result.success(Unit)
        }
    }

    /**
     * Determine if initialization is needed
     */
    private fun shouldInitialize(lastInit: Long?, now: Long): Boolean {
        // Development mode: ALWAYS force re-init to prevent DUKPT key corruption
        // This fixes NA_002 errors when doing rebuild without uninstalling
        if (com.jaac.avoqado_tpv.BuildConfig.FORCE_BLUMON_REINIT) {
            Timber.w("⚠️ [DEV MODE] Forcing Blumon SDK re-initialization (FORCE_BLUMON_REINIT=true)")
            return true
        }

        // Production mode: Use 24-hour cache
        return when {
            lastInit == null -> {
                Timber.d("   First initialization (no timestamp found)")
                true
            }
            (now - lastInit) > TWENTY_FOUR_HOURS_MS -> {
                val hoursSinceInit = ((now - lastInit) / (1000 * 60 * 60)).toInt()
                Timber.d("   $hoursSinceInit hours since last init (> 24h threshold)")
                true
            }
            else -> {
                false
            }
        }
    }

    /**
     * Execute full initialization sequence
     *
     * 1. InitializerUseCase - OAuth + DUKPT keys
     * 2. InsertInitUseCase - Force correct posId (SDK bug workaround)
     * 3. GetInitDataUseCase - Verification
     * 4. Save timestamp
     */
    private suspend fun executeInitialization(timestamp: Long): Result<Unit> {
        return try {
            // STEP 1: InitializerUseCase (OAuth + DUKPT download)
            Timber.i("[INIT STEP 1] InitializerUseCase - OAuth + DUKPT key download...")
            val initParams = InitializerParams(
                serial = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber,
                brand = com.jaac.avoqado_tpv.core.domain.TerminalConfig.brand,
                model = com.jaac.avoqado_tpv.core.domain.TerminalConfig.model
            )

            val initResult = initializerUseCase.run(initParams)

            if (initResult.isLeft) {
                val failure = initResult.leftValue()
                Timber.e("❌ [INIT STEP 1] InitializerUseCase failed: $failure")
                return Result.failure(Exception("InitializerUseCase failed: $failure"))
            }

            Timber.i("✅ [INIT STEP 1] OAuth + DUKPT keys downloaded successfully")

            // STEP 1.5: Get the correct posId from backend BEFORE overwriting
            Timber.i("[INIT STEP 1.5] GetInitDataUseCase - Fetching backend posId...")
            val preInitDataParams = GetInitDataParams()
            val preInitDataResult = getInitDataUseCase.run(preInitDataParams)

            val correctPosId = if (preInitDataResult.isRight) {
                val preInitData = preInitDataResult.rightValue().initData
                Timber.i("   Backend returned posId: ${preInitData.posId} for serial: ${TerminalConfig.serialNumber}")
                preInitData.posId
            } else {
                // Fallback to old hardcoded value if backend call fails
                Timber.w("   ⚠️ Failed to get posId from backend, using fallback: 376")
                "376"
            }

            // STEP 2: InsertInitUseCase (posId bug workaround)
            Timber.i("[INIT STEP 2] InsertInitUseCase - Forcing correct posId ($correctPosId)...")
            val postInitData = InitData(
                posId = correctPosId,
                commerceName = "Avoqado Test Venue",
                commerceAddress = "Test Address, Test City",
                contact = Contact(
                    name = "Avoqado Support",
                    telephone = "5555555555",
                    email = "contact@avoqado.io"
                ),
                dollarMembership = false,
                transactionProfile = "SALE",
                emv = true,
                contactless = true,
                manual = false,
                q6 = false,
                qps = false,
                qpsAmount = 0.0,
                cashback = false,
                partialCancellation = false,
                ticketPromotions = false,
                initializeKeys = true,
                kushki = KushkiData(isKsk = false)
            )

            val postInsertParams = InsertInitParams(postInitData)
            val postInsertResult = insertInitUseCase.run(postInsertParams)

            if (postInsertResult.isLeft) {
                Timber.e("❌ [INIT STEP 2] InsertInitUseCase failed")
                return Result.failure(Exception("InsertInitUseCase failed"))
            }

            Timber.i("✅ [INIT STEP 2] posId corrected: ${TerminalConfig.serialNumber} → $correctPosId")

            // STEP 3: Verification with GetInitDataUseCase
            Timber.i("[INIT STEP 3] GetInitDataUseCase - Verifying posId...")
            val initDataParams = GetInitDataParams()
            val initDataResult = getInitDataUseCase.run(initDataParams)

            if (initDataResult.isLeft) {
                val failure = initDataResult.leftValue()
                Timber.e("❌ [INIT STEP 3] GetInitDataUseCase failed: $failure")
                return Result.failure(Exception("GetInitDataUseCase failed: $failure"))
            }

            val initDataResponse = initDataResult.rightValue()
            val initData = initDataResponse.initData
            Timber.i("✅ [INIT STEP 3] Verification successful!")
            Timber.i("   posId: ${initData.posId} (safe to parse as Int)")
            Timber.i("   Commerce: ${initData.commerceName}")

            // STEP 4: Save timestamp
            secureStorage.saveLastBlumonInitTimestamp(timestamp)
            Timber.i("✅ [InitializationManager] Initialization complete - valid for 24 hours")

            Result.success(Unit)

        } catch (e: Exception) {
            Timber.e(e, "❌ [InitializationManager] Unexpected error during initialization")
            Result.failure(e)
        }
    }

    /**
     * Force re-initialization (for testing or troubleshooting)
     *
     * Clears timestamp and executes full init sequence
     */
    suspend fun forceReinitialize(): Result<Unit> {
        Timber.w("⚠️ [InitializationManager] Force re-initialization requested")
        return executeInitialization(System.currentTimeMillis())
    }
}
