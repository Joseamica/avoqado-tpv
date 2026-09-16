package com.jaac.avoqado_tpv.features.payment.domain.model

import java.math.BigDecimal

/**
 * Recibo digital generado por el backend después de grabar un pago.
 *
 * El backend genera automáticamente un DigitalReceipt para cada Payment
 * con un access key único que permite acceso público sin autenticación.
 *
 * **Campos:**
 * - paymentId: ID único del Payment en database
 * - receiptUrl: URL pública del recibo (ej. https://api.avoqado.io/api/v1/public/receipt/{accessKey})
 * - accessKey: Token seguro para acceder al recibo sin login
 * - amount: Monto BASE pagado (subtotal SIN tip - así lo guarda el backend)
 * - tipAmount: Propina incluida (para confirmación rápida en UI)
 *
 * **IMPORTANTE - Backend Schema:**
 * El backend guarda `payment.amount = subtotal` (sin tip) y `payment.tipAmount = tip` por separado.
 * Por eso `totalAmount = amount + tipAmount` y `baseAmount = amount`.
 *
 * **Uso:**
 * ```kotlin
 * // Después de pago exitoso
 * val receipt = recordPaymentUseCase(...)
 * receipt.onSuccess { receipt ->
 *     // Mostrar recibo en app
 *     displayReceipt(receipt.receiptUrl)
 *
 *     // O enviar por email/SMS
 *     sendReceiptEmail(receipt.receiptUrl)
 * }
 * ```
 *
 * **Seguridad:**
 * - accessKey es único y no guessable (CUID)
 * - Recibo no expone información sensible de tarjeta
 * - URL es pública pero solo conocida por quien recibe el link
 *
 * @param paymentId ID del payment en database (ej. "clxxx...")
 * @param receiptUrl URL completa del recibo público
 * @param accessKey Token de acceso único (CUID)
 * @param amount Monto BASE pagado (subtotal SIN propina)
 * @param tipAmount Propina incluida en el pago
 */
data class PaymentReceipt(
    val paymentId: String,
    val receiptUrl: String,
    val accessKey: String,
    val amount: BigDecimal,
    val tipAmount: BigDecimal,
    val autofacturaAvailable: Boolean = false,
    /**
     * Checkpoint 2 (Codex P1-2, 16-sep): el 2xx del REST NO siempre es una venta registrada. Una SEGUNDA CAPTURA o una
     * COLISIÓN de referencia llegan como Payment `PENDING` con `processorData.reconciliation.kind`; un servidor anterior
     * no manda `status` (⇒ null ⇒ RECORDED, el comportamiento de hoy). Aditivos: todos los constructores viejos compilan.
     */
    val serverStatus: String? = null,
    val reconciliationKind: String? = null,
    /** En una segunda captura, el Payment que SÍ ganó la solicitud (el dinero de este intento se concilia). */
    val winnerPaymentId: String? = null,
    /**
     * Quién creó el Payment según el SERVIDOR (`processorData.registradoVia`): `webhook` cuando el webhook fue el primer
     * confirmador, `terminal` en cualquier otro caso. Nunca se infiere del canal por el que llegó este recibo.
     */
    val serverRecordedVia: String = "terminal",
    /**
     * N0b (Codex, diseño v3 cambio 3): la solicitud POS→terminal que este Payment CERRÓ, según la columna que el servidor
     * sólo escribe al ligar. Null = registrado pero no ligado (o servidor anterior): «COMPLETED no basta para acreditar al
     * ganador» — sin identidad de terminal o con una asociación inválida el servidor registra el cobro y deja la solicitud
     * pendiente a propósito.
     */
    val solicitudLigada: String? = null,
) {
    /**
     * El Payment que cerró la SOLICITUD, si este recibo lo acredita: COMPLETED **y ligado** (`solicitudLigada`), o el ganador
     * que el servidor nombra en una segunda captura. Null = este recibo NO acredita ningún ganador (COMPLETED sin ligar,
     * colisión, PENDING sin clasificar): la bandeja conserva su obligación.
     */
    val ganadorAcreditado: String?
        get() = when (veredictoDelServidor) {
            VeredictoDelServidor.RECORDED -> paymentId.takeIf { !solicitudLigada.isNullOrBlank() }
            VeredictoDelServidor.SECOND_CAPTURE_EVIDENCE -> winnerPaymentId?.takeIf { it.isNotBlank() }
            VeredictoDelServidor.REFERENCE_COLLISION_EVIDENCE, VeredictoDelServidor.PENDING_EVIDENCE -> null
        }

    /** Qué acreditó el servidor sobre ESTE cobro. Sólo [VeredictoDelServidor.RECORDED] es una venta normal. */
    val veredictoDelServidor: VeredictoDelServidor
        get() = VeredictoDelServidor.de(serverStatus, reconciliationKind)

    /**
     * Monto total del pago (base + propina).
     * ✅ FIX: Backend guarda subtotal en `amount`, tip en `tipAmount` por separado.
     */
    val totalAmount: BigDecimal
        get() = amount + tipAmount

    /**
     * Monto sin propina (solo base payment).
     * ✅ FIX: `amount` del backend YA ES el base (subtotal).
     */
    val baseAmount: BigDecimal
        get() = amount

    /**
     * Verifica si el pago incluye propina.
     */
    val hasTip: Boolean
        get() = tipAmount > BigDecimal.ZERO
}

/**
 * Veredicto del SERVIDOR sobre un intento de cobro. Mismos nombres que `AttemptOutcome` de S6 en el servidor
 * (`GET tpv/venues/:venueId/terminal-payment/attempts/:attemptId`) y que `server_outcome` en la libreta.
 *
 * 🔴 Ninguno de los tres «evidencia» es una venta normal: el dinero se movió en el banco y el servidor lo CONSERVA,
 * pero como evidencia que Avoqado concilia — la terminal no lo presenta como cobro exitoso ni lo vuelve a cobrar.
 */
enum class VeredictoDelServidor {
    RECORDED,
    SECOND_CAPTURE_EVIDENCE,
    REFERENCE_COLLISION_EVIDENCE,
    /** Un Payment que no es COMPLETED y cuya `reconciliation.kind` no se reconoce: evidencia sin clasificar. */
    PENDING_EVIDENCE;

    val esVentaNormal: Boolean get() = this == RECORDED

    companion object {
        /**
         * `status` ausente (servidor anterior a S3) ⇒ RECORDED, que es exactamente lo que la terminal asumía hasta hoy.
         * `status` presente y distinto de COMPLETED ⇒ evidencia, clasificada por `reconciliation.kind`.
         */
        fun de(serverStatus: String?, reconciliationKind: String?): VeredictoDelServidor = when {
            serverStatus == null || serverStatus.equals("COMPLETED", ignoreCase = true) -> RECORDED
            reconciliationKind == "POSSIBLE_SECOND_CAPTURE" -> SECOND_CAPTURE_EVIDENCE
            reconciliationKind == "POSSIBLE_REFERENCE_COLLISION" -> REFERENCE_COLLISION_EVIDENCE
            else -> PENDING_EVIDENCE
        }

        /** El nombre del veredicto de S6 (`attempt.outcome`) o de `server_outcome`; desconocido ⇒ null (nunca se inventa). */
        fun porNombre(nombre: String?): VeredictoDelServidor? = entries.firstOrNull { it.name == nombre }
    }
}
