# Error Handling Pattern

Dense reference for error propagation in avoqado-tpv. All paths verified against source code.

---

## Core Types

### `Result<T>` — Custom Wrapper

**File:** `core/domain/models/Result.kt`

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: ApiException) : Result<Nothing>()
}
```

| Method | Returns | Use |
|--------|---------|-----|
| `isSuccess` / `isError` | `Boolean` | Quick checks |
| `getOrNull()` | `T?` | Safe data access |
| `getOrThrow()` | `T` | Throws `ApiException` on error |
| `map { }` | `Result<R>` | Transform success data |
| `onSuccess { }` / `onError { }` | `Result<T>` | Side-effect chains |

### `ApiException` — Sealed Class Taxonomy

**File:** `core/domain/models/Result.kt`

Every subclass carries two messages: `message` (technical, English, for Timber logs) and `userMessage` (Spanish, for UI display).

| Subclass | Trigger | `userMessage` (Spanish) |
|----------|---------|------------------------|
| `HttpError(code, errorMessage, customUserMessage?)` | Non-2xx HTTP response | Code-based defaults (see table below) or backend `customUserMessage` |
| `NetworkError(cause)` | `IOException`, timeout, no internet | "Error de conexion. Verifique su internet e intente nuevamente." |
| `ParseError(cause)` | Malformed JSON, deserialization failure | "Error al procesar respuesta del servidor." |
| `Unknown(cause)` | Any other `Exception` | "Error desconocido. Por favor intente nuevamente." |
| `SessionExpired` | object, no params | "Su sesion ha expirado. Por favor inicie sesion nuevamente." |
| `PermissionDenied(requiredPermission?)` | 403 with permission context | "No tiene permisos para realizar esta accion." |
| `ValidationError(errorMessage)` | Client-side validation failure | Passes `errorMessage` directly as `userMessage` |

#### HttpError Default Messages by Status Code

```kotlin
400 -> "Solicitud invalida. Por favor intente nuevamente."
401 -> "Sesion expirada. Por favor inicie sesion nuevamente."
403 -> "No tiene permisos para realizar esta accion."
404 -> "Recurso no encontrado."
408 -> "Tiempo de espera agotado. Verifique su conexion."
429 -> "Demasiadas solicitudes. Por favor espere un momento."
500..599 -> "Error del servidor. Por favor intente mas tarde."
else -> "Error de conexion. Por favor intente nuevamente."
```

These defaults are overridden when `customUserMessage` is provided (from backend error body).

### `ActivationError` — Separate Sealed Hierarchy

**File:** `core/domain/models/Activation.kt`

Business-specific errors for the activation flow only. Same dual-message pattern (`message` + `userMessage`).

| Subclass | Trigger | `userMessage` |
|----------|---------|---------------|
| `InvalidCode(attemptsRemaining)` | Wrong activation code | "Codigo de activacion incorrecto. {N} intento(s) restantes." |
| `TerminalLocked` | Too many failed attempts | "Terminal bloqueado por demasiados intentos fallidos. Contacte soporte." |
| `CodeExpired` | Code older than 7 days | "Codigo de activacion expirado. Solicite uno nuevo desde el dashboard." |
| `AlreadyActivated` | Terminal already activated | "Este terminal ya esta activado." |
| `TerminalNotRegistered` | Serial number not in DB | "Terminal no registrado. Contacte al administrador." |
| `NetworkError(cause)` | Network failure | "Error de conexion. Verifique su internet e intente nuevamente." |
| `Unknown(cause)` | Catch-all | "Error desconocido. Por favor intente nuevamente." |

### `ConflictException` — Order Sync

**File:** `features/ordering/domain/OrderSyncCoordinator.kt:1530`

```kotlin
class ConflictException(val serverVersion: String, message: String) : Exception(message)
```

Thrown on HTTP 409 when order version mismatches. Handled by `OrderSyncCoordinator` with auto-refresh + retry.

---

## Two Result Systems

The codebase uses **two** Result types. Which one depends on the feature's age and architecture layer.

| Type | Import | Used By |
|------|--------|---------|
| **Custom `Result<T>`** | `core.domain.models.Result` | Auth, Activation, Shifts, Payments history, Reports |
| **`kotlin.Result<T>`** | `kotlin.Result` | Ordering repos, Timeclock `verifyPinOnly`, `TerminalConfigRepository` |

**Custom `Result<T>`** wraps `ApiException` — provides structured error classification.
**`kotlin.Result<T>`** wraps generic `Exception` with Spanish message strings baked in.

Pattern in ordering repositories using `kotlin.Result`:
```kotlin
override suspend fun getOrder(venueId: String, orderId: String): Result<Order> {
    return try {
        val response = apiService.getOrder(venueId, orderId)
        if (response.isSuccessful) {
            Result.success(body.data.toOrder())
        } else {
            val errorMessage = when (response.code()) {
                401 -> "No autorizado. Por favor inicia sesion nuevamente."
                403 -> "No tienes permisos para ver esta orden."
                404 -> "Orden no encontrada."
                else -> "Error al obtener orden: ${response.code()}"
            }
            Result.failure(Exception(errorMessage))
        }
    } catch (e: Exception) { Result.failure(e) }
}
```

---

## Error Propagation Chain

```
API (Retrofit) -> Repository -> UseCase (optional) -> ViewModel -> UI State -> Compose Screen
```

### Layer 1: Repository

Standard try/catch wrapping around Retrofit calls. Three catch categories:

```kotlin
try {
    val response = apiService.someCall(...)
    if (response.isSuccessful && response.body() != null) {
        Result.Success(response.body()!!.toDomain())
    } else {
        // Parse error body for backend message
        Result.Error(ApiException.HttpError(response.code(), response.message()))
    }
} catch (e: java.io.IOException) {
    Result.Error(ApiException.NetworkError(e))       // Network-specific
} catch (e: Exception) {
    Result.Error(ApiException.Unknown(e))            // Catch-all
}
```

**Backend error body parsing** (AuthRepository pattern):
```kotlin
val backendMessage = try {
    val errorBody = response.errorBody()?.string()
    val json = JSONObject(errorBody!!)
    json.optString("message").ifEmpty { json.optString("error") }.ifEmpty { null }
} catch (e: Exception) { null }
```

Backend response shape: `ApiErrorResponse(error: String?, message: String?, statusCode: Int?)` defined in `ApiService.kt:1451`.

### Layer 2: ViewModel

ViewModels consume `Result` via `when` and map to sealed UI state classes.

```kotlin
when (val result = repository.doSomething()) {
    is Result.Success -> _state.value = FeatureState.Success(result.data)
    is Result.Error   -> _state.value = FeatureState.Error(result.exception.userMessage)
}
```

### Layer 3: UI (Compose)

Screens observe `StateFlow<FeatureState>` and render error states. Two primary display patterns:

| Pattern | Used In | Mechanism |
|---------|---------|-----------|
| **Inline error text** | LoginScreen, ActivationScreen, TimeclockScreen | `is State.Error -> Text(state.message)` |
| **Snackbar** | MenuScreen, FloorPlanCanvasScreen, SettingsScreen, KioskCartScreen | `snackbarHostState.showSnackbar(message)` |

---

## UI State Error Patterns

Every feature's sealed state class includes an `Error` variant with a `message: String`:

```kotlin
sealed class ShiftState {
    data object Idle : ShiftState()
    data object Loading : ShiftState()
    data class ShiftActive(...) : ShiftState()
    data class Error(val message: String) : ShiftState()  // <-- always present
}
```

Features following this pattern: `LoginState`, `ActivationState`, `ShiftState`, `PaymentsState`, `ReportsState`, `MenuState`, `FloorPlanState`, `TimeclockState`, `SelfUpdateState`, `OrderListState`, `CouponValidationState`, `CustomerSearchState`, `DiscountState`, `PaymentState`.

---

## Network Interceptor Error Handling

Three interceptors handle errors at the OkHttp level, before repository code runs.

### TokenAuthenticator (401 Auto-Refresh)

**File:** `core/data/network/interceptors/TokenAuthenticator.kt`

```
Request -> 401 Response -> Is auth endpoint? -> Yes: return null (propagate error)
                                              -> No:  refresh token -> retry request
                                                      -> refresh fails -> SessionManager.notifySessionExpired()
                                                                        -> clear session -> navigate to Login
```

Auth endpoints excluded from refresh: `/auth/login`, `/login-pin`, `/activate`, `/refresh`.

Thread-safe: synchronized block + `@Volatile isRefreshing` flag prevents concurrent refreshes.

### VersionGateInterceptor (426 Force Update)

**File:** `core/data/network/interceptors/VersionGateInterceptor.kt`

Intercepts HTTP 426 globally. Parses `VersionGateResponse` from body, sets `UpdateCheckManager.setForceUpdate()` which triggers `ForceUpdateDialog` in UI. App is unusable until updated.

### SlowNetworkInterceptor (Testing Only)

**File:** `core/data/network/interceptors/SlowNetworkInterceptor.kt`

Adds configurable delay to all requests. Toggled from SuperAdmin screen. Never enabled in production.

---

## Session Expiration Flow

**File:** `core/session/SessionManager.kt`

```
TokenAuthenticator detects 401
  -> SessionManager.notifySessionVerifying()     // Show loading overlay
  -> Attempt token refresh
     -> Success: resetSessionExpiringState()     // Hide overlay, continue
     -> Failure: clearSession() + notifySessionExpired()
                                                 // Emit SessionEvent.Expired
                                                 // AppNavigation navigates to Login
```

`SessionEvent` sealed class: `Expired`, `TerminalDeactivated`, `TokenRefreshed`.

`TerminalDeactivated` is emitted by HeartbeatWorker when backend reports terminal status = RETIRED.

---

## Offline Payment Queue Error Handling

**File:** `core/data/workers/PaymentSyncWorker.kt`

Payments approved by Blumon SDK are queued in Room if backend recording fails.

| HTTP Code | Action |
|-----------|--------|
| 2xx | `markSynced()` |
| 409 | `markSynced()` (idempotent -- already recorded) |
| 4xx | `markFailed()` (won't self-heal) |
| 5xx | Retry with exponential backoff (1s, 2s, 4s) -- max 3 attempts |
| Network error | Retry (same backoff) |

Worker runs every 15 minutes via WorkManager.

---

## Error Mapping Strategies

### Strategy 1: Backend Message Passthrough (AuthRepository)

Parse `errorBody()` JSON, extract `message`/`error`/`detail` field, pass as `customUserMessage` to `HttpError`. Falls back to code-based Spanish defaults.

### Strategy 2: Code-Based Mapping (ActivationRepositoryImpl)

```kotlin
private fun mapHttpErrorToActivationError(code: Int, message: String): ApiException {
    return when (code) {
        400 -> when {
            message.contains("expired") -> HttpError(400, "Codigo de activacion expirado")
            message.contains("already activated") -> HttpError(400, "Terminal ya activado")
            else -> HttpError(400, message)
        }
        401 -> when {
            message.contains("locked") -> HttpError(401, "Terminal bloqueado")
            // Extract attempts remaining from "X attempt(s) remaining"
            message.contains("Invalid activation code") -> HttpError(401, "Codigo incorrecto. $N intento(s)")
        }
        404 -> HttpError(404, "Terminal no registrado")
    }
}
```

### Strategy 3: Inline Spanish Messages (Ordering Repos, FastPaymentRecorder)

No `ApiException` -- just `kotlin.Result.failure(Exception("Spanish message"))` with code-specific messages baked into each `when` branch:

```kotlin
response.code() == 401 -> Result.failure(Exception("Token de autenticacion invalido o expirado..."))
response.code() == 403 -> Result.failure(Exception("No tienes permisos para registrar pagos..."))
response.code() == 429 -> Result.failure(Exception("Demasiadas solicitudes. Por favor, espera..."))
```

### Strategy 4: Keyword-Based Backend Message Translation (MenuViewModel)

```kotlin
private fun mapCouponErrorToSpanish(errorMessage: String?): String {
    return when {
        "not found" in msg    -> "Cupon no encontrado. Verifica el codigo."
        "expired" in msg      -> "Este cupon ya expiro."
        "inactive" in msg     -> "Este cupon esta desactivado."
        "usage limit" in msg  -> "Este cupon ya agoto todos sus usos disponibles."
        "minimum purchase"    -> "Compra minima requerida: $$amount"
        else -> errorMessage  // Fallback: show raw backend message
    }
}
```

### Strategy 5: Keyword Matching on Error Messages (LoginViewModel)

LoginViewModel inspects both `userMessage` and `technicalMessage` for keywords to determine special states:

```kotlin
when {
    containsAny("TERMINAL_NOT_ACTIVATED", "must be activated") -> LoginState.TerminalNotActivated
    containsAny("suspended", "suspendido", "cerrado permanentemente") -> LoginState.VenueNotOperational(msg)
    containsAny("no longer active", "ya no esta activo") -> LoginState.Error("Tu cuenta ya no esta activa...")
    else -> LoginState.Error(userFriendlyMessage)
}
```

---

## Order Sync Conflict Handling (409)

**File:** `features/ordering/domain/OrderSyncCoordinator.kt`

1. Backend returns 409 with server's current version
2. `ConflictException(serverVersion)` thrown
3. Coordinator stores server version in `conflictData`
4. Emits `SyncEvent.Error` via `SharedFlow`
5. ViewModel auto-refreshes order from server + retries operation

---

## Common Error Scenarios

| Scenario | Where Caught | User Sees |
|----------|-------------|-----------|
| No internet | Repository catch block | "Error de conexion. Verifique su internet..." |
| Token expired mid-session | TokenAuthenticator | Loading overlay, then auto-refresh or redirect to Login |
| Wrong PIN | AuthRepository -> LoginViewModel | Backend message (e.g., "PIN incorrecto") or fallback |
| Rate limited (429) | Repository | "Demasiadas solicitudes. Por favor espere..." |
| Terminal deactivated remotely | HeartbeatWorker -> SessionManager | Navigate to Activation screen |
| App version too old (426) | VersionGateInterceptor | ForceUpdateDialog (non-dismissible) |
| Order version conflict (409) | OrderSyncCoordinator | Auto-refresh + retry (transparent to user) |
| Payment recording fails | PaymentViewModel | Queued to Room DB, synced by PaymentSyncWorker |
| Permission denied (403) | Repository | "No tienes permisos para..." |
| Venue suspended | LoginViewModel keyword match | LoginState.VenueNotOperational with backend message |

---

## Key File Paths

| File | Role |
|------|------|
| `core/domain/models/Result.kt` | `Result<T>` + `ApiException` sealed class |
| `core/domain/models/Activation.kt` | `ActivationError` sealed class |
| `core/session/SessionManager.kt` | Session events (expired, deactivated, refreshed) |
| `core/data/network/interceptors/TokenAuthenticator.kt` | Auto token refresh on 401 |
| `core/data/network/interceptors/VersionGateInterceptor.kt` | Force update on 426 |
| `core/data/network/ApiService.kt:1451` | `ApiErrorResponse` DTO |
| `features/ordering/domain/OrderSyncCoordinator.kt:1530` | `ConflictException` for 409 |
| `core/data/workers/PaymentSyncWorker.kt` | Offline payment queue retry logic |
