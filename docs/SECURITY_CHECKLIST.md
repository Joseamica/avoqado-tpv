# Security Checklist - Avoqado TPV

> **Main Context:** See [CLAUDE.md](./CLAUDE.md) for core principles and quick reference

---

## 📋 Table of Contents

1. [Security Principles](#security-principles)
2. [Encrypted Storage](#encrypted-storage)
3. [Certificate Pinning](#certificate-pinning)
4. [Tenant Isolation](#tenant-isolation)
5. [Rate Limiting](#rate-limiting)
6. [Input Validation](#input-validation)
7. [Secrets Management](#secrets-management)
8. [Common Security Pitfalls](#common-security-pitfalls)
9. [Security Testing](#security-testing)

---

## Security Principles

### Defense in Depth

Avoqado TPV implements multiple layers of security:

```
┌─────────────────────────────────────────┐
│ Layer 1: Network (Certificate Pinning) │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ Layer 2: Authentication (JWT + Refresh)│
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ Layer 3: Authorization (Tenant Filter) │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ Layer 4: Data (Encrypted Storage)      │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ Layer 5: Input (Validation & Sanitize) │
└─────────────────────────────────────────┘
```

### Security-First Mindset

**MANDATORY Rules:**
- ✅ Encrypt ALL sensitive data at rest
- ✅ Use HTTPS with certificate pinning for ALL network calls
- ✅ ALWAYS filter database queries by `venueId` (tenant isolation)
- ✅ Validate ALL user input (amount, PIN, search queries)
- ✅ NEVER log sensitive data (tokens, PINs, card numbers)
- ✅ NEVER hardcode secrets in code (use environment variables)
- ✅ Handle rate limiting gracefully (429 responses)

---

## Encrypted Storage

### Implementation

**File:** `core/data/local/SecureStorage.kt`

#### Setup EncryptedSharedPreferences

```kotlin
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.Context
import android.content.SharedPreferences

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_session",  // File name
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Save token (encrypted automatically)
    fun saveToken(token: String) {
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .apply()
    }

    // Retrieve token (decrypted automatically)
    fun getToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    // Clear all session data
    fun clearSession() {
        encryptedPrefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_VENUE_ID = "venue_id"
        private const val KEY_STAFF_ID = "staff_id"
    }
}
```

### What Gets Encrypted

| Data | Storage Location | Why Encrypted |
|------|------------------|---------------|
| **Access Token** | EncryptedSharedPreferences | Prevents token theft if device compromised |
| **Refresh Token** | EncryptedSharedPreferences | Long-lived token (7 days) - critical |
| **Venue ID** | EncryptedSharedPreferences | Tenant isolation - prevents cross-tenant access |
| **Staff ID** | EncryptedSharedPreferences | User identification - privacy concern |
| **Blumon Credentials** | Database (encrypted JSON) | API keys + DUKPT keys - NEVER in plain text |

### Hilt Integration

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context
    ): SecureStorage {
        return SecureStorage(context)
    }
}
```

---

## Certificate Pinning

### Configuration

**File:** `core/di/NetworkModule.kt`

#### Setup OkHttp with Certificate Pinning

```kotlin
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            .add(
                "api.avoqado.io",
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="  // Replace with actual pin
            )
            .add(
                "api.avoqado.io",
                "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="  // Backup pin
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        certificatePinner: CertificatePinner,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .certificatePinner(certificatePinner)  // ← Pinning enabled
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
```

### Getting Certificate Pins

```bash
# Get certificate pins for api.avoqado.io
openssl s_client -connect api.avoqado.io:443 | \
  openssl x509 -pubkey -noout | \
  openssl rsa -pubin -outform der | \
  openssl dgst -sha256 -binary | \
  openssl enc -base64

# Example output:
# sha256/Vjs8r4z+80wjNcr1YKepWQboSIRi63WsWXhIMN+eWys=
```

### Testing Certificate Pinning

```bash
# 1. Enable pinning in NetworkModule
# 2. Install app
./gradlew installDebug

# 3. Attempt MITM attack (should fail)
# Using Charles Proxy or similar → Connection should fail with CertificatePinningException

# 4. Check logs
adb logcat | grep -E "CertificatePinner|SSL"

# Expected error if MITM attempted:
# javax.net.ssl.SSLPeerUnverifiedException: Certificate pinning failure!
```

---

## Tenant Isolation

### Critical Rule: ALWAYS Filter by venueId

> **SECURITY RISK**: Without `venueId` filtering, restaurants can access each other's data (orders, payments, customers).

#### ✅ CORRECT: All queries filtered by venueId

```kotlin
// ✅ Repository interface (domain layer)
interface OrderRepository {
    suspend fun getOrders(venueId: String): Flow<List<Order>>
    suspend fun getOrder(venueId: String, orderId: String): Order?
    suspend fun createOrder(venueId: String, order: Order): Order
}

// ✅ Repository implementation (data layer)
class OrderRepositoryImpl @Inject constructor(
    private val api: AvoqadoService,
    private val database: AvoqadoDatabase
) : OrderRepository {

    override suspend fun getOrders(venueId: String): Flow<List<Order>> {
        return database.orderDao()
            .getOrdersByVenue(venueId)  // ← ALWAYS filter
            .map { it.map { entity -> entity.toDomain() } }
    }

    override suspend fun getOrder(venueId: String, orderId: String): Order? {
        return database.orderDao()
            .getOrderById(venueId, orderId)  // ← ALWAYS include venueId
            ?.toDomain()
    }
}

// ✅ Room DAO with tenant isolation
@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE venueId = :venueId")
    fun getOrdersByVenue(venueId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE venueId = :venueId AND id = :orderId")
    suspend fun getOrderById(venueId: String, orderId: String): OrderEntity?
}
```

#### ❌ WRONG: No tenant filter (SECURITY VULNERABILITY!)

```kotlin
// ❌ WRONG: Cross-tenant data leak
@Query("SELECT * FROM orders")
fun getAllOrders(): Flow<List<OrderEntity>>  // Restaurant A can see Restaurant B's orders!

// ❌ WRONG: orderId alone is NOT unique across venues
@Query("SELECT * FROM orders WHERE id = :orderId")
suspend fun getOrderById(orderId: String): OrderEntity?
```

### Backend API Tenant Isolation

**File:** `core/data/network/AvoqadoService.kt`

```kotlin
interface AvoqadoService {
    // ✅ CORRECT: venueId in path
    @GET("tpv/venues/{venueId}/orders")
    suspend fun getOrders(
        @Path("venueId") venueId: String
    ): Response<List<OrderDto>>

    @POST("tpv/venues/{venueId}/orders")
    suspend fun createOrder(
        @Path("venueId") venueId: String,
        @Body order: CreateOrderRequest
    ): Response<OrderDto>

    // ❌ WRONG: No venueId (backend should reject)
    @GET("tpv/orders")  // NEVER do this!
    suspend fun getAllOrders(): Response<List<OrderDto>>
}
```

### Testing Tenant Isolation

```kotlin
// Unit test to verify tenant isolation
@Test
fun `should only return orders for specified venueId`() = runTest {
    // Given: Two venues with orders
    database.orderDao().insert(OrderEntity(id = "1", venueId = "venue_A"))
    database.orderDao().insert(OrderEntity(id = "2", venueId = "venue_B"))

    // When: Fetch orders for venue A
    val ordersA = repository.getOrders("venue_A").first()

    // Then: Only venue A's orders returned
    assertThat(ordersA).hasSize(1)
    assertThat(ordersA[0].id).isEqualTo("1")
    assertThat(ordersA[0].venueId).isEqualTo("venue_A")
}
```

---

## Rate Limiting

> **CRITICAL**: Backend must configure environment-specific rate limits. Development environments need higher limits to prevent blocking during testing.

### Production vs Development Limits

| Endpoint | Production | Development | Reason |
|----------|-----------|-------------|---------|
| **PIN Login** | 10 attempts / 15 min | 100 attempts / 1 min | DEV needs rapid testing without lockouts |
| **Activation** | 5 attempts / 15 min | 50 attempts / 1 min | Multiple device testing in DEV |
| **API Calls** | 1000 req / hour | 10,000 req / hour | Load testing and development |
| **Refresh Token** | 20 attempts / hour | 200 attempts / hour | Session testing |

### Android Error Handling

**File:** `features/authentication/data/repository/AuthRepositoryImpl.kt`

```kotlin
when (response.code()) {
    401 -> "PIN incorrecto. Intenta nuevamente."
    403 -> "Acceso denegado. Contacta al administrador."
    429 -> {
        Timber.w("⚠️ Rate limit exceeded (429) - Backend should have higher limits in DEV")
        "Demasiados intentos. Por favor espera un momento e intenta nuevamente.\n\n" +
        "ℹ️ Si estás en desarrollo, el backend debe configurar rate limits más altos para DEV."
    }
    else -> "Error desconocido (${response.code()})"
}
```

---

## Input Validation

### MANDATORY Validation Rules

#### Amounts (Payment Input)

```kotlin
// ✅ CORRECT: Validate and sanitize
fun validateAmount(input: String): BigDecimal? {
    // Remove non-numeric characters except decimal point
    val cleaned = input.replace(Regex("[^0-9.]"), "")

    // Convert to BigDecimal
    val amount = cleaned.toBigDecimalOrNull() ?: return null

    // Business rules
    return when {
        amount <= BigDecimal.ZERO -> null  // Must be positive
        amount > BigDecimal("999999.99") -> null  // Max $999,999.99
        else -> amount
    }
}

// ❌ WRONG: No validation (crash risk + SQL injection risk)
fun processPayment(amountString: String) {
    val amount = amountString.toDouble()  // Can crash!
    // ...
}
```

#### PINs (Authentication Input)

```kotlin
// ✅ CORRECT: Validate PIN format
fun validatePin(pin: String): Boolean {
    return pin.matches(Regex("^[0-9]{4}$"))  // Exactly 4 digits
}

// ❌ WRONG: No validation (security risk)
fun login(pin: String) {
    api.loginWithPin(pin)  // Could send arbitrary strings
}
```

#### Search Queries (SQL Injection Prevention)

```kotlin
// ✅ CORRECT: Use parameterized queries
@Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' AND venueId = :venueId")
suspend fun searchProducts(query: String, venueId: String): List<ProductEntity>

// ❌ WRONG: String concatenation (SQL injection!)
@Query("SELECT * FROM products WHERE name LIKE '%$query%'")  // NEVER DO THIS!
suspend fun searchProducts(query: String): List<ProductEntity>
```

---

## Secrets Management

### NEVER Hardcode Secrets

#### ❌ WRONG: Hardcoded in Code

```kotlin
// ❌ WRONG: Exposed in version control
const val API_KEY = "sk_live_abc123"
const val BLUMON_MERCHANT_ID = "12345"
```

#### ✅ CORRECT: Environment Variables

**File:** `local.properties` (NOT committed to Git)

```properties
AVOQADO_API_KEY=your_api_key_here
BLUMON_MERCHANT_ID=your_merchant_id
```

**File:** `app/build.gradle.kts`

```kotlin
android {
    defaultConfig {
        // Read from local.properties
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        buildConfigField(
            "String",
            "API_KEY",
            "\"${localProperties.getProperty("AVOQADO_API_KEY", "")}\""
        )
    }
}
```

**File:** `.gitignore`

```
# CRITICAL: Never commit secrets
local.properties
*.keystore
*.jks
secrets/
```

---

## Common Security Pitfalls

### 1. Logging Sensitive Data

```kotlin
// ❌ WRONG: Logs sensitive data
Timber.d("User token: $token")
Timber.d("PIN entered: $pin")
Timber.d("Card number: ${card.number}")

// ✅ CORRECT: Log without sensitive data
Timber.d("User authenticated successfully")
Timber.d("PIN validation completed")
Timber.d("Card processed (last 4: ${card.lastFour})")
```

### 2. Exposing Internal Errors to Users

```kotlin
// ❌ WRONG: Technical error to user
catch (e: Exception) {
    _state.value = State.Error("Error: ${e.message}")
    // User sees: "java.net.SocketTimeoutException: timeout"
}

// ✅ CORRECT: User-friendly message
catch (e: Exception) {
    Timber.e(e, "Payment failed")  // Log technical details
    _state.value = State.Error(
        "No se pudo procesar el pago. Verifica tu conexión e intenta nuevamente."
    )
}
```

---

## Security Testing

### Pre-Commit Checklist

- [ ] **No hardcoded secrets**: Check for API keys, tokens, passwords in code
- [ ] **Tenant isolation**: All queries filter by `venueId`
- [ ] **Input validation**: All user input validated before use
- [ ] **Encrypted storage**: Sensitive data uses EncryptedSharedPreferences
- [ ] **Certificate pinning**: NetworkModule has pinning enabled
- [ ] **No sensitive logs**: Check for Timber logs with tokens, PINs, etc.
- [ ] **Error messages**: User-friendly (not technical exceptions)

### Manual Security Tests

```bash
# 1. Test certificate pinning (should fail with MITM)
# - Enable Charles Proxy / Burp Suite
# - Install app
# - Attempt login → Should see CertificatePinningException

# 2. Test tenant isolation (should NOT see other venue's data)
# - Login as Venue A
# - Check database with: adb shell
#   run-as com.jaac.avoqado_tpv
#   sqlite3 databases/avoqado.db
#   SELECT * FROM orders WHERE venueId != 'venue_A';  # Should be empty

# 3. Test encrypted storage (should be encrypted on disk)
# - adb shell
#   run-as com.jaac.avoqado_tpv
#   cat shared_prefs/secure_session.xml
#   # Should see encrypted gibberish, NOT plain text tokens
```

---

## Additional Resources

### Documentation
- [EncryptedSharedPreferences](https://developer.android.com/topic/security/data)
- [Certificate Pinning](https://square.github.io/okhttp/features/https/)
- [OWASP Mobile Top 10](https://owasp.org/www-project-mobile-top-10/)

### Related Guides
- [TESTING_GUIDE.md](./TESTING_GUIDE.md) - Security testing patterns
- [PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md) - Payment security (merchant isolation)
- [CLAUDE.md](./CLAUDE.md) - Security standards and anti-patterns

---

**Last Updated:** 2025-01-11
**Maintainer:** Development Team
