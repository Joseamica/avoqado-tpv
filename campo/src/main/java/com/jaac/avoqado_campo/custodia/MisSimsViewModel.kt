package com.jaac.avoqado_campo.custodia

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.EvidenciaSueltaNueva
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SimConVersion
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.normalizarSerie
import com.jaac.avoqado_campo.copia.Copia
import com.jaac.avoqado_campo.copia.MarcaLocal
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class EstadoFila { POR_DECIDIR, POR_SUBIR, EN_CUSTODIA, VENDIDA, EN_REVISION, REVISAR, CORRECCION_POR_SUBIR }

data class FilaSim(val sim: SimEnCopia, val estado: EstadoFila, val nota: String? = null)

/** «Corregir evidencia» en curso (R8-3): qué foto se rehace, la foto ya comprimida y el número (opcional). */
data class Correccion(val serie: String, val photoLabel: String, val foto: String? = null, val numero: String = "")

data class EstadoMisSims(
    val porAceptar: List<FilaSim> = emptyList(),
    val enCustodia: List<FilaSim> = emptyList(),
    val vendidas: List<FilaSim> = emptyList(),
    val sinCopia: Boolean = true,
    val actualizando: Boolean = false,
    val ocupado: Boolean = false,
    val corrigiendo: Correccion? = null,
    val error: String? = null,
)

@HiltViewModel
class MisSimsViewModel @Inject constructor(
    private val encolador: Encolador,
    private val copia: RepositorioDeCopia,
    private val dao: PiezaDao,
    private val quienSoy: QuienSoy,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoMisSims())
    val estado: StateFlow<EstadoMisSims> = _estado

    /** Lo decidido en esta pantalla mientras la cola de Room todavía no lo muestra (un toque rápido no lo repite). */
    private val recienDecididas = mutableSetOf<String>()
    private val recienCorregidas = mutableSetOf<String>()
    private val refrescadas = mutableSetOf<Long>()
    private var aDecidir: Map<String, Pieza> = emptyMap()
    private var ultimo: Pair<Copia?, List<Pieza>> = null to emptyList()

    init {
        viewModelScope.launch { copia.leer(); actualizar() }
        viewModelScope.launch { combine(copia.copia, dao.pendientesDeVer()) { a, b -> a to b }.collect { (c, cola) -> derivar(c, cola) } }
    }

    fun actualizar() {
        val venueId = quienSoy.sesion?.venueId ?: return
        _estado.update { it.copy(actualizando = true) }
        viewModelScope.launch {
            try { copia.actualizar(venueId) } finally { _estado.update { it.copy(actualizando = false) } }
        }
    }

    fun aceptarTodas() = decidir(_estado.value.porAceptar.filter { it.estado == EstadoFila.POR_DECIDIR }.map { it.sim }, aceptar = true)

    fun aceptar(serie: String) = decidir(porDecidir(serie), aceptar = true)

    fun rechazar(serie: String) = decidir(porDecidir(serie), aceptar = false)

    private fun porDecidir(serie: String) = _estado.value.porAceptar
        .filter { it.estado == EstadoFila.POR_DECIDIR && it.sim.serialNumber == serie }.map { it.sim }

    /** Hasta 50 SIMs por pieza; cada una con la `custodyVersion` que trae la copia. */
    private fun decidir(sims: List<SimEnCopia>, aceptar: Boolean) {
        val venueId = quienSoy.sesion?.venueId
        if (sims.isEmpty() || _estado.value.ocupado) return
        if (venueId == null) { _estado.update { it.copy(error = SIN_SESION) }; return }
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            val error = withContext(NonCancellable) {
                try {
                    for (lote in sims.chunked(50)) {
                        val v = lote.map { SimConVersion(it.serialNumber, it.custodyVersion) }
                        if (aceptar) encolador.aceptarCustodia(v, venueId) else encolador.rechazarCustodia(v.single(), venueId)
                        recienDecididas += lote.map { normalizarSerie(it.serialNumber) }
                        val marca = if (aceptar) MarcaLocal.ACEPTADA_EN_EL_CELULAR else MarcaLocal.RECHAZADA_EN_EL_CELULAR
                        lote.forEach { despues { copia.marcar(it.serialNumber, marca) } }
                        // La decisión vieja que el SERVIDOR rechazó por ASIGNACION_CAMBIO ya no se va a subir.
                        lote.mapNotNull { aDecidir[normalizarSerie(it.serialNumber)] }.distinct()
                            .forEach { despues { dao.actualizar(it.copy(estado = EstadoPieza.DESCARTADA)) } }
                    }
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (_: CelularBorrandose) {
                    "Espera un momento."
                } catch (x: Exception) {
                    "No se guardó. ${x.message.orEmpty()}".trim()
                } finally {
                    despues { Disparador.ahora(context) }
                }
            }
            _estado.update { it.copy(ocupado = false, error = error) }
        }
    }

    fun empezarCorreccion(serie: String, photoLabel: String) {
        if (_estado.value.vendidas.none { it.sim.serialNumber == serie && it.estado == EstadoFila.REVISAR }) return
        borrarFotoDeCorreccion()
        _estado.update { it.copy(corrigiendo = Correccion(serie, photoLabel), error = null) }
    }

    fun alTomarFotoDeCorreccion(archivo: String) {
        val c = _estado.value.corrigiendo
        if (c == null || _estado.value.ocupado) { File(archivo).delete(); return }
        c.foto?.takeIf { it != archivo }?.let { File(it).delete() }
        _estado.update { it.copy(corrigiendo = c.copy(foto = archivo)) }
    }

    fun alEscribirNumeroDeCorreccion(n: String) = _estado.update { e ->
        e.copy(corrigiendo = e.corrigiendo?.copy(numero = n.filter(Char::isDigit).take(10)))
    }

    fun cancelarCorreccion() {
        if (_estado.value.ocupado) return
        borrarFotoDeCorreccion()
        _estado.update { it.copy(corrigiendo = null) }
    }

    /** R8-3: una EVIDENCIA suelta que ya sabe su pago y su verificación; el número es opcional (9 o 10 dígitos). */
    fun enviarCorreccion() {
        val e = _estado.value
        val c = e.corrigiendo ?: return
        if (e.ocupado) return
        val sim = e.vendidas.firstOrNull { it.sim.serialNumber == c.serie }?.sim
        val venueId = quienSoy.sesion?.venueId
        val pago = sim?.paymentId
        val verificacion = sim?.verificationId
        val foto = c.foto
        val falta = when {
            venueId == null -> SIN_SESION
            foto == null -> "Falta la foto."
            c.numero.isNotEmpty() && c.numero.length !in 9..10 -> "El número lleva 9 o 10 dígitos."
            pago == null || verificacion == null -> "Esta venta no trae sus datos. Toca «Actualizar» con señal."
            else -> null
        }
        if (falta != null || venueId == null || foto == null || pago == null || verificacion == null) {
            _estado.update { it.copy(error = falta) }; return
        }
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            val error = withContext(NonCancellable) {
                try {
                    encolador.evidenciaSuelta(
                        EvidenciaSueltaNueva(
                            venueId, pago, verificacion, c.photoLabel, c.numero.ifEmpty { null }, foto, "Corregir evidencia de SIM ${c.serie}",
                        ),
                    )
                    recienCorregidas += verificacion
                    despues { Disparador.ahora(context) }
                    null
                } catch (x: CancellationException) {
                    throw x
                } catch (_: CelularBorrandose) {
                    "Espera un momento."
                } catch (x: Exception) {
                    "No se guardó la corrección. ${x.message.orEmpty()}".trim()
                }
            }
            _estado.update { it.copy(ocupado = false, error = error, corrigiendo = if (error == null) null else it.corrigiendo) }
            if (error == null) derivarOtraVez()
        }
    }

    override fun onCleared() = borrarFotoDeCorreccion()

    /** La foto de una corrección que no se envió; la enviada ya es del `Encolador`. */
    private fun borrarFotoDeCorreccion() { _estado.value.corrigiendo?.foto?.let { File(it).delete() } }

    private fun derivarOtraVez() = derivar(ultimo.first, ultimo.second)

    private fun derivar(c: Copia?, cola: List<Pieza>) {
        ultimo = c to cola
        val custodias = cola.filter { it.tipo == TipoPieza.CUSTODIA_ACEPTAR || it.tipo == TipoPieza.CUSTODIA_RECHAZAR }
        val enCola = mutableSetOf<String>()
        val aDecidirAhora = mutableMapOf<String, Pieza>()
        for (p in custodias) {
            val series = seriesDe(p)
            when {
                p.estado == EstadoPieza.RECHAZADA && p.rechazoCode == ASIGNACION_CAMBIO -> series.forEach { aDecidirAhora[it] = p }
                p.estado in POR_SUBIR -> { enCola += series; recienDecididas.removeAll(series.toSet()) }
            }
        }
        enCola += recienDecididas
        aDecidir = aDecidirAhora
        val corrigiendo = recienCorregidas + cola.filter { it.tipo == TipoPieza.EVIDENCIA && it.estado in POR_SUBIR }
            .mapNotNull { campo(it.evidenciaJson, "verificationId") }

        // «Te volvieron a asignar»: la versión que trae la copia ya no vale; se baja otra (una vez por pieza).
        val nuevas = aDecidirAhora.values.map { it.id }.filter { refrescadas.add(it) }
        if (nuevas.isNotEmpty()) actualizar()

        val filas = c?.sims.orEmpty().mapNotNull { s ->
            val serie = normalizarSerie(s.serialNumber)
            when {
                s.custodyState == "SOLD" || s.localmente == MarcaLocal.VENDIDA_EN_EL_CELULAR -> when (s.verificationStatus) {
                    "FAILED" -> if (s.verificationId in corrigiendo) FilaSim(s, EstadoFila.CORRECCION_POR_SUBIR, "Corrección por subir")
                    else FilaSim(s, EstadoFila.REVISAR, motivos(s))
                    "PENDING" -> FilaSim(s, EstadoFila.EN_REVISION, "En revisión")
                    else -> FilaSim(s, EstadoFila.VENDIDA)
                }
                s.custodyState == "PROMOTER_PENDING" -> when {
                    serie in enCola -> FilaSim(s, EstadoFila.POR_SUBIR, "Por subir")
                    serie in aDecidirAhora -> FilaSim(s, EstadoFila.POR_DECIDIR, "Te volvieron a asignar esta SIM: vuelve a decidir")
                    s.localmente == MarcaLocal.ACEPTADA_EN_EL_CELULAR || s.localmente == MarcaLocal.RECHAZADA_EN_EL_CELULAR ->
                        FilaSim(s, EstadoFila.POR_SUBIR, "Por subir")
                    else -> FilaSim(s, EstadoFila.POR_DECIDIR)
                }
                s.custodyState == "PROMOTER_HELD" -> FilaSim(s, EstadoFila.EN_CUSTODIA)
                else -> null
            }
        }
        val vendidas = setOf(EstadoFila.VENDIDA, EstadoFila.EN_REVISION, EstadoFila.REVISAR, EstadoFila.CORRECCION_POR_SUBIR)
        _estado.update { e ->
            e.copy(
                porAceptar = filas.filter { it.sim.custodyState == "PROMOTER_PENDING" && it.estado !in vendidas },
                enCustodia = filas.filter { it.estado == EstadoFila.EN_CUSTODIA },
                vendidas = filas.filter { it.estado in vendidas },
                sinCopia = c == null,
            )
        }
    }

    private suspend fun despues(f: suspend () -> Unit) {
        try { f() } catch (c: CancellationException) { throw c } catch (x: Exception) { Log.w("MisSims", "guardado; falló lo de después", x) }
    }

    private companion object {
        const val ASIGNACION_CAMBIO = "ASIGNACION_CAMBIO"
        const val SIN_SESION = "Entra con tu PIN para esto."
        val POR_SUBIR = setOf(EstadoPieza.PENDIENTE, EstadoPieza.RETENIDA, EstadoPieza.RECHAZADA)

        val MOTIVOS = mapOf(
            "REVIEW_PORTABILIDAD" to "Falta imagen de portabilidad",
            "REVIEW_DUPLICATE_VINCULACION" to "Número de vinculación duplicado",
            "REVIEW_ILLEGIBLE_IMAGES" to "Imágenes ilegibles",
            "REVIEW_MISSING_LINKING_IMAGE" to "Falta imagen de vinculación",
            "OTHER" to "Otro motivo",
        )

        fun motivos(s: SimEnCopia) = (s.rejectionReasons.map { MOTIVOS[it] ?: it } + listOfNotNull(s.reviewNotes?.takeIf { it.isNotBlank() }))
            .joinToString(" · ").ifEmpty { "Tu supervisor pidió corregir la evidencia" }

        fun seriesDe(p: Pieza): List<String> = runCatching {
            val o = JsonParser.parseString(p.cuerpoJson).asJsonObject
            val lista: List<JsonElement> = o["sims"]?.asJsonArray?.toList() ?: listOf(o)
            lista.mapNotNull { it.asJsonObject["serialNumber"]?.asString?.let(::normalizarSerie) }
        }.getOrDefault(emptyList())

        fun campo(json: String?, nombre: String): String? = runCatching {
            JsonParser.parseString(json).asJsonObject[nombre]?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
    }
}
