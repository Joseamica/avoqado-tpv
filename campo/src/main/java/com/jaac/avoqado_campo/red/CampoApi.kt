package com.jaac.avoqado_campo.red

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Las rutas del celular (sin `/api/v1/`: ya está en la base). Las tipadas se llaman SIEMPRE con [llamarSeguro].
 * `credencial` = `X-Celular-Credencial` (64 hex). `bearer` = `"Bearer <access>"`, lo pone [GuardiaDeSesion].
 * Las piezas de la cola van como `RequestBody` crudo: el texto exacto que se encoló.
 */
interface CampoApi {
    @POST("tpv/campo/activar")
    suspend fun activar(@Body b: ActivarBody): Response<RespuestaActivar>

    @POST("tpv/campo/entrar")
    suspend fun entrar(@Header(CREDENCIAL) credencial: String, @Body b: EntrarBody): Response<RespuestaEntrar>

    @POST("tpv/campo/renovar")
    suspend fun renovar(@Header(CREDENCIAL) credencial: String, @Body b: RenovarBody): Response<RespuestaRenovar>

    @POST("tpv/campo/latido")
    suspend fun latido(@Header(CREDENCIAL) credencial: String, @Body b: LatidoBody): Response<RespuestaLatido>

    @POST("tpv/campo/desvincular")
    suspend fun desvincular(@Header(CREDENCIAL) credencial: String, @Body b: DesvincularBody): Response<ResponseBody>

    @POST("tpv/campo/gracia")
    suspend fun gracia(@Header(CREDENCIAL) credencial: String, @Body b: GraciaBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/ventas")
    suspend fun ventas(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/custodia/aceptar")
    suspend fun aceptarCustodia(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/custodia/rechazar")
    suspend fun rechazarCustodia(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/checadas/entrada")
    suspend fun entrada(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/checadas/salida")
    suspend fun salida(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @POST("tpv/campo/venues/{venueId}/evidencia")
    suspend fun evidencia(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body cuerpo: RequestBody): Response<ResponseBody>

    @GET("tpv/campo/venues/{venueId}/mis-sims")
    suspend fun misSims(@Header("Authorization") bearer: String, @Path("venueId") venueId: String): Response<MisSimsDto>

    @POST("tpv/campo/venues/{venueId}/ubicacion")
    suspend fun ubicacion(@Header("Authorization") bearer: String, @Path("venueId") venueId: String, @Body b: PingBody): Response<ResponseBody>

    /** Semipública (B2): sin `Authorization`, sólo `X-Venue-Id`. */
    @GET("tpv/modules")
    suspend fun modulos(@Header("X-Venue-Id") venueId: String): Response<RespuestaModulos>

    companion object { const val CREDENCIAL = "X-Celular-Credencial" }
}
