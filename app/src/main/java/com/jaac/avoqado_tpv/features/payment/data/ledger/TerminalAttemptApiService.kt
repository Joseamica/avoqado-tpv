package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
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
     * 🔴 Pieza D (22-sep): el estado de UNA SOLICITUD de esta terminal, aunque no tenga intento.
     *
     * Nace de un hallazgo en hardware: la N86 llevaba 25 h avisando de «un cobro de $50 sin confirmar» sobre una
     * solicitud que el servidor ya había resuelto el día anterior. Su fila de bandeja seguía PROCESSING y su libreta
     * no tenía NINGÚN intento de esa solicitud, así que nada podía alcanzarla — toda la recuperación pregunta por
     * intento. Con esto la bandeja se puede cerrar y el aviso se apaga solo.
     *
     *  · 200 ⇒ `{ request: {…}, resuelta }`. `resuelta` = el servidor ya NO la cuenta como desenlace pendiente.
     *  · 404 `REQUEST_NOT_FOUND` ⇒ no es de esta terminal. 🔴 NO acredita nada sobre el cobro: la fila se conserva.
     *  · 403/401/5xx/sin red ⇒ nada cambia.
     */
    @GET("tpv/venues/{venueId}/terminal-payment/requests/{requestId}")
    suspend fun getRequestStatus(
        @Path("venueId") venueId: String,
        @Path("requestId") requestId: String,
    ): Response<TerminalRequestStatusResponse>

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

/** Respuesta de la consulta por SOLICITUD (pieza D). Todo nulo por defecto: Gson no respeta la no-nulabilidad. */
data class TerminalRequestStatusResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("requestId") val requestId: String? = null,
    @JsonAdapter(ObjetoJsonONulo::class)
    @SerializedName("request") val request: JsonObject? = null,
    /** El servidor ya no cuenta esta solicitud como desenlace pendiente. Lo ÚNICO que autoriza cerrar la bandeja. */
    @SerializedName("resuelta") val resuelta: Boolean? = null,
)

/** Cuerpo de [TerminalAttemptApiService.resolveNoInstrument]; `statement`/`statementVersion` fijan el texto legal que el cajero afirma. */
data class NoInstrumentResolutionRequest(
    /**
     * 🔴 OPCIONAL desde el 22-sep («ninguna terminal muerta»): un **Pago rápido** —cobro iniciado EN la terminal— no
     * tiene solicitud del POS. Gson OMITE los nulos por defecto (el converter no lleva `serializeNulls`), así que un
     * `null` viaja AUSENTE, que es lo que el servidor espera: su esquema lo declara opcional y un `null` explícito lo
     * rechazaría. Omitirlo NO elige el camino: si el intento tiene vínculo, el servidor aplica igual las guardas de su
     * solicitud. (Misma familia que el `vieneAusente()` de los reembolsos, 11-sep: ausente ≠ nulo.)
     */
    @SerializedName("requestId") val requestId: String?,
    @SerializedName("resolutionId") val resolutionId: String,
    @SerializedName("statement") val statement: String = "NO_INSTRUMENT_PRESENTED",
    @SerializedName("statementVersion") val statementVersion: Int = 1,
    @SerializedName("supervisorPin") val supervisorPin: String? = null,
    /**
     * Ronda 21 (Codex r19, P1-3): el comercio con el que se hizo ESE cobro. Sólo lo manda la liberación AUTOMÁTICA: el servidor
     * mide el aviso del banco de ese comercio, no el de lo que la terminal tenga asignado hoy. `null` viaja ausente (Gson).
     */
    @SerializedName("merchantAccountId") val merchantAccountId: String? = null,
)

/** Espejo tolerante de `TerminalAttemptStatus` del servidor: todo nulo por defecto (Gson no respeta la no-nulabilidad). */
data class TerminalAttemptStatusResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("attemptId") val attemptId: String? = null,
    @SerializedName("requestId") val requestId: String? = null,
    @SerializedName("attempt") val attempt: TerminalAttemptResultDto? = null,
    @JsonAdapter(ObjetoJsonONulo::class)
    @SerializedName("request") val request: JsonObject? = null,
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
    /**
     * «Ninguna terminal muerta» (22-sep): la declaración del cajero «no se presentó tarjeta» sobre ESTE intento, o `null`
     * si nadie declaró. 🔴 Es lo único que destraba un cobro LOCAL: la señal que se lee hoy viaja dentro de `request.outcome`,
     * y un Pago rápido no tiene `request`. Campo ADITIVO: un servidor anterior no lo manda y todo sigue igual.
     */
    @JsonAdapter(ObjetoJsonONulo::class)
    @SerializedName("resolution") val resolution: JsonObject? = null,
    /**
     * El servidor tiene evidencia del procesador de este intento que no pudo atribuir a NADIE (sin vínculo y sin
     * serial). No es evidencia propia ni contradicción — pero mientras esté encendida, una `resolution` NO sirve para
     * soltar la venta. Campo ADITIVO: un servidor anterior no lo manda, y `null`/ausente se lee como «no hay».
     */
    @SerializedName("unattributedEvidence") val unattributedEvidence: Boolean? = null,
)

/**
 * 🔴 QA en la N86 (22-sep): el servidor manda `"resolution": null` y `"request": null`, y un campo `JsonObject?` REVENTABA
 * con `JsonSyntaxException: Expected a JsonObject but was JsonNull` — Gson lee el `null` de JSON como `JsonNull` y después
 * falla el tipo; el `?` de Kotlin no ayuda. La consulta ENTERA se perdía y la recuperación por servidor quedaba muda.
 *
 * Con `@JsonAdapter` (nullSafe por defecto) un `null` de JSON llega como `null` de Kotlin sin pasar por aquí; y cualquier
 * cosa que no sea un objeto también se lee como `null`: lo conservador, porque en estos tres campos «hay objeto» es lo que
 * puede liberar un cobro, y una forma inesperada no debe liberar nada ni tumbar la respuesta entera.
 */
internal class ObjetoJsonONulo : JsonDeserializer<JsonObject?> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): JsonObject? =
        json?.takeIf { it.isJsonObject }?.asJsonObject
}
