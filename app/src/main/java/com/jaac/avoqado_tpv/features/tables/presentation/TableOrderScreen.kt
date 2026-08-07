package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AssignmentInd
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.data.PendingRoundCart
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * `TableOrderScreen` (Plan C, Task 7) — lo ya enviado a cocina (por rondas) vs
 * lo pendiente de enviar. Ver KDoc de [TableOrderViewModel] para el alcance
 * exacto y lo que se dejó fuera a propósito.
 *
 * Offline: una ronda enviada sin red se ve IGUAL de enviada que una online —
 * solo con la etiqueta "Por sincronizar", nunca un diálogo de error (regla de
 * oro de `offline-first-y-hub-lan.md` §2.3, y el defecto real que ya mordió a
 * `AngelPayPaymentViewModel` en Pagos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableOrderScreen(
    onNavigateBack: () -> Unit,
    onAddProducts: () -> Unit,
    onNavigateToCheckout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableOrderViewModel = hiltViewModel(),
) {
    val session by viewModel.tableSession.active.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val isLoadingCheck by viewModel.isLoadingCheck.collectAsStateWithLifecycle()
    val checkLoadFailed by viewModel.checkLoadFailed.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val sendTakingLong by viewModel.sendTakingLong.collectAsStateWithLifecycle()
    val pendingLines by viewModel.pendingLines.collectAsStateWithLifecycle()
    val queuedLines by viewModel.queuedLines.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readOnly by viewModel.readOnly.collectAsStateWithLifecycle()
    val lockOwnerName by viewModel.lockOwnerName.collectAsStateWithLifecycle()

    // Fase 1 (2026-08-03) — SPLIT_ORDER · COMP_ORDER · MOVE_ORDER.
    val isSplitting by viewModel.isSplitting.collectAsStateWithLifecycle()
    val isComping by viewModel.isComping.collectAsStateWithLifecycle()
    val isMoving by viewModel.isMoving.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val availableTargetTables by viewModel.availableTargetTables.collectAsStateWithLifecycle()
    val isLoadingTargetTables by viewModel.isLoadingTargetTables.collectAsStateWithLifecycle()
    val targetTablesUnavailable by viewModel.targetTablesUnavailable.collectAsStateWithLifecycle()

    // Fase 2 (2026-08-03) — MERGE_ORDERS · CANCEL_ORDER · ASSIGN_ORDER.
    val isMerging by viewModel.isMerging.collectAsStateWithLifecycle()
    val mergeCandidateTables by viewModel.mergeCandidateTables.collectAsStateWithLifecycle()
    val isLoadingMergeCandidates by viewModel.isLoadingMergeCandidates.collectAsStateWithLifecycle()
    val mergeCandidatesUnavailable by viewModel.mergeCandidatesUnavailable.collectAsStateWithLifecycle()
    val isCancelling by viewModel.isCancelling.collectAsStateWithLifecycle()
    val cancelled by viewModel.cancelled.collectAsStateWithLifecycle()
    val isAssigning by viewModel.isAssigning.collectAsStateWithLifecycle()
    val activeStaff by viewModel.activeStaff.collectAsStateWithLifecycle()
    val isLoadingActiveStaff by viewModel.isLoadingActiveStaff.collectAsStateWithLifecycle()
    val activeStaffStale by viewModel.activeStaffStale.collectAsStateWithLifecycle()
    val activeStaffUnavailable by viewModel.activeStaffUnavailable.collectAsStateWithLifecycle()

    // Fase 3 (2026-08-03) — SPLIT_BY_SEAT · UPDATE_DETAILS.
    val isSplittingBySeat by viewModel.isSplittingBySeat.collectAsStateWithLifecycle()
    val isUpdatingDetails by viewModel.isUpdatingDetails.collectAsStateWithLifecycle()

    var showSplitSheet by remember { mutableStateOf(false) }
    var showCompSheet by remember { mutableStateOf(false) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showMergeSheet by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showAssignSheet by remember { mutableStateOf(false) }
    var showSplitBySeatDialog by remember { mutableStateOf(false) }
    var showEditDetailsSheet by remember { mutableStateOf(false) }
    var splitTriggered by remember { mutableStateOf(false) }
    var compTriggered by remember { mutableStateOf(false) }
    var moveTriggered by remember { mutableStateOf(false) }
    var mergeTriggered by remember { mutableStateOf(false) }
    var assignTriggered by remember { mutableStateOf(false) }
    var splitBySeatTriggered by remember { mutableStateOf(false) }
    var editDetailsTriggered by remember { mutableStateOf(false) }

    // Cada sheet se auto-cierra cuando SU acción termina (éxito o error — el
    // error ya se muestra en el snackbar compartido) — nunca antes, para que
    // el mesero vea el spinner del botón mientras la acción está en vuelo.
    LaunchedEffect(isSplitting) {
        if (splitTriggered && !isSplitting) {
            showSplitSheet = false
            splitTriggered = false
        }
    }
    LaunchedEffect(isComping) {
        if (compTriggered && !isComping) {
            showCompSheet = false
            compTriggered = false
        }
    }
    LaunchedEffect(isMoving) {
        if (moveTriggered && !isMoving) {
            showMoveSheet = false
            moveTriggered = false
        }
    }
    LaunchedEffect(isMerging) {
        if (mergeTriggered && !isMerging) {
            showMergeSheet = false
            mergeTriggered = false
        }
    }
    LaunchedEffect(isAssigning) {
        if (assignTriggered && !isAssigning) {
            showAssignSheet = false
            assignTriggered = false
        }
    }
    LaunchedEffect(isSplittingBySeat) {
        if (splitBySeatTriggered && !isSplittingBySeat) {
            showSplitBySeatDialog = false
            splitBySeatTriggered = false
        }
    }
    LaunchedEffect(isUpdatingDetails) {
        if (editDetailsTriggered && !isUpdatingDetails) {
            showEditDetailsSheet = false
            editDetailsTriggered = false
        }
    }
    // CANCEL_ORDER libera la mesa del lado del server — a diferencia de las
    // otras 5 acciones, esta SÍ sale de la pantalla (ver KDoc de
    // TableOrderViewModel.cancelled). El diálogo se cierra solo al disparar
    // la salida; no hace falta un LaunchedEffect(isCancelling) separado.
    LaunchedEffect(cancelled) {
        if (cancelled) {
            showCancelDialog = false
        }
    }

    val activeSession = session
    if (activeSession == null) {
        // No debería pasar (se llega aquí SOLO tras abrir/ver una mesa), pero
        // si la sesión se limpió por fuera (p.ej. otra pestaña de navegación)
        // salir al plano es más seguro que una pantalla vacía sin salida.
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    // 🔴 Bug real encontrado EN HARDWARE (NEXGO N86, Task 7): el botón físico
    // de "Atrás" (o el gesto del sistema) NO pasa por el ícono de flecha del
    // topbar — sin este handler, salía de la pantalla sin llamar
    // `exitToFloor()`, dejando la sesión de mesa activa "colgada": el plano
    // seguía marcando esa mesa como "Tu mesa" aunque el mesero ya estuviera
    // parado en otra. Mismo patrón ya usado en `SerializedSaleScreen.kt`.
    BackHandler(enabled = true) {
        viewModel.exitToFloor()
        onNavigateBack()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    TableOrderScreenContent(
        modifier = modifier,
        session = activeSession,
        check = check,
        isLoadingCheck = isLoadingCheck,
        checkLoadFailed = checkLoadFailed,
        isSending = isSending,
        sendTakingLong = sendTakingLong,
        pendingLines = pendingLines,
        queuedLines = queuedLines,
        readOnly = readOnly,
        lockOwnerName = lockOwnerName,
        snackbarHostState = snackbarHostState,
        onNavigateBack = {
            viewModel.exitToFloor()
            onNavigateBack()
        },
        onAddProducts = onAddProducts,
        onSendRound = viewModel::sendRound,
        onRetryLoad = viewModel::loadCheck,
        onNavigateToCheckout = onNavigateToCheckout,
        onSplitClick = { showSplitSheet = true },
        onCompClick = { showCompSheet = true },
        onMoveClick = {
            showMoveSheet = true
            viewModel.loadAvailableTargetTables()
        },
        onMergeClick = {
            showMergeSheet = true
            viewModel.loadMergeCandidateTables()
        },
        onCancelClick = { showCancelDialog = true },
        onAssignClick = {
            showAssignSheet = true
            viewModel.loadActiveStaff()
        },
        onSplitBySeatClick = { showSplitBySeatDialog = true },
        onEditDetailsClick = { showEditDetailsSheet = true },
    )

    if (showSplitSheet) {
        SplitOrderSheet(
            check = check,
            isSplitting = isSplitting,
            onDismiss = { if (!isSplitting) showSplitSheet = false },
            onConfirm = { itemIds ->
                splitTriggered = true
                viewModel.splitOrder(itemIds)
            },
        )
    }

    if (showCompSheet) {
        CompOrderSheet(
            check = check,
            isComping = isComping,
            isOnline = isOnline,
            onDismiss = { if (!isComping) showCompSheet = false },
            onConfirmWhole = { reason ->
                compTriggered = true
                viewModel.compWholeOrder(reason)
            },
            onConfirmItems = { itemIds, reason ->
                compTriggered = true
                viewModel.compItems(itemIds, reason)
            },
        )
    }

    if (showMoveSheet) {
        MoveTableSheet(
            tables = availableTargetTables,
            isLoading = isLoadingTargetTables,
            isMoving = isMoving,
            unavailable = targetTablesUnavailable,
            onDismiss = { if (!isMoving) showMoveSheet = false },
            onSelect = { target ->
                moveTriggered = true
                viewModel.moveOrder(target)
            },
            onRetry = viewModel::loadAvailableTargetTables,
        )
    }

    if (showMergeSheet) {
        MergeOrdersSheet(
            tables = mergeCandidateTables,
            isLoading = isLoadingMergeCandidates,
            isMerging = isMerging,
            unavailable = mergeCandidatesUnavailable,
            onDismiss = { if (!isMerging) showMergeSheet = false },
            onSelect = { source ->
                mergeTriggered = true
                viewModel.mergeOrders(source)
            },
            onRetry = viewModel::loadMergeCandidateTables,
        )
    }

    if (showCancelDialog) {
        CancelOrderDialog(
            isCancelling = isCancelling,
            onDismiss = { if (!isCancelling) showCancelDialog = false },
            onConfirm = { reason -> viewModel.cancelOrder(reason) },
        )
    }

    if (showAssignSheet) {
        AssignWaiterSheet(
            staff = activeStaff,
            isLoading = isLoadingActiveStaff,
            isAssigning = isAssigning,
            unavailable = activeStaffUnavailable,
            stale = activeStaffStale,
            onDismiss = { if (!isAssigning) showAssignSheet = false },
            onSelect = { member ->
                assignTriggered = true
                viewModel.assignOrder(member)
            },
            onRetry = viewModel::loadActiveStaff,
        )
    }

    if (showSplitBySeatDialog) {
        SplitBySeatDialog(
            isSplitting = isSplittingBySeat,
            onDismiss = { if (!isSplittingBySeat) showSplitBySeatDialog = false },
            onConfirm = {
                splitBySeatTriggered = true
                viewModel.splitOrderBySeat()
            },
        )
    }

    if (showEditDetailsSheet) {
        EditDetailsSheet(
            initialName = check?.customerName,
            initialCovers = check?.covers,
            isSaving = isUpdatingDetails,
            onDismiss = { if (!isUpdatingDetails) showEditDetailsSheet = false },
            onConfirm = { name, covers ->
                editDetailsTriggered = true
                viewModel.updateOrderDetails(name, covers)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableOrderScreenContent(
    modifier: Modifier = Modifier,
    session: TableSession.Active,
    check: OrderDetail?,
    isLoadingCheck: Boolean,
    checkLoadFailed: Boolean,
    isSending: Boolean,
    sendTakingLong: Boolean,
    pendingLines: List<PendingRoundCart.Line>,
    queuedLines: List<PendingRoundCart.Line>,
    readOnly: Boolean,
    lockOwnerName: String?,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onAddProducts: () -> Unit,
    onSendRound: () -> Unit,
    onRetryLoad: () -> Unit,
    onNavigateToCheckout: () -> Unit,
    onSplitClick: () -> Unit = {},
    onCompClick: () -> Unit = {},
    onMoveClick: () -> Unit = {},
    onMergeClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
    onAssignClick: () -> Unit = {},
    onSplitBySeatClick: () -> Unit = {},
    onEditDetailsClick: () -> Unit = {},
) {
    val rounds = check?.roundsSentToKitchen() ?: emptyList()
    val pendingCount = pendingLines.sumOf { it.quantity }
    val pendingTotal = pendingLines.fold(BigDecimal.ZERO) { acc, line -> acc + line.lineTotal }
    val checkTotal = check?.total
    // "Pagar" solo tiene sentido con algo YA en la cuenta (rondas enviadas,
    // no lo pendiente de enviar) — una mesa recién abierta sin nada mandado
    // a cocina no tiene cheque contra el cual cobrar.
    val canCheckout = !readOnly && !session.isProvisional && (checkTotal ?: BigDecimal.ZERO) > BigDecimal.ZERO
    // Separar/dar cortesía necesitan artículos YA enviados; mover mesa no
    // (una mesa provisional recién abierta offline también se puede mover).
    val hasSentItems = !check?.items.isNullOrEmpty()
    // Fusionar necesita un cheque REAL de destino (mismo motivo que separar:
    // la ruta online no resuelve un orderId local), pero no items YA enviados
    // — se puede fusionar sobre una cuenta recién abierta y vacía. Cancelar y
    // reasignar sí funcionan sobre una sesión provisional (mismo criterio que
    // mover mesa: viajan siempre por intent, con localOrderId si hace falta).
    val mergeEnabled = !readOnly && !session.isProvisional
    val cancelEnabled = !readOnly
    val assignEnabled = !readOnly
    // Dividir por puesto necesita artículos YA enviados CON asiento asignado
    // — ver KDoc de [splitBySeatEnabled] para el porqué (paridad Android
    // 2026-08-06, Hallazgo #1).
    val splitBySeatIsEnabled = splitBySeatEnabled(readOnly = readOnly, hasSentItems = hasSentItems, check = check)
    // Editar detalles funciona incluso en sesión provisional (UPDATE_DETAILS
    // siempre viaja por intent, con localOrderId si hace falta) — mismo
    // criterio que cancelar/reasignar.
    val editDetailsEnabled = !readOnly

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                AvoqadoTopBar(
                    title = session.label,
                    subtitle = checkTotal?.let { moneyDisplay(it) } ?: if (session.isProvisional) "Sin conexión — abierta local" else null,
                    onNavigationClick = onNavigateBack,
                    actions = {
                        TableOrderOverflowMenu(
                            editDetailsEnabled = editDetailsEnabled,
                            splitEnabled = !readOnly && hasSentItems,
                            splitBySeatEnabled = splitBySeatIsEnabled,
                            compEnabled = !readOnly && hasSentItems,
                            moveEnabled = !readOnly,
                            mergeEnabled = mergeEnabled,
                            cancelEnabled = cancelEnabled,
                            assignEnabled = assignEnabled,
                            onEditDetailsClick = onEditDetailsClick,
                            onSplitClick = onSplitClick,
                            onSplitBySeatClick = onSplitBySeatClick,
                            onCompClick = onCompClick,
                            onMoveClick = onMoveClick,
                            onMergeClick = onMergeClick,
                            onCancelClick = onCancelClick,
                            onAssignClick = onAssignClick,
                        )
                    },
                )
                if (readOnly) {
                    OwnershipLockBanner(ownerName = lockOwnerName)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            TableOrderBottomBar(
                pendingCount = pendingCount,
                pendingTotal = pendingTotal,
                isSending = isSending,
                sendTakingLong = sendTakingLong,
                readOnly = readOnly,
                canCheckout = canCheckout,
                checkTotal = checkTotal,
                onAddProducts = onAddProducts,
                onSendRound = onSendRound,
                onNavigateToCheckout = onNavigateToCheckout,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Ver KDoc de [resolveTableOrderBodyState] para el bug real (Mesa
        // M1) que esta distinción cierra: un LOAD_FAILED nunca debe caer en
        // la misma rama que una cuenta genuinamente vacía.
        when (
            resolveTableOrderBodyState(
                isLoadingCheck = isLoadingCheck,
                check = check,
                checkLoadFailed = checkLoadFailed,
                isProvisional = session.isProvisional,
                hasPendingOrQueuedLines = pendingLines.isNotEmpty() || queuedLines.isNotEmpty(),
            )
        ) {
            TableOrderBodyState.LOADING -> {
                AvoqadoFullScreenLoading(message = "Cargando cuenta…", modifier = Modifier.padding(padding))
            }

            TableOrderBodyState.LOAD_FAILED -> {
                TableOrderLoadFailedState(onRetry = onRetryLoad, modifier = Modifier.padding(padding))
            }

            TableOrderBodyState.CONTENT -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Space4, vertical = Spacing.Space3),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Space3),
                ) {
                    if (pendingLines.isNotEmpty()) {
                        item(key = "pending-header") { SectionHeader("Por enviar") }
                        items(pendingLines, key = { "pending-${it.lineId}" }) { line ->
                            PendingLineRow(line = line, readOnly = readOnly)
                        }
                    }

                    if (queuedLines.isNotEmpty()) {
                        item(key = "queued-header") { SectionHeader("Por sincronizar") }
                        items(queuedLines, key = { "queued-${it.lineId}" }) { line ->
                            QueuedLineRow(line = line)
                        }
                    }

                    if (rounds.isNotEmpty()) {
                        rounds.forEachIndexed { index, round ->
                            item(key = "round-header-${round.sentAt}-$index") {
                                SectionHeader(roundLabel(round.sentAt, rounds.size - index))
                            }
                            items(round.items, key = { "sent-${it.id}" }) { orderItem ->
                                SentItemRow(item = orderItem)
                            }
                        }
                    }

                    if (rounds.isEmpty() && pendingLines.isEmpty() && queuedLines.isEmpty()) {
                        item(key = "empty") {
                            EmptyOrderState(onAddProducts = onAddProducts, readOnly = readOnly)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyOrderState(onAddProducts: () -> Unit, readOnly: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Todavía no hay nada en esta cuenta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!readOnly) {
            Spacer(Modifier.height(Spacing.Space3))
            AvoqadoButton(text = "Agregar productos", onClick = onAddProducts, icon = Icons.Default.Add)
        }
    }
}

/**
 * "No se pudo cargar la cuenta" — la carga INICIAL de [TableOrderViewModel.check]
 * falló (blip de red, timeout, 500) y todavía no hay ningún cheque en
 * memoria. Deliberadamente DISTINTA de [EmptyOrderState]: esta pantalla NUNCA
 * debe verse igual que "la mesa no tiene nada", porque puede haber dinero
 * real esperando (ver KDoc de [resolveTableOrderBodyState] — Mesa M1,
 * $199.00 reales que esta pantalla dejaba de mostrar). Mismo patrón visual
 * que `TablesScreen.TablesErrorState` (ícono + mensaje + "Reintentar").
 */
@Composable
private fun TableOrderLoadFailedState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
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
                text = "No se pudo cargar la cuenta — puede que ya tenga productos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
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

@Composable
private fun PendingLineRow(line: PendingRoundCart.Line, readOnly: Boolean) {
    LineRowCard(
        name = line.name,
        quantity = line.quantity,
        total = line.lineTotal,
        modifiersLabel = line.modifiers.takeIf { it.isNotEmpty() }?.joinToString { it.name },
        trailingBadge = null,
    )
}

@Composable
private fun QueuedLineRow(line: PendingRoundCart.Line) {
    LineRowCard(
        name = line.name,
        quantity = line.quantity,
        total = line.lineTotal,
        modifiersLabel = line.modifiers.takeIf { it.isNotEmpty() }?.joinToString { it.name },
        trailingBadge = "Por sincronizar" to MaterialTheme.avoqadoColors.offlineOrange,
    )
}

@Composable
private fun SentItemRow(item: OrderDetailItem) {
    LineRowCard(
        name = item.productName ?: "Artículo",
        quantity = item.quantity,
        total = item.total,
        modifiersLabel = item.modifiers.takeIf { it.isNotEmpty() }?.joinToString { it.name },
        trailingBadge = if (item.isCortesia) "Cortesía" to MaterialTheme.avoqadoColors.statusInfo else null,
    )
}

@Composable
private fun LineRowCard(
    name: String,
    quantity: Int,
    total: BigDecimal,
    modifiersLabel: String?,
    trailingBadge: Pair<String, androidx.compose.ui.graphics.Color>?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${quantity}× $name",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                modifiersLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 🔴 Fix visual encontrado EN HARDWARE (NEXGO N86, portrait
                // angosto): la etiqueta iba PEGADA al nombre en el mismo
                // renglón — con un nombre largo ("Coca-Cola 600ml") + "Por
                // sincronizar", el Row se quedaba sin ancho y el texto de la
                // etiqueta partía la palabra a la mitad ("Por sincroni-zar").
                // Su PROPIO renglón, debajo del nombre, nunca compite por
                // ancho con nada.
                trailingBadge?.let { (label, color) ->
                    Spacer(Modifier.padding(top = 2.dp))
                    Box(
                        modifier = Modifier
                            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50)),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = Spacing.Space2, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = moneyDisplay(total),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun OwnershipLockBanner(ownerName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.18f))
            .padding(horizontal = Spacing.Space4, vertical = Spacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.avoqadoColors.statusWarning,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.padding(start = Spacing.Space2))
        Text(
            text = if (ownerName != null) "Mesa de $ownerName — solo lectura" else "Mesa de otro mesero — solo lectura",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Menú de "más acciones" — Editar detalles / Separar cuenta / Dividir por
 * puesto / Cortesía / Mover mesa (Fase 1 + Fase 3) + Fusionar cuentas /
 * Reasignar mesero / Cancelar cuenta (Fase 2). Un solo ícono `MoreVert` en vez
 * de 8 íconos sueltos: en NEXGO 480×480 el topbar ya carga
 * título+subtítulo+flecha, y el propio `AvoqadoTopBar` establece este patrón
 * (ver su preview "With Actions").
 *
 * "Cancelar cuenta" va AL FINAL y separada por un divisor — es la única
 * acción destructiva del menú (riesgo explícito del founder: difícil de
 * hacer sin querer, pero encontrable bajo presión). Un tap aquí solo ABRE
 * [CancelOrderDialog], nunca cancela directo — ver su KDoc.
 */
@Composable
private fun TableOrderOverflowMenu(
    editDetailsEnabled: Boolean,
    splitEnabled: Boolean,
    splitBySeatEnabled: Boolean,
    compEnabled: Boolean,
    moveEnabled: Boolean,
    mergeEnabled: Boolean,
    cancelEnabled: Boolean,
    assignEnabled: Boolean,
    onEditDetailsClick: () -> Unit,
    onSplitClick: () -> Unit,
    onSplitBySeatClick: () -> Unit,
    onCompClick: () -> Unit,
    onMoveClick: () -> Unit,
    onMergeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onAssignClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "Más acciones")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Editar detalles") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            enabled = editDetailsEnabled,
            onClick = { expanded = false; onEditDetailsClick() },
        )
        DropdownMenuItem(
            text = { Text("Separar cuenta") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null) },
            enabled = splitEnabled,
            onClick = { expanded = false; onSplitClick() },
        )
        DropdownMenuItem(
            text = { Text("Dividir por puesto") },
            leadingIcon = { Icon(Icons.Default.EventSeat, contentDescription = null) },
            enabled = splitBySeatEnabled,
            onClick = { expanded = false; onSplitBySeatClick() },
        )
        DropdownMenuItem(
            text = { Text("Cortesía") },
            leadingIcon = { Icon(Icons.Default.CardGiftcard, contentDescription = null) },
            enabled = compEnabled,
            onClick = { expanded = false; onCompClick() },
        )
        DropdownMenuItem(
            text = { Text("Mover mesa") },
            leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
            enabled = moveEnabled,
            onClick = { expanded = false; onMoveClick() },
        )
        DropdownMenuItem(
            text = { Text("Fusionar cuentas") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null) },
            enabled = mergeEnabled,
            onClick = { expanded = false; onMergeClick() },
        )
        DropdownMenuItem(
            text = { Text("Reasignar mesero") },
            leadingIcon = { Icon(Icons.Default.AssignmentInd, contentDescription = null) },
            enabled = assignEnabled,
            onClick = { expanded = false; onAssignClick() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Cancelar cuenta", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            enabled = cancelEnabled,
            onClick = { expanded = false; onCancelClick() },
        )
    }
}

@Composable
private fun TableOrderBottomBar(
    pendingCount: Int,
    pendingTotal: BigDecimal,
    isSending: Boolean,
    sendTakingLong: Boolean,
    readOnly: Boolean,
    canCheckout: Boolean,
    checkTotal: BigDecimal?,
    onAddProducts: () -> Unit,
    onSendRound: () -> Unit,
    onNavigateToCheckout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider()
        // 🔴 Hallazgo real (founder, PAX en vivo) — ver KDoc de
        // `TableOrderViewModel._sendTakingLong`: un envío lento (ngrok/red
        // real) dejaba el spinner mudo del botón indistinguible de
        // "se congeló" hasta 25s. Este texto SOLO aparece pasado el umbral
        // de reafirmación — nunca reemplaza al spinner del botón, nunca toca
        // AvoqadoButton (compartido con "Cobrar", intocable).
        if (isSending && sendTakingLong) {
            Text(
                text = "Sigue enviando la ronda… no vuelvas a tocar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Space4, vertical = Spacing.Space1),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onAddProducts,
                enabled = !readOnly,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Agregar productos")
            }

            AvoqadoButton(
                text = if (pendingCount > 0) "Enviar ronda · ${moneyDisplay(pendingTotal)}" else "Enviar ronda",
                onClick = onSendRound,
                enabled = !readOnly && pendingCount > 0 && !isSending,
                loading = isSending,
                fullWidth = true,
                modifier = Modifier.weight(1f),
            )
        }

        // "Pagar" — Plan C, Task 8. Solo cuando hay algo YA enviado a cocina
        // (canCheckout) y sin rondas pendientes de mandar primero (evita
        // cobrar una cuenta que todavía no refleja lo que el mesero acaba de
        // teclear).
        if (canCheckout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Space4, vertical = Spacing.Space2),
            ) {
                com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton(
                    text = "Pagar · ${moneyDisplay(checkTotal ?: BigDecimal.ZERO)}",
                    onClick = onNavigateToCheckout,
                    enabled = pendingCount == 0 && !isSending,
                    fullWidth = true,
                )
            }
        }
    }
}

private fun moneyDisplay(amount: BigDecimal): String = "$${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

/** "Ronda 3" (más reciente) → "Ronda 1" (primera) — o la hora si se puede parsear. */
private fun roundLabel(sentAt: String?, roundNumber: Int): String {
    val time = sentAt?.let { raw ->
        runCatching { OffsetDateTime.parse(raw).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
    }
    return if (time != null) "Ronda $roundNumber · $time" else "Ronda $roundNumber"
}

/**
 * Qué debe pintar el cuerpo de [TableOrderScreenContent] — extraído a función
 * pura (`internal`, sin Compose) para poder probarlo con un JUnit normal, ver
 * `TableOrderScreenStateTest`.
 *
 * 🔴 Bug real (Mesa M1, `cmsds40wp000cc9ixmm8u71f4`): antes de esto el `when`
 * solo distinguía LOADING vs "todo lo demás" — un [checkLoadFailed] (la
 * carga inicial de la cuenta falló, típico blip de red en el WiFi del local)
 * caía en el mismo "todo lo demás" que una cuenta genuinamente vacía, así
 * que el mesero veía "Todavía no hay nada en esta cuenta" sobre una cuenta
 * con 3 artículos y $199.00 reales en el server. `LOAD_FAILED` es su propia
 * rama — nunca se disfraza de cuenta vacía.
 */
internal enum class TableOrderBodyState { LOADING, LOAD_FAILED, CONTENT }

/**
 * @param hasPendingOrQueuedLines `true` si hay algo en el carrito local
 *   (pendiente de enviar o encolado sin red) — datos que viven INDEPENDIENTES
 *   de si el GET de la cuenta funcionó. Sin este parámetro, LOAD_FAILED podía
 *   tapar el carrito de un mesero que agregó productos ANTES de que la carga
 *   inicial fallara: el peor momento posible para esconderle lo que ya
 *   tecleó. La pantalla de "no se pudo cargar" es el último recurso — solo
 *   cuando literalmente no hay nada más que mostrar.
 */
internal fun resolveTableOrderBodyState(
    isLoadingCheck: Boolean,
    check: OrderDetail?,
    checkLoadFailed: Boolean,
    isProvisional: Boolean,
    hasPendingOrQueuedLines: Boolean,
): TableOrderBodyState = when {
    isLoadingCheck && check == null && !isProvisional -> TableOrderBodyState.LOADING
    // Solo mientras no hay NINGÚN cheque cargado NI nada local que mostrar —
    // ver KDoc de TableOrderViewModel.checkLoadFailed: un refresh fallido
    // sobre una cuenta YA visible (o sobre un carrito con algo dentro) nunca
    // la reemplaza por esta pantalla.
    checkLoadFailed && check == null && !hasPendingOrQueuedLines -> TableOrderBodyState.LOAD_FAILED
    else -> TableOrderBodyState.CONTENT
}

/**
 * Si "Dividir por puesto" debe estar habilitado — extraído a función pura
 * (`internal`, sin Compose) por el mismo motivo que [resolveTableOrderBodyState],
 * ver `TableOrderScreenStateTest`.
 *
 * Paridad Android 2026-08-06 (`paridad-android-tpv.md`, Hallazgo #1): la TPV
 * NUNCA manda `seat` al agregar items (ni el cliente lo captura, ni el
 * server lo acepta en `addOrderItemsSchema` — ver KDoc de
 * `TablesRepository.splitOrderBySeat`), así que una cuenta armada
 * ÍNTEGRAMENTE en esta terminal SIEMPRE tiene 0 items con `seat` y el server
 * SIEMPRE rechazaría con "se necesitan al menos dos puestos" — un botón que
 * siempre falla es peor que no tenerlo. Solo se habilita si el cheque YA
 * trae asientos (típicamente porque llegó de Android/iOS, que SÍ los
 * asignan) — ahí la acción funciona de verdad hoy.
 */
internal fun splitBySeatEnabled(readOnly: Boolean, hasSentItems: Boolean, check: OrderDetail?): Boolean =
    !readOnly && hasSentItems && check?.items?.any { it.seat != null } == true

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "NEXGO 480×480 — vacía")
@Composable
private fun TableOrderScreenEmptyPreview() {
    AvoqadoTheme {
        TableOrderScreenContent(
            session = TableSession.Active(tableId = "1", tableNumber = "5", orderId = "order-1"),
            check = OrderDetail(id = "order-1"),
            isLoadingCheck = false,
            checkLoadFailed = false,
            isSending = false,
            sendTakingLong = false,
            pendingLines = emptyList(),
            queuedLines = emptyList(),
            readOnly = false,
            lockOwnerName = null,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onAddProducts = {},
            onSendRound = {},
            onRetryLoad = {},
            onNavigateToCheckout = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — con rondas + pendiente + solo lectura")
@Composable
private fun TableOrderScreenFullPreview() {
    AvoqadoTheme {
        TableOrderScreenContent(
            session = TableSession.Active(tableId = "1", tableNumber = "12", orderId = "order-1", total = BigDecimal("845.50")),
            check = OrderDetail(
                id = "order-1",
                total = BigDecimal("845.50"),
                servedBy = com.jaac.avoqado_tpv.features.tables.domain.model.OrderStaffSummary(id = "w1", firstName = "Fátima", lastName = "Flores"),
                items = listOf(
                    OrderDetailItem(id = "i1", productName = "Café americano", quantity = 2, unitPrice = BigDecimal("45.00"), total = BigDecimal("90.00"), sentToKitchenAt = "2026-07-29T10:00:00Z"),
                    OrderDetailItem(id = "i2", productName = "Croissant", quantity = 1, unitPrice = BigDecimal("55.00"), total = BigDecimal("55.00"), sentToKitchenAt = "2026-07-29T10:00:00Z", isCortesia = true),
                ),
            ),
            isLoadingCheck = false,
            checkLoadFailed = false,
            isSending = false,
            sendTakingLong = false,
            pendingLines = listOf(PendingRoundCart.Line(productId = "p1", name = "Agua mineral", unitPrice = BigDecimal("30.00"), quantity = 2)),
            queuedLines = listOf(PendingRoundCart.Line(productId = "p2", name = "Ensalada", unitPrice = BigDecimal("120.00"), quantity = 1)),
            readOnly = true,
            lockOwnerName = "Fátima Flores",
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onAddProducts = {},
            onSendRound = {},
            onRetryLoad = {},
            onNavigateToCheckout = {},
        )
    }
}

/**
 * Repro exacta de la Mesa M1 (`cmsds40wp000cc9ixmm8u71f4`) — la carga inicial
 * falló y NUNCA hubo un cheque en memoria. Debe verse claramente DISTINTA de
 * [TableOrderScreenEmptyPreview] — nunca "Todavía no hay nada en esta
 * cuenta".
 */
@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "NEXGO 480×480 — falló la carga inicial (Mesa M1)")
@Composable
private fun TableOrderScreenLoadFailedPreview() {
    AvoqadoTheme {
        TableOrderScreenContent(
            session = TableSession.Active(tableId = "1", tableNumber = "1", orderId = "cmsds40wp000cc9ixmm8u71f4", total = BigDecimal("199.00")),
            check = null,
            isLoadingCheck = false,
            checkLoadFailed = true,
            isSending = false,
            sendTakingLong = false,
            pendingLines = emptyList(),
            queuedLines = emptyList(),
            readOnly = false,
            lockOwnerName = null,
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onAddProducts = {},
            onSendRound = {},
            onRetryLoad = {},
            onNavigateToCheckout = {},
        )
    }
}
