package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember

/**
 * "Reasignar mesero" (Fase 2, `ASSIGN_ORDER`) — la propina y la comisión de
 * la plataforma cuelgan de `servedById` (ver KDoc de
 * [TablesRepository.assignOrder][com.jaac.avoqado_tpv.features.tables.data.TablesRepository.assignOrder]),
 * así que esta lista SOLO ofrece personal EN TURNO ahora mismo — nunca un
 * campo de texto libre donde alguien pueda teclear el nombre equivocado. Un
 * solo tap confirma, mismo lenguaje que [MoveTableSheet]/[MergeOrdersSheet].
 *
 * Vacío ≠ error: si la lista viene vacía (nadie ha marcado entrada, o quien
 * está usando la terminal no tiene el permiso para verla — ver concern en
 * [com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffResponse]),
 * se muestra un mensaje claro en vez de una lista en blanco sin explicación.
 *
 * 🔴 [unavailable]/[stale] — a diferencia de [MoveTableSheet]/[MergeOrdersSheet]
 * (que NUNCA sirven caché), este picker SÍ puede seguir mostrando la última
 * lista de personal conocida cuando un refresh falla ([stale] = `true`): el
 * riesgo es bajo (reasignar a alguien que ya salió de turno es corregible,
 * `ASSIGN_ORDER` ya es offline-capable en el server). [unavailable] = `true`
 * solo cuando NUNCA hubo una carga exitosa — ahí no hay nada que servir y el
 * mensaje tiene que decir "no se pudo cargar", nunca "no hay personal".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignWaiterSheet(
    staff: List<ActiveStaffMember>,
    isLoading: Boolean,
    isAssigning: Boolean,
    unavailable: Boolean,
    stale: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ActiveStaffMember) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Reasignar mesero", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space1))
            Text(
                text = "La propina y la comisión de esta cuenta se atribuyen a quien elijas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.Space4))

            when {
                isLoading -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(32.dp))
                    }
                }

                unavailable -> {
                    PickerUnavailableState(
                        message = "No se pudo cargar el personal en turno — revisa tu conexión.",
                        onRetry = onRetry,
                    )
                }

                staff.isEmpty() -> {
                    Text(
                        text = "No hay personal marcado como en turno ahora mismo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    if (stale) {
                        StaleStaffBanner(onRetry = onRetry)
                        Spacer(Modifier.height(Spacing.Space2))
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(vertical = Spacing.Space2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                    ) {
                        items(staff, key = { it.staffId }) { member ->
                            StaffRow(member = member, enabled = !isAssigning, onClick = { onSelect(member) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Banner de staleness — NUNCA oculta las filas debajo (a diferencia de
 * [PickerUnavailableState], que reemplaza toda la lista). Ver KDoc de
 * [AssignWaiterSheet]: mostrar personal "puede no estar actualizado" es
 * preferible a bloquear la reasignación por un blip de red.
 */
@Composable
private fun StaleStaffBanner(onRetry: () -> Unit) {
    Card(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space3, vertical = Spacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.padding(start = Spacing.Space2))
            Text(
                text = "Último personal visto — puede no estar actualizado. Toca para reintentar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StaffRow(member: ActiveStaffMember, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.padding(start = Spacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name.ifBlank { "Sin nombre" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                member.employeeCode?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (member.onBreak) {
                Text(
                    text = "En descanso",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.avoqadoColors.statusWarning,
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "NEXGO 480×480")
@Composable
private fun AssignWaiterSheetPreview() {
    AvoqadoTheme {
        AssignWaiterSheet(
            staff = listOf(
                ActiveStaffMember(staffId = "s1", name = "Fátima Flores", employeeCode = "EMP-001"),
                ActiveStaffMember(staffId = "s2", name = "Diego Ruiz", onBreak = true, employeeCode = "EMP-014"),
            ),
            isLoading = false,
            isAssigning = false,
            unavailable = false,
            stale = false,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — vacío")
@Composable
private fun AssignWaiterSheetEmptyPreview() {
    AvoqadoTheme {
        AssignWaiterSheet(
            staff = emptyList(),
            isLoading = false,
            isAssigning = false,
            unavailable = false,
            stale = false,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin conexión, sin caché")
@Composable
private fun AssignWaiterSheetUnavailablePreview() {
    AvoqadoTheme {
        AssignWaiterSheet(
            staff = emptyList(),
            isLoading = false,
            isAssigning = false,
            unavailable = true,
            stale = false,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — lista vieja")
@Composable
private fun AssignWaiterSheetStalePreview() {
    AvoqadoTheme {
        AssignWaiterSheet(
            staff = listOf(
                ActiveStaffMember(staffId = "s1", name = "Fátima Flores", employeeCode = "EMP-001"),
            ),
            isLoading = false,
            isAssigning = false,
            unavailable = false,
            stale = true,
            onDismiss = {},
            onSelect = {},
            onRetry = {},
        )
    }
}
