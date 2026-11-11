package com.jaac.avoqado_campo.red

import com.jaac.avoqado_campo.identidad.Instantanea
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.QuienSoy
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

enum class MotivoSinSesion { SIN_RED, NECESITA_PIN, OTRA_SUCURSAL, DESVINCULADO, DESCONOCIDO, DADO_DE_BAJA, ESPERA }

sealed interface ResultadoConSesion<T> {
    data class Respuesta<T>(val r: Response<T>) : ResultadoConSesion<T>
    class SinSesion<T>(val motivo: MotivoSinSesion, val reintentaEnMs: Long? = null) : ResultadoConSesion<T> {
        override fun toString() = "SinSesion($motivo, $reintentaEnMs)"
    }
}

/**
 * La sesión «sólo sale con 401/403»: toda llamada de `/tpv/campo/venues/:venueId/…` pasa por aquí. Un 401 de sesión vencida
 * renueva UNA vez (bajo el [MutexDeSesion] que comparte con `entrar`) y repite; lo demás de sesión se vuelve [ResultadoConSesion.SinSesion].
 * Nunca devuelve una respuesta que [clasificar] daría como `Sesion`. Sin red o 5xx al renovar, la sesión no se toca.
 */
@Singleton
class GuardiaDeSesion @Inject constructor(
    private val api: CampoApi,
    private val quienSoy: QuienSoy,
    private val mutexDeSesion: MutexDeSesion,
) {
    /**
     * [llamada] recibe `"Bearer <access>"`. [yo]: la instantánea de quien llama (r3); la sesión y la generación salen de ella.
     * Durante un borrado no se manda nada (`SIN_RED`: se repite solo).
     */
    suspend fun <T> conSesion(
        venueId: String, yo: Instantanea = quienSoy.instantanea(), llamada: suspend (bearer: String) -> Response<T>,
    ): ResultadoConSesion<T> {
        val generacion = yo.generacion
        val sesion = yo.sesion
        if (yo.limpiando) return sin(MotivoSinSesion.SIN_RED)
        if (sesion == null || yo.necesitaPin) return sin(MotivoSinSesion.NECESITA_PIN)
        if (sesion.venueId != venueId) return sin(MotivoSinSesion.OTRA_SUCURSAL)

        val r = intentar(sesion.accessToken, llamada) ?: return sin(MotivoSinSesion.SIN_RED)
        if (r.code() != 401 || codigoDeError(r) in CODIGOS_DEL_CELULAR) return cerrar(r, generacion)

        renovar(sesion.accessToken)?.let { return sin(it.first, it.second) }
        // Otra activación (o un borrado) mientras se renovaba: la pieza vieja no sale con el token de la nueva.
        val ahora = quienSoy.instantanea()
        if (ahora.generacion != generacion || ahora.limpiando) return sin(MotivoSinSesion.SIN_RED)
        val vigente = ahora.sesion ?: return sin(MotivoSinSesion.NECESITA_PIN)
        if (vigente.venueId != venueId) return sin(MotivoSinSesion.OTRA_SUCURSAL)
        val r2 = intentar(vigente.accessToken, llamada) ?: return sin(MotivoSinSesion.SIN_RED)
        return cerrar(r2, generacion)
    }

    private suspend fun <T> intentar(access: String, llamada: suspend (String) -> Response<T>): Response<T>? =
        (llamarSeguro { llamada("Bearer $access") } as? Llamada.Contesto)?.r

    /**
     * null = ya hay un token para repetir (lo renovó éste o alguien más); si no, el motivo y la espera.
     * Si bajo el candado el access guardado ya no es [accessUsado], otro ya renovó (o entró): no se renueva otra vez.
     */
    private suspend fun renovar(accessUsado: String): Pair<MotivoSinSesion, Long?>? = mutexDeSesion.mutex.withLock {
        val yo = quienSoy.instantanea() // r3: sesión, credencial y generación de la misma persona
        if (yo.limpiando) return@withLock MotivoSinSesion.SIN_RED to null
        val generacion = yo.generacion
        val actual = yo.sesion ?: return@withLock MotivoSinSesion.NECESITA_PIN to null
        if (actual.accessToken != accessUsado) return@withLock null
        // Quien esperaba detrás de un renovar que ya pidió el PIN no vuelve a pegarle al servidor.
        if (yo.necesitaPin) return@withLock MotivoSinSesion.NECESITA_PIN to null
        val credencial = yo.credencial ?: return@withLock MotivoSinSesion.DESCONOCIDO to null
        val refreshUsado = actual.refreshToken

        val r = (llamarSeguro { api.renovar(credencial, RenovarBody(refreshUsado)) } as? Llamada.Contesto)?.r
            ?: return@withLock MotivoSinSesion.SIN_RED to null // sin red o ilegible (portal cautivo, `{}`)
        val nuevo = r.body()
        when {
            r.isSuccessful && nuevo != null -> {
                // Si devuelve false, otra sesión ganó: se repite con el token vigente sin renovar.
                quienSoy.guardarTokensSi(generacion, refreshUsado, nuevo.accessToken!!, nuevo.refreshToken!!, nuevo.venueId!!)
                null
            }
            r.code() == 429 -> MotivoSinSesion.ESPERA to (esperaDe(r.headers()["Retry-After"]) ?: 60_000L)
            // Sólo un 401 con su código toca «Quién soy». Lo demás (400, 403/404/407/408 de un proxy, 401 sin código)
            // es «algo en medio», no el servidor: sin red, con tokens y banderas intactos.
            r.code() != 401 -> MotivoSinSesion.SIN_RED to null
            else -> when (codigoDeError(r)) {
                "CELULAR_DESVINCULADO" -> desvinculado(generacion) to null
                "CELULAR_DESCONOCIDO" -> desconocido(generacion) to null
                "SESION_INVALIDA", "SIN_ACCESO_A_LA_SUCURSAL" -> necesitaPin(generacion) to null
                else -> MotivoSinSesion.SIN_RED to null
            }
        }
    }

    private fun <T> cerrar(r: Response<T>, generacion: String): ResultadoConSesion<T> {
        val code = codigoDeError(r)
        return when {
            r.code() == 401 -> sin(
                when (code) {
                    "CELULAR_DESVINCULADO" -> desvinculado(generacion)
                    "CELULAR_DESCONOCIDO" -> desconocido(generacion)
                    else -> necesitaPin(generacion) // un 401 tras renovar: la sesión murió
                },
            )
            r.code() == 403 && code == "PERSONA_DADA_DE_BAJA" -> sin(dadoDeBaja(generacion))
            r.code() == 403 && code == "SIN_ACCESO_A_LA_SUCURSAL" -> {
                necesitaPin(generacion) // R5-1: la pieza va a «Mis pendientes» (Definitiva) y el PIN se pide
                ResultadoConSesion.Respuesta(r)
            }
            else -> ResultadoConSesion.Respuesta(r)
        }
    }

    private fun necesitaPin(generacion: String): MotivoSinSesion {
        quienSoy.guardarSi(generacion) { marcarSesionMuerta() }
        return MotivoSinSesion.NECESITA_PIN
    }
    private fun desvinculado(generacion: String): MotivoSinSesion {
        quienSoy.guardarSi(generacion) { marcarDesvinculado(null) }
        return MotivoSinSesion.DESVINCULADO
    }
    /** La cola pasa a la gracia: el estado se guarda aquí (I1 lo permite desde VINCULADO, no desde DESVINCULADO). */
    private fun dadoDeBaja(generacion: String): MotivoSinSesion {
        quienSoy.guardarSi(generacion) { marcarDadoDeBaja() }
        return MotivoSinSesion.DADO_DE_BAJA
    }
    private fun desconocido(generacion: String): MotivoSinSesion {
        quienSoy.guardarSi(generacion) { marcarDesconocido() }
        return MotivoSinSesion.DESCONOCIDO
    }

    private fun <T> sin(motivo: MotivoSinSesion, ms: Long? = null) = ResultadoConSesion.SinSesion<T>(motivo, ms)

    /** El `code` sin gastar el cuerpo de error (Retrofit ya lo tiene en memoria): quien llama lo vuelve a leer completo. */
    private fun codigoDeError(r: Response<*>): String? = codigoDe(r.errorBody()?.source()?.peek()?.readUtf8())

    private companion object { val CODIGOS_DEL_CELULAR = setOf("CELULAR_DESVINCULADO", "CELULAR_DESCONOCIDO") }
}
