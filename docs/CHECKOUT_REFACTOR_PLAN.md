# Checkout Unified Cart Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar un Cart unificado (CheckoutScreen) portado fielmente desde avoqado-android al `WelcomeScreen` como **botón "Cobrar"** que convive con los existentes "Pago Rápido" y "Órdenes" durante la fase de validación. Una vez validado en producción, una fase posterior elimina las pantallas viejas.

**Rollout strategy (additive):** El refactor llega en dos olas:
1. **Olas 1-7 (este plan):** Construir el nuevo Checkout y exponerlo como un 3er botón en el Home, sin tocar las pantallas viejas. Las 2 entradas viejas siguen funcionando idénticas.
2. **Ola 8 (separada, después de validar):** Borrar `FastPaymentEntryScreen` + `OrderingWelcomeScreen` y migrar permisos/flags.

**Architecture:** Nuevo módulo `features/checkout/` con `CheckoutScreen` + 4 tabs (Teclado, Shortcuts, Todos los productos, Configurar) + `CartPanelView`. Reusa la infra existente de TPV (Customers, Products, Discounts, Orders, ZXing scanner, Hilt). Construye nuevo `MosaicRepository` (Room) para Shortcuts. `WelcomeScreen` durante validación: 3 botones — Pago Rápido (legacy), Órdenes (legacy), **Cobrar (nuevo)**. Después de validar: queda solo Cobrar + Mesas (si restaurante) + Pedidos.

**Tech Stack:** Kotlin + Jetpack Compose, Hilt DI, Room (migration nueva), Retrofit, ZXing (ya integrado), MockK + JUnit4, target PAX A910S (360x640dp).

---

## Background

### Estado actual (lo que vamos a cambiar)

| Pantalla | Path | Tamaño | Destino |
|---|---|---|---|
| `FastPaymentEntryScreen` | `core/presentation/screens/FastPaymentEntryScreen.kt` | 192 líneas | **BORRAR** |
| `OrderingWelcomeScreen` | `features/ordering/presentation/OrderingWelcomeScreen.kt` | 323 líneas | **BORRAR** (+ ViewModel) |
| `WelcomeScreen` (botones) | `core/presentation/screens/WelcomeScreen.kt:758-796` | refactor parcial | **MODIFICAR** |
| `MenuScreen` | `features/ordering/presentation/menu/MenuScreen.kt` | 888 líneas | **CONSERVAR** (sigue accesible desde FloorPlan) |
| `OrderListScreen` | `features/ordering/presentation/OrderListScreen.kt` | 408 líneas | **CONSERVAR** (acceso directo desde "Pedidos") |
| `FloorPlanCanvasScreen` | `features/ordering/presentation/FloorPlanCanvasScreen.kt` | 2589 líneas | 🔒 **NO TOCAR** |
| `KioskCartScreen` | `features/kiosk/presentation/screens/KioskCartScreen.kt` | ~150 líneas | 🔒 **NO TOCAR** (precedente, no reusar) |

### Fuente de la portación

Repo `avoqado-android` (rama `main`), módulo `pos/presentation/`:
- `checkout/CheckoutScreen.kt` — contenedora con tabs (líneas 1-700+)
- `checkout/NumericKeypadView.kt` — tab "Teclado" (200 líneas)
- `checkout/ShortcutsGridView.kt`, `ProductGridView.kt`, `MosaicConfigView.kt` — tabs restantes
- `cart/CartPanelView.kt` — panel del carrito (900 líneas)
- `cart/CartViewModel.kt` — state machine (550 líneas)
- `cart/StaffSelectorSheet.kt` — selector de mesero
- `search/SearchOverlayView.kt`, `scanner/BarcodeScannerView.kt`
- `data/ActiveCartState.kt`, `data/SavedCartsRepository.kt`, `data/model/CartItem.kt`, `data/model/SavedCart.kt`

### Decisiones de diseño (cerradas)

1. **Opción B para restaurantes:** Cart unificado + botón "Mesas" separado cuando `venue.type` indica restaurante. No se integra FloorPlan dentro del Cart.
2. **Fidelidad alta:** Los 4 tabs (Teclado, Shortcuts, Todos los productos, Configurar) + búsqueda + refresh + scan QR del screenshot original.
3. **Cliente + Staff + Pay-later integrados en el Cart**, igual que avoqado-android.
4. **`OrderListScreen` se conserva** como entrada independiente (botón "Pedidos") con sus filtros actuales (OPEN, IN_PROGRESS, COMPLETED, UNPAID_TAKEOUT).
5. **Solo layout phone**, no portamos el split 50/50 de tablet (PAX A910S es 360x640dp).
6. **Mantener `PaymentFlowOrigin` intacto:** El nuevo Cart emite `FAST` cuando solo hay `CustomAmount`s, `ORDER` cuando hay productos del catálogo.

### Mapeo de tokens de diseño (avoqado-android → TPV)

| avoqado-android | TPV |
|---|---|
| `AvoqadoTheme.spacing.xs/sm/md/lg/xl` | `MaterialTheme.spacing` o constantes locales (`4.dp/8.dp/12.dp/16.dp/24.dp`) |
| `AvoqadoTheme.cornerRadius.md` | `RoundedCornerShape(12.dp)` |
| `AvoqadoAdaptiveSizeClass.Compact/Medium/Expanded` | `ResponsiveSizes.small/medium/large` via `LocalResponsiveSizes.current` |
| `PrimaryButton(...)` | `AvoqadoButton(...)` |
| `AvoqadoDialog(...)` | `AvoqadoDialog(...)` (ya existe en TPV) |
| `AvoqadoPillTextField(...)` | **Construir equivalente** o usar `OutlinedTextField` con `RoundedCornerShape(50)` |
| `AvoqadoSuccessToast(...)` | `Snackbar` con `SnackbarHost` |
| `MaterialTheme.colorScheme.*` | `MaterialTheme.avoqadoColors.*` (semantic) o `colorScheme` (Material) |

### Infraestructura TPV reusable (auditada)

| Necesidad del Cart | TPV tiene | Path |
|---|---|---|
| Customer search/create | ✅ | `features/ordering/domain/CustomerRepository.kt` |
| Product search + modifiers | ✅ | `features/ordering/domain/ProductRepository.kt` |
| Discounts + coupons | ✅ | `features/ordering/domain/DiscountRepository.kt` |
| Barcode/QR scanner (ZXing) | ✅ | `features/verification/presentation/components/BarcodeScannerScreen.kt` |
| Order creation + pay-later | ✅ | `features/ordering/data/repository/OrderRepositoryImpl.kt` |
| Hilt + Room | ✅ | `core/di/`, `core/data/local/` |
| Shortcuts/Mosaico | ❌ | **Construir nuevo** (Fase 3) |

---

## File Structure

### Archivos a crear

```
app/src/main/java/com/jaac/avoqado_tpv/features/checkout/
├── domain/
│   ├── model/
│   │   ├── CartItem.kt              (Fase 1)
│   │   ├── CartItemType.kt          (Fase 1; sealed: ProductItem | CustomAmount)
│   │   ├── CartState.kt             (Fase 1)
│   │   ├── SavedCart.kt             (Fase 1)
│   │   └── MosaicShortcut.kt        (Fase 3)
│   └── repository/
│       ├── SavedCartsRepository.kt  (Fase 1, interface)
│       └── MosaicRepository.kt      (Fase 3, interface)
├── data/
│   ├── ActiveCartState.kt           (Fase 1; singleton)
│   ├── repository/
│   │   ├── SavedCartsRepositoryImpl.kt   (Fase 1, SharedPreferences)
│   │   └── MosaicRepositoryImpl.kt       (Fase 3, Room)
│   └── local/
│       ├── MosaicShortcutEntity.kt       (Fase 3)
│       └── MosaicShortcutDao.kt          (Fase 3)
├── presentation/
│   ├── CheckoutScreen.kt            (Fase 2 esqueleto, Fase 3-4 contenido)
│   ├── CheckoutViewModel.kt         (Fase 1; antes CartViewModel)
│   ├── components/
│   │   ├── SearchBarView.kt         (Fase 3; top bar)
│   │   ├── TabSelectorView.kt       (Fase 2)
│   │   ├── NumericKeypadView.kt     (Fase 2)
│   │   ├── ShortcutsGridView.kt     (Fase 3)
│   │   ├── ProductGridView.kt       (Fase 3)
│   │   ├── MosaicConfigView.kt      (Fase 3)
│   │   ├── SearchOverlayView.kt     (Fase 3)
│   │   └── cart/
│   │       ├── CartPanelView.kt     (Fase 2)
│   │       ├── CustomerHeader.kt    (Fase 4)
│   │       └── StaffSelectorSheet.kt (Fase 4)
│   └── InputTab.kt                  (Fase 2; enum)
└── di/
    └── CheckoutModule.kt            (Fase 1; Hilt)
```

### Archivos a modificar

| Path | Cambio | Fase |
|---|---|---|
| `core/presentation/screens/WelcomeScreen.kt:758-796` | Reemplazar bloques de Pago Rápido + Órdenes; agregar Cobrar/Mesas/Pedidos | 6 |
| `core/presentation/navigation/NavRoute.kt:50,77` | Borrar `FastPaymentEntry`, `OrderingWelcome`; agregar `Checkout` | 6 |
| `core/presentation/navigation/AppNavigation.kt` | Eliminar composables de rutas borradas; registrar `Checkout` | 6 |
| `features/payment/domain/model/TpvSettings.kt` | Reemplazar flags `showQuickPayment`/`showOrderManagement` por `showCheckout`/`showOrders` (con compat default) | 6 |
| `core/data/network/dto/TpvSettingsDto.kt` | Mismo cambio + retrocompatibilidad con campos viejos | 6 |
| `core/data/local/AvoqadoDatabase.kt` | Agregar `MosaicShortcutEntity` + `MIGRATION_X_Y` | 3 |
| `core/di/DatabaseModule.kt` | Registrar nueva migración + DAO | 3 |

### Archivos a borrar

| Path | Razón | Fase |
|---|---|---|
| `core/presentation/screens/FastPaymentEntryScreen.kt` | Reemplazado por tab Teclado del Checkout | 6 |
| `features/ordering/presentation/OrderingWelcomeScreen.kt` | Reemplazado por entrada directa a Pedidos/Mesas | 6 |
| `features/ordering/presentation/OrderingWelcomeViewModel.kt` | Junto con el screen | 6 |
| `app/src/test/.../FastPaymentRecorderTest.kt` (si existe) | Junto con el screen | 7 |

---

# Phase 1 — Foundation: domain models + CheckoutViewModel

**Objetivo:** Tener modelos, repositorio de saved carts, singleton activo y `CheckoutViewModel` compilando y con tests verdes, todavía sin UI.

**Files in this phase:**
- Create: `features/checkout/domain/model/CartItem.kt`, `CartItemType.kt`, `CartState.kt`, `SavedCart.kt`
- Create: `features/checkout/data/ActiveCartState.kt`
- Create: `features/checkout/domain/repository/SavedCartsRepository.kt`
- Create: `features/checkout/data/repository/SavedCartsRepositoryImpl.kt`
- Create: `features/checkout/presentation/CheckoutViewModel.kt`
- Create: `features/checkout/di/CheckoutModule.kt`
- Test: `app/src/test/.../checkout/CartStateTest.kt`, `CheckoutViewModelTest.kt`

### Task 1.1 — Domain models (CartItem, CartState, SavedCart)

- [ ] **Step 1: Portar `CartItem` y `CartItemType` desde avoqado-android**

  Referencia: `avoqado-android/.../pos/data/model/CartItem.kt` (líneas 10-53). Adaptar a paquete TPV y reemplazar referencias a `com.avoqado.pos.pos.data.model.Product` por `com.jaac.avoqado_tpv.features.ordering.domain.Product`.

  Estructura esperada:
  ```kotlin
  data class CartItem(
      val id: String,                        // UUID local
      val type: CartItemType,
      val name: String,
      val unitPriceCents: Int,
      val quantity: Int = 1,
      val priceAdjustment: Int = 0,
      val itemNote: String? = null,
      val isCortesia: Boolean = false,
      val selectedModifiers: List<SelectedModifier> = emptyList(),
  ) {
      val totalPrice: Int
          get() = if (isCortesia) 0 else (unitPriceCents + priceAdjustment) * quantity
  }

  sealed class CartItemType {
      data class ProductItem(val productId: String) : CartItemType()
      data object CustomAmount : CartItemType()
  }
  ```

- [ ] **Step 2: Crear `CartState`**

  Referencia: `avoqado-android/.../cart/CartViewModel.kt:38-73`. Incluir computed properties: `itemCount`, `subtotalCents`, `taxableSubtotalCents`, `discountCents`, `taxCents`, `totalCents`, `isEmpty`. Campos: `items`, `orderDiscount`, `orderTaxPercent`, `selectedStaffId`, `selectedStaffName`, `orderNote`.

- [ ] **Step 3: Crear `SavedCart` con `@Serializable` (kotlinx-serialization)**

  Referencia: `avoqado-android/.../pos/data/model/SavedCart.kt`. Verificar que TPV ya tiene kotlinx-serialization en `build.gradle.kts`; si no, agregarla.

- [ ] **Step 4: Test `CartStateTest` — coverage por property**

  Portar fielmente `avoqado-android/app/src/test/.../cart/CartStateTest.kt`. Probar cálculo de subtotal con items mixtos (ProductItem + CustomAmount), descuento `PERCENTAGE` vs `FIXED`, impuestos aplicados, cortesía → totalPrice=0.

- [ ] **Step 5: Correr tests y commit**

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 23)
  ./gradlew testSandboxDebugUnitTest --tests "*CartStateTest*"
  ```
  Esperado: 0 failures. Commit: `feat(checkout): add cart domain models`.

### Task 1.2 — ActiveCartState + SavedCartsRepository

- [ ] **Step 1: Crear `ActiveCartState` singleton**

  ```kotlin
  @Singleton
  class ActiveCartState @Inject constructor() {
      private val _itemCount = MutableStateFlow(0)
      val itemCount: StateFlow<Int> = _itemCount.asStateFlow()
      private val _totalDisplay = MutableStateFlow("$0.00")
      val totalDisplay: StateFlow<String> = _totalDisplay.asStateFlow()
      fun update(itemCount: Int, totalCents: Int) { ... }
      fun clear() { ... }
  }
  ```

  Referencia: `avoqado-android/.../pos/data/ActiveCartState.kt`.

- [ ] **Step 2: Definir interface `SavedCartsRepository`**

  Métodos: `getAll(): List<SavedCart>`, `save(cart: SavedCart)`, `delete(id: String)`, `clear()`.

- [ ] **Step 3: Implementación con SharedPreferences**

  Persistir como JSON bajo clave `"saved_carts"` (igual a Android). Usar `kotlinx.serialization`. Path: `features/checkout/data/repository/SavedCartsRepositoryImpl.kt`. Inyectar `SharedPreferences` vía Hilt (TPV ya lo tiene en `EncryptedSharedPreferences`; **NO** persistir aquí porque no son datos sensibles — usar SharedPreferences regular).

- [ ] **Step 4: Tests — round-trip serialization**

  ```kotlin
  @Test fun `save then getAll returns serialized cart`() { ... }
  @Test fun `delete removes cart by id`() { ... }
  ```

- [ ] **Step 5: Commit**

  `feat(checkout): add ActiveCartState + SavedCartsRepository`.

### Task 1.3 — CheckoutViewModel (sin UI, lógica pura)

- [ ] **Step 1: Crear `CheckoutViewModel` con dependencias**

  ```kotlin
  @HiltViewModel
  class CheckoutViewModel @Inject constructor(
      private val productRepository: ProductRepository,
      private val customerRepository: CustomerRepository,
      private val discountRepository: DiscountRepository,
      private val orderRepository: OrderRepository,
      private val savedCartsRepository: SavedCartsRepository,
      private val activeCartState: ActiveCartState,
      private val authRepository: AuthRepository,
      private val secureStorage: SecureStorage,
      private val shiftRepository: ShiftRepository,
  ) : ViewModel() { ... }
  ```

  Notar diferencia con Android: TPV tiene `ShiftRepository` (turno) que es relevante para validar `canOperate`. Inyectarlo aquí.

- [ ] **Step 2: Métodos públicos (firma exacta)**

  Portar desde `avoqado-android/.../cart/CartViewModel.kt`:
  - `addCustomAmount(name: String, amountCents: Int)`
  - `addProduct(product: Product, quantity: Int = 1, modifiers: List<SelectedModifier> = emptyList())`
  - `removeItem(itemId: String)`
  - `incrementQuantity(itemId: String)`, `decrementQuantity(itemId: String)`
  - `applyOrderDiscount(discount: OrderDiscount?)`
  - `applyOrderTaxPercent(percent: Int?)`
  - `setItemNote(itemId: String, note: String)`
  - `setOrderNote(note: String)`
  - `clearCart()`
  - `saveCurrentCart(): Boolean`
  - `searchProducts(query: String)` → expone `searchResults: StateFlow<List<Product>>`
  - `refreshProducts()` → `productRepository.syncProducts(venueId)`
  - `selectStaff(staffId: String, name: String)`
  - `createPayLaterOrder(customerId: String): Result<Order>` — adapta a `OrderRepository.createOrder(paymentStatus = PENDING)`

- [ ] **Step 3: Bridge `ActiveCartState`**

  Cada `cartState` emitido debe llamar `activeCartState.update(itemCount, totalCents)`. Si `clearCart()` → `activeCartState.clear()`.

- [ ] **Step 4: Test de ViewModel con MockK + UnconfinedTestDispatcher**

  ⚠️ Lectura obligada antes de escribir tests: memoria `MEMORY.md` → "ViewModel Testing Patterns (2026-02-06)" — debe usar `UnconfinedTestDispatcher` y llamar `viewModelScope.cancel()` en cada test. ⚠️ NO usar `SystemClock` — usar `System.currentTimeMillis()` si necesitas tiempo.

  Cubrir:
  - `addCustomAmount` → state contiene 1 item de tipo `CustomAmount`
  - `addProduct` con `quantity=2` → cartState.itemCount = 2
  - `clearCart` → `activeCartState.itemCount` = 0
  - `applyOrderTaxPercent(16)` → `cartState.taxCents` correcto
  - `createPayLaterOrder` con customer mock → llama `orderRepository.createOrder` con `paymentStatus = PENDING`

- [ ] **Step 5: Hilt module `CheckoutModule.kt`**

  ```kotlin
  @Module
  @InstallIn(SingletonComponent::class)
  abstract class CheckoutModule {
      @Binds @Singleton
      abstract fun bindSavedCartsRepository(
          impl: SavedCartsRepositoryImpl
      ): SavedCartsRepository
  }
  ```

  `ActiveCartState` y `CheckoutViewModel` se resuelven solos con `@Singleton` / `@HiltViewModel`.

- [ ] **Step 6: Compile + tests + commit**

  ```bash
  ./gradlew compileSandboxDebugKotlin
  ./gradlew testSandboxDebugUnitTest --tests "*CheckoutViewModelTest*"
  ```
  Commit: `feat(checkout): add CheckoutViewModel wired to existing repos`.

---

# Phase 2 — UI core: NumericKeypadView + CartPanelView + skeleton

**Objetivo:** Pantalla `CheckoutScreen` renderizable @360x640dp con tab Teclado funcional (single-tab, sin tabs aún). Cart panel abajo con items y "Cobrar". Sin integración con PaymentScreen todavía (botón Cobrar solo loggea).

**Files in this phase:**
- Create: `features/checkout/presentation/CheckoutScreen.kt` (skeleton)
- Create: `features/checkout/presentation/InputTab.kt` (enum)
- Create: `features/checkout/presentation/components/NumericKeypadView.kt`
- Create: `features/checkout/presentation/components/TabSelectorView.kt`
- Create: `features/checkout/presentation/components/cart/CartPanelView.kt`

### Task 2.1 — InputTab enum + TabSelectorView

- [ ] **Step 1: Crear enum**

  ```kotlin
  enum class InputTab(val label: String) {
      KEYPAD("Teclado"),
      SHORTCUTS("Shortcuts"),
      PRODUCTS("Todos los productos"),
      MOSAIC("Configurar"),
  }
  ```

- [ ] **Step 2: `TabSelectorView` composable**

  Referencia: el screenshot muestra tabs estilo Material con underline en el seleccionado. Usar `ScrollableTabRow` (porque "Todos los productos" + "Configurar" no caben en 360dp horizontalmente).

  ```kotlin
  @Composable
  fun TabSelectorView(
      selectedTab: InputTab,
      onTabSelected: (InputTab) -> Unit,
  ) {
      ScrollableTabRow(
          selectedTabIndex = selectedTab.ordinal,
          edgePadding = 16.dp,
          indicator = { tabPositions ->
              TabRowDefaults.SecondaryIndicator(
                  Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal])
              )
          },
      ) {
          InputTab.entries.forEach { tab ->
              Tab(
                  selected = selectedTab == tab,
                  onClick = { onTabSelected(tab) },
                  text = { Text(tab.label, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal) },
              )
          }
      }
  }
  ```

- [ ] **Step 3: Preview**

  ```kotlin
  @Preview(widthDp = 360, heightDp = 640)
  @Composable
  private fun TabSelectorPreview() {
      AvoqadoTheme { TabSelectorView(InputTab.KEYPAD, {}) }
  }
  ```

- [ ] **Step 4: Commit**

  `feat(checkout): add InputTab + TabSelectorView`.

### Task 2.2 — NumericKeypadView (port directo)

- [ ] **Step 1: Copiar `NumericKeypadView.kt` desde avoqado-android**

  Referencia: `avoqado-android/.../checkout/NumericKeypadView.kt` (200 líneas completas). Mantener el layout 4x3 (1-9, C, 0, +), display de monto grande, "+ Nota", chips $50/$100/$200/$500.

- [ ] **Step 2: Adaptar tokens de theme (ver tabla de mapeo)**

  - `AvoqadoTheme.spacing.xl` → `24.dp`
  - `AvoqadoTheme.spacing.lg` → `16.dp`
  - `AvoqadoTheme.spacing.md` → `12.dp`
  - `AvoqadoTheme.spacing.sm` → `8.dp`
  - `AvoqadoTheme.spacing.xs` → `4.dp`
  - `AvoqadoTheme.cornerRadius.md` → `12.dp`

  El cálculo de `isCompact` por `screenHeight < 700` queda igual — PAX A910S tiene `screenHeight=640dp`, por lo tanto **`isCompact=true` siempre** en este device. Mantener la condicional para emuladores y previews.

- [ ] **Step 3: Mantener firma original**

  ```kotlin
  @Composable
  fun NumericKeypadView(
      amountCents: Int,
      onAmountChange: (Int) -> Unit,
      onAddToCart: () -> Unit,
      onNoteTap: () -> Unit = {},
      noteText: String = "",
      useCompactSizing: Boolean = true,  // default true en TPV
  )
  ```

- [ ] **Step 4: Preview con banner de status**

  Memoria `MEMORY.md` "PAX A910S": cada screen debe tener `@Preview(widthDp=360, heightDp=640)` con venue status banner visible. Para esta vista, usar también `device = PAX_A910S` (constante en `core/presentation/preview/`).

  ```kotlin
  private const val PAX_A910S = "spec:width=720px,height=1280px,dpi=320"

  @Preview(name = "PAX A910S - Empty", device = PAX_A910S, showSystemUi = true)
  @Composable
  private fun KeypadEmptyPreview() {
      AvoqadoTheme {
          NumericKeypadView(amountCents = 0, onAmountChange = {}, onAddToCart = {})
      }
  }

  @Preview(name = "PAX A910S - With Amount", device = PAX_A910S, showSystemUi = true)
  @Composable
  private fun KeypadWithAmountPreview() {
      AvoqadoTheme {
          NumericKeypadView(amountCents = 15000, onAmountChange = {}, onAddToCart = {})
      }
  }
  ```

- [ ] **Step 5: Commit**

  `feat(checkout): port NumericKeypadView from avoqado-android`.

### Task 2.3 — CartPanelView (port phone layout)

- [ ] **Step 1: Copiar `CartPanelView.kt` desde avoqado-android**

  Referencia: `avoqado-android/.../cart/CartPanelView.kt` (900 líneas). Es Compose puro. **Omitir** por ahora: `CustomerHeader` (queda en Fase 4), `StaffSelectorSheet` (Fase 4), `showTaxDialog` (Fase 4 si aplica).

  Conservar: lista de items con stepper +/-, `SwipeToDismissBox` para borrar, subtotal/descuento/impuestos/total, botón "Cobrar" abajo, empty state con ícono.

- [ ] **Step 2: Adaptar imports y tokens**

  - `com.avoqado.pos.designsystem.theme.AvoqadoTheme` → equivalente TPV
  - `com.avoqado.pos.designsystem.components.PrimaryButton` → `AvoqadoButton`
  - `com.avoqado.pos.designsystem.components.AvoqadoDialog` → ya existe en TPV con mismo nombre
  - `AvoqadoAdaptiveSizeClass.Compact` → `LocalResponsiveSizes.current.isSmall`

- [ ] **Step 3: Firma adaptada**

  ```kotlin
  @Composable
  fun CartPanelView(
      cartState: CartState,
      onItemTap: (CartItem) -> Unit = {},
      onCharge: () -> Unit,
      onClearCart: () -> Unit,
      onSaveCart: () -> Unit = {},
      onAddCustomAmount: () -> Unit = {},
      onRemoveItem: (String) -> Unit = {},
      onApplyTaxPercent: (Int?) -> Unit = {},
      // Stubs para Fase 4 — no se renderizan aún:
      customerName: String? = null,
      onCustomerTap: () -> Unit = {},
      staffName: String = "",
      onStaffTap: () -> Unit = {},
  )
  ```

- [ ] **Step 4: Previews — Empty / 1 item / múltiples items con tax+discount**

- [ ] **Step 5: Commit**

  `feat(checkout): port CartPanelView (phone layout)`.

### Task 2.4 — CheckoutScreen esqueleto (single-tab Keypad)

- [ ] **Step 1: Layout phone**

  Stack vertical: top bar placeholder (vacío en esta fase) → TabSelectorView → contenido del tab → CartPanelView abajo. Como PAX A910S es phone, NO portar la lógica de `isTablet` con split 50/50. El cart panel toma la parte inferior y muestra collapsible si está vacío.

  ```kotlin
  @Composable
  fun CheckoutScreen(
      onNavigateBack: () -> Unit,
      onNavigateToPayment: (PaymentPayload) -> Unit,  // wired en Fase 5
      viewModel: CheckoutViewModel = hiltViewModel(),
  ) {
      val cartState by viewModel.cartState.collectAsStateWithLifecycle()
      var selectedTab by remember { mutableStateOf(InputTab.KEYPAD) }
      var amountCents by remember { mutableIntStateOf(0) }
      var currentNote by remember { mutableStateOf("") }

      Column(modifier = Modifier.fillMaxSize()) {
          // TopBar placeholder (Fase 3 reemplaza con SearchBarView)
          Spacer(modifier = Modifier.height(56.dp))

          TabSelectorView(selectedTab) { selectedTab = it }

          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
              when (selectedTab) {
                  InputTab.KEYPAD -> NumericKeypadView(
                      amountCents = amountCents,
                      onAmountChange = { amountCents = it },
                      onAddToCart = {
                          if (amountCents > 0) {
                              viewModel.addCustomAmount(
                                  name = currentNote.ifBlank { "Importe personalizado" },
                                  amountCents = amountCents,
                              )
                              amountCents = 0
                              currentNote = ""
                          }
                      },
                      onNoteTap = { /* Fase 3: NoteDialog */ },
                      noteText = currentNote,
                  )
                  else -> Placeholder("Tab \"${selectedTab.label}\" — Fase 3")
              }
          }

          CartPanelView(
              cartState = cartState,
              onCharge = { /* Fase 5 wire */ Log.d("Checkout", "onCharge: $cartState") },
              onClearCart = { viewModel.clearCart() },
              onRemoveItem = { viewModel.removeItem(it) },
              onApplyTaxPercent = { viewModel.applyOrderTaxPercent(it) },
              modifier = Modifier.heightIn(min = 200.dp, max = 320.dp),
          )
      }
  }
  ```

- [ ] **Step 2: Registrar ruta provisoria (no en producción aún)**

  En `AppNavigation.kt` añadir composable temporal `CHECKOUT_PREVIEW` accesible solo en `BuildConfig.DEBUG` para QA visual. **NO modificar todavía** las rutas que toca Fase 6.

- [ ] **Step 3: Build + install + ADB**

  ```bash
  ./gradlew installSandboxDebug
  adb logcat -c && adb logcat -s Checkout
  ```

  Acceder por menú debug → Checkout Preview. Verificar: agregar $100 con `+`, ver el cart panel con item, eliminar con swipe, "Cobrar" produce log con el state.

- [ ] **Step 4: Commit**

  `feat(checkout): add CheckoutScreen skeleton with keypad tab functional`.

---

# Phase 3 — Tabs restantes (Shortcuts, Productos, Configurar) + TopBar

**Objetivo:** Los 4 tabs funcionales + barra superior (búsqueda + refresh + QR) + sistema de mosaico/shortcuts persistente.

**Files in this phase:**
- Create: `features/checkout/presentation/components/SearchBarView.kt`
- Create: `features/checkout/presentation/components/ProductGridView.kt`
- Create: `features/checkout/presentation/components/SearchOverlayView.kt`
- Create: `features/checkout/presentation/components/ShortcutsGridView.kt`
- Create: `features/checkout/presentation/components/MosaicConfigView.kt`
- Create: `features/checkout/data/local/MosaicShortcutEntity.kt`
- Create: `features/checkout/data/local/MosaicShortcutDao.kt`
- Create: `features/checkout/domain/repository/MosaicRepository.kt`
- Create: `features/checkout/data/repository/MosaicRepositoryImpl.kt`
- Create: `features/checkout/domain/model/MosaicShortcut.kt`
- Modify: `core/data/local/AvoqadoDatabase.kt` (version bump + migration)
- Modify: `core/di/DatabaseModule.kt` (registrar migration + DAO)

### Task 3.1 — SearchBarView (top bar)

- [ ] **Step 1: Componente con search + refresh + QR scan**

  Layout horizontal: campo de búsqueda con pill rounded (taparla de placeholder "Buscar"), botón refresh (ícono ↻), botón QR scan (ícono QR). Referencia: el screenshot del usuario muestra exactamente esto.

  ```kotlin
  @Composable
  fun SearchBarView(
      isLoading: Boolean,
      onSearchTap: () -> Unit,
      onRefresh: () -> Unit,
      onBarcodeScan: () -> Unit,
  ) { ... }
  ```

  El campo de búsqueda en realidad no edita inline — al tocar abre `SearchOverlayView`. Es un botón que parece input.

- [ ] **Step 2: Preview + commit**

  `feat(checkout): add SearchBarView top bar`.

### Task 3.2 — ProductGridView (tab "Todos los productos")

- [ ] **Step 1: Grid de productos**

  Referencia: `avoqado-android/.../checkout/ProductGridView.kt` (si existe — si no, mirar `MenuScreen.kt` de TPV como inspiración). Usar `LazyVerticalGrid` con `GridCells.Adaptive(minSize = 110.dp)` para 3 columnas en 360dp.

  Cada celda: imagen del producto (Coil), nombre, precio. Click → ver Step 2.

- [ ] **Step 2: Click → si tiene modifiers, abrir `ProductDetailPanel` (existe en TPV); si no, agregar directo con `viewModel.addProduct(product)`**

  Reusar el flow de TPV's MenuScreen para modifiers — investigar el componente exacto durante implementación. Si no existe `ProductDetailPanel` separado, portar `ProductDetailPanel.kt` desde avoqado-android.

- [ ] **Step 3: Estado: usar `viewModel.products: StateFlow<List<Product>>`**

  Añadir al `CheckoutViewModel`:
  ```kotlin
  val products: StateFlow<List<Product>> = productRepository
      .observeProducts(venueId)
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
  ```

- [ ] **Step 4: Categorías (chips horizontales arriba del grid)**

  Si `ProductRepository` expone `getCategories()`, agregar fila scrollable de chips para filtrar. Si no, omitir en esta fase.

- [ ] **Step 5: Preview con productos mock + commit**

  `feat(checkout): add ProductGridView tab`.

### Task 3.3 — SearchOverlayView

- [ ] **Step 1: Overlay full-screen sobre el Checkout cuando se toca el SearchBar**

  Referencia: `avoqado-android/.../search/SearchOverlayView.kt`. Lista de resultados que filtra a medida que escribe. Cada resultado: imagen pequeña + nombre + precio. Click → `onProductTap(product)` y cierra overlay.

  Si el producto buscado no existe y `roleManager.canCreateProducts`, mostrar "Crear nuevo producto" abajo — opcional para Fase 3.

- [ ] **Step 2: Wire en CheckoutScreen**

  ```kotlin
  var showSearch by remember { mutableStateOf(false) }
  if (showSearch) {
      SearchOverlayView(
          viewModel = viewModel,
          onProductTap = { product ->
              viewModel.addProduct(product)
              showSearch = false
          },
          onDismiss = { showSearch = false },
      )
  }
  ```

- [ ] **Step 3: Commit**

  `feat(checkout): add SearchOverlayView`.

### Task 3.4 — Integrar BarcodeScanner

- [ ] **Step 1: Reusar `BarcodeScannerScreen` existente de TPV**

  Path: `features/verification/presentation/components/BarcodeScannerScreen.kt`. Refactorizar para extraer un componente genérico si la API actual es muy específica de verification.

- [ ] **Step 2: Wire**

  ```kotlin
  var showBarcodeScanner by remember { mutableStateOf(false) }
  if (showBarcodeScanner) {
      BarcodeScannerScreen(
          onBarcodeScanned = { code ->
              showBarcodeScanner = false
              viewModel.findProductByBarcode(code) { product ->
                  if (product != null) viewModel.addProduct(product)
                  else { /* mostrar dialog "producto desconocido" */ }
              }
          },
          onDismiss = { showBarcodeScanner = false },
      )
  }
  ```

  Agregar a `CheckoutViewModel`: `fun findProductByBarcode(code: String, onResult: (Product?) -> Unit)` — usa `ProductRepository.findByBarcode` o filtra en memoria.

- [ ] **Step 3: Commit**

  `feat(checkout): integrate barcode scanner`.

### Task 3.5 — Room migration: MosaicShortcutEntity

⚠️ **CRITICAL:** Esta migración va a producción donde users no pueden desinstalar — sin migration = crash 100%. Ver memoria `MEMORY.md` y `.claude/rules/critical-warnings.md` → "Room Migration Checklist".

- [ ] **Step 1: Definir entity**

  ```kotlin
  @Entity(
      tableName = "mosaic_shortcut",
      indices = [Index(value = ["venue_id"]), Index(value = ["venue_id", "position"], unique = true)]
  )
  data class MosaicShortcutEntity(
      @PrimaryKey val id: String,                       // UUID
      @ColumnInfo(name = "venue_id") val venueId: String,
      @ColumnInfo(name = "product_id") val productId: String?,  // null si es discount/coupon
      @ColumnInfo(name = "type") val type: String,      // "PRODUCT" | "DISCOUNT" | "COURTESY"
      @ColumnInfo(name = "position") val position: Int,
      @ColumnInfo(name = "label") val label: String,
      @ColumnInfo(name = "color_hex") val colorHex: String? = null,
      @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
  )
  ```

- [ ] **Step 2: DAO**

  ```kotlin
  @Dao
  interface MosaicShortcutDao {
      @Query("SELECT * FROM mosaic_shortcut WHERE venue_id = :venueId ORDER BY position ASC")
      fun observeForVenue(venueId: String): Flow<List<MosaicShortcutEntity>>

      @Insert(onConflict = OnConflictStrategy.REPLACE)
      suspend fun upsertAll(items: List<MosaicShortcutEntity>)

      @Query("DELETE FROM mosaic_shortcut WHERE id = :id")
      suspend fun delete(id: String)

      @Query("DELETE FROM mosaic_shortcut WHERE venue_id = :venueId")
      suspend fun clearVenue(venueId: String)
  }
  ```

- [ ] **Step 3: Migration en `AvoqadoDatabase.kt`**

  Subir la version actual a `version + 1`. Agregar `MosaicShortcutEntity::class` al array de entities y abstract fun `mosaicShortcutDao()`.

  ```kotlin
  val MIGRATION_X_Y = object : Migration(X, Y) {
      override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("""
              CREATE TABLE IF NOT EXISTS `mosaic_shortcut` (
                  `id` TEXT NOT NULL PRIMARY KEY,
                  `venue_id` TEXT NOT NULL,
                  `product_id` TEXT,
                  `type` TEXT NOT NULL,
                  `position` INTEGER NOT NULL,
                  `label` TEXT NOT NULL,
                  `color_hex` TEXT,
                  `updated_at` INTEGER NOT NULL
              )
          """.trimIndent())
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_mosaic_shortcut_venue_id` ON `mosaic_shortcut` (`venue_id`)")
          db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_mosaic_shortcut_venue_id_position` ON `mosaic_shortcut` (`venue_id`, `position`)")
      }
  }
  ```

- [ ] **Step 4: Registrar en `DatabaseModule.kt`**

  ```kotlin
  .addMigrations(..., MIGRATION_X_Y)
  ```

  Y exponer el DAO con `@Provides fun provideMosaicShortcutDao(db: AvoqadoDatabase) = db.mosaicShortcutDao()`.

- [ ] **Step 5: Test de migración manual**

  Instalar versión anterior → generar data en cualquier tabla → instalar nueva versión → verificar `adb logcat -s "RoomDatabase:*" | grep -i migration`. No debe crashear.

- [ ] **Step 6: Commit**

  `feat(checkout): add MosaicShortcutEntity + room migration`.

### Task 3.6 — MosaicRepository

- [ ] **Step 1: Interface + impl**

  ```kotlin
  interface MosaicRepository {
      fun observe(venueId: String): Flow<List<MosaicShortcut>>
      suspend fun set(venueId: String, shortcuts: List<MosaicShortcut>)
      suspend fun remove(id: String)
      suspend fun clear(venueId: String)
  }
  ```

  Impl: convierte entre `MosaicShortcut` (domain) y `MosaicShortcutEntity` (data).

- [ ] **Step 2: Test con in-memory Room**

- [ ] **Step 3: Commit**

  `feat(checkout): add MosaicRepository`.

### Task 3.7 — ShortcutsGridView

- [ ] **Step 1: Layout grid**

  Referencia: `avoqado-android/.../checkout/ShortcutsGridView.kt`. Grid de celdas configurables (botones grandes con label + color). Click → agrega producto/descuento/cortesía al carrito.

  En 360dp: 3 columnas, celdas cuadradas ~110x110dp.

- [ ] **Step 2: Estado vacío**

  Si `mosaicRepository.observe(venueId)` emite lista vacía, mostrar mensaje "Configura tus shortcuts en la pestaña Configurar →" con ícono. Botón "Configurar shortcuts" que cambia `selectedTab` a `MOSAIC`.

- [ ] **Step 3: Wire + commit**

  `feat(checkout): add ShortcutsGridView tab`.

### Task 3.8 — MosaicConfigView

- [ ] **Step 1: UI de configuración**

  Referencia: `avoqado-android/.../checkout/MosaicConfigView.kt`. Grid editable de slots; tap en slot vacío → modal para elegir producto/descuento/cortesía → asigna a esa posición.

  Si el modal de selección no existe en TPV, construirlo con `ModalBottomSheet` listando productos (reusar `ProductGridView` simplificado).

- [ ] **Step 2: Persistencia**

  Cada cambio → `mosaicRepository.set(venueId, currentList)`.

- [ ] **Step 3: Commit**

  `feat(checkout): add MosaicConfigView tab`.

### Task 3.9 — Wire CheckoutScreen con los 4 tabs

- [ ] **Step 1: Reemplazar `Placeholder` en `CheckoutScreen` por los componentes reales**

  ```kotlin
  when (selectedTab) {
      InputTab.KEYPAD -> NumericKeypadView(...)
      InputTab.SHORTCUTS -> ShortcutsGridView(viewModel = viewModel)
      InputTab.PRODUCTS -> ProductGridView(viewModel = viewModel)
      InputTab.MOSAIC -> MosaicConfigView(viewModel = viewModel)
  }
  ```

- [ ] **Step 2: Top bar (SearchBarView) reemplaza el `Spacer`**

  ```kotlin
  SearchBarView(
      isLoading = isLoading,
      onSearchTap = { showSearch = true },
      onRefresh = { viewModel.refreshProducts() },
      onBarcodeScan = { showBarcodeScanner = true },
  )
  ```

- [ ] **Step 3: QA visual con ADB**

  ```bash
  adb logcat -c && adb logcat -s Checkout,CheckoutViewModel,MosaicRepository
  ```

  Cada tab debe abrir sin crash. Mosaico debe persistir tras restart.

- [ ] **Step 4: Commit**

  `feat(checkout): wire all 4 tabs in CheckoutScreen`.

---

# Phase 4 — Sheets: Customer, Staff, Pay Later, Note

**Objetivo:** Cliente (CustomerHeader + search modal), Staff selector, Pay Later confirmation, NoteDialog.

**Files in this phase:**
- Create: `features/checkout/presentation/components/cart/CustomerHeader.kt`
- Create: `features/checkout/presentation/components/cart/StaffSelectorSheet.kt`
- Create: `features/checkout/presentation/components/NoteDialog.kt`
- Create: `features/checkout/presentation/components/PayLaterConfirmDialog.kt`

### Task 4.1 — CustomerHeader

- [ ] **Step 1: Componente arriba del CartPanelView**

  Referencia: `avoqado-android/.../cart/CartPanelView.kt:99-109` (CustomerHeader). Pill horizontal con:
  - "Agregar cliente" (texto) / nombre del cliente si está seleccionado
  - Botón "..." menu (kebab) con opciones: Limpiar, Aplicar tax, etc.
  - Avatar/inicial del staff seleccionado al lado derecho

- [ ] **Step 2: Wire `onCustomerTap` al `CustomerSearchModal` existente**

  Path TPV: probablemente `features/ordering/presentation/.../CustomerSearchModal.kt`. Verificar firma y adaptar.

- [ ] **Step 3: Commit**

  `feat(checkout): add CustomerHeader with customer selector`.

### Task 4.2 — StaffSelectorSheet

- [ ] **Step 1: Generalizar desde `KioskStaffAssignDialog`**

  Path: `features/kiosk/presentation/components/KioskStaffAssignDialog.kt`. Mover/extender a `features/checkout/presentation/components/cart/StaffSelectorSheet.kt` como `ModalBottomSheet`. Lista de staff del venue con avatar y rol.

- [ ] **Step 2: Wire desde CartPanelView (onStaffTap)**

- [ ] **Step 3: Persistencia: el staff seleccionado se guarda en `CheckoutViewModel` y viaja con el pago**

  Si el turno activo ya tiene staff y no es manager/admin, autoseleccionarlo. Solo permitir cambio si el rol lo permite (similar a kiosk pattern).

- [ ] **Step 4: Commit**

  `feat(checkout): add StaffSelectorSheet`.

### Task 4.3 — NoteDialog

- [ ] **Step 1: Dialog simple con TextField**

  Trigger: `onNoteTap` desde NumericKeypadView (botón "+ Nota"). Muestra dialog con campo de texto, "Guardar" / "Cancelar".

- [ ] **Step 2: Commit**

  `feat(checkout): add NoteDialog for custom amount note`.

### Task 4.4 — PayLaterConfirmDialog

- [ ] **Step 1: Confirmar pay-later requiere cliente**

  Si `selectedCustomer == null` cuando se toca "Pagar después", abrir CustomerSearchModal primero. Luego confirmar: "¿Crear orden pendiente para [Cliente]?".

- [ ] **Step 2: Wire `viewModel.createPayLaterOrder(customerId)`**

  Toast de éxito + `clearCart()`. Toast de error en failure.

- [ ] **Step 3: Botón "Pagar después"**

  Aparece en `CartPanelView` arriba de "Cobrar", o dentro del menu kebab. Decidir durante implementación según fidelidad con Android (ver `cart/CartPanelView.kt` y `CheckoutScreen.kt:131-162`).

- [ ] **Step 4: Commit**

  `feat(checkout): add pay-later confirmation flow`.

---

# Phase 5 — Integración con PaymentScreen

**Objetivo:** Botón "Cobrar" del Cart navega a `PaymentScreen` con el `PaymentFlowOrigin` correcto y los items/total que la orden actual contiene.

**Files in this phase:**
- Modify: `features/checkout/presentation/CheckoutViewModel.kt` (método `prepareForPayment`)
- Modify: `features/checkout/presentation/CheckoutScreen.kt` (onCharge real)
- Modify: `features/payment/presentation/PaymentViewModel.kt` (sandbox + production) si hace falta nuevo entry point

### Task 5.1 — Decidir PaymentFlowOrigin desde CartState

- [ ] **Step 1: Lógica de origin**

  ```kotlin
  fun CartState.inferPaymentOrigin(): PaymentFlowOrigin =
      if (items.all { it.type is CartItemType.CustomAmount }) PaymentFlowOrigin.FAST
      else PaymentFlowOrigin.ORDER
  ```

  Justificación: si todo el cart son montos sueltos (sin productos del catálogo), es semánticamente igual al viejo "Pago Rápido". Si hay productos, es una orden — PaymentViewModel debe calcular tax/discount como si viniera de MenuScreen.

- [ ] **Step 2: Crear `Order` cuando `origin == ORDER`**

  ```kotlin
  suspend fun prepareForPayment(): PaymentPayload {
      return if (cartState.value.inferPaymentOrigin() == PaymentFlowOrigin.FAST) {
          PaymentPayload(
              amountCents = cartState.value.totalCents,
              origin = PaymentFlowOrigin.FAST,
              orderId = null,
              orderNote = cartState.value.orderNote,
          )
      } else {
          val order = orderRepository.createOrder(
              items = cartState.value.toOrderItems(),
              discount = cartState.value.orderDiscount,
              taxPercent = cartState.value.orderTaxPercent,
              staffId = cartState.value.selectedStaffId,
              customerId = selectedCustomer?.id,
              paymentStatus = PaymentStatus.PENDING_PAYMENT,
          )
          PaymentPayload(
              amountCents = order.totalCents,
              origin = PaymentFlowOrigin.ORDER,
              orderId = order.id,
              orderNote = cartState.value.orderNote,
          )
      }
  }
  ```

  ⚠️ Verificar API real de `OrderRepository.createOrder()` y adaptar la firma. Si no existe parámetro `paymentStatus`, agregar (no breaking change, default a PENDING_PAYMENT).

- [ ] **Step 3: Test del routing**

  ```kotlin
  @Test fun `cart with only custom amounts returns FAST origin`()
  @Test fun `cart with products returns ORDER origin and creates order`()
  ```

- [ ] **Step 4: Commit**

  `feat(checkout): infer PaymentFlowOrigin from cart contents`.

### Task 5.2 — Wire navegación a PaymentScreen

- [ ] **Step 1: `onCharge` real**

  ```kotlin
  CartPanelView(
      onCharge = {
          scope.launch {
              val payload = viewModel.prepareForPayment()
              onNavigateToPayment(payload)
          }
      },
      ...
  )
  ```

- [ ] **Step 2: Limpieza tras success**

  Cuando regrese del PaymentScreen con success, llamar `viewModel.clearCart()`. Usar `SavedStateHandle` callback o nav result API.

- [ ] **Step 3: Verificar que PaymentViewModel acepta ambos orígenes correctamente**

  Memoria: `payment/data/InitializationManager.kt` y `PaymentViewModel.kt` ya existen para sandbox y production. NO refactorizar — solo invocar con `origin = FAST` u `ORDER`. Verificar con `./gradlew testSandboxDebugUnitTest --tests "*PaymentViewModelTest*"` que no rompemos nada.

- [ ] **Step 4: ADB QA**

  ```bash
  ./scripts/capture-logs.sh payment start
  # Probar: monto suelto → Pago → success → cart limpio
  # Probar: agregar producto → Pago → success → cart limpio
  ./scripts/capture-logs.sh payment stop
  ```

- [ ] **Step 5: Commit**

  `feat(checkout): wire Cobrar button to PaymentScreen`.

---

# Phase 6 — Agregar "Cobrar" al WelcomeScreen (ADITIVO, sin borrar)

**Objetivo:** Exponer la nueva `CheckoutScreen` como un 3er botón "Cobrar" en el Home. **NO** se borra "Pago Rápido" ni "Órdenes" en esta fase — corren en paralelo durante validación. La eliminación va en Fase 8 (post-validación, plan separado).

**Files in this phase:**
- Modify: `core/presentation/screens/WelcomeScreen.kt` (agregar bloque del botón Cobrar)
- Modify: `core/presentation/navigation/NavRoute.kt` (agregar `Checkout`, sin borrar existentes)
- Modify: `core/presentation/navigation/AppNavigation.kt` (registrar `Checkout`, sin borrar)
- Modify: `features/payment/domain/model/TpvSettings.kt` (agregar `showCheckout`, conservar viejos)
- Modify: `core/data/network/dto/TpvSettingsDto.kt` (agregar campo nuevo, conservar viejos)
- **NO BORRAR:** `FastPaymentEntryScreen.kt`, `OrderingWelcomeScreen.kt` (van en Fase 8)

### Task 6.1 — Detectar tipo de venue (restaurante)

- [ ] **Step 1: Investigar fuente actual de `venue.type` en TPV**

  Grep: `venueType`, `SecureStorage.getVenueType()`, `venue.industry`, `terminal.venue.type`. Probablemente `SecureStorage.getVenueType(): String?` (memoria audit dijo guarda string tipo "RESTAURANT").

- [ ] **Step 2: Helper boolean**

  Si no existe, crear en `core/domain/util/VenueTypeUtil.kt`:
  ```kotlin
  fun String?.isFoodService(): Boolean =
      this?.uppercase() in setOf("RESTAURANT", "FOOD_AND_BEVERAGE", "FOOD_BEVERAGE", "CAFE", "BAR")
  ```

  Si TPV ya tiene enum, usarlo. Conservar conservador — si no se reconoce el tipo, mostrar "Mesas" (no esconder por error).

### Task 6.2 — Agregar botón "Cobrar" al WelcomeScreen (sin borrar nada)

- [ ] **Step 1: Mantener intactos los bloques de Pago Rápido + Órdenes**

  NO TOCAR `WelcomeScreen.kt:760-796`. Los `if (tpvSettings.showQuickPayment)` y `if (tpvSettings.showOrderManagement)` siguen funcionando idénticos.

- [ ] **Step 2: Insertar bloque "Cobrar" inmediatamente DESPUÉS del bloque de "Órdenes" (~línea 796)**

  ```kotlin
  // 🆕 COBRAR (NUEVO) — Cart unificado en validación, corre en paralelo con Pago Rápido + Órdenes
  if (tpvSettings.showCheckout) {
      val checkoutEnabled = canOperate && canWork
      val checkoutBadge = when {
          !canWork -> "Registra tu entrada"
          !canOperate -> "Abre el turno primero"
          else -> null
      }
      allButtons.add(
          ActionButton(
              icon = Icons.Default.PointOfSale,
              label = "Cobrar",
              enabled = checkoutEnabled,
              badge = checkoutBadge,
              onClick = { onNavigateToCheckout() }
          )
      )
  }
  ```

- [ ] **Step 3: Agregar UN callback nuevo al composable WelcomeScreen**

  Añadir parámetro: `onNavigateToCheckout: () -> Unit`. **No** tocar `onNavigateToOrdering` (sigue siendo usado por "Órdenes"). **No** agregar `onNavigateToFloorPlan` ni `onNavigateToOrderList` todavía — eso va en Fase 8 cuando "Órdenes" se borra y los reemplazamos.

- [ ] **Step 4: Update `WelcomeScreenContent` (private composable) con `onNavigateToCheckout`**

  Memoria: "WelcomeScreen vs WelcomeScreenContent: The actual UI is in WelcomeScreenContent (private), params must be passed through both". Pasar el callback por ambos niveles.

### Task 6.3 — Agregar `showCheckout` a TpvSettings (sin tocar flags viejos)

- [ ] **Step 1: Solo agregar flag nuevo**

  En `features/payment/domain/model/TpvSettings.kt`:
  - Agregar `val showCheckout: Boolean = true` (default ON para validación).
  - **NO** marcar `showQuickPayment` ni `showOrderManagement` como deprecated — siguen funcionando idénticos.

- [ ] **Step 2: Update DTO solo con campo nuevo**

  En `core/data/network/dto/TpvSettingsDto.kt`:
  ```kotlin
  @SerialName("showCheckout") val showCheckout: Boolean? = null,
  // resto del DTO intacto
  ```
  Mapper: `showCheckout = dto.showCheckout ?: true` (default true para que aparezca el nuevo botón sin esperar backend).

- [ ] **Step 3: Test del mapper + commit**

  Verificar: si backend no manda `showCheckout`, el botón sigue apareciendo (default true). Si el backend manda `showCheckout=false`, el botón se oculta (útil para apagar la feature en venues específicos durante rollout).

### Task 6.4 — Agregar ruta Checkout (sin borrar existentes)

- [ ] **Step 1: NavRoute — solo agregar**

  ```kotlin
  // AGREGAR (las existentes intactas):
  data object Checkout : NavRoute("checkout")
  ```

  Conservar: `FastPaymentEntry`, `OrderingWelcome`, `Menu`, `OrderList`, `FloorPlan`, `Payment` — todos siguen funcionando.

- [ ] **Step 2: AppNavigation — solo registrar Checkout**

  Agregar al NavHost, **sin tocar las composables existentes**:
  ```kotlin
  composable(NavRoute.Checkout.route) {
      CheckoutScreen(
          onNavigateBack = { navController.popBackStack() },
          onNavigateToPayment = { payload ->
              navController.navigate(NavRoute.Payment.buildPath(payload))
          },
      )
  }
  ```

- [ ] **Step 3: Wire `onNavigateToCheckout` en el llamador de WelcomeScreen**

  Donde se construye `WelcomeScreen(...)`, agregar:
  ```kotlin
  onNavigateToCheckout = { navController.navigate(NavRoute.Checkout.route) }
  ```

### Task 6.5 — Validación en device (sin borrar)

- [ ] **Step 1: Compile + install**

  ```bash
  ./gradlew installSandboxDebug
  ```

- [ ] **Step 2: Verificar Home muestra 3 botones**

  Home debe mostrar Pago Rápido + Órdenes + **Cobrar** (más los otros del flag). Cada uno abre su pantalla correspondiente.

- [ ] **Step 3: ADB monitoring**

  ```bash
  adb logcat -c && adb logcat -s Checkout,CheckoutViewModel
  ```

  Verificar que abrir "Cobrar" no rompe ninguno de los otros flujos.

- [ ] **Step 4: Commit**

  `feat(checkout): add Cobrar button to home (additive, alongside Pago Rapido and Ordenes)`.

### Task 6.6 — CHANGELOG entry

⚠️ **Política obligatoria** (ver `.claude/rules/changelog-policy.md`): cada modificación va en CHANGELOG.md.

- [ ] **Step 1: Agregar entrada en `CHANGELOG.md` bajo `## [Unreleased]`**

  ```markdown
  ### **Added**
  - **Botón "Cobrar" en Home (validación)**: nuevo CheckoutScreen unificado accesible desde un 3er botón en `WelcomeScreen`, en paralelo con "Pago Rápido" y "Órdenes" durante la fase de validación. CheckoutScreen incluye 4 tabs (Teclado, Shortcuts, Todos los productos, Configurar) inspirados en avoqado-android, búsqueda, scan QR, mosaico configurable, selección de cliente/staff y pagar después.
  - **MosaicShortcut**: nueva tabla Room para persistir shortcuts/favoritos configurables por venue.
  - **TpvSettings.showCheckout**: flag (default true) para habilitar/deshabilitar el botón Cobrar por venue.
  ```

  Notar: NO hay sección "Removed" todavía. Las pantallas viejas siguen activas hasta Fase 8.

---

# Phase 7 — Tests + QA en PAX A910S

**Objetivo:** Cobertura de tests verde + QA manual de los 3 flujos en device real.

### Task 7.1 — Tests unitarios

- [ ] **Step 1: Portar `CartStateTest` desde avoqado-android**

  Path destino: `app/src/test/java/com/jaac/avoqado_tpv/features/checkout/domain/model/CartStateTest.kt`. Adaptar imports. Esperado: 100% pass.

- [ ] **Step 2: Tests de `CheckoutViewModel`**

  Cubrir: addCustomAmount, addProduct, clearCart, applyOrderTaxPercent, createPayLaterOrder, prepareForPayment (FAST vs ORDER).

- [ ] **Step 3: Tests de `MosaicRepository`**

  Con in-memory Room: upsert, observe, delete.

- [ ] **Step 4: Borrar tests obsoletos**

  ```bash
  git rm app/src/test/.../FastPaymentRecorderTest.kt 2>/dev/null
  ```

- [ ] **Step 5: Correr toda la suite**

  ```bash
  ./gradlew testSandboxDebugUnitTest
  ```

  Esperado: ~220 + nuevos = 240+ tests, 0 failures. Si algo del legado rompe (ej. test que referencia `FastPaymentEntry`), borrarlo o adaptarlo.

### Task 7.2 — QA manual en PAX A910S (checklist en device)

⚠️ Monitoreo ADB obligatorio (ver `.claude/rules/testing-and-adb.md`). NO continuar a Phase 8 / borrado de pantallas legacy hasta que **todos** los checks aquí pasen.

#### Paso 1 — Setup

- [ ] **Install sandbox sobre versión anterior** (sin desinstalar, para probar Room migration v20→v21):
  ```bash
  ./gradlew installSandboxDebug
  ```
- [ ] **Capturar logs en background** (cubre payment + order + room):
  ```bash
  adb logcat -c
  adb logcat -s Checkout,CheckoutViewModel,RoomDatabase,PaymentViewModel,OrderRepository | tee /tmp/checkout-qa-$(date +%s).log
  ```
- [ ] Abrir Crashlytics MCP en paralelo para detectar non-fatals durante QA.

#### Paso 2 — Room migration (CRÍTICA — si esto rompe, no continuar)

- [ ] App **abre sin crash** después del install (la migration v20→v21 corre).
- [ ] En logcat: buscar `migrate from version 20 to 21` o equivalente. NO debe haber `IllegalStateException` ni `SQLiteException`.
- [ ] Verificar tabla nueva existe:
  ```bash
  adb shell run-as com.jaac.avoqado_tpv.sandbox sqlite3 databases/avoqado_database \
    "SELECT name FROM sqlite_master WHERE type='table' AND name='mosaic_shortcut';"
  ```
  Debe devolver: `mosaic_shortcut`.
- [ ] **Datos viejos preservados**: si tenías órdenes/pagos/turnos antes del update, siguen visibles (verifica entrando a "Órdenes" y "Pagos" — los flows legacy).

#### Paso 3 — Home muestra los 3 botones (sin borrar nada)

- [ ] **"Pago Rápido"** sigue visible y funciona idéntico (entry point legacy intacto).
- [ ] **"Órdenes"** sigue visible y funciona idéntico (entry point legacy intacto).
- [ ] **"Cobrar"** (NUEVO) aparece con ícono PointOfSale, junto a los otros dos.
- [ ] Los 3 botones se deshabilitan con badge "Abre el turno primero" cuando `canOperate=false`.
- [ ] Los 3 botones se deshabilitan con badge "Registra tu entrada" cuando `canWork=false`.

#### Paso 4 — Flujo FAST (cart con solo monto)

- [ ] Home → tap **Cobrar** → abre CheckoutScreen en el tab **Teclado**.
- [ ] Teclear `1`, `5`, `0`, `0`, `0` → display muestra `$150.00`.
- [ ] Tap chip `$100` → display salta a `$1.00` (porque son centavos). Re-teclear `1`,`5`,`0`,`0`,`0`.
- [ ] Tap `+ Nota` → abre **NoteDialog** real. Escribir "Propina mesero" → Guardar. Botón muestra "Propina mesero" en azul.
- [ ] Tap `+` (key 12 del keypad) → item entra al cart con nombre "Propina mesero" y monto $150.00.
- [ ] Cart panel abajo muestra "1 item" + "Cobrar $150.00".
- [ ] Tap **Cobrar $150.00** → toast "Preparando sistema de pagos…" → navega a PaymentScreen con `initialAmount=150.00`, sin `orderId`.
- [ ] **PaymentScreen procesa como Pago Rápido normal** (mismo flujo que el botón legacy).
- [ ] Tap aprobado → Payment Success → Home.
- [ ] ⚠️ **Limitación conocida**: regresar a Cobrar muestra el item de $150 todavía en el cart (no se limpia automáticamente). Tap "Limpiar" del header del cart funciona.

#### Paso 5 — Flujo ORDER (cart con productos del catálogo)

- [ ] Home → Cobrar → tab **Todos los productos** → grid muestra productos del venue (cargados desde `ProductRepository`).
- [ ] Tap en un producto → snackbar "Agregado: [nombre]". Item entra al cart con stepper `+/-`.
- [ ] Tap producto con modifiers — Phase 4 no agregó modifier sheet, entra "pelado" sin modifiers. (Phase 5+ agregará).
- [ ] Stepper `+` aumenta cantidad, `-` baja, llegar a 0 elimina la línea.
- [ ] Agregar 2 productos → "Cobrar $X.XX" muestra suma correcta.
- [ ] Tap **Cobrar** → en logcat ves: `🛒 Mixed cart` no aparece si solo hay productos; `💳 Checkout → Payment: origin=ORDER amount=...`.
- [ ] En Crashlytics: NO debe haber non-fatal de `OrderRepository.createOrder` ni `addItemsToOrder`.
- [ ] Backend recibe POST a `/tpv/venues/{venueId}/orders` con `orderType=TAKEOUT` y luego PATCH a `/orders/{id}/items` con los items.
- [ ] PaymentScreen muestra el orderNumber + total correcto.
- [ ] Pago aprobado → Order queda PAID en backend.

#### Paso 6 — Flujo Pay-later (con cliente)

- [ ] Home → Cobrar → agregar 1 producto desde tab Productos.
- [ ] CartPanelView header muestra "Agregar cliente" (gris) + staff name (chip derecho).
- [ ] Tap "Agregar cliente" → abre ModalBottomSheet → muestra recientes (si los hay).
- [ ] Tipear "5" (un dígito): NO dispara búsqueda, sigue mostrando recientes (umbral 2 chars).
- [ ] Tipear "55" → ejecuta `customerRepository.searchCustomers("55")` → muestra resultados.
- [ ] Tap cliente → sheet cierra, snackbar "Cliente: [Nombre]", header muestra nombre en color primary.
- [ ] **"Pagar después"** ahora aparece arriba del botón Cobrar (solo aparece con productos + cliente).
- [ ] Tap "Pagar después" → texto cambia a "Guardando…" durante el call → snackbar "Orden T-XXX guardada para cobrar después".
- [ ] Cart se limpia (cliente también).
- [ ] Home → tap **"Órdenes"** legacy → la orden aparece en la lista, con cliente asociado y `paymentStatus=PENDING`.

#### Paso 7 — Shortcuts + Mosaico (persistencia Room v21)

- [ ] Home → Cobrar → tab **Shortcuts** → empty state con CTA "Configurar shortcuts".
- [ ] Tap CTA → cambia al tab **Configurar** → grid 3x3 de 9 slots vacíos.
- [ ] Tap slot vacío → ModalBottomSheet con lista de productos.
- [ ] Tap producto → slot se llena con label, sheet cierra. Persistencia inmediata en Room.
- [ ] Repetir con 2-3 slots más.
- [ ] Tap × en un slot lleno → slot se libera. Inmediato.
- [ ] Tab **Shortcuts** → ahora muestra los tiles configurados.
- [ ] Tap un tile → snackbar "Agregado: [producto]", item entra al cart.
- [ ] **Persistencia**: matar la app (`adb shell am force-stop com.jaac.avoqado_tpv.sandbox`), reabrir → Shortcuts siguen ahí.
- [ ] **Venue switch**: cambiar de venue → Shortcuts del venue anterior NO aparecen (cada venue tiene su propio mosaico).

#### Paso 8 — Búsqueda y QR

- [ ] Top bar → tap el campo "Buscar" (estilo pill) → abre overlay full-screen con campo autoenfocado.
- [ ] Tipear nombre parcial de un producto → resultados filtran client-side. Confirmar **case-insensitive** (tipear en minúsculas un producto que está en mayúsculas funciona).
- [ ] Tap resultado → producto al cart, overlay cierra, snackbar.
- [ ] Top bar → botón Refresh → loading spinner en su lugar → snackbar o productos refrescan.
- [ ] Top bar → botón QR → abre `BarcodeScannerScreen` (ZXing). Apuntar a un código válido → producto al cart + snackbar. Apuntar a código inexistente → snackbar "Producto no encontrado".

#### Paso 9 — Regresiones críticas (NO debe romperse nada legacy)

- [ ] **FastPaymentEntry sigue funcionando** desde el botón "Pago Rápido" del Home.
- [ ] **OrderingWelcome sigue funcionando** desde el botón "Órdenes" del Home → MenuScreen → Pago.
- [ ] **FloorPlan sigue funcionando** (solo si venue=restaurant): Órdenes → Servicio de Mesa.
- [ ] **KioskCartScreen sigue funcionando** si el venue tiene `kioskModeEnabled=true`.
- [ ] **Refunds** desde el botón "Pagos" funcionan igual.
- [ ] **Reportes, Turnos, Mensajes** del Home navegan a sus pantallas sin cambios.

#### Paso 10 — Previews

Abrir Android Studio → revisar visualmente los `@Preview` con `device = PAX_A910S`:

- [ ] `NumericKeypadView` — Empty, With Amount, With Note
- [ ] `CartPanelView` — Empty, One custom amount, Mixed items + tax
- [ ] `ProductGridView` — Loading, Empty, Populated
- [ ] `ShortcutsGridView` — Empty, Populated
- [ ] `TabSelectorView` — Keypad selected, Products selected
- [ ] `SearchBarView` — Idle, Loading
- [ ] `NoteDialog` — Empty, With text
- [ ] `CheckoutScreen` — Empty cart

Verificar: ningún texto se corta, ningún botón se sale del frame, padding razonable en cada estado.

#### Paso 11 — Crashlytics (≥30 min post-install)

- [ ] Abrir Firebase Crashlytics MCP (sandbox app ID).
- [ ] Buscar `crashlytics_list_events(filter={issueErrorTypes:["FATAL"]}, since=30m)`.
- [ ] Si aparece cualquier crash mencionando `checkout`, `Mosaic`, `CheckoutViewModel`, `CartPanelView`, `Room migrate from 20 to 21` → **bloqueador para Phase 8**. Investigar.
- [ ] Same with NON_FATAL — observabilidad de payment loading stalls debería estar limpia.

#### Paso 12 — Stop logs + revisar

- [ ] `adb logcat -d > /tmp/checkout-final-$(date +%s).log`
- [ ] Buscar:
  - `🛒 Mixed cart` — cuántas veces apareció. Si >2 durante QA normal → considerar UX más estricto.
  - `e: ` o `FATAL` — debe ser 0.
  - `RoomDatabase` warnings o errors — debe ser 0.

---

#### Resultado esperado

| Categoría | Pasa si... |
|---|---|
| Room migration | App abre sin crash, tabla `mosaic_shortcut` existe, datos viejos preservados |
| 3 botones en Home | Pago Rápido + Órdenes + Cobrar todos visibles y funcionales |
| Flujo FAST | Cobrar con monto suelto procesa como FastPayment legacy |
| Flujo ORDER | Cobrar con productos crea Order TAKEOUT en backend + Payment funciona |
| Pay-later | Crea orden PENDING + cliente asociado, visible en Órdenes legacy |
| Shortcuts persistencia | Sobrevive force-stop, venue-scoped |
| Regresiones | Cero — todos los flujos legacy intactos |
| Crashlytics | Cero crashes nuevos atribuibles al checkout |

**Si todos los checks pasan**: Phase 8 (borrar pantallas legacy) habilitada.
**Si algún check falla**: arreglar antes de continuar; los flujos legacy son el fallback.

### Task 7.3 — Version bump + release prep

- [ ] **Step 1: Decidir bump**

  Esto es un **MINOR** (`1.x.0`) — los usuarios pueden hacer algo nuevo (Cobrar con productos del catálogo + shortcuts configurables) que antes no podían. Ver `.claude/rules/release-and-git.md` → "Version Bump Recommendations".

- [ ] **Step 2: Update `app/build.gradle.kts`**

  - `versionCode` += 1
  - `versionName` siguiente minor

- [ ] **Step 3: Mover `[Unreleased]` a versión específica en CHANGELOG**

- [ ] **Step 4: Release ceremony**

  Seguir `avoqado:release-production` skill:
  - bump → CHANGELOG → commit → tag → `./gradlew assembleProductionRelease` → apksigner v2 → guardar en iCloud → mandar a Blumon.

---

---

# Phase 8 — Cleanup post-validación (PLAN SEPARADO, después de Fase 7)

**No ejecutar hasta confirmar que el botón "Cobrar" funciona correctamente en producción durante al menos 1 release ciclo.**

**Trigger para arrancar Fase 8:**
- Métricas: ≥80% de pagos pasan por "Cobrar" en lugar de "Pago Rápido"/"Órdenes" durante 2 semanas
- Cero crashes nuevos atribuibles al CheckoutScreen en Crashlytics
- Feedback positivo de operadores

**Files in this phase:**
- Delete: `core/presentation/screens/FastPaymentEntryScreen.kt`
- Delete: `features/ordering/presentation/OrderingWelcomeScreen.kt` + ViewModel
- Modify: `core/presentation/screens/WelcomeScreen.kt` (borrar bloques `showQuickPayment` y `showOrderManagement`, agregar "Mesas" y "Pedidos")
- Modify: `core/presentation/navigation/NavRoute.kt` (borrar `FastPaymentEntry`, `OrderingWelcome`)
- Modify: `core/presentation/navigation/AppNavigation.kt` (borrar composables viejos)
- Modify: `features/payment/domain/model/TpvSettings.kt` (deprecar viejos flags)
- Delete: tests asociados

### Task 8.1 — Borrar pantallas viejas

- [ ] Git rm `FastPaymentEntryScreen.kt`
- [ ] Git rm `OrderingWelcomeScreen.kt` + ViewModel
- [ ] Buscar referencias rotas: `./gradlew compileSandboxDebugKotlin 2>&1 | grep -i unresolved`
- [ ] Fix imports rotos
- [ ] Commit: `refactor(home): remove legacy FastPaymentEntry and OrderingWelcome screens`

### Task 8.2 — Agregar "Mesas" y "Pedidos" en su lugar

- [ ] Detectar venue tipo restaurante (helper `isFoodService()`)
- [ ] Agregar bloque "Mesas" en WelcomeScreen (solo si restaurante) → `onNavigateToFloorPlan()`
- [ ] Agregar bloque "Pedidos" en WelcomeScreen → `onNavigateToOrderList()`
- [ ] Pasar nuevos callbacks por `WelcomeScreen` y `WelcomeScreenContent`
- [ ] Wire en `AppNavigation`
- [ ] Commit

### Task 8.3 — Deprecar flags viejos en TpvSettings

- [ ] Marcar `showQuickPayment` y `showOrderManagement` con `@Deprecated`
- [ ] Mapear a `showCheckout` y `showOrders` (nuevo)
- [ ] Update DTO con retrocompat de campos viejos
- [ ] Test del mapper
- [ ] Commit

### Task 8.4 — Borrar rutas + AppNavigation cleanup

- [ ] Borrar `NavRoute.FastPaymentEntry` y `NavRoute.OrderingWelcome`
- [ ] Borrar composables de `AppNavigation`
- [ ] Compile clean
- [ ] Commit

### Task 8.5 — CHANGELOG + version bump

- [ ] Agregar `### **Removed**` con lo borrado
- [ ] Decidir bump (probablemente PATCH si no agrega capability nueva — solo limpieza)
- [ ] Release ceremony

---

## Notas de migración para usuarios existentes

1. **DraftOrders en curso:** Si hay órdenes en estado DRAFT cuando se instale la nueva versión, siguen siendo accesibles desde "Pedidos" → filtro OPEN. No se pierden.
2. **Saved Carts (nuevo):** El nuevo `SavedCartsRepository` parte vacío — no migra de DraftOrders.
3. **Mosaico:** Empieza vacío. Cada venue/staff debe configurarlo manualmente desde el tab "Configurar" la primera vez.
4. **TpvSettings backend:** Si el venue tiene `showQuickPayment=false` o `showOrderManagement=false` configurado, mapea automáticamente a `showCheckout=false` o `showOrders=false`. Confirmar con producto si quieren resetear todos los venues a defaults.

## Riesgos y mitigaciones

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| Room migration crashea en update | Baja | Alta (100% crash) | Test manual de migration con datos reales antes de release |
| `PaymentFlowOrigin.ORDER` esperaba comportamiento específico de MenuScreen | Media | Media | Tests de PaymentViewModel existentes deben seguir verdes; QA manual cubre ambos orígenes |
| ProductRepository sync no expone barcode lookup | Media | Baja | Fallback: filtrar `products.firstOrNull { it.barcode == code }` en memoria |
| Permission system no tiene `tpv-checkout:*` | Media | Baja | Reusar permisos de Pago Rápido / Órdenes (FAST y ORDER) — no se rompen porque PaymentFlowOrigin sigue igual |
| ShortcutsGrid sin datos en primer uso | Alta | Baja | Empty state con CTA "Configurar" — onboarding implícito |
| FloorPlan navegación cambia | Baja | Crítica | NO modificar FloorPlan ni MenuScreen; el botón "Mesas" abre FloorPlan directo igual que antes desde OrderingWelcome |
| Backend no acepta `paymentStatus = PENDING_PAYMENT` en createOrder | Media | Media | Verificar en avoqado-server `routes/orders.ts` antes de Fase 5; el modelo Order ya soporta `isPayLater` (auditado) |

## Checklist global pre-release

- [ ] Todas las fases 1-7 completadas
- [ ] `./gradlew testSandboxDebugUnitTest` verde (≥240 tests, 0 failures)
- [ ] `./gradlew compileSandboxDebugKotlin` + `compileProductionDebugKotlin` verde
- [ ] `./gradlew lint --continue` sin warnings nuevos
- [ ] Room migration testeada con datos reales
- [ ] CHANGELOG.md actualizado
- [ ] Cross-repo check verde
- [ ] QA manual en PAX A910S: 3 flujos principales + 4 secundarios
- [ ] Variant sync: `sandbox/` y `production/` consistentes (esto no debería tocarlos, pero confirmar)
- [ ] Backend confirma soporte para `paymentStatus = PENDING` y para nuevos/viejos flags TpvSettings
