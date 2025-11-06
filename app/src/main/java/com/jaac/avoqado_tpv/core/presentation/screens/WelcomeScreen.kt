package com.jaac.avoqado_tpv.core.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoCard
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Home Screen (Welcome Screen)
 *
 * Main dashboard after successful login.
 * Currently a placeholder while main TPV features are implemented.
 */
@Composable
fun WelcomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToPayment: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.Space6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(Spacing.Space12))

            AvoqadoCard(
                modifier = Modifier.padding(Spacing.Space4)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(Spacing.Space6)
                ) {
                    // Logo placeholder (Restaurant icon hasta tener logo oficial)
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = "Avoqado Logo",
                        modifier = Modifier.size(80.dp), // TODO: Add Size.LogoLarge to Dimensions.kt
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(Spacing.Space6))

                    Text(
                        text = "Avoqado TPV",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(Spacing.Space2))

                    Text(
                        text = "Sistema de Punto de Venta",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(Spacing.Space8))

                    Text(
                        text = "✅ Activación completa\n✅ Login con PIN\n✅ Sistema de heartbeat\n✅ Sesión activa",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.5f
                    )

                    Spacer(modifier = Modifier.height(Spacing.Space8))

                    // Payment Button
                    Button(
                        onClick = onNavigateToPayment,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreditCard,
                            contentDescription = "Realizar pago",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Realizar Pago")
                    }

                    Spacer(modifier = Modifier.height(Spacing.Space4))

                    // Logout Button
                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Cerrar sesión",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Cerrar Sesión")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, device = "spec:width=800px,height=1280px,dpi=160")
@Composable
private fun WelcomeScreenPreview() {
    AvoqadoTheme {
        WelcomeScreen()
    }
}
