package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt
import com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor

/**
 * Checkpoint 2 (diseño v3, E1–E4): lo que el SERVIDOR acreditó de UN intento, normalizado desde sus tres orígenes —
 * el aviso S5 (`terminal:payment_confirmed`), la consulta S6 (`GET …/attempts/:attemptId`) y el 2xx del REST— para que la
 * libreta lo aplique con UNA sola regla ([PaymentAttemptDao.aplicarVeredictoDelServidor]).
 *
 * `ganadorAcreditado` es el Payment que cerró la SOLICITUD, o null si este veredicto no lo acredita (colisión de
 * referencia, PENDING sin clasificar, `isWinner=false` sin `winnerPaymentId`): la bandeja sólo se resuelve con un ganador.
 */
data class VeredictoDeIntento(
    val venueId: String,
    val attemptId: String,
    /** La solicitud del POS según el origen (null si el origen no la conoce; entonces manda la columna de la fila). */
    val requestId: String?,
    val outcome: VeredictoDelServidor,
    /** El Payment de ESTE intento (nunca el de otro), o null si el servidor no tiene ninguno. */
    val paymentId: String?,
    /** `webhook` | `terminal`, tal como lo dijo el servidor — nunca inferido del canal por el que llegó. */
    val recordedVia: String,
    val amountCents: Long?,
    val tipCents: Long?,
    val ganadorAcreditado: String?,
    val fuente: Fuente,
) {
    enum class Fuente { SOCKET_S5, CONSULTA_S6, REST }

    /** Un veredicto FINAL y aprobado para este intento: la única prueba que libera (E1). */
    val esFinalAprobado: Boolean
        get() = outcome == VeredictoDelServidor.RECORDED ||
            (outcome == VeredictoDelServidor.SECOND_CAPTURE_EVIDENCE && ganadorAcreditado != null)

    companion object {
        /** El 2xx del REST (registrador en vivo, recuperación por aprobación o replay de la cola). */
        fun desdeRecibo(venueId: String, attemptId: String, requestId: String?, receipt: PaymentReceipt) = VeredictoDeIntento(
            venueId = venueId,
            attemptId = attemptId,
            requestId = requestId,
            outcome = receipt.veredictoDelServidor,
            paymentId = receipt.paymentId,
            recordedVia = receipt.serverRecordedVia,
            amountCents = receipt.amount.movePointRight(2).longValueExact(),
            tipCents = receipt.tipAmount.movePointRight(2).longValueExact(),
            ganadorAcreditado = receipt.ganadorAcreditado,
            fuente = Fuente.REST,
        )

        /**
         * S5: el servidor sólo lo emite cuando SU Payment cerró la solicitud (`primerConfirmador` exige `closedVia = webhook`
         * y el mismo `paymentId`), así que el Payment del aviso ES el ganador acreditado.
         */
        fun desdeAvisoS5(venueId: String, requestId: String, attemptId: String, paymentId: String, via: String, amountCents: Long, tipCents: Long) =
            VeredictoDeIntento(
                venueId = venueId,
                attemptId = attemptId,
                requestId = requestId,
                outcome = VeredictoDelServidor.RECORDED,
                paymentId = paymentId,
                recordedVia = if (via == "webhook") "webhook" else "terminal",
                amountCents = amountCents,
                tipCents = tipCents,
                ganadorAcreditado = paymentId,
                fuente = Fuente.SOCKET_S5,
            )

        /** S6: `attempt.outcome` tal cual; `NOT_RECORDED` o un outcome desconocido ⇒ null (nada que aplicar, sólo se estampa la consulta). */
        fun desdeConsultaS6(venueId: String, attemptId: String, respuesta: TerminalAttemptStatusResponse): VeredictoDeIntento? {
            val intento = respuesta.attempt ?: return null
            val outcome = VeredictoDelServidor.porNombre(intento.outcome) ?: return null
            val paymentId = intento.paymentId?.takeIf { it.isNotBlank() } ?: return null
            val ganador = when (outcome) {
                VeredictoDelServidor.RECORDED -> paymentId.takeIf { intento.isWinner == true }
                VeredictoDelServidor.SECOND_CAPTURE_EVIDENCE -> intento.winnerPaymentId?.takeIf { it.isNotBlank() }
                VeredictoDelServidor.REFERENCE_COLLISION_EVIDENCE, VeredictoDelServidor.PENDING_EVIDENCE -> null
            }
            return VeredictoDeIntento(
                venueId = venueId,
                attemptId = attemptId,
                requestId = respuesta.requestId?.takeIf { it.isNotBlank() },
                outcome = outcome,
                paymentId = paymentId,
                recordedVia = if (intento.recordedVia == "webhook") "webhook" else "terminal",
                amountCents = intento.amountCents,
                tipCents = intento.tipCents,
                ganadorAcreditado = ganador,
                fuente = Fuente.CONSULTA_S6,
            )
        }
    }
}

/** Resultado VERIFICABLE de aplicar un veredicto (E1): lo consumen las pruebas, la pantalla y el emisor del socket. */
data class ResultadoDelVeredicto(
    val decision: Decision,
    /** La fila pasó a REGISTRADO en esta aplicación. */
    val transiciono: Boolean,
    /** La bandeja quedó RESOLVED con `success` en esta aplicación (o se enriqueció): hay que EMITIR ese JSON después del commit. */
    val bandejaResueltaJson: String?,
    /** El predicado derivado de contradicción, evaluado sobre la fila tras la escritura. */
    val contradiccion: Boolean,
) {
    enum class Decision {
        /** Evidencia guardada; la fila pasó (o ya estaba) en REGISTRADO. */
        APLICADO,
        /** Evidencia guardada; la fila conserva su estado y su retención (veredicto no final, montos distintos, estado no elegible…). */
        GUARDADO_SIN_LIBERAR,
        /** La fila ya tiene OTRO `server_payment_id`: no se pisa. */
        RECHAZADO_OTRO_PAYMENT,
        /** Mismo Payment con datos distintos (outcome, origen, montos): no se pisa. */
        RECHAZADO_DATOS_DISTINTOS,
        /** La fila es de OTRO venue o de OTRA solicitud: un veredicto que no le pertenece (anomalía; no se pisa nada). */
        RECHAZADO_PERTENENCIA,
        /**
         * Codex (código, P1-2/P1-6): la fila existe pero queda FUERA del checkpoint 2 — heredada (`legacy_shadow`), de otro
         * procesador (Blumon/PAX: port posterior) o una devolución. No es un rechazo: la cola y la recuperación por aprobación
         * siguen el camino que tenían ANTES del checkpoint.
         */
        FUERA_DE_ALCANCE,
        /** No hay fila para ese intento en esta terminal. */
        SIN_FILA,
    }
}

/**
 * La SOLICITUD (no el intento) quedó NOT_CHARGED por evidencia del SERVIDOR: ventana vencida o declaración del cajero.
 * Lleva la solicitud que el servidor liberó ([requestId]): la fila sólo se cierra si es SUYA (pertenencia, Task 7 · B) —
 * una respuesta de otra solicitud, cruzada o atrasada, nunca acredita «no se cobró» sobre este intento.
 */
data class LiberacionDelServidor(val venueId: String, val attemptId: String, val requestId: String, val evidencia: String) {
    companion object {
        val EVIDENCIAS = setOf("NO_EVIDENCE_AFTER_WINDOW", "OPERATOR_RECONCILED")

        /**
         * Evidencia POSITIVA del servidor sobre el INTENTO (Task 7 · fix 2, P1-2): un outcome con dinero (los cuatro de
         * [PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO], con o sin `paymentId`) o `processorEvidence = APPROVED` — el banco
         * aprobó (tarde, con otro importe…) y el servidor conserva ese evento aunque no haya Payment ni reabra la liberación.
         * Con ella NINGUNA liberación se aplica: ni la de S6 ni la sintetizada del 2xx de la declaración. `DECLINED`/`NONE` no
         * vetan. ÚNICO punto que decide esto: los dos parsers de abajo y `LedgerServerRecovery.recoverOne` lo consultan aquí.
         */
        fun acreditaDinero(intento: TerminalAttemptResultDto?): Boolean =
            intento != null && (intento.outcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO || intento.processorEvidence == "APPROVED")

        fun desdeConsultaS6(venueId: String, attemptId: String, respuesta: TerminalAttemptStatusResponse): LiberacionDelServidor? {
            if (acreditaDinero(respuesta.attempt)) return null // el dinero (o una aprobación conocida) manda
            val requestId = respuesta.requestId?.takeIf { it.isNotBlank() } ?: return null // sin solicitud no hay pertenencia que comprobar
            val request = respuesta.request ?: return null
            val outcome = runCatching { request.get("outcome")?.asString }.getOrNull()
            val evidencia = runCatching { request.get("outcomeEvidence")?.asString }.getOrNull()
            if (outcome != "NOT_CHARGED" || evidencia !in EVIDENCIAS) return null
            return LiberacionDelServidor(venueId, attemptId, requestId, evidencia!!)
        }

        /**
         * El 2xx de la declaración del cajero (`POST …/no-instrument-resolution`): por contrato el servidor sólo contesta 200
         * cuando liberó la solicitud como `OPERATOR_RECONCILED`, así que la liberación se sintetiza de la respuesta misma —
         * pero con el MISMO veto que S6: si el cuerpo acredita dinero del intento, no hay liberación (null).
         */
        fun desdeDeclaracion(venueId: String, attemptId: String, requestId: String, respuesta: TerminalAttemptStatusResponse?): LiberacionDelServidor? =
            if (acreditaDinero(respuesta?.attempt)) null else LiberacionDelServidor(venueId, attemptId, requestId, "OPERATOR_RECONCILED")
    }
}
