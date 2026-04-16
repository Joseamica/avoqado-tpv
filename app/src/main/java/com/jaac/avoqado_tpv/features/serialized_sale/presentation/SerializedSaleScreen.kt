package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.jaac.avoqado_tpv.core.presentation.theme.Size
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
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
import androidx.compose.ui.graphics.Color
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
    onNavigateToPayment: (orderId: String, orderNumber: String?, orderTotal: String, isPortabilidad: Boolean, serialNumber: String?, categoryName: String?) -> Unit,
    resetOnEnter: Boolean = false,
    onNavigateToMisSims: () -> Unit = {},
    viewModel: SerializedSaleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val canCreateCategory by viewModel.canCreateCategory.collectAsState()
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
                    onNavigationClick = onNavigateBack,
                    actions = {
                        if (canCreateCategory) {
                            TextButton(onClick = { showCreateCategoryDialog = true }) {
                                Text("+ categoría")
                            }
                        }
                    }
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
                            .verticalScroll(rememberScrollState())
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

                            // Inline error message (visible immediately under input)
                            if (uiState.error != null && uiState.scanResult == null) {
                                Text(
                                    text = uiState.error!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                                )
                            }

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
                            val scanResult = uiState.scanResult!!
                            Timber.d("📦 [Screen] Showing scan result: ${scanResult::class.simpleName}")

                            // Dynamic step numbering based on current state
                            var nextStep = 1
                            val statusStep = nextStep++
                            val categoryStep = if (scanResult is ScanResult.NotRegistered) nextStep++ else null
                            val showPriceSection = scanResult is ScanResult.Available ||
                                (scanResult is ScanResult.NotRegistered && uiState.selectedCategory != null)
                            val priceStep = if (showPriceSection) nextStep++ else null
                            val portabilidadStep = if (uiState.showPortabilidadToggle && showPriceSection) nextStep++ else null

                            // Step 1: Status banner
                            StepRow(statusStep) {
                                ScanResultCard(
                                    scanResult = uiState.scanResult,
                                    serialNumber = uiState.currentSerialNumber,
                                    itemLabel = itemLabel,
                                    barcodeLabel = barcodeLabel,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(Spacing.Space3))

                            // Step 2 (NotRegistered only): Category selector
                            if (categoryStep != null) {
                                StepRow(categoryStep) {
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
                                        canCreateCategory = canCreateCategory,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(Spacing.Space3))
                            }

                            // Step N: Price input
                            if (priceStep != null) {
                                StepRow(priceStep) {
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
                                        shape = RoundedCornerShape(50),
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
                                }

                                Spacer(modifier = Modifier.height(Spacing.Space3))
                            }

                            // Step N+1: Portabilidad toggle
                            if (portabilidadStep != null) {
                                StepRow(portabilidadStep) {
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
                                }

                                Spacer(modifier = Modifier.height(Spacing.Space3))
                            }

                            // Confirm sale button (outside StepRow — it's the CTA)
                            var showGiftConfirmDialog by remember { mutableStateOf(false) }

                            if (showPriceSection) {
                                Button(
                                    onClick = {
                                        focusManager.clearFocus()
                                        if (uiState.isZeroPrice) {
                                            showGiftConfirmDialog = true
                                        } else {
                                            val isPortabilidadValue = uiState.isPortabilidad
                                            val serialNumberValue = uiState.currentSerialNumber
                                            val categoryNameValue = uiState.selectedCategory?.name
                                            viewModel.onConfirmSale { result ->
                                                onNavigateToPayment(
                                                    result.orderId,
                                                    result.orderNumber,
                                                    result.total.toPlainString(),
                                                    isPortabilidadValue,
                                                    serialNumberValue,
                                                    categoryNameValue
                                                )
                                            }
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
                                        text = when {
                                            uiState.isZeroPrice -> "Regalar (Gratis)"
                                            uiState.isPortabilidad -> "Portabilidad $${uiState.enteredPrice}"
                                            else -> "Vender $${uiState.enteredPrice.ifEmpty { "0" }}"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            // $0 gift confirmation dialog
                            if (showGiftConfirmDialog) {
                                AlertDialog(
                                    onDismissRequest = { showGiftConfirmDialog = false },
                                    title = { Text("Confirmar regalo") },
                                    text = { Text("Este ${itemLabel.lowercase()} se registrará gratis, sin cobro.") },
                                    confirmButton = {
                                        Button(onClick = {
                                            showGiftConfirmDialog = false
                                            val isPortabilidadValue = uiState.isPortabilidad
                                            val serialNumberValue = uiState.currentSerialNumber
                                            val categoryNameValue = uiState.selectedCategory?.name
                                            viewModel.onConfirmSale { result ->
                                                onNavigateToPayment(
                                                    result.orderId,
                                                    result.orderNumber,
                                                    result.total.toPlainString(),
                                                    isPortabilidadValue,
                                                    serialNumberValue,
                                                    categoryNameValue
                                                )
                                            }
                                        }) { Text("Sí, regalar") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showGiftConfirmDialog = false }) {
                                            Text("Cancelar")
                                        }
                                    }
                                )
                            }

                            // Divider + "Escanear Otro" pattern
                            Spacer(modifier = Modifier.height(Spacing.Space4))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                    text = "  o escanea otro  ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(Spacing.Space3))

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
                            Spacer(modifier = Modifier.height(Spacing.Space4))
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

                // SIM_NOT_ACCEPTED dialog — deep-links the promoter to Mis SIMs (plan §3.3)
                if (uiState.simNotAcceptedError) {
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissSimNotAcceptedError() },
                        title = { Text("SIM no aceptado") },
                        text = {
                            Text(
                                "Debes aceptar la recepción de este SIM en \"Mis SIMs\" antes de venderlo."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.dismissSimNotAcceptedError()
                                    onNavigateToMisSims()
                                }
                            ) { Text("Ir a Mis SIMs") }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissSimNotAcceptedError() }) {
                                Text("Cancelar")
                            }
                        }
                    )
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
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Categoría: ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = scanResult.category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (scanResult.category.source == "organization") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "ORG",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
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
private fun formatSoldAtDate(isoTimestamp: String, zoneId: ZoneId = ZoneId.of("America/Mexico_City")): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(zoneId)
            .format(instant)
    } catch (e: Exception) {
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
    canCreateCategory: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    if (categories.isEmpty()) {
        if (canCreateCategory) {
            // When no categories exist and user has permission, show "Create Category" button
            OutlinedButton(
                onClick = onCreateCategory,
                modifier = modifier
                    .fillMaxWidth()
                    .height(Size.SerializedCategorySelectorHeight),
                shape = RoundedCornerShape(50)
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
            // No categories and no permission to create
            Text(
                text = "No hay categorías. Contacta al administrador.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                shape = RoundedCornerShape(50),
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
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = category.name,
                                            fontWeight = if (category.id == selectedCategory?.id) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (category.source == "organization") {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.padding(start = 2.dp)
                                            ) {
                                                Text(
                                                    text = "ORG",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
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

/**
 * Step indicator row — 24dp primary circle with step number, content on the right.
 * Provides visual step progression for the sale flow.
 */
@Composable
private fun StepRow(
    stepNumber: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Space3)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$stepNumber",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

