package com.jaac.avoqado_tpv.core.presentation.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Avoqado TPV Spacing & Sizing System
 *
 * Consistent spacing scale based on 4dp/8dp grid
 * All spacing values are multiples of 4dp for visual harmony
 *
 * **IMPORTANT:**
 * - NEVER hardcode dp values in composables
 * - ALWAYS use Spacing.* or Size.* instead
 * - Maintains consistency across the entire app
 *
 * Spacing Scale (4dp baseline):
 * - Space0:  0dp  (no spacing)
 * - Space1:  4dp  (minimal)
 * - Space2:  8dp  (tight)
 * - Space3:  12dp (comfortable)
 * - Space4:  16dp (standard)
 * - Space5:  20dp (spacious)
 * - Space6:  24dp (section)
 * - Space8:  32dp (large section)
 * - Space10: 40dp (major section)
 * - Space12: 48dp (screen padding)
 * - Space16: 64dp (extra large)
 *
 * Common Usage:
 * - Space1 (4dp):  Icon padding, tight lists
 * - Space2 (8dp):  Card padding, text spacing
 * - Space4 (16dp): Screen padding, button padding
 * - Space6 (24dp): Section spacing
 * - Space8 (32dp): Major sections, top bar height
 */

object Spacing {
    /** No spacing (0dp) */
    val Space0: Dp = 0.dp

    /** Minimal spacing (4dp) - Icon padding, tight lists */
    val Space1: Dp = 4.dp

    /** Tight spacing (8dp) - Card padding, text spacing */
    val Space2: Dp = 8.dp

    /** Comfortable spacing (12dp) - List item padding */
    val Space3: Dp = 12.dp

    /** Standard spacing (16dp) - Screen padding, button padding */
    val Space4: Dp = 16.dp

    /** Spacious (20dp) - Card spacing */
    val Space5: Dp = 20.dp

    /** Section spacing (24dp) - Between components */
    val Space6: Dp = 24.dp

    /** Large section (32dp) - Major sections, top bar height */
    val Space8: Dp = 32.dp

    /** Major section (40dp) - Screen sections */
    val Space10: Dp = 40.dp

    /** Screen padding (48dp) - Large screens */
    val Space12: Dp = 48.dp

    /** Extra large (64dp) - Hero sections */
    val Space16: Dp = 64.dp
}

/**
 * Component sizes for consistent UI elements
 *
 * Standard sizes for buttons, icons, cards, etc.
 */
object Size {
    // ========== Icon Sizes ==========
    /** Small icon (16dp) - List items, inline icons */
    val IconSmall: Dp = 16.dp

    /** Medium icon (24dp) - Standard buttons, toolbar */
    val IconMedium: Dp = 24.dp

    /** Large icon (32dp) - Primary actions, FAB */
    val IconLarge: Dp = 32.dp

    /** Extra large icon (48dp) - Hero sections, empty states */
    val IconExtraLarge: Dp = 48.dp

    // ========== Button Sizes ==========
    /** Button height (48dp) - Minimum touch target */
    val ButtonHeight: Dp = 48.dp

    /** Small button height (36dp) - Compact UIs */
    val ButtonHeightSmall: Dp = 36.dp

    /** Large button height (56dp) - Primary CTAs */
    val ButtonHeightLarge: Dp = 56.dp

    /** Button minimum width (64dp) - Text buttons */
    val ButtonMinWidth: Dp = 64.dp

    // ========== Card Sizes ==========
    /** Card corner radius (12dp) - Standard cards */
    val CardCornerRadius: Dp = 12.dp

    /** Card elevation (2dp) - Subtle depth */
    val CardElevation: Dp = 2.dp

    /** Card pressed elevation (8dp) - Interaction feedback */
    val CardElevationPressed: Dp = 8.dp

    // ========== Input Field Sizes ==========
    /** TextField height (56dp) - Standard text inputs */
    val TextFieldHeight: Dp = 56.dp

    /** TextField corner radius (8dp) - Rounded corners */
    val TextFieldCornerRadius: Dp = 8.dp

    // ========== Top Bar Sizes ==========
    /** Top bar height (64dp) - Standard app bar */
    val TopBarHeight: Dp = 64.dp

    /** Top bar elevation (4dp) - Elevated surface */
    val TopBarElevation: Dp = 4.dp

    // ========== Bottom Sheet Sizes ==========
    /** Bottom sheet corner radius (16dp) - Rounded top corners */
    val BottomSheetCornerRadius: Dp = 16.dp

    /** Bottom sheet peek height (96dp) - Collapsed state */
    val BottomSheetPeekHeight: Dp = 96.dp

    // ========== Divider ==========
    /** Divider thickness (1dp) - Section separators */
    val DividerThickness: Dp = 1.dp

    // ========== Border ==========
    /** Border thin (1dp) - Subtle borders */
    val BorderThin: Dp = 1.dp

    /** Border medium (2dp) - Standard borders */
    val BorderMedium: Dp = 2.dp

    /** Border thick (4dp) - Emphasis borders */
    val BorderThick: Dp = 4.dp

    // ========== Touch Targets ==========
    /** Minimum touch target (48dp) - Accessibility requirement */
    val TouchTargetMin: Dp = 48.dp

    /** Comfortable touch target (56dp) - Easy to tap */
    val TouchTargetComfortable: Dp = 56.dp

    // ========== Serialized Inventory (PAX A910S optimized) ==========
    /** Category selector height (50dp) - Compact dropdown for small screens */
    val SerializedCategorySelectorHeight: Dp = 50.dp

    /** Scanner input height (48dp) - Standard touch target */
    val SerializedScannerInputHeight: Dp = 48.dp

    /** Item row height (40dp) - Compact for listing many items */
    val SerializedItemRowHeight: Dp = 40.dp

    /** List max height (400dp) - Fixed height to prevent unbounded growth */
    val SerializedListMaxHeight: Dp = 400.dp

    /** Counter badge height (32dp) - Compact status display */
    val SerializedCounterHeight: Dp = 32.dp

    /** Product card width (260dp) - Centered card for sale preview */
    val SerializedProductCardWidth: Dp = 260.dp

    /** Product card height (160dp) - Compact product display */
    val SerializedProductCardHeight: Dp = 160.dp
}

/**
 * Usage examples:
 *
 * ```kotlin
 * // ✅ CORRECT - Using spacing constants
 * Column(
 *     modifier = Modifier.padding(Spacing.Space4),
 *     verticalArrangement = Arrangement.spacedBy(Spacing.Space2)
 * ) {
 *     Text("Order #1234")
 *     Text("Total: $50.00")
 * }
 *
 * Button(
 *     modifier = Modifier.height(Size.ButtonHeight)
 * ) {
 *     Icon(
 *         imageVector = Icons.Default.Payment,
 *         contentDescription = null,
 *         modifier = Modifier.size(Size.IconMedium)
 *     )
 *     Spacer(Modifier.width(Spacing.Space2))
 *     Text("Process Payment")
 * }
 *
 * // ❌ WRONG - Hardcoded dp values
 * Column(
 *     modifier = Modifier.padding(16.dp),  // BAD!
 *     verticalArrangement = Arrangement.spacedBy(8.dp)  // BAD!
 * ) {
 *     Text("Order #1234")
 *     Text("Total: $50.00")
 * }
 * ```
 */
