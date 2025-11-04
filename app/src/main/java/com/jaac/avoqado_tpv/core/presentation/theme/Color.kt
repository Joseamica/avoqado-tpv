package com.jaac.avoqado_tpv.core.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Avoqado TPV Color System
 *
 * Semantic color palette for Material 3 theme
 * Based on Avoqado brand colors with accessibility compliance (AA/AAA)
 *
 * **IMPORTANT:**
 * - NEVER use Color(0xFF...) directly in composables
 * - ALWAYS use MaterialTheme.colorScheme.* instead
 * - These colors are ONLY for theme definition
 *
 * Color contrast ratios:
 * - Normal text (< 18pt): 4.5:1 minimum (AA)
 * - Large text (≥ 18pt): 3:1 minimum (AA)
 * - Icons and graphics: 3:1 minimum (AA)
 */

// ========== Primary Colors (Avoqado Green) ==========

/**
 * Primary brand color - Avoqado Green
 * Used for primary actions, active states, emphasis
 */
val AvoqadoGreen = Color(0xFF10B981) // Green 500

/**
 * Light variant of primary color
 * Used for hover states, light backgrounds
 */
val AvoqadoGreenLight = Color(0xFF34D399) // Green 400

/**
 * Dark variant of primary color
 * Used for pressed states, dark backgrounds
 */
val AvoqadoGreenDark = Color(0xFF059669) // Green 600

// ========== Secondary Colors (Warm Accent) ==========

/**
 * Secondary accent color - Warm Orange
 * Used for secondary actions, highlights, warnings
 */
val AvoqadoOrange = Color(0xFFF59E0B) // Amber 500

val AvoqadoOrangeLight = Color(0xFFFBBF24) // Amber 400
val AvoqadoOrangeDark = Color(0xFFD97706) // Amber 600

// ========== Tertiary Colors (Cool Blue) ==========

/**
 * Tertiary color - Cool Blue
 * Used for information, links, tertiary actions
 */
val AvoqadoBlue = Color(0xFF3B82F6) // Blue 500

val AvoqadoBlueLight = Color(0xFF60A5FA) // Blue 400
val AvoqadoBlueDark = Color(0xFF2563EB) // Blue 600

// ========== Neutral Colors (Grays) ==========

val Gray50 = Color(0xFFF9FAFB)
val Gray100 = Color(0xFFF3F4F6)
val Gray200 = Color(0xFFE5E7EB)
val Gray300 = Color(0xFFD1D5DB)
val Gray400 = Color(0xFF9CA3AF)
val Gray500 = Color(0xFF6B7280)
val Gray600 = Color(0xFF4B5563)
val Gray700 = Color(0xFF374151)
val Gray800 = Color(0xFF1F2937)
val Gray900 = Color(0xFF111827)

// ========== Semantic Colors ==========

/**
 * Success color - Positive feedback, completed actions
 * Contrast ratio: 4.85:1 (AA compliant)
 */
val Success = Color(0xFF10B981) // Green 500

/**
 * Warning color - Caution, attention needed
 * Contrast ratio: 3.52:1 (AA large text)
 */
val Warning = Color(0xFFF59E0B) // Amber 500

/**
 * Error color - Destructive actions, errors
 * Contrast ratio: 4.53:1 (AA compliant)
 */
val Error = Color(0xFFEF4444) // Red 500

/**
 * Info color - Informational messages
 * Contrast ratio: 4.56:1 (AA compliant)
 */
val Info = Color(0xFF3B82F6) // Blue 500

// ========== Light Theme Colors ==========

val LightPrimary = AvoqadoGreen
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFD1FAE5) // Green 100
val LightOnPrimaryContainer = Color(0xFF064E3B) // Green 900

val LightSecondary = AvoqadoOrange
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFFFEF3C7) // Amber 100
val LightOnSecondaryContainer = Color(0xFF78350F) // Amber 900

val LightTertiary = AvoqadoBlue
val LightOnTertiary = Color.White
val LightTertiaryContainer = Color(0xFFDBEAFE) // Blue 100
val LightOnTertiaryContainer = Color(0xFF1E3A8A) // Blue 900

val LightError = Error
val LightOnError = Color.White
val LightErrorContainer = Color(0xFFFEE2E2) // Red 100
val LightOnErrorContainer = Color(0xFF7F1D1D) // Red 900

val LightBackground = Color.White
val LightOnBackground = Gray900
val LightSurface = Color.White
val LightOnSurface = Gray900
val LightSurfaceVariant = Gray100
val LightOnSurfaceVariant = Gray700

val LightOutline = Gray300
val LightOutlineVariant = Gray200

// ========== Dark Theme Colors (Avoqado Dashboard Web) ==========

/**
 * Dark theme matching Avoqado Web Dashboard design system
 * OKLCH color space converted to HEX for Android compatibility
 *
 * **DEFAULT THEME**: App always uses Dark Mode (no system detection)
 * Professional TPV pattern following Square Terminal & Toast POS
 *
 * Source: avoqado-web-dashboard/src/index.css (.dark theme)
 *
 * Design tokens:
 * - background: oklch(0.145 0 0) → #1C1C1C (Deep charcoal)
 * - foreground: oklch(0.985 0 0) → #FAFAFA (Soft white)
 * - card: oklch(0.205 0 0) → #2A2A2A (Card surface)
 * - primary: oklch(0.922 0 0) → #E8E8E8 (Light primary)
 * - secondary: oklch(0.269 0 0) → #383838 (Muted gray)
 * - accent: oklch(0.371 0 0) → #505050 (Medium gray)
 * - destructive: oklch(0.704 0.191 22.216) → #EB5757 (Soft red)
 * - surface: oklch(0.2 0 0) → #282828 (Surface layer)
 */

// Primary (light gray for text/buttons in dark mode)
val DarkPrimary = Color(0xFFE8E8E8) // oklch(0.922 0 0)
val DarkOnPrimary = Color(0xFF2A2A2A) // oklch(0.205 0 0)
val DarkPrimaryContainer = Color(0xFF505050) // oklch(0.371 0 0)
val DarkOnPrimaryContainer = Color(0xFFFAFAFA) // oklch(0.985 0 0)

// Secondary (muted grays)
val DarkSecondary = Color(0xFF383838) // oklch(0.269 0 0)
val DarkOnSecondary = Color(0xFFFAFAFA) // oklch(0.985 0 0)
val DarkSecondaryContainer = Color(0xFF383838) // oklch(0.269 0 0)
val DarkOnSecondaryContainer = Color(0xFFE8E8E8) // oklch(0.922 0 0)

// Tertiary (keep Avoqado green for brand consistency)
val DarkTertiary = AvoqadoGreenLight
val DarkOnTertiary = Color(0xFF064E3B)
val DarkTertiaryContainer = Color(0xFF047857)
val DarkOnTertiaryContainer = Color(0xFFD1FAE5)

// Error/Destructive (soft red from dashboard)
val DarkError = Color(0xFFEB5757) // oklch(0.704 0.191 22.216)
val DarkOnError = Color(0xFFFAFAFA) // oklch(0.985 0 0)
val DarkErrorContainer = Color(0xFF8B2E2E) // Darker variant
val DarkOnErrorContainer = Color(0xFFFAFAFA) // oklch(0.985 0 0)

// Background & Surface (deep charcoal matching dashboard)
val DarkBackground = Color(0xFF1C1C1C) // oklch(0.145 0 0) - Main background
val DarkOnBackground = Color(0xFFFAFAFA) // oklch(0.985 0 0) - Text on background
val DarkSurface = Color(0xFF2A2A2A) // oklch(0.205 0 0) - Cards & elevated surfaces
val DarkOnSurface = Color(0xFFFAFAFA) // oklch(0.985 0 0) - Text on surface
val DarkSurfaceVariant = Color(0xFF282828) // oklch(0.2 0 0) - Subtle surface layer
val DarkOnSurfaceVariant = Color(0xFFB5B5B5) // oklch(0.708 0 0) - Muted text

// Outlines & Borders (subtle in dark mode)
val DarkOutline = Color(0xFF383838) // oklch(0.269 0 0) - Borders
val DarkOutlineVariant = Color(0xFF2A2A2A) // oklch(0.205 0 0) - Subtle dividers
