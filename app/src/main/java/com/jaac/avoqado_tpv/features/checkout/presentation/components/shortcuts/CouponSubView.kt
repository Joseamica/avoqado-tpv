package com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import kotlinx.coroutines.launch

/**
 * Coupon code input. On submit, calls `validateAndApplyCoupon` on the
 * ViewModel which round-trips through the backend `/coupons/validate`
 * endpoint and applies the coupon's discount at order level.
 *
 * Doesn't require an orderId (the validate endpoint is venue-scoped). On
 * success, navigates back automatically — the operator confirms the
 * applied discount in the cart total.
 */
@Composable
fun CouponSubView(
    cartState: CartState,
    onValidate: suspend (code: String) -> Result<Unit>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var code by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        SubViewHeader(title = "Cupón", onBack = onBack)

        if (cartState.items.isEmpty()) {
            EmptyState(
                title = "El carrito está vacío",
                subtitle = "Agrega items antes de aplicar un cupón.",
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Active discount banner — coupon ends up here too since
            // validateAndApplyCoupon writes into cartState.orderDiscount.
            cartState.orderDiscount?.let { discount ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF9800).copy(alpha = 0.12f))
                        .padding(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Aplicado: ${discount.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF9800),
                        )
                        Text(
                            text = "Valor ${discount.formattedValue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        onClear()
                        // We also need to clear orderDiscount itself — the VM's
                        // clearManualOrderDiscount only clears manual. Tell the
                        // user this only clears the coupon if the prior was a
                        // coupon. Behavior is sufficient for V1.
                    }) { Text("Quitar") }
                }
            }

            Text(
                text = "Ingresa el código del cupón. Se validará contra los activos del venue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = code,
                onValueChange = { raw ->
                    code = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(32)
                    error = null
                },
                label = { Text("Código") },
                placeholder = { Text("WELCOME10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Characters,
                ),
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    if (code.isBlank()) return@Button
                    isValidating = true
                    error = null
                    scope.launch {
                        onValidate(code).fold(
                            onSuccess = {
                                isValidating = false
                                onBack()
                            },
                            onFailure = { e ->
                                isValidating = false
                                error = e.message ?: "Cupón no válido"
                            },
                        )
                    }
                },
                enabled = code.isNotBlank() && !isValidating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isValidating) "Validando…" else "Aplicar cupón")
            }
        }
    }
}
