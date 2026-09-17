package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * S6 (checkpoint 1 del webhook, servidor): lo que la TERMINAL puede saber de SU intento.
 * `GET tpv/venues/{venueId}/terminal-payment/attempts/{attemptId}` con el token de la terminal.
 *
 *  · 200 ⇒ [TerminalAttemptStatusResponse] (el Payment de ESTE intento, nunca el de otro; y la solicitud aparte).
 *  · 404 `ATTEMPT_NOT_FOUND` + `outcome: NO_EVIDENCE` ⇒ el servidor no conoce el intento PARA ESTA TERMINAL. 🔴 NO acredita
 *    ausencia de cobro: la libreta se conserva. Un 404 de RUTA (servidor anterior a S6) se trata igual.
 *  · 403 `TERMINAL_IDENTITY_REQUIRED`, 401, 5xx, sin red ⇒ nada cambia.
 * Interfaz propia (no `ApiService`): es el único cliente de S6 y va por el cliente HTTP de pagos (5/10 s, fail-fast).
 */
interface TerminalAttemptApiService {
    @GET("tpv/venues/{venueId}/terminal-payment/attempts/{attemptId}")
    suspend fun getAttemptStatus(
        @Path("venueId") venueId: String,
        @Path("attemptId") attemptId: String,
    ): Response<TerminalAttemptStatusResponse>

    /**
     * Declaración del cajero (ventana de confirmación, Task 7): «el cliente no presentó tarjeta».
     *  · 200 ⇒ la SOLICITUD quedó liberada por el servidor (`request.outcome = NOT_CHARGED`, `OPERATOR_RECONCILED`); el cuerpo
     *    es la misma proyección que S6 — si trae evidencia de DINERO del intento, ésa manda y no hay liberación.
     *  · 403 `SUPERVISOR_AUTHORIZATION_REQUIRED` ⇒ pedir PIN de supervisor y repetir con el MISMO `resolutionId`;
     *    `SESSION_NOT_IN_VENUE` / `TERMINAL_IDENTITY_REQUIRED` ⇒ no hay PIN que valga.
     *  · 409 `ATTEMPT_NOT_ELIGIBLE` · `POSITIVE_EVIDENCE_EXISTS` · `RESOLUTION_CONFLICT` · `OTHER_ATTEMPT_UNRESOLVED` ⇒ no elegible:
     *    se consulta el veredicto (S6), nunca se libera en local.
     *  · 404 `ATTEMPT_NOT_FOUND`, 503 `RESOLUTION_UNAVAILABLE`, sin red ⇒ nada cambia.
     * El `resolutionId` es UNO por intento: el replay (mismo id) es idempotente en el servidor.
     */
    @retrofit2.http.POST("tpv/venues/{venueId}/terminal-payment/attempts/{attemptId}/no-instrument-resolution")
    suspend fun resolveNoInstrument(
        @Path("venueId") venueId: String,
        @Path("attemptId") attemptId: String,
        @retrofit2.http.Body body: NoInstrumentResolutionRequest,
    ): Response<TerminalAttemptStatusResponse>
}

/** Cuerpo de [TerminalAttemptApiService.resolveNoInstrument]; `statement`/`statementVersion` fijan el texto legal que el cajero afirma. */
data class NoInstrumentResolutionRequest(
    @SerializedName("requestId") val requestId: String,
    @SerializedName("resolutionId") val resolutionId: String,
    @SerializedName("statement") val statement: String = "NO_INSTRUMENT_PRESENTED",
    @SerializedName("statementVersion") val statementVersion: Int = 1,
    @SerializedName("supervisorPin") val supervisorPin: String? = null,
)

/** Espejo tolerante de `TerminalAttemptStatus` del servidor: todo nulo por defecto (Gson no respeta la no-nulabilidad). */
data class TerminalAttemptStatusResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("attemptId") val attemptId: String? = null,
    @SerializedName("requestId") val requestId: String? = null,
    @SerializedName("attempt") val attempt: TerminalAttemptResultDto? = null,
    @SerializedName("request") val request: com.google.gson.JsonObject? = null,
)

data class TerminalAttemptResultDto(
    @SerializedName("attemptId") val attemptId: String? = null,
    /** RECORDED · SECOND_CAPTURE_EVIDENCE · REFERENCE_COLLISION_EVIDENCE · NOT_RECORDED */
    @SerializedName("outcome") val outcome: String? = null,
    @SerializedName("paymentId") val paymentId: String? = null,
    @SerializedName("paymentStatus") val paymentStatus: String? = null,
    @SerializedName("recordedVia") val recordedVia: String? = null,
    @SerializedName("amountCents") val amountCents: Long? = null,
    @SerializedName("tipCents") val tipCents: Long? = null,
    @SerializedName("isWinner") val isWinner: Boolean? = null,
    @SerializedName("winnerPaymentId") val winnerPaymentId: String? = null,
    @SerializedName("processorEvidence") val processorEvidence: String? = null,
    @SerializedName("paymentContradiction") val paymentContradiction: Boolean? = null,
    @SerializedName("evidenceContradiction") val evidenceContradiction: Boolean? = null,
)
