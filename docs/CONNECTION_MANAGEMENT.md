# Connection Management

Dense reference for all connectivity, reconnection, and network state infrastructure in avoqado-tpv.

## Architecture Overview

```
                          Android OS
                    ConnectivityManager
                           |
              +------------+------------+
              |                         |
     ConnectivityObserver         NetworkMonitor
     (binary: up/down)      (rich: type, signal, metered)
              |                         |
              +-------+     +-----------+
                      |     |
                ConnectionViewModel          HeartbeatWorker
                (heartbeat polling,          (WorkManager, runs
                 reconnection FSM,            independently of
                 command processing)          login state)
                      |
          +-----------+-----------+
          |                       |
  ConnectionStateManager   ConnectionEventManager
  (StateFlow: current      (SharedFlow: one-shot
   internet+server state)   reconnection events)
          |                       |
          v                       v
  DeviceHealthViewModel    ShiftViewModel
  (unified alert banner)   HomeViewModel
                           MenuViewModel
```

## Core Components

### 1. ConnectivityObserver

**File:** `core/util/ConnectivityObserver.kt` | **Scope:** `@Singleton`

Binary network status observer using `ConnectivityManager.NetworkCallback`. Emits `Flow<NetworkStatus>` with `.distinctUntilChanged()`.

| Method | Returns | Purpose |
|--------|---------|---------|
| `observe()` | `Flow<NetworkStatus>` | Reactive stream of Available/Unavailable |
| `getCurrentNetworkStatus()` | `NetworkStatus` | Synchronous snapshot |

Uses `NET_CAPABILITY_VALIDATED` (not just `NET_CAPABILITY_INTERNET`) to ensure actual internet reachability, not just WiFi association.

### 2. NetworkMonitor

**File:** `core/util/NetworkMonitor.kt` | **Scope:** `@Singleton`

Rich network information provider. Returns `NetworkInfo` with type, signal strength, metered status.

| Method | Returns | Purpose |
|--------|---------|---------|
| `getCurrentNetworkInfo()` | `NetworkInfo` | Snapshot with type/signal/metered |
| `networkStateFlow` | `Flow<NetworkInfo>` | Reactive stream on capability changes |
| `isConnected()` | `Boolean` | Quick connectivity check |
| `isGoodForLargeSync()` | `Boolean` | WiFi/Ethernet + unmetered |

**NetworkType enum:** `WIFI`, `ETHERNET`, `CELLULAR`, `OTHER`, `NONE`

**Signal strength scale (Android 10+):**

| dBm | Score | Label |
|-----|-------|-------|
| >= -50 | 4 | Excellent |
| >= -60 | 3 | Good |
| >= -70 | 2 | Fair |
| >= -80 | 1 | Poor |
| < -80 | 0 | Very poor |

**Adaptive heartbeat intervals** (via `NetworkInfo.getRecommendedHeartbeatInterval()`):

| Condition | Interval |
|-----------|----------|
| No network | 120s |
| Low battery + not charging | 120s |
| Ethernet/WiFi + charging | 15s |
| Cellular | 60s |
| Weak signal (0-1) | 60s |
| Default | 30s |

### 3. ConnectionStateManager

**File:** `core/util/ConnectionStateManager.kt` | **Scope:** `@Singleton`

Single source of truth for connection state. Holds a `StateFlow<ConnectionState>` with two booleans: `hasInternet` and `hasServer`.

```kotlin
data class ConnectionState(
    val hasInternet: Boolean = true,  // Network layer reachability
    val hasServer: Boolean = true     // Backend heartbeat success
) {
    val isFullyConnected: Boolean get() = hasInternet && hasServer
    val hasAnyIssue: Boolean get() = !hasInternet || !hasServer
}
```

**Writer:** `ConnectionViewModel` (sole writer via `setInternetConnected()`, `setServerConnected()`, `updateState()`, `resetToConnected()`).

**Reader:** `DeviceHealthViewModel` observes `connectionState` flow and merges connection alerts (P0: NoInternet, P2: ServerDown) into the unified alert list.

Only emits when state actually changes (dedup guard in each setter).

### 4. ConnectionEventManager

**File:** `core/util/ConnectionEventManager.kt` | **Scope:** `@Singleton`

One-shot event bus for reconnection events. Uses `MutableSharedFlow<ConnectionRestoredEvent>(replay = 0, extraBufferCapacity = 10)` -- no replay means late subscribers don't get stale events.

```kotlin
data class ConnectionRestoredEvent(
    val timestamp: String,                  // ISO-8601
    val attemptsBeforeReconnection: Int     // How many failed attempts
)
```

**Emitter:** `ConnectionViewModel` -- emits when heartbeat succeeds after `reconnectionAttempts > 0`.

**Listeners and their reactions:**

| ViewModel | File | Reaction on reconnection |
|-----------|------|--------------------------|
| `ShiftViewModel` | `features/shift/presentation/ShiftViewModel.kt` | `loadCurrentShift()` -- syncs shift closures from other terminals |
| `HomeViewModel` | `core/presentation/viewmodels/HomeViewModel.kt` | Re-fetches merchants if using fallback, re-checks for app updates |
| `MenuViewModel` | `features/ordering/presentation/menu/MenuViewModel.kt` | Auto-syncs pending local orders (only if ordering screen active) |

Pattern: each listener calls `connectionEventManager.connectionRestoredEvents.collect { ... }` in `viewModelScope.launch`.

### 5. ConnectionViewModel

**File:** `core/presentation/viewmodels/ConnectionViewModel.kt` | **Scope:** `@HiltViewModel`

Central orchestrator. Manages the reconnection state machine, periodic heartbeat polling, and command processing.

**Dependencies injected:**

| Dependency | Purpose |
|------------|---------|
| `NetworkMonitor` | Check network before heartbeat |
| `ConnectivityObserver` | React immediately to network changes |
| `HeartbeatRepository` | Send lightweight heartbeat to backend |
| `DeviceInfoManager` | Terminal serial, activation status |
| `DeviceHealthMonitor` | System health metrics for heartbeat payload |
| `ConnectionEventManager` | Emit reconnection events to other VMs |
| `CommandExecutor` | Execute pending commands from heartbeat response |
| `ConnectionStateManager` | Update shared connection state |

**State machine (`ConnectionState` sealed class):**

```
Checking --> Connected (heartbeat OK)
Checking --> DisconnectedNoInternet (no network)
Checking --> DisconnectedServerDown (network OK, heartbeat fails)

Connected --> DisconnectedNoInternet (network lost)
Connected --> DisconnectedServerDown (heartbeat fails)

DisconnectedNoInternet --> Reconnecting (network restored, grace 2s)
DisconnectedServerDown --> Reconnecting (retry heartbeat)

Reconnecting --> Reconnected (heartbeat succeeds, shown 2s)
Reconnecting --> DisconnectedServerDown (heartbeat fails again)

Reconnected --> Connected (after 2s delay)

Any state --> Dismissed (user dismisses banner)
Dismissed --> [rechecks on network change or manual retry]
```

**Monitoring loop (`startMonitoring()`):**

Runs `while(true)` in `viewModelScope`. Interval adapts to state:

| State | Poll Interval |
|-------|---------------|
| `Connected` | 30s |
| `DisconnectedNoInternet` / `DisconnectedServerDown` | Exponential backoff (5s -> 10s -> 20s -> 30s max) |
| `Reconnecting` | 5s |
| Default | 10s |

**Network observer (`observeNetworkChanges()`):**

Collects `connectivityObserver.observe()`. On `Available`: waits 2s grace period (WiFi/DNS stabilization after screen wake), clears `isDismissed`, triggers `checkConnection()`. On `Unavailable`: immediately sets `DisconnectedNoInternet` and updates `ConnectionStateManager`.

**`checkConnection()` flow:**

1. Skip if terminal not activated (`deviceInfoManager.isDeviceActivated()` -- prevents false banner on activation screen)
2. Check `networkMonitor.getCurrentNetworkInfo().isConnected` -- if false, set `DisconnectedNoInternet`
3. Send lightweight heartbeat via `heartbeatRepository.sendHeartbeat()`
4. On success: update `ConnectionStateManager` to fully connected, process pending commands, emit `ConnectionRestoredEvent` if `reconnectionAttempts > 0`, show `Reconnected` for 2s, then `Connected`
5. On error: set `DisconnectedServerDown`, update `ConnectionStateManager` (internet=true, server=false)

**Command processing (Square Terminal API pattern):**

Pending commands arrive in heartbeat response. ConnectionViewModel processes them via `CommandExecutor` and sends HTTP ACKs back. This runs every 30s, more reliable than WorkManager's 15-min minimum for command delivery.

### 6. SocketManager

**File:** `core/data/realtime/SocketManager.kt` | **Scope:** `@Singleton` (via `SocketModule`)

Socket.IO client. Separate from the HTTP heartbeat system -- handles real-time event push.

**Connection options:**

| Option | Value |
|--------|-------|
| Transports | `["websocket", "polling"]` (WebSocket preferred) |
| Auth | `{ token: JWT, terminalId: serial }` via auth object |
| Reconnection | Enabled, max 5 attempts |
| Reconnection delay | 1s base, 5s max, 0.5 jitter |
| Connection timeout | 20s |
| Force new | true |

**Connection lifecycle:**

1. `LoginViewModel` calls `socketManager.connect(url, token, terminalId)` after successful login
2. `HomeViewModel` also calls `socketManager.connect()` on init (covers app restart after login)
3. On `SessionEvent.TokenRefreshed`: `HomeViewModel` calls `socketManager.reconnectWithFreshToken()` which reads fresh JWT from `SecureStorage` (breaks stale-token reconnection loops)
4. Socket emits `SocketEvent.Connected` / `SocketEvent.Disconnected` / `SocketEvent.ConnectionError`
5. Connection state exposed via `isConnected: SharedFlow<Boolean>` (replay=1)

**`reconnectWithFreshToken()` vs `reconnect()`:**

| Method | Token source | Use case |
|--------|-------------|----------|
| `reconnect()` | In-memory `currentToken` | General retry |
| `reconnectWithFreshToken()` | `secureStorage.getToken()` | After TokenAuthenticator refresh (prevents expired-token loop) |

**Event flow:** `SharedFlow<SocketEvent>(replay=1, extraBufferCapacity=10, DROP_OLDEST)`. ViewModels collect from `socketManager.events` and filter by event type.

### 7. HeartbeatWorker

**File:** `core/data/workers/HeartbeatWorker.kt` | **Scope:** WorkManager periodic task

Runs independently of login state (only requires terminal activation). Sends device metrics to backend. Also processes pending commands and force updates from heartbeat response. Does NOT update `ConnectionStateManager` -- that is ConnectionViewModel's responsibility.

## State Flow Diagram

```
Network change (Android OS)
    |
    v
ConnectivityObserver.observe() -----> ConnectionViewModel.observeNetworkChanges()
                                          |
                                          | 2s grace period
                                          v
                                    checkConnection()
                                          |
                              +-----------+-----------+
                              |                       |
                         No network              Has network
                              |                       |
                              v                       v
                  ConnectionStateManager      heartbeatRepository.sendHeartbeat()
                  .setInternetConnected(false)        |
                              |              +--------+--------+
                              v              |                 |
                   _state = NoInternet    Success           Error
                                             |                 |
                                             v                 v
                               ConnectionStateManager    ConnectionStateManager
                               .updateState(T, T)       .updateState(T, F)
                                             |                 |
                                             v                 v
                                   _state = Connected   _state = ServerDown
                                             |
                                   (if reconnectionAttempts > 0)
                                             |
                                             v
                                  ConnectionEventManager
                                  .emitConnectionRestored()
                                             |
                              +--------------+--------------+
                              |              |              |
                              v              v              v
                         ShiftVM        HomeVM         MenuVM
                      loadShift()   fetchMerchants()  syncOrders()
```

## UI Integration

`ConnectionBanner` composable in `core/presentation/components/ConnectionBanner.kt`. Mounted in `AppNavigation.kt` inside the main `Column`. Receives `connectionState` from `ConnectionViewModel`.

| ConnectionState | Banner | Color | Action |
|-----------------|--------|-------|--------|
| `Connected` | Hidden | -- | -- |
| `Checking` | Hidden | -- | -- |
| `Dismissed` | Hidden | -- | -- |
| `DisconnectedNoInternet` | "Sin conexion a internet" | Orange | Reintentar button |
| `DisconnectedServerDown` | "Sin conexion al servidor" | Orange | Reintentar button |
| `Reconnecting` | "Reconectando..." | Orange | -- |
| `Reconnected` | "Conectado" | Green | Auto-hides after 2s |

Since v2 of the alert system, connection alerts (P0 NoInternet, P2 ServerDown) are also surfaced through `DeviceHealthViewModel`'s unified alert banner alongside device health alerts (battery, storage, memory, WiFi signal). `ConnectionViewModel` still owns reconnection logic; `DeviceHealthViewModel` only reads `ConnectionStateManager` for display.

## Hilt DI Wiring

All connection singletons are auto-provided via `@Inject constructor()` on the classes themselves (no explicit module needed for `ConnectionStateManager`, `ConnectionEventManager`, `ConnectivityObserver`, `NetworkMonitor`).

`SocketManager` is provided by `SocketModule` (`core/di/SocketModule.kt`) because it needs `SecureStorage` injected.

## Key File Paths

| Component | Path |
|-----------|------|
| ConnectionStateManager | `app/src/main/java/.../core/util/ConnectionStateManager.kt` |
| ConnectionEventManager | `app/src/main/java/.../core/util/ConnectionEventManager.kt` |
| ConnectivityObserver | `app/src/main/java/.../core/util/ConnectivityObserver.kt` |
| NetworkMonitor | `app/src/main/java/.../core/util/NetworkMonitor.kt` |
| ConnectionViewModel | `app/src/main/java/.../core/presentation/viewmodels/ConnectionViewModel.kt` |
| DeviceHealthViewModel | `app/src/main/java/.../core/presentation/viewmodels/DeviceHealthViewModel.kt` |
| SocketManager | `app/src/main/java/.../core/data/realtime/SocketManager.kt` |
| SocketModule | `app/src/main/java/.../core/di/SocketModule.kt` |
| ConnectionBanner | `app/src/main/java/.../core/presentation/components/ConnectionBanner.kt` |
| HeartbeatWorker | `app/src/main/java/.../core/data/workers/HeartbeatWorker.kt` |

## Design Decisions

**Why two network observers (ConnectivityObserver vs NetworkMonitor)?**
`ConnectivityObserver` is a lightweight binary signal (up/down) with `NET_CAPABILITY_VALIDATED`. `NetworkMonitor` provides rich metadata (type, signal, metered) for adaptive behavior. They serve different consumers.

**Why ConnectionStateManager + ConnectionEventManager (two singletons)?**
`ConnectionStateManager` holds continuous state (current internet+server status) for `DeviceHealthViewModel`'s alert display. `ConnectionEventManager` emits one-shot events (reconnection) that trigger data sync. These are fundamentally different patterns: state observation vs event reaction. Hilt prohibits injecting `@HiltViewModel` into other ViewModels, so singleton services bridge the gap.

**Why heartbeat polling instead of relying on Socket.IO connection status?**
Socket.IO may appear connected but the backend could be partially down (e.g., database unavailable). HTTP heartbeat validates end-to-end backend health. It also delivers pending commands (Square Terminal API pattern), which is more reliable than socket push when connections are unstable.

**Why 2s grace period after network restoration?**
WiFi association completes before DNS resolution is ready. Checking immediately after `onAvailable` causes false negatives. The 2s delay matches observed DNS propagation time on PAX devices after screen wake.
