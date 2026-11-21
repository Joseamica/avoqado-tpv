package com.avoqado.cobropropio.red

import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.SobreDeBlumon
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.time.Duration

/** Los dos orígenes de Blumon. Sólo HTTPS; `core` termina en `/` y las rutas se le pegan. */
data class Endpoints(val tokener: String, val core: String) {
    init {
        require(tokener.startsWith("https://")) { "tokener sin https" }
        require(core.startsWith("https://") && core.endsWith("/")) { "core sin https o sin barra final" }
    }

    companion object {
        val PRODUCCION = Endpoints("https://tokener.blumonpay.net/oauth/token", "https://core.blumonpay.net/")
        val SANDBOX = Endpoints("https://sandbox-tokener.blumonpay.net/oauth/token", "https://sandbox-core.blumonpay.net/")
    }
}

sealed interface Respuesta {
    /** Lo que contestó Blumon. El cuerpo crudo nunca sale de aquí: sólo campos saneados y con su forma verificada. */
    class Http(val codigo: Int, private val cuerpo: String) : Respuesta {
        /** El sobre ya leído por el lector estricto; `null` si el cuerpo no es UN objeto JSON estricto. */
        internal fun sobre(): SobreDeBlumon? = apariciones()?.let { SobreDeBlumon.de(it) }

        /** Para los lectores tipados de la sesión (`ClienteBlumon`): nunca un `JsonObject`. */
        @JvmSynthetic internal fun apariciones(): Apariciones? = Apariciones.leer(cuerpo)

        override fun toString() = "Http(codigo=$codigo)"
    }

    /** Cortada o sin contestar. `clase` es fija (`TIEMPO`, `TLS`, `RED`, `CUERPO_GRANDE`): nunca URL ni cuerpo. */
    data class SinRespuesta(val clase: String) : Respuesta

    /** Con evidencia de cero bytes escritos: rechazada antes de salir, o el plazo venció antes del primer byte. */
    data object NoEnviada : Respuesta
}

/**
 * El único que habla HTTP con Blumon (rescate de `lectorlab/PilotHttp.kt`). Su propio `OkHttpClient`: sin interceptores,
 * autenticador, proxy, reintentos ni redirecciones; sólo HTTP/1.1 y sin reusar conexiones. Cada cuerpo sale UNA vez.
 * Nunca registra URL ni cuerpo.
 */
class EnvioUnico internal constructor(
    private val endpoints: Endpoints,
    private val ahora: () -> Long,
    internal val confianzaDePrueba: X509TrustManager?,
    private val dns: Dns,
) {
    constructor(endpoints: Endpoints, ahora: () -> Long) : this(endpoints, ahora, null, Dns.SYSTEM)

    /** Sólo pruebas: se llama dentro del socket justo antes de revisar el plazo de cada `write`. */
    @Volatile internal var ganchoAntesDeEscribir: (bytesEscritos: Long) -> Unit = {}

    /** Sólo pruebas: los bytes de la petición que se intentaron escribir en el último envío. */
    @Volatile internal var bytesDelUltimoEnvio: Long = -1

    private val confianza: X509TrustManager = confianzaDePrueba
        ?: TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    private val fabricaTls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(confianza), null) }.socketFactory

    /** `internal` sólo para la prueba del cliente efectivo: el `newBuilder()` de cada llamada copia ESTE. */
    @get:JvmSynthetic internal val base = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 }) // propio y sin cola por host
        .eventListener(EventListener.NONE)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .dns(dns)
        .build()

    /**
     * Venta, cancelación, reverso y la sesión. `noEmpezarDespuesDe` (reloj `ahora`): ni un byte después. [alLeer] recibe lo
     * que llegó (o el corte) en el hilo de OkHttp, ANTES de reanudar a quien llamó (R3-2).
     */
    suspend fun post(
        url: String,
        autorizacion: String?,
        cuerpo: CuerpoDeBlumon,
        espera: Duration,
        noEmpezarDespuesDe: Long?,
        alLeer: (Respuesta) -> Unit = {},
    ): Respuesta = enviar(url, autorizacion, cuerpo.comoRequestBody(), espera, noEmpezarDespuesDe, alLeer)

    /** El token del sandbox, que va por formulario. */
    suspend fun postFormulario(url: String, autorizacion: String?, campos: Map<String, String>, espera: Duration): Respuesta =
        enviar(url, autorizacion, FormBody.Builder().apply { campos.forEach { (k, v) -> add(k, v) } }.build(), espera, null) {}

    private suspend fun enviar(
        url: String,
        autorizacion: String?,
        cuerpo: RequestBody,
        espera: Duration,
        noEmpezarDespuesDe: Long?,
        alLeer: (Respuesta) -> Unit,
    ): Respuesta {
        val destino = url.toHttpUrlOrNull()?.takeIf(::permitida) ?: return Respuesta.NoEnviada
        val inicio = ahora()
        if (noEmpezarDespuesDe != null && inicio >= noEmpezarDespuesDe) return Respuesta.NoEnviada
        val esperaMs = espera.inWholeMilliseconds.coerceAtLeast(1)
        val restanteMs = noEmpezarDespuesDe?.let { it - inicio } ?: esperaMs
        val medidor = Medidor(noEmpezarDespuesDe, ahora, ganchoAntesDeEscribir)
        val cliente = base.newBuilder()
            .sslSocketFactory(FabricaConPlazo(fabricaTls, medidor), confianza)
            .connectTimeout(minOf(CONEXION_MS, esperaMs), TimeUnit.MILLISECONDS)
            .writeTimeout(restanteMs, TimeUnit.MILLISECONDS)
            .readTimeout(esperaMs, TimeUnit.MILLISECONDS)
            .callTimeout(minOf(esperaMs, restanteMs), TimeUnit.MILLISECONDS)
            .build()
        val peticion = Request.Builder().url(destino).post(UnSoloUso(cuerpo))
            .apply { if (autorizacion != null) header("Authorization", autorizacion) }
            .header("User-Agent", "Avoqado-CobroPropio")
            .build()
        val llamada = cliente.newCall(peticion)

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { llamada.cancel() }
            llamada.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    entregar(corte(e, medidor))
                }

                fun entregar(r: Respuesta) {
                    runCatching { alLeer(r) } // nunca deja a la corrutina sin reanudar
                    cont.resume(r)
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use { leerCuerpo(it) }
                    } catch (e: Exception) { // la corrutina siempre se reanuda, y sin el texto de la excepción
                        corte(e as? IOException ?: IOException(), medidor)
                    }
                    bytesDelUltimoEnvio = medidor.bytesEscritos.get()
                    entregar(r)
                }
            })
        }
    }

    /** `NoEnviada` sólo con cero bytes Y el plazo vencido; cualquier otro corte es duda. */
    private fun corte(e: IOException, medidor: Medidor): Respuesta {
        val escritos = medidor.bytesEscritos.get()
        bytesDelUltimoEnvio = escritos
        if (escritos == 0L && medidor.vencido()) return Respuesta.NoEnviada
        return Respuesta.SinRespuesta(
            when (e) {
                is InterruptedIOException -> "TIEMPO"
                is SSLException -> "TLS"
                else -> "RED"
            },
        )
    }

    private fun leerCuerpo(response: Response): Respuesta {
        val fuente = response.body?.source() ?: return Respuesta.Http(response.code, "")
        if (fuente.request(LIMITE_DEL_CUERPO + 1)) return Respuesta.SinRespuesta("CUERPO_GRANDE")
        return Respuesta.Http(response.code, fuente.readUtf8())
    }

    /** Sólo HTTPS, el origen exacto del tokener (con su ruta) o del core (con una ruta de Blumon y sin query). */
    private fun permitida(url: HttpUrl): Boolean {
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
        val tokener = endpoints.tokener.toHttpUrl()
        val core = endpoints.core.toHttpUrl()
        fun mismoOrigen(otra: HttpUrl) = url.scheme == otra.scheme && url.host == otra.host && url.port == otra.port
        if (mismoOrigen(tokener) && url.encodedPath == tokener.encodedPath) return true
        return mismoOrigen(core) && url.query == null && url.encodedPath.startsWith(core.encodedPath) &&
            RUTAS_DEL_CORE.matches(url.encodedPath.removePrefix(core.encodedPath))
    }

    private companion object {
        const val CONEXION_MS = 3_000L
        const val LIMITE_DEL_CUERPO = 64L * 1024
        val RUTAS_DEL_CORE = Regex(
            "device/(?:init|getKey|initDukptKeys|validateCancelation)|retail/present/(?:charge|reverse/|cancel/[1-9][0-9]{0,9})",
        )
    }
}

/** OkHttp nunca lo puede volver a mandar (p. ej. tras un 503 con `Retry-After: 0`). Lo usan Blumon y el SERVIDOR. */
internal class UnSoloUso(private val d: RequestBody) : RequestBody() {
    private val usado = AtomicBoolean(false)
    override fun contentType() = d.contentType()
    override fun contentLength() = d.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        if (!usado.compareAndSet(false, true)) throw IOException("cuerpo de un solo uso")
        d.writeTo(sink)
    }
}
