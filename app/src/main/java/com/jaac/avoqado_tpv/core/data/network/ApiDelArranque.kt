package com.jaac.avoqado_tpv.core.data.network

import com.jaac.avoqado_tpv.core.data.network.dto.ActivationStatusResponse
import com.jaac.avoqado_tpv.features.modules.data.dto.ModulesApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Las dos preguntas del logo de arranque, por el cliente corto [com.jaac.avoqado_tpv.core.di.ArranqueClient] (encargo
 * 097e01). Mismas rutas y respuestas que [ApiService.checkActivationStatus] y [ApiService.getModules]; sólo cambia cuánto
 * se espera al SERVIDOR.
 */
interface ApiDelArranque {
    @GET("tpv/terminals/{serialNumber}/activation-status")
    suspend fun checkActivationStatus(
        @Path("serialNumber") serialNumber: String,
        @Query("environment") environment: String
    ): Response<ActivationStatusResponse>

    @GET("tpv/modules")
    suspend fun getModules(
        @Header("X-Venue-Id") venueId: String
    ): Response<ModulesApiResponse>
}
