# Device Identification & Terminal Activation

How the TPV app identifies physical devices, activates terminals against the backend, and uses device identity across features.

---

## Architecture Overview

```
Hardware Serial (Build.getSerial())
        |
        v
DeviceInfoManager.getSerialNumber()  -->  "AVQD-{SERIAL}"
        |
        +---> ActivateTerminalUseCase  -->  POST /tpv/activate
        +---> HeartbeatWorker          -->  POST /tpv/heartbeat
        +---> AuthRepository           -->  POST /venues/:id/auth/pin-login
        +---> TerminalConfigRepository -->  GET /tpv/terminals/:serial/config
        +---> CommandAck               -->  POST /tpv/commands/ack
        +---> checkActivationStatus    -->  GET /tpv/terminals/:serial/activation-status
```

---

## Key Classes

| Class | Path | Responsibility |
|-------|------|----------------|
| `DeviceInfoManager` | `core/util/DeviceInfoManager.kt` | Generate `AVQD-{serial}`, check activation status with backend, get device metadata |
| `TerminalConfig` | `core/domain/TerminalConfig.kt` | Runtime-mutable serial for multi-merchant SDK switching (Blumon) |
| `SecureStorage` | `core/data/local/SecureStorage.kt` | Persist `serial_number`, `venue_id`, `terminal_id` in EncryptedSharedPreferences |
| `ActivateTerminalUseCase` | `core/domain/usecase/ActivateTerminalUseCase.kt` | Validate + delegate activation to repository |
| `ActivationRepositoryImpl` | `core/data/repository/ActivationRepositoryImpl.kt` | Call API, store venueId/serialNumber on success |
| `ActivationViewModel` | `features/activation/presentation/ActivationViewModel.kt` | UI state machine for activation screen (manual code + QR scan) |
| `HeartbeatWorker` | `core/data/workers/HeartbeatWorker.kt` | Send serial as `terminalId` every 30s |
| `MultiMerchantSDKManager` | `features/payment/data/MultiMerchantSDKManager.kt` | Switch `TerminalConfig.serialNumber` for multi-merchant routing |
| `BlumonAuthManager` | `features/payment/data/BlumonAuthManager.kt` | OAuth + DUKPT key download using current serial |

---

## Device ID Generation

**Source:** `DeviceInfoManager.getSerialNumber()` in `core/util/DeviceInfoManager.kt`

```kotlin
fun getSerialNumber(): String {
    val hardwareSerial = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Build.getSerial()  // Requires READ_PHONE_STATE
    } else {
        @Suppress("DEPRECATION")
        Build.SERIAL
    }
    return "AVQD-${hardwareSerial.uppercase()}"
}
```

**Format:** `AVQD-{HARDWARE_SERIAL}` (e.g., `AVQD-2841548417`)

**Properties of the hardware serial:**
- Persists across app reinstall, factory reset, and OS updates
- Deterministic: same device always produces the same ID
- Requires `READ_PHONE_STATE` permission on Android 8+
- No ANDROID_ID fallback -- throws `SecurityException` if permission denied

**Permission enforcement in `MainActivity`:**

```kotlin
// Android 8+: mandatory permission check on launch
private fun checkAndRequestPhoneStatePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Request READ_PHONE_STATE, block app if denied
    } else {
        // Android 7-: Build.SERIAL requires no permission
    }
}
```

If denied, `PermissionDeniedScreen` blocks the entire app. No fallback, no workaround.

**AndroidManifest declaration:**
```xml
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

---

## DeviceInfo Data Class

`DeviceInfoManager.getDeviceInfo()` returns:

| Field | Source | Example |
|-------|--------|---------|
| `serialNumber` | `AVQD-{Build.getSerial()}` | `AVQD-2841548417` |
| `deviceModel` | `Build.MODEL` | `A910S` |
| `androidVersion` | `Build.VERSION.RELEASE` | `9` |
| `deviceManufacturer` | `Build.MANUFACTURER` | `PAX` |
| `deviceBrand` | `Build.BRAND` | `PAX` |

---

## Terminal Activation Flow

### Preconditions
- `READ_PHONE_STATE` permission granted
- Device not yet activated (no `serial_number` in SecureStorage)

### Sequence

```
1. SplashScreen
   |-- DeviceInfoManager.isDeviceActivated()  (checks SecureStorage for venueId)
   |-- DeviceInfoManager.checkActivationStatusWithBackend()
   |     GET /tpv/terminals/{serial}/activation-status?environment=PROD|SAND
   |
   |-- If not activated --> ActivationScreen
   |-- If activated --> LoginScreen

2. ActivationScreen
   |-- Displays device serial (e.g., "AVQD-2841548417")
   |-- User enters 6-char alphanumeric code (manual) OR scans QR
   |
   |-- ActivationViewModel.activate(code)
   |     |-- ActivateTerminalUseCase(serialNumber, code)
   |     |     |-- Validates: code.length == 6, matches [A-Z0-9]{6}
   |     |     |-- ActivationRepositoryImpl.activateTerminal()
   |     |           POST /tpv/activate
   |     |           Body: { serialNumber, activationCode, environment }
   |     |
   |     |-- On 200: saves venueId, venueSlug, serialNumber to SecureStorage
   |     |-- Fetches terminal config (merchant accounts)
   |     |-- Navigate to LoginScreen

3. LoginScreen
   |-- AuthRepository.loginWithPin(pin, venueId)
   |     |-- Includes serialNumber from SecureStorage in request
   |     |-- Backend validates PIN + serial + venue combo
```

### QR Activation

`ActivationViewModel.processQrActivation()` supports two JSON formats:

**Simplified (recommended):**
```json
{ "t": "a", "c": "A3F9K2" }
```

**Legacy:**
```json
{
  "type": "avoqado_activation",
  "code": "A3F9K2",
  "serialNumber": "AVQD-...",
  "venueId": "uuid",
  "expiresAt": "2025-01-20T..."
}
```

Legacy format validates `serialNumber` match (prevents activating wrong device).

### Activation Error Mapping

| HTTP Code | Backend Message | Spanish User Message |
|-----------|----------------|---------------------|
| 400 | `expired` | Codigo de activacion expirado |
| 400 | `already activated` | Terminal ya activado |
| 401 | `Invalid activation code. X attempts remaining` | Codigo incorrecto. X intento(s) restantes |
| 401 | `locked` | Terminal bloqueado por demasiados intentos |
| 404 | Terminal not registered | Terminal no registrado |

---

## Activation Status Check (Backend Verification)

`DeviceInfoManager.checkActivationStatusWithBackend()` calls:

```
GET /tpv/terminals/{serialNumber}/activation-status?environment=PROD|SAND
```

**Response:** `ActivationStatusResponse`

| Field | Type | Description |
|-------|------|-------------|
| `isActivated` | Boolean | `activatedAt !== null` on backend |
| `status` | String | `ACTIVE`, `INACTIVE`, `MAINTENANCE`, `RETIRED` |
| `venueId` | String? | Venue UUID if activated |
| `venueName` | String? | Human-readable name |
| `venueSlug` | String? | URL-friendly slug |
| `activatedAt` | String? | ISO 8601 timestamp |

**Security behavior by status:**

| Status | Action |
|--------|--------|
| `RETIRED` | `secureStorage.clearAll()` -- wipe all local data (stolen device) |
| `!isActivated` | Preserve venueId for recovery (temporary server issue) |
| `isActivated` + local cache empty | Restore venueId from backend (reinstall scenario) |
| Network error | Trust local venueId (offline-first) |

---

## SecureStorage Keys for Device Identity

All stored in `EncryptedSharedPreferences` (AES256-GCM):

| Key | Set By | Cleared By | Purpose |
|-----|--------|------------|---------|
| `serial_number` | `ActivationRepositoryImpl` on activation | `clearAll()` | Device serial with AVQD- prefix |
| `venue_id` | Activation + login | `clearAll()` | Tenant isolation |
| `venue_slug` | Activation + login | `clearAll()` | Firebase Storage paths |
| `terminal_id` | Activation (server-assigned CUID) | `clearAll()` | Server-side terminal ID |

**Session vs Device data on logout:**
- `clearSession()` clears tokens, staffId, permissions
- `clearSession()` does NOT clear `venue_id`, `venue_slug`, `serial_number` -- these are device-level

---

## Heartbeat: Device Identity in Health Monitoring

`HeartbeatWorker.buildHeartbeat()` uses the serial as `terminalId`:

```kotlin
val terminalId = deviceInfoManager.getSerialNumber()  // "AVQD-2841548417"
```

Sent every 30 seconds via `POST /tpv/heartbeat`:

```json
{
  "terminalId": "AVQD-2841548417",
  "status": "ACTIVE",
  "version": "1.5.0",
  "systemInfo": { "batteryLevel": 85, "memory": {...}, ... }
}
```

**Heartbeat lifecycle:** Starts on app launch if activated (not on login). Runs independently of user session. Stops only on deactivation or `RETIRED` status.

**Security via heartbeat:**
- 10 consecutive HTTP 404s --> `secureStorage.clearAll()` + stop heartbeat (terminal deleted from backend)
- `RETIRED` in error message --> immediate wipe (stolen/revoked device)
- Command ACKs include `terminalId` for ownership validation

---

## Multi-Merchant Serial Switching (Blumon SDK)

**Problem:** One physical PAX terminal, multiple merchant accounts. Blumon SDK ties credentials to serial number.

**TerminalConfig** (`core/domain/TerminalConfig.kt`) holds runtime-mutable state:

```kotlin
object TerminalConfig {
    private const val DEFAULT_SERIAL = "2841548417"  // Sandbox default
    var serialNumber: String = DEFAULT_SERIAL
        private set

    fun initialize(serial: String, brand: String, model: String) { ... }
    fun updateSerial(newSerial: String) { this.serialNumber = newSerial }
    fun reset() { serialNumber = DEFAULT_SERIAL }
}
```

**`MultiMerchantSDKManager.switchMerchant()`:**
1. Update `TerminalConfig.serialNumber` to target merchant's serial
2. Call `InitializationManager.forceReinitialize()` (OAuth + DUKPT keys for new serial)
3. ~3-5 seconds for switch; ~0ms if already on target merchant

**Two serial number concepts:**

| Concept | Source | Example | Purpose |
|---------|--------|---------|---------|
| Device serial | `DeviceInfoManager.getSerialNumber()` | `AVQD-2841548417` | Activation, heartbeat, auth, backend identity |
| Blumon serial | `TerminalConfig.serialNumber` | `2841548417` (no prefix) | Blumon SDK auth, DUKPT keys, payment processing |

---

## PAX Terminal Specifics

- **Default device:** PAX A910S (1GB RAM, Android 7-9)
- `Build.getSerial()` returns PAX hardware serial (e.g., `2841548417`)
- `Build.MANUFACTURER` = `PAX`, `Build.BRAND` = `PAX`, `Build.MODEL` = `A910S`
- Volume buttons intercepted for barcode scanner / camera capture (standard Android KeyEvent, no PAX SDK)
- PAX Neptune API available for EMV operations (separate from device identification)

---

## Auth Flow: Serial in Login

`AuthRepository.loginWithPin()` includes serial number:

```kotlin
val serialNumber = secureStorage.getSerialNumber()
    ?: return Result.Error("El dispositivo debe activarse primero")

val request = PinLoginRequest(pin = pin, serialNumber = serialNumber)
apiService.loginWithPin(venueId, request)
```

Backend uses serial to:
1. Verify terminal is registered and activated for this venue
2. Associate staff session with specific physical device
3. Audit log which terminal processed each action

Master TOTP login (8-digit code) also includes `serialNumber` in request.
