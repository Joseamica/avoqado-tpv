package com.jaac.avoqado_tpv.features.payment.presentation.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import java.util.Locale

@Composable
fun PaymentTransactionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: PaymentTransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var emailDialogTx by remember { mutableStateOf<UnifiedTransaction?>(null) }
    var emailInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Transacciones ${uiState.processorType.name}",
                onNavigationClick = onNavigateBack,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = uiState.startDate,
                onValueChange = viewModel::updateStartDate,
                label = { Text("Inicio (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.endDate,
                onValueChange = viewModel::updateEndDate,
                label = { Text("Fin (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.reference,
                onValueChange = viewModel::updateReference,
                label = { Text("Referencia (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = uiState.terminal,
                onValueChange = viewModel::updateTerminal,
                label = { Text("Terminal (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::refreshHistory,
                    enabled = !uiState.isLoading,
                ) {
                    Text("Buscar")
                }
                OutlinedButton(
                    onClick = viewModel::clearMessage,
                    enabled = !uiState.message.isNullOrBlank(),
                ) {
                    Text("Limpiar")
                }
            }

            uiState.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.transactions, key = { "${it.reference}_${it.creationDate}" }) { tx ->
                    TransactionCard(
                        transaction = tx,
                        onCancel = { viewModel.cancelTransaction(tx) },
                        onRefund = { viewModel.refundTransaction(tx) },
                        onPrint = { viewModel.printTicket(tx) },
                        onGetTicketUrl = { viewModel.getTicketUrl(tx) },
                        onSendEmail = {
                            emailDialogTx = tx
                            emailInput = ""
                        },
                    )
                }
            }
        }
    }

    if (emailDialogTx != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { emailDialogTx = null }) {
            Card(modifier = Modifier.fillMaxWidth(0.92f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Enviar Ticket por Email", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        singleLine = true,
                        label = { Text("Correo") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = { emailDialogTx = null }) {
                            Text("Cancelar")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val tx = emailDialogTx
                                if (tx != null) {
                                    viewModel.sendTicketEmail(tx, emailInput.trim())
                                }
                                emailDialogTx = null
                            },
                            enabled = emailInput.contains("@"),
                        ) {
                            Text("Enviar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionCard(
    transaction: UnifiedTransaction,
    onCancel: () -> Unit,
    onRefund: () -> Unit,
    onPrint: () -> Unit,
    onSendEmail: () -> Unit,
    onGetTicketUrl: () -> Unit,
) {
    val amount = "$" + String.format(Locale.US, "%.2f", transaction.amount)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Ref: ${transaction.reference}", fontWeight = FontWeight.Bold)
            Text("Monto: $amount")
            Text("Status: ${transaction.status}")
            Text("Auth: ${transaction.authorizationCode.ifBlank { "-" }}")
            Text("Fecha: ${transaction.creationDate.ifBlank { "-" }}")
            if (!transaction.postOperationType.isNullOrBlank()) {
                Text(
                    text = "Post-op: ${transaction.postOperationType} (${transaction.postOperationStatus ?: "-"})",
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = transaction.postOperationType.isNullOrBlank(),
                ) { Text("Cancelar") }
                OutlinedButton(
                    onClick = onRefund,
                    enabled = transaction.postOperationType.isNullOrBlank(),
                ) { Text("Devolver") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPrint) { Text("Imprimir") }
                OutlinedButton(onClick = onGetTicketUrl) { Text("URL") }
                OutlinedButton(onClick = onSendEmail) { Text("Email") }
            }
        }
    }
}
