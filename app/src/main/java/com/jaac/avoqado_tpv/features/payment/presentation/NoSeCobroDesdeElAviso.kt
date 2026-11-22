package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTextField
import com.jaac.avoqado_tpv.core.presentation.systemui.ImmersiveSystemUiEffect
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.remotepayment.AvisoDeCobrosPendientes
import com.jaac.avoqado_tpv.core.remotepayment.ObligacionPendiente
import com.jaac.avoqado_tpv.features.payment.data.ledger.CobroDeclarable
import com.jaac.avoqado_tpv.features.payment.data.ledger.DeclaracionDesdeElAviso
import com.jaac.avoqado_tpv.features.payment.data.ledger.ResultadoDeLaDeclaracion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Cada cuánto se consulta un cobro mientras el banner esté a la vista: si se declaró en el POS, el banner se va en segundos. */
internal const val REFRESCO_DEL_BANNER_MS = 15_000L

private const val PIN_MINIMO = 4
private const val PIN_MAXIMO = 10

/** La regla del servidor (`PIN_REGEX`, `pin.schema.ts`): un PIN largo es la única defensa de un PIN en texto plano. */
internal fun pinDeGerenteValido(pin: String): Boolean = pin.length in PIN_MINIMO..PIN_MAXIMO && pin.all { it in '0'..'9' }

/**
 * Lo que pinta la revisión del gerente (F1 mínimo, tarea 4). Los diálogos sólo dibujan esto: lo que decide vive en [FlujoDeNoSeCobro].
 * Orden: elegir la fila → consultar → UN paso con el PIN del gerente y [Firmar] (Codex código r1 #10) → la respuesta.
 */
internal sealed interface PasoDeNoSeCobro {
    data object Cerrado : PasoDeNoSeCobro
    data object Consultando : PasoDeNoSeCobro
    data class Elegir(val cobros: List<CobroDeclarable>) : PasoDeNoSeCobro
    /**
     * El paso del gerente: dice qué se firma ([textoDeLaFirma]) y [Firmar] manda el PIN. Avoqado decide si ese PIN firma; si no, se
     * vuelve aquí con [pinRechazado]. El PIN nunca vive en el paso: sólo viaja en la llamada.
     */
    data class PedirPin(val cobro: CobroDeclarable, val pinRechazado: Boolean = false, val enviando: Boolean = false) : PasoDeNoSeCobro
    data class Aviso(val mensaje: String) : PasoDeNoSeCobro
}

internal class FlujoDeNoSeCobro(
    private val declaracion: DeclaracionDesdeElAviso,
    private val venueId: String,
    private val alcance: CoroutineScope,
) {
    private val _paso = MutableStateFlow<PasoDeNoSeCobro>(PasoDeNoSeCobro.Cerrado)
    val paso: StateFlow<PasoDeNoSeCobro> = _paso.asStateFlow()

    /** Con un cobro lo abre directo; con varios deja elegir cuál. */
    fun tocar(cobros: List<CobroDeclarable>) {
        if (cobros.size == 1) elegir(cobros.single())
        else if (cobros.isNotEmpty()) _paso.value = PasoDeNoSeCobro.Elegir(cobros)
    }

    /**
     * Consulta la fila ELEGIDA. Si admite declaración, el siguiente paso es el PIN del gerente; si trae dinero o una contradicción,
     * sólo se dice cómo quedó (consulta, sin PIN ni declaración). Si ya se había resuelto, se dice su resultado.
     */
    fun elegir(cobro: CobroDeclarable) {
        _paso.value = PasoDeNoSeCobro.Consultando
        alcance.launch {
            _paso.value = if (!cobro.declarable) PasoDeNoSeCobro.Aviso(declaracion.consultarYDecir(venueId, cobro).texto())
            else when (val resultado = declaracion.preparar(venueId, cobro)) {
                null -> PasoDeNoSeCobro.PedirPin(cobro)
                ResultadoDeLaDeclaracion.Declarado -> PasoDeNoSeCobro.Aviso(declaracion.resultadoDe(venueId, cobro).texto())
                else -> PasoDeNoSeCobro.Aviso(resultado.texto())
            }
        }
    }

    /**
     * [Firmar] (Codex código r1 #10): la declaración de ESA fila con ESE PIN, en el mismo paso del PIN. Un PIN fuera de la regla del
     * servidor no se manda; con un envío en vuelo no hace nada (un segundo toque no manda otro). Si Avoqado dice que ese PIN no firma,
     * se vuelve al mismo paso con el aviso.
     */
    fun firmar(pin: String) {
        val paso = _paso.value as? PasoDeNoSeCobro.PedirPin ?: return
        if (paso.enviando || !pinDeGerenteValido(pin)) return
        val enviando = PasoDeNoSeCobro.PedirPin(paso.cobro, enviando = true)
        _paso.value = enviando
        alcance.launch {
            _paso.value = when (val resultado = declaracion.declarar(venueId, paso.cobro, pin)) {
                // Otro envío seguía en vuelo: no se afirma nada y se puede volver a tocar.
                null -> PasoDeNoSeCobro.PedirPin(paso.cobro)
                ResultadoDeLaDeclaracion.Declarado -> PasoDeNoSeCobro.Cerrado
                ResultadoDeLaDeclaracion.PidePin -> PasoDeNoSeCobro.PedirPin(paso.cobro, pinRechazado = true)
                else -> PasoDeNoSeCobro.Aviso(resultado.texto())
            }
        }
    }

    /** Cancelar cierra (el PIN vivía sólo en el campo). Con una consulta o un envío en vuelo no se cierra. */
    fun cerrar() {
        val paso = _paso.value
        val ocupado = paso is PasoDeNoSeCobro.Consultando || (paso is PasoDeNoSeCobro.PedirPin && paso.enviando)
        if (!ocupado) _paso.value = PasoDeNoSeCobro.Cerrado
    }

    /**
     * La lista cambia (aparece B, cambia el orden): la fila elegida NO cambia. Si la elegida se resolvió en otro lado o dejó de admitir
     * declaración, se dice su resultado en vez de cerrar en silencio. Con un envío en vuelo o un aviso a la vista no se toca nada.
     */
    fun vigentesCambiaron(vigentes: List<CobroDeclarable>) {
        fun sigue(cobro: CobroDeclarable) = vigentes.any { it.identidad == cobro.identidad && it.declarable }
        when (val paso = _paso.value) {
            is PasoDeNoSeCobro.Elegir -> _paso.value = if (vigentes.isEmpty()) PasoDeNoSeCobro.Cerrado else PasoDeNoSeCobro.Elegir(vigentes)
            is PasoDeNoSeCobro.PedirPin -> if (!paso.enviando && !sigue(paso.cobro)) mostrarResultado(paso.cobro)
            else -> Unit
        }
    }

    private fun mostrarResultado(cobro: CobroDeclarable) {
        _paso.value = PasoDeNoSeCobro.Consultando
        alcance.launch { _paso.value = PasoDeNoSeCobro.Aviso(declaracion.resultadoDe(venueId, cobro).texto()) }
    }
}

/**
 * El banner verde de cobros sin confirmar del Inicio y su refresco. 🔴 F1 (tarea 4): ya no declara nada — «Revisar» lleva a Pagos, donde
 * el gerente revisa con su PIN («Por revisar», [CobrosPorRevisar]).
 */
@Composable
internal fun BannerDeCobrosPendientes(
    /** null = no hay nada que avisar: no se pinta el banner. */
    aviso: String?,
    venueId: String,
    ahora: Long,
    declaracion: DeclaracionDesdeElAviso,
    onRevisar: () -> Unit,
    onAyuda: () -> Unit,
) {
    val cobrosFlow = remember(venueId) { declaracion.observarDeclarables(venueId) }
    val cobros by cobrosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val vigentes = DeclaracionDesdeElAviso.vigentes(cobros, ahora)
    val vigentesAlDia by rememberUpdatedState(vigentes)
    val hayDeclarables = vigentes.isNotEmpty()
    val cicloDeVida = LocalLifecycleOwner.current.lifecycle

    // Si se declaró en el POS, el banner se va en segundos. Sin evento de socket nuevo: una consulta, de una en una, sólo con el banner a la vista.
    LaunchedEffect(hayDeclarables, venueId) {
        if (!hayDeclarables) return@LaunchedEffect
        cicloDeVida.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                declaracion.refrescarUno(venueId, vigentesAlDia)
                delay(REFRESCO_DEL_BANNER_MS)
            }
        }
    }

    if (aviso != null) {
        AvisoConBotones(aviso = aviso, onRevisar = onRevisar, onAyuda = onAyuda)
    }
}

/** Cada cuánto se pone al día el «hace N min» del recuadro «Por revisar». */
internal const val RELOJ_DE_POR_REVISAR_MS = 30_000L

/**
 * 🔴 D20 (founder, 17-nov: «lista»): el recuadro «Por revisar» de Pagos, sin la ventana de 10 min. 🔴 F1 (tarea 4): es la entrada del
 * GERENTE: elige la fila exacta (importe, hora y referencia), se identifica con su PIN y firma; una fila con dinero o contradicción sólo
 * se consulta. Sin nada por revisar, no pinta nada.
 */
@Composable
internal fun CobrosPorRevisar(venueId: String, declaracion: DeclaracionDesdeElAviso, zona: java.time.ZoneId) {
    val alcance = rememberCoroutineScope()
    val flujo = remember(venueId) { FlujoDeNoSeCobro(declaracion, venueId, alcance) }
    val paso by flujo.paso.collectAsStateWithLifecycle()
    // Codex r2: con más de 50 por revisar, «Ver anteriores» trae otros 50; sin esto los viejos sólo salían al resolver los recientes.
    var limite by remember(venueId) { mutableStateOf(DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA) }
    val cobrosFlow = remember(venueId, limite) { declaracion.observarDeclarables(venueId, limite) }
    val cobros by cobrosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // F1 bloque 5: con el total exacto, «Ver anteriores» sigue hasta el último renglón.
    val total by remember(venueId) { declaracion.observarTotal(venueId) }.collectAsStateWithLifecycle(initialValue = 0)
    val recortada = DeclaracionDesdeElAviso.faltanRenglones(cobros, total)
    val porRevisar = DeclaracionDesdeElAviso.porRevisar(cobros)
    val porRevisarAlDia by rememberUpdatedState(porRevisar)
    val hayPorRevisar = porRevisar.isNotEmpty()
    val ahora by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(RELOJ_DE_POR_REVISAR_MS)
            value = System.currentTimeMillis()
        }
    }
    val cicloDeVida = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(porRevisar) { flujo.vigentesCambiaron(porRevisar) }
    // Si otro aparato o el POS lo resolvió, sale de la lista en segundos: una consulta, de una en una, sólo con Pagos a la vista.
    // hz:248b195b:D20 (la duda firmada en la tablet seguía ~20 min): al mostrarse (y al volver), la rueda da hasta 10 vueltas sin pausa.
    // Si el barrido se cortó (sin respuesta), la rueda sigue en el acto (Codex r1 #2); si no, su siguiente vuelta espera los 15 s.
    LaunchedEffect(hayPorRevisar, venueId) {
        if (!hayPorRevisar) return@LaunchedEffect
        cicloDeVida.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (declaracion.refrescarAlMostrarse(venueId, porRevisarAlDia)) delay(REFRESCO_DEL_BANNER_MS)
            while (true) {
                declaracion.refrescarUno(venueId, porRevisarAlDia)
                delay(REFRESCO_DEL_BANNER_MS)
            }
        }
    }

    val texto = AvisoDeCobrosPendientes.textoPorRevisar(
        porRevisar.map { ObligacionPendiente(it.totalCentavos, it.desdeMillis) }, ahora, recortada = recortada,
    )
    if (texto != null) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = texto, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (recortada) {
                        TextButton(onClick = { limite += DeclaracionDesdeElAviso.TOPE_DE_LA_CONSULTA }) {
                            Text("Ver anteriores")
                        }
                    }
                    TextButton(onClick = { flujo.tocar(porRevisar) }) {
                        Text("Revisar")
                    }
                }
            }
        }
    }
    DialogoDeNoSeCobro(paso, zona, onElegir = flujo::elegir, onFirmar = flujo::firmar, onCerrar = flujo::cerrar)
}

/** El texto arriba y los botones abajo: los tres no caben junto al texto en los 360 dp de la PAX. */
@Composable
internal fun AvisoConBotones(
    aviso: String,
    onRevisar: () -> Unit,
    onAyuda: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRevisar)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = aviso,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRevisar) {
                    Text("Revisar")
                }
                TextButton(onClick = onAyuda) {
                    Text("Ayuda")
                }
            }
        }
    }
}

@Composable
private fun DialogoDeNoSeCobro(
    paso: PasoDeNoSeCobro,
    zona: java.time.ZoneId,
    onElegir: (CobroDeclarable) -> Unit,
    onFirmar: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    when (paso) {
        PasoDeNoSeCobro.Cerrado -> Unit
        PasoDeNoSeCobro.Consultando -> ConsultandoDialog()
        is PasoDeNoSeCobro.Elegir -> ListaDeCobrosDialog(paso.cobros, zona, onElegir, onCerrar)
        is PasoDeNoSeCobro.PedirPin ->
            PinDeGerenteDialog(textoDeLaFirma(paso.cobro, zona), paso.pinRechazado, paso.enviando, onFirmar, onCerrar)
        is PasoDeNoSeCobro.Aviso -> AvisoDialog(paso.mensaje, onCerrar)
    }
}

private fun renglon(cobro: CobroDeclarable, zona: java.time.ZoneId): String =
    AvisoDeCobrosPendientes.renglonParaElGerente(cobro.totalCentavos, cobro.desdeMillis, cobro.referenciaCorta, zona)

@Composable
private fun ConsultandoDialog() {
    AlertDialog(
        onDismissRequest = {},
        text = {
            ImmersiveSystemUiEffect()
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ListaDeCobrosDialog(
    cobros: List<CobroDeclarable>,
    zona: java.time.ZoneId,
    onElegir: (CobroDeclarable) -> Unit,
    onCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            ImmersiveSystemUiEffect()
            Text("¿Cuál vas a revisar?")
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                cobros.forEach { cobro ->
                    TextButton(onClick = { onElegir(cobro) }, modifier = Modifier.fillMaxWidth()) {
                        Text(renglon(cobro, zona) + if (cobro.declarable) "" else " · sólo consulta")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCerrar) {
                Text("Cancelar")
            }
        },
    )
}

/**
 * F1 (tarea 4, Codex código r1 #10 y r2 #7): lo que firma el gerente, COMPLETO, en el mismo paso del PIN: que revisó la terminal y ese
 * cobro no aparece — exactamente lo que Avoqado le atribuye después. En la PAX, que el banco dijo «no» sólo lo afirma el reverso de
 * Blumon, así que el texto nunca dice «no se cobró» ni presenta la firma como respuesta del banco. La referencia distingue dos cobros
 * del mismo importe y minuto.
 */
internal fun textoDeLaFirma(cobro: CobroDeclarable, zona: java.time.ZoneId): String =
    "Gerente: revisé la terminal y este cobro de ${AvisoDeCobrosPendientes.pesosYHora(cobro.totalCentavos, cobro.desdeMillis, zona)} " +
        "(ref ${cobro.referenciaCorta}) no aparece. Escribe tu PIN para firmarlo."

/** K31 en un solo paso (Codex código r1 #10): qué se firma, el PIN y [Firmar]. Avoqado decide si ese PIN firma. */
@Composable
private fun PinDeGerenteDialog(
    queSeFirma: String,
    pinRechazado: Boolean,
    enviando: Boolean,
    onFirmar: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    // En memoria, nunca en el estado guardado: el PIN no sobrevive a la pantalla.
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(pinRechazado) {
        if (pinRechazado) pin = ""
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        text = {
            ImmersiveSystemUiEffect()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(queSeFirma, style = MaterialTheme.typography.bodyMedium)
                AvoqadoTextField(
                    value = pin,
                    onValueChange = { nuevo -> if (!enviando && nuevo.length <= PIN_MAXIMO && nuevo.all { it in '0'..'9' }) pin = nuevo },
                    label = "PIN de gerente",
                    errorMessage = if (pinRechazado) "Ese PIN no es de un gerente con permiso." else null,
                    // Con el teclado abierto no se ve «Firmar» (QA en la N86): la palomita del teclado también firma.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pinDeGerenteValido(pin)) onFirmar(pin) }),
                    isPassword = true,
                )
                Text("Queda en Avoqado con tu nombre y la hora. Necesita internet.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            AvoqadoButton(
                text = "Firmar",
                onClick = { onFirmar(pin) },
                enabled = pinDeGerenteValido(pin),
                loading = enviando,
            )
        },
        dismissButton = {
            TextButton(onClick = onCerrar, enabled = !enviando) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun AvisoDialog(mensaje: String, onCerrar: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCerrar,
        text = {
            ImmersiveSystemUiEffect()
            Text(mensaje, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            TextButton(onClick = onCerrar) {
                Text("Entendido")
            }
        },
    )
}

private const val AHORA_DE_EJEMPLO = 1_700_000_000_000L
private val cobroDeEjemplo = CobroDeclarable("a", "r-6f1c2a", 12_000, AHORA_DE_EJEMPLO - 3 * 60_000, null)
private val ZONA_DE_EJEMPLO: java.time.ZoneId = java.time.ZoneOffset.ofHours(-6)

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun BannerConBotonPreview() {
    AvoqadoTheme(darkTheme = false) {
        Column {
            AvisoConBotones(
                aviso = "Quedaron 2 cobros sin confirmar: $120.00 hace 3 min, $50.00 hace 8 min. Si alguno es esta venta, no la cobres otra vez.",
                onRevisar = {},
                onAyuda = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun BannerSinDeclarablesPreview() {
    AvoqadoTheme(darkTheme = false) {
        Column {
            AvisoConBotones(
                aviso = "Quedó un cobro de $120.00 sin confirmar hace 3 min. Si es esta misma venta, no la cobres otra vez.",
                onRevisar = {},
                onAyuda = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ListaPreview() {
    AvoqadoTheme(darkTheme = false) {
        ListaDeCobrosDialog(
            cobros = listOf(cobroDeEjemplo, cobroDeEjemplo.copy(attemptId = "b", requestId = null, totalCentavos = 5_000, declarable = false)),
            zona = ZONA_DE_EJEMPLO,
            onElegir = {},
            onCerrar = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun PinPreview() {
    AvoqadoTheme(darkTheme = false) {
        PinDeGerenteDialog(textoDeLaFirma(cobroDeEjemplo, ZONA_DE_EJEMPLO), pinRechazado = true, enviando = false, onFirmar = {}, onCerrar = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun AvisoPreview() {
    AvoqadoTheme(darkTheme = false) {
        AvisoDialog(ResultadoDeLaDeclaracion.SinPermiso.texto(), onCerrar = {})
    }
}
