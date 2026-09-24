package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

/**
 * 🔴 QA en la N86 (22-sep, 19:32): el servidor de hoy contesta `"resolution": null` y `"request": null` para un cobro
 * LOCAL, y la terminal REVENTABA al leerlo —
 * `JsonSyntaxException: Expected a com.google.gson.JsonObject but was JsonNull; at path $.attempt.resolution`—.
 * Para un campo `JsonObject?`, Gson lee el `null` de JSON como `JsonNull` y después falla al comprobar el tipo; el `?`
 * de Kotlin no ayuda. Consecuencia medida: TODA consulta S6 sin declaración contaba como «sin respuesta» y la
 * recuperación por servidor quedaba muda.
 *
 * Ninguna prueba lo veía porque todas arman las respuestas como objetos Kotlin. Éstas parsean el JSON que el servidor
 * MANDÓ DE VERDAD (copiado del logcat del aparato), con el mismo `Gson()` que usa `GsonConverterFactory.create()`.
 */
class RespuestasRealesDelServidorTest {

    private val gson = Gson()

    /** S6 de un Pago rápido sin declaración — tal cual llegó a la N86 (`attempts/21830498-…`, 200 en 481 ms). */
    private val s6CobroLocalReal = """{"success":true,"attemptId":"21830498-1fff-40a7-a80d-6fb29ac9ff5f","requestId":null,""" +
        """"attempt":{"attemptId":"21830498-1fff-40a7-a80d-6fb29ac9ff5f","outcome":"NOT_RECORDED","paymentId":null,""" +
        """"paymentStatus":null,"recordedVia":null,"amountCents":null,"tipCents":null,"isWinner":false,"winnerPaymentId":null,""" +
        """"processorEvidence":"NONE","processorEvidenceAt":null,"paymentContradiction":false,"evidenceContradiction":false,""" +
        """"unattributedEvidence":false,"linkedAt":null,"resolution":null},"request":null}"""

    /** Pieza D — tal cual llegó a la N86 (`requests/ad5fb00b-…`, el aviso fantasma de $50). */
    private val piezaDReal = """{"success":true,"requestId":"ad5fb00b-fa24-48f0-86be-8fb449b925cd","request":{""" +
        """"requestId":"ad5fb00b-fa24-48f0-86be-8fb449b925cd","venueId":"cmtwfpana001ic996lz8q2uuq","terminalId":"n860w173397",""" +
        """"status":"FAILED","amount":50,"tip":0,"orderId":null,"paymentId":null,"senderDevice":null,"lateResult":false,""" +
        """"cancelDisposition":null,"failureCode":"OPERATOR_RECONCILED_NO_CHARGE","outcome":"NOT_CHARGED",""" +
        """"outcomeEvidence":"OPERATOR_RECONCILED","evidenceClass":"OPERATOR","createdAt":"2026-09-21T18:53:00.845Z",""" +
        """"updatedAt":"2026-09-21T18:56:27.140Z","closedVia":null},"resuelta":true}"""

    @Test
    fun `P1 la S6 real de un cobro LOCAL se lee aunque traiga resolution y request en null`() {
        val r = gson.fromJson(s6CobroLocalReal, TerminalAttemptStatusResponse::class.java)
        assertThat(r.success).isTrue()
        assertThat(r.attempt?.outcome).isEqualTo("NOT_RECORDED")
        assertThat(r.attempt?.resolution).isNull()
        assertThat(r.request).isNull()
        // Y el camino que la usa decide como siempre: sin declaración, no se libera nada.
        assertThat(LiberacionDelServidor.desdeConsultaS6("v1", "21830498-1fff-40a7-a80d-6fb29ac9ff5f", r)).isNull()
    }

    @Test
    fun `P1 la pieza D con request en null se lee y NO autoriza cerrar nada`() {
        val r = gson.fromJson("""{"success":true,"requestId":"r1","request":null,"resuelta":false}""", TerminalRequestStatusResponse::class.java)
        assertThat(r.request).isNull()
        assertThat(r.resuelta).isFalse()
    }

    @Test
    fun `regresion - la pieza D real sigue llegando con su objeto request`() {
        val r = gson.fromJson(piezaDReal, TerminalRequestStatusResponse::class.java)
        assertThat(r.resuelta).isTrue()
        assertThat(r.request?.get("status")?.asString).isEqualTo("FAILED")
        assertThat(r.request?.get("failureCode")?.asString).isEqualTo("OPERATOR_RECONCILED_NO_CHARGE")
    }

    @Test
    fun `regresion - una declaracion del cajero sigue llegando como objeto y libera el cobro local`() {
        val conDeclaracion = s6CobroLocalReal.replace(
            """"resolution":null""",
            """"resolution":{"id":"res-1","kind":"NO_INSTRUMENT_PRESENTED","acceptedAt":"2026-09-22T19:40:00.000Z",""" +
                """"bodyHash":"abc","staffId":"s1","staffVenueId":"sv1","by":"SESSION","statementVersion":1}""",
        )
        val r = gson.fromJson(conDeclaracion, TerminalAttemptStatusResponse::class.java)
        assertThat(r.attempt?.resolution?.get("kind")?.asString).isEqualTo("NO_INSTRUMENT_PRESENTED")
        assertThat(LiberacionDelServidor.desdeConsultaS6("v1", "21830498-1fff-40a7-a80d-6fb29ac9ff5f", r)).isNotNull()
    }
}
