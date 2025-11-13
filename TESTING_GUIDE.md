# Testing Guide - Avoqado TPV

> **Main Context:** See [CLAUDE.md](./CLAUDE.md) for core principles and quick reference

---

## 📋 Table of Contents

1. [Testing Strategy](#testing-strategy)
2. [Unit Tests](#unit-tests)
3. [Integration Tests](#integration-tests)
4. [Test Scripts](#test-scripts)
5. [Debugging Tools](#debugging-tools)
6. [Common Issues](#common-issues)

---

## Testing Strategy

### Test Pyramid

```
           ┌─────────────┐
           │  Manual     │  ← Merchant testing, UAT
           │  Testing    │
           └─────────────┘
         ┌─────────────────┐
         │  Integration    │  ← Critical flows (payment end-to-end)
         │    Tests        │
         └─────────────────┘
    ┌────────────────────────┐
    │     Unit Tests         │  ← ViewModels, repositories
    │  (Fast, isolated)      │
    └────────────────────────┘
```

### What to Test

#### Unit Tests (Fast, isolated)
- ✅ ViewModels: State transitions
- ✅ Repositories: Data transformation
- ✅ Use cases: Business logic
- ✅ Utilities: Formatters, validators

#### Integration Tests (Slower, realistic)
- ✅ Critical flows: Login → Payment → Receipt
- ✅ Backend integration: API calls
- ✅ Database operations: CRUD flows

#### Manual Tests (Slowest, comprehensive)
- ✅ Payment hardware: Card readers
- ✅ Network scenarios: Offline, slow connection
- ✅ Multi-merchant switching
- ✅ User experience: Real-world scenarios

---

## Unit Tests

### Setup

#### Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
}
```

### ViewModel Testing Pattern

#### Example: PaymentViewModel

```kotlin
class PaymentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Mocks
    private lateinit var processPaymentUseCase: ProcessPaymentUseCase
    private lateinit var viewModel: PaymentViewModel

    @Before
    fun setup() {
        processPaymentUseCase = mockk(relaxed = true)
        viewModel = PaymentViewModel(processPaymentUseCase)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `should process payment successfully`() = runTest {
        // Given
        val payment = Payment(amount = 500, tip = 50)
        coEvery { processPaymentUseCase(any()) } returns Result.success(payment)

        // When
        viewModel.processPayment(payment)

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Success::class.java)
        coVerify(exactly = 1) { processPaymentUseCase(any()) }
    }

    @Test
    fun `should handle payment failure`() = runTest {
        // Given
        val error = Exception("Payment failed")
        coEvery { processPaymentUseCase(any()) } returns Result.failure(error)

        // When
        viewModel.processPayment(Payment(amount = 500))

        // Then
        val state = viewModel.state.value
        assertThat(state).isInstanceOf(PaymentState.Error::class.java)
    }

    @Test
    fun `should transition through payment states correctly`() = runTest {
        // Given
        val payment = Payment(amount = 500)
        coEvery { processPaymentUseCase(any()) } coAnswers {
            delay(100)  // Simulate processing time
            Result.success(payment)
        }

        // When
        viewModel.state.test {
            viewModel.processPayment(payment)

            // Then
            assertThat(awaitItem()).isEqualTo(PaymentState.Idle)
            assertThat(awaitItem()).isEqualTo(PaymentState.Loading)
            assertThat(awaitItem()).isInstanceOf(PaymentState.Success::class.java)
        }
    }
}
```

### Repository Testing Pattern

```kotlin
class PaymentRepositoryTest {
    private lateinit var apiService: ApiService
    private lateinit var database: PaymentDatabase
    private lateinit var repository: PaymentRepositoryImpl

    @Before
    fun setup() {
        apiService = mockk(relaxed = true)
        database = mockk(relaxed = true)
        repository = PaymentRepositoryImpl(apiService, database)
    }

    @Test
    fun `should save payment to database`() = runTest {
        // Given
        val payment = Payment(id = "1", amount = 500)
        coEvery { database.paymentDao().insert(any()) } returns Unit

        // When
        repository.savePayment(payment)

        // Then
        coVerify(exactly = 1) { database.paymentDao().insert(payment) }
    }

    @Test
    fun `should sync payment with backend`() = runTest {
        // Given
        val payment = Payment(id = "1", amount = 500)
        coEvery { apiService.recordPayment(any()) } returns Response.success(payment)

        // When
        val result = repository.recordPayment(payment)

        // Then
        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) { apiService.recordPayment(any()) }
    }
}
```

### Running Unit Tests

```bash
# Run all unit tests
./gradlew test

# Run specific test class
./gradlew test --tests "*PaymentViewModelTest"

# Run specific test method
./gradlew test --tests "*PaymentViewModelTest.should process payment successfully"

# Run with coverage
./gradlew testDebugUnitTestCoverage

# View results
open app/build/reports/tests/testDebugUnitTest/index.html
```

---

## Integration Tests

### Critical Flow: Complete Payment

```kotlin
@Test
fun `complete payment flow from cart to receipt`() = runTest {
    // 1. Create order
    val order = orderRepository.createOrder(
        items = listOf(
            OrderItem(productId = "1", quantity = 2, price = 1000),
            OrderItem(productId = "2", quantity = 1, price = 500)
        ),
        venueId = "venue_123"
    )

    assertThat(order.total).isEqualTo(2500)
    assertThat(order.status).isEqualTo(OrderStatus.PENDING)

    // 2. Process payment
    val payment = paymentRepository.processPayment(
        orderId = order.id,
        amount = 2500,
        tip = 250,
        merchantAccountId = "merchant_001",
        method = PaymentMethod.CARD
    )

    assertThat(payment.status).isEqualTo(PaymentStatus.COMPLETED)

    // 3. Verify backend sync
    val updatedOrder = orderRepository.getOrder(order.id)
    assertThat(updatedOrder.status).isEqualTo(OrderStatus.PAID)
    assertThat(updatedOrder.paymentId).isEqualTo(payment.id)

    // 4. Verify receipt generated
    val receipt = receiptRepository.getReceipt(payment.id)
    assertThat(receipt).isNotNull()
    assertThat(receipt.qrCodeUrl).isNotEmpty()
}
```

### Integration Test: Token Refresh

```kotlin
@Test
fun `should refresh token on 401 and retry request`() = runTest {
    // Given: Expired token
    secureStorage.saveToken("expired_token")

    // First call returns 401
    coEvery { apiService.getOrders(any()) } returns Response.error(401, "Unauthorized".toResponseBody())

    // Token refresh succeeds
    coEvery { authRepository.refreshAccessToken() } returns Result.Success(
        RefreshTokenResponse(
            accessToken = "new_token",
            expiresIn = 3600,
            tokenType = "Bearer"
        )
    )

    // Retry with new token succeeds
    coEvery { apiService.getOrders(any()) } returns Response.success(listOf(Order(...)))

    // When
    val result = orderRepository.getOrders("venue_123")

    // Then
    assertThat(result.isSuccess).isTrue()
    coVerify(exactly = 1) { authRepository.refreshAccessToken() }
    assertThat(secureStorage.getToken()).isEqualTo("new_token")
}
```

---

## Test Scripts

### Token Refresh Test Script

**File**: `test_token_refresh.sh`

#### Purpose
Tests automatic token refresh when 401 Unauthorized is received during payment recording.

#### Prerequisites
1. Android device/emulator connected (`adb devices`)
2. App installed (`./gradlew installDebug`)
3. Backend running (`npm run dev`)
4. Backend configured with SHORT token expiration (30s)

#### Backend Configuration

**File**: `avoqado-server/src/services/auth/auth.service.ts`

```typescript
// Change token expiration from 24h to 30s for testing
const accessToken = jwt.sign(
  {
    sub: staff.id,
    venueId: venue.id,
    role: staff.role,
    permissions: permissions,
  },
  process.env.JWT_SECRET!,
  { expiresIn: '30s' }  // ← 30 seconds for testing (was '24h')
);
```

**⚠️ IMPORTANT**: Restart backend after changing:
```bash
cd avoqado-server
npm run dev
```

#### Running the Script

```bash
# 1. Make script executable
chmod +x test_token_refresh.sh

# 2. Run the test
./test_token_refresh.sh

# 3. Follow on-screen instructions:
#    - Enter PIN when prompted
#    - Attempt payment after 35 seconds
#    - Script analyzes logs automatically
```

#### Expected Output

```
╔════════════════════════════════════════════════════════════╗
║                    ✅ TEST PASSED                         ║
╔════════════════════════════════════════════════════════════╗

✅ Token expired (401 received)
✅ Token refresh triggered
✅ Token refreshed successfully
✅ Original request retried
✅ Payment recorded to backend
✅ QR code should be displayed
```

#### Manual Testing (Step by Step)

1. **Configure backend** with 30s token expiration (see above)

2. **Clear app data**:
   ```bash
   adb shell pm clear com.jaac.avoqado_tpv
   ```

3. **Launch app**:
   ```bash
   adb shell am start -n com.jaac.avoqado_tpv/.MainActivity
   ```

4. **Start monitoring logs**:
   ```bash
   adb logcat -c
   adb logcat | grep -E "(Auth|Token|Refresh|401|Backend Recording)"
   ```

5. **Login with PIN**: Select venue, enter PIN `1234`, tap "Iniciar Sesión"

6. **Wait for token to expire** (35 seconds):
   ```bash
   for i in {35..1}; do echo "Token expires in: ${i}s"; sleep 1; done
   ```

7. **Attempt payment**:
   - Tap "Cobrar Rápido"
   - Enter amount: `$50.00`
   - Skip rating (or rate 5 stars)
   - Skip tip (or add tip)
   - Select "Pagar en Efectivo" (cash payment - no card needed)

8. **Verify in logs**:
   ```
   ⚠️ [Auth] Received 401 Unauthorized - Token expired, attempting refresh...
   ✅ [Auth] Token refreshed successfully, retrying original request
   ✅ [Backend Recording] Payment recorded successfully | paymentId=...
   ✅ [Receipt] Updated Success state with receipt | URL=...
   ```

---

## Debugging Tools

### ADB Log Monitoring

#### Basic Filtering

```bash
# Clear logs and monitor with filters
adb logcat -c && adb logcat | grep -E "AvoqadoTPV|Payment|Socket" --line-buffered

# Monitor specific component
adb logcat | grep -E "PaymentViewModel|SocketIO"

# Save logs to file
adb logcat > logs.txt
```

#### Advanced Filtering

```bash
# Monitor auth flow
adb logcat | grep -E "Auth|Token|Refresh|401"

# Monitor payment flow
adb logcat | grep -E "Payment|Amount|Receipt|QR"

# Monitor merchant switching
adb logcat | grep -E "Merchant|PosId|DUKPT|SDK"

# Monitor errors only
adb logcat *:E

# Multiple tags with priority
adb logcat PaymentViewModel:D AuthRepository:D *:S
```

### Socket.IO Debugging

#### Enable Debug Logs

```kotlin
// In development builds
if (BuildConfig.DEBUG) {
    socket.on(Socket.EVENT_CONNECT) {
        Timber.d("✅ Socket connected")
    }

    socket.on(Socket.EVENT_DISCONNECT) {
        Timber.w("⚠️ Socket disconnected")
    }

    socket.on(Socket.EVENT_ERROR) { args ->
        Timber.e("❌ Socket error: ${args[0]}")
    }

    socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
        Timber.e("❌ Socket connection error: ${args[0]}")
    }
}
```

#### Monitor Socket Events

```bash
# Monitor all socket events
adb logcat | grep -E "Socket|WebSocket|room_"

# Monitor specific room
adb logcat | grep -E "venue_.*|order_updated"

# Monitor connection issues
adb logcat | grep -E "Socket.*error|disconnected"
```

### Network Traffic Inspection

#### Using Charles Proxy

1. **Setup**:
   - Install Charles Proxy
   - Configure Android to use Charles as proxy
   - Install Charles SSL certificate on device

2. **Monitor**:
   - Filter by `api.avoqado.io`
   - View request/response bodies
   - Check headers (Authorization, venueId)

3. **Test Scenarios**:
   - Slow network: Throttle to 3G
   - Network failure: Block specific endpoints
   - 401 errors: Remove Authorization header

#### Using Android Studio Network Profiler

1. Open Android Studio → Profiler
2. Select device and app
3. View network requests in real-time
4. Inspect request/response details

### Device Testing

#### Clear App Data

```bash
# Clear all app data (forces re-login)
adb shell pm clear com.jaac.avoqado_tpv

# Clear shared preferences only
adb shell run-as com.jaac.avoqado_tpv rm -rf /data/data/com.jaac.avoqado_tpv/shared_prefs/
```

#### Simulate Network Conditions

```bash
# Enable airplane mode
adb shell cmd connectivity airplane-mode enable

# Disable airplane mode
adb shell cmd connectivity airplane-mode disable

# Simulate slow network (requires root)
adb shell
tc qdisc add dev wlan0 root netem delay 500ms
```

---

## Common Issues

### Issue: Unit Tests Fail to Compile

**Symptoms**:
```
e: Unresolved reference: mockk
e: Unresolved reference: runTest
```

**Solution**:
```kotlin
// Add test dependencies
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

// Sync Gradle
./gradlew clean build
```

### Issue: Test Timeout

**Symptoms**:
```
Test timed out after 60 seconds
```

**Solution**:
```kotlin
// Increase timeout in test
@Test(timeout = 120_000)  // 2 minutes
fun `long running test`() = runTest {
    // ...
}

// Or use runTest with timeout
@Test
fun `test with custom timeout`() = runTest(timeout = 2.minutes) {
    // ...
}
```

### Issue: Mock Not Working

**Symptoms**:
```
io.mockk.MockKException: no answer found for: fun getOrders()
```

**Solution**:
```kotlin
// Use relaxed mock
private val repository = mockk<PaymentRepository>(relaxed = true)

// Or provide explicit answer
coEvery { repository.getOrders(any()) } returns emptyList()
```

### Issue: Flow Test Fails

**Symptoms**:
```
Expected 3 emissions but got 2
```

**Solution**:
```kotlin
// Use Turbine for flow testing
@Test
fun `should emit all states`() = runTest {
    viewModel.state.test {
        // Explicitly await each emission
        assertThat(awaitItem()).isEqualTo(State.Idle)
        assertThat(awaitItem()).isEqualTo(State.Loading)
        assertThat(awaitItem()).isEqualTo(State.Success)

        // Cancel if done
        cancelAndConsumeRemainingEvents()
    }
}
```

### Issue: Token Refresh Test Inconclusive

**Symptoms**:
```
⚠️ TEST INCONCLUSIVE
⚠️ No 401 error detected
```

**Solution**:
1. Verify backend token expiration is set to `30s`
2. Restart backend (`npm run dev`)
3. Clear app data (`adb shell pm clear com.jaac.avoqado_tpv`)
4. Run script again

---

## Testing Checklist

### Before Every Commit

- [ ] Unit tests pass: `./gradlew test`
- [ ] Code compiles: `./gradlew compileDebugKotlin`
- [ ] Lint passes: `./gradlew lint --continue`
- [ ] No debug logs in production code
- [ ] Test coverage for new code

### Before Release

- [ ] Integration tests pass
- [ ] Manual testing on real device
- [ ] Token refresh tested (run `test_token_refresh.sh`)
- [ ] Multi-merchant switching tested
- [ ] Offline scenarios tested
- [ ] Payment hardware tested (card readers)

---

## Additional Resources

### Test Files
- Unit tests: `app/src/test/java/`
- Integration tests: `app/src/androidTest/java/`
- Test scripts: `test_token_refresh.sh`

### Documentation
- Token Refresh Guide: `TOKEN_REFRESH_TEST_GUIDE.md`
- Testing best practices: [Android Testing](https://developer.android.com/training/testing)
- MockK docs: [MockK](https://mockk.io/)
- Turbine docs: [Turbine](https://github.com/cashapp/turbine)

---

**Last Updated:** 2025-01-11
**Maintainer:** Development Team
