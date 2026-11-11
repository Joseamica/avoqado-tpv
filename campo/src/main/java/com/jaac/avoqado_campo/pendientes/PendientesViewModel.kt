package com.jaac.avoqado_campo.pendientes

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.EvidenciaPendiente
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SubidorDeGracia
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.cola.gsonCola
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.red.Conectividad
import com.jaac.avoqado_campo.red.objetoJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

enum class Accion { CORREGIR_PRECIO, CORREGIR_EVIDENCIA, USAR_ENTRADA, VOLVER_A_DECIDIR, ENTENDIDO, QUITAR }
data class Boton(val accion: Accion, val texto: String)

/** [problema]: RECHAZADA o AVISO (se pinta distinto; nunca rojo por estar sin señal). */
data class FilaPendiente(val id: Long, val resumen: String, val texto: String, val problema: Boolean, val botones: List<Boton>)

data class CorreccionDeEvidencia(
    val id: Long, val photoLabel: String, val pideFoto: Boolean, val foto: String? = null, val numero: String = "", val pideNumero: Boolean = false,
)
data class EstadoPendientes(val ocupado: Boolean = false, val error: String? = null, val corrigiendo: CorreccionDeEvidencia? = null)

private val QUITAR = Boton(Accion.QUITAR, "Quitar de mi celular")
private val ENTENDIDO = Boton(Accion.ENTENDIDO, "Entendido")
private val VOLVER = Boton(Accion.VOLVER_A_DECIDIR, "Volver a decidir")
private const val RESPALDO = "No se pudo subir. Pídele a tu supervisor que lo revise."
internal const val NO_SE_SUBE = "No se puede subir: este celular ya no está registrado. Avísale a tu supervisor."
private const val RESPALDO_AVISO = "Ya llegó, pero no se guardó todo. Revísalo con tu supervisor."
private val CUSTODIA_A_MEDIAS = setOf("CUSTODIA_EN_PARTE", "CUSTODIA_SIN_CONFIRMAR")
private val DESCARTADOS = setOf("SU_VENTA_SE_DESCARTO", "SU_ENTRADA_SE_DESCARTO")

private fun dato(p: Pieza, campo: String): String? = objetoJson(p.rechazoDatosJson)?.get(campo)?.takeIf { it.isJsonPrimitive }?.asString
internal fun precioActual(p: Pieza): BigDecimal? = dato(p, "precioActual")?.toBigDecimalOrNull()?.setScale(2)
private fun String?.siHay() = this?.takeIf { it.isNotBlank() }

/**
 * Cómo se ve una pieza no terminal en «Mis pendientes». «Quitar» sólo y siempre para RECHAZADA (R7-3). [llavesVivas] = llaves
 * no terminales (su venta o su entrada aún no sube). [notaDelSupervisor]: la nota de un `resuelto: DESCARTADO` del padre.
 */
fun filaDe(p: Pieza, yo: Identidad, hayRed: Boolean, llavesVivas: Set<String>, notaDelSupervisor: String? = null): FilaPendiente {
    fun nombre(venueId: String?) = yo.sucursales.firstOrNull { it.venueId == venueId }?.nombre ?: "otra tienda"
    val code = p.rechazoCode
    val (texto, botones) = when (p.estado) {
        EstadoPieza.RETENIDA -> SubidorDeGracia.EN_REVISION to emptyList()
        EstadoPieza.AVISO -> (p.rechazoMensaje.siHay() ?: RESPALDO_AVISO) to (if (code in CUSTODIA_A_MEDIAS) listOf(VOLVER, ENTENDIDO) else listOf(ENTENDIDO))
        EstadoPieza.RECHAZADA -> {
            val entradaDeAqui = code == "YA_TIENE_ENTRADA" && dato(p, "venueId") == p.venueId && dato(p, "timeEntryId") != null
            val texto = when {
                code == "SALE_REJECTED" -> "Tu supervisor rechazó esta venta"
                code == "ENTRADA_SE_ENCIMA" || code == "SALIDA_SE_ENCIMA" -> "Se encima con otro turno. Tu supervisor lo revisa"
                code == "ASIGNACION_CAMBIO" -> "Te volvieron a asignar esta SIM: vuelve a decidir"
                code == "YA_TIENE_ENTRADA" && !entradaDeAqui -> "Tu entrada abierta es de ${nombre(dato(p, "venueId"))}: marca la salida ahí"
                code in DESCARTADOS && notaDelSupervisor != null -> "Tu supervisor no la aceptó: $notaDelSupervisor"
                else -> p.rechazoMensaje.siHay() ?: RESPALDO
            }
            val propia = when {
                p.tipo == TipoPieza.VENTA && code == "PRECIO_CAMBIO" ->
                    precioActual(p)?.let { listOf(Boton(Accion.CORREGIR_PRECIO, "Corregir a \$${it.toPlainString()}")) }.orEmpty()
                p.tipo == TipoPieza.EVIDENCIA && (code == "INVALID_LINKING_NUMBER" || code == "FOTO_NO_SUBIO") -> listOf(Boton(Accion.CORREGIR_EVIDENCIA, "Corregir"))
                p.tipo == TipoPieza.ENTRADA && entradaDeAqui -> listOf(Boton(Accion.USAR_ENTRADA, "Usar esa entrada"))
                p.tipo.startsWith("CUSTODIA_") && code == "ASIGNACION_CAMBIO" -> listOf(VOLVER)
                else -> emptyList()
            }
            texto to (propia + QUITAR)
        }
        else -> when { // PENDIENTE
            // Ni la ruta normal ni la gracia suben nada sin la credencial (v6 §3): nunca prometer una subida.
            yo.estado == EstadoDelCelular.DESCONOCIDO -> NO_SE_SUBE
            !hayRed -> "Espera señal"
            yo.estado == EstadoDelCelular.VINCULADO && p.venueId != yo.venueIdDeLaSesion && yo.sucursales.any { it.venueId == p.venueId } ->
                "Se sube cuando entres a ${nombre(p.venueId)}"
            p.dependeDe != null && p.dependeDe in llavesVivas -> if (p.tipo == TipoPieza.SALIDA) "Espera a su entrada" else "Espera a su venta"
            yo.estado == EstadoDelCelular.VINCULADO && yo.necesitaPin -> "Escribe tu PIN con señal para subirlo"
            yo.estado != EstadoDelCelular.VINCULADO -> "Se sube para que tu supervisor lo revise"
            else -> "Subiendo…"
        } to emptyList()
    }
    return FilaPendiente(p.id, p.resumen, texto, p.estado == EstadoPieza.RECHAZADA || p.estado == EstadoPieza.AVISO, botones)
}

@HiltViewModel
class PendientesViewModel @Inject constructor(
    private val dao: PiezaDao,
    private val encolador: Encolador,
    private val quienSoy: QuienSoy,
    private val copia: RepositorioDeCopia,
    conectividad: Conectividad,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val filas: StateFlow<List<FilaPendiente>> = combine(dao.pendientesDeVer(), quienSoy.identidad, conectividad.cambios) { lista, yo, red ->
        val vivas = lista.map { it.llave }.toSet()
        lista.map { p ->
            val nota = if (p.rechazoCode in DESCARTADOS && p.dependeDe != null) {
                dao.porLlave(p.dependeDe)?.takeIf { it.estado == EstadoPieza.DESCARTADA && it.rechazoCode == null }?.rechazoMensaje.siHay()
            } else null
            filaDe(p, yo, red, vivas, nota)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _estado = MutableStateFlow(EstadoPendientes())
    val estado: StateFlow<EstadoPendientes> = _estado

    /** Con confirmación en la pantalla. Sólo una RECHAZADA (R7-3); sus hijos vivos quedan rechazados (R2-2). */
    fun quitar(id: Long) = descartar(id, EstadoPieza.RECHAZADA)

    /** El AVISO ya lo vio: pasa a DESCARTADA. */
    fun entendido(id: Long) = descartar(id, EstadoPieza.AVISO)

    private fun descartar(id: Long, debeEstar: String) = correr {
        val p = dao.porId(id)?.takeIf { it.estado == debeEstar } ?: return@correr
        dao.descartarConHijos(id, if (p.tipo == TipoPieza.ENTRADA) "SU_ENTRADA_SE_DESCARTO" else "SU_VENTA_SE_DESCARTO")
    }

    fun corregirPrecio(id: Long) = correr { generacion ->
        val precio = dao.porId(id)?.let(::precioActual) ?: return@correr
        encolador.corregirVenta(generacion, id, precio)
        despertar()
    }

    fun usarEntrada(id: Long) = correr { generacion ->
        val p = dao.porId(id) ?: return@correr
        encolador.usarEntrada(generacion, id, dato(p, "timeEntryId") ?: return@correr, dato(p, "venueId") ?: return@correr)
        despertar()
    }

    /** Refresca la copia (sin hacer esperar); la pantalla abre «Mis SIMs». */
    fun volverADecidir() {
        val v = quienSoy.sesion?.venueId ?: return
        viewModelScope.launch { runCatching { copia.actualizar(v) }.onFailure { if (it is CancellationException) throw it } }
    }

    fun empezarCorreccion(id: Long) = correr {
        val p = dao.porId(id)?.takeIf { it.estado == EstadoPieza.RECHAZADA } ?: return@correr
        val e = gsonCola.fromJson(p.evidenciaJson, EvidenciaPendiente::class.java)
        _estado.update {
            it.copy(corrigiendo = CorreccionDeEvidencia(
                    id, e.photoLabel, p.rechazoCode == "FOTO_NO_SUBIO", numero = e.linkingNumber ?: e.portedNumber.orEmpty(),
                    pideNumero = p.rechazoCode == "INVALID_LINKING_NUMBER",
                ),)
        }
    }

    /** H5: la reemplazada se borra; sin corrección abierta, o con una enviándose (su foto ya es del `Encolador`), la nueva sobra. */
    fun alTomarFoto(archivo: String) {
        val e = _estado.value
        val c = e.corrigiendo
        if (c == null || e.ocupado) { File(archivo).delete(); return }
        c.foto?.takeIf { it != archivo }?.let { File(it).delete() }
        _estado.update { it.copy(corrigiendo = c.copy(foto = archivo)) }
    }
    fun alEscribirNumero(n: String) = _estado.update { it.copy(corrigiendo = it.corrigiendo?.copy(numero = n.filter(Char::isDigit).take(10))) }
    fun cancelarCorreccion() {
        _estado.value.corrigiendo?.foto?.let { runCatching { File(it).delete() } }
        _estado.update { it.copy(corrigiendo = null, error = null) }
    }

    /** H5: salir con Atrás deja la foto de la corrección sin enviar; enviándose (`ocupado`), es del `Encolador` y no se toca. */
    override fun onCleared() {
        val e = _estado.value
        if (!e.ocupado) e.corrigiendo?.foto?.let { File(it).delete() }
    }

    fun enviarCorreccion() {
        val c = _estado.value.corrigiendo ?: return
        val falta = when {
            c.pideFoto && c.foto == null -> "Toma la foto otra vez."
            (c.pideNumero || c.numero.isNotEmpty()) && c.numero.length !in 9..10 -> "El número lleva 9 o 10 dígitos."
            else -> null
        }
        if (falta != null) return _estado.update { it.copy(error = falta) }
        correr { generacion ->
            encolador.corregirEvidencia(generacion, c.id, c.numero.ifEmpty { null }, c.foto)
            _estado.update { it.copy(corrigiendo = null) }
            despertar()
        }
    }

    private fun despertar() = runCatching { Disparador.ahora(context) }.onFailure { Log.w("Pendientes", "despertar al Subidor", it) }

    /** [bloque] recibe la `generacion` leída al tocar: el `Encolador` no escribe si el celular cambió de persona desde entonces. */
    private fun correr(bloque: suspend (generacion: String) -> Unit) {
        if (_estado.value.ocupado) return
        if (quienSoy.limpiando) return _estado.update { it.copy(error = "Espera un momento") }
        val generacion = quienSoy.generacion
        _estado.update { it.copy(ocupado = true, error = null) }
        viewModelScope.launch {
            val error = try {
                // Salir de la pantalla a media escritura no la corta (como Venta, Checada y Mis SIMs): se guarda o se deshace entera.
                withContext(NonCancellable) { bloque(generacion) }; null
            } catch (e: CancellationException) {
                throw e
            } catch (e: CelularBorrandose) {
                "Espera un momento"
            } catch (e: IOException) {
                "La foto ya no está en el celular. Tómala otra vez."
            } catch (e: IllegalArgumentException) {
                "Esto ya cambió. Revisa la lista."
            } catch (e: IllegalStateException) {
                "Esto ya cambió. Revisa la lista."
            }
            _estado.update { it.copy(ocupado = false, error = error) }
        }
    }
}
