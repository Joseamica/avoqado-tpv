package com.jaac.avoqado_campo.auth

import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.LoginBody
import com.jaac.avoqado_campo.red.LoginRespuesta
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import javax.inject.Inject

class RepositorioAuthCampoImpl @Inject constructor(
    private val api: CampoApi,
) : RepositorioAuthCampo {

    override suspend fun entrar(correo: String, contrasena: String): ResultadoLogin = try {
        val r = api.login(LoginBody(email = correo, password = contrasena))
        if (r.isSuccessful) {
            // 🔴 El cliente NO elige sucursal ni la deduce de venues[]: lee la que el
            // servidor YA eligió al emitir el token (ver Task 0). venues[] trae también
            // las suspendidas y cerradas, así que contarlas rechazaría logins válidos.
            r.body()?.selectedVenueId
                ?.let { ResultadoLogin.Ok(it) }
                ?: ResultadoLogin.Falla("El servidor no indicó la sucursal de la sesión.")
        } else {
            // 🔴 Retrofit deja body() en null cuando la respuesta NO es 2xx: el JSON del error
            // vive en errorBody(). Leerlo de body() haría que todo 401 dijera un genérico.
            ResultadoLogin.Falla(mensajeDeError(r.errorBody()?.string(), r.code()))
        }
    } catch (e: IOException) {
        ResultadoLogin.Falla("Sin conexión. Revisa tu internet e intenta de nuevo.")
    }

    /** Saca el CAMPO `message` del JSON de error; nunca busca palabras dentro del texto. */
    private fun mensajeDeError(json: String?, codigo: Int): String = try {
        Gson().fromJson(json, LoginRespuesta::class.java)?.message
    } catch (e: JsonSyntaxException) {
        null
    } ?: "No se pudo entrar ($codigo)"
}
