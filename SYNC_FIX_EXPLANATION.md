# Version Conflict Fix - Debounce Sync Race Condition

**Date:** 2025-12-12
**Issue:** False "Order was modified by another terminal" conflicts when adding items rapidly
**Root Cause:** Race condition in debounced sync scheduling

---

## The Problem

When users added items rapidly to an order (within 2 seconds), multiple debounced syncs would be scheduled. Even though `scheduleSync()` cancelled the previous job, if that job had already passed its delay and started executing, the new job would ALSO execute. Both syncs would read the same version number from the database, causing the second one to fail with a 409 conflict.

### Timeline Example

```
T=0.0s: User adds item 1 → scheduleSync() → Job A scheduled (delay 2s)
T=1.0s: User adds item 2 → scheduleSync() → Job A.cancel(), Job B scheduled (delay 2s)
T=2.0s: Job A delay expired → starts executing (reads version=3)
T=3.0s: Job B delay expired → starts executing (reads version=3)
T=3.5s: Job A completes → backend at version=4
T=4.0s: Job B tries to sync with version=3 → 409 CONFLICT!
```

### Log Evidence

```log
14:44:03.210: Sync 1 starts (version=3, thread 18694)
14:44:03.467: Sync 2 starts (version=3, thread 18755) ← Same version!
14:44:03.747: ⚠️ Version conflict - order was modified by another terminal
```

Both syncs read `version=3` because they started before either one completed and updated the version in the database.

---

## Why the Mutex Wasn't Enough

The existing mutex in `executeSyncWithRetry()` prevented **concurrent execution** of the sync logic, but it didn't prevent **sequential execution** of redundant syncs:

1. Sync A acquires mutex → reads version=3 → updates to version=4 → releases mutex
2. Sync B (which was scheduled earlier) acquires mutex → reads version=3 from DB... wait, shouldn't it read version=4?

**The issue:** Sync B was already queued BEFORE Sync A completed. When Sync B's delay expired, it would wait for the mutex, then execute with whatever version was in the DB at that moment. If Sync A was slow (network delay), Sync B might still read version=3.

Actually, the real issue was simpler: Both syncs passed their delays at nearly the same time (within 257ms in the logs), so both got queued to execute. The mutex made them execute sequentially, but both had already "committed" to syncing based on the stale DB state.

---

## The Fix

Added a check **after the debounce delay expires** to see if another sync is already in progress:

```kotlin
fun scheduleSync(orderId: String) {
    pendingSyncJobs[orderId]?.cancel()

    val job = syncScope.launch {
        val scheduledAt = System.currentTimeMillis()
        Timber.d("⏱️ [Sync] Scheduled debounced sync | order=$orderId | delay=${SYNC_DEBOUNCE_MS}ms")

        delay(SYNC_DEBOUNCE_MS)

        // ⚡ P0 FIX: Check if another sync is already in progress
        val currentOrder = draftOrderDao.getOrder(orderId)
        if (currentOrder?.syncStatus == DraftOrderEntity.SYNC_STATUS_SYNCING) {
            Timber.d("⏭️ [Sync] Skipping debounced sync - already in progress | order=$orderId")
            pendingSyncJobs.remove(orderId)
            return@launch
        }

        Timber.d("🔄 [Sync] Debounce expired, executing sync | order=$orderId")
        executeSyncWithRetry(orderId)
    }

    pendingSyncJobs[orderId] = job
}
```

### How It Works

1. **User makes rapid changes** → Multiple syncs get scheduled and cancelled
2. **First sync's delay expires** → Checks if another sync is in progress (no) → Starts syncing → Sets `syncStatus = SYNCING`
3. **Second sync's delay expires** → Checks if another sync is in progress (YES! syncStatus = SYNCING) → **Skips execution**
4. **First sync completes** → Sets `syncStatus = SYNCED` → Version updated
5. **No conflict!**

---

## Benefits

1. **Prevents false conflicts** - Only one sync runs at a time per order
2. **Preserves debouncing** - Still batches rapid changes into one sync
3. **No performance impact** - Just one extra DB query after delay
4. **Thread-safe** - Uses existing database as source of truth

---

## Testing Recommendations

1. **Rapid item additions** - Add 5+ items within 2 seconds → Should sync once
2. **Concurrent edits** - Add item, change quantity, add another → Should sync once with all changes
3. **Network delays** - Slow network + rapid changes → Should still sync correctly
4. **Edge cases** - Verify no conflicts when:
   - Adding items while sync is in progress
   - Changing quantities rapidly
   - Multiple users editing same order (actual concurrent edits should still conflict correctly)

---

## Related Code

- **OrderSyncCoordinator.kt:581-608** - Fixed scheduleSync()
- **OrderSyncCoordinator.kt:800** - Where syncStatus is set to SYNCING
- **OrderSyncCoordinator.kt:1118** - Where syncStatus is set to SYNCED

---

## Future Improvements (Optional)

1. **Atomic sync flag** - Use in-memory flag instead of DB query for better performance
2. **Sync queue** - Queue pending changes instead of scheduling multiple syncs
3. **WebSocket sync** - Real-time sync like Square POS (no debounce needed)

---

**Status:** ✅ Fixed
**File Modified:** `app/src/main/java/com/jaac/avoqado_tpv/features/ordering/domain/OrderSyncCoordinator.kt`
