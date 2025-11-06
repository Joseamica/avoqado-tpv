package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onNavigateBack: () -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val currentMerchant by viewModel.currentMerchant.collectAsStateWithLifecycle()
    val merchantSwitchingLoading by viewModel.merchantSwitchingLoading.collectAsStateWithLifecycle()
    val merchantSwitchMessage by viewModel.merchantSwitchMessage.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Pago con Tarjeta",
                onNavigationClick = onNavigateBack
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = state) {
                is PaymentState.Idle -> {
                    PaymentIdleContent(
                        merchants = merchants,
                        currentMerchant = currentMerchant,
                        merchantSwitchingLoading = merchantSwitchingLoading,
                        merchantSwitchMessage = merchantSwitchMessage,
                        onSelectMerchant = { merchant ->
                            viewModel.selectMerchant(merchant)
                        },
                        onClearMerchantMessage = {
                            viewModel.clearMerchantSwitchMessage()
                        },
                        onStartPayment = { amount ->
                            viewModel.startPayment(amount)
                        }
                    )
                }
                is PaymentState.ConfiguringKernel -> {
                    PaymentLoadingContent("Configurando terminal...")
                }
                is PaymentState.DetectingCard -> {
                    PaymentDetectingCard()
                }
                is PaymentState.Processing -> {
                    PaymentLoadingContent(currentState.message)
                }
                is PaymentState.Success -> {
                    PaymentSuccessContent(
                        authCode = currentState.authCode,
                        amount = currentState.amount,
                        onFinish = {
                            viewModel.resetPayment()
                            onNavigateBack()
                        }
                    )
                }
                is PaymentState.Error -> {
                    PaymentErrorContent(
                        message = currentState.message,
                        canRetry = currentState.canRetry,
                        onRetry = { viewModel.resetPayment() },
                        onCancel = {
                            viewModel.resetPayment()
                            onNavigateBack()
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
private fun PaymentDetectingCard() {
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
                    text = "Acerque la tarjeta al lector",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Coloque la tarjeta sobre el lector\ndel terminal PAX",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
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

@Composable
private fun PaymentSuccessContent(
    authCode: String,
    amount: String,
    onFinish: () -> Unit
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
                    text = "✅",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Pago Aprobado",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Monto: $$amount MXN",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Código de autorización:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = authCode,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // ⭐ Show OFFLINE indicator if auth code starts with "OFFLINE-"
                if (authCode.startsWith("OFFLINE-")) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "⚠️ MODO OFFLINE",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Sin autorización bancaria",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                AvoqadoButton(
                    text = "Finalizar",
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PaymentErrorContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
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
                    if (canRetry) {
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
