package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElement
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementRepository
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementType
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Floor Plan State
 *
 * Represents the state of the floor plan screen.
 */
sealed class FloorPlanState {
    data object Idle : FloorPlanState()
    data object Loading : FloorPlanState()

    data class Success(
        val tables: List<Table>,
        val floorElements: List<FloorElement>,
        val areas: List<AreaInfo>,
        val selectedAreaId: String? = null, // null = show all areas
        val selectedTable: Table? = null,
        val isEditMode: Boolean = false // Edit mode for repositioning tables
    ) : FloorPlanState() {
        /**
         * Get tables filtered by selected area.
         * If selectedAreaId is null, returns all tables.
         */
        fun getFilteredTables(): List<Table> {
            return if (selectedAreaId == null) {
                tables
            } else {
                tables.filter { it.areaId == selectedAreaId }
            }
        }

        /**
         * Get floor elements filtered by selected area.
         * If selectedAreaId is null, returns all elements.
         * Elements with null areaId are always shown (global elements).
         */
        fun getFilteredFloorElements(): List<FloorElement> {
            return if (selectedAreaId == null) {
                floorElements
            } else {
                floorElements.filter { it.areaId == selectedAreaId || it.areaId == null }
            }
        }
    }

    data class Error(val message: String) : FloorPlanState()
}

/**
 * Area Info
 *
 * Simplified area information for filtering.
 */
data class AreaInfo(
    val id: String,
    val name: String,
    val tableCount: Int = 0
)

/**
 * Floor Plan ViewModel
 *
 * Manages floor plan state, table filtering, and user interactions.
 *
 * **Responsibilities:**
 * 1. Load tables and floor elements
 * 2. Filter by area (tab selection)
 * 3. Handle table selection
 * 4. Manage zoom/pan state (TODO: Phase 2)
 * 5. Handle table positioning updates (TODO: Phase 2)
 */
@HiltViewModel
class FloorPlanViewModel @Inject constructor(
    private val tableRepository: TableRepository,
    private val floorElementRepository: FloorElementRepository,
    private val deviceInfoManager: DeviceInfoManager,
    private val secureStorage: SecureStorage,
    private val socketManager: com.jaac.avoqado_tpv.core.data.realtime.SocketManager
) : ViewModel() {

    private val _state = MutableStateFlow<FloorPlanState>(FloorPlanState.Idle)
    val state: StateFlow<FloorPlanState> = _state.asStateFlow()

    // Error events for user-friendly error messages
    private val _errorEvent = MutableSharedFlow<String>()
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    init {
        // 🔌 Start listening to Socket.IO events for real-time table status updates
        listenToSocketEvents()
    }

    /**
     * Listen to Socket.IO Events
     *
     * Reloads floor plan when table status changes (order created/cleared).
     * This ensures all terminals see up-to-date table availability.
     *
     * **Events handled:**
     * - TableStatusChange: When table status changes (AVAILABLE ↔ OCCUPIED)
     */
    private fun listenToSocketEvents() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                when (event) {
                    is com.jaac.avoqado_tpv.core.data.realtime.events.SocketEvent.TableStatusChange -> {
                        Timber.i("🔄 [FloorPlan] Table status changed - reloading floor plan")
                        Timber.d("   TableId: ${event.tableId} | VenueId: ${event.venueId}")

                        // Reload floor plan to show updated table status
                        loadFloorPlan()
                    }
                    else -> {
                        // Ignore other events
                    }
                }
            }
        }
    }

    /**
     * Load Floor Plan
     *
     * Fetches tables, floor elements, and areas from repository.
     */
    fun loadFloorPlan() {
        viewModelScope.launch {
            _state.value = FloorPlanState.Loading

            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                _state.value = FloorPlanState.Error(
                    "No hay venue configurado.\\n\\nVerifique la configuración del terminal."
                )
                return@launch
            }

            Timber.i("🗺️ [FloorPlan] Loading floor plan for venue: $venueId")

            val tablesResult = tableRepository.getTables(venueId)
            val elementsResult = floorElementRepository.getFloorElements(venueId)

            when {
                tablesResult is Result.Error -> {
                    val errorMessage = tablesResult.exception.userMessage
                    Timber.e(tablesResult.exception, "❌ [FloorPlan] Failed to load tables")
                    _state.value = FloorPlanState.Error(errorMessage)
                }

                elementsResult is Result.Error -> {
                    val errorMessage = elementsResult.exception.userMessage
                    Timber.e(elementsResult.exception, "❌ [FloorPlan] Failed to load floor elements")
                    _state.value = FloorPlanState.Error(errorMessage)
                }

                tablesResult is Result.Success && elementsResult is Result.Success -> {
                    val tables = tablesResult.data
                    val floorElements = elementsResult.data

                    Timber.i("✅ [FloorPlan] Loaded ${tables.size} tables and ${floorElements.size} floor elements")

                    // Extract unique areas from tables
                    val areas = tables
                        .filter { it.areaId != null && it.areaName != null }
                        .groupBy { it.areaId!! }
                        .map { (areaId, tablesInArea) ->
                            AreaInfo(
                                id = areaId,
                                name = tablesInArea.first().areaName!!,
                                tableCount = tablesInArea.size
                            )
                        }

                    _state.value = FloorPlanState.Success(
                        tables = tables,
                        floorElements = floorElements,
                        areas = areas,
                        selectedAreaId = null,
                        selectedTable = null
                    )
                }
            }
        }
    }

    /**
     * Set Area Filter
     *
     * Changes the selected area filter.
     * Pass null to show all areas.
     */
    fun setAreaFilter(areaId: String?) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        Timber.i("🔍 [FloorPlan] Area filter changed: ${areaId ?: "All"}")

        _state.value = currentState.copy(
            selectedAreaId = areaId,
            selectedTable = null // Clear selection when changing filter
        )
    }

    /**
     * Select Table
     *
     * Selects a table on the floor plan.
     */
    fun selectTable(table: Table) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        Timber.i("🪑 [FloorPlan] Table selected: ${table.number}")

        _state.value = currentState.copy(
            selectedTable = if (currentState.selectedTable?.id == table.id) {
                null // Deselect if already selected
            } else {
                table // Select new table
            }
        )
    }

    /**
     * Toggle Edit Mode
     *
     * Enables/disables edit mode for table repositioning.
     */
    fun toggleEditMode() {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        val newEditMode = !currentState.isEditMode
        Timber.i("✏️ [FloorPlan] Edit mode: ${if (newEditMode) "ON" else "OFF"}")

        _state.value = currentState.copy(
            isEditMode = newEditMode,
            selectedTable = null // Clear selection when toggling edit mode
        )
    }

    /**
     * Update Table Position
     *
     * Updates a table's position on the floor plan (0-1 coordinates).
     * Persists to backend immediately with optimistic update.
     */
    fun updateTablePosition(tableId: String, newX: Float, newY: Float) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        Timber.i("📍 [FloorPlan] Updating table position: $tableId to ($newX, $newY)")

        // Store original tables for rollback on error
        val originalTables = currentState.tables

        // Update table position in local state immediately (optimistic update)
        val updatedTables = currentState.tables.map { table ->
            if (table.id == tableId) {
                table.copy(positionX = newX, positionY = newY)
            } else {
                table
            }
        }

        _state.value = currentState.copy(tables = updatedTables)

        // Persist to backend
        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                // Rollback to original state
                _state.value = currentState.copy(tables = originalTables)
                return@launch
            }

            when (val result = tableRepository.updateTablePosition(venueId, tableId, newX, newY)) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Table position persisted successfully")
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to persist table position")
                    // Rollback to original state
                    val stateBeforeRollback = _state.value
                    if (stateBeforeRollback is FloorPlanState.Success) {
                        _state.value = stateBeforeRollback.copy(tables = originalTables)
                    }
                    // Note: Error message is logged but not shown to user to avoid interrupting drag & drop
                    // User will notice if position resets on next load
                }
            }
        }
    }

    /**
     * Create Floor Element
     *
     * Creates a new floor element and adds it to the floor plan.
     */
    fun createFloorElement(
        type: FloorElementType,
        positionX: Float,
        positionY: Float,
        width: Float? = null,
        height: Float? = null,
        rotation: Int = 0,
        endX: Float? = null,
        endY: Float? = null,
        label: String? = null,
        color: String? = null,
        areaId: String? = null
    ) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("🎨 [FloorPlan] Creating floor element type $type")

            when (val result = floorElementRepository.createFloorElement(
                venueId = venueId,
                type = type,
                positionX = positionX,
                positionY = positionY,
                width = width,
                height = height,
                rotation = rotation,
                endX = endX,
                endY = endY,
                label = label,
                color = color,
                areaId = areaId
            )) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Floor element created: ${result.data.id}")
                    // Add new element to current state
                    _state.value = currentState.copy(
                        floorElements = currentState.floorElements + result.data
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to create floor element")
                    // Emit user-friendly error
                    val errorMessage = "Error al crear elemento: ${result.exception.userMessage}"
                    _errorEvent.emit(errorMessage)
                }
            }
        }
    }

    /**
     * Update Floor Element
     *
     * Updates an existing floor element.
     */
    fun updateFloorElement(
        elementId: String,
        positionX: Float? = null,
        positionY: Float? = null,
        width: Float? = null,
        height: Float? = null,
        rotation: Int? = null,
        endX: Float? = null,
        endY: Float? = null,
        label: String? = null,
        color: String? = null,
        areaId: String? = null
    ) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("🎨 [FloorPlan] Updating floor element: $elementId")

            when (val result = floorElementRepository.updateFloorElement(
                venueId = venueId,
                elementId = elementId,
                positionX = positionX,
                positionY = positionY,
                width = width,
                height = height,
                rotation = rotation,
                endX = endX,
                endY = endY,
                label = label,
                color = color,
                areaId = areaId
            )) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Floor element updated: ${result.data.id}")
                    // Update element in current state
                    _state.value = currentState.copy(
                        floorElements = currentState.floorElements.map { element ->
                            if (element.id == elementId) result.data else element
                        }
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to update floor element")
                }
            }
        }
    }

    /**
     * Delete Floor Element
     *
     * Deletes a floor element from the floor plan.
     */
    fun deleteFloorElement(elementId: String) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("🎨 [FloorPlan] Deleting floor element: $elementId")

            when (val result = floorElementRepository.deleteFloorElement(venueId, elementId)) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Floor element deleted: $elementId")
                    // Remove element from current state
                    _state.value = currentState.copy(
                        floorElements = currentState.floorElements.filter { it.id != elementId }
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to delete floor element")
                }
            }
        }
    }

    /**
     * Create Table
     *
     * Creates a new table and adds it to the floor plan.
     */
    fun createTable(
        number: String,
        capacity: Int,
        shape: TableShape,
        rotation: Int = 0,
        positionX: Float? = null,
        positionY: Float? = null,
        areaId: String? = null
    ) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("➕ [FloorPlan] Creating table: $number at position ($positionX, $positionY) in area: $areaId")

            when (val result = tableRepository.createTable(
                venueId = venueId,
                number = number,
                capacity = capacity,
                shape = shape,
                rotation = rotation,
                positionX = positionX,
                positionY = positionY,
                areaId = areaId
            )) {
                is Result.Success -> {
                    val newTable = result.data
                    Timber.i("✅ [FloorPlan] Table created: ${newTable.number} (id: ${newTable.id}) at (${newTable.positionX}, ${newTable.positionY}) in area: ${newTable.areaId}")
                    Timber.i("📊 [FloorPlan] Total tables in state: ${currentState.tables.size} → ${currentState.tables.size + 1}")

                    // Add new table to current state
                    _state.value = currentState.copy(
                        tables = currentState.tables + newTable
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to create table")
                    // Show error (could add error state to UI)
                }
            }
        }
    }

    /**
     * Update Table
     *
     * Updates table properties (number, capacity, shape, rotation, areaId).
     */
    fun updateTable(
        tableId: String,
        number: String,
        capacity: Int,
        shape: TableShape,
        rotation: Int,
        areaId: String?
    ) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("🔧 [FloorPlan] Updating table: $tableId")

            when (val result = tableRepository.updateTable(
                venueId = venueId,
                tableId = tableId,
                number = number,
                capacity = capacity,
                shape = shape,
                rotation = rotation,
                areaId = areaId
            )) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Table updated: ${result.data.number}")
                    // Update table in current state
                    _state.value = currentState.copy(
                        tables = currentState.tables.map { table ->
                            if (table.id == tableId) result.data else table
                        }
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to update table")
                    // Show error (could add error state to UI)
                }
            }
        }
    }

    /**
     * Delete Table
     *
     * Deletes a table from the floor plan (soft delete).
     */
    fun deleteTable(tableId: String) {
        val currentState = _state.value
        if (currentState !is FloorPlanState.Success) return

        viewModelScope.launch {
            val venueId = deviceInfoManager.getVenueId() ?: run {
                Timber.e("❌ No venueId configured")
                return@launch
            }

            Timber.i("🗑️ [FloorPlan] Deleting table: $tableId")

            when (val result = tableRepository.deleteTable(venueId, tableId)) {
                is Result.Success -> {
                    Timber.i("✅ [FloorPlan] Table deleted: $tableId")
                    // Remove table from current state
                    _state.value = currentState.copy(
                        tables = currentState.tables.filter { it.id != tableId }
                    )
                }

                is Result.Error -> {
                    Timber.e(result.exception, "❌ [FloorPlan] Failed to delete table")
                    // Show error (could add error state to UI)
                }
            }
        }
    }

    /**
     * Retry
     *
     * Retries loading the floor plan after an error.
     */
    fun retry() {
        Timber.i("🔄 [FloorPlan] Retrying...")
        loadFloorPlan()
    }
}
