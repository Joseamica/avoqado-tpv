package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import com.jaac.avoqado_tpv.features.payment.data.ledger.FALTA_CONFIRMAR_LA_REVISION
import com.jaac.avoqado_tpv.features.payment.data.ledger.SE_MARCO_COMO_NO_COBRADO
import java.math.BigDecimal

/**
 * 🔴 F1 mínimo, tarea 5 (17-nov): lo que ve el cajero de una tarjeta en duda, en la PAX y en la N86. Sólo el importe del intento
 * (con propina) y qué hacer; ninguna decisión: la revisa el gerente con su PIN en «Por revisar». null = la fila todavía no se lee.
 */
internal fun tarjetaPorConfirmar(total: BigDecimal?): String =
    "Tarjeta ${total?.let { CurrencyFormatter.format(it) + " " }.orEmpty()}por confirmar. No la repitas. Avisa al gerente."

/** F1: el total del intento guardado (importe + propina), nunca el de la pantalla que esté abierta. */
internal fun totalDelIntento(row: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity): BigDecimal =
    BigDecimal.valueOf(row.amountCents + row.tipCents, 2)

/** K19a: mientras se consulta. */
internal const val CONFIRMANDO_CON_EL_BANCO = "Confirmando con el banco… No pases la tarjeta otra vez."

/** Codex final r1 #9 (N86): venció la espera y Avoqado nunca contestó. El texto de la base, corto. */
internal const val SIN_CONEXION_SE_REVISA_AL_VOLVER = "Sin conexión con Avoqado: este cobro se revisa solo al volver la red."

/**
 * «Intento cerrado»: con `declarado_sin_cobro:` lo declaró el cajero (D12) — y con la marca de D22, Avoqado lo guardó; si no, lo
 * liberó el servidor. Sin la marca se dice «no aparece en Avoqado» aunque una respuesta perdida lo haya guardado: la pantalla dice
 * de menos, nunca de más, y la siguiente consulta agrega la marca (`marcarDeclaracionEnAvoqado`).
 */
internal fun textoDelIntentoCerrado(
    lastError: String?,
    /** 🔴 Familia «el texto miente» (Codex final r2): si algo de este cobro todavía frena su tarjeta, eso se dice en vez de «volver a cobrar». */
    freno: com.jaac.avoqado_tpv.features.payment.data.ledger.LoQueFrenaElResuelto? = null,
): String =
    // F1-textos (catálogo D5, fila 5 ⚙): la frase según la fila que frena (cobro del POS o de la terminal sola).
    if (freno != null) freno.textoSegunElFlujo
    // F1-textos (fila 9a · F-D): quién lo decidió y que la cuenta ya se puede cobrar, igual que ANDROID y el SERVIDOR.
    else if (com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.declaradoEnAvoqado(lastError)) SE_MARCO_COMO_NO_COBRADO
    // F1-textos (fila 9b · F-F): no consta que Avoqado lo tenga (puede haberse perdido la respuesta): no se invita a cobrar.
    else if (lastError?.startsWith("declarado_sin_cobro:") == true) FALTA_CONFIRMAR_LA_REVISION
    // D5 (533d77, Codex r4): neutral. La liberación del servidor deja el mismo `last_error` para «no presentó tarjeta» y para
    // «revisé y no se cobró» declarado en el POS; en un chip que rechazó tras aprobar, la tarjeta SÍ se presentó. F1-textos (9c · F-D).
    else SE_MARCO_COMO_NO_COBRADO

/**
 * F1-textos (fila 17, Codex fila17 #1 y #2): la frase de la barrera es una foto del momento en que frenó. Sólo se enseña mientras la
 * duda sigue por confirmar; registrada, con evidencia o cerrada, su propio texto manda («Se libera sola a los 5 min» ya no sería cierto).
 */
internal fun avisoDeLaBarreraVisible(aviso: String?, estado: BlumonAttemptResolver.State): String? =
    aviso.takeIf {
        estado != BlumonAttemptResolver.State.RECORDED && estado != BlumonAttemptResolver.State.RELEASED &&
            estado != BlumonAttemptResolver.State.MONEY_EVIDENCE
    }

/** D5 (533d77): ¿la duda es «el banco aprobó y la tarjeta rechazó el cierre»? */
internal fun chipRechazoTrasAprobacion(lastError: String?): Boolean =
    lastError == com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION

/**
 * The amount belongs to the saved attempt, which may predate the payment screen currently open.
 * 🔴 F1 (tarea 5): una sola acción, [Seguir], siempre activa (también durante una consulta lenta) y que sale UNA vez: deja atender la
 * siguiente venta; la duda sigue guardada y sólo espera su cuenta. La consulta sigue sola en el ViewModel mientras la pantalla vive.
 */
@Composable
internal fun BlumonRecoveryContent(
    result: BlumonAttemptResolver.Result,
    busy: Boolean,
    secondsRemaining: Int,
    /** F1-textos (fila 17): por qué la barrera no inició ESTE cobro; va arriba de la duda que estorba. null = la duda es del propio cobro. */
    avisoDeLaBarrera: String? = null,
    onSeguir: () -> Unit,
) {
    var salio by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        avisoDeLaBarreraVisible(avisoDeLaBarrera, result.state)?.let { Text(it) }
        Text(when (result.state) {
            BlumonAttemptResolver.State.RECORDED -> "Cobro registrado"
            BlumonAttemptResolver.State.RELEASED -> when (result.freno?.freno) {
                null -> "Intento cerrado"
                com.jaac.avoqado_tpv.features.payment.data.ledger.FrenoDelResuelto.DINERO -> "Hay evidencia de cobro"
                else -> "Tarjeta por confirmar"
            }
            BlumonAttemptResolver.State.MONEY_EVIDENCE -> "Hay evidencia de cobro"
            else -> "Tarjeta por confirmar"
        }, style = MaterialTheme.typography.headlineSmall)
        Text(when (result.state) {
            BlumonAttemptResolver.State.RECORDED -> "Avoqado confirmó este pago. No vuelvas a cobrar esta venta."
            BlumonAttemptResolver.State.RELEASED -> textoDelIntentoCerrado(result.row?.lastError, result.freno)
            BlumonAttemptResolver.State.MONEY_EVIDENCE -> "No vuelvas a pasar la tarjeta ni cobres esta venta en efectivo. Revisa el pago con el gerente; la evidencia sigue guardada."
            else -> tarjetaPorConfirmar(result.row?.let(::totalDelIntento))
        })
        if (busy || secondsRemaining > 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(CONFIRMANDO_CON_EL_BANCO)
        }
        Button(onClick = { if (!salio) { salio = true; onSeguir() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Seguir")
        }
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun BlumonRecoveryPreview() {
    MaterialTheme {
        Column {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text("Sin conexión", modifier = Modifier.padding(16.dp))
            }
            BlumonRecoveryContent(
                result = BlumonAttemptResolver.Result(BlumonAttemptResolver.State.PENDING),
                busy = true, secondsRemaining = 10, onSeguir = {},
            )
        }
    }
}
