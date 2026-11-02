package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.presentation.components.ConnectionBanner
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionState
import com.jaac.avoqado_tpv.features.shift.data.repository.PaginaPagosDelCierre

/** La lista es una página; el total viene del COUNT de Room, nunca de su tamaño. */
@Composable
fun CloseBlockedByPaymentsContent(
    page: PaginaPagosDelCierre?,
    busy: Boolean,
    error: String?,
    onAcknowledge: (Long) -> Unit,
    onPage: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("La caja espera a las ventas guardadas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            page?.let { "${it.total} venta(s) sin registrar o sin revisar en esta terminal." }
                ?: "Hay que verificar las ventas antes de cerrar la caja.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("No vuelvas a cobrarlas. Revisado conserva la venta y deja constancia de quién la revisó; no la registra ni la reintenta.",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Reintentar")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(page?.pagos.orEmpty(), key = { it.id }) { pago ->
                val subiendo = pago.syncStatus in listOf("PENDING", "SYNCING")
                Card(colors = CardDefaults.cardColors(containerColor = if (subiendo) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Importe $${pago.amount} · Propina $${pago.tip}", style = MaterialTheme.typography.titleMedium)
                        Text("Referencia ${pago.referenceNumber}", style = MaterialTheme.typography.bodySmall)
                        if (subiendo) {
                            Text("Subiendo… Sin conexión se conserva en la terminal.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(when (pago.syncStatus) {
                                "FAILED" -> "Sin registrar: ${pago.lastError ?: "requiere revisión"}"
                                "SUCCESS" -> "Registrada con una diferencia: ${pago.lastError ?: "revisa el importe cobrado"}"
                                else -> "Requiere revisión: ${pago.lastError ?: "revisa el importe cobrado"}"
                            },
                                style = MaterialTheme.typography.bodyMedium)
                            if (pago.syncStatus == "FAILED" || pago.requiereRevision) {
                                Button(onClick = { onAcknowledge(pago.id) }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Revisado") }
                            }
                        }
                    }
                }
            }
        }
        if (page != null && page.total > 0) {
            Text("${page.offset + 1}–${page.offset + page.pagos.size} de ${page.total}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onPage(page.offset - 20) }, enabled = !busy && page.offset > 0,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Anterior") }
                TextButton(onClick = { onPage(page.offset + page.pagos.size) },
                    enabled = !busy && page.offset + page.pagos.size < page.total,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Siguiente") }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Volver a la caja") }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun CloseBlockedByPaymentsPreview() {
    AvoqadoTheme {
        Column {
            ConnectionBanner(ConnectionState.DisconnectedNoInternet)
            CloseBlockedByPaymentsContent(
                page = PaginaPagosDelCierre(23, List(20) { indice ->
                    PendingPaymentEntity(id = indice.toLong() + 1, referenceNumber = "CASH-venta-${indice + 1}", venueId = "negocio", staffId = "vendedora",
                        amount = "150.25", tip = "0.00", rating = null, merchantAccountId = "", blumonSerialNumber = "",
                        maskedPan = null, cardBrand = null, entryMode = "CASH", isInternational = false,
                        authorizationNumber = "EFECTIVO", createdAt = indice.toLong(),
                        syncStatus = if (indice == 0) "PENDING" else "FAILED")
                }, 0),
                busy = false, error = null, onAcknowledge = {}, onPage = {}, onRetry = {}, onBack = {},
            )
        }
    }
}
