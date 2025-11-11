package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

/**
 * AmountInputBottomSheet - Modal para ingresar monto de pago
 *
 * Modal deslizable desde abajo con teclado numérico personalizado.
 *
 * Features:
 * - Entrada de monto con formato de moneda
 * - Teclado numérico grande (CustomKeyboard)
 * - Validación en tiempo real
 * - Animación slide-up
 *
 * @param onDismiss Callback cuando se cierra el modal
 * @param onConfirm Callback cuando se confirma el monto (String)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmountInputBottomSheet(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var amount by remember { mutableStateOf("0") }
    var showError by remember { mutableStateOf(false) }  // Only show error when user tries to confirm

    // Remember sheet state to control expansion
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true  // Open fully expanded, no half-height state
    )

    // Format amount as currency ($X.XX)
    // Treat input as direct dollar amount (not centavos)
    val formattedAmount = remember(amount) {
        val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        // Use Locale.US to display with period (25.00) instead of comma (25,00)
        "$${String.format(java.util.Locale.US, "%,.2f", decimal)}"
    }

    // Check if amount is valid (> 0)
    val isValid = remember(amount) {
        val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        decimal > BigDecimal.ZERO
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 200)),  // Smooth animation
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cantidad personalizada",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Amount display (large)
            Text(
                text = formattedAmount,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = if (isValid)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            )

            // Custom keyboard
            CustomKeyboard(
                onNumberClick = { digit ->
                    // Hide error when user starts typing
                    showError = false
                    // Append digit (max 8 digits = $99,999,999.00)
                    if (amount.length < 8) {
                        amount = if (amount == "0") digit.toString() else amount + digit.toString()
                    }
                },
                onDecimalClick = {
                    // Decimal point not needed - treating input as whole dollars
                },
                onClearClick = {
                    showError = false  // Hide error when clearing
                    amount = "0"
                },
                onBackspaceClick = {
                    showError = false  // Hide error when editing
                    amount = if (amount.length > 1) {
                        amount.dropLast(1)
                    } else {
                        "0"
                    }
                },
                onConfirmClick = {
                    if (isValid) {
                        // ✅ FIX: Use Locale.US to ensure period (.) as decimal separator
                        // Spanish locale would use comma (25,00) which breaks toBigDecimalOrNull()
                        val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        onConfirm(String.format(java.util.Locale.US, "%.2f", decimal))
                    } else {
                        // Show error only when user tries to confirm with invalid amount
                        showError = true
                    }
                }
            )

            // ✅ Professional UX: Smooth animated error message
            // Error only appears when user tries to confirm with $0.00
            // Uses animateContentSize on parent Column for smooth transition
            if (showError) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Ingresa un monto mayor a $0.00",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AmountInputBottomSheetPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Simulate bottom sheet appearance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp)
            ) {
                Text(
                    text = "Cantidad personalizada",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "$0.00",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                CustomKeyboard(
                    onNumberClick = {},
                    onDecimalClick = {},
                    onClearClick = {},
                    onBackspaceClick = {},
                    onConfirmClick = {}
                )
            }
        }
    }
}
