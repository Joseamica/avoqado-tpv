package com.jaac.avoqado_tpv.features.ordering.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElement
import com.jaac.avoqado_tpv.features.ordering.domain.FloorElementType
import com.jaac.avoqado_tpv.features.ordering.domain.Table
import com.jaac.avoqado_tpv.features.ordering.domain.TableShape
import com.jaac.avoqado_tpv.features.ordering.domain.TableStatus
import kotlinx.coroutines.launch
import timber.log.Timber
import java.math.BigDecimal

/**
 * Floor Element Creation Mode
 *
 * Represents the current creation mode for floor elements.
 * Each mode has specific gesture handling and UI.
 */
sealed class FloorElementCreationMode {
    /**
     * Wall creation mode - tap and drag to draw line
     */
    data object Wall : FloorElementCreationMode()

    /**
     * Bar counter creation mode - tap to place, then resize
     */
    data object BarCounter : FloorElementCreationMode()

    /**
     * Service area creation mode - tap to place, then resize
     */
    data object ServiceArea : FloorElementCreationMode()

    /**
     * Door creation mode - tap to place, then resize
     */
    data object Door : FloorElementCreationMode()

    /**
     * Label creation mode - tap to place, then enter text
     */
    data object Label : FloorElementCreationMode()
}

/**
 * Floor Plan Canvas Screen
 *
 * Visual floor plan editor showing tables and decorative elements.
 * Uses global coordinate system (0-1) for all elements.
 *
 * **Pattern:** Toast POS / Square POS floor plan
 * - One canvas per venue (NOT per area)
 * - Areas are filters/tabs (logical grouping)
 * - Zoom/pan gestures for navigation
 * - Tap to select/edit elements
 *
 * @param modifier Modifier for customization
 * @param onNavigateBack Navigate back callback
 * @param onTableAssigned Callback when table is selected (passes orderId)
 * @param viewModel FloorPlanViewModel
 */
@Composable
fun FloorPlanCanvasScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    onTableAssigned: (String) -> Unit = {},
    viewModel: FloorPlanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Snackbar for error messages
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Collect error events
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { errorMessage ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = errorMessage,
                    withDismissAction = true
                )
            }
        }
    }

    // Load floor plan on screen launch
    LaunchedEffect(Unit) {
        viewModel.loadFloorPlan()
    }

    FloorPlanCanvasScreenContent(
        modifier = modifier,
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onAreaFilterChange = { areaId -> viewModel.setAreaFilter(areaId) },
        onTableClick = { table ->
            viewModel.selectTable(table)
            // Create order command - MenuViewModel will create the order in the backend
            val orderCommand = "CREATE_TABLE_ORDER:${table.id}"
            Timber.i("🪑 [FloorPlan] Table ${table.number} selected - Creating order for table: ${table.id}")
            onTableAssigned(orderCommand)
        },
        onEditModeToggle = { viewModel.toggleEditMode() },
        onTablePositionUpdate = { tableId, x, y -> viewModel.updateTablePosition(tableId, x, y) },
        onCreateTable = { number, capacity, shape, rotation, positionX, positionY, areaId ->
            viewModel.createTable(number, capacity, shape, rotation, positionX, positionY, areaId)
        },
        onUpdateTable = { tableId, number, capacity, shape, rotation, areaId ->
            viewModel.updateTable(tableId, number, capacity, shape, rotation, areaId)
        },
        onDeleteTable = { tableId -> viewModel.deleteTable(tableId) },
        onCreateFloorElement = { type, posX, posY, width, height, rotation, endX, endY, label, color, areaId ->
            viewModel.createFloorElement(type, posX, posY, width, height, rotation, endX, endY, label, color, areaId)
        },
        onUpdateFloorElement = { elementId, posX, posY, width, height, rotation, endX, endY, label, color, areaId ->
            viewModel.updateFloorElement(elementId, posX, posY, width, height, rotation, endX, endY, label, color, areaId)
        },
        onDeleteFloorElement = { elementId -> viewModel.deleteFloorElement(elementId) },
        onRetry = { viewModel.loadFloorPlan() }
    )
}

/**
 * Floor Plan Canvas Screen Content
 *
 * Stateless UI component that can be previewed without ViewModel.
 */
@Composable
private fun FloorPlanCanvasScreenContent(
    modifier: Modifier = Modifier,
    state: FloorPlanState,
    snackbarHostState: SnackbarHostState,  // For error and confirmation messages
    onNavigateBack: () -> Unit,
    onAreaFilterChange: (String?) -> Unit,
    onTableClick: (Table) -> Unit,
    onEditModeToggle: () -> Unit,
    onTablePositionUpdate: (tableId: String, x: Float, y: Float) -> Unit,
    onCreateTable: (number: String, capacity: Int, shape: TableShape, rotation: Int, positionX: Float?, positionY: Float?, areaId: String?) -> Unit,
    onUpdateTable: (tableId: String, number: String, capacity: Int, shape: TableShape, rotation: Int, areaId: String?) -> Unit,
    onDeleteTable: (tableId: String) -> Unit,
    onCreateFloorElement: (type: FloorElementType, posX: Float, posY: Float, width: Float?, height: Float?, rotation: Int, endX: Float?, endY: Float?, label: String?, color: String?, areaId: String?) -> Unit,
    onUpdateFloorElement: (elementId: String, posX: Float?, posY: Float?, width: Float?, height: Float?, rotation: Int?, endX: Float?, endY: Float?, label: String?, color: String?, areaId: String?) -> Unit,
    onDeleteFloorElement: (elementId: String) -> Unit,
    onRetry: () -> Unit
) {
    var showElementDialog by remember { mutableStateOf(false) }
    var selectedElement by remember { mutableStateOf<FloorElement?>(null) }

    // Inline resize controls (shown when element is tapped in normal mode)
    var resizingElement by remember { mutableStateOf<FloorElement?>(null) }
    var tempWidth by remember { mutableFloatStateOf(0f) }
    var tempHeight by remember { mutableFloatStateOf(0f) }

    // New table/element creation
    var showCreationMenu by remember { mutableStateOf(false) }

    // Table editing
    var showTableEditDialog by remember { mutableStateOf(false) }
    var editingTable by remember { mutableStateOf<Table?>(null) }

    // Table creation
    var showTableCreationDialog by remember { mutableStateOf(false) }

    // Floor element creation mode
    var creationMode by remember { mutableStateOf<FloorElementCreationMode?>(null) }
    var creationStartPoint by remember { mutableStateOf<Offset?>(null) }
    var creationCurrentPoint by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf<Size?>(null) }  // Store canvas size for normalized coords
    var showLabelInputDialog by remember { mutableStateOf(false) }
    var labelCreationPosition by remember { mutableStateOf<Offset?>(null) }

    // Use passed snackbarHostState for both errors and confirmations
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            AvoqadoTopBar(
                title = "Plano de Piso",
                subtitle = when (state) {
                    is FloorPlanState.Success -> {
                        val filteredTables = state.getFilteredTables()
                        if (state.isEditMode) {
                            "Modo Edición: Arrastra las mesas"
                        } else {
                            "${filteredTables.count { it.isAvailable }} disponibles · ${filteredTables.count { it.isOccupied }} ocupadas"
                        }
                    }
                    else -> null
                },
                onNavigationClick = onNavigateBack,
                actions = {
                    // Edit Mode toggle button (only when Success state)
                    if (state is FloorPlanState.Success) {
                        IconButton(onClick = onEditModeToggle) {
                            Icon(
                                imageVector = if (state.isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (state.isEditMode) "Guardar" else "Editar",
                                tint = if (state.isEditMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // FAB always visible - shows creation menu
            if (state is FloorPlanState.Success) {
                FloatingActionButton(
                    onClick = {
                        showCreationMenu = true
                    }
                ) {
                    Icon(Icons.Default.Add, "Agregar")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when (state) {
            is FloorPlanState.Idle -> {
                // Show nothing (loading will appear immediately)
            }

            is FloorPlanState.Loading -> {
                AvoqadoLoadingOverlay(message = "Cargando plano...")
            }

            is FloorPlanState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Area filter tabs
                    AreaFilterTabs(
                        areas = state.areas,
                        selectedAreaId = state.selectedAreaId,
                        onAreaSelected = onAreaFilterChange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Canvas with floor plan
                    FloorPlanCanvas(
                        tables = state.getFilteredTables(),
                        floorElements = state.getFilteredFloorElements(),
                        selectedTable = state.selectedTable,
                        isEditMode = state.isEditMode,
                        onTableQuickTap = { table ->
                            if (state.isEditMode) {
                                // Edit mode: open table editor
                                editingTable = table
                                showTableEditDialog = true
                            } else {
                                // Normal mode: open order screen
                                onTableClick(table)
                            }
                        },
                        onTablePositionUpdate = onTablePositionUpdate,
                        onElementTap = { element ->
                            if (state.isEditMode) {
                                // Edit mode: open element dialog
                                selectedElement = element
                                showElementDialog = true
                            }
                            // Normal mode: do nothing (elements are visual only)
                        },
                        onFloorElementPositionUpdate = { elementId, newPosX, newPosY, newEndX, newEndY ->
                            onUpdateFloorElement(elementId, newPosX, newPosY, null, null, null, newEndX, newEndY, null, null, null)
                        },
                        resizingElementId = resizingElement?.id,  // Pass resizing element for preview
                        resizingTempWidth = if (resizingElement != null) tempWidth else null,
                        resizingTempHeight = if (resizingElement != null) tempHeight else null,
                        creationMode = creationMode,  // Pass creation mode
                        creationStartPoint = creationStartPoint,
                        creationCurrentPoint = creationCurrentPoint,
                        onCreationStartPointChange = { creationStartPoint = it },
                        onCreationCurrentPointChange = { creationCurrentPoint = it },
                        onCanvasSizeChange = { size -> canvasSize = size },  // Store canvas size for wall confirmation
                        onElementCreate = { type, posX, posY, width, height, endX, endY ->
                            // Log coordinates for debugging
                            Timber.i("🎨 [FloorPlan] Creating element: type=$type, posX=$posX, posY=$posY, width=$width, height=$height, endX=$endX, endY=$endY")

                            // Create element via callback
                            onCreateFloorElement(type, posX, posY, width, height, 0, endX, endY, null, null, state.selectedAreaId)

                            // Show confirmation snackbar
                            val elementName = when (type) {
                                FloorElementType.WALL -> "Pared"
                                FloorElementType.BAR_COUNTER -> "Barra"
                                FloorElementType.SERVICE_AREA -> "Área de servicio"
                                FloorElementType.DOOR -> "Puerta"
                                FloorElementType.LABEL -> "Etiqueta"
                            }
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "✅ $elementName creada",
                                    withDismissAction = true
                                )
                            }

                            // Exit creation mode
                            creationMode = null
                            creationStartPoint = null
                            creationCurrentPoint = null
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }

            is FloorPlanState.Error -> {
                ErrorView(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        // Creation mode overlay (ONLY for walls - other elements auto-create in center)
        if (creationMode == FloorElementCreationMode.Wall && state is FloorPlanState.Success) {
            val wallPreviewReady = creationStartPoint != null && creationCurrentPoint != null

            CreationModeOverlay(
                mode = FloorElementCreationMode.Wall,
                wallPreviewReady = wallPreviewReady,
                onCancel = {
                    creationMode = null
                    creationStartPoint = null
                    creationCurrentPoint = null
                },
                onConfirm = {
                    // Confirm wall creation
                    if (creationStartPoint != null &&
                        creationCurrentPoint != null &&
                        canvasSize != null) {

                        Timber.i("🎨 [FloorPlan] User confirmed wall: start=$creationStartPoint, end=$creationCurrentPoint, canvasSize=$canvasSize")

                        val startX = (creationStartPoint!!.x / canvasSize!!.width).coerceIn(0f, 1f)
                        val startY = (creationStartPoint!!.y / canvasSize!!.height).coerceIn(0f, 1f)
                        val endX = (creationCurrentPoint!!.x / canvasSize!!.width).coerceIn(0f, 1f)
                        val endY = (creationCurrentPoint!!.y / canvasSize!!.height).coerceIn(0f, 1f)

                        Timber.i("🎨 [FloorPlan] Wall normalized coords: start=($startX, $startY), end=($endX, $endY)")

                        // Create wall via callback (same as before, but triggered by button)
                        onCreateFloorElement(FloorElementType.WALL, startX, startY, null, null, 0, endX, endY, null, null, state.selectedAreaId)

                        // Show confirmation
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "✅ Pared creada",
                                withDismissAction = true
                            )
                        }

                        // Exit creation mode
                        creationMode = null
                        creationStartPoint = null
                        creationCurrentPoint = null
                        canvasSize = null
                    }
                }
            )
        }

        // Label Input Dialog
        if (showLabelInputDialog && state is FloorPlanState.Success) {
            LabelInputDialog(
                areas = state.areas,
                onDismiss = {
                    showLabelInputDialog = false
                },
                onSave = { labelText, areaId ->
                    showLabelInputDialog = false

                    // Create label at center position (0.5, 0.5)
                    Timber.i("🎨 [FloorPlan] Creating label in center: \"$labelText\"")
                    onCreateFloorElement(
                        FloorElementType.LABEL,
                        0.5f, 0.5f,  // Center position
                        null, null,  // No width/height for labels
                        0,           // No rotation
                        null, null,  // No endX/endY (not a wall)
                        labelText,   // The label text
                        null,        // Use default color
                        areaId       // Area filter
                    )

                    // Switch to the area where the label was created
                    onAreaFilterChange(areaId)

                    // Show success message
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "✅ Etiqueta \"$labelText\" creada - arrástrala donde quieras",
                            withDismissAction = true
                        )
                    }
                }
            )
        }

        // Inline resize controls overlay (when resizing an element)
        if (resizingElement != null && state is FloorPlanState.Success) {
            InlineResizeControls(
                element = resizingElement!!,
                tempWidth = tempWidth,
                tempHeight = tempHeight,
                onWidthChange = { tempWidth = it },
                onHeightChange = { tempHeight = it },
                onSave = {
                    // Save the new dimensions
                    onUpdateFloorElement(
                        resizingElement!!.id,
                        null, null,
                        tempWidth, tempHeight,
                        null, null, null, null, null, null
                    )
                    resizingElement = null
                },
                onCancel = {
                    resizingElement = null
                }
            )
        }

        // Floor Element Editor Dialog
        if (showElementDialog && state is FloorPlanState.Success) {
            FloorElementEditorDialog(
                element = selectedElement,
                currentAreaId = state.selectedAreaId,
                areas = state.areas,
                onDismiss = {
                    showElementDialog = false
                    selectedElement = null
                },
                onSave = { type, label, color, rotation, areaId, width, height ->
                    if (selectedElement == null) {
                        // Create new element with default position (center)
                        val defaultPosX = 0.5f
                        val defaultPosY = 0.5f
                        val defaultEndX = if (type == FloorElementType.WALL) 0.7f else null
                        val defaultEndY = if (type == FloorElementType.WALL) 0.5f else null

                        onCreateFloorElement(type, defaultPosX, defaultPosY, width, height, rotation, defaultEndX, defaultEndY, label, color, areaId)
                    } else {
                        // Update existing element (can update dimensions, label, color, rotation, area)
                        onUpdateFloorElement(selectedElement!!.id, null, null, width, height, rotation, null, null, label, color, areaId)
                    }
                },
                onDelete = if (selectedElement != null) {
                    { elementId -> onDeleteFloorElement(elementId) }
                } else null,
                onEditVisually = if (selectedElement != null &&
                    selectedElement!!.type in listOf(FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR)) {
                    { element ->
                        // Enter inline resize mode
                        resizingElement = element
                        tempWidth = element.width ?: 0.1f
                        tempHeight = element.height ?: 0.1f
                        showElementDialog = false
                    }
                } else null
            )
        }

        // Creation menu (Add Table / Add Element)
        if (showCreationMenu && state is FloorPlanState.Success) {
            CreationMenuDialog(
                onDismiss = { showCreationMenu = false },
                onAddTable = {
                    showCreationMenu = false
                    showTableCreationDialog = true
                },
                onAddWall = {
                    showCreationMenu = false
                    creationMode = FloorElementCreationMode.Wall
                },
                onAddBarCounter = {
                    showCreationMenu = false
                    // Create bar counter IMMEDIATELY in center (0.5, 0.5) - Toast POS pattern
                    Timber.i("🎨 [FloorPlan] Creating bar counter in center (auto-create)")
                    onCreateFloorElement(
                        FloorElementType.BAR_COUNTER,
                        0.5f, // center X
                        0.5f, // center Y
                        0.15f, // default width
                        0.1f,  // default height
                        0,     // rotation
                        null,  // endX (not a wall)
                        null,  // endY (not a wall)
                        null,  // label
                        null,  // color (use default)
                        state.selectedAreaId // current area filter
                    )
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "✅ Barra creada en el centro - arrástrala donde quieras",
                            withDismissAction = true
                        )
                    }
                },
                onAddServiceArea = {
                    showCreationMenu = false
                    // Create service area IMMEDIATELY in center - Toast POS pattern
                    Timber.i("🎨 [FloorPlan] Creating service area in center (auto-create)")
                    onCreateFloorElement(
                        FloorElementType.SERVICE_AREA,
                        0.5f, 0.5f, 0.15f, 0.1f, 0, null, null, null, null, state.selectedAreaId
                    )
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "✅ Área de servicio creada en el centro - arrástrala donde quieras",
                            withDismissAction = true
                        )
                    }
                },
                onAddDoor = {
                    showCreationMenu = false
                    // Create door IMMEDIATELY in center - Toast POS pattern
                    Timber.i("🎨 [FloorPlan] Creating door in center (auto-create)")
                    onCreateFloorElement(
                        FloorElementType.DOOR,
                        0.5f, 0.5f, 0.08f, 0.05f, 0, null, null, null, null, state.selectedAreaId
                    )
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "✅ Puerta creada en el centro - arrástrala donde quieras",
                            withDismissAction = true
                        )
                    }
                },
                onAddLabel = {
                    showCreationMenu = false
                    // Show label text input dialog
                    showLabelInputDialog = true
                }
            )
        }

        // Table Creation Dialog
        if (showTableCreationDialog && state is FloorPlanState.Success) {
            TableCreationDialog(
                areas = state.areas,
                onDismiss = {
                    showTableCreationDialog = false
                },
                onSave = { number, capacity, shape, rotation, areaId ->
                    // Create table at center position (0.5, 0.5) explicitly
                    onCreateTable(number, capacity, shape, rotation, 0.5f, 0.5f, areaId)
                    showTableCreationDialog = false

                    // Switch to the area where the table was created (or "All" if no area)
                    // This ensures the newly created table is immediately visible
                    onAreaFilterChange(areaId)

                    // Show success message
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = "✅ Mesa creada en el centro - arrástrala donde quieras",
                            withDismissAction = true
                        )
                    }
                }
            )
        }

        // Table Edit Dialog
        if (showTableEditDialog && editingTable != null && state is FloorPlanState.Success) {
            TableEditDialog(
                table = editingTable!!,
                areas = state.areas,
                onDismiss = {
                    showTableEditDialog = false
                    editingTable = null
                },
                onSave = { number, capacity, shape, rotation, areaId ->
                    onUpdateTable(editingTable!!.id, number, capacity, shape, rotation, areaId)
                    showTableEditDialog = false
                    editingTable = null
                },
                onDelete = {
                    onDeleteTable(editingTable!!.id)
                    showTableEditDialog = false
                    editingTable = null
                }
            )
        }
    }
}

/**
 * Area Filter Tabs
 *
 * Horizontal scrollable tabs for filtering floor plan by area.
 * First tab is always "Todas" (show all areas).
 *
 * ✅ FIXED: Now uses horizontalScroll for better UX when many areas
 */
@Composable
private fun AreaFilterTabs(
    areas: List<AreaInfo>,
    selectedAreaId: String?,
    onAreaSelected: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())  // ← Makes it scrollable
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // "Todas" tab (null = show all)
        FilterChip(
            selected = selectedAreaId == null,
            onClick = { onAreaSelected(null) },
            label = {
                Text(
                    text = "Todas",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )

        // Individual area tabs
        areas.forEach { area ->
            FilterChip(
                selected = selectedAreaId == area.id,
                onClick = { onAreaSelected(area.id) },
                label = {
                    Text(
                        text = area.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/**
 * Floor Plan Canvas
 *
 * Main canvas that renders tables and floor elements.
 * Supports zoom/pan gestures.
 *
 * **Coordinate System:**
 * - All elements use normalized 0-1 coordinates
 * - Canvas size is determined by available space
 * - Elements are scaled to canvas size
 */
@Composable
private fun FloorPlanCanvas(
    tables: List<Table>,
    floorElements: List<FloorElement>,
    selectedTable: Table?,
    isEditMode: Boolean,
    onTableQuickTap: (Table) -> Unit,
    onTablePositionUpdate: (tableId: String, x: Float, y: Float) -> Unit,
    onElementTap: (FloorElement) -> Unit,
    onFloorElementPositionUpdate: (elementId: String, newPosX: Float, newPosY: Float, newEndX: Float?, newEndY: Float?) -> Unit,
    resizingElementId: String? = null,  // ID of element being resized (for real-time preview)
    resizingTempWidth: Float? = null,   // Temporary width during resize
    resizingTempHeight: Float? = null,  // Temporary height during resize
    creationMode: FloorElementCreationMode? = null,  // Current creation mode
    creationStartPoint: Offset? = null,  // Start point for wall creation
    creationCurrentPoint: Offset? = null,  // Current point for wall preview
    onCreationStartPointChange: (Offset?) -> Unit = {},  // Callback to update start point
    onCreationCurrentPointChange: (Offset?) -> Unit = {},  // Callback to update current point
    onCanvasSizeChange: (Size) -> Unit = {},  // Callback to update canvas size for wall normalization
    onElementCreate: (type: FloorElementType, posX: Float, posY: Float, width: Float?, height: Float?, endX: Float?, endY: Float?) -> Unit = { _, _, _, _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    // Dragged table state (for edit mode)
    var draggedTableId by remember { mutableStateOf<String?>(null) }
    var draggedTableOffset by remember { mutableStateOf(Offset.Zero) }

    // Dragged floor element state (for edit mode)
    var draggedElementId by remember { mutableStateOf<String?>(null) }
    var draggedElementOffset by remember { mutableStateOf(Offset.Zero) }

    val textMeasurer = rememberTextMeasurer()

    // Helper function: Check if point hits a floor element
    fun hitTestFloorElement(element: FloorElement, tapOffset: Offset, canvasWidth: Float, canvasHeight: Float): Boolean {
        val x = element.positionX * canvasWidth
        val y = element.positionY * canvasHeight

        return when (element.type) {
            FloorElementType.WALL -> {
                // Line hit detection
                val endX = (element.endX ?: element.positionX) * canvasWidth
                val endY = (element.endY ?: element.positionY) * canvasHeight
                val hitRadius = 20f // Tap tolerance

                // Distance from point to line segment
                val dx = endX - x
                val dy = endY - y
                val lengthSquared = dx * dx + dy * dy
                if (lengthSquared == 0f) return false

                val t = ((tapOffset.x - x) * dx + (tapOffset.y - y) * dy) / lengthSquared
                val tClamped = t.coerceIn(0f, 1f)
                val closestX = x + tClamped * dx
                val closestY = y + tClamped * dy

                val distSquared = (tapOffset.x - closestX) * (tapOffset.x - closestX) +
                        (tapOffset.y - closestY) * (tapOffset.y - closestY)
                distSquared < hitRadius * hitRadius
            }

            FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR -> {
                // Rectangle hit detection
                val width = (element.width ?: 0.1f) * canvasWidth
                val height = (element.height ?: 0.1f) * canvasHeight
                tapOffset.x >= x && tapOffset.x <= x + width &&
                        tapOffset.y >= y && tapOffset.y <= y + height
            }

            FloorElementType.LABEL -> {
                // Text bounds hit detection (simple rectangle around text position)
                val hitSize = 40f
                tapOffset.x >= x - hitSize && tapOffset.x <= x + hitSize &&
                        tapOffset.y >= y - hitSize && tapOffset.y <= y + hitSize
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isEditMode, creationMode, tables, floorElements) {
                    if (creationMode != null) {
                        // CREATION MODE: Different gestures for different element types
                        when (creationMode) {
                            FloorElementCreationMode.Wall -> {
                                // Wall: Tap-and-drag to draw line
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downPosition = down.position

                                    // Set start point
                                    onCreationStartPointChange(downPosition)
                                    onCreationCurrentPointChange(downPosition)

                                    // Track drag to draw preview line
                                    do {
                                        val event = awaitPointerEvent()
                                        val currentPosition = event.changes.first().position

                                        if (event.changes.first().pressed) {
                                            onCreationCurrentPointChange(currentPosition)
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    // Wall preview is ready - DON'T auto-create
                                    // User must tap "Confirmar" button to create wall
                                    Timber.i("🎨 [FloorPlan] Wall gesture complete - preview ready. Points: start=$creationStartPoint, end=$creationCurrentPoint")
                                    // Keep creationStartPoint and creationCurrentPoint set for preview
                                    // The "Confirmar" button will handle actual creation
                                }
                            }

                            FloorElementCreationMode.BarCounter,
                            FloorElementCreationMode.ServiceArea,
                            FloorElementCreationMode.Door -> {
                                // Tap to place, default size
                                detectTapGestures { offset ->
                                    Timber.i("🎨 [FloorPlan] Tap detected: offset=($offset), canvasSize=(${size.width}, ${size.height})")

                                    val posX = (offset.x / size.width).coerceIn(0f, 1f)
                                    val posY = (offset.y / size.height).coerceIn(0f, 1f)
                                    val defaultWidth = 0.15f
                                    val defaultHeight = 0.1f

                                    Timber.i("🎨 [FloorPlan] Normalized coords: posX=$posX, posY=$posY")

                                    val elementType = when (creationMode) {
                                        FloorElementCreationMode.BarCounter -> FloorElementType.BAR_COUNTER
                                        FloorElementCreationMode.ServiceArea -> FloorElementType.SERVICE_AREA
                                        FloorElementCreationMode.Door -> FloorElementType.DOOR
                                        else -> FloorElementType.SERVICE_AREA
                                    }

                                    onElementCreate(elementType, posX, posY, defaultWidth, defaultHeight, null, null)
                                }
                            }

                            FloorElementCreationMode.Label -> {
                                // Tap to place label - will trigger text input dialog
                                detectTapGestures { offset ->
                                    val posX = (offset.x / size.width).coerceIn(0f, 1f)
                                    val posY = (offset.y / size.height).coerceIn(0f, 1f)

                                    // Store position and trigger label input dialog
                                    onCreationStartPointChange(Offset(posX, posY))
                                }
                            }
                        }
                    } else if (isEditMode) {
                        // EDIT MODE: Floor elements - tap to edit, drag to reposition
                        // Use awaitEachGesture for continuous gesture detection
                        awaitEachGesture {
                            // Wait for initial down event
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPosition = down.position

                            // Check if we hit a floor element
                            val hitElement = floorElements.firstOrNull { element ->
                                hitTestFloorElement(element, downPosition, size.width.toFloat(), size.height.toFloat())
                            }

                            if (hitElement != null) {
                                // Mark element as being dragged
                                draggedElementId = hitElement.id
                                draggedElementOffset = Offset.Zero
                                var totalDistance = 0f
                                var previousPosition = downPosition

                                // Track all pointer events until release
                                do {
                                    val event = awaitPointerEvent()
                                    val currentPosition = event.changes.first().position

                                    if (event.changes.first().pressed) {
                                        // Calculate drag amount
                                        val dragAmount = currentPosition - previousPosition
                                        val distance = kotlin.math.sqrt(
                                            (dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y).toDouble()
                                        ).toFloat()

                                        if (distance > 0) {
                                            // Update drag offset for visual feedback
                                            draggedElementOffset += dragAmount
                                            totalDistance += distance
                                            previousPosition = currentPosition

                                            // Consume the event
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // Gesture ended - determine if tap or drag
                                if (totalDistance < 10f) {
                                    // Tap - open editor
                                    onElementTap(hitElement)
                                } else {
                                    // Drag - update position
                                    val element = floorElements.first { it.id == hitElement.id }
                                    val oldX = element.positionX * size.width
                                    val oldY = element.positionY * size.height
                                    val newX = ((oldX + draggedElementOffset.x) / size.width).coerceIn(0f, 1f)
                                    val newY = ((oldY + draggedElementOffset.y) / size.height).coerceIn(0f, 1f)

                                    val newEndX = if (element.type == FloorElementType.WALL) {
                                        val oldEndX = (element.endX ?: element.positionX) * size.width
                                        ((oldEndX + draggedElementOffset.x) / size.width).coerceIn(0f, 1f)
                                    } else null

                                    val newEndY = if (element.type == FloorElementType.WALL) {
                                        val oldEndY = (element.endY ?: element.positionY) * size.height
                                        ((oldEndY + draggedElementOffset.y) / size.height).coerceIn(0f, 1f)
                                    } else null

                                    onFloorElementPositionUpdate(hitElement.id, newX, newY, newEndX, newEndY)
                                }

                                // Reset state
                                draggedElementId = null
                                draggedElementOffset = Offset.Zero
                            }
                        }
                    } else {
                        // NORMAL MODE:
                        // - Tables: Tap to open order
                        // - Elements: Tap does nothing (visual only)
                        detectTapGestures { offset ->
                            val tableSize = 80f

                            // Check floor elements first (tap does nothing)
                            val tappedElement = floorElements.firstOrNull { element ->
                                hitTestFloorElement(element, offset, size.width.toFloat(), size.height.toFloat())
                            }
                            if (tappedElement != null) {
                                onElementTap(tappedElement) // Will do nothing in normal mode
                            } else {
                                // Check tables (open order)
                                val tappedTable = tables.firstOrNull { table ->
                                    val x = (table.positionX ?: 0.5f) * size.width
                                    val y = (table.positionY ?: 0.5f) * size.height
                                    val dx = offset.x - x
                                    val dy = offset.y - y
                                    (dx * dx + dy * dy) < (tableSize * tableSize)
                                }
                                tappedTable?.let { onTableQuickTap(it) }
                            }
                        }
                    }
                }
                .pointerInput(isEditMode, tables) {
                    if (isEditMode) {
                        // EDIT MODE: Tables - Long-press drag + quick tap
                        var dragStartOffset: Offset? = null
                        var totalDragDistance = 0f
                        var tappedTableForEdit: Table? = null

                        // Long-press drag for tables
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val tableSize = 80f
                                val tappedTable = tables.firstOrNull { table ->
                                    val x = (table.positionX ?: 0.5f) * size.width
                                    val y = (table.positionY ?: 0.5f) * size.height
                                    val dx = offset.x - x
                                    val dy = offset.y - y
                                    (dx * dx + dy * dy) < (tableSize * tableSize)
                                }
                                draggedTableId = tappedTable?.id
                                draggedTableOffset = Offset.Zero
                                dragStartOffset = offset
                                totalDragDistance = 0f
                            },
                            onDrag = { change, dragAmount ->
                                if (draggedTableId != null) {
                                    draggedTableOffset += dragAmount
                                    totalDragDistance += kotlin.math.sqrt(
                                        dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y
                                    )
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                draggedTableId?.let { tableId ->
                                    val table = tables.first { it.id == tableId }
                                    val oldX = (table.positionX ?: 0.5f) * size.width
                                    val oldY = (table.positionY ?: 0.5f) * size.height
                                    val newX = ((oldX + draggedTableOffset.x) / size.width).coerceIn(0f, 1f)
                                    val newY = ((oldY + draggedTableOffset.y) / size.height).coerceIn(0f, 1f)
                                    onTablePositionUpdate(tableId, newX, newY)
                                }
                                draggedTableId = null
                                draggedTableOffset = Offset.Zero
                                dragStartOffset = null
                                totalDragDistance = 0f
                            },
                            onDragCancel = {
                                draggedTableId = null
                                draggedTableOffset = Offset.Zero
                                dragStartOffset = null
                                totalDragDistance = 0f
                            }
                        )
                    }
                }
                .pointerInput(isEditMode, tables) {
                    if (isEditMode) {
                        // Quick tap for tables (open edit dialog)
                        detectTapGestures { offset ->
                            val tableSize = 80f
                            val tappedTable = tables.firstOrNull { table ->
                                val x = (table.positionX ?: 0.5f) * size.width
                                val y = (table.positionY ?: 0.5f) * size.height
                                val dx = offset.x - x
                                val dy = offset.y - y
                                (dx * dx + dy * dy) < (tableSize * tableSize)
                            }
                            tappedTable?.let { onTableQuickTap(it) }
                        }
                    } else {
                        // NORMAL MODE: Tables - Tap to open order
                        detectTapGestures { offset ->
                            val tableSize = 80f
                            val tappedTable = tables.firstOrNull { table ->
                                val x = (table.positionX ?: 0.5f) * size.width
                                val y = (table.positionY ?: 0.5f) * size.height
                                val dx = offset.x - x
                                val dy = offset.y - y
                                (dx * dx + dy * dy) < (tableSize * tableSize)
                            }
                            tappedTable?.let { onTableQuickTap(it) }
                        }
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Update canvas size for wall confirmation (needed to normalize coords)
            onCanvasSizeChange(size)

            // TODO: Implement zoom/pan transformation (Phase 2)
            // For now, render at base coordinates

            // Render floor elements first (background)
            floorElements.forEach { element ->
                // Apply drag offset if this element is being dragged
                val dragOffset = if (element.id == draggedElementId) draggedElementOffset else Offset.Zero

                // Use temporary dimensions if this element is being resized (for real-time preview)
                val elementWidth = if (element.id == resizingElementId && resizingTempWidth != null) {
                    resizingTempWidth
                } else {
                    element.width
                }
                val elementHeight = if (element.id == resizingElementId && resizingTempHeight != null) {
                    resizingTempHeight
                } else {
                    element.height
                }

                drawFloorElement(
                    element = element,
                    isDragging = element.id == draggedElementId,
                    dragOffset = dragOffset,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    textMeasurer = textMeasurer,
                    overrideWidth = elementWidth,  // Real-time preview of width
                    overrideHeight = elementHeight,  // Real-time preview of height
                    isEditMode = isEditMode  // Pass edit mode to control rendering style
                )
            }

            // Render tables
            tables.forEach { table ->
                // Apply drag offset if this table is being dragged
                val dragOffset = if (table.id == draggedTableId) draggedTableOffset else Offset.Zero

                drawTable(
                    table = table,
                    isSelected = table.id == selectedTable?.id,
                    isDragging = table.id == draggedTableId,
                    dragOffset = dragOffset,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    textMeasurer = textMeasurer
                )
            }

            // Draw creation preview (wall line preview)
            if (creationMode == FloorElementCreationMode.Wall &&
                creationStartPoint != null &&
                creationCurrentPoint != null) {
                // Draw preview line for wall creation
                drawLine(
                    color = Color(0xFF424242).copy(alpha = 0.5f),  // Semi-transparent dark gray
                    start = creationStartPoint!!,
                    end = creationCurrentPoint!!,
                    strokeWidth = 8f
                )

                // Draw start and end point indicators
                drawCircle(
                    color = Color(0xFF4CAF50),  // Green for start
                    radius = 12f,
                    center = creationStartPoint!!
                )
                drawCircle(
                    color = Color(0xFF2196F3),  // Blue for current/end
                    radius = 12f,
                    center = creationCurrentPoint!!
                )
            }
        }
    }
}

/**
 * Draw Floor Element
 *
 * Renders a floor element on the canvas based on its type.
 */
private fun DrawScope.drawFloorElement(
    element: FloorElement,
    isDragging: Boolean,
    dragOffset: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    overrideWidth: Float? = null,  // For real-time preview during resize
    overrideHeight: Float? = null,  // For real-time preview during resize
    isEditMode: Boolean = false  // Controls rendering style (solid in edit, outline in normal)
) {
    val baseX = element.positionX * canvasWidth
    val baseY = element.positionY * canvasHeight
    val x = baseX + dragOffset.x
    val y = baseY + dragOffset.y

    // Use override dimensions if provided (for real-time resize preview)
    val actualWidth = overrideWidth ?: element.width
    val actualHeight = overrideHeight ?: element.height

    val elementColor = element.color?.let { Color(android.graphics.Color.parseColor(it)) }
        ?: when (element.type) {
            FloorElementType.WALL -> Color(0xFF424242)         // Dark gray
            FloorElementType.BAR_COUNTER -> Color(0xFF795548)  // Brown (wood)
            FloorElementType.SERVICE_AREA -> Color(0xFFE0E0E0) // Light gray
            FloorElementType.LABEL -> Color(0xFF9E9E9E)        // Medium gray
            FloorElementType.DOOR -> Color(0xFF8D6E63)         // Light brown
        }

    // Control opacity and rendering style based on mode
    val renderColor = when {
        isDragging -> elementColor.copy(alpha = 0.7f)
        !isEditMode -> elementColor.copy(alpha = 0.8f)  // More visible in normal mode (was 0.4f)
        else -> elementColor
    }

    // Render style: outline in normal mode, filled in edit mode
    val useOutlineStyle = !isEditMode && !isDragging

    when (element.type) {
        FloorElementType.WALL -> {
            // Draw line from (positionX, positionY) to (endX, endY)
            val baseEndX = (element.endX ?: element.positionX) * canvasWidth
            val baseEndY = (element.endY ?: element.positionY) * canvasHeight
            val endX = baseEndX + dragOffset.x
            val endY = baseEndY + dragOffset.y
            drawLine(
                color = renderColor,
                start = Offset(x, y),
                end = Offset(endX, endY),
                strokeWidth = 8f
            )
            // Draw white border when dragging
            if (isDragging) {
                drawLine(
                    color = Color.White,
                    start = Offset(x, y),
                    end = Offset(endX, endY),
                    strokeWidth = 12f,
                    alpha = 0.5f
                )
            }
        }

        FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR -> {
            // Draw rectangle (use actualWidth/actualHeight for real-time preview)
            val width = (actualWidth ?: 0.1f) * canvasWidth
            val height = (actualHeight ?: 0.1f) * canvasHeight

            rotate(element.rotation.toFloat(), Offset(x + width / 2, y + height / 2)) {
                // Render as outline in normal mode, filled in edit mode
                if (useOutlineStyle) {
                    // Outline style - doesn't block tables
                    drawRect(
                        color = renderColor,
                        topLeft = Offset(x, y),
                        size = Size(width, height),
                        style = Stroke(width = 6f)  // Thicker stroke for visibility
                    )
                } else {
                    // Filled style - for edit mode
                    drawRect(
                        color = renderColor,
                        topLeft = Offset(x, y),
                        size = Size(width, height)
                    )
                }

                // Draw white border when dragging
                if (isDragging) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x, y),
                        size = Size(width, height),
                        style = Stroke(width = 4f),
                        alpha = 0.8f
                    )
                }

                // Draw label if present
                element.label?.let { label ->
                    val textLayoutResult = textMeasurer.measure(
                        text = label,
                        style = TextStyle(fontSize = 12.sp, color = Color.White)
                    )
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(
                            x + (width - textLayoutResult.size.width) / 2,
                            y + (height - textLayoutResult.size.height) / 2
                        )
                    )
                }
            }
        }

        FloorElementType.LABEL -> {
            // Draw text label
            element.label?.let { label ->
                val textLayoutResult = textMeasurer.measure(
                    text = label,
                    style = TextStyle(fontSize = 16.sp, color = renderColor)
                )
                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(x, y)
                )
                // Draw white background when dragging
                if (isDragging) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.3f),
                        topLeft = Offset(x - 4, y - 4),
                        size = Size(
                            textLayoutResult.size.width.toFloat() + 8,
                            textLayoutResult.size.height.toFloat() + 8
                        )
                    )
                    // Re-draw text on top of background
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(x, y)
                    )
                }
            }
        }
    }
}

/**
 * Draw Table
 *
 * Renders a table on the canvas based on its shape and status.
 */
private fun DrawScope.drawTable(
    table: Table,
    isSelected: Boolean,
    isDragging: Boolean,
    dragOffset: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    val baseX = (table.positionX ?: 0.5f) * canvasWidth
    val baseY = (table.positionY ?: 0.5f) * canvasHeight
    val x = baseX + dragOffset.x
    val y = baseY + dragOffset.y
    val tableSize = 80f // Base size in pixels

    // Color by status (standard colors)
    val tableColor = when (table.status) {
        TableStatus.AVAILABLE -> Color(0xFF4CAF50)  // 🟢 Green
        TableStatus.OCCUPIED -> Color(0xFFF44336)   // 🔴 Red
        TableStatus.RESERVED -> Color(0xFFFFC107)   // 🟡 Yellow
    }

    rotate(table.rotation.toFloat(), Offset(x, y)) {
        when (table.shape) {
            TableShape.ROUND -> {
                drawCircle(
                    color = tableColor,
                    radius = tableSize / 2,
                    center = Offset(x, y)
                )
                if (isSelected) {
                    drawCircle(
                        color = Color.White,
                        radius = tableSize / 2,
                        center = Offset(x, y),
                        style = Stroke(width = 4f)
                    )
                }
            }

            TableShape.SQUARE -> {
                drawRect(
                    color = tableColor,
                    topLeft = Offset(x - tableSize / 2, y - tableSize / 2),
                    size = Size(tableSize, tableSize)
                )
                if (isSelected) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x - tableSize / 2, y - tableSize / 2),
                        size = Size(tableSize, tableSize),
                        style = Stroke(width = 4f)
                    )
                }
            }

            TableShape.RECTANGLE -> {
                val width = tableSize * 1.5f
                val height = tableSize
                drawRect(
                    color = tableColor,
                    topLeft = Offset(x - width / 2, y - height / 2),
                    size = Size(width, height)
                )
                if (isSelected) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(x - width / 2, y - height / 2),
                        size = Size(width, height),
                        style = Stroke(width = 4f)
                    )
                }
            }
        }

        // Draw table number
        val textLayoutResult = textMeasurer.measure(
            text = table.number,
            style = TextStyle(fontSize = 14.sp, color = Color.White)
        )
        drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(
                x - textLayoutResult.size.width / 2,
                y - textLayoutResult.size.height / 2
            )
        )
    }
}

/**
 * Error View
 *
 * Shown when floor plan loading fails.
 */
@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// FLOOR ELEMENT EDITOR DIALOG
// ══════════════════════════════════════════════════════════════════════

/**
 * Floor Element Editor Dialog
 *
 * Simplified dialog for creating or editing floor elements.
 * Position is set by dragging on canvas, not by entering coordinates.
 */
@Composable
fun FloorElementEditorDialog(
    element: FloorElement? = null, // null = create new, non-null = edit existing
    currentAreaId: String? = null, // Current area filter (for "add to this area")
    areas: List<AreaInfo> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (
        type: FloorElementType,
        label: String?,
        color: String?,
        rotation: Int,
        areaId: String?, // null = show in all areas
        width: Float?,
        height: Float?
    ) -> Unit,
    onDelete: ((String) -> Unit)? = null, // Only available when editing
    onEditVisually: ((FloorElement) -> Unit)? = null // Visual resize editor callback
) {
    var selectedType by remember { mutableStateOf(element?.type ?: FloorElementType.WALL) }
    var label by remember { mutableStateOf(element?.label ?: "") }
    var selectedColor by remember { mutableStateOf(element?.color ?: "#424242") }
    var rotation by remember { mutableStateOf(element?.rotation ?: 0) }
    var selectedAreaId by remember { mutableStateOf(element?.areaId ?: currentAreaId) }

    // Dimension controls (only for rectangular elements)
    val defaultWidth = when (selectedType) {
        FloorElementType.BAR_COUNTER -> 0.25f
        FloorElementType.SERVICE_AREA -> 0.15f
        FloorElementType.DOOR -> 0.08f
        else -> null
    }
    val defaultHeight = when (selectedType) {
        FloorElementType.BAR_COUNTER -> 0.08f
        FloorElementType.SERVICE_AREA -> 0.15f
        FloorElementType.DOOR -> 0.05f
        else -> null
    }
    var width by remember { mutableFloatStateOf(element?.width ?: defaultWidth ?: 0.1f) }
    var height by remember { mutableFloatStateOf(element?.height ?: defaultHeight ?: 0.1f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (element == null) "Nuevo Elemento" else "Editar Elemento")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Type selector (only when creating)
                if (element == null) {
                    Text("Tipo", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedType == FloorElementType.WALL,
                                onClick = { selectedType = FloorElementType.WALL },
                                label = { Text("Pared") }
                            )
                            FilterChip(
                                selected = selectedType == FloorElementType.BAR_COUNTER,
                                onClick = { selectedType = FloorElementType.BAR_COUNTER },
                                label = { Text("Barra") }
                            )
                            FilterChip(
                                selected = selectedType == FloorElementType.SERVICE_AREA,
                                onClick = { selectedType = FloorElementType.SERVICE_AREA },
                                label = { Text("Área") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedType == FloorElementType.LABEL,
                                onClick = { selectedType = FloorElementType.LABEL },
                                label = { Text("Etiqueta") }
                            )
                            FilterChip(
                                selected = selectedType == FloorElementType.DOOR,
                                onClick = { selectedType = FloorElementType.DOOR },
                                label = { Text("Puerta") }
                            )
                        }
                    }

                    Text(
                        text = "💡 Arrastra el elemento con el dedo para posicionarlo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Label
                if (selectedType != FloorElementType.WALL) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Etiqueta") },
                        placeholder = { Text("Ej: Cocina, Baño, Barra") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Rotation (for rectangular elements only)
                if (selectedType in listOf(FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR)) {
                    Text("Rotación", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0, 90, 180, 270).forEach { angle ->
                            FilterChip(
                                selected = rotation == angle,
                                onClick = { rotation = angle },
                                label = { Text("${angle}°") }
                            )
                        }
                    }
                }

                // Visual resize option (for rectangular elements only, when editing)
                if (element != null && onEditVisually != null &&
                    selectedType in listOf(FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Button(
                        onClick = {
                            onEditVisually.invoke(element)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Editar Dimensiones")
                    }
                }

                // Color selector
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val colors = listOf(
                        "#424242" to "Gris",
                        "#795548" to "Marrón",
                        "#E0E0E0" to "Claro",
                        "#8D6E63" to "Beige"
                    )
                    colors.forEach { (colorHex, colorName) ->
                        FilterChip(
                            selected = selectedColor == colorHex,
                            onClick = { selectedColor = colorHex },
                            label = { Text(colorName, fontSize = 12.sp) }
                        )
                    }
                }

                // Area assignment
                if (areas.isNotEmpty()) {
                    Text("Mostrar en", style = MaterialTheme.typography.labelMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // "Todas las áreas" option
                        FilterChip(
                            selected = selectedAreaId == null,
                            onClick = { selectedAreaId = null },
                            label = { Text("Todas las áreas") }
                        )

                        // Individual areas
                        areas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = { selectedAreaId = area.id },
                                label = { Text(area.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Only pass width/height for rectangular elements
                    val finalWidth = if (selectedType in listOf(FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR)) {
                        width
                    } else null

                    val finalHeight = if (selectedType in listOf(FloorElementType.BAR_COUNTER, FloorElementType.SERVICE_AREA, FloorElementType.DOOR)) {
                        height
                    } else null

                    onSave(
                        selectedType,
                        label.ifBlank { null },
                        selectedColor,
                        rotation,
                        selectedAreaId,
                        finalWidth,
                        finalHeight
                    )
                    onDismiss()
                }
            ) {
                Text(if (element == null) "Crear" else "Guardar")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Delete button (only when editing)
                if (element != null && onDelete != null) {
                    TextButton(
                        onClick = {
                            onDelete(element.id)
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Eliminar")
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
// CREATION MENU DIALOG
// ══════════════════════════════════════════════════════════════════════

/**
 * Creation Menu Dialog
 *
 * Menu to choose what type of element to add to floor plan.
 * Each button triggers a different creation mode.
 */
@Composable
private fun CreationMenuDialog(
    onDismiss: () -> Unit,
    onAddTable: () -> Unit,
    onAddWall: () -> Unit,
    onAddBarCounter: () -> Unit,
    onAddServiceArea: () -> Unit,
    onAddDoor: () -> Unit,
    onAddLabel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar Elemento") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Table
                Button(
                    onClick = onAddTable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🪑 Agregar Mesa")
                }

                HorizontalDivider()
                Text(
                    "Elementos de Piso",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Wall
                Button(
                    onClick = onAddWall,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🧱 Agregar Pared")
                }

                // Bar Counter
                Button(
                    onClick = onAddBarCounter,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🍺 Agregar Barra")
                }

                // Service Area
                Button(
                    onClick = onAddServiceArea,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🍽️ Agregar Área de Servicio")
                }

                // Door
                Button(
                    onClick = onAddDoor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🚪 Agregar Puerta")
                }

                // Label
                Button(
                    onClick = onAddLabel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🏷️ Agregar Etiqueta")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
// INLINE RESIZE CONTROLS
// ══════════════════════════════════════════════════════════════════════

/**
 * Inline Resize Controls
 *
 * Compact draggable panel with single slider that switches between width/height.
 * User can drag to reposition it to avoid blocking the element being edited.
 * Element updates in real-time as slider moves.
 */
@Composable
private fun InlineResizeControls(
    element: FloorElement,
    tempWidth: Float,
    tempHeight: Float,
    onWidthChange: (Float) -> Unit,
    onHeightChange: (Float) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    // Track which dimension is being edited
    var editingDimension by remember { mutableStateOf("width") } // "width" or "height"

    // Panel position (draggable)
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Determine slider value and callback based on selected dimension
    val sliderValue = if (editingDimension == "width") tempWidth else tempHeight
    val onSliderChange: (Float) -> Unit = if (editingDimension == "width") onWidthChange else onHeightChange

    // Very light overlay to show editing mode (doesn't block view)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.15f))
    ) {
        // Compact draggable control panel
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
                .padding(16.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                },
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(min = 280.dp, max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Drag handle indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp)
                        )
                )

                // Dimension selector + value display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle buttons for dimension selection
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = editingDimension == "width",
                            onClick = { editingDimension = "width" },
                            label = { Text("Ancho", style = MaterialTheme.typography.labelMedium) }
                        )
                        FilterChip(
                            selected = editingDimension == "height",
                            onClick = { editingDimension = "height" },
                            label = { Text("Alto", style = MaterialTheme.typography.labelMedium) }
                        )
                    }

                    // Current value display
                    Text(
                        text = "${(sliderValue * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                // Single slider for selected dimension (real-time preview)
                androidx.compose.material3.Slider(
                    value = sliderValue,
                    onValueChange = onSliderChange, // Updates in real-time as you drag
                    valueRange = 0.05f..0.8f,
                    modifier = Modifier.fillMaxWidth()
                )

                // Action buttons (compact)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Guardar", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * Table Creation Dialog
 *
 * Dialog for creating a new table with properties (number, capacity, shape, rotation, area).
 */
@Composable
private fun TableCreationDialog(
    areas: List<AreaInfo>,
    onDismiss: () -> Unit,
    onSave: (number: String, capacity: Int, shape: TableShape, rotation: Int, areaId: String?) -> Unit
) {
    var number by remember { mutableStateOf("") }
    var capacity by remember { mutableStateOf("4") }
    var selectedShape by remember { mutableStateOf(TableShape.ROUND) }
    var rotation by remember { mutableStateOf("0") }
    var selectedAreaId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear Nueva Mesa") },
        text = {
            // ✅ CRITICAL FIX: Initialize keyboard controller INSIDE AlertDialog scope
            // AlertDialog creates isolated composition scope - controllers initialized outside won't work
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Close keyboard when user taps outside TextFields
                                Timber.d("🎹 [TableDialog] Tapped outside - hiding keyboard")
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Table Number
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Número de Mesa") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Prevent tap from propagating to parent
                            detectTapGestures { /* consume */ }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    )
                )

                // Capacity
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it },
                    label = { Text("Capacidad (personas)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Prevent tap from propagating to parent
                            detectTapGestures { /* consume */ }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next
                    )
                )

                // Shape Selection
                Text(
                    text = "Forma",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TableShape.entries.forEach { shape ->
                        FilterChip(
                            selected = selectedShape == shape,
                            onClick = {
                                selectedShape = shape
                                // Close keyboard when selecting shape
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            },
                            label = {
                                Text(
                                    when (shape) {
                                        TableShape.ROUND -> "Redonda"
                                        TableShape.SQUARE -> "Cuadrada"
                                        TableShape.RECTANGLE -> "Rectangular"
                                    }
                                )
                            }
                        )
                    }
                }

                // Rotation
                OutlinedTextField(
                    value = rotation,
                    onValueChange = { rotation = it },
                    label = { Text("Rotación (grados)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Prevent tap from propagating to parent
                            detectTapGestures { /* consume */ }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Close keyboard when user presses green Done button
                            Timber.d("🎹 [TableDialog] Done button pressed - hiding keyboard")
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    )
                )

                // Area Selection
                if (areas.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Mostrar en",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "Todas" option (null areaId)
                        FilterChip(
                            selected = selectedAreaId == null,
                            onClick = {
                                selectedAreaId = null
                                // Close keyboard when selecting area
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            },
                            label = { Text("Todas") }
                        )

                        // Individual areas
                        areas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = {
                                    selectedAreaId = area.id
                                    // Close keyboard when selecting area
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                },
                                label = { Text(area.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val capacityInt = capacity.toIntOrNull() ?: 4
                    val rotationInt = rotation.toIntOrNull() ?: 0
                    if (number.isNotBlank()) {
                        onSave(number, capacityInt, selectedShape, rotationInt, selectedAreaId)
                    }
                }
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Table Edit Dialog
 *
 * Dialog for editing table properties (number, capacity, shape, rotation, area).
 */
@Composable
private fun TableEditDialog(
    table: Table,
    areas: List<AreaInfo>,
    onDismiss: () -> Unit,
    onSave: (number: String, capacity: Int, shape: TableShape, rotation: Int, areaId: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var number by remember { mutableStateOf(table.number) }
    var capacity by remember { mutableStateOf(table.capacity.toString()) }
    var selectedShape by remember { mutableStateOf(table.shape) }
    var rotation by remember { mutableStateOf(table.rotation.toString()) }
    var selectedAreaId by remember { mutableStateOf(table.areaId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Mesa ${table.number}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Table Number
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Número de Mesa") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Capacity
                OutlinedTextField(
                    value = capacity,
                    onValueChange = { capacity = it },
                    label = { Text("Capacidad (personas)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Shape Selection
                Text(
                    text = "Forma",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TableShape.entries.forEach { shape ->
                        FilterChip(
                            selected = selectedShape == shape,
                            onClick = { selectedShape = shape },
                            label = {
                                Text(
                                    when (shape) {
                                        TableShape.ROUND -> "Redonda"
                                        TableShape.SQUARE -> "Cuadrada"
                                        TableShape.RECTANGLE -> "Rectangular"
                                    }
                                )
                            }
                        )
                    }
                }

                // Rotation
                OutlinedTextField(
                    value = rotation,
                    onValueChange = { rotation = it },
                    label = { Text("Rotación (grados)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Area Selection
                if (areas.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Mostrar en",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "Todas" option (null areaId)
                        FilterChip(
                            selected = selectedAreaId == null,
                            onClick = { selectedAreaId = null },
                            label = { Text("Todas") }
                        )

                        // Individual areas
                        areas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = { selectedAreaId = area.id },
                                label = { Text(area.name) }
                            )
                        }
                    }
                }

                // Delete Button
                if (onDelete != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Button(
                        onClick = {
                            onDelete.invoke()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Eliminar Mesa")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val capacityInt = capacity.toIntOrNull() ?: table.capacity
                    val rotationInt = rotation.toIntOrNull() ?: table.rotation
                    onSave(number, capacityInt, selectedShape, rotationInt, selectedAreaId)
                    onDismiss()
                }
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

/**
 * Label Input Dialog
 *
 * Dialog for entering label text and selecting area.
 */
@Composable
private fun LabelInputDialog(
    areas: List<AreaInfo>,
    onDismiss: () -> Unit,
    onSave: (labelText: String, areaId: String?) -> Unit
) {
    var labelText by remember { mutableStateOf("") }
    var selectedAreaId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crear Etiqueta") },
        text = {
            // ✅ CRITICAL FIX: Initialize keyboard controller INSIDE AlertDialog scope
            // AlertDialog creates isolated composition scope - controllers initialized outside won't work
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // Close keyboard when user taps outside TextField
                                Timber.d("🎹 [LabelDialog] Tapped outside - hiding keyboard")
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Label Text
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Texto de la etiqueta") },
                    placeholder = { Text("Ej: Entrada, Salida, Baños") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Prevent tap from propagating to parent Column
                            detectTapGestures { /* consume tap */ }
                        },
                    singleLine = true,  // ✅ CRITICAL: Must be true for Done button to work
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Close keyboard when user presses green Done button
                            Timber.d("🎹 [LabelDialog] Done button pressed - hiding keyboard")
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    )
                )

                // Area Selection
                if (areas.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "Mostrar en",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // "Todas" option (null areaId)
                        FilterChip(
                            selected = selectedAreaId == null,
                            onClick = {
                                selectedAreaId = null
                                // Close keyboard when selecting area
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            },
                            label = { Text("Todas") }
                        )

                        // Individual areas
                        areas.forEach { area ->
                            FilterChip(
                                selected = selectedAreaId == area.id,
                                onClick = {
                                    selectedAreaId = area.id
                                    // Close keyboard when selecting area
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                },
                                label = { Text(area.name) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (labelText.isNotBlank()) {
                        onSave(labelText.trim(), selectedAreaId)
                    }
                },
                enabled = labelText.isNotBlank()
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEW
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun FloorPlanCanvasScreenPreview() {
    AvoqadoTheme {
        val snackbarHostState = remember { SnackbarHostState() }

        val sampleTables = listOf(
            Table(
                id = "1",
                number = "1",
                capacity = 4,
                positionX = 0.2f,
                positionY = 0.3f,
                shape = TableShape.ROUND,
                rotation = 0,
                status = TableStatus.AVAILABLE,
                currentOrder = null
            ),
            Table(
                id = "2",
                number = "2",
                capacity = 2,
                positionX = 0.5f,
                positionY = 0.3f,
                shape = TableShape.SQUARE,
                rotation = 0,
                status = TableStatus.OCCUPIED,
                currentOrder = null
            ),
            Table(
                id = "3",
                number = "3",
                capacity = 6,
                positionX = 0.2f,
                positionY = 0.7f,
                shape = TableShape.RECTANGLE,
                rotation = 0,
                status = TableStatus.RESERVED,
                currentOrder = null
            )
        )

        val sampleFloorElements = listOf(
            FloorElement(
                id = "wall1",
                type = FloorElementType.WALL,
                positionX = 0.0f,
                positionY = 0.5f,
                endX = 1.0f,
                endY = 0.5f
            ),
            FloorElement(
                id = "bar1",
                type = FloorElementType.BAR_COUNTER,
                positionX = 0.7f,
                positionY = 0.6f,
                width = 0.2f,
                height = 0.1f,
                label = "Barra"
            )
        )

        val sampleAreas = listOf(
            AreaInfo(id = "area1", name = "Interior", tableCount = 2),
            AreaInfo(id = "area2", name = "Terraza", tableCount = 1)
        )

        FloorPlanCanvasScreenContent(
            state = FloorPlanState.Success(
                tables = sampleTables,
                floorElements = sampleFloorElements,
                areas = sampleAreas,
                selectedAreaId = null,
                selectedTable = null,
                isEditMode = false
            ),
            snackbarHostState = snackbarHostState,
            onNavigateBack = {},
            onAreaFilterChange = {},
            onTableClick = {},
            onEditModeToggle = {},
            onTablePositionUpdate = { _, _, _ -> },
            onCreateTable = { _, _, _, _, _, _, _ -> },
            onUpdateTable = { _, _, _, _, _, _ -> },
            onDeleteTable = {},
            onCreateFloorElement = { _, _, _, _, _, _, _, _, _, _, _ -> },
            onUpdateFloorElement = { _, _, _, _, _, _, _, _, _, _, _ -> },
            onDeleteFloorElement = {},
            onRetry = {}
        )
    }
}

/**
 * Creation Mode Overlay
 *
 * Shows instructions and cancel button during element creation.
 */
@Composable
private fun CreationModeOverlay(
    mode: FloorElementCreationMode,
    wallPreviewReady: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit = {}
) {
    // CRITICAL: Use Column instead of Box to avoid blocking canvas touches
    // Box with fillMaxSize() blocks ALL touch events, even with no background
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Instructions card at top
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (mode) {
                        FloorElementCreationMode.Wall -> {
                            if (wallPreviewReady) {
                                "🧱 Suelta el dedo y toca 'Confirmar' para crear la pared"
                            } else {
                                "🧱 Toca y arrastra para dibujar una pared"
                            }
                        }
                        FloorElementCreationMode.BarCounter -> "🍺 Toca donde quieras colocar la barra"
                        FloorElementCreationMode.ServiceArea -> "🍽️ Toca donde quieras colocar el área de servicio"
                        FloorElementCreationMode.Door -> "🚪 Toca donde quieras colocar la puerta"
                        FloorElementCreationMode.Label -> "🏷️ Toca donde quieras colocar la etiqueta"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Spacer in the middle - THIS IS KEY: It doesn't block touches like Box does
        Spacer(modifier = Modifier.weight(1f))

        // Action buttons at bottom (Confirmar + Cancelar for walls, just Cancelar for others)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            // Confirmar button (only for walls when preview is ready)
            if (mode is FloorElementCreationMode.Wall && wallPreviewReady) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Confirmar")
                }
            }

            // Cancel button (always visible)
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cancelar")
            }
        }
    }
}
