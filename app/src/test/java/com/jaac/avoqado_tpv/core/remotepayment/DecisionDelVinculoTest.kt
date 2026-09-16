package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/** La tabla de ACK de N1 (diseño v2, D4), leída estrictamente. */
class DecisionDelVinculoTest {
    private fun ack(vararg pares: Pair<String, Any?>) = JSONObject().apply { pares.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

    @Test fun `cobrar solo con success true y executionAuthorized true`() {
        assertThat(DecisionDelVinculo.de(ack("success" to true, "outcome" to "LINKED", "requestStatus" to "SENT", "executionAuthorized" to true)))
            .isEqualTo(DecisionDelVinculo.Cobrar)
        assertThat(DecisionDelVinculo.de(ack("success" to true, "outcome" to "ALREADY_LINKED", "requestStatus" to "PENDING", "executionAuthorized" to true)))
            .isEqualTo(DecisionDelVinculo.Cobrar)
    }

    @Test fun `LINKED con executionAuthorized false NO cobra, y el outcome no manda`() {
        for (outcome in listOf("LINKED", "ALREADY_LINKED", "LATE_EVIDENCE")) {
            val d = DecisionDelVinculo.de(ack("success" to true, "outcome" to outcome, "requestStatus" to "CANCEL_REQUESTED", "executionAuthorized" to false))
            assertThat(d).isInstanceOf(DecisionDelVinculo.NoEjecutable::class.java)
            assertThat((d as DecisionDelVinculo.NoEjecutable).statusNegativo).isEqualTo("cancelled")
        }
        val unknown = DecisionDelVinculo.de(ack("success" to true, "outcome" to "LINKED", "requestStatus" to "UNKNOWN", "executionAuthorized" to false)) as DecisionDelVinculo.NoEjecutable
        assertThat(unknown.statusNegativo).isEqualTo("failed")
        assertThat(unknown.yaCobrada).isFalse()
        val completed = DecisionDelVinculo.de(ack("success" to true, "outcome" to "LATE_EVIDENCE", "requestStatus" to "COMPLETED", "executionAuthorized" to false)) as DecisionDelVinculo.NoEjecutable
        assertThat(completed.yaCobrada).isTrue()
        assertThat(completed.statusNegativo).isEqualTo("failed")
    }

    @Test fun `NOT_OWNER nunca cobra y se reconoce por igualdad exacta`() {
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "NOT_OWNER"))).isEqualTo(DecisionDelVinculo.NoEsDuena)
        // Una variante que NO es el contrato no se lee como NOT_OWNER… ni como autorización: cae a legacy.
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "not_owner"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        // Y `success:true` con reason NOT_OWNER (forma imposible) tampoco autoriza: falta executionAuthorized ⇒ legacy.
        assertThat(DecisionDelVinculo.de(ack("success" to true, "reason" to "NOT_OWNER"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
    }

    @Test fun `llave ajena e invalida no cobran y piden llave nueva`() {
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "ATTEMPT_OWNED_BY_OTHER_REQUEST"))).isEqualTo(DecisionDelVinculo.LlaveAjena)
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "INVALID"))).isEqualTo(DecisionDelVinculo.Invalida)
    }

    @Test fun `ERROR, malformado, reason desconocido y sin ACK caen al camino legacy sin inferir autorizacion`() {
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "ERROR"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(ack("success" to false, "reason" to "ALGO_NUEVO"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(ack("success" to false))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(ack("success" to "true", "executionAuthorized" to true))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(ack("success" to true, "executionAuthorized" to "true"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(ack("success" to true))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(null as JSONObject?)).isEqualTo(DecisionDelVinculo.Legacy(DecisionDelVinculo.MOTIVO_SIN_ACK))
        assertThat(DecisionDelVinculo.de(arrayOf<Any?>("no soy json"))).isInstanceOf(DecisionDelVinculo.Legacy::class.java)
        assertThat(DecisionDelVinculo.de(arrayOf<Any?>(ack("success" to true, "executionAuthorized" to true)))).isEqualTo(DecisionDelVinculo.Cobrar)
        assertThat(DecisionDelVinculo.de(emptyArray<Any?>())).isEqualTo(DecisionDelVinculo.Legacy(DecisionDelVinculo.MOTIVO_SIN_ACK))
    }
}
