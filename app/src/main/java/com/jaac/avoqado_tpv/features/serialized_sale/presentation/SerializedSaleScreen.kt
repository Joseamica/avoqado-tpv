package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.AmountInputBottomSheet
import timber.log.Timber

/**
 * SerializedSaleScreen (Vender flow) - Optimized for PAX A910S
 *
 * **Dual Scanner Support:**
 * - Physical scanner (pistol): TextField captures keyboard input
 * - Camera scanner: Button opens camera dialog
 *
 * **Flow:**
 * 1. Select category (optional, shows suggested price)
 * 2. Scan barcode (physical or camera)
 * 3. Show item info + price input
 * 4. Confirm sale → Navigate to payment
 *
 * @param onNavigateBack Navigation callback to go back
 * @param onNavigateToPayment Navigation callback with order ID for payment
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SerializedSaleScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (orderId: String, orderTotal: String, skipProofOfSale: Boolean) -> Unit,
    resetOnEnter: Boolean = false,
    viewModel: SerializedSaleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val labels = viewModel.labels

    // Use configured labels or defaults
    val itemLabel = labels?.item ?: "Artículo"
    val barcodeLabel = labels?.barcode ?: "Código"

    // Scanner input state (for physical scanner)
    var scannerInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Create category dialog state
    var showCreateCategoryDialog by remember { mutableStateOf(false) }

    // Amount input dialog state
    var showAmountInput by remember { mutableStateOf(false) }

    // Log UI state changes for debugging
    LaunchedEffect(uiState) {
        Timber.d("📦 [Screen] UI State changed: isLoading=${uiState.isLoading}, scanResult=${uiState.scanResult?.let { it::class.simpleName }}, error=${uiState.error}, serial=${uiState.currentSerialNumber}")
    }

    // Reset flow when returning from payment success
    LaunchedEffect(resetOnEnter) {
        if (resetOnEnter) {
            scannerInput = ""
            viewModel.returnToScanner()
        }
    }

    // Request focus on scanner input when screen loads and after scanning
    LaunchedEffect(uiState.scanResult) {
        Timber.d("📦 [Screen] scanResult changed to: ${uiState.scanResult?.let { it::class.simpleName } ?: "null"}")
        if (uiState.scanResult == null && !uiState.showCameraScanner) {
            Timber.d("📦 [Screen] Requesting focus on scanner input")
            focusRequester.requestFocus()
        }
    }

    // 🛡️ BackHandler ALWAYS enabled on this screen
    // Prevents physical scanner's Enter key from being interpreted as back navigation
    BackHandler(enabled = true) {
        Timber.d("📦 [SerializedSale] BackHandler intercepted - ignoring hardware back during scan flow")
    }

    // Camera scanner dialog (fullscreen)
    if (uiState.showCameraScanner) {
        BarcodeScannerScreen(
            onBarcodeScanned = { barcode, _ ->
                viewModel.onBarcodeScanned(barcode)
            },
            onClose = { viewModel.hideCameraScanner() },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Scaffold(
            modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                // 🔫 Safety net: Intercept ENTER KeyUp from physical scanner
                // When TextField is removed (isLoading=true), ENTER KeyUp can activate
                // the TopBar back button → navigates to WelcomeScreen.
                // Consuming KeyUp here prevents that. KeyDown still reaches TextField's onDone.
                val isEnterKey = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                if (isEnterKey && keyEvent.type == KeyEventType.KeyUp) {
                    Timber.d("📦 [SerializedSale] Screen-level ENTER KeyUp consumed (preventing accidental back navigation)")
                    true // Consume
                } else {
                    false
                }
            },
            topBar = {
                AvoqadoTopBar(
                    title = "Vender $itemLabel",
                    onNavigationClick = onNavigateBack
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (uiState.isLoading) {
                    // Loading overlay
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(Spacing.Space4))
                            Text("Procesando...")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Spacing.Space4)
                    ) {
                        // ══════════════════════════════════════════════════════════
                        // Scanner Input (physical scanner + camera button)
                        // Only shown before scanning - category is auto-detected
                        // ══════════════════════════════════════════════════════════
                        if (uiState.scanResult == null) {
                            // Log current UI state for debugging
                            Timber.d("📦 [Screen] Showing scanner input - scanResult is null")

                            OutlinedTextField(
                                value = scannerInput,
                                onValueChange = { newValue ->
                                    try {
                                        // Log raw input for debugging (show control chars)
                                        val debugValue = newValue.map { c ->
                                            when {
                                                c == '\n' -> "\\n"
                                                c == '\r' -> "\\r"
                                                c == '\t' -> "\\t"
                                                c.code < 32 -> "\\x${c.code.toString(16)}"
                                                else -> c.toString()
                                            }
                                        }.joinToString("")
                                        Timber.d("📦 [Screen] Input changed: '$debugValue' (len=${newValue.length})")

                                        // Physical scanner sends text + Enter/CR
                                        if (newValue.contains("\n") || newValue.contains("\r")) {
                                            val serial = newValue.trim()
                                                .replace("\n", "")
                                                .replace("\r", "")
                                                .replace("\t", "")
                                                .filter { it.code >= 32 } // Remove control chars
                                            Timber.d("📦 [Screen] Detected scanner input with Enter - serial: '$serial' (len=${serial.length})")
                                            if (serial.isNotBlank()) {
                                                Timber.d("📦 [Screen] Calling viewModel.onBarcodeScanned('$serial')")
                                                viewModel.onBarcodeScanned(serial)
                                                scannerInput = ""
                                            } else {
                                                Timber.w("📦 [Screen] Serial was blank after cleaning, ignoring")
                                                scannerInput = ""
                                            }
                                        } else {
                                            scannerInput = newValue
                                        }
                                    } catch (e: Exception) {
                                        Timber.e(e, "📦 [Screen] ERROR in onValueChange")
                                        scannerInput = ""
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(Size.SerializedScannerInputHeight)
                                    .focusRequester(focusRequester),
                                placeholder = {
                                    Text(
                                        "Escanear $barcodeLabel...",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { viewModel.showCameraScanner() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = "Usar cámara",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (scannerInput.isNotBlank()) {
                                            viewModel.onBarcodeScanned(scannerInput.trim())
                                            scannerInput = ""
                                        }
                                    }
                                )
                            )

                            Spacer(modifier = Modifier.height(Spacing.Space3))

                            // Hint text
                            Text(
                                text = "Escanea con pistola o toca 📷 para usar cámara",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // ══════════════════════════════════════════════════════════
                        // Scan Result Card (when item is scanned)
                        // ══════════════════════════════════════════════════════════
                        if (uiState.scanResult != null) {
                            Timber.d("📦 [Screen] Showing scan result: ${uiState.scanResult!!::class.simpleName}")
                            ScanResultCard(
                                scanResult = uiState.scanResult,
                                serialNumber = uiState.currentSerialNumber,
                                itemLabel = itemLabel,
                                barcodeLabel = barcodeLabel,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(Spacing.Space3))

                            // Category selector for unregistered items
                            if (uiState.scanResult is ScanResult.NotRegistered) {
                                Text(
                                    text = "Selecciona categoría",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(Spacing.Space1))

                                CategorySelectorDropdown(
                                    categories = uiState.categories,
                                    selectedCategory = uiState.selectedCategory,
                                    onCategorySelected = viewModel::onCategorySelected,
                                    onCreateCategory = { showCreateCategoryDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(Spacing.Space3))
                            }

                            // Price input (for available or not_registered with category)
                            if (uiState.scanResult is ScanResult.Available ||
                                (uiState.scanResult is ScanResult.NotRegistered && uiState.selectedCategory != null)
                            ) {
                                Text(
                                    text = "Precio de venta",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(Spacing.Space1))

                                // Clickable price display — opens numpad dialog
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showAmountInput = true },
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (uiState.enteredPrice.isNotEmpty())
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.outline
                                    ),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (uiState.enteredPrice.isNotEmpty())
                                                "$${uiState.enteredPrice}"
                                            else
                                                "$0",
                                            style = MaterialTheme.typography.headlineMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (uiState.enteredPrice.isNotEmpty())
                                                MaterialTheme.colorScheme.onSurface
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Editar precio",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(Spacing.Space3))

                                // Portabilidad toggle (only when backend enables it)
                                if (uiState.showPortabilidadToggle) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (uiState.isPortabilidad)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        tonalElevation = if (uiState.isPortabilidad) 2.dp else 0.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.onPortabilidadToggled(!uiState.isPortabilidad) }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.SwapHoriz,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(20.dp),
                                                    tint = if (uiState.isPortabilidad)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Column {
                                                    Text(
                                                        text = "Portabilidad",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "Conserva su número actual",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = uiState.isPortabilidad,
                                                onCheckedChange = viewModel::onPortabilidadToggled
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(Spacing.Space3))
                                }

                                // Confirm sale button
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        val skipProof = uiState.isPortabilidad
                                        viewModel.onConfirmSale { result ->
                                            onNavigateToPayment(
                                                result.orderId,
                                                result.total.toPlainString(),
                                                skipProof
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(Size.ButtonHeightLarge),
                                    enabled = uiState.canProceedToSell
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                    Spacer(modifier = Modifier.width(Spacing.Space2))
                                    Text(
                                        text = if (uiState.isPortabilidad)
                                            "Portabilidad $${uiState.enteredPrice.ifEmpty { "0" }}"
                                        else
                                            "Vender $${uiState.enteredPrice.ifEmpty { "0" }}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            // Already sold - show scan another button
                            if (uiState.scanResult is ScanResult.AlreadySold) {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            Spacer(modifier = Modifier.height(Spacing.Space3))

                            // Scan another button
                            OutlinedButton(
                                onClick = {
                                    scannerInput = ""
                                    viewModel.returnToScanner()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(Size.ButtonHeight)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(Spacing.Space2))
                                Text("Escanear Otro")
                            }
                        }
                    }
                }

                // Error Snackbar
                if (uiState.error != null) {
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Spacing.Space4),
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

        // Amount Input Bottom Sheet (numpad dialog)
        AmountInputBottomSheet(
            visible = showAmountInput,
            onDismiss = { showAmountInput = false },
            onConfirm = { amount ->
                viewModel.onPriceChanged(amount)
                showAmountInput = false
            }
        )

        // Create Category Dialog
        if (showCreateCategoryDialog) {
            CreateCategoryDialog(
                onDismiss = { showCreateCategoryDialog = false },
                onCreate = { name, description, suggestedPrice ->
                    viewModel.createCategory(
                        name = name,
                        description = description,
                        suggestedPrice = suggestedPrice,
                        onSuccess = {
                            showCreateCategoryDialog = false
                        }
                    )
                },
                categoryLabel = labels?.category ?: "Categoría"
            )
        }
    }
}

/**
 * Scan result card showing item status
 */
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
            modifier = Modifier.padding(Spacing.Space4)
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
                Spacer(modifier = Modifier.width(Spacing.Space3))
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
                Spacer(modifier = Modifier.height(Spacing.Space2))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(Spacing.Space2))
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
                Spacer(modifier = Modifier.height(Spacing.Space2))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(Spacing.Space2))
                if (scanResult.soldAt != null) {
                    val formattedDate = formatSoldAtDate(scanResult.soldAt)
                    Text(
                        text = "Vendido: $formattedDate",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/**
 * Format ISO timestamp to localized date/time with fallback to raw string
 */
private fun formatSoldAtDate(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (e: Exception) {
        // Fallback to raw string if parsing fails
        isoTimestamp
    }
}

/**
 * Compact category selector dropdown for PAX A910S small screen
 * Shows category name and suggested price in dropdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySelectorDropdown(
    categories: List<CategoryWithStock>,
    selectedCategory: CategoryWithStock?,
    onCategorySelected: (CategoryWithStock) -> Unit,
    onCreateCategory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    if (categories.isEmpty()) {
        // When no categories exist, show "Create Category" button instead of loading
        OutlinedButton(
            onClick = onCreateCategory,
            modifier = modifier
                .fillMaxWidth()
                .height(Size.SerializedCategorySelectorHeight)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Crear categoría")
        }
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
                leadingIcon = {
                    Icon(
                        Icons.Default.Category,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Show price if category selected
                        selectedCategory?.suggestedPrice?.let { price ->
                            Text(
                                text = "$$price",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(Spacing.Space1))
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = category.name,
                                        fontWeight = if (category.id == selectedCategory?.id) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "${category.availableCount} disponibles",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                category.suggestedPrice?.let { price ->
                                    Text(
                                        text = "$$price",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
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

/**
 * Dialog for creating a new category when no categories exist.
 * Shows a form with name, description (optional), and suggested price (optional).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?, suggestedPrice: BigDecimal?) -> Unit,
    categoryLabel: String = "Categoría",
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var suggestedPrice by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Title
                Text(
                    text = "Nueva $categoryLabel",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Name field (required)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre *") },
                    placeholder = { Text("Ej: SIM Movistar, Anillo de Oro") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    isError = name.isBlank()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Description field (optional)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción (opcional)") },
                    placeholder = { Text("Detalles adicionales") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Suggested price field (optional)
                OutlinedTextField(
                    value = suggestedPrice,
                    onValueChange = { suggestedPrice = it },
                    label = { Text("Precio sugerido (opcional)") },
                    placeholder = { Text("0.00") },
                    leadingIcon = { Text("$", style = MaterialTheme.typography.titleMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val price = suggestedPrice.toBigDecimalOrNull()
                            onCreate(
                                name.trim(),
                                description.trim().ifBlank { null },
                                price
                            )
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Crear")
                    }
                }
            }
        }
    }
}
