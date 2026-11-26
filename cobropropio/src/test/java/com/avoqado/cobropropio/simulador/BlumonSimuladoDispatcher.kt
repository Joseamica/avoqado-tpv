package com.avoqado.cobropropio.simulador

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.aHex
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_CON_ERROR
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_CON_ERROR_SUELTO
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_PARCIAL
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_SIN_ECO
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_TARDIO_ANTES_DEL_ARBITRAJE
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.APROBADO_TARDIO_DESPUES_DEL_ARBITRAJE
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.CANCELACION_CON_ECO_DE_LA_VENTA
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.CANCELACION_CON_ERROR
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.CANCELACION_NO_PERMITIDA
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.CANCELACION_SIN_RESPUESTA
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.CODIGO_68
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.RECHAZO_05
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.RECHAZO_CON_DATOS
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.RECHAZO_CON_HTTP_DE_TRANSPORTE
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.REVERSO_SIN_RESPUESTA_Y_TX007
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.SILENCIO_RQ006_DOS_VECES_Y_REVERSADA
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.SILENCIO_Y_REVERSADA_SIN_ECO
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.SILENCIO_Y_TX007
import com.avoqado.cobropropio.simulador.EscenarioDeBlumon.SIMULATION_TRUE
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher

/**
 * El Blumon simulado de las pruebas de punta a punta: contesta por HTTPS (con el certificado de prueba) lo que diga
 * [EscenarioDeBlumon] para los centavos de la venta, con las formas MEDIDAS en el sandbox (`recorrido-sandbox.md` §2.3 y
 * §2.10). La sesión (token, `init`, llave RSA e IPEK con su KCV) es la de siempre, para ESTA identidad.
 *
 * Registra sólo la ruta, la `reference` y el escenario que contestó, nunca el cuerpo: la venta lleva el PAN y la banda dentro
 * del TLS. Del cuerpo sólo lee, sin guardarlo, lo que necesita para contestar (la `reference`, el importe, `sendReverse` y lo de
 * la sesión). Sólo atiende POST: otro verbo da 405.
 */
internal class BlumonSimuladoDispatcher(
    private val identidad: Identidad,
    private val token: String,
    private val ipek: ByteArray,
) : Dispatcher() {

    /** `escenario`: el que contestó (el de la venta de ese folio); `null` en la sesión, un 404 o un 405. */
    data class Pedida(val ruta: String, val reference: String?, val escenario: EscenarioDeBlumon?)

    val registro = MutableStateFlow<List<Pedida>>(emptyList())

    /** Retiene la venta de los escenarios 50 y 55 hasta que la prueba la suelta. */
    val ventaTardia = CompletableDeferred<Unit>()

    private class Venta(val reference: String, val escenario: EscenarioDeBlumon)

    private val siguienteFolio = AtomicLong(84_000)

    /** El folio de Blumon de cada venta APROBADA (o parcial) ⇒ la venta: el preflight y la cancelación se eligen por ella. */
    private val ventas = ConcurrentHashMap<Long, Venta>()

    /** `reference` ⇒ el folio de su éxito (venta, parcial o cancelación). */
    private val folios = ConcurrentHashMap<String, Long>()

    /** `reference` de cada venta ⇒ su escenario: el reverso se elige por su venta, nunca por el importe de su propio cuerpo. */
    private val escenarios = ConcurrentHashMap<String, EscenarioDeBlumon>()
    private val vueltas = ConcurrentHashMap<String, AtomicInteger>()

    /** Peticiones con otro verbo que POST: se contestan 405 y se cuentan aquí. */
    val verbosIncorrectos = AtomicInteger()

    /** Las peticiones a [ruta] (de [id], si se da). [CANCELACION] cuenta las de cualquier folio. */
    fun pedidas(ruta: String, id: AttemptId? = null) = registro.value.filter { it.de(ruta, id) }

    /** La barrera: regresa cuando llegó una petición a [ruta] (de [id], si se da). */
    suspend fun esperar(ruta: String, id: AttemptId? = null) {
        registro.first { l -> l.any { it.de(ruta, id) } }
    }

    private fun Pedida.de(r: String, id: AttemptId?) =
        (ruta == r || (r == CANCELACION && ruta.startsWith(CANCELACION))) && (id == null || reference == id.valor)

    fun folioDe(id: AttemptId): Long? = folios[id.valor]

    /** Al cerrar: nada se queda retenido. */
    fun soltarTodo() {
        ventaTardia.complete(Unit)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val ruta = request.path.orEmpty().substringBefore('?')
        val texto = request.body.readUtf8()
        val pedido = runCatching { JsonParser.parseString(texto).asJsonObject }.getOrNull()
        val reference = pedido?.get("reference")?.takeIf { it.isJsonPrimitive }?.asString
        val ventaDelFolio = if (ruta.startsWith(CANCELACION)) ruta.removePrefix(CANCELACION).toLongOrNull()?.let(ventas::get) else null
        if (request.method != "POST") {
            verbosIncorrectos.incrementAndGet()
            registro.update { it + Pedida(ruta, reference, null) }
            return MockResponse().setResponseCode(405)
        }
        val escenario = when {
            ruta == VENTA && reference != null && pedido != null -> EscenarioDeBlumon.de(centavos(pedido)).also { escenarios[reference] = it }
            ruta == REVERSO -> reference?.let(escenarios::get)
            ruta == PREFLIGHT -> pedido?.get("operationNumber")?.asLong?.let(ventas::get)?.escenario
            else -> ventaDelFolio?.escenario
        }
        registro.update { it + Pedida(ruta, reference, escenario) }
        return when {
            ruta == TOKEN -> json(JsonObject().apply { addProperty("access_token", token) })
            ruta == INIT -> json(
                JsonObject().apply {
                    addProperty("status", true)
                    add("dataResponse", JsonObject().apply {
                        addProperty("posId", identidad.posId.toLong())
                        addProperty("commerceName", identidad.comercio)
                        addProperty("contactless", true)
                        addProperty("initializeKeys", true)
                        addProperty("qps", false)
                    })
                },
            )
            ruta == LLAVE -> json(JsonObject().apply {
                addProperty("status", true)
                add("dataResponse", JsonObject().apply { addProperty("rsa", RSA.public.encoded.aHex()) })
            })
            ruta == LLAVES_DUKPT -> llavesDukpt(requireNotNull(pedido))
            ruta == VENTA -> venta(requireNotNull(reference), centavos(requireNotNull(pedido)), requireNotNull(escenario))
            ruta == REVERSO -> reverso(requireNotNull(reference), requireNotNull(pedido), escenario)
            ruta == PREFLIGHT && escenario != null -> preflight(escenario)
            ventaDelFolio != null -> cancelacion(requireNotNull(reference), centavos(requireNotNull(pedido)), ventaDelFolio)
            else -> MockResponse().setResponseCode(404)
        }
    }

    // ───────────── La sesión (la misma que ClienteBlumonTest) ─────────────

    private fun llavesDukpt(pedido: JsonObject): MockResponse {
        val tk = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, RSA.private) }
            .doFinal(pedido.get("rsa").asString.hexABytes())
        return json(JsonObject().apply {
            addProperty("status", true)
            add("dataResponse", JsonObject().apply {
                addProperty("key", Dukpt.tdes(tk, ipek).aHex())
                addProperty("ksn", KSN)
                addProperty("keyCheckValue", Dukpt.kcv(ipek))
            })
        })
    }

    // ───────────── La venta, el reverso y la cancelación ─────────────

    private fun venta(ref: String, centavos: Long, e: EscenarioDeBlumon): MockResponse {
        fun aprobada(eco: String? = ref) = exito(nuevoFolio(ref, e), eco, centavos, "APROBADA", autorizacion = true, e == SIMULATION_TRUE)
        return when (e) {
            RECHAZO_05 -> json(error("05"))
            RECHAZO_CON_DATOS -> json(error("05").apply { add("dataResponse", JsonObject().apply { addProperty("description", "RECHAZADA") }) })
            RECHAZO_CON_HTTP_DE_TRANSPORTE -> json(error("05", http = 504))
            CODIGO_68 -> json(error("68"))
            APROBADO_SIN_ECO -> json(aprobada(eco = null))
            APROBADO_CON_ERROR -> json(aprobada().apply { add("error", JsonObject().apply { addProperty("httpStatusCode", 400) }) })
            APROBADO_CON_ERROR_SUELTO -> json(aprobada().apply { addProperty("error", "x") })
            APROBADO_PARCIAL -> {
                val parcial = centavos / 2
                val sobre = exito(nuevoFolio(ref, e), ref, parcial, "APROBADA", autorizacion = true, simulation = false)
                sobre.getAsJsonObject("dataResponse").addProperty("responseCode", "10")
                json(sobre)
            }
            APROBADO_TARDIO_ANTES_DEL_ARBITRAJE, APROBADO_TARDIO_DESPUES_DEL_ARBITRAJE -> {
                runBlocking { ventaTardia.await() }
                json(aprobada())
            }
            EscenarioDeBlumon.SILENCIO_Y_REVERSADA, SILENCIO_Y_REVERSADA_SIN_ECO, SILENCIO_Y_TX007,
            SILENCIO_RQ006_DOS_VECES_Y_REVERSADA, REVERSO_SIN_RESPUESTA_Y_TX007,
            EscenarioDeBlumon.REVERSADA_Y_SERVIDOR_CONTRADICTION -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            EscenarioDeBlumon.APROBADO, SIMULATION_TRUE, CANCELACION_NO_PERMITIDA, CANCELACION_SIN_RESPUESTA,
            CANCELACION_CON_ECO_DE_LA_VENTA, CANCELACION_CON_ERROR -> json(aprobada())
        }
    }

    private fun reverso(ref: String, pedido: JsonObject, escenario: EscenarioDeBlumon?): MockResponse {
        val vuelta = vueltas.getOrPut(ref) { AtomicInteger() }.incrementAndGet()
        // El freno del simulador: ningún escenario pide más de 3 vueltas. Un bucle de reversos (con el reloj virtual quieto no
        // tiene techo) termina aquí con `TX_007`, y la invariante de `PuntaAPuntaTest` lo señala.
        if (vuelta > TOPE_DE_VUELTAS) return json(error("TX_007"))
        // Un reverso de una venta que el simulador no vio: 404, también contado por el freno.
        val e = escenario ?: return MockResponse().setResponseCode(404)
        // Medido: sin `"sendReverse": true` (booleano) Blumon contesta `RQ_005` y el reverso no entra.
        val conBandera = pedido.get("sendReverse")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        if (!conBandera) return json(error("RQ_005"))
        val centavos = centavos(pedido)
        fun reversada(eco: String? = ref, simulation: Boolean = false) =
            json(exito(siguienteFolio.incrementAndGet(), eco, centavos, "REVERSADA", autorizacion = false, simulation = simulation))
        return when (e) {
            SILENCIO_Y_TX007 -> json(error("TX_007"))
            SILENCIO_RQ006_DOS_VECES_Y_REVERSADA -> if (vuelta <= 2) json(error("RQ_006")) else reversada()
            REVERSO_SIN_RESPUESTA_Y_TX007 ->
                if (vuelta == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else json(error("TX_007"))
            SILENCIO_Y_REVERSADA_SIN_ECO -> if (vuelta == 1) reversada(eco = null) else json(error("TX_007"))
            SIMULATION_TRUE -> if (vuelta == 1) reversada(simulation = true) else json(error("TX_007"))
            else -> reversada()
        }
    }

    /** El «sí» del piloto y el `TX_024` medido (HTTP 200, `status: false`, error plano con `httpStatusCode` 409). */
    private fun preflight(e: EscenarioDeBlumon): MockResponse =
        if (e == CANCELACION_NO_PERMITIDA) {
            json(error("TX_024"))
        } else {
            json(JsonObject().apply {
                addProperty("status", true)
                add("dataResponse", JsonObject().apply { addProperty("simulation", false) })
            })
        }

    /** El sobre de la cancelación no se pudo medir (el sandbox no deja cancelar): el éxito de la venta sin autorización. */
    private fun cancelacion(ref: String, centavos: Long, venta: Venta): MockResponse {
        fun cancelada(eco: String) = exito(nuevoFolio(ref, venta.escenario), eco, centavos, "APROBADA", autorizacion = false, simulation = false)
        return when (venta.escenario) {
            CANCELACION_SIN_RESPUESTA -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            CANCELACION_CON_ECO_DE_LA_VENTA -> json(cancelada(eco = venta.reference))
            CANCELACION_CON_ERROR -> json(cancelada(eco = ref).apply { add("error", JsonObject().apply { addProperty("httpStatusCode", 400) }) })
            else -> json(cancelada(eco = ref))
        }
    }

    private fun nuevoFolio(ref: String, e: EscenarioDeBlumon): Long = siguienteFolio.incrementAndGet().also {
        ventas[it] = Venta(ref, e)
        folios[ref] = it
    }

    /** El éxito medido: folio en texto, la referencia de Blumon (12 dígitos) y nuestro folio en `batch`. Sin bloque `error`. */
    private fun exito(folio: Long, eco: String?, centavos: Long, descripcion: String, autorizacion: Boolean, simulation: Boolean) =
        JsonObject().apply {
            addProperty("status", true)
            addProperty("id", folio.toString())
            addProperty("requestId", "req-$folio")
            addProperty("date", "2026-11-20")
            addProperty("time", "12:00:00")
            add("dataResponse", JsonObject().apply {
                if (autorizacion) {
                    addProperty("authorization", AUTORIZACION)
                    addProperty("renewKey", false)
                }
                addProperty("description", descripcion)
                addProperty("reference", "%012d".format(folio))
                eco?.let { addProperty("batch", it) }
                add("amountInfo", JsonObject().apply {
                    addProperty("taxAmount", "0.00")
                    addProperty("subtotal", pesos(centavos))
                    addProperty("total", pesos(centavos))
                    add("taxes", JsonNull.INSTANCE)
                    add("tipAmount", JsonNull.INSTANCE)
                })
                add("binInformation", JsonObject().apply {
                    addProperty("bank", TEXTO_DEL_BANCO)
                    addProperty("brand", "VISA")
                })
                addProperty("simulation", simulation)
            })
        }

    /** El error medido: plano, con las cuatro claves y un texto libre del banco que nada debe repetir. */
    private fun error(codigo: String, http: Int = 409) = JsonObject().apply {
        addProperty("status", false)
        add("error", JsonObject().apply {
            addProperty("httpStatusCode", http)
            addProperty("code", codigo)
            addProperty("description", TEXTO_DEL_BANCO)
            addProperty("simulation", false)
        })
    }

    private fun centavos(pedido: JsonObject): Long = pedido.get("amount").asBigDecimal.movePointRight(2).longValueExact()

    private fun pesos(centavos: Long) = BigDecimal.valueOf(centavos, 2).toPlainString()

    private fun json(o: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    companion object {
        const val TOKEN = "/oauth/token"
        const val INIT = "/device/init"
        const val LLAVE = "/device/getKey"
        const val LLAVES_DUKPT = "/device/initDukptKeys"
        const val VENTA = "/retail/present/charge"
        const val REVERSO = "/retail/present/reverse/"
        const val PREFLIGHT = "/device/validateCancelation"
        const val CANCELACION = "/retail/present/cancel/"
        const val KSN = "FFFF9876543210E00000"
        const val AUTORIZACION = "A1B2C3"
        private const val TOPE_DE_VUELTAS = 5

        /** Un texto libre de la respuesta del banco: ninguna salida del módulo lo puede repetir. */
        const val TEXTO_DEL_BANCO = "TEXTO LIBRE DEL BANCO SIMULADO"

        private val RSA by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair() }

        /** Para la sonda del barrido: una llave de transporte cifrada con la llave pública del simulador, como la manda el cliente. */
        fun cifrarParaLlaves(tk: ByteArray): String =
            Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, RSA.public) }.doFinal(tk).aHex()
    }
}
