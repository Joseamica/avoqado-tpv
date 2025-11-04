package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.presentation.theme.Spacing

/**
 * Avoqado Dialog
 *
 * Standard dialog for confirmations and alerts
 *
 * @param title Dialog title
 * @param message Dialog message
 * @param onDismiss Callback when dialog is dismissed
 * @param confirmText Confirm button text (default: "Confirm")
 * @param dismissText Dismiss button text (default: "Cancel")
 * @param onConfirm Callback when confirm button is clicked
 * @param icon Optional icon to display
 * @param isDangerous Whether this is a dangerous action (uses error colors)
 */
@Composable
fun AvoqadoDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit,
    icon: ImageVector? = null,
    isDangerous: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (isDangerous) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            AvoqadoButton(
                text = confirmText,
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            )
        },
        dismissButton = {
            AvoqadoTextButton(
                text = dismissText,
                onClick = onDismiss
            )
        }
    )
}

/**
 * Avoqado Alert Dialog
 *
 * Simple alert with only dismiss button
 *
 * @param title Dialog title
 * @param message Dialog message
 * @param onDismiss Callback when dialog is dismissed
 * @param dismissText Dismiss button text (default: "OK")
 * @param icon Optional icon to display
 */
@Composable
fun AvoqadoAlertDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    dismissText: String = "OK",
    icon: ImageVector? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            AvoqadoButton(
                text = dismissText,
                onClick = onDismiss
            )
        }
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoDialogPreview() {
    AvoqadoTheme {
        var showConfirmDialog by remember { mutableStateOf(true) }
        var showAlertDialog by remember { mutableStateOf(false) }
        var showDangerDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.padding(Spacing.Space4),
            verticalArrangement = Arrangement.spacedBy(Spacing.Space3)
        ) {
            // Button to show confirmation dialog
            AvoqadoButton(
                text = "Show Confirmation",
                onClick = { showConfirmDialog = true },
                fullWidth = true
            )

            // Button to show alert dialog
            AvoqadoButton(
                text = "Show Alert",
                onClick = { showAlertDialog = true },
                fullWidth = true
            )

            // Button to show dangerous dialog
            AvoqadoButton(
                text = "Show Dangerous Action",
                onClick = { showDangerDialog = true },
                fullWidth = true
            )
        }

        // Confirmation Dialog
        if (showConfirmDialog) {
            AvoqadoDialog(
                title = "Process Payment",
                message = "Are you sure you want to process this payment of $45.00?",
                onDismiss = { showConfirmDialog = false },
                onConfirm = { /* Process payment */ },
                confirmText = "Process",
                dismissText = "Cancel"
            )
        }

        // Alert Dialog
        if (showAlertDialog) {
            AvoqadoAlertDialog(
                title = "Payment Successful",
                message = "The payment of $45.00 has been processed successfully.",
                onDismiss = { showAlertDialog = false },
                icon = Icons.Default.Warning
            )
        }

        // Dangerous Dialog
        if (showDangerDialog) {
            AvoqadoDialog(
                title = "Delete Order",
                message = "Are you sure you want to delete this order? This action cannot be undone.",
                onDismiss = { showDangerDialog = false },
                onConfirm = { /* Delete order */ },
                confirmText = "Delete",
                dismissText = "Cancel",
                icon = Icons.Default.Delete,
                isDangerous = true
            )
        }
    }
}

@Preview(showBackground = true, name = "Dialog Variants")
@Composable
private fun DialogVariantsPreview() {
    AvoqadoTheme {
        var showDialog by remember { mutableStateOf(true) }

        if (showDialog) {
            AvoqadoDialog(
                title = "Complete Order",
                message = "Order #1234 is ready to be completed. Total amount: $45.00",
                onDismiss = { showDialog = false },
                onConfirm = { /* Complete order */ },
                confirmText = "Complete",
                dismissText = "Cancel"
            )
        }
    }
}
