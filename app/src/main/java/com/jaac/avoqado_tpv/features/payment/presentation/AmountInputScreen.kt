package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTextField
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes

/**
 * Amount input screen - First step of payment flow
 *
 * **Flow:**
 * 1. User enters amount in MXN
 * 2. User clicks "Continuar" to proceed to rating
 *
 * **Design:**
 * Simple input with validation
 */
@Composable
fun AmountInputScreen(
    currentAmount: String,
    onAmountChange: (String) -> Unit,
    onContinue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var amount by remember { mutableStateOf(currentAmount) }

    ResponsiveScaffold(
        modifier = modifier,
        scrollable = false,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val sizes = LocalResponsiveSizes.current

        AvoqadoCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(sizes.paddingScreen)
        ) {
            Column(
                modifier = Modifier.padding(sizes.paddingCard),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Ingresa el monto a cobrar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(sizes.spacingMedium))

                // Amount input
                AvoqadoTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                        onAmountChange(it)
                    },
                    label = "Monto (MXN)",
                    placeholder = "100.00",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )

                Spacer(modifier = Modifier.height(sizes.spacingLarge))

                // Continue button
                AvoqadoButton(
                    text = "Continuar",
                    onClick = { onContinue(amount) },
                    enabled = amount.isNotBlank() && amount.toBigDecimalOrNull() != null,
                    modifier = Modifier.fillMaxWidth()
                )

                // Helper text
                Spacer(modifier = Modifier.height(sizes.spacingSmall))
                Text(
                    text = "Ingresa el monto total a cobrar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AmountInputScreenPreview() {
    MaterialTheme {
        AmountInputScreen(
            currentAmount = "",
            onAmountChange = {},
            onContinue = {}
        )
    }
}
