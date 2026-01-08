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

---

**Last Updated:** 2025-01-07
**Author:** Claude Code
**Version:** 1.0
