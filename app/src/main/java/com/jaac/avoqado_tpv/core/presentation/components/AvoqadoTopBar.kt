package com.jaac.avoqado_tpv.core.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme

/**
 * Avoqado Top App Bar
 *
 * Standard top bar with consistent styling
 *
 * @param title Bar title
 * @param modifier Modifier for customization
 * @param titleStyle Optional custom title style (defaults to titleLarge)
 * @param subtitle Optional subtitle for context (e.g., "$125.50 · 5 items")
 * @param onNavigationClick Optional back button click handler
 * @param onSettingsClick Optional settings button click handler
 * @param actions Optional action buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvoqadoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle? = null,
    subtitle: String? = null,
    onNavigationClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = 20.dp,  // Rounded bottom corners (increased for modern look)
        bottomEnd = 20.dp
    )

    // ⭐ PRODUCTION MIGRATION (2025-11-19): Environment border color
    val borderColor = if (BuildConfig.DEBUG) {
        when (BuildConfig.BLUMON_ENV) {
            "PROD" -> Color(0xFFEF5350)  // Red 400 - Production (danger)
//            "SAND" -> Color(0xFFFFA726)  // Amber 400 - Sandbox (warning)
            else -> MaterialTheme.colorScheme.outline
        }
    } else {
        // Release builds: No environment indicator
        MaterialTheme.colorScheme.outline
    }

    val borderWidth = if (BuildConfig.DEBUG && BuildConfig.BLUMON_ENV in listOf("PROD", "SAND")) {
        3.dp  // Thicker border for debug builds to make environment obvious
    } else {
        1.dp
    }

    val resolvedTitleStyle = titleStyle ?: MaterialTheme.typography.titleLarge

    CenterAlignedTopAppBar(
        title = {
            if (subtitle != null) {
                // Title + Subtitle layout
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        style = resolvedTitleStyle
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else {
                // Title only
                Text(
                    text = title,
                    style = resolvedTitleStyle
                )
            }
        },
        modifier = modifier
            .clip(shape)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape
            ),
        navigationIcon = {
            if (onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
            }
        },
        actions = {
            // ⭐ PRODUCTION MIGRATION (2025-11-19): Environment indicated by border color (not badge)

            // Settings button (if provided)
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }
            }
            // Custom actions
            actions()
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,  // Dark surface (#2A2A2A)
            titleContentColor = MaterialTheme.colorScheme.onSurface,  // White text
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

// ========== Previews ==========

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Orders"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithBackPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Order Details",
            onNavigationClick = { /* Handle back */ }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithActionsPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Menu",
            actions = {
                IconButton(onClick = { /* Handle action */ }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithSubtitlePreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Checkout",
            subtitle = "$125.50 · 5 items",
            onNavigationClick = { /* Handle back */ }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AvoqadoTopBarWithSettingsPreview() {
    AvoqadoTheme {
        AvoqadoTopBar(
            title = "Hola, Juan Pérez",
            subtitle = "Sin turno activo",
            onSettingsClick = { /* Open settings */ }
        )
    }
}
