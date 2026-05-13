package com.jaac.avoqado_tpv.features.checkout.presentation.components.cart

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.checkout.presentation.CheckoutViewModel
import com.jaac.avoqado_tpv.features.ordering.domain.Customer

/**
 * Customer picker shown when the operator taps the "Agregar cliente" header
 * on the cart panel.
 *
 * Driven by [CheckoutViewModel] state: searches via
 * `CustomerRepository.searchCustomers` (or `getRecentCustomers` when the
 * query is empty). Tapping a result calls [onPick]; the parent typically
 * stores the customer on the cart and dismisses the sheet.
 *
 * This is the content for a `ModalBottomSheet` — the parent is responsible
 * for wrapping it and managing the sheet state.
 */
@Composable
fun CustomerSelectorSheet(
    viewModel: CheckoutViewModel,
    onPick: (Customer) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    hasSelectedCustomer: Boolean,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.customerSearchQuery.collectAsState()
    val results by viewModel.customerResults.collectAsState()
    val isSearching by viewModel.isSearchingCustomers.collectAsState()

    // Eagerly load recent customers when the sheet first appears.
    LaunchedEffect(Unit) {
        viewModel.loadRecentCustomers()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (hasSelectedCustomer) "Cambiar cliente" else "Agregar cliente",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (hasSelectedCustomer) {
                TextButton(onClick = {
                    onClear()
                    onDismiss()
                }) {
                    Text("Quitar")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateCustomerSearchQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar por nombre, teléfono o email") },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isSearching && results.isEmpty() -> LoadingRow()
            results.isEmpty() && query.isBlank() -> {
                EmptyRecentsState()
            }
            results.isEmpty() -> {
                NoMatchesState(query = query)
            }
            else -> {
                if (query.isBlank()) {
                    Text(
                        text = "Recientes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxHeight()) {
                    items(items = results, key = { it.id }) { customer ->
                        CustomerResultRow(
                            customer = customer,
                            onClick = {
                                onPick(customer)
                                onDismiss()
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomerResultRow(customer: Customer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle with initial
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val initial = customer.firstName?.firstOrNull()
                ?: customer.phone?.lastOrNull()
                ?: '?'
            Text(
                text = initial.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = customer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(customer.phone, customer.email).firstOrNull()
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (customer.loyaltyPoints > 0) {
            Text(
                text = customer.formattedLoyaltyPoints,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EmptyRecentsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sin clientes recientes. Empieza a escribir para buscar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoMatchesState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Sin resultados para “$query”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
