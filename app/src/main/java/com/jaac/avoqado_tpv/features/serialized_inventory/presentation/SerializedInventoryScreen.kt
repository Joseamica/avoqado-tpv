package com.jaac.avoqado_tpv.features.serialized_inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.InventoryScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen

/**
 * SerializedInventoryScreen (Alta de Productos flow)
 *
 * Flow:
 * 1. Select category
 * 2. Scan multiple barcodes
 * 3. Review list
 * 4. Register batch
 *
 * @param onNavigateBack Navigation callback to go back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerializedInventoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SerializedInventoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val labels = viewModel.labels

    // Use configured labels or defaults
    val itemLabel = labels?.item ?: "Artículo"
    // Simple pluralization: add 's' for Spanish (SIM → SIMs, Pieza → Piezas)
    val itemLabelPlural = if (itemLabel.endsWith("s")) itemLabel else "${itemLabel}s"
    val barcodeLabel = labels?.barcode ?: "Código"
    val registerLabel = labels?.register ?: "Alta de Productos"

    // Snackbar state for feedback
    var lastScanFeedback by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(registerLabel) },
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
                    // Barcode scanner overlay
                    BarcodeScannerScreen(
                        onBarcodeScanned = { barcode, _ ->
                            val result = viewModel.onBarcodeScanned(barcode)
                            lastScanFeedback = when (result) {
                                is InventoryScanResult.Added -> "✓ Agregado: $barcode"
                                is InventoryScanResult.AlreadyScanned -> "Ya escaneado: $barcode"
                                is InventoryScanResult.Duplicate -> "Duplicado: $barcode"
                            }
                            // Don't close scanner - allow continuous scanning
                        },
                        onClose = { viewModel.stopScanning() },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Scanned count overlay
                    ScannedCountOverlay(
                        count = uiState.scannedCount,
                        lastFeedback = lastScanFeedback,
                        onDone = { viewModel.stopScanning() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 100.dp)
                    )
                }
                uiState.isLoading -> {
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
                    // Main inventory form
                    InventoryFormContent(
                        uiState = uiState,
                        itemLabel = itemLabel,
                        itemLabelPlural = itemLabelPlural,
                        barcodeLabel = barcodeLabel,
                        onCategorySelected = viewModel::onCategorySelected,
                        onStartScanning = viewModel::startScanning,
                        onRemoveSerialNumber = viewModel::removeSerialNumber,
                        onClearList = viewModel::clearScannedList,
                        onRegisterBatch = { viewModel.registerBatch() },
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
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Text(uiState.error!!)
                }
            }

            // Success Snackbar
            if (uiState.successMessage != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissSuccess() }) {
                            Text("OK")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.successMessage!!)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedCountOverlay(
    count: Int,
    lastFeedback: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Feedback text
        if (lastFeedback != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    text = lastFeedback,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Count badge and done button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Count badge
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "$count",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Done button
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Done, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Listo")
            }
        }
    }
}

@Composable
private fun InventoryFormContent(
    uiState: com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.SerializedInventoryUiState,
    itemLabel: String,
    itemLabelPlural: String,
    barcodeLabel: String,
    onCategorySelected: (CategoryWithStock) -> Unit,
    onStartScanning: () -> Unit,
    onRemoveSerialNumber: (String) -> Unit,
    onClearList: () -> Unit,
    onRegisterBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        // Step 1: Category Selection
        Text(
            text = "1. Selecciona categoría",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        CategorySelectorGrid(
            categories = uiState.categories,
            selectedCategory = uiState.selectedCategory,
            onCategorySelected = onCategorySelected,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Step 2: Scan barcodes
        Text(
            text = "2. Escanea $itemLabelPlural",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Scan button
        Button(
            onClick = onStartScanning,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.selectedCategory != null
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.scannedCount > 0) {
                    "Continuar Escaneando (${uiState.scannedCount})"
                } else {
                    "Iniciar Escaneo"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scanned items list
        if (uiState.scannedSerialNumbers.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$barcodeLabel escaneados (${uiState.scannedCount}):",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onClearList) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Limpiar")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // List of scanned barcodes
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(uiState.scannedSerialNumbers) { index, serialNumber ->
                        ScannedItemRow(
                            index = index + 1,
                            serialNumber = serialNumber,
                            onRemove = { onRemoveSerialNumber(serialNumber) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 3: Register
            Text(
                text = "3. Registrar ${uiState.scannedCount} $itemLabelPlural",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onRegisterBatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = uiState.canRegister
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Registrar $itemLabelPlural", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Show registration result
        if (uiState.registrationResult != null) {
            Spacer(modifier = Modifier.height(16.dp))
            RegistrationResultCard(
                result = uiState.registrationResult,
                itemLabelPlural = itemLabelPlural
            )
        }
    }
}

@Composable
private fun CategorySelectorGrid(
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
                text = "Cargando categorías...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                CategoryChip(
                    category = category,
                    isSelected = category.id == selectedCategory?.id,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(
    category: CategoryWithStock,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
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
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
            Text(
                text = "${category.totalCount} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScannedItemRow(
    index: Int,
    serialNumber: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$index.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            Text(
                text = serialNumber,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Eliminar",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RegistrationResultCard(
    result: com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult,
    itemLabelPlural: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (result.hasSuccess) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (result.hasSuccess) Icons.Default.Check else Icons.Default.Warning,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Resultado del Registro",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${result.created} $itemLabelPlural registrados",
                style = MaterialTheme.typography.bodyMedium
            )

            if (result.hasDuplicates) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${result.duplicates.size} ya existían en el sistema:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                result.duplicates.take(5).forEach { dup ->
                    Text(
                        text = "• $dup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.duplicates.size > 5) {
                    Text(
                        text = "... y ${result.duplicates.size - 5} más",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
