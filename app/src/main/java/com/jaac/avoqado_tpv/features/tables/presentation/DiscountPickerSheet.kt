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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import java.math.BigDecimal

/**
 * "Aplicar descuento" (Fase 1, `APPLY_DISCOUNT`) — todo negocio corre promos y
 * descuentos de personal. Cada fila ya muestra el monto exacto que se ahorra
 * (`estimatedSavings`) ANTES de tocarla — es el "preview visible" que
 * sustituye a un paso de confirmación aparte (mismo espíritu que el MCP:
 * nunca aplicar dinero a ciegas). Descuentos que `requiresApproval` se ven
 * PERO deshabilitados con candado — sin flujo de autorización de gerente en
 * esta versión (gap documentado, no un olvido).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscountPickerSheet(
    discounts: List<AvailableDiscount>,
    isLoading: Boolean,
    isApplying: Boolean,
    onDismiss: () -> Unit,
    onSelect: (discountId: String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4).padding(bottom = Spacing.Space6)) {
            Text(text = "Descuentos disponibles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(Spacing.Space4))

            when {
                isLoading -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(32.dp))
                    }
                }

                discounts.isEmpty() -> {
                    Text(
                        text = "No hay descuentos disponibles para esta cuenta.",
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
                        items(discounts, key = { it.id }) { discount ->
                            DiscountRow(
                                discount = discount,
                                enabled = !discount.requiresApproval && !isApplying,
                                onClick = { onSelect(discount.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscountRow(discount: AvailableDiscount, enabled: Boolean, onClick: () -> Unit) {
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
                    text = discount.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (discount.requiresApproval) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.avoqadoColors.statusWarning,
                            modifier = Modifier.height(14.dp),
                        )
                        Spacer(Modifier.padding(start = 4.dp))
                        Text(
                            text = "Requiere autorización de gerente",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.avoqadoColors.statusWarning,
                        )
                    }
                } else {
                    Text(
                        text = discount.savingsDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.avoqadoColors.statusSuccess,
                    )
                }
            }
            DiscountValueBadge(text = discount.valueDisplay)
        }
    }
}

@Composable
private fun DiscountValueBadge(text: String) {
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
private fun DiscountPickerSheetPreview() {
    AvoqadoTheme {
        DiscountPickerSheet(
            discounts = listOf(
                AvailableDiscount(id = "d1", name = "Cliente frecuente", type = "PERCENTAGE", value = BigDecimal("10"), requiresApproval = false, estimatedSavings = BigDecimal("45.00")),
                AvailableDiscount(id = "d2", name = "Descuento gerencial", type = "FIXED_AMOUNT", value = BigDecimal("100"), requiresApproval = true, estimatedSavings = BigDecimal("100.00")),
            ),
            isLoading = false,
            isApplying = false,
            onDismiss = {},
            onSelect = {},
        )
    }
}
