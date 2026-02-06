# Backend Changes: Environment Separation for Same Serial Number

## Problem

**Current Situation:**
- Backend stores: `serialNumber → venueId` (1:1 mapping)
- Same physical device (same serial number) needs to work with BOTH:
  - Production Blumon SDK (`core.blumonpay.net`)
  - Sandbox Blumon SDK (`sandbox-core.blumonpay.net`)
- **Conflict**: Can't activate same serial number in both environments

**Example:**
```
Device: PAX A920 (serialNumber: AVQD-2841548417)
├── Production App → Wants to activate for Venue A
└── Sandbox App    → Wants to activate for Venue B (test venue)
```

Currently, activating one **overwrites** the other in the database.

---

## ✅ Solution: Add `environment` Field

### Concept

Change the backend to store: `(serialNumber, environment) → venueId`

This creates a **composite key** allowing the same serial number to have different activations per environment.

---

## Backend API Changes Required

### 1. Activation Endpoint

**Endpoint:** `POST /tpv/activate`

**Before:**
```json
{
  "serialNumber": "AVQD-2841548417",
  "activationCode": "ABC123"
}
```

**After (NEW):**
```json
{
  "serialNumber": "AVQD-2841548417",
  "activationCode": "ABC123",
  "environment": "PROD"  // ← NEW FIELD: "PROD" or "SAND"
}
```

**Backend Logic:**
```typescript
// Before (WRONG):
const terminal = await db.terminals.findOne({
  where: { serialNumber }
})

// After (CORRECT):
const terminal = await db.terminals.findOne({
  where: {
    serialNumber,
    environment  // ← Composite key
  }
})
```

---

### 2. Activation Status Check

**Endpoint:** `GET /tpv/terminals/{serialNumber}/activation-status`

**Before:**
```
GET /tpv/terminals/AVQD-2841548417/activation-status
```

**After (NEW):**
```
GET /tpv/terminals/AVQD-2841548417/activation-status?environment=PROD
```

**Query Parameter:**
- `environment` (required): `"PROD"` or `"SAND"`

**Backend Logic:**
```typescript
// Before (WRONG):
const terminal = await db.terminals.findOne({
  where: { serialNumber }
})

// After (CORRECT):
const terminal = await db.terminals.findOne({
  where: {
    serialNumber,
    environment: req.query.environment  // ← From query param
  }
})
```

---

### 3. Database Schema Changes

**Option A: Add `environment` column to existing `terminals` table**

```sql
-- Migration: Add environment column
ALTER TABLE terminals
ADD COLUMN environment VARCHAR(4) NOT NULL DEFAULT 'PROD';

-- Create composite unique index (prevents duplicate serial+environment)
CREATE UNIQUE INDEX terminals_serial_environment_unique
ON terminals(serial_number, environment);

-- Remove old unique index on serial_number only
DROP INDEX IF EXISTS terminals_serial_number_unique;
```

**Option B: Create separate tables** (if you want complete isolation)

```sql
CREATE TABLE terminals_production (
  id UUID PRIMARY KEY,
  serial_number VARCHAR(255) UNIQUE NOT NULL,
  venue_id UUID NOT NULL,
  activation_code VARCHAR(6),
  activated_at TIMESTAMP,
  -- ... other fields
);

CREATE TABLE terminals_sandbox (
  id UUID PRIMARY KEY,
  serial_number VARCHAR(255) UNIQUE NOT NULL,
  venue_id UUID NOT NULL,
  activation_code VARCHAR(6),
  activated_at TIMESTAMP,
  -- ... other fields
);
```

**Recommendation:** Use **Option A** (single table with environment column) - simpler to maintain.

---

### 4. Backend Data Examples

**After implementing environment separation:**

```typescript
// Database state example:
[
  {
    id: "term_001",
    serialNumber: "AVQD-2841548417",
    environment: "PROD",
    venueId: "venue_123",  // ← Real production venue
    activatedAt: "2025-01-15T10:00:00Z"
  },
  {
    id: "term_002",
    serialNumber: "AVQD-2841548417",  // ← SAME serial number!
    environment: "SAND",
    venueId: "venue_test_456",  // ← Test venue for sandbox
    activatedAt: "2025-01-15T11:30:00Z"
  }
]
```

**Query examples:**
```typescript
// Get production activation
db.terminals.findOne({
  serialNumber: "AVQD-2841548417",
  environment: "PROD"
})
// → Returns venue_123

// Get sandbox activation
db.terminals.findOne({
  serialNumber: "AVQD-2841548417",
  environment: "SAND"
})
// → Returns venue_test_456
```

---

## Android App Implementation Status

✅ **Already implemented in Android app:**

1. **DTO Updated:**
   - `ActivateTerminalRequest` now includes `environment: String`
   - `ApiService.checkActivationStatus()` includes `environment` query parameter

2. **Repository Updated:**
   - `ActivationRepositoryImpl` automatically sends `BuildConfig.BLUMON_ENV`
   - `DeviceInfoManager` includes environment in status check

3. **Build Variants:**
   - Production build: Sends `environment: "PROD"`
   - Sandbox build: Sends `environment: "SAND"`

4. **No Manual Switching Required:**
   - Environment is **automatically determined** by build variant
   - User installs sandbox APK → sends `SAND`
   - User installs production APK → sends `PROD`

---

## Testing Workflow

### Step 1: Activate Production

```bash
# Build production APK
./gradlew assembleProductionDebug

# Install on device
adb install app/build/outputs/apk/production/debug/app-production-debug.apk

# Open app, activate with code → Backend stores:
{
  serialNumber: "AVQD-2841548417",
  environment: "PROD",
  venueId: "venue_real"
}
```

### Step 2: Activate Sandbox (SAME device)

```bash
# Build sandbox APK
./gradlew assembleSandboxDebug

# Install on device (different package name: .sandbox)
adb install app/build/outputs/apk/sandbox/debug/app-sandbox-debug.apk

# Open app, activate with code → Backend stores:
{
  serialNumber: "AVQD-2841548417",  // ← SAME serial!
  environment: "SAND",
  venueId: "venue_test"
}
```

### Step 3: Switch Between Environments

```bash
# Launch production app
adb shell am start -n com.jaac.avoqado_tpv/.MainActivity
# → Backend queries: (AVQD-2841548417, PROD) → venue_real

# Launch sandbox app
adb shell am start -n com.jaac.avoqado_tpv.sandbox/.MainActivity
# → Backend queries: (AVQD-2841548417, SAND) → venue_test
```

**No conflicts!** ✅

---

## Backward Compatibility

### Option 1: Default to "PROD" (Safe Migration)

```typescript
// If old app sends request WITHOUT environment field:
const environment = req.body.environment || "PROD";

// Find terminal with fallback
const terminal = await db.terminals.findOne({
  where: {
    serialNumber,
    environment: environment
  }
});
```

### Option 2: Migrate Existing Data

```sql
-- Set all existing terminals to PROD environment
UPDATE terminals
SET environment = 'PROD'
WHERE environment IS NULL;
```

---

## Required Backend Changes Summary

| File/Component | Change |
|----------------|--------|
| **DTO/Schema** | Add `environment: string` field to ActivationRequest |
| **Database** | Add `environment VARCHAR(4)` column to `terminals` table |
| **Database** | Create composite unique index on `(serial_number, environment)` |
| **Activation Logic** | Query with `WHERE serial_number = ? AND environment = ?` |
| **Status Check** | Read `environment` from query parameter |
| **Validation** | Ensure `environment` is either "PROD" or "SAND" |

---

## Benefits

✅ **Same device works in both environments**
✅ **No serial number conflicts**
✅ **Clean separation of production vs test data**
✅ **Easy to switch between environments (just install different APK)**
✅ **Automatic environment detection (no user intervention)**

---

## Questions?

If you need help implementing this on the backend, let me know! The Android app is already ready and will start sending the `environment` field immediately.

**Contact:** Development Team
**Last Updated:** 2025-11-19
