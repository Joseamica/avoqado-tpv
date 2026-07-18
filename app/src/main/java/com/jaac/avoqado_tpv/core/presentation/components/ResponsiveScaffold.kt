package com.jaac.avoqado_tpv.core.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ScreenProfile {
    CompactSquare,
    CompactPortrait,
    RegularPortrait,
}

/**
 * Responsive Sizes Token System
 *
 * Provides dynamic sizing based on device screen dimensions.
 * Follows Square Terminal / Toast POS design patterns.
 *
 * **Size Categories:**
 * - Small: < 600dp height (PAX A80, small tablets)
 * - Medium: 600-700dp height (PAX A920)
 * - Large: > 700dp height (10" tablets)
 *
 * **Usage:**
 * ```kotlin
 * val sizes = LocalResponsiveSizes.current
 * Image(modifier = Modifier.size(sizes.logoSize))
 * Spacer(modifier = Modifier.height(sizes.spacingMedium))
 * ```
 */
data class ResponsiveSizes(
    val screenHeight: Dp,
    val screenWidth: Dp,
    val screenProfile: ScreenProfile,
    val sizeCategory: String,
    val isSquareScreen: Boolean,

    // Element sizes
    val logoSize: Dp,
    val iconSizeSmall: Dp,
    val iconSizeMedium: Dp,
    val iconSizeLarge: Dp,

    // Spacing
    val spacingXSmall: Dp,
    val spacingSmall: Dp,
    val spacingMedium: Dp,
    val spacingLarge: Dp,
    val spacingXLarge: Dp,

    // Padding
    val paddingScreen: Dp,
    val paddingSection: Dp,
    val paddingCard: Dp,

    // Keyboard (adaptive for square screens like Nexgo N62)
    val keyboardButtonSize: Dp,
    val keyboardActionWidth: Dp,
    val keyboardSpacing: Dp,
    val keyboardFontSize: Int,
) {
    companion object {
        /**
         * Calculate responsive sizes based on screen dimensions
         */
        fun calculate(height: Dp, width: Dp): ResponsiveSizes {
            val isSquare = (height - width).value.let { kotlin.math.abs(it) } < 80f
            val screenProfile = when {
                isSquare -> ScreenProfile.CompactSquare
                height < 600.dp -> ScreenProfile.CompactPortrait
                else -> ScreenProfile.RegularPortrait
            }
            val category = when {
                isSquare -> "small" // Square screens (N62 480x480) = compact
                height < 600.dp -> "small"
                height < 700.dp -> "medium"
                else -> "large"
            }

            return ResponsiveSizes(
                screenHeight = height,
                screenWidth = width,
                screenProfile = screenProfile,
                sizeCategory = category,
                isSquareScreen = isSquare,

                // Element sizes (adjust based on screen size)
                logoSize = when (category) {
                    "small" -> 60.dp
                    "medium" -> 80.dp
                    else -> 100.dp
                },
                iconSizeSmall = when (category) {
                    "small" -> 16.dp
                    "medium" -> 20.dp
                    else -> 24.dp
                },
                iconSizeMedium = when (category) {
                    "small" -> 24.dp
                    "medium" -> 32.dp
                    else -> 40.dp
                },
                iconSizeLarge = when (category) {
                    "small" -> 48.dp
                    "medium" -> 56.dp
                    else -> 64.dp
                },

                // Spacing (smaller on small screens)
                spacingXSmall = when (category) {
                    "small" -> 4.dp
                    "medium" -> 6.dp
                    else -> 8.dp
                },
                spacingSmall = when (category) {
                    "small" -> 8.dp
                    "medium" -> 12.dp
                    else -> 16.dp
                },
                spacingMedium = when (category) {
                    "small" -> 16.dp
                    "medium" -> 24.dp
                    else -> 32.dp
                },
                spacingLarge = when (category) {
                    "small" -> 24.dp
                    "medium" -> 32.dp
                    else -> 48.dp
                },
                spacingXLarge = when (category) {
                    "small" -> 32.dp
                    "medium" -> 48.dp
                    else -> 64.dp
                },

                // Padding - ⭐ Reduced for more screen space (user request)
                paddingScreen = when (category) {
                    "small" -> 8.dp   // ⭐ Was 16dp, now 8dp (closer to edge)
                    "medium" -> 12.dp // ⭐ Was 20dp, now 12dp
                    else -> 12.dp     // ⭐ Was 24dp, now 12dp (PAX A920)
                },
                paddingSection = when (category) {
                    "small" -> 12.dp
                    "medium" -> 16.dp
                    else -> 20.dp
                },
                paddingCard = when (category) {
                    "small" -> 12.dp
                    "medium" -> 16.dp
                    else -> 20.dp
                },

                // Keyboard sizing — compact square screens prioritize touch targets
                keyboardButtonSize = when (screenProfile) {
                    ScreenProfile.CompactSquare -> 60.dp
                    ScreenProfile.CompactPortrait,
                    ScreenProfile.RegularPortrait -> 80.dp
                },
                keyboardActionWidth = when (screenProfile) {
                    ScreenProfile.CompactSquare -> 72.dp
                    ScreenProfile.CompactPortrait,
                    ScreenProfile.RegularPortrait -> 100.dp
                },
                keyboardSpacing = if (screenProfile == ScreenProfile.CompactSquare) 4.dp else 8.dp,
                keyboardFontSize = if (screenProfile == ScreenProfile.CompactSquare) 22 else 24,
            )
        }
    }
}

@Composable
fun rememberResponsiveSizes(): ResponsiveSizes {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenHeightDp, configuration.screenWidthDp) {
        ResponsiveSizes.calculate(
            height = configuration.screenHeightDp.dp,
            width = configuration.screenWidthDp.dp,
        )
    }
}

/**
 * CompositionLocal for accessing responsive sizes
 *
 * Automatically provided by ResponsiveScaffold.
 * Access anywhere in the component tree with `LocalResponsiveSizes.current`
 */
val LocalResponsiveSizes = compositionLocalOf<ResponsiveSizes> {
    error("ResponsiveSizes not provided. Wrap your screen with ResponsiveScaffold.")
}

/**
 * Responsive Scaffold
 *
 * Adaptive layout container that automatically adjusts sizing based on screen dimensions.
 * Follows Square Terminal / Toast POS / Clover design patterns.
 *
 * **Features:**
 * - Calculates optimal sizes for current device
 * - Provides sizes via CompositionLocal to all children
 * - Optional vertical scroll for content-heavy screens
 * - Centers content horizontally by default
 *
 * **When to use:**
 * - ✅ All workflow screens (login, payment, checkout)
 * - ✅ Forms and input screens
 * - ✅ List screens (with scrollable = true)
 * - ❌ Complex custom layouts (use BoxWithConstraints directly)
 *
 * **Example (no scroll - login screen):**
 * ```kotlin
 * ResponsiveScaffold(scrollable = false) {
 *     val sizes = LocalResponsiveSizes.current
 *
 *     Image(modifier = Modifier.size(sizes.logoSize))
 *     Spacer(modifier = Modifier.height(sizes.spacingMedium))
 *     Text("Title")
 *     Spacer(modifier = Modifier.height(sizes.spacingMedium))
 *     PinPad()
 * }
 * ```
 *
 * **Example (with scroll - settings screen):**
 * ```kotlin
 * ResponsiveScaffold(scrollable = true) {
 *     val sizes = LocalResponsiveSizes.current
 *
 *     repeat(20) {
 *         SettingRow()
 *         Spacer(modifier = Modifier.height(sizes.spacingSmall))
 *     }
 * }
 * ```
 *
 * @param modifier Modifier for the root container
 * @param scrollable Whether content should be scrollable (default: false)
 * @param horizontalAlignment Horizontal alignment of content (default: Center)
 * @param verticalArrangement Vertical arrangement for non-scrollable content (default: Center)
 * @param content Screen content that can access ResponsiveSizes via LocalResponsiveSizes.current
 */
@Composable
fun ResponsiveScaffold(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Calculate sizes based on PHYSICAL screen dimensions (not available space).
        // Using LocalConfiguration ensures the size category stays stable
        // even when banners/overlays reduce available space.
        val sizes = rememberResponsiveSizes()

        // Provide sizes to all children via CompositionLocal
        CompositionLocalProvider(LocalResponsiveSizes provides sizes) {
            if (scrollable) {
                // Scrollable content - no vertical arrangement
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = sizes.paddingScreen),
                    horizontalAlignment = horizontalAlignment
                ) {
                    content()
                }
            } else {
                // Non-scrollable workflow screen - apply vertical arrangement
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = sizes.paddingScreen),
                    horizontalAlignment = horizontalAlignment,
                    verticalArrangement = verticalArrangement
                ) {
                    content()
                }
            }
        }
    }
}
