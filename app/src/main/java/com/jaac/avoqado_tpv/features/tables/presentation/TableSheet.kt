package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable

/**
 * Mesa sheet (Plan C, Task 6, design spec §6.2 step 4) — comensales → "Abrir
 * mesa y ordenar". Muestra info EN VIVO de la mesa; el flujo de abrir/ordenar
 * en sí (`TableOrderScreen`) es Task 7, todavía no construido — el CTA queda
 * visible pero deshabilitado con la razón ("Próxima actualización"), el
 * mismo patrón "disabled-with-explanation" que ya usa el tile "Mesas" en
 * `WelcomeScreen.kt` (Task 1) en vez de un placeholder o un botón mudo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableSheet(
    table: DiningTable,
    isMySession: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val status = table.displayStatus()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space4)
                .padding(bottom = Spacing.Space6),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Mesa ${table.number}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(start = Spacing.Space2))
                StatusDot(status = status)
                Spacer(Modifier.padding(start = Spacing.Space1))
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isMySession) {
                    Spacer(Modifier.padding(start = Spacing.Space2))
                    MyTableBadge()
                }
            }

            table.areaName?.takeIf { it.isNotBlank() }?.let { area ->
                Text(
                    text = area,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.Space4))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.Space4))

            if (table.isAvailable) {
                AvailableTableContent(table = table)
            } else {
                OccupiedTableContent(table = table)
            }

            Spacer(Modifier.height(Spacing.Space4))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar")
            }
        }
    }
}

@Composable
private fun MyTableBadge() {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
    ) {
        Text(
            text = "Tu mesa",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.Space2, vertical = 2.dp),
        )
    }
}

@Composable
private fun AvailableTableContent(table: DiningTable) {
    var covers by rememberSaveable(table.id) {
        mutableIntStateOf(table.capacity.coerceIn(MIN_COVERS, MAX_COVERS))
    }

    Text(
        text = "Comensales",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.Space2))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = { covers = (covers - 1).coerceAtLeast(MIN_COVERS) },
            enabled = covers > MIN_COVERS,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Menos comensales")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.padding(start = Spacing.Space2))
            Text(
                text = "$covers",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        FilledIconButton(
            onClick = { covers = (covers + 1).coerceAtMost(MAX_COVERS) },
            enabled = covers < MAX_COVERS,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Más comensales")
        }
    }

    Spacer(Modifier.height(Spacing.Space4))
    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
        Text("Abrir mesa y ordenar")
    }
    Spacer(Modifier.height(Spacing.Space1))
    Text(
        text = "Disponible en la próxima actualización",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OccupiedTableContent(table: DiningTable) {
    val order = table.currentOrder
    if (order != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Cuenta ${order.orderNumber}".trim(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${order.itemCount} producto${if (order.itemCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                order.waiter?.let {
                    Text(
                        text = "Atiende: ${it.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = order.totalDisplay,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Text(
            text = "Sin información de cuenta todavía.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(Spacing.Space4))
    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
        Text("Ver cuenta")
    }
    Spacer(Modifier.height(Spacing.Space1))
    Text(
        text = "Disponible en la próxima actualización",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Indicador de color — SIEMPRE redundante a un texto (nunca la única forma de
 * comunicar el estado, WCAG 1.4.1). Trae un borde sutil para que se distinga
 * incluso en tonos claros (p. ej. RESERVED en tema claro contra un fondo
 * también claro).
 */
@Composable
fun StatusDot(status: TableDisplayStatus, size: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(status.color(), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
    )
}

@Composable
fun TableDisplayStatus.color(): Color = when (this) {
    TableDisplayStatus.FREE -> MaterialTheme.avoqadoColors.tableAvailable
    TableDisplayStatus.OCCUPIED -> MaterialTheme.avoqadoColors.tableOccupied
    TableDisplayStatus.RESERVED -> MaterialTheme.avoqadoColors.tableReserved
    TableDisplayStatus.NEEDS_ATTENTION -> MaterialTheme.avoqadoColors.statusWarning
}

fun TableDisplayStatus.label(): String = when (this) {
    TableDisplayStatus.FREE -> "Libre"
    TableDisplayStatus.OCCUPIED -> "Ocupada"
    TableDisplayStatus.RESERVED -> "Reservada"
    TableDisplayStatus.NEEDS_ATTENTION -> "Atención"
}

private const val MIN_COVERS = 1
private const val MAX_COVERS = 20

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "Mesa libre — NEXGO")
@Composable
private fun TableSheetAvailablePreview() {
    AvoqadoTheme {
        TableSheet(
            table = DiningTable(id = "1", number = "5", capacity = 4, areaName = "Terraza", status = "AVAILABLE"),
            isMySession = false,
            onDismiss = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "Mesa ocupada — PAX A920")
@Composable
private fun TableSheetOccupiedPreview() {
    AvoqadoTheme {
        TableSheet(
            table = DiningTable(
                id = "2",
                number = "12",
                capacity = 6,
                areaName = "Salón principal",
                status = "OCCUPIED",
                currentOrder = com.jaac.avoqado_tpv.features.tables.domain.model.TableOrder(
                    id = "order-1",
                    orderNumber = "A-104",
                    total = java.math.BigDecimal("845.50"),
                    itemCount = 7,
                    waiter = com.jaac.avoqado_tpv.features.tables.domain.model.TableWaiter(id = "w1", name = "Fátima Flores"),
                ),
            ),
            isMySession = true,
            onDismiss = {},
        )
    }
}
