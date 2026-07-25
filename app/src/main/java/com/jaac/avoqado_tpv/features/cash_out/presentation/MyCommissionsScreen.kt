package com.jaac.avoqado_tpv.features.cash_out.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoFullScreenLoading
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.util.CurrencyFormatter
import java.math.BigDecimal

@Composable
fun MyCommissionsScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: MyCommissionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MyCommissionsContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onWithdraw = viewModel::withdraw,
        onRetry = viewModel::loadSaldo,
    )
}

@Composable
private fun MyCommissionsContent(
    uiState: MyCommissionsUiState,
    onNavigateBack: () -> Unit = {},
    onWithdraw: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold(
        topBar = { AvoqadoTopBar(title = "Mis Comisiones", onNavigationClick = onNavigateBack) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isLoading) {
                AvoqadoFullScreenLoading(message = "Cargando comisiones...")
                return@Column
            }

            // ── Saldo card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Saldo disponible",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = CurrencyFormatter.format(uiState.saldo),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Active-day status ──
            StatusRow(activeToday = uiState.activeToday)

            Spacer(Modifier.height(24.dp))

            // ── Retirar ──
            Button(
                onClick = onWithdraw,
                enabled = uiState.canWithdraw,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (uiState.isWithdrawing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text("Retirar", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Success / error banners ──
            uiState.lastWithdrawalFolio?.let { folio ->
                Banner(
                    icon = Icons.Default.CheckCircle,
                    text = "Retiro solicitado. Folio $folio. Se depositará a tu cuenta.",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            uiState.error?.let { err ->
                Banner(
                    icon = Icons.Default.Info,
                    text = err,
                    container = MaterialTheme.colorScheme.errorContainer,
                    onContainer = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
        }
    }
}

@Composable
private fun StatusRow(activeToday: Boolean) {
    val text = if (activeToday) "Hoy puedes retirar tu comisión" else "Hoy no es un día habilitado para retirar"
    val color = if (activeToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text = text, color = color, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, container: androidx.compose.ui.graphics.Color, onContainer: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(28.dp))
            Text(text = text, color = onContainer, textAlign = TextAlign.Center)
        }
    }
}

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun MyCommissionsPreview() {
    AvoqadoTheme {
        MyCommissionsContent(
            uiState = MyCommissionsUiState(isLoading = false, saldo = BigDecimal("30"), activeToday = true),
        )
    }
}
