package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.local.dao.TableDao
import com.jaac.avoqado_tpv.core.data.local.mappers.toTableDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toTableEntities
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Table Service State
 *
 * Represents the different states of the table service screen.
 */
sealed class TableServiceState {
    /**
     * Initial state - no data loaded yet
     */
    data object Idle : TableServiceState()

    /**
     * Loading tables from backend
     */
    data object Loading : TableServiceState()

    /**
     * Tables loaded successfully
     *
     * @param tables List of tables with current status
     * @param selectedTable Currently selected table (null if none)
     */
    data class Success(
        val tables: List<Table>,
        val selectedTable: Table? = null
    ) : TableServiceState()

    /**
     * Assigning a table and creating/getting order
     *
     * @param table Table being assigned
     * @param covers Number of people
     */
    data class AssigningTable(
        val table: Table,
        val covers: Int
    ) : TableServiceState()

    /**
     * Error loading or assigning tables
     *
     * @param message User-friendly error message
     */
    data class Error(
        val message: String
    ) : TableServiceState()
}

/**
 * Table Service ViewModel
 *
 * Manages state for the table service screen (floor plan).
 *
 * **Responsibilities:**
 * 1. Load tables from backend
 * 2. Handle table selection
 * 3. Assign tables and create orders
 * 4. Navigate to order editor
 *
 * **Pattern:** Similar to Square POS table management
 */
@HiltViewModel
class TableServiceViewModel @Inject constructor(
    private val tableRepository: TableRepository,
    private val tableDao: TableDao,
    private val deviceInfoManager: DeviceInfoManager,
    private val secureStorage: SecureStorage
) : ViewModel() {

    private val _state = MutableStateFlow<TableServiceState>(TableServiceState.Idle)
    val state: StateFlow<TableServiceState> = _state.asStateFlow()

    /**
     * Get current venue ID from device info
     */
    private fun getVenueId(): String? {
        return deviceInfoManager.getVenueId()
    }

    /**
     * Get current staff ID from session
     */
    private fun getStaffId(): String? {
        return secureStorage.getStaffId()
    }

    /**
     * Load tables from backend
     */
    fun loadTables() {
        viewModelScope.launch {
            val venueId = getVenueId()
            if (venueId == null) {
                Timber.e("❌ [TableServiceVM] No venue ID found")
                _state.value = TableServiceState.Error(
                    "Error de configuración.\n\n" +
                    "No se pudo obtener el ID del venue.\n" +
                    "Por favor, reinicie la aplicación."
                )
                return@launch
            }

            val previousState = _state.value as? TableServiceState.Success
            val selectedTableId = previousState?.selectedTable?.id

            var cachedTables: List<Table> = emptyList()
            var latestCacheAt: Long? = null

            withContext(Dispatchers.IO) {
                cachedTables = tableDao.getTables(venueId).toTableDomain()
                latestCacheAt = tableDao.getLatestCacheTimestamp(venueId)
            }

            val hasCache = cachedTables.isNotEmpty()
            if (hasCache) {
                val selectedTable = cachedTables.firstOrNull { it.id == selectedTableId }
                _state.value = TableServiceState.Success(tables = cachedTables, selectedTable = selectedTable)

                val minutesAgo = latestCacheAt?.let {
                    ((System.currentTimeMillis() - it) / 60000L).coerceAtLeast(0)
                }
                Timber.i("🗄️ [TableServiceVM] Cache hit | tables=${cachedTables.size} | cachedMinutes=${minutesAgo ?: -1}")
            } else {
                Timber.i("🪑 [TableServiceVM] Cache miss - loading from backend | venue=$venueId")
                _state.value = TableServiceState.Loading
            }

            when (val result = tableRepository.getTables(venueId)) {
                is Result.Success -> {
                    Timber.i("✅ [TableServiceVM] Tables loaded: ${result.data.size}")

                    val cachedAt = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        tableDao.upsertTables(result.data.toTableEntities(venueId, cachedAt))
                    }

                    val selectedTable = result.data.firstOrNull { it.id == selectedTableId }
                    _state.value = TableServiceState.Success(tables = result.data, selectedTable = selectedTable)
                }
                is Result.Error -> {
                    Timber.e(result.exception, "❌ [TableServiceVM] Error loading tables")
                    if (!hasCache) {
                        _state.value = TableServiceState.Error(
                            result.exception.message ?: "Error al cargar las mesas"
                        )
                    } else {
                        Timber.w("⚠️ [TableServiceVM] Using cached tables due to backend error")
                    }
                }
            }
        }
    }

    /**
     * Select a table (for preview or action)
     *
     * @param table Table to select (null to deselect)
     */
    fun selectTable(table: Table?) {
        val currentState = _state.value
        if (currentState is TableServiceState.Success) {
            _state.value = currentState.copy(selectedTable = table)
        }
    }

    /**
     * Assign a table and create/get order
     *
     * **Behavior:**
     * - If table is AVAILABLE → Creates new order
     * - If table is OCCUPIED → Returns existing order
     * - Navigates to order editor after success
     *
     * @param table Table to assign
     * @param covers Number of people at the table
     * @param onSuccess Callback with order ID to navigate
     */
    fun assignTable(table: Table, covers: Int, onSuccess: (orderId: String) -> Unit) {
        viewModelScope.launch {
            val venueId = getVenueId()
            val staffId = getStaffId()

            if (venueId == null || staffId == null) {
                Timber.e("❌ [TableServiceVM] Missing venue ID or staff ID")
                _state.value = TableServiceState.Error(
                    "Error de sesión.\n\n" +
                    "No se pudo obtener la información de sesión.\n" +
                    "Por favor, inicie sesión nuevamente."
                )
                return@launch
            }

            Timber.i("🪑 [TableServiceVM] Assigning table ${table.number} with $covers covers")
            _state.value = TableServiceState.AssigningTable(table, covers)

            when (val result = tableRepository.assignTable(venueId, table.id, staffId, covers)) {
                is Result.Success -> {
                    val assignResult = result.data
                    Timber.i("✅ [TableServiceVM] Table assigned: ${assignResult.orderNumber} (new: ${assignResult.isNewOrder})")

                    // Navigate to order editor
                    onSuccess(assignResult.orderId)

                    // Reload tables to update status
                    loadTables()
                }
                is Result.Error -> {
                    Timber.e(result.exception, "❌ [TableServiceVM] Error assigning table")
                    _state.value = TableServiceState.Error(
                        result.exception.message ?: "Error al asignar la mesa"
                    )
                }
            }
        }
    }

    /**
     * Retry loading tables after error
     */
    fun retry() {
        loadTables()
    }
}
