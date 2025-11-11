package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * TipInputBottomSheet - ModalBottomSheet nativo para ingresar propina personalizada
 *
 * Permite al usuario ingresar:
 * - Porcentaje (%) - Calcula automáticamente basado en subtotal
 * - Monto fijo ($) - Usuario ingresa cantidad exacta
 *
 * Toggle $/% para cambiar entre modos.
 *
 * **Implementación**: Usa ModalBottomSheet nativo de Material3 (como todas las apps)
 * - skipPartiallyExpanded = true → Abre directo a altura completa (sin estado parcial)
 * - Reduce tiempo de animación perceptible
 * - API estándar de Android/Material3
 *
 * @param subtotal Monto base para cálculo de porcentaje
 * @param onDismiss Callback cuando se cierra el modal
 * @param onConfirm Callback cuando se confirma (recibe monto final en string)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipInputBottomSheet(
    subtotal: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var isPercentageMode by remember { mutableStateOf(false) }

    // Calcular monto a mostrar
    val displayAmount = remember(input, isPercentageMode, subtotal) {
        if (input.isEmpty()) {
            "0.00"
        } else if (isPercentageMode) {
            // Modo %: calcular monto basado en porcentaje
            calculateTipFromPercentage(subtotal, input)
        } else {
            // Modo $: mostrar input directamente
            input.ifEmpty { "0.00" }
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true  // Abre directo a altura completa (sin animación de 2 pasos)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Propina personalizada",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Subtotal reference
            Text(
                text = "Subtotal: $$subtotal",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Mode indicator + input display
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mode label
                Text(
                    text = if (isPercentageMode) "Porcentaje" else "Monto fijo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Input display
                Text(
                    text = if (isPercentageMode) {
                        if (input.isEmpty()) "0%" else "$input%"
                    } else {
                        "$$displayAmount"
                    },
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // If percentage mode, show calculated amount
                if (isPercentageMode && input.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "= $$displayAmount",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Custom keyboard with toggle
            CustomKeyboard(
                showToggle = true,
                onNumberClick = { number ->
                    input += number.toString()
                },
                onDecimalClick = {
                    if (!isPercentageMode && !input.contains(".")) {
                        input += "."
                    }
                },
                onClearClick = {
                    input = ""
                },
                onBackspaceClick = {
                    if (input.isNotEmpty()) {
                        input = input.dropLast(1)
                    }
                },
                onConfirmClick = {
                    // Return final amount (not percentage)
                    onConfirm(displayAmount)
                },
                onToggleClick = {
                    // Toggle between $ and %
                    isPercentageMode = !isPercentageMode
                    input = "" // Clear input when toggling
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Calculate tip amount from percentage
 */
private fun calculateTipFromPercentage(subtotal: String, percentage: String): String {
    val subtotalDecimal = subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val percentageDecimal = percentage.toBigDecimalOrNull() ?: BigDecimal.ZERO

    val tip = subtotalDecimal
        .multiply(percentageDecimal)
        .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

    return tip.toString()
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1C)
@Composable
private fun TipInputBottomSheetPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        TipInputBottomSheet(
            subtotal = "100.00",
            onDismiss = {},
            onConfirm = {}
        )
    }
}
