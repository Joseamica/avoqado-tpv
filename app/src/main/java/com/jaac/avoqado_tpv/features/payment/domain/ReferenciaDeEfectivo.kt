package com.jaac.avoqado_tpv.features.payment.domain

/**
 * La referencia de un cobro en efectivo sale de la LLAVE del intento, no del reloj.
 *
 * Antes era `CASH-${System.currentTimeMillis()}`, acuñada dentro de cada corrutina: en una ráfaga salían N referencias distintas a
 * ~5 ms y la defensa por `referenceNumber` del servidor no veía nada. Derivada de la llave, un reintento del MISMO intento manda la
 * MISMA referencia. Conserva el prefijo `CASH-`, que el servidor, la cola offline (`QueuedPayment.isCashQueuedPayment()`) y el
 * ticket ya reconocen. El kiosco usa `CASH-KIOSK-`.
 *
 * Una sola función para las dos PaymentViewModel (production y sandbox) y para la venta local de «Cobrar» sin red.
 */
fun referenciaDeEfectivo(llave: String, prefijo: String = "CASH-"): String =
    prefijo + llave.replace("-", "").takeLast(16)
