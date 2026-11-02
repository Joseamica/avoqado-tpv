package com.jaac.avoqado_tpv.features.shift.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.shift.data.repository.LocalShiftPage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint @InstallIn(SingletonComponent::class)
interface CajaPrinterEntryPoint { fun printerManager(): PrinterManager }

/** La misma tarjeta, conteo y papel se usan en Caja y kiosco. No mantiene otra verdad financiera. */
@Composable
fun LocalCajaContent(
    caja: CajaLocalPresentation,
    offline: Boolean,
    canClose: Boolean,
    canOpen: Boolean,
    needsDone: Boolean,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onAdopt: () -> Unit,
    onReview: (String, Int) -> Unit,
    onHistory: (Int) -> Unit,
    history: LocalShiftPage?,
    modifier: Modifier = Modifier,
    financialShift: com.jaac.avoqado_tpv.features.shift.domain.Shift? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var printError by remember(caja.session.localSessionId) { mutableStateOf<String?>(null) }
    var printing by remember(caja.session.localSessionId) { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable(caja.session.localSessionId) { mutableStateOf(false) }
    val needsReview = caja.session.closeStatus == "REVIEW" || caja.session.openingStatus == "REVIEW"
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(16.dp)) {
        item {
            if (offline) Text("Sin conexión · guardado en este aparato", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(caja.title, style = MaterialTheme.typography.titleLarge)
            caja.notice?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            caja.knownLines.dropLast(1).forEach {
                Text(it, modifier = Modifier.padding(top = 8.dp))
            }
            if (caja.session.localStatus == "OPEN") {
                Button(onClick = onClose, enabled = canClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cerrar caja") }
            } else if (needsDone) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Listo") }
            } else {
                Button(onClick = onOpen, enabled = canOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Abrir nueva caja") }
            }
            if (caja.canAdopt) OutlinedButton(onClick = onAdopt, enabled = canOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Usar la caja vinculada para nuevas ventas")
            }
            if (needsReview) OutlinedButton(onClick = { onReview(caja.session.localSessionId, 0) }, modifier = Modifier.fillMaxWidth()) {
                Text("Revisar registros pendientes")
            }
            TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (detailsExpanded) "Expandido" else "Contraído" }) {
                Text(if (detailsExpanded) "Ocultar detalles" else "Ver detalles")
                Icon(if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (detailsExpanded) {
                Text(caja.description)
                val metrics = financialShift?.financialDisplayLines()
                if (metrics == null) Text(caja.knownLines.last()) else {
                    Text("Totales confirmados", style = MaterialTheme.typography.titleSmall)
                    metrics.forEach { (label, value) -> Text("$label: $value") }
                    Text("Efectivo esperado y diferencia: pendientes del corte")
                }
                if (!needsReview) OutlinedButton(onClick = { onReview(caja.session.localSessionId, 0) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Revisar registros pendientes")
                }
                OutlinedButton(enabled = !printing, onClick = {
                    val captured = caja
                    printing = true; printError = null
                    scope.launch {
                        try {
                            val printer = EntryPointAccessors.fromApplication(context.applicationContext, CajaPrinterEntryPoint::class.java).printerManager()
                            val result = withContext(Dispatchers.IO) { printer.printLocalShiftSummary(captured) }
                            printError = result.exceptionOrNull()?.message
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                          catch (error: Exception) { printError = "No se pudo imprimir. Vuelve a intentar." }
                        finally { printing = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (printing) "Imprimiendo…" else "Imprimir resumen provisional") }
                TextButton(onClick = { onHistory(0) }) { Text("Historial de este aparato") }
            }
            printError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (printing && !detailsExpanded) Text("Imprimiendo resumen provisional…")
        }
        if (detailsExpanded) history?.let { page ->
            item { Text("${page.total} cajas guardadas" + if (page.sessions.isEmpty()) "" else " · ${page.offset + 1}-${page.offset + page.sessions.size}") }
            page.sessions.forEach { row -> item(key = row.localSessionId) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.originalOpenedAt} · ${row.localStatus} · envío ${row.closeStatus}")
                        Text(row.startingCash?.let { "${if (row.openingKind == "REMOTE") "Fondo de la caja" else "Fondo capturado"}: $$it" } ?: "Fondo no disponible")
                        if (row.openingDisposition == "ADOPTED") Text(row.remoteStartingCash?.let {
                            "Fondo de la caja vinculada: $$it"
                        } ?: "Fondo de la caja vinculada no disponible")
                        when (row.cashReconciliationAction) {
                            "COUNTED" -> Text(row.countedCash?.let { "Conteo capturado: $$it" } ?: "Conteo no disponible")
                            "SKIPPED" -> Text("Cierre sin conteo")
                        }
                        TextButton(onClick = { onReview(row.localSessionId, 0) }) { Text("Revisar esta caja") }
                    }
                }
            } }
            item { Row {
                TextButton(enabled = page.offset > 0, onClick = { onHistory(page.offset - 20) }) { Text("Anterior") }
                TextButton(enabled = page.offset + page.sessions.size < page.total, onClick = { onHistory(page.offset + 20) }) { Text("Siguiente") }
            } }
        }
    }
}

@Composable
fun LocalCajaReviewDialog(review: LocalCajaReview, onDismiss: () -> Unit, onPage: (Int) -> Unit,
    onCash: (Long) -> Unit, onCrypto: (String) -> Unit, onRetry: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Registros de esta caja") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(if (review.busy) "Consultando…" else review.page?.let { "${it.total} registros que el cierre espera" } ?: "Lista pendiente de confirmar") }
            review.error?.let { item { Text(it) } }
            review.page?.rows?.forEach { row -> item(key = "${row.owner}:${row.ownerId}") {
                Text(when (row.owner) {
                    "CRYPTO" -> "Pago cripto pendiente de confirmación"
                    "ATTEMPT", "REMOTE" -> "Operación pendiente de resolver; no vuelvas a cobrarla"
                    "REFUND" -> "Devolución pendiente de registro o revisión"
                    "TABLE_CASH" -> "Efectivo de Mesas pendiente de registro"
                    else -> "Pago pendiente de registro o revisión"
                })
                Text("Folio: ${row.ownerId}", style = MaterialTheme.typography.bodySmall)
                if (row.owner == "PAYMENT" && row.ownerId.toLongOrNull()?.let { it in review.cashEligible } == true)
                    TextButton(enabled = !review.busy, onClick = { row.ownerId.toLongOrNull()?.let(onCash) }) { Text("Efectivo: ya lo revisé") }
                if (row.owner == "CRYPTO") TextButton(enabled = !review.busy, onClick = { onCrypto(row.ownerId) }) { Text("Consultar esta operación") }
            } }
            review.page?.let { page -> item { Row {
                TextButton(enabled = !review.busy && page.offset > 0, onClick = { onPage(page.offset - 20) }) { Text("Anterior") }
                TextButton(enabled = !review.busy && page.offset + page.rows.size < page.total, onClick = { onPage(page.offset + 20) }) { Text("Siguiente") }
            } } }
        } },
        confirmButton = { TextButton(enabled = !review.busy, onClick = onRetry) { Text("Actualizar / enviar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Volver") } })
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun LocalCajaPendingWithBannerPreview() {
    val row = com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity(
        "c000000000000000000000001", "venue", "LOCAL", openingStatus = "PENDING",
        originalOpenedAt = "2026-10-28T10:00:00Z", startingCash = "50.00", openedByStaffId = "staff",
        localStatus = "CLOSED", closeOperationId = "c000000000000000000000002", closeStatus = "PENDING",
        countedCash = "72.00", cashReconciliationAction = "COUNTED", createdAt = 1, updatedAt = 1)
    val caja = CajaLocalPresentation(row, com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary(
        java.math.BigDecimal("50.00"), java.math.BigDecimal("72.00"), "CLOSED", "PENDING", "PENDING", null))
    AvoqadoTheme {
        Column {
            com.jaac.avoqado_tpv.core.presentation.components.ShiftStatusBanner(null, true, onClick = {}, localCaja = caja)
            LocalCajaContent(caja, true, true, true, true, {}, {}, {}, {}, { _, _ -> }, {}, null)
        }
    }
}
