package com.jaac.avoqado_tpv.features.checkout.presentation.components.cart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState

/**
 * Bottom bar of the Checkout screen — collapses to a single pill button.
 *
 * Empty cart: button reads "Cobrar" (disabled). Has items: "Cobrar $X.XX"
 * (enabled). Tap fires [onTap] — `CheckoutScreen` then opens the
 * [CartDetailsSheet] modal with customer / items / totals / kebab actions.
 *
 * Rationale: at PAX A910S (640dp tall) the previous inline bottom panel
 * ate ~150dp and pushed the product grid off-screen. One deliberate tap on
 * Cobrar opens a full-context summary — Square iOS pattern.
 */
@Composable
fun CartPanelView(
    cartState: CartState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider()
        Button(
            onClick = onTap,
            enabled = !cartState.isEmpty,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = if (cartState.isEmpty) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                text = if (cartState.isEmpty) "Cobrar" else "Cobrar ${cartState.totalDisplay}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Preview(name = "Cart bar - Empty", widthDp = 360, heightDp = 90, showBackground = true)
@Composable
private fun CartBarEmptyPreview() {
    AvoqadoTheme {
        CartPanelView(cartState = CartState(), onTap = {})
    }
}

@Preview(name = "Cart bar - With items", widthDp = 360, heightDp = 90, showBackground = true)
@Composable
private fun CartBarWithItemsPreview() {
    AvoqadoTheme {
        CartPanelView(
            cartState = CartState(
                items = listOf(
                    CartItem(type = CartItemType.ProductItem("p-1"), name = "Corte", unitPriceCents = 25000),
                ),
            ),
            onTap = {},
        )
    }
}
