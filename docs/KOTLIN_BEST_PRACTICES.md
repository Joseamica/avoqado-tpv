# Kotlin Best Practices for Avoqado TPV

**Purpose**: World-class Kotlin/Android patterns inspired by Toast and Square engineering standards.

---

## 1. Core Principles: Anti-Hallucination Protocol

> **MANDATORY**: All code MUST follow world-class Kotlin/Android best practices. NO exceptions unless explicitly documented.

### Zero Tolerance for Bad Practices

#### ❌ NEVER Do This

```kotlin
// ❌ Magic numbers
val timeout = 5000

// ❌ Mutable state without StateFlow
var isLoading = false

// ❌ Blocking calls on Main thread
suspend fun loadData() { database.query() }

// ❌ Nullability hacks
val value = data!!.field!!.value!!

// ❌ Exception swallowing
try { riskyOperation() } catch (e: Exception) { }

// ❌ Manual threading
Thread { doWork() }.start()
```

#### ✅ ALWAYS Do This

```kotlin
// ✅ Named constants
private const val NETWORK_TIMEOUT_MS = 5000L

// ✅ Immutable state with StateFlow
private val _state = MutableStateFlow<UiState>(UiState.Idle)
val state: StateFlow<UiState> = _state.asStateFlow()

// ✅ Proper coroutine dispatchers
suspend fun loadData() = withContext(Dispatchers.IO) { database.query() }

// ✅ Safe null handling
val value = data?.field?.value ?: defaultValue

// ✅ Proper error handling
try {
    riskyOperation()
} catch (e: NetworkException) {
    Timber.e(e, "Network error")
    emit(Result.Error(e.toUserFriendlyMessage()))
}

// ✅ Structured concurrency
viewModelScope.launch { doWork() }
```

---

## 2. Anti-Overengineering Protocol

> **CRITICAL**: Before implementing any solution, ALWAYS verify it's the MINIMUM necessary code. Review TWICE to prevent overengineering.

### The 3-Question Test (Ask Before Every Implementation)

1. **"Does this already work?"** → Check existing code first. 99% of cases may already be handled.
2. **"Is this the simplest solution?"** → Can I achieve the same with fewer files/lines?
3. **"Am I solving a real problem?"** → Is this fixing an actual bug or a hypothetical one?

### Red Flags 🚩 (STOP and Reconsider)

```kotlin
// 🚩 RED FLAG: Modifying 7+ files for a "simple" fix
// Ask: Can I do this with 3 files or less?

// 🚩 RED FLAG: Adding JWT decoding, proactive token refresh, new managers
// Ask: Does TokenAuthenticator already handle 401s automatically?

// 🚩 RED FLAG: Creating new abstractions "for future flexibility"
// Ask: Do I need this NOW or am I guessing future requirements?

// 🚩 RED FLAG: Adding validation that duplicates existing validation
// Ask: Is this already validated elsewhere in the flow?
```

### Minimal Solution Pattern

```kotlin
// ❌ OVERENGINEERED: 7 files, JWT decoding, proactive refresh, new managers
// "What if the token expires mid-payment? Let me decode JWT, track expiry,
//  add proactive refresh, create a SessionHealthChecker..."

// ✅ MINIMAL: 3 files, simple null check
// "If venueId/staffId are null, show error + login button. Done."
fun startPayment(amount: String) {
    val venueId = authRepository.getVenueId()
    val staffId = authRepository.getStaffId()

    if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
        _state.value = PaymentState.Error(
            message = "Tu sesión expiró.\n\nPor favor inicia sesión de nuevo.",
            showLoginButton = true
        )
        return
    }
    // Continue with existing flow...
}
```

### Before Implementing, Check:

- [ ] **Existing handlers**: Does TokenAuthenticator/SessionManager already cover this?
- [ ] **Scope creep**: Am I adding features nobody asked for?
- [ ] **Hypothetical bugs**: Am I fixing a bug that hasn't happened?
- [ ] **File count**: Can I reduce the number of modified files?
- [ ] **Line count**: Can I achieve the same with less code?

**Rule of Thumb**: If your "fix" touches more than 3-4 files, STOP and ask yourself if you're overengineering.

---

## 3. Naming Conventions

```kotlin
// Classes: PascalCase
class PaymentViewModel

// Functions: camelCase (verb-based)
fun processPayment()
fun calculateTotal()

// Properties: camelCase
val totalAmount: BigDecimal

// Constants: SCREAMING_SNAKE_CASE
const val MAX_RETRY_ATTEMPTS = 3

// Backing properties: underscore prefix
private val _state = MutableStateFlow(State.Idle)
val state: StateFlow<State> = _state.asStateFlow()

// Sealed classes for state
sealed class PaymentState {
    data object Idle : PaymentState()
    data class Loading(val progress: Float) : PaymentState()
    data class Success(val result: TransactionResult) : PaymentState()
    data class Error(val error: PaymentError) : PaymentState()
}
```

---

## 4. Architecture Patterns (Clean Architecture)

```
┌─────────────────────────────────────────┐
│ PRESENTATION (UI)                       │
│ • Composables (stateless)               │
│ • ViewModels (StateFlow)                │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ DOMAIN (Business Logic)                 │
│ • UseCases (single responsibility)      │
│ • Repository Interfaces                 │
│ • Domain Models                         │
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│ DATA (Infrastructure)                   │
│ • Repository Implementations            │
│ • Data Sources (API, DB, SDK)           │
│ • DTOs → Domain Mappers                 │
└─────────────────────────────────────────┘
```

### Complete Example

```kotlin
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// PRESENTATION: ViewModel
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val processPaymentUseCase: ProcessPaymentUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val state: StateFlow<PaymentState> = _state.asStateFlow()

    fun processPayment(request: PaymentRequest) {
        viewModelScope.launch {
            _state.value = PaymentState.Loading
            processPaymentUseCase(request)
                .onSuccess { result -> _state.value = PaymentState.Success(result) }
                .onFailure { error -> _state.value = PaymentState.Error(error) }
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// DOMAIN: UseCase
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class ProcessPaymentUseCase @Inject constructor(
    private val paymentRepository: PaymentRepository
) {
    suspend operator fun invoke(request: PaymentRequest): Result<TransactionResult> {
        return try {
            val result = paymentRepository.processPayment(request)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// DATA: Repository Implementation
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
class PaymentRepositoryImpl @Inject constructor(
    private val blumonSdk: BlumonPaySDK,
    private val api: PaymentApi
) : PaymentRepository {
    override suspend fun processPayment(request: PaymentRequest): TransactionResult {
        return withContext(Dispatchers.IO) {
            val sdkResult = blumonSdk.processPayment(request.toSdkRequest())
            api.recordPayment(sdkResult.toDto())
            sdkResult.toDomain()
        }
    }
}
```

---

## 5. Error Handling Philosophy

### Always Translate Technical Errors to User-Friendly Messages

```kotlin
// ❌ WRONG: Technical error exposed
catch (e: Exception) {
    _state.value = State.Error("Error: $e")
    // User sees: "ReadingContactlessFailure@efcd17c"
}

// ✅ CORRECT: User-friendly translation
if (result.isLeft) {
    val error = result.leftValue()
    Timber.e("❌ [TECHNICAL] Contactless failed: $error")

    val userMessage = when {
        error.toString().contains("ReadingContactlessFailure", ignoreCase = true) -> {
            "La tarjeta se retiró demasiado rápido.\n\n" +
            "Por favor, mantenga la tarjeta sobre el lector hasta que " +
            "aparezca el mensaje de confirmación."
        }
        error.toString().contains("Timeout", ignoreCase = true) -> {
            "Tiempo de espera agotado.\n\n" +
            "Por favor, mantenga la tarjeta cerca del lector durante toda la transacción."
        }
        error.toString().contains("NetworkException", ignoreCase = true) -> {
            "No se pudo conectar al servidor.\n\n" +
            "Verifique su conexión a internet e intente nuevamente."
        }
        else -> {
            "Error leyendo tarjeta contactless.\n\n" +
            "Intente nuevamente o inserte la tarjeta en el chip."
        }
    }

    _state.value = State.Error(userMessage)
}
```

**Every error message MUST have:**
1. ✅ **What happened** (in simple terms) - "La tarjeta se retiró demasiado rápido"
2. ✅ **How to fix it** (actionable steps) - "Mantenga la tarjeta sobre el lector"
3. ✅ **Alternative action** (if available) - "Intente nuevamente o inserte en el chip"

---

## 6. Composable UI Structure

```kotlin
@Composable
fun FeatureScreen(
    viewModel: FeatureViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ResponsiveScaffold(scrollable = false) {
        val sizes = LocalResponsiveSizes.current

        when (val currentState = state) {
            is State.Loading -> AvoqadoLoadingOverlay(message = "Cargando...")
            is State.Success -> SuccessContent(currentState.data)
            is State.Error -> ErrorMessage(currentState.message)
        }
    }
}

// ✅ MUST HAVE Preview
@Preview(showBackground = true)
@Composable
private fun FeatureScreenPreview() {
    AvoqadoTheme {
        FeatureScreen()
    }
}
```

---

## 7. Coroutine Best Practices

```kotlin
// ✅ ALWAYS use proper dispatchers
suspend fun loadFromDatabase() = withContext(Dispatchers.IO) {
    database.query()
}

// ✅ ALWAYS use viewModelScope (auto-cancellation)
viewModelScope.launch {
    loadData()
}

// ✅ ALWAYS handle errors in coroutines
viewModelScope.launch {
    try {
        val result = loadData()
        _state.value = State.Success(result)
    } catch (e: Exception) {
        Timber.e(e, "Failed to load data")
        _state.value = State.Error(e.toUserFriendlyMessage())
    }
}

// ❌ NEVER swallow cancellation
try {
    riskyOperation()
} catch (e: Exception) {
    // WRONG: This catches CancellationException too!
}

// ✅ CORRECT: Re-throw CancellationException
try {
    riskyOperation()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.e(e, "Error in riskyOperation")
}
```

---

## 8. StateFlow vs LiveData

**Always use StateFlow** (Kotlin-first, better flow operators)

```kotlin
// ✅ CORRECT: StateFlow
private val _state = MutableStateFlow<UiState>(UiState.Idle)
val state: StateFlow<UiState> = _state.asStateFlow()

// ❌ AVOID: LiveData (legacy Java API)
private val _state = MutableLiveData<UiState>()
val state: LiveData<UiState> = _state
```

---

## 9. Dependency Injection (Hilt)

```kotlin
// ✅ CORRECT: Constructor injection
@HiltViewModel
class PaymentViewModel @Inject constructor(
    private val processPaymentUseCase: ProcessPaymentUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {
    // ...
}

// ❌ WRONG: Field injection (harder to test)
@HiltViewModel
class PaymentViewModel : ViewModel() {
    @Inject lateinit var processPaymentUseCase: ProcessPaymentUseCase
}
```

---

## 10. Common Anti-Patterns to Avoid

| Anti-Pattern | Why Bad | Solution |
|--------------|---------|----------|
| `GlobalScope.launch` | Leaks memory, no auto-cancellation | Use `viewModelScope.launch` |
| `runBlocking` in UI code | Freezes UI thread | Use `suspend` functions |
| `!!` null assertion | Crashes on null | Use `?.` or `?:` |
| Mutable state in ViewModels | Race conditions | Use StateFlow |
| Blocking I/O on Main | ANR (App Not Responding) | Use `Dispatchers.IO` |
| Hard-coded strings | Not translatable | Use `stringResource()` |
| Magic numbers | Unclear meaning | Use named constants |

---

**Last Updated:** 2025-12-12
