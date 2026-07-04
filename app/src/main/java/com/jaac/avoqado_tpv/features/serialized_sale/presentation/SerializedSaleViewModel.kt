package com.jaac.avoqado_tpv.features.serialized_sale.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleLabels
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.QuickSellResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.SerializedSaleUiState
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.IccidValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * ViewModel for SerializedSaleScreen (Vender flow)
 *
 * Flow:
 * 1. User scans barcode
 * 2. API check returns: available, not_registered, already_sold, or module_disabled
 * 3. If available → show suggested price
 * 4. If not_registered → show category selector
 * 5. User enters/confirms price
 * 6. User confirms sale → quickSell creates order
 * 7. Navigate to PaymentScreen with order ID
 */
@HiltViewModel
class SerializedSaleViewModel @Inject constructor(
    private val serializedSaleRepository: SerializedSaleRepository,
    private val modulesRepository: ModulesRepository,
    private val secureStorage: com.jaac.avoqado_tpv.core.data.local.SecureStorage,
    private val permissionsRepository: PermissionsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SerializedSaleUiState())
    val uiState: StateFlow<SerializedSaleUiState> = _uiState.asStateFlow()

    /** Whether the current user can create categories (requires inventory:org-manage) */
    private val _canCreateCategory = MutableStateFlow(false)
    val canCreateCategory: StateFlow<Boolean> = _canCreateCategory.asStateFlow()

    // Job for scan operation - allows cancellation when user scans rapidly
    private var scanJob: Job? = null

    // Labels from module config (e.g., "SIM", "ICCID")
    val labels: ModuleLabels?
        get() = modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)?.labels

    init {
        loadCategories()
        loadPortabilidadConfig()
        checkCategoryPermission()
    }

    private fun checkCategoryPermission() {
        viewModelScope.launch {
            _canCreateCategory.value = permissionsRepository.hasPermission("inventory:org-manage")
        }
    }

    /**
     * Check if portabilidad toggle should be shown based on module config.
     */
    private fun loadPortabilidadConfig() {
        val config = modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)
        val enabled = config?.features?.enablePortabilidad == true
        Timber.d("📦 [SerializedSale] Portabilidad config: enablePortabilidad=$enabled")
        _uiState.update { it.copy(showPortabilidadToggle = enabled) }
    }

    /**
     * Toggle portabilidad mode on/off.
     */
    fun onPortabilidadToggled(enabled: Boolean) {
        Timber.d("📦 [SerializedSale] Portabilidad toggled: $enabled")
        _uiState.update { it.copy(isPortabilidad = enabled) }
    }

    /**
     * Load categories for the venue.
     * Used when item is not registered and user needs to select category.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            serializedSaleRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.update { it.copy(categories = categories) }
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to load categories")
                }
        }
    }

    /**
     * Show camera scanner dialog.
     */
    fun showCameraScanner() {
        _uiState.update { it.copy(showCameraScanner = true) }
    }

    /**
     * Hide camera scanner dialog.
     */
    fun hideCameraScanner() {
        _uiState.update { it.copy(showCameraScanner = false) }
    }

    /**
     * Handle barcode scan result from scanner (physical or camera).
     *
     * @param serialNumber The scanned barcode/serial number
     */
    fun onBarcodeScanned(serialNumber: String) {
        Timber.d("📦 [SerializedSale] Barcode scanned: '$serialNumber'")

        // Validate serial number
        if (serialNumber.isBlank()) {
            Timber.w("📦 [SerializedSale] Ignoring blank serial number")
            return
        }
        if (!IccidValidator.isValidFormat(serialNumber)) {
            _uiState.update {
                it.copy(error = "Código inválido. Verifica que el sticker empiece con 8952 (México) y tenga 20 dígitos.")
            }
            return
        }

        // Cancel any previous scan in progress (prevents race condition)
        scanJob?.cancel()

        Timber.d("📦 [SerializedSale] Setting isLoading=true, calling API...")
        _uiState.update {
            it.copy(
                isLoading = true,
                showCameraScanner = false,
                error = null,
                currentSerialNumber = serialNumber
            )
        }

        scanJob = viewModelScope.launch {
            Timber.d("📦 [SerializedSale] Calling scanItem API for: $serialNumber")
            serializedSaleRepository.scanItem(serialNumber)
                .onSuccess { result ->
                    Timber.d("📦 [SerializedSale] API SUCCESS - Result: $result")
                    handleScanResult(result, serialNumber)
                }
                .onFailure { error ->
                    Timber.e(error, "📦 [SerializedSale] API FAILED - Error: ${error.message}")
                    // Plan §3.3 — the custody precheck runs at scan time, so
                    // SIM_NOT_ACCEPTED surfaces here. Route it to the "Mis SIMs"
                    // deep-link dialog (same handling as the sale path in
                    // onConfirmSale) instead of dumping the raw backend error.
                    val raw = error.message.orEmpty()
                    val notAccepted = raw.contains("SIM_NOT_ACCEPTED", ignoreCase = true) ||
                        raw.contains("aceptar la recepción", ignoreCase = true)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            simNotAcceptedError = notAccepted,
                            error = if (notAccepted) null else raw.ifEmpty { "Error al escanear" }
                        )
                    }
                }
        }
    }

    private fun handleScanResult(result: ScanResult, serialNumber: String) {
        Timber.d("📦 [SerializedSale] handleScanResult: ${result::class.simpleName}")
        when (result) {
            is ScanResult.Available -> {
                Timber.d("📦 [SerializedSale] Item AVAILABLE - Category: ${result.category?.name}, Price: ${result.suggestedPrice}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        enteredPrice = result.suggestedPrice?.toPlainString() ?: ""
                    )
                }
            }
            is ScanResult.NotRegistered -> {
                Timber.d("📦 [SerializedSale] Item NOT_REGISTERED - blocked (requires alta + approval)")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = "Esta SIM no está dada de alta o no está aprobada para venta.",
                    )
                }
            }
            is ScanResult.AlreadySold -> {
                Timber.d("📦 [SerializedSale] Item ALREADY_SOLD - soldAt: ${result.soldAt}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = "Este artículo ya fue vendido"
                    )
                }
            }
            is ScanResult.ModuleDisabled -> {
                Timber.d("📦 [SerializedSale] MODULE_DISABLED")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        scanResult = result,
                        error = "El módulo de inventario serializado no está habilitado"
                    )
                }
            }
        }
        Timber.d("📦 [SerializedSale] State updated - scanResult is now: ${_uiState.value.scanResult}")
    }

    /**
     * Update the entered price.
     *
     * @param price Price string entered by user
     */
    fun onPriceChanged(price: String) {
        // Only allow valid decimal input
        if (price.isEmpty() || price.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
            _uiState.update { it.copy(enteredPrice = price) }
        }
    }

    /**
     * Select a category for unregistered items.
     *
     * @param category The selected category
     */
    fun onCategorySelected(category: CategoryWithStock) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                enteredPrice = category.suggestedPrice?.toPlainString() ?: ""
            )
        }
    }

    /**
     * Execute the sale.
     *
     * Creates an order via quickSell endpoint.
     * Returns the order info for navigation to PaymentScreen.
     *
     * @param onSuccess Callback with QuickSellResult for navigation
     */
    fun onConfirmSale(onSuccess: (QuickSellResult) -> Unit) {
        val state = _uiState.value
        val price = state.enteredPrice.ifEmpty { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO

        if (price < BigDecimal.ZERO) {
            _uiState.update { it.copy(error = "Ingresa un precio válido") }
            return
        }

        val categoryId = when (val result = state.scanResult) {
            is ScanResult.Available -> result.item.categoryId
            is ScanResult.NotRegistered -> state.selectedCategory?.id
            else -> null
        }

        if (state.scanResult is ScanResult.NotRegistered && categoryId == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val terminalId = secureStorage.getTerminalId()

            serializedSaleRepository.quickSell(
                serialNumber = state.currentSerialNumber,
                categoryId = categoryId,
                price = price,
                terminalId = terminalId,
                isPortabilidad = state.isPortabilidad,
                skipProofOfSale = false
            )
                .onSuccess { result ->
                    Timber.d("Quick sell success: ${result.orderNumber}, price: $price, terminalId: $terminalId")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sellResult = result
                        )
                    }
                    onSuccess(result)
                    // The item is already SOLD server-side at this point, so a re-submit
                    // with the stale serial can only ever produce "ya fue vendido" (400).
                    // Navigation doesn't read uiState (payload captured via callback params),
                    // so clearing here is safe. Prevents re-selling when the promoter backs
                    // into this screen mid photo/payment flow (prod 2026-07-01: 6 duplicate
                    // sell attempts across 5 SIMs, all rejected by the backend guard).
                    returnToScanner()
                }
                .onFailure { error ->
                    Timber.e(error, "Quick sell failed")
                    // Plan §3.3 — surface SIM_NOT_ACCEPTED distinctly so the
                    // screen can deep-link the promoter to Mis SIMs.
                    val raw = error.message.orEmpty()
                    val notAccepted = raw.contains("SIM_NOT_ACCEPTED", ignoreCase = true) ||
                        raw.contains("aceptar la recepción", ignoreCase = true)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            simNotAcceptedError = notAccepted,
                            error = if (notAccepted) null else raw.ifEmpty { "Error al procesar venta" }
                        )
                    }
                }
        }
    }

    /**
     * Reset state to scan another item.
     * Does NOT auto-open camera - keeps the scanner input ready.
     */
    fun resetForNewScan() {
        _uiState.update {
            SerializedSaleUiState(
                categories = it.categories, // Keep categories loaded
                showPortabilidadToggle = it.showPortabilidadToggle // Keep config
            )
        }
    }

    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Dismiss the SIM_NOT_ACCEPTED dialog. Screen decides whether to deep-link
     * to MisSimsScreen or stay on the scan view.
     */
    fun dismissSimNotAcceptedError() {
        _uiState.update { it.copy(simNotAcceptedError = false) }
    }

    /**
     * Reset to scan another item.
     * Does NOT auto-open camera - keeps the scanner input ready for physical scanner.
     */
    fun returnToScanner() {
        _uiState.update {
            it.copy(
                showCameraScanner = false,
                scanResult = null,
                sellResult = null,
                enteredPrice = "",
                selectedCategory = null,
                error = null,
                currentSerialNumber = ""
            )
        }
    }

    /**
     * Create a new category.
     * Called when no categories exist and user creates one from TPV.
     *
     * @param name Category name (e.g., "SIM Movistar")
     * @param description Optional description
     * @param suggestedPrice Optional suggested price
     * @param onSuccess Callback when category is created successfully
     */
    fun createCategory(
        name: String,
        description: String? = null,
        suggestedPrice: BigDecimal? = null,
        onSuccess: (CategoryWithStock) -> Unit = {}
    ) {
        Timber.d("📦 [SerializedSale] Creating category: $name")

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            serializedSaleRepository.createCategory(
                name = name.trim(),
                description = description?.trim(),
                suggestedPrice = suggestedPrice
            )
                .onSuccess { category ->
                    Timber.d("📦 [SerializedSale] Category created: ${category.name}")

                    // Add category to list
                    val updatedCategories = _uiState.value.categories + category

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            categories = updatedCategories,
                            selectedCategory = category // Auto-select the new category
                        )
                    }

                    onSuccess(category)
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to create category")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al crear categoría"
                        )
                    }
                }
        }
    }
}
