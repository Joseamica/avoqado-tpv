package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.features.authentication.domain.models.VenueStatus
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Venue Status Banner
 *
 * Displays a banner indicating the current venue status when it's not ACTIVE.
 * Automatically hides when venue is in normal operational mode.
 *
 * Status colors:
 * - LIVE_DEMO: Purple (demo mode)
 * - TRIAL: Blue (trial period)
 * - ONBOARDING: Yellow (setup in progress)
 * - PENDING_ACTIVATION: Yellow (awaiting KYC approval)
 * - ACTIVE: No banner shown
 * - SUSPENDED: Red (payment/policy issues)
 * - ADMIN_SUSPENDED: Red (admin action)
 * - CLOSED: Gray (permanently closed)
 */
@Composable
fun VenueStatusBanner(
    status: VenueStatus,
    modifier: Modifier = Modifier
) {
    // Don't show banner for ACTIVE status
    val shouldShow = status != VenueStatus.ACTIVE

    AnimatedVisibility(
        visible = shouldShow,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val (backgroundColor, textColor, icon, message) = getStatusConfig(status)

        Surface(
            color = backgroundColor,
            shape = RoundedCornerShape(0.dp),
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Status Row Component for Settings Screen
 *
 * Shows venue status as a chip/badge in settings.
 */
@Composable
fun VenueStatusRow(
    status: VenueStatus,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Estado del establecimiento",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        val (chipColor, label) = getStatusChipConfig(status)

        Surface(
            color = chipColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = label,
                color = chipColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONFIGURATION HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private data class StatusConfig(
    val backgroundColor: Color,
    val textColor: Color,
    val icon: ImageVector,
    val message: String
)

private fun getStatusConfig(status: VenueStatus): StatusConfig {
    return when (status) {
        VenueStatus.LIVE_DEMO -> StatusConfig(
            backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.2f),
            textColor = Color(0xFF8B5CF6),
            icon = Icons.Default.Science,
            message = "Modo Demo - Transacciones simuladas"
        )
        VenueStatus.TRIAL -> StatusConfig(
            backgroundColor = Color(0xFF3B82F6).copy(alpha = 0.2f),
            textColor = Color(0xFF3B82F6),
            icon = Icons.Default.Info,
            message = "Periodo de prueba activo"
        )
        VenueStatus.ONBOARDING -> StatusConfig(
            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
            textColor = Color(0xFFF59E0B),
            icon = Icons.Default.Info,
            message = "Configuraci\u00F3n en progreso"
        )
        VenueStatus.PENDING_ACTIVATION -> StatusConfig(
            backgroundColor = Color(0xFFF59E0B).copy(alpha = 0.2f),
            textColor = Color(0xFFF59E0B),
            icon = Icons.Default.Warning,
            message = "Pendiente de activaci\u00F3n"
        )
        VenueStatus.ACTIVE -> StatusConfig(
            backgroundColor = Color.Transparent,
            textColor = Color.Transparent,
            icon = Icons.Default.Info,
            message = ""
        )
        VenueStatus.SUSPENDED -> StatusConfig(
            backgroundColor = Color(0xFFEF4444).copy(alpha = 0.2f),
            textColor = Color(0xFFEF4444),
            icon = Icons.Default.Warning,
            message = "Establecimiento suspendido"
        )
        VenueStatus.ADMIN_SUSPENDED -> StatusConfig(
            backgroundColor = Color(0xFFEF4444).copy(alpha = 0.2f),
            textColor = Color(0xFFEF4444),
            icon = Icons.Default.Block,
            message = "Suspendido por administraci\u00F3n"
        )
        VenueStatus.CLOSED -> StatusConfig(
            backgroundColor = Color(0xFF6B7280).copy(alpha = 0.2f),
            textColor = Color(0xFF6B7280),
            icon = Icons.Default.Block,
            message = "Establecimiento cerrado"
        )
    }
}

private fun getStatusChipConfig(status: VenueStatus): Pair<Color, String> {
    return when (status) {
        VenueStatus.LIVE_DEMO -> Color(0xFF8B5CF6) to "Demo"
        VenueStatus.TRIAL -> Color(0xFF3B82F6) to "Prueba"
        VenueStatus.ONBOARDING -> Color(0xFFF59E0B) to "Configurando"
        VenueStatus.PENDING_ACTIVATION -> Color(0xFFF59E0B) to "Pendiente"
        VenueStatus.ACTIVE -> Color(0xFF10B981) to "Activo"
        VenueStatus.SUSPENDED -> Color(0xFFEF4444) to "Suspendido"
        VenueStatus.ADMIN_SUSPENDED -> Color(0xFFEF4444) to "Suspendido"
        VenueStatus.CLOSED -> Color(0xFF6B7280) to "Cerrado"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PREVIEWS
// ═══════════════════════════════════════════════════════════════════════════════

@Preview(showBackground = true)
@Composable
private fun VenueStatusBannerDemoPreview() {
    AvoqadoTheme {
        VenueStatusBanner(status = VenueStatus.LIVE_DEMO)
    }
}

@Preview(showBackground = true)
@Composable
private fun VenueStatusBannerTrialPreview() {
    AvoqadoTheme {
        VenueStatusBanner(status = VenueStatus.TRIAL)
    }
}

@Preview(showBackground = true)
@Composable
private fun VenueStatusBannerSuspendedPreview() {
    AvoqadoTheme {
        VenueStatusBanner(status = VenueStatus.SUSPENDED)
    }
}

@Preview(showBackground = true)
@Composable
private fun VenueStatusRowActivePreview() {
    AvoqadoTheme {
        Surface(color = Color(0xFF2A2A2A)) {
            VenueStatusRow(status = VenueStatus.ACTIVE)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VenueStatusRowSuspendedPreview() {
    AvoqadoTheme {
        Surface(color = Color(0xFF2A2A2A)) {
            VenueStatusRow(status = VenueStatus.SUSPENDED)
        }
    }
}
