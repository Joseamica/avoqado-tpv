# Local-First Sync Patterns - Common Pitfalls

> **CRITICAL**: Este documento describe errores comunes al trabajar con datos locales + backend. Revisar ANTES de modificar cualquier código de sync, cache, o carga de datos.

---

## El Problema Principal: Campos Solo-Locales

Algunos campos existen SOLO en Room DB y NO en el backend:

| Campo | Ubicacion | Por que es local |
|-------|-----------|------------------|
| `sentToKitchenAt` | `DraftOrderItemEntity` | Backend no trackea impresiones de cocina |
| `syncStatus` | `DraftOrderEntity/ItemEntity` | Estado de sync es solo relevante localmente |
| `isServerCreated` | `DraftOrderEntity/ItemEntity` | Flag para saber si tiene CUID del server |

**Cuando el backend devuelve datos, estos campos vienen como `null` o valores default.**

---

## Anti-Patron #1: Usar Datos del Backend Directamente

```kotlin
// ❌ MAL: Backend order tiene sentToKitchenAt = null para todos los items
val backendOrder = orderRepository.getOrder(venueId, orderId)
_state.value = MenuState.Success(backendOrder)  // PIERDE timestamps locales!

// ✅ BIEN: Cachear primero (preserva campos locales), luego cargar desde DB local
orderSyncCoordinator.cacheBackendOrder(backendOrder)
val mergedOrder = orderSyncCoordinator.getLocalOrder(orderId) ?: backendOrder
_state.value = MenuState.Success(mergedOrder)  // PRESERVA timestamps!
```

---

## Anti-Patron #2: Sobrescribir Cache Sin Preservar

```kotlin
// ❌ MAL: Inserta directamente, pierde datos locales
suspend fun cacheBackendOrder(order: Order) {
    val entities = order.items.toEntities()
    draftOrderItemDao.insertAll(entities)  // Sobrescribe sentToKitchenAt!
}

// ✅ BIEN: Preservar campos locales antes de insertar
suspend fun cacheBackendOrder(order: Order) {
    // 1. Leer valores locales existentes
    val existingItems = draftOrderItemDao.getAllItemsByOrder(order.id)
    val localTimestamps = existingItems.associate { it.id to it.sentToKitchenAt }

    // 2. Convertir y PRESERVAR campos locales
    val entities = order.items.toEntities().map { item ->
        val localTimestamp = localTimestamps[item.id]
        if (localTimestamp != null) {
            item.copy(sentToKitchenAt = localTimestamp)
        } else {
            item
        }
    }

    draftOrderItemDao.insertAll(entities)
}
```

---

## Anti-Patron #3: Sync Event Sobrescribe State

```kotlin
// ❌ MAL: Sync event reemplaza state con datos del backend
fun handleSyncEvent(event: SyncEvent.Synced) {
    val backendOrder = fetchFromBackend(event.orderId)
    _state.value = MenuState.Success(backendOrder)  // PIERDE campos locales!
}

// ✅ BIEN: Cargar desde DB local despues del sync
fun handleSyncEvent(event: SyncEvent.Synced) {
    val localOrder = orderSyncCoordinator.getLocalOrder(event.orderId)
    if (localOrder != null) {
        _state.value = MenuState.Success(localOrder)  // PRESERVA campos locales!
    }
}
```

---

## Checklist: Antes de Modificar Codigo de Sync/Cache

Preguntate SIEMPRE:

- [ ] **¿Este codigo carga datos del backend?** → Verificar si hay campos solo-locales que se perderan
- [ ] **¿Este codigo actualiza el cache?** → Verificar que preserve `sentToKitchenAt` y otros campos locales
- [ ] **¿Este codigo actualiza `_state`?** → Verificar que use datos del DB local, no del backend directamente
- [ ] **¿Este codigo maneja sync events?** → Verificar que recargue desde DB local despues del evento
- [ ] **¿Order depende de datos que cambian en backend (payments, splitType)?** → Escuchar Socket events relevantes (`PaymentCompleted`)

---

## Campos Locales Actuales (Actualizar cuando se agreguen nuevos)

### DraftOrderItemEntity
- `sentToKitchenAt: Long?` - Timestamp de cuando se imprimio ticket de cocina
- `syncStatus: String` - SYNCED, PENDING, SYNCING, DELETED
- `isServerCreated: Boolean` - True si tiene CUID del backend

### DraftOrderEntity
- `syncStatus: String` - SYNCED, PENDING, SYNCING
- `isServerCreated: Boolean` - True si tiene CUID del backend

---

## Flujo Correcto: Load Order

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Intentar cargar desde Room DB (LOCAL-FIRST)              │
│    val localOrder = getLocalOrder(orderId)                  │
│    if (localOrder != null) return localOrder  ✅            │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼ (no existe localmente)
┌─────────────────────────────────────────────────────────────┐
│ 2. Fetch desde backend                                      │
│    val backendOrder = orderRepository.getOrder(...)         │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Cachear CON PRESERVACION de campos locales               │
│    cacheBackendOrder(backendOrder)  // Preserva timestamps  │
└─────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Cargar desde DB local (tiene campos preservados)         │
│    val mergedOrder = getLocalOrder(orderId) ?: backendOrder │
│    return mergedOrder  ✅                                   │
└─────────────────────────────────────────────────────────────┘
```

---

## Anti-Patron #4: Order No Se Actualiza Despues de Pago

```kotlin
// ❌ MAL: loadOrder() tiene early return si order ya esta cargado
fun loadOrder(orderId: String) {
    val currentState = _state.value
    if (currentState is MenuState.Success) {
        return  // NUNCA recarga! lastSplitType sigue siendo null
    }
}

// ✅ BIEN: Escuchar PaymentCompleted para refrescar orden
private fun listenToSocketEvents() {
    viewModelScope.launch {
        socketManager.events
            .filter { it is SocketEvent.PaymentCompleted }
            .collect { event ->
                val paymentEvent = event as SocketEvent.PaymentCompleted
                val currentState = _state.value

                if (currentState is MenuState.Success &&
                    paymentEvent.orderId == currentState.order.id) {
                    // Refrescar desde backend para obtener lastSplitType actualizado
                    refreshOrderFromBackend(paymentEvent.orderId)
                }
            }
    }
}
```

---

## Historial de Bugs Causados por Este Patron

| Fecha | Bug | Causa | Fix |
|-------|-----|-------|-----|
| 2024-11-28 | Items pierden estado "impreso" al volver a mesa | `cacheBackendOrder` no preservaba `sentToKitchenAt` | Leer timestamps existentes antes de insertar |
| 2024-11-28 | Items pierden estado despues de cache | `loadOrder` usaba `backendOrder` directamente | Cargar desde DB local despues de cache |
| 2024-11-28 | Split options no se restringen despues de pago | `loadOrder` no recarga si order ya esta en state | Escuchar `PaymentCompleted` y refrescar orden |

---

---

## SSOT Migration (Single Source of Truth) - 2025-12-11

### El Problema Original: Dual Source of Truth

Antes de la migración, teníamos DOS fuentes de verdad que podían diverger:

```
┌─────────────────────────────────────────────────────────────┐
│                    PROBLEMA CENTRAL                         │
├─────────────────────────────────────────────────────────────┤
│  MenuViewModel._state.value.order  ←→  Room DB Order        │
│         (StateFlow in memory)           (SQLite)            │
│                                                             │
│  ⚠️ Estos DOS estados pueden diverger en cualquier momento │
└─────────────────────────────────────────────────────────────┘
```

**Bug típico:**
1. Usuario abre mesa → `_state = Success(order: local_xxx)`
2. Debounce sync completa → Room DB cambia `local_xxx` → `cmj123...`
3. Usuario hace click → `_state.value.order.id` aún es `local_xxx` ❌
4. API call falla: "Order not found"

**Nuevo bug observado (2026-01-22):**
1. Sync reemplaza `local_xxx` → `cmj123...` (ID replacement)
2. Usuario agrega item en ese instante
3. Insert falla con **FK constraint** (orderId ya no existe)

**Mitigación:**
- Resolver `orderId` antes de escribir en Room (add/update/remove)
- Si el ID local ya fue reemplazado, usar el ID server

### La Solución: Room como Single Source of Truth

Siguiendo las [recomendaciones oficiales de Google](https://developer.android.com/topic/architecture):

```
┌─────────────────────────────────────────────────────────────┐
│                    ARQUITECTURA SSOT                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Room DB (SQLite)  ──────►  Flow<Order>  ──────►  UI        │
│       ↑                         ↑                           │
│       │                         │                           │
│  OrderSyncCoordinator      MenuViewModel                    │
│  (escribe a DB)           (solo observa)                    │
│                                                             │
│  ✅ Room emite Flow → UI se actualiza automáticamente       │
└─────────────────────────────────────────────────────────────┘
```

### Archivos Modificados

| Archivo | Cambio |
|---------|--------|
| `DraftOrderDao.kt` | +`observeOrderWithItems()` - Flow de Room |
| `DraftOrderWithItems.kt` | NUEVO - Relación Room (Order + Items) |
| `OrderSyncCoordinator.kt` | +`observeOrder()` - Flow que transforma a dominio |
| `MenuViewModel.kt` | +`_currentOrderId` + `collectOrderFromRoom()` |

### Código Clave

**1. DAO expone Flow:**
```kotlin
// DraftOrderDao.kt
@Transaction
@Query("SELECT * FROM draft_orders WHERE id = :orderId")
fun observeOrderWithItems(orderId: String): Flow<DraftOrderWithItems?>
```

**2. Coordinator transforma a dominio:**
```kotlin
// OrderSyncCoordinator.kt
fun observeOrder(orderId: String): Flow<Order> {
    return draftOrderDao.observeOrderWithItems(orderId)
        .filterNotNull()
        .map { withItems ->
            withItems.order.toDomain(
                withItems.items.filter { it.syncStatus != DELETED }
            )
        }
        .distinctUntilChanged()
}
```

**3. ViewModel observa via Flow:**
```kotlin
// MenuViewModel.kt
private val _currentOrderId = MutableStateFlow<String?>(null)

private fun collectOrderFromRoom() {
    viewModelScope.launch {
        _currentOrderId
            .filterNotNull()
            .flatMapLatest { orderId ->
                orderSyncCoordinator.observeOrder(orderId)
            }
            .collect { order ->
                _state.update { current ->
                    if (current is MenuState.Success) {
                        current.copy(order = order)
                    } else {
                        MenuState.Success(order)
                    }
                }
            }
    }
}
```

**4. Sync actualiza `_currentOrderId`:**
```kotlin
// En collectSyncEvents()
is SyncEvent.Synced -> {
    val previousId = _currentOrderId.value
    if (previousId != event.orderId) {
        _currentOrderId.value = event.orderId  // Flow se re-suscribe automáticamente
    }
}
```

### Logs de Diagnóstico

Buscar estos patrones en logcat:
```
🔄 [SSOT] _currentOrderId set to local_xxx
🔄 [SSOT] Observing order from Room | orderId=local_xxx
🔄 [SSOT] Room emitted order update | id=local_xxx | items=3
✅ [SSOT] State updated from Room | orderId=local_xxx
🔄 [SSOT] Order ID changed after sync | old=local_xxx → new=cmjxxx
```

### Optimistic Updates (Conservados)

Los updates manuales en `addItem()`, `removeItem()`, etc. se mantienen para feedback instantáneo (0ms). El Flow proporciona consistencia (IDs correctos después de sync).

**Patrón "Belt & Suspenders":**
- Optimistic update → UI responde instantáneamente
- Room Flow → Garantiza consistencia de datos

### Testing Verificado (2025-12-11)

| Test | Estado |
|------|--------|
| Quick order → add items → sync → ID changes | ✅ PASS |
| Table order → Flow emits updates | ✅ PASS |
| Add customer to local order → sync | ✅ PASS |
| Payment with correct orderId | ✅ PASS |

---

**Ultima actualizacion:** 2025-12-11
