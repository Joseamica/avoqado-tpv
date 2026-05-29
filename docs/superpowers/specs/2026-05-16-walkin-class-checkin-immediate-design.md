# Walk-in de clase: inscribir + check-in + Order en un paso

Fecha: 2026-05-16
Autor: Jose Antonio Amieva
Status: APPROVED FOR PLANNING
Repos afectados: `avoqado-server`, `avoqado-android`
Repos NO afectados: `avoqado-tpv`, `avoqado-ios`, `avoqado-consumer-app`, `avoqado-booking-widget`

## Problema

Cliente walk-in llega a una clase de yoga. Hoy el cajero hace 3 navegaciones:

```
1. Calendar → tap ClassSession → "Agregar asistente" → Reservation CONFIRMED  ✅ cupo decrementa
2. Salir → ir a vista de reservaciones → buscar la reserva → check-in        ✅ Order PENDING creada
3. Salir → ir a Cobrar → encontrar la Order → procesar pago Blumon            ✅ cobrado
```

Debería ser un solo flujo: "Inscribir y cobrar" → llega a `PaymentFlowScreen` con la Order ya armada.

## Lo que ya existe (no reinventar)

| Pieza | Ubicación | Estado |
|---|---|---|
| `addAttendee` con `FOR UPDATE`, `SUM(partySize)`, `withSerializableRetry`, crea Reservation con `productId` + `status=CONFIRMED` | `avoqado-server/src/services/dashboard/classSession.dashboard.service.ts:498` | ✅ Producción |
| `transitionReservation(..., 'CHECKED_IN')` | `avoqado-server/src/services/dashboard/reservation.dashboard.service.ts:625` | ✅ Producción |
| `createOrderFromReservation` idempotente, crea Order + OrderItems + modifiers | `avoqado-server/src/services/reservation/createOrderFromReservation.ts` | ✅ Producción |
| `Order.reservationId` FK | Migración aditiva | ✅ Producción |
| `ClassSessionApi.addAttendee` cliente Android | `avoqado-android/app/.../reservations/data/ClassSessionApi.kt` | ✅ Producción |
| `AddAttendeeRequest` Android model | `avoqado-android/app/.../reservations/data/model/ClassSession.kt:106` | ✅ Producción |
| `ClassSessionDetailViewModel.addCustomer/addGuest` | `avoqado-android/app/.../classsessions/ClassSessionDetailViewModel.kt:63-84` | ✅ Producción |
| `ClassSessionDetailScreen.AddAttendeeSheet` | `avoqado-android/app/.../classsessions/ClassSessionDetailScreen.kt:241-309` | ✅ Producción |
| `PaymentFlowScreen` que recibe `orderId` | `avoqado-android/app/.../payment/presentation/PaymentFlowScreen.kt` | ✅ Producción |

## Lo que NO está en scope

- Cambios al TPV. La Order generada se cobra con el flow Cobrar/PaymentFlow existente.
- Cobro inmediato sin Reservation (la regla del negocio dice: clase con cupo → siempre Reservation).
- Pre-pago / depósito antes de check-in. El check-in y el cobro son simultáneos en este flow.
- Tarifas por persona distintas dentro del mismo cobro. `partySize=3` cobra **3× el mismo precio** (1 Reservation `partySize=3`, 1 OrderItem `quantity=3`). Si las 3 personas pagan distinto, el cajero crea 3 reservations individuales (1 con cobro, 2 sólo inscribir).

---

## Diseño

### Server: extender `addAttendee` con `checkInImmediately`

**Schema** (`src/schemas/dashboard/classSession.schema.ts:55`):

```typescript
export const addAttendeeSchema = z.object({
  guestName: z.string().min(1).max(255),
  guestPhone: z.string().min(6).optional().nullable(),
  guestEmail: z.string().email().optional().nullable(),
  partySize: z.number().int().min(1).default(1),
  specialRequests: z.string().max(2000).optional().nullable(),
  customerId: z.string().cuid().optional().nullable(),
  checkInImmediately: z.boolean().default(false),   // ← NUEVO
})
```

**Service** (`src/services/dashboard/classSession.dashboard.service.ts:498`):

```typescript
export async function addAttendee(venueId, sessionId, data, staffId) {
  // ... validación customer + withSerializableRetry existentes ...
  return withSerializableRetry(async tx => {
    // ... FOR UPDATE ClassSession + capacity check existentes ...
    
    const reservation = await tx.reservation.create({
      data: {
        // ... campos existentes ...
        status: data.checkInImmediately ? 'CHECKED_IN' : 'CONFIRMED',
        checkedInAt: data.checkInImmediately ? new Date() : null,
      },
      include: { customer: { select: { id: true, firstName: true, lastName: true } } },
    })
    
    // ... gcal push outbox existente ...

    // NUEVO: si checkInImmediately, crear la Order en la MISMA TX
    let orderId: string | null = null
    if (data.checkInImmediately) {
      const result = await createOrderFromReservation(tx, {
        reservationId: reservation.id,
        venueId,
        createdByStaffId: staffId,
      })
      orderId = result?.orderId ?? null
    }

    return { reservation, orderId }
  })
}
```

**Controller** (`src/controllers/dashboard/classSession.dashboard.controller.ts:109`):

Cambiar el shape del response para acomodar `{ reservation, orderId }` cuando viene la flag. Mantener retrocompat: si `checkInImmediately` es false (default), seguir respondiendo solo la reservation aplanada como hoy.

```typescript
const result = await classSessionService.addAttendee(venueId, sessionId, req.body, userId)
if (req.body.checkInImmediately) {
  return res.status(201).json({ reservation: result.reservation, orderId: result.orderId })
}
return res.status(201).json(result.reservation)
```

**Cero migraciones de BD.** Todo se logra con campos existentes.

### Server: ajustar `createOrderFromReservation` para honrar `partySize`

Hoy el helper hardcodea `quantity: 1` (`createOrderFromReservation.ts:113` y `:188`). Cambiar a `quantity: reservation.partySize` para que `partySize=3` cobre 3× automático. Tax y subtotal se calculan desde `quantity * unitPrice` así que la matemática se ajusta sola.

```typescript
// línea ~113
const lineSubtotal = unitPrice.mul(reservation.partySize)  // antes: unitPrice
const lineTax = lineSubtotal.mul(taxRate)

// línea ~188 (dentro del orderItem.create)
quantity: reservation.partySize,  // antes: 1
```

Modifiers también escalan por partySize (3 personas, 3× el modifier "Esmalte de color +$150"). Verificar línea ~122 (`new Prisma.Decimal(m.price).mul(m.quantity)`) — si `m.quantity` es 1 hoy, multiplicar también por `partySize` o por `m.quantity * partySize`. Decisión durante impl al leer el modifier schema.

### Android: dos botones en el sheet + navegación

**Model** (`data/model/ClassSession.kt:106`):

```kotlin
@Serializable
data class AddAttendeeRequest(
    val guestName: String,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int = 1,
    val specialRequests: String? = null,
    val customerId: String? = null,
    val checkInImmediately: Boolean = false,  // NUEVO
)
```

Necesita una `AddAttendeeResponse` envoltura nueva para cuando viene `orderId`. O parsing tolerante: si la response trae top-level `orderId`, capturarlo; si no, asumir que es la reservation aplanada.

**Stepper de partySize en el sheet** — hoy no existe; default fijo 1. Para que opción B se note, agregar control:

```kotlin
// Dentro de AddAttendeeSheet, arriba de los botones de acción
Column(Modifier.padding(horizontal = AvoqadoTheme.spacing.lg)) {
    Text("Lugares", style = MaterialTheme.typography.labelMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = false, onClick = { partySize = (partySize - 1).coerceAtLeast(1) }, label = { Text("-") })
        Text("$partySize", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = AvoqadoTheme.spacing.md))
        FilterChip(selected = false, onClick = { partySize++ }, label = { Text("+") })
        Spacer(Modifier.weight(1f))
        // Preview del total — solo en tab "Inscribir y cobrar"
        Text("$partySize × $${session.productPrice} = $${partySize * session.productPrice}",
             style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
```

Cap superior = `session.capacity - session.enrolled` (no permitir pedir 5 lugares en una sesión con 3 disponibles). `ClassSession` ya expone `enrolled` y `capacity`; el `productPrice` puede o no estar en el shape actual de `ClassSession` — verificar durante impl, posible añadirlo al DTO del server.

**ViewModel** (`presentation/classsessions/ClassSessionDetailViewModel.kt`):

```kotlin
fun addCustomerAndCharge(customer: Customer, onOrder: (String) -> Unit) {
    addAttendeeAndCharge(
        AddAttendeeRequest(
            customerId = customer.id,
            guestName = customer.fullName,
            guestPhone = customer.phone,
            guestEmail = customer.email,
            checkInImmediately = true,
        ),
        onOrder,
    )
}

fun addGuestAndCharge(name: String, phone: String?, email: String?, onOrder: (String) -> Unit) {
    addAttendeeAndCharge(
        AddAttendeeRequest(
            guestName = name,
            guestPhone = phone?.takeIf { it.isNotBlank() },
            guestEmail = email?.takeIf { it.isNotBlank() },
            checkInImmediately = true,
        ),
        onOrder,
    )
}

private fun addAttendeeAndCharge(request: AddAttendeeRequest, onOrder: (String) -> Unit) {
    val s = _session.value
    if (s != null && s.enrolled >= s.capacity) {
        _message.value = Result.failure(Exception("Sesión llena (${s.enrolled}/${s.capacity})"))
        return
    }
    viewModelScope.launch {
        _isSubmitting.value = true
        repository.addAttendeeWithOrder(sessionId, request).onSuccess { (_, orderId) ->
            refresh()
            orderId?.let(onOrder)  // navegar a PaymentFlowScreen
        }.onFailure {
            _message.value = Result.failure(it)
        }
        _isSubmitting.value = false
    }
}
```

`repository.addAttendeeWithOrder` retorna `Result<Pair<ClassSessionAttendee, String?>>`.

**Screen** (`presentation/classsessions/ClassSessionDetailScreen.kt:240`):

`AddAttendeeSheet` cambia los botones:

```kotlin
// Tab "Cliente existente": cada Row de customer → 2 acciones
Row(modifier = Modifier.fillMaxWidth().padding(vertical = AvoqadoTheme.spacing.md)) {
    Text(customer.fullName, modifier = Modifier.weight(1f))
    IconButton(onClick = { viewModel.addCustomer(customer); onDone() }) {
        Icon(Icons.Filled.PersonAdd, "Solo inscribir")
    }
    IconButton(onClick = { 
        viewModel.addCustomerAndCharge(customer) { orderId -> onChargeOrder(orderId) }
        onDone()
    }) {
        Icon(Icons.Filled.PointOfSale, "Inscribir y cobrar")
    }
}

// Tab "Invitado": dos PrimaryButton apilados
PrimaryButton(
    text = "Inscribir y cobrar",
    onClick = {
        viewModel.addGuestAndCharge(guestName, guestPhone, guestEmail) { orderId -> 
            onChargeOrder(orderId) 
        }
        onDone()
    },
    enabled = guestName.isNotBlank(),
    fullWidth = true,
)
OutlinedButton(  // secundario
    onClick = {
        viewModel.addGuest(guestName, guestPhone, guestEmail)
        onDone()
    },
    enabled = guestName.isNotBlank(),
    modifier = Modifier.fillMaxWidth(),
) { Text("Solo inscribir") }
```

`onChargeOrder` viene del callsite de `ClassSessionDetailScreen` y rutea a `PaymentFlowScreen(orderId)`.

### Navegación

`ClassSessionDetailScreen` añade un callback nuevo `onChargeOrder: (String) -> Unit`. Su composable padre (probablemente `AvoqadoNavGraph.kt`) lo cablea a la ruta de `PaymentFlowScreen`. Lo mismo que ya hace cualquier callsite que abre `PaymentFlowScreen` con un `orderId`.

---

## Validación atómica (lo que ya hace `withSerializableRetry` + lo que añadimos)

```
addAttendee TX:
  1. SELECT ClassSession FOR UPDATE              ← lock
  2. SUM(Reservation.partySize) capacity check   ← race-safe
  3. INSERT Reservation status=CHECKED_IN        ← cupo consumido
  4. createOrderFromReservation(tx, ...):
     - SELECT Order WHERE reservationId=? (idempotent)
     - if exists → return existing orderId
     - else → INSERT Order + OrderItems + Modifiers
  5. COMMIT

Si cualquier paso falla → ROLLBACK total → cupo no se consumió, Order no existe.
Cliente sigue sin ser inscrito. Cashier reintenta.
```

`createOrderFromReservation` ya es idempotente (línea 53-58 del archivo) — si la TX se reintenta por retry serializable, no duplica Order.

---

## Casos edge

| Caso | Comportamiento |
|---|---|
| Sesión llena entre tap del cashier y submit del sheet | Capacity check dentro de la TX → 409 → mensaje "Sesión llena" en sheet, no se inscribe nada |
| Cliente paga, después quiere cancelar la clase | Existing flow de `cancelReservation` (libera cupo) + refund manual del Payment desde dashboard |
| Cliente quiere comprar paquete de 10 clases sin asignar sesión | Fuera de scope. Eso es el flow de creditPacks que ya existe (`CreditPackItem`/`CreditItemBalance`). |
| `partySize > 1` (familia de 3) | **1 Reservation `partySize=3`, 1 OrderItem `quantity=3`, total = 3× precio.** Helper recalcula subtotal/tax. Cupo decrementa 3. Check-in entra los 3 juntos. Si una persona del grupo falta: `cancelReservation` cancela la reserva entera (no parcial). Para tarifas distintas por persona: 3 reservations individuales. |
| Cliente no quiere dejar nombre/email | Tab "Invitado" requiere mínimo nombre. Sin teléfono/email es OK. |
| `checkInImmediately=true` pero la reservation no logra crear Order (reservation sin productId — caso extraño) | `createOrderFromReservation` retorna null → `orderId` queda null → Android no navega a payment, queda inscrito como CHECKED_IN sin Order. Toast "Asistente inscrito, no se generó cobro" + log de warning. Caso raro: solo pasa si la ClassSession existe pero su productId apunta a un producto soft-deleted. |
| Pago en `PaymentFlowScreen` falla / cliente se arrepiente | Order ya existe, queda PENDING/PENDING. Cajero puede reintentar o cancelar la Order desde el POS (existing flow). La Reservation queda CHECKED_IN aunque no haya cobro — esto es OK porque el cliente sí entró a la clase. Si quieren expulsarlo: usar `cancelReservation` que ya libera cupo. |

---

## Pruebas

### Backend (server)

```
[★★★] addAttendee con checkInImmediately=false → Reservation CONFIRMED, NO Order, response shape sin orderId
[★★★] addAttendee con checkInImmediately=true → Reservation CHECKED_IN + Order PENDING, response shape con orderId
[★★★] addAttendee con checkInImmediately=true + sesión llena → 409, NO Reservation, NO Order
[★★★] addAttendee con checkInImmediately=true + productId de la ClassSession apunta a producto deletedAt!=null → Reservation CHECKED_IN sin Order (orderId=null), warning loggeado
[★★★] race: 2 cobros concurrentes para la última silla con checkInImmediately=true → 1 gana, otro 409 (verifica que withSerializableRetry sigue funcionando con la lógica extendida)
[★★★] REGRESIÓN: addAttendee sin flag (cliente Android viejo) sigue retornando shape anterior, status=CONFIRMED
[★★]  idempotencia interna: si la TX se reintenta por serializable retry, no se duplica Order (createOrderFromReservation idempotent)
[★★★] addAttendee con `partySize=3, checkInImmediately=true` → 1 Reservation partySize=3, Order con OrderItem quantity=3, total = 3 × productPrice, cupo de la sesión -3
[★★★] addAttendee con `partySize=3` + sesión con 2 cupos disponibles → 409 (cap check existente ya cubre esto)
[★★]  partySize=3 con modifiers picked → cada modifier multiplicado correctamente (verifica el comportamiento al ajustar createOrderFromReservation)
```

### Android

```
[★★★] AddAttendeeSheet → tap "Inscribir y cobrar" cliente existente → llama addCustomerAndCharge → navega a PaymentFlowScreen con orderId
[★★★] AddAttendeeSheet → tap "Solo inscribir" → no navega, cierra sheet, muestra toast "Asistente agregado"
[★★★] AddAttendeeSheet → "Inscribir y cobrar" invitado con nombre vacío → botón disabled
[★★★] Response sin orderId (server retornó shape antiguo) → toast "Asistente inscrito" sin navegación
[★★★] Sesión llena 409 → snackbar "Sesión llena (10/10)", no navega
[★★★] Stepper de partySize: tap "+" 2 veces → muestra "3 lugares", preview total "3 × $250 = $750", al cobrar pasa partySize=3 al server
[★★★] Stepper cap superior: sesión con `enrolled=8, capacity=10` → stepper no permite subir partySize > 2
[★★]  Cancelar PaymentFlowScreen → vuelve atrás sin cobrar; Order queda PENDING (visible en lista de pendientes)
```

### E2E (con PAX / dispositivo Android real)

```
[→E2E] Walk-in completo:
  1. Calendar día → tap ClassSession "Yoga 7pm"
  2. "Agregar asistente" → tab Invitado → "Juan" + teléfono + "Inscribir y cobrar"
  3. Sheet cierra → app navega a PaymentFlowScreen con Order pre-poblada
  4. Cobrar con Blumon (sandbox) → éxito
  5. Volver a ClassSession → "Asistentes (1/10)", Juan aparece como CHECKED_IN
  6. DB: Order COMPLETED/PAID, Reservation CHECKED_IN, ClassSession capacity efectiva 9
```

---

## Estimación

| Pieza | Esfuerzo |
|---|---|
| Server: schema + service + controller + tests | 0.5 día |
| Server: `createOrderFromReservation` honra `partySize` + tests | 0.25 día |
| Android: model + repo + viewmodel + screen + nav + tests | 1 día |
| Android: stepper de partySize + preview de total | 0.25 día |
| QA E2E (incluye caso `partySize=3`) | 0.5 día |
| **Total** | **2.5 días** |

(Sin gate de investigaciones — todo lo que tenía v3 como `I1-I4` se resolvió leyendo el código.)

---

## Decisiones cerradas durante el análisis

- **D1 PENDING vs CONFIRMED**: las Reservations creadas vía `addAttendee` ya son `CONFIRMED`. Cuando `checkInImmediately=true` arrancan en `CHECKED_IN`. No hay ambigüedad.
- **D2 venueId scope**: `addAttendee` ya valida customer.venueId === sessionId.venueId. Reusable.
- **D3 SlotHold**: no aplica. `addAttendee` opera con la sesión directamente; el SlotHold es del flow online (booking-widget / consumer-app) donde hay tiempo entre pick y pago.
- **D4 Idempotency**: `createOrderFromReservation` es idempotente por `(reservationId, venueId)`. Si Android reintenta el POST, server retornará la misma Order existente.
- **D5 partySize**: **Opción B locked.** 1 Reservation `partySize=N`, 1 OrderItem `quantity=N`, cobra N× el precio automáticamente. `createOrderFromReservation` se ajusta (2 líneas + tax recalc). UI de Android añade stepper de partySize para que el cajero lo controle. Si una sola persona del grupo falta el día de la clase, la cancelación es total (cancela la Reservation completa); para tarifas distintas por persona, el cajero crea reservations individuales.
