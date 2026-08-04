package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableServiceCharge
import java.math.BigDecimal

/**
 * "Aplicar cargo por servicio" (Fase 3, `APPLY_SERVICE_CHARGE`) — la mayoría de
 * los cargos ya se auto-aplican por comensales (`ServiceCharge.autoApplyMinCovers`,
 * ver [AvailableServiceCharge.autoApplyDisplay]); esto es el override manual
 * (descorche, un grupo justo bajo el umbral que igual acepta el cargo…). A
 * diferencia de [DiscountPickerSheet] no hay `requiresApproval` que deshabilite
 * filas — el server rechaza duplicados con un mensaje claro (propagado por el
 * snackbar compartido), así que toda fila queda tocable mientras no haya una
 * aplicación en vuelo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceChargePickerSheet(
    charges: List<AvailableServiceCharge>,
    isLoading: Boolean,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onSelect: (serviceChargeId: String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Cargos por servicio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space4))

            when {
                isLoading -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(32.dp))
                    }
                }

                charges.isEmpty() -> {
                    Text(
                        text = "Este venue no tiene cargos por servicio configurados.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(vertical = Spacing.Space2),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
                    ) {
                        items(charges, key = { it.id }) { charge ->
                            ServiceChargeRow(charge = charge, enabled = !isApplying, onClick = { onSelect(charge.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceChargeRow(charge: AvailableServiceCharge, enabled: Boolean, onClick: () -> Unit) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = charge.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                charge.autoApplyDisplay?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ServiceChargeValueBadge(text = charge.valueDisplay)
        }
    }
}

@Composable
private fun ServiceChargeValueBadge(text: String) {
    Box(
        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.Space2, vertical = 2.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 480, heightDp = 640, name = "NEXGO 480×480")
@Composable
private fun ServiceChargePickerSheetPreview() {
    AvoqadoTheme {
        ServiceChargePickerSheet(
            charges = listOf(
                AvailableServiceCharge(id = "sc1", name = "Propina automática", type = "PERCENTAGE", value = BigDecimal("15"), taxable = true, autoApplyMinCovers = 6),
                AvailableServiceCharge(id = "sc2", name = "Descorche", type = "FIXED_AMOUNT", value = BigDecimal("150.00"), taxable = true, autoApplyMinCovers = null),
            ),
            isLoading = false,
            isApplying = false,
            onDismiss = {},
            onSelect = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin catálogo")
@Composable
private fun ServiceChargePickerSheetEmptyPreview() {
    AvoqadoTheme {
        ServiceChargePickerSheet(
            charges = emptyList(),
            isLoading = false,
            isApplying = false,
            onDismiss = {},
            onSelect = {},
        )
    }
}
