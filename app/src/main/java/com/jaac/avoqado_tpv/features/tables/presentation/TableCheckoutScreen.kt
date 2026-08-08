package com.jaac.avoqado_tpv.features.tables.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoSecondaryButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDiscountLine
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderServiceChargeLine
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `TableCheckoutScreen` (Plan C, Task 8) — la costura hacia el pago. Ver KDoc
 * de [TableCheckoutViewModel] para el contrato completo de EFECTIVO vs
 * TARJETA.
 *
 * Descuentos y cobros por servicio van SIEMPRE como líneas propias, nunca
 * escondidos dentro del total — riesgo explícito del founder: "si lo hace
 * mal o feo puede enojar al cliente". Legible en NEXGO 480×480 y PAX
 * 720×1280 — mismo `Scaffold`+`LazyColumn`+`AvoqadoTopBar` que
 * `TableOrderScreen`.
 *
 * [onProceedToCardPayment] es la ÚNICA salida hacia "Cobrar": este archivo
 * nunca navega ahí directo ni conoce `getPaymentRoute()` — esa traducción
 * vive en `AppNavigation.kt`, el mismo dueño de la costura para
 * `SplitByProductScreen`/`SplitByPersonScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableCheckoutScreen(
    onNavigateBack: () -> Unit,
    onProceedToCardPayment: (CardChargeRequest) -> Unit,
    onPaymentFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TableCheckoutViewModel = hiltViewModel(),
) {
    val session by viewModel.tableSession.active.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Fase 1 (2026-08-03) — APPLY_DISCOUNT.
    val availableDiscounts by viewModel.availableDiscounts.collectAsStateWithLifecycle()
    val isLoadingDiscounts by viewModel.isLoadingDiscounts.collectAsStateWithLifecycle()
    val isApplyingDiscount by viewModel.isApplyingDiscount.collectAsStateWithLifecycle()
    // Paridad Android 2026-08-06 (`paridad-android-tpv.md`, Hallazgo #4) —
    // "quitar descuento aplicado", SIEMPRE online (ver KDoc de
    // TableCheckoutViewModel.removeDiscount).
    val isRemovingDiscount by viewModel.isRemovingDiscount.collectAsStateWithLifecycle()
    var showDiscountSheet by remember { mutableStateOf(false) }
    var discountTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(isApplyingDiscount) {
        if (discountTriggered && !isApplyingDiscount) {
            showDiscountSheet = false
            discountTriggered = false
        }
    }

    // Fase 3 (2026-08-03) — APPLY_SERVICE_CHARGE.
    val availableServiceCharges by viewModel.availableServiceCharges.collectAsStateWithLifecycle()
    val isLoadingServiceCharges by viewModel.isLoadingServiceCharges.collectAsStateWithLifecycle()
    val isApplyingServiceCharge by viewModel.isApplyingServiceCharge.collectAsStateWithLifecycle()
    // Paridad Android 2026-08-06 (`paridad-android-tpv.md`, Hallazgo #4) —
    // "quitar cargo por servicio aplicado", SIEMPRE online (ver KDoc de
    // TableCheckoutViewModel.removeServiceCharge).
    val isRemovingServiceCharge by viewModel.isRemovingServiceCharge.collectAsStateWithLifecycle()
    var showServiceChargeSheet by remember { mutableStateOf(false) }
    var serviceChargeTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(isApplyingServiceCharge) {
        if (serviceChargeTriggered && !isApplyingServiceCharge) {
            showServiceChargeSheet = false
            serviceChargeTriggered = false
        }
    }

    // 🔴 Bug real encontrado EN HARDWARE (PAX A910S, Task 8): un cobro en
    // EFECTIVO que salda la cuenta llama TableSession.clear() (dentro de
    // TableCheckoutViewModel.releaseTable()) mientras ESTA pantalla Y
    // TableOrderScreen siguen montadas en el back stack. Las DOS tienen su
    // propio guard "sesión nula -> onNavigateBack()" en un
    // LaunchedEffect(Unit) — pero SafeNavigationHelper.safePopBackStack
    // debounça 300ms con un timestamp GLOBAL (singleton, no por pantalla):
    // el primer pop (este archivo) gana, el segundo (TableOrderScreen) se
    // descarta en silencio, y como su LaunchedEffect(Unit) ya corrió una vez
    // nunca reintenta — la mesera queda en una pantalla en blanco para
    // siempre. Verificado con uiautomator (Compose tree vacío) + logcat
    // (el pago SÍ se aplicó, "13 mesas cargadas" corrió de fondo, pero
    // ninguna pantalla quedó compuesta).
    //
    // Fix: un salto ATÓMICO y DELIBERADO de vuelta al plano
    // ([onPaymentFinished], un solo popBackStack hasta Tables) que dispara
    // en cuanto [TableCheckoutState.tableReleased] es true — ANTES de que
    // TableSession.clear() siquiera ocurra (ese es async, tras el HTTP de
    // clearTable; tableReleased ya es true de forma síncrona). Para cuando
    // la sesión de verdad se limpia, esta pantalla y TableOrderScreen ya
    // fueron destruidas por el popUpTo — sus guards nunca llegan a competir.
    //
    // 🧾 Cierre de la asimetría EFECTIVO vs TARJETA (este archivo): un cobro
    // en efectivo que SALDA la cuenta pone tableReleased=true en el MISMO
    // tick que arma `state.cashReceipt` (ver TableCheckoutViewModel.payCash).
    // Sin la condición `state.cashReceipt == null` de abajo, el salto atómico
    // de arriba destruiría la pantalla ANTES de que el mesero alcance a ver
    // el recibo — la pantalla de éxito de "Cobrar" nunca tiene ese problema
    // porque el pago con tarjeta se queda en SU PROPIA pantalla hasta que el
    // cajero cierra el recibo. `consumeCashReceipt()` (el botón "Cerrar" del
    // diálogo) vuelve a disparar este efecto, y entonces sí navega.
    LaunchedEffect(state.tableReleased, state.cashReceipt) {
        if (state.tableReleased && state.cashReceipt == null) {
            onPaymentFinished()
        }
    }

    val activeSession = session
    if (activeSession == null) {
        // No debería pasar (solo se llega aquí desde "Cobrar" en
        // TableOrderScreen), pero si la sesión se limpió por otra vía (no el
        // camino de tableReleased de arriba, que ya navegó), salir es más
        // seguro que una pantalla de cobro sin mesa a la que apuntar.
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    BackHandler(enabled = true) {
        viewModel.onExit()
        onNavigateBack()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    val chargeableAmount = viewModel.chargeableAmount()

    TableCheckoutScreenContent(
        modifier = modifier,
        session = activeSession,
        state = state,
        chargeableAmount = chargeableAmount,
        snackbarHostState = snackbarHostState,
        onNavigateBack = {
            viewModel.onExit()
            onNavigateBack()
        },
        onRetryLoad = viewModel::loadCheck,
        onSelectSplitType = viewModel::selectSplitType,
        onCustomAmountChanged = viewModel::setCustomAmountInput,
        onEqualPartsChanged = viewModel::setEqualParts,
        onEqualPartsPayingForChanged = viewModel::setEqualPartsPayingFor,
        onToggleItem = viewModel::toggleItemSelected,
        onTipChanged = viewModel::setTip,
        onPayCash = { viewModel.payCash(chargeableAmount) },
        onPayCard = {
            onProceedToCardPayment(viewModel.buildPaymentPayload(chargeableAmount))
        },
        onAddDiscountClick = {
            showDiscountSheet = true
            viewModel.loadAvailableDiscounts()
        },
        onAddServiceChargeClick = {
            showServiceChargeSheet = true
            viewModel.loadAvailableServiceCharges()
        },
        isRemovingDiscount = isRemovingDiscount,
        onRemoveDiscount = { discountId -> viewModel.removeDiscount(discountId) },
        isRemovingServiceCharge = isRemovingServiceCharge,
        onRemoveServiceCharge = { chargeId -> viewModel.removeServiceCharge(chargeId) },
    )

    if (showDiscountSheet) {
        DiscountPickerSheet(
            discounts = availableDiscounts,
            isLoading = isLoadingDiscounts,
            isApplying = isApplyingDiscount,
            onDismiss = { if (!isApplyingDiscount) showDiscountSheet = false },
            onSelect = { discountId ->
                discountTriggered = true
                viewModel.applyDiscount(discountId)
            },
        )
    }

    if (showServiceChargeSheet) {
        ServiceChargePickerSheet(
            charges = availableServiceCharges,
            isLoading = isLoadingServiceCharges,
            isApplying = isApplyingServiceCharge,
            onDismiss = { if (!isApplyingServiceCharge) showServiceChargeSheet = false },
            onSelect = { serviceChargeId ->
                serviceChargeTriggered = true
                viewModel.applyServiceCharge(serviceChargeId)
            },
        )
    }

    // 🧾 Recibo de EFECTIVO — ver KDoc del LaunchedEffect(tableReleased, cashReceipt)
    // de arriba. Se muestra en TODO cobro (encolado o no); solo cuando
    // [CashReceiptSummary.isSettlingPayment] es true se agrega la invitación a
    // calificar — nunca en un split parcial.
    state.cashReceipt?.let { receipt ->
        CashReceiptDialog(
            receipt = receipt,
            onDismiss = viewModel::consumeCashReceipt,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableCheckoutScreenContent(
    modifier: Modifier = Modifier,
    session: TableSession.Active,
    state: TableCheckoutState,
    chargeableAmount: BigDecimal,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onSelectSplitType: (CheckoutSplitType) -> Unit,
    onCustomAmountChanged: (String) -> Unit,
    onEqualPartsChanged: (Int) -> Unit,
    onEqualPartsPayingForChanged: (Int) -> Unit,
    onToggleItem: (String) -> Unit,
    onTipChanged: (BigDecimal) -> Unit,
    onPayCash: () -> Unit,
    onPayCard: () -> Unit,
    onAddDiscountClick: () -> Unit = {},
    onAddServiceChargeClick: () -> Unit = {},
    isRemovingDiscount: Boolean = false,
    onRemoveDiscount: (String) -> Unit = {},
    isRemovingServiceCharge: Boolean = false,
    onRemoveServiceCharge: (String) -> Unit = {},
) {
    val check = state.check

    Scaffold(
        modifier = modifier,
        topBar = {
            AvoqadoTopBar(
                title = "Cobrar · ${session.label}",
                subtitle = "Restante ${moneyDisplay(state.remaining)}",
                onNavigationClick = onNavigateBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CheckoutBottomBar(
                chargeableAmount = chargeableAmount,
                state = state,
                onPayCash = onPayCash,
                onPayCard = onPayCard,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading && check == null && !state.isProvisional -> {
                AvoqadoFullScreenLoading(message = "Cargando cuenta…", modifier = Modifier.padding(padding))
            }

            check == null && state.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .padding(Spacing.Space4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = state.errorMessage, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Spacing.Space3))
                    AvoqadoButton(text = "Reintentar", onClick = onRetryLoad)
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Space4, vertical = Spacing.Space3),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Space4),
                ) {
                    item(key = "summary") {
                        CheckSummaryCard(
                            check = check,
                            remaining = state.remaining,
                            isRemovingDiscount = isRemovingDiscount,
                            onRemoveDiscount = onRemoveDiscount,
                            isRemovingServiceCharge = isRemovingServiceCharge,
                            onRemoveServiceCharge = onRemoveServiceCharge,
                        )
                    }

                    // "Descuento" (Fase 1, APPLY_DISCOUNT) — solo con el cheque YA
                    // cargado (el catálogo se lee del server, no hay versión
                    // offline de "qué descuentos aplican"). No se condiciona a
                    // `state.isOnline`: si cae la red justo después de abrir el
                    // sheet, `DiscountPickerSheet` simplemente se queda cargando
                    // y el error real (si lo hay) sale por el snackbar compartido.
                    if (check != null) {
                        item(key = "add-discount") {
                            AvoqadoSecondaryButton(
                                text = "+ Agregar descuento",
                                onClick = onAddDiscountClick,
                                fullWidth = true,
                            )
                        }
                        // "Cargo por servicio" (Fase 3, APPLY_SERVICE_CHARGE) — mismo
                        // criterio que el descuento de arriba: solo con el cheque YA
                        // cargado (el catálogo se lee del server). Manual override del
                        // auto-apply por comensales, así que vive junto al descuento —
                        // ambos afectan el TOTAL antes de cobrar.
                        item(key = "add-service-charge") {
                            AvoqadoSecondaryButton(
                                text = "+ Agregar cargo por servicio",
                                onClick = onAddServiceChargeClick,
                                fullWidth = true,
                            )
                        }
                    }

                    item(key = "split-header") {
                        Text(
                            text = "Cómo dividir el cobro",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    item(key = "split-chips") {
                        SplitTypeChips(
                            selected = state.splitType,
                            itemsAvailable = !check?.items.isNullOrEmpty(),
                            onSelect = onSelectSplitType,
                        )
                    }

                    when (state.splitType) {
                        CheckoutSplitType.CUSTOMAMOUNT -> item(key = "custom-amount") {
                            OutlinedTextField(
                                value = state.customAmountInput,
                                onValueChange = onCustomAmountChanged,
                                label = { Text("Monto a cobrar") },
                                prefix = { Text("$") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        CheckoutSplitType.EQUALPARTS -> item(key = "equal-parts") {
                            EqualPartsPicker(
                                parts = state.equalParts,
                                payingFor = state.equalPartsPayingFor,
                                perPart = state.remaining.divide(BigDecimal(state.equalParts), 2, RoundingMode.UP),
                                onPartsChanged = onEqualPartsChanged,
                                onPayingForChanged = onEqualPartsPayingForChanged,
                            )
                        }

                        CheckoutSplitType.PERPRODUCT -> {
                            val currentCheck = check
                            if (currentCheck != null) {
                                val payableItems = currentCheck.items.filter { it.id !in currentCheck.paidItemIds }
                                items(payableItems, key = { "split-item-${it.id}" }) { item ->
                                    SelectableItemRow(
                                        item = item,
                                        selected = item.id in state.selectedItemIds,
                                        onToggle = { onToggleItem(item.id) },
                                    )
                                }
                            }
                        }

                        CheckoutSplitType.FULLPAYMENT -> Unit
                    }

                    item(key = "tip") {
                        OutlinedTextField(
                            value = if (state.tipAmount == BigDecimal.ZERO) "" else state.tipAmount.toPlainString(),
                            onValueChange = { text -> onTipChanged(text.toBigDecimalOrNull() ?: BigDecimal.ZERO) },
                            label = { Text("Propina (opcional)") },
                            prefix = { Text("$") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (!state.isOnline) {
                        item(key = "offline-banner") {
                            OfflineCardNotice(reason = state.cardPaymentDisabledReason)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckSummaryCard(
    check: OrderDetail?,
    remaining: BigDecimal,
    isRemovingDiscount: Boolean = false,
    onRemoveDiscount: (String) -> Unit = {},
    isRemovingServiceCharge: Boolean = false,
    onRemoveServiceCharge: (String) -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(Spacing.Space4)) {
            if (check == null) {
                Text(
                    text = "Sin conexión — abierta local; el desglose llega cuando la mesa se sincronice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            SummaryRow(label = "Subtotal", amount = check.subtotal)

            // "Quitar" (paridad Android 2026-08-06, Hallazgo #4) solo sobre
            // líneas con `id` real — el fallback agregado de abajo
            // (discountAmount sin desglose) no tiene un orderDiscountId
            // contra el cual llamar DELETE, así que no ofrece el control.
            check.orderDiscounts.forEach { discount ->
                SummaryRow(
                    label = discount.name.ifBlank { "Descuento" },
                    amount = -discount.amount,
                    color = MaterialTheme.avoqadoColors.statusSuccess,
                    onRemove = if (discount.id.isNotBlank()) {
                        { onRemoveDiscount(discount.id) }
                    } else {
                        null
                    },
                    removeEnabled = !isRemovingDiscount,
                )
            }
            if (check.orderDiscounts.isEmpty() && check.discountAmount > BigDecimal.ZERO) {
                SummaryRow(label = "Descuentos", amount = -check.discountAmount, color = MaterialTheme.avoqadoColors.statusSuccess)
            }

            // "Quitar" (paridad Android 2026-08-06, Hallazgo #4) — mismo
            // criterio que los descuentos arriba: solo sobre líneas con `id`
            // real. Un cargo AUTOMÁTICO (`isAutomatic`, puesto por la regla de
            // comensales) no ofrece el control — quitarlo a mano solo
            // reaparecería en el próximo `syncAutomaticServiceCharges` del
            // server mientras los comensales sigan calificando; Android
            // (`ServiceChargesDialog`) tiene la misma restricción.
            check.serviceCharges.forEach { charge ->
                SummaryRow(
                    label = charge.name.ifBlank { "Cobro por servicio" },
                    amount = charge.amount,
                    onRemove = if (charge.id.isNotBlank() && !charge.isAutomatic) {
                        { onRemoveServiceCharge(charge.id) }
                    } else {
                        null
                    },
                    removeEnabled = !isRemovingServiceCharge,
                )
            }
            if (check.serviceCharges.isEmpty() && check.serviceChargeAmount > BigDecimal.ZERO) {
                SummaryRow(label = "Cobros por servicio", amount = check.serviceChargeAmount)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.Space2))
            SummaryRow(label = "Total", amount = check.total, emphasized = true)

            if (check.paidAmount > BigDecimal.ZERO) {
                SummaryRow(label = "Ya pagado", amount = -check.paidAmount, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.Space2))
            SummaryRow(label = "Restante", amount = remaining, emphasized = true)
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    amount: BigDecimal,
    emphasized: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
    /** No-null = fila removible (ej. un descuento aplicado); dispara [onRemoveDiscount]. */
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                color = color ?: MaterialTheme.colorScheme.onSurface,
            )
            if (onRemove != null) {
                FilledIconButton(
                    onClick = onRemove,
                    enabled = removeEnabled,
                    modifier = Modifier.size(20.dp).padding(start = Spacing.Space1),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Quitar $label",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = moneyDisplay(amount),
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SplitTypeChips(
    selected: CheckoutSplitType,
    itemsAvailable: Boolean,
    onSelect: (CheckoutSplitType) -> Unit,
) {
    // 🔴 Bug real encontrado EN HARDWARE (PAX A910S, Task 8): con 4 chips en
    // una Row sin scroll, "Partes iguales"/"Por artículo" quedaban fuera del
    // ancho visible — NUNCA alcanzables, ni con scroll vertical (el Row no es
    // parte del eje que se desplaza). Confirmado con uiautomator: el último
    // chip visible ("Monto personalizado") terminaba en x=640 de 720dp de
    // ancho, sin espacio para uno más. Horizontal scroll propio, igual
    // patrón que un carrusel de categorías.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Space2),
    ) {
        CheckoutSplitType.entries
            .filter { it != CheckoutSplitType.PERPRODUCT || itemsAvailable }
            .forEach { type ->
                FilterChip(
                    selected = selected == type,
                    onClick = { onSelect(type) },
                    label = { Text(type.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
    }
}

@Composable
private fun EqualPartsPicker(
    parts: Int,
    payingFor: Int,
    perPart: BigDecimal,
    onPartsChanged: (Int) -> Unit,
    onPayingForChanged: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(Spacing.Space4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dividir entre", style = MaterialTheme.typography.bodyMedium)
                Stepper(value = parts, onDecrement = { onPartsChanged(parts - 1) }, onIncrement = { onPartsChanged(parts + 1) })
            }
            Spacer(Modifier.height(Spacing.Space2))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cobrando", style = MaterialTheme.typography.bodyMedium)
                Stepper(
                    value = payingFor,
                    onDecrement = { onPayingForChanged(payingFor - 1) },
                    onIncrement = { onPayingForChanged(payingFor + 1) },
                )
            }
            Spacer(Modifier.height(Spacing.Space2))
            Text(
                text = "${moneyDisplay(perPart)} por parte",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stepper(value: Int, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Space3)) {
        FilledIconButton(
            onClick = onDecrement,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Default.Remove, contentDescription = "Menos")
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.Space2),
        )
        FilledIconButton(
            onClick = onIncrement,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Más")
        }
    }
}

@Composable
private fun SelectableItemRow(item: OrderDetailItem, selected: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Space3, vertical = Spacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Text(
                text = "${item.quantity}× ${item.productName ?: "Artículo"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = moneyDisplay(item.total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = Spacing.Space2).size(20.dp),
            )
        }
    }
}

@Composable
private fun OfflineCardNotice(reason: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.avoqadoColors.offlineOrange.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(Spacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.WifiOff, contentDescription = null, tint = MaterialTheme.avoqadoColors.offlineOrange, modifier = Modifier.size(18.dp))
        Spacer(Modifier.padding(start = Spacing.Space2))
        Text(
            text = "Tarjeta no disponible — ${reason ?: "necesita conexión"}. La cuenta se queda abierta.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CheckoutBottomBar(
    chargeableAmount: BigDecimal,
    state: TableCheckoutState,
    onPayCash: () -> Unit,
    onPayCard: () -> Unit,
) {
    // 🔴 Contrato verificado, no asumido (Task 8): la pantalla de pago con
    // tarjeta de Nexgo (`AngelPayPaymentScreen`) NO expone `splitType` /
    // `equalPartsPartySize` / `paidProductIds` — solo `initialAmount` +
    // `orderId`. Sin esos parámetros, un cargo dividido ahí no tiene forma
    // de decirle al server CÓMO se dividió. Blumon (PAX, `PaymentScreen`) sí
    // los soporta — mismo contrato que `SplitByProductScreen`/
    // `SplitByPersonScreen` ya prueban en producción. Mientras eso no
    // cambie, TARJETA + división distinta de "cuenta completa" solo se
    // ofrece en PAX.
    val cardSupportsThisSplit = state.splitType == CheckoutSplitType.FULLPAYMENT || BuildConfig.ENABLE_PAX_SDK
    val cardEnabled = state.cardPaymentEnabled && cardSupportsThisSplit && chargeableAmount > BigDecimal.ZERO
    val cardReason = when {
        !state.cardPaymentEnabled -> state.cardPaymentDisabledReason
        !cardSupportsThisSplit -> "División no disponible en este equipo — cobra el total o usa efectivo"
        else -> null
    }
    // EFECTIVO + "por artículo" no existe hoy — PAY_CASH no acepta
    // paidProductsId (ver KDoc de TablesRepository.payCash). Hueco de
    // producto documentado, no un olvido.
    val cashSupportsThisSplit = state.splitType != CheckoutSplitType.PERPRODUCT
    val cashEnabled = cashSupportsThisSplit && chargeableAmount > BigDecimal.ZERO && !state.isProcessingCash

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider()
        if (cardReason != null) {
            Text(
                text = cardReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Space4, vertical = Spacing.Space1),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Space4, vertical = Spacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Space3),
        ) {
            CheckoutChargeButton(
                label = if (!cashSupportsThisSplit) "Efectivo no disponible" else "Efectivo",
                amountText = if (cashSupportsThisSplit) moneyDisplay(chargeableAmount) else null,
                onClick = onPayCash,
                enabled = cashEnabled,
                loading = state.isProcessingCash,
                icon = Icons.Default.Payments,
                modifier = Modifier.weight(1f),
            )
            CheckoutChargeButton(
                label = if (cardEnabled) "Tarjeta" else "Tarjeta no disponible",
                amountText = if (cardEnabled) moneyDisplay(chargeableAmount) else null,
                onClick = onPayCard,
                enabled = cardEnabled,
                icon = Icons.Default.CreditCard,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 🔴 Bug real encontrado EN HARDWARE (PAX A910S, Task 8) — verificado en
 * capturas de esta misma sesión (`t8-pax-16/17/18/29-*.png`, magnificadas):
 * el `AvoqadoButton` genérico (`core/presentation/components/AvoqadoButton.kt`)
 * pone `text` en una sola `Text` sin `maxLines`, dentro de un `Button` con
 * altura FIJA de 48dp (`Size.ButtonHeight`). "Efectivo · $845.50" no cabe en
 * una línea a la mitad del ancho de la barra — Compose lo envuelve a 2
 * líneas, pero el botón sigue recortando a 48dp: la línea 1 ("Efectivo ·")
 * se ve, la línea 2 (el MONTO) queda cortada casi por completo. El mesero
 * no puede leer cuánto está a punto de cobrar — exactamente el riesgo que
 * marcó el founder ("si lo hace mal o feo puede enojar al cliente").
 *
 * Fix LOCAL a esta pantalla (no se tocó `AvoqadoButton.kt` — es un
 * componente compartido por toda la app y no hay evidencia de que otras
 * pantallas disparen este mismo ancho de texto; cambiar su comportamiento
 * global es un riesgo aparte, fuera del alcance de Task 8). Layout propio de
 * 2 líneas con altura suficiente: etiqueta arriba, monto en `titleMedium`
 * bold abajo — SIEMPRE en su propia línea, nunca concatenado con el label
 * en una sola `Text`. `maxLines = 1` + `Ellipsis` en cada línea como red de
 * seguridad ante un monto absurdamente largo, no como mecanismo principal.
 */
@Composable
private fun CheckoutChargeButton(
    label: String,
    amountText: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = Spacing.Space3, vertical = Spacing.Space2),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Spacing.Space2))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (amountText != null) {
                        Text(
                            text = amountText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Confirmación de recibo tras un cobro en EFECTIVO — cierra la asimetría con
 * TARJETA descrita en el KDoc de [TableCheckoutViewModel]: TARJETA siempre
 * terminaba en la pantalla de éxito de "Cobrar" (con recibo); EFECTIVO se
 * quedaba mudo. Se muestra en TODO cobro (encolado o no) — ver KDoc de
 * [CashReceiptSummary].
 *
 * La invitación a calificar SOLO aparece cuando [CashReceiptSummary.isSettlingPayment]
 * es true — pedirla en cada pago de una cuenta dividida generaría un `Review`
 * por split para la MISMA visita y sesgaría el promedio del venue (regla dura
 * del founder, verificada contra `avoqado-server/prisma/schema.prisma:3716`:
 * `Review.venueId` promedia TODOS los reviews del venue). No hay picker de
 * estrellas aquí a propósito: `applyPayCash` (server) todavía no acepta un
 * campo `rating` en el payload de `PAY_CASH` — construir un selector que
 * capture un número y no lo mande a ningún lado sería peor que no tener uno
 * (el mesero/cliente creería que quedó guardado). En su lugar se apoya en el
 * canal que YA funciona hoy: la página del recibo digital (`receiptUrl`)
 * expone su propio formulario de review (`receiptReview.tpv.service.ts`,
 * ruta pública), así que EXISTE una forma real de calificar — esta pantalla
 * solo decide CUÁNDO invitar a usarla.
 */
@Composable
private fun CashReceiptDialog(
    receipt: CashReceiptSummary,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Payments, contentDescription = null) },
        title = { Text(if (receipt.queued) "Cobro guardado" else "Cobro registrado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Space1)) {
                if (!receipt.orderNumber.isNullOrBlank()) {
                    Text("Orden: ${receipt.orderNumber}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Cobrado: ${moneyDisplay(receipt.amount)}", style = MaterialTheme.typography.bodyMedium)
                if (receipt.tipAmount > BigDecimal.ZERO) {
                    Text("Propina: ${moneyDisplay(receipt.tipAmount)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Total: ${moneyDisplay(receipt.totalAmount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(Spacing.Space2))
                when {
                    receipt.queued -> Text(
                        "Sin conexión — el recibo digital estará disponible en cuanto este cobro se sincronice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    receipt.receiptUrl != null -> Text(
                        if (receipt.autofacturaAvailable) "El recibo digital incluye acceso a autofactura." else "Recibo digital disponible para compartir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Text(
                        "Este cobro no generó recibo digital.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (receipt.isSettlingPayment && receipt.receiptUrl != null) {
                    Spacer(Modifier.height(Spacing.Space2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.avoqadoColors.statusSuccess,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.Space1))
                        Text(
                            "Cuenta saldada — comparte el recibo para que el cliente pueda calificar la visita.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = if (receipt.receiptUrl != null) {
            {
                TextButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, receipt.receiptUrl)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Compartir recibo"))
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.Space1))
                    Text("Compartir")
                }
            }
        } else {
            null
        },
    )
}

private fun moneyDisplay(amount: BigDecimal): String {
    val scaled = amount.setScale(2, RoundingMode.HALF_UP)
    return if (scaled < BigDecimal.ZERO) "-$${scaled.abs().toPlainString()}" else "$${scaled.toPlainString()}"
}

// ══════════════════════════════════════════════════════════════════════
// PREVIEWS
// ══════════════════════════════════════════════════════════════════════

@Preview(showBackground = true, widthDp = 480, heightDp = 480, name = "NEXGO 480×480 — cuenta completa")
@Composable
private fun TableCheckoutScreenNexgoPreview() {
    AvoqadoTheme {
        TableCheckoutScreenContent(
            session = TableSession.Active(tableId = "1", tableNumber = "5", orderId = "order-1", total = BigDecimal("845.50")),
            state = TableCheckoutState(
                isLoading = false,
                check = previewCheck(),
                remaining = BigDecimal("845.50"),
            ),
            chargeableAmount = BigDecimal("845.50"),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onRetryLoad = {},
            onSelectSplitType = {},
            onCustomAmountChanged = {},
            onEqualPartsChanged = {},
            onEqualPartsPayingForChanged = {},
            onToggleItem = {},
            onTipChanged = {},
            onPayCash = {},
            onPayCard = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 1280, name = "PAX A920 — sin red + partes iguales")
@Composable
private fun TableCheckoutScreenPaxPreview() {
    AvoqadoTheme {
        TableCheckoutScreenContent(
            session = TableSession.Active(tableId = "1", tableNumber = "12", orderId = "order-1", total = BigDecimal("845.50")),
            state = TableCheckoutState(
                isLoading = false,
                check = previewCheck(),
                splitType = CheckoutSplitType.EQUALPARTS,
                equalParts = 3,
                equalPartsPayingFor = 1,
                remaining = BigDecimal("845.50"),
                isOnline = false,
            ),
            chargeableAmount = BigDecimal("281.84"),
            snackbarHostState = remember { SnackbarHostState() },
            onNavigateBack = {},
            onRetryLoad = {},
            onSelectSplitType = {},
            onCustomAmountChanged = {},
            onEqualPartsChanged = {},
            onEqualPartsPayingForChanged = {},
            onToggleItem = {},
            onTipChanged = {},
            onPayCash = {},
            onPayCard = {},
        )
    }
}

private fun previewCheck(): OrderDetail = OrderDetail(
    id = "order-1",
    subtotal = BigDecimal("900.00"),
    discountAmount = BigDecimal("100.00"),
    serviceChargeAmount = BigDecimal("45.50"),
    total = BigDecimal("845.50"),
    orderDiscounts = listOf(OrderDiscountLine(id = "d1", name = "Cliente frecuente", amount = BigDecimal("100.00"))),
    serviceCharges = listOf(OrderServiceChargeLine(id = "s1", name = "Propina automática 6 personas", amount = BigDecimal("45.50"))),
    items = listOf(
        OrderDetailItem(id = "i1", productName = "Café americano", quantity = 2, unitPrice = BigDecimal("45.00"), total = BigDecimal("90.00")),
        OrderDetailItem(id = "i2", productName = "Croissant", quantity = 1, unitPrice = BigDecimal("55.00"), total = BigDecimal("55.00")),
    ),
)
