package com.jaac.avoqado_tpv.features.serialized_inventory.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.features.modules.domain.model.ModuleLabels
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.InventoryScanResult
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.RegistrationResult
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.SerializedInventoryUiState
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.CategoryWithStock
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.serialized_sale.domain.IccidValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject

/**
 * ViewModel for SerializedInventoryScreen (Alta de Productos flow)
 *
 * Flow:
 * 1. Select category first
 * 2. Scan multiple barcodes (batch collection)
 * 3. Review scanned items
 * 4. Register batch → API creates items
 */
@HiltViewModel
class SerializedInventoryViewModel @Inject constructor(
    private val serializedSaleRepository: SerializedSaleRepository,
    private val modulesRepository: ModulesRepository,
    private val permissionsRepository: PermissionsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SerializedInventoryUiState())
    val uiState: StateFlow<SerializedInventoryUiState> = _uiState.asStateFlow()

    /** Whether the current user can create categories (requires inventory:org-manage) */
    private val _canCreateCategory = MutableStateFlow(false)
    val canCreateCategory: StateFlow<Boolean> = _canCreateCategory.asStateFlow()

    /** Tracks serial numbers currently being validated to prevent double-tap duplicates */
    private val _validatingSerials = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Labels from module config (e.g., "SIM", "ICCID")
    val labels: ModuleLabels?
        get() = modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)?.labels

    init {
        loadCategories()
        checkCategoryPermission()
    }

    private fun checkCategoryPermission() {
        viewModelScope.launch {
            _canCreateCategory.value = permissionsRepository.hasPermission("inventory:org-manage")
        }
    }

    /**
     * Load categories for the venue.
     */
    private fun loadCategories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            serializedSaleRepository.getCategories()
                .onSuccess { categories ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            categories = categories
                        )
                    }
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to load categories")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Error al cargar categorías: ${error.message}"
                        )
                    }
                }
        }
    }

    /**
     * Select a category for registration.
     */
    fun onCategorySelected(category: CategoryWithStock) {
        _uiState.update {
            it.copy(
                selectedCategory = category,
                error = null
            )
        }
    }

    /**
     * Create a new category and auto-select it.
     */
    fun createCategory(
        name: String,
        description: String? = null,
        suggestedPrice: BigDecimal? = null,
        onSuccess: (CategoryWithStock) -> Unit = {}
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            _uiState.update { it.copy(error = "Ingresa un nombre de categoría") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            serializedSaleRepository.createCategory(
                name = trimmedName,
                description = description?.trim()?.ifBlank { null },
                suggestedPrice = suggestedPrice
            )
                .onSuccess { category ->
                    val updatedCategories = (_uiState.value.categories + category).distinctBy { it.id }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            categories = updatedCategories,
                            selectedCategory = category
                        )
                    }
                    onSuccess(category)
                }
                .onFailure { error ->
                    Timber.e(error, "Failed to create category from inventory flow")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al crear categoría"
                        )
                    }
                }
        }
    }

    /**
     * Start scanning mode.
     */
    fun startScanning() {
        if (_uiState.value.selectedCategory == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría primero") }
            return
        }
        _uiState.update { it.copy(isScanning = true, error = null) }
    }

    /**
     * Stop scanning mode.
     */
    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
    }

    /**
     * Handle barcode scan result.
     * Validates against database BEFORE adding to batch.
     *
     * @param serialNumber The scanned barcode/serial number
     * @param onResult Callback with scan result (added, duplicate, or already scanned)
     */
    fun onBarcodeScanned(serialNumber: String, onResult: (InventoryScanResult) -> Unit) {
        val canonical = IccidValidator.canonicalize(serialNumber)

        // Layer 1: Strict Mexican ICCID format guard (^8952\d{15,16}F?$).
        // Verified against 1,021 real ALTAN SIMs (100% match). Locks to MX (8952 prefix
        // = MII 89 + country MX 52) but does NOT lock to a specific operator sub-code,
        // since the ITU registry is incomplete (e.g., ALTAN's 8952 14 isn't listed but
        // is in active use). Funnel for both camera (ZXing raw text) and manual typing.
        if (!IccidValidator.MX_ICCID_REGEX.matches(canonical)) {
            Timber.w("📦 ICCID rejected (format): raw='$serialNumber' canonical='$canonical' len=${canonical.length}")
            _uiState.update {
                it.copy(error = "Código inválido. Verifica que el sticker empiece con 8952 (México) y que el escaneo no tenga letras raras a la mitad.")
            }
            onResult(InventoryScanResult.Error(serialNumber, "Formato inválido"))
            return
        }

        // Layer 2: Luhn checksum on the digit body (last digit IS the check digit per
        // ISO/IEC 7812). NOT a hard reject — empirically 1 in ~1,000 valid SIMs from
        // carriers fail Luhn (verified: 1020/1021 ALTAN SIMs pass). Instead, surface
        // as a "needs confirmation" warning so the promotor visually verifies the
        // physical sticker before it lands in the batch. This catches single-digit
        // ZXing misreads (the most common failure mode) without losing legit SIMs.
        val digitsForLuhn = canonical.removeSuffix("F")
        if (!IccidValidator.isLuhnValid(digitsForLuhn)) {
            Timber.w("📦 ICCID Luhn warning: $canonical")
            _uiState.update {
                it.copy(
                    pendingLuhnConfirmation = canonical,
                    error = null
                )
            }
            onResult(InventoryScanResult.NeedsConfirmation(canonical))
            return
        }

        proceedToBatchValidation(canonical, onResult)
    }

    /**
     * Confirm a previously-scanned ICCID that failed Luhn but was visually verified
     * by the promotor against the physical sticker. Proceeds with normal duplicate
     * check + backend validation as if Luhn had passed.
     */
    fun confirmLuhnWarning(iccid: String, onResult: (InventoryScanResult) -> Unit) {
        if (_uiState.value.pendingLuhnConfirmation != iccid) {
            Timber.w("📦 confirmLuhnWarning called with stale iccid=$iccid (current=${_uiState.value.pendingLuhnConfirmation})")
            return
        }
        Timber.d("📦 ICCID Luhn-warning confirmed by user: $iccid")
        _uiState.update { it.copy(pendingLuhnConfirmation = null) }
        proceedToBatchValidation(iccid, onResult)
    }

    /**
     * Dismiss a Luhn warning without adding to batch (user wants to rescan).
     */
    fun dismissLuhnWarning() {
        _uiState.update { it.copy(pendingLuhnConfirmation = null) }
    }

    private fun proceedToBatchValidation(canonical: String, onResult: (InventoryScanResult) -> Unit) {
        // Check if already in current batch (fast-path, no network)
        if (_uiState.value.scannedSerialNumbers.contains(canonical)) {
            Timber.d("Barcode already scanned in batch: $canonical")
            onResult(InventoryScanResult.AlreadyScanned(canonical))
            return
        }

        // Skip if this serial number is already being validated (prevents double-tap)
        if (!_validatingSerials.add(canonical)) {
            Timber.d("Barcode already being validated: $canonical")
            onResult(InventoryScanResult.AlreadyScanned(canonical))
            return
        }

        // VALIDATE AGAINST DATABASE before adding
        _uiState.update { it.copy(isValidating = true) }

        val serialNumber = canonical
        viewModelScope.launch {
            try {
                serializedSaleRepository.scanItem(serialNumber)
                    .onSuccess { scanResult ->
                        when (scanResult) {
                            is com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult.Available -> {
                                // Item already registered in DB - show serial number so user can verify
                                _uiState.update {
                                    it.copy(error = "Código $serialNumber ya registrado")
                                }
                                onResult(InventoryScanResult.Duplicate(serialNumber))
                            }
                            is com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult.AlreadySold -> {
                                // Item was sold - show serial number so user can verify
                                _uiState.update {
                                    it.copy(error = "Código $serialNumber ya registrado y vendido")
                                }
                                onResult(InventoryScanResult.Duplicate(serialNumber))
                            }
                            is com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult.NotRegistered -> {
                                // Item is new - add to batch using CURRENT state (not stale capture)
                                _uiState.update {
                                    if (it.scannedSerialNumbers.contains(serialNumber)) {
                                        it // Already added by concurrent scan — skip
                                    } else {
                                        it.copy(
                                            scannedSerialNumbers = it.scannedSerialNumbers + serialNumber,
                                            error = null
                                        )
                                    }
                                }
                                Timber.d("Barcode added to batch: $serialNumber (total: ${_uiState.value.scannedCount})")
                                onResult(InventoryScanResult.Added(serialNumber))
                            }
                            is com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult.ModuleDisabled -> {
                                _uiState.update {
                                    it.copy(error = "El módulo de inventario serializado no está habilitado")
                                }
                                onResult(InventoryScanResult.Duplicate(serialNumber))
                            }
                        }
                    }
                    .onFailure { error ->
                        Timber.e(error, "Failed to validate serial number")
                        _uiState.update {
                            it.copy(error = "Error de conexión al validar ${getItemLabel()}. Intenta de nuevo.")
                        }
                        onResult(InventoryScanResult.Error(serialNumber, error.message ?: "Error de conexión"))
                    }
            } finally {
                _validatingSerials.remove(serialNumber)
                // Update isValidating based on remaining in-flight validations
                _uiState.update { it.copy(isValidating = _validatingSerials.isNotEmpty()) }
            }
        }
    }

    /**
     * Get the singular item label from module config.
     */
    private fun getItemLabel(): String {
        return modulesRepository.getModuleConfig(ModulesRepository.MODULE_SERIALIZED_INVENTORY)
            ?.labels?.item ?: "artículo"
    }

    /**
     * Remove a serial number from the scanned list.
     */
    fun removeSerialNumber(serialNumber: String) {
        _uiState.update {
            it.copy(
                scannedSerialNumbers = it.scannedSerialNumbers.filter { it != serialNumber }
            )
        }
    }

    /**
     * Clear all scanned serial numbers.
     */
    fun clearScannedList() {
        _uiState.update {
            it.copy(
                scannedSerialNumbers = emptyList(),
                registrationResult = null,
                successMessage = null
            )
        }
    }

    /**
     * Register the batch of scanned items.
     */
    fun registerBatch(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        val categoryId = state.selectedCategory?.id

        if (categoryId == null) {
            _uiState.update { it.copy(error = "Selecciona una categoría") }
            return
        }

        if (state.scannedSerialNumbers.isEmpty()) {
            _uiState.update { it.copy(error = "Escanea al menos un artículo") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            serializedSaleRepository.registerBatch(
                categoryId = categoryId,
                serialNumbers = state.scannedSerialNumbers
            )
                .onSuccess { result ->
                    Timber.d("Batch registration success: created=${result.created}, mode=${result.mode}, submitted=${result.submitted}")
                    if (result.mode == "approval") {
                        val n = result.submitted ?: 0
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                successMessage = "Solicitud enviada. $n SIM(s) pendiente(s) de aprobación.",
                                registrationResult = null,
                                scannedSerialNumbers = emptyList()
                            )
                        }
                    } else {
                        // ✅ FIX: Don't show Snackbar when we have registrationResult
                        // The RegistrationResultCard banner provides better feedback
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                registrationResult = result,
                                // Clear list after registration attempt
                                scannedSerialNumbers = emptyList()
                            )
                        }
                    }

                    // Reload categories to update stock counts
                    loadCategories()

                    onSuccess()
                }
                .onFailure { error ->
                    Timber.e(error, "Batch registration failed")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Error al registrar artículos"
                        )
                    }
                }
        }
    }

    /**
     * Dismiss error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Dismiss success message.
     */
    fun dismissSuccess() {
        _uiState.update { it.copy(successMessage = null, registrationResult = null) }
    }

    /**
     * Reset state for new registration session.
     */
    fun resetForNewSession() {
        _uiState.update {
            SerializedInventoryUiState(
                categories = it.categories,
                selectedCategory = it.selectedCategory
            )
        }
    }

}
