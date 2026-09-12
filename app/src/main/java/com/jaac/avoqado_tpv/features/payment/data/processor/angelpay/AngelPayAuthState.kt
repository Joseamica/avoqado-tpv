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
 * AuthError(kind recuperable: SIN_RED · SIN_CREDENCIALES · CUENTA_NO_EN_CONFIG) / Unauthenticated
 *     └── AngelPayAuthRecovery (red de vuelta, config leída, servidor, «Reintentar»; T26)
 *             → Recuperando → Authenticated | SelectingMerchant | AuthError
 *             (multicuenta sin cuenta conocida → Unauthenticated: «requiere autenticación»)
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
     * T26: la recuperación de FONDO está re-autenticando (red recuperada, config leída,
     * servidor de vuelta o «Reintentar»). Estado propio y no [Authenticating] a propósito:
     * apaga la Tarjeta pero NO el Efectivo — el efectivo no usa la sesión de AngelPay y la
     * recuperación no toca el estado de la pantalla de cobro.
     */
    object Recuperando : AngelPayAuthState()

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

    /**
     * Surfaced for cashier — operator banner. Payments are blocked.
     *
     * [kind] (T26, 2026-09-11) separa las tres causas que antes salían todas como
     * «credentials missing»: sin red, sin credenciales en el panel, y cuenta que ya no
     * está en la config. Por defecto [AuthErrorKind.OTHER] para no romper vistas previas,
     * pruebas ni los errores que no son de auth (el bloqueo de config D5).
     */
    data class AuthError(
        val message: String,
        val kind: AuthErrorKind = AuthErrorKind.OTHER,
    ) : AngelPayAuthState()

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

/**
 * T26 (Testarudo, 2026-09-11): por qué falló la autenticación de AngelPay.
 *
 * La distinción no es cosmética: decide si la terminal puede recuperarse SOLA y si se
 * permite caer a la cuenta primaria del venue.
 *  - [SIN_RED]: no se pudo consultar la config ni hablar con AngelPay. Se reintenta sola al
 *    volver la red, y NUNCA cae a la primaria (sin red no se puede afirmar que la cuenta del
 *    comercio ya no existe — regla de Amaena).
 *  - [SIN_CREDENCIALES]: la config SÍ se leyó y no trae credenciales de AngelPay.
 *  - [CUENTA_NO_EN_CONFIG]: la config SÍ se leyó y la cuenta del comercio elegido no está.
 *  - [OTHER]: cualquier otra (PIN rechazado, bloqueo de config D5…). No se reintenta en
 *    fondo: repetir credenciales malas contra AngelPay no arregla nada.
 */
enum class AuthErrorKind {
    SIN_RED,
    SIN_CREDENCIALES,
    CUENTA_NO_EN_CONFIG,
    OTHER;

    /** Reintentar en fondo no manda credenciales rechazadas a AngelPay. */
    val recuperableEnFondo: Boolean get() = this != OTHER
}
