package com.jaac.avoqado_tpv.core.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Avoqado TPV Typography System
 *
 * Material 3 typography scale using Inter font family
 * Optimized for readability on PAX terminals (800x1280 @ 160dpi)
 *
 * **IMPORTANT:**
 * - NEVER hardcode text styles in composables
 * - ALWAYS use MaterialTheme.typography.* instead
 * - These styles are ONLY for theme definition
 *
 * Type Scale:
 * - Display: Large, decorative text (rare in TPV)
 * - Headline: Section titles, emphasis
 * - Title: Card headers, dialog titles
 * - Body: Primary content text
 * - Label: Buttons, tabs, form labels
 *
 * Font Sizes (optimized for terminals):
 * - Display: 57sp, 45sp, 36sp
 * - Headline: 32sp, 28sp, 24sp
 * - Title: 22sp, 16sp, 14sp
 * - Body: 16sp, 14sp
 * - Label: 14sp, 12sp, 11sp
 */

// ========== Font Families ==========

/**
 * Primary font family - System default
 * Falls back to system fonts for maximum compatibility
 *
 * TODO: Add Inter font family when assets are available
 * ```kotlin
 * val InterFontFamily = FontFamily(
 *     Font(R.font.inter_regular, FontWeight.Normal),
 *     Font(R.font.inter_medium, FontWeight.Medium),
 *     Font(R.font.inter_semibold, FontWeight.SemiBold),
 *     Font(R.font.inter_bold, FontWeight.Bold)
 * )
 * ```
 */
private val DefaultFontFamily = FontFamily.Default

// ========== Typography Scale ==========

val AvoqadoTypography = Typography(
    // ========== Display Styles (Decorative) ==========
    displayLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ========== Headline Styles (Section Titles) ==========
    headlineLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ========== Title Styles (Card Headers, Dialog Titles) ==========
    titleLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ========== Body Styles (Content Text) ==========
    bodyLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ========== Label Styles (Buttons, Form Labels) ==========
    labelLarge = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = DefaultFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/**
 * Usage examples:
 *
 * ```kotlin
 * // ✅ CORRECT - Using theme typography
 * Text(
 *     text = "Order #1234",
 *     style = MaterialTheme.typography.headlineSmall
 * )
 *
 * Text(
 *     text = "Total: $50.00",
 *     style = MaterialTheme.typography.bodyLarge
 * )
 *
 * // ❌ WRONG - Hardcoded text style
 * Text(
 *     text = "Total: $50.00",
 *     fontSize = 16.sp,  // BAD!
 *     fontWeight = FontWeight.Bold  // BAD!
 * )
 * ```
 */
