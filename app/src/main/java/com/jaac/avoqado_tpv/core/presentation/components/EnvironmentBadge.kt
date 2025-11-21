package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Environment Badge - Shows current Blumon environment (SANDBOX vs PRODUCTION)
 *
 * ⭐ PRODUCTION MIGRATION (2025-11-19):
 * Displays build variant environment to prevent confusion during testing
 *
 * **Visibility:**
 * - DEBUG builds: Always visible (sandboxDebug, productionDebug)
 * - RELEASE builds: Hidden (users don't need to see this)
 *
 * **Colors:**
 * - SANDBOX: Amber/Yellow background (warning color)
 * - PRODUCTION: Red background (critical/danger color)
 *
 * @param modifier Modifier for customization
 */
@Composable
fun EnvironmentBadge(
    modifier: Modifier = Modifier
) {
    // Only show in debug builds
    if (!BuildConfig.DEBUG) {
        return
    }

    val environment = BuildConfig.BLUMON_ENV
    val backgroundColor = when (environment) {
        "SAND" -> Color(0xFFFFA726)  // Amber 400 (warning)
        "PROD" -> Color(0xFFEF5350)  // Red 400 (danger)
        else -> Color.Gray
    }
    val displayText = when (environment) {
        "SAND" -> "SANDBOX"
        "PROD" -> "PRODUCCIÓN"
        else -> environment
    }

    Text(
        text = displayText,
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun EnvironmentBadgeSandboxPreview() {
    AvoqadoTheme {
        EnvironmentBadge()
    }
}
