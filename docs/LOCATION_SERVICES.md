# Location Services (GPS, Cell Tower, WiFi)

Technical reference for the location subsystem used by attendance verification (clock-in/out GPS capture). Covers the multi-strategy approach required for PAX A80 terminals that lack Google Play Services and typically operate indoors.

**Related:** [Attendance Verification](ATTENDANCE_VERIFICATION.md) covers the full photo + GPS attendance flow. This doc covers only the location acquisition layer.

---

## Architecture Overview

```
TimeclockViewModel                LocationService                  CellLocationApiImpl
┌──────────────────┐             ┌────────────────────┐           ┌──────────────────┐
│ performClockIn() │──────────>  │ getCurrentLocation()│           │                  │
│ performClockOut()│             │                    │           │                  │
│                  │             │ Priority 1:        │──Cell+WiFi─>│ POST /tpv/       │
│ captureGps =     │             │  Network Location  │<──coords──│ geolocation/     │
│  settings        │             │  (Cell ID + WiFi)  │           │ cell-towers      │
│  .requireClock   │             │                    │           │ (Google Geoloc.) │
│  InPhoto         │             │ Priority 2:        │           └──────────────────┘
│                  │             │  GPS fallback      │
│ location =       │             │  - FusedLocation   │
│  locationService │             │  - LocationManager │
│  .getCurrent     │             └────────────────────┘
│  Location(45000) │
└──────────────────┘
```

GPS capture is **implicit** -- tied to the `requireClockInPhoto` / `requireClockOutPhoto` settings. There is no separate GPS toggle. When photo verification is enabled, GPS is automatically captured.

---

## Key Classes and Responsibilities

| Class | Path | Role |
|-------|------|------|
| `LocationService` | `core/location/LocationService.kt` | Singleton. Orchestrates multi-strategy location acquisition. Injected via Hilt. |
| `CellLocationApi` | `core/location/LocationService.kt` | Interface for cell tower + WiFi geolocation API. |
| `CellLocationApiImpl` | `core/location/CellLocationApiImpl.kt` | Implementation. Calls backend `POST /tpv/geolocation/cell-towers`. |
| `LocationResult` | `core/location/LocationService.kt` | Data class: `latitude: Double`, `longitude: Double`, `accuracy: Float`. |
| `CellTowerInfo` | `core/location/LocationService.kt` | Data class for cell tower params (radioType, MCC, MNC, LAC, CID). |
| `WifiAccessPointInfo` | `core/location/LocationService.kt` | Data class for WiFi AP params (macAddress, signalStrength, channel). |
| `TimeclockViewModel` | `features/timeclock/presentation/TimeclockViewModel.kt` | Consumer. Calls `locationService.getCurrentLocation()` during clock-in/out. |
| `AppModule` | `core/di/AppModule.kt` | Hilt DI. Provides `CellLocationApiImpl` as `CellLocationApi`. |

---

## Location Acquisition Strategy

### Priority Order (Optimized for PAX Indoor Use)

| Priority | Method | Provider | Works Indoors | Accuracy | Latency |
|----------|--------|----------|---------------|----------|---------|
| **1** | Network Location (Cell + WiFi) | Backend API (Google Geolocation) | YES | 20-1000m | ~1-3s |
| **2a** | Fused Location (last known) | Google Play Services | N/A (cached) | Varies | Instant |
| **2b** | Fused Location (fresh) | Google Play Services | Partial | 5-100m | 1-30s |
| **3** | Android LocationManager | GPS_PROVIDER + NETWORK_PROVIDER | GPS: NO, Network: YES | 5-100m | 10-60s |

**Why Network Location is Priority 1:**
- PAX A80 has no Google Play Services (Fused will always fail)
- Clock-in happens indoors (GPS satellite fix is impossible)
- Cell + WiFi is instant (no cold start delay)
- Accuracy of 20-50m with WiFi is sufficient for "at store" verification

### Fallback Chain in Code

```kotlin
// LocationService.getCurrentLocation()
suspend fun getCurrentLocation(timeoutMs: Long = 20_000): LocationResult? {
    return withTimeoutOrNull(timeoutMs) {
        // 1. Network Location (Cell ID + WiFi) - Primary for PAX
        getLocationFromCellId()?.let { return@withTimeoutOrNull it }

        // 2. FusedLocationProvider (if Google Play available - unlikely on PAX)
        if (isGooglePlayServicesAvailable()) {
            getLastKnownLocation()?.let { return@withTimeoutOrNull it }
            getFreshLocation()?.let { return@withTimeoutOrNull it }
        }

        // 3. Android LocationManager (GPS + Network)
        getLocationFromAndroidManager(timeoutMs / 2)?.let { return@withTimeoutOrNull it }

        null // All strategies failed
    }
}
```

---

## PAX A80 Workarounds

### Problem: No Google Play Services

PAX A80 terminals run a stripped Android without Google Play Services. `FusedLocationProviderClient` will silently fail.

**Detection:**
```kotlin
private fun isGooglePlayServicesAvailable(): Boolean {
    return try {
        val resultCode = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        resultCode == ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }
}
```

This always returns `false` on PAX, so the Fused path is skipped entirely.

### Problem: Phantom "fused" Provider

PAX's `LocationManager.getProviders(true)` returns a phantom `"fused"` provider that doesn't actually work. Requesting updates from it hangs indefinitely.

**Fix:** Explicitly check only real providers:

```kotlin
// CRITICAL: Use explicit providers, NOT getProviders(true)
val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
```

### Problem: GPS Cold Start (30-60 seconds)

PAX devices rarely have a warm GPS cache. First fix requires downloading satellite almanac data.

**Mitigation:** The 45-second timeout in `TimeclockViewModel` accommodates this:

```kotlin
try {
    kotlinx.coroutines.withTimeout(45000) {
        location = locationService.getCurrentLocation(timeoutMs = 45000)
    }
} catch (e: Exception) {
    Timber.w("GPS capture timed out (45s), proceeding without location")
}
```

If all strategies timeout, clock-in/out proceeds **without** location (non-blocking).

### Problem: Indoor Operation

PAX terminals sit on store counters. GPS satellites are unreachable.

**Solution:** Network Location (Priority 1) uses cell towers + WiFi, which work indoors.

---

## Network Location (Cell + WiFi) Deep Dive

### Data Collection

**Cell Towers** (`getCellTowerInfo()`):
- Reads from `TelephonyManager.allCellInfo`
- Only uses **registered** (connected) cells (`cellInfo.isRegistered`)
- Supports GSM, WCDMA (3G), LTE (4G)
- Extracts: MCC, MNC, LAC/TAC, CID
- Handles API level differences (P+ uses `mccString`, pre-P uses deprecated `mcc`)
- Filters out invalid values (`Int.MAX_VALUE`)

**WiFi Access Points** (`getWifiAccessPoints()`):
- Reads from `WifiManager.scanResults` (cached, no new scan triggered)
- Extracts: BSSID (MAC address), signal strength (dBm), channel number
- Limits to 10 APs (sufficient for accuracy)
- Converts frequency (MHz) to channel number:
  - 2.4 GHz: `(freq - 2412) / 5 + 1` (channels 1-14)
  - 5 GHz: `(freq - 5170) / 5 + 34`

### API Call

```
POST /api/v1/tpv/geolocation/cell-towers

Request:
{
  "cellTowers": [{
    "radioType": "lte",
    "mobileCountryCode": 334,
    "mobileNetworkCode": 20,
    "locationAreaCode": 12345,
    "cellId": 67890
  }],
  "wifiAccessPoints": [{
    "macAddress": "00:11:22:33:44:55",
    "signalStrength": -70,
    "channel": 6
  }]
}

Response:
{
  "latitude": 19.4326,
  "longitude": -99.1332,
  "accuracy": 35.0
}
```

Backend proxies this to Google Geolocation API (API key stays server-side).

### Accuracy by Signal Source

| Source | Typical Accuracy | When Used |
|--------|-----------------|-----------|
| Cell towers only | 100-1000m | WiFi disabled or no scan results |
| Cell + WiFi | 20-50m | WiFi enabled with nearby APs |
| WiFi only | 20-50m | No SIM card but WiFi available |

---

## Timeouts and Thresholds

| Parameter | Value | Location | Notes |
|-----------|-------|----------|-------|
| Overall location timeout | 20,000ms | `LocationService.getCurrentLocation()` default | Overridden by caller |
| Clock-in/out GPS timeout | 45,000ms | `TimeclockViewModel.performClockIn/Out()` | Accommodates GPS cold start |
| GPS fallback timeout | `timeoutMs / 2` | `getLocationFromAndroidManager()` | Half of remaining time budget |
| Last known location max age | 5 minutes | `getLastKnownLocation()` and `getLocationFromAndroidManager()` | Rejects stale cached locations |
| WiFi AP limit | 10 | `getWifiAccessPoints()` | More than enough for accuracy |
| LocationManager minTime | 0ms | `requestLocationUpdates()` | No delay between updates |
| LocationManager minDistance | 0m | `requestLocationUpdates()` | Any movement triggers update |

---

## Permissions

### Manifest Declarations

```xml
<!-- GPS -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-feature android:name="android.hardware.location.gps" android:required="false" />

<!-- WiFi scanning for geolocation -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

`android.hardware.location.gps` is `required="false"` because network location works without GPS hardware.

### Runtime Permission Check

`LocationService.hasLocationPermission()` accepts either `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`:

```kotlin
fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
```

### Permission Request Flow

Permissions are requested in `CameraPreviewScreen.kt` (the same screen that handles photo capture for attendance). Camera + Location permissions are requested together via `ActivityResultContracts.RequestMultiplePermissions()`.

On PAX A80 terminals, permissions are typically pre-granted by the device provisioning system and do not show a runtime dialog.

---

## Error Handling and Fallback Behavior

| Scenario | Behavior | User Impact |
|----------|----------|-------------|
| No location permission | `getCurrentLocation()` returns `null` immediately | Clock-in proceeds without GPS |
| Location services disabled | GPS fallback skipped, network location still attempted | May get cell/WiFi location |
| No SIM card | Cell tower info empty | WiFi-only geolocation or null |
| WiFi disabled | WiFi APs empty | Cell-only geolocation (~100-1000m) |
| No SIM + No WiFi | Network location returns null | Falls through to GPS (unlikely indoors) |
| All providers fail | `getCurrentLocation()` returns `null` | Clock-in/out proceeds without GPS |
| Backend geolocation API error | `CellLocationApiImpl` returns `null` | Falls through to GPS fallback |
| 45s timeout exceeded | `withTimeout` throws, caught in ViewModel | Clock-in/out proceeds without GPS |

**Critical design decision:** Location capture is **never blocking**. If all strategies fail, clock-in/out succeeds without GPS coordinates. The `latitude`, `longitude`, and `accuracy` fields in the API request are nullable.

---

## API Contract (Clock-In/Out with GPS)

### Clock-In Request DTO

```kotlin
data class ClockInRequestDto(
    val staffId: String,
    val pin: String,
    val checkInPhotoUrl: String? = null,
    @SerializedName("latitude")      // Backend expects "latitude" not "clockInLatitude"
    val clockInLatitude: Double? = null,
    @SerializedName("longitude")
    val clockInLongitude: Double? = null,
    @SerializedName("accuracy")
    val clockInAccuracy: Float? = null
)
```

### Clock-Out Request DTO

Same structure with `checkOutPhotoUrl`, `clockOutLatitude`, `clockOutLongitude`, `clockOutAccuracy` (all serialized as `latitude`, `longitude`, `accuracy`).

### Backend Schema (Prisma)

```prisma
model TimeEntry {
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

---

## Hilt Dependency Graph

```
AppModule
├── provides CellLocationApi ← CellLocationApiImpl(ApiService)
│
LocationService (@Singleton, @Inject constructor)
├── @ApplicationContext context
├── CellLocationApi
├── (lazy) FusedLocationProviderClient
├── (lazy) LocationManager
├── (lazy) TelephonyManager
└── (lazy) WifiManager

TimeclockViewModel (@HiltViewModel)
├── LocationService
├── TpvSettingsRepository  ← decides if GPS capture is needed
└── TimeEntryRepository    ← sends location to backend
```

---

## Testing

### Unit Tests

**File:** `app/src/test/java/com/jaac/avoqado_tpv/features/timeclock/domain/AttendanceVerificationTest.kt`

Covers TpvSettings domain model and DTO mapping for attendance fields. Does not test `LocationService` directly (requires Android context).

### Manual Testing on PAX

```bash
# Monitor location acquisition in real-time
adb logcat -c && adb logcat | grep -iE "📍|Cell ID|WiFi|GPS|location"

# Expected log sequence for successful network location:
# 📍 Using Network Location API (Cell ID + WiFi)...
# 📍 Cell ID: LTE tower - MCC=334, MNC=20, TAC=12345, CI=67890
# 📍 WiFi: Found 5 access point(s)
# 📍 Network location: Found 1 cell tower(s) and 5 WiFi AP(s), calling API...
# 📍 Network location: API returned: 19.4326, -99.1332 (acc: 35.0m)
# 📍 GPS captured: 19.4326, -99.1332
```

### Checklist

- [ ] Network location works indoors with SIM card
- [ ] WiFi improves accuracy (compare cell-only vs cell+WiFi)
- [ ] GPS fallback works outdoors when network location fails
- [ ] Clock-in succeeds when all location strategies fail (null location)
- [ ] 45s timeout does not block the clock-in UI indefinitely
- [ ] Permissions granted on PAX without runtime dialog

---

**Last Updated:** 2026-02-05
