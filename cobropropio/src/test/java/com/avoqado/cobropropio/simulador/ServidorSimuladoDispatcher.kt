package com.avoqado.cobropropio.simulador

import com.avoqado.cobropropio.dominio.AttemptId
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger

/**
 * El SERVIDOR simulado: la ruta del reverso (`…/attempts/{id}/reverso/resultado`), con lo que diga [EscenarioDelServidor]
 * para el `AttemptId` de la ruta. Registra la ruta, el folio y, de `/resultado`, sólo el valor que se
 * reportó (`descripcion` o `codigo`) si es del vocabulario fijo (`REVERSADA`, `SIN_RESPUESTA` o un código con forma de
 * catálogo); cualquier otro valor queda como su forma y su largo. Nunca el cuerpo. [alRecibirCuerpo] deja que el barrido de
 * privacidad lea cada cuerpo al llegar, sin guardarlo aquí. Sólo atiende POST: otro verbo da 405.
 */
internal class ServidorSimuladoDispatcher(private val alRecibirCuerpo: (String) -> Unit = {}) : Dispatcher() {

    data class Pedida(val ruta: String, val attemptId: String, val reportado: String?)

    val registro = MutableStateFlow<List<Pedida>>(emptyList())

    /** Peticiones con otro verbo que POST: se contestan 405 y se cuentan aquí. */
    val verbosIncorrectos = AtomicInteger()

    fun pedidas(ruta: String, id: AttemptId? = null) = registro.value.filter { it.ruta == ruta && (id == null || it.attemptId == id.valor) }

    override fun dispatch(request: RecordedRequest): MockResponse {
        // api/v1/tpv/venues/{venue}/terminal-payment/attempts/{id}/reverso/resultado
        val partes = request.path.orEmpty().trim('/').split('/')
        val id = AttemptId(partes[7])
        val ruta = partes[9]
        val cuerpo = request.body.readUtf8()
        alRecibirCuerpo(cuerpo)
        if (request.method != "POST") {
            verbosIncorrectos.incrementAndGet()
            return MockResponse().setResponseCode(405)
        }
        val reportado = if (ruta == RESULTADO) {
            JsonParser.parseString(cuerpo).asJsonObject.let { it.texto("descripcion") ?: it.texto("codigo") }?.let(::delVocabulario)
        } else {
            null
        }
        registro.update { it + Pedida(ruta, id.valor, reportado) }
        val escenario = EscenarioDelServidor.de(id)
        val acuse = when {
            escenario == EscenarioDelServidor.ACUSA_CONTRADICCION -> "contradiction"
            reportado == "REVERSADA" -> "reversed"
            reportado == "SIN_RESPUESTA" -> "reversing"
            else -> "reverse_failed"
        }
        return json(200, estado(id, "evt-sim-2", acuse))
    }

    private fun estado(id: AttemptId, evento: String, estado: String) = JsonObject().apply {
        addProperty("attemptId", id.valor)
        addProperty("eventLogId", evento)
        addProperty("estado", estado)
    }

    /** Sólo el vocabulario fijo pasa al registro; lo demás, como su forma y su largo. */
    private fun delVocabulario(v: String) = v.takeIf { it in FIJOS || CODIGO.matches(it) } ?: "otro(largo=${v.length})"

    private fun JsonObject.texto(campo: String) = get(campo)?.takeIf { it.isJsonPrimitive }?.asString

    private fun json(codigo: Int, o: JsonObject) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(o.toString())

    companion object {
        const val RESULTADO = "resultado"
        private val FIJOS = setOf("REVERSADA", "SIN_RESPUESTA")

        /** La forma de los códigos del catálogo de Blumon: `TX_007`, `RQ_006`, o un ISO de dos caracteres. */
        private val CODIGO = Regex("^(?:[A-Z]{2,4}_[0-9]{3}|[0-9A-Z]{2})$")
    }
}
