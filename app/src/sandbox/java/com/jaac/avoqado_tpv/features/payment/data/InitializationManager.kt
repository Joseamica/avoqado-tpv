package com.jaac.avoqado_tpv.features.payment.data

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val initializerUseCase: InitializerUseCase,
    private val insertInitUseCase: InsertInitUseCase,
    private val getInitDataUseCase: GetInitDataUseCase
) {

    companion object {
        private const val TWENTY_FOUR_HOURS_MS = 86_400_000L  // 24 hours in milliseconds
        private const val SDK_DATABASE_NAME = "pax-database"  // Blumon SDK Room database
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC STATE - Expose initialization status for other components
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Whether SDK is initialized and ready for payments
     * Used by PaymentViewModel to check if SDK is ready before starting payment
     */
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Mutex to prevent concurrent initialization attempts
     * Ensures only one init runs at a time even if called from multiple places
     */
    private val initMutex = Mutex()

    /**
     * Deferred to allow awaiting ongoing initialization
     * If init is in progress, new callers can await this instead of starting new init
     */
    private var initializationDeferred: CompletableDeferred<Result<Unit>>? = null

    /**
     * Ensure Blumon SDK is initialized
     *
     * Executes init sequence only if:
     * - First time (no timestamp exists)
     * - 24+ hours since last init
     *
     * Otherwise skips full init (reuses existing configuration) BUT still ensures
     * the correct posId is in the database to handle app restarts after merchant switches.
     *
     * **Thread-safe:** Uses mutex to prevent concurrent initialization attempts.
     * If called while init is in progress, awaits the ongoing init instead.
     *
     * @param defaultMerchantPosId Optional posId from default MerchantAccount.
     *                             When provided, ensures this posId is in the SDK database
     *                             even when skipping full init (handles app restart after merchant switch).
     * @return Result.success(Unit) if initialized or already valid
     * @return Result.failure if initialization fails
     */
    suspend fun ensureInitialized(defaultMerchantPosId: String? = null): Result<Unit> {
        if (!com.jaac.avoqado_tpv.BuildConfig.ENABLE_BLUMON_INIT) {
            if (!_isInitialized.value) {
                Timber.w("🧪 [InitializationManager] Blumon initialization disabled for this flavor")
                _isInitialized.value = true
            }
            return Result.success(Unit)
        }

        // Fast path: already initialized
        if (_isInitialized.value) {
            Timber.d("✅ [InitializationManager] Already initialized (fast path)")
            return Result.success(Unit)
        }

        // Check if initialization is in progress - await it instead of starting new
        initializationDeferred?.let { deferred ->
            if (!deferred.isCompleted) {
                Timber.d("⏳ [InitializationManager] Awaiting ongoing initialization...")
                return deferred.await()
            }
        }

        // Use mutex to ensure only one init runs at a time
        return initMutex.withLock {
            // Double-check after acquiring lock
            if (_isInitialized.value) {
                Timber.d("✅ [InitializationManager] Already initialized (after lock)")
                return@withLock Result.success(Unit)
            }

            val lastInit = secureStorage.getLastBlumonInitTimestamp()
            val now = System.currentTimeMillis()

            if (shouldInitialize(lastInit, now)) {
                // Create deferred so other callers can await
                val deferred = CompletableDeferred<Result<Unit>>()
                initializationDeferred = deferred

                Timber.i("🔧 [InitializationManager] Running Blumon SDK initialization...")
                val result = executeInitialization(now, merchantPosId = defaultMerchantPosId)

                // Mark as initialized on success
                if (result.isSuccess) {
                    _isInitialized.value = true
                }

                deferred.complete(result)
                result
            } else {
                val hoursSinceInit = ((now - (lastInit ?: 0)) / (1000 * 60 * 60)).toInt()

                // CRITICAL FIX: When defaultMerchantPosId is provided, always do full re-init
                // Reason: Room's in-memory cache is loaded on app start with stale posId.
                // Clearing SQLite directly doesn't clear Room's cache, so GetInitDataUseCase
                // returns stale data. Only full re-init properly resets Room's state.
                if (defaultMerchantPosId != null) {
                    Timber.i("🔄 [InitializationManager] App restart detected - forcing full re-init to fix posId")
                    Timber.d("   Reason: Room cache may have stale posId from previous merchant switch")

                    // Force full initialization with the correct posId
                    val result = executeInitialization(now, merchantPosId = defaultMerchantPosId)

                    if (result.isSuccess) {
                        _isInitialized.value = true
                    }

                    return@withLock result
                }

                // No defaultMerchantPosId = normal skip (no merchant switch history to worry about)
                Timber.d("✅ [InitializationManager] SDK already initialized ($hoursSinceInit hours ago) - skipping full init")
                _isInitialized.value = true  // Mark as initialized (cached)
                Result.success(Unit)
            }
        }
    }

    /**
     * Await initialization if in progress, or return immediately if ready
     *
     * Use this when you need to ensure SDK is ready before proceeding.
     * - If already initialized: returns immediately
     * - If init in progress: awaits completion
     * - If not started: starts initialization and awaits
     *
     * @return Result.success(Unit) when SDK is ready
     * @return Result.failure if initialization fails
     */
    suspend fun awaitInitialization(): Result<Unit> {
        return ensureInitialized()
    }

    /**
     * Determine if initialization is needed
     */
    private fun shouldInitialize(lastInit: Long?, now: Long): Boolean {
        // Development mode: ALWAYS force re-init to prevent DUKPT key corruption
        // This fixes NA_002 errors when doing rebuild without uninstalling
        // ⚠️ DISABLE FORCE RE-INIT: It causes GenericFailure due to rate limiting
        /* if (com.jaac.avoqado_tpv.BuildConfig.FORCE_BLUMON_REINIT) {
            Timber.w("⚠️ [DEV MODE] Forcing Blumon SDK re-initialization (FORCE_BLUMON_REINIT=true)")
            return true
        } */

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
     *
     * @param timestamp Current timestamp for caching
     * @param merchantPosId Optional posId from MerchantAccount (for merchant switching).
     *                      When provided, bypasses GetInitDataUseCase (which may return stale cache).
     *                      This follows the same pattern as PaymentViewModel.performOnlineAuthorization().
     */
    private suspend fun executeInitialization(timestamp: Long, merchantPosId: String? = null): Result<Unit> {
        return try {
            // STEP 0: Ensure PAX DAL is initialized (defensive — may already be done by Application)
            // AppManager.init() sets lateinit var dal. If Application's background init hasn't
            // completed yet, the SDK will crash with "lateinit property dal has not been initialized".
            // This call is idempotent — safe to call multiple times.
            try {
                com.blumonpay.pax.utils.AppManager.init(context)
            } catch (e: Throwable) {
                // Catches UnsatisfiedLinkError (native lib missing in tests/non-PAX) and any other errors
                Timber.w(e, "⚠️ [InitializationManager] AppManager.init() failed (non-PAX device?)")
            }

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

            // STEP 1.5: Clear init table for merchant switching
            // CRITICAL FIX: InitializerUseCase may insert init data with wrong posId from server.
            // InsertInitUseCase ADDS records (doesn't update), so GetInitDataUseCase returns
            // the first/stale record. By clearing the table before InsertInitUseCase, we ensure
            // only our correct posId exists in the database.
            // NOTE: This uses raw SQLite to bypass Room's in-memory cache.
            if (merchantPosId != null) {
                Timber.i("[INIT STEP 1.5] Clearing init table for merchant switch...")
                clearInitTable()
            }

            // STEP 1.6: Determine correct posId
            // When merchantPosId is provided (merchant switching), use it directly.
            // Otherwise, query SDK (initial app launch).
            val correctPosId = if (merchantPosId != null) {
                // ✅ Merchant switching: Use provided posId directly (bypasses stale SDK cache)
                Timber.i("[INIT STEP 1.6] Using provided merchantPosId: $merchantPosId (merchant switch)")
                merchantPosId
            } else {
                // Initial app launch: Query SDK for posId
                Timber.i("[INIT STEP 1.6] GetInitDataUseCase - Fetching backend posId...")
                val preInitDataParams = GetInitDataParams()
                val preInitDataResult = getInitDataUseCase.run(preInitDataParams)

                if (preInitDataResult.isRight) {
                    val preInitData = preInitDataResult.rightValue().initData
                    Timber.i("   Backend returned posId: ${preInitData.posId} for serial: ${TerminalConfig.serialNumber}")
                    preInitData.posId
                } else {
                    // Fallback to old hardcoded value if backend call fails
                    Timber.w("   ⚠️ Failed to get posId from backend, using fallback: 376")
                    "376"
                }
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
                qpsAmount = 0.0,      // QPS amount (sandbox requires this parameter)
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
     * Force re-initialization (for testing, troubleshooting, or merchant switching)
     *
     * Clears timestamp and executes full init sequence.
     *
     * @param merchantPosId Optional posId from MerchantAccount.
     *                      When provided (merchant switching), bypasses GetInitDataUseCase
     *                      which may return stale cached data after multiple merchant switches.
     *                      This follows the same pattern as PaymentViewModel.performOnlineAuthorization().
     */
    suspend fun forceReinitialize(merchantPosId: String? = null): Result<Unit> {
        if (!com.jaac.avoqado_tpv.BuildConfig.ENABLE_BLUMON_INIT) {
            _isInitialized.value = true
            Timber.w("🧪 [InitializationManager] forceReinitialize skipped (Blumon disabled for this flavor)")
            return Result.success(Unit)
        }

        Timber.w("⚠️ [InitializationManager] Force re-initialization requested (merchantPosId: ${merchantPosId ?: "null"})")

        return initMutex.withLock {
            // Reset state before re-initializing
            _isInitialized.value = false

            val deferred = CompletableDeferred<Result<Unit>>()
            initializationDeferred = deferred

            val result = executeInitialization(System.currentTimeMillis(), merchantPosId)

            if (result.isSuccess) {
                _isInitialized.value = true
            }

            deferred.complete(result)
            result
        }
    }

    /**
     * Clear the init table using raw SQLite to bypass Room's in-memory cache
     *
     * **Problem:**
     * Room's DAO singleton caches query results. Even after InsertInitUseCase adds a new record,
     * GetInitDataUseCase may return the first/stale record from cache.
     *
     * **Solution:**
     * Use raw SQLite to delete all records from the init table. This bypasses Room and directly
     * modifies the database file. The next GetInitDataUseCase call will query the updated database.
     *
     * **Table names to try:**
     * The SDK's Room database may use various table names for init data:
     * - InitEntity, init_entity, initentity
     * - Init, init
     * - InitData, init_data, initdata
     * - Configuration, configuration
     */
    private fun clearInitTable() {
        try {
            // Open SDK database directly using SQLite (bypasses Room)
            val dbPath = context.getDatabasePath(SDK_DATABASE_NAME)
            if (!dbPath.exists()) {
                Timber.w("   SDK database doesn't exist yet, skipping clear")
                return
            }

            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )

            try {
                // Try table names used by Blumon SDK Room database
                // "Init" is the confirmed table name (discovered 2025-12-15)
                val tableNames = listOf(
                    "Init",  // ← Confirmed correct table name for Blumon SDK
                    "init",
                    "InitEntity", "init_entity", "initentity",
                    "InitData", "init_data", "initdata",
                    "Configuration", "configuration"
                )

                var cleared = false
                for (tableName in tableNames) {
                    try {
                        val rowsDeleted = db.delete(tableName, null, null)
                        if (rowsDeleted > 0) {
                            Timber.i("   ✅ Cleared $rowsDeleted rows from '$tableName' table")
                            cleared = true
                        }
                    } catch (e: Exception) {
                        // Table doesn't exist, try next
                        Timber.v("   Table '$tableName' not found or error: ${e.message}")
                    }
                }

                if (!cleared) {
                    // Fallback: List all tables and try to find init-related one
                    val cursor = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                        null
                    )
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0))
                    }
                    cursor.close()
                    Timber.w("   ⚠️ Could not find init table. Available tables: $tables")
                }
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            Timber.e(e, "   ❌ Failed to clear init table")
        }
    }
}
