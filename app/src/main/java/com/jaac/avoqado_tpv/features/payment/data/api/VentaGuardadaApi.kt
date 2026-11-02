package com.jaac.avoqado_tpv.features.payment.data.api

import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** Usa el Retrofit por defecto de ApiService, cuya base ya incluye /api/v1/. */
interface VentaGuardadaApi {
    @POST("mobile/venues/{venueId}/orders")
    suspend fun crearOrden(@Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<JsonObject>

    @POST("mobile/venues/{venueId}/orders/{orderId}/pay")
    suspend fun pagar(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body cuerpo: PagoDeVentaGuardadaRequest,
    ): Response<JsonObject>
}

/** Mobile habla centavos; Room guarda pesos. Los opcionales nulos se omiten con el Gson existente. */
data class PagoDeVentaGuardadaRequest(
    val venueId: String,
    val amount: Int,
    val tip: Int,
    val staffId: String,
    val idempotencyKey: String,
    val shiftIdDeLaVenta: String?,
    val createdAtLocal: Long,
    val edadDeLaVentaMs: Long?,
    val status: String = "COMPLETED",
    val method: String = "CASH",
    val source: String = "TPV",
    val isOfflineReplay: Boolean = true,
)
