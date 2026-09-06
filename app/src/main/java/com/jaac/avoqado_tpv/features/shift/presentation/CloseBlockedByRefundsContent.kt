package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import java.math.BigDecimal

/**
 * 💸 Fase 2, Task 10: lo que ve el cajero cuando la caja NO se puede cerrar porque hay
 * devoluciones que el SDK ya aprobó y que todavía no están registradas en Avoqado.
 *
 * Tres reglas del texto, todas de dinero:
 * - Dice con todas sus letras que la devolución SÍ se hizo y que NO se repita — el defecto que
 *   esta cola arregla era justamente que el cajero, sin aviso, volviera a reembolsar.
 * - Una PENDIENTE no tiene botón: se registra sola al volver la red. Dejar que alguien la quite
 *   de la barrera cerraría el corte con dinero sin anotar.
 * - Una RECHAZADA muestra el motivo del servidor y «Ya lo vi»: libera el cierre y deja la
 *   devolución para conciliarse a mano.
 */
@Composable
fun CloseBlockedByRefundsContent(
    refunds: List<QueuedRefund>,
    onAcknowledge: (idempotencyKey: String) -> Unit,
    onBack: () -> Unit,
) {
    val rechazadas = refunds.count { it.permanent }
    val pendientes = refunds.size - rechazadas

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "No se puede cerrar la caja todavía",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = buildString {
                append("Hay ${refunds.size} ")
                append(if (refunds.size == 1) "devolución que SÍ se hizo" else "devoluciones que SÍ se hicieron")
                append(" en la terminal pero que Avoqado todavía no tiene registrada")
                if (refunds.size != 1) append("s")
                append(". Si la caja se cerrara ahora, el corte cuadraría de más.")
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pendientes > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Con conexión se registran solas en unos minutos. NO vuelvas a hacer la devolución.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(refunds, key = { it.idempotencyKey }) { refund ->
                RefundBlockingCard(refund = refund, onAcknowledge = onAcknowledge)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Volver a la caja")
        }
    }
}

@Composable
private fun RefundBlockingCard(
    refund: QueuedRefund,
    onAcknowledge: (idempotencyKey: String) -> Unit,
) {
    val fondo = if (refund.permanent) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = fondo),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Devolución de $${refund.amount.setScale(2).toPlainString()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Referencia ${refund.referenceNumber}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (refund.permanent) {
                Text(
                    text = "El servidor rechazó el registro: ${refund.lastError ?: "sin motivo"}. " +
                        "La devolución SÍ se hizo — NO la repitas. Un gerente debe conciliarla a mano " +
                        "con esta referencia.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onAcknowledge(refund.idempotencyKey) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Ya lo vi — liberar el cierre")
                }
            } else {
                Text(
                    text = if (refund.retryCount >= 3) {
                        "Sin registrar todavía tras ${refund.retryCount} intentos. Se seguirá intentando sola: revisa la conexión de la terminal."
                    } else {
                        "Sin registrar todavía. Se registrará sola al recuperar la conexión."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun CloseBlockedByRefundsContentPreview() {
    MaterialTheme {
        CloseBlockedByRefundsContent(
            refunds = listOf(
                previewRefund("k-1", permanent = false),
                previewRefund("k-2", permanent = true),
            ),
            onAcknowledge = {},
            onBack = {},
        )
    }
}

private fun previewRefund(key: String, permanent: Boolean) = QueuedRefund(
    idempotencyKey = key,
    venueId = "v1",
    staffId = "s1",
    processor = ProcessorType.BLUMON,
    originalPaymentId = "pay-orig",
    originalOrderId = null,
    amount = BigDecimal("150.00"),
    originalTotalAmount = BigDecimal("300.00"),
    tipRefundCents = null,
    isPartialRefund = true,
    refundReason = RefundReason.CUSTOMER_REQUEST,
    merchantAccountId = "m1",
    blumonSerialNumber = "SER1",
    originalOperationNumber = 1,
    authorizationNumber = "502511",
    referenceNumber = "000000188231",
    maskedPan = null,
    cardBrand = null,
    entryMode = "CHIP",
    createdAt = 0L,
    permanent = permanent,
    lastError = if (permanent) "El monto excede lo reembolsable" else null,
)
