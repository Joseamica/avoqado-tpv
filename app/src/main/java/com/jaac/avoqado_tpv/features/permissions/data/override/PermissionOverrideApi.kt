package com.jaac.avoqado_tpv.features.permissions.data.override

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class PedirTokenBody(val pin: String, val permission: String)
data class AutorizadoPor(val id: String, val name: String)
data class TokenData(val token: String, val expiresAt: String, val authorizedBy: AutorizadoPor?)
data class TokenResponse(val success: Boolean, val data: TokenData?)

/** Sólo los dos campos que importan de GET mobile/venues/:venueId/settings; lo demás se ignora. */
data class ComponentesDeSettings(val promotionsPanel: String?)
data class DatosDeSettings(val managerPinOverrideEnabled: Boolean?, val componentsStatus: ComponentesDeSettings?)
data class VenueSettingsResponse(val data: DatosDeSettings?)

/** POST /api/v1/mobile/venues/:venueId/permission-overrides: cambia el PIN de un gerente por un token de un solo uso. */
interface PermissionOverrideApi {
    @POST("mobile/venues/{venueId}/permission-overrides")
    suspend fun crear(@Path("venueId") venueId: String, @Body body: PedirTokenBody): Response<TokenResponse>

    @GET("mobile/venues/{venueId}/settings")
    suspend fun settings(@Path("venueId") venueId: String): Response<VenueSettingsResponse>
}
