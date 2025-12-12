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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerGroup
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderCustomer
import com.jaac.avoqado_tpv.features.ordering.domain.OrderType
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.LocalDateTime

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ==========================================
        // CUSTOMER SECTION (Multi-Customer)
        // ==========================================
        Text(
            text = "Clientes de la Orden",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Click en un cliente para agregarlo. El primer cliente agregado recibe los puntos de lealtad.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Search button (opens modal)
        OutlinedButton(
            onClick = {
                onLoadRecentCustomers()
                showSearchModal = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isAddingCustomer
        ) {
            if (isAddingCustomer) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Agregando...")
            } else {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Buscar y Agregar Cliente")
            }
        }

        // Customer chips (multi-customer)
        if (orderCustomers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

        // Inline create customer form (expandable)
        InlineCreateCustomerForm(
            isExpanded = isCreateFormExpanded,
            onExpandToggle = { isCreateFormExpanded = !isCreateFormExpanded },
            onCreate = { firstName, phone, email ->
                onCreateAndAddCustomer(firstName, phone, email)
                isCreateFormExpanded = false
            },
            isLoading = isAddingCustomer
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ==========================================
        // GUEST INFORMATION SECTION (existing form)
        // ==========================================
        Text(
            text = "Información del Cliente",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = when (order.orderType) {
                OrderType.DINE_IN -> "Actualiza los datos del cliente y restricciones alimentarias"
                OrderType.TAKEOUT -> "Información de contacto para orden para llevar"
                OrderType.DELIVERY -> "Información de contacto y entrega"
                OrderType.PICKUP -> "Información de contacto para recoger orden"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Form card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (order.orderType) {
                    OrderType.DINE_IN -> {
                        // Covers field
                        OutlinedTextField(
                            value = covers.toString(),
                            onValueChange = { newValue ->
                                newValue.toIntOrNull()?.let { covers = it.coerceIn(1, 20) }
                            },
                            label = { Text("Número de Comensales") },
                            placeholder = { Text("2") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Customer name (auto-filled from selected customer)
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text("Nombre del Cliente (Opcional)") },
                            placeholder = { Text("Juan Pérez") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        // Special requests
                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Alergias / Restricciones Alimentarias") },
                            placeholder = { Text("Sin nueces, sin gluten...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }

                    OrderType.TAKEOUT, OrderType.DELIVERY, OrderType.PICKUP -> {
                        // Customer name (required)
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text("Nombre del Cliente *") },
                            placeholder = { Text("Juan Pérez") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customerName.isBlank()
                        )

                        // Phone (required)
                        OutlinedTextField(
                            value = customerPhone,
                            onValueChange = { customerPhone = it },
                            label = { Text("Teléfono *") },
                            placeholder = { Text("5512345678") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = customerPhone.isBlank()
                        )

                        // Special requests
                        OutlinedTextField(
                            value = specialRequests,
                            onValueChange = { specialRequests = it },
                            label = { Text("Instrucciones Especiales") },
                            placeholder = { Text("Extra salsa, sin cebolla...") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
            }
        }

        // Save button
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar Información")
        }
    }
}

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
    loyaltyActive: Boolean = false,  // Toast/Square pattern
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
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.shortName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Customer info
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (customer.isVip) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (orderCustomer.isPrimary) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "★",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // 🎁 Toast/Square pattern: Only show loyalty points if program is active
                if (loyaltyActive) {
                    Text(
                        text = customer.formattedLoyaltyPoints,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Remove button
            IconButton(
                onClick = onRemove,
                enabled = !isRemoving,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Quitar cliente",
                    modifier = Modifier.size(16.dp),
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

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Header (always visible - toggle to expand)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Crear Nuevo Cliente",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Ingresa al menos nombre, teléfono o email",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("Nombre") },
                        placeholder = { Text("Juan Pérez") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading
                    )

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Teléfono") },
                        placeholder = { Text("5512345678") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        placeholder = { Text("cliente@email.com") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isLoading
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Creando...")
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Crear y Agregar")
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
    loyaltyActive: Boolean = false,  // Toast/Square pattern
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Customer avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.shortName.take(2).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Customer info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (customer.isVip) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Group badge
                customer.customerGroup?.let { group ->
                    GroupBadge(group = group)
                }

                // Stats - Toast/Square pattern: Only show loyalty points if active
                Text(
                    text = if (loyaltyActive)
                        "${customer.formattedLoyaltyPoints} • ${customer.totalVisits} visitas • ${customer.formattedTotalSpent}"
                    else
                        "${customer.totalVisits} visitas • ${customer.formattedTotalSpent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }

            // Remove button
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Quitar cliente",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
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
    loyaltyActive: Boolean = false,  // Toast/Square pattern
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
    loyaltyActive: Boolean = false,  // Toast/Square pattern
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (customer.isVip) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "VIP",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    customer.phone?.let { phone ->
                        Text(
                            text = phone,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    customer.customerGroup?.let { group ->
                        GroupBadge(group = group, small = true)
                    }
                }
            }

            // Loyalty points / Stats - Toast/Square pattern
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
                    style = MaterialTheme.typography.bodySmall,
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
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No se encontraron clientes",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "para \"$searchQuery\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onCreateCustomer) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
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
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Crear Nuevo Cliente",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text("Nombre") },
                placeholder = { Text("Juan Pérez") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words
                ),
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = phone.isBlank() && email.isBlank()
            )

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

            Text(
                text = "* Se requiere teléfono o email",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancelar")
                }
                Button(
                    onClick = {
                        onCreate(
                            firstName.ifBlank { null },
                            null, // lastName
                            phone.ifBlank { null },
                            email.ifBlank { null }
                        )
                    },
                    enabled = phone.isNotBlank() || email.isNotBlank(),
                    modifier = Modifier.weight(1f)
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

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor.copy(alpha = 0.2f))
            .border(1.dp, backgroundColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = if (small) 4.dp else 8.dp, vertical = if (small) 2.dp else 4.dp)
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
