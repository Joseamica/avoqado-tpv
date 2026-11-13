package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility

/** Qué cuentas ofrece «Método de pago» de la Nexgo y si la lista se puede ocultar. */
internal data class SelectorDeCuentas(
    val cuentas: List<MerchantAccount>,
    val ocultarLista: Boolean,
    val elegidaPermitida: Boolean = true,
    val cuentaAPasar: MerchantAccount? = null,
)

/**
 * 🧭 MERCHANT_ROUTING_RULES en la Nexgo (D3, 12-nov).
 *
 * - **Qué cuentas:** con reglas que filtran, sólo las permitidas; sin reglas, sin red o si ninguna permitida está cargada,
 *   todas (una regla nunca frena una venta).
 * - **Cuándo se oculta la lista:** sólo si no hay cuentas, o si queda UNA y ya hay una cuenta elegida. Si queda una y no hay
 *   ninguna elegida (varias cuentas en el negocio y la sesión de AngelPay pidiendo elegir), la lista se muestra para que la
 *   cajera la toque: ocultarla dejaba «Tarjeta» apagada sin forma de elegir (Codex r1 #1). La Nexgo no elige sola: cuando
 *   la elegía sin pasar por el SDK fallaba con cuentas que cargan tarde y en el modo app-a-app (Codex r2 #1 y #2); el toque
 *   va por `selectMerchant`, con todos sus candados.
 * - **No cambia:** una cuenta YA elegida que la regla excluye se queda elegida (la Nexgo sólo filtra, decisión del 13-jul).
 *   Es deuda declarada en `docs/audit/2026-11-12-nexgo-ruteo-sin-propina/promesa.md`.
 */
internal fun selectorDeCuentas(
    todas: List<MerchantAccount>,
    reglas: MerchantEligibility?,
    elegida: MerchantAccount?,
): SelectorDeCuentas {
    val cuentas = if (reglas == null || reglas.shouldShowAll) {
        todas
    } else {
        todas.filter { reglas.eligibleMerchantAccountIds.contains(it.merchantAccountId) }.ifEmpty { todas }
    }
    val ocultarLista = cuentas.isEmpty() || (cuentas.size == 1 && elegida != null)
    return SelectorDeCuentas(cuentas = cuentas, ocultarLista = ocultarLista)
}
