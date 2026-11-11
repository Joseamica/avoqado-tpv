package com.jaac.avoqado_tpv.features.permissions.data.override

import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorCode
import com.jaac.avoqado_tpv.core.data.network.parseBackendErrorMessage
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

sealed class ErrorDeAutorizacion(val texto: String) : Exception(texto) {
    object PinIncorrecto : ErrorDeAutorizacion("Código incorrecto")
    object SinEstePermiso : ErrorDeAutorizacion("Ese código tampoco tiene este permiso")
    object PinApagado : ErrorDeAutorizacion("En este negocio el PIN de gerente está apagado. La cortesía la da un gerente con su sesión")
    object DemasiadosIntentos : ErrorDeAutorizacion("Demasiados intentos. Espera 15 minutos")
    object SinConexion : ErrorDeAutorizacion("Sin conexión: el PIN del gerente necesita internet")
    object SesionVencida : ErrorDeAutorizacion("Tu sesión venció. Vuelve a entrar")
    class Otro(texto: String) : ErrorDeAutorizacion(texto)
}

class PermissionOverrideRepository @Inject constructor(private val api: PermissionOverrideApi) {

    suspend fun pedirToken(venueId: String, pin: String, permiso: String, operationKey: String? = null): Result<String> {
        val resp = try {
            api.crear(venueId, PedirTokenBody(pin, permiso, operationKey))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Result.failure(ErrorDeAutorizacion.SinConexion)
        }
        if (resp.isSuccessful) {
            val token = resp.body()?.data?.token
            return if (token.isNullOrBlank()) Result.failure(ErrorDeAutorizacion.Otro(OTRO)) else Result.success(token)
        }
        val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
        val error = when {
            resp.code() == 429 -> ErrorDeAutorizacion.DemasiadosIntentos
            else -> when (parseBackendErrorCode(raw)) {
                "OVERRIDE_INVALID_PIN" -> ErrorDeAutorizacion.PinIncorrecto
                "OVERRIDE_INSUFFICIENT" -> ErrorDeAutorizacion.SinEstePermiso
                "OVERRIDE_DISABLED" -> ErrorDeAutorizacion.PinApagado
                else -> if (resp.code() == 401) ErrorDeAutorizacion.SesionVencida
                else ErrorDeAutorizacion.Otro(parseBackendErrorMessage(raw, OTRO).ifBlank { OTRO })
            }
        }
        return Result.failure(error)
    }

    /** ¿Está prendido el PIN de gerente en este negocio? `null` si no se sabe (sin red, error, panel fallido, campo ausente). */
    suspend fun pinPrendido(venueId: String): Boolean? {
        val resp = try {
            api.settings(venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (!resp.isSuccessful) return null
        val d = resp.body()?.data ?: return null
        if (d.componentsStatus?.promotionsPanel == "failed") return null
        return d.managerPinOverrideEnabled
    }

    private companion object { const val OTRO = "No se pudo autorizar. Intenta otra vez" }
}
