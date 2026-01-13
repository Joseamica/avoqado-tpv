package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jaac.avoqado_tpv.features.authentication.domain.models.StaffRole
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskStaffSession
import timber.log.Timber

/**
 * Kiosk Staff Info Dialog
 *
 * Shows information about the currently assigned staff and allows changing.
 *
 * @param staffSession Current staff session
 * @param onDismiss Callback when dialog is dismissed
 * @param onChangeStaff Callback when user wants to change staff (opens assign dialog)
 */
@Composable
fun KioskStaffInfoDialog(
    staffSession: KioskStaffSession,
    onDismiss: () -> Unit,
    onChangeStaff: () -> Unit
) {
    Timber.d("🥝 [KIOSK-STAFF] Staff info dialog opened for: ${staffSession.staffName}")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar with initials
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = staffSession.staffInitials,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Staff name
                Text(
                    text = staffSession.staffName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Role badge
                Text(
                    text = getRoleDisplayName(staffSession.role),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Change staff button
                    OutlinedButton(
                        onClick = {
                            Timber.i("🥝 [KIOSK-STAFF] Change staff requested")
                            onChangeStaff()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cambiar Empleado")
                    }

                    // Close button
                    TextButton(
                        onClick = {
                            Timber.d("🥝 [KIOSK-STAFF] Info dialog closed")
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

/**
 * Get localized display name for staff role
 */
private fun getRoleDisplayName(role: StaffRole): String {
    return when (role) {
        StaffRole.SUPERADMIN -> "Super Administrador"
        StaffRole.OWNER -> "Propietario"
        StaffRole.ADMIN -> "Administrador"
        StaffRole.MANAGER -> "Gerente"
        StaffRole.CASHIER -> "Cajero"
        StaffRole.WAITER -> "Mesero"
        StaffRole.KITCHEN -> "Cocina"
        StaffRole.HOST -> "Host"
        StaffRole.VIEWER -> "Visor"
    }
}
