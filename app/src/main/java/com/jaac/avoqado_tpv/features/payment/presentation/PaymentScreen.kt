package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timber.log.Timber
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTextField
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.features.payment.domain.AuthWatchdogLevel
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.presentation.components.CryptoPaymentLoadingScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.CryptoPaymentQrScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.KioskCashConfirmationContent
import com.jaac.avoqado_tpv.features.payment.presentation.components.PaymentApprovedScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.WhatsAppReceiptDialog
import com.jaac.avoqado_tpv.features.verification.presentation.VerificationScreen
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen
import java.io.File
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentFlowOrigin
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.model.OrderNumberFormatter
// 👤 Customer search imports (for email receipt dialog)
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerSearchState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Search
import kotlinx.coroutines.delay

/**
 * PaymentScreen - EMV chip card payment with online authorization via Blumon Momentum
 *
 * Flow: PreTrans → DetectCard → StartEmvTrans → SaleIcc (ONLINE) → CompleteEmvTrans
 *
 * 💸 REFUND MODE:
 * When isRefundMode=true, this screen processes a TransType.REFUND instead of SALE.
 * The refund uses the same EMV flow but with the original payment's merchant account.
 */
@Composable
fun PaymentScreen(
    initialAmount: String? = null,
    orderId: String? = null,  // 🆕 Order ID (for order payment with inventory deduction)
    orderNumber: String? = null,  // 🆕 Order number (for display in receipt)
    tableId: String? = null,  // 🆕 Table ID (for clearing table post-payment)
    skipReview: Boolean = false,  // 🧪 Skip rating/tip (test payment from SuperAdmin)
    externalTipCents: Long? = null,  // 🔵 External device tip (cents)
    externalRating: Int? = null,  // 🔵 External device rating (1-5)
    externalSkipReview: Boolean = false,  // 🔵 External device: skip rating/tip screens
    skipLocalOrderValidation: Boolean = false,  // 📱 SERIALIZED SALE: Order exists only on backend, skip local lookup AND sync
    isPortabilidad: Boolean = false,  // 📱 PORTABILIDAD: Controls 1 vs 2 proof-of-sale photos
    serialNumber: String? = null,  // 📱 SERIALIZED: ICCID/serial number for receipt
    categoryName: String? = null,  // 📱 SERIALIZED: Category name for receipt
    // ⭐ Split payment params (from SplitByPersonScreen or SplitByProductScreen)
    splitType: String? = null,  // EQUALPARTS, PERPRODUCT, CUSTOMAMOUNT, FULLPAYMENT
    equalPartsPartySize: Int? = null,  // Total people for EQUALPARTS mode
    equalPartsPayedFor: Int? = null,  // How many parts being paid now
    paidProductIds: List<String> = emptyList(),  // Product IDs for PERPRODUCT mode
    // 💸 REFUND MODE PARAMS
    isRefundMode: Boolean = false,  // 💸 True = process refund, False = normal payment
    refundAmount: String? = null,  // 💸 Amount to refund
    refundReason: String? = null,  // 💸 Reason enum name (CUSTOMER_REQUEST, etc.)
    originalPaymentId: String? = null,  // 💸 Original payment being refunded
    originalOrderId: String? = null,  // 💸 Original order (if applicable)
    originalTotalAmount: String? = null,  // 💸 Original payment total
    originalTipAmount: String? = null,  // 💸 Original tip amount
    refundMerchantAccountId: String? = null,  // 💸 CRITICAL: Original payment's merchant
    refundBlumonSerialNumber: String? = null,  // 💸 Original payment's terminal serial
    originalOperationNumber: Int? = null,  // 🎫 CRITICAL: Blumon operation number for CancelIcc (from webhook)
    refundVenueId: String? = null,  // 🏢 CRITICAL: Payment's venueId for refund API call (NOT auth context's venue!)
    refundTipCents: Int? = null,  // 💸 Optional explicit tip portion override (null = backend default proportional, 0 = sale-only)
    // 💳 PAY-LATER CONTEXT PARAMS
    wasPayLaterOrder: Boolean = false,  // 💳 True = order had customers (for contextual button)
    payLaterOrdersCount: Int = 0,  // 💳 Remaining pay-later orders count
    // 🥝 KIOSK MODE PARAMS
    isKioskPayment: Boolean = false,  // 🥝 True = payment from kiosk self-service flow
    kioskStaffId: String? = null,  // 🥝 Staff ID from kiosk session for sales attribution (commissions/tips). If null, uses authContext staffId.
    onKioskPaymentSuccess: ((String, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt?, List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>?) -> Unit)? = null,  // 🥝 Callback with orderNumber + receipt + orderItems when kiosk payment succeeds
    // 📡 SOCKET PAYMENT SOURCE (for sending result back via Socket.IO)
    paymentSource: String? = null,  // "BLE" | "SOCKET" | null (direct)
    socketRequestId: String? = null,  // Request ID for Socket.IO result callback
    socketProcessedByStaffId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit = onNavigateBack,
    onRefundComplete: () -> Unit = onNavigateBack,
    onNavigateToShifts: () -> Unit = {},  // 🆕 Navigate to Shifts screen (for "No shift open" errors)
    onNavigateToNewOrder: () -> Unit = {},  // 🆕 Navigate to new order (Toast/Square pattern)
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Serialized sale: return to scanner flow
    onNavigateToNewFastPayment: () -> Unit = {},  // 🆕 Navigate to new fast payment (open WelcomeScreen modal)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {},  // 🆕 Clear table and return to floor plan (tableId)
    onNavigateToOrder: (String, String?) -> Unit = { _, _ -> },  // ⭐ NEW: Navigate to order for split payment (orderId, tableId)
    onNavigateToPayLaterOrders: () -> Unit = {},  // 💳 Navigate to pay-later orders list
    viewModel: PaymentViewModel = hiltViewModel()
) {
    // [PERF] Composition tracking
    LaunchedEffect(Unit) {
        Timber.d("[PERF] PaymentScreen COMPOSED at ${android.os.SystemClock.elapsedRealtime()}ms")
    }

    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    // 🧭 MERCHANT_ROUTING_RULES: eligibility for the charge in progress (drives filter + banner)
    val merchantRouting by viewModel.merchantRouting.collectAsStateWithLifecycle()

    // 📸 PROOF-OF-SALE: derived from PaymentSession snapshot (module-driven)
    val showProofOfSale by viewModel.showProofOfSale.collectAsStateWithLifecycle()
    val isUploadingProofOfSale by viewModel.isUploadingProofOfSale.collectAsStateWithLifecycle()
    val isPortabilidadState by viewModel.isPortabilidad.collectAsStateWithLifecycle()
    val proofOfSaleComplete by viewModel.proofOfSaleComplete.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    val merchantSwitchingLoading by viewModel.merchantSwitchingLoading.collectAsStateWithLifecycle()
    val merchantSwitchMessage by viewModel.merchantSwitchMessage.collectAsStateWithLifecycle()
    val hideKioskMerchantSelector by viewModel.hideKioskMerchantSelector.collectAsStateWithLifecycle()  // 🥝 Hide merchant list in kiosk mode
    val tpvSettings by viewModel.tpvSettings.collectAsStateWithLifecycle()
    val pinEntryState by viewModel.pinEntryState.collectAsStateWithLifecycle()  // PIN asterisks feedback
    val isPinDialogVisible by viewModel.isPinDialogVisible.collectAsStateWithLifecycle()  // PIN dialog visibility
    val isSendingReceipt by viewModel.isSendingReceipt.collectAsStateWithLifecycle()  // 📧 Send receipt loading
    val isPrinting by viewModel.isPrinting.collectAsStateWithLifecycle()  // 🖨️ Receipt printing loading
    val sendReceiptMessage by viewModel.sendReceiptMessage.collectAsStateWithLifecycle()  // 📧 Send receipt result
    val flowOrigin by viewModel.flowOrigin.collectAsStateWithLifecycle()
    // 👤 Customer search states (for email receipt dialog)
    val customerSearchState by viewModel.customerSearchState.collectAsStateWithLifecycle()
    val recentCustomers by viewModel.recentCustomers.collectAsStateWithLifecycle()
    val isLoadingRecentCustomers by viewModel.isLoadingRecentCustomers.collectAsStateWithLifecycle()

    // 📧 Show toast when send receipt message changes
    LaunchedEffect(sendReceiptMessage) {
        sendReceiptMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearSendReceiptMessage()
        }
    }

    // 🔀 Set initial flow origin from navigation args (prevents back stack leaks)
    val initialFlowOrigin = remember(skipLocalOrderValidation, orderId, isRefundMode, isKioskPayment) {
        when {
            isRefundMode -> PaymentFlowOrigin.REFUND
            isKioskPayment -> PaymentFlowOrigin.KIOSK
            skipLocalOrderValidation -> PaymentFlowOrigin.SERIALIZED
            orderId != null -> PaymentFlowOrigin.ORDER
            else -> PaymentFlowOrigin.FAST
        }
    }
    LaunchedEffect(initialFlowOrigin) {
        viewModel.setFlowOrigin(initialFlowOrigin)
    }
    val effectiveOrigin = if (flowOrigin == PaymentFlowOrigin.FAST) initialFlowOrigin else flowOrigin

    // 🥝 KIOSK MODE: Set kiosk payment mode in ViewModel for secure cash flow
    // Pass kioskStaffId for sales attribution (commissions/tips)
    LaunchedEffect(isKioskPayment, kioskStaffId) {
        viewModel.setKioskPaymentMode(isKioskPayment, kioskStaffId)
    }

    // 📱 PORTABILIDAD: Set portabilidad mode for proof-of-sale photo count
    LaunchedEffect(isPortabilidad) {
        viewModel.setIsPortabilidad(isPortabilidad)
    }

    // 📱 SERIALIZED: Pass serial number and category for receipt
    LaunchedEffect(serialNumber, categoryName) {
        viewModel.setSerializedItemInfo(serialNumber, categoryName)
    }

    // 📡 SOCKET PAYMENT: Pass source info to ViewModel for result callback
    LaunchedEffect(paymentSource, socketRequestId, socketProcessedByStaffId) {
        viewModel.setSocketPaymentSource(paymentSource, socketRequestId)
        viewModel.setSocketProcessedByStaffId(socketProcessedByStaffId)
    }

    // 📊 Dynamic step counter based on TPV settings
    // PRE-PAYMENT screens: Review? → Tip? → Payment (always)
    // POST-PAYMENT: Verification? (separate, after success)
    val showReview = tpvSettings?.showReviewScreen ?: false
    val showTip = tpvSettings?.showTipScreen ?: false
    val showVerification = tpvSettings?.showVerificationScreen ?: false

    // Only count PRE-PAYMENT steps (verification is post-payment)
    val prePaymentSteps = listOf(showReview, showTip, true /* payment */).count { it }

    // Calculate current step number based on state and enabled screens
    fun getCurrentStep(currentState: PaymentState): Int {
        return when (currentState) {
            is PaymentState.CollectingRating -> 1
            is PaymentState.CollectingTip -> if (showReview) 2 else 1
            is PaymentState.SelectingMerchant -> {
                var step = 1
                if (showReview) step++
                if (showTip) step++
                step
            }
            else -> 0 // No step indicator for other states
        }
    }

    // Dynamic topBar titles based on payment state
    // Note: EnteringAmount removed - amount now comes from WelcomeScreen modal
    val (topBarTitle, topBarSubtitle) = when (val currentState = state) {
        is PaymentState.CollectingRating -> {
            val step = getCurrentStep(currentState)
            "Calificación" to "Paso $step de $prePaymentSteps · $${currentState.amount}"
        }
        is PaymentState.CollectingTip -> {
            val step = getCurrentStep(currentState)
            val tipAmount = currentState.tipAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val subtotal = currentState.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val total = subtotal.add(tipAmount)

            if (tipAmount > java.math.BigDecimal.ZERO) {
                "Propina" to "Paso $step de $prePaymentSteps · Total: $$total MXN"
            } else {
                "Propina" to "Paso $step de $prePaymentSteps · Subtotal: $${currentState.amount} MXN"
            }
        }
        is PaymentState.SelectingMerchant -> {
            val step = getCurrentStep(currentState)
            "Seleccionar Cuenta" to "Paso $step de $prePaymentSteps · Total: $${currentState.totalAmount}"
        }
        is PaymentState.Verifying -> {
            // Post-payment verification - separate from pre-payment steps
            "Verificación" to "Post-pago · $${currentState.amount}"
        }
        // 🥝 KIOSK: Cash confirmation shows different title
        is PaymentState.AwaitingCashConfirmation -> "Pago en Efectivo" to null
        // 💸 Different title for refund mode vs regular payment
        else -> if (isRefundMode) "Reembolso con Tarjeta" to null else "Pago con Tarjeta" to null
    }

    // Hide topBar on Success screen (full-screen receipt)
    val showTopBar = state !is PaymentState.Success
    val navigateBack = resolveBackNavigation(
        flowOrigin = effectiveOrigin,
        orderId = orderId,
        tableId = tableId,
        onNavigateBack = onNavigateBack,
        onNavigateToSerializedSale = onNavigateToSerializedSale,
        onNavigateToOrder = onNavigateToOrder
    )

    Scaffold(
        topBar = {
            if (showTopBar) {
                AvoqadoTopBar(
                    title = topBarTitle,
                    subtitle = topBarSubtitle,
                    onNavigationClick = {
                        // Try to go back one step in payment flow first
                        // If at first step (returns false), navigate back to home
                        if (!viewModel.goBackOneStep()) {
                            // 🔧 FIX: Reset ViewModel to Idle so next payment uses new initialAmount
                            // Without this, the LaunchedEffect in Idle block won't re-run
                            viewModel.resetPayment()
                            navigateBack()
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                // ✅ EnteringAmount should never show in new flow
                // Amount ALWAYS comes from WelcomeScreen modal
                is PaymentState.EnteringAmount -> {
                    // If we somehow end up here, immediately navigate back to WelcomeScreen
                    LaunchedEffect(Unit) {
                        navigateBack()
                    }

                    // Show loading while navigating (prevents flash)
                    AvoqadoLoadingOverlay(
                        message = "Regresando..."
                    )
                }

                is PaymentState.CollectingRating -> {
                    ReviewScreen(
                        currentReview = currentState.rating,
                        amount = currentState.amount,
                        onReviewChange = { rating ->
                            // ⭐ NEW: Use combined function to avoid state race condition
                            // This ensures correct rating value when auto-advancing
                            viewModel.selectRatingAndProceed(currentState.amount, rating)
                        },
                        onContinue = {
                            // This is now only called by "Saltar" button (kept for backward compatibility)
                            viewModel.submitRating(currentState.amount, currentState.rating)
                        },
                        onSkip = {
                            viewModel.skipRating(currentState.amount)
                        },
                        onNavigateBack = {
                            // ✅ NEW: goBackOneStep() returns false (first step)
                            // Navigate back to WelcomeScreen
                            if (!viewModel.goBackOneStep()) {
                                // 🔧 FIX: Reset ViewModel to Idle so next payment uses new initialAmount
                                viewModel.resetPayment()
                                navigateBack()
                            }
                        }
                    )
                }

                is PaymentState.CollectingTip -> {
                    TipScreen(
                        subtotal = currentState.amount,
                        selectedTipPercentage = currentState.selectedTipPercentage,
                        customTipAmount = if (currentState.selectedTipPercentage == null && currentState.tipAmount != "0") {
                            currentState.tipAmount
                        } else null,
                        // 🎯 Pass tip settings from TPV configuration
                        tipSuggestions = tpvSettings?.tipSuggestions ?: listOf(10, 15, 20),
                        defaultTipPercentage = tpvSettings?.defaultTipPercentage,
                        // 🎯 Called when user taps a tip option (updates header without advancing)
                        onTipSelectionChanged = { percentage ->
                            viewModel.updateTipPercentage(currentState.amount, currentState.rating, percentage)
                        },
                        // 🎯 Called when user confirms custom tip in modal (updates header without advancing)
                        onCustomTipChanged = { customTip ->
                            viewModel.updateCustomTip(currentState.amount, currentState.rating, customTip)
                        },
                        onTipPercentageSelected = { percentage ->
                            // 🎯 Called when user presses "Continuar" with percentage selected
                            viewModel.selectTipPercentageAndProceed(currentState.amount, currentState.rating, percentage)
                        },
                        onCustomTipSelected = { customTip ->
                            // 🎯 Called when user presses "Continuar" with custom tip
                            viewModel.selectCustomTipAndProceed(currentState.amount, currentState.rating, customTip)
                        },
                        onContinue = {
                            // Legacy callback (kept for backward compatibility)
                            viewModel.submitTip(currentState.amount, currentState.tipAmount, currentState.rating)
                        },
                        onSkipTip = {
                            // 🎯 Called when user presses "Continuar" with "Sin propina" selected
                            viewModel.skipTip(currentState.amount, currentState.rating)
                        },
                        onNavigateBack = {
                            // Go back to rating
                            viewModel.goBackOneStep()
                        }
                    )
                }

                // 📸 PRE-Payment Verification (Step 4 - BEFORE payment processing)
                // Flow: CollectingTip → VerifyingPrePayment → SelectingMerchant → Payment
                is PaymentState.VerifyingPrePayment -> {
                    val context = LocalContext.current
                    val verificationDir = remember {
                        java.io.File(context.cacheDir, "verification_photos").apply { mkdirs() }
                    }

                    // State for showing camera/scanner overlays
                    var showCamera by remember { mutableStateOf(false) }
                    var showScanner by remember { mutableStateOf(false) }

                    if (showCamera) {
                        CameraPreviewScreen(
                            onPhotoCaptured = { photoPath ->
                                viewModel.addPrePaymentPhoto(photoPath)
                                showCamera = false
                            },
                            onClose = { showCamera = false },
                            outputDirectory = verificationDir
                        )
                    } else if (showScanner) {
                        BarcodeScannerScreen(
                            onBarcodeScanned = { barcode, format ->
                                viewModel.addPrePaymentBarcode(barcode, format)
                                showScanner = false
                            },
                            onClose = { showScanner = false }
                        )
                    } else {
                        // Show verification screen with PRE-payment context
                        VerificationScreen(
                            paymentId = null, // 📸 PRE-payment: No paymentId yet (payment hasn't happened)
                            amount = currentState.amount,
                            orderNumber = null, // PRE-payment: order number not available yet
                            capturedPhotos = currentState.capturedPhotos,
                            scannedBarcodes = currentState.scannedBarcodes,
                            requirePhoto = currentState.requirePhoto,
                            requireBarcode = currentState.requireBarcode,
                            isUploading = currentState.isUploading,
                            uploadProgress = 0f,
                            error = currentState.error,
                            onTakePhoto = { showCamera = true },
                            onScanBarcode = { showScanner = true },
                            onRemovePhoto = { index ->
                                viewModel.removePrePaymentPhoto(index)
                            },
                            onRemoveBarcode = { barcode ->
                                viewModel.removePrePaymentBarcode(barcode)
                            },
                            onConfirm = {
                                viewModel.completePrePaymentVerification()
                            },
                            onSkip = {
                                viewModel.skipPrePaymentVerification()
                            },
                            // 📸 PRE-payment specific: Back button and conditional skip
                            canSkip = currentState.canSkip(),
                            onNavigateBack = {
                                viewModel.goBackFromPrePaymentVerification()
                            }
                        )
                    }
                }

                is PaymentState.SelectingMerchant -> {
                    // 🧭 MERCHANT_ROUTING_RULES: show only eligible accounts (unless show-all), plus a
                    // banner when no rule matched. Fail-open filtering never leaves the selector empty.
                    val routing = merchantRouting
                    val visibleMerchants = if (routing == null || routing.shouldShowAll) {
                        merchants
                    } else {
                        merchants.filter { routing.eligibleMerchantAccountIds.contains(it.merchantAccountId) }
                            .ifEmpty { merchants }
                    }
                    val routingBanner = if (routing?.showFallbackBanner == true) {
                        "Mostrando todas las cuentas: ninguna regla de cobro aplica a esta venta."
                    } else null
                    MerchantSelectionContent(
                        subtotalAmount = currentState.subtotal,
                        totalAmount = currentState.totalAmount,
                        tipAmount = currentState.tipAmount,
                        rating = currentState.rating,
                        merchants = visibleMerchants,
                        currentMerchant = currentMerchant,
                        merchantSwitchingLoading = merchantSwitchingLoading,
                        merchantSwitchMessage = merchantSwitchMessage,
                        onSelectMerchant = { merchant ->
                            // ✅ FIX: Use updateSelectedMerchant for immediate visual selection
                            // (SDK switch happens later in startPayment if needed)
                            viewModel.updateSelectedMerchant(merchant)
                        },
                        onStartPayment = {
                            // ✅ FIX: Pass SUBTOTAL (not totalAmount) - tip is tracked separately in currentTip
                            // Backend receives: amount (subtotal) + tip (currentTip) separately
                            viewModel.startPayment(currentState.subtotal)
                        },
                        onStartPaymentWithMsi = { selectedMsi ->
                            // ✅ FIX: Pass SUBTOTAL (not totalAmount); MSI only affects Blumon TPV auth params.
                            viewModel.startPayment(currentState.subtotal, selectedMsi)
                        },
                        onStartCashPayment = {
                            // ✅ FIX: Pass SUBTOTAL - processCashPayment reads subtotal from state anyway
                            viewModel.processCashPayment(currentState.subtotal)
                        },
                        onNavigateBack = {
                            // Go back to tip selection
                            viewModel.goBackOneStep()
                        },
                        // 🪙 Crypto payment callback (B4Bit integration)
                        onStartCryptoPayment = {
                            viewModel.processCryptoPayment(currentState.totalAmount)
                        },
                        // 🥝 KIOSK MODE: Cash is enabled (customers may want to pay in cash)
                        showCashOption = true,
                        // 🪙 Crypto option: controlled by TpvSettings from dashboard
                        showCryptoOption = tpvSettings?.showCryptoOption ?: false,
                        enableMsiPromotions = true,
                        // 🥝 KIOSK MODE: Hide merchant selector when admin pre-configured a default merchant
                        hideAccountSelector = hideKioskMerchantSelector,
                        // 🧭 MERCHANT_ROUTING_RULES: "showing all accounts" notice when no rule matched
                        routingBannerMessage = routingBanner
                    )
                }

                // 🪙 CRYPTO: Generating QR code (loading state)
                is PaymentState.GeneratingCryptoQR -> {
                    CryptoPaymentLoadingScreen(
                        totalAmount = currentState.totalAmount
                    )
                }

                // 🪙 CRYPTO: Awaiting payment (showing QR code)
                is PaymentState.AwaitingCryptoPayment -> {
                    CryptoPaymentQrScreen(
                        paymentUrl = currentState.paymentUrl,
                        totalAmount = currentState.totalAmount,
                        expiresInSeconds = currentState.expiresInSeconds,
                        onCancel = { viewModel.cancelCryptoPayment() },
                        onTimeout = { viewModel.handleCryptoTimeout() }
                    )
                }

                // LEGACY: Old idle state (redirect to new flow)
                is PaymentState.Idle -> {
                    LaunchedEffect(initialAmount, skipReview, orderId, orderNumber, splitType, isRefundMode, externalTipCents, externalRating, externalSkipReview) {
                        // 💸 REFUND MODE: Process refund instead of payment
                        if (isRefundMode && refundAmount != null && originalPaymentId != null) {
                            Timber.i("💸 [PaymentScreen] Refund mode detected - starting refund for payment: $originalPaymentId")

                            // Create RefundPayment context
                            val refundContext = PaymentContext.RefundPayment(
                                // 🏢 CRITICAL: Use payment's venueId, NOT auth context (fixes 404 bug)
                                // The refund MUST be recorded to the same venue as the original payment
                                venueId = refundVenueId ?: "", // Payment's venue (fallback to empty, will be validated)
                                staffId = "", // Will be populated from authContext in ViewModel
                                shiftId = null, // Will be populated from authContext in ViewModel
                                amount = refundAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                                tip = java.math.BigDecimal.ZERO, // No tip for refunds
                                rating = null,
                                merchantAccountId = refundMerchantAccountId,
                                blumonSerialNumber = refundBlumonSerialNumber ?: "",
                                originalPaymentId = originalPaymentId,
                                originalOrderId = originalOrderId,
                                originalTotalAmount = originalTotalAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                                refundReason = refundReason?.let { RefundReason.fromString(it) } ?: RefundReason.CUSTOMER_REQUEST,
                                isPartialRefund = (refundAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) <
                                    (originalTotalAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO),
                                // 🎫 CRITICAL: Blumon operation number for CancelIcc (from webhook)
                                // If null/0, validation will fail with clear error message
                                originalOperationNumber = originalOperationNumber ?: 0,
                                // Optional tip-split override propagated from RefundConfirmationScreen.
                                tipRefundCents = refundTipCents,
                            )

                            // Start refund flow
                            viewModel.startRefund(refundContext)
                        } else if (initialAmount != null) {
                            // 💳 NORMAL PAYMENT MODE
                            val hasExternalInputs = externalTipCents != null || externalRating != null || externalSkipReview

                            when {
                                hasExternalInputs -> {
                                    // 🔵 External device provided tip/rating or requested skip
                                    viewModel.submitAmountWithExternalInputs(
                                        amount = initialAmount,
                                        tipCents = externalTipCents,
                                        rating = externalRating,
                                        skipReview = externalSkipReview || skipReview,
                                        orderId = orderId,
                                        orderNumber = orderNumber,
                                        splitType = splitType,
                                        equalPartsPartySize = equalPartsPartySize,
                                        equalPartsPayedFor = equalPartsPayedFor,
                                        paidProductIds = paidProductIds,
                                        skipLocalOrderValidation = skipLocalOrderValidation  // 📱 SERIALIZED SALE
                                    )
                                }
                                skipReview -> {
                                    // 🧪 Test payment from SuperAdmin OR 📱 Serialized Sale → skip rating/tip
                                    viewModel.submitAmountDirectToMerchant(
                                        amount = initialAmount,
                                        orderId = orderId,
                                        orderNumber = orderNumber,
                                        splitType = splitType,
                                        equalPartsPartySize = equalPartsPartySize,
                                        equalPartsPayedFor = equalPartsPayedFor,
                                        paidProductIds = paidProductIds,
                                        skipLocalOrderValidation = skipLocalOrderValidation  // 📱 SERIALIZED SALE
                                    )
                                }
                                else -> {
                                    // ✅ Coming from WelcomeScreen/MenuScreen with amount → start payment flow
                                    viewModel.submitAmount(
                                        amount = initialAmount,
                                        orderId = orderId,
                                        orderNumber = orderNumber,
                                        splitType = splitType,
                                        equalPartsPartySize = equalPartsPartySize,
                                        equalPartsPayedFor = equalPartsPayedFor,
                                        paidProductIds = paidProductIds,
                                        skipLocalOrderValidation = skipLocalOrderValidation  // 📱 SERIALIZED SALE
                                    )
                                }
                            }
                        } else {
                            // ✅ NO initialAmount → This flow REQUIRES amount from WelcomeScreen
                            // Navigate back instead of showing EnteringAmount
                            navigateBack()
                        }
                    }

                    // Show loading while processing
                    if (initialAmount != null || isRefundMode) {
                        AvoqadoLoadingOverlay(
                            message = when {
                                isRefundMode -> "Preparando reembolso..."
                                skipReview -> "Preparando pago de prueba..."
                                else -> "Preparando pago..."
                            }
                        )
                    }
                }

                // Payment processing states with progressive timeout warnings
                is PaymentState.ConfiguringKernel -> {
                    // Track elapsed time for this state
                    var elapsedSeconds by remember { mutableIntStateOf(0) }
                    LaunchedEffect(currentState) {
                        elapsedSeconds = 0
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            elapsedSeconds++
                        }
                    }
                    PaymentLoadingContent(
                        message = "Configurando terminal...",
                        showTimeoutWarning = elapsedSeconds >= 30,
                        onCancel = if (elapsedSeconds >= 45) {{ viewModel.cancelPayment() }} else null
                    )
                }
                is PaymentState.DetectingCard -> {
                    // Track elapsed time for this state
                    var elapsedSeconds by remember { mutableIntStateOf(0) }
                    LaunchedEffect(currentState) {
                        elapsedSeconds = 0
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            elapsedSeconds++
                        }
                    }
                    PaymentDetectingCard(
                        amount = currentState.amount,
                        isRefund = isRefundMode,
                        showTimeoutWarning = elapsedSeconds >= 30,
                        onCancel = if (elapsedSeconds >= 30) {{ viewModel.cancelPayment() }} else null
                    )
                }
                is PaymentState.Processing -> {
                    // Track elapsed time for this state
                    var elapsedSeconds by remember { mutableIntStateOf(0) }
                    LaunchedEffect(currentState) {
                        elapsedSeconds = 0
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            elapsedSeconds++
                            if (elapsedSeconds == 45) {
                                viewModel.reportProcessingTimeoutIfNeeded(
                                    message = currentState.message,
                                    elapsedSeconds = elapsedSeconds
                                )
                            }
                        }
                    }
                    PaymentLoadingContent(
                        message = currentState.message,
                        pinState = pinEntryState,  // Show asterisks when user types PIN
                        showPinSection = isPinDialogVisible,  // Keep PIN section visible even when cleared
                        showTimeoutWarning = elapsedSeconds >= 30,
                        onCancel = if (elapsedSeconds >= 45) {{ viewModel.cancelPayment() }} else null,
                        // 🔴 Vigilante de autorizacion (riel Blumon/PAX): banda no bloqueante, NONE
                        // en el camino feliz -> sin cambio visual. Ver AuthorizationWatchdog.kt.
                        watchdogLevel = currentState.watchdogLevel
                    )
                }
                is PaymentState.Success -> {
                    // 🎉 Payment Success Flow:
                    // 1. Show "Aprobado" animation with confetti
                    // 2. After animation: show success content
                    // Works for: fast payments, quick orders, table service
                    //
                    // 📸 NOTE: Verification now happens BEFORE payment (PRE-payment flow)
                    // The POST-payment verification has been removed.

                    // Track if we should show the approved animation
                    // 🚨 Money-safety: skip the confetti when the record was lost — the amber
                    // alarm must be the first thing the cashier sees, not buried behind a
                    // "success" celebration (mirrors AngelPay's Queued-state confetti skip,
                    // escalated here since this case has no local queue fallback at all).
                    var showApprovedAnimation by remember(currentState.authCode) {
                        mutableStateOf(currentState.recordingLostMessage == null && currentState.pendingSyncMessage == null)
                    }
                    // 🔴 Fix round 1 (Important): the check above is NEVER actually true when it
                    // matters — Success publishes the INSTANT the card is approved, seconds
                    // BEFORE backend recording (let alone the queue fallback) is even attempted,
                    // so recordingLostMessage is ALWAYS null at first composition. That made the
                    // "fast-failure edge case" framing wrong: it was the ONLY case, and every
                    // lost-record sale got the identical confetti as a healthy one. React to the
                    // alarm ARRIVING instead of only checking it once at first composition: force
                    // the animation off the moment recordingLostMessage flips non-null, at any
                    // point in the animation (not a remember-key change — authCode never changes
                    // for this screen instance, so re-keying `remember` wouldn't fire either).
                    // 🟡 pendingSyncMessage (queued-for-sync, the far more common case) follows the
                    // SAME reasoning: it also arrives asynchronously after Success first publishes,
                    // and a cashier who needs to read "don't recharge" should get there as fast as
                    // possible — a few seconds of confetti first isn't worth delaying that note.
                    LaunchedEffect(currentState.recordingLostMessage, currentState.pendingSyncMessage) {
                        if (currentState.recordingLostMessage != null || currentState.pendingSyncMessage != null) {
                            showApprovedAnimation = false
                        }
                    }

                    // 🥝 KIOSK: Auto-navigate to KioskSuccessScreen when receipt is ready
                    if (isKioskPayment && onKioskPaymentSuccess != null && currentState.receipt != null) {
                        LaunchedEffect(currentState.receipt) {
                            Timber.i("🥝 [KIOSK] Receipt ready - navigating to KioskSuccessScreen | URL=${currentState.receipt?.receiptUrl}, items=${currentState.orderItems?.size}")
                            val displayOrderNumber = OrderNumberFormatter.display(currentState.orderNumber ?: orderNumber) ?: "0000"
                            viewModel.resetPayment()
                            onKioskPaymentSuccess(displayOrderNumber, currentState.receipt, currentState.orderItems)
                        }
                    }

                    when {
                        // 🎉 Phase 1: Show approved animation
                        showApprovedAnimation -> {
                            PaymentApprovedScreen(
                                amount = currentState.amount,
                                onAnimationComplete = {
                                    if (isKioskPayment && onKioskPaymentSuccess != null) {
                                        // 🥝 KIOSK: Wait for receipt (handled by LaunchedEffect above)
                                        // Just log that animation completed
                                        Timber.d("🥝 [KIOSK] Approved animation complete - waiting for receipt...")
                                    } else {
                                        // Staff: Show PaymentSuccessContent as normal
                                        Timber.d("🎉 [Approved] Animation complete, showing success content")
                                        showApprovedAnimation = false
                                    }
                                },
                                isRefund = currentState.isRefund  // 💸 Show "Reembolso Aprobado" for refunds
                            )
                        }

                        // ✅ Phase 2: Show success content (unified for staff and kiosk)
                        else -> {
                            // 🥝 KIOSK: Determine if this is kiosk mode
                            val showKioskMode = isKioskPayment && onKioskPaymentSuccess != null
                            PaymentSuccessContent(
                                authCode = currentState.authCode,
                                amount = currentState.amount,
                                receipt = currentState.receipt,
                                orderId = currentState.orderId,
                                orderNumber = currentState.orderNumber,
                                orderItems = currentState.orderItems,
                                tableId = tableId,
                                remainingBalance = currentState.remainingBalance,
                                showReceiptOptions = viewModel.showReceiptScreen,
                                showPrintButton = viewModel.canPrintReceipt,
                                recordingLostMessage = currentState.recordingLostMessage,  // 🚨 Money-safety alarm banner
                                pendingSyncMessage = currentState.pendingSyncMessage,  // 🟡 Queued-for-sync note
                                isRefund = currentState.isRefund,  // 💸 Show refund-specific UI
                                wasPayLaterOrder = wasPayLaterOrder,  // 💳 Pay-later context
                                payLaterOrdersCount = payLaterOrdersCount,  // 💳 Remaining count
                                flowOrigin = effectiveOrigin,
                                // 🥝 KIOSK MODE PARAMS
                                isKioskPayment = showKioskMode,
                                kioskCountdownSeconds = 12,
                                onKioskTimeout = {
                                    if (showKioskMode && onKioskPaymentSuccess != null) {
                                        Timber.i("🥝 [KIOSK] Auto-dismiss from unified success screen")
                                        val displayOrderNumber = OrderNumberFormatter.display(currentState.orderNumber ?: orderNumber) ?: "0000"
                                        viewModel.resetPayment()
                                        onKioskPaymentSuccess(displayOrderNumber, currentState.receipt, currentState.orderItems)
                                    }
                                },
                                onPrintReceipt = viewModel::printReceipt,
                                onPrintKitchenTicket = {
                                    currentState.orderItems?.let { items ->
                                        viewModel.printKitchenTicket(
                                            orderNumber = OrderNumberFormatter.display(currentState.orderNumber ?: orderNumber),
                                            tableName = null,
                                            orderItems = items
                                        )
                                    }
                                },
                                onNavigateHome = onNavigateHome,
                                onRefundComplete = onRefundComplete,
                                onNewOrder = {
                                    viewModel.resetPayment()
                                    onNavigateToNewOrder()
                                },
                                onNavigateToSerializedSale = {
                                    viewModel.resetPayment()
                                    onNavigateToSerializedSale()
                                },
                                onNewFastPayment = {
                                    viewModel.resetPayment()
                                    onNavigateToNewFastPayment()
                                },
                                onClearTableAndReturnToFloorPlan = { clearedTableId ->
                                    viewModel.resetPayment()
                                    onClearTableAndReturnToFloorPlan(clearedTableId)
                                },
                                onContinuePayment = {
                                    currentState.orderId?.let { orderIdValue ->
                                        viewModel.resetPayment()
                                        onNavigateToOrder(orderIdValue, tableId)
                                    }
                                },
                                onNavigateToPayLaterOrders = {
                                    viewModel.resetPayment()
                                    onNavigateToPayLaterOrders()
                                },  // 💳 Navigate to pay-later list
                                onSendReceipt = viewModel::sendReceiptByEmail,
                                onSendReceiptWhatsApp = viewModel::sendReceiptByWhatsApp,
                                isSendingReceipt = isSendingReceipt,
                                // 👤 Customer search for email receipt dialog
                                customerSearchState = customerSearchState,
                                recentCustomers = recentCustomers,
                                isLoadingRecentCustomers = isLoadingRecentCustomers,
                                onSearchCustomer = viewModel::searchCustomersForReceipt,
                                onLoadRecentCustomers = viewModel::loadRecentCustomersForReceipt,
                                onResetCustomerSearch = viewModel::resetCustomerSearch,
                                // 📸 PROOF-OF-SALE: Show camera FAB when SERIALIZED_INVENTORY is active and we have paymentId
                                showProofOfSaleButton = showProofOfSale && currentState.receipt?.paymentId != null,
                                isPortabilidad = isPortabilidadState,
                                proofOfSaleComplete = proofOfSaleComplete,
                                onProofOfSalePhotoTaken = { photoPath, photoLabel ->
                                    // Handle proof-of-sale photo upload
                                    val paymentId = currentState.receipt?.paymentId
                                    val totalAmount = currentState.receipt?.totalAmount
                                    // Use orderNumber if available, otherwise use paymentId as identifier
                                    val orderRef = OrderNumberFormatter.reference(currentState.orderNumber ?: orderNumber)
                                        ?: OrderNumberFormatter.reference(paymentId)
                                        ?: System.currentTimeMillis().toString().takeLast(8)

                                    if (paymentId != null && totalAmount != null) {
                                        Timber.d("📸 [PROOF-OF-SALE] Uploading $photoLabel photo for payment $paymentId (ref: $orderRef)")
                                        viewModel.uploadProofOfSale(photoPath, paymentId, orderRef, totalAmount.toString(), photoLabel)
                                    } else {
                                        Timber.e("📸 [PROOF-OF-SALE] Missing required data for upload")
                                        Timber.e("📸 [PROOF-OF-SALE] Missing: paymentId=${paymentId==null}, totalAmount=${totalAmount==null}")
                                    }
                                },
                                isUploadingProofOfSale = isUploadingProofOfSale,
                                onRetakeProofOfSalePhoto = viewModel::retakeProofOfSalePhoto,
                                isPrinting = isPrinting  // 🖨️ Show "Imprimiendo..." on button
                            )
                        }
                    }
                }
                is PaymentState.Error -> {
                    PaymentErrorContent(
                        message = currentState.message,
                        canRetry = currentState.canRetry,
                        showOpenShiftButton = currentState.showOpenShiftButton,  // 🆕 Show "Abrir Turno" button
                        showCashFallback = currentState.showCashFallback,
                        isRefund = isRefundMode,  // 💸 Show "Error en el Reembolso" for refunds
                        onRetry = {
                            // 🔄 Smart Retry: Restore context if available, otherwise reset
                            if (currentState.context != null) {
                                viewModel.retryPayment(currentState.context)
                            } else {
                                viewModel.resetPayment()
                            }
                        },
                        onOpenShift = {
                            // 🆕 Navigate to Shifts screen to open a shift
                            viewModel.resetPayment()
                            onNavigateToShifts()
                        },
                        onCancel = {
                            if (!isRefundMode && currentState.canRetry && currentState.context != null && !currentState.showOpenShiftButton) {
                                viewModel.returnToSelectingMerchantFromError(currentState.context)
                            } else {
                                viewModel.resetPayment()
                                navigateBack()
                            }
                        },
                        onCashFallback = {
                            viewModel.processCashPaymentFromError(currentState.context)
                        },
                    )
                }
                // 🖨️ Printing state is no longer used for UI transitions - printing feedback
                // is shown on the Success screen's button (changes to "Imprimiendo...").
                // This case is kept for sealed class exhaustiveness but should not be reached.
                is PaymentState.Printing -> {
                    // No separate UI - printing happens in background while on Success screen
                }
                // 🆕 NEW: Print error state (show error dialog, can retry or dismiss)
                is PaymentState.PrintError -> {
                    PaymentErrorContent(
                        message = currentState.message,
                        canRetry = true,
                        onRetry = viewModel::printReceipt,  // Retry printing
                        onCancel = viewModel::dismissPrintError  // Return to success screen
                    )
                }

                // 📸 Step 4: Sale Verification (for retail/telecomunicaciones venues)
                is PaymentState.Verifying -> {
                    val context = LocalContext.current
                    val verificationDir = remember {
                        File(context.cacheDir, "verification_photos").apply { mkdirs() }
                    }

                    // State for showing camera/scanner overlays
                    var showCamera by remember { mutableStateOf(false) }
                    var showScanner by remember { mutableStateOf(false) }

                    if (showCamera) {
                        CameraPreviewScreen(
                            onPhotoCaptured = { photoPath ->
                                viewModel.addVerificationPhoto(photoPath)
                                showCamera = false
                            },
                            onClose = { showCamera = false },
                            outputDirectory = verificationDir
                        )
                    } else if (showScanner) {
                        BarcodeScannerScreen(
                            onBarcodeScanned = { barcode, format ->
                                viewModel.addScannedBarcode(barcode, format)
                                showScanner = false
                            },
                            onClose = { showScanner = false }
                        )
                    } else {
                        // Get settings from ViewModel (collected from SecureStorage)
                        val tpvSettings by viewModel.tpvSettings.collectAsStateWithLifecycle()

                        VerificationScreen(
                            paymentId = currentState.paymentId,
                            amount = currentState.amount,
                            orderNumber = currentState.orderNumber,
                            capturedPhotos = currentState.capturedPhotos,
                            scannedBarcodes = currentState.scannedBarcodes,
                            requirePhoto = tpvSettings?.requireVerificationPhoto ?: false,
                            requireBarcode = tpvSettings?.requireVerificationBarcode ?: false,
                            isUploading = currentState.isUploading,
                            uploadProgress = currentState.uploadProgress,
                            error = currentState.error,
                            onTakePhoto = { showCamera = true },
                            onScanBarcode = { showScanner = true },
                            onRemovePhoto = { index ->
                                viewModel.removeVerificationPhoto(index)
                            },
                            onRemoveBarcode = { barcode ->
                                viewModel.removeScannedBarcode(barcode)
                            },
                            onConfirm = {
                                viewModel.confirmVerification()
                            },
                            onSkip = {
                                viewModel.skipVerification()
                            }
                        )
                    }
                }

                // 🥝 KIOSK CASH: Awaiting staff confirmation
                is PaymentState.AwaitingCashConfirmation -> {
                    KioskCashConfirmationContent(
                        totalAmount = currentState.totalAmount,
                        tipAmount = currentState.tipAmount,
                        orderNumber = currentState.orderNumber,
                        printerWarning = currentState.printerWarning,  // 🖨️ Show printer warning if any
                        onConfirm = { staffId ->
                            Timber.i("🥝 [KIOSK CASH] Staff $staffId confirmed payment")
                            viewModel.confirmCashPayment(staffId)
                        },
                        onCancel = {
                            Timber.i("🥝 [KIOSK CASH] Payment cancelled by staff")
                            viewModel.cancelCashPayment()
                        }
                    )
                }

                is PaymentState.Cancelled -> {
                    // Auto-navigate back
                    LaunchedEffect(Unit) {
                        viewModel.resetPayment()
                        navigateBack()
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentIdleContent(
    merchants: List<com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount>,
    currentMerchant: com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount?,
    merchantSwitchingLoading: Boolean,
    merchantSwitchMessage: String?,
    onSelectMerchant: (com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount) -> Unit,
    onClearMerchantMessage: () -> Unit,
    onStartPayment: (String) -> Unit
) {
    var amount by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AvoqadoCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ═══════════════════════════════════════════════════════
                    // MERCHANT SELECTION (MVP: Simple 2-button layout)
                    // ═══════════════════════════════════════════════════════
                    Text(
                        text = "Seleccionar Cuenta",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Display current merchant
                    Text(
                        text = "Cuenta activa: ${currentMerchant?.displayName ?: "Default (${com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber})"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2-button layout: Account A | Account B
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        merchants.forEach { merchant ->
                            AvoqadoButton(
                                text = merchant.displayName,
                                onClick = { onSelectMerchant(merchant) },
                                enabled = !merchantSwitchingLoading,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Success/error message
                    merchantSwitchMessage?.let { message ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith("✅")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // ═══════════════════════════════════════════════════════
                    // PAYMENT AMOUNT INPUT
                    // ═══════════════════════════════════════════════════════
                    Text(
                        text = "Pago con Chip EMV",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    AvoqadoTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = "Monto (MXN)",
                        placeholder = "100.00"
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    AvoqadoButton(
                        text = "Iniciar Pago",
                        onClick = {
                            if (amount.isNotBlank()) {
                                onStartPayment(amount)
                            }
                        },
                        enabled = amount.isNotBlank() && !merchantSwitchingLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Loading overlay during merchant switch
        if (merchantSwitchingLoading) {
            AvoqadoLoadingOverlay(
                message = merchantSwitchMessage ?: "Cambiando cuenta..."
            )
        }
    }
}

private data class SuccessRouting(
    val text: String,
    val onClick: () -> Unit
)

private fun resolveBackNavigation(
    flowOrigin: PaymentFlowOrigin,
    orderId: String?,
    tableId: String?,
    onNavigateBack: () -> Unit,
    onNavigateToSerializedSale: () -> Unit,
    onNavigateToOrder: (String, String?) -> Unit
): () -> Unit {
    return when (flowOrigin) {
        PaymentFlowOrigin.SERIALIZED -> onNavigateToSerializedSale
        PaymentFlowOrigin.ORDER -> {
            if (orderId.isNullOrBlank()) {
                onNavigateBack
            } else {
                { onNavigateToOrder(orderId, tableId) }
            }
        }
        else -> onNavigateBack
    }
}

private fun resolveSuccessRouting(
    flowOrigin: PaymentFlowOrigin,
    wasPayLaterOrder: Boolean,
    payLaterOrdersCount: Int,
    tableId: String?,
    onNavigateToPayLaterOrders: () -> Unit,
    onClearTableAndReturnToFloorPlan: (String) -> Unit,
    onNavigateToSerializedSale: () -> Unit,
    onNewOrder: () -> Unit,
    onNewFastPayment: () -> Unit
): SuccessRouting {
    if (wasPayLaterOrder) {
        val text = if (payLaterOrdersCount > 0) "Pagar cuenta ($payLaterOrdersCount)" else "Pagar cuenta"
        return SuccessRouting(text = text, onClick = onNavigateToPayLaterOrders)
    }

    if (tableId != null) {
        return SuccessRouting(text = "Nueva Orden", onClick = { onClearTableAndReturnToFloorPlan(tableId) })
    }

    return when (flowOrigin) {
        PaymentFlowOrigin.SERIALIZED -> SuccessRouting(text = "Nueva Venta", onClick = onNavigateToSerializedSale)
        PaymentFlowOrigin.ORDER -> SuccessRouting(text = "Nueva Orden", onClick = onNewOrder)
        PaymentFlowOrigin.FAST -> SuccessRouting(text = "Nuevo Pago", onClick = onNewFastPayment)
        PaymentFlowOrigin.REFUND -> SuccessRouting(text = "Nuevo Pago", onClick = onNewFastPayment)
        PaymentFlowOrigin.KIOSK -> SuccessRouting(text = "Nuevo Pago", onClick = onNewFastPayment)
    }
}

@Composable
private fun PaymentDetectingCard(
    amount: String,
    isRefund: Boolean = false,
    showTimeoutWarning: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    // 💸 Refund indicator color (amber/orange like Square's yellow arrow)
    val refundColor = MaterialTheme.avoqadoColors.statusWarning

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Contactless payment icon with optional refund badge
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(id = R.drawable.ic_contact_payment),
                    contentDescription = if (isRefund) "Refund card" else "Contactless payment",
                    modifier = Modifier.size(120.dp),
                    colorFilter = ColorFilter.tint(
                        if (isRefund) refundColor else MaterialTheme.colorScheme.onBackground
                    )
                )

                // 💸 Refund arrow icon overlay (bottom-right)
                if (isRefund) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Refund indicator",
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 8.dp, y = 8.dp),
                        tint = refundColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Amount display (large, bold) - show negative for refunds
            Text(
                text = if (isRefund) "-$$amount" else "$$amount",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = if (isRefund) refundColor else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            // Instructions (smaller, below amount)
            Text(
                text = if (isRefund) "Acerca o inserta la tarjeta para reembolso" else "Acerca o inserta la tarjeta",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            // Timeout warning
            if (showTimeoutWarning) {
                Text(
                    text = "Esto está tomando más de lo normal. Verifica tu conexión a internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Cancel button
            if (onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

@Composable
private fun PaymentLoadingContent(
    message: String,
    pinState: String = "",  // Asterisks from SDK ("*", "**", "***", "****")
    showPinSection: Boolean = false,  // True when SDK is waiting for PIN (even if cleared)
    showTimeoutWarning: Boolean = false,
    onCancel: (() -> Unit)? = null,
    // 🔴 Vigilante de autorizacion: NONE por defecto -> el camino feliz no renderiza
    // nada nuevo y queda byte-identico. Ver AuthorizationWatchdog.kt / WatchdogCopy.kt.
    watchdogLevel: AuthWatchdogLevel = AuthWatchdogLevel.NONE
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top  // Changed to Top so PIN is visible above PAX keyboard
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // 🔢 PIN entry feedback ABOVE the card (visible even when PAX keyboard appears)
        // Show section when SDK requests PIN OR when there are asterisks
        if (showPinSection || pinState.isNotEmpty()) {
            Text(
                text = "Ingrese su PIN",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                // Show asterisks or placeholder dots when cleared
                text = if (pinState.isNotEmpty()) pinState else "● ● ● ●",
                style = MaterialTheme.typography.displayMedium,
                color = if (pinState.isNotEmpty())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                letterSpacing = 12.sp
            )
            Spacer(modifier = Modifier.height(32.dp))
        }

        AvoqadoCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 🔢 During PIN entry hide the spinner — customers read the spinning circle as
                // "still loading" and don't know it's their turn to type the PIN (Arantza 2026-06-29).
                // The "Ingrese su PIN" + ● ● ● ● section above the card is the cue instead.
                if (!showPinSection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }

                Text(
                    text = message,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                // 🔴 Vigilante de autorizacion — banda NO bloqueante bajo el indicador.
                // NUNCA cancela la autorizacion (ver AuthorizationWatchdog.kt): solo avisa.
                // Mismo patron de contraste que el banner ambar de Success (statusWarning/
                // statusError al 12% de fondo, texto SIEMPRE onSurface) — un aviso a full-
                // strength sobre fondo claro quedo en ~1.96:1 y hubo que subirlo a 16.17:1
                // para que se leyera en la terminal. Sin colores nuevos.
                val watchdogText = watchdogMessage(watchdogLevel)
                if (watchdogText != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val watchdogColor = if (watchdogLevel == AuthWatchdogLevel.VERY_SLOW) {
                        MaterialTheme.avoqadoColors.statusError
                    } else {
                        MaterialTheme.avoqadoColors.statusWarning
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(watchdogColor.copy(alpha = 0.12f))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = watchdogColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = watchdogText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Timeout warning
                if (showTimeoutWarning) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Esto está tomando más de lo normal. Verifica tu conexión a internet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                // Cancel button
                if (onCancel != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancelar")
                    }
                }
            }
        }
    }
}

/**
 * DashedDivider - Dashed line separator (like receipt paper perforation)
 */
@Composable
private fun DashedDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSuccessContent(
    authCode: String,
    amount: String,
    receipt: com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt? = null,
    orderId: String? = null,  // 🆕 Order ID (for determining if this is an order payment)
    orderNumber: String? = null,  // 🆕 Order number (for display)
    orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>? = null,  // 🆕 Order items (for displaying itemized receipt)
    tableId: String? = null,  // 🆕 Table ID (for clearing table post-payment)
    remainingBalance: java.math.BigDecimal? = null,  // ⭐ NEW: Amount left to pay (for split payments)
    showReceiptOptions: Boolean = true,  // ⚙️ TPV Settings: Show/hide QR code & print button
    showPrintButton: Boolean = true,  // 🖨️ Hide on terminals without built-in printer (e.g., N62)
    // 🚨 MONEY-SAFETY: non-null ONLY when the card charge succeeded but neither the backend NOR
    // the local offline queue could record it (PaymentState.Success.recordingLostMessage).
    // Renders an amber "don't recharge, reconcile via referenceNumber" banner. Null (default)
    // for every normal Success, which stays byte-identical.
    recordingLostMessage: String? = null,
    // 🟡 MONEY-SAFETY: non-null ONLY when the card charge succeeded, backend recording failed,
    // but the LOCAL OFFLINE QUEUE captured it — the common, self-healing sibling of
    // recordingLostMessage (PaymentState.Success.pendingSyncMessage). Renders an amber "queued,
    // syncs on its own, don't recharge" note in place of the QR/instructions, and disables
    // Email/WhatsApp (server-dependent) while leaving Imprimir enabled (local, ESC/POS). Null
    // (default) for every normal Success, which stays byte-identical.
    pendingSyncMessage: String? = null,
    isRefund: Boolean = false,  // 💸 True = show refund-specific UI text
    wasPayLaterOrder: Boolean = false,  // 💳 True = order had customers (pay-later)
    payLaterOrdersCount: Int = 0,  // 💳 Remaining pay-later orders count
    flowOrigin: PaymentFlowOrigin = PaymentFlowOrigin.FAST,  // 🔀 Origin flow for success actions
    // 🥝 KIOSK MODE PARAMS
    isKioskPayment: Boolean = false,  // 🥝 True = kiosk mode with auto-dismiss
    kioskCountdownSeconds: Int = 12,  // 🥝 Seconds before auto-dismiss
    onKioskTimeout: () -> Unit = {},  // 🥝 Callback when kiosk countdown finishes
    onPrintReceipt: () -> Unit = {},
    onPrintKitchenTicket: () -> Unit = {},  // 🆕 Print kitchen ticket (comanda)
    onNavigateHome: () -> Unit,  // 🆕 Navigate to WelcomeScreen (home button)
    onRefundComplete: () -> Unit = onNavigateHome,  // 💸 Navigate after refund success
    onNewOrder: () -> Unit,  // 🆕 Navigate to new order (for order payments)
    onNavigateToSerializedSale: () -> Unit = {},  // 📱 Serialized sale: return to scanner flow
    onNewFastPayment: () -> Unit,  // 🆕 Navigate to new fast payment (for fast payments)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {},  // 🆕 Clear table and return to floor plan
    onContinuePayment: () -> Unit = {},  // ⭐ NEW: Continue paying remaining balance
    onNavigateToPayLaterOrders: () -> Unit = {},  // 💳 Navigate to pay-later orders list
    onSendReceipt: (email: String) -> Unit = {},  // 📧 Send receipt by email
    onSendReceiptWhatsApp: (phone: String) -> Unit = {},  // 💬 Send receipt by WhatsApp
    isSendingReceipt: Boolean = false,  // 📧💬 Loading state for sending receipt
    // 👤 Customer search for email receipt dialog
    customerSearchState: CustomerSearchState = CustomerSearchState.Idle,
    recentCustomers: List<Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onLoadRecentCustomers: () -> Unit = {},
    onResetCustomerSearch: () -> Unit = {},
    // 📸 PROOF-OF-SALE
    showProofOfSaleButton: Boolean = false,  // Show camera FAB for SERIALIZED_INVENTORY
    isPortabilidad: Boolean = false,  // 📱 Controls 1 vs 2 proof-of-sale photos
    proofOfSaleComplete: Boolean = false,  // All required photos uploaded
    onProofOfSalePhotoTaken: (String, String) -> Unit = { _, _ -> },  // Callback (path, photoLabel)
    isUploadingProofOfSale: Boolean = false,  // Show loading during upload
    onRetakeProofOfSalePhoto: (String) -> Unit = {},  // Retake callback (photoLabel)
    // 🖨️ RECEIPT PRINTING
    isPrinting: Boolean = false  // Show "Imprimiendo..." on print button
) {
    val displayOrderNumber = OrderNumberFormatter.display(orderNumber)

    // Parse amounts (prefer receipt data if available) — memoized to avoid repeated BigDecimal parsing
    val totalAmount = remember(receipt, amount) {
        receipt?.totalAmount ?: (amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
    }
    val tipAmount = remember(receipt) { receipt?.tipAmount ?: java.math.BigDecimal.ZERO }
    val subtotalAmount = remember(receipt, totalAmount) { receipt?.baseAmount ?: totalAmount }

    // ⭐ Check if there's remaining balance (split payment scenario)
    val hasRemainingBalance = remainingBalance != null && remainingBalance > java.math.BigDecimal.ZERO

    // State for order details modal
    var showOrderDetailsModal by remember { mutableStateOf(false) }

    // 📧 State for email receipt dialog
    var showEmailDialog by remember { mutableStateOf(false) }
    // 💬 State for WhatsApp receipt dialog
    var showWhatsAppDialog by remember { mutableStateOf(false) }

    // 📸 State for proof-of-sale camera (multi-photo wizard)
    var showProofOfSaleCamera by remember { mutableStateOf(false) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var lineaPhotoPath by remember { mutableStateOf<String?>(null) }
    var portabilidadPhotoPath by remember { mutableStateOf<String?>(null) }
    var currentPhotoLabel by remember { mutableStateOf("linea") }  // which photo camera is capturing
    var viewingPhotoLabel by remember { mutableStateOf<String?>(null) }  // photo being previewed for retake

    // 🥝 KIOSK MODE: Auto-dismiss countdown
    var kioskSecondsRemaining by remember { mutableIntStateOf(kioskCountdownSeconds) }

    if (isKioskPayment) {
        LaunchedEffect(Unit) {
            Timber.i("🥝 [KIOSK-SUCCESS] Starting auto-dismiss countdown ($kioskCountdownSeconds seconds)")
            while (kioskSecondsRemaining > 0) {
                delay(1000)
                kioskSecondsRemaining--
                if (kioskSecondsRemaining <= 3) {
                    Timber.d("🥝 [KIOSK-SUCCESS] Countdown: $kioskSecondsRemaining seconds remaining")
                }
            }
            Timber.i("🥝 [KIOSK-SUCCESS] Auto-dismiss triggered")
            onKioskTimeout()
        }
    }

    androidx.compose.material3.Scaffold(
        floatingActionButton = {
            // 📸 PROOF-OF-SALE FAB removed — replaced by inline camera icon on ticket
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
        // 🥝 KIOSK MODE: Show simplified toolbar with countdown
        if (isKioskPayment) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nueva orden en $kioskSecondsRemaining segundos...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Custom toolbar with individual buttons (matching AvoqadoPOS design)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Home button (left) - Navigate to WelcomeScreen
                    // Hidden in SERIALIZED_INVENTORY mode (staff must use "Nueva Venta" flow)
                    if (flowOrigin != PaymentFlowOrigin.SERIALIZED) {
                        IconButton(
                            onClick = onNavigateHome,
                            modifier = Modifier
                                .size(48.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Home,
                                contentDescription = "Inicio",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Center button - "Listo" (refund), "Continuar pagando" (remaining balance), or "Nueva Orden"/"Nuevo Pago"
                    // ✅ Centered using Box alignment
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            // 💸 Refund: Show "Listo" button that navigates back to payments list
                            isRefund -> {
                                Button(
                                    onClick = onRefundComplete,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(text = "Listo")
                                }
                            }
                            // ⭐ Split payment: Show "Continuar pagando" button with remaining amount
                            hasRemainingBalance -> {
                                Button(
                                    onClick = onContinuePayment,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,  // Highlighted color
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(
                                        text = "Continuar pagando $${String.format(java.util.Locale.US, "%.2f", remainingBalance)}"
                                    )
                                }
                            }
                            // Normal flow: "Pagar cuenta (X)", "Nueva Orden", or "Nuevo Pago"
                            else -> {
                                val successRouting = resolveSuccessRouting(
                                    flowOrigin = flowOrigin,
                                    wasPayLaterOrder = wasPayLaterOrder,
                                    payLaterOrdersCount = payLaterOrdersCount,
                                    tableId = tableId,
                                    onNavigateToPayLaterOrders = onNavigateToPayLaterOrders,
                                    onClearTableAndReturnToFloorPlan = onClearTableAndReturnToFloorPlan,
                                    onNavigateToSerializedSale = onNavigateToSerializedSale,
                                    onNewOrder = onNewOrder,
                                    onNewFastPayment = onNewFastPayment
                                )
                                val buttonText = successRouting.text

                                Timber.d("💳 [PaymentSuccess] Button: $buttonText | wasPayLater=$wasPayLaterOrder | count=$payLaterOrdersCount | orderId=$orderId")

                                Button(
                                    onClick = successRouting.onClick,
                                    enabled = true, // Non-blocking: staff can start new sale immediately, upload photos later
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Text(buttonText)
                                }
                            }
                        }
                    }

                    // Order details sheet button (only if there are order items)
                    // Hidden in SERIALIZED_INVENTORY mode
                    if (!orderItems.isNullOrEmpty() && flowOrigin != PaymentFlowOrigin.SERIALIZED) {
                        IconButton(
                            onClick = { showOrderDetailsModal = true },
                            modifier = Modifier
                                .size(48.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Receipt,
                                contentDescription = "Ver detalles de la orden",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }
            }
        }
        // Receipt visual (weight 1f to push buttons to bottom)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // 🚨 MONEY-SAFETY (Fix round 1): moved to the TOP of the scrollable content — was
            // previously the LAST element inside the ticket, below a 180dp QR box, instruction
            // text, and the totals/breakdown, which fill essentially the whole viewport on a PAX
            // A910S (360×640dp). A cashier would have had to scroll to see it. AngelPaySuccessContent
            // puts its affirmation/alarm before the QR for the same reason.
            //
            // The green "Cobro aprobado" line only renders when alarmed/queued — a normal Success
            // gets its celebration from PaymentApprovedScreen's confetti (Phase 1); this is needed
            // ONLY here because that confetti now correctly aborts when recordingLostMessage OR
            // pendingSyncMessage is set (see the LaunchedEffect above the `when` block). With no
            // confetti and no green, the screen would read as pure warning even though the charge
            // succeeded. Mirrors AngelPaySuccessContent.kt:205-232 exactly.
            //
            // 🟡 recordingLostMessage (catastrophic, nothing recovers on its own) and
            // pendingSyncMessage (the common case — a queue row exists, this self-heals) share
            // this ONE block rather than two near-duplicate ones: same amber-note visual language,
            // same position, and — critically — they are set on the two mutually-exclusive
            // branches of the SAME queue-enqueue call (see PaymentState.Success.pendingSyncMessage
            // kdoc), so a live Success only ever carries one of the two. Only the ICON and TEXT
            // differ: Warning for the alarm (something needs a human), Sync for the queued note
            // (this is already working itself out) — same distinction AngelPay's own Queued state
            // draws with its own Icons.Default.Sync, and the same "pending payments" device-alert
            // icon language referenced in PaymentState.Success's kdoc.
            val bannerMessage = recordingLostMessage ?: pendingSyncMessage
            if (bannerMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.avoqadoColors.statusSuccess,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = "Cobro aprobado",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Same visual language as AngelPaySuccessContent's pending-sync note:
                    // statusWarning at 12% background, but the TEXT is onSurface — full-strength
                    // statusWarning as body text on a light background fails WCAG AA contrast
                    // (~1.96:1, see that file's fix-round-1 note); onSurface matches every other
                    // text element in this screen.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.avoqadoColors.statusWarning.copy(alpha = 0.12f))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (recordingLostMessage != null) Icons.Default.Warning else Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.avoqadoColors.statusWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = bannerMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                val isSerializedFlow = flowOrigin == PaymentFlowOrigin.SERIALIZED

                // Receipt background image (ticket paper texture)
                Image(
                    painter = painterResource(R.drawable.ilu_ticket_background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (isSerializedFlow) if (showProofOfSaleButton) 40.dp else 16.dp else 70.dp)
                        .then(if (isSerializedFlow) Modifier.height(if (showProofOfSaleButton) 200.dp else 180.dp) else Modifier),
                    contentDescription = "",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceVariant)
                )

                // QR Code (normal mode) or proof-of-sale photo (serialized mode)
                if (!isSerializedFlow) {
                    // Normal flow: show QR code
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .align(Alignment.TopCenter)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White)
                            .border(
                                width = 10.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(24.dp)
                            )
                    ) {
                        receipt?.receiptUrl?.let { qrUrl ->
                            Image(
                                painter = com.jaac.avoqado_tpv.core.presentation.components.rememberQrBitmapPainter(
                                    content = qrUrl,
                                    size = 140.dp,
                                    padding = 0.dp
                                ),
                                contentDescription = "Código QR del recibo",
                                modifier = Modifier
                                    .size(140.dp)
                                    .align(Alignment.Center)
                            )
                        } ?: run {
                            // 🚨 Fix round 1: an infinitely-animating shimmer here implies "still
                            // loading" — false when recordingLostMessage is set, since no receipt
                            // is coming for this sale (nothing recorded it anywhere). Show nothing
                            // instead of a shimmer that will never resolve; the alarm banner at
                            // the top of this screen already explains why.
                            //
                            // 🟡 pendingSyncMessage is the THIRD case: unlike recordingLostMessage,
                            // a receipt IS coming (once the queue syncs) — but ALSO unlike the
                            // normal in-flight case, the shimmer's "loading any second now" promise
                            // is wrong here (this can take until the next connectivity window). A
                            // hole where the QR was reads as broken, so this fills the SAME 140dp
                            // footprint with an honest, calm placeholder instead of either extreme.
                            when {
                                pendingSyncMessage != null -> {
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = null,
                                            tint = MaterialTheme.avoqadoColors.statusWarning,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Recibo pendiente",
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                recordingLostMessage == null -> {
                                    com.jaac.avoqado_tpv.core.presentation.components.ShimmerBox(
                                        modifier = Modifier
                                            .size(140.dp)
                                            .align(Alignment.Center),
                                        cornerRadius = 12.dp
                                    )
                                }
                            }
                        }
                    }
                } else if (showProofOfSaleButton) {
                    // Serialized flow: proof-of-sale photo at TopCenter (like QR)
                    ProofOfSalePhotoSection(
                        isPortabilidad = isPortabilidad,
                        lineaPhotoPath = lineaPhotoPath,
                        portabilidadPhotoPath = portabilidadPhotoPath,
                        isUploading = isUploadingProofOfSale,
                        isComplete = proofOfSaleComplete,
                        onTapLinea = {
                            if (lineaPhotoPath != null) {
                                viewingPhotoLabel = "linea"
                            } else {
                                currentPhotoLabel = "linea"
                                showProofOfSaleCamera = true
                            }
                        },
                        onTapPortabilidad = {
                            if (portabilidadPhotoPath != null) {
                                viewingPhotoLabel = "portabilidad"
                            } else {
                                currentPhotoLabel = "portabilidad"
                                showProofOfSaleCamera = true
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }

                // Receipt content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    // Instruction text - hidden in SERIALIZED_INVENTORY mode AND when the record
                    // was lost (Fix round 1: there is no QR coming for this sale — the alarm
                    // banner at the top already explains why; this line would contradict it).
                    //
                    // 🟡 pendingSyncMessage gets its OWN replacement line instead of just being
                    // hidden like recordingLostMessage: unlike the lost case, a receipt genuinely
                    // IS coming here, so silence would read as "still loading" with no promise of
                    // when — the amber banner above already carries the full explanation, this is
                    // just the short version that belongs where the QR instructions normally sit.
                    when {
                        isSerializedFlow -> Unit
                        recordingLostMessage != null -> Unit
                        pendingSyncMessage != null -> {
                            Text(
                                text = "El recibo estará disponible en cuanto el pago se sincronice",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        else -> {
                            Text(
                                text = "Escanea el código QR para descargar el recibo y dejar una calificación",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(if (isSerializedFlow) 4.dp else 20.dp))

                    // Dashed divider (normal flow only — serialized has photo above)
                    if (!isSerializedFlow) {
                        DashedDivider()
                    }

                    Spacer(modifier = Modifier.height(if (isSerializedFlow) 4.dp else 24.dp))

                    // Total pagado / Total reembolsado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isRefund) "Total reembolsado" else "Total pagado",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val formattedTotal = remember(totalAmount) {
                            "$${String.format(java.util.Locale.US, "%.2f", totalAmount)}"
                        }
                        Text(
                            text = formattedTotal,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // ⭐ Queda por pagar (only shown when there's remaining balance)
                    if (hasRemainingBalance) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Queda por pagar",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error  // Red to highlight pending
                            )
                            val formattedRemaining = remember(remainingBalance) {
                                "$${String.format(java.util.Locale.US, "%.2f", remainingBalance)}"
                            }
                            Text(
                                text = formattedRemaining,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))  // Reduced from 16dp to 12dp

                    // Solid divider
                    HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))  // Reduced from 16dp to 12dp

                    // Breakdown: Monto
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Monto",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val formattedSubtotal = remember(subtotalAmount) {
                            "$${String.format(java.util.Locale.US, "%.2f", subtotalAmount)}"
                        }
                        Text(
                            text = formattedSubtotal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Breakdown: Propina
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Propina",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val formattedTip = remember(tipAmount) {
                            "$${String.format(java.util.Locale.US, "%.2f", tipAmount)}"
                        }
                        Text(
                            text = formattedTip,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // ⚙️ TPV Settings: Only show receipt options if showReceiptOptions is enabled
        if (showReceiptOptions) {
            // Segmented button group for receipt options (Print optional by device)
            val buttonShape = RoundedCornerShape(12.dp)
            val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            // 🟡 Imprimir is local (ESC/POS straight to the printer via printerManager — already
            // handles receipt == null with a generic receipt, see PaymentViewModel.printReceipt)
            // so it stays enabled. Email/WhatsApp both go through the server
            // (paymentApiService.sendReceipt / sendReceiptWhatsApp) AND both short-circuit in the
            // ViewModel when receipt == null ("No hay recibo disponible") — which is exactly this
            // state, since the record hasn't synced yet. Disable them here instead of letting the
            // cashier tap into a dialog that will always fail once submitted.
            val receiptActionsAwaitingSync = pendingSyncMessage != null
            val disabledActionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(buttonShape)
                    .border(
                        width = 1.dp,
                        color = dividerColor,
                        shape = buttonShape
                    )
            ) {
                if (showPrintButton) {
                    // 🖨️ Print receipt button (left)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = !isPrinting) { onPrintReceipt() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isPrinting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = "Imprimir",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPrinting) "..." else "Imprimir",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(dividerColor)
                    )
                }

                // 📧 Email button (center)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = !receiptActionsAwaitingSync) { showEmailDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Email",
                            tint = if (receiptActionsAwaitingSync) disabledActionColor else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Email",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (receiptActionsAwaitingSync) disabledActionColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(dividerColor)
                )

                // 💬 WhatsApp button (right)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = !receiptActionsAwaitingSync) { showWhatsAppDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "WA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (receiptActionsAwaitingSync) disabledActionColor else Color(0xFF25D366),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "WhatsApp",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (receiptActionsAwaitingSync) disabledActionColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 🟡 "brief reason rather than silence" — a greyed-out button alone can just look
            // broken; state why, right where the cashier is looking, instead of only after they
            // tap into a dialog that would fail on submit.
            if (receiptActionsAwaitingSync) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Email y WhatsApp estarán disponibles cuando el pago se sincronice",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
        }  // Close Scaffold Column
    }  // Close Scaffold

    // 📸 Proof-of-sale camera dialog (full screen)
    if (showProofOfSaleCamera) {
        val context = LocalContext.current

        // Create output directory for photos
        val outputDirectory = remember {
            val dir = File(context.cacheDir, "proof_of_sale_photos")
            dir.mkdirs()
            dir
        }

        // Map label to display text: "linea" → "1. Vinculación", "portabilidad" → "2. Portabilidad"
        val cameraLabel = when (currentPhotoLabel) {
            "linea" -> "1. Vinculación"
            "portabilidad" -> "2. Portabilidad"
            else -> null
        }

        com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen(
            onPhotoCaptured = { photoPath ->
                Timber.d("📸 [PROOF-OF-SALE] Photo captured: $photoPath")
                showProofOfSaleCamera = false
                capturedPhotoPath = photoPath  // Store path for preview
            },
            onClose = {
                showProofOfSaleCamera = false
            },
            outputDirectory = outputDirectory,
            photoLabel = cameraLabel
        )
    }

    // 📸 Photo preview dialog with confirm/retake buttons (multi-photo wizard)
    capturedPhotoPath?.let { photoPath ->
        ProofOfSalePhotoPreviewDialog(
            photoPath = photoPath,
            onConfirm = {
                Timber.d("📸 [PROOF-OF-SALE] Photo confirmed ($currentPhotoLabel), starting upload")
                // Store path in the correct slot
                if (currentPhotoLabel == "linea") {
                    lineaPhotoPath = photoPath
                } else {
                    portabilidadPhotoPath = photoPath
                }
                onProofOfSalePhotoTaken(photoPath, currentPhotoLabel)
                capturedPhotoPath = null

                // Wizard auto-advance: if portabilidad and other photo still needed, open camera for next
                if (isPortabilidad) {
                    when {
                        currentPhotoLabel == "linea" && portabilidadPhotoPath == null -> {
                            currentPhotoLabel = "portabilidad"
                            showProofOfSaleCamera = true
                        }
                        currentPhotoLabel == "portabilidad" && lineaPhotoPath == null -> {
                            currentPhotoLabel = "linea"
                            showProofOfSaleCamera = true
                        }
                    }
                }
            },
            onRetake = {
                Timber.d("📸 [PROOF-OF-SALE] Retaking $currentPhotoLabel photo")
                capturedPhotoPath = null
                showProofOfSaleCamera = true
            },
            onDismiss = {
                Timber.d("📸 [PROOF-OF-SALE] Photo preview dismissed")
                capturedPhotoPath = null
            }
        )
    }

    // 📸 View existing photo with retake option
    viewingPhotoLabel?.let { label ->
        val viewingPath = if (label == "linea") lineaPhotoPath else portabilidadPhotoPath
        viewingPath?.let { path ->
            ProofOfSalePhotoPreviewDialog(
                photoPath = path,
                onConfirm = {
                    // Just close the preview — photo is already confirmed
                    viewingPhotoLabel = null
                },
                onRetake = {
                    Timber.d("📸 [PROOF-OF-SALE] Retaking $label photo from preview")
                    viewingPhotoLabel = null
                    onRetakeProofOfSalePhoto(label)
                    // Reset local path
                    if (label == "linea") lineaPhotoPath = null else portabilidadPhotoPath = null
                    currentPhotoLabel = label
                    showProofOfSaleCamera = true
                },
                onDismiss = {
                    viewingPhotoLabel = null
                },
                confirmText = "Cerrar",
                retakeText = "Retomar"
            )
        }
    }

    // 📸 Show loading overlay during proof-of-sale upload
    if (isUploadingProofOfSale) {
        com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay(
            message = "Subiendo foto de comprobante..."
        )
    }

    // Order details modal (bottom sheet)
    if (showOrderDetailsModal && !orderItems.isNullOrEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showOrderDetailsModal = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Text(
                    text = if (!displayOrderNumber.isNullOrBlank()) "Orden #$displayOrderNumber" else "Detalles de la Orden",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Items list
                orderItems.forEach { item ->
                    // Calculate base product price (without modifiers)
                    val modifiersUnitPrice = item.modifiers.sumOf { it.priceAdjustment }
                    val baseUnitPrice = item.unitPrice - modifiersUnitPrice
                    val baseTotalPrice = baseUnitPrice * item.quantity.toBigDecimal()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        // Product name + quantity + base price
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            Text(
                                text = "${item.quantity}x ${item.productName}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = String.format(java.util.Locale.US, "$%.2f", baseTotalPrice),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Modifiers (if any) - with individual prices
                        if (item.modifiers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(
                                modifier = Modifier.padding(start = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                item.modifiers.forEach { modifier ->
                                    val modifierTotalPrice = modifier.priceAdjustment * item.quantity.toBigDecimal()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "  • ${modifier.name}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Text(
                                            text = String.format(java.util.Locale.US, "+$%.2f", modifierTotalPrice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Notes (if any)
                        if (!item.notes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Print kitchen ticket button (comanda para cocina)
                Button(
                    onClick = {
                        showOrderDetailsModal = false
                        onPrintKitchenTicket()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_contact_payment),
                        contentDescription = "Imprimir",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Imprimir Comanda")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Close button
                AvoqadoButton(
                    text = "Cerrar",
                    onClick = { showOrderDetailsModal = false },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 📧 Email receipt dialog
    if (showEmailDialog) {
        EmailReceiptDialog(
            onDismiss = { showEmailDialog = false },
            onSend = { email ->
                onSendReceipt(email)
                showEmailDialog = false
            },
            isLoading = isSendingReceipt,
            showCustomerSearch = flowOrigin != PaymentFlowOrigin.SERIALIZED,
            // 👤 Customer search parameters
            customerSearchState = customerSearchState,
            recentCustomers = recentCustomers,
            isLoadingRecentCustomers = isLoadingRecentCustomers,
            onSearchCustomer = onSearchCustomer,
            onLoadRecentCustomers = onLoadRecentCustomers,
            onResetSearch = onResetCustomerSearch
        )
    }

    // 💬 WhatsApp receipt dialog
    if (showWhatsAppDialog) {
        WhatsAppReceiptDialog(
            onDismiss = { showWhatsAppDialog = false },
            onSend = { phone ->
                onSendReceiptWhatsApp(phone)
                showWhatsAppDialog = false
            },
            isLoading = isSendingReceipt,
            showCustomerSearch = flowOrigin != PaymentFlowOrigin.SERIALIZED,
            customerSearchState = customerSearchState,
            recentCustomers = recentCustomers,
            isLoadingRecentCustomers = isLoadingRecentCustomers,
            onSearchCustomer = onSearchCustomer,
            onLoadRecentCustomers = onLoadRecentCustomers,
            onResetSearch = onResetCustomerSearch,
        )
    }
}

@Composable
private fun PaymentErrorContent(
    message: String,
    canRetry: Boolean,
    showOpenShiftButton: Boolean = false,  // 🆕 Show "Abrir Turno" instead of "Reintentar"
    showCashFallback: Boolean = false,
    isRefund: Boolean = false,  // 💸 Show "Error en el Reembolso" instead of "Error en el Pago"
    onRetry: () -> Unit,
    onOpenShift: () -> Unit = {},  // 🆕 Navigate to Shifts screen
    onCashFallback: () -> Unit = {},
    onCancel: () -> Unit
) {
    // 🩹 Center the card when it fits, but SCROLL when it doesn't. On the short
    // PAX A910S (~640dp) a long error message + the two action buttons — worse
    // still with the global "Sin conexión" banner stealing top space — used to
    // overflow a centered, non-scrollable Column and clip the bottom button(s)
    // ("Reintentar"/"Cancelar") off-screen. heightIn(min = maxHeight) keeps the
    // vertical centering for short errors while allowing scroll for tall ones.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val minContentHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minContentHeight)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AvoqadoCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "❌",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isRefund) "Error en el Reembolso" else "Error en el Pago",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ⭐ SHIFT VALIDATION: Show "Abrir Turno" button when no shift is open
                        if (showOpenShiftButton) {
                            AvoqadoButton(
                                text = "Abrir Turno",
                                onClick = onOpenShift,
                                fullWidth = true
                            )
                        } else {
                            if (showCashFallback) {
                                AvoqadoButton(
                                    text = "Cobrar en Efectivo",
                                    onClick = onCashFallback,
                                    fullWidth = true
                                )
                            }

                            if (canRetry) {
                                AvoqadoButton(
                                    text = "Reintentar",
                                    onClick = onRetry,
                                    fullWidth = true
                                )
                            }
                        }

                        AvoqadoButton(
                            text = "Cancelar",
                            onClick = onCancel,
                            fullWidth = true
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EMAIL RECEIPT DIALOG
// Following keyboard handling pattern from docs/COMPOSE_KEYBOARD_HANDLING.md
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Dialog for entering customer email to send receipt
 *
 * **Features:**
 * - Customer search with debounce (300ms)
 * - Recent customers list on open
 * - Auto-fill email when selecting customer with email
 * - Manual email entry fallback
 *
 * **Keyboard Handling Pattern:**
 * 1. FocusManager captured INSIDE Dialog context
 * 2. imePadding() on Card container
 * 3. verticalScroll for scrollable content
 * 4. pointerInput + detectTapGestures for tap-outside-to-dismiss
 * 5. ImeAction.Done + KeyboardActions to clear focus on Done
 * 6. clearFocus() on button click before action
 *
 * @param onDismiss Callback when dialog is dismissed
 * @param onSend Callback with email when user clicks "Enviar"
 * @param isLoading True when sending is in progress (disables button)
 * @param customerSearchState State of customer search (Idle, Loading, Success, Error)
 * @param recentCustomers List of recent customers to show on dialog open
 * @param isLoadingRecentCustomers Loading state for recent customers
 * @param onSearchCustomer Callback to search customers
 * @param onLoadRecentCustomers Callback to load recent customers
 * @param onResetSearch Callback to reset search state
 */
@Composable
private fun EmailReceiptDialog(
    onDismiss: () -> Unit,
    onSend: (email: String) -> Unit,
    isLoading: Boolean = false,
    showCustomerSearch: Boolean = true,
    // 👤 Customer search parameters
    customerSearchState: CustomerSearchState = CustomerSearchState.Idle,
    recentCustomers: List<Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onLoadRecentCustomers: () -> Unit = {},
    onResetSearch: () -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    val emailFocusRequester = remember { FocusRequester() }

    // Load recent customers when dialog opens
    LaunchedEffect(Unit) {
        if (showCustomerSearch) onLoadRecentCustomers()
    }

    // Debounce customer search (300ms)
    LaunchedEffect(searchQuery) {
        if (showCustomerSearch && searchQuery.length >= 2) {
            delay(300)
            onSearchCustomer(searchQuery)
        } else if (searchQuery.isEmpty()) {
            onResetSearch()
        }
    }

    // Cleanup on dismiss
    DisposableEffect(Unit) {
        onDispose {
            onResetSearch()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ✅ STEP 2: Capture FocusManager INSIDE Dialog
        val focusManager = LocalFocusManager.current

        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .imePadding(),  // ✅ STEP 3: IME padding
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())  // ✅ STEP 4: Scrollable
                    .padding(24.dp)
                    .pointerInput(Unit) {  // ✅ STEP 5: Tap outside to dismiss keyboard
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )

                // Title
                Text(
                    text = "Enviar recibo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // 👤 Customer search section (hidden for SERIALIZED_INVENTORY)
                if (showCustomerSearch) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar cliente") },
                    placeholder = { Text("Nombre, teléfono o correo") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (customerSearchState is CustomerSearchState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus() }
                    ),
                    singleLine = true,
                    enabled = !isLoading
                )

                // 👤 Customer list (search results or recent customers)
                val customersToShow = when {
                    searchQuery.length >= 2 -> {
                        when (val state = customerSearchState) {
                            is CustomerSearchState.Success -> state.customers
                            else -> emptyList()
                        }
                    }
                    else -> recentCustomers
                }

                val showLoading = isLoadingRecentCustomers || customerSearchState is CustomerSearchState.Loading

                if (customersToShow.isNotEmpty()) {
                    // Section header
                    Text(
                        text = if (searchQuery.length >= 2) "Resultados" else "Clientes recientes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )

                    // Customer list
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        customersToShow.take(5).forEach { customer ->
                            CustomerListItem(
                                customer = customer,
                                onClick = {
                                    // Auto-fill email if customer has one
                                    customer.email?.let { customerEmail ->
                                        email = customerEmail
                                    }
                                    searchQuery = ""
                                    onResetSearch()
                                    focusManager.clearFocus()
                                }
                            )
                        }
                    }
                } else if (showLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(vertical = 8.dp),
                        strokeWidth = 2.dp
                    )
                } else if (searchQuery.length >= 2 && customerSearchState is CustomerSearchState.Success) {
                    // No results found
                    Text(
                        text = "No se encontraron clientes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                } // end showCustomerSearch

                // Divider between customer list and manual email
                if (showCustomerSearch) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                Text(
                    text = if (showCustomerSearch) "o escribe el correo manualmente" else "Escribe el correo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Email TextField (manual entry)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(emailFocusRequester),
                    label = { Text("Correo electrónico") },
                    placeholder = { Text("ejemplo@correo.com") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done  // ✅ STEP 6: Done button
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()  // ✅ Clear focus on Done
                        }
                    ),
                    singleLine = true,
                    enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    // Cancel button
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading
                    ) {
                        Text("Cancelar")
                    }

                    // Send button
                    Button(
                        onClick = {
                            focusManager.clearFocus()  // ✅ STEP 7: Clear on button click
                            onSend(email.trim())
                        },
                        enabled = email.isValidEmail() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Enviando..." else "Enviar")
                    }
                }
            }
        }
    }
}

/**
 * Customer list item for email receipt dialog
 * Shows customer name, email (if available) or phone, and email indicator icon
 */
@Composable
private fun CustomerListItem(
    customer: Customer,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with initials
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.shortName.take(2).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Name and contact info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = customer.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Show email if available, otherwise phone
                customer.email?.let { emailAddress ->
                    Text(
                        text = emailAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } ?: customer.phone?.let { phone ->
                    Text(
                        text = phone,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Email indicator icon (only if customer has email)
            if (customer.email != null) {
                Icon(
                    imageVector = Icons.Default.Email,
                    contentDescription = "Tiene email",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Email validation extension function
 * Uses Android's built-in EMAIL_ADDRESS pattern matcher
 */
private fun String.isValidEmail(): Boolean {
    return this.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(this).matches()
}

// ═══════════════════════════════════════════════════════════════════════════
// PREVIEWS (ALWAYS use AvoqadoTheme for correct dark theme + colors)
// ═══════════════════════════════════════════════════════════════════════════

@androidx.compose.ui.tooling.preview.Preview(name = "Detecting Card - Dark Theme", showBackground = true)
@Composable
private fun PaymentDetectingCardPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentDetectingCard(amount = "79.66")
    }
}

// ============================================================
// PREVIEW SECTION
// ============================================================

@androidx.compose.ui.tooling.preview.Preview(name = "Loading - Dark Theme", showBackground = true)
@Composable
private fun PaymentLoadingContentPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentLoadingContent(message = "Configurando terminal...")
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Loading - PIN entry (no spinner)", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun PaymentLoadingContentPinPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentLoadingContent(
            message = "Procesando pago...",
            pinState = "**",
            showPinSection = true
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Success - No Receipt", showBackground = true)
@Composable
private fun PaymentSuccessContentPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentSuccessContent(
            authCode = "123456",
            amount = "500.00",
            receipt = null,  // No receipt - button won't show
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Success - With Receipt & QR", showBackground = true)
@Composable
private fun PaymentSuccessWithReceiptPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentSuccessContent(
            authCode = "123456",
            amount = "500.00",
            receipt = com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt(
                paymentId = "pay_abc123",
                receiptUrl = "https://api.avoqado.io/api/v1/public/receipt/cmhti3qev000f9keochwkvz5e",
                accessKey = "cmhti3qev000f9keochwkvz5e",
                amount = java.math.BigDecimal("500.00"),
                tipAmount = java.math.BigDecimal("50.00")
            ),
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

// 🔴 Fix round 1 (device spec): a bare showBackground=true preview at desktop proportions
// would never have shown the banner sitting below the fold under a QR shimmer that never
// resolves — the finding that drove moving the banner to the top. Use the real PAX A910S
// spec (720×1280px @320dpi) that FastPaymentEntryScreen.kt and AngelPaySuccessContent.kt
// already preview against, not an arbitrary desktop-shaped canvas.
private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

// 🚨 MONEY-SAFETY: card charged but neither the backend NOR the local offline queue could
// record it — amber banner, NOT the red ErrorContent (the charge did not fail). Dark theme
// (AvoqadoTheme default).
@androidx.compose.ui.tooling.preview.Preview(name = "Success - Recording Lost (PAX A910S)", device = PAX_A910S, showSystemUi = true)
@Composable
private fun PaymentSuccessRecordingLostPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentSuccessContent(
            authCode = "123456",
            amount = "500.00",
            receipt = null,  // Never recorded — no receipt, no QR
            // Reference is embedded in the message text below — PaymentSuccessContent has no
            // standalone referenceNumber field (unlike AngelPaySuccessContent).
            recordingLostMessage = "El cobro con tarjeta SÍ se realizó, pero Avoqado no pudo registrarlo " +
                "(ni en el servidor ni en la cola local de este equipo). NO vuelvas a cobrar. Avisa al " +
                "supervisor con la referencia 195978383755 para reconciliar el pago manualmente.",
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

// Light-theme twin — the contrast fix (onSurface text, not statusWarning) is only checkable in
// the Android Studio preview pane against a light background; AvoqadoTheme{} defaults to dark.
// Same rationale as AngelPaySuccessPendingSyncLightPreview.
@androidx.compose.ui.tooling.preview.Preview(name = "Success - Recording Lost (PAX A910S, light)", device = PAX_A910S, showSystemUi = true)
@Composable
private fun PaymentSuccessRecordingLostLightPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme(darkTheme = false) {
        PaymentSuccessContent(
            authCode = "123456",
            amount = "500.00",
            receipt = null,
            recordingLostMessage = "El cobro con tarjeta SÍ se realizó, pero Avoqado no pudo registrarlo " +
                "(ni en el servidor ni en la cola local de este equipo). NO vuelvas a cobrar. Avisa al " +
                "supervisor con la referencia 195978383755 para reconciliar el pago manualmente.",
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

// 🟡 Followup (2026-07-27): card charged, backend record FAILED but the LOCAL OFFLINE QUEUE
// captured it — the common, self-healing sibling of the recording-lost case above. Amber, NOT
// red; the QR placeholder is replaced (not just removed) with a calm "Recibo pendiente" note;
// Email/WhatsApp are visibly disabled with a stated reason; Imprimir stays enabled. Dark theme
// (AvoqadoTheme default).
@androidx.compose.ui.tooling.preview.Preview(name = "Success - Pending Sync (PAX A910S)", device = PAX_A910S, showSystemUi = true)
@Composable
private fun PaymentSuccessPendingSyncPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentSuccessContent(
            authCode = "F628CL",
            amount = "25.00",
            receipt = null,  // Not recorded yet — queued, will sync automatically
            pendingSyncMessage = "El cobro con tarjeta se realizó correctamente. Avoqado no pudo registrarlo " +
                "de inmediato, pero quedó en cola en este equipo y se completará solo en cuanto haya " +
                "conexión — no necesitas hacer nada. NO vuelvas a cobrar. Referencia: 873257481453",
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

// Light-theme twin — same reasoning as PaymentSuccessRecordingLostLightPreview: the disabled
// Email/WhatsApp greyed state and the onSurface banner text both need checking against a light
// background, which AvoqadoTheme{} (dark by default) can't surface.
@androidx.compose.ui.tooling.preview.Preview(name = "Success - Pending Sync (PAX A910S, light)", device = PAX_A910S, showSystemUi = true)
@Composable
private fun PaymentSuccessPendingSyncLightPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme(darkTheme = false) {
        PaymentSuccessContent(
            authCode = "F628CL",
            amount = "25.00",
            receipt = null,
            pendingSyncMessage = "El cobro con tarjeta se realizó correctamente. Avoqado no pudo registrarlo " +
                "de inmediato, pero quedó en cola en este equipo y se completará solo en cuanto haya " +
                "conexión — no necesitas hacer nada. NO vuelvas a cobrar. Referencia: 873257481453",
            onPrintReceipt = {},
            onNavigateHome = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}

/**
 * 📸 Proof-of-sale photo section with 1 or 2 photo placeholders.
 *
 * - Non-portabilidad: Single full-width placeholder ("Registro de línea")
 * - Portabilidad: Two side-by-side placeholders ("Registro de línea" + "Registro de portabilidad")
 *
 * Each placeholder shows a dashed border when empty, or a thumbnail when photo is taken.
 * Tapping opens the camera for that specific photo.
 */
@Composable
private fun ProofOfSalePhotoSection(
    isPortabilidad: Boolean,
    lineaPhotoPath: String?,
    portabilidadPhotoPath: String?,
    isUploading: Boolean,
    isComplete: Boolean,
    onTapLinea: () -> Unit,
    onTapPortabilidad: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showWarning = !isComplete
    if (!isPortabilidad) {
        // Single photo: full-width placeholder with inline warning
        ProofOfSalePlaceholder(
            label = "Vinculacion",
            photoPath = lineaPhotoPath,
            showWarning = showWarning && lineaPhotoPath == null,
            onClick = onTapLinea,
            modifier = modifier.size(width = 160.dp, height = 110.dp)
        )
    } else {
        // Two photos side by side
        Row(
            modifier = modifier.width(220.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProofOfSalePlaceholder(
                label = "Vinculacion",
                photoPath = lineaPhotoPath,
                showWarning = showWarning && lineaPhotoPath == null,
                onClick = onTapLinea,
                modifier = Modifier.weight(1f).height(96.dp)
            )
            ProofOfSalePlaceholder(
                label = "Portabilidad",
                photoPath = portabilidadPhotoPath,
                showWarning = showWarning && portabilidadPhotoPath == null,
                onClick = onTapPortabilidad,
                modifier = Modifier.weight(1f).height(96.dp)
            )
        }
    }
}

/**
 * Single proof-of-sale photo placeholder.
 * Dashed border when empty with camera icon + label. Red border when warning.
 * Thumbnail when photo taken.
 */
@Composable
private fun ProofOfSalePlaceholder(
    label: String,
    photoPath: String?,
    showWarning: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val errorColor = MaterialTheme.colorScheme.error
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val dashedBorderColor = if (showWarning) errorColor else mutedColor
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .then(
                if (photoPath == null) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = dashedBorderColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx()),
                                    0f
                                )
                            ),
                            cornerRadius = CornerRadius(16.dp.toPx())
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (photoPath != null) {
            coil.compose.AsyncImage(
                model = java.io.File(photoPath),
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = if (showWarning) errorColor.copy(alpha = 0.8f) else mutedColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (showWarning) errorColor.copy(alpha = 0.9f) else mutedColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (showWarning) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Toma foto",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp
                        ),
                        color = errorColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Photo preview dialog for proof-of-sale confirmation.
 *
 * Shows captured photo with Confirm/Retake buttons before uploading.
 *
 * @param photoPath Local file path of captured photo
 * @param onConfirm Callback when user confirms photo (triggers upload)
 * @param onRetake Callback when user wants to retake photo (opens camera again)
 * @param onDismiss Callback when user dismisses dialog (cancels)
 */
@Composable
private fun ProofOfSalePhotoPreviewDialog(
    photoPath: String,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Confirmar",
    retakeText: String = "Retomar"
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top bar with close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vista Previa",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }
                }

                // Photo preview (centered)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model = java.io.File(photoPath),
                        contentDescription = "Preview de foto",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Retake button
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(retakeText)
                    }

                    // Confirm button
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
