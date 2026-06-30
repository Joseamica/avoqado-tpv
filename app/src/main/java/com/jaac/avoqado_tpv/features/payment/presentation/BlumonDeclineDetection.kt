package com.jaac.avoqado_tpv.features.payment.presentation

/**
 * Decides whether a Blumon `SaleIcc`/`SaleCtls` response that came back NON-null is actually an
 * issuer DECLINE disguised as success.
 *
 * Blumon returns issuer declines (e.g. "PAGO NO PERMITIDO EMISOR") as a NON-null `SaleIccResponse`
 * with `authorization` BLANK and `description` carrying the reason (`error` = null). The legacy
 * `response == null` check in `performOnlineAuthorization` misses these, so the flow proceeded to
 * `CompleteEmvTrans` and HUNG ("se queda pasmada") instead of showing the decline.
 *
 * A genuine approval ALWAYS carries an authorization code — verified against production on
 * 2026-06-29: **2850/2850** real Blumon approvals (CHIP + CONTACTLESS) have a non-blank
 * `authorization`. So a blank authorization reliably means the transaction was declined, with no
 * risk of false declines on real approvals.
 *
 * Pure function (no SDK / Android types) so it can be unit-tested without a terminal.
 *
 * @return a user-facing decline message when [authorization] is blank — the caller should route it
 *   through the existing (already-tested) `response == null` error path; or `null` when the
 *   transaction is a genuine approval and the flow should proceed normally.
 */
internal fun blumonDeclineMessage(
    authorization: String?,
    description: String?,
    isRefund: Boolean,
): String? {
    if (!authorization.isNullOrBlank()) return null
    val prefix = if (isRefund) "Reembolso rechazado" else "Pago rechazado"
    val reason = description?.takeIf { it.isNotBlank() } ?: "El banco no autorizó la transacción"
    return "$prefix:\n\n$reason\n\nSolicita otra forma de pago."
}
