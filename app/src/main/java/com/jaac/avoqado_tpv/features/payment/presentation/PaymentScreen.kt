package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.R
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoButton
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoLoadingOverlay
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTextField
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.features.payment.domain.PaymentState

/**
 * PaymentScreen - EMV chip card payment with online authorization via Blumon Momentum
 *
 * Flow: PreTrans → DetectCard → StartEmvTrans → SaleIcc (ONLINE) → CompleteEmvTrans
 */
@Composable
fun PaymentScreen(
    initialAmount: String? = null,
    orderId: String? = null,  // 🆕 Order ID (for order payment with inventory deduction)
    orderNumber: String? = null,  // 🆕 Order number (for display in receipt)
    tableId: String? = null,  // 🆕 Table ID (for clearing table post-payment)
    skipReview: Boolean = false,  // 🧪 Skip rating/tip (test payment from SuperAdmin)
    onNavigateBack: () -> Unit,
    onNavigateToShifts: () -> Unit = {},  // 🆕 Navigate to Shifts screen (for "No shift open" errors)
    onNavigateToNewOrder: () -> Unit = {},  // 🆕 Navigate to new order (Toast/Square pattern)
    onNavigateToNewFastPayment: () -> Unit = {},  // 🆕 Navigate to new fast payment (open WelcomeScreen modal)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {},  // 🆕 Clear table and return to floor plan (tableId)
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    val merchantSwitchingLoading by viewModel.merchantSwitchingLoading.collectAsStateWithLifecycle()
    val merchantSwitchMessage by viewModel.merchantSwitchMessage.collectAsStateWithLifecycle()

    // Dynamic topBar titles based on payment state
    // Note: EnteringAmount removed - amount now comes from WelcomeScreen modal
    val (topBarTitle, topBarSubtitle) = when (val currentState = state) {
        is PaymentState.CollectingRating -> "Calificación" to "Paso 1 de 3 · $${currentState.amount}"
        is PaymentState.CollectingTip -> {
            val tipAmount = currentState.tipAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val subtotal = currentState.amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val total = subtotal.add(tipAmount)

            if (tipAmount > java.math.BigDecimal.ZERO) {
                "Propina" to "Paso 2 de 3 · Total: $$total MXN"
            } else {
                "Propina" to "Paso 2 de 3 · Subtotal: $${currentState.amount} MXN"
            }
        }
        is PaymentState.SelectingMerchant -> "Seleccionar Cuenta" to "Paso 3 de 3 · Total: $${currentState.totalAmount}"
        else -> "Pago con Tarjeta" to null
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
                            viewModel.startPayment(currentState.totalAmount)
                        },
                        onStartCashPayment = {
                            viewModel.processCashPayment(currentState.totalAmount)
                        },
                        onNavigateBack = {
                            // Go back to tip selection
                            viewModel.goBackOneStep()
                        }
                    )
                }

                // LEGACY: Old idle state (redirect to new flow)
                is PaymentState.Idle -> {
                    LaunchedEffect(initialAmount, skipReview, orderId, orderNumber) {
                        if (initialAmount != null) {
                            if (skipReview) {
                                // 🧪 Test payment from SuperAdmin → skip rating/tip, go directly to merchant selection
                                viewModel.submitAmountDirectToMerchant(initialAmount, orderId, orderNumber)
                            } else {
                                // ✅ Coming from WelcomeScreen/MenuScreen with amount → start payment flow
                                viewModel.submitAmount(initialAmount, orderId, orderNumber)
                            }
                        } else {
                            // ✅ NO initialAmount → This flow REQUIRES amount from WelcomeScreen
                            // Navigate back instead of showing EnteringAmount
                            onNavigateBack()
                        }
                    }

                    // Show loading while processing
                    if (initialAmount != null) {
                        AvoqadoLoadingOverlay(
                            message = if (skipReview) "Preparando pago de prueba..." else "Preparando pago..."
                        )
                    }
                }

                // Payment processing states (EXISTING - No changes)
                is PaymentState.ConfiguringKernel -> {
                    PaymentLoadingContent("Configurando terminal...")
                }
                is PaymentState.DetectingCard -> {
                    PaymentDetectingCard(amount = currentState.amount)
                }
                is PaymentState.Processing -> {
                    PaymentLoadingContent(currentState.message)
                }
                is PaymentState.Success -> {
                    PaymentSuccessContent(
                        authCode = currentState.authCode,
                        amount = currentState.amount,
                        receipt = currentState.receipt,  // 🆕 NEW: Pass receipt for QR code
                        orderId = currentState.orderId,  // 🆕 Order ID (for determining if this is an order payment)
                        orderNumber = currentState.orderNumber,  // 🆕 Order number (for display)
                        orderItems = currentState.orderItems,  // 🆕 Order items (for displaying itemized receipt)
                        tableId = tableId,  // 🆕 Table ID (for clearing table post-payment)
                        onPrintReceipt = viewModel::printReceipt,  // 🆕 NEW: Print callback
                        onNavigateBack = onNavigateBack,  // 🆕 Navigate to WelcomeScreen (home button)
                        onNewOrder = {
                            viewModel.resetPayment()
                            onNavigateToNewOrder()  // 🔄 Toast/Square pattern: Navigate FORWARD to new order
                        },
                        onNewFastPayment = {
                            viewModel.resetPayment()
                            onNavigateToNewFastPayment()  // 🔄 Navigate to WelcomeScreen and open fast payment modal
                        },
                        onClearTableAndReturnToFloorPlan = { clearedTableId ->
                            viewModel.resetPayment()
                            onClearTableAndReturnToFloorPlan(clearedTableId)  // 🪑 Square pattern: Clear table and return to floor plan
                        }
                    )
                }
                is PaymentState.Error -> {
                    PaymentErrorContent(
                        message = currentState.message,
                        canRetry = currentState.canRetry,
                        showOpenShiftButton = currentState.showOpenShiftButton,  // 🆕 Show "Abrir Turno" button
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
private fun PaymentDetectingCard(amount: String) {
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
            // Contactless payment icon (custom drawable)
            Image(
                painter = painterResource(id = R.drawable.ic_contact_payment),
                contentDescription = "Contactless payment",
                modifier = Modifier.size(120.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Amount display (large, bold)
            Text(
                text = "$$amount",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            // Instructions (smaller, below amount)
            Text(
                text = "Acerca o inserta la tarjeta",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PaymentLoadingContent(message: String) {
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
    onPrintReceipt: () -> Unit = {},
    onNavigateBack: () -> Unit,  // 🆕 Navigate to WelcomeScreen (home button)
    onNewOrder: () -> Unit,  // 🆕 Navigate to new order (for order payments)
    onNewFastPayment: () -> Unit,  // 🆕 Navigate to new fast payment (for fast payments)
    onClearTableAndReturnToFloorPlan: (String) -> Unit = {}  // 🆕 Clear table and return to floor plan
) {
    // Parse amounts (prefer receipt data if available)
    val totalAmount = receipt?.amount ?: (amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
    val tipAmount = receipt?.tipAmount ?: java.math.BigDecimal.ZERO
    val subtotalAmount = receipt?.baseAmount ?: totalAmount

    // State for order details modal
    var showOrderDetailsModal by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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

                // Center button - "Nueva Orden" or "Nuevo Pago"
                // ✅ Centered using Box alignment
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            val currentTableId = tableId
                            when {
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
                        Text(
                            // ✅ Dynamic text based on payment type
                            if (orderId != null) "Nueva Orden" else "Nuevo Pago"
                        )
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

                // QR Code (centered on top) - Shows shimmer while loading
                // ✅ UX: Shimmer loader while waiting for backend receipt (1-2s delay on card payments)
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
                    // Instruction text
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

                    // Total pagado
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Total pagado",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$${String.format(java.util.Locale.US, "%.2f", totalAmount)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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

                    // Breakdown: Total
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Total",
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

        // Print button (ALWAYS visible - prints generic receipt if backend failed)
        Button(
            onClick = onPrintReceipt,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_contact_payment), // Placeholder - ideally use print icon
                contentDescription = "Imprimir",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "O imprime el recibo",
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.Top
                    ) {
                        // Product name + quantity
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "${item.quantity}x ${item.productName}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            // Notes (if any)
                            if (!item.notes.isNullOrBlank()) {
                                Text(
                                    text = item.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }

                        // Price
                        Text(
                            text = item.formattedTotalPrice,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
}

@Composable
private fun PaymentErrorContent(
    message: String,
    canRetry: Boolean,
    showOpenShiftButton: Boolean = false,  // 🆕 Show "Abrir Turno" instead of "Reintentar"
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
                    text = "Error en el Pago",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
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
// PREVIEWS (ALWAYS use AvoqadoTheme for correct dark theme + colors)
// ═══════════════════════════════════════════════════════════════════════════

@androidx.compose.ui.tooling.preview.Preview(name = "Detecting Card - Dark Theme", showBackground = true)
@Composable
private fun PaymentDetectingCardPreview() {
    com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme {
        PaymentDetectingCard(amount = "79.66")
    }
}

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
