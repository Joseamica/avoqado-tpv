# Offline Sync Architecture

**Pattern:** Square Terminal / Toast POS offline-first queue system

Avoqado TPV follows industry-standard offline-first architecture. Payments succeed locally via Blumon SDK, then queue for backend sync. Terminal remains operational even when backend is unreachable.

## Core Philosophy

| Principle | Implementation |
|-----------|----------------|
| **Offline-first** | Blumon SDK approves payment locally, backend sync is secondary |
| **Idempotent** | Blumon `referenceNumber` prevents duplicate charges on retry |
| **Eventually consistent** | Queued payments sync when network available (15-min interval) |
| **Graceful degradation** | Terminal works without backend (payments already approved) |
| **Multi-queue** | Separate queues for payments (money) vs verification (photos) |

## Payment Queue Architecture

### 1. Queue Trigger

Payment queued when backend recording fails **after** Blumon SDK approves:

```kotlin
// PaymentViewModel flow
1. Blumon SDK → approveTransaction() → SUCCESS (card charged)
2. Backend API → recordPayment() → FAILS (network timeout, 500, etc.)
3. PaymentQueueRepository.enqueue() → Save to Room DB
4. Show success screen (payment already approved, sync happens in background)
```

### 2. Data Schema

**Room Entity:** `PendingPaymentEntity` (table: `pending_payments`)

| Field | Type | Purpose |
|-------|------|---------|
| `id` | Long (PK) | Queue ID (auto-increment) |
| `referenceNumber` | String (UNIQUE) | Blumon idempotency key (e.g., "000000188231") |
| `merchantAccountId` | String | Provider-agnostic merchant FK |
| `deviceSerialNumber` | String | Terminal attribution (e.g., "AVQD-2841548417") |
| `amount` / `tip` | String (BigDecimal) | Payment amounts |
| `maskedPan` / `cardBrand` | String | Card details for receipt |
| `authorizationNumber` | String | Blumon auth code (e.g., "502511") |
| `syncStatus` | String | `PENDING` / `SYNCING` / `SUCCESS` / `FAILED` |
| `retryCount` | Int | Retry attempts (max 3) |
| `lastError` | String | Last error message for debugging |
| `createdAt` | Long | Unix timestamp (for FIFO ordering) |

**Indexes:** `reference_number` (UNIQUE), `sync_status`, `created_at` (FIFO)

### 3. Retry Strategy

**WorkManager:** `PaymentSyncWorker` runs every 15 minutes (standard: Square=15min, Toast=15min)

| Interval | Constraint | Policy |
|----------|-----------|--------|
| 15 minutes | `CONNECTED` network | `ExistingPeriodicWorkPolicy.KEEP` |

**Exponential Backoff (per payment):**

| Attempt | Delay | Cumulative |
|---------|-------|------------|
| 1 | 0s (immediate) | 0s |
| 2 | 1s | 1s |
| 3 | 2s | 3s |
| 4 (abort) | N/A | 3 attempts max |

**After 3 attempts:** Status → `FAILED` (manual review needed, appears in dashboard)

### 4. Error Classification

| HTTP Status | Action | Reason |
|-------------|--------|--------|
| 409 (duplicate) | Mark `SUCCESS` | Idempotency success (payment already recorded) |
| 400, 401, 403, 404 | Mark `FAILED` immediately | Client error won't fix itself |
| 500, 502, 503 | Retry with backoff | Server error might be temporary |
| Network timeout | Retry with backoff | Network might recover |

**Code:**
```kotlin
// PaymentSyncWorker.kt line 224-234
if (errorMessage.contains("duplicate", ignoreCase = true) ||
    errorMessage.contains("409") ||
    errorMessage.contains("idempoten")) {
    paymentQueueRepository.markSynced(payment.queueId) // ✅ Success
}

// line 237-253: Client errors (4xx) → FAILED
// line 256-276: Server errors / timeout → Retry
```

### 5. Idempotency Guarantee

**Key:** Blumon SDK generates unique `referenceNumber` per transaction (e.g., "000000188231")

**Backend:** Uses `referenceNumber` to detect duplicates:
- First request → Create payment record
- Retry requests → Return HTTP 409 or success (no duplicate charge)

**Room:** `UNIQUE` index on `reference_number` prevents double-queueing

**OnConflict:** `IGNORE` strategy (line 48) → Duplicate insert returns ID=0, not error

## Verification Queue Architecture

**Purpose:** Offline queue for sale verification photos/barcodes (SERIALIZED_INVENTORY module)

### 1. Data Schema

**Room Entity:** `VerificationQueueEntity` (table: `verification_queue`)

| Field | Type | Purpose |
|-------|------|---------|
| `id` | String (UUID PK) | Verification record ID |
| `paymentId` / `orderId` | String | Associated payment/order |
| `photoLocalPaths` | String (JSON) | Local file paths before Firebase upload |
| `photoUrls` | String (JSON) | Firebase Storage URLs after upload |
| `scannedBarcodes` | String (JSON) | Product barcode data |
| `syncStatus` | String | `PENDING` / `UPLOADING_PHOTOS` / `SYNCING` / `SYNCED` / `FAILED` |
| `syncAttempts` | Int | Retry counter |
| `lastSyncError` | String | Last error message |

### 2. Sync Flow

```
1. User captures photo → Save to local storage → photoLocalPaths
2. Attempt Firebase upload → On success: photoUrls + status=SYNCING
3. Send metadata to backend API → On success: status=SYNCED
4. WorkManager retries failed items periodically
```

**Status Progression:**
```
PENDING → UPLOADING_PHOTOS → SYNCING → SYNCED
           ↓ (failure)         ↓ (failure)
         FAILED ← ← ← ← ← ← ← ← ←
```

### 3. Progress Tracking

**DAO Methods:**
- `observePendingVerifications()` → Flow for reactive UI updates
- `updatePhotoUrls()` → Mark Firebase upload complete
- `recordSyncFailure()` → Increment `syncAttempts`, mark `FAILED`

**No dedicated worker yet** — verification sync handled via repository layer (TODO: create `VerificationSyncWorker`)

## Heartbeat Worker

**Purpose:** Device health monitoring + remote command polling (Square Terminal API pattern)

### 1. Execution Pattern

| Interval | Constraint | Policy |
|----------|-----------|--------|
| 30 seconds | `CONNECTED` network | `ExistingPeriodicWorkPolicy.REPLACE` |

**Lifecycle:**
```
Login → HeartbeatScheduler.start()
  → Every 30s: HeartbeatWorker.doWork()
  → Send device metrics + receive commands
Logout → HeartbeatScheduler.stop()
```

### 2. Metrics Sent

**Payload:** `Heartbeat` model (lines 259-287)

| Category | Fields |
|----------|--------|
| **Identity** | `terminalId` (e.g., "AVQD-2841548417"), `version`, `versionCode` |
| **Status** | `ACTIVE` / `MAINTENANCE` (from MaintenanceManager state) |
| **System** | Battery level (%), charging status, storage GB, memory MB |
| **Network** | Connection type (WiFi/Cellular), metered status |

### 3. Server Response

**HeartbeatResponse** includes:

| Field | Purpose |
|-------|---------|
| `serverStatus` | Acknowledge heartbeat received |
| `pendingCommands` | List of TPV commands (polling pattern, Square Terminal API) |
| `forceUpdate` | FORCE update APK version (cannot dismiss) |

### 4. Security: Terminal Retirement

**HTTP 404 for 5+ minutes (10 consecutive attempts):**
```kotlin
// HeartbeatWorker.kt line 203-227
if (httpError.code == 404) {
    currentCount++
    if (currentCount >= MAX_NOT_FOUND_BEFORE_CLEAR) { // 10
        secureStorage.clearAll() // Deactivate terminal
        HeartbeatScheduler.stop() // Stop heartbeat
        return Result.failure()
    }
}
```

**"Terminal retired" message:**
```kotlin
// line 176-188
if (errorMessage.contains("retired", ignoreCase = true)) {
    secureStorage.clearAll() // Force deactivation
    HeartbeatScheduler.stop()
    return Result.failure()
}
```

### 5. Remote Commands (HTTP Polling)

**Pattern:** Commands delivered via heartbeat response (not Socket.IO push)

**Why HTTP polling over Socket.IO?**
- Works when socket not connected (login screen, network instability)
- More reliable delivery than fire-and-forget socket events
- Backend needs ACK confirmation

**Flow:**
```
1. Dashboard sends command → Backend queues in TpvCommandQueue
2. Heartbeat response includes pendingCommands[]
3. HeartbeatWorker executes via CommandExecutor
4. Send HTTP ACK to backend (terminalId for security validation)
5. Backend updates queue status → Broadcasts to dashboard
```

## Mid-Payment Network Loss

**Scenario:** Device loses network during Blumon SDK transaction

### Case 1: Before Blumon SDK Call
```
User enters amount → Network drops → Blumon SDK call → FAILS (connection error)
Action: Show error, do NOT queue (no payment approved)
```

### Case 2: During Blumon SDK Processing
```
Blumon SDK processing (CHIP read) → Network irrelevant (SDK local)
Result: Payment completes locally, SDK returns SUCCESS
```

### Case 3: After Blumon SDK Success
```
Blumon SDK → SUCCESS (card charged) → Backend recordPayment() → FAILS (network down)
Action: Queue payment → Show success screen → Sync when network returns
```

**Key:** Blumon SDK operates independently of backend network. Payment approval happens locally on terminal.

## Background Workers Summary

| Worker | Interval | Constraint | Purpose | Policy |
|--------|----------|-----------|---------|--------|
| **PaymentSyncWorker** | 15 min | `CONNECTED` | Retry failed payment recordings | `KEEP` (preserve backoff) |
| **HeartbeatWorker** | 30 sec | `CONNECTED` | Device health + command polling | `REPLACE` (single instance) |
| **VerificationSyncWorker** | TODO | TODO | Photo/barcode upload queue | Not implemented yet |

## Data Flow Diagrams

### Payment Flow (Success)
```
User → PaymentScreen → PaymentViewModel
  → Blumon SDK → approveTransaction() → SUCCESS (card charged)
  → RecordPaymentUseCase → Backend API → HTTP 200 OK
  → PaymentState.Success → Show receipt → Done
```

### Payment Flow (Offline Queue)
```
User → PaymentScreen → PaymentViewModel
  → Blumon SDK → approveTransaction() → SUCCESS (card charged)
  → RecordPaymentUseCase → Backend API → TIMEOUT / 500
  → PaymentQueueRepository.enqueue() → Room DB
  → PaymentState.Success → Show receipt (payment approved, syncing in background)

[15 minutes later]
  → PaymentSyncWorker.doWork()
  → Fetch pending from Room DB
  → RecordPaymentUseCase (retry with exponential backoff)
  → Backend API → HTTP 200 OK / 409 duplicate
  → paymentQueueRepository.markSynced()
```

### Heartbeat Flow
```
[Every 30s when CONNECTED network]
  → HeartbeatWorker.doWork()
  → Collect device metrics (battery, storage, memory, network)
  → HeartbeatRepository.sendHeartbeat()
  → Backend API → HTTP 200 + HeartbeatResponse
  → Process pendingCommands[] (if any)
  → Check forceUpdate (if set, user cannot dismiss)
  → Result.success() → Schedule next run
```

## Key Files Reference

| Layer | File | Lines | Purpose |
|-------|------|-------|---------|
| **Worker** | `PaymentSyncWorker.kt` | 316 | Periodic payment queue retry |
| **Scheduler** | `PaymentSyncScheduler.kt` | 200 | WorkManager lifecycle for payments |
| **Repository** | `PaymentQueueRepository.kt` | 140 | Domain interface for queue ops |
| **Impl** | `PaymentQueueRepositoryImpl.kt` | 201 | Room DB mapping layer |
| **Entity** | `PendingPaymentEntity.kt` | 108 | Room schema for payments |
| **DAO** | `PendingPaymentDao.kt` | 163 | Room queries for payments |
| **Worker** | `HeartbeatWorker.kt` | 380 | Device health + command polling |
| **Scheduler** | `HeartbeatScheduler.kt` | 180 | WorkManager lifecycle for heartbeat |
| **Entity** | `VerificationQueueEntity.kt` | 75 | Room schema for verification |
| **DAO** | `VerificationQueueDao.kt` | 127 | Room queries for verification |

## Operational Notes

**Queue monitoring:** Dashboard shows pending/failed payments per terminal (via heartbeat metrics)

**Manual intervention:** Failed payments (after 3 attempts) require admin review in dashboard

**Cleanup:** `deleteOldSyncedPayments()` removes SUCCESS payments after 7 days (prevent DB bloat)

**Testing:** Use `PaymentSyncScheduler.runNow()` or `HeartbeatScheduler.runNow()` to trigger immediate execution

**Constraints:** Both workers require `CONNECTED` network (no point trying when offline)

**Battery:** No charging/battery constraints (payments must sync ASAP, health monitoring critical)
