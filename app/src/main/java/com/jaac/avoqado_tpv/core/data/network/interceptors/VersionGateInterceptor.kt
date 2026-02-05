package com.jaac.avoqado_tpv.core.data.network.interceptors

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.data.network.UpdateMode
import com.jaac.avoqado_tpv.core.util.UpdateCheckManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Version Gate Response DTO
 *
 * Matches backend 426 response from tpv-version-gate.middleware.ts
 */
private data class VersionGateResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("error")
    val error: String,

    @SerializedName("message")
    val message: String,

    @SerializedName("minVersionCode")
    val minVersionCode: Int,

    @SerializedName("currentVersionCode")
    val currentVersionCode: Int,

    @SerializedName("update")
    val update: VersionGateUpdate?
)

private data class VersionGateUpdate(
    @SerializedName("versionName")
    val versionName: String,

    @SerializedName("versionCode")
    val versionCode: Int,

    @SerializedName("downloadUrl")
    val downloadUrl: String,

    @SerializedName("releaseNotes")
    val releaseNotes: String?
)

/**
 * Version Gate Interceptor
 *
 * Intercepts HTTP 426 (Upgrade Required) responses from the backend and
 * triggers ForceUpdateDialog via UpdateCheckManager.
 *
 * **Square/Toast/Stripe Pattern:**
 * This is the "cannot bypass" enforcement mechanism. When the backend returns 426,
 * the app cannot function until updated - every API call will fail.
 *
 * **Flow:**
 * 1. Backend middleware checks X-App-Version-Code header
 * 2. If version is below minimum FORCE update, returns HTTP 426
 * 3. This interceptor catches 426 and notifies UpdateCheckManager
 * 4. UpdateCheckManager sets pendingUpdate with FORCE mode
 * 5. UI observes pendingUpdate and shows ForceUpdateDialog
 * 6. User cannot dismiss dialog or use app until update is installed
 *
 * **Why Interceptor instead of handling in each call site?**
 * - Centralized handling: Don't need to check for 426 in every API call
 * - Consistent behavior: Same response everywhere
 * - Clean code: API callers don't need to know about version gating
 *
 * **Lazy Injection:**
 * Uses Lazy<UpdateCheckManager> to break dependency cycle:
 * UpdateCheckManager → ApiService → Retrofit → OkHttpClient → VersionGateInterceptor → UpdateCheckManager
 */
@Singleton
class VersionGateInterceptor @Inject constructor(
    private val updateCheckManagerLazy: dagger.Lazy<UpdateCheckManager>
) : Interceptor {

    private val gson = Gson()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // Check for HTTP 426 Upgrade Required
        if (response.code == 426) {
            Timber.w("🚨 [VersionGate] HTTP 426 received - app version is outdated!")

            try {
                // Read response body (need to buffer it since we'll read it twice)
                val responseBody = response.peekBody(Long.MAX_VALUE).string()

                // Parse the response
                val versionGateResponse = gson.fromJson(responseBody, VersionGateResponse::class.java)

                Timber.w("🚨 [VersionGate] Current: ${versionGateResponse.currentVersionCode}, Required: ${versionGateResponse.minVersionCode}")

                // If update info is provided, notify UpdateCheckManager
                val updateInfo = versionGateResponse.update
                if (updateInfo != null) {
                    Timber.i("🚨 [VersionGate] Setting force update: v${updateInfo.versionCode} (${updateInfo.versionName})")

                    val avoqadoUpdate = AvoqadoUpdateInfo(
                        id = "version-gate-${updateInfo.versionCode}",
                        versionName = updateInfo.versionName,
                        versionCode = updateInfo.versionCode,
                        downloadUrl = updateInfo.downloadUrl,
                        fileSize = "0",
                        checksum = "version-gate",
                        releaseNotes = updateInfo.releaseNotes,
                        updateMode = UpdateMode.FORCE,
                        minAndroidSdk = 27,
                        publishedAt = java.time.Instant.now().toString()
                    )

                    // Set pending update - this will trigger ForceUpdateDialog
                    // Use .get() because of Lazy injection to break dependency cycle
                    updateCheckManagerLazy.get().setForceUpdate(avoqadoUpdate)
                }
            } catch (e: Exception) {
                Timber.e(e, "🚨 [VersionGate] Failed to parse 426 response")
            }
        }

        return response
    }
}
