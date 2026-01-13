package com.jaac.avoqado_tpv.features.payment.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.authentication.presentation.components.PinDisplay
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat
import java.util.Locale

/**
 * EntryPoint for accessing dependencies in KioskCashConfirmationContent
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KioskCashConfirmationEntryPoint {
    fun authRepository(): com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
    fun secureStorage(): com.jaac.avoqado_tpv.core.data.local.SecureStorage
}

/**
 * Roles authorized to confirm cash payments
 */
private val CASH_CONFIRMATION_AUTHORIZED_ROLES = setOf(
    StaffRole.SUPERADMIN,
    StaffRole.OWNER,
    StaffRole.ADMIN,
    StaffRole.MANAGER,
    StaffRole.CASHIER  // Cashiers can confirm cash payments
)

/**
 * 🥝 Kiosk Cash Confirmation Content
 *
 * Screen shown when customer selects cash payment in kiosk mode.
 * Staff must enter PIN to confirm they received the cash before
 * the payment is recorded to the backend.
 *
 * **McDonald's/Cinépolis Pattern:**
 * - Customer sees amount to pay and "Espera al empleado" message
 * - Receipt auto-prints with amount
 * - Staff enters PIN to confirm they received cash
 * - Payment is then recorded to backend
 *
 * @param totalAmount The total amount the customer owes
 * @param tipAmount Optional tip amount (already included in totalAmount)
 * @param orderNumber Optional order number for display
 * @param onConfirm Callback when staff confirms they received cash (with staff ID)
 * @param onCancel Callback when payment is cancelled
 */
@Composable
fun KioskCashConfirmationContent(
    totalAmount: String,
    tipAmount: String? = null,
    orderNumber: String? = null,
    printerWarning: String? = null,  // 🖨️ Warning message if printer has issues
    onConfirm: (staffId: String) -> Unit,
    onCancel: () -> Unit
) {
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale("es", "MX"))
    }

    // Format amount for display
    val displayAmount = remember(totalAmount) {
        try {
            val amount = totalAmount.toBigDecimal()
            currencyFormat.format(amount)
        } catch (e: Exception) {
            "\$$totalAmount MXN"
        }
    }

    // State for showing PIN dialog (only for confirm - cancel doesn't need PIN)
    var showConfirmPinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top section - Order number only (title already in header)
        if (!orderNumber.isNullOrBlank()) {
            val displayOrderNumber = if (orderNumber.length > 6) {
                orderNumber.takeLast(6)
            } else {
                orderNumber
            }
            Text(
                text = "Orden #$displayOrderNumber",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Middle section - Amount (this is the important part)

        // Amount Card - Center section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Monto a cobrar:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayAmount,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                // Show tip if present
                if (!tipAmount.isNullOrBlank()) {
                    val tipValue = tipAmount.toBigDecimalOrNull()
                    if (tipValue != null && tipValue > java.math.BigDecimal.ZERO) {
                        Text(
                            text = "(Incluye propina: ${currencyFormat.format(tipValue)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 🖨️ Printer Warning Banner (if applicable)
        if (!printerWarning.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3CD)  // Warning yellow
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFF856404),  // Warning icon color
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = printerWarning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF856404),  // Warning text color
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Bottom section - Instructions and Buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Entrega el recibo impreso y el dinero al empleado",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Buttons for staff - compact to fit 5" screen
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),  // Reduced horizontal padding
                horizontalArrangement = Arrangement.spacedBy(8.dp)  // Reduced spacing
            ) {
                // Cancel button - no PIN needed, customer can change their mind
                OutlinedButton(
                    onClick = { onCancel() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)  // Smaller icon
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Cancelar",
                        style = MaterialTheme.typography.bodyMedium,  // Smaller text
                        maxLines = 1
                    )
                }

                // Confirm button
                Button(
                    onClick = { showConfirmPinDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)  // Smaller icon
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Confirmar",
                        style = MaterialTheme.typography.bodyMedium,  // Smaller text
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Small text for staff
            Text(
                text = "(Solo personal autorizado)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }  // End bottom Column
    }

    // Confirm PIN Dialog - only needed for confirming cash receipt
    if (showConfirmPinDialog) {
        CashConfirmationPinDialog(
            title = "Confirmar Pago",
            subtitle = "Ingresa tu PIN para confirmar que recibiste $displayAmount",
            onDismiss = { showConfirmPinDialog = false },
            onSuccess = { staffId ->
                showConfirmPinDialog = false
                onConfirm(staffId)
            }
        )
    }
}

/**
 * PIN Dialog for cash confirmation/cancellation
 */
@Composable
private fun CashConfirmationPinDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onSuccess: (staffId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Get dependencies
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            KioskCashConfirmationEntryPoint::class.java
        )
    }
    val authRepository = entryPoint.authRepository()
    val secureStorage = entryPoint.secureStorage()
    // 🥝 KIOSK: Prioritize kioskVenueId (configured separately for kiosk mode)
    val venueId = remember { secureStorage.getKioskVenueId() ?: secureStorage.getVenueId() ?: "" }

    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false // Allow custom width beyond platform defaults
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f) // Occupy most of the screen width
                .padding(vertical = 16.dp), // Removed horizontal padding to allow full width
            shape = RoundedCornerShape(24.dp), // Slightly more rounded for premium feel
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Scrollable content area
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        // Update text format: "Ingresa tu PIN para confirmar $99.00"
                        text = subtitle.replace("que recibiste ", ""),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // PIN Display
                    PinDisplay(
                        pin = pin,
                        maxLength = 10,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Error message
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Loading indicator
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Number pad
                    NumberPad(
                        onNumberClick = { number ->
                            if (pin.length < 10 && !isLoading) {
                                pin += number
                                errorMessage = null
                            }
                        },
                        onBackspace = {
                            if (pin.isNotEmpty() && !isLoading) {
                                pin = pin.dropLast(1)
                                errorMessage = null
                            }
                        },
                        enabled = !isLoading
                    )
                }

                // Fixed buttons at bottom (outside scroll)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel button
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading
                    ) {
                        Text("Cancelar", fontWeight = FontWeight.Bold)
                    }

                    // Accept button
                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                try {
                                    val result = authRepository.loginWithPin(pin, venueId)

                                    when (result) {
                                        is Result.Success -> {
                                            val response = result.data
                                            val role = response.role
                                            Timber.i("Staff role: $role")

                                            // Check if role is authorized
                                            if (CASH_CONFIRMATION_AUTHORIZED_ROLES.contains(role)) {
                                                onSuccess(response.staffId)
                                            } else {
                                                errorMessage = "No tienes permisos para esta acción"
                                                pin = ""
                                                isLoading = false
                                            }
                                        }
                                        is Result.Error -> {
                                            Timber.w(result.exception, "PIN validation failed")
                                            errorMessage = "PIN incorrecto"
                                            pin = ""
                                            isLoading = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.e(e, "Error validating PIN")
                                    errorMessage = "Error al validar PIN"
                                    pin = ""
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = pin.length >= 4 && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50)
                        )
                    ) {
                        Text("Aceptar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Simple number pad for PIN entry
 */
@Composable
private fun NumberPad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit,
    enabled: Boolean = true
) {
    val numbers = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        numbers.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.Center, // Center the buttons
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEachIndexed { index, key ->
                    // Add spacing between buttons
                    if (index > 0) Spacer(modifier = Modifier.width(16.dp))

                    when (key) {
                        "" -> {
                            // Empty space
                            Spacer(modifier = Modifier.size(72.dp))
                        }
                        "⌫" -> {
                            // Backspace
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (enabled) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable(enabled = enabled) { onBackspace() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Borrar",
                                    tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        else -> {
                            // Number button
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (enabled) MaterialTheme.colorScheme.surfaceVariant
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable(enabled = enabled) { onNumberClick(key) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
