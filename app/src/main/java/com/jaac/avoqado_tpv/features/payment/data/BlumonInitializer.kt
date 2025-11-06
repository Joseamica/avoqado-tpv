package com.jaac.avoqado_tpv.features.payment.data

import com.example.clean_lib_services.shared.core.domain.entity.dukpt_keys.DUKPTData
import com.example.clean_lib_services.shared.core.domain.entity.init.Contact
import com.example.clean_lib_services.shared.core.domain.entity.init.InitData
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_dukpt_data.InsertDUKPTDataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_dukpt_data.InsertDUKPTDataUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_init.InsertInitUseCase
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_rsa_data.InsertRSADataParams
import com.example.clean_lib_services.shared.initializer.domain.use_case.insert_rsa_data.InsertRSADataUseCase
import com.jaac.avoqado_tpv.BuildConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BlumonInitializer - Configures Blumon SDK credentials for Momentum Sandbox
 *
 * **Problems Solved:**
 * 1. **InitData missing**: SDK throws "List is empty" at InitializerDataSourceLocalImpl:65
 * 2. **RSA keys missing**: SDK throws NullPointerException at getRSAData():102
 *
 * **Solution:**
 * This class provides lazy initialization that:
 * 1. Calls InsertInitUseCase with test/placeholder InitData (merchant credentials)
 * 2. Calls InsertRSADataUseCase with placeholder RSA key (encryption)
 * 3. Configures SDK for Sandbox environment
 * 4. Runs only once (singleton pattern)
 *
 * **Usage:**
 * ```kotlin
 * // In PaymentViewModel before SaleIccUseCase
 * blumonInitializer.initializeIfNeeded()
 * ```
 *
 * **InitData Structure (17 parameters):**
 * - posId: Terminal serial number
 * - commerceName: Venue name (placeholder for testing)
 * - commerceAddress: Address (placeholder)
 * - contact: Contact info (name, phone, email)
 * - Feature flags: emv=true, contactless=true, others=false
 * - transactionProfile: "SAND" for sandbox
 * - kushki: null (not using Kushki payment gateway)
 *
 * **RSA Data Structure (2 parameters):**
 * - posId: Terminal serial number
 * - tk: Terminal Key (RSA public key - currently using placeholder)
 *
 * **⚠️ PRODUCTION TODO:**
 * Replace RSA_PLACEHOLDER_KEY with real key from Blumon OAuth backend:
 * 1. Implement BlumonAuthManager (see AvoqadoPOS reference)
 * 2. GET https://sandbox-tokener.blumonpay.net/oauth/token
 * 3. Use OAuth token to fetch real RSA keys from Momentum API
 *
 * **Environment:**
 * - Sandbox: BLUMON_ENV = "SAND" (from BuildConfig)
 * - Production: BLUMON_ENV = "PROD" (future)
 *
 * **Reference:**
 * - Decompiled SDK errors:
 *   - InitializerDataSourceLocalImpl.java:65 (InitData "List is empty")
 *   - InitializerDataSourceLocalImpl.java:102 (RSA NullPointerException)
 * - Entity structures:
 *   - InitData.java:205 (17 parameters)
 *   - RSADataEntity.java:82 (posId + tk)
 */
@Singleton
class BlumonInitializer @Inject constructor(
    private val insertInitUseCase: InsertInitUseCase,
    private val insertRSADataUseCase: InsertRSADataUseCase,
    private val insertDUKPTDataUseCase: InsertDUKPTDataUseCase,
    private val blumonAuthManager: BlumonAuthManager,
    // ⭐ NEW: InitUseCase for backend validation (per Edgardo's guidance)
    // "Es importante realizar el validate"
    private val initUseCase: com.example.clean_lib_services.shared.core.domain.use_case.init.InitUseCase
) {
    // Track initialization state to prevent duplicate calls
    private var isInitialized = false

    companion object {
        /**
         * Placeholder RSA Terminal Key for Sandbox testing
         *
         * ⚠️ THIS IS A TEMPORARY PLACEHOLDER!
         *
         * Format: 256-character hex string (1024-bit RSA key)
         * - SDK requires EVEN-length hex string for byte conversion
         * - This is dummy data - real key must come from Blumon OAuth backend
         */
        private const val RSA_PLACEHOLDER_KEY =
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF" +
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF" +
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF" +
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"

        /**
         * Placeholder DUKPT (Derived Unique Key Per Transaction) Keys
         *
         * ⚠️ THESE ARE TEMPORARY PLACEHOLDERS - WILL NOT WORK FOR REAL AUTHORIZATION!
         *
         * **What is DUKPT:**
         * - Industry-standard PIN encryption system (PCI DSS requirement)
         * - Generates unique encryption key for each transaction
         * - Prevents key reuse attacks
         *
         * **Components:**
         * - KSN: Key Serial Number (identifies terminal + transaction number)
         * - Key: Base DUKPT encryption key (derived from BDK - Base Derivation Key)
         * - KeyCrc32: CRC32 checksum of the key for validation
         * - KeyCheckValue: Additional validation (typically first 6 chars of encrypted zeros)
         * - TransactionCounter: Number of transactions processed (starts at 0)
         *
         * **Real Implementation Required:**
         * 1. Terminal must be registered with Blumon/issuer
         * 2. Issuer generates unique BDK for this terminal
         * 3. BDK never leaves secure system - only derived keys transmitted
         * 4. Initial KSN + Key fetched from Blumon backend during onboarding
         * 5. Counter increments with each transaction
         *
         * **Why Placeholders Won't Work:**
         * - Blumon backend will reject invalid KSN format
         * - Key must be mathematically derived from BDK registered in issuer system
         * - CRC32/CheckValue must match or crypto operations fail
         *
         * **Production Solution:**
         * See RSA_PLACEHOLDER_KEY comment - same OAuth flow fetches DUKPT keys
         */
        private const val DUKPT_KSN_PLACEHOLDER = "FFFF9876543210E00000"
        private const val DUKPT_KEY_PLACEHOLDER = "0123456789ABCDEFFEDCBA9876543210"
        private const val DUKPT_KEY_CRC32_PLACEHOLDER = "00000000"
        private const val DUKPT_KEY_CHECK_VALUE_PLACEHOLDER = "000000"
        private const val DUKPT_TRANSACTION_COUNTER_PLACEHOLDER = "0"
    }

    /**
     * Initialize Blumon SDK credentials (idempotent - runs only once)
     *
     * **NEW (2025-11-04)**: Uses backend validation per Edgardo's guidance
     * - Calls InitUseCase to validate terminal serial with backend
     * - Backend returns InitData with proper posId that can convert to Int
     * - Fixes Integer overflow issue with serial 2841548417
     *
     * **Fallback**: Uses manual InitData creation if validation fails
     *
     * @param useOAuth If true, attempts to fetch real credentials via OAuth (default: true)
     * @return true if initialization succeeded or already done, false if failed
     */
    suspend fun initializeIfNeeded(useOAuth: Boolean = true): Boolean {
        if (isInitialized) {
            Timber.d("✅ [BlumonInitializer] Already initialized - skipping")
            return true
        }

        Timber.i("🔧 [BlumonInitializer] Starting SDK initialization for ${BuildConfig.BLUMON_ENV} environment...")

        return try {
            // Step 0: ALWAYS authenticate via OAuth (PROD and SAND)
            // ⭐ CRITICAL: Backend requires Bearer token even in SANDBOX
            // Proven by successful curl: POST /oauth/token with serial 2841548417
            Timber.i("   [Step 0] Authenticating with Blumon OAuth...")
            if (!useOAuth) {
                Timber.e("   ❌ OAuth disabled - cannot proceed (backend requires authentication)")
                return false
            }

            // SAND mode: Only fetch access_token (no RSA/DUKPT to avoid Integer overflow)
            // PROD mode: Fetch full credentials (needs RSA/DUKPT keys)
            val isSandbox = (BuildConfig.BLUMON_ENV == "SAND")

            if (isSandbox) {
                // SANDBOX: Get token only
                Timber.d("   🧪 SAND mode - fetching access_token only...")
                val token = blumonAuthManager.fetchAccessTokenOnly()
                if (token == null) {
                    Timber.e("   ❌ OAuth failed - cannot proceed")
                    return false
                }
                Timber.i("   ✅ OAuth successful - Bearer token active")
            }

            // Step 1: Validate terminal with backend and get InitData
            // ⭐ NOW with Bearer token active, backend should accept validation
            Timber.d("   [Step 1] Validating terminal with backend (with Bearer token)...")
            val initData = validateAndGetInitData()
            if (initData == null) {
                Timber.w("   ⚠️ Backend validation failed - using manual InitData (may cause issues)")
                // Fallback: Use manually created InitData (legacy behavior)
                val manualInitData = createSandboxInitData()
                insertInitUseCase.runInfallible(InsertInitParams(manualInitData))
            } else {
                Timber.i("   ✅ Backend validated InitData received (posId: ${initData.posId})")
                insertInitUseCase.runInfallible(InsertInitParams(initData))
            }
            Timber.i("   ✅ InitData inserted successfully")

            // Step 2 & 3: Only insert keys in PROD mode
            // SAND mode uses KUSHKY cipher (no key provisioning required)
            if (isSandbox) {
                Timber.i("   🧪 Sandbox mode - using KUSHKY cipher (no DUKPT/RSA keys)")
                Timber.i("   ℹ️ KUSHKY = Test encryption for sandbox")
                Timber.i("   ℹ️ Access token will be used for authorization, but no key storage")
            } else {
                // PROD mode: Fetch full credentials with RSA/DUKPT keys
                Timber.i("   🔐 Production mode - fetching RSA/DUKPT keys...")
                val credentials = blumonAuthManager.fetchCredentials()
                if (credentials == null) {
                    Timber.e("   ❌ Failed to fetch production credentials")
                    return false
                }

                val keysInserted = insertRealCredentials(credentials)
                if (!keysInserted) {
                    Timber.e("   ❌ Failed to insert production credentials - cannot initialize")
                    return false
                }
            }

            isInitialized = true
            Timber.i("✅ [BlumonInitializer] SDK initialized successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "❌ [BlumonInitializer] Failed to initialize SDK")
            false
        }
    }

    /**
     * ⭐ NEW: Validate terminal with Blumon backend and get InitData
     *
     * Per Edgardo's guidance: "Es importante realizar el validate"
     *
     * **Why this is critical:**
     * - Backend validates the terminal serial number
     * - Returns InitData with a posId that can safely convert to Int
     * - Prevents Integer overflow (2841548417 > Integer.MAX_VALUE)
     * - Determines cipher type (KUSHKY vs DUKPT)
     * - Extracts idMembership from backend
     *
     * **Flow:**
     * 1. Call InitUseCase with terminal serial
     * 2. Backend validates: "Does this terminal exist?"
     * 3. Backend returns InitResponse with validated InitData
     * 4. InitData.posId is safe to convert to Int (SDK requirement)
     *
     * @return InitData from backend, or null if validation fails
     */
    private suspend fun validateAndGetInitData(): InitData? {
        return try {
            Timber.d("   🔍 Calling InitUseCase to validate terminal...")
            val params = com.example.clean_lib_services.shared.core.domain.use_case.init.InitParams(
                serial = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
            )

            when (val result = initUseCase.run(params)) {
                is com.example.clean_lib_services.utils.clean.Either.Right -> {
                    val response = result.right  // InitResponse
                    val initData = response.initData
                    Timber.i("   ✅ Backend validation successful!")
                    Timber.d("      posId: ${initData.posId}")
                    Timber.d("      commerceName: ${initData.commerceName}")
                    Timber.d("      transactionProfile: ${initData.transactionProfile}")
                    Timber.d("      Cipher detection: ${if (initData.transactionProfile == "SAND") "KUSHKY" else "DUKPT"}")
                    initData
                }
                is com.example.clean_lib_services.utils.clean.Either.Left -> {
                    val failure = result.left  // InitFailure
                    Timber.e("   ❌ Backend validation failed: $failure")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "   ❌ Exception during backend validation")
            null
        }
    }

    /**
     * Insert REAL credentials from OAuth (PROD mode only)
     *
     * @param credentials OAuth credentials already fetched
     * @return true if successful, false if insertion fails
     */
    private suspend fun insertRealCredentials(credentials: BlumonAuthManager.BlumonCredentials): Boolean {
        return try {
            // Step 2: Insert RSA keys
            Timber.d("   [Step 2/3] Inserting REAL RSA keys from OAuth...")
            val rsaParams = InsertRSADataParams(
                posId = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber,
                tk = credentials.rsaKey
            )
            insertRSADataUseCase.runInfallible(rsaParams)
            Timber.i("   ✅ Real RSA keys inserted (ID: ${credentials.rsaId})")

            // Step 3: Insert DUKPT keys
            Timber.d("   [Step 3/3] Inserting REAL DUKPT keys from OAuth...")
            val dukptParams = InsertDUKPTDataParams(
                dukptData = credentials.toDUKPTData(),
                posID = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
            )
            insertDUKPTDataUseCase.runInfallible(dukptParams)
            Timber.i("   ✅ Real DUKPT keys inserted (KSN: ${credentials.dukptKsn})")

            Timber.i("   🎉 Real credentials from OAuth successfully inserted!")
            true
        } catch (e: Exception) {
            Timber.e(e, "   ❌ Failed to insert real credentials")
            false
        }
    }

    /**
     * Insert placeholder credentials (fallback when OAuth unavailable)
     *
     * ⚠️ These keys will likely fail during online authorization
     *
     * @return true if insertion succeeds
     */
    private suspend fun insertPlaceholderCredentials(): Boolean {
        return try {
            // Step 2: Insert placeholder RSA keys
            Timber.d("   [Step 2/3] Inserting placeholder RSA keys...")
            val rsaParams = InsertRSADataParams(
                posId = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber,
                tk = RSA_PLACEHOLDER_KEY
            )
            insertRSADataUseCase.runInfallible(rsaParams)
            Timber.i("   ✅ Placeholder RSA keys inserted")

            // Step 3: Insert placeholder DUKPT keys
            Timber.d("   [Step 3/3] Inserting placeholder DUKPT keys...")
            val dukptData = DUKPTData(
                ksn = DUKPT_KSN_PLACEHOLDER,
                key = DUKPT_KEY_PLACEHOLDER,
                keyCrc32 = DUKPT_KEY_CRC32_PLACEHOLDER,
                keyCheckValue = DUKPT_KEY_CHECK_VALUE_PLACEHOLDER,
                transactionCounter = DUKPT_TRANSACTION_COUNTER_PLACEHOLDER
            )
            val dukptParams = InsertDUKPTDataParams(
                dukptData = dukptData,
                posID = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
            )
            insertDUKPTDataUseCase.runInfallible(dukptParams)
            Timber.i("   ✅ Placeholder DUKPT keys inserted")

            Timber.w("   ⚠️ Using placeholder keys - will likely fail at authorization")
            true
        } catch (e: Exception) {
            Timber.e(e, "   ❌ Failed to insert placeholder credentials")
            false
        }
    }

    /**
     * Create InitData for Sandbox environment
     *
     * **Test/Placeholder Values:**
     * - posId: Terminal serial from BuildConfig (PAX A910S: 2841548417)
     * - commerceName: "Avoqado Test Venue" (placeholder)
     * - commerceAddress: "Test Address" (placeholder)
     * - contact: Test contact info
     * - transactionProfile: "SAND" (sandbox environment)
     * - Feature flags:
     *   - emv: true (chip cards enabled)
     *   - contactless: true (NFC enabled)
     *   - manual: false (manual entry disabled for now)
     *   - Others: false (not needed for basic testing)
     *
     * **Production TODO:**
     * When migrating to production:
     * 1. Replace test values with real venue data from activation
     * 2. Change transactionProfile to "PROD"
     * 3. Enable additional features as needed (cashback, etc.)
     * 4. Get merchant credentials from Blumon onboarding
     */
    private fun createSandboxInitData(): InitData {
        return InitData(
            posId = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber,
            commerceName = "Avoqado Test Venue",
            commerceAddress = "Test Address, Test City",
            contact = Contact(
                name = "Test Contact",
                telephone = "1234567890",
                email = "test@avoqado.io"
            ),
            dollarMembership = false, // Dollar payments not enabled in Sandbox
            transactionProfile = BuildConfig.BLUMON_ENV, // "SAND"
            emv = true,           // ✅ Chip cards enabled
            contactless = true,   // ✅ NFC enabled
            manual = false,       // ❌ Manual entry disabled
            q6 = false,           // ❌ Q6 not used
            qps = false,          // ❌ QPS (Quick Payment Service) not used
            qpsAmount = 0.0,      // QPS amount limit (not applicable)
            cashback = false,     // ❌ Cashback not used
            partialCancellation = false, // ❌ Partial cancellation not used
            ticketPromotions = false,    // ❌ Ticket promotions not used
            initializeKeys = false, // ❌ KUSHKY cipher doesn't use DUKPT keys in SAND
            kushki = null         // null = Not using Kushki payment gateway
        )
    }

    /**
     * Reset initialization state (for testing only)
     *
     * ⚠️ DO NOT call in production code!
     * This is only for unit tests or debugging.
     */
    fun resetForTesting() {
        isInitialized = false
        Timber.w("⚠️ [BlumonInitializer] Reset for testing - next call will re-initialize")
    }
}
