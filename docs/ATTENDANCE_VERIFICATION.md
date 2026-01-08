# Attendance Verification (Clock-In/Out Photo + GPS)

## Overview

Attendance verification is a feature that requires employees to take a selfie with automatic GPS capture when clocking in or out. This provides anti-fraud protection and proof of presence at the work location.

## Architecture Decision

### TpvSettings-Based (Current) vs Module-Based (Deprecated)

**Decision:** Attendance verification is configured via **TpvSettings** (terminal-level settings), NOT via VenueModule configurations.

**Rationale:**
- Attendance verification is a **general feature** that any venue can use
- NOT tied to specific modules like `SERIALIZED_INVENTORY`
- Simpler architecture: one place to configure all terminal behaviors
- Configurable per-terminal via Dashboard

### Configuration Flow

```
Dashboard UI                     Backend API                      TPV Android
┌─────────────────┐             ┌────────────────┐               ┌──────────────────┐
│ Reloj Checador  │ ──SAVE──>   │ TpvSettings    │ ──FETCH──>   │ TpvSettingsRepo  │
│ ☑ Foto entrada  │             │ requireClock   │               │ getCurrentSettings│
│ ☑ Foto salida   │             │ InPhoto: true  │               │ .requireClockIn  │
└─────────────────┘             └────────────────┘               │ Photo            │
                                                                 └──────────────────┘
```

## Configuration Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `requireClockInPhoto` | boolean | false | Require selfie + GPS at clock-in |
| `requireClockOutPhoto` | boolean | false | Require selfie + GPS at clock-out |

**Key Behavior:** GPS is **implicit** when photo is enabled. When `requireClockInPhoto` or `requireClockOutPhoto` is `true`, GPS location is automatically captured along with the photo.

## Implementation Details

### Backend (avoqado-server)

**File:** `src/services/dashboard/tpv.dashboard.service.ts`

```typescript
export interface TpvSettings {
  // ... existing fields
  requireClockInPhoto: boolean;
  requireClockOutPhoto: boolean;
}

const DEFAULT_SETTINGS: TpvSettings = {
  // ... existing
  requireClockInPhoto: false,
  requireClockOutPhoto: false,
};
```

### Dashboard (avoqado-web-dashboard)

**File:** `src/pages/Settings/components/TpvSettingsForm.tsx`

New section "Reloj Checador" with two toggle switches:
- "Foto al registrar entrada" → `requireClockInPhoto`
- "Foto al registrar salida" → `requireClockOutPhoto`

### TPV Android (avoqado-tpv)

**Files:**
- `features/payment/domain/model/TpvSettings.kt` - Domain model
- `core/data/network/dto/TpvSettingsDto.kt` - API DTO
- `core/data/local/SecureStorage.kt` - Persistent cache (**CRITICAL**)
- `features/payment/data/repository/TpvSettingsRepository.kt` - Repository
- `features/timeclock/presentation/TimeclockViewModel.kt` - Business logic

#### ⚠️ CRITICAL: SecureStorage Persistence

The attendance fields MUST be explicitly saved/loaded in `SecureStorage.kt`. This was a bug fix (2025-01-07) - the fields existed in the model but weren't being persisted.

**Required keys in SecureStorage:**
```kotlin
private const val KEY_TPV_REQUIRE_CLOCK_IN_PHOTO = "tpv_require_clock_in_photo"
private const val KEY_TPV_REQUIRE_CLOCK_OUT_PHOTO = "tpv_require_clock_out_photo"
```

**In `saveTpvSettings()`:**
```kotlin
putBoolean(KEY_TPV_REQUIRE_CLOCK_IN_PHOTO, settings.requireClockInPhoto)
putBoolean(KEY_TPV_REQUIRE_CLOCK_OUT_PHOTO, settings.requireClockOutPhoto)
```

**In `getTpvSettings()`:**
```kotlin
requireClockInPhoto = encryptedPrefs.getBoolean(KEY_TPV_REQUIRE_CLOCK_IN_PHOTO, false),
requireClockOutPhoto = encryptedPrefs.getBoolean(KEY_TPV_REQUIRE_CLOCK_OUT_PHOTO, false)
```

**In `clearTpvSettings()`:**
```kotlin
remove(KEY_TPV_REQUIRE_CLOCK_IN_PHOTO)
remove(KEY_TPV_REQUIRE_CLOCK_OUT_PHOTO)
```

**Key Code Pattern:**

```kotlin
// TimeclockViewModel.kt
fun clockIn() {
    viewModelScope.launch {
        val settings = tpvSettingsRepository.getCurrentSettings()
        val requirePhoto = settings.requireClockInPhoto

        if (requirePhoto) {
            // Show camera for selfie capture
            // GPS is captured automatically when photo is taken
            _events.emit(TimeclockEvent.ShowPhotoCapture(isClockOut = false))
        } else {
            // Clock in without photo
            performClockIn(staffId, staffName, photoUrl = null)
        }
    }
}

private suspend fun performClockIn(staffId: String, staffName: String, photoUrl: String?) {
    val settings = tpvSettingsRepository.getCurrentSettings()
    val captureGps = settings.requireClockInPhoto // GPS auto-captured when photo required

    val gpsLocation = if (captureGps) {
        locationService.getCurrentLocation()
    } else null

    // Send to backend with photo URL + GPS coordinates
}
```

## Data Flow

### Clock-In with Photo Verification

```
1. User taps "Registrar entrada"
2. TimeclockViewModel checks tpvSettingsRepository.getCurrentSettings().requireClockInPhoto
3. If true:
   a. Launch camera for selfie
   b. Upload photo to Firebase Storage
   c. Capture GPS location automatically
   d. Send TimeEntry with:
      - checkInPhotoUrl: "https://..."
      - clockInLatitude: 19.4326
      - clockInLongitude: -99.1332
      - clockInAccuracy: 15.0 (meters)
4. TimeEntry created in backend
5. Success → Show confirmation
```

### Clock-Out with Photo Verification

Same flow but uses:
- `requireClockOutPhoto` setting
- `checkOutPhotoUrl` field
- `clockOutLatitude/Longitude/Accuracy` fields

## Database Schema

**TimeEntry model already has GPS fields:**

```prisma
model TimeEntry {
  // ... existing fields
  clockInLatitude    Float?
  clockInLongitude   Float?
  clockInAccuracy    Float?
  clockOutLatitude   Float?
  clockOutLongitude  Float?
  clockOutAccuracy   Float?
  checkInPhotoUrl    String?
  checkOutPhotoUrl   String?
}
```

## Testing

### Unit Tests

**File:** `app/src/test/java/com/jaac/avoqado_tpv/features/timeclock/domain/AttendanceVerificationTest.kt`

Tests covered:
- Default TpvSettings has attendance verification disabled
- TpvSettings can enable clock-in/clock-out photo verification
- DTO mapping with null values defaults to disabled
- DTO mapping with explicit true/false values
- Independence from other TpvSettings fields

### Manual Testing Checklist

- [ ] Dashboard: "Reloj Checador" toggles save correctly
- [ ] TPV: Fetches TpvSettings with correct `requireClockInPhoto` value
- [ ] TPV: Photo prompt shown at clock-in when `requireClockInPhoto = true`
- [ ] TPV: GPS captured automatically when photo is taken
- [ ] TPV: Photo + GPS sent to backend and saved in TimeEntry
- [ ] TPV: No photo prompt when `requireClockInPhoto = false`
- [ ] TPV: Same behavior for clock-out with `requireClockOutPhoto`
- [ ] Offline: Settings cached for offline clock-in/out

## Migration Notes

### From Module-Based to TpvSettings-Based

**Before (Deprecated):**
```kotlin
// OLD: Tied to SERIALIZED_INVENTORY module
val moduleConfig = modulesRepository.getModuleConfig(MODULE_SERIALIZED_INVENTORY)
val requirePhoto = moduleConfig?.attendance?.requireClockInPhoto == true
```

**After (Current):**
```kotlin
// NEW: General terminal setting
val settings = tpvSettingsRepository.getCurrentSettings()
val requirePhoto = settings.requireClockInPhoto
```

**Why the change:**
1. Attendance verification is NOT specific to serialized inventory
2. Any venue type (restaurant, retail, etc.) can use it
3. Configuration is simpler (one place: TpvSettings)
4. Dashboard UI is cleaner (part of TPV Settings, not module config)

## Bug Fixes History

### 2025-01-07: SecureStorage Not Persisting Attendance Fields

**Symptom:** Photo verification not prompting at clock-in despite Dashboard toggle being enabled.

**Root Cause:** `SecureStorage.kt` had the `saveTpvSettings()` and `getTpvSettings()` methods, but they weren't including the `requireClockInPhoto` and `requireClockOutPhoto` fields. The settings were fetched from the API but never persisted locally.

**Fix:** Added explicit save/load/clear of both attendance boolean fields in SecureStorage.

**Files Changed:**
- `core/data/local/SecureStorage.kt` - Added keys and persistence logic

**Lesson Learned:** When adding new fields to `TpvSettings`, you MUST also update:
1. `TpvSettingsDto.kt` - DTO mapping
2. `SecureStorage.kt` - Persistence (saveTpvSettings, getTpvSettings, clearTpvSettings)
3. `TpvSettingsRepository.kt` - Logging (optional but helpful)

### 2025-01-07: Data Source Mismatch (Dashboard vs TPV API)

**Symptom:** Photo verification not prompting at clock-in despite Dashboard toggle showing enabled.

**Root Cause:** Data source mismatch between Dashboard and TPV API:
- Dashboard `TpvSettingsForm.tsx` saves `requireClockInPhoto` to `Terminal.config.settings`
- TPV API `terminal.tpv.controller.ts` was reading `requireClockInPhoto` from `VenueSettings` table
- The VenueSettings value was OVERWRITING the terminal-level setting

**The Bug (in `terminal.tpv.controller.ts`):**
```typescript
// terminalTpvSettings already has the correct value from Terminal.config
const tpvSettings: TpvSettings = {
  ...terminalTpvSettings,
  enableShifts: venueSettings?.enableShifts ?? DEFAULT,
  requireClockInPhoto: venueSettings?.requireClockInPhoto ?? DEFAULT, // ← BUG: overwrites!
}
```

**Fix:** Removed the `requireClockInPhoto` overwrite. Now attendance settings come from `Terminal.config.settings` (via `terminalTpvSettings`), which is where the Dashboard saves them.

**Files Changed:**
- `avoqado-server/src/controllers/tpv/terminal.tpv.controller.ts` - Removed VenueSettings overwrite

**Lesson Learned:** When Dashboard UI saves settings to a specific location, the TPV API MUST read from that same location. Always verify data flow end-to-end:
```
Dashboard → saves to [X] → Backend API → reads from [X] → TPV receives correct value
```

## Related Documentation

- [Plan File](/Users/amieva/.claude/plans/groovy-exploring-hopcroft.md) - Full implementation plan
- [TpvSettings Domain Model](../app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/TpvSettings.kt)
- [TimeclockViewModel](../app/src/main/java/com/jaac/avoqado_tpv/features/timeclock/presentation/TimeclockViewModel.kt)
- [SecureStorage](../app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt) - Critical for persistence

---

**Last Updated:** 2025-01-07
**Author:** Claude Code
**Version:** 1.2 (Added data source mismatch bug fix documentation)
