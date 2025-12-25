# Observability System Guide

**Complete guide to terminal monitoring, logging, and health tracking**

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Quick Start](#quick-start)
4. [Android Components](#android-components)
5. [Backend Components](#backend-components)
6. [Usage Examples](#usage-examples)
7. [Dashboard Integration](#dashboard-integration)
8. [Troubleshooting](#troubleshooting)
9. [Production Checklist](#production-checklist)

---

## Overview

The Avoqado observability system provides **enterprise-grade monitoring** for production TPV terminals, inspired by Toast, Square, and Clover fleet management systems.

### Features

✅ **Firebase Crashlytics** - Automatic crash reporting with stack traces
✅ **Remote Logging** - Real-time logs via Socket.IO to backend
✅ **File Logging** - Offline fallback when network unavailable
✅ **Health Monitoring** - Proactive terminal health metrics (memory, battery, uptime)
✅ **Dashboard Integration** - Real-time visibility in web dashboard

### Why This Matters

In production, you can't use `adb logcat` to debug terminals in the field. This system gives you:

- 📊 Real-time visibility into terminal health
- 🔍 Historical logs for debugging customer issues
- ⚡ Proactive alerts before failures occur
- 📈 Fleet-wide analytics (memory usage, crash rates, etc.)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android TPV Terminal                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │          ObservabilityManager (Singleton)            │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────────┐   │  │
│  │  │ Crashlytics│ │RemoteLogger│ │  FileLogger    │   │  │
│  │  │  (Firebase)│ │ (Socket.IO)│ │  (Local Disk)  │   │  │
│  │  └────────────┘ └────────────┘ └────────────────┘   │  │
│  │                                                        │  │
│  │  ┌──────────────────────────────────────────────┐    │  │
│  │  │         HealthMonitor (Every 5 min)          │    │  │
│  │  └──────────────────────────────────────────────┘    │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────┬────────────────────────────────────────┘
                     │ Socket.IO (tpv:log, tpv:heartbeat)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  Backend (avoqado-server)                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         ObservabilityController                       │  │
│  │   • Receives logs → PostgreSQL (TerminalLog)         │  │
│  │   • Receives heartbeats → PostgreSQL (TerminalHealth)│  │
│  │   • Broadcasts to dashboard subscribers              │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────────┬────────────────────────────────────────┘
                     │ Socket.IO (terminal:log, terminal:heartbeat)
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              Web Dashboard (React)                          │
│  • Real-time log viewer                                     │
│  • Terminal health dashboard                                │
│  • Historical analytics                                     │
└─────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Step 1: Initialize in Application Class

The `ObservabilityManager` must be initialized **after authentication** to set terminal context.

**Option A: After login (Recommended)**

```kotlin
// In LoginViewModel or AuthRepository after successful login
@Inject lateinit var observabilityManager: ObservabilityManager

suspend fun loginSuccess(authContext: AuthContext) {
    // Initialize observability with terminal context
    observabilityManager.initialize(
        venueId = authContext.venueId,
        terminalId = terminalId, // From Terminal.id
        userId = authContext.userId
    )

    // Observability system is now active
    observabilityManager.logInfo("Auth", "User logged in successfully", mapOf(
        "userId" to authContext.userId,
        "role" to authContext.role
    ))
}
```

**Option B: In Application.onCreate (if pre-authenticated)**

```kotlin
// In AvoqadoTPVApplication.kt
@Inject lateinit var observabilityManager: ObservabilityManager
@Inject lateinit var authRepository: AuthRepository

override fun onCreate() {
    super.onCreate()

    // Later, after authentication is restored
    viewModelScope.launch {
        authRepository.getAuthContext()?.let { authContext ->
            observabilityManager.initialize(
                venueId = authContext.venueId,
                terminalId = getTerminalId(),
                userId = authContext.userId
            )
        }
    }
}
```

### Step 2: Use in ViewModels

```kotlin
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val observability: ObservabilityManager
) : ViewModel() {

    suspend fun processPayment(amount: BigDecimal) {
        try {
            observability.logInfo("Payment", "Payment initiated", mapOf(
                "amount" to amount.toString(),
                "orderId" to orderId
            ))

            val result = blumonSDK.processPayment(amount)

            observability.logInfo("Payment", "Payment successful", mapOf(
                "transactionId" to result.transactionId
            ))
        } catch (e: Exception) {
            observability.logError("Payment", "Payment failed", e, mapOf(
                "amount" to amount.toString(),
                "errorCode" to e.errorCode
            ))
            throw e
        }
    }
}
```

---

## Android Components

### ObservabilityManager

**Singleton** that coordinates all observability systems.

```kotlin
// Inject in any class
@Inject lateinit var observability: ObservabilityManager

// Initialize (once, after auth)
observability.initialize(venueId, terminalId, userId)

// Log events
observability.logInfo(tag, message, metadata)
observability.logWarning(tag, message, metadata)
observability.logError(tag, message, error, metadata)
observability.logCritical(tag, message, error, metadata) // High-priority alerts

// Breadcrumbs (for debugging context)
observability.addBreadcrumb("Navigation", "Opened payment screen")

// Flush logs before shutdown
observability.flush()
observability.shutdown()
```

### RemoteLogger

Streams logs to backend via Socket.IO.

- **Batching**: Sends logs in batches of 10 every 5 seconds (efficiency)
- **Queue**: Max 100 logs buffered offline
- **Retry**: Automatic retry with exponential backoff

```kotlin
// Managed by ObservabilityManager - you don't call this directly
```

### FileLogger

Writes logs to local storage for offline debugging.

- **Retention**: 7 days max
- **Size limit**: 10MB total (auto-cleanup)
- **Format**: JSON (one log per line)
- **Location**: `/data/data/com.jaac.avoqado_tpv/files/logs/`

```kotlin
// Access logs manually
val logs = fileLogger.getRecentLogs(maxLines = 100)
val logFiles = fileLogger.getLogFiles()
```

### HealthMonitor

Sends terminal health metrics every 5 minutes.

**Metrics collected:**
- Memory: Total, available, usage %
- Storage: Total, available, usage %
- Battery: Level, charging status, temperature
- Connectivity: Socket.IO connection, online status
- Device: Manufacturer, model, OS version, app version
- Uptime: Minutes since app start

**Health Score** (0-100):
- 90-100: Healthy
- 70-89: Degraded (low memory OR low battery)
- <70: Critical (multiple issues OR offline >30min)

```kotlin
// Starts automatically when ObservabilityManager is initialized
// Stops when ObservabilityManager.shutdown() is called
```

---

## Backend Components

### Prisma Models

#### TerminalLog

Stores real-time logs from terminals.

```prisma
model TerminalLog {
  id         String   @id @default(cuid())
  venueId    String
  terminalId String
  level      LogLevel // INFO, WARN, ERROR
  tag        String
  message    String   @db.Text
  error      String?  @db.Text
  metadata   Json?
  timestamp  BigInt
  createdAt  DateTime @default(now())
}
```

**Query examples:**

```typescript
// Get recent errors for terminal
const errors = await prisma.terminalLog.findMany({
  where: {
    terminalId,
    level: 'ERROR',
    createdAt: { gte: new Date(Date.now() - 24 * 60 * 60 * 1000) } // Last 24h
  },
  orderBy: { timestamp: 'desc' },
  take: 50
})

// Get logs by tag
const paymentLogs = await prisma.terminalLog.findMany({
  where: {
    venueId,
    tag: 'Payment',
    level: { in: ['ERROR', 'WARN'] }
  },
  orderBy: { timestamp: 'desc' }
})
```

#### TerminalHealth

Stores periodic health snapshots.

```prisma
model TerminalHealth {
  id                  String   @id @default(cuid())
  venueId             String
  terminalId          String
  healthScore         Int      // 0-100
  memoryTotalMB       Int
  memoryAvailableMB   Int
  batteryLevel        Int?
  batteryCharging     Boolean
  lowMemory           Boolean
  lowBattery          Boolean
  timestamp           DateTime
  createdAt           DateTime @default(now())
  // ... more fields
}
```

**Query examples:**

```typescript
// Get latest health for terminal
const latestHealth = await prisma.terminalHealth.findFirst({
  where: { terminalId },
  orderBy: { timestamp: 'desc' }
})

// Get unhealthy terminals
const unhealthyTerminals = await prisma.terminalHealth.findMany({
  where: {
    venueId,
    healthScore: { lt: 70 },
    timestamp: { gte: new Date(Date.now() - 15 * 60 * 1000) } // Last 15min
  },
  distinct: ['terminalId'],
  orderBy: { timestamp: 'desc' }
})
```

### Socket.IO Events

#### `tpv:log` (Terminal → Server)

```typescript
socket.emit('tpv:log', {
  level: 'ERROR',
  tag: 'Payment',
  message: 'Payment failed',
  error: 'NA_002: Invalid serial number',
  metadata: { amount: 100.50, orderId: 'abc123' },
  terminalId: 'cm123abc',
  venueId: 'cm456def',
  timestamp: 1734567890000
})
```

#### `tpv:heartbeat` (Terminal → Server)

```typescript
socket.emit('tpv:heartbeat', {
  terminalId: 'cm123abc',
  venueId: 'cm456def',
  healthScore: 85,
  health: {
    memory: { totalMB: 1024, availableMB: 256, usagePercent: 75, lowMemory: false },
    storage: { totalMB: 8192, availableMB: 2048, usagePercent: 75, lowStorage: false },
    battery: { level: 45, isCharging: false, temperatureCelsius: 35, lowBattery: false },
    connectivity: { socketConnected: true, online: true },
    device: { manufacturer: 'PAX', model: 'A920', osVersion: '8.1', appVersion: '1.0.0', appVersionCode: 1, blumonEnv: 'PROD' },
    uptime: { uptimeMinutes: 120 }
  },
  timestamp: 1734567890000
})
```

#### `terminal:log` (Server → Dashboard)

Dashboard subscribers receive real-time logs.

```typescript
// In React dashboard
socket.on('terminal:log', (logData) => {
  console.log('New log:', logData)
  // Update UI with new log entry
})
```

#### `terminal:heartbeat` (Server → Dashboard)

Dashboard subscribers receive health updates.

```typescript
socket.on('terminal:heartbeat', (healthData) => {
  console.log('Health update:', healthData)
  // Update terminal health UI
})
```

---

## Usage Examples

### Example 1: Payment Flow

```kotlin
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val observability: ObservabilityManager,
    private val blumonSDK: BlumonSDK
) : ViewModel() {

    suspend fun processPayment(amount: BigDecimal, orderId: String) {
        observability.logInfo("Payment", "Payment flow started", mapOf(
            "amount" to amount.toString(),
            "orderId" to orderId
        ))

        try {
            // Step 1: Initialize terminal
            observability.addBreadcrumb("Payment", "Initializing Blumon SDK")
            blumonSDK.initialize()

            // Step 2: Detect card
            observability.addBreadcrumb("Payment", "Waiting for card")
            val card = blumonSDK.detectCard()

            observability.logInfo("Payment", "Card detected", mapOf(
                "cardType" to card.type,
                "last4" to card.last4
            ))

            // Step 3: Process payment
            observability.addBreadcrumb("Payment", "Processing payment")
            val result = blumonSDK.processPayment(amount)

            observability.logInfo("Payment", "Payment successful", mapOf(
                "transactionId" to result.transactionId,
                "approvalCode" to result.approvalCode,
                "amount" to amount.toString()
            ))

        } catch (e: BlumonException) {
            // Critical error - needs immediate attention
            observability.logCritical("Payment", "Payment processing failed", e, mapOf(
                "amount" to amount.toString(),
                "orderId" to orderId,
                "errorCode" to e.errorCode,
                "blumonMessage" to e.blumonMessage
            ))
            throw e
        }
    }
}
```

### Example 2: Menu Sync

```kotlin
@HiltViewModel
class MenuViewModel @Inject constructor(
    private val observability: ObservabilityManager,
    private val menuRepository: MenuRepository
) : ViewModel() {

    suspend fun syncMenu() {
        observability.logInfo("Menu", "Menu sync started")

        try {
            val menu = menuRepository.fetchMenuFromBackend()

            observability.logInfo("Menu", "Menu synced successfully", mapOf(
                "productCount" to menu.products.size,
                "categoryCount" to menu.categories.size
            ))
        } catch (e: NetworkException) {
            observability.logWarning("Menu", "Menu sync failed (network)", e, mapOf(
                "willRetry" to true
            ))
            // Will retry later
        } catch (e: Exception) {
            observability.logError("Menu", "Menu sync failed", e)
            throw e
        }
    }
}
```

### Example 3: Socket.IO Connection

```kotlin
@Singleton
class SocketService @Inject constructor(
    private val observability: ObservabilityManager
) {

    fun connect() {
        try {
            socket.connect()
            observability.logInfo("Socket", "Socket.IO connected")
        } catch (e: Exception) {
            observability.logError("Socket", "Socket.IO connection failed", e)
        }
    }

    fun onReconnect() {
        observability.logWarning("Socket", "Socket.IO reconnected after disconnect", mapOf(
            "downtime" to downtimeMs
        ))
    }
}
```

---

## Dashboard Integration

### Real-Time Log Viewer (React)

```typescript
import { useEffect, useState } from 'react'
import { socket } from '@/lib/socket'

export function TerminalLogs({ terminalId }: { terminalId: string }) {
  const [logs, setLogs] = useState<TerminalLog[]>([])

  useEffect(() => {
    // Subscribe to real-time logs
    socket.on('terminal:log', (logData) => {
      if (logData.terminalId === terminalId) {
        setLogs(prev => [logData, ...prev].slice(0, 100))
      }
    })

    return () => {
      socket.off('terminal:log')
    }
  }, [terminalId])

  return (
    <div>
      {logs.map(log => (
        <div key={log.id} className={`log-${log.level.toLowerCase()}`}>
          <span>{new Date(log.timestamp).toLocaleTimeString()}</span>
          <span>[{log.tag}]</span>
          <span>{log.message}</span>
        </div>
      ))}
    </div>
  )
}
```

### Terminal Health Dashboard

```typescript
export function TerminalHealth({ terminalId }: { terminalId: string }) {
  const [health, setHealth] = useState<TerminalHealthData | null>(null)

  useEffect(() => {
    socket.on('terminal:heartbeat', (healthData) => {
      if (healthData.terminalId === terminalId) {
        setHealth(healthData)
      }
    })
  }, [terminalId])

  if (!health) return <div>Waiting for heartbeat...</div>

  const statusColor = health.healthScore >= 90 ? 'green' : health.healthScore >= 70 ? 'yellow' : 'red'

  return (
    <div>
      <h3>Terminal Health: {health.healthScore}/100</h3>
      <div style={{ color: statusColor }}>
        {health.healthScore >= 90 ? '✅ Healthy' : health.healthScore >= 70 ? '⚠️ Degraded' : '❌ Critical'}
      </div>

      <div>
        <h4>Memory</h4>
        <p>{health.health.memory.availableMB} MB / {health.health.memory.totalMB} MB available</p>
        {health.health.memory.lowMemory && <p className="warning">⚠️ Low memory</p>}
      </div>

      <div>
        <h4>Battery</h4>
        <p>{health.health.battery.level}% {health.health.battery.isCharging ? '(Charging)' : ''}</p>
        {health.health.battery.lowBattery && <p className="warning">⚠️ Low battery</p>}
      </div>
    </div>
  )
}
```

---

## Troubleshooting

### Logs not appearing in dashboard

**Check:**
1. Is ObservabilityManager initialized? (should see "Observability system initialized" in console)
2. Is Socket.IO connected? (`SocketService.isConnected()`)
3. Is terminal in production build? (logs disabled in debug builds)
4. Check backend logs for errors in `ObservabilityController`

**Solution:**
```kotlin
// Verify initialization
observability.logInfo("Test", "Test log message")
// Should appear in dashboard within 5 seconds
```

### Health metrics not updating

**Check:**
1. Is HealthMonitor running? (check logs for "HealthMonitor started")
2. Is heartbeat interval correct? (default 5 minutes)
3. Is Socket.IO connected?

**Solution:**
```kotlin
// Force health update
healthMonitor.sendHeartbeat()
```

### OutOfMemoryError when logging

**Cause:** Too many logs buffered offline
**Solution:** FileLogger and RemoteLogger automatically limit queue size (100 logs max)

```kotlin
// Force flush to clear queue
observability.flush()
```

### Crashlytics not reporting crashes

**Check:**
1. Is Firebase properly configured? (`google-services.json` present)
2. Is this a release build? (Crashlytics only active in release)
3. Check Firebase console for crashes (may take 5-10 minutes to appear)

---

## Production Checklist

Before deploying to production:

- [ ] Firebase Crashlytics enabled in `google-services.json`
- [ ] ObservabilityManager initialized after authentication
- [ ] Critical payment flows have error logging
- [ ] Health monitoring starts on app launch
- [ ] Dashboard has real-time log viewer
- [ ] Alerting configured for critical errors (optional)
- [ ] Log retention policy configured (default 30 days)
- [ ] Test observability with simulated errors

**Test command:**
```kotlin
// In any ViewModel
observability.logCritical("Test", "Test critical error", Exception("Test exception"), mapOf(
    "test" to true
))
// Should appear in Firebase Crashlytics + Dashboard
```

---

## Performance Impact

- **CPU**: Negligible (<1% overhead)
- **Memory**: ~5MB for log buffers
- **Network**: ~10KB per heartbeat (every 5 min)
- **Disk**: Max 10MB for file logs

**Best Practices:**
- Only log ERROR and WARN in production (automatic)
- Avoid logging sensitive data (PINs, card numbers)
- Use breadcrumbs for navigation flow (not every screen)

---

## Future Enhancements

- [ ] Real-time alerts (Slack/Email) for critical errors
- [ ] Historical analytics dashboard (crash rates, memory trends)
- [ ] Log search and filtering in dashboard
- [ ] Export logs as CSV for analysis
- [ ] Integration with Sentry or Datadog

---

**Last Updated:** 2025-12-18
**Version:** 1.0
**Maintainer:** Development Team
