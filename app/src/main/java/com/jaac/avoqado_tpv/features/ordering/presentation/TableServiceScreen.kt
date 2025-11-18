package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.LocalResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import com.jaac.avoqado_tpv.features.ordering.domain.TableStatus
import java.math.BigDecimal

/**
 * Table Service Screen
 *
 * Displays floor plan with all tables and their current status.
 * Allows staff to select tables to start new orders or view existing ones.
 *
 * **Pattern:** Similar to Square POS table management
 *
 * @param modifier Modifier for customization
 * @param onNavigateBack Navigate back callback
 * @param onTableAssigned Navigate to order editor after table assignment
 * @param viewModel TableServiceViewModel
 */
@Composable
fun TableServiceScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onTableAssigned: (orderId: String) -> Unit = {},
    viewModel: TableServiceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Load tables on screen launch
    LaunchedEffect(Unit) {
        viewModel.loadTables()
    }

    TableServiceScreenContent(
        modifier = modifier,
        state = state,
        onNavigateBack = onNavigateBack,
        onTableClick = { table ->
            // For now, just assign table with default 2 covers
            // TODO: Show modal to input covers count
            viewModel.assignTable(table, covers = 2, onSuccess = onTableAssigned)
        },
        onRetry = { viewModel.retry() }
    )
}

/**
 * Table Service Screen Content
 *
 * Stateless UI component that can be previewed without ViewModel.
 */
@Composable
private fun TableServiceScreenContent(
    modifier: Modifier = Modifier,
    state: TableServiceState,
    onNavigateBack: () -> Unit,
    onTableClick: (Table) -> Unit,
    onRetry: () -> Unit
) {
    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Servicio de Mesa",
                subtitle = when (state) {
                    is TableServiceState.Success -> {
                        "${state.tables.count { it.isAvailable }} disponibles · ${state.tables.count { it.isOccupied }} ocupadas"
                    }
                    else -> null
                },
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when (state) {
            is TableServiceState.Idle -> {
                // Show nothing (loading will appear immediately)
            }

            is TableServiceState.Loading -> {
                AvoqadoLoadingOverlay(message = "Cargando mesas...")
            }

            is TableServiceState.Success -> {
                if (state.tables.isEmpty()) {
                    EmptyTablesView(modifier = Modifier.padding(paddingValues))
                } else {
                    TablesGridView(
                        tables = state.tables,
                        onTableClick = onTableClick,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            is TableServiceState.AssigningTable -> {
                AvoqadoLoadingOverlay(
                    message = "Asignando Mesa ${state.table.number}...\n${state.covers} comensales"
                )
            }

            is TableServiceState.Error -> {
                ErrorView(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}

/**
 * Tables Grid View
 *
 * Displays tables in a responsive grid layout.
 * Shows table number, capacity, status, and current order info.
 */
@Composable
private fun TablesGridView(
    tables: List<Table>,
    onTableClick: (Table) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(tables) { table ->
            TableCard(
                table = table,
                onClick = { onTableClick(table) }
            )
        }
    }
}

/**
 * Table Card
 *
 * Individual table card showing status and info.
 * Color-coded by status (green = available, orange = occupied).
 */
@Composable
private fun TableCard(
    table: Table,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Color based on status
    val containerColor = when (table.status) {
        TableStatus.AVAILABLE -> MaterialTheme.colorScheme.surface
        TableStatus.OCCUPIED -> MaterialTheme.colorScheme.errorContainer
        TableStatus.RESERVED -> MaterialTheme.colorScheme.tertiaryContainer
    }

    val contentColor = when (table.status) {
        TableStatus.AVAILABLE -> MaterialTheme.colorScheme.onSurface
        TableStatus.OCCUPIED -> MaterialTheme.colorScheme.onErrorContainer
        TableStatus.RESERVED -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    val borderColor = when (table.status) {
        TableStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
        TableStatus.OCCUPIED -> MaterialTheme.colorScheme.error
        TableStatus.RESERVED -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        onClick = onClick,
        modifier = modifier.height(140.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(2.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Table number and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mesa ${table.number}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Icon(
                    imageVector = when (table.status) {
                        TableStatus.AVAILABLE -> Icons.Default.TableRestaurant
                        TableStatus.OCCUPIED -> Icons.Default.Person
                        TableStatus.RESERVED -> Icons.Default.Check
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = borderColor
                )
            }

            // Capacity
            Text(
                text = "${table.capacity} personas",
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f)
            )

            // Order info (if occupied)
            if (table.isOccupied && table.currentOrder != null) {
                Text(
                    text = "$${table.currentOrder.total}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${table.currentOrder.itemCount} items · ${table.currentOrder.covers ?: 0} comensales",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    text = when (table.status) {
                        TableStatus.AVAILABLE -> "Disponible"
                        TableStatus.RESERVED -> "Reservada"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = borderColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Empty Tables View
 *
 * Shown when venue has no tables configured.
 */
@Composable
private fun EmptyTablesView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TableRestaurant,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No hay mesas configuradas",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Configure las mesas desde el panel de administración web.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Error View
 *
 * Shown when table loading fails.
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(onClick = onRetry) {
                Text("Reintentar")
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun TableServiceScreenPreview() {
    AvoqadoTheme {
        val sampleTables = listOf(
            Table(
                id = "1",
                number = "1",
                capacity = 4,
                positionX = null,
                positionY = null,
                shape = TableShape.ROUND,
                rotation = 0,
                status = TableStatus.AVAILABLE,
                currentOrder = null
            ),
            Table(
                id = "2",
                number = "2",
                capacity = 2,
                positionX = null,
                positionY = null,
                shape = TableShape.SQUARE,
                rotation = 0,
                status = TableStatus.OCCUPIED,
                currentOrder = com.jaac.avoqado_tpv.features.ordering.domain.CurrentOrderInfo(
                    id = "order_1",
                    orderNumber = "ORD-123",
                    covers = 2,
                    total = BigDecimal("125.50"),
                    itemCount = 5,
                    waiter = com.jaac.avoqado_tpv.features.ordering.domain.WaiterInfo(
                        id = "staff_1",
                        name = "Juan Pérez"
                    ),
                    createdAt = "2025-01-15T10:30:00Z"
                )
            ),
            Table(
                id = "3",
                number = "3",
                capacity = 6,
                positionX = null,
                positionY = null,
                shape = TableShape.RECTANGLE,
                rotation = 0,
                status = TableStatus.RESERVED,
                currentOrder = null
            )
        )

        TableServiceScreenContent(
            state = TableServiceState.Success(tables = sampleTables),
            onNavigateBack = {},
            onTableClick = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024)
@Composable
private fun TableServiceScreenEmptyPreview() {
    AvoqadoTheme {
        TableServiceScreenContent(
            state = TableServiceState.Success(tables = emptyList()),
            onNavigateBack = {},
            onTableClick = {},
            onRetry = {}
        )
    }
}
