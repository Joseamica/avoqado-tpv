# 🎯 Plan de Implementación: OrderScreen Completo

> **Decisión del Usuario:** Implementación completa (5-7 días)
> **Fecha:** 2025-01-15
> **Status:** Pendiente de aprobación

---

## 📦 Objetivo
Implementar pantalla de gestión de órdenes con funcionalidad completa: ver orden, agregar/quitar items, sincronización multi-terminal, y navegación a pago.

---

## 🎯 Decisiones del Usuario

✅ **Enfoque:** Completo (5-7 días) - Funcionalidad completa desde el inicio
✅ **Productos:** Mock data primero - 15 productos hardcoded para iteración rápida de UI
✅ **Concurrencia:** Auto-refresh silencioso - Sin notificar al usuario sobre conflictos de versión
✅ **Kitchen:** No crítico - Omitir "Send to Kitchen" del MVP inicial

---

## 📅 Cronograma: 5-7 días (7 fases)

### **Fase 1: Domain Layer** (Día 1 - 3 horas)

**Archivos a crear:**
- `features/ordering/domain/Order.kt` - Modelo con Order, OrderItem, enums (OrderStatus, KitchenStatus, PaymentStatus, OrderType)
- `features/ordering/domain/Product.kt` - Modelo Product con mock data hardcoded (15 productos de ejemplo)
- `features/ordering/domain/OrderRepository.kt` - Interface con `getOrder()`, `addItemsToOrder()`
- `features/ordering/domain/usecases/GetOrderUseCase.kt`
- `features/ordering/domain/usecases/AddItemsToOrderUseCase.kt`

**Producto mock incluye:** Pizza Margherita ($180), Coca-Cola ($35), Ensalada César ($120), Hamburguesa ($150), Cerveza ($50), etc.

**Categorías:** Bebidas, Comidas, Postres

---

### **Fase 2: Data Layer** (Día 1-2 - 4 horas)

**Archivos a crear:**
- `features/ordering/data/dto/OrderDto.kt` - DTOs para API responses
- `features/ordering/data/dto/AddItemsRequest.kt` - Request body para agregar items
- `features/ordering/data/api/OrderApiService.kt` - Retrofit service con `getOrder()`, `addItemsToOrder()`
- `features/ordering/data/repository/OrderRepositoryImpl.kt` - Implementación con manejo de errores y optimistic concurrency
- `features/ordering/data/mappers/OrderMappers.kt` - DTO → Domain conversión

**Endpoints backend:**
- `GET /tpv/venues/{venueId}/orders/{orderId}`
- `PATCH /tpv/venues/{venueId}/orders/{orderId}/items`

**Backend valida:** Optimistic concurrency con campo `version` (incrementa en cada update)

---

### **Fase 3: Socket.IO Integration** (Día 2 - 2 horas)

**Archivos a modificar:**
- `core/data/realtime/events/SocketEvent.kt` - Agregar `SocketEvent.OrderUpdated`
- `core/data/realtime/SocketManager.kt` - Listener para `"order_updated"` event

**Payload del evento:**
```kotlin
data class OrderUpdated(
    val orderId: String,
    val orderNumber: String,
    val tableId: String?,
    val newItems: List<OrderItemInfo>,
    val subtotal: Double,
    val total: Double,
    val version: Int,  // Para optimistic concurrency
    val venueId: String,
    val timestamp: String
)
```

**Por qué es crítico:**
- Terminal A agrega item → Terminal B ve actualización instantánea
- Evita conflictos de versión en entornos multi-terminal
- Permite cocina/barra ver pedidos en tiempo real

---

### **Fase 4: ViewModel & State** (Día 3 - 3 horas)

**Archivos a crear:**
- `features/ordering/presentation/orderdetail/OrderDetailViewModel.kt`
- `features/ordering/presentation/orderdetail/OrderDetailState.kt` - Sealed class (Idle, Loading, Success, Error, AddingItems)

**Funcionalidad:**
- Cargar orden con `getOrderUseCase(orderId)`
- Escuchar Socket.IO events para sync multi-terminal
- Manejo de concurrencia optimista con **auto-refresh silencioso**:
  ```kotlin
  // Si version mismatch (400 error):
  1. Timber.w("Version conflict - refreshing silently")
  2. refreshOrder()  // Get latest version
  3. Retry addItems with new version
  4. NO mostrar error al usuario
  ```
- `addItems()`, `removeItem()` functions
- State caching para optimistic UI updates

---

### **Fase 5: UI Básica - Ver Orden** (Día 3-4 - 4 horas)

**Archivos a crear:**
- `features/ordering/presentation/orderdetail/OrderDetailScreen.kt`
- `features/ordering/presentation/orderdetail/components/OrderSummaryCard.kt` - Header con número de orden, mesa, covers, waiter
- `features/ordering/presentation/orderdetail/components/OrderItemCard.kt` - Item individual con cantidad, nombre, precio, botón eliminar
- `features/ordering/presentation/orderdetail/components/OrderTotalCard.kt` - Subtotal, tax, total

**Layout:**
```
┌─────────────────────────────────────────┐
│ AvoqadoTopBar                           │
│ "Orden #ORD-1234567890"                 │
│ "Mesa 5 · 2 personas · Juan Pérez"     │
├─────────────────────────────────────────┤
│ ResponsiveScaffold                      │
│  │                                      │
│  │ OrderSummaryCard                     │
│  │ ┌─────────────────────────────────┐ │
│  │ │ Mesa 5 · 2 Personas             │ │
│  │ │ Mesero: Juan Pérez              │ │
│  │ │ Estado: PENDIENTE               │ │
│  │ └─────────────────────────────────┘ │
│  │                                      │
│  │ Spacer(spacingLarge)                │
│  │                                      │
│  │ Text("Items de la Orden")           │
│  │ Spacer(spacingMedium)               │
│  │                                      │
│  │ LazyColumn (OrderItemsList)         │
│  │ ┌─────────────────────────────────┐ │
│  │ │ 🍕 Pizza Margherita         x2  │ │
│  │ │    $180.00 c/u       $360.00   │ │
│  │ │    [Eliminar]                   │ │
│  │ ├─────────────────────────────────┤ │
│  │ │ 🥤 Coca-Cola                x1  │ │
│  │ │    $35.00 c/u        $35.00    │ │
│  │ │    [Eliminar]                   │ │
│  │ ├─────────────────────────────────┤ │
│  │ │ 🥗 Ensalada César           x1  │ │
│  │ │    $120.00 c/u       $120.00   │ │
│  │ │    [Eliminar]                   │ │
│  │ └─────────────────────────────────┘ │
│  │                                      │
│  │ Spacer(spacingLarge)                │
│  │                                      │
│  │ OrderTotalCard                       │
│  │ ┌─────────────────────────────────┐ │
│  │ │ Subtotal           $515.00      │ │
│  │ │ IVA (16%)          $82.40       │ │
│  │ │ ────────────────────────────    │ │
│  │ │ TOTAL              $597.40      │ │
│  │ └─────────────────────────────────┘ │
│  │                                      │
│  │ Spacer(spacingLarge)                │
│  │                                      │
│  │ Row (Action Buttons)                │
│  │ ┌─────────────────────────────────┐ │
│  │ │ [+ Agregar Items]               │ │
│  │ │ [Tomar Pago]                    │ │
│  │ └─────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

**Spacing usando ResponsiveSizes:**
- `spacingLarge` (24-48dp) entre secciones mayores
- `spacingMedium` (16-32dp) entre items de lista
- `spacingSmall` (8-16dp) dentro de cards

---

### **Fase 6: Selección de Productos** (Día 4-5 - 5 horas)

**Archivos a crear:**
- `features/ordering/presentation/orderdetail/components/ProductSelectionModal.kt` - Full-screen modal con grid de productos
- `features/ordering/presentation/orderdetail/components/ProductCard.kt` - Card individual con imagen (emoji), nombre, precio
- `features/ordering/presentation/orderdetail/components/ProductCategoryTabs.kt` - Tabs horizontales (Bebidas, Comidas, Postres)
- `features/ordering/presentation/orderdetail/components/QuantitySelector.kt` - Widget +/- para cantidad

**Productos Mock (15 items en Product.kt):**

**Bebidas:**
- Coca-Cola - $35.00 🥤
- Agua Natural - $25.00 💧
- Cerveza Corona - $50.00 🍺
- Jugo de Naranja - $40.00 🍊
- Café Americano - $30.00 ☕

**Comidas:**
- Pizza Margherita - $180.00 🍕
- Hamburguesa Clásica - $150.00 🍔
- Ensalada César - $120.00 🥗
- Tacos al Pastor - $95.00 🌮
- Pasta Alfredo - $165.00 🍝

**Postres:**
- Tiramisú - $80.00 🍰
- Helado de Vainilla - $60.00 🍨
- Brownie con Helado - $70.00 🧁
- Flan Napolitano - $55.00 🍮
- Pay de Limón - $65.00 🥧

**Flow de Selección:**
```
1. User toca "Agregar Items"
   ↓
2. Abre ProductSelectionModal (fullscreen)
   ├─ CategoryTabs en top (Todas | Bebidas | Comidas | Postres)
   ├─ LazyVerticalGrid de ProductCards (2 columnas en portrait, 3 en landscape)
   └─ [Cerrar] button
   ↓
3. User toca ProductCard
   ↓
4. Muestra QuantitySelector inline o bottom sheet
   ├─ [-] [  2  ] [+]
   ├─ TextField para notas opcionales
   └─ [Agregar] button
   ↓
5. User confirma cantidad
   ↓
6. ViewModel.addItems(listOf(AddOrderItemRequest(productId, quantity, notes)))
   ↓
7. Loading overlay: "Agregando items..."
   ↓
8. Backend valida version y agrega items
   ↓
9. Si version conflict (400):
   - Timber.w("Version conflict")
   - refreshOrder() silently
   - Retry addItems with new version
   ↓
10. On success:
    - Cierra modal
    - Muestra item en lista (optimistic update)
    - Socket.IO notifica otros terminals
```

---

### **Fase 7: Navegación & Polish** (Día 6-7 - 4 horas)

**Archivos a modificar:**
- `core/presentation/navigation/NavRoute.kt` - Agregar:
  ```kotlin
  data object OrderDetail : NavRoute("order_detail/{orderId}") {
      fun createRoute(orderId: String) = "order_detail/$orderId"
  }
  ```

- `core/presentation/navigation/AppNavigation.kt` - Agregar composable:
  ```kotlin
  composable(
      route = NavRoute.OrderDetail.route,
      arguments = listOf(navArgument("orderId") { type = NavType.StringType })
  ) { backStackEntry ->
      val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
      OrderDetailScreen(
          orderId = orderId,
          onNavigateBack = { navController.navigateUp() },
          onNavigateToPayment = { orderId, amount ->
              // TODO: Navigate to PaymentScreen with orderId
              navController.navigate(NavRoute.Payment.route)
          }
      )
  }
  ```

- `features/ordering/presentation/TableServiceScreen.kt` - Actualizar callback:
  ```kotlin
  TableServiceScreen(
      onNavigateBack = { navController.navigateUp() },
      onTableAssigned = { orderId ->
          navController.navigate(NavRoute.OrderDetail.createRoute(orderId))
      }
  )
  ```

**Unit Tests:**
- `GetOrderUseCaseTest.kt` - Test casos éxito/error
- `AddItemsToOrderUseCaseTest.kt` - Test optimistic concurrency
- `OrderMappersTest.kt` - Test DTO → Domain conversión

**Polish:**
- Loading overlays con `AvoqadoLoadingOverlay("Cargando orden...")`
- Error messages user-friendly (sin tecnicismos):
  - Network error → "No se pudo conectar al servidor. Verifica tu conexión."
  - 404 → "Orden no encontrada. La orden pudo haber sido eliminada."
  - 500 → "Error del servidor. Intenta nuevamente."
- Empty states cuando orden sin items:
  ```kotlin
  if (order.items.isEmpty()) {
      AvoqadoEmptyState(
          message = "No hay items en esta orden",
          icon = Icons.Default.ShoppingCart,
          actionText = "Agregar Items",
          onAction = { showProductModal = true }
      )
  }
  ```
- Responsive spacing con `ResponsiveScaffold`
- Confirmación antes de eliminar items:
  ```kotlin
  var showDeleteConfirmation by remember { mutableStateOf(false) }
  if (showDeleteConfirmation) {
      AvoqadoDialog(
          title = "Eliminar Item",
          message = "¿Seguro que quieres eliminar ${item.productName}?",
          onConfirm = { viewModel.removeItem(item.id) },
          onDismiss = { showDeleteConfirmation = false }
      )
  }
  ```

---

## 🔑 Decisiones Técnicas Clave

### 1. **Productos Mock (no backend real)**

**Razón:** Iterar rápidamente en UI sin bloqueo de backend

**Implementación:**
```kotlin
// features/ordering/domain/Product.kt
object MockProducts {
    val allProducts = listOf(
        Product(
            id = "prod_1",
            name = "Coca-Cola",
            sku = "BEB-001",
            price = BigDecimal("35.00"),
            categoryId = "cat_bebidas",
            categoryName = "Bebidas",
            description = "Refresco de cola 600ml",
            imageUrl = null,  // Usamos emoji "🥤" en UI
            available = true
        ),
        // ... 14 productos más
    )

    val categories = listOf(
        ProductCategory(id = "cat_bebidas", name = "Bebidas", displayOrder = 1, productCount = 5),
        ProductCategory(id = "cat_comidas", name = "Comidas", displayOrder = 2, productCount = 5),
        ProductCategory(id = "cat_postres", name = "Postres", displayOrder = 3, productCount = 5)
    )
}
```

**Ventajas:**
- ✅ Deploy UI inmediato sin esperar backend
- ✅ Pruebas de UX con datos realistas
- ✅ Fácil reemplazar después con `ProductRepository` real

**Futura migración:**
```kotlin
// Fase 8 (Futuro): Reemplazar con backend real
class ProductRepositoryImpl @Inject constructor(
    private val productApi: ProductApiService
) : ProductRepository {
    override suspend fun getProducts(venueId: String): Result<List<Product>> {
        // GET /tpv/venues/{venueId}/products
    }
}
```

---

### 2. **Concurrencia Optimista: Auto-refresh Silencioso**

**Problema:** Múltiples terminals pueden modificar la misma orden simultáneamente

**Solución Backend:** Campo `version` que incrementa en cada update

**Estrategia Android:**
```kotlin
suspend fun addItems(items: List<AddOrderItemRequest>) {
    val currentVersion = (_state.value as? OrderDetailState.Success)?.order?.version ?: return

    _state.value = OrderDetailState.AddingItems

    when (val result = addItemsToOrderUseCase(orderId, items, currentVersion)) {
        is Result.Success -> {
            _state.value = OrderDetailState.Success(result.data)
        }
        is Result.Error -> {
            if (result.exception is ApiException.HttpError && result.exception.code == 400) {
                // Version conflict - auto-refresh silently
                Timber.w("⚠️ Version conflict detected - refreshing silently")
                refreshOrder()

                // Retry with new version
                delay(500)  // Brief delay for UX
                addItems(items)
            } else {
                _state.value = OrderDetailState.Error(result.exception.toUserMessage())
            }
        }
    }
}
```

**Razón de auto-refresh silencioso:**
- ✅ Seamless UX - usuario no se entera del conflicto
- ✅ Evita fricciones innecesarias
- ✅ Backend resuelve el conflicto correctamente
- ✅ Logs técnicos para debugging

**Alternativas descartadas:**
- ❌ Mostrar error "Orden modificada" → Confunde al usuario
- ❌ Bloquear UI durante conflict resolution → Mala UX
- ❌ Pedir confirmación → Fricciona el flujo

---

### 3. **"Send to Kitchen" - Omitido del MVP**

**Razón:** No es crítico para flujo básico de orden + pago

**Backend actual:** `kitchenStatus` se actualiza automáticamente
- PENDING → cuando se crea orden
- PREPARING → cuando cocina comienza (automatizado o manual)
- READY → cuando platillos listos
- SERVED → cuando mesero entrega

**Futuro (Fase 8):**
```kotlin
// Button "Enviar a Cocina"
AvoqadoButton(
    onClick = { viewModel.sendToKitchen() },
    enabled = order.kitchenStatus == KitchenStatus.PENDING
) {
    Text("Enviar a Cocina")
}

// ViewModel
fun sendToKitchen() {
    viewModelScope.launch {
        sendToKitchenUseCase(orderId)
            .onSuccess { /* Update status */ }
    }
}
```

---

### 4. **Socket.IO: Multi-Terminal Sync**

**Escenario:**
```
Terminal A (Mesero Juan)           Terminal B (Mesero María)
─────────────────────────          ─────────────────────────
Orden #123 - Mesa 5                Orden #123 - Mesa 5
Items: Pizza x1                    Items: Pizza x1

[Agregar Items]
  → Coca-Cola x2
  → Backend: PATCH /orders/123/items
  → Version: 1 → 2
  → Socket.IO: emit("order_updated")
                                   ← Socket.IO: receive("order_updated")
                                   ← refreshOrder()
Items: Pizza x1, Coca-Cola x2      Items: Pizza x1, Coca-Cola x2 ✅
```

**Implementación:**
```kotlin
// OrderDetailViewModel.kt
init {
    collectSocketEvents()
}

private fun collectSocketEvents() {
    viewModelScope.launch {
        socketManager.events.collect { event ->
            when (event) {
                is SocketEvent.OrderUpdated -> {
                    if (event.orderId == currentOrderId) {
                        Timber.i("✅ Order updated remotely - refreshing")
                        refreshOrder()
                    }
                }
                is SocketEvent.OrderStatusChanged -> {
                    if (event.orderId == currentOrderId) {
                        Timber.i("✅ Order status changed - refreshing")
                        refreshOrder()
                    }
                }
                else -> {}
            }
        }
    }
}
```

**Ventajas:**
- ✅ Sincronización instantánea entre terminals
- ✅ Evita conflictos de versión (refresh preventivo)
- ✅ Mejor UX para equipos grandes

---

## 📂 Estructura de Archivos Completa

```
app/src/main/java/com/jaac/avoqado_tpv/features/ordering/

├── domain/
│   ├── Order.kt                            ← NEW (Order, OrderItem, enums)
│   ├── OrderRepository.kt                  ← NEW (interface)
│   ├── Product.kt                          ← NEW (Product, MockProducts)
│   ├── Table.kt                            ← EXISTS
│   ├── TableRepository.kt                  ← EXISTS
│   ├── FloorElement.kt                     ← EXISTS
│   └── usecases/
│       ├── GetOrderUseCase.kt              ← NEW
│       └── AddItemsToOrderUseCase.kt       ← NEW

├── data/
│   ├── dto/
│   │   ├── OrderDto.kt                     ← NEW
│   │   ├── OrderItemDto.kt                 ← NEW
│   │   └── AddItemsRequest.kt              ← NEW
│   ├── api/
│   │   ├── OrderApiService.kt              ← NEW
│   │   ├── TableApiService.kt              ← EXISTS
│   │   └── FloorElementApiService.kt       ← EXISTS
│   ├── repository/
│   │   ├── OrderRepositoryImpl.kt          ← NEW
│   │   ├── TableRepositoryImpl.kt          ← EXISTS
│   │   └── FloorElementRepositoryImpl.kt   ← EXISTS
│   └── mappers/
│       └── OrderMappers.kt                 ← NEW

└── presentation/
    ├── orderdetail/
    │   ├── OrderDetailScreen.kt            ← NEW
    │   ├── OrderDetailViewModel.kt         ← NEW
    │   ├── OrderDetailState.kt             ← NEW
    │   └── components/
    │       ├── OrderSummaryCard.kt         ← NEW
    │       ├── OrderItemCard.kt            ← NEW
    │       ├── OrderTotalCard.kt           ← NEW
    │       ├── ProductSelectionModal.kt    ← NEW
    │       ├── ProductCard.kt              ← NEW
    │       ├── ProductCategoryTabs.kt      ← NEW
    │       └── QuantitySelector.kt         ← NEW
    ├── OrderingWelcomeScreen.kt            ← EXISTS
    ├── TableServiceScreen.kt               ← EXISTS (modify navigation)
    ├── TableServiceViewModel.kt            ← EXISTS
    ├── FloorPlanCanvasScreen.kt            ← EXISTS
    └── FloorPlanViewModel.kt               ← EXISTS

core/
├── data/realtime/
│   ├── events/SocketEvent.kt               ← MODIFY (add OrderUpdated)
│   └── SocketManager.kt                    ← MODIFY (add listener)
└── presentation/
    ├── components/                          ← EXISTS (reuse AvoqadoComponents)
    └── navigation/
        ├── NavRoute.kt                     ← MODIFY (add OrderDetail)
        └── AppNavigation.kt                ← MODIFY (add route)
```

**Resumen:**
- **Nuevos:** 22 archivos
- **Modificados:** 6 archivos
- **Total:** ~28 archivos
- **Líneas estimadas:** 2,500-3,000 LOC

---

## ✅ Criterios de Éxito (Acceptance Criteria)

### Funcionales

1. ✅ Usuario toca mesa en TableServiceScreen → Navega a OrderDetailScreen
2. ✅ Pantalla carga orden del backend con loading overlay
3. ✅ Muestra header con: Número de orden, mesa, covers, waiter
4. ✅ Lista todos los items actuales con cantidad, precio unitario, subtotal
5. ✅ Muestra totales: Subtotal, IVA, Total
6. ✅ Botón "Agregar Items" abre modal con 15 productos mock en grid
7. ✅ CategoryTabs filtran productos por Bebidas/Comidas/Postres
8. ✅ Usuario selecciona producto → Muestra quantity selector
9. ✅ Usuario confirma → Item agregado a orden vía backend
10. ✅ Loading overlay durante `addItems` con mensaje "Agregando items..."
11. ✅ Si version conflict → Auto-refresh silencioso + retry automático
12. ✅ Terminal B recibe Socket.IO event → Refresca orden automáticamente
13. ✅ Botón eliminar item → Confirmación → Llama backend
14. ✅ Botón "Tomar Pago" → Navega a PaymentScreen (orderId pendiente implementar)
15. ✅ Botón back → Regresa a TableServiceScreen

### No Funcionales

1. ✅ UI responsiva en PAX A80 (1024x600), A920 (1280x720)
2. ✅ Spacing consistente usando `ResponsiveScaffold` + `LocalResponsiveSizes`
3. ✅ Manejo de errores user-friendly (sin tecnicismos)
4. ✅ Loading states en todas las operaciones async
5. ✅ Empty states cuando orden sin items
6. ✅ Confirmaciones antes de acciones destructivas
7. ✅ Logs técnicos con Timber para debugging
8. ✅ Zero crashes/ANRs
9. ✅ Smooth animations (no flash screens)
10. ✅ Offline resilience (mostrar último estado conocido)

### Testing

1. ✅ Unit tests para GetOrderUseCase (éxito, error 404, error 500)
2. ✅ Unit tests para AddItemsToOrderUseCase (éxito, version conflict, network error)
3. ✅ Unit tests para OrderMappers (DTO → Domain conversión correcta)
4. ✅ Integration test: assignTable → OrderScreen → addItems → Socket.IO event
5. ✅ Manual test en dispositivo físico PAX

---

## 🚀 Próximos Pasos (Post-MVP - Fase 8+)

### Fase 8: Backend Real de Productos (2-3 días)
- ProductRepository + ProductApiService
- `GET /tpv/venues/{venueId}/products`
- `GET /tpv/venues/{venueId}/categories`
- Reemplazar MockProducts con datos reales
- Imágenes reales en ProductCard (URLs de productos)

### Fase 9: "Send to Kitchen" (1 día)
- Botón "Enviar a Cocina" en OrderDetailScreen
- `PATCH /tpv/venues/{venueId}/orders/{orderId}/kitchen-status`
- Socket.IO event `kitchen_status_changed`
- Notificaciones push a app de cocina

### Fase 10: Modificadores (2-3 días)
- UI para seleccionar modificadores (Extra queso, Sin cebolla, etc.)
- ModifierGroup, Modifier domain models
- Backend: `GET /tpv/venues/{venueId}/products/{productId}/modifiers`
- Precio adicional por modificadores

### Fase 11: Split Items (3-4 días)
- Dividir cuenta entre múltiples pagos
- PaymentAllocation model (qué items se pagaron en qué payment)
- UI: Seleccionar items a pagar
- Backend: `POST /tpv/venues/{venueId}/orders/{orderId}/split`

### Fase 12: Discounts & Promotions (2-3 días)
- Aplicar descuentos a orden completa o items individuales
- Discount domain model
- UI: Modal de descuentos
- Backend: `PATCH /tpv/venues/{venueId}/orders/{orderId}/discount`

---

## ⏱️ Estimación Total

**Duración:** 5-7 días de desarrollo

**Breakdown:**
- Día 1: Domain + Data layers (7 horas)
- Día 2: Socket.IO + ViewModel (5 horas)
- Día 3-4: UI básica (8 horas)
- Día 4-5: Product selection (5 horas)
- Día 6-7: Navigation + Polish + Testing (4 horas)

**Total:** ~29 horas de desarrollo

**Archivos:**
- Nuevos: 22 archivos
- Modificados: 6 archivos
- LOC estimado: 2,500-3,000 líneas

**Complejidad:** Media-Alta
- Socket.IO integration
- Optimistic concurrency control
- Multi-terminal sync
- Responsive UI

---

## 📚 Referencias

**Documentación:**
- [CLAUDE.md](./CLAUDE.md) - Patrones de arquitectura
- [SOCKET_IO_IMPLEMENTATION.md](./SOCKET_IO_IMPLEMENTATION.md) - Socket.IO patterns
- [UI_RESPONSIVE_GUIDE.md](./UI_RESPONSIVE_GUIDE.md) - Responsive UI patterns
- [PAYMENT_RECONCILIATION.md](./PAYMENT_RECONCILIATION.md) - Payment integration

**Backend Endpoints:**
- `GET /tpv/venues/{venueId}/orders/{orderId}` - Get order details
- `PATCH /tpv/venues/{venueId}/orders/{orderId}/items` - Add items to order
- Socket.IO event: `order_updated`

**Similar Features (Reference):**
- PaymentViewModel.kt - Socket.IO integration pattern
- TableServiceViewModel.kt - CRUD operations pattern
- FloorPlanCanvasScreen.kt - Responsive UI pattern

---

**Documento creado:** 2025-01-15
**Última actualización:** 2025-01-15
**Status:** ⏸️ Pausado - Primero resolver bugs de FloorPlan
**Siguiente paso:** Fix NPE en creación de walls + elementos no se agregan
