package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jaac.avoqado_tpv.features.checkout.presentation.components.AvisoSinConexionDeCobrar
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.presentation.components.variantes.HojaDeVariantes
import com.jaac.avoqado_tpv.core.presentation.systemui.ImmersiveSystemUiEffect
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.textoDelErrorParaElCajero
import com.jaac.avoqado_tpv.features.checkout.domain.model.PaymentNavigationPayload
import com.jaac.avoqado_tpv.features.checkout.presentation.components.MosaicConfigView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.NoteDialog
import com.jaac.avoqado_tpv.features.checkout.presentation.components.NumericKeypadView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.ProductDetailSheet
import com.jaac.avoqado_tpv.features.checkout.presentation.components.ProductGridView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.SearchBarView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.SearchOverlayView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.ShortcutsGridView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.TabSelectorView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.TaxPercentDialog
import com.jaac.avoqado_tpv.features.checkout.presentation.components.VentaEnCeroGuardadaDialog
import com.jaac.avoqado_tpv.features.checkout.presentation.components.cart.CartDetailsSheet
import com.jaac.avoqado_tpv.features.checkout.presentation.components.cart.CartPanelView
import com.jaac.avoqado_tpv.features.checkout.presentation.components.cart.CustomerSelectorSheet
import com.jaac.avoqado_tpv.features.permissions.di.PermissionsEntryPoint
import com.jaac.avoqado_tpv.features.plan.domain.model.PlanFeatureCatalog
import com.jaac.avoqado_tpv.features.plan.domain.model.allowsFeature
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.formatoDePrecio
import com.jaac.avoqado_tpv.features.ordering.presentation.menu.hojaDeVariantesPermitida
import com.jaac.avoqado_tpv.features.referrals.presentation.ReferralCaptureSection
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import timber.log.Timber

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/**
 * Unified Checkout screen with four input tabs.
 *
 * Phase 3 scope: all four tabs functional (Teclado, Shortcuts, Productos,
 * Configurar). Top bar with search/refresh/QR scan wired to overlays and
 * the existing `BarcodeScannerScreen`. The cart panel and `onCharge` are
 * the same stub from Phase 2 — real PaymentScreen integration arrives in
 * Phase 5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: (PaymentNavigationPayload) -> Unit = { payload ->
        Timber.d("🛒 onNavigateToPayment (no caller wired) — payload=$payload")
    },
    viewModel: CheckoutViewModel = hiltViewModel(),
) {
    val cartState by viewModel.cartState.collectAsState()
    val avisoDescuentoDelCliente by viewModel.avisoDescuentoDelCliente.collectAsState()
    val products by viewModel.products.collectAsState()
    val familias by viewModel.familias.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorCatalogo by viewModel.errorCatalogo.collectAsState()
    val shortcuts by viewModel.shortcuts.collectAsState()
    val selectedCustomer by viewModel.selectedCustomer.collectAsState()
    val referralCode by viewModel.referralCode.collectAsState()
    val referralValidation by viewModel.referralValidation.collectAsState()
    // 🔒 Venta en preparación: mientras se arma/manda la venta, el carrito no cambia (el ViewModel lo rechaza y la pantalla no responde).
    val ventaEnPreparacion by viewModel.ventaEnPreparacion.collectAsState()
    val aviso by viewModel.aviso.collectAsState()
    val avisoSinConexion by viewModel.avisoSinConexion.collectAsState()
    val ventaSinMostrar by viewModel.ventaSinMostrar.collectAsState()
    val errorRecuperacion by viewModel.errorRecuperacion.collectAsState()
    val sinRed by viewModel.sinRed.collectAsState()
    val canCompItems by viewModel.canCompItems.collectAsState()
    val canApplyDiscount by viewModel.canApplyDiscount.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.actualizarEstadoDeCobrar()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Product opened in the modifier-selection sheet; null = closed.
    var productForModifiers by remember {
        mutableStateOf<com.jaac.avoqado_tpv.features.ordering.domain.Product?>(null)
    }

    // Producto agotado abierto en el modal informativo (Fase 2); null = cerrado.
    var productUnavailableInfo by remember {
        mutableStateOf<com.jaac.avoqado_tpv.features.ordering.domain.Product?>(null)
    }

    // groupId de la familia de variantes (talla/color) cuya hoja está abierta; null = cerrada. Se guarda el id y no la familia: la hoja
    // siempre lee `familias[id]` del catálogo de AHORA.
    var familiaAbierta by remember { mutableStateOf<String?>(null) }
    // Abrir una familia desde la búsqueda deja el teclado puesto, y taparía «Agregar al carrito» (la hoja no usa `imePadding`).
    val teclado = LocalSoftwareKeyboardController.current
    val foco = LocalFocusManager.current

    var selectedTab by rememberSaveable { mutableStateOf(InputTab.KEYPAD) }
    var amountCents by rememberSaveable { mutableIntStateOf(0) }
    var isChargeInFlight by remember { mutableStateOf(false) }
    var isPayLaterInFlight by remember { mutableStateOf(false) }
    // 💵 Venta en $0 guardada en la terminal (sin red): su diálogo con «Imprimir ticket» y «Listo».
    val ventaEnCero by viewModel.ventaEnCeroGuardada.collectAsState()
    var imprimiendoVentaEnCero by remember { mutableStateOf(false) }
    var errorAlImprimirVentaEnCero by remember { mutableStateOf<String?>(null) }

    // Overlays and modals
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showBarcodeScanner by rememberSaveable { mutableStateOf(false) }
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showTaxDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomerSheet by rememberSaveable { mutableStateOf(false) }
    var showCartDetailsSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sinRed) {
        if (sinRed) showCustomerSheet = false
    }
    val customerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cartDetailsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // V1 of the new Cobrar flow creates a complete chargeable order only.
    // Pay-later remains available in legacy order surfaces until backend
    // settleOrder inventory behavior is fixed.
    val payLaterAvailable = false

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Avisos del ViewModel (p. ej. «Recuperamos la venta que estaba a medias»): se muestran y luego se dan por vistos.
    LaunchedEffect(aviso) {
        val texto = aviso ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = texto, duration = SnackbarDuration.Long)
        viewModel.avisoMostrado()
    }

    // 👑 Plan-tier gating (fail open — null plan info ⇒ nothing is locked).
    // Gates ONLY: (a) manual ad-hoc discount creation (PROMOTIONS → Plan Pro)
    // and (b) referral capture (REFERRAL_PROGRAM → Plan Pro). Charging,
    // predefined discounts/coupons and printing are NEVER plan-gated.
    val context = LocalContext.current
    val planManager = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PermissionsEntryPoint::class.java,
        ).planManager()
    }
    val planInfo by planManager.planInfo.collectAsState()
    val manualDiscountPlanLocked = !planInfo.allowsFeature(PlanFeatureCatalog.PROMOTIONS)
    val referralPlanLocked = !planInfo.allowsFeature(PlanFeatureCatalog.REFERRAL_PROGRAM)

    Scaffold(
        // Header ("Cobrar" title bar) intentionally removed on this screen to reclaim
        // vertical height on the PAX A910S (640dp tall). Back navigation moves inline,
        // to the LEFT of the search bar, so the operator can still go back.
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Back arrow + search bar in a single row (replaces the removed top header).
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                    )
                }
                SearchBarView(
                    isLoading = isLoading,
                    onSearchTap = { if (!ventaEnPreparacion) showSearch = true },
                    onRefresh = { viewModel.refreshProducts() },
                    onBarcodeScan = { if (!ventaEnPreparacion) showBarcodeScanner = true },
                    modifier = Modifier.weight(1f),
                )
            }

            avisoSinConexion?.let { AvisoSinConexionDeCobrar(it) }

            TabSelectorView(
                selectedTab = selectedTab,
                onTabSelected = { if (!ventaEnPreparacion) selectedTab = it },
            )

            // Tab content — fills remaining space above the cart panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedTab) {
                    InputTab.KEYPAD -> NumericKeypadView(
                        amountCents = amountCents,
                        onAmountChange = { amountCents = it },
                        onAddToCart = {
                            if (amountCents > 0) {
                                viewModel.addCustomAmount(
                                    name = "Otro importe",
                                    amountCents = amountCents,
                                )
                                amountCents = 0
                            }
                        },
                        useCompactSizing = true,
                    )
                    InputTab.SHORTCUTS -> ShortcutsGridView(
                        errorCatalogo = errorCatalogo,
                        isLoading = isLoading,
                        onRetry = viewModel::refreshProducts,
                        cartState = cartState,
                        shortcuts = shortcuts,
                        selectedCustomer = selectedCustomer,
                        onShortcutTap = { shortcut ->
                            // No snackbar — the Cobrar button label updates
                            // immediately with the new total, which is
                            // already strong visual feedback. Con extras
                            // obligatorios abre la misma hoja que la cuadrícula.
                            viewModel.addShortcutToCart(shortcut)?.let { productForModifiers = it }
                        },
                        onConfigureTap = { selectedTab = InputTab.MOSAIC },
                        onApplyCortesia = viewModel::applyCortesiaToItem,
                        onRemoveCortesia = viewModel::removeCortesiaFromItem,
                        onApplyManualDiscount = viewModel::applyManualOrderDiscount,
                        onClearManualDiscount = {
                            viewModel.clearManualOrderDiscount()
                            // Also clear any predefined/coupon-derived order
                            // discount so "Quitar descuento" wipes both paths.
                            viewModel.applyOrderDiscount(null)
                        },
                        onValidateCoupon = viewModel::validateAndApplyCoupon,
                        onRemoveItem = viewModel::removeItem,
                        onSelectCustomer = { if (!sinRed) showCustomerSheet = true },
                        onConfirmPayLater = {
                            viewModel.createPayLaterOrder().fold(
                                onSuccess = { order ->
                                    viewModel.clearCart()
                                    viewModel.selectCustomer(null)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Orden ${order.orderNumber} guardada como Pagar después",
                                        )
                                    }
                                    Result.success(Unit)
                                },
                                onFailure = { Result.failure(it) },
                            )
                        },
                        snackbarHostState = snackbarHostState,
                        manualDiscountPlanLocked = manualDiscountPlanLocked,
                        sinRed = sinRed,
                        canCompItems = canCompItems,
                        canApplyDiscount = canApplyDiscount,
                    )
                    InputTab.PRODUCTS -> ProductGridView(
                        errorCatalogo = errorCatalogo,
                        onRetry = viewModel::refreshProducts,
                        products = products,
                        categories = categories,
                        isLoading = isLoading,
                        sinRed = sinRed,
                        familias = familias,
                        onFamiliaTap = { familiaAbierta = it.groupId },
                        onProductTap = { product ->
                            when {
                                // 🚫 Agotado: no se agrega, solo se informa QUÉ falta.
                                !product.available -> productUnavailableInfo = product
                                // Con modificadores → hoja de selección.
                                product.hasModifiers -> productForModifiers = product
                                // Directo al carrito.
                                else -> viewModel.addProduct(product)
                            }
                        },
                    )
                    InputTab.MOSAIC -> MosaicConfigView(
                        errorCatalogo = errorCatalogo,
                        isLoading = isLoading,
                        onRetry = viewModel::refreshProducts,
                        shortcuts = shortcuts,
                        products = products,
                        onAssignToSlot = { product, position ->
                            viewModel.assignShortcut(product, position)
                        },
                        onClearSlot = { shortcut -> viewModel.removeShortcut(shortcut.id) },
                    )
                }
                if (ventaEnPreparacion) {
                    VentaEnPreparacionVelo()
                }
            }

            CartPanelView(
                cartState = cartState,
                onTap = { showCartDetailsSheet = true },
                avisoDescuentoDelCliente = avisoDescuentoDelCliente,
                onQuitarDescuentoDelCliente = { viewModel.quitarDescuentoDelCliente() },
                onPedirAutorizacionDescuento = { viewModel.pedirAutorizacionDescuentoDelCliente() },
                onUsarDescuentoDelCliente = { viewModel.usarDescuentoDelCliente() },
            )
        }
    }

    // Cart details — opens when the operator taps the bottom Cobrar button.
    // All the cart context lives here: customer header, items, kebab actions
    // (Agregar nota, Agregar impuesto, Limpiar), Guardar carrito + Cobrar.
    if (showCartDetailsSheet) {
        val triggerCharge: () -> Unit = {
            if (!isChargeInFlight) {
                scope.launch {
                    isChargeInFlight = true
                    val resultado = viewModel.prepareForPayment()
                    // D5 (QA N86): libre ANTES de avisar. `showSnackbar` (Long) suspende ~10 s y dejaba el botón en «Preparando…».
                    isChargeInFlight = false
                    resultado.fold(
                        onSuccess = { payload ->
                            if (payload is PaymentNavigationPayload.CompletedFreeCart) {
                                viewModel.clearCart()
                                showCartDetailsSheet = false
                                snackbarHostState.showSnackbar(
                                    message = "Orden ${payload.orderNumber} completada como cortesía",
                                    duration = SnackbarDuration.Long,
                                )
                            } else if (payload is PaymentNavigationPayload.VentaEnCeroGuardada) {
                                // El ViewModel ya guardó la fila y consumió el carrito; conserva el ticket al recrear esta pantalla.
                                showCartDetailsSheet = false
                                errorAlImprimirVentaEnCero = null
                            } else {
                                showCartDetailsSheet = false
                                onNavigateToPayment(payload)
                            }
                        },
                        onFailure = { error ->
                            Timber.e(error, "🛒 prepareForPayment failed")
                            // D5 (QA N86): el snackbar del Scaffold queda DEBAJO de la hoja; se cierra antes, como en la cortesía. El
                            // carrito no se toca: «Cobrar» la vuelve a abrir.
                            showCartDetailsSheet = false
                            snackbarHostState.showSnackbar(
                                message = textoDelErrorParaElCajero(error, "No se pudo iniciar el cobro"),
                                duration = SnackbarDuration.Long,
                            )
                        },
                    )
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showCartDetailsSheet = false },
            sheetState = cartDetailsSheetState,
        ) {
            ImmersiveSystemUiEffect()

            CartDetailsSheet(
                cartState = cartState,
                customerName = selectedCustomer?.displayName,
                // Con la venta preparándose, ni el cliente ni la nota cambian (el ViewModel los rechazaría en silencio).
                onCustomerTap = { if (!ventaEnPreparacion && !sinRed) showCustomerSheet = true },
                necesitaInternet = sinRed,
                avisoDescuentoDelCliente = avisoDescuentoDelCliente,
                onQuitarDescuentoDelCliente = { viewModel.quitarDescuentoDelCliente() },
                onPedirAutorizacionDescuento = { viewModel.pedirAutorizacionDescuentoDelCliente() },
                onUsarDescuentoDelCliente = { viewModel.usarDescuentoDelCliente() },
                onClose = { showCartDetailsSheet = false },
                onClearCart = {
                    if (!ventaEnPreparacion) {
                        viewModel.clearCart()
                        showCartDetailsSheet = false
                    }
                },
                onRemoveItem = { viewModel.removeItem(it) },
                onIncrementQuantity = { viewModel.incrementQuantity(it) },
                onDecrementQuantity = { viewModel.decrementQuantity(it) },
                onAddNote = { if (!ventaEnPreparacion) showNoteDialog = true },
                // Tax is intentionally hidden in new Cobrar V1. Backend rejects
                // non-zero tax until payment math/receipts/dashboard support it.
                onApplyTax = null,
                onCharge = triggerCharge,
                isChargeInFlight = isChargeInFlight || ventaEnPreparacion,
                onPayLater = if (payLaterAvailable) {
                    {
                        if (!isPayLaterInFlight) {
                            scope.launch {
                                isPayLaterInFlight = true
                                val resultado = viewModel.createPayLaterOrder()
                                // D5 (QA N86): libre antes de avisar, como «Cobrar».
                                isPayLaterInFlight = false
                                resultado.fold(
                                    onSuccess = { order ->
                                        viewModel.clearCart()
                                        viewModel.selectCustomer(null)
                                        showCartDetailsSheet = false
                                        snackbarHostState.showSnackbar(
                                            message = "Orden ${order.orderNumber} guardada para cobrar después",
                                            duration = SnackbarDuration.Long,
                                        )
                                    },
                                    onFailure = { error ->
                                        Timber.e(error, "🛒 createPayLaterOrder failed")
                                        // D5 (QA N86): debajo de la hoja no se ve; se cierra antes del aviso. El carrito se queda.
                                        showCartDetailsSheet = false
                                        snackbarHostState.showSnackbar(
                                            message = textoDelErrorParaElCajero(error, "No se pudo guardar la orden"),
                                            duration = SnackbarDuration.Long,
                                        )
                                    },
                                )
                            }
                        }
                    }
                } else null,
                isSubmittingPayLater = isPayLaterInFlight || ventaEnPreparacion,
                referralSection = {
                    ReferralCaptureSection(
                        code = referralCode,
                        uiState = referralValidation,
                        customerSelected = selectedCustomer != null,
                        onCodeChange = viewModel::onReferralCodeChange,
                        onValidate = viewModel::validateReferralCode,
                        onClear = viewModel::clearReferral,
                        onForceOverride = {
                            // Force override is restricted to managers and not
                            // yet wired to the manager TOTP flow. Until that
                            // piece ships, show the user that the action is
                            // coming soon. The button still reflects the
                            // EXISTING_CUSTOMER state so the operator knows
                            // why the discount was rejected.
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Forzar atribución requiere un manager (próximamente)",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        },
                        planLocked = referralPlanLocked,
                        necesitaInternet = sinRed,
                    )
                },
            )
        }
    }

    // Note dialog — now writes to the cart's order-level note (the keypad
    // "+ Nota" button was removed; notes live in the cart details kebab).
    if (showNoteDialog) {
        NoteDialog(
            initialText = cartState.orderNote.orEmpty(),
            onDismiss = { showNoteDialog = false },
            onSave = { trimmed ->
                viewModel.setOrderNote(trimmed)
                showNoteDialog = false
            },
            title = if (cartState.orderNote.isNullOrBlank()) "Agregar nota" else "Editar nota",
        )
    }

    // Tax dialog — simple 3-button chooser (Sin / 8% / 16%) + custom input.
    if (showTaxDialog) {
        TaxPercentDialog(
            currentPercent = cartState.orderTaxPercent,
            onDismiss = { showTaxDialog = false },
            onApply = { percent ->
                viewModel.applyOrderTaxPercent(percent)
                showTaxDialog = false
            },
        )
    }

    // Customer selector — pulls Recientes by default, searches on type.
    if (showCustomerSheet && !sinRed) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.clearCustomerSearch()
                showCustomerSheet = false
            },
            sheetState = customerSheetState,
        ) {
            ImmersiveSystemUiEffect()

            CustomerSelectorSheet(
                viewModel = viewModel,
                onPick = { customer ->
                    viewModel.selectCustomer(customer)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Cliente: ${customer.displayName}",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
                onClear = { viewModel.selectCustomer(null) },
                onDismiss = {
                    viewModel.clearCustomerSearch()
                    showCustomerSheet = false
                },
                hasSelectedCustomer = selectedCustomer != null,
            )
        }
    }

    // Search overlay (full-screen)
    if (showSearch) {
        SearchOverlayView(
            viewModel = viewModel,
            onFamiliaTap = {
                teclado?.hide()
                foco.clearFocus()
                familiaAbierta = it.groupId
            },
            onProductTap = { product ->
                viewModel.updateSearchQuery("")
                showSearch = false
                when {
                    !product.available -> productUnavailableInfo = product
                    product.hasModifiers -> productForModifiers = product
                    else -> viewModel.addProduct(product)
                }
            },
            onDismiss = {
                viewModel.updateSearchQuery("")
                showSearch = false
            },
        )
    }

    // 🧩 Hoja de tallas/colores de la familia tocada (en la cuadrícula o en la búsqueda). Va DESPUÉS de la búsqueda, para pintarse encima
    // de ella (tocar una familia ahí la abre), y ANTES del escáner, que se pinta sobre todo; los diálogos y hojas de abajo son ventanas
    // propias. Cubre toda la pantalla y consume los toques (nada de lo de abajo los recibe).
    // Cobro rápido no tiene un «Preparando pago…» a pantalla completa: su equivalente es `isChargeInFlight` (el «Cobrar» de la hoja del
    // carrito mientras se crea la orden; esa hoja se puede cerrar con la operación en vuelo) y `isPayLaterInFlight`. Con eso en vuelo o
    // con el escáner abierto la hoja NO se pinta y se cierra: un artículo que entre en ese momento saldría fuera del cobro.
    val hojaPermitida = hojaDeVariantesPermitida(
        preparandoPago = isChargeInFlight || isPayLaterInFlight || ventaEnPreparacion,
        escanerAbierto = showBarcodeScanner,
    )
    val familiaDeLaHoja = if (hojaPermitida) familiaAbierta?.let { familias[it] } else null
    if (familiaAbierta != null && familiaDeLaHoja == null) {
        // La familia ya no es válida (catálogo refrescado) o no se puede mostrar ahora: la hoja se cierra sola.
        LaunchedEffect(familiaAbierta) { familiaAbierta = null }
    }
    if (familiaDeLaHoja != null) {
        // El Box aísla el zIndex(100f) de la hoja: lo que se monta DESPUÉS (el escáner) queda encima de ella.
        Box(modifier = Modifier.fillMaxSize()) {
            HojaDeVariantes(
                familia = familiaDeLaHoja,
                reglas = reglasDeCobro,
                precioTexto = { formatoDePrecio(it.price) },
                // Como hoy un agotado se muestra y al tocarlo avisa: aquí la talla agotada se ve marcada («Agotado»), sin «se puede vender
                // igual» (eso es de Android); las demás dicen cuántas quedan.
                aviso = ::avisoDeExistenciaEnCobro,
                onCerrar = { familiaAbierta = null },
                onElegir = { elegida ->
                    // 1º cerrar la hoja (la entrega es única por apertura); 2º resolver la variante por id en el catálogo de AHORA;
                    // 3º el camino de hoy: agotado ⇒ aviso de no disponible; con extras ⇒ sus extras; si no ⇒ agregar.
                    familiaAbierta = null
                    val accion = accionAlElegirEnCobro(viewModel.productoVigente(elegida.id))
                    if (accion != AccionAlElegir.Cerrar) {
                        // Si venía de la búsqueda, se cierra como al tocar un producto de ahí.
                        viewModel.updateSearchQuery("")
                        showSearch = false
                    }
                    when (accion) {
                        is AccionAlElegir.Agregar -> viewModel.addProduct(accion.variante)
                        is AccionAlElegir.AbrirExtras -> productForModifiers = accion.variante
                        is AccionAlElegir.NoDisponible -> productUnavailableInfo = accion.variante
                        AccionAlElegir.Cerrar -> Unit
                    }
                },
            )
        }
    }

    // Modifier selection sheet — opens when an product with modifier groups
    // is tapped (from grid, search, or barcode scan).
    productForModifiers?.let { product ->
        ModalBottomSheet(
            onDismissRequest = { productForModifiers = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ImmersiveSystemUiEffect()

            ProductDetailSheet(
                product = product,
                onClose = { productForModifiers = null },
                onAddToCart = { selectedModifiers ->
                    viewModel.addProduct(product, modifiers = selectedModifiers)
                    productForModifiers = null
                },
            )
        }
    }

    // Modal informativo al tocar un producto agotado (Fase 2). Solo informa QUÉ
    // falta — nunca agrega al carrito.
    productUnavailableInfo?.let { product ->
        UnavailableProductDialog(
            product = product,
            onDismiss = { productUnavailableInfo = null },
        )
    }

    // Barcode scanner (full-screen camera)
    if (showBarcodeScanner) {
        BarcodeScannerScreen(
            onBarcodeScanned = { barcode, _format ->
                showBarcodeScanner = false
                viewModel.findProductByBarcode(barcode) { product, delCatalogoGuardado ->
                    when {
                        product == null -> scope.launch {
                            snackbarHostState.showSnackbar(
                                // Sin red sólo se buscó en el catálogo guardado: decir «no encontrado» sería falso.
                                message = if (delCatalogoGuardado) {
                                    "No está en el catálogo guardado"
                                } else {
                                    "Producto no encontrado (código: $barcode)"
                                },
                                duration = SnackbarDuration.Short,
                            )
                        }
                        !product.available -> productUnavailableInfo = product
                        product.hasModifiers -> productForModifiers = product
                        else -> viewModel.addProduct(product)
                    }
                }
            },
            onClose = { showBarcodeScanner = false },
        )
    }

    if (errorRecuperacion != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Venta anterior guardada") },
            text = { Text(errorRecuperacion.orEmpty()) },
            confirmButton = {
                TextButton(onClick = viewModel::actualizarEstadoDeCobrar, enabled = !ventaEnPreparacion) { Text("Reintentar") }
            },
        )
    } else if (ventaEnCero == null) ventaSinMostrar?.let { vista ->
        val venta = vista.venta
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Esta venta ya quedó cobrada y guardada: $${String.format(java.util.Locale.US, "%.2f", (venta.totalCents.toLong() + vista.propinaCents) / 100.0)}") },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    venta.renglones.forEach { renglon ->
                        Text("${renglon.cantidad}× ${renglon.nombre} · $${String.format(java.util.Locale.US, "%.2f", renglon.totalCents / 100.0)}")
                        renglon.extras.forEach { Text("  + ${it.nombre}", style = MaterialTheme.typography.bodySmall) }
                        renglon.nota?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.ventaGuardadaVista(venta.llave) }, enabled = !ventaEnPreparacion) { Text("Entendido") }
            },
        )
    }

    // 💵 Venta en $0 guardada sin red: no pasó por la pantalla de cobro. Verla cuenta como «la venta se mostró» (exito_mostrado).
    ventaEnCero?.let { venta ->
        LaunchedEffect(venta.llave) { viewModel.ventaGuardadaVista(venta.llave) }
        VentaEnCeroGuardadaDialog(
            articulos = venta.renglones.sumOf { it.cantidad },
            imprimiendo = imprimiendoVentaEnCero,
            errorDeImpresion = errorAlImprimirVentaEnCero,
            onImprimir = {
                if (!imprimiendoVentaEnCero) {
                    scope.launch {
                        imprimiendoVentaEnCero = true
                        errorAlImprimirVentaEnCero = viewModel.imprimirVentaGuardada(venta).exceptionOrNull()
                            ?.let { textoDelErrorParaElCajero(it, "No se pudo imprimir el ticket") }
                        imprimiendoVentaEnCero = false
                    }
                }
            },
            onListo = {
                errorAlImprimirVentaEnCero = null
                viewModel.cerrarVentaEnCero()
            },
        )
    }
}

/**
 * 🔒 Velo de «venta en preparación» sobre las pestañas de entrada: se traga los toques (la cuadrícula, el teclado y los atajos no
 * responden) y dice qué pasa. Neutro, no rojo: es una espera normal.
 */
@Composable
private fun VentaEnPreparacionVelo() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier.padding(top = 16.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Preparando…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Modal informativo para un producto que no se puede vender ahora mismo.
 *
 * Solo informa —nunca agrega al carrito—. Cuando el backend manda el detalle de
 * insumos (Fase 2), lista exactamente QUÉ falta; si no, cae a un texto genérico
 * según el método de inventario.
 */
@Composable
private fun UnavailableProductDialog(
    product: com.jaac.avoqado_tpv.features.ordering.domain.Product,
    onDismiss: () -> Unit,
) {
    val isRecipe = product.inventoryMethod == "RECIPE"
    // Prefiere la lista de insumos insuficientes; si viene vacía usa el cuello de
    // botella; si no hay nada (backend viejo) queda vacío → texto genérico.
    val shortages = product.insufficientIngredients
        .ifEmpty { product.limitingIngredient?.let { listOf(it) } ?: emptyList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Entendido") }
        },
        title = { Text(product.name) },
        text = {
            Column {
                ImmersiveSystemUiEffect()

                when {
                    // Fase 2: sabemos QUÉ insumo(s) faltan.
                    isRecipe && shortages.isNotEmpty() -> {
                        Text("No disponible porque falta:")
                        Spacer(modifier = Modifier.height(8.dp))
                        shortages.forEach { ing ->
                            Text(
                                text = "•  " + describeShortage(ing),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Avisa al encargado para resurtir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Receta agotada pero sin detalle (backend viejo).
                    isRecipe -> Text(
                        "No disponible. Se agotó un insumo de la receta. " +
                            "Avisa al encargado para resurtir.",
                    )
                    // Producto por cantidad agotado.
                    product.trackInventory -> Text("No disponible. Se agotó el stock de este producto.")
                    // Inactivo u otra causa.
                    else -> Text("Este producto no está disponible por ahora.")
                }
            }
        },
    )
}

/** "Pan: agotado" · "Pan: quedan 0.5 kg (se necesita 1 kg)" */
private fun describeShortage(
    ing: com.jaac.avoqado_tpv.features.ordering.domain.IngredientShortage,
): String {
    val unit = unitLabel(ing.unit)
    return if (ing.available <= 0.0) {
        "${ing.name}: agotado"
    } else {
        "${ing.name}: quedan ${formatQty(ing.available)}$unit (se necesita ${formatQty(ing.required)}$unit)"
    }
}

private fun formatQty(value: Double): String {
    return if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

/** Unidad del backend → forma corta legible (con espacio inicial para concatenar). */
private fun unitLabel(unit: String): String = when (unit.uppercase()) {
    "PIECE", "PIEZA", "UNIT", "UNIDAD" -> " pza"
    "GRAM", "GRAMO" -> " g"
    "KILOGRAM", "KILO", "KG" -> " kg"
    "LITER", "LITRO" -> " l"
    "MILLILITER", "ML" -> " ml"
    "", "NONE" -> ""
    else -> " ${unit.lowercase()}"
}

@Preview(name = "Checkout - Empty cart", device = PAX_A910S, showSystemUi = true)
@Composable
private fun CheckoutScreenEmptyPreview() {
    AvoqadoTheme {
        CheckoutScreenPreviewStub(cartState = CartState())
    }
}

/**
 * Preview-only stand-in that reproduces the screen layout without depending on
 * Hilt-injected dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckoutScreenPreviewStub(cartState: CartState) {
    var selectedTab by remember { mutableStateOf(InputTab.KEYPAD) }
    var amountCents by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cobrar", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchBarView(isLoading = false, onSearchTap = {}, onRefresh = {}, onBarcodeScan = {})
            TabSelectorView(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                NumericKeypadView(
                    amountCents = amountCents,
                    onAmountChange = { amountCents = it },
                    onAddToCart = {},
                    useCompactSizing = true,
                )
            }
            CartPanelView(cartState = cartState, onTap = {})
        }
    }
}
