package com.jaac.avoqado_campo.custodia

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.EvidenciaSueltaNueva
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SimConVersion
import com.jaac.avoqado_campo.cola.SimYaEnCola
import com.jaac.avoqado_campo.cola.simsDeCustodia
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

enum class EstadoFila { POR_DECIDIR, ACTUALIZANDO, POR_SUBIR, EN_CUSTODIA, VENDIDA, EN_REVISION, REVISAR, CORRECCION_POR_SUBIR }

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
    private var aDecidir: Map<String, Reasignada> = emptyMap()
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

    /**
     * Hasta 50 SIMs por pieza; cada una con la `custodyVersion` que trae la copia. La custodia vieja que el SERVIDOR rechazó
     * por `ASIGNACION_CAMBIO` se descarta en la MISMA transacción que la decisión nueva (Encolador).
     */
    private fun decidir(sims: List<SimEnCopia>, aceptar: Boolean) {
        // R6-A1 + r3: generación y sesión de UNA instantánea: un cambio de persona no pasa con el venueId viejo.
        val yo = quienSoy.instantanea()
        val generacion = yo.generacion
        val venueId = yo.sesion?.venueId
        if (sims.isEmpty() || _estado.value.ocupado) return
        if (venueId == null) { _estado.update { it.copy(error = SIN_SESION) }; return }
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            var guardadas = 0
            val error = withContext(NonCancellable) {
                try {
                    for (lote in sims.chunked(50)) {
                        val v = lote.map { SimConVersion(it.serialNumber, it.custodyVersion) }
                        val viejas = lote.mapNotNull { aDecidir[normalizarSerie(it.serialNumber)]?.pieza }
                            .filter { it.estado == EstadoPieza.RECHAZADA }.map { it.id }.distinct()
                        if (aceptar) encolador.aceptarCustodia(generacion, v, venueId, viejas) else encolador.rechazarCustodia(generacion, v.single(), venueId, viejas)
                        guardadas += lote.size
                        recienDecididas += lote.map { normalizarSerie(it.serialNumber) }
                        val marca = if (aceptar) MarcaLocal.ACEPTADA_EN_EL_CELULAR else MarcaLocal.RECHAZADA_EN_EL_CELULAR
                        lote.forEach { despues { copia.marcar(generacion, it.serialNumber, marca) } }
                    }
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    val porque = when (x) {
                        is CelularBorrandose -> "Espera un momento."
                        is SimYaEnCola -> "Esta SIM ya tiene una decisión por subir."
                        else -> "No se guardó. ${x.message.orEmpty()}".trim()
                    }
                    if (guardadas == 0) porque else "Se guardaron $guardadas de ${sims.size}; las demás no. $porque"
                } finally {
                    if (guardadas > 0) despues { Disparador.ahora(context) }
                }
            }
            derivarOtraVez()
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
        val yo = quienSoy.instantanea() // R6-A1 + r3
        val generacion = yo.generacion
        val venueId = yo.sesion?.venueId
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
                        generacion,
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

    /**
     * La foto de una corrección que no se envió. R2-1: enviándose (`ocupado`) ya es del `Encolador`, que la copia y la borra; si
     * el envío falla con la pantalla ya cerrada, la recoge el barrido de 24 h.
     */
    private fun borrarFotoDeCorreccion() {
        if (!_estado.value.ocupado) _estado.value.corrigiendo?.foto?.let { File(it).delete() }
    }

    private fun derivarOtraVez() = derivar(ultimo.first, ultimo.second)

    private fun derivar(c: Copia?, cola: List<Pieza>) {
        ultimo = c to cola
        val porSerie = c?.sims.orEmpty().associateBy { normalizarSerie(it.serialNumber) }
        val enCola = mutableSetOf<String>()
        val aDecidirAhora = mutableMapOf<String, Reasignada>()
        for (p in cola) {
            if (p.tipo != TipoPieza.CUSTODIA_ACEPTAR && p.tipo != TipoPieza.CUSTODIA_RECHAZAR) continue
            val sims = simsDeCustodia(p.cuerpoJson)
            when {
                // Rechazar: el código viene en la pieza. Aceptar: un 200 con la fila en error (AVISO CUSTODIA_EN_PARTE).
                p.estado == EstadoPieza.RECHAZADA && p.rechazoCode == ASIGNACION_CAMBIO -> sims.forEach { aDecidirAhora[it.serialNumber] = Reasignada(p, it.custodyVersion) }
                p.estado == EstadoPieza.AVISO -> {
                    val reasignadas = filasReasignadas(p.rechazoDatosJson)
                    sims.filter { it.serialNumber in reasignadas }.forEach { aDecidirAhora[it.serialNumber] = Reasignada(p, it.custodyVersion) }
                }
                p.estado in POR_SUBIR -> enCola += sims.map { it.serialNumber }
            }
        }
        // Lo recién decidido sale de la memoria cuando la cola o la copia ya lo dicen.
        recienDecididas.removeAll {
            it in enCola || (it !in aDecidirAhora && porSerie[it]?.let { s -> s.localmente != null || s.custodyState != "PROMOTER_PENDING" } == true)
        }
        enCola += recienDecididas
        aDecidir = aDecidirAhora
        val correccionesEnCola = cola.filter { it.tipo == TipoPieza.EVIDENCIA && it.estado in POR_SUBIR }
            .mapNotNull { campo(it.evidenciaJson, "verificationId") }.toSet()
        recienCorregidas.removeAll { it in correccionesEnCola || c?.sims?.none { s -> s.verificationId == it && s.verificationStatus == "FAILED" } == true }
        val corrigiendo = recienCorregidas + correccionesEnCola

        // «Te volvieron a asignar»: se decide otra vez sólo con la versión NUEVA en la copia; mientras tanto se baja otra (una vez por pieza).
        val sinVersionNueva = aDecidirAhora.filter { (serie, r) -> porSerie[serie]?.custodyVersion == r.version }
        if (sinVersionNueva.values.map { it.pieza.id }.filter { refrescadas.add(it) }.isNotEmpty()) actualizar()

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
                    serie in sinVersionNueva -> FilaSim(s, EstadoFila.ACTUALIZANDO, "Te volvieron a asignar esta SIM · Actualizando…")
                    serie in aDecidirAhora -> FilaSim(s, EstadoFila.POR_DECIDIR, REASIGNADA)
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

    /** Una custodia que el SERVIDOR no aplicó porque reasignaron la SIM; [version] es la que se mandó. */
    private class Reasignada(val pieza: Pieza, val version: Int)

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

        const val REASIGNADA = "Te volvieron a asignar esta SIM: vuelve a decidir"

        /** Series de las filas `fallidas` (B12) con `ASIGNACION_CAMBIO`. */
        fun filasReasignadas(datos: String?): Set<String> = runCatching {
            JsonParser.parseString(datos).asJsonObject["fallidas"].asJsonArray.mapNotNull { f ->
                f.asJsonObject.takeIf { it["code"]?.asString == ASIGNACION_CAMBIO }?.get("serialNumber")?.asString?.let(::normalizarSerie)
            }.toSet()
        }.getOrDefault(emptySet())

        fun campo(json: String?, nombre: String): String? = runCatching {
            JsonParser.parseString(json).asJsonObject[nombre]?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()
    }
}
