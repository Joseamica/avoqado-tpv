package com.jaac.avoqado_tpv.core.remotepayment

import org.json.JSONObject

/**
 * Checkpoint 2 · N1: qué hace la terminal con el ACK de `terminal:payment_attempt_opened` (S1) ANTES de tocar el SDK.
 *
 * Contrato del servidor (`AttemptLinkAck`): `{success:true, outcome: LINKED|ALREADY_LINKED|LATE_EVIDENCE, requestStatus,
 * executionAuthorized}` o `{success:false, reason: INVALID|NOT_OWNER|ATTEMPT_OWNED_BY_OTHER_REQUEST|ERROR}`.
 *
 * 🔴 Se lee ESTRICTAMENTE (Codex, diseño v2): la autorización sólo sale de `executionAuthorized === true` —nunca de
 * `success` solo ni del `outcome` (LINKED puede venir con `executionAuthorized:false`: CANCEL_REQUESTED/UNKNOWN retienen la
 * ranura sin autorizar otra ejecución)—, y `NOT_OWNER` se reconoce por igualdad exacta. Cualquier forma que no sea una de
 * las del contrato (campos ausentes o de otro tipo, `reason` desconocido, `ERROR`, sin ACK) cae al camino LEGACY: cobrar
 * sin vínculo, que es el camino certificado del checkpoint 1 — un ACK que no se entiende no autoriza ni prohíbe nada nuevo.
 */
sealed class DecisionDelVinculo {
    /** `success:true` ∧ `executionAuthorized:true` ⇒ CAS a AUTORIZANDO y SDK sólo si gana. */
    object Cobrar : DecisionDelVinculo()

    /** `success:true` ∧ `executionAuthorized:false` (cualquier `outcome`): la solicitud ya no es ejecutable. No SDK. */
    data class NoEjecutable(val requestStatus: String?, val outcome: String?) : DecisionDelVinculo() {
        /** El POS pidió cancelar o ya canceló ⇒ el negativo (si H.3 lo acredita) es `cancelled`; si no, `failed`. */
        val statusNegativo: String
            get() = if (requestStatus == "CANCEL_REQUESTED" || requestStatus == "CANCELLED") "cancelled" else "failed"

        /** Con la solicitud ya COMPLETED la verdad es «no se inició OTRO cobro», nunca «no se cobró». */
        val yaCobrada: Boolean get() = requestStatus == "COMPLETED"
    }

    /** `NOT_OWNER`: el servidor no acredita que ESTA conexión sea la dueña de la solicitud. No SDK, nunca. */
    object NoEsDuena : DecisionDelVinculo()

    /** `ATTEMPT_OWNED_BY_OTHER_REQUEST`: el `attemptId` ya pertenece a OTRA solicitud. No SDK; llave nueva y reintento. */
    object LlaveAjena : DecisionDelVinculo()

    /** `INVALID`: ids vacíos o demasiado largos. No SDK (el REST los rechazaría igual); llave nueva y reintento. */
    object Invalida : DecisionDelVinculo()

    /** `ERROR`, ACK malformado, `reason` desconocido o sin ACK: camino legacy (cobrar sin vínculo). */
    data class Legacy(val motivo: String) : DecisionDelVinculo()

    companion object {
        const val MOTIVO_SIN_ACK = "sin ACK"

        fun de(ack: JSONObject?): DecisionDelVinculo {
            if (ack == null) return Legacy(MOTIVO_SIN_ACK)
            val success = ack.opt("success")
            if (success !is Boolean) return Legacy("success no es booleano")
            if (success) {
                val autorizado = ack.opt("executionAuthorized")
                if (autorizado !is Boolean) return Legacy("executionAuthorized no es booleano")
                if (autorizado) return Cobrar
                return NoEjecutable(
                    requestStatus = ack.opt("requestStatus") as? String,
                    outcome = ack.opt("outcome") as? String,
                )
            }
            return when (ack.opt("reason") as? String) {
                "NOT_OWNER" -> NoEsDuena
                "ATTEMPT_OWNED_BY_OTHER_REQUEST" -> LlaveAjena
                "INVALID" -> Invalida
                "ERROR" -> Legacy("ERROR del servidor")
                null -> Legacy("success:false sin reason")
                else -> Legacy("reason desconocido")
            }
        }

        /** El ACK llega como `Array<Any>` de socket.io: el primer elemento, si es un JSONObject; si no, malformado. */
        fun de(args: Array<out Any?>?): DecisionDelVinculo = de(args?.firstOrNull() as? JSONObject)
    }
}
