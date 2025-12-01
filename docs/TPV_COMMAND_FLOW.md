# TPV Remote Command System

## Architecture Overview

TPV terminals receive remote commands from the dashboard through a polling-based system
(Square Terminal API pattern), ensuring reliable delivery even when Socket.IO is unavailable.

## Command Flow

### 1. Dashboard -> Server
- Dashboard sends `POST /api/v1/dashboard/tpv/:terminalId/command`
- Server queues command in `TpvCommandQueue` with status `QUEUED`
- Socket.IO broadcast notifies connected clients (optional, for UI updates)

### 2. Server -> TPV (via Heartbeat Polling)
- TPV sends heartbeat every 30 seconds via `HeartbeatWorker`
- Server responds with pending commands in HTTP response body
- Commands delivered reliably even on login screen (no socket connection required)

### 3. TPV Execution
- `HeartbeatWorker.processPendingCommands()` receives commands from heartbeat response
- `CommandExecutor.execute()` handles each command type
- Commands execute in order, results stored locally

### 4. TPV -> Server (HTTP ACK)
- `HeartbeatRepository.sendCommandAck()` sends result via HTTP POST
- Server updates `TpvCommandQueue` status to COMPLETED/FAILED
- Dashboard receives update via Socket.IO broadcast or polling

## Sequence Diagram

```
Dashboard                  Server                     TPV
   |                         |                         |
   |--POST /command--------->|                         |
   |                         |--Queue in DB----------->|
   |<--Command ID------------|                         |
   |                         |                         |
   |                         |<---Heartbeat (30s)------|
   |                         |---Commands in response->|
   |                         |                         |--Execute
   |                         |                         |
   |                         |<---HTTP ACK-------------|
   |<--Socket.IO update------|                         |
   |                         |                         |
```

## Key Files (Android)

| File | Purpose |
|------|---------|
| `HeartbeatWorker.kt` | Sends heartbeats every 30s, receives commands via response |
| `CommandExecutor.kt` | Executes commands (lock, maintenance, restart, etc.) |
| `HeartbeatRepository.kt` | HTTP calls for heartbeat and command ACK |
| `MaintenanceManager.kt` | Singleton managing maintenance mode state |
| `LockScreenManager.kt` | Singleton managing lock screen state |

### File Locations

```
app/src/main/java/com/jaac/avoqado_tpv/
├── core/data/workers/
│   └── HeartbeatWorker.kt          # Heartbeat polling + command delivery
├── core/data/repository/
│   └── HeartbeatRepository.kt      # HTTP client for heartbeat/ACK
├── core/data/manager/
│   ├── MaintenanceManager.kt       # Maintenance mode singleton
│   └── LockScreenManager.kt        # Lock screen singleton
└── features/remote_command/
    └── domain/
        └── CommandExecutor.kt      # Command execution logic
```

## Command Types

| Command | Description | Executor Method |
|---------|-------------|-----------------|
| `LOCK` | Lock terminal remotely | `executeLock()` |
| `UNLOCK` | Unlock terminal remotely | `executeUnlock()` |
| `MAINTENANCE_MODE` | Enter maintenance mode | `executeMaintenanceMode()` |
| `EXIT_MAINTENANCE` | Exit maintenance mode | `executeExitMaintenance()` |
| `RESTART` | Restart TPV application | `executeRestart()` |
| `SHUTDOWN` | Close TPV application | `executeShutdown()` |
| `SYNC_DATA` | Force data synchronization | `executeSyncData()` |
| `REFRESH_MENU` | Refresh menu from server | `executeRefreshMenu()` |
| `FORCE_UPDATE` | Check Firebase App Distribution for updates | `executeForceUpdate()` |
| `UPDATE_STATUS` | Send immediate status heartbeat | `executeUpdateStatus()` |

## Design Decisions

### 1. HTTP ACK over Socket.IO ACK
**Why:** Socket.IO may not be connected (login screen), HTTP is always available.
**Implementation:** `HeartbeatRepository.sendCommandAck()` - dedicated HTTP endpoint.

### 2. Heartbeat Polling Pattern (Square Terminal API)
**Why:** Ensures commands reach terminal even without persistent socket connection.
**Implementation:** Commands returned in heartbeat HTTP response body.

### 3. Singleton Managers for State
**Why:** Maintain consistent state across the app, survive activity recreation.
**Implementation:** `MaintenanceManager` and `LockScreenManager` use Hilt `@Singleton` scope.

### 4. 30-Second Heartbeat Interval
**Why:** Balance between responsiveness and battery/network efficiency.
**Calculation:** 4 heartbeats within 2-minute timeout window.

### 5. Heartbeat Does NOT Change Server Status (2025-12-01)
**Why:** Prevents race conditions with command execution.
**Problem Solved:** Terminal would enter maintenance locally, but next heartbeat would reset server status to ACTIVE.
**Implementation:** Server ignores heartbeat status for MAINTENANCE terminals, only commands change status.

## Command Status Flow

```
PENDING -> QUEUED -> SENT -> RECEIVED -> EXECUTING -> COMPLETED/FAILED
                                                   |
                                                   +-> EXPIRED (timeout)
```

## Previous Issues Fixed

### Issue 1: Command ID Mismatch (2025-11-30)
**Problem:** Socket.IO used `correlationId` but ACK handler expected database `id`.
**Fix:** Server ACK handler now accepts both `id` and `correlationId` with fallback logic.

### Issue 2: Invalid Enum Value (2025-11-30)
**Problem:** TPV sent `resultStatus: 'ERROR'` but enum only had `FAILED`.
**Fix:** Server maps `ERROR` -> `FAILED` before database update.

### Issue 3: Dual ACK (Socket.IO + HTTP) Race Condition (2025-11-30)
**Problem:** TPV sent ACK via both Socket.IO AND HTTP, causing race conditions.
**Fix:** Removed Socket.IO ACK from `CommandExecutor`, HTTP-only flow via `HeartbeatRepository`.

### Issue 4: Maintenance Mode Reset by Heartbeat (2025-12-01)
**Problem:** Terminal entered maintenance, but heartbeat sent ACTIVE status, resetting server.
**Fix:** Server ignores heartbeat status for status changes, only explicit commands change status.

### Issue 5: Dashboard/TPV State Desync (2025-12-01)
**Problem:** Dashboard showed MAINTENANCE but TPV was ACTIVE. EXIT_MAINTENANCE was REJECTED but dashboard didn't update.
**Fix (Server):** Server now syncs state when commands are REJECTED:
- EXIT_MAINTENANCE REJECTED → Server sets ACTIVE (TPV isn't in maintenance)
- MAINTENANCE_MODE REJECTED → Server sets MAINTENANCE (TPV is already in maintenance)
- LOCK REJECTED → Server sets isLocked=true (TPV is already locked)
- UNLOCK REJECTED → Server sets isLocked=false (TPV isn't locked)

### Issue 6: Socket.IO Commands Not Sending HTTP ACK (2025-12-01)
**Problem:** Commands received via Socket.IO were executed locally but no HTTP ACK was sent to server.
- `HeartbeatWorker` path: ✅ Sent HTTP ACK
- `HomeViewModel` path (Socket.IO): ❌ NO ACK was sent
**Root Cause:** Server state sync on REJECTED depended on receiving the ACK, but Socket.IO path never sent it.
**Fix:** Added `HeartbeatRepository.sendCommandAck()` call to `HomeViewModel.executeRemoteCommand()`:
```kotlin
// Execute command
val result = commandExecutor.execute(command)
// Send HTTP ACK (CRITICAL FIX)
val ackResult = heartbeatRepository.sendCommandAck(command.commandId, terminalId, result)
```

### Issue 7: State Lost on App Restart (2025-12-01)
**Problem:** Lock and Maintenance states were lost when app was restarted or reinstalled.
- User puts terminal in MAINTENANCE from dashboard
- User force-closes app or app crashes
- On restart, terminal shows ACTIVE but server shows MAINTENANCE
- Security risk: User could bypass LOCK by restarting app!
**Root Cause:** `MaintenanceManager` and `LockScreenManager` only used `MutableStateFlow(false)` in memory.
**Fix:** Added `SecureStorage` persistence to both managers:
- State saved to `EncryptedSharedPreferences` on every state change
- State restored from storage on manager initialization
- Survives: app restart, device reboot, app reinstall

```kotlin
// MaintenanceManager now persists state
class MaintenanceManager @Inject constructor(
    private val secureStorage: SecureStorage
) {
    // Initialized from SecureStorage!
    private val _isInMaintenance = MutableStateFlow(secureStorage.getIsInMaintenance())

    fun enterMaintenance(reason: String?, initiatedBy: String?) {
        _isInMaintenance.value = true
        // Persist to survive app restart
        secureStorage.saveMaintenanceState(true, reason, initiatedBy)
    }
}
```

**Files Changed:**
- `SecureStorage.kt` - Added `saveLockState()`, `saveMaintenanceState()` methods
- `MaintenanceManager.kt` - Uses SecureStorage for persistence
- `LockScreenManager.kt` - Uses SecureStorage for persistence

## Testing Commands

### From Dashboard
1. Navigate to TPV detail page
2. Toggle Lock or Maintenance switch
3. Observe loading spinner during command execution
4. Switch updates only after server confirms

### From Server Logs
```bash
# Watch for command flow
tail -f logs/development*.log | grep -E "(command|heartbeat|ACK)"
```

### From Android Logs
```bash
adb logcat | grep -E "(HeartbeatWorker|CommandExecutor|MaintenanceManager)"
```

## Common Issues

### Command Not Received
1. Check if terminal is sending heartbeats (server logs)
2. Verify terminal is online (lastHeartbeat < 2 minutes ago)
3. Check command status in TpvCommandQueue table

### Command Stuck in SENT
1. Terminal received but didn't ACK
2. Check Android logs for execution errors
3. Verify HTTP ACK endpoint is reachable

### Maintenance Mode Resets
1. **Fixed in 2025-12-01** - Heartbeat no longer overrides MAINTENANCE status
2. **Fixed in 2025-12-01** - State now persists across app restarts (SecureStorage)
3. If still occurring, check for other code paths that might change status

### State Lost on App Restart (Persistence Testing)
1. Put terminal in MAINTENANCE from dashboard
2. Force-close app: `adb shell am force-stop com.jaac.avoqado_tpv.sandbox`
3. Reopen app
4. **Expected:** Terminal should show MAINTENANCE overlay immediately
5. Check logs: `adb logcat | grep "State restored from storage"`

## Database Tables

```sql
-- Check pending commands for a terminal
SELECT id, correlationId, commandType, status, resultStatus, createdAt
FROM "TpvCommandQueue"
WHERE "terminalId" = 'xxx'
ORDER BY "createdAt" DESC
LIMIT 10;

-- Check terminal status
SELECT id, serialNumber, status, lastHeartbeat
FROM "Terminal"
WHERE id = 'xxx';
```
