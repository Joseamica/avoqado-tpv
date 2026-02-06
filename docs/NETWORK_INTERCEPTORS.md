# Network Interceptors & Authentication

OkHttp interceptor chain for request/response processing and 401 token refresh flow.

## Interceptor Chain Order

Execution order matters. Request flows top-to-bottom, response flows bottom-to-top.

| Order | Interceptor | Purpose | When it runs |
|-------|-------------|---------|--------------|
| 1 | `SlowNetworkInterceptor` | Simulate network delays (DEBUG) | BEFORE auth |
| 2 | `AuthInterceptor` | Add JWT token + version headers | BEFORE request |
| 3 | `TenantInterceptor` | Add X-Venue-Id header | AFTER auth |
| 4 | `VersionGateInterceptor` | Handle HTTP 426 Upgrade Required | AFTER response |
| 5 | `LoggingInterceptor` | Log request/response (DEBUG only) | LAST |
| - | `TokenAuthenticator` | Refresh token on HTTP 401 | AFTER 401 response |

**Configuration**: `/Users/amieva/Documents/Programming/Avoqado/avoqado-tpv/app/src/main/java/com/jaac/avoqado_tpv/core/di/NetworkModule.kt:112-121`

## Interceptor Details

### 1. SlowNetworkInterceptor (DEBUG only)

```kotlin
// Toggle from SuperAdmin screen
SlowNetworkInterceptor.enabled = true
SlowNetworkInterceptor.delayMs = 3000L  // 3 seconds

// Presets
Presets.FAST_3G = 1000L      // 1s
Presets.SLOW_3G = 3000L      // 3s
Presets.EDGE = 5000L         // 5s
Presets.NEAR_TIMEOUT = 12000L // 12s
```

**Use case**: Test payment flow under poor network conditions.

**WARNING**: NEVER enable in production builds.

### 2. AuthInterceptor

Adds headers to EVERY request:

```kotlin
Authorization: Bearer {token}
X-App-Version-Code: 16
X-App-Version-Name: 1.4.2-sandbox
```

**Token source**: `SecureStorage.getToken()`

**No token?**: Request proceeds without Authorization header (public endpoints like `/tpv/activate`).

**401 handling**: Separate concern (see TokenAuthenticator).

### 3. TenantInterceptor

Adds `X-Venue-Id` header for multi-tenant isolation.

**Skipped for**: `/api/v1/tpv/activate` (no venueId until activation).

**Missing venueId**: Request proceeds but likely fails with 403 Forbidden.

**Backend validation**: Backend MUST validate venueId matches user's access.

### 4. VersionGateInterceptor

Intercepts HTTP 426 (Upgrade Required) and triggers ForceUpdateDialog.

**Flow**:
1. Backend checks `X-App-Version-Code` header
2. If below minimum → HTTP 426 with update info
3. Interceptor parses response → `UpdateCheckManager.setForceUpdate()`
4. UI observes `pendingUpdate` → shows ForceUpdateDialog
5. User CANNOT dismiss until APK installed

**Lazy injection**: Uses `dagger.Lazy<UpdateCheckManager>` to break DI cycle.

**Response format**:
```json
{
  "success": false,
  "error": "VERSION_OUTDATED",
  "minVersionCode": 17,
  "currentVersionCode": 16,
  "update": {
    "versionName": "1.5.0",
    "versionCode": 17,
    "downloadUrl": "https://...",
    "releaseNotes": "..."
  }
}
```

### 5. LoggingInterceptor

Logs full HTTP request/response to Timber (tag: `OkHttp`).

**DEBUG**: `Level.BODY` (logs everything)

**RELEASE**: `Level.NONE` (no logging for security)

**Implementation**: Uses `okhttp3.logging.HttpLoggingInterceptor`.

## TokenAuthenticator (401 Refresh Flow)

**Why separate from AuthInterceptor?**

| Interceptor | Authenticator |
|-------------|---------------|
| Runs BEFORE request | Runs AFTER 401 response |
| Adds token header | Refreshes expired token |
| No cycle risk | Needs Lazy<AuthRepository> |
| OkHttp pattern | OkHttp pattern |

### 401 Refresh Flow

```
Request → AuthInterceptor (adds token) → Server → 401 Response
  ↓
TokenAuthenticator.authenticate()
  ↓
1. Show SessionManager.notifySessionVerifying() (loading overlay)
2. Check if another thread already refreshed (compare tokens)
3. Call authRepository.refreshAccessToken()
4. Success → retry request with new token
5. Failure → clearSession() + navigate to Login
```

### Endpoints Excluded from Refresh

These use credentials (PIN, activation code), NOT tokens. A 401 means "bad credentials", NOT "token expired":

```kotlin
/auth/login
/auth/
/login-pin
/activate
/refresh  // Don't retry refresh if refresh itself fails
```

### Lazy<AuthRepository> DI Cycle Fix

**Problem**: Circular dependency

```
ApiService → Retrofit → OkHttpClient → TokenAuthenticator → AuthRepository → ApiService
                                            ↑_________________________________|
```

**Solution**: Lazy injection

```kotlin
class TokenAuthenticator @Inject constructor(
    private val authRepositoryLazy: Lazy<AuthRepository>
) {
    private val authRepository: AuthRepository by lazy { authRepositoryLazy.get() }
}
```

Authenticator only called AFTER OkHttpClient fully constructed. Safe to resolve lazily.

### Thread Safety

Multiple simultaneous 401s (e.g., 5 requests expire at same time) → Only ONE refresh.

```kotlin
private val refreshLock = Any()
@Volatile
private var isRefreshing = false

synchronized(refreshLock) {
    if (isRefreshing) {
        // Wait for other thread's refresh to complete
        while (isRefreshing && timeout < 5000) {
            Thread.sleep(100)
        }
        return buildRequestWithNewToken(...)
    }
    isRefreshing = true
    // ... refresh logic ...
}
```

### SessionManager Integration

```kotlin
notifySessionVerifying()    // Show loading overlay
notifyTokenRefreshed()       // Socket.IO reconnects with fresh token
notifySessionExpired()       // Navigate to Login
resetSessionExpiringState()  // Hide loading overlay
```

## Certificate Pinning Status

**DISABLED** (2025-12-26). HTTPS provides sufficient security.

**Why disabled**:
- Let's Encrypt certificates rotate every 90 days
- Requires app updates to maintain pins
- Risk of bricking terminals if pins expire
- Operational burden for 3-5 day APK signing cycle

**If re-enabling**:
1. Get current pin: `openssl s_client -connect api.avoqado.io:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64`
2. Add BOTH current AND backup pins (root CA)
3. Set up monitoring for certificate expiration
4. Update pins BEFORE expiration

**Config**: `NetworkModule.provideCertificatePinner()` returns `null`.

## Adding a New Interceptor

1. Create class implementing `okhttp3.Interceptor`
2. Add `@Inject` constructor for Hilt
3. Add to `NetworkModule.provideOkHttpClient()` builder
4. Order matters: auth → tenant → custom → logging
5. Use `@Singleton` if stateful

Example:
```kotlin
@Singleton
class CustomInterceptor @Inject constructor(
    private val secureStorage: SecureStorage
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Custom-Header", "value")
            .build()
        return chain.proceed(request)
    }
}
```
