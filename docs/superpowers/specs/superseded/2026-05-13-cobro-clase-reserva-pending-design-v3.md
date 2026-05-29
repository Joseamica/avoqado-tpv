# Cobro de clase con reserva PENDING desde TPV — v3

Fecha: 2026-05-16
Autor: Jose Antonio Amieva
Status: APPROVED FOR PLANNING
Supersedes: v2 (`2026-05-13-cobro-clase-reserva-pending-design-v2.md`)

## Por qué v3

v2 cerró 10 de 16 hallazgos al 100%. Codex luego encontró **9 hallazgos nuevos** y 6 que solo estaban parcialmente cerrados. v3 los cierra todos y locka contratos pendientes.

Cambios netos respecto a v2:

| Área | v2 | v3 |
|---|---|---|
| **Pseudocódigo TX** | `finalizeReservationsForOrder(tx, ...)` luego llamaba `withSerializableRetry` shadow-eando la tx | El caller (`payment.tpv.service`) hace `withSerializableRetry`. Finalize **recibe** la tx, no la crea |
| **Por unidad / sesión** | `OrderItem.quantity=2 + classSessionId` (no soporta 2 sesiones distintas) | **1 OrderItem por sesión** (regla del cart) + `classSessionId` único por OrderItem |
| **Recovery PAX offline** | Implícito | **Sección dedicada**. Order avanza a `PAID_NEEDS_FINALIZATION`; backend retries; TPV `PendingPaymentEntity` lleva `orderId`+`cartCorrelationId` |
| **Idempotency-Key** | Mencionado | Cableado en `OrderApiService.kt` (header) + tabla `Idempotency` server-side |
| **Capacity sobre Reservation** | `count(*)` | `SUM(partySize)` (consistencia con SlotHold) |
| **SlotHold ownership** | Solo id+session+venue+partySize | **+ `productId` y `cartCorrelationId`** en la tabla. Validar match al consumir |
| **SlotHold expiresAt en consume** | No verificaba | `WHERE expiresAt > NOW()` explícito antes de DELETE |
| **Cart edits mid-cobro** | Indefinido | **Cart se congela** después de `createOrderWithItems`. Cashier debe cancelar order para editar |
| **`Reservation.productIds[]`** | Ignorado | TPV escribe `productId` Y `productIds=[productId]` para no romper consumers existentes. Audit + migración a uno solo = v1.1 |
| **Refund cascade** | Cancela por `orderId` | Cancela solo Reservations cubiertas por el Payment refundeado (via `PaymentAllocation`). Refund parcial NO cascadea v1 (manual) |
| **D14 (PENDING vs CONFIRMED)** | "Investigar después" | **Investigar ANTES del kick-off** (0.5d gate) |
| **Pay-later detection backend** | "Decidir durante impl" | **Flag explícito `payLater: boolean`** en request body |
| **ProductType mapping** | "Propagar" | Enum domain + DTO parsing con fallback a `REGULAR` + Room migration explícita |

---

## Problema (recap)

TPV vende `CLASS` sin consumir capacidad en `ClassSession`. Necesitamos: picker en cobro, hold, reserva real post-pago, asignación posterior desde dashboard, refund cascade correcto, robustez bajo PAX offline.

---

## Lo que YA existe (no reinventar)

(Mismo que v2; añadir:)

| Pieza | Ubicación | Uso v3 |
|---|---|---|
| `PaymentAllocation` (Payment → Order) | `prisma/schema.prisma` | Refund cascade correcto en split payments |
| `PendingPaymentEntity` + `QueuedPayment.toPaymentContext()` que retorna FastPayment | `app/src/main/.../payment/domain/model/QueuedPayment.kt:75`, `PendingPaymentEntity.kt:31` | Extender para preservar `orderId`+`cartCorrelationId`+`paymentType` |
| `OrderApiService.createOrderWithItems` (Retrofit, body-only) | `app/.../ordering/data/api/OrderApiService.kt:180` | Añadir `@Header("Idempotency-Key")` |

---

## Arquitectura corregida

### Flujo end-to-end

```
TPV — Cobrar                           Backend                             Dashboard
─────────────                          ─────────                           ──────────
1. Tap CLASS product
   │
   ▼
2. GET /tpv/.../products/:id/sessions  (venueId scoped)
   ◄── próximas sesiones con capacity efectiva
   │
   ▼
3a. Elige sesión              3b. Pulsa "Omitir"
   │                              │
   POST /tpv/.../slot-holds       │
   {sessionId, partySize=qty,     │
    productId, venueId,           │
    cartCorrelationId}            │
   ◄── {holdId, expiresAt}        │
   │                              │
   ▼                              ▼
4. CartItem guarda {classSessionId|null, slotHoldId|null}
   IMPORTANTE: 1 CartItem = 1 sesión. Si cashier quiere 2 sesiones distintas para
   el mismo producto, debe agregar el producto 2 veces (cart UI lo permite).
   ├── qty++ en item CLASS  → PUT slot-hold (partySize++)
   ├── qty-- en item CLASS  → PUT (partySize--) o DELETE si llega a 0
   ├── cambiar sesión       → DELETE + POST nuevo
   └── quitar item          → DELETE hold
   │
   ▼
5. Cobrar → si CLASS sin customer → CustomerSelectorSheet bloqueante
   │                                │
   │                                └─► [+ Cliente nuevo] → POST /tpv/.../customers
   ▼
6. POST /tpv/.../orders/with-items
   Headers: Idempotency-Key: <cartCorrelationId>
   Body: { ...items, customerId, payLater: false, cartCorrelationId,
           items[].classSessionId, items[].slotHoldId, items[].quantity }
                                            │
                                            ▼
                                  Backend: createOrderWithItems
                                  ├─ Idempotency-Key check (tabla Idempotency)
                                  │   └─ Si existe → retornar Order anterior, no re-crear
                                  ├─ Validaciones (rechaza con 400 si):
                                  │   • CLASS items + customerId NULL
                                  │   • payLater=true + cualquier CLASS item
                                  │   • classSessionId existe pero no pertenece a venueId
                                  │   • classSessionId.productId != item.productId
                                  │   • slotHoldId existe pero no matchea (session/product/venue/partySize)
                                  ├─ Crear Order: status PENDING / paymentStatus PENDING
                                  └─ Crear OrderItem con (classSessionId, slotHoldId, qty)
                                  
                                  CART FREEZE: después de este POST, el cart en TPV
                                  pasa a modo "locked" — no se puede editar.
                                  Para cambiar: el cashier debe CANCELAR la order
                                  (DELETE /orders/:id) y empezar de cero.
   ◄── { orderId, ... }
   │
   ▼
7. Navega a PaymentScreen
   │
   ▼
8. Blumon TPV procesa pago
   │
   ├── Falla local → vuelve al cart (Order queda PENDING, hold expira en 5min, cleanup)
   └── Éxito local ↓
   │
   ▼
9. POST /tpv/.../payments/record
   (cliente: OrderPaymentRecorder ya con orderId + cartCorrelationId en el cuerpo)
                                            │
                                            ▼
                                  Backend: payment.tpv.service.recordPayment
                                  
                                  withSerializableRetry(async tx => {
                                    // (1) Crear Payment + PaymentAllocation
                                    payment = await tx.payment.create({...})
                                    await tx.paymentAllocation.create({paymentId, orderId, amount})
                                    
                                    // (2) Marcar Order PAID / COMPLETED si totalmente pagada
                                    await tx.order.update({...paymentStatus: PAID, status: COMPLETED})
                                    
                                    // (3) FINALIZE RESERVATIONS — recibe tx, NO crea una nueva
                                    await finalizeReservationsForOrder(tx, orderId, venueId, cartCorrelationId)
                                  })
                                  
                                  Si finalize falla por capacity (hold expiró, alguien más
                                  ya tomó el cupo): la TX completa hace ROLLBACK.
                                  
                                  → Order vuelve a PENDING/PENDING
                                  → Payment NO se crea
                                  → TPV recibe 409 "Capacity changed during payment"
                                  → Cashier debe reintentar (elegir otra sesión o omitir)
                                  → Cashier debe REFUND manualmente al cliente vía Blumon
                                    (porque el dinero del cliente YA salió de su tarjeta)
                                  → Documentar como caso edge en runbook
                                  
                                  ALTERNATIVA si el cliente quiere correr el riesgo
                                  de no-rollback: usar Order.status = PAID_NEEDS_FINALIZATION
                                  y un background job que reintente. Decisión durante impl
                                  (sección "investigaciones previas").
                                            │
                                            ▼
                                  finalizeReservationsForOrder(tx, orderId, venueId, cartCorrId):
                                  ├─ Para cada OrderItem con classSessionId IS NOT NULL:
                                  │   ├─ SELECT ClassSession FOR UPDATE WHERE id=? AND venueId=?
                                  │   ├─ activeReserved = SUM(Reservation.partySize) 
                                  │   │   WHERE classSessionId=? AND venueId=?
                                  │   │   AND status IN ('PENDING','CONFIRMED','CHECKED_IN')
                                  │   │   AND orderId != :orderId (excluye retries de esta order)
                                  │   ├─ heldByOthers = SUM(SlotHold.partySize)
                                  │   │   WHERE classSessionId=? AND venueId=?
                                  │   │   AND expiresAt > NOW()
                                  │   │   AND id != item.slotHoldId
                                  │   ├─ Si activeReserved + heldByOthers + item.qty > session.capacity:
                                  │   │     → throw ConflictError (TX rollback)
                                  │   ├─ Si item.slotHoldId:
                                  │   │   ├─ SELECT SlotHold WHERE id=? AND classSessionId=item.classSessionId
                                  │   │   │   AND productId=item.productId AND venueId=order.venueId
                                  │   │   │   AND cartCorrelationId=:cartCorrId AND partySize>=item.qty
                                  │   │   │   AND expiresAt > NOW()
                                  │   │   ├─ Si no matchea → throw ConflictError
                                  │   │   └─ DELETE SlotHold (consumed)
                                  │   ├─ Por cada unitIdx en 0..item.qty-1:
                                  │   │   └─ UPSERT Reservation:
                                  │   │       WHERE idempotencyKey = '${cartCorrId}:${item.id}:${unitIdx}'
                                  │   │       ON CONFLICT DO NOTHING (idempotent)
                                  │   │       INSERT {
                                  │   │         productId: item.productId,
                                  │   │         productIds: [item.productId],  // dual-write para retrocompat
                                  │   │         classSessionId, customerId,
                                  │   │         orderId, orderItemId, unitIdx,
                                  │   │         status: 'CONFIRMED' (D14 — investigar antes),
                                  │   │         channel: 'TPV',
                                  │   │         depositAmount: item.unitPrice,
                                  │   │         depositStatus: 'PAID',
                                  │   │         depositPaidAt: NOW(),
                                  │   │         startsAt, endsAt, duration,
                                  │   │         confirmationCode, cancelSecret,
                                  │   │         idempotencyKey: cartCorrId:itemId:unitIdx,
                                  │   │         partySize: 1, venueId
                                  │   │       }
                                  ├─ Para cada OrderItem CLASS con classSessionId IS NULL (omitido):
                                  │   └─ UPSERT Reservation status=PENDING, classSessionId=null
                                  │       (mismo upsert pattern)
                                  └─ done
                                                                              10. Filter
                                                                                  Reservations.tsx
                                                                                  "Sin sesión"
                                                                                  │
                                                                                  ▼
                                                                              11. PUT /reservations/:id
                                                                                  { classSessionId,
                                                                                    status: CONFIRMED }
                                                                                  Backend valida con
                                                                                  withSerializableRetry +
                                                                                  FOR UPDATE
```

### Recovery PAX offline

```
Caso: TPV completa pago Blumon, llama POST /payments/record, network falla.
                  │
                  ▼
TPV: `PaymentQueueRepository.enqueue(...)` (cola persistente local)
                  │
                  ▼
PendingPaymentEntity extendida con:
  • paymentType: ENUM('FAST', 'ORDER')
  • orderId: String?               (NUEVO)
  • cartCorrelationId: String?     (NUEVO)
                  │
                  ▼
PaymentSyncWorker dispara reintento (existing)
                  │
                  ▼
QueuedPayment.toPaymentContext() actualizada:
  - Si paymentType == FAST → retorna PaymentContext.FastPayment (existente)
  - Si paymentType == ORDER → retorna PaymentContext.Order(orderId, ...) (NUEVO)
                  │
                  ▼
RecordPaymentUseCase invoca OrderPaymentRecorder con orderId + cartCorrelationId
                  │
                  ▼
Backend: misma TX que el primer intento (idempotent via Reservation.idempotencyKey)
                  ├─ Si SlotHold aún vivo (TTL no venció) → finalize OK, Reservations CONFIRMED
                  ├─ Si SlotHold expiró pero capacity sigue OK (no entró nadie nuevo) → finalize OK
                  └─ Si capacity ya no alcanza (alguien tomó la silla) → 409
                      → TPV marca el PendingPayment como FAILED_NEEDS_MANUAL
                      → Alertar staff: "Pago Blumon confirmado pero reserva imposible.
                         Reembolsar manualmente vía dashboard."
```

### Refund cascade (correcto para split payments)

```
Refund de un Payment específico
                  │
                  ▼
Backend: refundPayment(paymentId) — withSerializableRetry:
  • Payment.status = REFUNDED
  • Para cada PaymentAllocation del Payment:
    └─ orderId, amount → si amount == order.total:
         FULL refund de la order
         → UPDATE Reservation
             SET status=CANCELLED, cancelledAt=NOW(), cancellationReason='Order refunded'
             WHERE orderId = allocation.orderId
               AND status IN ('PENDING','CONFIRMED','CHECKED_IN')
       Si amount < order.total:
         PARTIAL refund
         → Reservations NO se tocan (manual en v1)
         → Loggear warning + alertar dashboard
  • Recalcular Order.paymentStatus en base a allocations restantes
```

---

## Cambios al modelo de datos

### `OrderItem` (Prisma)

```prisma
model OrderItem {
  ...
  classSessionId String?  @db.Text
  slotHoldId     String?  @db.Text
  
  classSession   ClassSession? @relation(fields: [classSessionId], references: [id], onDelete: SetNull)
  
  @@index([classSessionId])
}
```

**Regla de negocio**: 1 OrderItem por sesión. Si cashier quiere 2 sesiones distintas del mismo producto, son 2 OrderItems (mismo productId, distinto classSessionId).

### `Reservation` (Prisma)

```prisma
model Reservation {
  ...
  orderId      String?
  orderItemId  String?
  unitIdx      Int?
  
  order        Order?     @relation(fields: [orderId], references: [id], onDelete: SetNull)
  orderItem    OrderItem? @relation(fields: [orderItemId], references: [id], onDelete: SetNull)
  
  @@index([orderId])
  @@index([orderItemId])
  @@unique([orderItemId, unitIdx])  // 1 reserva por unidad por item
}
```

### `SlotHold` (Prisma) — añadir ownership

```prisma
model SlotHold {
  ...
  productId          String?  // NUEVO
  cartCorrelationId  String?  // NUEVO
  
  @@index([cartCorrelationId])
}
```

### `Idempotency` (Prisma) — nueva tabla

```prisma
model Idempotency {
  key         String   @id
  responseBody Json
  statusCode  Int
  expiresAt   DateTime
  createdAt   DateTime @default(now())
  
  @@index([expiresAt])
}
```

Backend middleware: `Idempotency-Key` header → check tabla → si existe y `expiresAt > NOW()` retornar response cacheado; si no, procesa y cachea por 24h.

### `ReservationChannel` enum

```sql
ALTER TYPE "ReservationChannel" ADD VALUE IF NOT EXISTS 'TPV';
```

### TPV Room migration 21→22

```kotlin
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ProductEntity: add type column
        db.execSQL("ALTER TABLE products ADD COLUMN type TEXT NOT NULL DEFAULT 'REGULAR'")
        // PendingPaymentEntity: support order payments
        db.execSQL("ALTER TABLE pending_payments ADD COLUMN payment_type TEXT NOT NULL DEFAULT 'FAST'")
        db.execSQL("ALTER TABLE pending_payments ADD COLUMN order_id TEXT")
        db.execSQL("ALTER TABLE pending_payments ADD COLUMN cart_correlation_id TEXT")
    }
}
```

Registrar en `DatabaseModule.addMigrations(MIGRATION_21_22)` y bump `@Database(version = 22)`.

### TPV `CartItem` (Kotlin)

```kotlin
data class CartItem(
    ...
    // Reservation intent (CLASS only)
    val classSessionId: String? = null,
    val slotHoldId: String? = null,
)
```

### TPV `Product` domain

```kotlin
data class Product(
    ...
    val type: ProductType,  // NUEVO — enum
)

enum class ProductType {
    REGULAR, FOOD_AND_BEV, CLASS, APPOINTMENTS_SERVICE,
    EVENT, DIGITAL, DONATION, FOOD, BEVERAGE, ALCOHOL, RETAIL, SERVICE, OTHER;
    companion object {
        fun fromString(raw: String?): ProductType =
            runCatching { valueOf(raw ?: "") }.getOrDefault(REGULAR)
    }
}
```

`ProductDto.kt` actualiza comentario del field `type`. `ProductMappers.kt` parsea via `ProductType.fromString(dto.type)`.

### TPV `OrderApiService` (Retrofit)

```kotlin
@POST("tpv/venues/{venueId}/orders/with-items")
suspend fun createOrderWithItems(
    @Path("venueId") venueId: String,
    @Header("Idempotency-Key") idempotencyKey: String,  // NUEVO
    @Body request: TpvCreateOrderWithItemsRequestDto
): Response<TpvOrderDto>
```

---

## Reglas y validaciones

### Validaciones backend en `createOrderWithItems`

| Condición | Resultado |
|---|---|
| Cualquier item es CLASS y `customerId` es null | 400 "Customer requerido para clases" |
| `payLater === true` y cualquier item es CLASS | 400 "Pago diferido no disponible para clases" |
| `classSessionId` provisto pero no existe o venueId distinto | 400 "Sesión inválida" |
| `classSessionId.productId !== item.productId` | 400 "Sesión no pertenece al producto" |
| `session.startsAt < NOW()` | 400 "Sesión ya empezó" |
| `slotHoldId` provisto pero no matchea (id, sessionId, productId, venueId, cartCorrelationId, expiresAt>NOW) | 400 "Hold inválido o expirado" |
| Mismo `Idempotency-Key` ya procesado | 200 con Order anterior (no re-crea) |

### Validaciones backend en `finalizeReservationsForOrder` (TX inner)

Todas dentro de la TX outer (`withSerializableRetry`):
- `SELECT ClassSession FOR UPDATE WHERE id=? AND venueId=?`
- Capacity = `SUM(Reservation.partySize) WHERE active AND venueId AND orderId != currentOrder` + `SUM(SlotHold.partySize) WHERE expiresAt>NOW AND venueId AND id != currentHoldId`
- Si capacity + item.qty > session.capacity → throw → TX rollback
- SlotHold consume valida ownership (productId, cartCorrelationId, expiresAt) antes de DELETE
- UPSERT Reservation con `idempotencyKey` UNIQUE

### Customer obligatorio (UI + backend)

UI `prepareForPayment` abre `CustomerSelectorSheet` bloqueante si hay CLASS y no hay customer.
Backend rechaza con 400 (defensa en profundidad).

### SlotHold lifecycle

| Evento TPV | Llamada backend |
|---|---|
| Elegir sesión | `POST /tpv/.../slot-holds {sessionId, partySize, productId, venueId, cartCorrelationId}` |
| qty++ | `PUT /tpv/.../slot-holds/:id {partySize: newQty}` (recheck capacity FOR UPDATE) |
| qty-- (>0) | `PUT` con nuevo partySize |
| qty=0 / quitar item | `DELETE /tpv/.../slot-holds/:id` |
| Cambiar sesión | `DELETE` + `POST` |
| Pago éxito | `finalizeReservationsForOrder` consume (DELETE atómico) |
| TTL 5min | `expiresAt < NOW()` excluido por queries; cleanup cron opcional v1.1 |

### Cart freeze después de `createOrderWithItems`

- Después de POST exitoso, el cart en TPV pasa a estado `LOCKED`.
- UI deshabilita botones de edición (incrementar, decrementar, agregar item, quitar item).
- Único botón disponible: "Cancelar y volver" → DELETE `/orders/:id` → cart vuelve a editable, holds se liberan.
- Justificación: editar el cart después de crear la Order requiere sync de OrderItem + SlotHold = complejidad innecesaria para un POS donde el flow normal es "Cobrar → Pagar" en segundos.

---

## Estados

### Reservation

```
                ┌─────────┐
                │  null   │
                └────┬────┘
                     │ finalizeReservationsForOrder (post-pago)
                     │
            ┌────────┴────────┐
            │                 │
   (sin classSessionId)   (con classSessionId)
            │                 │
            ▼                 ▼
       ┌─────────┐       ┌───────────┐
       │ PENDING │       │ CONFIRMED │ ← D14, lockear post-investigación
       └────┬────┘       └─────┬─────┘
            │                  │ checkIn
            │                  ▼
            │            ┌────────────┐
            │            │ CHECKED_IN │ → COMPLETED
            │            └─────┬──────┘
            │                  │ noShow
            │                  ▼
            │            ┌──────────┐
            │            │ NO_SHOW  │
            │            └──────────┘
            │
            │ refund/cancel
            ▼
       ┌───────────┐
       │ CANCELLED │
       └───────────┘
```

### Order (relevante a este flow)

```
PENDING + PENDING (createOrderWithItems)
       │
       ├── Payment registrado exitoso + finalize OK → COMPLETED + PAID
       ├── Payment registrado exitoso + finalize 409 → ROLLBACK → sigue PENDING + PENDING
       └── Cancel manual → CANCELLED + CANCELLED + holds liberados
```

---

## Idempotency (contrato concreto)

| Nivel | Mecanismo | Storage | TTL |
|---|---|---|---|
| `createOrderWithItems` | Header `Idempotency-Key: <cartCorrelationId>` | Tabla `Idempotency` (key, response, status, expiresAt) | 24h |
| `recordPayment` | Reusar `Payment.externalReference` o key existente (verificar durante impl) | Existing | — |
| `Reservation` (dentro de finalize) | UPSERT `WHERE idempotencyKey = '${cartCorrId}:${itemId}:${unitIdx}'` | UNIQUE constraint en `Reservation.idempotencyKey` | Permanente |
| `slot-hold POST` | Body `cartCorrelationId` → si existe hold activo con mismo (cartCorrId, sessionId), retornar el mismo | — | TTL del hold (5min) |

`cartCorrelationId`:
- Generado en TPV cuando `ActiveCartState` se crea (UUID).
- Persistido en `ActiveCartState` (no en SecureStorage — vida del cart, no del usuario).
- Sobrevive crashes del TPV mientras la session esté activa.
- Se renueva al limpiar el cart (post-pago éxito o cancel).

---

## Pruebas requeridas

### Backend — `finalizeReservationsForOrder`

```
CONFIRMED + edge cases:
[★★★] CLASS + sesión OK → Reservation CONFIRMED, capacity decrementa, hold borrado
[★★★] CLASS omitido → Reservation PENDING, classSessionId=null
[★★★] qty=2 misma sesión → 2 Reservations, capacity -2
[★★★] 2 OrderItems CLASS distintas sesiones → 2 Reservations correctas
[★★★] Capacity llena por OTROS Reservations → 409, TX rollback (Order, Payment, Reservations todos retroceden)
[★★★] Capacity llena por OTROS SlotHolds → 409, rollback
[★★★] MI slotHoldId NO cuenta contra mí (excluido por id != currentHoldId)
[★★★] slotHoldId con productId/sessionId/venueId/cartCorrelationId mismatched → 409
[★★★] slotHoldId expirado (expiresAt < NOW) → 409
[★★★] classSessionId de otro venue → 400 antes de llegar a finalize
[★★★] classSessionId de otro producto → 400
[★★★] session.startsAt en pasado → 400
[★★★] REGRESIÓN: cobro NO-CLASS no crea Reservations
[★★★] cobro CLASS sin customerId → 400
[★★★] cobro CLASS + payLater → 400
[★★★] retry idempotente (mismo cartCorrId) → no duplica Reservations
[★★★] race: 2 cobros concurrentes a última silla → 1 OK + 1 409 (withSerializableRetry no permite ambos)
[★★]  capacity usa SUM(Reservation.partySize), no count (regresión: reservation con partySize=2 cuenta 2)
[★★]  productIds[] se escribe junto con productId

SPLIT PAYMENTS:
[★★★] Order con 2 Payments (split), refund de 1 cubre 50% del total → Reservations NO cambian (partial)
[★★★] Order con 2 Payments, ambos refunded (total = 100%) → Reservations CANCELLED
[★★★] Order con 1 Payment que cubre 100%, refunded → Reservations CANCELLED

OFFLINE/QUEUED:
[★★★] PendingPaymentEntity con paymentType=ORDER + orderId → retry via OrderPaymentRecorder
[★★★] Retry con hold expirado pero capacity OK → finalize éxito
[★★★] Retry con capacity NO disponible → FAILED_NEEDS_MANUAL state + alert
```

### Backend — `updateReservation` (asignar después)

```
[★★★] PUT con classSessionId + status=CONFIRMED → valida con FOR UPDATE, ok si hay cupo
[★★★] PUT con sesión llena → 409
[★★★] PUT con sesión de otro venue → 400
[★★★] PUT con sesión de otro producto → 400
[★★]  PUT reasigna sesión → libera cupo viejo, ocupa nuevo
```

### Backend — `SlotHold` lifecycle

```
[★★★] POST con cartCorrelationId duplicado → retorna hold existente (idempotent)
[★★★] PUT partySize aumentado, capacity OK → ok
[★★★] PUT partySize aumentado, capacity exhausted → 409
[★★★] DELETE existente → ok
[★★★] expiresAt < NOW excluido en todas las queries
```

### Backend — Idempotency middleware

```
[★★★] mismo Idempotency-Key → mismo response, no doble-procesa
[★★★] Idempotency-Key después de 24h → procesa de nuevo (TTL expirado)
[★★★] Idempotency-Key con body distinto → ¿reject? (decidir durante impl: por seguridad mejor rechazar 422)
```

### TPV — UI

```
[★★★] tap CLASS → SessionPickerSheet (venueId scoped)
[★★★] 0 sesiones próximas → solo "Omitir"
[★★★] elegir sesión → POST slot-hold, cart guarda holdId
[★★★] Omitir → cart guarda classSessionId=null
[★★★] Cobrar CLASS sin customer → CustomerSelectorSheet bloqueante
[★★★] CustomerSelectorSheet [+ Cliente nuevo] → POST /tpv/.../customers, selecciona auto
[★★★] qty++ → PUT slot-hold (partySize++)
[★★★] qty-- → PUT o DELETE
[★★★] cart con CLASS → "Pagar después" deshabilitado + tooltip
[★★★] después de createOrderWithItems → cart LOCKED, edit buttons disabled
[★★★] cancelar order desde estado LOCKED → DELETE order, cart unlock, holds liberados
[★★★] cartCorrelationId persiste en ActiveCartState a través de proceso muerte/restart
[★★★] PendingPaymentEntity con paymentType=ORDER → sync worker reconstruye OrderPaymentRecorder context
```

### E2E

```
[→E2E] Happy: tap → picker → elige → cobrar → quick-customer → Blumon éxito → Reservation CONFIRMED + capacity -1
[→E2E] Omitir: tap → picker → omitir → cobrar → Reservation PENDING → dashboard asigna → CONFIRMED + capacity -1
[→E2E] Refund full: cobro 2 reservations → refund total Payment → ambas CANCELLED + capacity +2
[→E2E] Refund partial: 2 Payments → refund 1 → Reservations intactas + alerta a dashboard
[→E2E] Offline: cobro Blumon OK + network falla → queued → red vuelve → retry → Reservation creada
[→E2E] Race: 2 TPVs cobran último cupo → 1 gana, 1 recibe 409 + UX clara
[→E2E] Hold expiry: cashier elige sesión, espera 6min, intenta cobrar → 409 (hold expirado), elegir nueva sesión
```

---

## Investigaciones previas al kick-off (gate de 1-2 días)

Estas decisiones requieren leer código que no he leído / no puedo asumir. Antes de empezar la implementación:

### I1 — Semántica PENDING vs CONFIRMED para attendance (D14)

**Qué investigar**: en `avoqado-web-dashboard` y endpoints de check-in / class roster, ¿cómo se filtran las Reservations? Si esperan `CONFIRMED` para mostrar como "attendee de la sesión", v3 está correcto al crear directo `CONFIRMED` post-pago. Si esperan ambos `PENDING` y `CONFIRMED`, podemos crear `PENDING` y dejar el upgrade a `CONFIRMED` para el check-in.

**Bloqueo si no se resuelve**: Reservations creadas en sesión pueden ser invisibles para el staff durante la clase.

**Esfuerzo**: 0.5d (grep + lectura de queries en dashboard reservation services).

### I2 — Detección de "Pagar después" en backend

**Qué investigar**: actualmente `createOrderWithItems` deja `paymentStatus: PENDING` siempre. ¿Cómo distingue "Pagar después" intencional vs "voy a pagar en el siguiente paso"? Opciones:
- **A) Flag explícito `payLater: boolean`** en el body — recomendado, claro.
- B) Endpoint separado `POST /orders/pay-later` que reusa la misma lógica.
- C) Inferir por presencia de `customerId` + ausencia de payment posterior dentro de X minutos — frágil.

**Esfuerzo**: 0.5d (decidir, no investigar profundo).

### I3 — Comportamiento de transacciones en `payment.tpv.service.recordPayment`

**Qué investigar**: leer las TX de `recordPayment` (líneas 340, 461, 2215). Si la TX ya hace commit antes de marcar `Order.paymentStatus=PAID`, no podemos hacer `finalizeReservationsForOrder` en la misma TX. En ese caso necesitamos:
- O reestructurar la TX (extender el scope)
- O usar pattern outbox: marcar `Order.status=PAID_NEEDS_FINALIZATION` y background worker reintenta finalize hasta que succeed. Cliente cobra, dashboard ve la Order como pagada pero la sesión no consumida hasta que el worker termine (típicamente <1s).

**Bloqueo si no se resuelve**: arquitectura "atomic post-payment" puede no ser viable; pattern outbox es la alternativa.

**Esfuerzo**: 1d (leer + decidir).

### I4 — Audit de `Reservation.productIds` consumers

**Qué investigar**: ¿qué partes del backend/dashboard leen `Reservation.productIds[]`? Si solo lo escribe la antigua app de booking pero ningún consumer lo lee, podemos ignorarlo (v3 escribe ambos por precaución pero v1.1 puede migrar a solo `productId`).

**Esfuerzo**: 0.5d (grep en avoqado-server + dashboard).

---

## Estimación de esfuerzo (revisado v3)

| Repo | Trabajo | Días |
|---|---|---|
| **Investigaciones previas (I1-I4)** | Antes de impl | **2** |
| **avoqado-server** | • Migrations (OrderItem, Reservation, SlotHold, Idempotency table, enum)<br>• Endpoints: GET sessions, POST/PUT/DELETE slot-holds (con ownership)<br>• Modificar createOrderWithItems: validations completas, idempotency middleware<br>• finalizeReservationsForOrder service (tx-only, no wrapper)<br>• Hook en payment.tpv.service (o pattern outbox según I3)<br>• Refund cascade via PaymentAllocation<br>• Extender updateReservation con validations cross-tenant<br>• Tests unitarios + integration | **6-7** |
| **avoqado-tpv** | • `ProductType` enum + propagación DTO→domain→Room (MIGRATION_21_22)<br>• Extender CartItem con classSessionId/slotHoldId<br>• Extender PendingPaymentEntity + QueuedPayment con orderId+cartCorrelationId+paymentType<br>• SessionPickerSheet + ClassSession DTO/repo<br>• Generación + persistencia cartCorrelationId en ActiveCartState<br>• SlotHold lifecycle completo (POST/PUT/DELETE según qty changes)<br>• Cart LOCK state después de createOrderWithItems<br>• Extender CustomerSelectorSheet con quick-create<br>• Customer-gate + payLater-gate en prepareForPayment<br>• Idempotency-Key header en OrderApiService<br>• Tests | **7-8** |
| **avoqado-web-dashboard** | • Filter chip "Sin sesión asignada"<br>• Dropdown asignación en ReservationDetail<br>• Alerts para partial refund + offline failures | **2** |
| **QA cross-repo + E2E** | • PAX A910S sandbox<br>• 7 escenarios E2E<br>• Race conditions con load testing pequeño | **2** |
| **Total** | | **19-21** |

---

## Riesgos remanentes (post v3)

| Riesgo | Mitigación |
|---|---|
| `payment.tpv.service.recordPayment` no permite extender la TX → pattern outbox necesario | I3 antes de impl; outbox aumenta esfuerzo +1d |
| D14 resuelve "esperan PENDING" → status inicial debe ser PENDING + auto-upgrade en check-in | I1 antes de impl; ajuste trivial |
| Partial refund + reservation no cancelada → cliente cree que mantiene el cupo | v1: alertar a dashboard + documentar runbook. v1.1: cascade proporcional |
| Cleanup de SlotHold expirados → tabla crece | v1 sin cleanup (filter ignora expirados). v1.1: cron diario |
| `Reservation.productIds[]` consumers desconocidos → datos inconsistentes | I4 antes de impl; dual-write como cobertura |
| TPV crashea entre createOrderWithItems éxito y PaymentScreen → Order PENDING huérfana | Sweeping job v1.1 que cancela Orders PENDING > 30min sin Payment |

---

## Cosas que v1 NO resuelve (documentar honesto)

- Refund parcial → cascada automática a Reservations.
- Cleanup automático de SlotHolds expirados.
- Sweeping de Orders PENDING huérfanas.
- Migración de `Reservation.productIds[]` a solo `productId`.
- avoqado-android UI (usa los mismos endpoints cuando se construya).
- APPOINTMENTS_SERVICE con calendar picker.
- Waitlist.
- Push/SMS notifications.

Todos quedan listados en TODOS.md o backlog del cliente.
