package com.jaac.avoqado_tpv.features.onlineorders.data

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.math.BigDecimal

/**
 * Tienda híbrida: los folios («Pedidos por pagar») en la terminal. SOLO `tpv/…`; el SERVIDOR monta estas siete rutas en
 * `routes/tpv/onlineOrders.tpv.routes.ts`. `orderId` es siempre la CUENTA, no el `onlineOrderId`.
 */
interface OnlineOrdersApiService {

    /**
     * Lista y latido. [puedeCobrar] se manda como `x-online-orders-to-pay: 1` SÓLO cuando la terminal puede cobrar
     * ahora mismo; `null` no manda la cabecera (Retrofit la omite). [section] + [cursor] piden más de una sección.
     * D661 (Ruling 45): [avisaTarjetas] (`x-online-orders-card-alert: 1`) sólo la manda el sondeo del vigía con la app al frente.
     */
    @GET("tpv/venues/{venueId}/online-orders/to-pay")
    suspend fun toPay(
        @Path("venueId") venueId: String,
        @Header("x-online-orders-to-pay") puedeCobrar: String?,
        @Query("section") section: String? = null,
        @Query("cursor") cursor: String? = null,
        @Header("x-online-orders-card-alert") avisaTarjetas: String? = null,
    ): Response<ToPayEnvelope>

    /**
     * El cuerpo `{}` es obligatorio: el esquema del SERVIDOR exige un objeto. D661: `{ "abrirPagadoEnLinea": true }` abre (y el
     * SERVIDOR cobra) un pedido pagado con tarjeta en línea; sólo lo manda el botón «Abrir y cobrar».
     */
    @POST("tpv/venues/{venueId}/online-orders/{orderId}/accept")
    suspend fun accept(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: Map<String, Boolean> = emptyMap(),
    ): Response<Unit>

    /** D661 (Ruling 14): UN pedido por su propio camino (la comanda de uno que no está en la página de «Por entregar»). */
    @GET("tpv/venues/{venueId}/online-orders/{orderId}")
    suspend fun order(@Path("venueId") venueId: String, @Path("orderId") orderId: String): Response<OrderEnvelope>

    @POST("tpv/venues/{venueId}/online-orders/{orderId}/ready")
    suspend fun ready(@Path("venueId") venueId: String, @Path("orderId") orderId: String): Response<Unit>

    @POST("tpv/venues/{venueId}/online-orders/{orderId}/delivered")
    suspend fun delivered(@Path("venueId") venueId: String, @Path("orderId") orderId: String): Response<Unit>

    @POST("tpv/venues/{venueId}/online-orders/{orderId}/cancel")
    suspend fun cancel(@Path("venueId") venueId: String, @Path("orderId") orderId: String): Response<Unit>

    /**
     * Exige `x-device-id` (400 `DEVICE_ID_REQUIRED` sin ella). D661 (Ruling 37): [imprimeLaMarca] (`x-online-orders-card-alert: 1`)
     * dice «esta caja imprime POSIBLE REPETIDA»; sin ella el SERVIDOR no le da el reclamo REPETIDO de un pedido pagado en línea.
     */
    @POST("tpv/venues/{venueId}/online-orders/{orderId}/print/claim")
    suspend fun printClaim(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Header("x-device-id") deviceId: String,
        @Header("x-online-orders-card-alert") imprimeLaMarca: String? = null,
    ): Response<PrintClaimEnvelope>

    @POST("tpv/venues/{venueId}/online-orders/{orderId}/print/confirm")
    suspend fun printConfirm(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Header("x-device-id") deviceId: String,
    ): Response<PrintConfirmEnvelope>
}

// Todo opcional: un campo que falte o llegue null nunca tumba la lista; los campos desconocidos los ignora Gson.
// El dinero es BigDecimal para que 66.10 llegue como 66.10 y no pase nunca por Double.

data class ToPayEnvelope(@SerializedName("data") val data: ToPayDto?)

data class ToPayDto(
    @SerializedName("serverTime") val serverTime: String?,
    @SerializedName("showTile") val showTile: Boolean?,
    @SerializedName("pollSeconds") val pollSeconds: Int?,
    @SerializedName("counts") val counts: CountsDto?,
    @SerializedName("toPay") val toPay: List<FolioDto?>?,
    @SerializedName("toDeliver") val toDeliver: List<FolioDto?>?,
    @SerializedName("nextCursors") val nextCursors: NextCursorsDto?,
    // D661, opcionales: un SERVIDOR viejo no los manda.
    @SerializedName("tarjetasPorAbrir") val tarjetasPorAbrir: List<TarjetaPorAbrirDto?>? = null,
    @SerializedName("comandasPorImprimir") val comandasPorImprimir: List<ComandaPorImprimirDto?>? = null,
)

/** D661: `total` en PESOS (Ruling 13), como los montos de los folios. */
data class TarjetaPorAbrirDto(
    @SerializedName("orderId") val orderId: String?,
    @SerializedName("orderNumber") val orderNumber: String?,
    @SerializedName("customerName") val customerName: String?,
    @SerializedName("total") val total: BigDecimal?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("cobrando") val cobrando: Boolean?,
)

data class ComandaPorImprimirDto(@SerializedName("orderId") val orderId: String?, @SerializedName("orderNumber") val orderNumber: String?)

data class OrderEnvelope(@SerializedName("data") val data: FolioDto?)

data class CountsDto(@SerializedName("toPay") val toPay: Int?, @SerializedName("toDeliver") val toDeliver: Int?)

data class NextCursorsDto(@SerializedName("toPay") val toPay: String?, @SerializedName("toDeliver") val toDeliver: String?)

data class FolioDto(
    @SerializedName("orderId") val orderId: String?,
    @SerializedName("onlineOrderId") val onlineOrderId: String?,
    @SerializedName("orderNumber") val orderNumber: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("customerName") val customerName: String?,
    @SerializedName("customerPhone") val customerPhone: String?,
    @SerializedName("note") val note: String?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("acceptDeadlineAt") val acceptDeadlineAt: String?,
    @SerializedName("paidAt") val paidAt: String?,
    @SerializedName("total") val total: BigDecimal?,
    @SerializedName("remaining") val remaining: BigDecimal?,
    @SerializedName("paymentStatus") val paymentStatus: String?,
    @SerializedName("needsPrint") val needsPrint: Boolean?,
    @SerializedName("items") val items: List<ItemDto?>?,
    // S14 (parte 2), opcionales: de quién es la cuenta (nombre corto, nunca su contacto) y sus filas de descuento en PESOS.
    @SerializedName("cliente") val cliente: ClienteDto? = null,
    @SerializedName("beneficios") val beneficios: List<BeneficioDto?>? = null,
    @SerializedName("beneficiosTotal") val beneficiosTotal: Int? = null,
    // D661 (Ruling 36), opcionales: ISO o null.
    @SerializedName("notPickedUpAt") val notPickedUpAt: String? = null,
    @SerializedName("refundRequestedAt") val refundRequestedAt: String? = null,
    @SerializedName("refundedAt") val refundedAt: String? = null,
)

data class ClienteDto(@SerializedName("id") val id: String?, @SerializedName("nombre") val nombre: String?)

/** El monto llega crudo: un valor raro descarta ese beneficio, nunca la lista. */
data class BeneficioDto(@SerializedName("nombre") val nombre: String?, @SerializedName("monto") val monto: JsonElement?)

data class ItemDto(
    @SerializedName("name") val name: String?,
    @SerializedName("quantity") val quantity: Int?,
    @SerializedName("note") val note: String?,
    @SerializedName("modifiers") val modifiers: List<ModifierDto?>?,
    // R76: precio por renglón en PESOS, opcional (un SERVIDOR viejo no lo manda). Llega crudo: un valor raro no tumba la lista.
    @SerializedName("unitPrice") val unitPrice: JsonElement? = null,
    @SerializedName("total") val total: JsonElement? = null,
)

data class ModifierDto(@SerializedName("name") val name: String?, @SerializedName("quantity") val quantity: Int?)

data class PrintClaimEnvelope(@SerializedName("data") val data: PrintClaimDto?)
data class PrintClaimDto(
    @SerializedName("claimed") val claimed: Boolean?,
    /** Ruling 9: concedido sobre otro reclamo que venció sin confirmarse: el papel pudo haber salido ya. */
    @SerializedName("posibleRepetida") val posibleRepetida: Boolean? = null,
)
data class PrintConfirmEnvelope(@SerializedName("data") val data: PrintConfirmDto?)
data class PrintConfirmDto(@SerializedName("ok") val ok: Boolean?)
