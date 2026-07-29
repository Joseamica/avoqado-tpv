package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoPullToRefresh
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.components.ScreenProfile
import com.jaac.avoqado_tpv.core.presentation.components.rememberResponsiveSizes
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement

/**
 * Mesas — plano en vivo (Plan C, Task 6). Toggle canvas ⇄ lista EN la misma
 * pantalla (requisito explícito del founder). El canvas es de SOLO LECTURA
 * en la TPV — nada de `detectDragGestures`; editar el plano vive en el
 * dashboard (regla dura del plan, §"En la TPV el canvas NO se edita").
 *
 * Offline-first de lectura: si el refresco falla pero ya había un plano
 * cargado, [TablesViewModel] sigue mostrando ese plano
 * ([TablesUiState.isOffline]) — esta pantalla nunca reemplaza un plano
 * funcionando con una pantalla de error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: TablesViewModel = hiltViewModel(),
) {
    val sizes = rememberResponsiveSizes()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()

    LaunchedEffect(sizes.screenProfile) {
        viewModel.initialize(sizes.screenProfile)
    }

    var selectedTable by remember { mutableStateOf<DiningTable?>(null) }

    TablesScreenContent(
        modifier = modifier,
        sizes = sizes,
        uiState = uiState,
        viewMode = viewMode,
        onNavigateBack = onNavigateBack,
        onViewModeChange = viewModel::setViewMode,
        onAreaFilterChange = viewModel::setAreaFilter,
        onRetry = viewModel::refresh,
        onTableClick = { selectedTable = it },
    )

    selectedTable?.let { table ->
        TableSheet(
            table = table,
            isMySession = table.id == uiState.activeSessionTableId,
            onDismiss = { selectedTable = null },
        )
    }
}

@Composable
private fun TablesScreenContent(
    modifier: Modifier = Modifier,
    sizes: ResponsiveSizes,
    uiState: TablesUiState,
    viewMode: TableViewMode,
    onNavigateBack: () -> Unit,
    onViewModeChange: (TableViewMode) -> Unit,
    onAreaFilterChange: (String?) -> Unit,
    onRetry: () -> Unit,
    onTableClick: (DiningTable) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                AvoqadoTopBar(
                    title = "Mesas",
                    subtitle = tablesSubtitle(uiState.tables),
                    onNavigationClick = onNavigateBack,
                    actions = {
                        TableViewModeToggle(mode = viewMode, onChange = onViewModeChange)
                    },
                )
                if (uiState.isOffline) {
                    OfflinePlanBanner(onRetry = onRetry)
                }
                if (viewMode == TableViewMode.LIST && uiState.areas.size > 1) {
                    AreaFilterRow(
                        areas = uiState.areas,
                        selectedAreaId = uiState.selectedAreaId,
                        onAreaSelected = onAreaFilterChange,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            uiState.isLoading && uiState.tables.isEmpty() -> {
                AvoqadoFullScreenLoading(
                    message = "Cargando plano de mesas…",
                    modifier = Modifier.padding(padding),
                )
            }

            uiState.errorMessage != null && uiState.tables.isEmpty() -> {
                TablesErrorState(
                    message = uiState.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.padding(padding),
                )
            }

            uiState.tables.isEmpty() -> {
                TablesEmptyState(modifier = Modifier.padding(padding))
            }

            viewMode == TableViewMode.CANVAS -> {
                // Pull-to-refresh: el mesero también puede pedir un refresco a
                // mano (además del debounce por socket) — mismo componente que
                // Reportes/Pagos/Turnos (AvoqadoPullToRefresh.kt), mismo lenguaje.
                AvoqadoPullToRefresh(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRetry,
                    modifier = Modifier.padding(padding),
                ) {
                    TableFloorCanvas(
                        tables = uiState.tables,
                        floorElements = uiState.floorElements,
                        activeSessionTableId = uiState.activeSessionTableId,
                        chipSize = sizes.tableChipSize(),
                        onTableClick = onTableClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            else -> {
                AvoqadoPullToRefresh(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = onRetry,
                    modifier = Modifier.padding(padding),
                ) {
                    TableListView(
                        tables = uiState.tablesForSelectedArea(),
                        areas = uiState.areas,
                        selectedAreaId = uiState.selectedAreaId,
                        activeSessionTableId = uiState.activeSessionTableId,
                        onTableClick = onTableClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun tablesSubtitle(tables: List<DiningTable>): String? {
    if (tables.isEmpty()) return null
    val counts = tables.groupingBy { it.displayStatus() }.eachCount()
    val parts = buildList {
        counts[TableDisplayStatus.FREE]?.let { add("$it libre${if (it == 1) "" else "s"}") }
        counts[TableDisplayStatus.OCCUPIED]?.let { add("$it ocupada${if (it == 1) "" else "s"}") }
        counts[TableDisplayStatus.RESERVED]?.let { add("$it reservada${if (it == 1) "" else "s"}") }
        counts[TableDisplayStatus.NEEDS_ATTENTION]?.let { add("$it en atención") }
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun ResponsiveSizes.tableChipSize(): Dp = when (screenProfile) {
    ScreenProfile.CompactSquare, ScreenProfile.CompactPortrait -> 56.dp
    ScreenProfile.RegularPortrait -> 72.dp
}

// ══════════════════════════════════════════════════════════════════════
// TOGGLE — vive en la topbar, requisito explícito del founder
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun TableViewModeToggle(mode: TableViewMode, onChange: (TableViewMode) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        ToggleSegment(
            selected = mode == TableViewMode.CANVAS,
            icon = Icons.Default.GridView,
            contentDescription = "Ver plano",
            onClick = { onChange(TableViewMode.CANVAS) },
        )
        ToggleSegment(
            selected = mode == TableViewMode.LIST,
            icon = Icons.AutoMirrored.Filled.ViewList,
            contentDescription = "Ver lista",
            onClick = { onChange(TableViewMode.LIST) },
        )
    }
}

@Composable
private fun ToggleSegment(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    // Container/onContainer, NUNCA primary/onPrimary crudo: en tema claro
    // AvoqadoGreen + blanco da ~2.5:1 de contraste (falla hasta el mínimo de
    // texto grande). El par container/onPrimaryContainer de Material 3 está
    // diseñado para AA garantizado en los dos temas — es el problema exacto
    // que ya mordió a este repo (1.96:1 → 16.17:1).
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════
// OFFLINE BANNER — mismo lenguaje visual que ConnectionBanner.kt
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun OfflinePlanBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.offlineOrange)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.padding(start = Spacing.Space2))
            Text(
                text = "Mostrando el último plano guardado",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
        }
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.padding(start = 4.dp))
            Text("Reintentar", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// AREA FILTER — mismo patrón que FloorPlanCanvasScreen.kt (legacy)
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun AreaFilterRow(
    areas: List<AreaSummary>,
    selectedAreaId: String?,
    onAreaSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Space4, vertical = Spacing.Space2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
    ) {
        FilterChip(
            selected = selectedAreaId == null,
            onClick = { onAreaSelected(null) },
            label = { Text("Todas", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        areas.forEach { area ->
            FilterChip(
                selected = selectedAreaId == area.id,
                onClick = { onAreaSelected(area.id) },
                label = { Text("${area.name} (${area.tableCount})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// CANVAS — solo lectura, coordenadas normalizadas 0–1 del server
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun TableFloorCanvas(
    tables: List<DiningTable>,
    floorElements: List<FloorElement>,
    activeSessionTableId: String?,
    chipSize: Dp,
    onTableClick: (DiningTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val positioned = remember(tables) { tables.filter { it.hasPosition } }
    val unpositioned = remember(tables) { tables.filter { !it.hasPosition } }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val chipPx = with(density) { chipSize.toPx() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                floorElements.forEach { element -> drawFloorElementReadOnly(element, size.width, size.height) }
            }

            positioned.forEach { table ->
                val offsetXDp = with(density) { (table.positionX!! * widthPx - chipPx / 2f).toDp() }
                val offsetYDp = with(density) { (table.positionY!! * heightPx - chipPx / 2f).toDp() }
                Box(
                    modifier = Modifier.offset(x = offsetXDp, y = offsetYDp),
                ) {
                    TableChip(
                        table = table,
                        size = chipSize,
                        isMySession = table.id == activeSessionTableId,
                        onClick = { onTableClick(table) },
                    )
                }
            }
        }

        if (unpositioned.isNotEmpty()) {
            UnpositionedTablesNotice(count = unpositioned.size)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloorElementReadOnly(
    element: FloorElement,
    canvasWidth: Float,
    canvasHeight: Float,
) {
    val color = element.color?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrDefault(Color(0xFF9E9E9E))
    } ?: Color(0xFF9E9E9E)
    val x = element.positionX * canvasWidth
    val y = element.positionY * canvasHeight

    when (element.type) {
        "WALL" -> {
            val endX = (element.endX ?: element.positionX) * canvasWidth
            val endY = (element.endY ?: element.positionY) * canvasHeight
            drawLine(color = color.copy(alpha = 0.6f), start = Offset(x, y), end = Offset(endX, endY), strokeWidth = 6f)
        }

        "BAR_COUNTER", "SERVICE_AREA", "DOOR" -> {
            val width = (element.width ?: 0.1f) * canvasWidth
            val height = (element.height ?: 0.1f) * canvasHeight
            drawRect(
                color = color.copy(alpha = 0.5f),
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(width, height),
                style = Stroke(width = 4f),
            )
        }

        "LABEL" -> {
            // Las etiquetas de texto libre son decorativas; se omiten en el
            // canvas de solo-lectura de la TPV para no competir por espacio
            // con las mesas en pantallas chicas — el nombre del área ya se ve
            // en el filtro y en el sheet de cada mesa.
        }
    }
}

@Composable
private fun UnpositionedTablesNotice(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = Spacing.Space4, vertical = Spacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.padding(start = Spacing.Space2))
        Text(
            text = "$count mesa${if (count == 1) "" else "s"} sin ubicar en el plano · cámbiate a Lista para verla${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TableChip(
    table: DiningTable,
    size: Dp,
    isMySession: Boolean,
    onClick: () -> Unit,
) {
    val status = table.displayStatus()
    val shape = if (table.shape == "ROUND") CircleShape else RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .size(size)
            // Halo de "tu mesa": aro extra en `primary`, independiente del
            // color de estado — para que se distinga incluso si el status es
            // el mismo verde de "libre" no aplica (una mesa con sesión activa
            // siempre está OCCUPIED, pero el halo la hace inconfundible de un
            // vistazo entre el resto de mesas ocupadas).
            .then(
                if (isMySession) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .padding(if (isMySession) 3.dp else 0.dp)
            .clip(shape)
            // Relleno NEUTRAL + tinte translúcido del color de estado — nunca
            // un fill sólido saturado con texto encima: varios de nuestros
            // colores semánticos (verde, ámbar) no llegan a 3:1 con texto
            // blanco. El número de mesa se pinta siempre en `onSurface`,
            // contraste garantizado por el theme sin importar el estado.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .background(status.color().copy(alpha = 0.22f))
            .border(2.5.dp, status.color(), shape)
            .pointerInput(table.id) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = table.number,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            val caption = if (status == TableDisplayStatus.OCCUPIED && table.currentOrder != null) {
                table.currentOrder.totalDisplay
            } else {
                "${table.capacity}p"
            }
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// LISTA — agrupada por área, modo primario en pantallas chicas
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun TableListView(
    tables: List<DiningTable>,
    areas: List<AreaSummary>,
    selectedAreaId: String?,
    activeSessionTableId: String?,
    onTableClick: (DiningTable) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cuando ya hay un filtro de área activo (chip seleccionado arriba) la
    // lista es plana; sin filtro se agrupa por área con encabezado — así el
    // mesero no pierde el "dónde" al recorrer el salón completo.
    val grouped = remember(tables, selectedAreaId) {
        if (selectedAreaId != null) {
            mapOf<String, List<DiningTable>>("" to tables)
        } else {
            tables
                .groupBy { it.areaName?.takeIf { name -> name.isNotBlank() } ?: "Sin área" }
                .toSortedMap()
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.Space4, vertical = Spacing.Space2),
        verticalArrangement = Arrangement.spacedBy(Spacing.Space2),
    ) {
        grouped.forEach { (areaName, tablesInArea) ->
            if (areaName.isNotEmpty()) {
                item(key = "header-$areaName") {
                    Text(
                        text = areaName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.Space2, bottom = Spacing.Space1),
                    )
                }
            }
            items(tablesInArea, key = { it.id }) { table ->
                TableListRow(
                    table = table,
                    isMySession = table.id == activeSessionTableId,
                    onClick = { onTableClick(table) },
                )
            }
        }
        if (tables.isEmpty()) {
            item {
                Text(
                    text = "No hay mesas en esta área.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.Space4),
                )
            }
        }
    }
}

@Composable
private fun TableListRow(table: DiningTable, isMySession: Boolean, onClick: () -> Unit) {
    val status = table.displayStatus()

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status = status, size = 14.dp)
            Spacer(Modifier.padding(start = Spacing.Space3))

            Icon(
                imageVector = Icons.Default.TableRestaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.padding(start = Spacing.Space2))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Mesa ${table.number}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isMySession) {
                        Spacer(Modifier.padding(start = Spacing.Space2))
                        Text(
                            text = "· Tu mesa",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.padding(start = 2.dp))
                    Text(
                        text = "${table.capacity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    table.currentOrder?.let { order ->
                        Text(
                            text = " · ${order.itemCount} producto${if (order.itemCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                table.currentOrder?.let { order ->
                    Text(
                        text = order.totalDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// ESTADOS: vacío / error
// ══════════════════════════════════════════════════════════════════════

@Composable
private fun TablesEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.Space6)) {
            Icon(
                imageVector = Icons.Default.TableRestaurant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.Space2))
            Text(
                text = "Este venue todavía no tiene mesas configuradas.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TablesErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.Space6)) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.Space2))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.Space4))
            TextButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.padding(start = Spacing.Space1))
                Text("Reintentar")
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

private val previewTables = listOf(
    DiningTable(id = "1", number = "1", capacity = 2, positionX = 0.2f, positionY = 0.25f, status = "AVAILABLE", areaName = "Terraza", areaId = "a1"),
    DiningTable(id = "2", number = "2", capacity = 4, positionX = 0.5f, positionY = 0.25f, status = "OCCUPIED", areaName = "Terraza", areaId = "a1"),
    DiningTable(id = "3", number = "3", capacity = 6, positionX = 0.8f, positionY = 0.25f, status = "RESERVED", areaName = "Salón", areaId = "a2"),
    DiningTable(id = "4", number = "4", capacity = 2, positionX = 0.35f, positionY = 0.65f, status = "CLEANING", areaName = "Salón", areaId = "a2"),
)

@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "Canvas — NEXGO 480×480")
@Composable
private fun TablesScreenCanvasNexgoPreview() {
    AvoqadoTheme {
        TablesScreenContent(
            sizes = ResponsiveSizes.calculate(480.dp, 480.dp),
            uiState = TablesUiState(isLoading = false, tables = previewTables),
            viewMode = TableViewMode.CANVAS,
            onNavigateBack = {},
            onViewModeChange = {},
            onAreaFilterChange = {},
            onRetry = {},
            onTableClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 600, heightDp = 1024, name = "Lista — PAX A80")
@Composable
private fun TablesScreenListPaxPreview() {
    AvoqadoTheme {
        TablesScreenContent(
            sizes = ResponsiveSizes.calculate(1024.dp, 600.dp),
            uiState = TablesUiState(isLoading = false, tables = previewTables, isOffline = true),
            viewMode = TableViewMode.LIST,
            onNavigateBack = {},
            onViewModeChange = {},
            onAreaFilterChange = {},
            onRetry = {},
            onTableClick = {},
        )
    }
}
