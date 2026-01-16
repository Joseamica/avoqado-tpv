package com.jaac.avoqado_tpv.features.payment.presentation

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState
import com.jaac.avoqado_tpv.features.payment.presentation.components.KioskCashConfirmationContent
import com.jaac.avoqado_tpv.features.payment.presentation.components.PaymentApprovedScreen
import com.jaac.avoqado_tpv.features.verification.presentation.VerificationScreen
import com.jaac.avoqado_tpv.features.verification.presentation.components.BarcodeScannerScreen
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraPreviewScreen
import java.io.File
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
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
    skipLocalOrderValidation: Boolean = false,  // 📱 SERIALIZED SALE: Order exists only on backend, skip local lookup AND sync
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
    // 💳 PAY-LATER CONTEXT PARAMS
    wasPayLaterOrder: Boolean = false,  // 💳 True = order had customers (for contextual button)
    payLaterOrdersCount: Int = 0,  // 💳 Remaining pay-later orders count
    // 🥝 KIOSK MODE PARAMS
    isKioskPayment: Boolean = false,  // 🥝 True = payment from kiosk self-service flow
    kioskStaffId: String? = null,  // 🥝 Staff ID from kiosk session for sales attribution (commissions/tips). If null, uses authContext staffId.
    onKioskPaymentSuccess: ((String, com.jaac.avoqado_tpv.features.payment.domain.model.PaymentReceipt?, List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>?) -> Unit)? = null,  // 🥝 Callback with orderNumber + receipt + orderItems when kiosk payment succeeds
    onNavigateBack: () -> Unit,
    onNavigateToShifts: () -> Unit = {},  // 🆕 Navigate to Shifts screen (for "No shift open" errors)
    onNavigateToNewOrder: () -> Unit = {},  // 🆕 Navigate to new order (Toast/Square pattern)
    onNavigateToNewFastPayment: () -> Unit = {},  // 🆕 Navigate to new fast payment (open WelcomeScreen modal)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {},  // 🆕 Clear table and return to floor plan (tableId)
    onNavigateToOrder: (String, String?) -> Unit = { _, _ -> },  // ⭐ NEW: Navigate to order for split payment (orderId, tableId)
    onNavigateToPayLaterOrders: () -> Unit = {},  // 💳 Navigate to pay-later orders list
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    val merchantSwitchingLoading by viewModel.merchantSwitchingLoading.collectAsStateWithLifecycle()
    val merchantSwitchMessage by viewModel.merchantSwitchMessage.collectAsStateWithLifecycle()
    val hideKioskMerchantSelector by viewModel.hideKioskMerchantSelector.collectAsStateWithLifecycle()  // 🥝 Hide merchant list in kiosk mode
    val tpvSettings by viewModel.tpvSettings.collectAsStateWithLifecycle()
    val pinEntryState by viewModel.pinEntryState.collectAsStateWithLifecycle()  // PIN asterisks feedback
    val isPinDialogVisible by viewModel.isPinDialogVisible.collectAsStateWithLifecycle()  // PIN dialog visibility
    val isSendingReceipt by viewModel.isSendingReceipt.collectAsStateWithLifecycle()  // 📧 Send receipt loading
    val sendReceiptMessage by viewModel.sendReceiptMessage.collectAsStateWithLifecycle()  // 📧 Send receipt result
    // 👤 Customer search states (for email receipt dialog)
    val customerSearchState by viewModel.customerSearchState.collectAsStateWithLifecycle()
    val recentCustomers by viewModel.recentCustomers.collectAsStateWithLifecycle()
    val isLoadingRecentCustomers by viewModel.isLoadingRecentCustomers.collectAsStateWithLifecycle()

    // 📧 Show toast when send receipt message changes
    val context = LocalContext.current
    LaunchedEffect(sendReceiptMessage) {
        sendReceiptMessage?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearSendReceiptMessage()
        }
    }

    // 🥝 KIOSK MODE: Set kiosk payment mode in ViewModel for secure cash flow
    // Pass kioskStaffId for sales attribution (commissions/tips)
    LaunchedEffect(isKioskPayment, kioskStaffId) {
        viewModel.setKioskPaymentMode(isKioskPayment, kioskStaffId)
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
                            onNavigateBack()
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
                        onNavigateBack()
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
                                onNavigateBack()
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
                        onTipPercentageSelected = { percentage ->
                            // ⭐ NEW: Use combined function to avoid state race condition
                            // This ensures correct tip value when auto-advancing
                            viewModel.selectTipPercentageAndProceed(currentState.amount, currentState.rating, percentage)
                        },
                        onCustomTipSelected = { customTip ->
                            // ⭐ NEW: Use combined function to avoid state race condition
                            viewModel.selectCustomTipAndProceed(currentState.amount, currentState.rating, customTip)
                        },
                        onContinue = {
                            // This is now only called by "Sin propina" button (kept for backward compatibility)
                            viewModel.submitTip(currentState.amount, currentState.tipAmount, currentState.rating)
                        },
                        onSkipTip = {
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
                    MerchantSelectionContent(
                        totalAmount = currentState.totalAmount,
                        tipAmount = currentState.tipAmount,
                        rating = currentState.rating,
                        merchants = merchants,
                        currentMerchant = currentMerchant,
                        merchantSwitchingLoading = merchantSwitchingLoading,
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
                        onStartCashPayment = {
                            // ✅ FIX: Pass SUBTOTAL - processCashPayment reads subtotal from state anyway
                            viewModel.processCashPayment(currentState.subtotal)
                        },
                        onNavigateBack = {
                            // Go back to tip selection
                            viewModel.goBackOneStep()
                        },
                        // 🥝 KIOSK MODE: Cash is enabled (customers may want to pay in cash)
                        showCashOption = true,
                        // 🥝 KIOSK MODE: Hide merchant selector when admin pre-configured a default merchant
                        hideAccountSelector = hideKioskMerchantSelector
                    )
                }

                // LEGACY: Old idle state (redirect to new flow)
                is PaymentState.Idle -> {
                    LaunchedEffect(initialAmount, skipReview, orderId, orderNumber, splitType, isRefundMode) {
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
                                originalOperationNumber = originalOperationNumber ?: 0
                            )

                            // Start refund flow
                            viewModel.startRefund(refundContext)
                        } else if (initialAmount != null) {
                            // 💳 NORMAL PAYMENT MODE
                            if (skipReview) {
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
                            } else {
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
                        } else {
                            // ✅ NO initialAmount → This flow REQUIRES amount from WelcomeScreen
                            // Navigate back instead of showing EnteringAmount
                            onNavigateBack()
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

                // Payment processing states (EXISTING - No changes)
                is PaymentState.ConfiguringKernel -> {
                    PaymentLoadingContent("Configurando terminal...")
                }
                is PaymentState.DetectingCard -> {
                    PaymentDetectingCard(
                        amount = currentState.amount,
                        isRefund = isRefundMode
                    )
                }
                is PaymentState.Processing -> {
                    PaymentLoadingContent(
                        message = currentState.message,
                        pinState = pinEntryState,  // Show asterisks when user types PIN
                        showPinSection = isPinDialogVisible  // Keep PIN section visible even when cleared
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
                    var showApprovedAnimation by remember(currentState.authCode) {
                        mutableStateOf(true)
                    }

                    // 🥝 KIOSK: Auto-navigate to KioskSuccessScreen when receipt is ready
                    if (isKioskPayment && onKioskPaymentSuccess != null && currentState.receipt != null) {
                        LaunchedEffect(currentState.receipt) {
                            Timber.i("🥝 [KIOSK] Receipt ready - navigating to KioskSuccessScreen | URL=${currentState.receipt?.receiptUrl}, items=${currentState.orderItems?.size}")
                            val displayOrderNumber = currentState.orderNumber ?: orderNumber ?: "0000"
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
                                isRefund = currentState.isRefund,  // 💸 Show refund-specific UI
                                wasPayLaterOrder = wasPayLaterOrder,  // 💳 Pay-later context
                                payLaterOrdersCount = payLaterOrdersCount,  // 💳 Remaining count
                                // 🥝 KIOSK MODE PARAMS
                                isKioskPayment = showKioskMode,
                                kioskCountdownSeconds = 12,
                                onKioskTimeout = {
                                    if (showKioskMode && onKioskPaymentSuccess != null) {
                                        Timber.i("🥝 [KIOSK] Auto-dismiss from unified success screen")
                                        val displayOrderNumber = currentState.orderNumber ?: orderNumber ?: "0000"
                                        viewModel.resetPayment()
                                        onKioskPaymentSuccess(displayOrderNumber, currentState.receipt, currentState.orderItems)
                                    }
                                },
                                onPrintReceipt = viewModel::printReceipt,
                                onPrintKitchenTicket = {
                                    currentState.orderItems?.let { items ->
                                        viewModel.printKitchenTicket(
                                            orderNumber = currentState.orderNumber,
                                            tableName = null,
                                            orderItems = items
                                        )
                                    }
                                },
                                onNavigateBack = onNavigateBack,
                                onNewOrder = {
                                    viewModel.resetPayment()
                                    onNavigateToNewOrder()
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
                                isSendingReceipt = isSendingReceipt,
                                // 👤 Customer search for email receipt dialog
                                customerSearchState = customerSearchState,
                                recentCustomers = recentCustomers,
                                isLoadingRecentCustomers = isLoadingRecentCustomers,
                                onSearchCustomer = viewModel::searchCustomersForReceipt,
                                onLoadRecentCustomers = viewModel::loadRecentCustomersForReceipt,
                                onResetCustomerSearch = viewModel::resetCustomerSearch
                            )
                        }
                    }
                }
                is PaymentState.Error -> {
                    PaymentErrorContent(
                        message = currentState.message,
                        canRetry = currentState.canRetry,
                        showOpenShiftButton = currentState.showOpenShiftButton,  // 🆕 Show "Abrir Turno" button
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
                            viewModel.resetPayment()
                            onNavigateBack()
                        }
                    )
                }
                // 🆕 NEW: Printing state (show loading indicator)
                is PaymentState.Printing -> {
                    PaymentLoadingContent("Imprimiendo recibo...")
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
                        onNavigateBack()
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

@Composable
private fun PaymentDetectingCard(
    amount: String,
    isRefund: Boolean = false
) {
    // 💸 Refund indicator color (amber/orange like Square's yellow arrow)
    val refundColor = Color(0xFFFFA726) // Orange/Amber

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
        }
    }
}

@Composable
private fun PaymentLoadingContent(
    message: String,
    pinState: String = "",  // Asterisks from SDK ("*", "**", "***", "****")
    showPinSection: Boolean = false  // True when SDK is waiting for PIN (even if cleared)
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
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
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
    isRefund: Boolean = false,  // 💸 True = show refund-specific UI text
    wasPayLaterOrder: Boolean = false,  // 💳 True = order had customers (pay-later)
    payLaterOrdersCount: Int = 0,  // 💳 Remaining pay-later orders count
    // 🥝 KIOSK MODE PARAMS
    isKioskPayment: Boolean = false,  // 🥝 True = kiosk mode with auto-dismiss
    kioskCountdownSeconds: Int = 12,  // 🥝 Seconds before auto-dismiss
    onKioskTimeout: () -> Unit = {},  // 🥝 Callback when kiosk countdown finishes
    onPrintReceipt: () -> Unit = {},
    onPrintKitchenTicket: () -> Unit = {},  // 🆕 Print kitchen ticket (comanda)
    onNavigateBack: () -> Unit,  // 🆕 Navigate to WelcomeScreen (home button)
    onNewOrder: () -> Unit,  // 🆕 Navigate to new order (for order payments)
    onNewFastPayment: () -> Unit,  // 🆕 Navigate to new fast payment (for fast payments)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {},  // 🆕 Clear table and return to floor plan
    onContinuePayment: () -> Unit = {},  // ⭐ NEW: Continue paying remaining balance
    onNavigateToPayLaterOrders: () -> Unit = {},  // 💳 Navigate to pay-later orders list
    onSendReceipt: (email: String) -> Unit = {},  // 📧 Send receipt by email
    isSendingReceipt: Boolean = false,  // 📧 Loading state for sending receipt
    // 👤 Customer search for email receipt dialog
    customerSearchState: CustomerSearchState = CustomerSearchState.Idle,
    recentCustomers: List<Customer> = emptyList(),
    isLoadingRecentCustomers: Boolean = false,
    onSearchCustomer: (String) -> Unit = {},
    onLoadRecentCustomers: () -> Unit = {},
    onResetCustomerSearch: () -> Unit = {}
) {
    // Parse amounts (prefer receipt data if available)
    val totalAmount = receipt?.totalAmount ?: (amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)  // ✅ FIX: Use totalAmount (includes tip)
    val tipAmount = receipt?.tipAmount ?: java.math.BigDecimal.ZERO
    val subtotalAmount = receipt?.baseAmount ?: totalAmount

    // ⭐ Check if there's remaining balance (split payment scenario)
    val hasRemainingBalance = remainingBalance != null && remainingBalance > java.math.BigDecimal.ZERO

    // State for order details modal
    var showOrderDetailsModal by remember { mutableStateOf(false) }

    // 📧 State for email receipt dialog
    var showEmailDialog by remember { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    IconButton(
                        onClick = onNavigateBack,  // ✅ Navigate to WelcomeScreen
                        modifier = Modifier
                            .size(48.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,  // ✅ Home icon
                            contentDescription = "Inicio",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Center button - "Listo" (refund), "Continuar pagando" (remaining balance), or "Nueva Orden"/"Nuevo Pago"
                    // ✅ Centered using Box alignment
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            // 💸 Refund: Show "Listo" button that navigates back
                            isRefund -> {
                                Button(
                                    onClick = onNavigateBack,
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
                                // 💳 Determine button text and action
                                val buttonText = when {
                                    wasPayLaterOrder && payLaterOrdersCount > 0 -> "Pagar cuenta ($payLaterOrdersCount)"
                                    wasPayLaterOrder -> "Pagar cuenta"  // Show even without count
                                    orderId != null -> "Nueva Orden"
                                    else -> "Nuevo Pago"
                                }

                                Timber.d("💳 [PaymentSuccess] Button: $buttonText | wasPayLater=$wasPayLaterOrder | count=$payLaterOrdersCount | orderId=$orderId")

                                Button(
                                    onClick = {
                                        val currentTableId = tableId
                                        when {
                                            // 💳 Pay-later: Navigate to pay-later orders list
                                            wasPayLaterOrder -> onNavigateToPayLaterOrders()
                                            // 🪑 Table order → Clear table and return to floor plan
                                            currentTableId != null -> onClearTableAndReturnToFloorPlan(currentTableId)
                                            // 📋 Quick order → Create new quick order
                                            orderId != null -> onNewOrder()
                                            // ⚡ Fast payment → New fast payment
                                            else -> onNewFastPayment()
                                        }
                                    },
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
                    // ✅ Keeps right side balanced with left home button
                    if (!orderItems.isNullOrEmpty()) {
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
                        // Spacer to balance layout when no receipt button
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                // Receipt background image (ticket paper texture)
                Image(
                    painter = painterResource(R.drawable.ilu_ticket_background),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 70.dp), // Reduced from 90dp - less space for QR
                    contentDescription = "",
                    contentScale = ContentScale.FillBounds,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surface)
                )

                // QR Code (centered on top) - ALWAYS shown
                // ✅ UX: Shimmer loader while waiting for backend receipt (1-2s delay on card payments)
                // Note: showReceiptOptions only controls the print button below, NOT the QR code
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
                        // Receipt arrived from backend → Show QR code
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
                        // Receipt pending (backend response in flight) → Show shimmer
                        com.jaac.avoqado_tpv.core.presentation.components.ShimmerBox(
                            modifier = Modifier
                                .size(140.dp)
                                .align(Alignment.Center),
                            cornerRadius = 12.dp
                        )
                    }
                }

                // Receipt content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    // Instruction text - QR is always shown
                    Text(
                        text = "Escanea el código QR para descargar el recibo y dejar una calificación",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 📦 Order items REMOVED from success screen - shown only in printed receipt
                    // Reason: With 10+ products, UI becomes cluttered and messy
                    // QR code section should stay clean and focused
                    // Product details are fully visible on the printed receipt

                    // Dashed divider
                    DashedDivider()

                    Spacer(modifier = Modifier.height(24.dp))

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
                        Text(
                            text = "$${String.format(java.util.Locale.US, "%.2f", totalAmount)}",
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
                            Text(
                                text = "$${String.format(java.util.Locale.US, "%.2f", remainingBalance)}",
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
                        Text(
                            text = "$${String.format(java.util.Locale.US, "%.2f", subtotalAmount)}",
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
                        Text(
                            text = "$${String.format(java.util.Locale.US, "%.2f", tipAmount)}",
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
            // Segmented button group for receipt options (side by side)
            val buttonShape = RoundedCornerShape(12.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
                    .clip(buttonShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = buttonShape
                    )
            ) {
                // 🖨️ Print receipt button (left)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onPrintReceipt() },  // Material3 uses theme ripple by default
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = "Imprimir",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Imprimir",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )

                // 📧 Send receipt by email button (right)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showEmailDialog = true },  // Material3 uses theme ripple by default
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Enviar",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Enviar",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
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
                    text = if (!orderNumber.isNullOrBlank()) "Orden #$orderNumber" else "Detalles de la Orden",
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
            // 👤 Customer search parameters
            customerSearchState = customerSearchState,
            recentCustomers = recentCustomers,
            isLoadingRecentCustomers = isLoadingRecentCustomers,
            onSearchCustomer = onSearchCustomer,
            onLoadRecentCustomers = onLoadRecentCustomers,
            onResetSearch = onResetCustomerSearch
        )
    }
}

@Composable
private fun PaymentErrorContent(
    message: String,
    canRetry: Boolean,
    showOpenShiftButton: Boolean = false,  // 🆕 Show "Abrir Turno" instead of "Reintentar"
    isRefund: Boolean = false,  // 💸 Show "Error en el Reembolso" instead of "Error en el Pago"
    onRetry: () -> Unit,
    onOpenShift: () -> Unit = {},  // 🆕 Navigate to Shifts screen
    onCancel: () -> Unit
) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ⭐ SHIFT VALIDATION: Show "Abrir Turno" button when no shift is open
                    if (showOpenShiftButton) {
                        AvoqadoButton(
                            text = "Abrir Turno",
                            onClick = onOpenShift,
                            modifier = Modifier.weight(1f)
                        )
                    } else if (canRetry) {
                        AvoqadoButton(
                            text = "Reintentar",
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    AvoqadoButton(
                        text = "Cancelar",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
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
        onLoadRecentCustomers()
    }

    // Debounce customer search (300ms)
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
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

                // 👤 Customer search section
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

                // Divider between customer list and manual email
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Text(
                    text = "o escribe el correo manualmente",
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

@androidx.compose.ui.tooling.preview.Preview(name = "Success - No Receipt", showBackground = true)
@Composable
private fun PaymentSuccessContentPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentSuccessContent(
            authCode = "123456",
            amount = "500.00",
            receipt = null,  // No receipt - button won't show
            onPrintReceipt = {},
            onNavigateBack = {},
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
            onNavigateBack = {},
            onNewOrder = {},
            onNewFastPayment = {}
        )
    }
}
