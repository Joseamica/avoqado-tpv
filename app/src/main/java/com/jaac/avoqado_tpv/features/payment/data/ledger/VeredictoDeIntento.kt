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
    /**
     * 🔴 Ronda 21 (Codex r19, P1-1): el VETO que la MISMA respuesta publica (`paymentContradiction`, `evidenceContradiction`,
     * `unattributedEvidence`). El servidor calcula el desenlace del Payment y la contradicción por separado, así que una
     * respuesta puede traer las dos cosas; aplicado sin el veto, la fila quedaba RECORDED y limpia y el aviso ofrecía
     * «Entendido» sobre un cobro que el propio servidor contradice. Se escribe en la MISMA transacción que el veredicto.
     */
    val veto: String? = null,
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
                veto = LiberacionDelServidor.motivoDelVeto(intento),
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
    /**
     * Codex r10/r11: el veredicto QUEDÓ en la fila. Las dos «RECHAZADO_» de datos cuentan porque la fila YA trae dinero del
     * servidor (otro `server_payment_id`, o el mismo con otros datos) y eso la protege; las demás no escribieron nada, y quien
     * aplica tiene que dejar la evidencia por su cuenta. No lanzar no es haberlo guardado.
     */
    val quedoEnLaFila: Boolean get() = when (decision) {
        Decision.APLICADO, Decision.GUARDADO_SIN_LIBERAR, Decision.RECHAZADO_OTRO_PAYMENT, Decision.RECHAZADO_DATOS_DISTINTOS -> true
        Decision.RECHAZADO_PERTENENCIA, Decision.FUERA_DE_ALCANCE, Decision.SIN_FILA -> false
    }

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
data class LiberacionDelServidor(val venueId: String, val attemptId: String, val requestId: String?, val evidencia: String) {
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
            intento != null &&
                (intento.outcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO ||
                    intento.processorEvidence == "APPROVED" ||
                    // 🔴 Codex r5-3 (22-sep): un `Payment` de ESTE intento que el servidor publica con estado NO final
                    // (PENDING, PROCESSING) es dinero en vuelo — el veto del POST lo rechaza — y aquí no se miraba:
                    // el outcome sigue siendo NOT_RECORDED y los tres avisos pueden estar apagados, así que el cliente
                    // liberaba. Hay un pago identificado: eso basta para NO decirle al cajero que puede volver a cobrar.
                    intento.paymentId != null)

        /**
         * 🔴 Pieza C (22-sep): el servidor publica en `attempt.unattributedEvidence` que existe evidencia del procesador
         * para este intento que NO pudo atribuir a nadie (sin vínculo y sin serial). No cambia el desenlace —inventarle
         * dueño sería peor— pero mientras exista, NINGUNA liberación puede aplicarse: es exactamente el caso del
         * aprobado tardío que llega sin número de serie después de que el cajero declaró (Codex P1-3).
         */
        private fun hayEvidenciaSinDueno(intento: TerminalAttemptResultDto?): Boolean = intento?.unattributedEvidence == true

        /**
         * 🔴 Codex r4-3: la decisión de liberar conserva TODOS los vetos que el servidor publica, no sólo el dinero
         * propio. `paymentContradiction` significa «hay un Payment con esta llave que no es atribuible a este intento»
         * (otra solicitud, otra terminal, otro negocio, llave sucia) y `evidenceContradiction`, «hay evidencia del
         * procesador con el serial de otra terminal». Ninguno acredita dinero PROPIO —por eso no entran en
         * `acreditaDinero`— pero los dos son motivo de sobra para NO decirle al cajero que puede volver a cobrar.
         */
        private fun hayContradiccion(intento: TerminalAttemptResultDto?): Boolean =
            intento?.paymentContradiction == true || intento?.evidenceContradiction == true

        /**
         * 🔴 Codex r5-4: qué veto publica el servidor sobre este intento, para hacerlo DURABLE. Devuelve el primero
         * que aplique —cuál fue da igual, lo que importa es que algo contradice— o `null` si la respuesta está limpia.
         * Único punto que traduce los avisos de S6 a un motivo guardable.
         */
        fun motivoDelVeto(intento: TerminalAttemptResultDto?): String? = when {
            intento == null -> null
            intento.paymentContradiction == true -> PaymentAttemptEntity.VETO_PAYMENT_CONTRADICTION
            intento.evidenceContradiction == true -> PaymentAttemptEntity.VETO_EVIDENCE_CONTRADICTION
            intento.unattributedEvidence == true -> PaymentAttemptEntity.VETO_UNATTRIBUTED_EVIDENCE
            else -> null
        }

        fun desdeConsultaS6(venueId: String, attemptId: String, respuesta: TerminalAttemptStatusResponse): LiberacionDelServidor? {
            if (acreditaDinero(respuesta.attempt)) return null // el dinero (o una aprobación conocida) manda
            if (hayEvidenciaSinDueno(respuesta.attempt)) return null // algo lo contradice y nadie puede reclamarlo: no se libera
            if (hayContradiccion(respuesta.attempt)) return null // hay dinero o evidencia que no es de este intento: tampoco

            // ── Cobro LOCAL (22-sep): iniciado EN la terminal, sin solicitud del POS ────────────────────────────
            // Su liberación NO puede viajar en `request.outcome` porque no hay `request`. El servidor publica la
            // declaración del cajero en `attempt.resolution`, y ésa es la única señal que destraba este caso.
            val requestIdCrudo = respuesta.requestId?.takeIf { it.isNotBlank() }
            if (requestIdCrudo == null && respuesta.request == null) {
                val declaracion = respuesta.attempt?.resolution ?: return null
                val evidencia = evidenciaDeLaDeclaracion(runCatching { declaracion.get("kind")?.asString }.getOrNull()) ?: return null
                // `requestId` nulo = «sin solicitud»: es lo que manda la fila local al CAS, que exige IS NULL.
                return LiberacionDelServidor(venueId, attemptId, null, evidencia)
            }

            val requestId = requestIdCrudo ?: return null // sin solicitud no hay pertenencia que comprobar
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
        fun desdeDeclaracion(venueId: String, attemptId: String, requestId: String?, respuesta: TerminalAttemptStatusResponse?): LiberacionDelServidor? =
            if (acreditaDinero(respuesta?.attempt) || hayEvidenciaSinDueno(respuesta?.attempt) || hayContradiccion(respuesta?.attempt)) null
            // Ronda 20: la declaración AUTOMÁTICA de la terminal se guarda como liberación por ventana (no la firmó una persona).
            // Sin clase (un servidor anterior) o con la del cajero, lo de siempre.
            else LiberacionDelServidor(
                venueId, attemptId, requestId,
                evidenciaDeLaDeclaracion(runCatching { respuesta?.attempt?.resolution?.get("kind")?.asString }.getOrNull()) ?: "OPERATOR_RECONCILED",
            )

        /**
         * Ronda 20: qué liberación representa cada testimonio que publica el servidor. `NO_INSTRUMENT_PRESENTED` es la palabra del
         * CAJERO; `NO_BANK_TRACE_AFTER_WINDOW` la de la TERMINAL (esperó el aviso del banco y no llegó, donde el servidor lo tiene
         * comprobado). Una clase desconocida no libera nada.
         */
        fun evidenciaDeLaDeclaracion(kind: String?): String? = when (kind) {
            "NO_INSTRUMENT_PRESENTED" -> "OPERATOR_RECONCILED"
            "NO_BANK_TRACE_AFTER_WINDOW" -> "NO_EVIDENCE_AFTER_WINDOW"
            else -> null
        }
    }
}
