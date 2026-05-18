package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retrofit implementation of the AngelPay backend reporter (Task 14 + Option B).
 *
 * The base URL is the same `Retrofit` singleton used by `ApiService` — it already
 * includes `/api/v1/` and runs through `AuthInterceptor` + `TenantInterceptor`,
 * so terminal JWT authentication and venue headers are added automatically.
 *
 * Paths are RELATIVE to that base URL — do NOT prefix them with `/api/v1/`.
 *
 * Until now (Task 30 / Task 31), `AngelPayBindingsModule.provideAngelPayReportApi`
 * returned `null` so the Phase 1 validation reporter was a no-op. The Option B
 * workaround needs real network delivery, so this binding becomes the production
 * implementation. All call sites in [AngelPayAuthRepository] are still wrapped
 * in `runCatching` so any transport failure stays opaque to the cashier UI.
 */
interface AngelPayReportApiRetrofit {
    @POST("tpv/angelpay/report-validation")
    suspend fun reportValidation(@Body body: ReportValidationBody)

    @POST("tpv/angelpay/report-discovered-merchants")
    suspend fun reportDiscoveredMerchants(@Body body: ReportDiscoveredMerchantsBody)
}

data class ReportValidationBody(
    val accountId: String,
    val state: String,
    val externalUserId: Int?,
    val error: String?,
    val missingInAvoqado: List<Int>?,
    val missingInSdk: List<Int>?,
)

data class ReportDiscoveredMerchantsBody(
    val accountId: String,
    val merchants: List<DiscoveredMerchantWire>,
)

data class DiscoveredMerchantWire(
    val angelpayId: Int,
    val name: String,
    val affiliationNumber: String,
    val isActive: Boolean,
)

/**
 * Adapter that bridges the [AngelPayReportApi] domain contract (used by
 * [AngelPayAuthRepository] and trivially mocked in unit tests) to the
 * Retrofit interface above. Wraps both endpoints in `runCatching` so the
 * callers never see exceptions.
 */
@Singleton
class AngelPayReportApiImpl @Inject constructor(
    private val retrofit: AngelPayReportApiRetrofit,
) : AngelPayReportApi {
    override suspend fun reportValidation(
        accountId: String,
        state: String,
        externalUserId: Int?,
        error: String?,
        missingInAvoqado: List<Int>?,
        missingInSdk: List<Int>?,
    ): Result<Unit> = runCatching {
        retrofit.reportValidation(
            ReportValidationBody(
                accountId = accountId,
                state = state,
                externalUserId = externalUserId,
                error = error,
                missingInAvoqado = missingInAvoqado,
                missingInSdk = missingInSdk,
            ),
        )
    }

    override suspend fun reportDiscoveredMerchants(
        accountId: String,
        merchants: List<DiscoveredMerchantDto>,
    ): Result<Unit> = runCatching {
        retrofit.reportDiscoveredMerchants(
            ReportDiscoveredMerchantsBody(
                accountId = accountId,
                merchants = merchants.map {
                    DiscoveredMerchantWire(
                        angelpayId = it.angelpayId,
                        name = it.name,
                        affiliationNumber = it.affiliationNumber,
                        isActive = it.isActive,
                    )
                },
            ),
        )
    }
}
