package com.jaac.avoqado_tpv.core.presentation.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Avoqado TPV Material 3 Theme
 *
 * Complete theme definition with:
 * - Light & Dark color schemes (Dark is DEFAULT)
 * - Typography scale
 * - Spacing & sizing system
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     AvoqadoTheme {  // Dark mode by default
 *         // Your composables here
 *         Text(
 *             text = "Hello World",
 *             style = MaterialTheme.typography.headlineSmall,
 *             color = MaterialTheme.colorScheme.primary
 *         )
 *     }
 * }
 * ```
 *
 * **Dark Theme (ALWAYS DEFAULT):**
 * TPV apps use dark mode for restaurant environments (Square/Toast pattern).
 * System theme detection is DISABLED - always dark mode.
 * Light theme only available for design previews via `darkTheme = false`.
 *
 * **Professional TPV Pattern:**
 * - Square Terminal: Always dark UI
 * - Toast POS: Always dark UI
 * - Stripe Terminal: Always dark UI
 * - Avoqado TPV: Always dark UI ✅
 *
 * **Dynamic Color:**
 * Material 3 dynamic color is disabled to maintain brand consistency.
 * Dashboard Web dark theme colors are used.
 */

// ========== Light Color Scheme ==========
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,

    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,

    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,

    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,

    background = LightBackground,
    onBackground = LightOnBackground,

    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,

    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

// ========== Dark Color Scheme ==========
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,

    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,

    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,

    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,

    background = DarkBackground,
    onBackground = DarkOnBackground,

    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,

    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

/**
 * Avoqado Theme wrapper
 *
 * **IMPORTANT:** TPV always uses Dark Mode by default (Square/Toast pattern).
 * Professional POS systems maintain consistent dark UI for restaurant environments.
 *
 * @param darkTheme Whether to use dark theme. Always defaults to true.
 *                  Only set to false for specific preview purposes.
 * @param content The composable content to wrap with theme
 */
@Composable
fun AvoqadoTheme(
    darkTheme: Boolean = true,  // ✅ ALWAYS dark mode by default
    content: @Composable () -> Unit
) {
    // ✅ Force dark theme for production TPV
    // Light theme only available for design/preview purposes
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme  // Only for previews
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // ✅ Always use dark status bar for TPV (light icons on dark background)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AvoqadoTypography,
        content = content
    )
}
