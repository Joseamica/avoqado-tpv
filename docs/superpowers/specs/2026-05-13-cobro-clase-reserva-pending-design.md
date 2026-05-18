# Cobro de clase con reserva PENDING desde TPV

Fecha: 2026-05-13
Autor: Jose Antonio Amieva
Status: APPROVED FOR PLANNING

## Problema

Cuando un cajero cobra un producto tipo `CLASS` (clase grupal con cupo limitado) desde el TPV, hoy se registra `Order + Payment + OrderItem` pero **no se consume cupo de ninguna `ClassSession`**. Resultado: la sesión sigue mostrando capacidad completa aunque la clase ya esté pagada, lo que permite vender el mismo asiento dos veces.

El negocio (yoga/pilates/lagree studios como PlayTelecom) opera en modelo "compro mi lugar en una sesión específica" — necesita que **cobrar = reservar cupo**, con una válvula de escape para casos donde el cliente aún no decide cuándo asistir ("asignar después").

## Decisiones aprobadas

| Decisión | Resolución |
|---|---|
| Modelo de datos | **B** — crear `Reservation` con `status=PENDING` al cobrar. Puede tener `classSessionId` (cupo asignado) o `NULL` (asignar después). |
| Hold de cupo | **Reusar `SlotHold` existente.** Al elegir sesión en el picker, crear `SlotHold` con `expiresAt=now+2min`. El pago lo convierte en `Reservation`. |
| UI dashboard | **Reusar `Reservations.tsx`** con filter chip "Sin sesión asignada". `ReservationDetail.tsx` agrega dropdown para seleccionar sesión. |
| Refund cascade | **Incluido en v1.** Al reembolsar un Payment cuya Order tiene Reservations, marcarlas `CANCELLED` y liberar cupo. |
| Idempotency | `cartCorrelationId` (UUID del cliente, no de Order) `:lineIdx:unitIdx`. TPV lo genera al iniciar el carrito. |
| Quantity > 1 | **N Reservations independientes** (1 por unidad). Match con cupo (1 lugar = 1 fila); permite refunds/cambios individuales. |
| Customer | **Obligatorio** para cobros con `CLASS`. Validación tanto en UI del TPV como en el backend (defensa en profundidad). |
| Scope v1 | Solo `CLASS`. `APPOINTMENTS_SERVICE` se difiere a v2. |

## Lo que ya existe (no reinventar)

| Pieza | Ubicación |
|---|---|
| Tabla `Reservation` con `classSessionId`, `customerId`, `guestName/Phone/Email`, `depositAmount`, `depositStatus`, `idempotencyKey`, `confirmationCode`, `cancelSecret`, `partySize`, `status`, `channel` | `avoqado-server/prisma/schema.prisma` |
| Tabla `ClassSession` con `capacity`, `startsAt`, `endsAt`, `productId`, `assignedStaffId` | `avoqado-server/prisma/schema.prisma` |
| Tabla `SlotHold` con `expiresAt`, FK a `ClassSession` | `avoqado-server/prisma/schema.prisma` |
| `PUT /dashboard/.../reservations/:id` con `updateReservation` | `reservation.dashboard.controller.ts:149` |
| `POST /tpv/.../customers` quick-create idempotente | `customer.tpv.controller.ts:99` |
| `createOrderWithItems` | `order.tpv.service.ts:777` |
| `CustomerSelectorSheet` (buscar customer existente) | `app/.../checkout/presentation/components/cart/CustomerSelectorSheet.kt` |
| Pantalla `Reservations.tsx`, `ReservationDetail.tsx`, `ReservationCalendar.tsx` | `avoqado-web-dashboard/src/pages/Reservations/` |
| `reservationAvailability.service.ts` con `createHold` / TTL / count de Reservations activas | `avoqado-server/src/services/dashboard/` |

## Lo que NO está en scope

- `APPOINTMENTS_SERVICE` con slot picker (calendario + staff). v2.
- Reembolso automático del Payment al cancelar Reservation desde dashboard (la Reservation se marca CANCELLED, pero el dinero se devuelve manualmente por ahora).
- avoqado-android UI nueva. Consume el mismo `PUT /reservations/:id` cuando se construya.
- Waitlist cuando la sesión está llena.
- Notificaciones push/SMS al cliente al asignar sesión.
- Cobro mixto (CLASS + producto regular en mismo cart) genera correctamente Reservations solo para CLASS — incluido. Mezcla con APPOINTMENTS_SERVICE en el mismo cart se posterga junto con el resto de v2.

---

## Arquitectura

### Flujo end-to-end

```
TPV — Cobrar                                  Backend                          Dashboard
─────────────                                ─────────                          ──────────
1. Cashier tap CLASS product
   │
   ▼
2. SessionPickerSheet abre
   GET /tpv/.../products/:id/sessions      ◄── (nuevo endpoint)
   ◄── lista [sesiones próximas con cupo]
   │
   ▼
3a. Elige sesión          3b. Pulsa "Omitir"
   │                          │
   POST /tpv/.../slot-holds   │
   {sessionId, qty}           │
   ◄── {holdId, expiresAt}    │
   │                          │
   ▼                          ▼
4. Item al cart con classSessionId (o null) + slotHoldId (o null)
   │
   ▼
5. Pulsa Cobrar → si CLASS sin customer → CustomerSelectorSheet bloqueante
   │                                       │
   │                                       └─► [+ Cliente nuevo] → POST /tpv/.../customers
   ▼
6. Blumon TPV procesa pago
   │
   ▼
7. POST /tpv/.../orders/with-items                                            
   { ...items, cartCorrelationId, customerId, lines:[{classSessionId, slotHoldId}] }
                                            │
                                            ▼
                                  TX: createOrderWithItems
                                  ├─ Order + OrderItem + Payment (existente)
                                  └─ createReservationsForOrder(tx, order, lines)
                                        │
                                        ├─ Para cada line CLASS:
                                        │   ├─ Validar capacity (batch query)
                                        │   ├─ Por cada unidad (1..qty):
                                        │   │   ├─ Crear Reservation
                                        │   │   │   {productId, classSessionId, customerId,
                                        │   │   │    status=PENDING, channel=TPV,
                                        │   │   │    depositAmount, depositStatus=PAID,
                                        │   │   │    confirmationCode, cancelSecret,
                                        │   │   │    idempotencyKey=cartCorrId:lineIdx:i,
                                        │   │   │    partySize=1, startsAt/endsAt}
                                        │   │   └─ Si slotHoldId: eliminar SlotHold
                                        │   └─ Si capacity excedida: ROLLBACK, 409
                                        └─ Commit
                                  
                                                                              8. Staff filtra
                                                                                 "Sin sesión"
                                                                                 │
                                                                                 ▼
                                                                              9. Click reserva
                                                                                 ReservationDetail
                                                                                 selecciona sesión
                                                                                 │
                                                                                 ▼
                                                                              10. PUT /reservations/:id
                                                                                  {classSessionId, status: CONFIRMED}
                                                                                  Backend valida capacity → OK
```

### Componentes nuevos / modificados

#### `avoqado-server` (Express + Prisma)

```
NUEVOS:
- src/routes/tpv/classSession.tpv.routes.ts          GET /tpv/.../products/:id/sessions
- src/controllers/tpv/classSession.tpv.controller.ts
- src/services/tpv/classSession.tpv.service.ts        listUpcomingSessionsForProduct(...)
- src/routes/tpv/slotHold.tpv.routes.ts              POST /tpv/.../slot-holds, DELETE /tpv/.../slot-holds/:id
- src/controllers/tpv/slotHold.tpv.controller.ts
- src/services/tpv/reservation.tpv.service.ts        createReservationsForOrder(tx, order, lines, ...)
- prisma/migrations/.../add_tpv_to_reservation_channel.sql

MODIFICADOS:
- src/services/tpv/order.tpv.service.ts              createOrderWithItems
                                                     • aceptar lines[].classSessionId, slotHoldId
                                                     • aceptar cartCorrelationId
                                                     • llamar createReservationsForOrder dentro de la TX
                                                     • validar customer obligatorio si hay CLASS
- src/schemas/tpv.schema.ts                          ampliar request body con campos arriba
- src/services/dashboard/reservation.dashboard.service.ts
                                                     updateReservation:
                                                     • permitir transicionar PENDING → CONFIRMED al setear classSessionId
                                                     • validar capacity al asignar
- src/services/payment/refund.service.ts (o equivalente)
                                                     al refund: cancelar Reservations de la Order, liberar cupo
```

#### `avoqado-tpv` (Kotlin + Compose)

```
NUEVOS:
- features/checkout/presentation/components/SessionPickerSheet.kt
- features/checkout/data/dto/ClassSessionDto.kt
- features/checkout/data/repository/ClassSessionRepositoryImpl.kt
- features/checkout/domain/repository/ClassSessionRepository.kt
- features/checkout/domain/model/ClassSession.kt

MODIFICADOS:
- features/ordering/domain/Product.kt                + val type: ProductType (enum)
- features/ordering/data/dto/ProductDto.kt           type ya existe (String); usarlo
- features/ordering/data/mappers/ProductMappers.kt   parsear type
- core/data/local/entities/ProductEntity.kt          + type column + MIGRATION_X_Y
- core/data/network/ApiService.kt                    nuevos endpoints sessions/slot-holds
- features/checkout/presentation/CheckoutViewModel.kt
                                                     • onProductTap: si CLASS → SessionPickerSheet
                                                     • generar cartCorrelationId al iniciar carrito
                                                     • bloquear cobro si CLASS sin customer
                                                     • crear SlotHold al elegir sesión, liberar al cambiar
- features/checkout/presentation/components/cart/CustomerSelectorSheet.kt
                                                     + botón "+ Cliente nuevo" con mini-form (nombre + email)
                                                     + POST /tpv/.../customers
- features/checkout/data/ActiveCartState.kt          CartItem + classSessionId, slotHoldId, cartCorrelationId
- features/checkout/data/repository/SavedCartsRepositoryImpl.kt   persistir nuevos campos
```

#### `avoqado-web-dashboard` (React)

```
MODIFICADOS:
- src/pages/Reservations/Reservations.tsx            + filter chip "Sin sesión asignada"
                                                     (classSessionId === null && status === PENDING)
- src/pages/Reservations/ReservationDetail.tsx       + sección "Asignar sesión" con dropdown de
                                                     ClassSessions del producto, llama PUT /reservations/:id
- src/services/reservation.service.ts                añadir updateReservation con classSessionId/status
```

---

## Reglas de validación

### Customer obligatorio para CLASS

- **UI TPV**: si el carrito tiene cualquier item con `Product.type === CLASS` y no hay customer seleccionado, el botón "Cobrar" abre `CustomerSelectorSheet` modal-bloqueante en lugar de proceder al pago.
- **Backend** `createOrderWithItems`: si cualquier `OrderItem` tiene `productId` de tipo CLASS y `order.customerId` es `null` → `400 Bad Request "Customer requerido para clases"`.

### Capacity check (batch query, evita N+1)

```sql
-- Una sola query agrupada (no una por sesión)
SELECT "classSessionId", count(*) AS active_count
FROM "Reservation"
WHERE "classSessionId" IN (:sessionIds)
  AND status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN')
GROUP BY "classSessionId";

-- Más SlotHolds activos:
SELECT "classSessionId", count(*) AS hold_count
FROM "SlotHold"
WHERE "classSessionId" IN (:sessionIds)
  AND "expiresAt" > NOW()
GROUP BY "classSessionId";
```

Por sesión: `available = capacity - active_count - hold_count`. Si `qty > available` → `409 Conflict "Cupo insuficiente en sesión X"`.

### SlotHold lifecycle

```
Elige sesión en TPV  →  POST /tpv/.../slot-holds   →  SlotHold creado, expiresAt = now + 2min
       │
       ├─ Cambia sesión       →  DELETE /tpv/.../slot-holds/:id  →  hold liberado
       ├─ Quita item del cart →  DELETE /tpv/.../slot-holds/:id
       ├─ Pago exitoso        →  createReservationsForOrder consume el hold (DELETE en la misma TX)
       └─ Timeout 2min        →  expiresAt < NOW(), ignorado por queries (cupo se libera)
```

### Idempotency

- TPV genera `cartCorrelationId = UUID.randomUUID()` al iniciar nuevo carrito (en `ActiveCartState`).
- Persiste en `ActiveCartState` y se incluye en `POST /tpv/.../orders/with-items` body.
- `Reservation.idempotencyKey = "${cartCorrelationId}:${lineIdx}:${unitIdx}"` (unique index ya existe en la tabla).
- En reintento: `prisma.reservation.upsert` con `where: { idempotencyKey }` → no duplica.

### Refund cascade

Al ejecutar un refund que cubra el 100% del Payment de una Order:

```
TX:
  - Payment.status = REFUNDED
  - Por cada Reservation WHERE orderId = order.id (vía OrderItem.productId match)
    AND status IN ('PENDING', 'CONFIRMED'):
      - Reservation.status = CANCELLED
      - Reservation.cancelledAt = NOW()
      - Reservation.cancellationReason = 'Order refunded'
  - Cupo se libera automáticamente (porque el filter cuenta solo PENDING/CONFIRMED/CHECKED_IN)
```

Para refunds parciales: por simplicidad, **NO cancelar reservations en v1**. El staff decide manualmente. Documentar en runbook.

---

## Estados y transiciones

### Reservation

```
                ┌─────────┐
                │  null   │ (no existe)
                └────┬────┘
                     │ createReservationsForOrder
                     ▼
                ┌─────────┐
        ┌──────►│ PENDING ├──────┐ assignSession (dashboard)
        │       └────┬────┘      │
   refund│            │           ▼
        │            │      ┌───────────┐
        │            │      │ CONFIRMED ├──────┐ checkIn
        │            │      └─────┬─────┘      │
        │            │            │            ▼
        │            │            │      ┌────────────┐
        │            │            │      │ CHECKED_IN ├──┐ noShow / complete
        │            │            │      └─────┬──────┘   │
        │            │            │            │         │
        │            ▼            ▼            ▼         ▼
        │       ┌───────────┐ ┌───────────┐ ┌──────────┐ ┌───────────┐
        └──────►│ CANCELLED │ │ NO_SHOW   │ │ COMPLETED│ │ NO_SHOW   │
                └───────────┘ └───────────┘ └──────────┘ └───────────┘
```

### SlotHold

```
[POST hold]──► ACTIVE ─┬──► [DELETE hold] ──► gone (cambio sesión / quitar item)
                       ├──► [TX consume]  ──► gone + Reservation creada
                       └──► [expiresAt]   ──► ignored (queries lo filtran; cleanup job opcional)
```

---

## Pruebas requeridas (cobertura mínima)

### Backend — `createOrderWithItems` con clases

```
[★★★] cobro CLASS con sesión válida → 1 Reservation PENDING con classSessionId, hold consumido
[★★★] cobro CLASS omitido → 1 Reservation PENDING con classSessionId=NULL
[★★★] cobro 2x misma CLASS misma sesión → 2 Reservations independientes, cupo decrementa 2
[★★★] cobro CLASS con sesión llena (capacity reached por reservations existentes) → 409, NO Order
[★★★] cobro CLASS con sesión llena por SlotHolds activos → 409, NO Order
[★★★] REGRESIÓN: cobro de producto NO-CLASS (FOOD/REGULAR) no crea Reservations  ← CRÍTICO
[★★★] cobro CLASS sin customerId → 400 con mensaje explícito
[★★★] retry idempotente con mismo cartCorrelationId → no duplica Reservations
[★★★] cobro mixto CLASS + producto regular en mismo cart → solo 1 Reservation (la del CLASS)
[★★]  cobro 1 CLASS qty=3 misma sesión → 3 Reservations (verify uniques de idempotencyKey)
[★★]  validation batch: 2 CLASS distintas en cart → 1 sola query agrupada (verificar con prisma logging)
```

### Backend — refund cascade

```
[★★★] refund total de Order con 2 Reservations PENDING → ambas CANCELLED, cupo liberado
[★★★] refund total de Order con Reservation CONFIRMED → CANCELLED, cupo liberado
[★★]  refund parcial → Reservations NO se tocan (documentado)
```

### Backend — assign-session vía updateReservation

```
[★★★] PUT /reservations/:id con classSessionId válido y disponible → status CONFIRMED, capacity OK
[★★★] PUT /reservations/:id con classSessionId llena → 409, sin cambio
[★★]  PUT /reservations/:id ya CONFIRMED → reasigna a otra sesión, libera cupo viejo, ocupa nuevo
```

### TPV — UI

```
[★★★] tap producto CLASS → SessionPickerSheet aparece con sesiones próximas
[★★★] SessionPickerSheet con 0 sesiones programadas → solo botón "Omitir"
[★★★] elegir sesión → POST slot-hold se ejecuta, cart guarda holdId
[★★★] pulsar "Omitir" → no se crea hold, cart guarda classSessionId=null
[★★★] Cobrar CLASS sin customer → CustomerSelectorSheet bloqueante
[★★★] CustomerSelectorSheet quick-create → POST /tpv/.../customers, selecciona auto
[★★]  cambiar sesión en cart → DELETE hold viejo, POST hold nuevo
[★★]  quitar item CLASS del cart → DELETE hold
```

### E2E — golden path

```
[→E2E] Cobro completo:
  1. Cajero abre TPV en PlayTelecom Centro
  2. Tap "[TEST] Clase de Prueba"
  3. SessionPickerSheet muestra sesión "Lun 18 May 19:00, 8/10"
  4. Cajero elige esa sesión
  5. Cobro con cliente "test@ejemplo.com" (quick-create)
  6. Pago Blumon TPV (sandbox)
  7. → DB: Order + Payment + Reservation PENDING con classSessionId, capacity 9/10
  8. Dashboard: filter "Sin sesión asignada" NO la muestra (sí tiene sesión)
  9. ClassSession query: occupiedSeats = 1
```

```
[→E2E] Cobro con "Omitir" + asignación posterior:
  1. Cajero cobra "[TEST] Clase de Prueba", pulsa "Omitir"
  2. → DB: Reservation PENDING con classSessionId=NULL
  3. Dashboard: filter "Sin sesión asignada" la muestra
  4. Staff abre ReservationDetail, elige sesión "Lun 18 May 19:00"
  5. PUT /reservations/:id → status=CONFIRMED, classSessionId seteado
  6. ClassSession: capacity 8/10 → 7/10
```

---

## Migración de datos / DB

```sql
-- Migration 1: añadir 'TPV' al enum ReservationChannel
ALTER TYPE "ReservationChannel" ADD VALUE IF NOT EXISTS 'TPV';

-- No tocar filas existentes. Solo nuevas reservations desde TPV usarán este valor.
```

```kotlin
// Migration Room para ProductEntity.type
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN type TEXT NOT NULL DEFAULT 'REGULAR'")
    }
}
```

---

## Riesgos y mitigaciones

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| TX de `createOrderWithItems` se vuelve muy pesada | Media | Helper extraído `createReservationsForOrder`; batch query para capacity; tests de duración |
| 2 cajeros toman última silla a la vez | Media | SlotHold con 2min TTL; capacity check incluye SlotHolds activos |
| Refund parcial no cancela reservations correctas | Alta | Documentado como manual en v1; runbook explícito; v1.1 lo automatiza |
| Migration de enum falla en filas viejas | Baja | `ADD VALUE IF NOT EXISTS`; no actualiza filas existentes |
| TPV cachea producto sin `type` (de versión vieja) | Media | MIGRATION_X_Y de Room con default `REGULAR`; refresh forzado al actualizar |
| SlotHold queda huérfano si TPV crashea entre POST hold y crash | Baja | `expiresAt` lo libera en 2min; cleanup job opcional |

---

## Estimación de esfuerzo

| Repo | Trabajo | Estimado |
|---|---|---|
| `avoqado-server` | Nuevos endpoints (sessions, slot-holds), modificar `createOrderWithItems`, extender `updateReservation`, refund cascade, migration, tests | 3-4 días |
| `avoqado-tpv` | `SessionPickerSheet`, propagar `Product.type`, Room migration, `CustomerSelectorSheet` quick-create, gate de customer, cartCorrelationId, lifecycle de SlotHold, tests | 4-5 días |
| `avoqado-web-dashboard` | Filter chip en `Reservations.tsx`, dropdown en `ReservationDetail.tsx`, llamada `updateReservation` | 1-1.5 días |
| QA cross-repo + E2E | Probar flujo completo en sandbox con PAX device | 1 día |
| **Total** | | **~9-11 días** |

---

## Decisiones pendientes (potenciales bloqueadores antes de implementar)

1. ¿Cuántas sesiones muestra el `SessionPickerSheet` por defecto? Propuesta: próximas 14 días, ordenadas por `startsAt ASC`, paginación si > 20.
2. ¿El customer obligatorio aplica también si el cajero pulsa "Omitir"? Propuesta: **sí** — sin customer, omitir = reservation huérfana.
3. ¿Qué pasa si la sesión ya empezó (`startsAt < now`)? Propuesta: el endpoint `GET .../products/:id/sessions` no la devuelve. Si el cajero la tenía en hold y pasa el tiempo, el hold expira normalmente.
4. ¿El `confirmationCode` se le muestra al cliente al cobrar? Propuesta: imprimirlo en el ticket (próxima iteración) o solo guardarlo en BD por ahora.
