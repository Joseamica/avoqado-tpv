package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.red.UnSoloUso
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * La ruta del reverso del SERVIDOR, `/resultado` (CON §2.2). Arma su PROPIO cliente con `OkHttpClient.Builder()` nuevo (R1-3,
 * R3-3): nunca el de la app ni un `newBuilder()` de uno ajeno, que en OkHttp 4.12.0 hereda `Dispatcher`, `EventListener`,
 * `CookieJar`, interceptores (el log `BODY`) y el autenticador. El JWT lo pone [credencial]. `baseUrl` es el origen
 * (`https://api.avoqado.io/`); la ruta `api/v1/tpv/…` se agrega aquí. Nunca registra URL ni cuerpos, y de las respuestas
 * sólo saca campos con forma verificada.
 *
 * @param pinner el de la app (`NetworkModule.provideCertificatePinner`), para que la fase 3 conserve el pinning.
 */
internal class ReversoDelServidorHttp(
    baseUrl: String,
    private val credencial: CredencialDelServidor,
    pinner: CertificatePinner? = null,
    /** El plazo total de una entrega de `/resultado` (R4-4). Sólo las pruebas lo cambian. */
    private val esperaDelResultado: Duration = 10.seconds,
) : ReversoDelServidor {

    private val base: HttpUrl = baseUrl.toHttpUrl()

    /**
     * `internal` sólo para la prueba del cliente efectivo. `@JvmSynthetic` (ola final, 4ª parte · 6): desde Java su accesor
     * dejaba ver las llamadas en curso, con su JWT.
     */
    @get:JvmSynthetic internal val cliente: OkHttpClient = OkHttpClient.Builder()
        // Propio y sin cola por host: el reloj de OkHttp arranca al ejecutar, así que ejecutar ≈ pedir.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
        .eventListener(EventListener.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply { if (pinner != null) certificatePinner(pinner) }
        .build()

    /**
     * Ola final, 4ª parte · 5 (r25): una credencial que LANZA es lo mismo que ninguna (`null`): la entrega se reintenta. La
     * cancelación no se traga.
     */
    private fun vigente(): String? = try {
        credencial.vigente()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** El 401 renueva el JWT (la app lo guarda) y la entrega SIGUIENTE ya sale con él; el mapeo de lo demás no cambia. */
    override suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado {
        val token = vigente() ?: return AcuseDelResultado.Reintentar
        val cuerpo = JsonObject().apply {
            r.descripcion?.let { addProperty("descripcion", it) }
            r.codigo?.let { addProperty("codigo", it) }
            r.operationNumber?.let { addProperty("operationNumber", it) }
            addProperty("reversadoEn", r.reversadoEn)
        }
        // R4-4: plazo total; una entrega lenta no detiene el barrido. Con las cabeceras y sin cuerpo, un 200 se reintenta.
        val c = post(ruta(venueId, id, "resultado"), cuerpo.toString(), token, esperaDelResultado) ?: return AcuseDelResultado.Reintentar
        // 401 = token vencido (rutina en una terminal): se renueva por su CÓDIGO, antes de validar el cuerpo (R7-2), y la cola
        // lo vuelve a mandar. Si fuera final con V quedaría CONCILIAR y un REVERSADA confirmado nunca llegaría (fix r1 D1).
        if (c.http == 401) {
            renovado(token, ESPERA_DE_RENOVAR)
            return AcuseDelResultado.Reintentar
        }
        return try {
            acuse(c, id)
        } catch (_: CampoInvalido) {
            AcuseDelResultado.Reintentar // R6-2: un campo presente con forma inválida invalida el acuse; se vuelve a mandar
        }
    }

    private fun acuse(c: Contestacion, id: AttemptId): AcuseDelResultado {
        val code = c.json.codigo()
        return when {
            c.http == 200 -> {
                val estado = c.json.estado()
                val evento = c.json?.texto("eventLogId", ID)
                if (estado == null || evento == null || c.json?.texto("attemptId", ID) != id.valor) AcuseDelResultado.Reintentar
                else AcuseDelResultado.Guardado(estado, evento, c.json?.texto("paymentId", ID))
            }
            c.http == 409 && code == "REVERSAL_RESULT_CONFLICT" ->
                c.json.estado()?.let(AcuseDelResultado::Conflicto) ?: AcuseDelResultado.Final(c.http, code)
            c.http == 404 -> AcuseDelResultado.NoEncontrado
            c.http == 408 || c.http == 429 || c.http >= 500 -> AcuseDelResultado.Reintentar
            else -> AcuseDelResultado.Final(c.http, code)
        }
    }

    /** Un JWT nuevo dentro de [espera]. Un `renovar` que lanza cuenta como `null`; la cancelación sí se propaga. */
    private suspend fun renovado(rechazado: String, espera: Duration): String? = try {
        withTimeoutOrNull(espera) { credencial.renovar(rechazado, espera) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun ruta(venueId: String, id: AttemptId, final: String): HttpUrl = base.newBuilder()
        .addPathSegments("api/v1/tpv/venues").addPathSegment(venueId)
        .addPathSegments("terminal-payment/attempts").addPathSegment(id.valor)
        .addPathSegment("reverso").addPathSegment(final)
        .build()

    /** El HTTP y el JSON (null si no parsea). null si no hubo respuesta: sin red, cortada o tiempo vencido. */
    private class Contestacion(val http: Int, val json: JsonObject?) {
        override fun toString() = "Contestacion(http=$http)"
    }

    /**
     * Con `espera`, UN solo reloj decide (r2 familia B): el de esta corrutina, que arranca al pedir. El de OkHttp (`timeout()`)
     * queda sólo de red de seguridad. Lo que llegó se guarda en [llegada] ANTES de leer el cuerpo: si el reloj vence con las
     * cabeceras ya aquí, sale el código sin cuerpo; sin cabeceras, `null` = no hubo respuesta.
     * El cuerpo es de un solo uso: OkHttp nunca lo repite (un 503 con `Retry-After: 0` da UN POST).
     */
    private suspend fun post(url: HttpUrl, cuerpo: String, token: String, espera: Duration): Contestacion? {
        val peticion = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .post(UnSoloUso(cuerpo.toRequestBody(JSON))).build()
        val llamada = cliente.newCall(peticion)
        llamada.timeout().timeout(espera.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        val llegada = AtomicReference<Contestacion?>(null)
        val enCurso: suspend () -> Contestacion? = {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { llamada.cancel() }
                llamada.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) = cont.resume(null)

                    override fun onResponse(call: Call, response: Response) {
                        // Hubo HTTP: el código cuenta aunque el cuerpo no se pueda leer.
                        llegada.set(Contestacion(response.code, null))
                        val json = runCatching { response.use { leer(it.peekBody(MAXIMO_CUERPO).string()) } }.getOrNull()
                        val c = Contestacion(response.code, json)
                        llegada.set(c)
                        cont.resume(c)
                    }
                })
            }
        }
        return withTimeoutOrNull(espera) { enCurso() } ?: llegada.get()
    }

    /** Un campo presente con forma inválida: invalida la respuesta entera (R6-2). Sin mensaje: nunca lleva el valor. */
    private class CampoInvalido : Exception()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAXIMO_CUERPO = 64L * 1024
        /** Lo que espera `/resultado` a que la app renueve el JWT tras un 401; la entrega siguiente lo usa. */
        val ESPERA_DE_RENOVAR = 10.seconds
        val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        /** Los `code` del SERVIDOR (`REVERSAL_RESULT_CONFLICT`…): sólo letras y guiones bajos. */
        val CODIGO = Regex("^[A-Z_]{1,64}$")

        fun leer(s: String): JsonObject? = runCatching { JsonParser.parseString(s).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

        fun JsonObject?.codigo(): String? = this?.texto("code", CODIGO)

        fun JsonObject?.estado(): EstadoDelReversoEnServidor? =
            this?.texto("estado", CODIGO_MINUSCULAS)?.let { s -> EstadoDelReversoEnServidor.entries.firstOrNull { it.name.lowercase() == s } }

        val CODIGO_MINUSCULAS = Regex("^[a-z_]{1,32}$")

        /**
         * R6-2: el campo, o `null` si no está (o es `null` de JSON). PRESENTE y con otra forma ⇒ [CampoInvalido], que invalida
         * la respuesta entera: nunca se lee como ausente, ni como cero.
         */
        private fun <T> JsonObject.siEsta(nombre: String, lector: (JsonElement) -> T?): T? {
            val e = get(nombre)?.takeUnless(JsonElement::isJsonNull) ?: return null
            return lector(e) ?: throw CampoInvalido()
        }

        fun JsonObject.texto(campo: String, forma: Regex): String? =
            siEsta(campo) { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf(forma::matches) }
    }
}
