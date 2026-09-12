package com.jaac.avoqado_tpv.features.payment.presentation

/**
 * Lo que la terminal DICE y OFRECE cuando el cobro lo pidió el POS (C.5 / H.3, 11-sep-2026).
 *
 * Vive en un solo sitio y lo usan los dos rieles (Blumon en sus dos variantes y AngelPay) y las dos
 * pantallas: un texto distinto en cada lado es un texto que nadie revisa.
 */
internal object CobroRemotoDelPos {

    /** El POS canceló ANTES de que nada capaz de autorizar empezara, y la terminal lo aceptó. */
    const val CANCELADO_POR_EL_POS = "El POS canceló este cobro. No se cobró."

    /** Ya salió el desenlace final de esa solicitud: esta terminal no la vuelve a ejecutar. */
    const val SOLICITUD_CERRADA = "Este cobro ya se cerró. Pídele al POS que lo vuelva a enviar."

    /** El POS pidió cancelar cuando el cobro ya estaba en marcha: la terminal NO lo detiene. */
    const val CANCEL_TARDE = "El POS pidió cancelar, pero el cobro ya empezó: termina o cancela en la terminal."

    /** Nadie retomó el cobro: salió su desenlace y la solicitud queda cerrada para esta terminal. */
    const val CERRADO_POR_ABANDONO = "El cobro se cerró sin cobrar. Vuelve a cobrar desde el punto de venta."

    /**
     * 🔴 INTERINO — decisión de producto PENDIENTE (P2-9, auditoría del 11-sep).
     *
     * Efectivo y cripto de un cobro que pidió el POS son hoy una ruta de doble registro medida: la
     * terminal los cobra y los registra por su cuenta, pero el servidor sólo cierra la solicitud con
     * TARJETA, así que la fila queda UNKNOWN con el dinero ya cobrado y la tablet ofreciendo cobrar otra
     * vez. Mientras el founder decide (ocultarlos o aceptar CASH como cierre), se ocultan.
     *
     * Revertir es cambiar esta constante a `false`: la barrera durable
     * ([com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger.iniciarEjecucionNoTarjeta])
     * sigue protegiendo la carrera contra el cancel en los dos casos.
     */
    const val OCULTAR_EFECTIVO_Y_CRIPTO_EN_COBRO_REMOTO = true

    /** ¿Se ofrecen Efectivo y Cripto en esta pantalla? `paymentSource` == "SOCKET" ⇒ cobro del POS. */
    fun permiteEfectivoYCripto(paymentSource: String?): Boolean =
        !(OCULTAR_EFECTIVO_Y_CRIPTO_EN_COBRO_REMOTO && paymentSource == FUENTE_SOCKET)

    const val FUENTE_SOCKET = "SOCKET"
}
