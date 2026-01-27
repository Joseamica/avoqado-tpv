# Modules System (VenueModule)

## Overview

The Modules System provides configuration-driven features that adapt the TPV to different industries (restaurants, telecom, retail, etc.). Each venue can have different modules enabled with specific configurations.

## Architecture

```
Backend API                          TPV Android
┌─────────────────────────┐          ┌─────────────────────────────┐
│ GET /tpv/modules        │ ──────>  │ ModulesRepository           │
│ Returns: VenueModule[]  │          │ ├─ fetchAndCache()          │
│ - moduleCode            │          │ ├─ modules: StateFlow<List> │ ← Reactive
│ - active                │          │ └─ clearCache()             │
│ - config                │          └─────────────────────────────┘
└─────────────────────────┘                      │
                                                 ↓
                                    ┌─────────────────────────────┐
                                    │ UI (WelcomeScreen, etc.)    │
                                    │ collectAsStateWithLifecycle │
                                    └─────────────────────────────┘
```

## Key Files

| File | Purpose |
|------|---------|
| `features/modules/domain/repository/ModulesRepository.kt` | Interface |
| `features/modules/data/repository/ModulesRepositoryImpl.kt` | Implementation with StateFlow |
| `features/modules/domain/model/VenueModule.kt` | Domain model |
| `core/data/local/SecureStorage.kt` | Persistent cache |

## StateFlow Pattern (CRITICAL)

### Why StateFlow?

UI components that depend on module configuration MUST observe changes via `StateFlow`. This ensures:
1. UI updates when modules are fetched (login)
2. UI updates when modules are cleared (logout)
3. No stale cached values in Compose

### Interface

```kotlin
interface ModulesRepository {
    /**
     * StateFlow of cached modules.
     * Use this in Compose to observe module changes.
     */
    val modules: StateFlow<List<VenueModule>>

    suspend fun fetchAndCache(): Result<List<VenueModule>>
    fun getCachedModules(): List<VenueModule>
    fun isModuleEnabled(moduleCode: String): Boolean
    fun getModuleConfig(moduleCode: String): ModuleConfig?
    fun getModule(moduleCode: String): VenueModule?
    fun clearCache()
}
```

### Implementation

```kotlin
@Singleton
class ModulesRepositoryImpl @Inject constructor(...) : ModulesRepository {

    // StateFlow for reactive access
    private val _modules = MutableStateFlow<List<VenueModule>>(emptyList())
    override val modules: StateFlow<List<VenueModule>> = _modules.asStateFlow()

    override suspend fun fetchAndCache(): Result<List<VenueModule>> {
        // ... fetch from API
        _modules.value = modules  // Update StateFlow
        secureStorage.saveModules(moduleDtos)
        return Result.success(modules)
    }

    override fun clearCache() {
        _modules.value = emptyList()  // Clear StateFlow
        secureStorage.clearCachedModules()
    }
}
```

### UI Usage

```kotlin
@Composable
fun WelcomeScreen(modulesRepository: ModulesRepository) {
    // CORRECT: Observe StateFlow for reactive updates
    val currentModules by modulesRepository.modules.collectAsStateWithLifecycle()

    val isSimplifiedMode = currentModules
        .find { it.moduleCode == "SERIALIZED_INVENTORY" }
        ?.config?.ui?.simplifiedOrderFlow == true

    // UI recomposes when modules change (login/logout)
}
```

### Anti-Pattern (DO NOT USE)

```kotlin
@Composable
fun WelcomeScreen(modulesRepository: ModulesRepository) {
    // WRONG: remember{} caches once and never updates!
    val config = remember {
        modulesRepository.getModuleConfig("SERIALIZED_INVENTORY")
    }

    // Bug: config stays cached even after logout
}
```

## Module Codes

```kotlin
companion object {
    const val MODULE_SERIALIZED_INVENTORY = "SERIALIZED_INVENTORY"
    const val MODULE_ATTENDANCE_TRACKING = "ATTENDANCE_TRACKING"
}
```

## Module Config Structure

```kotlin
data class ModuleConfig(
    val ui: UiConfig,
    val attendance: AttendanceConfig,
    val labels: Map<String, String>
)

data class UiConfig(
    val simplifiedOrderFlow: Boolean = false,  // PlayTelecom: single "Vender" button
    val skipTipScreen: Boolean = false,
    val skipReviewScreen: Boolean = false,
    val enableShifts: Boolean = true
)
```

## Data Flow

### On Login/App Start

```
1. SplashScreen detects device is activated
2. Call modulesRepository.fetchAndCache()
3. API returns venue's enabled modules
4. Modules cached in SecureStorage (persistent)
5. _modules.value updated (StateFlow emits)
6. UI observing StateFlow recomposes
```

### On Logout

```
1. AuthRepository.logout() called
2. modulesRepository.clearCache() called
3. _modules.value = emptyList() (StateFlow emits)
4. secureStorage.clearCachedModules()
5. UI observing StateFlow recomposes with empty list
6. WelcomeScreen shows default buttons (not simplified mode)
```

## Bug Fixes History

### 2025-01-07: Simplified Mode Persisting After Logout

**Symptom:** After logout, WelcomeScreen still showed PlayTelecom simplified UI ("Vender" button only) instead of normal buttons.

**Root Cause:** WelcomeScreen used `remember {}` to cache the module config once. This never re-queried when modules were cleared on logout.

**Fix:**
1. Added `StateFlow<List<VenueModule>>` to `ModulesRepository` interface
2. Updated `ModulesRepositoryImpl` to use `MutableStateFlow`
3. Changed WelcomeScreen to use `collectAsStateWithLifecycle()` instead of `remember {}`

**Files Changed:**
- `features/modules/domain/repository/ModulesRepository.kt` - Added StateFlow to interface
- `features/modules/data/repository/ModulesRepositoryImpl.kt` - Implemented StateFlow
- `core/presentation/screens/WelcomeScreen.kt` - Observe StateFlow

**Lesson Learned:** Module configurations that affect UI MUST be observed via StateFlow, not cached with `remember {}`.

## Testing

### Manual Testing Checklist

- [ ] Login with venue that has SERIALIZED_INVENTORY module → WelcomeScreen shows simplified UI
- [ ] Logout → WelcomeScreen shows normal buttons (not simplified)
- [ ] Login with different venue (no modules) → WelcomeScreen shows normal buttons
- [ ] App restart after logout → WelcomeScreen shows normal buttons

## Module-Specific Features

### SERIALIZED_INVENTORY Module: Proof-of-Sale Photo Upload

**Overview:** When SERIALIZED_INVENTORY module is active, merchants can optionally upload proof-of-sale photos after payment completion. This is useful for telecom stores (SIM card receipts) and jewelry stores (transaction documentation).

**UI Behavior:**
- FloatingActionButton (camera icon) appears on payment success screen
- Only visible when `SERIALIZED_INVENTORY` module is active
- Clicking FAB opens CameraPreviewScreen for photo capture
- Upload happens asynchronously (doesn't block payment flow)
- **Serialized sale flow:** After a serialized sale payment (`skipLocalOrderValidation=true`), the success action returns to the SerializedSale scanner (not Quick Order). Back navigation from payment steps also returns to the scanner to avoid entering tip/merchant flows.

**Implementation:**
```kotlin
// PaymentViewModel.kt - Module Observation
private val _isSerializedInventoryActive = MutableStateFlow(false)
val isSerializedInventoryActive: StateFlow<Boolean> = _isSerializedInventoryActive.asStateFlow()

private fun observeModules() {
    viewModelScope.launch {
        modulesRepository.modules.collect { modules ->
            val isActive = modules.any {
                it.moduleCode == ModulesRepository.MODULE_SERIALIZED_INVENTORY && it.active
            }
            _isSerializedInventoryActive.value = isActive
        }
    }
}

// PaymentScreen.kt - UI consumption
val isSerializedInventoryActive by viewModel.isSerializedInventoryActive.collectAsStateWithLifecycle()
showProofOfSaleButton = isSerializedInventoryActive && currentState.receipt?.paymentId != null
```

**Photo Upload Flow:**
1. Payment completes successfully
2. FAB button appears (if SERIALIZED_INVENTORY active)
3. User clicks FAB → CameraPreviewScreen opens
4. User captures photo
5. Photo compressed to ~126KB (1920px max, 80% JPEG)
6. Upload to Firebase Storage: `{env}/venues/{venueSlug}/proof-of-sale/{YYYY-MM-DD}/{paymentId-8chars}_{amount}.jpg`
7. Backend API call to store URL in SaleVerification table
8. Upload completes in background (~2 seconds)

**Files Involved:**
- `PaymentScreen.kt` (lines 125-141, 641-651, 1549-1583) - UI & module detection
- `PaymentViewModel.kt` (lines 4774-4847 sandbox, 4190-4263 production) - Upload logic
- `VerificationUploadManager.kt` (lines 209-277) - Firebase upload with compression
- `PaymentApiService.kt` (lines 276-308) - Backend API endpoint

**Backend Integration:**
- Endpoint: `POST /api/v1/tpv/verification/proof-of-sale`
- Request: `{ paymentId: string, photoUrls: string[] }`
- Response: `{ success: boolean, verificationId: string }`
- Database: Creates/updates SaleVerification record with photo URLs

**Key Design Decisions:**
1. **Optional Upload**: Photo upload doesn't block payment success (merchant can skip)
2. **PaymentId Fallback**: Uses `paymentId.take(8)` if orderNumber is null
3. **Background Upload**: Async operation, doesn't freeze UI
4. **Existing Infrastructure**: Reuses VerificationUploadManager from attendance tracking

**Testing Checklist:**
- [ ] SERIALIZED_INVENTORY enabled → FAB button shows on payment success ✅
- [ ] SERIALIZED_INVENTORY disabled → FAB button hidden ✅
- [ ] Photo capture → compression → Firebase upload → backend API ✅
- [ ] Upload time < 3 seconds ✅
- [ ] Photo appears at correct Firebase path ✅
- [ ] SaleVerification record created in database ✅
- [ ] Payment success not blocked by upload failure ✅

**Performance:**
- Photo compression: ~150ms
- Firebase upload: ~2 seconds
- Total time: ~2.5 seconds end-to-end

**Documentation:**
- Full implementation plan: `~/.claude/plans/crystalline-percolating-dawn.md`
- Backend controller: `avoqado-server/src/controllers/tpv/sale-verification.tpv.controller.ts`

---

**Last Updated:** 2026-01-21
**Author:** Claude Code
**Version:** 1.1 (Added proof-of-sale photo upload documentation)
