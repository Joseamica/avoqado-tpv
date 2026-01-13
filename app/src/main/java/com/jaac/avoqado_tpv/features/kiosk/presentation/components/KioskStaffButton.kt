package com.jaac.avoqado_tpv.features.kiosk.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.features.kiosk.domain.model.KioskStaffSession

/**
 * Kiosk Staff Button
 *
 * Circular button displayed in the kiosk header to show/manage staff assignment.
 *
 * States:
 * - No staff: Shows outline person icon (gray)
 * - Staff assigned: Shows initials with colored background
 *
 * @param staffSession Current staff session or null if not assigned
 * @param onClick Callback when button is clicked
 * @param modifier Optional modifier
 */
@Composable
fun KioskStaffButton(
    staffSession: KioskStaffSession?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(28.dp)  // Reduced from 40.dp to match gear icon size
            .clip(CircleShape)
            .then(
                if (staffSession != null) {
                    // Staff assigned - colored background
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    // No staff - outline style
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,  // Reduced from 1.5.dp
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (staffSession != null) {
            // Show initials
            Text(
                text = staffSession.staffInitials,
                style = MaterialTheme.typography.labelSmall,  // Reduced from labelLarge
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,  // Reduced from 14.sp
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            // Show person icon
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = "Asignar empleado",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)  // Reduced from 24.dp
            )
        }
    }
}
