package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.JsonObject
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
data class LiberacionDelServidor(
    val venueId: String,
    val attemptId: String,
    val requestId: String?,
    val evidencia: String,
    /** D22: quién declaró, según el testimonio que publica el servidor (sólo con [CAJERO_REVISO]). */
    val quien: String? = null,
) {
    companion object {
        val EVIDENCIAS = setOf("NO_EVIDENCE_AFTER_WINDOW", "OPERATOR_RECONCILED")

        /**
         * 🔴 D22 (founder, 17-nov): la declaración «revisé la terminal y no se cobró» de un Pago rápido que Avoqado guardó
         * (`UNCHARGED_VERIFIED`). No es [EVIDENCIAS]: la solicitud del POS nunca la trae (el servidor la rechaza con solicitud) y la
         * libreta la cierra como la declaración local del cajero, no como «no presentó tarjeta».
         */
        const val CAJERO_REVISO = "CASHIER_CHECKED"

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

        /**
         * 🔴 F1 cierre TPV (I4, `plan-cierre-familias.md` §2): EL ÚNICO que decide si un acuse del SERVIDOR libera ESTA fila. Todo el que
         * libera pasa por aquí —S6 ([desdeConsultaS6]), el GET por solicitud ([desdeConsultaPorSolicitud]) y el 2xx de una declaración
         * ([desdeDeclaracion]: la liberación sola, la N86; y la firma del gerente en `BlumonAttemptResolver.declareChecked`)— y lo aplica
         * el único escritor ([PaymentAttemptLedger.aplicarLiberacionDelServidor]), que vuelve a comprobar la fila DENTRO del UPDATE
         * (estado, pertenencia, dinero, veto y cese de la PAX). Nadie libera con un nombre suelto.
         *  · Sin dinero ni veto en lo que el servidor sabe del intento ([intento]).
         *  · Alcance: el comprobante, si viene, es de ESTE intento o de ESTA solicitud.
         *  · Clase: `OPERATOR_RECONCILED` o [CAJERO_REVISO] son de una PERSONA: con comprobante, tiene que acreditarla con una clase de
         *    persona y el autor es el SUYO. `NO_EVIDENCE_AFTER_WINDOW` (la ventana, de la terminal o del servidor) no pide autor ni se
         *    vuelve firma: [quien] queda vacío. El rechazo del banco no libera por acuse (lo decide el resultado del SDK), salvo el que el
         *    SERVIDOR publica en S6 sobre ESTE intento ([desdeRechazoDelBanco], Codex final r6 #1); cualquier otra clase, tampoco.
         * @param testimonio la declaración que publica el intento (`attempt.resolution`), para la regla vieja; null en la ruta por solicitud.
         */
        fun desdeAcuse(
            venueId: String, attemptId: String, requestId: String?, evidencia: String?,
            comprobante: ComprobanteDeDeclaracion?, testimonio: JsonObject?, intento: TerminalAttemptResultDto?,
        ): LiberacionDelServidor? {
            if (acreditaDinero(intento) || hayEvidenciaSinDueno(intento) || hayContradiccion(intento)) return null
            if (comprobante != null && !comprobante.esDe(attemptId, requestId)) return null
            return when (evidencia) {
                // 🔴 F1 · salida del gerente para el 05AP (20-nov): la ventana del SERVIDOR soltó la solicitud y, encima, un gerente firmó
                // con su PIN (el SERVIDOR guarda su asiento sin cambiar el estado financiero). Con el comprobante de una PERSONA para ESTA
                // solicitud, la liberación es la suya: la fila queda cerrada por una persona y su tarjeta entra de inmediato; el historial
                // de AngelPay se sigue consultando igual (Codex final r8 #3). Sin ese comprobante, sigue siendo sólo la ventana.
                "NO_EVIDENCE_AFTER_WINDOW" -> comprobante
                    ?.takeIf { requestId != null && it.kind in DECLARACIONES_DE_UNA_PERSONA && it.acreditaPersona(attemptId, requestId) }
                    ?.let { LiberacionDelServidor(venueId, attemptId, requestId, "OPERATOR_RECONCILED", it.staffId) }
                    ?: LiberacionDelServidor(venueId, attemptId, requestId, evidencia)
                "OPERATOR_RECONCILED", CAJERO_REVISO -> {
                    val quien = when {
                        comprobante != null -> comprobante.staffId
                            ?.takeIf { comprobante.kind in DECLARACIONES_DE_UNA_PERSONA && comprobante.acreditaPersona(attemptId, requestId) }
                            ?: return null
                        // ponytail: SERVIDOR anterior a `declarationReceipt` (avoqado-server 70ca51c5f, F1 bloque 7): la regla de antes —el
                        // testimonio con el `by` de una persona; sin testimonio (la ruta por solicitud), la evidencia sola. Se quita cuando
                        // ese SERVIDOR ya esté publicado.
                        testimonio != null -> quienDeclaroElAcuse(testimonio) ?: return null
                        else -> null
                    }
                    LiberacionDelServidor(venueId, attemptId, requestId, evidencia, quien)
                }
                else -> null
            }
        }

        fun desdeConsultaS6(venueId: String, attemptId: String, respuesta: TerminalAttemptStatusResponse): LiberacionDelServidor? {
            // ── Cobro LOCAL (22-sep): iniciado EN la terminal, sin solicitud del POS ────────────────────────────
            // Su liberación NO puede viajar en `request.outcome` porque no hay `request`. El servidor publica la
            // declaración en `attempt.resolution`, y ésa es la única señal que destraba este caso.
            val requestIdCrudo = respuesta.requestId?.takeIf { it.isNotBlank() }
            if (requestIdCrudo == null && respuesta.request == null) {
                val declaracion = respuesta.attempt?.resolution ?: return null
                // `requestId` nulo = «sin solicitud»: es lo que manda la fila local al CAS, que exige IS NULL.
                return desdeAcuse(
                    venueId, attemptId, null, evidenciaDeLaDeclaracion(textoDe(declaracion, "kind")),
                    respuesta.declarationReceipt, declaracion, respuesta.attempt,
                )
            }

            val requestId = requestIdCrudo ?: return null // sin solicitud no hay pertenencia que comprobar
            val request = respuesta.request ?: return null
            if (textoDe(request, "outcome") != "NOT_CHARGED") return null
            val evidenciaCruda = textoDe(request, "outcomeEvidence")
            if (evidenciaCruda == RECHAZO_DEL_BANCO) return desdeRechazoDelBanco(venueId, attemptId, requestId, respuesta.attempt)
            val evidencia = evidenciaCruda?.takeIf { it in EVIDENCIAS } ?: return null
            // F1 cierre TPV (I4): antes esta rama liberaba `OPERATOR_RECONCILED` sin mirar el comprobante.
            return desdeAcuse(venueId, attemptId, requestId, evidencia, respuesta.declarationReceipt, null, respuesta.attempt)
        }

        /** La solicitud que el SERVIDOR soltó porque el banco rechazó (aviso de AngelPay: `BANK_DECLINED`). */
        const val RECHAZO_DEL_BANCO = "PROCESSOR_DECLINED"

        /**
         * 🔴 Codex final r6 #1 (20-nov): el SERVIDOR soltó la solicitud porque AngelPay le avisó que el banco rechazó ESTE intento
         * (`attempt.processorEvidence = DECLINED`: el evento es de este intento, con dueño). La N86 no lo aceptaba («el rechazo lo decide el
         * resultado del SDK», I4) y, con la respuesta del lector perdida y la firma rechazada por el SERVIDOR (el banco ya resolvió), la duda
         * de esa cuenta frenaba su tarjeta para siempre. Se aplica como la ventana: sin autor, nunca firma, por el único escritor (que
         * vuelve a mirar dinero, veto y pertenencia dentro del UPDATE). NO quita la marca del historial: el SERVIDOR llama «rechazo» a todo
         * estado de AngelPay que no es `approved` (`clasificarEstadoBancario`), también al `05AP`, así que si el historial la trae sin
         * aprobar la tarjeta espera hasta el techo ([PaymentAttemptEntity.TECHO_DE_LA_DUDA_DEL_POS_MS]). El rechazo de OTRO intento de la solicitud
         * (`processorEvidence` ≠ DECLINED en éste) no suelta nada.
         */
        private fun desdeRechazoDelBanco(venueId: String, attemptId: String, requestId: String, intento: TerminalAttemptResultDto?): LiberacionDelServidor? =
            intento?.takeIf {
                it.attemptId == attemptId && it.processorEvidence == "DECLINED" &&
                    !acreditaDinero(it) && !hayEvidenciaSinDueno(it) && !hayContradiccion(it)
            }?.let { LiberacionDelServidor(venueId, attemptId, requestId, RECHAZO_DEL_BANCO) }

        /**
         * 🔴 D12 («ninguna duda de cobro sin salida», hallazgo #3 de Codex TPV r1), PASO 1 de la consulta por SOLICITUD: ¿puede la
         * terminal preguntar por la solicitud de un intento que NUNCA ligó? La PAX no emite `terminal:payment_attempt_opened`
         * (sólo la Nexgo liga): sin vínculo ni Payment, S6 contesta `requestId:null, request:null`, y el «no se cobró» que el cajero
         * declaró en el POS no le llega a ese intento.
         *
         * Sólo si S6 es una respuesta VÁLIDA de ESTE intento, LIMPIA y SIN vínculo: `success` explícito; la misma identidad en la
         * raíz y en el intento; ni `requestId` ni `request` (con vínculo manda la ruta de S6); y nada de lo que S6 sabe del dinero
         * ([acreditaDinero]) ni de sus avisos ([motivoDelVeto]). Una respuesta ausente, ilegible o de otro intento no es «sin
         * dinero»: la ausencia de respuesta no es ausencia de evidencia. Lo que S6 aporta sigue mandando.
         */
        fun s6DejaPreguntarPorLaSolicitud(attemptId: String, s6: TerminalAttemptStatusResponse?): Boolean =
            s6 != null && s6.success == true && s6.attemptId == attemptId && s6.attempt?.attemptId == attemptId &&
                s6.requestId.isNullOrBlank() && s6.request == null &&
                !acreditaDinero(s6.attempt) && motivoDelVeto(s6.attempt) == null

        /**
         * 🔴 D12, PASO 2: la liberación que acredita la consulta por SOLICITUD (`GET …/requests/{requestId}`) del [requestId] DURABLE
         * del intento. Sólo la declaración de una PERSONA sobre ESA solicitud: `success`, `resuelta` (el servidor ya no la cuenta
         * como pendiente), la misma identidad en la raíz y dentro de `request` —sin recortar ni normalizar nada hacia otra
         * solicitud— y `outcome = NOT_CHARGED` con `outcomeEvidence = OPERATOR_RECONCILED`. Nunca el silencio del banco
         * (`NO_EVIDENCE_AFTER_WINDOW`), el reloj (`AUTO_RELEASED`, `TIMED_OUT`), el monto ni el serial. No exige importe ni terminal:
         * el endpoint ya autentica la terminal y busca por clave exacta; si la proyección publica el negocio, debe ser el de la
         * sesión. Un JSON que no cuadra es una duda que se conserva, nunca una liberación.
         */
        fun desdeConsultaPorSolicitud(venueId: String, attemptId: String, requestId: String, respuesta: TerminalRequestStatusResponse?): LiberacionDelServidor? {
            if (respuesta == null || respuesta.success != true || respuesta.resuelta != true || respuesta.requestId != requestId) return null
            val solicitud = respuesta.request ?: return null
            if (textoDe(solicitud, "requestId") != requestId) return null
            if (solicitud.get("venueId")?.isJsonNull == false && textoDe(solicitud, "venueId") != venueId) return null
            if (textoDe(solicitud, "outcome") != "NOT_CHARGED") return null
            // F1 · 05AP (20-nov): también la ventana con la firma de un gerente encima. Lo que sale tiene que ser la liberación de una
            // PERSONA ([desdeAcuse] la da sólo con su comprobante): el silencio solo nunca cierra por aquí.
            val evidencia = textoDe(solicitud, "outcomeEvidence")
                ?.takeIf { it == "OPERATOR_RECONCILED" || it == "NO_EVIDENCE_AFTER_WINDOW" } ?: return null
            // F1 bloque 7 (I4): un `OPERATOR_RECONCILED` aislado no acredita a nadie: hace falta el comprobante de una PERSONA (lo decide
            // [desdeAcuse], con su ponytail para el SERVIDOR anterior).
            return desdeAcuse(venueId, attemptId, requestId, evidencia, respuesta.declarationReceipt, null, null)
                ?.takeIf { it.evidencia == "OPERATOR_RECONCILED" }
        }

        private fun textoDe(objeto: JsonObject?, campo: String): String? =
            runCatching { objeto?.get(campo)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()

        /**
         * El 2xx de la declaración del cajero (`POST …/no-instrument-resolution`): por contrato el servidor sólo contesta 200
         * cuando liberó la solicitud como `OPERATOR_RECONCILED`, así que la liberación se sintetiza de la respuesta misma —
         * pero con el MISMO veto que S6: si el cuerpo acredita dinero del intento, no hay liberación (null).
         */
        fun desdeDeclaracion(venueId: String, attemptId: String, requestId: String?, respuesta: TerminalAttemptStatusResponse?): LiberacionDelServidor? {
            // F1 bloque 7 (I4): sin testimonio no hay acuse (antes se sintetizaba `OPERATOR_RECONCILED` de un 2xx sin más). Ronda 20: la
            // declaración AUTOMÁTICA de la terminal se guarda como liberación por ventana (no la firmó una persona).
            val testimonio = respuesta?.attempt?.resolution ?: return null
            return desdeAcuse(
                venueId, attemptId, requestId, evidenciaDeLaDeclaracion(textoDe(testimonio, "kind")),
                respuesta.declarationReceipt, testimonio, respuesta.attempt,
            )
        }

        /**
         * 🔴 F1 (Codex código r1 #2 y #3): quién declaró, en el acuse que Avoqado YA guardó — por la sesión (`SESSION`) o con el PIN de
         * un gerente (`SUPERVISOR_PIN`) —, o null. El SERVIDOR de F1 ya no acepta una declaración nueva por la sesión (pide el PIN del
         * gerente), así que un `SESSION` sólo puede ser una declaración VIEJA que Avoqado aceptó con las reglas de entonces, y es la
         * única que va a volver a contestar (replay). Rechazarla no protege nada y deja la cuenta sin salida. Lo automático
         * (`AUTOMATIC`) o un acuse sin `by` no cuentan. Que sea de ESTE intento y de esta declaración lo comprueba quien llama.
         */
        private val DECLARACIONES_DE_UNA_PERSONA = setOf("UNCHARGED_VERIFIED", "NO_INSTRUMENT_PRESENTED")

        fun quienDeclaroElAcuse(declaracion: JsonObject?): String? =
            declaracion?.takeIf { textoDe(it, "by") == "SUPERVISOR_PIN" || textoDe(it, "by") == "SESSION" }?.let { quienDeclaro(it) }

        /** D22: el `staffId` del testimonio publicado (el servidor lo toma de la sesión, nunca del cuerpo). */
        private fun quienDeclaro(declaracion: JsonObject): String? = textoDe(declaracion, "staffId")?.takeIf { it.isNotBlank() }

        /**
         * Ronda 20: qué liberación representa cada testimonio que publica el servidor. `NO_INSTRUMENT_PRESENTED` es la palabra del
         * CAJERO; `NO_BANK_TRACE_AFTER_WINDOW` la de la TERMINAL (esperó el aviso del banco y no llegó, donde el servidor lo tiene
         * comprobado). Una clase desconocida no libera nada.
         */
        fun evidenciaDeLaDeclaracion(kind: String?): String? = when (kind) {
            "NO_INSTRUMENT_PRESENTED" -> "OPERATOR_RECONCILED"
            "NO_BANK_TRACE_AFTER_WINDOW" -> "NO_EVIDENCE_AFTER_WINDOW"
            // D22: sin esta línea, el 2xx de la declaración revisada caía en «no presentó tarjeta» (el `?:` de [desdeDeclaracion]).
            "UNCHARGED_VERIFIED" -> CAJERO_REVISO
            else -> null
        }
    }
}
