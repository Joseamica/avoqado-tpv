# Theme & Color System

## Overview

Avoqado TPV uses a dual-theme system (Light/Dark) with Material 3 + custom semantic colors.

- **Default**: Light mode
- **Toggle**: Settings > "Modo Oscuro"
- **Persistence**: `SecureStorage.saveIsDarkMode()` / `getIsDarkMode()` (survives logout)
- **Edge-to-edge**: `enableEdgeToEdge()` in MainActivity, transparent system bars

## File Map

| File | Purpose |
|------|---------|
| `core/presentation/theme/Color.kt` | All color definitions (single source of truth) |
| `core/presentation/theme/Theme.kt` | Assembles schemes, system bars, `AvoqadoTheme()` |
| `core/presentation/theme/Type.kt` | Typography scale |
| `res/values/themes.xml` | XML startup theme (splash screen only) |
| `res/values/colors.xml` | XML color resources (used only by themes.xml) |

## Color.kt Structure

### Brand Colors (lines 23-63)

```kotlin
val AvoqadoGreen      = Color(0xFF10B981)  // Primary — buttons, active states
val AvoqadoGreenLight = Color(0xFF34D399)  // Hover, light backgrounds
val AvoqadoGreenDark  = Color(0xFF059669)  // Pressed states

val AvoqadoOrange      = Color(0xFFF59E0B) // Secondary — highlights, warnings
val AvoqadoOrangeLight = Color(0xFFFBBF24)
val AvoqadoOrangeDark  = Color(0xFFD97706)

val AvoqadoBlue      = Color(0xFF3B82F6)   // Tertiary — info, links
val AvoqadoBlueLight = Color(0xFF60A5FA)
val AvoqadoBlueDark  = Color(0xFF2563EB)
```

### Neutral Grays (lines 65-76)

```kotlin
val Gray50  = Color(0xFFF9FAFB)  // Lightest
val Gray100 = Color(0xFFF3F4F6)  // surfaceVariant (light)
val Gray200 = Color(0xFFE5E7EB)  // outlineVariant (light)
val Gray300 = Color(0xFFD1D5DB)  // outline (light)
val Gray400 = Color(0xFF9CA3AF)
val Gray500 = Color(0xFF6B7280)
val Gray600 = Color(0xFF4B5563)
val Gray700 = Color(0xFF374151)  // onSurfaceVariant (light)
val Gray800 = Color(0xFF1F2937)
val Gray900 = Color(0xFF111827)  // onBackground / onSurface (light)
```

### Light Theme (lines 137-177)

| Token | Value | Usage |
|-------|-------|-------|
| `LightPrimary` | AvoqadoGreen `#10B981` | Primary buttons, active states |
| `LightOnPrimary` | White | Text on primary |
| `LightPrimaryContainer` | `#D1FAE5` | Light green backgrounds |
| `LightSecondary` | AvoqadoOrange `#F59E0B` | Secondary actions |
| `LightSecondaryContainer` | `#FEF3C7` | Amber backgrounds |
| `LightTertiary` | AvoqadoBlue `#3B82F6` | Info, links |
| `LightTertiaryContainer` | `#DBEAFE` | Blue backgrounds |
| `LightError` | `#EF4444` | Errors, destructive |
| `LightErrorContainer` | `#FEE2E2` | Error backgrounds |
| `LightBackground` | White | Screen background |
| `LightSurface` | White | Card surface |
| `LightSurfaceVariant` | Gray100 `#F3F4F6` | Subtle surfaces |
| `LightSurfaceTint` | Transparent | Prevents green tint on elevation |
| `LightSurfaceContainer` | `#F5F5F5` | Container backgrounds |
| `LightSurfaceContainerHigh` | `#F0F0F0` | Elevated containers |
| `LightSurfaceContainerHighest` | `#EBEBEB` | Highest elevation containers |
| `LightOutline` | Gray300 `#D1D5DB` | Borders |
| `LightOutlineVariant` | Gray200 `#E5E7EB` | Subtle dividers |

### Dark Theme (lines 179-235)

Source: `avoqado-web-dashboard/src/index.css` (.dark theme), converted from OKLCH to HEX.

| Token | Value | Source |
|-------|-------|--------|
| `DarkPrimary` | `#E8E8E8` | oklch(0.922 0 0) |
| `DarkOnPrimary` | `#2A2A2A` | oklch(0.205 0 0) |
| `DarkPrimaryContainer` | `#505050` | oklch(0.371 0 0) |
| `DarkSecondary` | `#383838` | oklch(0.269 0 0) |
| `DarkTertiary` | AvoqadoGreenLight `#34D399` | Brand green |
| `DarkError` | `#EB5757` | oklch(0.704 0.191 22.216) |
| `DarkBackground` | `#1C1C1C` | oklch(0.145 0 0) |
| `DarkSurface` | `#2A2A2A` | oklch(0.205 0 0) |
| `DarkSurfaceVariant` | `#282828` | oklch(0.2 0 0) |
| `DarkOnSurface` | `#FAFAFA` | oklch(0.985 0 0) |
| `DarkOnSurfaceVariant` | `#B5B5B5` | oklch(0.708 0 0) |
| `DarkOutline` | `#383838` | oklch(0.269 0 0) |

### Custom Semantic Colors — `AvoqadoColors` (lines 237-299)

Colors beyond Material 3's ColorScheme. Accessed via `MaterialTheme.avoqadoColors.*`.

| Field | Light | Dark | Usage |
|-------|-------|------|-------|
| `statusSuccess` | `#10B981` | `#34D399` | Success indicators |
| `statusWarning` | `#F59E0B` | `#FBBF24` | Warning indicators |
| `statusError` | `#EF4444` | `#EB5757` | Error indicators |
| `statusInfo` | `#3B82F6` | `#60A5FA` | Info indicators |
| `statusCritical` | `#B71C1C` | `#EF5350` | Critical alerts |
| `offlineOrange` | `#E65100` | `#FF6D00` | Offline state |
| `tableAvailable` | `#4CAF50` | `#66BB6A` | Floor plan: available |
| `tableOccupied` | `#F44336` | `#EF5350` | Floor plan: occupied |
| `tableReserved` | `#FFC107` | `#FFD54F` | Floor plan: reserved |
| `venueDemo` | `#8B5CF6` | `#A78BFA` | Venue badge: demo |
| `venueTrial` | `#3B82F6` | `#60A5FA` | Venue badge: trial |
| `venueOnboarding` | `#F59E0B` | `#FBBF24` | Venue badge: onboarding |
| `venueSuspended` | `#EF4444` | `#EF5350` | Venue badge: suspended |
| `venueClosed` | `#6B7280` | `#9CA3AF` | Venue badge: closed |

## Usage in Composables

```kotlin
// Material 3 colors
MaterialTheme.colorScheme.primary          // Green (light) / Light gray (dark)
MaterialTheme.colorScheme.onSurface        // Text color
MaterialTheme.colorScheme.surface          // Card background
MaterialTheme.colorScheme.background       // Screen background
MaterialTheme.colorScheme.error            // Red
MaterialTheme.colorScheme.surfaceVariant   // Subtle background

// Custom semantic colors (import needed)
import com.jaac.avoqado_tpv.core.presentation.theme.avoqadoColors

MaterialTheme.avoqadoColors.statusSuccess  // Green for success
MaterialTheme.avoqadoColors.tableAvailable // Floor plan green
MaterialTheme.avoqadoColors.offlineOrange  // Offline indicator
```

## Rules

1. **NEVER** use `Color(0xFF...)` directly in composables — always use theme tokens
2. **Canvas/DrawScope** is not `@Composable` — extract colors to `val` BEFORE the `Canvas` block
3. **`surfaceTint = Transparent`** in light theme prevents green tint on elevated surfaces (modals, cards)
4. Colors that should NOT be themed: confetti, physical objects (walls/doors), feature-specific branding

## Toggle Callback Chain

```
MainActivity (isDarkTheme state + secureStorage)
  → AvoqadoTheme(darkTheme = isDarkTheme)
    → AppNavigation(isDarkMode, onThemeToggle)
      → WelcomeScreen(isDarkMode, onDarkModeToggle)
        → WelcomeScreenContent(isDarkMode, onDarkModeToggle)
          → SettingsBottomSheet(isDarkMode, onDarkModeToggle)
```

## Edge-to-Edge Setup

- `enableEdgeToEdge()` in `MainActivity.onCreate()`
- `window.statusBarColor = Color.TRANSPARENT` in `Theme.kt` SideEffect
- `window.navigationBarColor = Color.TRANSPARENT` in `Theme.kt` SideEffect
- `isAppearanceLightStatusBars = !darkTheme` for dynamic icon colors
- `themes.xml` parent = `Theme.Material3.Light.NoActionBar` (startup only)
