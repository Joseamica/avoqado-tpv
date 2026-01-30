package com.jaac.avoqado_tpv.features.settings.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.provider.Settings
import com.jaac.avoqado_tpv.core.presentation.components.AvoqadoTopBar
import com.jaac.avoqado_tpv.core.presentation.components.ResponsiveScaffold

/**
 * External Devices Screen
 *
 * Allows linking external devices (iPad, tablets) to send payment amounts via Bluetooth.
 * Follows Square/Toast/Stripe pattern for device management.
 */
@Composable
fun ExternalDevicesScreen(
    onNavigateBack: () -> Unit,
    viewModel: ExternalDevicesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Bluetooth permission launcher (Android 12+)
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            timber.log.Timber.i("✅ [ExternalDevices] Bluetooth permissions granted - starting server")
            viewModel.toggleServer(context)
        } else {
            timber.log.Timber.w("❌ [ExternalDevices] Bluetooth permissions denied")
        }
    }

    // Function to check and request permissions before toggling server
    val toggleServerWithPermissions: () -> Unit = {
        if (state.isServerRunning) {
            // Stopping doesn't need permissions
            viewModel.toggleServer(context)
        } else {
            // Check permissions before starting
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val hasConnect = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasAdvertise = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_ADVERTISE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasConnect && hasAdvertise) {
                    viewModel.toggleServer(context)
                } else {
                    timber.log.Timber.i("📱 [ExternalDevices] Requesting Bluetooth permissions...")
                    bluetoothPermissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                            android.Manifest.permission.BLUETOOTH_ADVERTISE
                        )
                    )
                }
            } else {
                // Android 11 or below - no runtime permissions needed for BLE
                viewModel.toggleServer(context)
            }
        }
    }

    Scaffold(
        topBar = {
            AvoqadoTopBar(
                title = "Dispositivos Externos",
                subtitle = "Enlazar iPad/tablets via BLE",
                onNavigationClick = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        ResponsiveScaffold(
            modifier = Modifier.padding(paddingValues),
            scrollable = false
        ) {
            LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server Status Card
            item {
                ServerStatusCard(
                    isRunning = state.isServerRunning,
                    onToggleServer = toggleServerWithPermissions
                )
            }

            item {
                MultiDevicePolicyCard(
                    allowMultipleDevices = state.allowMultipleDevices,
                    onToggle = { viewModel.setAllowMultipleDevices(it) }
                )
            }

            if (state.showPairingHelp) {
                item {
                    PairingHelpBanner(
                        requiresPin = state.pairingRequiresPin,
                        onOpenBluetooth = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {
                                // No-op: if settings can't open, user can still use notification shade.
                            }
                        },
                        onDismiss = { viewModel.dismissPairingHelp() }
                    )
                }
            }

            // Connected Devices Section
            if (state.isServerRunning) {
                item {
                    Text(
                        text = if (state.connectedDeviceCount > 0) {
                            "Dispositivos Conectados (${state.connectedDeviceCount})"
                        } else {
                            "Dispositivos Conectados"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    ConnectedDevicesCard(devices = state.connectedDevices)
                }

                // Link New Device Button
                item {
                    OutlinedButton(
                        onClick = { viewModel.startLinkingWizard() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enlazar Nuevo Dispositivo")
                    }
                }
            }

            // Known Devices Section (persists across APK updates)
            if (state.knownDevices.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dispositivos Registrados (${state.knownDeviceCount})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.knownDevices.size > 1) {
                            TextButton(onClick = { viewModel.forgetAllDevices() }) {
                                Text("Olvidar todos", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                item {
                    KnownDevicesCard(
                        devices = state.knownDevices,
                        onForgetDevice = { viewModel.forgetDevice(it) },
                        onApproveDevice = { viewModel.approveDevice(it) }
                    )
                }
            }

            // Info Section
            item {
                InfoCard()
            }

            // Message Display
            if (state.message.isNotEmpty()) {
                item {
                    MessageCard(
                        message = state.message,
                        isError = state.isError,
                        onDismiss = { viewModel.clearMessage() }
                    )
                }
            }
        }
        }
    }

    // Wizard Dialog
    if (state.wizardStep != WizardStep.NONE) {
        LinkingWizardDialog(
            wizardStep = state.wizardStep,
            onNext = { viewModel.nextWizardStep() },
            onCancel = { viewModel.cancelWizard() },
            onFinish = { viewModel.cancelWizard() }
        )
    }

}

/**
 * Server Status Card
 */
@Composable
private fun ServerStatusCard(
    isRunning: Boolean,
    onToggleServer: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Estado del Servidor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = if (isRunning) {
                    "🟢 ACTIVO - Esperando conexiones..."
                } else {
                    "⚫ INACTIVO - Activa el servidor para recibir conexiones"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isRunning) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Button(
                onClick = onToggleServer,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isRunning) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRunning) "Detener Servidor" else "Iniciar Servidor")
            }
        }
    }
}

/**
 * Connected Devices Card - Shows all connected devices
 */
@Composable
private fun ConnectedDevicesCard(devices: List<com.jaac.avoqado_tpv.core.bluetooth.ConnectedDevice>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (devices.isNotEmpty()) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (devices.isNotEmpty()) {
                // Show all connected devices
                devices.forEachIndexed { index, device ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TabletAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name ?: "Dispositivo Desconocido",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = if (device.approved) {
                                    "🟢 Autorizado"
                                } else {
                                    "🟠 Pendiente de autorización"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (device.approved) {
                                    Color(0xFF4CAF50)
                                } else {
                                    Color(0xFFFFA000)
                                }
                            )
                        }
                        Icon(
                            imageVector = if (device.approved) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = if (device.approved) "Autorizado" else "Pendiente",
                            tint = if (device.approved) Color(0xFF4CAF50) else Color(0xFFFFA000),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else {
                // No devices connected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Ningún dispositivo conectado",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Los dispositivos aparecerán aquí cuando se conecten vía Bluetooth",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Known Devices Card - Shows devices that have connected before (persists across APK updates)
 */
@Composable
private fun KnownDevicesCard(
    devices: List<com.jaac.avoqado_tpv.core.bluetooth.KnownDevice>,
    onForgetDevice: (com.jaac.avoqado_tpv.core.bluetooth.KnownDevice) -> Unit,
    onApproveDevice: (com.jaac.avoqado_tpv.core.bluetooth.KnownDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            devices.forEachIndexed { index, device ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Device icon with connection indicator
                    Box {
                        Icon(
                            imageVector = Icons.Default.TabletAndroid,
                            contentDescription = null,
                            tint = if (device.isConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        // Connection status dot
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .offset(x = 2.dp, y = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = null,
                                tint = if (device.isConnected) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    // Device info
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name ?: "Dispositivo ${device.address.takeLast(5)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (device.isConnected) {
                                "🟢 Conectado ahora"
                            } else {
                                "⚫ Desconectado • ${formatLastConnected(device.lastConnectedAt)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.isConnected) {
                                Color(0xFF4CAF50)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = if (device.approved) "✅ Autorizado" else "🟠 Pendiente de autorización",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.approved) Color(0xFF4CAF50) else Color(0xFFFFA000)
                        )
                        if (device.connectionCount > 1) {
                            Text(
                                text = "${device.connectionCount} conexiones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!device.approved) {
                            TextButton(onClick = { onApproveDevice(device) }) {
                                Text("Autorizar")
                            }
                        }

                        // Forget button (only for disconnected devices)
                        if (!device.isConnected) {
                            IconButton(
                                onClick = { onForgetDevice(device) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Olvidar dispositivo",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiDevicePolicyCard(
    allowMultipleDevices: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Permitir varios dispositivos",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (allowMultipleDevices) {
                        "Se pueden conectar varios iPads/tablets al mismo tiempo."
                    } else {
                        "Solo 1 dispositivo a la vez."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = allowMultipleDevices,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun PairingHelpBanner(
    requiresPin: Boolean,
    onOpenBluetooth: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Emparejamiento Bluetooth",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cerrar"
                    )
                }
            }

            Text(
                text = "Es normal que el sistema muestre “null”. Ese es el iPad.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Text(
                text = "1) En el iPad ya aparece el PIN. Manténlo a la vista.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (requiresPin) {
                    "2) En el TPV, abre la solicitud de Bluetooth y escribe el PIN."
                } else {
                    "2) En el TPV, confirma el enlace en la solicitud de Bluetooth."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "3) Si no ves el diálogo, desliza desde arriba y toca “Solicitud de vinculación”.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "4) Espera a que aparezca “Conectado”.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "El PIN solo se ingresa en el diálogo del sistema, no en esta pantalla.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenBluetooth,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abrir Bluetooth")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Entendido")
                }
            }
        }
    }
}

/**
 * Format last connected timestamp for display
 */
private fun formatLastConnected(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "hace un momento"
        diff < 3_600_000 -> "hace ${diff / 60_000} min"
        diff < 86_400_000 -> "hace ${diff / 3_600_000} horas"
        diff < 604_800_000 -> "hace ${diff / 86_400_000} días"
        else -> "hace más de una semana"
    }
}

/**
 * Info Card
 */
@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "¿Cómo funciona?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Dispositivos externos (iPad, tablet) pueden enviar montos de pago a esta terminal vía Bluetooth.\n\n" +
                            "1. Activa el servidor BLE\n" +
                            "2. Conecta desde tu dispositivo externo\n" +
                            "3. Envía montos de pago directamente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/**
 * Message Card
 */
@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        }
    }
}

/**
 * Linking Wizard Dialog
 */
@Composable
private fun LinkingWizardDialog(
    wizardStep: WizardStep,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    onFinish: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            if (wizardStep == WizardStep.CONNECTED) {
                Button(onClick = onFinish) {
                    Text("Finalizar")
                }
            } else if (wizardStep == WizardStep.PREPARE_EXTERNAL) {
                Button(onClick = onNext) {
                    Text("Siguiente")
                }
            }
        },
        dismissButton = {
            if (wizardStep != WizardStep.CONNECTED) {
                TextButton(onClick = onCancel) {
                    Text("Cancelar")
                }
            }
        },
        title = {
            Text(
                text = when (wizardStep) {
                    WizardStep.PREPARE_EXTERNAL -> "Enlazar Dispositivo (Paso 1 de 3)"
                    WizardStep.WAITING_CONNECTION -> "Enlazar Dispositivo (Paso 2 de 3)"
                    WizardStep.CONNECTED -> "Enlazar Dispositivo (Paso 3 de 3)"
                    else -> ""
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (wizardStep) {
                    WizardStep.PREPARE_EXTERNAL -> {
                        Icon(
                            imageVector = Icons.Default.TabletAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Preparar Dispositivo Externo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1. Abre la app de pagos en tu iPad o tablet\n\n" +
                                    "2. Asegúrate de que Bluetooth está activado",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    WizardStep.WAITING_CONNECTION -> {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Conectar desde Externo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "En tu iPad/tablet:\n\n" +
                                    "1. Busca dispositivos Bluetooth\n\n" +
                                    "2. Selecciona el terminal PAX\n\n" +
                                    "3. Si aparece código, acéptalo en AMBOS dispositivos",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "🔵 Esperando conexión...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    WizardStep.CONNECTED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Text(
                            text = "¡Dispositivo Conectado!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Ya puedes enviar pagos desde tu dispositivo externo.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {}
                }
            }
        }
    )
}
