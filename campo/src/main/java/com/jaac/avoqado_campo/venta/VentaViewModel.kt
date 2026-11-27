package com.jaac.avoqado_campo.venta

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_campo.cola.CelularBorrandose
import com.jaac.avoqado_campo.cola.Disparador
import com.jaac.avoqado_campo.cola.Encolador
import com.jaac.avoqado_campo.cola.EvidenciaNueva
import com.jaac.avoqado_campo.cola.PiezaDao
import com.jaac.avoqado_campo.cola.SimYaEnCola
import com.jaac.avoqado_campo.cola.SinEntrada
import com.jaac.avoqado_campo.cola.VentaNueva
import com.jaac.avoqado_campo.cola.normalizarSerie
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.LoDelCelular
import com.jaac.avoqado_campo.copia.MarcaLocal
import com.jaac.avoqado_campo.copia.RepositorioDeCopia
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.copia.loDelCelular
import com.jaac.avoqado_campo.identidad.QuienSoy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import javax.inject.Inject

/** El mosaico del Inicio y «Registrar venta» sin una entrada vigente en el celular (0.6.0). */
internal const val CHECA_TU_ENTRADA = "Checa tu entrada para vender"

/** Codex r3 #7: la foto de la galería todavía se está copiando. */
internal const val ESPERA_LA_FOTO = "Espera a que se guarde la foto."

/** B7: `suggestedPrice` "0" → de caja; mayor que 0 → efectivo fijo; null → lo escribe el promotor. */
enum class TipoDePrecio { DE_CAJA, DE_PROMOTOR, LIBRE }

/** Cada evidencia de la venta; [photoLabel] es el del SERVIDOR (el `Encolador` saca de ahí el `replaceIndex`). */
enum class Slot(val photoLabel: String) { VINCULACION("Vinculacion"), PORTABILIDAD("Portabilidad") }

data class EstadoVenta(
    val sim: SimEnCopia? = null,
    val config: ConfigDeSucursal? = null,
    val tipoDePrecio: TipoDePrecio? = null,
    val precio: BigDecimal? = null,
    val precioEscrito: String = "",
    val portabilidad: Boolean = false,
    val fotos: Map<Slot, String> = emptyMap(),
    val numeros: Map<Slot, String> = emptyMap(),
    val error: String? = null,
    val buscando: Boolean = false,
    val registrando: Boolean = false,
    val guardada: Boolean = false,
    /**
     * Codex r3 #7 y r4 #7: cuántas fotos de la galería se están copiando (pueden ser la de vinculación y la de portabilidad a
     * la vez); «Registrar» espera a que terminen todas (si no, saldría la anterior).
     */
    val copiandoFotos: Int = 0,
) {
    val slots: List<Slot> get() = if (portabilidad) listOf(Slot.VINCULACION, Slot.PORTABILIDAD) else listOf(Slot.VINCULACION)

    /** ¿Se escribe un número en este slot? Vinculación sólo si la sucursal lo pide; el portado, siempre. */
    fun pideNumero(s: Slot) = s == Slot.PORTABILIDAD || config?.numeroDeVinculacion == true

    /** Lo primero que falta, en el texto que ve el promotor; null = ya se puede registrar. */
    val falta: String?
        get() = when {
            sim == null -> "Escanea una SIM."
            copiandoFotos > 0 -> ESPERA_LA_FOTO
            precio == null -> "Escribe el importe que cobraste (pon 0 si fue sin costo; hasta 2 decimales)."
            else -> slots.firstNotNullOfOrNull { s ->
                val nombre = if (s == Slot.VINCULACION) "vinculación" else "portabilidad"
                when {
                    fotos[s] == null -> "Falta la foto de $nombre."
                    pideNumero(s) && numeros[s]?.length !in 9..10 ->
                        if (s == Slot.VINCULACION) "El número de vinculación lleva 9 o 10 dígitos." else "El número portado lleva 9 o 10 dígitos."
                    else -> null
                }
            }
        }
}

@HiltViewModel
class VentaViewModel @Inject constructor(
    private val encolador: Encolador,
    private val copia: RepositorioDeCopia,
    private val dao: PiezaDao,
    private val quienSoy: QuienSoy,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoVenta())
    val estado: StateFlow<EstadoVenta> = _estado

    /** B8: sólo SIMs de la copia en `PROMOTER_HELD` (o con su aceptación encolada); sin la SIM, con señal se refresca una vez. */
    fun alEscanear(texto: String) {
        val serie = Iccid.canonica(texto)
        if (serie.isEmpty() || _estado.value.buscando || _estado.value.registrando) return
        borrarFotosSinRegistrar()
        _estado.value = EstadoVenta(buscando = true)
        viewModelScope.launch {
            val venueId = quienSoy.sesion?.venueId
            var c = copia.leer()
            var sim = c?.sims?.firstOrNull { normalizarSerie(it.serialNumber) == serie }
            if (sim == null && venueId != null && copia.actualizar(venueId)) {
                c = copia.leer()
                sim = c?.sims?.firstOrNull { normalizarSerie(it.serialNumber) == serie }
            }
            // D1: la misma regla que «Mis ventas»: lo que hizo ESTE celular con la SIM y la lista todavía no trae.
            val lo = if (c != null && sim != null) loDelCelular(c, dao.conSimsAhora())[serie] else null
            val error = when {
                c == null -> "Todavía no están tus SIMs en el celular. Conéctate a internet una vez para bajarlas."
                !c.config.modulo -> "Las ventas de SIM no están activas en esta sucursal."
                sim == null -> NO_ES_TUYA
                sim.custodyState == "SOLD" || lo?.esVenta == true -> "Esta SIM ya está vendida (o por subir)."
                lo == LoDelCelular.RECHAZADA || lo == LoDelCelular.RECHAZADA_POR_SUBIR -> NO_ES_TUYA
                lo == LoDelCelular.ACEPTADA || lo == LoDelCelular.ACEPTADA_POR_SUBIR || sim.custodyState == "PROMOTER_HELD" -> null
                sim.custodyState == "PROMOTER_PENDING" -> "Primero acepta esta SIM en Mis SIMs."
                else -> NO_ES_TUYA
            }
            _estado.value = if (error != null || sim == null || c == null) {
                EstadoVenta(error = error)
            } else {
                val p = sim.precio?.toBigDecimalOrNull()
                val tipo = when {
                    p == null || p.signum() < 0 -> TipoDePrecio.LIBRE
                    p.signum() == 0 -> TipoDePrecio.DE_CAJA
                    else -> TipoDePrecio.DE_PROMOTOR
                }
                EstadoVenta(sim = sim, config = c.config, tipoDePrecio = tipo, precio = if (tipo == TipoDePrecio.LIBRE) null else p)
            }
        }
    }

    fun alCambiarPortabilidad(b: Boolean) = cambiar {
        if (config?.portabilidad == true) copy(portabilidad = b) else this
    }

    /** [archivo] = la foto ya comprimida en `cacheDir`; el `Encolador` la copia a `cola/` al registrar. */
    fun alTomarFoto(slot: Slot, archivo: String) = cambiar {
        fotos[slot]?.takeIf { it != archivo }?.let { File(it).delete() } // la reemplazada no se queda en el celular
        copy(fotos = fotos + (slot to archivo))
    }

    /** Lo avisa «Elegir de la galería» al empezar y al terminar de copiar (Codex r3 #7). */
    fun alCopiarFoto(copiando: Boolean) =
        _estado.update { it.copy(copiandoFotos = (it.copiandoFotos + if (copiando) 1 else -1).coerceAtLeast(0)) }

    fun alEscribirNumero(slot: Slot, n: String) = cambiar { copy(numeros = numeros + (slot to n.filter(Char::isDigit).take(10))) }

    /** Sólo con precio LIBRE (B7): importe de 0 o más con hasta 2 decimales; 0 = sin costo, como «Regalar» de la PAX. */
    fun alEscribirPrecio(texto: String) = cambiar {
        if (tipoDePrecio != TipoDePrecio.LIBRE) return@cambiar this
        val t = texto.trim()
        val p = t.takeIf { IMPORTE.matches(it) }?.toBigDecimal()
        copy(precioEscrito = t, precio = p)
    }

    /** Se guarda ANTES de cualquier red; un segundo toque (o uno tras guardar) no hace nada. */
    fun registrar() {
        val e = _estado.value
        if (e.registrando || e.guardada || e.buscando) return
        // R6-A1 + r3: generación y sesión de UNA instantánea: un borrado y otra activación no las mezclan.
        val yo = quienSoy.instantanea()
        val generacion = yo.generacion
        val venueId = yo.sesion?.venueId
        val falta = e.falta ?: if (venueId == null) "Entra con tu PIN para vender." else null
        if (falta != null || venueId == null) { _estado.update { it.copy(error = falta) }; return }
        val sim = e.sim!!
        _estado.update { it.copy(registrando = true, error = null) }
        viewModelScope.launch {
            try {
                // Salir de la pantalla a media transacción no la corta: lo que se tocó se guarda o se deshace entero.
                withContext(NonCancellable) {
                    encolador.venta(
                        generacion,
                        VentaNueva(
                            serialNumber = sim.serialNumber, precio = e.precio!!, isPortabilidad = e.portabilidad, venueId = venueId,
                            evidencias = e.slots.map { s -> EvidenciaNueva(s.photoLabel, e.numeros[s].takeIf { e.pideNumero(s) }, e.fotos.getValue(s)) },
                            resumen = "Venta de ${e.config?.etiquetaArticulo ?: "SIM"} ${sim.serialNumber}",
                        ),
                        // 0.6.0 · Codex r3 #1 y #2: sin entrada vigente (ni con la rechazada) no se vende; lo decide la misma
                        // transacción que escribe la venta, con la misma hora que la fecha (la pantalla pudo quedar abierta).
                        exigirEntrada = true,
                    )
                    borrarFotosQueNoSeEncolaron(e) // H5: aquí, y no después, para que salir de la pantalla no las salte
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: CelularBorrandose) {
                _estado.update { it.copy(registrando = false, error = "Espera un momento.") }
                return@launch
            } catch (_: SinEntrada) {
                _estado.update { it.copy(registrando = false, error = CHECA_TU_ENTRADA) }
                return@launch
            } catch (_: SimYaEnCola) {
                _estado.update { it.copy(registrando = false, error = "Esta SIM ya está vendida (o por subir).") }
                return@launch
            } catch (x: Exception) {
                _estado.update { it.copy(registrando = false, error = "No se guardó la venta. ${x.message.orEmpty()}".trim()) }
                return@launch
            }
            // Ya está en la cola: desde aquí nada la vuelve a ofrecer, aunque falle la marca o el aviso al Subidor.
            _estado.update { it.copy(registrando = false, guardada = true) }
            withContext(NonCancellable) {
                despues("marcar la SIM") { copia.marcar(generacion, sim.serialNumber, MarcaLocal.VENDIDA_EN_EL_CELULAR) }
                despues("despertar al Subidor") { Disparador.ahora(context) }
            }
        }
    }

    private suspend fun despues(que: String, f: suspend () -> Unit) {
        try { f() } catch (c: CancellationException) { throw c } catch (x: Exception) { Log.w("Venta", "venta guardada; falló $que", x) }
    }

    /** Después de la confirmación: pantalla limpia para la siguiente. */
    fun venderOtra() {
        if (_estado.value.registrando) return
        borrarFotosSinRegistrar()
        _estado.value = EstadoVenta()
    }

    override fun onCleared() = borrarFotosSinRegistrar()

    /** H5: las de un slot que ya no va (p. ej. portabilidad apagada). Las encoladas son del `Encolador`, que las borra al copiarlas. */
    private fun borrarFotosQueNoSeEncolaron(e: EstadoVenta) {
        val encoladas = e.slots.mapNotNull { e.fotos[it] }.toSet()
        e.fotos.values.filterNot { it in encoladas }.forEach { File(it).delete() }
    }

    /** Las fotos de una venta que no se registró. Las registradas ya son del `Encolador` (y mientras guarda, no se tocan). */
    private fun borrarFotosSinRegistrar() {
        val e = _estado.value
        if (!e.registrando && !e.guardada) e.fotos.values.forEach { File(it).delete() }
    }

    private fun cambiar(f: EstadoVenta.() -> EstadoVenta) {
        if (_estado.value.registrando || _estado.value.guardada) return
        _estado.update { it.f().copy(error = null) }
    }

    private companion object {
        const val NO_ES_TUYA = "Esta SIM no está en tu custodia."
        val IMPORTE = Regex("^\\d{1,7}(\\.\\d{1,2})?$")
    }
}
