package com.jaac.avoqado_tpv.features.checkout.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts.CortesiaSubView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts.CouponSubView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts.ManualDiscountSubView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts.PayLaterSubView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.shortcuts.VoidItemsSubView
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import kotlinx.coroutines.launch

/**
 * Shortcuts tab — main entry + nested subviews for cortesía, descuento manual,
 * cupón, y quitar items. Visual reference: avoqado-android's CheckoutScreen
 * Shortcuts grid (solid-color action tiles with white text, sectioned layout).
 *
 * Layout:
 *  - **Favoritos**: product tiles configured via the Configurar tab (compact
 *    grid with letter avatar + price). Tapping a tile adds the product to
 *    the cart.
 *  - **Acciones**: 2-col grid of solid-color action tiles (Cortesía verde,
 *    Descuento magenta, Cupón naranja, Eliminar del carrito rojo). Tap disabled
 *    tiles (currently "Pagar después" only) to see the "Pronto" pill.
 *
 * The previous design used 12%-alpha tinted backgrounds — visible on light
 * theme, invisible on dark. This version uses solid colors so contrast is
 * preserved in both themes.
 */
@Composable
fun ShortcutsGridView(
    cartState: CartState,
    shortcuts: List<MosaicShortcut>,
    selectedCustomer: Customer?,
    onShortcutTap: (MosaicShortcut) -> Unit,
    onConfigureTap: () -> Unit,
    onApplyCortesia: (itemId: String, reason: String) -> Unit,
    onRemoveCortesia: (itemId: String) -> Unit,
    onApplyManualDiscount: (amountCents: Int, reason: String?) -> Unit,
    onClearManualDiscount: () -> Unit,
    onValidateCoupon: suspend (code: String) -> Result<Unit>,
    onRemoveItem: (itemId: String) -> Unit,
    onSelectCustomer: () -> Unit,
    onConfirmPayLater: suspend () -> Result<Unit>,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    var screen by remember { mutableStateOf(ShortcutsScreen.MAIN) }

    when (screen) {
        ShortcutsScreen.MAIN -> ShortcutsMainGrid(
            cartIsEmpty = cartState.isEmpty,
            shortcuts = shortcuts,
            onShortcutTap = onShortcutTap,
            onConfigureTap = onConfigureTap,
            onNavigate = { screen = it },
            snackbarHostState = snackbarHostState,
            modifier = modifier,
        )
        ShortcutsScreen.CORTESIA -> CortesiaSubView(
            cartState = cartState,
            onApply = onApplyCortesia,
            onRemove = onRemoveCortesia,
            onBack = { screen = ShortcutsScreen.MAIN },
            modifier = modifier,
        )
        ShortcutsScreen.DISCOUNT -> ManualDiscountSubView(
            cartState = cartState,
            onApply = onApplyManualDiscount,
            onClear = onClearManualDiscount,
            onBack = { screen = ShortcutsScreen.MAIN },
            modifier = modifier,
        )
        ShortcutsScreen.COUPON -> CouponSubView(
            cartState = cartState,
            onValidate = onValidateCoupon,
            onClear = onClearManualDiscount,
            onBack = { screen = ShortcutsScreen.MAIN },
            modifier = modifier,
        )
        ShortcutsScreen.VOID -> VoidItemsSubView(
            cartState = cartState,
            onRemove = onRemoveItem,
            onBack = { screen = ShortcutsScreen.MAIN },
            modifier = modifier,
        )
        ShortcutsScreen.PAY_LATER -> PayLaterSubView(
            cartState = cartState,
            selectedCustomer = selectedCustomer,
            onSelectCustomer = onSelectCustomer,
            onConfirm = {
                val result = onConfirmPayLater()
                if (result.isSuccess) {
                    screen = ShortcutsScreen.MAIN
                }
                result
            },
            onBack = { screen = ShortcutsScreen.MAIN },
            modifier = modifier,
        )
    }
}

enum class ShortcutsScreen { MAIN, CORTESIA, DISCOUNT, COUPON, VOID, PAY_LATER }

@Composable
private fun ShortcutsMainGrid(
    cartIsEmpty: Boolean,
    shortcuts: List<MosaicShortcut>,
    onShortcutTap: (MosaicShortcut) -> Unit,
    onConfigureTap: () -> Unit,
    onNavigate: (ShortcutsScreen) -> Unit,
    snackbarHostState: SnackbarHostState?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val showEmptyCartHint: () -> Unit = {
        if (snackbarHostState != null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Agrega items al carrito primero",
                )
            }
        }
    }

    val actionTiles = remember {
        listOf(
            ActionTile(
                screen = ShortcutsScreen.CORTESIA,
                label = "Cortesía",
                icon = Icons.Filled.CardGiftcard,
                tint = ActionPalette.cortesia,
                requiresCartItems = true,
                pronto = false,
            ),
            ActionTile(
                screen = ShortcutsScreen.DISCOUNT,
                label = "Descuento",
                icon = Icons.Filled.Sell,
                tint = ActionPalette.discount,
                requiresCartItems = true,
                pronto = false,
            ),
            ActionTile(
                screen = ShortcutsScreen.COUPON,
                label = "Cupón",
                icon = Icons.Filled.Tag,
                tint = ActionPalette.coupon,
                requiresCartItems = true,
                pronto = false,
            ),
            ActionTile(
                screen = ShortcutsScreen.VOID,
                // "Eliminar del carrito" — pre-orden, NO es el void-items legacy
                // que opera contra órdenes ya creadas en backend con audit trail.
                label = "Eliminar del carrito",
                icon = Icons.Filled.RemoveShoppingCart,
                tint = ActionPalette.voidTile,
                requiresCartItems = true,
                pronto = false,
            ),
            ActionTile(
                screen = ShortcutsScreen.PAY_LATER,
                label = "Pagar después",
                icon = Icons.Filled.Schedule,
                tint = ActionPalette.payLater,
                requiresCartItems = true,
                pronto = false,
            ),
        )
    }

    LazyVerticalGrid(
        // 3 columnas para tiles más chicas en PAX A910S (360dp).
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // ───── FAVORITOS section ─────
        item(span = { GridItemSpan(3) }) {
            SectionHeader(title = "Favoritos")
        }

        if (shortcuts.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                EmptyShortcutsState(onConfigureTap = onConfigureTap)
            }
        } else {
            items(items = shortcuts, key = { "product_${it.id}" }) { shortcut ->
                ProductShortcutTile(shortcut = shortcut, onClick = { onShortcutTap(shortcut) })
            }
        }

        // ───── ACCIONES section ─────
        item(span = { GridItemSpan(3) }) {
            Spacer(modifier = Modifier.height(6.dp))
            SectionHeader(title = "Acciones")
        }

        items(items = actionTiles, key = { "action_${it.label}" }) { tile ->
            ActionTileView(
                tile = tile,
                onTap = {
                    when {
                        tile.pronto -> {
                            // No-op — "Pronto" tiles aren't actionable yet.
                        }
                        tile.requiresCartItems && cartIsEmpty -> showEmptyCartHint()
                        tile.screen != null -> onNavigate(tile.screen)
                    }
                },
            )
        }
    }
}

private data class ActionTile(
    val screen: ShortcutsScreen?,
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val requiresCartItems: Boolean,
    val pronto: Boolean,
)

// Solid colors borrowed from avoqado-android's ActionColors palette. Slightly
// muted so the white-on-color text stays legible in both themes.
private object ActionPalette {
    val cortesia = Color(0xFF4CAF50)
    val discount = Color(0xFFE61F6B)
    val coupon = Color(0xFFFF9800)
    val voidTile = Color(0xFFE53935)
    val payLater = Color(0xFF7E57C2)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ActionTileView(tile: ActionTile, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(tile.tint)
            .clickable(onClick = onTap),
    ) {
        // "Pronto" pill — top-right, like avoqado-android's "Gift Card / Pronto" tile.
        if (tile.pronto) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = "Pronto",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProductShortcutTile(shortcut: MosaicShortcut, onClick: () -> Unit) {
    val tint = shortcut.colorHex
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.primary

    // Two-line letter avatar on a tinted background; matches the "CA"/"BO"
    // initials pattern we already use in SearchOverlay and CartDetailsSheet.
    val initials = shortcut.label.split(' ', '/', '-')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.firstOrNull()?.uppercaseChar() ?: ' ' }
        .joinToString("")
        .ifBlank { shortcut.label.take(2).uppercase() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyShortcutsState(onConfigureTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onConfigureTap)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Sin favoritos configurados",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Ve a Configurar para agregar productos rápidos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ShortcutsMainPreview() {
    AvoqadoTheme {
        ShortcutsGridView(
            cartState = CartState(),
            shortcuts = listOf(
                MosaicShortcut(id = "s1", venueId = "v", productId = "p1", position = 0, label = "Café Americano", colorHex = "#4CAF50"),
                MosaicShortcut(id = "s2", venueId = "v", productId = "p2", position = 1, label = "Latte", colorHex = "#2196F3"),
            ),
            selectedCustomer = null,
            onShortcutTap = {},
            onConfigureTap = {},
            onApplyCortesia = { _, _ -> },
            onRemoveCortesia = {},
            onApplyManualDiscount = { _, _ -> },
            onClearManualDiscount = {},
            onValidateCoupon = { Result.success(Unit) },
            onRemoveItem = {},
            onSelectCustomer = {},
            onConfirmPayLater = { Result.success(Unit) },
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, showBackground = true)
@Composable
private fun ShortcutsEmptyPreview() {
    AvoqadoTheme {
        ShortcutsGridView(
            cartState = CartState(),
            shortcuts = emptyList(),
            selectedCustomer = null,
            onShortcutTap = {},
            onConfigureTap = {},
            onApplyCortesia = { _, _ -> },
            onRemoveCortesia = {},
            onApplyManualDiscount = { _, _ -> },
            onClearManualDiscount = {},
            onValidateCoupon = { Result.success(Unit) },
            onRemoveItem = {},
            onSelectCustomer = {},
            onConfirmPayLater = { Result.success(Unit) },
        )
    }
}
