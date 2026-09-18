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

    // ── §3.8 (rechazo MUDO, 18-sep): la barrera de la libreta rechazó un cobro que mandó el POS. NO se inició nada: se
    //    dice AL INSTANTE (`failed + PRE_AUTHORIZATION`, verificado por la bandeja en su transacción) en vez de dejar a la
    //    tablet «esperando a la terminal» hasta que el servidor retenga la ranura como UNKNOWN (medido el 17-sep 21:45). ──

    /** Lo que ve la tablet cuando la terminal está apartada por un cobro anterior sin confirmar. */
    const val NO_INICIADO_POR_COBRO_PENDIENTE =
        "La terminal tiene un cobro anterior sin confirmar: este cobro NO se inició. Resuélvelo en la terminal o cobra con otra."

    /** Lo que ve la tablet cuando la terminal no puede nombrar qué la aparta (p. ej. no pudo guardar el intento). */
    const val NO_INICIADO_SIN_DETALLE =
        "La terminal no pudo iniciar este cobro (tiene un cobro pendiente o no pudo guardar el intento): este cobro NO se inició."

    /** La pantalla de la terminal, NOMBRANDO la fila que la aparta («$10.00, hace 2 min») cuando se conoce. */
    fun noIniciadoEnLaTerminal(queLaAparta: String?): String = if (queLaAparta != null) {
        "Este cobro del POS NO se inició: la terminal tiene un cobro anterior sin confirmar ($queLaAparta). " +
            "Resuélvelo o cobra con otra terminal."
    } else {
        "Este cobro del POS NO se inició: la terminal tiene un cobro pendiente o no pudo guardar el intento. No se cobró."
    }

    // ── Checkpoint 2 · N1 (16-sep): la decisión del servidor sobre el vínculo intento→solicitud, ANTES del SDK ──
    /** `NOT_OWNER`: el servidor no acredita que ESTA conexión sea la dueña de la solicitud. Nunca se toca el SDK. */
    const val NO_ES_LA_DUENA = "No se pudo verificar que esta terminal sea la dueña de este cobro. No se inició ningún cobro. " +
        "Vuelve a enviarlo desde el POS; si se repite, cierra sesión y vuelve a entrar en la terminal."

    /** La solicitud ya no es ejecutable en el servidor (cancelada, vencida, sin resolver): no se inició OTRO cobro. */
    fun noEjecutable(requestStatus: String?, yaCobrada: Boolean): String = if (yaCobrada) {
        "Este cobro ya está registrado en el POS. No se inició otro cobro."
    } else {
        "Este cobro ya no está activo en el POS (estado ${requestStatus ?: "desconocido"}). No se inició otro cobro."
    }

    /** `ATTEMPT_OWNED_BY_OTHER_REQUEST` / `INVALID`: la llave del intento no sirvió; el reintento abre una NUEVA. */
    const val LLAVE_RECHAZADA = "No se pudo iniciar el cobro (la llave del intento fue rechazada). No se cobró. Inténtalo de nuevo."

    /** E4: una segunda captura con ganador acreditado — el banco aprobó, pero NO es una venta más. */
    const val SEGUNDA_CAPTURA = "Avoqado registró este cobro como posible cobro DOBLE y lo concilia. No lo vuelvas a cobrar."

    /**
     * Codex (código, P1-1): el REST registró el cobro pero la libreta NO pudo dar el veredicto por bueno (importes distintos
     * a los de esta terminal, otro Payment para la misma llave, o datos que contradicen lo ya guardado): la pantalla no
     * afirma «cobrado» a secas y no reporta `success` — el servidor arbitra y Avoqado lo concilia.
     */
    const val REGISTRADO_CON_DISCREPANCIA = "El banco aprobó y Avoqado registró el cobro, pero con datos distintos a los de esta terminal. " +
        "No lo vuelvas a cobrar: Avoqado lo concilia. Revisa Transacciones antes de intentar otra vez."

    /** E4: evidencia sin ganador acreditado (colisión de referencia o pendiente sin clasificar). */
    const val EVIDENCIA_SIN_GANADOR = "El banco aprobó; Avoqado conserva el cobro como evidencia y lo concilia. No lo vuelvas a cobrar."

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
