package com.jaac.avoqado_tpv.features.checkout.domain.model

/**
 * 💵 «Cobrar» sin red: una venta armada en la terminal que se guarda como UNA fila de `pending_payments` y sube sola después.
 *
 * La [llave] es todo: es el `externalId` de la orden (mobile), la `idempotencyKey` del pago y la base de la referencia de efectivo.
 * [pedidoJson] es el cuerpo de `POST mobile/venues/{v}/orders` TAL CUAL se armó (en centavos): al subir viaja byte a byte, sin
 * re-armarlo con un catálogo más nuevo. [renglones] son los del ticket, con las mismas llaves por renglón que el pedido.
 *
 * Gson-serializable (entra en el `SavedStateHandle` como `"ventaLocalJson"` y sobrevive a que se recree la pantalla; un proceso
 * nuevo no la restaura, ver `EstadoDelProceso`, y lo que cuenta es la fila ya guardada): sólo tipos simples, nada sellado.
 */
data class VentaLocal(
    val llave: String,
    val venueId: String,
    val staffId: String,
    val shiftId: String?,
    /** Epoch ms del aparato al CAPTURAR la venta. */
    val createdAtLocal: Long,
    val totalCents: Int,
    val pedidoJson: String,
    val renglones: List<RenglonDeTicket>,
    val shiftMembership: String? = "UNKNOWN",
    val localShiftSessionId: String? = null,
    val cashDrawerSessionId: String? = null,
)

/** Un renglón del ticket de una venta guardada. */
data class RenglonDeTicket(
    /** `"<llave>:<i>"`: el MISMO que viaja en el pedido, para comparar renglón contra renglón con la respuesta del servidor. */
    val externalId: String,
    val nombre: String,
    val cantidad: Int,
    /** Lo que cuesta el renglón con cortesía aplicada (en cortesía, 0). */
    val totalCents: Int,
    /** (precio + extras) × cantidad antes de descuentos; en cortesía NO es 0. */
    val brutoCents: Int,
    val extras: List<ExtraDeTicket>,
    val nota: String?,
)

/** Un extra (modificador) elegido en un renglón. [tipo] es `ModifierType.name`. */
data class ExtraDeTicket(
    val id: String,
    val nombre: String,
    val precioCents: Int,
    val tipo: String,
)

/** La confirmación pendiente del cajero, reconstruida del ticket durable. */
data class VentaGuardadaVista(val venta: VentaLocal, val propinaCents: Int = 0)
