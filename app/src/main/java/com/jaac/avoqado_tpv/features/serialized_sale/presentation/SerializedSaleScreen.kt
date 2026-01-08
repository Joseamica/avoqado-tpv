package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen

/**
 * SerializedSaleScreen (Vender flow)
 *
 * Flow:
 * 1. Scan barcode
 * 2. Show item info (or category selector if not registered)
 * 3. Enter/confirm price
 * 4. Confirm sale → Navigate to payment
 *
 * @param onNavigateBack Navigation callback to go back
 * @param onNavigateToPayment Navigation callback with order ID for payment
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerializedSaleScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (orderId: String, orderTotal: String) -> Unit,
    viewModel: SerializedSaleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val labels = viewModel.labels

    // Use configured labels or defaults
    val itemLabel = labels?.item ?: "Artículo"
    val barcodeLabel = labels?.barcode ?: "Código"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vender $itemLabel") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isScanning -> {
                    // Show barcode scanner
                    BarcodeScannerScreen(
                        onBarcodeScanned = { barcode, _ ->
                            viewModel.onBarcodeScanned(barcode)
                        },
                        onClose = onNavigateBack,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                uiState.isLoading -> {
                    // Loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Procesando...")
                        }
                    }
                }
                else -> {
                    // Show scan result and sale form
                    SaleFormContent(
                        uiState = uiState,
                        itemLabel = itemLabel,
                        barcodeLabel = barcodeLabel,
                        onPriceChanged = viewModel::onPriceChanged,
                        onCategorySelected = viewModel::onCategorySelected,
                        onConfirmSale = {
                            viewModel.onConfirmSale { result ->
                                onNavigateToPayment(result.orderId, result.total.toPlainString())
                            }
                        },
                        onScanAnother = viewModel::returnToScanner,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Error Snackbar
            if (uiState.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(uiState.error!!)
                }
            }
        }
    }
}

@Composable
private fun SaleFormContent(
    uiState: com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedSaleUiState,
    itemLabel: String,
    barcodeLabel: String,
    onPriceChanged: (String) -> Unit,
    onCategorySelected: (CategoryWithStock) -> Unit,
    onConfirmSale: () -> Unit,
    onScanAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .padding(16.dp)
    ) {
        // Scan result card
        ScanResultCard(
            scanResult = uiState.scanResult,
            serialNumber = uiState.currentSerialNumber,
            itemLabel = itemLabel,
            barcodeLabel = barcodeLabel,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Category selector (only for unregistered items)
        if (uiState.scanResult is ScanResult.NotRegistered) {
            Text(
                text = "Selecciona categoría",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            CategorySelector(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = onCategorySelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Price input (only for available or not_registered)
        if (uiState.scanResult is ScanResult.Available ||
            (uiState.scanResult is ScanResult.NotRegistered && uiState.selectedCategory != null)) {

            Text(
                text = "Precio de venta",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.enteredPrice,
                onValueChange = onPriceChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Precio (MXN)") },
                leadingIcon = { Text("$", style = MaterialTheme.typography.titleLarge) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                ),
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Confirm sale button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onConfirmSale()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = uiState.canProceedToSell
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Confirmar Venta", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Already sold - show scan another button
        if (uiState.scanResult is ScanResult.AlreadySold) {
            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onScanAnother,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Escanear Otro", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scan another button (secondary action)
        if (uiState.scanResult !is ScanResult.AlreadySold) {
            OutlinedButton(
                onClick = onScanAnother,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Escanear Otro")
            }
        }
    }
}

@Composable
private fun ScanResultCard(
    scanResult: ScanResult?,
    serialNumber: String,
    itemLabel: String,
    barcodeLabel: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = when (scanResult) {
                is ScanResult.Available -> MaterialTheme.colorScheme.primaryContainer
                is ScanResult.NotRegistered -> MaterialTheme.colorScheme.secondaryContainer
                is ScanResult.AlreadySold -> MaterialTheme.colorScheme.errorContainer
                is ScanResult.ModuleDisabled -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (scanResult) {
                        is ScanResult.Available -> Icons.Default.Check
                        is ScanResult.NotRegistered -> Icons.Default.Warning
                        is ScanResult.AlreadySold -> Icons.Default.Error
                        is ScanResult.ModuleDisabled -> Icons.Default.Error
                        null -> Icons.Default.QrCodeScanner
                    },
                    contentDescription = null,
                    tint = when (scanResult) {
                        is ScanResult.Available -> MaterialTheme.colorScheme.primary
                        is ScanResult.NotRegistered -> MaterialTheme.colorScheme.secondary
                        is ScanResult.AlreadySold -> MaterialTheme.colorScheme.error
                        is ScanResult.ModuleDisabled -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (scanResult) {
                            is ScanResult.Available -> "$itemLabel Disponible"
                            is ScanResult.NotRegistered -> "$itemLabel No Registrado"
                            is ScanResult.AlreadySold -> "$itemLabel Ya Vendido"
                            is ScanResult.ModuleDisabled -> "Módulo Deshabilitado"
                            null -> "Escaneando..."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$barcodeLabel: $serialNumber",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Show category for available items
            if (scanResult is ScanResult.Available && scanResult.category != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Text(
                        text = "Categoría: ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = scanResult.category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (scanResult.suggestedPrice != null) {
                    Row {
                        Text(
                            text = "Precio sugerido: ",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "$${scanResult.suggestedPrice}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Show sold info for already sold items
            if (scanResult is ScanResult.AlreadySold) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                if (scanResult.soldAt != null) {
                    Text(
                        text = "Vendido: ${scanResult.soldAt}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CategorySelector(
    categories: List<CategoryWithStock>,
    selectedCategory: CategoryWithStock?,
    onCategorySelected: (CategoryWithStock) -> Unit,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No hay categorías disponibles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                CategoryItem(
                    category = category,
                    isSelected = category.id == selectedCategory?.id,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: CategoryWithStock,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (category.description != null) {
                    Text(
                        text = category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (category.suggestedPrice != null) {
                    Text(
                        text = "$${category.suggestedPrice}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${category.availableCount} disponibles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
