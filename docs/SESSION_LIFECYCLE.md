# Session Lifecycle

Three-stage authentication flow: Terminal Activation → User Login → Venue Assignment.

## Stages Overview

| Stage | Entry Point | SecureStorage Keys Set | Navigation |
|-------|-------------|------------------------|------------|
| 1. Terminal Activation | `ActivationViewModel` | `venueId`, `venueName`, `venueLogo` | Activation → Login |
| 2. User Login | `LoginViewModel` | `accessToken`, `refreshToken`, `userId`, `staffId`, `staffName`, `staffRole`, `venueStatus` | Login → Home |
| 3. Venue Assignment | (via SecureStorage) | N/A (venueId already set) | N/A |

**Pattern**: Square/Toast POS three-stage activation model.

## 1. Terminal Activation Flow

**Entry**: ActivationScreen with 6-character activation code or QR scan.

### Steps

1. User enters activation code (e.g., `A3F9K2`) or scans QR
2. `ActivationViewModel.activate(code)` calls `ActivateTerminalUseCase`
3. Backend validates code (expiration, attempts, terminal lock)
4. Backend returns `venueId` + `venueName`
5. **CRITICAL**: Fetch terminal config BEFORE navigation
   - Loads merchant accounts with `merchantAccountId` (backend CUID)
   - Without CUID, payments fail with 400 error
6. Store in SecureStorage:
   ```kotlin
   secureStorage.saveVenueId(venueId)
   secureStorage.saveVenueName(venueName)
   secureStorage.saveVenueLogo(logoUrl)
   ```
7. Navigate to Login

### QR Code Format

**Simplified (recommended)**:
```json
{
  "t": "a",        // type: avoqado_activation
  "c": "A3F9K2"    // code
}
```

**Legacy (backward compatible)**:
```json
{
  "type": "avoqado_activation",
  "code": "A3F9K2",
  "venueId": "uuid",
  "venueName": "Venue Name",
  "serialNumber": "AVQD-...",
  "expiresAt": "2025-01-20T..."
}
```

### Error States

| HTTP | Error | User Message |
|------|-------|--------------|
| 400 | Code expired | "Código expirado. Solicite uno nuevo." |
| 400 | Already activated | "Terminal ya activado." |
| 401 | Invalid code | "Código incorrecto. X intentos restantes." |
| 401 | Terminal locked | "Terminal bloqueado. Contacte soporte." |
| 404 | Terminal not registered | "Terminal no registrado." |

### Auto-Retry Pattern

`checkAlreadyActivatedOnBackend()` called every 10 seconds from ActivationScreen.

**Why needed**: User may arrive at ActivationScreen because server was down when app checked activation status. Auto-retry navigates to Login when server comes back.

### Config Error State

Terminal activated but config fetch failed → Block navigation until retry succeeds.

**Impact**: Payments fail without merchant account CUIDs.

**Resolution**: User taps "Reintentar" → `retryConfigFetch()`.

## 2. User Login Flow

**Entry**: LoginScreen with 4-10 digit PIN or Master TOTP.

### PIN Login Steps

1. User enters PIN
2. `LoginViewModel.loginWithPin(pin, venueId)` calls `AuthRepository.loginWithPin()`
3. Backend validates PIN + venue status
4. Backend returns:
   ```json
   {
     "accessToken": "jwt...",
     "refreshToken": "jwt...",
     "staff": { "id": "uuid", "displayName": "...", "role": "WAITER" },
     "venue": { "status": "ACTIVE" },
     "isMasterLogin": false
   }
   ```
5. Store in SecureStorage:
   ```kotlin
   secureStorage.saveToken(accessToken)
   secureStorage.saveRefreshToken(refreshToken)
   secureStorage.saveUserId(staff.id)
   secureStorage.saveStaffId(staff.id)
   secureStorage.saveStaffName(staff.displayName)
   secureStorage.saveStaffRole(staff.role)
   secureStorage.saveVenueStatus(venue.status)
   ```
6. Connect Socket.IO with JWT token + join venue room
7. **Clock-in check** (if `TpvSettings.requireClockInToLogin` enabled):
   - Has active clock-in? → Navigate to Home
   - No clock-in? → Show "Clock-in required" → Navigate to Timeclock
   - On break? → Show "End break first" error
8. Navigate to Home

### Master TOTP Login

**Purpose**: Bypass ALL venue rules (clock-in, checkout, venue status).

**Detection**: Backend sets `isMasterLogin: true` in response.

**Flow**: Same as PIN login, but skips clock-in check and venue status validation.

**Use case**: Avoqado support accessing suspended venues, debugging without staff account.

### Socket.IO Connection

```kotlin
socketManager.connect(
    url = socketUrl,              // BLUMON_ENV == "PROD" ? BuildConfig.SOCKET_URL : BuildConfig.SOCKET_URL_DEV
    token = jwtToken,             // From AuthResponse
    terminalId = serialNumber,    // AVQD-{androidId}
    reconnection = true,
    reconnectionAttempts = 5
)

// Wait for connection, then join venue room
socketManager.joinVenueRoom(venueId)
```

### Login Error States

| Error | Detection | Action |
|-------|-----------|--------|
| Terminal not activated | `TERMINAL_NOT_ACTIVATED` | Navigate to Activation |
| Venue suspended | `venue.status != ACTIVE` | Show error, block login |
| Staff inactive | `no longer active` | Show error |
| Incorrect PIN | HTTP 401 | Show backend message (includes remaining attempts) |
| Network error | Exception | Show "Verifique su internet" |

### Clock-in Integration

If `TpvSettings.requireClockInToLogin == true`:

```kotlin
checkClockInStatus(authResponse, pin)
  ↓
TimeEntryRepository.findActiveEntryForStaff(venueId, staffId)
  ↓
CLOCKED_IN → Allow access
NO_ENTRY → Navigate to Timeclock
ON_BREAK → Block access (must end break)
```

## 3. Venue Assignment

**Implicit**: venueId set during activation, persists across logins.

**Usage**: TenantInterceptor adds `X-Venue-Id` header to all requests.

**Backend validation**: Ensures user can only access data for assigned venue.

## SecureStorage Keys Reference

| Key | Set During | Type | Usage |
|-----|------------|------|-------|
| `venueId` | Activation | String | Tenant isolation, API header |
| `venueName` | Activation | String | Display in UI |
| `venueLogo` | Activation | String | Display in UI |
| `accessToken` | Login | String | JWT for API auth |
| `refreshToken` | Login | String | Refresh expired access token |
| `userId` | Login | String | User identity |
| `staffId` | Login | String | Staff identity |
| `staffName` | Login | String | Display in UI |
| `staffRole` | Login | String | WAITER, CASHIER, MANAGER, ADMIN |
| `venueStatus` | Login | String | ACTIVE, SUSPENDED, CLOSED |

**Path**: `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt`

**Implementation**: EncryptedSharedPreferences (Android Keystore).

## Session Expiry & Token Refresh

### Automatic Token Refresh (401)

1. API request fails with HTTP 401
2. `TokenAuthenticator.authenticate()` called
3. `SessionManager.notifySessionVerifying()` → Show loading overlay
4. Attempt `authRepository.refreshAccessToken()`
5. Success → Retry request with new token → Hide overlay
6. Failure → `clearSession()` + navigate to Login

**Thread-safe**: Only ONE refresh even if 5 requests fail simultaneously.

**Excluded endpoints**: `/auth/login`, `/activate`, `/refresh` (see NETWORK_INTERCEPTORS.md).

### Manual Logout

```kotlin
secureStorage.clearSession()
  ↓
Clears ALL keys:
- venueId (KEPT — terminal stays activated)
- accessToken, refreshToken
- userId, staffId, staffName, staffRole
- venueStatus
  ↓
Navigate to Login
```

**Logout vs Full Reset**:
- Logout: Clears user session, keeps venueId → Navigate to Login
- Full Reset: Clears ALL including venueId → Navigate to Activation

### Terminal Deactivation (Remote)

Backend reports `terminal.status = RETIRED` in heartbeat response:

```kotlin
SessionManager.notifyTerminalDeactivated()
  ↓
secureStorage.clearSession()  // Including venueId
  ↓
Navigate to Activation
```

**Use case**: Stolen or unauthorized terminals remotely disabled by admin.

## Session State Events

`SessionManager` emits events observed by AppNavigation:

| Event | Trigger | Action |
|-------|---------|--------|
| `SessionEvent.Expired` | Token refresh failed | Navigate to Login |
| `SessionEvent.TokenRefreshed` | Token refreshed successfully | Socket.IO reconnects |
| `SessionEvent.TerminalDeactivated` | Backend reports RETIRED | Navigate to Activation, clear ALL |

**Pattern**: Events (one-time) not state (continuous). Uses `SharedFlow` with `replay = 0`.

## Adding a New Session Field

1. Add getter/setter to `SecureStorage.kt`:
   ```kotlin
   fun saveCustomField(value: String) {
       prefs.edit().putString("custom_field", value).apply()
   }

   fun getCustomField(): String? {
       return prefs.getString("custom_field", null)
   }
   ```

2. Set during Login or Activation:
   ```kotlin
   secureStorage.saveCustomField(value)
   ```

3. Clear during logout:
   ```kotlin
   fun clearSession() {
       prefs.edit()
           .remove("custom_field")  // Add this
           .apply()
   }
   ```

4. Add to relevant ViewModel
