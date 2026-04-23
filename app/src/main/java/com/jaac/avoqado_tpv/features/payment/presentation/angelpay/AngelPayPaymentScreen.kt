package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.angelpay.angelpaysdk.AngelPayPaymentContract
import com.angelpay.angelpaysdk.models.PaymentResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors
import com.jaac.avoqado_tpv.core.util.ForegroundRecoveryGate
import com.jaac.avoqado_tpv.features.payment.presentation.MerchantSelectionContent
import com.jaac.avoqado_tpv.features.payment.presentation.ReviewScreen
import com.jaac.avoqado_tpv.features.payment.presentation.TipScreen
import com.jaac.avoqado_tpv.features.payment.presentation.components.PaymentApprovedScreen
import timber.log.Timber

private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

/**
 * AngelPay Payment Screen — full pre-payment UX matching Blumon flow.
 *
 * **COMPLETELY ISOLATED from Blumon PaymentScreen.**
 * Uses its own ViewModel (AngelPayPaymentViewModel) and state machine.
 *
 * **Flow:**
 * Idle → [Rating] → [Tip] → Merchant Selection → Card/Cash → Success
 *
 * Reuses: ReviewScreen, TipScreen, MerchantSelectionContent, PaymentApprovedScreen.
 */
@Composable
fun AngelPayPaymentScreen(
    initialAmount: String?,
    orderId: String? = null,
    orderNumber: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToTransactions: () -> Unit = {},
    onNavigateToShifts: () -> Unit = {},
    viewModel: AngelPayPaymentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    val isSendingReceipt by viewModel.isSendingReceipt.collectAsStateWithLifecycle()
    val sendReceiptMessage by viewModel.sendReceiptMessage.collectAsStateWithLifecycle()

    // Toast for receipt send result
    val context = LocalContext.current
    LaunchedEffect(sendReceiptMessage) {
        sendReceiptMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearSendReceiptMessage()
        }
    }

    // ActivityResultLauncher for AngelPay intent
    val angelPayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("🔶 [AngelPay] ActivityResult received | resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            ForegroundRecoveryGate.arm(
                durationMs = 4500L,
                reason = "angelpay_app_to_app_result_ok",
            )
        }
        viewModel.onAngelPayResult(result.resultCode, result.data)
    }
    val angelPaySdkLauncher = rememberLauncherForActivityResult(
        contract = AngelPayPaymentContract(),
    ) { result: PaymentResult ->
        Timber.d("🔶 [AngelPay SDK] ActivityResult received | approved=${result.approved}")
        if (result.approved) {
            ForegroundRecoveryGate.arm(
                durationMs = 4500L,
                reason = "angelpay_sdk_approved",
            )
        }
        viewModel.onAngelPaySdkResult(result)
    }

    // Auto-start payment when screen opens with amount
    LaunchedEffect(initialAmount) {
        if (initialAmount != null && state is AngelPayPaymentState.Idle) {
            viewModel.initPayment(
                amount = initialAmount,
                orderId = orderId,
                orderNumber = orderNumber,
            )
        }
    }

    // Launch AngelPay when intent is ready
    LaunchedEffect(state) {
        val currentState = state
        when (currentState) {
            is AngelPayPaymentState.LaunchingAngelPaySdk -> {
                try {
                    angelPaySdkLauncher.launch(currentState.request)
                    viewModel.onIntentLaunched()
                } catch (e: Exception) {
                    Timber.e(e, "🔶 [AngelPay SDK] Failed to launch SDK payment activity")
                    viewModel.onAngelPayResult(Activity.RESULT_CANCELED, null)
                }
            }
            is AngelPayPaymentState.LaunchingAngelPay -> {
                try {
                    angelPayLauncher.launch(currentState.intent)
                    viewModel.onIntentLaunched()
                } catch (e: Exception) {
                    Timber.e(e, "🔶 [AngelPay] Failed to launch AngelPay app")
                    viewModel.onAngelPayResult(Activity.RESULT_CANCELED, null)
                }
            }
            else -> Unit
        }
    }

    // Track whether to show success animation or success content
    var showApprovedAnimation by remember { mutableStateOf(false) }
    var showSuccessContent by remember { mutableStateOf(false) }

    // Trigger approved animation when entering Success state
    LaunchedEffect(state) {
        if (state is AngelPayPaymentState.Success && !showSuccessContent) {
            showApprovedAnimation = true
        }
    }

    // ── Full-screen overlays (no scaffold) ───────────────────────────
    when {
        // Approved animation overlay
        showApprovedAnimation && !showSuccessContent -> {
            val successState = state as? AngelPayPaymentState.Success
            val formattedTotal = remember(successState?.amount, successState?.tipAmount) {
                val subtotal = successState?.amount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                val tip = successState?.tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                "$${String.format(java.util.Locale.US, "%.2f", subtotal.add(tip))}"
            }

            PaymentApprovedScreen(
                amount = formattedTotal,
                onAnimationComplete = {
                    showApprovedAnimation = false
                    showSuccessContent = true
                },
            )
            return
        }

        // Success content (after animation)
        showSuccessContent -> {
            val successState = state as? AngelPayPaymentState.Success
            if (successState != null) {
                AngelPaySuccessContent(
                    state = successState,
                    showReceiptScreen = true,
                    showPrintButton = viewModel.canPrintReceipt,
                    onPrintReceipt = { viewModel.printReceipt(successState.receipt) },
                    onSendReceiptEmail = viewModel::sendReceiptByEmail,
                    onSendReceiptWhatsApp = viewModel::sendReceiptByWhatsApp,
                    isSendingReceipt = isSendingReceipt,
                    onNavigateHome = {
                        viewModel.resetPayment()
                        onNavigateHome()
                    },
                    onViewTransactions = onNavigateToTransactions,
                    onStartNewPayment = {
                        viewModel.resetPayment()
                        showSuccessContent = false
                        showApprovedAnimation = false
                        onNavigateBack()
                    },
                )
            }
            return
        }
    }

    // ── Top bar title based on state ─────────────────────────────────
    val topBarTitle = when (state) {
        is AngelPayPaymentState.CollectingRating -> "Calificacion"
        is AngelPayPaymentState.CollectingTip -> "Propina"
        is AngelPayPaymentState.SelectingMerchant -> "Metodo de Pago"
        is AngelPayPaymentState.WaitingForResult,
        is AngelPayPaymentState.LaunchingAngelPay,
        is AngelPayPaymentState.LaunchingAngelPaySdk -> "Procesando"
        is AngelPayPaymentState.RecordingPayment,
        is AngelPayPaymentState.ProcessingCash -> "Registrando"
        is AngelPayPaymentState.Error -> "Error"
        is AngelPayPaymentState.Cancelled -> "Cancelado"
        else -> "Cobro AngelPay"
    }

    // Show top bar for pre-payment states and error/cancelled
    val showTopBar = state !is AngelPayPaymentState.Success

    // ── Scaffold with AvoqadoTopBar (matching Blumon) ────────────────
    Scaffold(
        topBar = {
            if (showTopBar) {
                AvoqadoTopBar(
                    title = topBarTitle,
                    onNavigationClick = {
                        when (state) {
                            is AngelPayPaymentState.CollectingRating,
                            is AngelPayPaymentState.CollectingTip,
                            is AngelPayPaymentState.SelectingMerchant -> {
                                if (!viewModel.goBackOneStep()) {
                                    onNavigateBack()
                                }
                            }
                            is AngelPayPaymentState.Error,
                            is AngelPayPaymentState.Cancelled,
                            is AngelPayPaymentState.Idle -> {
                                viewModel.resetPayment()
                                onNavigateBack()
                            }
                            else -> { /* No back during processing */ }
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            when (val currentState = state) {
                is AngelPayPaymentState.Idle -> {
                    if (initialAmount == null) {
                        Text(
                            text = "No se recibio monto para cobrar",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }

                is AngelPayPaymentState.CollectingRating -> {
                    ReviewScreen(
                        currentReview = currentState.rating,
                        amount = currentState.amount,
                        onReviewChange = { rating ->
                            // Auto-advance on star tap (matching Blumon behavior)
                            viewModel.selectRatingAndProceed(currentState.amount, rating)
                        },
                        onContinue = {
                            viewModel.selectRatingAndProceed(currentState.amount, currentState.rating)
                        },
                        onSkip = {
                            viewModel.skipRating(currentState.amount)
                        },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                    )
                }

                is AngelPayPaymentState.CollectingTip -> {
                    TipScreen(
                        subtotal = currentState.amount,
                        selectedTipPercentage = currentState.selectedTipPercentage,
                        customTipAmount = if (currentState.selectedTipPercentage == null &&
                            currentState.tipAmount != "0") currentState.tipAmount else null,
                        tipSuggestions = viewModel.tipSuggestions,
                        defaultTipPercentage = viewModel.defaultTipPercentage,
                        // Update display only (no advance)
                        onTipSelectionChanged = { percentage ->
                            viewModel.updateTipSelection(percentage)
                        },
                        onCustomTipChanged = { customTip ->
                            viewModel.updateCustomTip(customTip)
                        },
                        // Advance on "Continuar" press
                        onTipPercentageSelected = { percentage ->
                            viewModel.updateTipSelection(percentage)
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = currentState.tipAmount,
                            )
                        },
                        onCustomTipSelected = { customTip ->
                            viewModel.updateCustomTip(customTip)
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = customTip,
                            )
                        },
                        onContinue = {
                            viewModel.selectTipAndProceed(
                                amount = currentState.amount,
                                rating = currentState.rating,
                                tipAmount = currentState.tipAmount,
                            )
                        },
                        onSkipTip = {
                            viewModel.skipTip(currentState.amount, currentState.rating)
                        },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                    )
                }

                is AngelPayPaymentState.SelectingMerchant -> {
                    MerchantSelectionContent(
                        subtotalAmount = currentState.subtotal,
                        totalAmount = currentState.totalAmount,
                        tipAmount = currentState.tipAmount,
                        rating = currentState.rating,
                        merchants = merchants,
                        currentMerchant = currentMerchant,
                        merchantSwitchingLoading = false,
                        onSelectMerchant = { viewModel.selectMerchant(it) },
                        onStartPayment = { viewModel.startCardPayment() },
                        onStartCashPayment = { viewModel.startCashPayment() },
                        onNavigateBack = null, // Back handled by Scaffold top bar
                        showCashOption = true,
                        showCryptoOption = false,
                        hideAccountSelector = merchants.size <= 1,
                    )
                }

                is AngelPayPaymentState.LaunchingAngelPay -> {
                    LoadingContent(message = "Abriendo AngelPay...")
                }
                is AngelPayPaymentState.LaunchingAngelPaySdk -> {
                    LoadingContent(message = "Abriendo AngelPay SDK...")
                }

                is AngelPayPaymentState.WaitingForResult -> {
                    LoadingContent(
                        message = currentState.message,
                        subtitle = "No cierres esta pantalla",
                        largeSpinner = true,
                    )
                }

                is AngelPayPaymentState.ProcessingCash -> {
                    LoadingContent(message = currentState.message)
                }

                is AngelPayPaymentState.RecordingPayment -> {
                    LoadingContent(message = currentState.message)
                }

                is AngelPayPaymentState.Success -> {
                    LoadingContent(message = "Pago exitoso")
                }

                is AngelPayPaymentState.Error -> {
                    ErrorContent(
                        state = currentState,
                        onRetry = {
                            viewModel.resetPayment()
                            if (initialAmount != null) {
                                viewModel.initPayment(
                                    amount = initialAmount,
                                    orderId = orderId,
                                    orderNumber = orderNumber,
                                )
                            }
                        },
                        onGoBack = onNavigateBack,
                        onOpenShift = onNavigateToShifts,
                    )
                }

                is AngelPayPaymentState.Cancelled -> {
                    CancelledContent(onNavigateBack = onNavigateBack)
                }
            }
        }
    }
}

// ── Shared composables ───────────────────────────────────────────────

@Composable
private fun LoadingContent(
    message: String,
    subtitle: String? = null,
    largeSpinner: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = if (largeSpinner) Modifier.size(64.dp) else Modifier,
        )
        Spacer(modifier = Modifier.height(if (largeSpinner) 24.dp else 16.dp))
        Text(
            text = message,
            style = if (largeSpinner) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorContent(
    state: AngelPayPaymentState.Error,
    onRetry: () -> Unit,
    onGoBack: () -> Unit,
    onOpenShift: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.avoqadoColors.statusError,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Error en el pago",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (state.showOpenShiftButton) {
            Button(
                onClick = onOpenShift,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Abrir Turno")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (state.canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reintentar")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = onGoBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Regresar")
        }
    }
}

@Composable
private fun CancelledContent(onNavigateBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pago cancelado",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Regresar")
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPayErrorPreview() {
    AvoqadoTheme {
        ErrorContent(
            state = AngelPayPaymentState.Error(
                message = "Debes abrir un turno antes de cobrar",
                canRetry = false,
                showOpenShiftButton = true,
            ),
            onRetry = {},
            onGoBack = {},
            onOpenShift = {},
        )
    }
}

@Preview(device = PAX_A910S, showSystemUi = true)
@Composable
private fun AngelPayCancelledPreview() {
    AvoqadoTheme {
        CancelledContent(onNavigateBack = {})
    }
}
