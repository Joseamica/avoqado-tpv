package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Order
import com.jaac.avoqado_tpv.features.ordering.domain.OrderStatus
import com.jaac.avoqado_tpv.features.ordering.presentation.components.OrderCard

/**
 * OrderListScreen - List of all orders with filters
 *
 * Features:
 * - Filter by status (ALL, OPEN, IN_PROGRESS, COMPLETED)
 * - Real-time order updates via ViewModel
 * - Tap order to view details (navigate to MenuScreen)
 * - Empty state when no orders
 *
 * Design:
 * - Filter chips at top (horizontal scroll)
 * - LazyColumn for performance
 * - Responsive spacing
 *
 * @param onNavigateBack Navigate back callback
 * @param onOrderClick Navigate to order details (MenuScreen)
 * @param modifier Modifier for customization
 * @param viewModel OrderListViewModel
 */
@Composable
fun OrderListScreen(
    onNavigateBack: () -> Unit,
    onOrderClick: (Order) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Órdenes",
                onNavigationClick = onNavigateBack
            )
        },
        modifier = modifier
    ) { paddingValues ->
        OrderListContent(
            state = state,
            selectedFilter = selectedFilter,
            onFilterChange = { viewModel.selectFilter(it) },
            onOrderClick = onOrderClick,
            modifier = Modifier.padding(paddingValues)
        )
    }
}

/**
 * OrderListContent - Main content for order list
 */
@Composable
private fun OrderListContent(
    state: OrderListState,
    selectedFilter: OrderStatusFilter,
    onFilterChange: (OrderStatusFilter) -> Unit,
    onOrderClick: (Order) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(sizes.paddingScreen)
    ) {
        // Filter chips
        FilterChipsRow(
            selectedFilter = selectedFilter,
            onFilterChange = onFilterChange
        )

        Spacer(modifier = Modifier.height(sizes.spacingMedium))

        // Order list
        when (state) {
            is OrderListState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is OrderListState.Success -> {
                if (state.orders.isEmpty()) {
                    EmptyState(filter = selectedFilter)
                } else {
                    OrderList(
                        orders = state.orders,
                        onOrderClick = onOrderClick
                    )
                }
            }

            is OrderListState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * FilterChipsRow - Horizontal scrollable filter chips
 */
@Composable
private fun FilterChipsRow(
    selectedFilter: OrderStatusFilter,
    onFilterChange: (OrderStatusFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OrderStatusFilter.values().forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                label = {
                    Text(
                        text = filter.label,
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                leadingIcon = if (selectedFilter == filter) {
                    {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null
                        )
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

/**
 * OrderList - Scrollable list of orders
 */
@Composable
private fun OrderList(
    orders: List<Order>,
    onOrderClick: (Order) -> Unit,
    modifier: Modifier = Modifier
) {
    val sizes = LocalResponsiveSizes.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(sizes.spacingMedium)
    ) {
        items(orders, key = { it.id }) { order ->
            OrderCard(
                order = order,
                onClick = { onOrderClick(order) }
            )
        }
    }
}

/**
 * EmptyState - Shown when no orders match filter
 */
@Composable
private fun EmptyState(
    filter: OrderStatusFilter,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when (filter) {
                    OrderStatusFilter.ALL -> "No hay órdenes"
                    OrderStatusFilter.OPEN -> "No hay órdenes abiertas"
                    OrderStatusFilter.IN_PROGRESS -> "No hay órdenes en cocina"
                    OrderStatusFilter.COMPLETED -> "No hay órdenes completadas"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Las órdenes aparecerán aquí",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// Filter Enum
// ============================================================================

/**
 * Order status filter options
 */
enum class OrderStatusFilter(val label: String) {
    ALL("Todas"),
    OPEN("Abiertas"),
    IN_PROGRESS("En Cocina"),
    COMPLETED("Completadas");

    /**
     * Check if order matches this filter
     */
    fun matches(order: Order): Boolean {
        return when (this) {
            ALL -> true
            OPEN -> order.status == OrderStatus.OPEN
            IN_PROGRESS -> order.status == OrderStatus.IN_PROGRESS
            COMPLETED -> order.status == OrderStatus.COMPLETED
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun OrderListScreenPreview() {
    AvoqadoTheme {
        OrderListContent(
            state = OrderListState.Success(emptyList()),
            selectedFilter = OrderStatusFilter.ALL,
            onFilterChange = {},
            onOrderClick = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun OrderListScreenLoadingPreview() {
    AvoqadoTheme {
        OrderListContent(
            state = OrderListState.Loading,
            selectedFilter = OrderStatusFilter.ALL,
            onFilterChange = {},
            onOrderClick = {}
        )
    }
}
