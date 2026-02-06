# Hilt DI Module Map

Complete dependency injection architecture. All modules in `core/di/` except PaymentsModule (in `features/payments/di/`).

## Module Overview Table

| Module | Scope | Location | Provides | Dependencies |
|--------|-------|----------|----------|--------------|
| NetworkModule | @Singleton | `core/di/` | OkHttpClient, Retrofit, ApiService, Interceptors, Authenticator | SecureStorage, UpdateCheckManager |
| DatabaseModule | @Singleton | `core/di/` | AvoqadoDatabase, 10 DAOs, Room migrations | ApplicationContext |
| RepositoryModule | @Singleton | `core/di/` | 6 repository bindings (interface → impl) | ApiService, DAOs |
| AppModule | @Singleton | `core/di/` | ApplicationContext, CellLocationApi | ApiService |
| ActivationModule | @Singleton | `core/di/` | ActivationRepository | ApiService, SecureStorage |
| PaymentModule | @Singleton | `core/di/` | Blumon OAuth (TokenServer, CoreServer, BlumonAuthManager), Payment recording (FastPaymentRecorder, OrderPaymentRecorder), PaymentQueueRepository | DeviceInfoManager, PendingPaymentDao |
| OrderingModule | @Singleton | `core/di/` | 6 API services + repositories (Table, FloorElement, Product, Order, Customer, Discount) | Retrofit, DraftOrderDao, DraftOrderItemDao |
| PrinterModule | @Singleton | `core/di/` | PrinterManager (Neptune SDK wrapper) | ApplicationContext |
| SocketModule | @Singleton | `core/di/` | SocketManager (Socket.IO wrapper) | SecureStorage |
| PaymentsModule | @Singleton | `features/payments/di/` | PaymentRepository (payment history) | ApiService |

## NetworkModule

**Path**: `core/di/NetworkModule.kt`

**Purpose**: HTTP client with interceptors, authenticator, certificate pinning.

| Provider | Returns | Dependencies | Notes |
|----------|---------|--------------|-------|
| `provideBaseUrl()` | String | BuildConfig | PROD: api.avoqado.io, SANDBOX: ngrok |
| `provideCertificatePinner()` | CertificatePinner? | None | Returns null (disabled 2025-12-26) |
| `provideOkHttpClient()` | OkHttpClient | 6 interceptors, TokenAuthenticator | Timeouts: 30s, retries enabled |
| `provideRetrofit()` | Retrofit | OkHttpClient, baseUrl | Gson converter |
| `provideApiService()` | ApiService | Retrofit | Shared across app |

### Interceptor Chain

1. SlowNetworkInterceptor (DEBUG only)
2. AuthInterceptor (JWT + version headers)
3. TenantInterceptor (X-Venue-Id)
4. VersionGateInterceptor (HTTP 426 handling)
5. LoggingInterceptor (DEBUG only)

**Authenticator**: TokenAuthenticator (401 refresh, uses Lazy<AuthRepository>)

## DatabaseModule

**Path**: `core/di/DatabaseModule.kt`

**Purpose**: Room database with 19 explicit migrations (version 20).

| Provider | Returns | Notes |
|----------|---------|-------|
| `provideDatabase()` | AvoqadoDatabase | WAL mode, 19 migrations, fallback to destructive |
| `providePendingPaymentDao()` | PendingPaymentDao | Offline payment queue |
| `provideDraftOrderDao()` | DraftOrderDao | Local-first orders |
| `provideDraftOrderItemDao()` | DraftOrderItemDao | Order items with soft delete |
| `provideHistoricalPeriodDao()` | HistoricalPeriodDao | Reports cache |
| `provideProductDao()` | ProductDao | Product cache (500ms → 10ms) |
| `provideProductCategoryDao()` | ProductCategoryDao | Category cache |
| `provideTableDao()` | TableDao | Floor plan tables |
| `provideFloorElementDao()` | FloorElementDao | Floor decorations |
| `provideCachedShiftDao()` | CachedShiftDao | Offline shift status |
| `provideVerificationQueueDao()` | VerificationQueueDao | Step 4 photo upload queue |

**Migration count**: 19 (MIGRATION_2_3 through MIGRATION_19_20)

**Downgrade support**: `fallbackToDestructiveMigrationOnDowngrade()` for INSTALL_VERSION rollback.

## RepositoryModule

**Path**: `core/di/RepositoryModule.kt`

**Purpose**: Bind domain interfaces to data implementations (Clean Architecture).

**Pattern**: Uses `@Binds` (abstract class), NOT `@Provides`.

| Binding | Interface | Implementation | Injected Into |
|---------|-----------|----------------|---------------|
| `bindTerminalConfigRepository()` | TerminalConfigRepository | TerminalConfigRepositoryImpl | SplashViewModel |
| `bindMerchantRepository()` | MerchantRepository | MerchantRepositoryImpl | PaymentViewModel |
| `bindReportsRepository()` | ReportsRepository | ReportsRepositoryImpl | ReportsViewModel |
| `bindTimeEntryRepository()` | TimeEntryRepository | TimeEntryRepositoryImpl | TimeclockViewModel |
| `bindModulesRepository()` | ModulesRepository | ModulesRepositoryImpl | WelcomeViewModel |
| `bindSerializedSaleRepository()` | SerializedSaleRepository | SerializedSaleRepositoryImpl | SerializedSaleViewModel |

## AppModule

**Path**: `core/di/AppModule.kt`

| Provider | Returns | Dependencies | Usage |
|----------|---------|--------------|-------|
| `provideApplicationContext()` | Context | @ApplicationContext | Injected everywhere |
| `provideCellLocationApi()` | CellLocationApi | ApiService | LocationService (Cell ID fallback) |

## ActivationModule

**Path**: `core/di/ActivationModule.kt`

| Provider | Returns | Dependencies | Usage |
|----------|---------|--------------|-------|
| `provideActivationRepository()` | ActivationRepository | ApiService, SecureStorage | ActivateTerminalUseCase |

## PaymentModule

**Path**: `core/di/PaymentModule.kt`

**Purpose**: Blumon OAuth + Backend payment recording + Offline queue.

### Blumon OAuth

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `provideTokenServer()` | TokenServer | None | OAuth access_token (Step 1) |
| `provideCoreServer()` | CoreServer | None | RSA + DUKPT keys (Steps 2-3) |
| `provideBlumonAuthManager()` | BlumonAuthManager | DeviceInfoManager, TokenServer, CoreServer, ApiService | Complete OAuth flow |

**Endpoint selection**: SDK AAR determines sandbox vs production (see build variants).

### Backend Payment Recording

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `providePaymentApiService()` | PaymentApiService | Retrofit | POST /tpv/venues/{venueId}/fast, POST /tpv/venues/{venueId}/orders/{orderId} |
| `provideFastPaymentRecorder()` | FastPaymentRecorder | PaymentApiService | Record fast payments |
| `provideOrderPaymentRecorder()` | OrderPaymentRecorder | PaymentApiService | Record order payments |
| `provideRecordPaymentUseCase()` | RecordPaymentUseCase | FastPaymentRecorder, OrderPaymentRecorder | Strategy pattern (selects recorder) |

### Offline Payment Queue

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `providePaymentQueueRepository()` | PaymentQueueRepository | PendingPaymentDao | Queue failed recordings for retry |

**Injected into**: PaymentViewModel (enqueue), PaymentSyncWorker (retry).

## OrderingModule

**Path**: `core/di/OrderingModule.kt`

**Purpose**: Table, floor, product, order, customer, discount management.

| Provider | Returns | Dependencies | Usage |
|----------|---------|--------------|-------|
| `provideTableApiService()` | TableApiService | Retrofit | Table CRUD |
| `provideTableRepository()` | TableRepository | TableApiService | TableViewModel |
| `provideFloorElementApiService()` | FloorElementApiService | Retrofit | Floor decorations |
| `provideFloorElementRepository()` | FloorElementRepository | FloorElementApiService | FloorPlanViewModel |
| `provideProductRepository()` | ProductRepository | ApiService | MenuViewModel |
| `provideOrderApiService()` | OrderApiService | Retrofit | Order CRUD |
| `provideOrderRepository()` | OrderRepository | OrderApiService, CustomerApiService, DraftOrderDao, DraftOrderItemDao | MenuViewModel |
| `provideCustomerApiService()` | CustomerApiService | Retrofit | Customer search |
| `provideCustomerRepository()` | CustomerRepository | CustomerApiService | CustomerViewModel |
| `provideDiscountApiService()` | DiscountApiService | Retrofit | Discount/coupon ops |
| `provideDiscountRepository()` | DiscountRepository | DiscountApiService | DiscountViewModel |

**Note**: ProductRepository uses shared ApiService (not dedicated ProductApiService).

## PrinterModule

**Path**: `core/di/PrinterModule.kt`

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `providePrinterManager()` | PrinterManager | ApplicationContext | Neptune SDK wrapper (PAX printer) |

**Why Singleton**: Printer initialization expensive (IPrinter.init()). Reuse across app.

## SocketModule

**Path**: `core/di/SocketModule.kt`

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `provideSocketManager()` | SocketManager | SecureStorage | Socket.IO wrapper, event parsing |

**Why Singleton**: Persist connection across screens, prevent multiple connections.

## PaymentsModule

**Path**: `features/payments/di/PaymentsModule.kt`

| Provider | Returns | Dependencies | Purpose |
|----------|---------|--------------|---------|
| `providePaymentRepository()` | PaymentRepository | ApiService | Payment history (NOT recording) |

**Note**: Different from PaymentModule. This is payment HISTORY feature.

## Scoping

| Scope | Usage | Lifespan |
|-------|-------|----------|
| @Singleton | All modules above | Application lifecycle |
| @ViewModelScoped | Not used in TPV | ViewModel lifecycle |
| No annotation | Per-injection (new instance) | Single use |

**Pattern**: TPV uses @Singleton for all DI. No @ViewModelScoped to avoid lifecycle issues.

## Dependency Graph

```
Application
  ├─ NetworkModule (OkHttpClient, Retrofit, ApiService)
  │   ├─ Requires: SecureStorage (from Context via Hilt)
  │   └─ Requires: UpdateCheckManager (Lazy to break cycle)
  │
  ├─ DatabaseModule (AvoqadoDatabase, DAOs)
  │   └─ Requires: ApplicationContext
  │
  ├─ RepositoryModule (@Binds interfaces → impls)
  │   ├─ Requires: ApiService (from NetworkModule)
  │   └─ Requires: DAOs (from DatabaseModule)
  │
  ├─ PaymentModule (Blumon + Payment recording)
  │   ├─ Requires: ApiService, PendingPaymentDao
  │   └─ Requires: DeviceInfoManager (from Hilt)
  │
  ├─ OrderingModule (6 API services + repos)
  │   ├─ Requires: Retrofit (from NetworkModule)
  │   └─ Requires: DraftOrderDao, DraftOrderItemDao (from DatabaseModule)
  │
  └─ ViewModels (@HiltViewModel)
      └─ Inject any of above via constructor
```

## Adding a New Repository

### Option 1: @Provides (object module)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object MyFeatureModule {

    @Provides
    @Singleton
    fun provideMyRepository(
        apiService: ApiService,
        dao: MyDao
    ): MyRepository {
        return MyRepositoryImpl(apiService, dao)
    }
}
```

### Option 2: @Binds (abstract class)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMyRepository(
        impl: MyRepositoryImpl
    ): MyRepository
}
```

**Use @Binds when**: Simple interface → impl binding, no additional logic.

**Use @Provides when**: Need constructor parameters, factory logic, or third-party classes.

## Adding a New Service

1. Define Retrofit interface:
   ```kotlin
   interface MyApiService {
       @GET("tpv/my-endpoint")
       suspend fun getData(): Response<MyData>
   }
   ```

2. Provide in module:
   ```kotlin
   @Provides
   @Singleton
   fun provideMyApiService(retrofit: Retrofit): MyApiService {
       return retrofit.create(MyApiService::class.java)
   }
   ```

3. Inject into repository:
   ```kotlin
   class MyRepositoryImpl @Inject constructor(
       private val apiService: MyApiService
   ) : MyRepository
   ```

## Breaking DI Cycles

### Problem: Circular dependency

```
A → B → C → A
```

### Solution 1: Lazy injection

```kotlin
class A @Inject constructor(
    private val bLazy: dagger.Lazy<B>
) {
    private val b: B by lazy { bLazy.get() }
}
```

**Example**: TokenAuthenticator uses `Lazy<AuthRepository>`.

### Solution 2: Move to different scope

Change one dependency to @ViewModelScoped instead of @Singleton.

### Solution 3: Extract common dependency

```
A → C ← B (instead of A → B → C → A)
```

## Testing with Hilt

Replace production modules with test doubles:

```kotlin
@UninstallModules(NetworkModule::class)
@HiltAndroidTest
class MyTest {

    @Module
    @InstallIn(SingletonComponent::class)
    object TestNetworkModule {
        @Provides
        fun provideApiService(): ApiService {
            return FakeApiService()
        }
    }
}
```

## Common Injection Patterns

| Inject Into | Annotation | Example |
|-------------|------------|---------|
| ViewModel | @HiltViewModel | `@HiltViewModel class MyVM @Inject constructor(repo: Repo)` |
| Activity | @AndroidEntryPoint | `@AndroidEntryPoint class MyActivity : ComponentActivity()` |
| Fragment | @AndroidEntryPoint | `@AndroidEntryPoint class MyFragment : Fragment()` |
| Worker | @HiltWorker | `@HiltWorker class MyWorker @AssistedInject constructor(...)` |
| Custom class | @Inject | `class MyClass @Inject constructor(repo: Repo)` |

**Rule**: Always annotate consuming class with appropriate Hilt annotation.
