package com.jaac.avoqado_tpv.features.payment.presentation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount

/**
 * Confirmation dialog for merchant account switching
 *
 * Shows before switching to prevent accidental changes.
 * User must explicitly confirm the switch.
 *
 * **Usage:**
 * ```kotlin
 * var showDialog by remember { mutableStateOf(false) }
 * var selectedMerchant by remember { mutableStateOf<MerchantAccount?>(null) }
 *
 * // When user taps merchant button:
 * selectedMerchant = merchant
 * showDialog = true
 *
 * // Show dialog:
 * if (showDialog && selectedMerchant != null) {
 *     MerchantSwitchConfirmationDialog(
 *         targetMerchant = selectedMerchant!!,
 *         onConfirm = {
 *             viewModel.selectMerchant(selectedMerchant!!)
 *             showDialog = false
 *         },
 *         onDismiss = {
 *             showDialog = false
 *         }
 *     )
 * }
 * ```
 *
 * **Future Enhancements:**
 * - Show warning if there are pending transactions
 * - Show estimated switch time (3-5 seconds)
 * - Show current active merchant name
 * - Add Material 3 design tokens
 *
 * @param targetMerchant Merchant account user wants to switch to
 * @param onConfirm Callback when user confirms the switch
 * @param onDismiss Callback when user cancels or dismisses dialog
 */
@Composable
fun MerchantSwitchConfirmationDialog(
    targetMerchant: MerchantAccount,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Cambiar de Cuenta",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "¿Desea cambiar a la cuenta '${targetMerchant.displayName}'?\n\n" +
                       "Este proceso tomará 3-5 segundos y reiniciará la configuración del terminal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                }
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancelar")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

/**
 * TODO: Future enhancement - Add progress dialog variant
 *
 * Could show progress steps during switch:
 * - 33% - Autenticación OAuth...
 * - 66% - Descargando claves DUKPT...
 * - 100% - Cuenta cambiada ✓
 *
 * @Composable
 * fun MerchantSwitchProgressDialog(
 *     targetMerchant: MerchantAccount,
 *     progress: Float,  // 0.0 to 1.0
 *     message: String
 * ) {
 *     // Show linear progress indicator with message
 * }
 */
