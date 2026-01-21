package com.jaac.avoqado_tpv.features.serialized_inventory.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.InventoryScanResult
import timber.log.Timber
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar

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

    // 🛡️ BackHandler to prevent accidental back navigation from physical scanner Enter key
    // Enable when category is selected (scanner mode active) OR have items OR is camera scanning
    // This prevents physical scanner's Enter key from being interpreted as back navigation
    val shouldInterceptBack = uiState.selectedCategory != null || uiState.scannedCount > 0 || uiState.isScanning
    BackHandler(enabled = shouldInterceptBack) {
        Timber.d("📦 BackHandler intercepted - category: ${uiState.selectedCategory?.name}, scannedCount: ${uiState.scannedCount}, isScanning: ${uiState.isScanning}")
        // Don't navigate back automatically - user must explicitly tap back arrow button in TopAppBar
        // This prevents physical scanner's Enter/back key events from causing navigation
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = registerLabel,
                onNavigationClick = onNavigateBack
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
                            viewModel.onBarcodeScanned(barcode) { result ->
                                lastScanFeedback = when (result) {
                                    is InventoryScanResult.Added -> "✓ Agregado: $barcode"
                                    is InventoryScanResult.AlreadyScanned -> "Ya escaneado: $barcode"
                                    is InventoryScanResult.Duplicate -> "⚠ Ya registrado"
                                }
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
                        onBarcodeScanned = viewModel::onBarcodeScanned,
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

/**
 * Compact overlay for camera scanning mode
 * Shows scan feedback and count with done button
 */
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
        // Feedback text (compact pill)
        if (lastFeedback != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (lastFeedback.startsWith("✓")) {
                    Color(0xFF4CAF50).copy(alpha = 0.9f)  // Green for success
                } else {
                    Color(0xFFFF9800).copy(alpha = 0.9f)  // Orange for warning
                }
            ) {
                Text(
                    text = lastFeedback,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Count + Done button in a single card
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.85f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Count badge (circular)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Done button (prominent)
                Button(
                    onClick = onDone,
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Listo", style = MaterialTheme.typography.labelLarge)
                }
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
    onBarcodeScanned: (String, (InventoryScanResult) -> Unit) -> Unit,
    onStartScanning: () -> Unit,
    onRemoveSerialNumber: (String) -> Unit,
    onClearList: () -> Unit,
    onRegisterBatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    // State for physical scanner input
    var physicalScannerInput by remember { mutableStateOf("") }
    var scanFeedback by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // 🎹 State to control if user WANTS to show keyboard (manual input mode)
    // By default false = physical scanner mode (no keyboard)
    var showKeyboardManually by remember { mutableStateOf(false) }

    // 🔑 CRITICAL: Auto-focus TextField when category is selected
    // Physical scanner NEEDS focus on TextField to receive input!
    // Without focus, scanner input goes nowhere and Enter triggers back navigation
    LaunchedEffect(uiState.selectedCategory) {
        if (uiState.selectedCategory != null) {
            Timber.d("📦 Category selected, requesting focus for physical scanner input")
            showKeyboardManually = false // Reset to scanner mode
            kotlinx.coroutines.delay(150) // Small delay for layout
            try {
                focusRequester.requestFocus()
                Timber.d("📦 Focus requested")
            } catch (e: Exception) {
                Timber.w("📦 Failed to request focus: ${e.message}")
            }
        }
    }

    // 🎹 Aggressively hide keyboard when NOT in manual keyboard mode
    // This runs whenever focus changes and we're in scanner mode
    LaunchedEffect(showKeyboardManually) {
        if (!showKeyboardManually && uiState.selectedCategory != null) {
            // Multiple attempts to hide keyboard (Android can be stubborn)
            repeat(3) {
                kotlinx.coroutines.delay(100)
                keyboardController?.hide()
            }
            Timber.d("📦 Keyboard hidden (scanner mode)")
        }
    }

    // Clear feedback after 2 seconds
    LaunchedEffect(scanFeedback) {
        if (scanFeedback != null) {
            kotlinx.coroutines.delay(2000)
            scanFeedback = null
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp)
            // Tap outside TextField to dismiss keyboard
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }
    ) {
        // Step 1: Category Selection (Compact dropdown)
        Text(
            text = "1. Selecciona categoría",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))

        CategorySelectorDropdown(
            categories = uiState.categories,
            selectedCategory = uiState.selectedCategory,
            onCategorySelected = onCategorySelected,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Step 2: Scan barcodes (Compact)
        Text(
            text = "2. Escanea $itemLabelPlural",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))

        // 📱 Physical scanner input field (compact for PAX A910S)
        OutlinedTextField(
            value = physicalScannerInput,
            onValueChange = { physicalScannerInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(Size.SerializedScannerInputHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    // When TextField receives focus and we're NOT in manual keyboard mode,
                    // aggressively hide the keyboard for physical scanner use
                    if (focusState.isFocused && !showKeyboardManually) {
                        Timber.d("📦 TextField focused in scanner mode - hiding keyboard")
                        keyboardController?.hide()
                    }
                },
            enabled = uiState.selectedCategory != null,
            placeholder = {
                Text(
                    "Escanea o escribe código",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.QrCode,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (uiState.selectedCategory != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Add button (when text is present)
                    if (physicalScannerInput.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (physicalScannerInput.isNotBlank()) {
                                    val barcode = physicalScannerInput.trim()
                                    onBarcodeScanned(barcode) { result ->
                                        scanFeedback = when (result) {
                                            is InventoryScanResult.Added -> "✓ $barcode"
                                            is InventoryScanResult.AlreadyScanned -> "⚠ Ya escaneado"
                                            is InventoryScanResult.Duplicate -> "⚠ Ya registrado"
                                        }
                                    }
                                    physicalScannerInput = ""
                                    showKeyboardManually = false
                                    keyboardController?.hide()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Agregar",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    // Keyboard toggle button (always visible when enabled)
                    if (uiState.selectedCategory != null) {
                        IconButton(
                            onClick = {
                                showKeyboardManually = true
                                focusRequester.requestFocus()
                                keyboardController?.show()
                                Timber.d("📦 Manual keyboard mode enabled")
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = "Mostrar teclado",
                                modifier = Modifier.size(18.dp),
                                tint = if (showKeyboardManually) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    Timber.d("📦 onDone triggered - input: '$physicalScannerInput'")
                    if (physicalScannerInput.isNotBlank()) {
                        val barcode = physicalScannerInput.trim()
                        Timber.d("📦 Processing barcode: $barcode")
                        onBarcodeScanned(barcode) { result ->
                            scanFeedback = when (result) {
                                is InventoryScanResult.Added -> "✓ $barcode"
                                is InventoryScanResult.AlreadyScanned -> "⚠ Ya escaneado"
                                is InventoryScanResult.Duplicate -> "⚠ Ya registrado"
                            }
                            Timber.d("📦 Scan result: $scanFeedback")
                        }
                        physicalScannerInput = ""
                    }
                    showKeyboardManually = false
                    keyboardController?.hide()
                }
            ),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall
        )

        // Scan feedback (compact, separate line)
        if (scanFeedback != null) {
            Text(
                text = scanFeedback!!,
                style = MaterialTheme.typography.labelSmall,
                color = if (scanFeedback!!.startsWith("✓")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Divider with "o" text
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "  o usa la cámara  ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 📷 Camera scan button (alternative method)
        OutlinedButton(
            onClick = onStartScanning,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.selectedCategory != null
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.scannedCount > 0) {
                    "Escanear con Cámara (${uiState.scannedCount})"
                } else {
                    "Escanear con Cámara"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scanned items counter badge (compact)
        if (uiState.scannedSerialNumbers.isNotEmpty()) {
            // Counter badge row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.SerializedCounterHeight)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${uiState.scannedCount} $itemLabelPlural escaneados",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(
                    onClick = onClearList,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Limpiar", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // List of scanned barcodes with FIXED HEIGHT (prevents unbounded growth)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = Size.SerializedListMaxHeight)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(
                        items = uiState.scannedSerialNumbers,
                        key = { _, serial -> serial }
                    ) { index, serialNumber ->
                        ScannedItemRowCompact(
                            index = index + 1,
                            serialNumber = serialNumber,
                            onRemove = { onRemoveSerialNumber(serialNumber) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Register (compact button)
            Button(
                onClick = onRegisterBatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.ButtonHeightLarge),
                enabled = uiState.canRegister
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Registrar ${uiState.scannedCount} $itemLabelPlural",
                    style = MaterialTheme.typography.titleMedium
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySelectorDropdown(
    categories: List<CategoryWithStock>,
    selectedCategory: CategoryWithStock?,
    onCategorySelected: (CategoryWithStock) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    if (categories.isEmpty()) {
        OutlinedTextField(
            value = "Cargando categorías...",
            onValueChange = {},
            modifier = modifier
                .fillMaxWidth()
                .height(Size.SerializedCategorySelectorHeight),
            enabled = false,
            readOnly = true,
            singleLine = true
        )
    } else {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier
        ) {
            OutlinedTextField(
                value = selectedCategory?.name ?: "Seleccionar categoría",
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.SerializedCategorySelectorHeight)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                readOnly = true,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = if (selectedCategory != null) {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = category.name,
                                    fontWeight = if (category.id == selectedCategory?.id) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "${category.availableCount} disp.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onCategorySelected(category)
                            expanded = false
                        },
                        leadingIcon = if (category.id == selectedCategory?.id) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null
                    )
                }
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
                text = "${category.availableCount} disponibles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Compact item row for scanned serial numbers (40dp height)
 * Optimized for PAX A910S small screen
 */
@Composable
private fun ScannedItemRowCompact(
    index: Int,
    serialNumber: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Size.SerializedItemRowHeight)
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$index.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
            Text(
                text = serialNumber,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Eliminar",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
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
    // ✅ FIX: Use appropriate colors based on result type
    val (containerColor, icon, iconTint) = when (result.resultType) {
        com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult.ResultType.SUCCESS -> {
            // All items registered - green (success)
            Triple(
                MaterialTheme.colorScheme.primaryContainer,
                Icons.Default.Check,
                MaterialTheme.colorScheme.primary
            )
        }
        com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult.ResultType.PARTIAL -> {
            // Some registered, some duplicates - yellow (warning)
            Triple(
                MaterialTheme.colorScheme.tertiaryContainer,
                Icons.Default.Warning,
                MaterialTheme.colorScheme.tertiary
            )
        }
        com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult.ResultType.INFO -> {
            // All duplicates - blue (info, not error)
            Triple(
                MaterialTheme.colorScheme.secondaryContainer,
                Icons.Default.Info,
                MaterialTheme.colorScheme.secondary
            )
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Resultado del Registro",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ✅ FIX: Improve message clarity
            if (result.created > 0) {
                Text(
                    text = "✅ ${result.created} $itemLabelPlural registrados exitosamente",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (result.hasDuplicates) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (result.created == 0) {
                        "ℹ️ ${result.duplicates.size} $itemLabelPlural ya existían en el sistema:"
                    } else {
                        "⚠️ ${result.duplicates.size} ya estaban registrados:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                result.duplicates.take(5).forEach { dup ->
                    Text(
                        text = "  • $dup",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (result.duplicates.size > 5) {
                    Text(
                        text = "  ... y ${result.duplicates.size - 5} más",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
