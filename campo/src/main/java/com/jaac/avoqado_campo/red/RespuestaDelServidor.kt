package com.jaac.avoqado_campo.red

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.MalformedJsonException
import retrofit2.Response
import java.io.EOFException
import kotlin.coroutines.cancellation.CancellationException
import java.io.IOException

/** Qué hacer con lo que contestó el servidor a una pieza de la cola. `SinRed` lo arma quien llama ante un `IOException`. */
sealed interface RespuestaDelServidor {
    data class Ok(val status: Int, val cuerpo: String) : RespuestaDelServidor
    data class Reintentable(val code: String?, val reintentaEnMs: Long?) : RespuestaDelServidor
    data class Definitiva(val status: Int, val code: String?, val message: String, val datosJson: String) : RespuestaDelServidor
    data class Sesion(val status: Int, val code: String?) : RespuestaDelServidor
    object SinRed : RespuestaDelServidor
}

private val CODIGOS_REINTENTABLES = setOf(
    "VENTA_EN_PROCESO", "CUSTODIA_EN_PROCESO", "CHECADA_EN_PROCESO", "ENTRADA_EN_PROCESO", "RETENIDOS_LLENOS",
    "APLICACION_EN_CURSO", "RETENIDO_EN_PROCESO", "OTRA_SUCURSAL", "VERIFICATION_MOVED",
)

/** El cuerpo como objeto JSON, o null si no lo es (HTML de un portal cautivo, vacío, texto). */
internal fun objetoJson(cuerpo: String?): JsonObject? =
    runCatching { JsonParser.parseString(cuerpo ?: "") }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.texto(campo: String): String? = get(campo)?.takeIf { it.isJsonPrimitive }?.asString

/** El `code` de un cuerpo de error (`{ code, message, ... }` o `{ success:false, message, code }`). */
internal fun codigoDe(cuerpo: String?): String? = objetoJson(cuerpo)?.texto("code")

/** `Retry-After` en segundos → ms. */
internal fun esperaDe(retryAfter: String?): Long? = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)

fun clasificar(status: Int, cuerpo: String?, retryAfter: String?): RespuestaDelServidor {
    val json = objetoJson(cuerpo)
    val code = json?.texto("code")
    return when {
        status in 200..299 -> if (json != null) RespuestaDelServidor.Ok(status, cuerpo!!) else RespuestaDelServidor.Reintentable(null, null)
        status == 408 || status == 429 || status >= 500 -> RespuestaDelServidor.Reintentable(code, esperaDe(retryAfter)) // 408 = timeout: red
        status !in 400..499 -> RespuestaDelServidor.Reintentable(code, null) // 1xx/3xx: algo en medio, no el servidor
        code in CODIGOS_REINTENTABLES -> RespuestaDelServidor.Reintentable(code, null)
        status == 401 || (status == 403 && code == "PERSONA_DADA_DE_BAJA") -> RespuestaDelServidor.Sesion(status, code)
        else -> RespuestaDelServidor.Definitiva(status, code, json?.texto("message") ?: "No se pudo (HTTP $status)", if (json != null) cuerpo!! else "{}")
    }
}

/** Resultado de una llamada tipada. `Ilegible` (portal cautivo, 2xx incompleto) se trata como «sin red» y nunca toca «Quién soy». */
sealed interface Llamada<T> {
    data class Contesto<T>(val r: Response<T>) : Llamada<T>
    class SinRed<T> : Llamada<T>
    class Ilegible<T> : Llamada<T>
}

/** TODA llamada tipada pasa por aquí (P9, R2-6). */
suspend fun <T> llamarSeguro(llamada: suspend () -> Response<T>): Llamada<T> {
    val r = try {
        llamada()
    } catch (e: MalformedJsonException) { // es IOException: va antes
        return Llamada.Ilegible()
    } catch (e: EOFException) {
        return Llamada.Ilegible()
    } catch (e: IOException) {
        return Llamada.SinRed()
    } catch (e: JsonParseException) {
        return Llamada.Ilegible()
    } catch (e: CancellationException) { // también es IllegalStateException: no se traga
        throw e
    } catch (e: IllegalStateException) {
        // Sólo la de Gson («Expected BEGIN_OBJECT but was STRING»); un `check()` de quien llama es un bug y sube.
        if (e.stackTrace.firstOrNull()?.className?.startsWith("com.google.gson.") != true) throw e
        return Llamada.Ilegible()
    }
    if (r.isSuccessful) {
        val b = r.body()
        if (b == null || (b is Validable && !b.valida())) return Llamada.Ilegible()
    }
    return Llamada.Contesto(r)
}
