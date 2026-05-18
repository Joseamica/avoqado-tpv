package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.MerchantOption

/**
 * Lifecycle state of the AngelPay auth + config-validation pipeline owned by
 * [AngelPayAuthRepository] (Task 30 — spec §6.5, §18.4, §18.6).
 *
 * Transitions:
 * ```
 * Unauthenticated
 *     ├── ensureAuthenticated() → Authenticating
 *     │       ├── AuthenticateSimpleResult.Success → Authenticated → runConfigValidation
 *     │       ├── AuthenticateSimpleResult.MerchantSelectionRequired → SelectingMerchant
 *     │       └── failure (after 3x retries) → AuthError(message)
 *     ├── (creds resolver returns missing) → AuthError
 * SelectingMerchant
 *     ├── completeMerchantSelection(id, token):
 *     │       ├── success → Authenticated → runConfigValidation
 *     │       └── failure → AuthError(message)
 * Authenticated
 *     ├── runConfigValidation():
 *     │       ├── AllClear → stays Authenticated
 *     │       ├── PartialOperable → ConfigMismatchBanner (banner overlay; payments still allowed)
 *     │       └── HardBlock → AuthError (payments blocked)
 *     ├── handleAuthExpiry() → logout() → Unauthenticated → ensureAuthenticated()
 *     └── logout() → Unauthenticated
 * AccountSuspended
 *     └── (terminal — only cleared by external account reactivation + cold start)
 * ConfigMismatchBanner
 *     └── (overlay over Authenticated — payments still allowed if PartialOperable;
 *          HardBlock surfaces as AuthError above)
 * ```
 */
sealed class AngelPayAuthState {
    /** Initial state and post-logout terminal state — no SDK session. */
    object Unauthenticated : AngelPayAuthState()

    /** Auth flight in progress — UI may show a spinner. */
    object Authenticating : AngelPayAuthState()

    /**
     * The SDK returned [com.angelpay.angelpaysdk.models.AuthenticateSimpleResult.MerchantSelectionRequired].
     * The ViewModel must prompt the cashier to pick a merchant and call
     * [AngelPayAuthRepository.completeMerchantSelection] with the choice plus the
     * one-shot [temporaryToken] from this state.
     */
    data class SelectingMerchant(
        val merchants: List<MerchantOption>,
        val temporaryToken: String,
    ) : AngelPayAuthState()

    /** Auth + initial merchant selection both complete. SDK is ready to charge. */
    object Authenticated : AngelPayAuthState()

    /** Surfaced for cashier — operator banner. Payments are blocked. */
    data class AuthError(val message: String) : AngelPayAuthState()

    /**
     * Backend reports the venue's `AngelPayUserAccount.status != ACTIVE` (spec §6.5).
     * Terminal state — only cleared by external account reactivation + cold start.
     */
    data class AccountSuspended(
        val statusFromBackend: String,
        val reason: String?,
    ) : AngelPayAuthState()

    /**
     * D5 validator returned `PartialOperable` (spec §6.9, §18.4) — the cashier can
     * still charge against the intersection but the operator banner explains the drift.
     * Logically overlays [Authenticated]; the merchant selector restricts to the
     * operable IDs.
     */
    data class ConfigMismatchBanner(
        val onlyInSdk: Set<Int>,
        val onlyInAvoqado: Set<Int>,
    ) : AngelPayAuthState()
}
