package com.jaac.avoqado_tpv.features.payment.presentation.split

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.payment.presentation.split.components.PersonCountSelector
import com.jaac.avoqado_tpv.features.payment.presentation.split.components.SelectableItemRow
import java.math.BigDecimal
import com.jaac.avoqado_tpv.core.util.rememberCurrencyFormat

/**
 * Split By Person Screen
 *
 * Allows user to split an order equally among N people.
 * User selects how many parts to pay for this transaction.
 *
 * Layout (Dark Theme):
 * ```
 * ┌─────────────────────────────────────────────────────┐
 * │  ←  Queda por pagar: $200.00                        │  TopBar
 * ├─────────────────────────────────────────────────────┤
 * │        ┌───┐                      ┌───┐             │
 * │        │ - │    4 partes          │ + │             │  Selector (if paidCount=0)
 * │        └───┘                      └───┘             │
 * ├─────────────────────────────────────────────────────┤
 * │                                                     │
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ○  Persona 1                   $50.00      │   │  Available
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ●  Persona 2                   $50.00      │   │  Selected
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │  ✓  Persona 3                   Pagado      │   │  Paid (disabled)
 * │  └─────────────────────────────────────────────┘   │
 * │                                                     │
 * ├─────────────────────────────────────────────────────┤
 * │  ┌─────────────────────────────────────────────┐   │
 * │  │        Pagar 1 parte • $50.00               │   │  Action button
 * │  └─────────────────────────────────────────────┘   │
 * └─────────────────────────────────────────────────────┘
 * ```
 *
 * @param onNavigateBack Go back to previous screen
 * @param onProceedToPayment Navigate to PaymentScreen with EQUALPARTS params
 * @param viewModel ViewModel injected by Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitByPersonScreen(
    onNavigateBack: () -> Unit,
    onProceedToPayment: (amount: BigDecimal, partySize: Int, payingFor: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SplitByPersonViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currencyFormatter = rememberCurrencyFormat()

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Queda por pagar: ${currencyFormatter.format(uiState.totalAmount)}",
                onNavigationClick = onNavigateBack
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState.isLoading -> {
                    AvoqadoFullScreenLoading(message = "Cargando...")
                }

                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Error desconocido",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                uiState.order != null -> {
                    SplitByPersonContent(
                        partySize = uiState.partySize,
                        canChangePartySize = uiState.canChangePartySize,
                        amountPerPerson = uiState.amountPerPerson,
                        availablePersons = uiState.availablePersons,
                        paidPersons = uiState.paidPersons,
                        selectedIndices = uiState.selectedIndices,
                        selectedAmount = uiState.selectedAmount,
                        selectedCount = uiState.selectedCount,
                        canProceed = uiState.canProceed,
                        onIncrementPartySize = viewModel::incrementPartySize,
                        onDecrementPartySize = viewModel::decrementPartySize,
                        onTogglePerson = viewModel::togglePerson,
                        onProceedToPayment = {
                            val params = viewModel.getPaymentParams()
                            onProceedToPayment(params.amount, params.partySize, params.payingFor)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Content composable for SplitByPersonScreen
 */
@Composable
private fun SplitByPersonContent(
    partySize: Int,
    canChangePartySize: Boolean,
    amountPerPerson: BigDecimal,
    availablePersons: List<PersonPayment>,
    paidPersons: List<PersonPayment>,
    selectedIndices: Set<Int>,
    selectedAmount: BigDecimal,
    selectedCount: Int,
    canProceed: Boolean,
    onIncrementPartySize: () -> Unit,
    onDecrementPartySize: () -> Unit,
    onTogglePerson: (Int) -> Unit,
    onProceedToPayment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormatter = rememberCurrencyFormat()

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Party size selector (only if no one has paid yet)
        if (canChangePartySize) {
            PersonCountSelector(
                count = partySize,
                onDecrement = onDecrementPartySize,
                onIncrement = onIncrementPartySize,
                enabled = true
            )
            HorizontalDivider()
        } else {
            // Show fixed party size info
            Text(
                text = "Dividido entre $partySize personas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            HorizontalDivider()
        }

        // Person list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Available persons (can be selected)
            if (availablePersons.isNotEmpty()) {
                item {
                    Text(
                        text = "Selecciona quién va a pagar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = availablePersons,
                    key = { it.index }
                ) { person ->
                    SelectableItemRow(
                        label = person.label,
                        trailingText = currencyFormatter.format(person.amount),
                        isSelected = person.index in selectedIndices,
                        isPaid = false,
                        onTap = { onTogglePerson(person.index) }
                    )
                }
            }

            // Paid persons (disabled)
            if (paidPersons.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Ya pagados",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(
                    items = paidPersons,
                    key = { it.index }
                ) { person ->
                    SelectableItemRow(
                        label = person.label,
                        trailingText = "Pagado",
                        isSelected = false,
                        isPaid = true,
                        onTap = { }
                    )
                }
            }
        }

        // Bottom action button
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            Button(
                onClick = onProceedToPayment,
                enabled = canProceed,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                val buttonText = if (selectedCount > 0) {
                    val partLabel = if (selectedCount == 1) "parte" else "partes"
                    "Pagar $selectedCount $partLabel • ${currencyFormatter.format(selectedAmount)}"
                } else {
                    "Selecciona personas"
                }
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ============================================================================
// Previews
// ============================================================================

@Preview(showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun SplitByPersonContentPreview() {
    AvoqadoTheme {
        val availablePersons = listOf(
            PersonPayment(1, "Persona 1", BigDecimal("50.00"), false),
            PersonPayment(2, "Persona 2", BigDecimal("50.00"), false),
        )
        val paidPersons = listOf(
            PersonPayment(3, "Persona 3", BigDecimal("50.00"), true),
            PersonPayment(4, "Persona 4", BigDecimal("50.00"), true),
        )

        SplitByPersonContent(
            partySize = 4,
            canChangePartySize = false,
            amountPerPerson = BigDecimal("50.00"),
            availablePersons = availablePersons,
            paidPersons = paidPersons,
            selectedIndices = setOf(2),
            selectedAmount = BigDecimal("50.00"),
            selectedCount = 1,
            canProceed = true,
            onIncrementPartySize = {},
            onDecrementPartySize = {},
            onTogglePerson = {},
            onProceedToPayment = {}
        )
    }
}

@Preview(showBackground = true, heightDp = 600, widthDp = 400)
@Composable
private fun SplitByPersonContentFreshPreview() {
    AvoqadoTheme {
        val availablePersons = listOf(
            PersonPayment(1, "Persona 1", BigDecimal("50.00"), false),
            PersonPayment(2, "Persona 2", BigDecimal("50.00"), false),
            PersonPayment(3, "Persona 3", BigDecimal("50.00"), false),
            PersonPayment(4, "Persona 4", BigDecimal("50.00"), false),
        )

        SplitByPersonContent(
            partySize = 4,
            canChangePartySize = true,
            amountPerPerson = BigDecimal("50.00"),
            availablePersons = availablePersons,
            paidPersons = emptyList(),
            selectedIndices = emptySet(),
            selectedAmount = BigDecimal.ZERO,
            selectedCount = 0,
            canProceed = false,
            onIncrementPartySize = {},
            onDecrementPartySize = {},
            onTogglePerson = {},
            onProceedToPayment = {}
        )
    }
}
