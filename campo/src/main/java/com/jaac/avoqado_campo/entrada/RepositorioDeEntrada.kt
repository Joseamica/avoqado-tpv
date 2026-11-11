package com.jaac.avoqado_campo.entrada

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_campo.BuildConfig
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.ACTIVACION_PENDIENTE
import com.jaac.avoqado_campo.identidad.AlmacenSeguro
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Instantanea
import com.jaac.avoqado_campo.identidad.MutexDeSesion
import com.jaac.avoqado_campo.identidad.PinLocal
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.ResultadoPinLocal
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.ActivarBody
import com.jaac.avoqado_campo.red.Aparato
import com.jaac.avoqado_campo.red.CampoApi
import com.jaac.avoqado_campo.red.EntrarBody
import com.jaac.avoqado_campo.red.Llamada
import com.jaac.avoqado_campo.red.aDatos
import com.jaac.avoqado_campo.red.esperaDe
import com.jaac.avoqado_campo.red.llamarSeguro
import com.jaac.avoqado_campo.red.objetoJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import kotlin.coroutines.cancellation.CancellationException

sealed interface ResultadoEntrada {
    object Dentro : ResultadoEntrada
    data class DentroSinSenal(val sucursal: String) : ResultadoEntrada
    data class EligeSucursal(val sucursales: List<Sucursal>) : ResultadoEntrada
    data class Rechazo(val mensaje: String, val intentosRestantes: Int?) : ResultadoEntrada
    data class Bloqueado(val reintentaEnSeg: Long) : ResultadoEntrada
    object ColaNoVacia : ResultadoEntrada
    object SinSenalSinVerificador : ResultadoEntrada
}

private data class ActivacionGuardada(val cuerpo: ActivarBody, val guardadaEn: Long)

/**
 * Activar el celular y entrar con el PIN. Las dos corren bajo el [MutexDeSesion] (R3-3, R7-2): nunca hay dos en vuelo, ni con
 * un `renovar`. La copia se refresca DESPUÉS de soltar el candado (su descarga puede renovar, que toma el mismo candado) y en
 * el alcance de la app: `Dentro` no espera esa descarga.
 */
class RepositorioDeEntrada(
    private val api: CampoApi,
    private val quienSoy: QuienSoy,
    private val reloj: RelojAnclado,
    private val pinLocal: PinLocal,
    private val mutexDeSesion: MutexDeSesion,
    private val dao: PiezaDao,
    private val almacen: AlmacenSeguro,
    private val copia: RepositorioDeCopia,
    /** Para refrescar la copia sin hacer esperar a la pantalla (`@AlcanceDeLaApp`). */
    private val alcance: CoroutineScope,
    private val aparato: (aparatoId: String) -> Aparato = {
        Aparato.de(it, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME)
    },
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    private val gson = Gson()

    /** Hay un cuerpo de activación cuyo resultado no se supo (B14): la pantalla dice «Terminando la activación anterior…». */
    fun activacionPendiente(): Boolean = pendiente() != null

    suspend fun activar(codigo: String, pin: String): ResultadoEntrada {
        val r = mutexDeSesion.mutex.withLock { activarConCandado(codigo, pin) }
        if (r == ResultadoEntrada.Dentro) refrescarCopia()
        return r
    }

    suspend fun entrar(pin: String, venueId: String?): ResultadoEntrada {
        val r = mutexDeSesion.mutex.withLock { entrarConCandado(pin, venueId) }
        if (r == ResultadoEntrada.Dentro) refrescarCopia()
        return r
    }

    private suspend fun activarConCandado(codigo: String, pin: String): ResultadoEntrada {
        // r10/r11: nunca con la marca de un borrado puesta. Con R-D (fail-stop del almacén) un borrado confirmado que no terminó
        // sólo se encuentra al ARRANCAR, y `terminarSiQuedoAMedias` lo termina antes de cualquier pantalla: esto es la defensa.
        if (quienSoy.limpiando) return NO_SE_PUDO_BORRAR
        // B6 y rediseño v7: se activa sólo desde un celular vacío (`BorradoDelCelular` deja SIN_ACTIVAR y la cola vacía).
        if (dao.cuantasVivas() > 0) return ResultadoEntrada.ColaNoVacia
        if (quienSoy.estado != EstadoDelCelular.SIN_ACTIVAR) return ResultadoEntrada.Rechazo("Este celular ya está activado.", null)

        // B14: si hay un cuerpo cuyo resultado no se supo, va ESE (aunque la pantalla traiga otro), con su hora original.
        val cuerpo = pendiente()?.cuerpo
            ?: ActivarBody(codigo.filterNot(Char::isWhitespace).uppercase(), pin, aparato(quienSoy.aparatoId))
                .also { guardarPendiente(ActivacionGuardada(it, ahora())) }

        val r = (llamarSeguro { api.activar(cuerpo) } as? Llamada.Contesto)?.r
        if (r == null || resultadoDesconocido(r)) return SIN_SENAL_AL_ACTIVAR // se conserva el cuerpo
        if (!r.isSuccessful) {
            olvidarPendiente()
            return rechazo(r)
        }

        val b = r.body()!! // llamarSeguro ya lo validó
        runCatching { reloj.anclar(b.horaDelServidor!!) }
        // r11 (R-D): la activación y el olvido del cuerpo van en UNA escritura. Si no se guarda, la app se detiene y al reabrir
        // sigue SIN_ACTIVAR con el MISMO cuerpo (B14): el reintento lo repite y el SERVIDOR contesta lo mismo.
        quienSoy.guardarActivacion(b.aDatos())
        return if (quienSoy.credencial == b.credencial) ResultadoEntrada.Dentro
        else ResultadoEntrada.Rechazo("No se pudo activar. Intenta de nuevo.", null)
    }

    private suspend fun entrarConCandado(pin: String, venueId: String?): ResultadoEntrada {
        // R6-A1 + r3: credencial y generación de UNA instantánea, leída ANTES de llamar.
        val yo = quienSoy.instantanea()
        if (yo.limpiando) return ResultadoEntrada.Rechazo("Espera un momento.", null)
        val credencial = yo.credencial ?: return ResultadoEntrada.Rechazo("Este celular no está activado.", null)
        val generacion = yo.generacion
        val r = (llamarSeguro { api.entrar(credencial, EntrarBody(pin, venueId)) } as? Llamada.Contesto)?.r
        // Sin red, portal cautivo o el SERVIDOR caído: se entra con el PIN guardado.
        if (r == null || resultadoDesconocido(r)) return entrarSinSenal(pin, venueId, yo)

        if (r.isSuccessful) {
            val b = r.body()!!
            val d = b.aDatos()
            var guardada = false
            // `guardarEntrada` quita `bloqueada` y `necesitaPin` (R3-2: sólo un `entrar` con señal quita la segunda).
            quienSoy.guardarSi(generacion) { guardarEntrada(d); guardada = sesion?.accessToken == d.sesion.accessToken }
            if (!guardada) return ResultadoEntrada.Rechazo("No se pudo entrar. Intenta de nuevo.", null)
            runCatching { reloj.anclar(b.horaDelServidor!!) }
            pinLocal.reiniciar()
            return ResultadoEntrada.Dentro
        }
        val json = objetoJson(r.errorBody()?.string())
        when (json.texto("code")) {
            "CELULAR_DESVINCULADO" -> quienSoy.guardarSi(generacion) { marcarDesvinculado(null) }
            "CELULAR_DESCONOCIDO" -> quienSoy.guardarSi(generacion) { marcarDesconocido() }
        }
        return rechazo(r, json)
    }

    /**
     * Contra el verificador de la sucursal; abre con la sesión que ya había y NO toca `necesitaPin` (R3-2). Esa sesión es de
     * UNA sucursal: sin señal no se cambia, así que elegir otra se rechaza antes del PIN (no gasta intentos).
     */
    private suspend fun entrarSinSenal(pin: String, venueId: String?, yo: Instantanea): ResultadoEntrada {
        val sucursales = yo.sucursales
        val actual = yo.sesion?.venueId
        if (venueId != null && actual != null && venueId != actual) {
            // Nunca el id; y «puedes entrar a» sólo si de verdad se puede (su sucursal con verificador, que un latido pudo quitar).
            fun nombre(id: String) = sucursales.find { it.venueId == id }?.nombre?.takeIf { it.isNotBlank() }
            val suya = sucursales.find { it.venueId == actual && it.verificadorPin != null }?.let { nombre(actual) ?: "tu sucursal" }
            return ResultadoEntrada.Rechazo(
                "Para entrar a ${nombre(venueId) ?: "otra sucursal"} necesitas señal." + (suya?.let { " Sin señal puedes entrar a $it." } ?: ""),
                null,
            )
        }
        val destino = venueId ?: actual ?: sucursales.singleOrNull()?.venueId
        val sucursal = sucursales.find { it.venueId == destino }
        val verificador = sucursal?.verificadorPin ?: return ResultadoEntrada.SinSenalSinVerificador
        return when (val p = pinLocal.verificar(pin, verificador)) {
            ResultadoPinLocal.Abre ->
                if (quienSoy.guardarSi(yo.generacion) { marcarBloqueada(false) }) ResultadoEntrada.DentroSinSenal(sucursal.nombre)
                else ResultadoEntrada.Rechazo("No se pudo entrar. Intenta de nuevo.", null)
            is ResultadoPinLocal.Incorrecto -> ResultadoEntrada.Rechazo(textoPinIncorrecto(p.intentosAntesDeEsperar), p.intentosAntesDeEsperar)
            is ResultadoPinLocal.Bloqueado -> ResultadoEntrada.Bloqueado(p.reintentaEnSeg)
        }
    }

    /** Best-effort y sin esperar: «Mis SIMs» se vuelve a bajar en el siguiente contacto si esto falla. */
    private fun refrescarCopia() {
        val venueId = quienSoy.sesion?.venueId ?: return
        alcance.launch {
            try {
                copia.actualizar(venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Entrada", "no se pudo refrescar la copia: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Los textos fijos de los códigos del bloque 1; lo demás, el `message` del SERVIDOR. */
    private fun rechazo(r: Response<*>, json: JsonObject? = objetoJson(r.errorBody()?.string())): ResultadoEntrada {
        val mensaje = json.texto("message")
        return when (json.texto("code")) {
            "CODIGO_INVALIDO" -> ResultadoEntrada.Rechazo("Ese código no existe. Pide uno nuevo a tu supervisor.", null)
            "CODIGO_USADO" -> ResultadoEntrada.Rechazo("Ese código ya se usó. Pide uno nuevo.", null)
            "CODIGO_VENCIDO", "CODIGO_ANULADO" -> ResultadoEntrada.Rechazo("Ese código ya no sirve. Pide uno nuevo.", null)
            "PIN_INCORRECTO" -> {
                // activar manda `intentosRestantes`; entrar, `intentosAntesDeEsperar`.
                val n = json.entero("intentosRestantes") ?: json.entero("intentosAntesDeEsperar")
                ResultadoEntrada.Rechazo(n?.let(::textoPinIncorrecto) ?: "PIN incorrecto.", n)
            }
            "SIN_PIN" -> ResultadoEntrada.Rechazo(mensaje ?: "Todavía no tienes PIN. Avísale a tu supervisor.", null)
            "SIN_SUCURSAL" -> ResultadoEntrada.Rechazo("No tienes sucursal asignada. Avísale a tu supervisor.", null)
            "PIN_BLOQUEADO" -> ResultadoEntrada.Bloqueado(
                json.entero("reintentaEn")?.toLong() ?: esperaDe(r.headers()["Retry-After"])?.div(1000) ?: 60L,
            )
            "ELIGE_SUCURSAL" -> ResultadoEntrada.EligeSucursal(sucursalesDe(json))
            else -> ResultadoEntrada.Rechazo(mensaje ?: "No se pudo (HTTP ${r.code()}).", null)
        }
    }

    /** Las de la respuesta, con el verificador que ya teníamos de cada una (para poder entrar sin señal después). */
    private fun sucursalesDe(json: JsonObject?): List<Sucursal> {
        val locales = quienSoy.sucursales.associateBy { it.venueId }
        return json?.get("sucursales")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val venueId = o.texto("venueId") ?: return@mapNotNull null
            Sucursal(venueId, o.texto("nombre") ?: venueId, locales[venueId]?.verificadorPin)
        }.orEmpty()
    }

    private fun pendiente(): ActivacionGuardada? {
        val texto = almacen.leer(ACTIVACION_PENDIENTE) ?: return null
        val g = runCatching { gson.fromJson(texto, ActivacionGuardada::class.java) }.getOrNull()
        // Gson no respeta los no-nulos de Kotlin; y a los 10 min (o con la hora movida hacia atrás) se borra solo.
        if (g?.cuerpo != null && ahora() - g.guardadaEn in 0 until DIEZ_MIN) return g
        olvidarPendiente()
        return null
    }

    private fun guardarPendiente(g: ActivacionGuardada) = almacen.escribir(mapOf(ACTIVACION_PENDIENTE to gson.toJson(g)))
    private fun olvidarPendiente() = almacen.escribir(mapOf(ACTIVACION_PENDIENTE to null))

    private companion object {
        const val DIEZ_MIN = 10 * 60 * 1000L
        val SIN_SENAL_AL_ACTIVAR = ResultadoEntrada.Rechazo("Para activar el celular necesitas señal.", null)
        val NO_SE_PUDO_BORRAR = ResultadoEntrada.Rechazo("No se pudo terminar de borrar el celular. Intenta otra vez.", null)

        /** Sin respuesta del SERVIDOR mismo: timeout (408), 5xx, o 1xx/3xx de algo en medio. Un 4xx sí es su respuesta. */
        fun resultadoDesconocido(r: Response<*>) = !r.isSuccessful && (r.code() == 408 || r.code() !in 400..499)

        fun textoPinIncorrecto(n: Int) = when (n) {
            0 -> "PIN incorrecto."
            1 -> "PIN incorrecto. Te queda 1 intento."
            else -> "PIN incorrecto. Te quedan $n intentos."
        }

        fun JsonObject?.texto(campo: String): String? = this?.get(campo)?.takeIf { it.isJsonPrimitive }?.asString
        fun JsonObject?.entero(campo: String): Int? =
            this?.get(campo)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    }
}
