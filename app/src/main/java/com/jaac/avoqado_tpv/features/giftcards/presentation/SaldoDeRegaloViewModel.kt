package com.jaac.avoqado_tpv.features.giftcards.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.giftcards.data.CobroConSaldoRepository
import com.jaac.avoqado_tpv.features.giftcards.data.ConsultaDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.DesenlaceDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.FalloDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.PruebaDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo
import com.jaac.avoqado_tpv.features.onlineorders.data.DespuesDeCobrarFolio
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.presentation.AperturaDeCobro
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

enum class EntradaDeCodigo { ESCANEAR, TECLEAR }

/** El folio quedó pagado con saldo (mockup: «Pagado con saldo»). */
data class PagoConSaldo(val montoCents: Long, val last4: String?, val saldoRestanteCents: Long?)

data class SaldoUi(
    /** Lo que falta cobrar del folio (centavos), la cifra grande arriba. */
    val faltaCents: Long = 0,
    val folio: ComandaDeFolio? = null,
    val entrada: EntradaDeCodigo = EntradaDeCodigo.ESCANEAR,
    /** El escáner de cámara a pantalla completa. */
    val escaneando: Boolean = false,
    /** Lo tecleado, limpio (sin guiones, ≤17); los guiones los pone la pantalla. */
    val codigo: String = "",
    /** Lo que se escaneó, para enseñarlo: el código con guiones o «Liga de la tarjeta». */
    val codigoLeido: String? = null,
    /** 2e·4: 17 caracteres cuyo dígito de control no cuadra. */
    val codigoInvalido: Boolean = false,
    val consultando: Boolean = false,
    val consulta: ConsultaDeSaldo? = null,
    val error: String? = null,
    val sinRed: Boolean = false,
    val cobrando: Boolean = false,
    val comprobando: Boolean = false,
    /** No se sabe si el saldo se cobró: la solicitud sigue guardada y el folio no se cobra con nada hasta comprobarla. */
    val enDuda: Boolean = false,
    val pagado: PagoConSaldo? = null,
    /** Evento (2e·3): el saldo no alcanzó y ya se cobró; se abre el cobro de siempre por lo que falta. Valor = centavos pagados con saldo. */
    val faltaPorCobrar: Long? = null,
) {
    /** Lo que se cobra con saldo: lo menor entre el saldo y lo que falta. */
    val montoCents: Long get() = consulta?.takeIf { it.usable }?.let { minOf(it.saldoCents, faltaCents) }?.coerceAtLeast(0) ?: 0
    /** Cobrando o comprobando: el código ya no se cambia (el cobro guardado es de ESA tarjeta). */
    val esperando: Boolean get() = cobrando || comprobando
    val ocupado: Boolean get() = esperando || consultando
    val puedeCobrar: Boolean get() = montoCents > 0 && !ocupado && !enDuda && pagado == null && faltaPorCobrar == null
}

/**
 * El código de una tarjeta de regalo (`giftCardCode.ts` del SERVIDOR): 16 caracteres Crockford Base32 + 1 de control (Luhn mod 32),
 * mostrado `XXXX-XXXX-XXXX-XXXX-X`. Se revisa aquí para avisar un error de tecleo antes de llamar al SERVIDOR (y sin gastar intentos).
 */
object CodigoDeTarjeta {
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LARGO = 17

    /** Mayúsculas, I y L → 1, O → 0 (como el SERVIDOR); fuera lo que no es del alfabeto; a lo más 17. */
    fun limpiar(texto: String): String = texto.uppercase()
        .map { c -> when (c) { 'I', 'L' -> '1'; 'O' -> '0'; else -> c } }
        .filter { it in ALFABETO }
        .take(LARGO)
        .joinToString("")

    fun esValido(limpio: String): Boolean = limpio.length == LARGO && limpio.all { it in ALFABETO } && control(limpio.take(16)) == limpio[16]

    fun formatear(limpio: String): String = limpio.chunked(4).joinToString("-")

    private fun control(carga: String): Char {
        val n = ALFABETO.length
        var factor = 2
        var suma = 0
        for (i in carga.indices.reversed()) {
            var sumando = factor * ALFABETO.indexOf(carga[i])
            factor = if (factor == 2) 1 else 2
            sumando = sumando / n + sumando % n
            suma += sumando
        }
        return ALFABETO[(n - suma % n) % n]
    }

    private val LIGA = Regex("^(?i:https?)://(?i:[^/\\s]+)/tarjeta/([A-Za-z0-9_-]{20,})/?$")

    /** Lo que trae el QR: la liga de la tarjeta (`…/tarjeta/<pageToken>`) o el código; null si no es ninguno válido. */
    fun reconocer(crudo: String): PruebaDeSaldo? {
        val texto = crudo.trim()
        LIGA.matchEntire(texto)?.let { return PruebaDeSaldo.Liga(it.groupValues[1]) }
        return limpiar(texto).takeIf { esValido(it) && texto.count { c -> c.isLetterOrDigit() } == LARGO }?.let { PruebaDeSaldo.Codigo(it) }
    }
}

/** Las dos líneas bajo el código (mockups 2e y 2e·2), o la razón por la que la tarjeta no sirve; null sin consulta. */
fun textoDeSaldo(ui: SaldoUi): Pair<String, String?>? {
    val c = ui.consulta ?: return null
    if (!c.usable) return TextosDeSaldo.noSirve(c.last4, c.razon ?: TextosDeSaldo.razon("EMPTY")!!) to null
    return if (c.saldoCents >= ui.faltaCents) {
        TextosDeSaldo.alcanza(c.saldoCents, ui.montoCents) to null
    } else {
        TextosDeSaldo.noAlcanza(c.saldoCents) to TextosDeSaldo.cobraYFalta(ui.montoCents, ui.faltaCents - ui.montoCents)
    }
}

/**
 * «Saldo o tarjeta de regalo» al cobrar un folio (P2, mockups 2e–2e·4). Escanea o teclea el código, consulta el saldo y cobra lo menor
 * entre el saldo y lo que falta, con el repositorio que guarda el cobro ANTES de mandarlo ([CobroConSaldoRepository]).
 *  - Saldo que cubre todo: [DespuesDeCobrarFolio.alConfirmarse] UNA vez y «Pagado con saldo».
 *  - Saldo que no alcanza: [SaldoUi.faltaPorCobrar] para que la navegación abra el cobro de siempre por lo que falta.
 *  - Sin respuesta: en duda; nada más se cobra de ese folio hasta comprobarlo («Volver a comprobar»).
 */
@HiltViewModel
class SaldoDeRegaloViewModel @Inject constructor(
    private val repo: CobroConSaldoRepository,
    private val despuesDeCobrar: DespuesDeCobrarFolio,
) : ViewModel() {

    private val _ui = MutableStateFlow(SaldoUi())
    val ui: StateFlow<SaldoUi> = _ui.asStateFlow()

    private var orderId: String? = null
    private var prueba: PruebaDeSaldo? = null
    /** El folio se confirma una sola vez, venga del cobro o de una comprobación. */
    private var confirmado = false

    /** Una sola vez por pantalla. Si el folio ya tiene un cobro con saldo guardado, lo comprueba antes de dejar cobrar. */
    fun iniciar(apertura: AperturaDeCobro) {
        if (orderId != null) return
        orderId = apertura.orderId
        val falta = apertura.initialAmount.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.movePointRight(2)?.toLong() ?: 0L
        _ui.update { it.copy(faltaCents = falta, folio = apertura.folio) }
        if (repo.pendienteDe(apertura.orderId) != null) comprobar()
    }

    fun elegir(entrada: EntradaDeCodigo) = _ui.update {
        if (it.esperando) it else it.copy(entrada = entrada, escaneando = entrada == EntradaDeCodigo.ESCANEAR)
    }

    fun cerrarEscaner() = _ui.update { it.copy(escaneando = false) }

    fun escaneado(texto: String) {
        if (_ui.value.esperando) return
        // Lo de antes ya no vale: una consulta vieja que llegue tarde no pinta su saldo sobre este código.
        prueba = null
        val leida = CodigoDeTarjeta.reconocer(texto)
        _ui.update {
            it.copy(
                escaneando = false,
                codigoLeido = when (leida) {
                    is PruebaDeSaldo.Codigo -> CodigoDeTarjeta.formatear(leida.codigo)
                    is PruebaDeSaldo.Liga -> TextosDeSaldo.LIGA_DE_LA_TARJETA
                    null -> null
                },
                codigoInvalido = leida == null,
                consulta = null,
                error = null,
                sinRed = false,
            )
        }
        leida?.let { consultar(it) }
    }

    fun teclear(texto: String) {
        if (_ui.value.esperando) return
        prueba = null
        val limpio = CodigoDeTarjeta.limpiar(texto)
        val completo = limpio.length == CodigoDeTarjeta.LARGO
        val valido = completo && CodigoDeTarjeta.esValido(limpio)
        _ui.update { it.copy(codigo = limpio, codigoInvalido = completo && !valido, consulta = null, error = null, sinRed = false) }
        if (valido) consultar(PruebaDeSaldo.Codigo(limpio))
    }

    fun cobrar() {
        val u = _ui.value
        val id = orderId ?: return
        val p = prueba ?: return
        if (!u.puedeCobrar) return
        val monto = u.montoCents
        _ui.update { it.copy(cobrando = true, error = null) }
        viewModelScope.launch {
            val d = repo.cobrar(id, p, monto)
            _ui.update { it.copy(cobrando = false) }
            atender(d)
        }
    }

    /** «Volver a comprobar»: pregunta por la MISMA solicitud guardada (nunca manda otra llave). */
    fun comprobar() {
        val id = orderId ?: return
        if (_ui.value.cobrando || _ui.value.comprobando) return
        _ui.update { it.copy(comprobando = true) }
        viewModelScope.launch {
            val d = repo.resolver(id)
            _ui.update { it.copy(comprobando = false) }
            if (d == null) _ui.update { it.copy(enDuda = false) } else atender(d)
        }
    }

    fun faltaAtendida() = _ui.update { it.copy(faltaPorCobrar = null) }

    private fun atender(d: DesenlaceDeSaldo) {
        when (d) {
            is DesenlaceDeSaldo.Cobrado -> {
                _ui.update { it.copy(enDuda = false) }
                if (d.restanteCents == 0L) {
                    if (!confirmado) {
                        confirmado = true
                        _ui.value.folio?.let { despuesDeCobrar.alConfirmarse(it) }
                    }
                    _ui.update { it.copy(pagado = PagoConSaldo(d.montoCents, d.last4, d.saldoRestanteCents)) }
                } else {
                    _ui.update { it.copy(faltaPorCobrar = d.montoCents) }
                }
            }
            is DesenlaceDeSaldo.NoSalio -> {
                _ui.update { it.copy(enDuda = false, error = d.texto, consulta = null) }
                // El saldo pudo cambiar: se vuelve a leer para que la cajera vea el de ahora.
                prueba?.let { consultar(it, conservarError = true) }
            }
            DesenlaceDeSaldo.Incierto -> _ui.update { it.copy(enDuda = true) }
        }
    }

    private fun consultar(p: PruebaDeSaldo, conservarError: Boolean = false) {
        prueba = p
        _ui.update { it.copy(consultando = true, consulta = null, sinRed = false, error = if (conservarError) it.error else null) }
        viewModelScope.launch {
            val r = repo.consultar(p)
            // Una respuesta vieja (la cajera ya tecleó otro código) no pisa la nueva.
            if (prueba != p) return@launch
            r.fold(
                onSuccess = { c -> _ui.update { it.copy(consultando = false, consulta = c) } },
                onFailure = { e ->
                    val f = e as? FalloDeSaldo
                    _ui.update {
                        it.copy(
                            consultando = false,
                            error = if (conservarError && it.error != null) it.error else f?.texto ?: TextosDeSaldo.NO_SE_PUDO_CONSULTAR,
                            sinRed = f?.sinRed == true,
                        )
                    }
                },
            )
        }
    }
}
