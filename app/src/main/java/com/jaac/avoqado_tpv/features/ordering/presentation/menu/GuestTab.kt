package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerGroup
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderCustomer
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import java.math.BigDecimal

/**
 * GuestTab - Customer lookup and guest information management
 *
 * **Multi-Customer Support:** Orders can have multiple customers for visit/loyalty tracking.
 * Uses Toast/Square pattern: Modal dialog for search, chips for selected customers.
 *
 * ## Layout
 * ```
 * ┌─────────────────────────────────────┐
 * │ Clientes de la Orden                 │
 * ├─────────────────────────────────────┤
 * │ [🔍 Buscar y Agregar Cliente]       │ ← Opens search modal
 * ├─────────────────────────────────────┤
 * │ ┌─────────────────────────────────┐ │
 * │ │ 👤 Juan ⭐ VIP  1,250pts  [×]   │ │ ← Customer chip (removable)
 * │ └─────────────────────────────────┘ │
 * │ ┌─────────────────────────────────┐ │
 * │ │ 👤 María    500pts        [×]   │ │ ← Another chip
 * │ └─────────────────────────────────┘ │
 * ├─────────────────────────────────────┤
 * │ ➕ Crear Nuevo Cliente              │ ← Expandable form
 * │ ┌─────────────────────────────────┐ │
 * │ │ Nombre: [________________]      │ │
 * │ │ Teléfono: [______________]      │ │
 * │ │ Email: [_________________]      │ │
 * │ │ [Crear y Agregar]               │ │
 * │ └─────────────────────────────────┘ │
 * └─────────────────────────────────────┘
 * ```
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuestTab(
    order: Order,
    // Multi-customer support (NEW)
    orderCustomers: List<OrderCustomer> = emptyList(),
    isAddingCustomer: Boolean = false,
    // 🎁 Loyalty program status (Toast/Square pattern)
    loyaltyActive: Boolean = false,
    // Search state
    searchState: CustomerSearchState = CustomerSearchState.Idle,
    recentCustomers: List<Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    // Callbacks
    onSearchCustomer: (String) -> Unit = {},
    onSelectCustomer: (Customer) -> Unit = {},  // Immediately adds customer (no extra save step)
    onRemoveCustomer: (customerId: String) -> Unit = {},
    onCreateAndAddCustomer: (firstName: String?, phone: String?, email: String?) -> Unit = { _, _, _ -> },
    onLoadRecentCustomers: () -> Unit = {},
    onSaveGuestInfo: (covers: Int?, customerName: String?, customerPhone: String?, specialRequests: String?) -> Unit,
    modifier: Modifier = Modifier,
    // @DEPRECATED: Use orderCustomers instead
    selectedCustomer: Customer? = null,
    onClearCustomer: () -> Unit = {}
) {
    // Form state - initialize with order data
    var covers by remember(order.id) { mutableIntStateOf(order.covers) }
    var customerName by remember(order.id) { mutableStateOf(order.customerName ?: "") }
    var customerPhone by remember(order.id) { mutableStateOf(order.customerPhone ?: "") }
    var specialRequests by remember(order.id) { mutableStateOf(order.specialRequests ?: "") }

    // Modal and form expansion state
    var showSearchModal by remember { mutableStateOf(false) }
    var isCreateFormExpanded by remember { mutableStateOf(false) }

    // Get primary customer for form auto-fill
    val primaryCustomer = orderCustomers.firstOrNull { it.isPrimary }?.customer

    // Update form when primary customer changes
    LaunchedEffect(primaryCustomer) {
        primaryCustomer?.let { customer ->
            customerName = customer.displayName
            customerPhone = customer.phone ?: ""
        }
    }

    // Show search modal
    if (showSearchModal) {
        CustomerSearchModal(
            searchState = searchState,
            recentCustomers = recentCustomers,
            isLoadingRecent = isLoadingRecentCustomers,
            onSearch = onSearchCustomer,
            onSelectCustomer = { customer ->
                onSelectCustomer(customer)  // Immediately adds - no extra save
                showSearchModal = false
            },
            onDismiss = { showSearchModal = false },
            loyaltyActive = loyaltyActive
        )
    }

    // PAX A910S: 720x1280 @ xhdpi (2x) = 360x640dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Space3, vertical = Spacing.Space2),
        verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
    ) {
        // ─── SECTION 1: Customer Lookup ──────────────────
        Text(
            text = "CLIENTES",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp
        )

        // Search bar — tappable surface that looks like a text field
        Surface(
            onClick = {
                if (!isAddingCustomer) {
                    onLoadRecentCustomers()
                    showSearchModal = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Spacing.Space2),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.Space3, vertical = Spacing.Space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                if (isAddingCustomer) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Size.IconSmall),
                        strokeWidth = 2.dp
                    )
                    Text(
                        "Agregando...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(Size.IconSmall),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Buscar por nombre, teléfono o email...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Customer chips
        if (orderCustomers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space1),
                verticalArrangement = Arrangement.spacedBy(Spacing.Space1)
            ) {
                orderCustomers.forEach { orderCustomer ->
                    OrderCustomerChip(
                        orderCustomer = orderCustomer,
                        onRemove = { onRemoveCustomer(orderCustomer.customerId) },
                        isRemoving = isAddingCustomer,
                        loyaltyActive = loyaltyActive
                    )
                }
            }
        }

        // "Crear nuevo" — inline text link
        InlineCreateCustomerForm(
            isExpanded = isCreateFormExpanded,
            onExpandToggle = { isCreateFormExpanded = !isCreateFormExpanded },
            onCreate = { firstName, phone, email ->
                onCreateAndAddCustomer(firstName, phone, email)
                isCreateFormExpanded = false
            },
            isLoading = isAddingCustomer
        )

        // ─── SECTION 2: Guest Info Form ──────────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Size.CardCornerRadius),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.Space3),
                verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                // Section label inside card
                Text(
                    text = when (order.orderType) {
                        OrderType.DINE_IN -> "DATOS DEL CLIENTE"
                        OrderType.TAKEOUT -> "DATOS DE CONTACTO"
                        OrderType.DELIVERY -> "DATOS DE ENTREGA"
                        OrderType.PICKUP -> "DATOS PARA RECOGER"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )

                when (order.orderType) {
                    OrderType.DINE_IN -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
                        ) {
                            OutlinedTextField(
                                value = covers.toString(),
                                onValueChange = { newValue ->
                                    newValue.toIntOrNull()?.let { covers = it.coerceIn(1, 20) }
                                },
                                label = { Text("Comensales") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                modifier = Modifier.width(90.dp),
                                singleLine = true,
                                shape = PillFieldShape,
                                colors = compactFieldColors()
                            )

                            OutlinedTextField(
                                value = customerName,
                                onValueChange = { customerName = it },
                                label = { Text("Nombre (Opcional)") },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = PillFieldShape,
                                colors = compactFieldColors()
                            )
                        }

                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Alergias / Restricciones") },
                            placeholder = { Text("Sin nueces, sin gluten...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            shape = PillFieldShape,
                            colors = compactFieldColors()
                        )
                    }

                    OrderType.TAKEOUT, OrderType.DELIVERY, OrderType.PICKUP -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
                        ) {
                            OutlinedTextField(
                                value = customerName,
                                onValueChange = { customerName = it },
                                label = { Text("Nombre *") },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = customerName.isBlank(),
                                shape = PillFieldShape,
                                colors = compactFieldColors()
                            )

                            OutlinedTextField(
                                value = customerPhone,
                                onValueChange = { customerPhone = it },
                                label = { Text("Teléfono *") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone
                                ),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                isError = customerPhone.isBlank(),
                                shape = PillFieldShape,
                                colors = compactFieldColors()
                            )
                        }

                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Instrucciones Especiales") },
                            placeholder = { Text("Extra salsa, sin cebolla...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            shape = PillFieldShape,
                            colors = compactFieldColors()
                        )
                    }
                }
            }
        }

        // Save button — Avoqado green accent
        Button(
            onClick = {
                val coversValue = if (order.orderType == OrderType.DINE_IN) covers else null
                val phoneValue = if (order.orderType != OrderType.DINE_IN) {
                    customerPhone.ifBlank { null }
                } else null

                onSaveGuestInfo(
                    coversValue,
                    customerName.ifBlank { null },
                    phoneValue,
                    specialRequests.ifBlank { null }
                )
            },
            enabled = when (order.orderType) {
                OrderType.DINE_IN -> true
                OrderType.TAKEOUT, OrderType.DELIVERY, OrderType.PICKUP -> {
                    customerName.isNotBlank() && customerPhone.isNotBlank()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(Size.ButtonHeight),
            shape = PillFieldShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                "Guardar",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Pill-shaped field shape for all inputs */
private val PillFieldShape = RoundedCornerShape(50)

/** Field colors with visible border */
@Composable
private fun compactFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    focusedBorderColor = MaterialTheme.colorScheme.primary
)

// ==========================================
// ORDER CUSTOMER CHIP (Multi-Customer)
// ==========================================

/**
 * Chip displaying a customer associated with the order.
 * Shows name, VIP badge, points, and remove button.
 */
@Composable
private fun OrderCustomerChip(
    orderCustomer: OrderCustomer,
    onRemove: () -> Unit,
    isRemoving: Boolean = false,
    loyaltyActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val customer = orderCustomer.customer

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (orderCustomer.isPrimary)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(Spacing.Space2)
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.Space2, top = Spacing.Space1, bottom = Spacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Space1)
        ) {
            // Avatar — 22dp ultra-compact
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.shortName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = customer.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (customer.isVip) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "VIP",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(12.dp)
                )
            }
            if (loyaltyActive) {
                Text(
                    text = customer.formattedLoyaltyPoints,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Remove — compact 28dp
            IconButton(
                onClick = onRemove,
                enabled = !isRemoving,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Quitar",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==========================================
// INLINE CREATE CUSTOMER FORM
// ==========================================

/**
 * Expandable inline form for creating a new customer.
 * At least one field (firstName, phone, or email) is required.
 */
@Composable
private fun InlineCreateCustomerForm(
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onCreate: (firstName: String?, phone: String?, email: String?) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    var firstName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // Reset form when collapsed
    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            firstName = ""
            phone = ""
            email = ""
        }
    }

    val hasAtLeastOneField = firstName.isNotBlank() || phone.isNotBlank() || email.isNotBlank()

    // Compact expandable — Surface card for visual grouping
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Size.CardCornerRadius),
        color = if (isExpanded) MaterialTheme.colorScheme.surface
        else Color.Transparent,
        tonalElevation = if (isExpanded) 1.dp else 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row — clickable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle)
                    .padding(horizontal = Spacing.Space3, vertical = Spacing.Space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Space1)
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Size.IconSmall)
                    )
                    Text(
                        text = "Crear Nuevo Cliente",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Size.IconSmall)
                )
            }

            // Expandable form
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Space3)
                        .padding(bottom = Spacing.Space3),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )

                    // Name + Phone side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
                    ) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            label = { Text("Nombre") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = PillFieldShape,
                            colors = compactFieldColors()
                        )

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Teléfono") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = PillFieldShape,
                            colors = compactFieldColors()
                        )
                    }

                    // Email + Create button side by side
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !isLoading,
                            shape = PillFieldShape,
                            colors = compactFieldColors()
                        )

                        Button(
                            onClick = {
                                onCreate(
                                    firstName.trim().ifBlank { null },
                                    phone.trim().ifBlank { null },
                                    email.trim().ifBlank { null }
                                )
                            },
                            enabled = hasAtLeastOneField && !isLoading,
                            modifier = Modifier.height(Size.ButtonHeightSmall),
                            shape = PillFieldShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Size.IconSmall),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Crear", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SELECTED CUSTOMER CARD
// ==========================================

@Composable
private fun SelectedCustomerCard(
    customer: Customer,
    onClear: () -> Unit,
    loyaltyActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(Size.CardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Customer avatar — 40dp for compact card
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.shortName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(Spacing.Space3))

            // Customer info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (customer.isVip) {
                        Spacer(modifier = Modifier.width(Spacing.Space1))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(Size.IconSmall)
                        )
                    }
                }

                // Group badge
                customer.customerGroup?.let { group ->
                    GroupBadge(group = group)
                }

                // Stats
                Text(
                    text = if (loyaltyActive)
                        "${customer.formattedLoyaltyPoints} • ${customer.totalVisits} visitas • ${customer.formattedTotalSpent}"
                    else
                        "${customer.totalVisits} visitas • ${customer.formattedTotalSpent}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }

            // Remove button
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(Size.ButtonHeightSmall)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Quitar cliente",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(Size.IconMedium)
                )
            }
        }
    }
}

// ==========================================
// CUSTOMER SEARCH RESULTS
// ==========================================

@Composable
private fun CustomerSearchResults(
    customers: List<Customer>,
    onSelect: (Customer) -> Unit,
    loyaltyActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
    ) {
        customers.take(5).forEach { customer ->
            CustomerResultCard(
                customer = customer,
                onClick = { onSelect(customer) },
                loyaltyActive = loyaltyActive
            )
        }
    }
}

@Composable
private fun CustomerResultCard(
    customer: Customer,
    onClick: () -> Unit,
    loyaltyActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(Size.CardCornerRadius)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar — 36dp for result cards
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Size.IconMedium)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.Space3))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (customer.isVip) {
                        Spacer(modifier = Modifier.width(Spacing.Space1))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
                ) {
                    customer.phone?.let { phone ->
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    customer.customerGroup?.let { group ->
                        GroupBadge(group = group, small = true)
                    }
                }
            }

            // Loyalty points / Stats
            Column(horizontalAlignment = Alignment.End) {
                if (loyaltyActive) {
                    Text(
                        text = customer.formattedLoyaltyPoints,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${customer.totalVisits} visitas",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==========================================
// NO RESULTS CARD
// ==========================================

@Composable
private fun NoResultsCard(
    searchQuery: String,
    onCreateCustomer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(Size.CardCornerRadius)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Space4),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No se encontraron clientes",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "para \"$searchQuery\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.Space3))

            OutlinedButton(onClick = onCreateCustomer) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(Size.IconSmall)
                )
                Spacer(modifier = Modifier.width(Spacing.Space2))
                Text("Crear Nuevo Cliente")
            }
        }
    }
}

// ==========================================
// QUICK CREATE CUSTOMER CARD
// ==========================================

@Composable
private fun QuickCreateCustomerCard(
    initialPhone: String?,
    initialName: String?,
    onCancel: () -> Unit,
    onCreate: (firstName: String?, lastName: String?, phone: String?, email: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var firstName by remember { mutableStateOf(initialName ?: "") }
    var phone by remember { mutableStateOf(initialPhone ?: "") }
    var email by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(Size.CardCornerRadius)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Space3),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
        ) {
            Text(
                text = "Crear Nuevo Cliente",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Name + Phone in a row for compact layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text("Nombre") },
                    placeholder = { Text("Juan Pérez") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words
                    ),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Teléfono *") },
                    placeholder = { Text("5512345678") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = phone.isBlank() && email.isBlank()
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email (opcional)") },
                placeholder = { Text("cliente@email.com") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Space2)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(Size.ButtonHeightSmall)
                ) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        onCreate(
                            firstName.ifBlank { null },
                            null,
                            phone.ifBlank { null },
                            email.ifBlank { null }
                        )
                    },
                    enabled = phone.isNotBlank() || email.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(Size.ButtonHeightSmall)
                ) {
                    Text("Crear")
                }
            }
        }
    }
}

// ==========================================
// GROUP BADGE
// ==========================================

@Composable
private fun GroupBadge(
    group: CustomerGroup,
    small: Boolean = false,
    modifier: Modifier = Modifier
) {
    val backgroundColor = try {
        Color(android.graphics.Color.parseColor(group.badgeColor))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primaryContainer
    }

    val badgeShape = RoundedCornerShape(Spacing.Space1)

    Box(
        modifier = modifier
            .clip(badgeShape)
            .background(backgroundColor.copy(alpha = 0.2f))
            .border(Size.BorderThin, backgroundColor.copy(alpha = 0.5f), badgeShape)
            .padding(
                horizontal = if (small) Spacing.Space1 else Spacing.Space2,
                vertical = if (small) 2.dp else Spacing.Space1
            )
    ) {
        Text(
            text = "${group.emoji} ${group.name}",
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = backgroundColor
        )
    }
}

// ==========================================
// PREVIEWS
// ==========================================

@Preview(showBackground = true)
@Composable
private fun GuestTabPreview() {
    AvoqadoTheme {
        Text(
            text = "GuestTab Preview\n(Requires mock Order data)",
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectedCustomerCardPreview() {
    AvoqadoTheme {
        SelectedCustomerCard(
            customer = Customer(
                id = "cust_1",
                firstName = "Juan",
                lastName = "Pérez",
                email = "juan@email.com",
                phone = "5512345678",
                loyaltyPoints = 1250,
                totalVisits = 15,
                totalSpent = BigDecimal("4500.00"),
                customerGroup = CustomerGroup(
                    id = "grp_1",
                    name = "VIP",
                    color = "#FFD700"
                )
            ),
            onClear = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CustomerResultCardPreview() {
    AvoqadoTheme {
        CustomerResultCard(
            customer = Customer(
                id = "cust_2",
                firstName = "María",
                lastName = "García",
                email = null,
                phone = "5598765432",
                loyaltyPoints = 500,
                totalVisits = 8,
                totalSpent = BigDecimal("2100.00"),
                customerGroup = CustomerGroup(
                    id = "grp_2",
                    name = "Empleado",
                    color = "#4CAF50"
                )
            ),
            onClick = {}
        )
    }
}
