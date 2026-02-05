# Force Update System (Backend Enforcement)

## Overview

This document describes the **3-layer force update enforcement system** implemented following Square/Toast/Stripe patterns. This system ensures critical updates cannot be bypassed by users.

**Last Updated:** 2026-02-05

---

## Architecture: 3-Layer Enforcement

```
┌──────────────────────────────────────────────────────────────────┐
│                    FORCE UPDATE ENFORCEMENT                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Layer 1: API VERSION GATE (HTTP 426) - STRONGEST                │
│  ═══════════════════════════════════════════════════════════════ │
│  • Every API request includes X-App-Version-Code header          │
│  • Backend middleware rejects outdated versions with 426         │
│  • App CANNOT function until updated - all API calls fail        │
│  • IMPOSSIBLE to bypass                                          │
│                                                                   │
│  Layer 2: HEARTBEAT NOTIFICATION - PROACTIVE                     │
│  ═══════════════════════════════════════════════════════════════ │
│  • Every 30 seconds, heartbeat includes forceUpdate if pending   │
│  • Shows ForceUpdateDialog even if user is idle                  │
│  • Reminder mechanism for long-running sessions                  │
│                                                                   │
│  Layer 3: STARTUP CHECK - INITIAL                                │
│  ═══════════════════════════════════════════════════════════════ │
│  • Check for updates when app opens                              │
│  • Show dialog/banner immediately                                │
│  • First line of defense                                         │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## Files Modified/Created

### Backend (`avoqado-server`)

| File | Purpose |
|------|---------|
| `src/middlewares/tpv-version-gate.middleware.ts` | **NEW** - API version gate (426 response) |
| `src/controllers/tpv/heartbeat.tpv.controller.ts` | Added `forceUpdate` to heartbeat response |
| `src/routes/index.ts` | Applied `tpvVersionGate` to TPV routes |

### Android (`avoqado-tpv`)

| File | Purpose |
|------|---------|
| `interceptors/AuthInterceptor.kt` | Adds `X-App-Version-Code` and `X-App-Version-Name` headers |
| `interceptors/VersionGateInterceptor.kt` | **NEW** - Handles HTTP 426, triggers ForceUpdateDialog |
| `util/UpdateCheckManager.kt` | Added `setForceUpdate()` and `setForceUpdateFromHeartbeat()` |
| `dto/HeartbeatDto.kt` | Added `ForceUpdateDto` and `forceUpdate` field |
| `workers/HeartbeatWorker.kt` | Processes `forceUpdate` from heartbeat response |
| `di/NetworkModule.kt` | Added `VersionGateInterceptor` to OkHttpClient |
| `self_update/data/AvoqadoUpdateRepository.kt` | Skip checksum for heartbeat placeholder values |
| `components/ForceUpdateDialog.kt` | Blocking modal dialog (cannot dismiss) |

---

## Backend Implementation Details

### Version Gate Middleware

```typescript
// tpv-version-gate.middleware.ts

// Paths excluded from version gate (must work on old versions)
const EXCLUDED_PATHS = [
  '/heartbeat',           // Health monitoring
  '/command-ack',         // Command acknowledgment
  '/auth/pin-login',      // Login
  '/auth/refresh-token',  // Token refresh
  '/activate',            // Terminal activation
  '/check-update',        // Update check endpoint
]

// Middleware logic
export async function tpvVersionGate(req, res, next) {
  // Skip excluded paths
  if (isExcludedPath(req.path)) return next()

  // No header = old version = allow (backwards compatible)
  const versionCode = req.headers['x-app-version-code']
  if (!versionCode) return next()

  // Check if FORCE update exists with higher version
  const minVersion = await getMinimumRequiredVersion(environment)
  if (!minVersion || clientVersion >= minVersion) return next()

  // Block with 426
  res.status(426).json({
    error: 'UPGRADE_REQUIRED',
    minVersionCode: minVersion,
    update: { versionName, versionCode, downloadUrl, releaseNotes }
  })
}
```

### Heartbeat Force Update

```typescript
// heartbeat.tpv.controller.ts

// Check for forced updates
const forceUpdate = await checkForForcedUpdate(version, versionCode)

res.json({
  success: true,
  serverStatus: terminalHealth.status,
  pendingCommands: pendingCommands,
  configVersion: configVersion,
  forceUpdate: forceUpdate  // ← Added for force update enforcement
})
```

---

## Android Implementation Details

### Version Headers (AuthInterceptor)

```kotlin
// AuthInterceptor.kt
val requestBuilder = originalRequest.newBuilder()
    .header("X-App-Version-Code", BuildConfig.VERSION_CODE.toString())
    .header("X-App-Version-Name", BuildConfig.VERSION_NAME)
```

### 426 Handler (VersionGateInterceptor)

```kotlin
// VersionGateInterceptor.kt
if (response.code == 426) {
    val updateInfo = parseUpdateFromResponse(response)
    updateCheckManagerLazy.get().setForceUpdate(updateInfo)
}
```

### Heartbeat Handler (HeartbeatWorker)

```kotlin
// HeartbeatWorker.kt
val forceUpdate = heartbeatResult.data.forceUpdate
if (forceUpdate != null) {
    updateCheckManager.setForceUpdateFromHeartbeat(forceUpdate)
}
```

---

## Update Modes

| Mode | Behavior | Use Case |
|------|----------|----------|
| `NONE` | No notification | Silent release |
| `BANNER` | Persistent banner, can ignore | Regular updates |
| `FORCE` | Blocking modal, 426 API block | Critical/security updates |

---

## Backwards Compatibility

**Old versions (< 1.4.2) without version headers:**

```
┌─────────────────────────────────────────────────────────────┐
│  Terminal 1.3.5 → No X-App-Version-Code header              │
│                  ↓                                          │
│  Middleware: "No header? Allow request" → next()            │
│                  ↓                                          │
│  App works normally (backwards compatible)                  │
└─────────────────────────────────────────────────────────────┘
```

**Migration path:**
1. Old terminals (1.3.x) work normally - no headers, no blocking
2. Admin manually updates to 1.4.x
3. New terminals have headers - force update system active
4. Future FORCE updates will block 1.4.x when needed

---

## Dashboard Configuration

To enable force update:

1. Go to **Superadmin → TPV Updates**
2. Upload APK or edit existing version
3. Select **"Forzar"** in "Modo de Notificación"
4. Save

**Database fields:**
- `updateMode`: `'NONE'` | `'BANNER'` | `'FORCE'`
- `isActive`: Must be `true` for update to be enforced
- `versionCode`: Must be higher than terminal's current version

---

## Testing Checklist

- [ ] Upload APK with FORCE mode to dashboard
- [ ] Install older version on terminal
- [ ] Open app → ForceUpdateDialog appears
- [ ] Try any action → HTTP 426 → ForceUpdateDialog appears
- [ ] Cannot dismiss dialog (no close button, back button disabled)
- [ ] Update downloads and installs
- [ ] App restarts with new version
- [ ] No more ForceUpdateDialog

---

## Troubleshooting

### Dialog not appearing?
1. Check DB: `SELECT updateMode FROM AppUpdate WHERE versionCode = X`
2. Verify `isActive = true`
3. Verify terminal's versionCode < update's versionCode
4. Check logs for `[VersionGate]` or `[Heartbeat] FORCE update`

### Checksum error?
Updates from heartbeat/426 use placeholder checksums (`"heartbeat"`, `"version-gate"`).
These are skipped in `AvoqadoUpdateRepository.downloadApk()`.

### Old terminals still working?
This is expected! Terminals without `X-App-Version-Code` header are allowed through for backwards compatibility. They must be manually updated to 1.4.x first.

---

## Security Considerations

1. **Cannot bypass**: HTTP 426 blocks ALL API calls - app is useless without update
2. **Backwards compatible**: Old versions still work (gradual migration)
3. **Admin controlled**: Only admins can mark updates as FORCE
4. **Cached efficiently**: Minimum version cached 60s to avoid DB queries on every request

---

## Related Documents

- `docs/DEVELOPMENT_WORKFLOW.md` - Build variants (sandbox/production)
- `docs/TPV_COMMAND_FLOW.md` - Remote command system
- `CLAUDE.md` Section 10 - Release build checklist
