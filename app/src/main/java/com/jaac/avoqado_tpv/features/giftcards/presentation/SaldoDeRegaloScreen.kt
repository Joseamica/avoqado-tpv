package com.jaac.avoqado_tpv.features.giftcards.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Warning
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.giftcards.data.ConsultaDeSaldo
import com.jaac.avoqado_tpv.features.giftcards.data.TextosDeSaldo
import com.jaac.avoqado_tpv.features.onlineorders.domain.ComandaDeFolio
import com.jaac.avoqado_tpv.features.onlineorders.presentation.AperturaDeCobro
import com.jaac.avoqado_tpv.features.onlineorders.presentation.FolioDelCobro
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen

/**
 * «Saldo o tarjeta de regalo» al cobrar un folio (mockups v2 2e, 2e·2 y 2e·4). Arriba lo que falta y el folio; abajo «Escanear» (el escáner
 * de cámara de la terminal, como `BarcodeQuickAddScreen`) o «Teclear código» (guiones solos, dígito de control al instante); luego el saldo
 * y «Cobrar $X». Mientras se cobra o se comprueba no se puede salir: el cobro ya está guardado y se espera su desenlace.
 *
 * [onPagado]: el saldo cubrió todo (el folio ya se confirmó). [onFalta]: se cobró parte; abre el cobro de siempre por lo que falta.
 */
@Composable
fun SaldoDeRegaloScreen(
    apertura: AperturaDeCobro,
    onSalir: () -> Unit,
    onPagado: () -> Unit,
    onFalta: (pagadoConSaldoCents: Long) -> Unit,
    viewModel: SaldoDeRegaloViewModel = hiltViewModel(),
) {
    LaunchedEffect(apertura.orderId) { viewModel.iniciar(apertura) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.faltaPorCobrar) {
        val pagado = ui.faltaPorCobrar ?: return@LaunchedEffect
        viewModel.faltaAtendida()
        onFalta(pagado)
    }
    SaldoDeRegaloContenido(
        ui = ui,
        onSalir = onSalir,
        onPagado = onPagado,
        onElegir = viewModel::elegir,
        onTeclear = viewModel::teclear,
        onEscaneado = viewModel::escaneado,
        onCerrarEscaner = viewModel::cerrarEscaner,
        onCobrar = viewModel::cobrar,
        onComprobar = viewModel::comprobar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaldoDeRegaloContenido(
    ui: SaldoUi,
    onSalir: () -> Unit,
    onPagado: () -> Unit,
    onElegir: (EntradaDeCodigo) -> Unit,
    onTeclear: (String) -> Unit,
    onEscaneado: (String) -> Unit,
    onCerrarEscaner: () -> Unit,
    onCobrar: () -> Unit,
    onComprobar: () -> Unit,
) {
    // Cobrando o comprobando, «Atrás» no hace nada; ya pagado, «Atrás» es «Listo».
    val esperando = ui.cobrando || ui.comprobando
    BackHandler(enabled = esperando || ui.pagado != null || ui.escaneando) {
        when {
            ui.escaneando -> onCerrarEscaner()
            ui.pagado != null -> onPagado()
        }
    }
    if (ui.escaneando) {
        // El QR de «Usar en caja» (la liga o el código) o el código de barras del certificado impreso.
        BarcodeScannerScreen(
            onBarcodeScanned = { texto, _ -> onEscaneado(texto) },
            onClose = onCerrarEscaner,
            requireMultiFrameConsensus = true,
            formats = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128),
            instruccion = TextosDeSaldo.INSTRUCCION_DEL_ESCANER,
            // Codex r2 #2: con el código o la liga se gasta el saldo; nunca al log.
            ocultarContenido = true,
        )
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (!esperando) {
                        IconButton(onClick = { if (ui.pagado != null) onPagado() else onSalir() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                esperando -> Esperando()
                ui.pagado != null -> Pagado(ui, onPagado)
                ui.enDuda -> EnDuda(onComprobar, onSalir)
                else -> Captura(ui, onSalir, onElegir, onTeclear, onCobrar)
            }
        }
    }
}

@Composable
private fun Encabezado(ui: SaldoUi) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(TextosDeSaldo.pesos(ui.faltaCents), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        ui.folio?.let { FolioDelCobro(it, Modifier.padding(top = 4.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Captura(
    ui: SaldoUi,
    onSalir: () -> Unit,
    onElegir: (EntradaDeCodigo) -> Unit,
    onTeclear: (String) -> Unit,
    onCobrar: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Encabezado(ui)
            Spacer(Modifier.height(20.dp))
            Text(TextosDeSaldo.TITULO, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EntradaDeCodigo.entries.forEachIndexed { i, entrada ->
                    SegmentedButton(
                        selected = ui.entrada == entrada,
                        onClick = { onElegir(entrada) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = EntradaDeCodigo.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (entrada == EntradaDeCodigo.ESCANEAR) TextosDeSaldo.ESCANEAR else TextosDeSaldo.TECLEAR) }
                }
            }
            Spacer(Modifier.height(14.dp))
            when (ui.entrada) {
                EntradaDeCodigo.TECLEAR -> CampoDeCodigo(ui, onTeclear)
                EntradaDeCodigo.ESCANEAR -> {
                    ui.codigoLeido?.let { Renglon(TextosDeSaldo.CODIGO, it) }
                    if (ui.codigoInvalido) Text(TextosDeSaldo.CODIGO_NO_VALIDO, color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
                    OutlinedButton(
                        onClick = { onElegir(EntradaDeCodigo.ESCANEAR) },
                        enabled = !ui.esperando,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(TextosDeSaldo.ESCANEAR_QR, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Estado(ui)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onSalir,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(TextosDeSaldo.CANCELAR, fontSize = 16.sp) }
            Button(
                onClick = onCobrar,
                enabled = ui.puedeCobrar,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.4f).height(56.dp),
            ) {
                Text(
                    etiquetaDeCobrar(ui),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Lo que pasa bajo el código: cargando, sin red, error, la tarjeta que no sirve, o el saldo (2e, 2e·2); vacío = la ayuda. */
@Composable
private fun ColumnScope.Estado(ui: SaldoUi) {
    val apagado = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        ui.consultando -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(TextosDeSaldo.CONSULTANDO, color = apagado, fontSize = 15.sp)
        }
        ui.sinRed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.avoqadoColors.offlineOrange, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text(ui.error ?: TextosDeSaldo.SIN_RED, fontSize = 15.sp)
        }
        else -> {
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            val lineas = textoDeSaldo(ui)
            when {
                lineas != null -> {
                    // Full-testing H5: «alcanza» en verde; «no alcanza» y la tarjeta que no sirve, en ámbar.
                    val alcanza = ui.consulta?.usable == true && (ui.consulta?.saldoCents ?: 0) >= ui.faltaCents
                    Text(
                        lineas.first,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (alcanza) MaterialTheme.avoqadoColors.statusSuccess else Warning,
                    )
                    lineas.second?.let { Text(it, fontSize = 15.sp, color = apagado, modifier = Modifier.padding(top = 4.dp)) }
                }
                ui.error == null && !ui.codigoInvalido -> Text(TextosDeSaldo.AYUDA, fontSize = 15.sp, color = apagado)
            }
        }
    }
}

@Composable
private fun CampoDeCodigo(ui: SaldoUi, onTeclear: (String) -> Unit) {
    val foco = LocalFocusManager.current
    OutlinedTextField(
        value = ui.codigo,
        onValueChange = onTeclear,
        singleLine = true,
        enabled = !ui.cobrando,
        label = { Text(TextosDeSaldo.CODIGO) },
        placeholder = { Text("XXXX-XXXX-XXXX-XXXX-X") },
        isError = ui.codigoInvalido,
        supportingText = if (ui.codigoInvalido) {
            { Text(TextosDeSaldo.CODIGO_NO_VALIDO) }
        } else {
            null
        },
        visualTransformation = GuionesDelCodigo,
        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
        // Full-testing H3: con este código se gasta el saldo. Teclado de contraseña (sin sugerencias y sin que aprenda lo tecleado) y sin
        // autocorrección; lo visible lo pinta GuionesDelCodigo.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { foco.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** «XXXX-XXXX-XXXX-XXXX-X»: el valor guardado va sin guiones; la pantalla los pone (un guion cada 4). */
private object GuionesDelCodigo : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val crudo = text.text
        val conGuiones = CodigoDeTarjeta.formatear(crudo)
        return TransformedText(
            AnnotatedString(conGuiones),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = (offset + offset / 4).coerceAtMost(conGuiones.length)
                override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, crudo.length)
            },
        )
    }
}

@Composable
private fun Renglon(etiqueta: String, valor: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(etiqueta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        Text(valor, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Esperando() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(TextosDeSaldo.COMPROBANDO, fontSize = 17.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EnDuda(onComprobar: () -> Unit, onSalir: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Warning, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(TextosDeSaldo.EN_DUDA_TITULO, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(TextosDeSaldo.EN_DUDA_CUERPO, fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onComprobar, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(TextosDeSaldo.VOLVER_A_COMPROBAR, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onSalir, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(TextosDeSaldo.SALIR)
        }
    }
}

@Composable
private fun Pagado(ui: SaldoUi, onListo: () -> Unit) {
    val p = ui.pagado ?: return
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.avoqadoColors.statusSuccess, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(TextosDeSaldo.PAGADO, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(TextosDeSaldo.pesos(p.montoCents), fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        ui.folio?.let { FolioDelCobro(it, Modifier.padding(top = 4.dp)) }
        TextosDeSaldo.tarjetaYSaldo(p.last4, p.saldoRestanteCents).takeIf { it.isNotEmpty() }?.let {
            Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onListo, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(TextosDeSaldo.LISTO, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// region — vistas previas (PAX A910S, 360×640), oscuras y claras

private val COMANDA = ComandaDeFolio("cord1", "W7K3P", "Ana", null, emptyList())

@Composable
private fun Previa(ui: SaldoUi, oscura: Boolean = true) = AvoqadoTheme(darkTheme = oscura) {
    SaldoDeRegaloContenido(ui, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaAlcanza() = Previa(
    SaldoUi(faltaCents = 13400, folio = COMANDA, entrada = EntradaDeCodigo.TECLEAR, codigo = "K7Q29DXM4R8HHB7TR", consulta = ConsultaDeSaldo("HB7T", 35000, true, null)),
)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaNoAlcanzaClara() = Previa(
    SaldoUi(faltaCents = 13400, folio = COMANDA, codigoLeido = "M3TX-8KQ4-2WZN-7RJC-W", consulta = ConsultaDeSaldo("7RJC", 5000, true, null)),
    oscura = false,
)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaCodigoMalo() = Previa(
    SaldoUi(faltaCents = 13400, folio = COMANDA, entrada = EntradaDeCodigo.TECLEAR, codigo = "K7Q29DXM4R8HHB7TQ", codigoInvalido = true),
)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaEnDuda() = Previa(SaldoUi(faltaCents = 13400, folio = COMANDA, enDuda = true))

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun PreviaPagado() = Previa(SaldoUi(faltaCents = 13400, folio = COMANDA, pagado = PagoConSaldo(13400, "HB7T", 21600)))

// endregion
