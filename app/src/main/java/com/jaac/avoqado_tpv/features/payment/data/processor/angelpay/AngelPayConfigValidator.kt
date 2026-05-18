package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.core.data.network.dto.TerminalConfigData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D5 intersection-based config validator (spec §6.9, §18.4).
 *
 * Compares the AngelPay SDK's user-merchant list (authoritative for "which
 * merchants can be authenticated against") with the Avoqado terminal config's
 * AngelPay merchant list (authoritative for "which merchants Avoqado can
 * record payments to"). Only the intersection is operable.
 *
 * Outcomes:
 *   - [ValidationResult.AllClear]: fully synchronized → no UI warning
 *   - [ValidationResult.PartialOperable]: drift exists but at least one merchant
 *     works → yellow banner, restricted selector, Crashlytics non-fatal recorded
 *   - [ValidationResult.HardBlock]: no shared merchants → red banner, payments
 *     blocked, Crashlytics non-fatal recorded
 *
 * Called from AngelPayAuthRepository post-auth (Task 30) and on every
 * subsequent merchant cache refresh (D6).
 */
@Singleton
class AngelPayConfigValidator @Inject constructor(
    private val sdkGateway: AngelPaySdkGateway,
    private val crashlytics: FirebaseCrashlytics,
) {
    suspend fun validate(config: TerminalConfigData): ValidationResult {
        val sdkMerchantIds: Set<Int> = sdkGateway.getUserMerchants()
            .getOrNull()
            ?.map { it.id }
            ?.toSet()
            .orEmpty()

        val avoqadoMerchantIds: Set<Int> = config.merchantAccounts
            .filter { it.providerCode == ANGELPAY_CODE && it.isActive }
            .mapNotNull { it.externalMerchantId?.toIntOrNull() }
            .toSet()

        val intersection = sdkMerchantIds intersect avoqadoMerchantIds
        val onlyInSdk = sdkMerchantIds - avoqadoMerchantIds
        val onlyInAvoqado = avoqadoMerchantIds - sdkMerchantIds

        return when {
            intersection.isEmpty() -> {
                crashlytics.recordException(
                    AngelPayConfigMismatchInfo(
                        onlyInSdk = onlyInSdk,
                        onlyInAvoqado = onlyInAvoqado,
                        severity = "hard_block",
                    ),
                )
                ValidationResult.HardBlock(
                    message = "Sin merchants válidos compartidos entre AngelPay y Avoqado. Contacta soporte.",
                )
            }
            onlyInSdk.isNotEmpty() || onlyInAvoqado.isNotEmpty() -> {
                crashlytics.recordException(
                    AngelPayConfigMismatchInfo(
                        onlyInSdk = onlyInSdk,
                        onlyInAvoqado = onlyInAvoqado,
                        severity = "partial_operable",
                    ),
                )
                ValidationResult.PartialOperable(
                    operableIds = intersection,
                    onlyInSdk = onlyInSdk,
                    onlyInAvoqado = onlyInAvoqado,
                )
            }
            else -> ValidationResult.AllClear
        }
    }

    companion object {
        private const val ANGELPAY_CODE = "ANGELPAY"
    }
}

/**
 * Outcome of [AngelPayConfigValidator.validate].
 */
sealed class ValidationResult {
    /** SDK + Avoqado merchant sets match exactly. */
    object AllClear : ValidationResult()

    /**
     * Intersection is non-empty but drift exists. Operator selector restricts
     * to [operableIds]; yellow banner surfaces the mismatch counts.
     */
    data class PartialOperable(
        val operableIds: Set<Int>,
        val onlyInSdk: Set<Int>,
        val onlyInAvoqado: Set<Int>,
    ) : ValidationResult()

    /** Empty intersection — operator cannot charge AngelPay. */
    data class HardBlock(val message: String) : ValidationResult()
}

/**
 * Non-fatal exception recorded to Crashlytics on every config mismatch.
 * Not thrown — the validator returns a structured [ValidationResult] instead.
 * [severity] differentiates `partial_operable` vs `hard_block` for analytics
 * dashboards / drift monitoring.
 */
data class AngelPayConfigMismatchInfo(
    val onlyInSdk: Set<Int>,
    val onlyInAvoqado: Set<Int>,
    val severity: String,
) : RuntimeException(
    "AngelPay config mismatch ($severity) — onlyInSdk=$onlyInSdk, onlyInAvoqado=$onlyInAvoqado",
)
