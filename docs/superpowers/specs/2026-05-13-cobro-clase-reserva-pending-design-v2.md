# Cobro de clase con reserva PENDING desde TPV — v2

Fecha: 2026-05-15
Autor: Jose Antonio Amieva
Status: APPROVED FOR PLANNING
Supersedes: `2026-05-13-cobro-clase-reserva-pending-design.md` (v1)

## Por qué v2

v1 fue auditado por dos LLMs independientes que encontraron 16 fallas reales:

- **6 hallazgos confirmados por verificación de código**: orden inverso pago/order, FK faltante Reservation→Order, capacity check sin SERIALIZABLE+FOR UPDATE, SlotHold count vs SUM(partySize), ProductDto.type ignorado, MIGRATION_21_22 explícito.
- **10 hallazgos adicionales**: el hold cuenta contra sí mismo, atomicidad post-pago, idempotency parcial, falta `orderItemId+unitIdx`, backend block CLASS+payLater, `venueId` faltante, validation cross-tenant de session, hold lifecycle en cambios de qty, hold ownership en consume, semántica PENDING vs CONFIRMED para attendance.

v2 reescribe la arquitectura central: **Reservation se crea cuando el pago se marca PAID, no cuando la Order se crea**. Persistimos la intención en `OrderItem` para que sobreviva crashes mid-cobro.

---

## Problema

El TPV vende productos `CLASS` (clases grupales con cupo limitado) sin consumir capacidad en `ClassSession`. Un cliente puede cobrar el lugar 11 de una clase con `capacity=10`. Necesitamos:

1. Que el cajero vea las próximas sesiones disponibles al tocar la clase.
2. Que pueda elegir una sesión → al pagar, ese cupo queda reservado.
3. Que pueda pulsar "Omitir" → se cobra sin asignar sesión, queda pendiente.
4. Que el dashboard liste las "pendientes de asignar" y permita asignar después.
5. Que ningún cupo se bloquee por pagos declinados o abandonados.
6. Que refund de la order cancele automáticamente sus Reservations y libere cupo.

---

## Decisiones aprobadas

| # | Decisión | Resolución |
|---|---|---|
| D1 | Modelo de datos | **B** — Reservation `status=PENDING` al pagar, con o sin `classSessionId`. |
| D2 | Cuándo se crea la Reservation | **Después de `Payment.status=COMPLETED`**, dentro de la misma TX que marca la Order como PAID. NO en `createOrderWithItems`. |
| D3 | Persistencia de intención pre-pago | Columnas nuevas en `OrderItem`: `classSessionId`, `slotHoldId`. Sobreviven crashes mid-cobro. |
| D4 | Vínculo Reservation ↔ Order | Columnas nuevas en `Reservation`: `orderId`, `orderItemId`, `unitIdx`. Permite refund cascade determinístico. |
| D5 | Hold de cupo entre picker y pago | **Reusar `SlotHold` existente** con TTL configurable (default 5min, suficiente para flujo Blumon). |
| D6 | Capacity check | **Reusar `withSerializableRetry` + `FOR UPDATE ClassSession`** (pattern de `addAttendee`). |
| D7 | UI dashboard | Reusar `Reservations.tsx` con filter chip "Sin sesión asignada". `ReservationDetail.tsx` agrega dropdown. Reusar `updateReservation` endpoint. |
| D8 | Refund cascade | **Incluido en v1.** Refund total → todas las Reservations de la Order pasan a CANCELLED. Refund parcial → manual (documentado). |
| D9 | Idempotency | **Dos niveles**: `Idempotency-Key` header en `createOrderWithItems` (dedupes Order); `cartCorrelationId:lineIdx:unitIdx` en Reservation (dedupes Reservation). |
| D10 | Quantity > 1 | **N Reservations independientes** (1 por unidad). Hold's `partySize` refleja qty total. |
| D11 | Customer obligatorio | **Sí, UI + Backend.** Si cart tiene CLASS y no hay `customerId` → 400 backend; UI bloquea botón Cobrar. |
| D12 | CLASS en "Pagar después" | **Bloqueado en v1, UI + Backend.** Backend rechaza `createOrderWithItems` con CLASS items si `paymentStatus=PENDING` indefinido. |
| D13 | Scope v1 | Solo `CLASS`. `APPOINTMENTS_SERVICE` se difiere a v2. |
| D14 | Verificación semántica PENDING | **Investigación previa a impl.** Si dashboards/check-in esperan CONFIRMED para contar attendance, el hook post-pago crea Reservation directamente en CONFIRMED cuando hay `classSessionId`. |

---

## Lo que ya existe (no reinventar)

| Pieza | Ubicación | Uso en v2 |
|---|---|---|
| Tabla `Reservation` con `depositAmount`, `depositStatus`, `idempotencyKey`, `partySize`, `confirmationCode`, `cancelSecret`, `guestName/Phone/Email`, `classSessionId` | `prisma/schema.prisma` | Base; añadir 3 columnas (D4) |
| Tabla `ClassSession` (`capacity`, `startsAt`, `productId`, `venueId`) | `prisma/schema.prisma` | Cupo y locking row-level |
| Tabla `SlotHold` (`partySize`, `expiresAt`, `classSessionId`, `venueId`) | `prisma/schema.prisma` | Hold v1 |
| `withSerializableRetry` helper | `services/dashboard/reservation.dashboard.service.ts` | Capacity check |
| Pattern `addAttendee` con `FOR UPDATE ClassSession` | `services/dashboard/classSession.dashboard.service.ts:364-400` | Modelo a replicar para crear reservation |
| `PUT /dashboard/.../reservations/:id` (updateReservation) | `controllers/dashboard/reservation.dashboard.controller.ts:149` | Asignación posterior |
| `POST /tpv/.../customers` quickCreateCustomer idempotente | `controllers/tpv/customer.tpv.controller.ts:99` | Customer quick-create |
| `payment.tpv.service.ts` setea `status: 'COMPLETED'` (líneas 340, 461, 2215) | `services/tpv/payment.tpv.service.ts` | Hook point post-pago |
| `OrderPaymentRecorder` / `FastPaymentRecorder` en TPV | `features/payment/data/repository/` | Cliente del hook backend |
| `Reservations.tsx`, `ReservationDetail.tsx`, `ReservationCalendar.tsx` | `avoqado-web-dashboard/src/pages/Reservations/` | UI de asignación |
| `CustomerSelectorSheet` (solo búsqueda) | `app/.../checkout/.../cart/CustomerSelectorSheet.kt` | Extender con quick-create |

---

## Lo que NO está en scope

- `APPOINTMENTS_SERVICE` con slot picker (calendario + staff). v2.
- Reembolso automático del Payment al cancelar Reservation desde dashboard. La Reservation se marca CANCELLED, el dinero se devuelve manualmente.
- avoqado-android UI. Consume el `PUT /reservations/:id` cuando se construya.
- Waitlist cuando sesión está llena.
- Push/SMS al cliente cuando se asigna sesión.
- Refund parcial → cascade automático. Manual con runbook.
- Cleanup job de `SlotHold` expirados. El filter `expiresAt > NOW()` los descarta; cron opcional v1.1.

---

## Arquitectura

### Flujo end-to-end (post-Codex)

```
TPV — Cobrar                            Backend                              Dashboard
─────────────                           ─────────                            ──────────
1. Cashier tap CLASS product
   │
   ▼
2. GET /tpv/.../products/:id/sessions
   ◄── lista próximas sesiones con cupo disponible
   │
   ▼
3a. Elige sesión              3b. Pulsa "Omitir"
   │                              │
   POST /tpv/.../slot-holds       │
   {sessionId, partySize=qty,     │
    cartCorrelationId,            │
    productId, venueId}           │
   ◄── {holdId, expiresAt}        │
   │                              │
   ▼                              ▼
4. CartItem guarda {productId, classSessionId|null, slotHoldId|null}
   ├── Aumentar qty → PUT slot-hold (partySize++)
   ├── Disminuir qty → PUT slot-hold (partySize--) o DELETE si 0
   ├── Cambiar sesión → DELETE viejo, POST nuevo
   └── Quitar item → DELETE hold
   │
   ▼
5. Pulsa Cobrar → si CLASS sin customer → CustomerSelectorSheet bloqueante
   │                                       │
   │                                       └─► [+ Cliente nuevo] → POST /tpv/.../customers
   ▼
6. POST /tpv/.../orders/with-items
   Headers: Idempotency-Key: <cartCorrelationId>
   Body: { ...items, customerId, cartCorrelationId,
           items[].classSessionId, items[].slotHoldId, items[].qty }
                                            │
                                            ▼
                                  Backend: createOrderWithItems
                                  ├─ Validaciones (rechaza si):
                                  │   ├─ CLASS en items + customerId NULL → 400
                                  │   ├─ CLASS en items + paymentStatus indef PENDING → 400 (D12)
                                  │   ├─ classSessionId no pertenece a venueId/productId → 400
                                  │   └─ slotHoldId existe pero no matchea (sessionId, productId, partySize) → 400
                                  ├─ Idempotency-Key check → si Order existe, retornar igual
                                  └─ Crear Order PENDING + OrderItem con
                                     {classSessionId, slotHoldId, qty} persistidos
   ◄── { orderId, ... }
   │
   ▼
7. Navega a PaymentScreen
   │
   ▼
8. Blumon TPV procesa pago real
   │
   ├── Falla → SlotHold expira en 5min → cupo se libera solo
   └── Éxito ↓
   │
   ▼
9. POST /tpv/.../payments/record (cliente: OrderPaymentRecorder)
                                            │
                                            ▼
                                  Backend: payment.tpv.service
                                  TX (withSerializableRetry):
                                  ├─ Crear Payment status=COMPLETED
                                  ├─ Order.paymentStatus = PAID, status = COMPLETED
                                  └─ finalizeReservationsForOrder(tx, orderId, venueId)
                                        │
                                        ├─ Para cada OrderItem con classSessionId IS NOT NULL:
                                        │   ├─ SELECT ClassSession FOR UPDATE WHERE id=? AND venueId=?
                                        │   ├─ Validar capacity:
                                        │   │   ├─ active = COUNT(Reservation WHERE classSessionId=? 
                                        │   │   │           AND venueId=? AND status IN active)
                                        │   │   ├─ heldByOthers = SUM(SlotHold.partySize 
                                        │   │   │           WHERE classSessionId=? AND venueId=? 
                                        │   │   │           AND expiresAt>NOW() AND id != currentHoldId)
                                        │   │   └─ Si active + heldByOthers + qty > capacity → ROLLBACK 409
                                        │   ├─ Validar slotHoldId: 
                                        │   │   ├─ SELECT SlotHold WHERE id=currentHoldId 
                                        │   │   │   AND classSessionId=item.classSessionId
                                        │   │   │   AND venueId=order.venueId
                                        │   │   ├─ Si no matchea → ROLLBACK 409 (hold expirado o robado)
                                        │   │   └─ DELETE SlotHold (consumed atomically)
                                        │   ├─ Por cada unidad (1..qty):
                                        │   │   └─ INSERT Reservation {
                                        │   │       productId, classSessionId, customerId,
                                        │   │       orderId, orderItemId, unitIdx,
                                        │   │       status = CONFIRMED (D14),
                                        │   │       channel = TPV,
                                        │   │       depositAmount = lineTotal/qty,
                                        │   │       depositStatus = PAID,
                                        │   │       depositPaidAt = NOW(),
                                        │   │       startsAt/endsAt = session,
                                        │   │       confirmationCode, cancelSecret generados,
                                        │   │       idempotencyKey = cartCorrId:lineIdx:unitIdx,
                                        │   │       partySize = 1, venueId
                                        │   │     }
                                        ├─ Para cada OrderItem con classSessionId IS NULL (omitido):
                                        │   └─ INSERT Reservation {
                                        │       productId, classSessionId = NULL, customerId,
                                        │       orderId, orderItemId, unitIdx,
                                        │       status = PENDING,
                                        │       channel = TPV,
                                        │       depositAmount, depositStatus = PAID,
                                        │       startsAt/endsAt = NULL,
                                        │       idempotencyKey, venueId
                                        │     }
                                        └─ Commit
                                                                              10. Staff filtra
                                                                                  Reservations.tsx
                                                                                  filter: "Sin sesión"
                                                                                  │
                                                                                  ▼
                                                                              11. ReservationDetail
                                                                                  dropdown sesiones
                                                                                  PUT /reservations/:id
                                                                                  { classSessionId,
                                                                                    status: CONFIRMED }
                                                                                            │
                                                                                            ▼
                                                                                  Backend: updateReservation
                                                                                  withSerializableRetry:
                                                                                  ├─ SELECT ClassSession FOR UPDATE
                                                                                  ├─ Validar capacity
                                                                                  ├─ Validar session.venueId
                                                                                  │   == reservation.venueId
                                                                                  ├─ Validar session.productId
                                                                                  │   == reservation.productId
                                                                                  └─ UPDATE Reservation
                                                                                     SET classSessionId,
                                                                                         startsAt, endsAt,
                                                                                         status=CONFIRMED
```

### Refund cascade (D8)

```
Dashboard / TPV refund total de Payment
                  │
                  ▼
Backend: refundPayment(paymentId)
TX (withSerializableRetry):
├─ Payment.status = REFUNDED
├─ Order.paymentStatus = REFUNDED, status = CANCELLED
└─ UPDATE Reservation
   SET status = CANCELLED,
       cancelledAt = NOW(),
       cancellationReason = 'Order refunded',
       cancelledBy = staffId
   WHERE orderId = :orderId
     AND status IN ('PENDING','CONFIRMED','CHECKED_IN')

→ Capacity se libera automáticamente (filter active excluye CANCELLED)
```

---

## Cambios al modelo de datos

### `OrderItem` — añadir 2 columnas (Prisma migration)

```prisma
model OrderItem {
  ...
  // Reservation intent (CLASS / APPOINTMENTS_SERVICE)
  classSessionId String?   // si NULL = cashier eligió "Omitir"
  slotHoldId     String?   // hold creado al elegir sesión; consumido al confirmar pago
  ...

  @@index([classSessionId])
}
```

Backfill: NULL para todas las filas existentes (default seguro).

### `Reservation` — añadir 3 columnas (Prisma migration)

```prisma
model Reservation {
  ...
  // Order linkage (v2: refund cascade determinístico)
  orderId      String?  // FK a Order
  orderItemId  String?  // FK al OrderItem específico
  unitIdx      Int?     // 0..qty-1 para correlación de unidad

  order        Order?     @relation(fields: [orderId], references: [id])
  orderItem    OrderItem? @relation(fields: [orderItemId], references: [id])

  @@index([orderId])
  @@index([orderItemId])
  @@unique([orderItemId, unitIdx])  // 1 reserva por unidad por item
}
```

Backfill: NULL para reservations preexistentes (channel != TPV).

### `ReservationChannel` enum — añadir `TPV`

```sql
ALTER TYPE "ReservationChannel" ADD VALUE IF NOT EXISTS 'TPV';
```

### TPV Room — `MIGRATION_21_22`

```kotlin
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN type TEXT NOT NULL DEFAULT 'REGULAR'")
    }
}
```

Registrar en `DatabaseModule.addMigrations()` y bump `@Database(version = 22)`.

---

## Reglas de validación

### Capacity check (D6) — patron del `addAttendee`

```typescript
// services/tpv/reservation.tpv.service.ts
async function finalizeReservationsForOrder(tx, orderId, venueId) {
  return withSerializableRetry(async (tx) => {
    const items = await tx.orderItem.findMany({
      where: { orderId, classSessionId: { not: null } },
      include: { product: true }
    })

    // Agrupar por classSessionId para hacer lock + check por sesión
    const sessionsToCheck = new Map<string, OrderItem[]>()
    for (const item of items) {
      const list = sessionsToCheck.get(item.classSessionId!) ?? []
      list.push(item)
      sessionsToCheck.set(item.classSessionId!, list)
    }

    for (const [sessionId, itemsForSession] of sessionsToCheck) {
      const totalQtyForThisOrder = itemsForSession.reduce((acc, it) => acc + it.quantity, 0)

      // Lock ClassSession row con venueId scope
      const session = await tx.$queryRaw<ClassSession[]>`
        SELECT * FROM "ClassSession"
        WHERE id = ${sessionId} AND "venueId" = ${venueId}
        FOR UPDATE
      `
      if (session.length === 0) throw new BadRequestError(`Sesión inválida`)

      // Active reservations (excluye esta order si retry)
      const activeCount = await tx.reservation.count({
        where: {
          classSessionId: sessionId,
          venueId,
          status: { in: ['PENDING', 'CONFIRMED', 'CHECKED_IN'] },
          orderId: { not: orderId }, // excluye intentos previos de esta misma order
        }
      })

      // Holds activos de OTROS carritos (excluye los holds de esta order)
      const ourHoldIds = itemsForSession.map(it => it.slotHoldId).filter(Boolean) as string[]
      const otherHoldsAgg = await tx.slotHold.aggregate({
        _sum: { partySize: true },
        where: {
          classSessionId: sessionId,
          venueId,
          expiresAt: { gt: new Date() },
          id: { notIn: ourHoldIds },
        }
      })
      const heldByOthers = otherHoldsAgg._sum.partySize ?? 0

      if (activeCount + heldByOthers + totalQtyForThisOrder > session[0].capacity) {
        throw new ConflictError(`Cupo insuficiente en sesión ${sessionId}`)
      }

      // Validar y consumir holds
      for (const item of itemsForSession) {
        if (item.slotHoldId) {
          const hold = await tx.slotHold.findFirst({
            where: {
              id: item.slotHoldId,
              classSessionId: sessionId,
              venueId,
              partySize: { gte: item.quantity }, // hold debe cubrir qty
            }
          })
          if (!hold) {
            throw new ConflictError(`Hold inválido o expirado para sesión ${sessionId}`)
          }
          await tx.slotHold.delete({ where: { id: hold.id } })
        }

        // Crear N Reservations (1 por unidad)
        for (let unitIdx = 0; unitIdx < item.quantity; unitIdx++) {
          await tx.reservation.upsert({
            where: {
              idempotencyKey: `${cartCorrelationId}:${item.id}:${unitIdx}`
            },
            create: {
              productId: item.productId,
              classSessionId: sessionId,
              customerId: order.customerId!,
              orderId, orderItemId: item.id, unitIdx,
              status: 'CONFIRMED',
              channel: 'TPV',
              depositAmount: item.unitPrice,
              depositStatus: 'PAID',
              depositPaidAt: new Date(),
              startsAt: session[0].startsAt,
              endsAt: session[0].endsAt,
              duration: session[0].duration,
              confirmationCode: generateConfirmationCode(),
              cancelSecret: generateCancelSecret(),
              idempotencyKey: `${cartCorrelationId}:${item.id}:${unitIdx}`,
              partySize: 1,
              venueId,
            },
            update: {} // idempotent: ya existe = no-op
          })
        }
      }
    }

    // Reservations omitidas (sin sesión)
    const omittedItems = await tx.orderItem.findMany({
      where: { orderId, productId: { in: classProductIds }, classSessionId: null }
    })
    for (const item of omittedItems) {
      for (let unitIdx = 0; unitIdx < item.quantity; unitIdx++) {
        await tx.reservation.upsert({
          where: { idempotencyKey: `${cartCorrelationId}:${item.id}:${unitIdx}` },
          create: { ...status: 'PENDING', classSessionId: null, ...resto },
          update: {}
        })
      }
    }
  })
}
```

**Puntos clave**:
- `withSerializableRetry` wrap entero
- `FOR UPDATE` en `ClassSession` por sesión
- `venueId` en TODAS las queries
- `SUM(SlotHold.partySize)` no `count(*)`
- `id: { notIn: ourHoldIds }` para no contar contra sí mismo
- Validar que el hold cubre la qty pedida (`partySize >= quantity`)
- Validar `classSessionId == hold.classSessionId == reservation.productId == item.productId`
- Upsert con `idempotencyKey` para resistir retries

### Customer obligatorio (D11)

**UI TPV**: si cart contiene `Product.type === CLASS` y `_selectedCustomer.value == null`, `prepareForPayment()` abre `CustomerSelectorSheet` bloqueante en vez de continuar.

**Backend `createOrderWithItems`**: si cualquier OrderItem es CLASS y `req.body.customerId` es null → `400 Bad Request "Customer requerido para clases"`.

### Bloqueo de CLASS en Pagar después (D12)

**UI TPV**: en `CheckoutScreen.kt`, deshabilitar acción "Pagar después" si cart contiene CLASS. Tooltip: "No disponible para clases".

**Backend**: añadir flag en `createOrderWithItems` body:
```typescript
if (req.body.payLater === true && items.some(i => i.productType === 'CLASS')) {
  throw new BadRequestError('CLASS no admite pago diferido')
}
```

O alternativa: detectar por endpoint o por origen del request. Decisión durante implementación.

### Validation cross-tenant de session (D6/D7)

En `createOrderWithItems` y en `updateReservation`:

```typescript
if (classSessionId) {
  const session = await prisma.classSession.findFirst({
    where: {
      id: classSessionId,
      venueId, // tenant scope
      productId: item.productId, // bound a este producto
    }
  })
  if (!session) throw new BadRequestError('Sesión inválida')
  if (session.startsAt < new Date()) throw new BadRequestError('Sesión ya empezó')
}
```

### SlotHold lifecycle (D5, hallazgo Codex #14)

| Evento TPV | Llamada backend |
|---|---|
| Elegir sesión por primera vez | POST `/tpv/.../slot-holds` `{sessionId, partySize=qty, productId, venueId, cartCorrelationId}` |
| Incrementar qty del item CLASS | PUT `/tpv/.../slot-holds/:id` `{partySize: newQty}` |
| Decrementar qty | PUT con nuevo partySize; si 0 → DELETE |
| Cambiar sesión | DELETE old + POST new |
| Quitar item del cart | DELETE `/tpv/.../slot-holds/:id` |
| Pago exitoso | Hold consumido server-side en `finalizeReservationsForOrder` |
| Pago fallido / cashier sale | TTL 5min → expira solo. Cleanup cron opcional v1.1 |

**Validation backend del PUT**: recheck capacity con `FOR UPDATE` antes de aumentar partySize. Si excede → 409.

### Idempotency (D9)

Dos niveles, no se confunden:

| Nivel | Mecanismo | Qué dedupea |
|---|---|---|
| **Order** | Header `Idempotency-Key: <cartCorrelationId>` en POST `/orders/with-items`. Backend usa pattern estándar (cache de 24h). | Evita duplicar Order si TPV retry. |
| **Reservation** | Campo `idempotencyKey = ${cartCorrelationId}:${orderItemId}:${unitIdx}` con UNIQUE constraint. Upsert. | Evita duplicar Reservation si el hook post-pago retry. |

`cartCorrelationId` se genera en TPV cuando `ActiveCartState` se inicia, se persiste en SecureStorage, sobrevive crashes.

---

## Estados y transiciones

### Reservation

```
                ┌─────────┐
                │  null   │ (no existe)
                └────┬────┘
                     │ finalizeReservationsForOrder (post-pago)
                     │
            ┌────────┴────────┐
            │                 │
   (sin classSessionId)   (con classSessionId)
            │                 │
            ▼                 ▼
       ┌─────────┐       ┌───────────┐
       │ PENDING │       │ CONFIRMED │
       └────┬────┘       └─────┬─────┘
            │ updateReservation │
            │ (assign session)  │
            └──────────►────────┤
                                │ checkIn
                                ▼
                          ┌────────────┐
                          │ CHECKED_IN │──► COMPLETED
                          └────────────┘
            ┌──────────────┘     ┌────────────┐
            │                    │ refund      │
            ▼                    ▼             │
       ┌───────────┐  ◄───────────────────────┘
       │ CANCELLED │
       └───────────┘
```

**Decisión clave (D14)**: si la Reservation se crea con `classSessionId`, va directamente a `CONFIRMED` (no a `PENDING`). Esto evita el problema señalado por Codex (hallazgo #16) — dashboards/check-in pueden contar attendance solo en CONFIRMED.

`PENDING` se reserva exclusivamente para reservations omitidas (sin sesión asignada).

### SlotHold

```
POST hold  ──► ACTIVE ─┬──► PUT hold (qty change)   ──► ACTIVE actualizado
                       ├──► DELETE hold (cambio/remove) ──► gone
                       ├──► finalizeReservations TX ──► gone + Reservation CONFIRMED
                       └──► expiresAt < NOW()       ──► ignored
```

### Order/Payment timing

```
CART → POST /orders/with-items  ──► Order PENDING+PENDING (con classSessionId/slotHoldId en items)
                                          │
                                          ▼
                                    PaymentScreen (Blumon TPV)
                                          │
                              ┌───────────┼───────────┐
                              ▼                       ▼
                          fallo/abort              éxito
                              │                       │
                  (Order queda PENDING,         POST /payments/record
                   SlotHold expira solo)             │
                                                     ▼
                                      Backend TX:
                                        Payment COMPLETED
                                        Order PAID/COMPLETED
                                        finalizeReservationsForOrder(...)
                                        ↓
                                      Reservations CONFIRMED (con sesión)
                                                 o PENDING (sin sesión)
```

---

## Pruebas requeridas

### Backend — `finalizeReservationsForOrder`

```
[★★★] cobro CLASS con sesión válida → 1 Reservation CONFIRMED con classSessionId, hold consumido
[★★★] cobro CLASS omitido → 1 Reservation PENDING con classSessionId=NULL
[★★★] cobro 2x misma CLASS misma sesión qty=2 → 2 Reservations CONFIRMED, capacity -2
[★★★] cobro 2x diferentes CLASSes diferentes sesiones en 1 cart → 2 Reservations correctas
[★★★] cobro CLASS con sesión llena (reservations) → 409, ROLLBACK Payment+Order+Reservations
[★★★] cobro CLASS con sesión llena por OTROS SlotHolds → 409
[★★★] cobro con MI slotHoldId no debe contar contra mí (regresión hallazgo Codex #7)
[★★★] cobro con slotHoldId que NO matchea sessionId/venueId → 409
[★★★] cobro con classSessionId de otro venue → 400 (cross-tenant)
[★★★] cobro con classSessionId de otro producto → 400
[★★★] cobro con sesión startsAt < NOW() → 400
[★★★] REGRESIÓN: cobro de producto NO-CLASS (FOOD/REGULAR) no crea Reservations
[★★★] cobro CLASS sin customerId → 400
[★★★] cobro CLASS + payLater → 400
[★★★] retry post-pago con mismo cartCorrelationId → no duplica Reservations (upsert idempotent)
[★★★] retry createOrderWithItems con mismo Idempotency-Key → retorna mismo Order id (no duplica)
[★★★] race: 2 cobros concurrentes a misma última silla → 1 gana, otro 409 (verifica withSerializableRetry retry)
[★★]  SUM(SlotHold.partySize) correcto cuando hold tiene partySize=3 (regresión hallazgo Codex #4)
```

### Backend — refund cascade

```
[★★★] refund total Order con 2 Reservations CONFIRMED → ambas CANCELLED, capacity liberada
[★★★] refund total Order con 1 CONFIRMED + 1 PENDING → ambas CANCELLED
[★★]  refund parcial → Reservations NO se tocan (documentado, manual)
[★★]  refund 2 veces (idempotente) → no error, status sigue CANCELLED
```

### Backend — assign-session vía updateReservation

```
[★★★] PUT con classSessionId disponible → status CONFIRMED, capacity validada con FOR UPDATE
[★★★] PUT con sesión llena → 409
[★★★] PUT con sesión de otro venue → 400 (cross-tenant)
[★★★] PUT con sesión de otro producto → 400
[★★]  PUT ya CONFIRMED → reasigna, libera viejo, ocupa nuevo (capacity correcta en ambas sesiones)
```

### Backend — SlotHold lifecycle

```
[★★★] POST hold → ACTIVE con expiresAt
[★★★] PUT hold partySize aumentado pero capacity insuficiente → 409
[★★★] DELETE hold inexistente → 404
[★★★] hold con expiresAt vencido → ignorado en queries (verifica con NOW())
```

### TPV — UI

```
[★★★] tap producto CLASS → SessionPickerSheet aparece con sesiones próximas (venueId scoped)
[★★★] SessionPickerSheet con 0 sesiones → solo botón "Omitir"
[★★★] elegir sesión → POST slot-hold, cart guarda holdId
[★★★] pulsar "Omitir" → cart guarda classSessionId=null, sin hold
[★★★] Cobrar CLASS sin customer → CustomerSelectorSheet bloqueante
[★★★] CustomerSelectorSheet quick-create → POST /tpv/.../customers, selecciona auto
[★★★] qty++ en item CLASS → PUT slot-hold con partySize+1
[★★★] qty-- en item CLASS → PUT con partySize-1, o DELETE si 0
[★★★] cambiar sesión → DELETE viejo, POST nuevo
[★★★] quitar item CLASS → DELETE hold
[★★★] cart con CLASS deshabilita botón "Pagar después" (tooltip explicativo)
[★★]  reinicio del TPV mid-cobro → cartCorrelationId persistido en SecureStorage permite recovery
```

### E2E

```
[→E2E] Cobro con sesión:
  1. PlayTelecom Centro, cart con "[TEST] Clase de Prueba"
  2. SessionPickerSheet muestra "Lun 18 May 19:00, 8/10"
  3. Cajero elige sesión → SlotHold creado (capacity efectiva 7/10 para otros)
  4. Quick-create customer "test@ejemplo.com"
  5. Blumon TPV procesa pago (sandbox éxito)
  6. POST /payments/record → finalizeReservationsForOrder
  7. → DB: Order COMPLETED+PAID, Payment COMPLETED, Reservation CONFIRMED con orderId+orderItemId+unitIdx=0
  8. → SlotHold borrado
  9. ClassSession capacity efectiva = 9 (1 reservation activa)
  10. Dashboard: NO aparece en "Sin sesión asignada" (tiene sesión)

[→E2E] Cobro "Omitir" + asignación dashboard:
  1. Cobrar "[TEST] Clase de Prueba" con Omitir
  2. → Reservation PENDING con classSessionId=NULL
  3. Dashboard Reservations.tsx con filter "Sin sesión asignada" la lista
  4. ReservationDetail dropdown → elige "Lun 18 May 19:00"
  5. PUT /reservations/:id → CONFIRMED, classSessionId, capacity correctamente decrementa

[→E2E] Refund cascade:
  1. Cobrar 2x "[TEST] Clase de Prueba" sesión X
  2. → 2 Reservations CONFIRMED, capacity X = -2
  3. Refund total del Payment desde dashboard
  4. → ambas Reservations CANCELLED, capacity X = +2 (restaurada)

[→E2E] Race condition:
  1. Sesión con capacity=1, cero reservations
  2. Cajero A elige sesión → SlotHold A (partySize=1, expira en 5min)
  3. Cajero B intenta elegir misma sesión → 409 (heldByOthers + 1 > 1)
  4. Cajero B elige otra sesión, prosigue
  5. Cajero A completa pago → Reservation A CONFIRMED, hold A consumido
```

---

## Migraciones (orden)

1. **Backend Prisma migration**:
   - `ALTER TYPE "ReservationChannel" ADD VALUE IF NOT EXISTS 'TPV';`
   - `ALTER TABLE "OrderItem" ADD COLUMN "classSessionId" TEXT, ADD COLUMN "slotHoldId" TEXT;`
   - Indices en OrderItem
   - `ALTER TABLE "Reservation" ADD COLUMN "orderId" TEXT, ADD COLUMN "orderItemId" TEXT, ADD COLUMN "unitIdx" INT;`
   - FKs Reservation → Order y Reservation → OrderItem (con cascade rules apropiadas)
   - UNIQUE `(orderItemId, unitIdx)` para evitar duplicados de unidad

2. **TPV Room migration 21→22**:
   - Add `type` column a products
   - Registrar en `DatabaseModule.addMigrations()`
   - Bump `@Database(version = 22)`

3. **Backfill**: no aplica. Filas existentes quedan con NULL en columnas nuevas (correcto).

---

## Riesgos y mitigaciones

| Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|
| `withSerializableRetry` causa retries excesivos bajo alta concurrencia | Media | Latencia + timeouts | Default 3 retries; log + métricas; tunear si se observa contención |
| TPV crashea entre POST hold y cobro | Alta | SlotHold huérfano | TTL 5min lo libera solo. Cleanup cron v1.1 |
| Pago Blumon éxito pero hook post-pago falla | Baja | Pago cobrado sin reservation | Hook está en la misma TX; falla = rollback = no se marca PAID. Si se separan algún día, requiere outbox pattern |
| `Idempotency-Key` cache TTL muy corto | Baja | Duplicates en retry tardío | Default 24h estándar; documentar |
| Cajero acumula muchos holds sin pagar | Baja | Cupo bloqueado N minutos | TTL 5min máximo. Dashboard puede ver holds activos para debug |
| Cliente trata de cobrar con classSessionId de otro venue (malicia) | Baja | Cross-tenant breach | Validation explícita rechaza con 400 |
| Migration Prisma falla en producción | Baja | Down | `ADD VALUE IF NOT EXISTS`; backfill NULL safe; rollback script |
| TPV cachea producto sin `type` después de migration Room | Media | Producto sin tipo | MIGRATION_21_22 con default `REGULAR`; refresh forzado tras update APK |
| `PENDING` vs `CONFIRMED` semantics conflicto con check-in flows existentes | Media | Cupo no contado en dashboards | D14: usar CONFIRMED si hay sesión. Investigar antes de impl. |

---

## Estimación de esfuerzo (revisado post-Codex)

| Repo | Trabajo | Días |
|---|---|---|
| **avoqado-server** | • Migrations (OrderItem + Reservation + enum)<br>• Endpoints nuevos: GET sessions, POST/PUT/DELETE slot-holds<br>• Modificar `createOrderWithItems`: validations (customer/payLater/cross-tenant), persistir intent en OrderItem<br>• Nuevo `finalizeReservationsForOrder` service<br>• Hook en `payment.tpv.service` puntos 340/461/2215<br>• Refund cascade en refund service<br>• Extender `updateReservation` con validations<br>• Tests unitarios + integration | **5-6** |
| **avoqado-tpv** | • Propagar `Product.type` (DTO→domain→cart→cache)<br>• Migration Room 21→22, registrar<br>• `SessionPickerSheet` + ClassSession DTO/repo<br>• Generación + persistencia de `cartCorrelationId`<br>• SlotHold lifecycle completo (create/update/delete según qty)<br>• Extender `CustomerSelectorSheet` con quick-create form<br>• Customer-gate en `prepareForPayment`<br>• Block UI "Pagar después" si CLASS en cart<br>• Tests | **6-7** |
| **avoqado-web-dashboard** | • Filter chip "Sin sesión asignada"<br>• Dropdown asignación en ReservationDetail<br>• Llamada a `updateReservation` con clase | **1.5-2** |
| **QA cross-repo + E2E** | • Probar en sandbox con PAX A910S<br>• 4 escenarios E2E listados<br>• Cargo testing race condition | **1.5-2** |
| **Investigación previa D14** | • Verificar semántica PENDING vs CONFIRMED en dashboards existentes<br>• Decidir si crear directo CONFIRMED o usar PENDING+upgrade | **0.5** |
| **Total** | | **14.5-17.5** |

---

## Decisiones pendientes (resolver antes o durante implementación)

1. **D14 — PENDING vs CONFIRMED para reservations con sesión asignada**: investigar dashboards/check-in. Si esperan CONFIRMED para contar attendance, el hook crea directamente CONFIRMED. Si manejan PENDING + auto-upgrade, podemos crear PENDING.

2. **Cuántas sesiones muestra el `SessionPickerSheet`**: propuesta = próximas 14 días, paginado si >20. A confirmar con el cliente.

3. **¿Customer obligatorio aplica también si "Omitir"?**: propuesta = **sí, siempre obligatorio para CLASS** independiente de elegir sesión o no. Sin customer = reservation huérfana inútil.

4. **¿Mostrar `confirmationCode` al cliente al cobrar?**: propuesta = imprimirlo en ticket en v1.1, guardar en BD por ahora.

5. **TTL del SlotHold**: propuesta = 5 minutos. Si el flujo Blumon promedio toma 30s, sobra margen. A ajustar según telemetría.

6. **Cleanup cron de SlotHold expirados**: v1 sin él (queries filtran por `expiresAt > NOW()`). v1.1 añade cron diario si la tabla crece descontroladamente.

7. **Cómo detectar "Pagar después" en backend para el bloqueo de CLASS**: opción A = flag explícito `payLater: true` en el request body; opción B = endpoint separado para pay-later; opción C = inferir por `paymentStatus` ausente. Decidir durante implementación según el código actual de `createOrderWithItems`.
