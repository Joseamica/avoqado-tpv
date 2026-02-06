# Heartbeat & Network Monitoring

Dense reference for the TPV heartbeat system, network monitoring, and remote command polling.

## Architecture Overview

```
HeartbeatScheduler (static object)
  └── HeartbeatWorker (WorkManager, every 30s)
        ├── DeviceHealthMonitor  → battery, storage, memory, uptime
        ├── NetworkMonitor       → type, signal, metered
        ├── HeartbeatRepository  → POST /tpv/heartbeat
        ├── CommandExecutor      → process pendingCommands from response
        └── 404 threshold logic  → deactivation after 10 consecutive HTTP 404s

ConnectionViewModel (ViewModel, every 30s)
  ├── ConnectivityObserver  → binary Available/Unavailable (Android callbacks)
  ├── NetworkMonitor        → detailed network info
  ├── HeartbeatRepository   → lightweight heartbeat as connectivity probe
  ├── CommandExecutor       → process pendingCommands (primary delivery path)
  └── ConnectionEventManager → broadcast reconnection events to other VMs

ConnectionStateManager (Singleton)
  └── Bridges ConnectionViewModel <-> DeviceHealthViewModel for unified alerts
```

**Two heartbeat senders exist.** `HeartbeatWorker` runs via WorkManager (survives app close). `ConnectionViewModel` runs in-process (more frequent, primary command delivery). Both call `POST /tpv/heartbeat` and process `pendingCommands` from the response.

## HeartbeatScheduler

**File:** `core/util/HeartbeatScheduler.kt` -- Static `object`, no DI.

| Method | When to Call | Behavior |
|--------|-------------|----------|
| `start(context)` | After login, app restart if activated | `REPLACE` policy, only one worker |
| `stop(context)` | Logout, deactivation, terminal retired | Cancels all heartbeat work |
| `isRunning(context)` | Debug/health checks | Returns `true` if worker enqueued/running |
| `runNow(context)` | Testing only | Enqueues `OneTimeWorkRequest` |

```kotlin
// WorkManager configuration
PeriodicWorkRequestBuilder<HeartbeatWorker>(
    repeatInterval = 30L, // seconds
    repeatIntervalTimeUnit = TimeUnit.SECONDS
)
.setConstraints(Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build())
// Policy: ExistingPeriodicWorkPolicy.REPLACE
// Work name: "heartbeat_worker"
```

**Important:** WorkManager enforces a 15-minute minimum for periodic work. The 30s interval is a request; actual execution depends on OS scheduling. `ConnectionViewModel`'s 30s loop is the reliable path for command delivery.

## HeartbeatWorker

**File:** `core/data/workers/HeartbeatWorker.kt` -- `@HiltWorker`, `CoroutineWorker`.

### Execution Flow

1. Check `secureStorage.isTerminalActivated()` -- skip if not activated (`Result.failure()`)
2. Build `Heartbeat` from `DeviceInfoManager` + `DeviceHealthMonitor` + `NetworkMonitor`
3. Send via `HeartbeatRepository.sendHeartbeat(heartbeat)`
4. On success: reset 404 counter, process `pendingCommands`, check `forceUpdate`
5. On error: evaluate HTTP status for deactivation/retry logic

### Heartbeat Payload (Request)

```
POST /tpv/heartbeat  (public endpoint, no auth required)
```

| Field | Source | Example |
|-------|--------|---------|
| `terminalId` | `DeviceInfoManager.getSerialNumber()` | `"AVQD-2841548417"` |
| `timestamp` | `Instant.now().toString()` | `"2025-11-03T12:30:00.000Z"` |
| `status` | `MaintenanceManager.isInMaintenance` | `"ACTIVE"` or `"MAINTENANCE"` |
| `version` | `BuildConfig.VERSION_NAME` | `"2.1.0"` |
| `systemInfo.platform` | hardcoded | `"Android"` |
| `systemInfo.osVersion` | `Build.VERSION.RELEASE` | `"Android 13"` |
| `systemInfo.deviceModel` | `Build.MODEL` | `"A80"` |
| `systemInfo.manufacturer` | `Build.MANUFACTURER` | `"PAX"` |
| `systemInfo.batteryLevel` | `BatteryManager` | `85` (0-100, -1 unknown) |
| `systemInfo.batteryCharging` | `ACTION_BATTERY_CHANGED` | `true` |
| `systemInfo.storageAvailableGB` | `StatFs` | `3.52` |
| `systemInfo.memory` | `Runtime.getRuntime()` | `{ total: 256, used: 180, free: 76 }` (MB) |
| `systemInfo.uptime` | `SystemClock.elapsedRealtime()` | `86400` (seconds, converted from ms) |
| `systemInfo.networkType` | `NetworkMonitor` | `"WIFI"` |
| `systemInfo.networkMetered` | `ConnectivityManager.isActiveNetworkMetered` | `false` |
| `systemInfo.networkConnected` | `NetworkCapabilities` check | `true` |
| `systemInfo.signalStrength` | `NetworkCapabilities.signalStrength` (API 29+) | `3` (0-4 scale) |
| `systemInfo.versionCode` | `BuildConfig.VERSION_CODE` | `45` |

### Heartbeat Response

```json
{
  "success": true,
  "message": "Heartbeat received",
  "serverStatus": "ACTIVE",
  "timestamp": "2025-11-03T12:30:01.000Z",
  "pendingCommands": [ ... ],
  "forceUpdate": { "versionName": "2.2.0", "versionCode": 50, "downloadUrl": "...", "updateMode": "FORCE" }
}
```

`pendingCommands` and `forceUpdate` are nullable. `forceUpdate` is included in every response when a forced update exists, until the terminal updates.

### Deactivation via 404 Threshold

```kotlin
private const val MAX_NOT_FOUND_BEFORE_CLEAR = 10  // ~5 min at 30s intervals
private const val PREF_NOT_FOUND_COUNT = "heartbeat_not_found_count"
```

| Condition | Action |
|-----------|--------|
| HTTP 404 | Increment counter. At 10: `secureStorage.clearAll()` + `HeartbeatScheduler.stop()` |
| "retired" in error message (HTTP only) | Immediate: `secureStorage.clearAll()` + `HeartbeatScheduler.stop()` + `Result.failure()` |
| Any non-404 HTTP error | Reset counter to 0 |
| Network error (timeout, refused) | Does NOT increment counter. Returns `Result.retry()` |
| Success | Reset counter to 0 |

Counter is stored in `SharedPreferences("heartbeat_prefs")` (unencrypted, just a counter).

### WorkManager Retry Policy

`Result.retry()` triggers exponential backoff: 10s -> 20s -> 40s -> 80s -> 5min (max).

## Remote Command Polling (Square Terminal API Pattern)

Commands are delivered via heartbeat response, not Socket.IO push. This ensures delivery even when socket is disconnected (login screen, network instability).

### Command Flow

```
Dashboard -> Backend (TpvCommandQueue) -> Heartbeat Response (pendingCommands)
  -> HeartbeatWorker/ConnectionViewModel: processPendingCommands()
    -> PendingCommandDto.toTpvCommand()  (null if unknown type)
    -> CommandExecutor.execute(command)   (checks expiry first)
    -> HeartbeatRepository.sendCommandAck(commandId, terminalId, result)
       POST /tpv/command-ack  { commandId, terminalId, resultStatus, resultMessage, resultPayload }
```

### PendingCommandDto Fields

| Field | Type | Description |
|-------|------|-------------|
| `commandId` | `String` | Unique command ID |
| `correlationId` | `String` | Groups related commands |
| `type` | `String` | `LOCK`, `UNLOCK`, `MAINTENANCE_MODE`, etc. |
| `payload` | `JsonObject?` | Command-specific data |
| `priority` | `String` | `LOW`, `NORMAL`, `HIGH`, `CRITICAL` |
| `requiresPin` | `Boolean` | Server-side PIN verification |
| `expiresAt` | `String?` | ISO-8601, defaults to +5 min if null |
| `requestedBy` | `String` | User ID who sent command |
| `requestedByName` | `String?` | Display name |

### Supported Command Types (21 total)

| Category | Commands |
|----------|----------|
| Device State | `LOCK`, `UNLOCK`, `MAINTENANCE_MODE`, `EXIT_MAINTENANCE`, `REACTIVATE`, `REMOTE_ACTIVATE` |
| App Lifecycle | `RESTART`, `SHUTDOWN`, `CLEAR_CACHE`, `FORCE_UPDATE`, `REQUEST_UPDATE`, `INSTALL_VERSION` |
| Data Management | `SYNC_DATA`, `FACTORY_RESET`, `EXPORT_LOGS` |
| Configuration | `UPDATE_CONFIG`, `REFRESH_MENU`, `UPDATE_MERCHANT` |
| Automation (server-side) | `SCHEDULE`, `GEOFENCE_TRIGGER`, `TIME_RULE` |

### ACK Results

| Status | When |
|--------|------|
| `SUCCESS` | Command executed successfully |
| `FAILED` | Execution threw exception |
| `REJECTED` | Unknown type, expired, invalid payload |
| `TIMEOUT` | Execution timed out |

ACK endpoint: `POST /tpv/command-ack` (public, no auth). Includes `terminalId` for ownership validation.

## NetworkMonitor

**File:** `core/util/NetworkMonitor.kt` -- `@Singleton`, Hilt injected.

Provides detailed network state. Used by `HeartbeatWorker` for payload data and `ConnectionViewModel` for connectivity checks.

### Key APIs

| Method | Returns | Use |
|--------|---------|-----|
| `getCurrentNetworkInfo()` | `NetworkInfo` | Snapshot of current network state |
| `networkStateFlow` | `Flow<NetworkInfo>` | Reactive state changes via `NetworkCallback` |
| `isGoodForLargeSync()` | `Boolean` | WiFi/Ethernet + not metered |
| `isConnected()` | `Boolean` | Any internet connectivity |

### NetworkInfo Data Class

```kotlin
data class NetworkInfo(
    val type: NetworkType,        // WIFI, ETHERNET, CELLULAR, OTHER, NONE
    val isMetered: Boolean,       // true for cellular or limited WiFi
    val isConnected: Boolean,
    val signalStrength: Int?      // 0-4 scale (null if unknown)
)
```

### Signal Strength Mapping (Android 10+ / `NetworkCapabilities.signalStrength`)

| dBm Range | Score | Label |
|-----------|-------|-------|
| >= -50 | 4 | Excellent |
| >= -60 | 3 | Good |
| >= -70 | 2 | Fair |
| >= -80 | 1 | Poor |
| < -80 | 0 | Very poor |
| Pre-Android 10 | 3 | Assumed good |

### Network Type Priority

Ethernet > WiFi > Cellular > Other (matches Android system priority via `NetworkCapabilities.hasTransport()`).

### Adaptive Heartbeat Intervals (defined but not yet active)

`NetworkInfo.getRecommendedHeartbeatInterval()` returns recommended seconds:

| Condition | Interval |
|-----------|----------|
| Not connected | 120s |
| Battery < 20% + not charging | 120s |
| Charging + WiFi/Ethernet | 15s |
| Cellular | 60s |
| Signal < 2 | 60s |
| Default | 30s |

Phase 1 uses fixed 30s. Adaptive intervals are planned for Phase 2.

## ConnectivityObserver

**File:** `core/util/ConnectivityObserver.kt` -- `@Singleton`, Hilt injected.

Binary connectivity check. Simpler than `NetworkMonitor` -- only reports Available/Unavailable.

```kotlin
sealed interface NetworkStatus {
    data object Available : NetworkStatus
    data object Unavailable : NetworkStatus
}
```

Key difference from `NetworkMonitor`: uses `NET_CAPABILITY_VALIDATED` (actual internet validation, not just WiFi connected). Emits `distinctUntilChanged()` to prevent duplicates.

**Used by:** `ConnectionViewModel.observeNetworkChanges()` for real-time network restoration detection with 2-second grace period.

## ConnectionViewModel -- Reconnection Logic

**File:** `core/presentation/viewmodels/ConnectionViewModel.kt`

### Connection States

| State | UI |
|-------|----|
| `Checking` | Initial |
| `Connected` | No banner |
| `DisconnectedNoInternet` | "Sin conexion a internet" |
| `DisconnectedServerDown` | "Sin conexion al servidor" |
| `Reconnecting` | "Reconectando al servidor..." |
| `Reconnected` | Green "Conectado" banner (2s) |
| `Dismissed` | User hid banner |

### Monitoring Loop

```
init → startMonitoring() + observeNetworkChanges()

startMonitoring():
  while(true) {
    checkConnection()      // immediate first check
    delay(interval)        // adaptive: 30s connected, 5s reconnecting, backoff if disconnected
  }
```

### Exponential Backoff (Disconnected State)

```
Base: 5s, shift left by attempts (capped at 4), max 30s
Attempt 1: 10s
Attempt 2: 20s
Attempt 3: 30s (max)
Attempt 4+: 30s
```

### Network Restoration Flow

1. `ConnectivityObserver` emits `Available`
2. 2-second grace period (WiFi/DNS stabilization after screen wake)
3. `checkConnection()` sends lightweight heartbeat
4. On success with `reconnectionAttempts > 0`: emit `ConnectionRestoredEvent` via `ConnectionEventManager`
5. Show `Reconnected` state for 2 seconds, then `Connected`

### ConnectionEventManager

`SharedFlow<ConnectionRestoredEvent>` with `replay = 0`. Listeners (`ShiftViewModel`, etc.) reload data on reconnection. Event includes `attemptsBeforeReconnection` count.

### ConnectionStateManager

`StateFlow<ConnectionState>` with `hasInternet: Boolean` + `hasServer: Boolean`. Bridges `ConnectionViewModel` to `DeviceHealthViewModel` for unified alert priority (P0: no internet, P2: server down).

## Key Source Files

| File | Path |
|------|------|
| HeartbeatScheduler | `core/util/HeartbeatScheduler.kt` |
| HeartbeatWorker | `core/data/workers/HeartbeatWorker.kt` |
| HeartbeatRepository | `core/data/repository/HeartbeatRepository.kt` |
| Heartbeat (domain) | `core/domain/models/Heartbeat.kt` |
| HeartbeatDto | `core/data/network/dto/HeartbeatDto.kt` |
| NetworkMonitor | `core/util/NetworkMonitor.kt` |
| ConnectivityObserver | `core/util/ConnectivityObserver.kt` |
| ConnectionViewModel | `core/presentation/viewmodels/ConnectionViewModel.kt` |
| ConnectionEventManager | `core/util/ConnectionEventManager.kt` |
| ConnectionStateManager | `core/util/ConnectionStateManager.kt` |
| DeviceHealthMonitor | `core/util/DeviceHealthMonitor.kt` |
| CommandExecutor | `features/remote_command/domain/CommandExecutor.kt` |
| TpvCommand | `features/remote_command/data/model/TpvCommand.kt` |
| ApiService endpoints | `core/data/network/ApiService.kt` |

## API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `tpv/heartbeat` | Public | Send health metrics, receive commands |
| POST | `tpv/command-ack` | Public | Acknowledge command execution result |
