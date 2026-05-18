package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.angelpay.angelpaysdk.models.MerchantSummary
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import kotlinx.coroutines.launch

/**
 * Bottom sheet that lists the cashier's accessible AngelPay merchants and lets
 * them switch the active one (spec §6.8, §18.1).
 *
 * Behavior:
 * - On open, fires a D6 refresh (`onRefresh()`) so the operator never picks from
 *   a stale cache.
 * - Each row shows merchant name + `affiliationNumber` subtitle.
 * - Trailing icon per row:
 *   - Green checkmark when `merchant.id == activeMerchantId` and no switch in flight.
 *   - Small spinner when `merchant.id == inFlightSwitchTargetId` (D2 sequencing).
 *   - Nothing otherwise.
 * - While a switch is in flight, all rows are disabled so the cashier can't
 *   stack switches.
 * - Empty list surfaces a retry button that re-runs the D6 refresh.
 *
 * Mounted by [AngelPayPaymentScreen] (Task 34) — opened from the
 * [AngelPayAuthBanner] tap action or a dedicated overflow item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AngelPayMerchantSwitcherSheet(
    merchants: List<MerchantSummary>,
    activeMerchantId: Int?,
    inFlightSwitchTargetId: Int?,
    onRefresh: suspend () -> Unit,
    onPick: (merchantId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // D6 refresh on open — spec §18.1
    LaunchedEffect(Unit) { onRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            // Title
            Text(
                text = "Cambiar merchant",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (merchants.isEmpty()) {
                EmptyState(
                    onRefresh = {
                        scope.launch { onRefresh() }
                    },
                )
            } else {
                merchants.forEach { merchant ->
                    MerchantRow(
                        merchant = merchant,
                        isActive = merchant.id == activeMerchantId && inFlightSwitchTargetId == null,
                        isInFlight = merchant.id == inFlightSwitchTargetId,
                        enabled = inFlightSwitchTargetId == null,
                        onClick = { onPick(merchant.id) },
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// SUB-COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MerchantRow(
    merchant: MerchantSummary,
    isActive: Boolean,
    isInFlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = merchant.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = merchant.affiliationNumber,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isInFlight -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                isActive -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Activo",
                    tint = MaterialTheme.avoqadoColors.statusSuccess,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No hay merchants disponibles",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) {
            Text("Reintentar")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PREVIEWS (PAX A910S — 360x640dp)
// ═══════════════════════════════════════════════════════════════════════════════

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayMerchantSwitcherSheetPopulatedPreview() {
    AvoqadoTheme {
        // Preview the inner content directly — ModalBottomSheet doesn't render in preview.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(
                text = "Cambiar merchant",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val merchants = listOf(
                MerchantSummary(id = 101, name = "Doña Simona Polanco", affiliationNumber = "0001234567", isActive = true),
                MerchantSummary(id = 102, name = "Doña Simona Roma", affiliationNumber = "0001234568", isActive = true),
                MerchantSummary(id = 103, name = "Doña Simona Condesa", affiliationNumber = "0001234569", isActive = true),
            )
            merchants.forEachIndexed { idx, m ->
                MerchantRow(
                    merchant = m,
                    isActive = m.id == 101 && idx != 1,
                    isInFlight = m.id == 102,
                    enabled = false,
                    onClick = {},
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun AngelPayMerchantSwitcherSheetEmptyPreview() {
    AvoqadoTheme {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(
                text = "Cambiar merchant",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            EmptyState(onRefresh = {})
        }
    }
}
