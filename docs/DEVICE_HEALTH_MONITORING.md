# Device Health Monitoring

Dense reference for the TPV device health monitoring system. Covers metric collection, priority-based alerts, heartbeat reporting, and observability health scoring.

## Architecture Overview

Two independent health monitoring systems run in parallel:

| System | Class | Transport | Interval | Purpose |
|--------|-------|-----------|----------|---------|
| **Heartbeat** (primary) | `HeartbeatWorker` + `DeviceHealthMonitor` | HTTP POST `/tpv/heartbeat` | 30s (WorkManager) | Backend reporting, command polling |
| **Observability** (secondary) | `HealthMonitor` | Socket.IO `tpv:heartbeat` | 5 min | Real-time dashboard score |

```
DeviceHealthMonitor ──> HeartbeatWorker ──> HeartbeatRepository ──> POST /tpv/heartbeat
       |                                                                    |
       v                                                                    v
DeviceHealthViewModel ──> DeviceAlertBanner (UI)            HeartbeatResponseDto
       ^                                                     (pendingCommands, forceUpdate)
       |
ConnectionStateManager + NetworkMonitor + UpdateCheckManager + SimulatedAlertsManager
```

## Priority-Based Alert System (P-1 to P6)

`DeviceHealthViewModel` polls every 30 seconds, merges all alert sources, and exposes a single sorted list.

### Alert Priority Table

| Priority | Type | Alert Class | Threshold | Color | Dismissible | Icon |
|----------|------|-------------|-----------|-------|-------------|------|
| **P-1** | Update available | `UpdateAvailable` | BANNER or FORCE mode | Blue (`statusInfo`) | No | `SystemUpdate` |
| **P0** | No internet | `NoInternet` | `!connectionState.hasInternet` | Red (`statusCritical`) | No | `WifiOff` |
| **P1** | Battery critical | `BatteryCritical(level)` | 0-10% AND not charging | Red | No | `BatteryAlert` |
| **P2** | Server down | `ServerDown` | hasInternet AND !hasServer | Red | No | `CloudOff` |
| **P3** | Battery low | `BatteryLow(level)` | 11-20% AND not charging | Orange (`offlineOrange`) | Yes | `Battery2Bar` |
| **P4** | Storage low | `StorageLow(gb)` | < 1 GB available | Yellow (`statusWarning`) | Yes | `Storage` |
| **P5** | WiFi weak | `WeakWifi(strength)` | Signal strength 0-1 (of 0-4) | Yellow | Yes | `SignalWifi...` |
| **P6** | Memory low | `MemoryLow(freeMB)` | < 100 MB free | Yellow | Yes | `Memory` |

**Dismissal rules:** Alerts P0-P2 cannot be dismissed by the user. Alerts P3-P6 can be dismissed via `dismissAlert()`, tracked in an in-memory `dismissedAlerts` set.

### Color Mapping

```kotlin
// From DeviceHealthViewModel.kt
fun DeviceAlert.getAlertColor(): AlertColor = when (priority) {
    -1 -> AlertColor.UPDATE        // Blue
    0, 1, 2 -> AlertColor.CRITICAL // Red
    3 -> AlertColor.WARNING        // Orange
    else -> AlertColor.CAUTION     // Yellow (P4-P6)
}
```

### Alert Data Flow

```
DeviceHealthViewModel.updateAlerts() runs every 30s AND on:
  - Network status change (ConnectivityObserver)
  - Connection state change (ConnectionStateManager)
  - Simulated alert change (SimulatedAlertsManager)
  - Update check result (UpdateCheckManager)

Pipeline:
  1. Collect health from DeviceHealthMonitor.getSystemHealth()
  2. Collect network from NetworkMonitor.getCurrentNetworkInfo()
  3. Collect connection from ConnectionStateManager.connectionState
  4. Collect update from UpdateCheckManager.pendingUpdate
  5. Build alert list by checking each threshold
  6. Merge simulated alerts (testing)
  7. .distinctBy { it.type }  // Deduplicate
  8. .filter { not dismissed OR priority <= 2 }
  9. .sortedBy { it.priority }
  10. Emit to _activeAlerts StateFlow
```

## Metric Collection: DeviceHealthMonitor

**File:** `core/util/DeviceHealthMonitor.kt` -- `@Singleton`, injected via Hilt.

Returns `SystemHealth` data class used by both HeartbeatWorker and DeviceHealthViewModel.

### Metrics Collected

| Metric | Source | Return Type | Error Value |
|--------|--------|-------------|-------------|
| Battery level | `BatteryManager.BATTERY_PROPERTY_CAPACITY` | `Int` (0-100) | -1 |
| Battery charging | `Intent.ACTION_BATTERY_CHANGED` + `EXTRA_STATUS` | `Boolean` | false |
| Storage available | `StatFs(context.filesDir)` | `Float` (GB) | -1f |
| Memory total/used/free | `Runtime.getRuntime()` max/total/free | `MemoryInfo` (MB) | -1L each |
| Uptime | `SystemClock.elapsedRealtime()` | `Long` (ms since boot) | -- |
| Platform/OS/Model | `Build.VERSION.RELEASE`, `Build.MODEL`, `Build.MANUFACTURER` | `String` | -- |

### Health State Methods

```kotlin
// Critical: device may fail during transaction
fun isCriticalHealth(): Boolean =
    (batteryLevel in 0..10 && !batteryCharging) ||
    (storageAvailableGB in 0f..1f) ||
    (memoryInfo.freeMB in 0..100)

// Warning: user should take action soon
fun isWarningHealth(): Boolean =
    (batteryLevel in 0..20 && !batteryCharging) ||
    (storageAvailableGB in 0f..5f) ||
    (memoryInfo.freeMB in 0..500)
```

## Network Monitoring: NetworkMonitor

**File:** `core/util/NetworkMonitor.kt` -- `@Singleton`.

| Metric | Source | Values |
|--------|--------|--------|
| Network type | `NetworkCapabilities` transport | `WIFI`, `ETHERNET`, `CELLULAR`, `OTHER`, `NONE` |
| Metered | `ConnectivityManager.isActiveNetworkMetered` | `Boolean` |
| Connected | `NET_CAPABILITY_INTERNET` check | `Boolean` |
| Signal strength | `NetworkCapabilities.signalStrength` (API 29+) | 0-4 scale (`>=-50`=4, `>=-60`=3, `>=-70`=2, `>=-80`=1, else=0) |

### Adaptive Heartbeat Intervals (Phase 2 -- not yet implemented)

```kotlin
// NetworkInfo.getRecommendedHeartbeatInterval() -- currently unused
!isConnected                         -> 120s
batteryLevel < 20 && !charging       -> 120s
charging && (ETHERNET or WIFI)       -> 15s
CELLULAR                             -> 60s
signalStrength < 2                   -> 60s
default                              -> 30s
```

## Heartbeat Reporting to Backend

### HeartbeatWorker (WorkManager)

**File:** `core/data/workers/HeartbeatWorker.kt` -- `@HiltWorker`.

**Lifecycle:** Started by `HeartbeatScheduler.start()` on login. Stopped on logout or deactivation. Runs every 30 seconds via `PeriodicWorkRequestBuilder`. Requires `NetworkType.CONNECTED` constraint.

**Precondition:** Terminal must be activated (`secureStorage.isTerminalActivated()`). Does NOT check if user is logged in -- prevents heartbeat deadlock.

### Heartbeat Request Payload

Sent to `POST /tpv/heartbeat` (public endpoint, no auth required).

```json
{
  "terminalId": "AVQD-2841548417",
  "timestamp": "2025-11-03T12:30:00.000Z",
  "status": "ACTIVE",       // or "MAINTENANCE"
  "version": "1.5.0",
  "systemInfo": {
    "platform": "Android",
    "osVersion": "Android 13",
    "deviceModel": "PAX A80",
    "manufacturer": "PAX",
    "batteryLevel": 85,
    "batteryCharging": true,
    "storageAvailableGB": 12.5,
    "memory": { "total": 1024, "used": 700, "free": 324 },
    "uptime": 86400,
    "networkType": "WIFI",
    "networkMetered": false,
    "networkConnected": true,
    "signalStrength": 3,
    "versionCode": 52
  }
}
```

Note: `uptime` is converted from milliseconds to **seconds** in the DTO mapper (`systemInfo.uptime / 1000`).

### Heartbeat Response

```json
{
  "success": true,
  "message": "Heartbeat processed",
  "serverStatus": "ACTIVE",
  "timestamp": "...",
  "pendingCommands": [ { "commandId": "...", "type": "LOCK", ... } ],
  "forceUpdate": { "versionName": "1.6.0", "versionCode": 55, "downloadUrl": "...", "updateMode": "FORCE" }
}
```

### Error Handling & Deactivation

| Condition | Action | Retry? |
|-----------|--------|--------|
| Success | Reset not-found counter, process commands | -- |
| HTTP 404 | Increment counter. After 10 consecutive 404s (~5 min): clear activation, stop heartbeat | Yes until threshold |
| "retired" in error message (HTTP only) | Clear ALL data, stop heartbeat immediately | No (failure) |
| Network error | Retry with exponential backoff (10s -> 20s -> 40s -> 80s -> 5min max) | Yes |
| Non-404 HTTP error | Reset not-found counter, retry | Yes |
| Unexpected exception | Retry | Yes |

## Observability HealthMonitor (Secondary System)

**File:** `core/observability/monitor/HealthMonitor.kt` -- `@Singleton`.

Separate from the primary heartbeat system. Sends richer metrics via Socket.IO every 5 minutes. Includes battery **temperature** (not in primary system).

### Health Score Calculation (0-100)

Starts at 100, penalties subtracted:

| Condition | Penalty |
|-----------|---------|
| System `lowMemory` flag | -20 |
| Memory usage > 90% | -20 |
| Memory usage > 80% | -10 |
| Battery < 10% | -30 |
| Battery < 20% AND not charging | -20 |
| Storage < 100 MB | -15 |
| Socket.IO disconnected | -10 |

Score ranges: **Healthy** (90-100), **Degraded** (70-89), **Critical** (<70).

Additional metrics collected by HealthMonitor but NOT by DeviceHealthMonitor:
- Battery temperature (`BatteryManager.EXTRA_TEMPERATURE` / 10.0 = Celsius)
- System-wide memory via `ActivityManager.MemoryInfo` (vs. app-only `Runtime` in primary)
- Socket.IO connection state
- Blumon environment (`BuildConfig.BLUMON_ENV`)

## UI: DeviceAlertBanner

**File:** `core/presentation/components/DeviceAlertBanner.kt`

Compose component placed at the top of `AppNavigation`. Shows the highest-priority alert as a colored banner. If multiple alerts exist, displays a `+N` badge that expands on tap to show all alerts.

**Behavior:**
- Empty list = no banner rendered
- Single alert = banner with message + description
- Multiple alerts = top alert + expandable list
- Connection alerts (P0, P2) show "Reintentar" button -> `connectionViewModel.forceCheck()`
- Update alerts (P-1) show "Actualizar" button -> navigate to SelfUpdate screen
- P3-P6 alerts show dismiss (X) button

## Testing: SimulatedAlertsManager

**File:** `core/util/SimulatedAlertsManager.kt` -- `@Singleton`.

Accessible from SuperAdmin screen. Persists across navigation because it is a singleton (DeviceHealthViewModel instances differ between SuperAdmin and AppNavigation).

```kotlin
simulateAlert(DeviceAlert.BatteryCritical(5))
simulateMultipleAlerts()   // BatteryLow(15), StorageLow(0.8), WeakWifi(1), MemoryLow(80)
clearAll()
```

## Key Classes Summary

| Class | Package | Role |
|-------|---------|------|
| `DeviceHealthMonitor` | `core.util` | Collects battery/storage/memory/uptime metrics |
| `DeviceHealthViewModel` | `core.presentation.viewmodels` | Merges all sources into priority-sorted alert list |
| `DeviceAlert` (sealed class) | `core.presentation.viewmodels` | Alert model with priority, type, message, description |
| `DeviceAlertBanner` | `core.presentation.components` | Compose UI banner with expand/dismiss/retry/update |
| `HeartbeatWorker` | `core.data.workers` | WorkManager worker that sends heartbeat every 30s |
| `HeartbeatScheduler` | `core.util` | Static object to start/stop/check heartbeat worker |
| `HeartbeatRepository` | `core.data.repository` | HTTP client for `/tpv/heartbeat` and command ACK |
| `NetworkMonitor` | `core.util` | Network type, metered, signal strength detection |
| `ConnectionStateManager` | `core.util` | Singleton tracking internet + server connectivity |
| `SimulatedAlertsManager` | `core.util` | Testing: inject fake alerts from SuperAdmin |
| `HealthMonitor` | `core.observability.monitor` | Secondary: Socket.IO health score with temperature |
| `ConnectionEventManager` | `core.util` | Broadcasts reconnection events to other ViewModels |

## HeartbeatScheduler Lifecycle

```
App Start
  └─> Login Success
        └─> HeartbeatScheduler.start(context)
              └─> WorkManager.enqueueUniquePeriodicWork("heartbeat_worker", REPLACE, 30s)
                    └─> HeartbeatWorker.doWork() every 30s
                          ├─> buildHeartbeat() (DeviceHealthMonitor + NetworkMonitor + DeviceInfoManager)
                          ├─> heartbeatRepository.sendHeartbeat(heartbeat)
                          ├─> Process pendingCommands from response
                          └─> Handle forceUpdate from response

Logout / Deactivation / Retired
  └─> HeartbeatScheduler.stop(context)
        └─> WorkManager.cancelUniqueWork("heartbeat_worker")
```
