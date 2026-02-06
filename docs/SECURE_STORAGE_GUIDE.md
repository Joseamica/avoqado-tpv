# SecureStorage Guide

## What is SecureStorage?

`SecureStorage.kt` is a wrapper around Android's `EncryptedSharedPreferences` that provides type-safe methods to store and retrieve ALL app state. It's the central storage for 50+ configuration keys spanning authentication, terminal config, payment settings, modules, and more.

**Security features:**
- AES256-GCM encryption for values
- AES256-SIV encryption for keys
- Hardware-backed keystore on supported devices
- Automatic corruption recovery (deletes corrupted storage and recreates fresh)

**Location:** `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt` (1,750 lines)

## Key Categories

### Auth Keys (Session)

| Key Constant | Type | Purpose | Cleared on Logout? |
|--------------|------|---------|-------------------|
| `KEY_SESSION_TOKEN` | String | JWT token from backend | ✅ Yes |
| `KEY_REFRESH_TOKEN` | String | Refresh token | ✅ Yes |
| `KEY_STAFF_ID` | String | Current staff member ID | ✅ Yes |
| `KEY_STAFF_NAME` | String | Staff display name | ✅ Yes |
| `KEY_STAFF_ROLE` | String | StaffRole enum (ADMIN, CASHIER, etc.) | ✅ Yes |
| `KEY_PERMISSIONS` | String | Comma-separated permission list | ✅ Yes |
| `KEY_MASTER_LOGIN` | Boolean | Master TOTP bypass flag (emergency access) | ✅ Yes |

### Venue Keys (Device Activation)

| Key Constant | Type | Purpose | Cleared on Logout? |
|--------------|------|---------|-------------------|
| `KEY_VENUE_ID` | String | Venue identifier (multi-tenant isolation) | ❌ No (device activation persists) |
| `KEY_VENUE_SLUG` | String | Venue slug for Firebase Storage paths | ❌ No |
| `KEY_VENUE_STATUS` | String | VenueStatus enum (ACTIVE, TRIAL, SUSPENDED, etc.) | ❌ No |
| `KEY_VENUE_LOGO` | String | Venue logo URL | ❌ No |
| `KEY_VENUE_NAME` | String | Venue name | ❌ No |
| `KEY_VENUE_LEGAL_NAME` | String | Legal name (razón social) | ❌ No |
| `KEY_VENUE_RFC` | String | Tax ID (RFC) | ❌ No |
| `KEY_VENUE_ADDRESS` | String | Venue address | ❌ No |
| `KEY_VENUE_CITY` | String | Venue city | ❌ No |
| `KEY_VENUE_STATE` | String | Venue state | ❌ No |
| `KEY_VENUE_ZIP` | String | Venue zip code | ❌ No |
| `KEY_VENUE_TYPE` | String | Venue type (RESTAURANT, BAR, RETAIL_STORE, etc.) | ❌ No |
| `KEY_LOYALTY_ACTIVE` | Boolean | Loyalty program enabled (Toast/Square pattern) | ❌ No |

### Terminal Activation Keys

| Key Constant | Type | Purpose | Cleared on Logout? |
|--------------|------|---------|-------------------|
| `KEY_SERIAL_NUMBER` | String | Device serial number (activation proof) | ❌ No |
| `KEY_TERMINAL_ID` | String | Server-assigned terminal CUID | ❌ No |
| `KEY_VENUE_TIMEZONE` | String | IANA timezone (e.g., "America/Mexico_City") | ❌ No |

### Blumon TPV Config Keys

| Key Constant | Type | Purpose | Cleared on Logout? |
|--------------|------|---------|-------------------|
| `KEY_BLUMON_MERCHANT_ID` | String | Blumon merchant identifier | ❌ No |
| `KEY_BLUMON_TERMINAL_ID` | String | Blumon terminal identifier | ❌ No |
| `KEY_BLUMON_USERNAME` | String | Blumon API username | ❌ No |
| `KEY_BLUMON_PASSWORD` | String | Blumon API password | ❌ No |
| `KEY_BLUMON_LAST_INIT_TIMESTAMP` | Long | Last SDK init timestamp (24h rate limit) | ❌ No |

### TPV Settings Keys (Payment Flow Configuration)

All keys are saved/loaded via `saveTpvSettings(TpvSettings)` / `getTpvSettings()`:

| Key Constant | Type | Default | Purpose |
|--------------|------|---------|---------|
| `KEY_TPV_SHOW_REVIEW` | Boolean | true | Show review screen in payment flow |
| `KEY_TPV_SHOW_TIP` | Boolean | true | Show tip screen in payment flow |
| `KEY_TPV_SHOW_RECEIPT` | Boolean | true | Show receipt screen after payment |
| `KEY_TPV_DEFAULT_TIP` | Int? | null | Default tip percentage (optional) |
| `KEY_TPV_TIP_SUGGESTIONS` | String | "10,15,20" | Comma-separated tip suggestions |
| `KEY_TPV_REQUIRE_PIN` | Boolean | true | Require PIN login |
| `KEY_TPV_SHOW_VERIFICATION` | Boolean | false | Show sale verification screen |
| `KEY_TPV_REQUIRE_VERIFICATION_PHOTO` | Boolean | false | Require verification photo |
| `KEY_TPV_REQUIRE_VERIFICATION_BARCODE` | Boolean | false | Require barcode scan |
| `KEY_ENABLE_SHIFTS` | Boolean | true | Enable shift system |
| `KEY_TPV_REQUIRE_CLOCK_IN_PHOTO` | Boolean | false | Require selfie on clock-in |
| `KEY_TPV_REQUIRE_CLOCK_OUT_PHOTO` | Boolean | false | Require selfie on clock-out |
| `KEY_TPV_REQUIRE_CLOCK_IN_TO_LOGIN` | Boolean | false | Block login without active clock-in |
| `KEY_TPV_KIOSK_MODE_ENABLED` | Boolean | false | Kiosk mode enabled |
| `KEY_TPV_KIOSK_DEFAULT_MERCHANT_ID` | String? | null | Default merchant for kiosk |
| `KEY_TPV_SHOW_QUICK_PAYMENT` | Boolean | true | Show quick payment button on home |
| `KEY_TPV_SHOW_ORDER_MANAGEMENT` | Boolean | true | Show order management button |
| `KEY_TPV_SHOW_CRYPTO_OPTION` | Boolean | false | Show crypto payment option (B4Bit) |

### Terminal State Keys (Persisted Across Restarts)

| Key Constant | Type | Purpose | Cleared? |
|--------------|------|---------|----------|
| `KEY_IS_LOCKED` | Boolean | Terminal locked remotely | `clearTerminalState()` |
| `KEY_LOCK_REASON` | String? | Lock reason | `clearTerminalState()` |
| `KEY_LOCK_MESSAGE` | String? | Custom lock message | `clearTerminalState()` |
| `KEY_LOCKED_BY` | String? | Who locked terminal | `clearTerminalState()` |
| `KEY_IS_IN_MAINTENANCE` | Boolean | Maintenance mode active | `clearTerminalState()` |
| `KEY_MAINTENANCE_REASON` | String? | Maintenance reason | `clearTerminalState()` |
| `KEY_MAINTENANCE_INITIATED_BY` | String? | Who initiated maintenance | `clearTerminalState()` |

### Kiosk Mode Keys

| Key Constant | Type | Purpose | Cleared? |
|--------------|------|---------|----------|
| `KEY_IS_KIOSK_MODE` | Boolean | Kiosk mode enabled | `clearTerminalState()` |
| `KEY_KIOSK_VENUE_ID` | String | Venue ID for kiosk (separate from auth) | `clearTerminalState()` |
| `KEY_KIOSK_TIPS_ENABLED` | Boolean | Tips enabled in kiosk | `clearTerminalState()` |
| `KEY_KIOSK_REVIEW_ENABLED` | Boolean | Review prompts in kiosk | `clearTerminalState()` |
| `KEY_KIOSK_VERIFICATION_ENABLED` | Boolean | Verification in kiosk | `clearTerminalState()` |

### Module Cache Keys

| Key Constant | Type | Purpose | Cleared? |
|--------------|------|---------|----------|
| `KEY_CACHED_MODULES` | String (JSON) | Cached list of VenueModuleDto | `clearCachedModules()` |

### BLE Payment Server Keys

| Key Constant | Type | Purpose | Cleared? |
|--------------|------|---------|----------|
| `KEY_BLE_SERVER_WAS_RUNNING` | Boolean | BLE server auto-restart flag | Never (persists) |
| `KEY_BLE_KNOWN_DEVICES` | String (JSON) | List of KnownBleDevice | `clearKnownBleDevices()` |
| `KEY_BLE_ALLOW_MULTIPLE_DEVICES` | Boolean | Allow concurrent BLE connections | Never (persists) |

### Settings Keys (User Preferences)

| Key Constant | Type | Default | Cleared? |
|--------------|------|---------|----------|
| `KEY_LAST_SYNC_TIMESTAMP` | Long | 0 | Never |
| `KEY_IS_OFFLINE_MODE` | Boolean | false | Never |
| `KEY_SELECTED_LANGUAGE` | String | "es" | Never (device-level) |
| `KEY_IS_DARK_MODE` | Boolean | false | Never (device-level) |

## How to Add a New TpvSettings Field

**Scenario:** You need to add `enableAutoReceipts` to TpvSettings.

### Step 1: Add key constant

```kotlin
// In SecureStorage companion object
private const val KEY_TPV_AUTO_RECEIPTS = "tpv_auto_receipts"
```

### Step 2: Add to saveTpvSettings()

```kotlin
fun saveTpvSettings(settings: TpvSettings) {
    encryptedPrefs.edit().apply {
        // ... existing fields ...
        putBoolean(KEY_TPV_AUTO_RECEIPTS, settings.enableAutoReceipts)
    }.apply()
    Timber.d("💾 TPV settings saved: ... autoReceipts=${settings.enableAutoReceipts}")
}
```

### Step 3: Add to getTpvSettings()

```kotlin
fun getTpvSettings(): TpvSettings {
    // ... existing fields ...
    return TpvSettings(
        // ... existing fields ...
        enableAutoReceipts = encryptedPrefs.getBoolean(KEY_TPV_AUTO_RECEIPTS, false)
    )
}
```

### Step 4: Add to clearTpvSettings()

```kotlin
fun clearTpvSettings() {
    encryptedPrefs.edit().apply {
        // ... existing removes ...
        remove(KEY_TPV_AUTO_RECEIPTS)
    }.apply()
}
```

### Step 5: Update TpvSettings domain model

```kotlin
// features/payment/domain/model/TpvSettings.kt
data class TpvSettings(
    // ... existing fields ...
    val enableAutoReceipts: Boolean = false
)
```

**Common mistake:** Forgetting Step 4. If you don't add to `clearTpvSettings()`, the field persists after logout/venue change, causing stale config.

## Corruption Recovery Mechanism

`EncryptedSharedPreferences` can become corrupted after:
- Device key change
- Factory reset with backup restore
- Android keystore issues

**Recovery flow (automatic, no user intervention):**

```kotlin
private val encryptedPrefs: SharedPreferences by lazy {
    try {
        createEncryptedPreferences()
    } catch (e: Exception) {
        Timber.e(e, "🔥 Encrypted storage corrupted - attempting recovery")

        // Delete corrupted storage files
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteSharedPreferences(PREFS_NAME)

        // Recreate fresh
        createEncryptedPreferences()
    }
}
```

**Result:** App continues to run with empty storage instead of crashing. User must re-activate terminal.

**Pattern source:** Square POS uses this same "graceful degradation" approach.

## Session Lifecycle

### Device Activation (One-Time)

```kotlin
// ActivationRepository.kt
secureStorage.saveSerialNumber(serialNumber)
secureStorage.saveTerminalId(terminalId)
secureStorage.saveVenueId(venueId)
secureStorage.saveVenueSlug(venueSlug)
secureStorage.saveVenueStatus(VenueStatus.ACTIVE)
secureStorage.saveVenueTimezone("America/Mexico_City")
```

**Keys set:** `serialNumber`, `terminalId`, `venueId`, `venueSlug`, `venueStatus`, `venueTimezone`

### Staff Login

```kotlin
// AuthRepository.kt
secureStorage.saveToken(token)
secureStorage.saveRefreshToken(refreshToken)
secureStorage.saveStaffId(staffId)
secureStorage.saveStaffName(staffName)
secureStorage.saveRole(role)
secureStorage.savePermissions(permissions)
```

**Keys set:** `sessionToken`, `refreshToken`, `staffId`, `staffName`, `staffRole`, `permissions`

### Venue Assignment (Terminal-Level Config)

```kotlin
// VenueRepository.kt
secureStorage.saveVenueName(venue.name)
secureStorage.saveVenueLogo(venue.logoUrl)
secureStorage.saveVenueLegalName(venue.legalName)
secureStorage.saveVenueRfc(venue.rfc)
secureStorage.saveVenueAddress(venue.address)
secureStorage.saveVenueType(venue.type)
secureStorage.saveTpvSettings(venue.tpvSettings)
```

**Keys set:** All venue metadata + TPV settings

### Blumon Configuration (Merchant-Level)

```kotlin
// PaymentRepository.kt
secureStorage.saveBlumonMerchantId(merchantId)
secureStorage.saveBlumonTerminalId(terminalId)
secureStorage.saveBlumonCredentials(username, password)
```

**Keys set:** `blumonMerchantId`, `blumonTerminalId`, `blumonUsername`, `blumonPassword`

## What Gets Cleared?

### clearSession() - Logout (Staff Only)

Clears: `sessionToken`, `refreshToken`, `staffId`, `staffName`, `staffRole`, `permissions`, `masterLogin`

**Preserves:** `venueId`, `venueSlug`, `venueStatus`, all venue metadata, Blumon config, TPV settings, terminal activation

**Why:** Device remains activated to venue after logout. Only the staff member's session ends.

### clearAll() - Factory Reset

Clears: **EVERYTHING** (50+ keys)

**Use case:** Terminal deactivation, venue change, emergency wipe

### clearTpvSettings() - Venue Change

Clears: All 18 `KEY_TPV_*` settings

**Use case:** Switching venue assignment (new venue may have different payment flow config)

### clearTerminalState() - Maintenance/Lock Reset

Clears: Lock state, maintenance state, kiosk mode

**Use case:** Exiting maintenance/lock/kiosk modes

### clearBlumonCredentials() - Merchant Unlink

Clears: `blumonMerchantId`, `blumonTerminalId`, `blumonUsername`, `blumonPassword`

**Use case:** Unlinking Blumon merchant account

### clearCachedModules() - Stale Module Data

Clears: `cachedModules`

**Use case:** Force module refresh from backend

### clearKnownBleDevices() - BLE Device Reset

Clears: `bleKnownDevices`

**Use case:** Resetting BLE device whitelist

## Common Mistakes

| Mistake | Symptom | Fix |
|---------|---------|-----|
| Forgot to add to `clearTpvSettings()` | Setting persists after venue change | Add `remove(KEY_NEW_SETTING)` |
| Used generic `getString()` instead of typed method | No validation, nullable confusion | Use `getTpvSettings()` instead |
| Cleared `venueId` in `clearSession()` | Device loses activation on logout | Never clear venue keys in session logout |
| Forgot to save after setting | Value not persisted, lost on restart | Call `.apply()` after every `edit()` |
| Added field to TpvSettings but not to save/load | Always returns default value | Add to `saveTpvSettings()` + `getTpvSettings()` |
| Used Float for money | Precision loss | Use Int (cents) or BigDecimal |

## Code Snippets

### Check if terminal is activated

```kotlin
if (secureStorage.isTerminalActivated()) {
    // Has serialNumber
}
```

### Check if staff is authenticated

```kotlin
if (secureStorage.isAuthenticated()) {
    val token = secureStorage.getToken()
}
```

### Get auth context

```kotlin
val venueId = secureStorage.getVenueId()
val staffId = secureStorage.getStaffId()
val role = secureStorage.getRole()
```

### Check permission

```kotlin
if (secureStorage.hasPermission("tpv-payments:refund")) {
    // Show refund button
}
```

### Save/load TPV settings

```kotlin
// Save
val settings = TpvSettings(showTipScreen = false, showReviewScreen = true)
secureStorage.saveTpvSettings(settings)

// Load
val settings = secureStorage.getTpvSettings()
if (settings.showTipScreen) {
    // Show tip screen
}
```

### Venue status checks

```kotlin
if (secureStorage.isVenueOperational()) {
    // Process payments
}

if (secureStorage.isVenueDemo()) {
    // Show demo mode banner
}
```

### BLE known devices

```kotlin
// Add/update device
secureStorage.upsertKnownBleDevice(
    address = "60:B3:88:95:25:48",
    name = "iPhone 14",
    deviceId = "UUID-123",
    incrementConnection = true
)

// Check if approved
if (secureStorage.isKnownBleDeviceApproved(deviceId, address)) {
    // Allow connection
}

// Prune old devices
secureStorage.pruneKnownBleDevices(maxAgeDays = 30, maxDevices = 30)
```

### Generic methods (avoid when possible)

```kotlin
// Only use if no typed method exists
secureStorage.putString("custom_key", "value")
val value = secureStorage.getString("custom_key")

secureStorage.putBoolean("custom_flag", true)
val flag = secureStorage.getBoolean("custom_flag", defaultValue = false)
```

## Related Files

- `features/payment/domain/model/TpvSettings.kt` - TpvSettings data class
- `features/authentication/domain/models/StaffRole.kt` - StaffRole enum
- `features/authentication/domain/models/VenueStatus.kt` - VenueStatus enum
- `features/modules/data/dto/VenueModuleDto.kt` - Module cache model
- `core/di/StorageModule.kt` - Hilt DI for SecureStorage singleton
