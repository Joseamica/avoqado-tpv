package com.jaac.avoqado_tpv.core.presentation.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.CustomKeyboard
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import java.math.BigDecimal

/**
 * FastPaymentEntryScreen - Pantalla dedicada para ingresar monto de pago rápido
 *
 * Reemplaza el modal AmountInputBottomSheet con una pantalla full-screen.
 *
 * **Ventajas sobre modal:**
 * - No más timing/lifecycle issues con auto-open
 * - Navigation predecible (back button funciona naturalmente)
 * - Más fácil de mantener
 * - UX más consistente con el resto de la app
 *
 * **UX Flow:**
 * WelcomeScreen → FastPaymentEntryScreen → PaymentScreen → Success
 *                                          ↑
 *                        Click "Nuevo Pago" regresa aquí directamente
 *
 * @param onNavigateBack Callback para regresar a WelcomeScreen
 * @param onAmountSubmit Callback cuando se confirma el monto (navega a PaymentScreen)
 */
@Composable
fun FastPaymentEntryScreen(
    onNavigateBack: () -> Unit = {},
    onAmountSubmit: (String) -> Unit = {}
) {
    var amount by remember { mutableStateOf("0") }
    var showError by remember { mutableStateOf(false) }

    // Format amount as currency ($X.XX)
    val formattedAmount = remember(amount) {
        val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        "$${String.format(java.util.Locale.US, "%,.2f", decimal)}"
    }

    // Check if amount is valid (> 0)
    val isValid = remember(amount) {
        val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
        decimal > BigDecimal.ZERO
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Pago Rápido",
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false,  // Keyboard is fixed, no scrolling needed
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .animateContentSize(animationSpec = tween(durationMillis = 200)),  // Smooth error animation
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Instructions
                Text(
                    text = "Ingresa el monto del pago",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Custom keyboard
                CustomKeyboard(
                    onNumberClick = { digit ->
                        showError = false
                        if (amount.length < 8) {
                            amount = if (amount == "0") digit.toString() else amount + digit.toString()
                        }
                    },
                    onDecimalClick = {
                        // Decimal point not needed - treating input as whole dollars
                    },
                    onClearClick = {
                        showError = false
                        amount = "0"
                    },
                    onBackspaceClick = {
                        showError = false
                        amount = if (amount.length > 1) {
                            amount.dropLast(1)
                        } else {
                            "0"
                        }
                    },
                    onConfirmClick = {
                        if (isValid) {
                            val decimal = amount.toBigDecimalOrNull() ?: BigDecimal.ZERO
                            onAmountSubmit(String.format(java.util.Locale.US, "%.2f", decimal))
                        } else {
                            showError = true
                        }
                    }
                )

                // Error message (only when user tries to confirm with $0.00)
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
}

// ══════════════════════════════════════════════════════════════════════════
// PREVIEW
// ══════════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun FastPaymentEntryScreenPreview() {
    AvoqadoTheme {
        FastPaymentEntryScreen()
    }
}
