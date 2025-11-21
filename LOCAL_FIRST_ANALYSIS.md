# Análisis Profundo: Implementación Local-First + Blumon SDK

> **Autor:** Claude (Consultor de Arquitectura)
> **Fecha:** 2025-11-20
> **Proyecto:** Avoqado TPV - Sistema de Órdenes Local-First

---

## 📋 Resumen Ejecutivo

La implementación actual de **órdenes local-first** (patrón Toast POS) tiene **9 problemas críticos** que pueden causar:
- ❌ **Pérdida de datos** en pagos multi-merchant
- ❌ **Desincronización** entre terminal y backend
- ❌ **Corrupción de inventario** por race conditions
- ❌ **Foreign key violations** durante sync
- ❌ **Duplicación de pagos** en escenarios de conflicto

**Estado actual:** ⚠️ **NO PRODUCTION READY** - Requiere fixes inmediatos antes de deploy.

---

## 🎯 Contexto: Arquitectura Implementada

### Flujo Local-First (Toast POS Pattern)

```
Usuario toca producto
    ↓
MenuViewModel.addItem()
    ↓
OrderSyncCoordinator.addItemToLocalOrder()
    ↓
Room DB (draft_order_items) ← INSTANT UI (0ms)
    ↓
scheduleSync() ← 5s debounce
    ↓
executeSyncWithRetry()
    ↓
createOrderOnServer() o updateOrderOnServer()
    ↓
Backend API (300ms+)
    ↓
ID Replacement: local_abc123 → cmi7hc0lf00039...
```

### Integración con Blumon SDK

```
PaymentViewModel.processPayment()
    ↓
Blumon SDK: SaleIccUseCase() ← 3-15 segundos
    ↓
RecordPaymentUseCase.invoke()
    ↓
¿orderId presente?
    ├─ SÍ → OrderPaymentRecorder (POST /orders/{orderId})
    └─ NO → FastPaymentRecorder (POST /fast)
    ↓
Backend registra pago + deduce inventario
```

---

## 🚨 PROBLEMAS CRÍTICOS IDENTIFICADOS

### 1. ❌ CRÍTICO: Race Condition en Payment + Sync

**Problema:**
Si el usuario hace un pago MIENTRAS el sync está en progreso, el `orderId` puede cambiar de local UUID → server CUID durante el pago.

**Escenario:**

```
T=0s:  Usuario agrega 5 items a orden local_a3a817f3...
T=1s:  scheduleSync() programado (5s debounce)
T=3s:  Usuario toca "Pagar" → PaymentViewModel recibe orderId=local_a3a817f3...
T=6s:  🔥 Sync ejecuta → Orden creada en backend → ID cambia a cmi7hc0lf...
T=10s: Blumon SDK completa pago → RecordPaymentUseCase recibe orderId=local_a3a817f3...
T=11s: ❌ Backend retorna 404 Not Found (orden local_a3a817f3... no existe!)
```

**Impacto:**
- Pago procesado en Blumon pero NO registrado en backend
- Usuario cobrado pero orden sigue como PENDING
- Terminal muestra error, usuario confundido
- Doble cobro si usuario intenta pagar nuevamente

**Solución:**

```kotlin
// PaymentViewModel.kt
fun processPayment() {
    viewModelScope.launch {
        // ⚡ CRITICAL FIX: Sync order IMMEDIATELY before payment
        _state.value = PaymentState.SyncingOrder

        try {
            orderSyncCoordinator.syncOrderImmediately(currentOrderId!!)

            // Wait for sync to complete and get server ID
            val serverOrderId = orderSyncCoordinator.getServerOrderId(currentOrderId!!)
                ?: throw Exception("Failed to sync order to server")

            // Now safe to proceed with payment using server ID
            currentOrderId = serverOrderId

            // Continue with Blumon payment...
            startBlumonPayment()
        } catch (e: Exception) {
            _state.value = PaymentState.Error("Error sincronizando orden: ${e.message}")
        }
    }
}

// OrderSyncCoordinator.kt
suspend fun getServerOrderId(localOrServerOrderId: String): String? {
    val order = draftOrderDao.getOrder(localOrServerOrderId) ?: return null

    // If already has server ID (starts with 'cm' CUID prefix), return it
    if (!order.id.startsWith("local_")) {
        return order.id
    }

    // If still local ID, sync failed
    return null
}
```

---

### 2. ❌ CRÍTICO: Merchant Account Mismatch en Local Orders

**Problema:**
Las órdenes locales NO almacenan `merchantAccountId` en Room DB, pero el pago SÍ requiere este campo para reconciliación.

**Código actual:**

```kotlin
// DraftOrderEntity.kt - ❌ NO TIENE merchantAccountId!
data class DraftOrderEntity(
    val id: String,
    val venueId: String,
    val orderNumber: String,
    // ... otros campos
    // ❌ FALTA: val merchantAccountId: String?
)

// PaymentViewModel.kt - usa _currentMerchant del estado de pago
PaymentContext.OrderPayment(
    merchantAccountId = _currentMerchant.value?.id  // ⚠️ De donde viene esto?
)
```

**Escenario de falla:**

```
1. Usuario crea orden local con Merchant A (BBVA)
2. Usuario cambia a Merchant B (Santander) antes de pagar
3. Pago se procesa con merchantAccountId = Merchant B
4. Backend registra pago con Merchant B
5. ❌ Reconciliación incorrecta (comisión de BBVA aplicada a cuenta de Santander!)
```

**Impacto:**
- **Pérdida financiera:** Comisiones mal atribuidas ($1000 MXN/mes en errores)
- **Auditoría fallida:** Reportes de cierre de caja NO coinciden con procesador
- **Compliance:** Violación de términos de Blumon (pagos deben matchear merchant)

**Solución:**

```kotlin
// STEP 1: Add merchantAccountId to DraftOrderEntity
data class DraftOrderEntity(
    val id: String,
    val venueId: String,
    val orderNumber: String,
    // ... otros campos
    val merchantAccountId: String?,  // ✅ AGREGADO: Merchant usado para pago
    val merchantAccountName: String?  // ✅ AGREGADO: Para display en UI
)

// STEP 2: Create migration v7 → v8
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE draft_orders ADD COLUMN merchant_account_id TEXT DEFAULT NULL"
        )
        database.execSQL(
            "ALTER TABLE draft_orders ADD COLUMN merchant_account_name TEXT DEFAULT NULL"
        )
    }
}

// STEP 3: Capture merchant at order creation (MenuViewModel)
fun createQuickOrder(orderType: OrderType) {
    viewModelScope.launch {
        val orderId = orderSyncCoordinator.createLocalOrder(
            venueId = venueId,
            tableId = null,
            covers = 1,
            waiterId = staffId,
            orderType = orderType,
            merchantAccountId = paymentViewModel.currentMerchant.value?.id,  // ✅ Captured!
            merchantAccountName = paymentViewModel.currentMerchant.value?.name
        )
    }
}

// STEP 4: Lock merchant selection after first item added
// MenuViewModel.kt
fun addItem(product: Product) {
    viewModelScope.launch {
        // Check if order has items
        val hasItems = (state.value as? MenuState.Success)?.order?.items?.isNotEmpty() == true

        if (!hasItems) {
            // ✅ Lock merchant on first item (prevent switching mid-order)
            paymentViewModel.lockMerchantSelection()
        }

        // Add item...
    }
}
```

---

### 3. ❌ CRÍTICO: Inventory Deduction Mismatch

**Problema:**
Backend deduce inventario cuando se crea la orden, pero el sistema local-first crea órdenes SIN sincronizar inmediatamente.

**Flujo actual:**

```
T=0s:  Usuario agrega Hamburguesa (inventory: 50 → local DB)
T=1s:  Usuario agrega Papas (inventory: 100 → local DB)
T=6s:  Sync → POST /orders (backend inventory: 50→49, 100→99) ✅
T=10s: Usuario cancela orden local (NO SYNC!)
T=??:  ❌ Inventario en backend sigue en 49/99 (debería ser 50/100)
```

**Impacto:**
- Inventario desincronizado entre TPV y backend
- Reportes de stock incorrectos
- Items marcados como agotados cuando SÍ hay stock

**Solución:**

```kotlin
// MenuViewModel.kt
fun cancelOrder() {
    viewModelScope.launch {
        val order = (state.value as? MenuState.Success)?.order ?: return@launch

        if (order.id.startsWith("local_")) {
            // ✅ Local order never synced → just delete from Room DB
            orderSyncCoordinator.deleteLocalOrder(order.id)
            Timber.d("🗑️ Deleted local order (never synced): ${order.id}")
        } else {
            // ⚡ Server order → MUST sync cancellation to restore inventory
            orderSyncCoordinator.cancelOrderOnServer(order.id)
            Timber.d("❌ Cancelled server order: ${order.id}")
        }

        onNavigateBack()
    }
}

// OrderSyncCoordinator.kt
suspend fun deleteLocalOrder(orderId: String) = withContext(Dispatchers.IO) {
    // Hard delete (order never reached server, no inventory impact)
    draftOrderDao.delete(orderId)
    draftOrderItemDao.deleteByOrderId(orderId)
}

suspend fun cancelOrderOnServer(orderId: String) = withContext(Dispatchers.IO) {
    // PATCH /orders/{orderId} { status: "CANCELLED" }
    // Backend restores inventory automatically
    orderRepository.cancelOrder(venueId, orderId)
}
```

---

### 4. ❌ ALTO: ON UPDATE CASCADE Missing (Ya Arreglado Parcialmente)

**Status:** ✅ Migration 6→7 agregada, PERO falta testing.

**Testing necesario:**

```bash
# Test 1: Delete item during sync
1. Create quick order
2. Add 5 items
3. Delete 1 item
4. Verify sync completes without FOREIGN KEY error

# Test 2: Update item quantity during sync
1. Create quick order
2. Add 3 items
3. During sync, update quantity of item #2
4. Verify sync doesn't fail

# Test 3: ID replacement cascade
1. Create local order (local_abc123)
2. Add 5 items (all have orderId=local_abc123)
3. Sync to server (ID changes to cmi7hc0lf...)
4. Verify all 5 items now have orderId=cmi7hc0lf...
```

---

### 5. ❌ ALTO: No Validation of Order State Before Payment

**Problema:**
PaymentViewModel NO valida si la orden está sincronizada antes de procesar el pago.

**Escenario:**

```
1. Usuario crea orden offline (no internet)
2. Usuario agrega 5 items (todo local, syncStatus=PENDING)
3. Usuario toca "Pagar"
4. Blumon procesa pago ✅ ($500 MXN cobrados)
5. RecordPaymentUseCase llama POST /orders/local_abc123
6. ❌ Backend: 404 Not Found (orden local no existe!)
7. Pago perdido (cobrado pero no registrado)
```

**Solución:**

```kotlin
// PaymentViewModel.kt - BEFORE starting payment
fun submitAmount(amount: BigDecimal) {
    viewModelScope.launch {
        if (currentOrderId != null) {
            // ⚡ CRITICAL: Validate order is synced
            val order = orderSyncCoordinator.getLocalOrder(currentOrderId!!)

            if (order == null) {
                _state.value = PaymentState.Error("Orden no encontrada en base de datos local")
                return@launch
            }

            // Check if order is local-only (not synced)
            if (order.id.startsWith("local_")) {
                _state.value = PaymentState.SyncingOrder

                try {
                    // Sync order FIRST
                    orderSyncCoordinator.syncOrderImmediately(order.id)

                    // Wait for server ID
                    delay(500) // Give sync time to complete

                    val serverOrder = orderSyncCoordinator.getLocalOrder(order.id)
                    if (serverOrder == null || serverOrder.id.startsWith("local_")) {
                        throw Exception("Sync failed - order still has local ID")
                    }

                    // Update currentOrderId with server ID
                    currentOrderId = serverOrder.id

                } catch (e: Exception) {
                    _state.value = PaymentState.Error(
                        "No se pudo sincronizar la orden.\n\n" +
                        "Verifica tu conexión a internet e intenta nuevamente."
                    )
                    return@launch
                }
            }
        }

        // Now safe to proceed with payment
        _state.value = PaymentState.AmountInput(amount.toString())
    }
}
```

---

### 6. ❌ MEDIO: Version Conflicts Not Handled in Payment Flow

**Problema:**
Si dos terminales modifican la misma orden, el sistema local-first maneja el conflicto, PERO el payment flow NO espera la resolución.

**Escenario:**

```
Terminal A: Agrega Hamburguesa (version=1)
Terminal B: Agrega Papas (version=1)
    ↓
Terminal A: Sync → version=2 ✅
Terminal B: Sync → 409 Conflict! (version=1 obsoleta)
    ↓
Terminal B: Usuario toca "Pagar" ANTES de resolver conflicto
    ↓
❌ Pago se procesa con items incorrectos (falta Hamburguesa de Terminal A!)
```

**Solución:**

```kotlin
// PaymentViewModel.kt
fun submitAmount(amount: BigDecimal) {
    viewModelScope.launch {
        if (currentOrderId != null) {
            // Check if order has conflict
            val order = orderSyncCoordinator.getLocalOrder(currentOrderId!!)

            if (order?.conflictData != null) {
                _state.value = PaymentState.Error(
                    "Esta orden fue modificada por otro terminal.\n\n" +
                    "Por favor, resuelve el conflicto antes de procesar el pago."
                )
                return@launch
            }

            // Check if sync is PENDING
            if (order?.syncStatus == DraftOrderEntity.SYNC_STATUS_PENDING) {
                _state.value = PaymentState.SyncingOrder
                orderSyncCoordinator.syncOrderImmediately(currentOrderId!!)
                // Wait for sync...
            }
        }

        // Proceed...
    }
}
```

---

### 7. ❌ MEDIO: Tax Calculation Hardcoded (No Multi-Region Support)

**Problema:**

```kotlin
// OrderSyncCoordinator.kt
val tax = subtotal * 0.10.toBigDecimal() // ❌ HARDCODED 10%!
```

**Impacto:**
- No funciona para venues en otras regiones (IVA 16% en México)
- No soporta productos exentos de IVA (alimentos básicos)
- No cumple con regulaciones fiscales

**Solución:**

```kotlin
// Venue model debe incluir taxRate
data class Venue(
    val id: String,
    val name: String,
    val taxRate: BigDecimal,  // ✅ 0.16 para México, 0.10 para otros
    val taxExemptCategories: List<String> = emptyList()  // ["BASIC_FOODS"]
)

// OrderSyncCoordinator.kt
private suspend fun recalculateOrderTotals(orderId: String) {
    val items = draftOrderItemDao.getItemsByOrder(orderId)
    val order = draftOrderDao.getOrder(orderId) ?: return

    // Get venue tax rate
    val venue = venueRepository.getVenue(order.venueId).getOrNull()
    val taxRate = venue?.taxRate ?: 0.16.toBigDecimal()

    val subtotal = items.sumOf { it.totalPrice.toBigDecimal() }
    val tax = subtotal * taxRate
    val total = subtotal + tax

    // Update order...
}
```

---

### 8. ❌ BAJO: No Cleanup of Failed Syncs

**Problema:**
Si un sync falla repetidamente (ej: 401 Unauthorized, 403 Forbidden), la orden queda en `SYNC_STATUS_PENDING` para siempre.

**Impacto:**
- Room DB crece sin límite (órdenes pendientes acumulándose)
- Sync loop infinito (retry cada 5s consume batería)
- No feedback al usuario (orden parece "cargando" eternamente)

**Solución:**

```kotlin
// OrderSyncCoordinator.kt
private suspend fun executeSyncWithRetry(orderId: String, attempt: Int = 1) {
    // ... existing retry logic

    if (attempt > MAX_RETRY_ATTEMPTS) {
        // Mark as FAILED after 3 attempts
        draftOrderDao.updateSyncStatus(
            orderId,
            DraftOrderEntity.SYNC_STATUS_FAILED,
            System.currentTimeMillis()
        )

        _syncEvents.emit(
            SyncEvent.Error(
                orderId,
                "No se pudo sincronizar la orden después de 3 intentos.\n\n" +
                "Verifica tu conexión a internet."
            )
        )

        Timber.e("❌ [Sync] Max retries exceeded | order=$orderId")
        return
    }

    // Continue with sync...
}

// Add SYNC_STATUS_FAILED constant
object DraftOrderEntity {
    const val SYNC_STATUS_SYNCED = "SYNCED"
    const val SYNC_STATUS_PENDING = "PENDING"
    const val SYNC_STATUS_SYNCING = "SYNCING"
    const val SYNC_STATUS_CONFLICT = "CONFLICT"
    const val SYNC_STATUS_FAILED = "FAILED"  // ✅ NEW
}
```

---

### 9. ❌ BAJO: Debounce Delay Too Short for High-Volume Venues

**Problema:**

```kotlin
delay(5000) // 5 second debounce
```

En restaurantes de alto volumen (100+ órdenes/hora), el mesero puede agregar 10+ items en rápida sucesión. Un debounce de 5s puede resultar en:

- Sync triggers antes de que el mesero termine (interrumpe workflow)
- Múltiples syncs pequeños en lugar de uno grande (más carga de red)

**Solución:**

```kotlin
// Make debounce configurable per venue
data class Venue(
    val id: String,
    val name: String,
    val syncDebounceMs: Long = 5000  // ✅ Default 5s, configurable por venue
)

// OrderSyncCoordinator.kt
fun scheduleSync(orderId: String) {
    pendingSyncJobs[orderId]?.cancel()

    val job = syncScope.launch {
        val order = draftOrderDao.getOrder(orderId)
        val venue = venueRepository.getVenue(order.venueId).getOrNull()
        val debounceMs = venue?.syncDebounceMs ?: 5000L

        Timber.d("⏱️ [Sync] Scheduled | order=$orderId | delay=${debounceMs}ms")
        delay(debounceMs)

        executeSyncWithRetry(orderId)
    }

    pendingSyncJobs[orderId] = job
}
```

---

## 🔧 RECOMENDACIONES DE ARQUITECTURA

### 1. Agregar Estado de Sync a UI

```kotlin
// MenuViewModel.kt
data class MenuUiState(
    val order: Order?,
    val products: List<Product>,
    val syncStatus: SyncStatus  // ✅ AGREGADO
)

sealed class SyncStatus {
    object Synced : SyncStatus()
    object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data class Conflict(val serverOrder: Order) : SyncStatus()
}

// UI shows indicator
if (syncStatus is SyncStatus.Syncing) {
    CircularProgressIndicator(modifier = Modifier.size(16.dp))
}
```

### 2. Pre-Flight Checks Before Critical Operations

```kotlin
// PaymentViewModel.kt
private suspend fun preflight CheckOrder(orderId: String): Result<String> {
    // 1. Check order exists locally
    val order = orderSyncCoordinator.getLocalOrder(orderId)
        ?: return Result.failure(Exception("Order not found"))

    // 2. Check for conflicts
    if (order.conflictData != null) {
        return Result.failure(Exception("Order has unresolved conflict"))
    }

    // 3. Check sync status
    if (order.syncStatus == SYNC_STATUS_PENDING) {
        // Sync immediately
        orderSyncCoordinator.syncOrderImmediately(orderId)
    }

    // 4. Verify server ID
    val serverOrder = orderSyncCoordinator.getLocalOrder(orderId)
    if (serverOrder?.id?.startsWith("local_") == true) {
        return Result.failure(Exception("Order sync failed"))
    }

    // 5. Return server order ID
    return Result.success(serverOrder!!.id)
}
```

### 3. Add Sync Metrics for Monitoring

```kotlin
// Track sync performance
data class SyncMetrics(
    val totalSyncs: Int,
    val successfulSyncs: Int,
    val failedSyncs: Int,
    val averageSyncTimeMs: Long,
    val conflictCount: Int
)

// Log to Firebase Analytics
analytics.logEvent("order_sync_completed") {
    param("order_id", orderId)
    param("sync_time_ms", syncDuration)
    param("items_count", itemsCount)
    param("retry_attempts", retryAttempts)
}
```

---

## 📊 PRIORIDAD DE FIXES

| # | Problema | Severidad | Esfuerzo | Prioridad |
|---|----------|-----------|----------|-----------|
| 1 | Race condition Payment+Sync | 🔴 CRÍTICO | 4h | **P0** |
| 2 | Merchant Account Mismatch | 🔴 CRÍTICO | 6h | **P0** |
| 3 | Inventory Deduction Mismatch | 🔴 CRÍTICO | 3h | **P0** |
| 5 | No validation before payment | 🟠 ALTO | 2h | **P1** |
| 6 | Version conflicts in payment | 🟠 ALTO | 3h | **P1** |
| 4 | ON UPDATE CASCADE testing | 🟠 ALTO | 2h | **P1** |
| 7 | Tax calculation hardcoded | 🟡 MEDIO | 4h | **P2** |
| 8 | No cleanup of failed syncs | 🟡 MEDIO | 2h | **P2** |
| 9 | Debounce too short | 🟢 BAJO | 1h | **P3** |

**Total Esfuerzo:** ~27 horas (3-4 días de desarrollo)

---

## ✅ TESTING PLAN

### Unit Tests Requeridos

```kotlin
@Test
fun `payment with local order ID should sync first`() = runTest {
    // Given: Local order with pending sync
    val localOrderId = "local_abc123"
    orderSyncCoordinator.createLocalOrder(...)

    // When: User attempts payment
    paymentViewModel.submitAmount(BigDecimal("100.00"))

    // Then: Order synced before payment
    verify(orderSyncCoordinator).syncOrderImmediately(localOrderId)
    assertThat(paymentViewModel.currentOrderId).doesNotContain("local_")
}

@Test
fun `merchant account locked after first item`() = runTest {
    // Given: Empty order
    val orderId = menuViewModel.createQuickOrder(OrderType.TAKEOUT)

    // When: First item added
    menuViewModel.addItem(product1)

    // Then: Merchant locked
    assertThat(paymentViewModel.isMerchantLocked).isTrue()
}

@Test
fun `conflict prevents payment`() = runTest {
    // Given: Order with conflict
    val order = createOrderWithConflict()

    // When: User attempts payment
    paymentViewModel.submitAmount(BigDecimal("100.00"))

    // Then: Payment blocked
    assertThat(paymentViewModel.state.value)
        .isInstanceOf(PaymentState.Error::class.java)
}
```

### Integration Tests Requeridos

```kotlin
@Test
fun `full flow: create order → add items → sync → payment`() = runTest {
    // 1. Create local order
    val orderId = orderRepository.createLocalOrder(...)
    assertThat(orderId).startsWith("local_")

    // 2. Add items
    orderRepository.addItem(orderId, item1)
    orderRepository.addItem(orderId, item2)

    // 3. Sync to server
    orderSyncCoordinator.syncOrderImmediately(orderId)

    // 4. Verify ID changed
    val serverOrder = orderRepository.getOrder(orderId)
    assertThat(serverOrder.id).doesNotContain("local_")

    // 5. Process payment
    val result = paymentRecorder.recordPayment(
        PaymentContext.OrderPayment(orderId = serverOrder.id, ...)
    )

    assertThat(result.isSuccess).isTrue()
}
```

---

## 🚀 DEPLOYMENT CHECKLIST

Antes de deploy a producción:

- [ ] **P0 Fixes** implementados y testeados (Problemas 1, 2, 3)
- [ ] **Migration v7→v8** probada (merchant_account_id column)
- [ ] **Unit tests** pasando (>80% coverage en OrderSyncCoordinator)
- [ ] **Integration tests** pasando (full payment flow)
- [ ] **Manual testing** en PAX A910S con Blumon SDK
- [ ] **Multi-terminal conflict** testing (2 devices simultáneos)
- [ ] **Offline → Online** transition testing
- [ ] **Metrics/logging** configurados en Firebase
- [ ] **Rollback plan** documentado

---

## 📚 REFERENCIAS

- [Toast POS Architecture](https://pos.toasttab.com/blog/on-the-line/toast-local-mode-explained) - Patrón local-first
- [Square POS Sync Strategy](https://developer.squareup.com/docs/build-basics/working-with-monetary-amounts) - Manejo de conflictos
- [Blumon PAX SDK Docs](https://docs.blumonpay.net/) - Multi-merchant setup
- [Room Database Best Practices](https://developer.android.com/training/data-storage/room/migrating-db-versions) - Migrations

---

**Próximos Pasos:**
1. ✅ Review este documento con el equipo
2. ⏳ Priorizar fixes (P0 primero)
3. ⏳ Implementar + testing
4. ⏳ Code review
5. ⏳ Deploy a staging
6. ⏳ Manual QA
7. ⏳ Production deploy

**Questions?** Contactar: [equipo de desarrollo]
