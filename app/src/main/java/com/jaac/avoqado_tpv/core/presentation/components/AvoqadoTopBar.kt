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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.jaac.avoqado_tpv.BuildConfig
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import kotlinx.coroutines.delay

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
    flatBottom: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val bottomRadius = 0.dp
    val shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius
    )

    // Border color - Blue for sandbox to distinguish from production
    val isSandbox = BuildConfig.BLUMON_ENV == "SAND"
    val borderColor = if (isSandbox) Color(0xFF42A5F5) else MaterialTheme.colorScheme.outline  // Blue 400
    val borderWidth = if (isSandbox) 3.dp else 1.dp

    val resolvedTitleStyle = titleStyle ?: MaterialTheme.typography.titleLarge

    // 🔒 Throttle navigation clicks to prevent double-click navigation bugs
    var isNavigationLocked by remember { mutableStateOf(false) }

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
                IconButton(
                    onClick = {
                        // 🔒 Throttle: Ignore clicks if already navigating
                        if (!isNavigationLocked) {
                            isNavigationLocked = true
                            onNavigationClick()
                        }
                    },
                    enabled = !isNavigationLocked
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }

                // 🔓 Unlock after 500ms to allow next navigation
                if (isNavigationLocked) {
                    LaunchedEffect(Unit) {
                        delay(500)
                        isNavigationLocked = false
                    }
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
