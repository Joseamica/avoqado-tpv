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
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.ScreenProfile
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

    // Display amount as typed (no forced decimals while typing)
    val formattedAmount = remember(amount) {
        "$$amount"
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
            val sizes = LocalResponsiveSizes.current
            val isCompact = sizes.screenProfile == ScreenProfile.CompactSquare

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompact) 8.dp else 16.dp)
                    .animateContentSize(animationSpec = tween(durationMillis = 200)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 16.dp))

                // Instructions
                Text(
                    text = "Ingresa el monto del pago",
                    style = if (isCompact) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 16.dp))

                // Amount display (large on rectangular, compact on square)
                Text(
                    text = formattedAmount,
                    fontSize = if (isCompact) 32.sp else 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isValid)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 24.dp))

                // Custom keyboard
                CustomKeyboard(
                    onNumberClick = { digit ->
                        showError = false
                        // Limit to 8 chars total, max 2 decimal places
                        val decimalIndex = amount.indexOf('.')
                        val canAddDigit = amount.length < 8 &&
                            (decimalIndex == -1 || amount.length - decimalIndex <= 2)
                        if (canAddDigit) {
                            amount = if (amount == "0") digit.toString() else amount + digit.toString()
                        }
                    },
                    onDecimalClick = {
                        showError = false
                        // Only add decimal if not already present
                        if (!amount.contains('.')) {
                            amount = "$amount."
                        }
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

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"
private const val NEXGO_N62 = "spec:width=480px,height=480px,dpi=160"
private const val NEXGO_N86 = "spec:width=720px,height=1280px,dpi=320"

@Preview(name = "PAX A910S", device = PAX_A910S, showSystemUi = true)
@Composable
private fun FastPaymentEntryScreenPreview() {
    AvoqadoTheme {
        FastPaymentEntryScreen()
    }
}

@Preview(name = "NEXGO N62", device = NEXGO_N62, showSystemUi = true)
@Composable
private fun FastPaymentEntryScreenN62Preview() {
    AvoqadoTheme {
        FastPaymentEntryScreen()
    }
}

@Preview(name = "NEXGO N86", device = NEXGO_N86, showSystemUi = true)
@Composable
private fun FastPaymentEntryScreenN86Preview() {
    AvoqadoTheme {
        FastPaymentEntryScreen()
    }
}
