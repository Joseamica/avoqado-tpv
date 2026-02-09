# Unit Testing Guide — Avoqado TPV

## Quick Reference

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)

# Run ALL tests (166 tests, ~50s)
./gradlew testSandboxDebugUnitTest

# Run a single test class
./gradlew testSandboxDebugUnitTest --tests "com.jaac.avoqado_tpv.core.data.realtime.SocketManagerTest"

# Run tests matching a pattern
./gradlew testSandboxDebugUnitTest --tests "*HomeViewModel*"

# Force re-run (skip Gradle cache)
./gradlew testSandboxDebugUnitTest --rerun-tasks
```

> **Important:** Always use `testSandboxDebugUnitTest`, not `testDebugUnitTest` (ambiguous with build variants).

---

## When to Run Tests

| Situation | Command | Why |
|-----------|---------|-----|
| Before every commit | `./gradlew testSandboxDebugUnitTest` | Catch regressions before they land |
| After refactoring | Full suite | Refactors can break unrelated tests |
| After touching a ViewModel | Full suite or the specific VM test | VMs are the most-tested layer |
| After changing data models/DTOs | Full suite | DTOs are used across many tests |
| After modifying SocketManager | `--tests "*SocketManager*"` | Socket events affect many features |
| After touching auth/token code | `--tests "*Auth*" --tests "*Token*"` | Auth bugs = full logout |

---

## Test Suite Overview (166 tests)

| Test Class | Tests | What It Covers |
|------------|-------|----------------|
| `HomeViewModelTest` | 7 | Init coroutines, staff info, sales goal, socket connection |
| `ConnectionViewModelTest` | 7 | Network state, socket reconnection, token refresh |
| `DeviceHealthViewModelTest` | 10 | Battery, memory, storage, temperature monitoring |
| `ShiftViewModelTest` | 11 | Shift open/close, settings, clock-in/out |
| `SocketManagerTest` | 18 | Connection lifecycle, event parsing, rooms, error handling |
| `CommandExecutorTest` | 25 | Remote commands (lock, maintenance, update, cache) |
| `AuthRepositoryTest` | 18 | Login, PIN auth, token refresh, rate limiting |
| `TokenAuthenticatorTest` | 6 | 401 handling, token refresh, session clearing |
| `TimeEntryDtoTest` | 9 | Clock in/out DTOs, duration calculation |
| `AttendanceVerificationTest` | 10 | Geofencing, location verification |
| Other test classes | ~45 | Various data layer and utility tests |

---

## Test Patterns Used in This Project

### 1. ViewModel Tests — UnconfinedTestDispatcher

ViewModels with `while(true)` loops or StateFlow collectors **must** use `UnconfinedTestDispatcher`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule() // Replaces Dispatchers.Main

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `test something`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        // ... assertions ...
        viewModel.viewModelScope.cancel() // CRITICAL: prevents runTest hang
    }
}
```

> **Never use `StandardTestDispatcher`** with StateFlow collectors — causes infinite loops.

### 2. MockK Basics

```kotlin
// Relaxed mock (returns defaults for unconfigured calls)
val mockRepo = mockk<MyRepository>(relaxed = true)

// Configure return values
every { mockRepo.getData() } returns "test"
coEvery { mockRepo.fetchAsync() } returns Result.Success(data)

// Verify calls
verify { mockRepo.getData() }
coVerify(exactly = 1) { mockRepo.fetchAsync() }

// kotlin.Result (inline class) — use answers{} not returns
coEvery { mockRepo.getSomething() } answers { kotlin.Result.success(value) }
```

### 3. Socket.IO Tests — Static Mock + connect()

SocketManager tests need `IO.socket()` mocked statically and `connect()` called to register listeners:

```kotlin
mockkStatic(IO::class)
every { IO.socket(any<URI>(), any<IO.Options>()) } returns mockSocket
socketManager.connect("https://test.url", "token")
// Now capturedListeners are populated and can be triggered
```

### 4. Turbine for Flow/SharedFlow Testing

```kotlin
socketManager.events.test {
    // Trigger an event
    capturedListeners["payment_completed"]?.call(paymentJson)

    // Assert emission
    val event = awaitItem()
    assertThat(event).isInstanceOf(SocketEvent.PaymentCompleted::class.java)
    cancelAndIgnoreRemainingEvents()
}
```

---

## Common Pitfalls

| Problem | Cause | Fix |
|---------|-------|-----|
| `runTest` hangs forever | ViewModel has `while(true)` loop | Call `viewModel.viewModelScope.cancel()` at end of test |
| `Method not mocked` crash | Using `android.os.SystemClock` | Use `System.currentTimeMillis()` instead |
| `StandardTestDispatcher` infinite loop | StateFlow collector blocks dispatcher | Use `UnconfinedTestDispatcher` |
| `Dispatchers.IO` coroutines not controlled | Test dispatcher only controls `Main` | Inject dispatcher or skip IO-dependent tests |
| MockK `returns` fails on `kotlin.Result` | `Result` is an inline class | Use `answers { Result.success(x) }` |
| Turbine `awaitItem()` times out | SharedFlow event never emitted | Verify listeners are registered (call `connect()`) |
| Gradle shows UP-TO-DATE (no rerun) | Build cache | Add `--rerun-tasks` flag |

---

## Adding New Tests

### File Location

Tests mirror the source structure:
```
app/src/test/java/com/jaac/avoqado_tpv/
├── core/
│   ├── data/realtime/SocketManagerTest.kt
│   ├── data/network/interceptors/TokenAuthenticatorTest.kt
│   └── presentation/viewmodels/
│       ├── HomeViewModelTest.kt
│       ├── ConnectionViewModelTest.kt
│       └── DeviceHealthViewModelTest.kt
├── features/
│   ├── authentication/data/repository/AuthRepositoryTest.kt
│   ├── remote_command/domain/CommandExecutorTest.kt
│   ├── shift/presentation/ShiftViewModelTest.kt
│   └── timeclock/...
└── MainDispatcherRule.kt  ← Shared test rule
```

### MainDispatcherRule

All ViewModel tests should use the shared `MainDispatcherRule`:

```kotlin
@get:Rule
val mainDispatcherRule = MainDispatcherRule()
```

This replaces `Dispatchers.Main` with a test dispatcher so coroutines don't crash in unit tests.

### Test Dependencies (build.gradle.kts)

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
testImplementation("com.google.truth:truth:1.4.4")
testImplementation("app.cash.turbine:turbine:1.2.0")
```

---

## Reading Test Results

```bash
# HTML report (opens in browser)
open app/build/reports/tests/testSandboxDebugUnitTest/index.html

# XML results (for CI/parsing)
ls app/build/test-results/testSandboxDebugUnitTest/

# Quick failure check
find app/build/test-results/testSandboxDebugUnitTest -name "*.xml" \
  -exec grep 'failures="[1-9]' {} \;
```
