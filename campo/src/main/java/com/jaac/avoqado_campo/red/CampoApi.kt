package com.jaac.avoqado_campo.red

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class LoginBody(val email: String, val password: String)

data class LoginRespuesta(
    val success: Boolean?,
    val message: String?,
    val user: UsuarioDto?,
    val accessToken: String?,
    val refreshToken: String?,
    /** La sucursal a la que el servidor ató esta sesión. Ver Task 0. */
    val selectedVenueId: String?,
)

// 🔴 El servidor NO devuelve `user.venueId`: devuelve `user.venues[]`, un arreglo
// (auth.mobile.service.ts:~719). Modelarlo mal hace que un login CORRECTO caiga en error.
data class UsuarioDto(
    val id: String?,
    val firstName: String?,
    val lastName: String?,
    val venues: List<VenueDto>?,
)

data class VenueDto(
    val id: String?,
    val name: String?,
    val role: String?,
    val roleDisplayName: String?,
)

interface CampoApi {
    /** Ruta real del carril móvil, verificada en mobile.routes.ts:179. */
    @POST("mobile/auth/login")
    suspend fun login(@Body body: LoginBody): Response<LoginRespuesta>
}
