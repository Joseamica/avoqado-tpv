package com.jaac.avoqado_tpv.features.payment.data

import com.example.clean_lib_services.shared.core.data.data_source.remote.CoreApiService
import com.example.clean_lib_services.shared.core.data.data_source.remote.model.GetDUKPTKeysApiRequest
import com.example.clean_lib_services.shared.core.data.data_source.remote.model.GetRSAKeysApiRequest
import com.example.clean_lib_services.shared.core.domain.entity.dukpt_keys.DUKPTData
import com.example.clean_lib_services.shared.tokener.data.remote.TokenerApiService
import com.example.clean_lib_services.shared_tools.api.GlobalResources
import com.example.clean_lib_services.shared_tools.api.server.CoreServer
import com.example.clean_lib_services.shared_tools.api.server.TokenServer
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlumonAuthManager @Inject constructor(
    private val deviceInfoManager: DeviceInfoManager,
    private val tokenServer: TokenServer,
    private val coreServer: CoreServer
) {
    // Store access token for Bearer authentication
    @Volatile
    private var accessToken: String? = null

    companion object {
        private const val CLIENT_ID = "blumon_pay_core_api"
        private const val CLIENT_SECRET = "blumon_pay_core_api_password"
        private const val GRANT_TYPE = "password"
        private const val BASIC_AUTH_CREDENTIALS =
            "Basic Ymx1bW9uX3BheV9jb3JlX2FwaTpibHVtb25fcGF5X2NvcmVfYXBpX3Bhc3N3b3Jk"
    }

    /**
     * Get current access token for Bearer authentication
     */
    fun getAccessToken(): String? = accessToken

    /**
     * Fetch ONLY the OAuth access_token (no RSA/DUKPT keys)
     *
     * **Purpose:** Enable Bearer authentication for InitUseCase validation
     *
     * **Flow:**
     * 1. Calculate SHA256 password
     * 2. POST /oauth/token → access_token
     * 3. Store token internally
     * 4. Return token (no key fetching)
     *
     * **Use Case:** SANDBOX mode needs OAuth for InitUseCase, but doesn't need keys
     *
     * @return access_token or null if OAuth fails
     */
    suspend fun fetchAccessTokenOnly(): String? {
        return try {
            val serialNumber = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
            val brand = com.jaac.avoqado_tpv.core.domain.TerminalConfig.brand
            val model = com.jaac.avoqado_tpv.core.domain.TerminalConfig.model

            Timber.i("🔐 [BlumonAuthManager] Fetching OAuth token only (no keys)...")
            Timber.d("   Serial: $serialNumber, Brand: $brand, Model: $model")

            val password = calculatePassword(serialNumber, brand, model)
            Timber.d("   Password (SHA256): ${password.take(16)}...")

            Timber.d("   Requesting OAuth token...")
            val token = getOAuthToken(serialNumber, password)
            if (token == null) {
                Timber.e("❌ Failed to get access token")
                return null
            }

            // Store token for Bearer authentication
            this.accessToken = token

            // ⭐ CRITICAL: Set token in GlobalResources for SDK HTTP interceptor
            GlobalResources.tokenAuth = token

            Timber.i("   ✅ Access token obtained and stored")
            Timber.d("   🔐 Token: ${token.take(20)}...")
            Timber.i("   ⭐ GlobalResources.tokenAuth = \"${GlobalResources.tokenAuth.take(20)}...\" (VERIFIED)")

            token
        } catch (e: Exception) {
            Timber.e(e, "❌ [BlumonAuthManager] Failed to fetch access token")
            null
        }
    }

    data class BlumonCredentials(
        val accessToken: String,
        val rsaId: Int,
        val rsaKey: String,
        val dukptKsn: String,
        val dukptKey: String,
        val dukptKeyCrc32: String,
        val dukptKeyCheckValue: String
    ) {
        fun toDUKPTData(): DUKPTData {
            return DUKPTData(
                ksn = dukptKsn,
                key = dukptKey,
                keyCrc32 = dukptKeyCrc32,
                keyCheckValue = dukptKeyCheckValue,
                transactionCounter = "0"
            )
        }
    }

    suspend fun fetchCredentials(): BlumonCredentials? {
        return try {
            val serialNumber = com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber
            val brand = com.jaac.avoqado_tpv.core.domain.TerminalConfig.brand  // "PAX" (not UNISOC!)
            val model = com.jaac.avoqado_tpv.core.domain.TerminalConfig.model  // "A910S"

            Timber.i("🔐 [BlumonAuthManager] Starting OAuth flow...")
            Timber.d("   Serial: $serialNumber, Brand: $brand, Model: $model")

            // Calculate password: SHA256(Serial + Brand + Model)
            val password = calculatePassword(serialNumber, brand, model)
            Timber.d("   Password (SHA256): ${password.take(16)}...")

            Timber.d("   [Step 1/3] Requesting OAuth token...")
            val token = getOAuthToken(serialNumber, password)
            if (token == null) {
                Timber.e("❌ Failed to get access token")
                return null
            }

            // Store token for Bearer authentication in subsequent requests
            this.accessToken = token

            // ⭐ CRITICAL: Set token in GlobalResources for SDK HTTP interceptor
            GlobalResources.tokenAuth = token

            Timber.i("   ✅ Access token obtained: ${token.take(20)}...")
            Timber.d("   🔐 Token stored for Bearer authentication")
            Timber.d("   🔐 GlobalResources.tokenAuth configured")

            Timber.d("   [Step 2/3] Fetching RSA encryption keys...")
            val rsaKeys = getRSAKeys(serialNumber)
            if (rsaKeys == null) {
                Timber.e("❌ Failed to get RSA keys")
                return null
            }
            val (rsaId, rsaKey) = rsaKeys
            Timber.i("   ✅ RSA keys obtained (ID: $rsaId, Key length: ${rsaKey.length})")

            Timber.d("   [Step 3/3] Initializing DUKPT keys...")
            val dukptKeys = getDUKPTKeys(serialNumber, rsaKey)
            if (dukptKeys == null) {
                Timber.e("❌ Failed to get DUKPT keys")
                return null
            }
            val (ksn, key, keyCrc32, keyCheckValue) = dukptKeys
            Timber.i("   ✅ DUKPT keys obtained (KSN: $ksn)")

            Timber.i("✅ [BlumonAuthManager] OAuth flow completed successfully!")

            BlumonCredentials(
                accessToken = token,  // Use local variable (non-null)
                rsaId = rsaId,
                rsaKey = rsaKey,
                dukptKsn = ksn,
                dukptKey = key,
                dukptKeyCrc32 = keyCrc32,
                dukptKeyCheckValue = keyCheckValue
            )
        } catch (e: Exception) {
            Timber.e(e, "❌ [BlumonAuthManager] OAuth flow failed")
            null
        }
    }

    private fun calculatePassword(serialNumber: String, brand: String, model: String): String {
        val input = "$serialNumber$brand$model"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private suspend fun getOAuthToken(username: String, password: String): String? {
        return try {
            val tokenerApi = tokenServer.retrofit.create(TokenerApiService::class.java)
            val response = tokenerApi.getToken(GRANT_TYPE, username, password)

            if (!response.accessToken.isNullOrEmpty()) {
                response.accessToken
            } else {
                Timber.e("Token response is empty")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get OAuth token")
            null
        }
    }

    private suspend fun getRSAKeys(posId: String): Pair<Int, String>? {
        return try {
            val coreApi = coreServer.retrofit.create(CoreApiService::class.java)
            val request = GetRSAKeysApiRequest(posId = posId)
            val response = coreApi.getRSAKeys(request)

            val rsaData = response.rsaData
            if (rsaData != null && rsaData.rsa.isNotEmpty()) {
                Pair(rsaData.rsaId, rsaData.rsa)
            } else {
                Timber.e("RSA response missing required fields")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get RSA keys")
            null
        }
    }

    private suspend fun getDUKPTKeys(posId: String, rsa: String): Quadruple<String, String, String, String>? {
        return try {
            val coreApi = coreServer.retrofit.create(CoreApiService::class.java)
            val request = GetDUKPTKeysApiRequest(
                posId = posId,
                rsa = rsa,
                checkValue = "",
                crc32 = ""
            )
            val response = coreApi.getDUKPTKeys(request)

            val dukptData = response.dukptData
            if (dukptData != null && dukptData.ksn.isNotEmpty() && dukptData.key.isNotEmpty()) {
                Quadruple(
                    dukptData.ksn,
                    dukptData.key,
                    dukptData.keyCrc32,
                    dukptData.keyCheckValue
                )
            } else {
                Timber.e("DUKPT response missing required fields")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get DUKPT keys")
            null
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
