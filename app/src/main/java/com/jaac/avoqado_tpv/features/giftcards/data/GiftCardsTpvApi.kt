package com.jaac.avoqado_tpv.features.giftcards.data

import com.google.gson.annotations.SerializedName
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.math.BigDecimal

/**
 * Tienda híbrida (Fase A, parte 2, P1): cobrar un folio con saldo o tarjeta de regalo. Rutas propias de la terminal que el SERVIDOR monta en
 * `routes/tpv/giftCards.tpv.routes.ts` (S12). El código va en el CUERPO, nunca en la URL. Montos en PESOS (número JSON).
 *
 * El cobro viaja como [RequestBody] crudo: son EXACTAMENTE los bytes que se guardaron antes de mandarlo, y un reenvío repite esos mismos
 * bytes con la misma llave (`CobroConSaldoRepository`).
 */
interface GiftCardsTpvApi {

    /** `giftCards:read` (lo trae `giftCards:redeem`). Cuerpo `{ code }` o `{ pageToken }`. 404 `GIFT_CARD_NOT_FOUND`, 429 límite. */
    @POST("tpv/venues/{venueId}/gift-cards/lookup")
    suspend fun consultar(
        @Path("venueId") venueId: String,
        @Header("x-device-id") deviceId: String?,
        @Body cuerpo: Map<String, String>,
    ): Response<SobreEnvelope<ConsultaDto>>

    /** `{ found: true, payment }` o `{ found: false }`. `found:false` NO prueba que no se cobró: el primer envío puede seguir vivo. */
    @GET("tpv/venues/{venueId}/gift-cards/payments/{key}")
    suspend fun cobroDeLaLlave(
        @Path("venueId") venueId: String,
        @Path("key") llave: String,
    ): Response<SobreEnvelope<CobroDeLaLlaveDto>>
}

/**
 * El cobro con saldo, aparte de las consultas (full-testing H1): las consultas usan el cliente de siempre, que reintenta en una conexión
 * nueva si la vieja estaba muerta; el cobro usa uno que nunca reenvía solo y nunca sale por una conexión dormida
 * (`NetworkModule.clienteDeCobroConSaldo`).
 */
interface CobroConSaldoApi {

    /** `giftCards:redeem`. Exige `x-device-id` (400 `GIFT_CARD_DEVICE_ID_REQUIRED` sin ella). */
    @POST("tpv/venues/{venueId}/gift-cards/orders/{orderId}/pay")
    suspend fun cobrar(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Header("x-device-id") deviceId: String,
        @Body cuerpo: RequestBody,
    ): Response<SobreEnvelope<CobroConSaldoDto>>
}

// Todo opcional: un campo que falte nunca tumba la respuesta. El dinero es BigDecimal para no pasar nunca por Double.

data class SobreEnvelope<T>(@SerializedName("data") val data: T?)

data class ConsultaDto(
    @SerializedName("usableBalance") val usableBalance: BigDecimal?,
    @SerializedName("cards") val cards: List<TarjetaDto?>?,
)

data class TarjetaDto(
    @SerializedName("last4") val last4: String?,
    @SerializedName("balance") val balance: BigDecimal?,
    @SerializedName("usable") val usable: Boolean?,
    @SerializedName("notUsableReason") val notUsableReason: String?,
)

data class CobroConSaldoDto(
    @SerializedName("paymentId") val paymentId: String?,
    @SerializedName("orderId") val orderId: String?,
    @SerializedName("amount") val amount: BigDecimal?,
    @SerializedName("redeemed") val redeemed: List<CanjeDto?>?,
    @SerializedName("orderRemaining") val orderRemaining: BigDecimal?,
    @SerializedName("orderPaymentStatus") val orderPaymentStatus: String?,
)

data class CanjeDto(
    @SerializedName("last4") val last4: String?,
    @SerializedName("balanceAfter") val balanceAfter: BigDecimal?,
)

data class CobroDeLaLlaveDto(
    @SerializedName("found") val found: Boolean?,
    @SerializedName("payment") val payment: CobroConSaldoDto?,
)
