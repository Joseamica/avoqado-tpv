package com.jaac.avoqado_tpv.core.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.R

/**
 * Avoqado TPV Typography System
 *
 * Material 3 typography scale using DM Sans (Square-like geometric sans)
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
 * Primary font family - DM Sans
 * A geometric sans-serif that closely resembles Square Market / ARS Maquette.
 *
 * **Two builds of the SAME typeface (DM Sans), chosen at compile time:**
 *
 * - **PAX (`ENABLE_PAX_SDK = true`):** the VARIABLE font (`dmsans_variable.ttf`).
 *   Renders perfectly on PAX's font engine. Unchanged — zero risk to production PAX.
 *
 * - **Nexgo (`ENABLE_PAX_SDK = false`):** STATIC weight files instantiated from
 *   the SAME `dmsans_variable.ttf` (pinned wght=300/400/500/600/700, opsz=9 — the
 *   variable default). The Nexgo N86 (Unisoc/SPRD, Android 9, old FreeType)
 *   miscomputes glyph advance widths from the variable font's `HVAR` table →
 *   letters overlap ("Total" looked like "Tbtal"). Static fonts bake advance
 *   widths into `hmtx` (no `HVAR`) → render correctly. It's the identical DM Sans
 *   typeface, just frozen per weight — NOT a font change.
 *
 * See CHANGELOG 2026-05-28 + the variable-font / HVAR diagnosis.
 */
private val VariableFontFamily = FontFamily(
    Font(R.font.dmsans_variable, FontWeight.Light),
    Font(R.font.dmsans_variable, FontWeight.Normal),
    Font(R.font.dmsans_variable, FontWeight.Medium),
    Font(R.font.dmsans_variable, FontWeight.SemiBold),
    Font(R.font.dmsans_variable, FontWeight.Bold)
)

private val StaticFontFamily = FontFamily(
    Font(R.font.dmsans_light, FontWeight.Light),
    Font(R.font.dmsans_regular, FontWeight.Normal),
    Font(R.font.dmsans_medium, FontWeight.Medium),
    Font(R.font.dmsans_semibold, FontWeight.SemiBold),
    Font(R.font.dmsans_bold, FontWeight.Bold)
)

/**
 * PAX → variable (unchanged). Nexgo / app-to-app builds → static (FreeType-safe).
 * Gated by the compile-time PAX flag, NOT `Build.MODEL` (hardware detection is
 * unreliable on Nexgo — same rule as payment routing).
 */
private val AvoqadoFontFamily = if (BuildConfig.ENABLE_PAX_SDK) VariableFontFamily else StaticFontFamily

// ========== Typography Scale ==========

val AvoqadoTypography = Typography(
    // ========== Display Styles (Decorative) ==========
    displayLarge = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ========== Headline Styles (Section Titles) ==========
    headlineLarge = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ========== Title Styles (Card Headers, Dialog Titles) ==========
    titleLarge = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ========== Body Styles (Content Text) ==========
    bodyLarge = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ========== Label Styles (Buttons, Form Labels) ==========
    labelLarge = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AvoqadoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AvoqadoFontFamily,
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
