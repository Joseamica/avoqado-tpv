package com.jaac.avoqado_tpv.core.presentation.theme

import android.app.Activity
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Avoqado TPV Material 3 Theme
 *
 * Complete theme definition with:
 * - Light & Dark color schemes (Light is DEFAULT, togglable via Settings)
 * - Custom semantic colors via [AvoqadoColors] CompositionLocal
 * - Typography scale
 * - Dynamic status bar appearance
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     AvoqadoTheme {
 *         Text(
 *             text = "Hello World",
 *             style = MaterialTheme.typography.headlineSmall,
 *             color = MaterialTheme.colorScheme.primary
 *         )
 *         // Custom semantic colors:
 *         Icon(tint = MaterialTheme.avoqadoColors.statusSuccess)
 *     }
 * }
 * ```
 *
 * **Dynamic Color:**
 * Material 3 dynamic color is disabled to maintain brand consistency.
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
    outlineVariant = LightOutlineVariant,

    surfaceTint = LightSurfaceTint,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    scrim = LightScrim
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
 * @param darkTheme Whether to use dark theme. Defaults to false (Light mode).
 *                  Togglable via Settings > "Modo Oscuro".
 * @param content The composable content to wrap with theme
 */
@Composable
fun AvoqadoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val avoqadoColors = if (darkTheme) DarkAvoqadoColors else LightAvoqadoColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Transparent system bars — enableEdgeToEdge() draws content behind them
            // Compose content background shows through, scrim covers full screen
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            // Dynamic: dark icons on light background, light icons on dark background
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAvoqadoColors provides avoqadoColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AvoqadoTypography,
            content = content
        )
    }
}

/**
 * Extension to access custom [AvoqadoColors] from MaterialTheme.
 *
 * Usage: `MaterialTheme.avoqadoColors.statusSuccess`
 */
val MaterialTheme.avoqadoColors: AvoqadoColors
    @Composable @ReadOnlyComposable
    get() = LocalAvoqadoColors.current
