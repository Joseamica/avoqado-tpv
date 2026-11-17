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
internal enum class SalidaDelCobro { REVISE_LA_TERMINAL, NO_PRESENTO_TARJETA, FALTA_PERMISO_CAJERO, FALTA_PERMISO_GERENCIA, FALTA_CONSULTAR, EN_EL_AVISO_O_EL_POS }

internal fun salidaDelCobro(
    result: BlumonAttemptResolver.Result,
    puedeRevisar: Boolean,
    puedeDeclararSinTarjeta: Boolean,
): SalidaDelCobro {
    val row = result.row
    // D5 (533d77, Codex r1 #4): si el banco aprobó y el chip rechazó el cierre, la tarjeta SÍ se presentó: «no presentó tarjeta»
    // sería falso. La declaración de un cobro del POS («revisé y no se cobró») vive en el aviso verde del Inicio y en el POS.
    if (row?.terminalPaymentRequestId != null && chipRechazoTrasAprobacion(row.lastError)) return SalidaDelCobro.EN_EL_AVISO_O_EL_POS
    // El cobro que mandó el POS tiene su propio camino, y su propia declaración de gerencia.
    if (row?.terminalPaymentRequestId != null)
        return if (puedeDeclararSinTarjeta) SalidaDelCobro.NO_PRESENTO_TARJETA else SalidaDelCobro.FALTA_PERMISO_GERENCIA
    // Cobro local (founder, 25-sep): el cajero dice lo que sí pasó — «revisé y no se cobró» —, con o sin red.
    if (!puedeRevisar) return SalidaDelCobro.FALTA_PERMISO_CAJERO
    // La libreta exige que Avoqado haya contestado alguna vez por este cobro (server_answered_at).
    return if (result.serverAnswered || row?.serverAnsweredAt != null) SalidaDelCobro.REVISE_LA_TERMINAL
        else SalidaDelCobro.FALTA_CONSULTAR
}

/** D5 (533d77): ¿la duda es «el banco aprobó y la tarjeta rechazó el cierre»? */
internal fun chipRechazoTrasAprobacion(lastError: String?): Boolean =
    lastError == com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION

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
        // D5 (533d77): el banco aprobó y la tarjeta rechazó el cierre. Es un dato, no un veredicto: no se sabe si el cargo quedó.
        if (pending && chipRechazoTrasAprobacion(result.row?.lastError)) {
            Text("El banco aprobó, pero la tarjeta rechazó el cierre de la operación: no se sabe si el cargo quedó. Antes de declarar, revisa con el cliente (por ejemplo, en la app de su banco).")
        }
        Text(when (result.state) {
            BlumonAttemptResolver.State.RECORDED -> "Avoqado confirmó este pago. No vuelvas a cobrar esta venta."
            BlumonAttemptResolver.State.RELEASED -> if (result.row?.lastError?.startsWith("declarado_sin_cobro:") == true)
                "Quedó registrado que revisaste la terminal y no se cobró. Puedes volver a cobrar esta venta; si Avoqado encuentra el cobro después, te avisará."
                // D5 (533d77, Codex r4): neutral. La liberación del servidor deja el mismo `last_error` para «no presentó tarjeta» y para
                // «revisé y no se cobró» declarado en el POS; en un chip que rechazó tras aprobar, la tarjeta SÍ se presentó.
                else "Quedó guardada la declaración de que no se cobró. Puedes volver a cobrar."
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
            SalidaDelCobro.EN_EL_AVISO_O_EL_POS ->
                Text("Este cobro lo mandó el POS. Si ya revisaste que no se cobró, decláralo con «No se cobró» en el aviso verde del Inicio o desde el POS.")
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
