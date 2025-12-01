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

**Ultima actualizacion:** 2024-11-28
