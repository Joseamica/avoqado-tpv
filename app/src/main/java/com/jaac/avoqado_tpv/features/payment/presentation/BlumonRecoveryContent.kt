package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.payment.data.ledger.BlumonAttemptResolver
import java.math.BigDecimal

/** Qué salida ofrece la pantalla para un intento pendiente, ya pasada la ventana de confirmación. */
internal enum class SalidaDelCobro { REVISE_LA_TERMINAL, NO_PRESENTO_TARJETA, FALTA_PERMISO_CAJERO, FALTA_PERMISO_GERENCIA, FALTA_CONSULTAR }

internal fun salidaDelCobro(
    result: BlumonAttemptResolver.Result,
    puedeRevisar: Boolean,
    puedeDeclararSinTarjeta: Boolean,
): SalidaDelCobro {
    val row = result.row
    // El cobro que mandó el POS tiene su propio camino, y su propia declaración de gerencia.
    if (row?.terminalPaymentRequestId != null)
        return if (puedeDeclararSinTarjeta) SalidaDelCobro.NO_PRESENTO_TARJETA else SalidaDelCobro.FALTA_PERMISO_GERENCIA
    // Cobro local (founder, 25-sep): el cajero dice lo que sí pasó — «revisé y no se cobró» —, con o sin red.
    if (!puedeRevisar) return SalidaDelCobro.FALTA_PERMISO_CAJERO
    // La libreta exige que Avoqado haya contestado alguna vez por este cobro (server_answered_at).
    return if (result.serverAnswered || row?.serverAnsweredAt != null) SalidaDelCobro.REVISE_LA_TERMINAL
        else SalidaDelCobro.FALTA_CONSULTAR
}

/** The amount belongs to the saved attempt, which may predate the payment screen currently open. */
@Composable
internal fun BlumonRecoveryContent(
    result: BlumonAttemptResolver.Result,
    busy: Boolean,
    secondsRemaining: Int,
    hasPermission: Boolean,
    onConsult: () -> Unit,
    onDeclare: () -> Unit,
    onDeclareChecked: () -> Unit,
    onBack: () -> Unit,
    hasCheckPermission: Boolean = false,
) {
    val pending = result.state == BlumonAttemptResolver.State.PENDING ||
        result.state == BlumonAttemptResolver.State.PERMISSION_REQUIRED
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(when (result.state) {
            BlumonAttemptResolver.State.RECORDED -> "Cobro registrado"
            BlumonAttemptResolver.State.RELEASED -> "Intento cerrado"
            BlumonAttemptResolver.State.MONEY_EVIDENCE -> "Hay evidencia de cobro"
            else -> "Estamos confirmando el cobro"
        }, style = MaterialTheme.typography.headlineSmall)
        result.row?.let { row ->
            Text("Importe: $${BigDecimal.valueOf(row.amountCents, 2).toPlainString()} · Propina: $${BigDecimal.valueOf(row.tipCents, 2).toPlainString()}")
        }
        // Lo que contestó Blumon es un dato, no un veredicto: sin código del banco no dice si hubo cargo.
        if (pending) blumonRespuestaGuardada(result.row?.lastError)?.let { respuesta ->
            Text("Blumon contestó «$respuesta», pero sin un código del banco no se puede confirmar si hubo cargo.")
        }
        Text(when (result.state) {
            BlumonAttemptResolver.State.RECORDED -> "Avoqado confirmó este pago. No vuelvas a cobrar esta venta."
            BlumonAttemptResolver.State.RELEASED -> if (result.row?.lastError?.startsWith("declarado_sin_cobro:") == true)
                "Quedó registrado que revisaste la terminal y no se cobró. Puedes volver a cobrar esta venta; si Avoqado encuentra el cobro después, te avisará."
                else "Se guardó la declaración de que no se presentó tarjeta. Puedes volver a cobrar."
            BlumonAttemptResolver.State.MONEY_EVIDENCE -> "No vuelvas a pasar la tarjeta ni cobres esta venta en efectivo. Revisa el pago con el gerente; la evidencia sigue guardada."
            BlumonAttemptResolver.State.UNAVAILABLE -> "No se pudo leer este intento o todavía está en curso. No vuelvas a pasar la tarjeta."
            else -> "No vuelvas a pasar la tarjeta. Estamos consultando el intento guardado; que aún no aparezca no significa que el banco lo rechazó."
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (pending && secondsRemaining > 0) Text("Esperando confirmación: $secondsRemaining s")
        if (pending && secondsRemaining == 0) when (salidaDelCobro(result, puedeRevisar = hasCheckPermission, puedeDeclararSinTarjeta = hasPermission)) {
            SalidaDelCobro.REVISE_LA_TERMINAL -> {
                Text("Si ya revisaste que no se cobró (por ejemplo, en la app del banco del cliente), déjalo registrado: queda tu nombre y la hora.")
                Button(onClick = onDeclareChecked, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Ya revisé la terminal: no se cobró")
                }
            }
            SalidaDelCobro.NO_PRESENTO_TARJETA -> {
                Text("Declara sólo si el cliente no presentó tarjeta, celular ni reloj para este intento.")
                Button(onClick = onDeclare, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("El cliente no presentó tarjeta")
                }
            }
            SalidaDelCobro.FALTA_PERMISO_CAJERO ->
                Text("Para dejar constancia de que no se cobró se necesita el permiso de conciliar cobros; pídeselo a tu gerente.")
            SalidaDelCobro.FALTA_PERMISO_GERENCIA ->
                Text("Para declarar que no se cobró, debe entrar un gerente con permiso para resolver cobros pendientes.")
            SalidaDelCobro.FALTA_CONSULTAR ->
                Text("Primero hay que consultar a Avoqado: conéctate a internet y toca «Consultar de nuevo».")
        }
        if (result.state != BlumonAttemptResolver.State.RECORDED && result.state != BlumonAttemptResolver.State.RELEASED) {
            OutlinedButton(onClick = onConsult, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Consultar de nuevo") }
        }
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Regresar") }
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
                busy = false, secondsRemaining = 10, hasPermission = false,
                onConsult = {}, onDeclare = {}, onDeclareChecked = {}, onBack = {},
            )
        }
    }
}
