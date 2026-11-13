package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility

/**
 * Qué cuentas ofrece «Método de pago» de la Nexgo, si la lista se puede ocultar, si la cuenta elegida puede cobrar esta
 * venta y a cuál debe pasar sola.
 */
internal data class SelectorDeCuentas(
    val cuentas: List<MerchantAccount>,
    val ocultarLista: Boolean,
    /** La cuenta elegida puede cobrar esta venta (sin reglas que filtren, siempre). Con `false`, «Tarjeta» no arranca. */
    val elegidaPermitida: Boolean = true,
    /** A qué cuenta pasa sola la Nexgo (por `selectMerchant`, el camino del toque); null si no hay nada que cambiar. */
    val cuentaAPasar: MerchantAccount? = null,
)

/**
 * 🧭 MERCHANT_ROUTING_RULES en la Nexgo (D3, 12/13-nov). Regla del founder (13-nov): **nunca se cobra en una cuenta que la
 * regla excluye**.
 *
 * - **Qué cuentas:** con reglas que filtran, sólo las permitidas; sin reglas, sin red o si ninguna permitida está cargada,
 *   todas (una regla nunca frena una venta) y nada cambia solo.
 * - **A cuál pasa sola:** si la elegida no está entre las permitidas (o no hay ninguna), con el criterio de la PAX
 *   (`proceedToMerchantSelection`): la que la regla marca para preseleccionar y, si no marca ninguna cargada, la primera
 *   permitida en el orden de la lista. La elegida se compara por `id`: la lista se vuelve a emitir desde la base.
 * - **Cuándo se oculta la lista:** sólo si no hay cuentas, o si queda UNA y ya es la elegida. Con la elegida excluida o sin
 *   elegir, la lista se queda a la vista para que, si el cambio automático no se puede, la cajera toque la permitida
 *   (Codex r1 #1: ocultarla dejaba «Tarjeta» apagada sin salida).
 * - **[sesionPideElegir]** (la sesión de AngelPay espera que se elija comercio): la elegida en pantalla todavía no está fijada
 *   en la sesión, así que no cuenta como lista para cobrar; si es una permitida, a ella se pasa (completa la elección), y la
 *   lista se queda a la vista. Sin esto, en app-a-app quedaba una permitida «elegida», la lista oculta y «Tarjeta»
 *   esperando un comercio que nadie fijaba (Codex r4 #2).
 */
internal fun selectorDeCuentas(
    todas: List<MerchantAccount>,
    reglas: MerchantEligibility?,
    elegida: MerchantAccount?,
    sesionPideElegir: Boolean = false,
): SelectorDeCuentas {
    val permitidas = if (reglas == null || reglas.shouldShowAll) {
        emptyList()
    } else {
        todas.filter { reglas.eligibleMerchantAccountIds.contains(it.merchantAccountId) }
    }
    if (reglas == null || permitidas.isEmpty()) {
        return SelectorDeCuentas(
            cuentas = todas,
            ocultarLista = todas.isEmpty() || (todas.size == 1 && elegida != null),
        )
    }
    val elegidaEntreLasPermitidas = elegida?.let { e -> permitidas.firstOrNull { it.id == e.id } }
    val elegidaPermitida = elegidaEntreLasPermitidas != null && !sesionPideElegir
    val cuentaAPasar = when {
        elegidaPermitida -> null
        elegidaEntreLasPermitidas != null -> elegidaEntreLasPermitidas
        else -> permitidas.firstOrNull { it.merchantAccountId == reglas.autoSelectMerchantAccountId } ?: permitidas.first()
    }
    return SelectorDeCuentas(
        cuentas = permitidas,
        ocultarLista = permitidas.size == 1 && elegidaPermitida,
        elegidaPermitida = elegidaPermitida,
        cuentaAPasar = cuentaAPasar,
    )
}
