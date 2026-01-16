# Master TOTP Login - Emergency SUPERADMIN Access

## Overview

Master TOTP Login is an **emergency access system** that allows SUPERADMIN users to access ANY TPV terminal using a Google Authenticator 8-digit TOTP code. This feature is designed for:

- Emergency support access when staff is unavailable
- Technical troubleshooting on production terminals
- Bypassing venue-specific restrictions for administrative tasks

---

## How It Works

### 1. Dashboard Setup (SUPERADMIN Only)

Location: `superadmin/master-totp` in avoqado-web-dashboard

1. SUPERADMIN navigates to Master TOTP setup page
2. Scans QR code with Google Authenticator app
3. The app generates **8-digit codes** (60-second period)

**Configuration:**
- **Digits:** 8 (not the standard 6)
- **Period:** 60 seconds
- **Algorithm:** SHA-1 (Google Authenticator default)

### 2. TPV Login Flow

When a user enters a PIN on the TPV login screen:

```
PIN Length Detection:
├── 4-6 digits → Regular staff PIN login
└── 8 digits   → Master TOTP login (detected automatically)
```

**Code location:** `AuthRepository.loginWithPin()` in `avoqado-tpv`

```kotlin
// Detection logic
if (pin.length == 8 && pin.all { it.isDigit() }) {
    Timber.d("🔐 Detected 8-digit code → Master TOTP login")
    return masterLogin(totpCode = pin, venueId = venueId)
}
```

### 3. Backend Validation

**Endpoint:** `POST /tpv/venues/:venueId/auth/master`

The backend:
1. Validates the TOTP code against the master secret
2. Returns a special `AuthResponse` with:
   - `isMasterLogin: true` flag
   - `role: "SUPERADMIN"`
   - Synthetic staff object ("Master Admin")

**Code location:** `avoqado-server/src/services/tpv/auth.tpv.service.ts` → `masterTotpLogin()`

---

## The `isMasterLogin` Flag

### What It Is

A boolean flag (`isMasterLogin: true`) included in the auth response for master TOTP sessions. This flag:

1. **Persists in SecureStorage** throughout the session
2. **Bypasses venue-specific rules** (see below)
3. **Is cleared on logout**

### Data Flow

```
Backend AuthResponse
    ↓
AuthResponseDto.isMasterLogin (TPV DTO)
    ↓
AuthResponse.isMasterLogin (Domain Model)
    ↓
SecureStorage.saveMasterLogin() (Persistence)
    ↓
AuthRepository.isMasterSession() (Runtime Check)
```

### Files Modified (TPV)

| File | Change |
|------|--------|
| `AuthDto.kt` | Added `isMasterLogin` field to `AuthResponseDto` |
| `AuthModels.kt` | Added `isMasterLogin` field to `AuthResponse` domain model |
| `SecureStorage.kt` | Added `saveMasterLogin()`, `isMasterLogin()`, clear on logout |
| `AuthRepository.kt` | Added `isMasterSession()` helper method |
| `LoginViewModel.kt` | Added bypass check for master sessions |

---

## Rules Bypassed by Master Sessions

When `isMasterLogin == true`, the following venue rules are **bypassed**:

### Currently Implemented

| Rule | Normal Behavior | Master Session Behavior |
|------|-----------------|-------------------------|
| **Clock-in requirement** | Staff must clock in before accessing system | Bypassed - direct access to Home |

### Future Candidates (Not Yet Implemented)

| Rule | Description |
|------|-------------|
| Checkout timeout | Auto-logout after inactivity |
| Session PIN re-entry | Require PIN for sensitive operations |
| Operating hours restriction | Block login outside business hours |
| Break time enforcement | Force break after X hours worked |

---

## Implementation Details

### LoginViewModel Clock-in Bypass

```kotlin
// In LoginViewModel.loginWithPin()
val isMasterSession = result.data.isMasterLogin
if (isMasterSession) {
    Timber.i("🔐 Master session detected - bypassing all venue restrictions")
    LoginState.Success(result.data)
} else {
    // Normal flow: check clock-in requirements, etc.
    val clockInRequired = tpvSettingsRepository.getCurrentSettings().requireClockInToLogin
    // ...
}
```

### Checking Master Session at Runtime

```kotlin
// Anywhere in the app
val isMaster = authRepository.isMasterSession()
if (isMaster) {
    // Skip restriction
} else {
    // Apply normal rules
}
```

Or using SecureStorage directly:

```kotlin
val isMaster = secureStorage.isMasterLogin()
```

---

## Security Considerations

### Audit Trail

All master logins are audited on the backend:
- IP address
- Terminal serial number
- Timestamp
- Venue accessed

### Access Control

- Only users with `SUPERADMIN` role can set up Master TOTP
- The TOTP secret is stored securely in the database
- 8-digit codes with 60-second period provide sufficient entropy

### Session Isolation

- Master session flag is stored per-device in EncryptedSharedPreferences
- Flag is cleared on logout
- No cross-device session sharing

---

## Testing

### Manual Testing

1. Set up Master TOTP in dashboard (requires SUPERADMIN account)
2. Open Google Authenticator, get current 8-digit code
3. On TPV login screen, enter the 8-digit code
4. Verify:
   - Login succeeds
   - Logs show `🔐 Master session detected - bypassing all venue restrictions`
   - Clock-in requirement is skipped (if enabled)

### Expected Logs

```
AuthRepository: 🔐 Detected 8-digit code → Master TOTP login
AuthRepository: 🔐 Master TOTP login for venue: venue_xxx
LoginViewModel: ✅ Login successful: Master Admin
LoginViewModel: 🔐 Master session detected - bypassing all venue restrictions
```

---

## Troubleshooting

### Error: HTTP 404 on master login

**Cause:** Endpoint path mismatch

**Solution:** Verify `ApiService.kt` uses correct path:
```kotlin
@POST("tpv/venues/{venueId}/auth/master")  // Correct
// NOT: @POST("tpv/venues/{venueId}/master-login")  // Wrong
```

### Error: Clock-in still required after master login

**Cause:** `isMasterLogin` not being saved or checked

**Debug:**
1. Check backend response includes `isMasterLogin: true`
2. Verify `SecureStorage.saveMasterLogin()` is called
3. Check `LoginViewModel` bypass logic is reached

### Error: TOTP code rejected

**Cause:** Time sync issue or wrong code format

**Solution:**
- Ensure device time is accurate (auto-sync)
- Verify code is exactly 8 digits
- Wait for next code cycle (60 seconds)

---

## Related Documentation

- **Backend:** `avoqado-server/docs/MASTER_TOTP_LOGIN.md` (if exists)
- **Dashboard:** Setup UI at `/superadmin/master-totp`
- **Permissions:** See `CLAUDE.md` section 9 (Permission System)

---

## Changelog

| Date | Change |
|------|--------|
| 2025-01-16 | Initial implementation of venue rule bypass |
| 2025-01-16 | Added `isMasterLogin` flag to TPV auth flow |
| 2025-01-16 | Implemented clock-in bypass for master sessions |

---

**Last Updated:** 2025-01-16
**Maintainer:** Development Team
