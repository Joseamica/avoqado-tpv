package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Lo que pinta el botón «No se cobró». Los diálogos sólo dibujan esto: lo que decide vive en [FlujoDeNoSeCobro]. */
internal sealed interface PasoDeNoSeCobro {
    data object Cerrado : PasoDeNoSeCobro
    data object Consultando : PasoDeNoSeCobro
    data class Elegir(val cobros: List<CobroDeclarable>) : PasoDeNoSeCobro
    data class Confirmar(val cobro: CobroDeclarable, val enviando: Boolean = false) : PasoDeNoSeCobro
    data class PedirPin(val cobro: CobroDeclarable, val enviando: Boolean = false, val pinRechazado: Boolean = false) : PasoDeNoSeCobro
    data class Aviso(val mensaje: String) : PasoDeNoSeCobro
}

internal class FlujoDeNoSeCobro(
    private val declaracion: DeclaracionDesdeElAviso,
    private val venueId: String,
    private val alcance: CoroutineScope,
) {
    private val _paso = MutableStateFlow<PasoDeNoSeCobro>(PasoDeNoSeCobro.Cerrado)
    val paso: StateFlow<PasoDeNoSeCobro> = _paso.asStateFlow()

    /** Con un cobro pide confirmar el importe; con varios deja elegir cuál. */
    fun tocar(cobros: List<CobroDeclarable>) {
        if (cobros.size == 1) elegir(cobros.single())
        else if (cobros.isNotEmpty()) _paso.value = PasoDeNoSeCobro.Elegir(cobros)
    }

    fun elegir(cobro: CobroDeclarable) {
        _paso.value = PasoDeNoSeCobro.Consultando
        alcance.launch {
            _paso.value = when (val resultado = declaracion.preparar(venueId, cobro)) {
                null -> PasoDeNoSeCobro.Confirmar(cobro)
                ResultadoDeLaDeclaracion.Declarado -> PasoDeNoSeCobro.Cerrado
                else -> PasoDeNoSeCobro.Aviso(resultado.texto())
            }
        }
    }

    /** [pin] = el del gerente, sólo desde el paso del PIN. Con un envío en vuelo no hace nada: un segundo toque no se manda. */
    fun confirmar(pin: String? = null) {
        val paso = _paso.value
        val cobro = when {
            paso is PasoDeNoSeCobro.Confirmar && !paso.enviando && pin == null -> paso.cobro
            paso is PasoDeNoSeCobro.PedirPin && !paso.enviando && pin != null && pinDeGerenteValido(pin) -> paso.cobro
            else -> return
        }
        _paso.value = when (paso) {
            is PasoDeNoSeCobro.PedirPin -> paso.copy(enviando = true)
            else -> PasoDeNoSeCobro.Confirmar(cobro, enviando = true)
        }
        alcance.launch {
            _paso.value = when (val resultado = declaracion.declarar(venueId, cobro, pin)) {
                // Otro envío seguía en vuelo: no se afirma nada y se puede volver a tocar.
                null -> if (pin == null) PasoDeNoSeCobro.Confirmar(cobro) else PasoDeNoSeCobro.PedirPin(cobro)
                ResultadoDeLaDeclaracion.Declarado -> PasoDeNoSeCobro.Cerrado
                ResultadoDeLaDeclaracion.PidePin -> PasoDeNoSeCobro.PedirPin(cobro, pinRechazado = pin != null)
                else -> PasoDeNoSeCobro.Aviso(resultado.texto())
            }
        }
    }

    fun cerrar() {
        val paso = _paso.value
        val ocupado = paso is PasoDeNoSeCobro.Consultando || (paso is PasoDeNoSeCobro.Confirmar && paso.enviando) ||
            (paso is PasoDeNoSeCobro.PedirPin && paso.enviando)
        if (!ocupado) _paso.value = PasoDeNoSeCobro.Cerrado
    }

    /** Si se declaró en el POS con el diálogo abierto, o venció la ventana, el diálogo se va solo. Un aviso se queda: hay que leerlo. */
    fun vigentesCambiaron(vigentes: List<CobroDeclarable>) {
        fun sigue(cobro: CobroDeclarable) = vigentes.any { it.identidad == cobro.identidad }
        val paso = _paso.value
        _paso.value = when {
            paso is PasoDeNoSeCobro.Elegir -> if (vigentes.isEmpty()) PasoDeNoSeCobro.Cerrado else PasoDeNoSeCobro.Elegir(vigentes)
            paso is PasoDeNoSeCobro.Confirmar && !paso.enviando && !sigue(paso.cobro) -> PasoDeNoSeCobro.Cerrado
            paso is PasoDeNoSeCobro.PedirPin && !paso.enviando && !sigue(paso.cobro) -> PasoDeNoSeCobro.Cerrado
            else -> paso
        }
    }
}

/** El banner verde de cobros sin confirmar, con su botón «No se cobró», sus diálogos y su refresco. */
@Composable
internal fun BannerDeCobrosPendientes(
    aviso: String,
    venueId: String,
    ahora: Long,
    declaracion: DeclaracionDesdeElAviso,
    onRevisar: () -> Unit,
    onAyuda: () -> Unit,
) {
    val alcance = rememberCoroutineScope()
    val flujo = remember(venueId) { FlujoDeNoSeCobro(declaracion, venueId, alcance) }
    val paso by flujo.paso.collectAsStateWithLifecycle()
    val cobrosFlow = remember(venueId) { declaracion.observarDeclarables(venueId) }
    val cobros by cobrosFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val vigentes = DeclaracionDesdeElAviso.vigentes(cobros, ahora)
    val vigentesAlDia by rememberUpdatedState(vigentes)
    val hayDeclarables = vigentes.isNotEmpty()
    val cicloDeVida = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(vigentes) { flujo.vigentesCambiaron(vigentes) }
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

    AvisoConBotones(
        aviso = aviso,
        mostrarNoSeCobro = hayDeclarables,
        onNoSeCobro = { flujo.tocar(vigentes) },
        onRevisar = onRevisar,
        onAyuda = onAyuda,
    )
    DialogoDeNoSeCobro(paso, ahora, onElegir = flujo::elegir, onConfirmar = flujo::confirmar, onCerrar = flujo::cerrar)
}

/** El texto arriba y los botones abajo: los tres no caben junto al texto en los 360 dp de la PAX. */
@Composable
internal fun AvisoConBotones(
    aviso: String,
    mostrarNoSeCobro: Boolean,
    onNoSeCobro: () -> Unit,
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
                if (mostrarNoSeCobro) {
                    TextButton(onClick = onNoSeCobro) {
                        Text("No se cobró")
                    }
                }
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
    ahora: Long,
    onElegir: (CobroDeclarable) -> Unit,
    onConfirmar: (String?) -> Unit,
    onCerrar: () -> Unit,
) {
    when (paso) {
        PasoDeNoSeCobro.Cerrado -> Unit
        PasoDeNoSeCobro.Consultando -> ConsultandoDialog()
        is PasoDeNoSeCobro.Elegir -> ListaDeCobrosDialog(paso.cobros, ahora, onElegir, onCerrar)
        is PasoDeNoSeCobro.Confirmar -> ConfirmacionDialog(paso.cobro, ahora, paso.enviando, onConfirmar = { onConfirmar(null) }, onCerrar)
        is PasoDeNoSeCobro.PedirPin -> PinDeGerenteDialog(paso.enviando, paso.pinRechazado, onConfirmar = onConfirmar, onCerrar)
        is PasoDeNoSeCobro.Aviso -> AvisoDialog(paso.mensaje, onCerrar)
    }
}

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
    ahora: Long,
    onElegir: (CobroDeclarable) -> Unit,
    onCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            ImmersiveSystemUiEffect()
            Text("¿Cuál no se cobró?")
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                cobros.forEach { cobro ->
                    TextButton(onClick = { onElegir(cobro) }, modifier = Modifier.fillMaxWidth()) {
                        Text(AvisoDeCobrosPendientes.renglonDeCobro(cobro.totalCentavos, cobro.desdeMillis, ahora))
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

@Composable
private fun ConfirmacionDialog(
    cobro: CobroDeclarable,
    ahora: Long,
    enviando: Boolean,
    onConfirmar: () -> Unit,
    onCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            ImmersiveSystemUiEffect()
            Text("¿No se cobró este cobro?")
        },
        text = {
            Text(
                AvisoDeCobrosPendientes.importeYAntiguedad(cobro.totalCentavos, cobro.desdeMillis, ahora) +
                    ". Revísalo antes de confirmar: queda tu nombre y la hora. Si el banco avisa que sí se cobró, se registra como cobrado.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            AvoqadoButton(text = "Sí, no se cobró", onClick = onConfirmar, loading = enviando)
        },
        dismissButton = {
            TextButton(onClick = onCerrar, enabled = !enviando) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun PinDeGerenteDialog(
    enviando: Boolean,
    pinRechazado: Boolean,
    onConfirmar: (String) -> Unit,
    onCerrar: () -> Unit,
) {
    // En memoria, nunca en el estado guardado: el PIN no sobrevive a la pantalla.
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(pinRechazado, enviando) {
        if (pinRechazado && !enviando) pin = ""
    }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = {
            ImmersiveSystemUiEffect()
            Text("Pide a un gerente que ingrese su PIN")
        },
        text = {
            AvoqadoTextField(
                value = pin,
                onValueChange = { nuevo -> if (nuevo.length <= PIN_MAXIMO && nuevo.all { it in '0'..'9' }) pin = nuevo },
                label = "PIN de gerente",
                errorMessage = if (pinRechazado) "Ese PIN no tiene permiso para confirmarlo." else null,
                enabled = !enviando,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isPassword = true,
            )
        },
        confirmButton = {
            AvoqadoButton(
                text = "Confirmar",
                onClick = { onConfirmar(pin) },
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
private val cobroDeEjemplo = CobroDeclarable("a", "r", 12_000, AHORA_DE_EJEMPLO - 3 * 60_000, null)

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun BannerConBotonPreview() {
    AvoqadoTheme(darkTheme = false) {
        Column {
            AvisoConBotones(
                aviso = "Quedaron 2 cobros sin confirmar: $120.00 hace 3 min, $50.00 hace 8 min. Si alguno es esta venta, no la cobres otra vez.",
                mostrarNoSeCobro = true,
                onNoSeCobro = {},
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
                mostrarNoSeCobro = false,
                onNoSeCobro = {},
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
            cobros = listOf(cobroDeEjemplo, cobroDeEjemplo.copy(attemptId = "b", requestId = null, totalCentavos = 5_000)),
            ahora = AHORA_DE_EJEMPLO,
            onElegir = {},
            onCerrar = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ConfirmacionPreview() {
    AvoqadoTheme(darkTheme = false) {
        ConfirmacionDialog(cobroDeEjemplo, AHORA_DE_EJEMPLO, enviando = false, onConfirmar = {}, onCerrar = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun PinPreview() {
    AvoqadoTheme(darkTheme = false) {
        PinDeGerenteDialog(enviando = false, pinRechazado = true, onConfirmar = {}, onCerrar = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun AvisoPreview() {
    AvoqadoTheme(darkTheme = false) {
        AvisoDialog(ResultadoDeLaDeclaracion.SinPermiso.texto(), onCerrar = {})
    }
}
