package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import com.jaac.avoqado_tpv.features.ordering.domain.PaymentStatus
import java.math.BigDecimal

// ============================================================
// COLOR CONSTANTS
// ============================================================

private object ActionColors {
    val GiftCard = Color(0xFF6B4EE6)      // Purple - DISABLED
    val Discounts = Color(0xFFE91E63)     // Pink/Magenta
    val Coupons = Color(0xFFFF9800)       // Orange
    val PayLater = Color(0xFF607D8B)      // Blue Grey - DISABLED
    val VoidItems = Color(0xFFF44336)     // Red
    val Cortesia = Color(0xFF4CAF50)      // Green
}

// ============================================================
// STATE MODELS
// ============================================================

/**
 * Internal navigation state for ActionsTab
 */
private sealed class ActionsNavigation {
    data object Grid : ActionsNavigation()
    data object DiscountsSubNav : ActionsNavigation()
    data object CouponsSubNav : ActionsNavigation()
}

/**
 * Dialog state management
 */
private sealed class ActionsDialogState {
    data object None : ActionsDialogState()
    data object Cortesia : ActionsDialogState()
    data object VoidItems : ActionsDialogState()
    data object PayLater : ActionsDialogState()
    data class DiscountConfirmation(val discount: Discount) : ActionsDialogState()
}

/**
 * Shortcut action definition for the grid
 */
@Immutable
private data class ShortcutAction(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val backgroundColor: Color,
    val enabled: Boolean = true,
    val badge: String? = null,
    val onClick: () -> Unit
)

/**
 * Coupon Validation State
 */
sealed class CouponValidationState {
    data object Idle : CouponValidationState()
    data object Loading : CouponValidationState()
    data class Valid(val coupon: com.jaac.avoqado_tpv.features.ordering.domain.CouponCode) : CouponValidationState()
    data class Invalid(val reason: String) : CouponValidationState()
    data class Error(val message: String) : CouponValidationState()
}

// ============================================================
// MAIN COMPOSABLE
// ============================================================

/**
 * ActionsTab - Square POS Style Grid of Quick Actions
 *
 * Provides functionality for:
 * - **Discounts**: Apply predefined or manual discounts
 * - **Coupons**: Validate and apply coupon codes
 * - **Cortesia**: Complimentary order (100% discount)
 * - **Gift Card**: Coming Soon
 * - **Pay Later**: Coming Soon
 * - **Void Items**: Cancel items with audit trail
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ ┌───────────┐  ┌───────────┐        │
 * │ │ Gift Card │  │ Descuentos│        │
 * │ │ (soon)    │  │     →     │        │
 * │ └───────────┘  └───────────┘        │
 * │ ┌───────────┐  ┌───────────┐        │
 * │ │ Cupones   │  │ Pagar     │        │
 * │ │     →     │  │ Despues   │        │
 * │ └───────────┘  └───────────┘        │
 * │ ┌───────────┐  ┌───────────┐        │
 * │ │ Void      │  │ Cortesia  │        │
 * │ │ Items     │  │           │        │
 * │ └───────────┘  └───────────┘        │
 * │                                     │
 * │ [2 descuentos aplicados] → ver      │
 * └─────────────────────────────────────┘
 * ```
 */
@Composable
fun ActionsTab(
    order: Order,
    availableDiscounts: List<Discount> = emptyList(),
    appliedDiscounts: List<OrderDiscount> = emptyList(),
    isLoadingDiscounts: Boolean = false,
    couponValidationState: CouponValidationState = CouponValidationState.Idle,
    // Customer search for Pay Later
    customerSearchState: com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState = com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState.Idle,
    recentCustomers: List<com.jaac.avoqado_tpv.features.ordering.domain.Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    loyaltyActive: Boolean = false,
    onApplyPredefinedDiscount: (discountId: String, itemIds: List<String>?, reason: String?) -> Unit = { _, _, _ -> },
    onApplyManualDiscount: (type: DiscountType, value: Double, reason: String?, itemIds: List<String>?) -> Unit = { _, _, _, _ -> },
    onRemoveDiscount: (orderDiscountId: String) -> Unit = {},
    onValidateCoupon: (code: String) -> Unit = {},
    onApplyCoupon: (code: String) -> Unit = {},
    onCompItems: () -> Unit = {},
    onVoidItems: (itemIds: List<String>, reason: String) -> Unit = { _, _ -> },
    onSearchCustomers: (query: String) -> Unit = {},
    onLoadRecentCustomers: () -> Unit = {},
    onClearCustomerSearch: () -> Unit = {},
    onCreatePayLaterOrder: (customerId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Navigation state
    var currentNav by remember { mutableStateOf<ActionsNavigation>(ActionsNavigation.Grid) }

    // Dialog state
    var dialogState by remember { mutableStateOf<ActionsDialogState>(ActionsDialogState.None) }
    var showManualDiscountDialog by remember { mutableStateOf(false) }

    // Handle back button for sub-navigation
    BackHandler(enabled = currentNav !is ActionsNavigation.Grid) {
        currentNav = ActionsNavigation.Grid
    }

    // Define actions for the grid
    // NOTE: Do NOT use remember for actions list - onClick lambdas need fresh state references
    val hasItems = order.items.isNotEmpty()
    val actions = buildList {
        add(
            ShortcutAction(
                id = "gift_card",
                title = "Gift Card",
                icon = Icons.Outlined.CardGiftcard,
                backgroundColor = ActionColors.GiftCard,
                enabled = false,
                badge = "Pronto",
                onClick = { /* Disabled */ }
            )
        )
        add(
            ShortcutAction(
                id = "descuentos",
                title = "Descuentos",
                icon = Icons.Outlined.Percent,
                backgroundColor = ActionColors.Discounts,
                enabled = hasItems,
                onClick = { currentNav = ActionsNavigation.DiscountsSubNav }
            )
        )
        add(
            ShortcutAction(
                id = "cupones",
                title = "Cupones",
                icon = Icons.Outlined.ConfirmationNumber,
                backgroundColor = ActionColors.Coupons,
                enabled = hasItems,
                onClick = { currentNav = ActionsNavigation.CouponsSubNav }
            )
        )
        // ⭐ FIX: Only show "Pagar Despues" if order is NOT already pay-later
        if (!order.isPayLater) {
            add(
                ShortcutAction(
                    id = "pagar_despues",
                    title = "Pagar Despues",
                    icon = Icons.Outlined.Schedule,
                    backgroundColor = ActionColors.PayLater,
                    enabled = hasItems,
                    onClick = { dialogState = ActionsDialogState.PayLater }
                )
            )
        }
        add(
            ShortcutAction(
                id = "void_items",
                title = "Void Items",
                icon = Icons.Default.Delete,
                backgroundColor = ActionColors.VoidItems,
                enabled = hasItems,
                onClick = { dialogState = ActionsDialogState.VoidItems }
            )
        )
        add(
            ShortcutAction(
                id = "cortesia",
                title = "Cortesia",
                icon = Icons.Default.Favorite,
                backgroundColor = ActionColors.Cortesia,
                enabled = hasItems,
                onClick = { dialogState = ActionsDialogState.Cortesia }
            )
        )
    }

    // Main content based on navigation state
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (currentNav) {
            ActionsNavigation.Grid -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Grid of actions
                    ActionCardGrid(
                        actions = actions,
                        modifier = Modifier.weight(1f)
                    )

                    // Applied discounts badge at bottom
                    if (appliedDiscounts.isNotEmpty()) {
                        AppliedDiscountsBadge(
                            count = appliedDiscounts.size,
                            onClick = { currentNav = ActionsNavigation.CouponsSubNav }
                        )
                    }
                }
            }

            ActionsNavigation.DiscountsSubNav -> {
                DiscountsSubNavScreen(
                    availableDiscounts = availableDiscounts,
                    isLoading = isLoadingDiscounts,
                    hasItems = hasItems,
                    onSelectDiscount = { discount ->
                        dialogState = ActionsDialogState.DiscountConfirmation(discount)
                    },
                    onOpenManualDiscount = { showManualDiscountDialog = true },
                    onBack = { currentNav = ActionsNavigation.Grid }
                )
            }

            ActionsNavigation.CouponsSubNav -> {
                CouponsSubNavScreen(
                    validationState = couponValidationState,
                    hasItems = hasItems,
                    appliedDiscounts = appliedDiscounts,
                    onApplyCoupon = onApplyCoupon,
                    onRemoveDiscount = onRemoveDiscount,
                    onBack = { currentNav = ActionsNavigation.Grid }
                )
            }
        }
    }

    // ==========================================
    // DIALOGS
    // ==========================================

    when (val state = dialogState) {
        ActionsDialogState.None -> { /* No dialog */ }

        ActionsDialogState.Cortesia -> {
            CortesiaDialog(
                orderSubtotal = order.subtotal,
                onDismiss = { dialogState = ActionsDialogState.None },
                onConfirm = { reason ->
                    // Apply 100% discount
                    onApplyManualDiscount(DiscountType.PERCENTAGE, 100.0, reason ?: "Cortesia", null)
                    dialogState = ActionsDialogState.None
                }
            )
        }

        ActionsDialogState.VoidItems -> {
            VoidItemsDialog(
                items = order.items.distinctBy { it.id },
                onDismiss = { dialogState = ActionsDialogState.None },
                onConfirm = { selectedIds, reason ->
                    onVoidItems(selectedIds, reason)
                    dialogState = ActionsDialogState.None
                }
            )
        }

        ActionsDialogState.PayLater -> {
            // Load recent customers on dialog open (proactive UX like Toast/Square)
            LaunchedEffect(Unit) {
                onLoadRecentCustomers()
            }

            // Use existing CustomerSearchModal (fully implemented, optimized for 1GB RAM)
            CustomerSearchModal(
                searchState = customerSearchState,
                recentCustomers = recentCustomers,
                isLoadingRecent = isLoadingRecentCustomers,
                onSearch = { query -> onSearchCustomers(query) },
                onSelectCustomer = { customer ->
                    onCreatePayLaterOrder(customer.id)
                    dialogState = ActionsDialogState.None  // Close dialog
                },
                onDismiss = {
                    dialogState = ActionsDialogState.None
                    onClearCustomerSearch()  // Reset search state
                },
                loyaltyActive = loyaltyActive
            )
        }

        is ActionsDialogState.DiscountConfirmation -> {
            PredefinedDiscountDialog(
                discount = state.discount,
                onDismiss = { dialogState = ActionsDialogState.None },
                onApply = { reason ->
                    onApplyPredefinedDiscount(state.discount.id, null, reason)
                    dialogState = ActionsDialogState.None
                    currentNav = ActionsNavigation.Grid
                }
            )
        }
    }

    // Manual Discount Dialog (reuse existing)
    if (showManualDiscountDialog) {
        ManualDiscountDialog(
            onDismiss = { showManualDiscountDialog = false },
            onApply = { type, value, reason ->
                onApplyManualDiscount(type, value, reason, null)
                showManualDiscountDialog = false
                currentNav = ActionsNavigation.Grid
            }
        )
    }
}

// ============================================================
// GRID COMPONENTS
// ============================================================

/**
 * Grid of shortcut action cards (2 columns)
 */
@Composable
private fun ActionCardGrid(
    actions: List<ShortcutAction>,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(sizes.paddingScreen),
        horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall),
        verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
    ) {
        items(actions, key = { it.id }) { action ->
            ShortcutActionCard(action = action)
        }
    }
}

/**
 * Individual action card - Square POS style
 * Icon in top-left, title at bottom, colored background
 */
@Composable
private fun ShortcutActionCard(
    action: ShortcutAction,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current
    val alpha = if (action.enabled) 1f else 0.5f

    Card(
        onClick = action.onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(sizes.iconSizeLarge * 2.5f), // Responsive height
        enabled = action.enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = action.backgroundColor.copy(alpha = alpha),
            disabledContainerColor = action.backgroundColor.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp,
            disabledElevation = 0.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Icon in top-left
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(32.dp),
                tint = Color.White.copy(alpha = if (action.enabled) 1f else 0.7f)
            )

            // Badge in top-right (if present)
            action.badge?.let { badgeText ->
                Badge(
                    modifier = Modifier.align(Alignment.TopEnd),
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // Title at bottom
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = if (action.enabled) 1f else 0.7f),
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}

/**
 * Badge showing applied discounts count - clickable to see details
 */
@Composable
private fun AppliedDiscountsBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current
    val greenColor = Color(0xFF22C55E)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sizes.paddingScreen, vertical = sizes.spacingSmall)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = greenColor.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, greenColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Green check icon
            Surface(
                shape = CircleShape,
                color = greenColor.copy(alpha = 0.2f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = greenColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text
            Text(
                text = "$count ${if (count == 1) "descuento aplicado" else "descuentos aplicados"}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            // Arrow
            Text(
                text = "Ver detalles",
                style = MaterialTheme.typography.bodyMedium,
                color = greenColor,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = greenColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============================================================
// SUB-NAVIGATION COMPONENTS
// ============================================================

/**
 * Breadcrumb header for sub-navigation
 * ← Shortcuts > [Title]
 */
@Composable
private fun BreadcrumbHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Shortcuts",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Discounts sub-navigation screen
 * Shows venue's predefined discounts in a grid
 */
@Composable
private fun DiscountsSubNavScreen(
    availableDiscounts: List<Discount>,
    isLoading: Boolean,
    hasItems: Boolean,
    onSelectDiscount: (Discount) -> Unit,
    onOpenManualDiscount: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Descuentos", onBack = onBack)

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(sizes.paddingScreen),
                horizontalArrangement = Arrangement.spacedBy(sizes.spacingSmall),
                verticalArrangement = Arrangement.spacedBy(sizes.spacingSmall)
            ) {
                // Predefined discounts
                items(availableDiscounts, key = { it.id }) { discount ->
                    DiscountSelectionCard(
                        discount = discount,
                        enabled = hasItems,
                        onClick = { onSelectDiscount(discount) }
                    )
                }

                // Empty state
                if (availableDiscounts.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        EmptyDiscountsMessage()
                    }
                }

                // Manual discount button (full width)
                item(span = { GridItemSpan(2) }) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onOpenManualDiscount,
                        enabled = hasItems,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Percent,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Descuento Manual")
                    }
                }

                // Warning if no items
                if (!hasItems) {
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            text = "Agrega productos a la orden para aplicar descuentos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card for selecting a predefined discount
 */
@Composable
private fun DiscountSelectionCard(
    discount: Discount,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current
    val iconVector = when (discount.type) {
        DiscountType.PERCENTAGE -> Icons.Outlined.Percent
        DiscountType.FIXED -> Icons.Outlined.CardGiftcard
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(sizes.iconSizeLarge * 2f),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // Icon in top-left with emoji
            Row(
                modifier = Modifier.align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ActionColors.Discounts
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = discount.emoji,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // Discount info at bottom
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = discount.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = discount.formattedValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ActionColors.Discounts
                )
            }
        }
    }
}

/**
 * Empty state when no discounts are configured
 */
@Composable
private fun EmptyDiscountsMessage(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Percent,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No hay descuentos predefinidos",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Puedes aplicar un descuento manual",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Coupons sub-navigation screen
 * Shows coupon input and applied discounts
 */
@Composable
private fun CouponsSubNavScreen(
    validationState: CouponValidationState,
    hasItems: Boolean,
    appliedDiscounts: List<OrderDiscount>,
    onApplyCoupon: (String) -> Unit,
    onRemoveDiscount: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbHeader(title = "Cupones", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(sizes.paddingScreen),
            verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
        ) {
            // Coupon input card
            CouponInputCard(
                validationState = validationState,
                hasItems = hasItems,
                onApplyCoupon = onApplyCoupon
            )

            // Applied discounts section
            if (appliedDiscounts.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Descuentos Aplicados",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                appliedDiscounts.forEach { discount ->
                    AppliedDiscountRow(
                        discount = discount,
                        onRemove = { onRemoveDiscount(discount.id) }
                    )
                }
            }
        }
    }
}

/**
 * Coupon code input card
 */
@Composable
private fun CouponInputCard(
    validationState: CouponValidationState,
    hasItems: Boolean,
    onApplyCoupon: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var couponCode by remember { mutableStateOf("") }
    var wasLoading by remember { mutableStateOf(false) }

    // Clear code on successful application
    LaunchedEffect(validationState) {
        if (validationState is CouponValidationState.Loading) {
            wasLoading = true
        } else if (wasLoading && validationState is CouponValidationState.Idle) {
            couponCode = ""
            wasLoading = false
        } else if (validationState is CouponValidationState.Invalid ||
            validationState is CouponValidationState.Error
        ) {
            wasLoading = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ConfirmationNumber,
                    contentDescription = null,
                    tint = ActionColors.Coupons
                )
                Text(
                    text = "Ingresar Codigo de Cupon",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = couponCode,
                    onValueChange = { couponCode = it.uppercase() },
                    placeholder = { Text("Ej: WELCOME10") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = validationState !is CouponValidationState.Loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (couponCode.isNotBlank() && hasItems) {
                                onApplyCoupon(couponCode)
                            }
                        }
                    )
                )

                Button(
                    onClick = { onApplyCoupon(couponCode) },
                    enabled = couponCode.isNotBlank() && hasItems && validationState !is CouponValidationState.Loading
                ) {
                    if (validationState is CouponValidationState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Aplicar")
                    }
                }
            }

            // Warning if no items
            if (!hasItems) {
                Text(
                    text = "Agrega productos para aplicar cupones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Show error/invalid messages
            when (validationState) {
                is CouponValidationState.Invalid -> {
                    CouponErrorMessage(
                        message = validationState.reason,
                        isError = true
                    )
                }

                is CouponValidationState.Error -> {
                    CouponErrorMessage(
                        message = validationState.message,
                        isError = false
                    )
                }

                else -> { /* Idle, Loading, or Valid - no additional UI */ }
            }
        }
    }
}

/**
 * Coupon error/invalid message
 */
@Composable
private fun CouponErrorMessage(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val iconTint = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val textColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Close else Icons.Default.Info,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/**
 * Applied discount row with remove option
 */
@Composable
private fun AppliedDiscountRow(
    discount: OrderDiscount,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val greenColor = Color(0xFF22C55E)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = greenColor.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, greenColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Green check icon
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = greenColor,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Discount info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = discount.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                discount.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Amount
            Text(
                text = "-${discount.formattedAmount}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = greenColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Remove button
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Eliminar descuento",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ============================================================
// DIALOGS
// ============================================================

/**
 * Cortesia Dialog - Apply 100% discount (complimentary order)
 */
@Composable
private fun CortesiaDialog(
    orderSubtotal: BigDecimal,
    onDismiss: () -> Unit,
    onConfirm: (reason: String?) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    val greenColor = ActionColors.Cortesia

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = greenColor,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Cortesia (100% Descuento)",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Summary card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = greenColor.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Descuento Total",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "-\$${String.format("%.2f", orderSubtotal)}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = greenColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "La orden quedara en \$0.00 MXN",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Razon (opcional)") },
                    placeholder = { Text("Ej: Recuperacion de servicio") },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason.takeIf { it.isNotBlank() }) },
                colors = ButtonDefaults.buttonColors(containerColor = greenColor)
            ) {
                Text("Aplicar Cortesia")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Void Items Dialog - Select items to void with checkboxes
 *
 * ⚠️ KEYBOARD HANDLING: Follows pattern from docs/COMPOSE_KEYBOARD_HANDLING.md
 * - Uses Dialog instead of AlertDialog for better keyboard control
 * - Captures LocalFocusManager INSIDE Dialog (not outside)
 * - Uses .imePadding() for keyboard spacing
 * - Tap outside TextField dismisses keyboard
 * - Enter key (Done) dismisses keyboard
 */
@Composable
private fun VoidItemsDialog(
    items: List<OrderItem>,
    onDismiss: () -> Unit,
    onConfirm: (selectedItemIds: List<String>, reason: String) -> Unit
) {
    var selectedItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reason by remember { mutableStateOf("") }
    val redColor = ActionColors.VoidItems
    val focusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ✅ STEP 2: Capture FocusManager INSIDE Dialog
        val focusManager = LocalFocusManager.current

        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .imePadding(),  // ✅ STEP 3: IME padding
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())  // ✅ STEP 4: Scrollable
                    .padding(24.dp)
                    .pointerInput(Unit) {  // ✅ STEP 5: Tap outside to dismiss keyboard
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon + Title
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = redColor,
                        modifier = Modifier.size(40.dp)
                    )

                    Text(
                        text = "Anular Items",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Subtitle
                Text(
                    text = "Selecciona los items a anular:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Items list with checkboxes
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedItems = if (item.id in selectedItems) {
                                    selectedItems - item.id
                                } else {
                                    selectedItems + item.id
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = item.id in selectedItems,
                            onCheckedChange = null // Handled by row click
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${item.quantity}x ${item.productName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = item.formattedTotalPrice,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                HorizontalDivider()

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Razon (requerida)") },
                    placeholder = { Text("Ej: Cliente cambio de opinion") },
                    singleLine = false,
                    maxLines = 3,
                    isError = reason.isBlank() && selectedItems.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done  // ✅ STEP 6: Done button
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()  // ✅ Clear focus on Done
                        }
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()  // ✅ STEP 7: Clear on button
                            onConfirm(selectedItems.toList(), reason)
                        },
                        enabled = selectedItems.isNotEmpty() && reason.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = redColor)
                    ) {
                        Text("Anular ${selectedItems.size} ${if (selectedItems.size == 1) "item" else "items"}")
                    }
                }
            }
        }
    }
}

/**
 * Manual Discount Dialog
 */
@Composable
private fun ManualDiscountDialog(
    onDismiss: () -> Unit,
    onApply: (type: DiscountType, value: Double, reason: String?) -> Unit
) {
    var selectedType by remember { mutableIntStateOf(0) } // 0 = Percentage, 1 = Fixed
    var valueText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    val discountType = if (selectedType == 0) DiscountType.PERCENTAGE else DiscountType.FIXED
    val value = valueText.toDoubleOrNull() ?: 0.0
    val isValidPercentage = discountType == DiscountType.PERCENTAGE && value in 0.01..100.0
    val isValidFixed = discountType == DiscountType.FIXED && value > 0
    val isValid = isValidPercentage || isValidFixed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Descuento Manual",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Type selector
                Text(
                    text = "Tipo de descuento",
                    style = MaterialTheme.typography.labelLarge
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedType == 0,
                        onClick = { selectedType = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("Porcentaje")
                    }
                    SegmentedButton(
                        selected = selectedType == 1,
                        onClick = { selectedType = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("Monto Fijo")
                    }
                }

                // Value input
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it.filter { char -> char.isDigit() || char == '.' } },
                    label = {
                        Text(
                            if (selectedType == 0) "Porcentaje (1-100)"
                            else "Monto ($)"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    suffix = { Text(if (selectedType == 0) "%" else "$") },
                    isError = valueText.isNotBlank() && !isValid
                )

                // Reason input (default: "Descuento manual" if empty)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Razón") },
                    placeholder = { Text("Ej: Cliente frecuente") },
                    supportingText = { Text("Se usará 'Descuento manual' si está vacío") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(discountType, value, reason.ifBlank { "Descuento manual" }) },
                enabled = isValid
            ) {
                Text("Aplicar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Predefined Discount Confirmation Dialog
 */
@Composable
private fun PredefinedDiscountDialog(
    discount: Discount,
    onDismiss: () -> Unit,
    onApply: (reason: String?) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Aplicar Descuento",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Discount info
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = discount.emoji,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = discount.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = discount.formattedValue,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        discount.conditions?.let { conditions ->
                            Text(
                                text = conditions.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Razon (opcional)") },
                    placeholder = { Text("Ej: Promocion especial") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(reason.takeIf { it.isNotBlank() }) }) {
                Text("Aplicar Descuento")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// ============================================================
// PREVIEWS
// ============================================================

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ActionsTabGridPreview() {
    AvoqadoTheme {
        val mockOrder = createMockOrder()

        ActionsTab(
            order = mockOrder,
            appliedDiscounts = listOf(createMockOrderDiscount())
        )
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ShortcutActionCardPreview() {
    AvoqadoTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShortcutActionCard(
                action = ShortcutAction(
                    id = "test1",
                    title = "Descuentos",
                    icon = Icons.Outlined.Percent,
                    backgroundColor = ActionColors.Discounts,
                    enabled = true,
                    onClick = {}
                )
            )
            ShortcutActionCard(
                action = ShortcutAction(
                    id = "test2",
                    title = "Gift Card",
                    icon = Icons.Outlined.CardGiftcard,
                    backgroundColor = ActionColors.GiftCard,
                    enabled = false,
                    badge = "Pronto",
                    onClick = {}
                )
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun DiscountsSubNavPreview() {
    val mockDiscounts = listOf(
        Discount(
            id = "1",
            name = "Happy Hour",
            type = DiscountType.PERCENTAGE,
            value = BigDecimal("15"),
            scope = DiscountScope.ORDER,
            conditions = null,
            active = true,
            requiresAuthorization = false
        ),
        Discount(
            id = "2",
            name = "Employee",
            type = DiscountType.PERCENTAGE,
            value = BigDecimal("20"),
            scope = DiscountScope.ORDER,
            conditions = null,
            active = true,
            requiresAuthorization = true
        )
    )

    AvoqadoTheme {
        DiscountsSubNavScreen(
            availableDiscounts = mockDiscounts,
            isLoading = false,
            hasItems = true,
            onSelectDiscount = {},
            onOpenManualDiscount = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun CouponsSubNavPreview() {
    AvoqadoTheme {
        CouponsSubNavScreen(
            validationState = CouponValidationState.Idle,
            hasItems = true,
            appliedDiscounts = listOf(createMockOrderDiscount()),
            onApplyCoupon = {},
            onRemoveDiscount = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CortesiaDialogPreview() {
    AvoqadoTheme {
        CortesiaDialog(
            orderSubtotal = BigDecimal("348.00"),
            onDismiss = {},
            onConfirm = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VoidItemsDialogPreview() {
    AvoqadoTheme {
        VoidItemsDialog(
            items = listOf(
                createMockOrderItem("1", "Hamburguesa Clasica", 2, BigDecimal("150.00")),
                createMockOrderItem("2", "Papas Fritas", 1, BigDecimal("60.00"))
            ),
            onDismiss = {},
            onConfirm = { _, _ -> }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ManualDiscountDialogPreview() {
    AvoqadoTheme {
        ManualDiscountDialog(
            onDismiss = {},
            onApply = { _, _, _ -> }
        )
    }
}

// ============================================================
// PREVIEW HELPERS
// ============================================================

private fun createMockOrderItem(
    id: String,
    name: String,
    quantity: Int,
    unitPrice: BigDecimal
): OrderItem = OrderItem(
    id = id,
    orderId = "order1",
    productId = "prod$id",
    productName = name,
    productSku = "SKU$id",
    quantity = quantity,
    unitPrice = unitPrice,
    totalPrice = unitPrice.multiply(BigDecimal(quantity)),
    modifiers = emptyList(),
    notes = null,
    kitchenStatus = KitchenStatus.PENDING,
    createdAt = java.time.Instant.now(),
    sentToKitchenAt = null
)

private fun createMockOrder(): Order = Order(
    id = "1",
    orderNumber = "001",
    venueId = "venue1",
    tableId = "table1",
    tableName = "Mesa 1",
    covers = 2,
    waiterId = "waiter1",
    waiterName = "Juan",
    status = OrderStatus.OPEN,
    kitchenStatus = KitchenStatus.PENDING,
    paymentStatus = PaymentStatus.PENDING,
    orderType = OrderType.DINE_IN,
    items = listOf(
        createMockOrderItem("item1", "Hamburguesa Clasica", 2, BigDecimal("150.00"))
    ),
    subtotal = BigDecimal("300.00"),
    tax = BigDecimal("48.00"),
    total = BigDecimal("348.00"),
    notes = null,
    createdAt = java.time.Instant.now(),
    updatedAt = java.time.Instant.now(),
    version = 1
)

private fun createMockOrderDiscount(): OrderDiscount = OrderDiscount(
    id = "od1",
    orderId = "1",
    discountId = "discount1",
    couponCodeId = null,
    name = "Happy Hour",
    type = DiscountType.PERCENTAGE,
    value = BigDecimal("15"),
    amount = BigDecimal("45.00"),
    reason = null,
    appliedBy = "staff1",
    appliedAt = java.time.Instant.now()
)
