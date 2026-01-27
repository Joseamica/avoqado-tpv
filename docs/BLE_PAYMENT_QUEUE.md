# BLE Payment Queue (TPV)

## Objetivo
Permitir que **múltiples dispositivos externos** (iPad/iPhone u otros) envíen cobros por BLE
sin interrumpir un pago activo. El TPV debe:

1. Aceptar múltiples solicitudes simultáneas.
2. Procesar **una sola** solicitud de pago activa a la vez.
3. Mostrar una **cola** de solicitudes para que el staff decida cuándo atenderlas.

> Regla principal: **nunca interrumpir** un pago en curso.

---

## Principios (Patrón Square/Stripe/Clover)

- **Single‑active payment**: solo un flujo activo por terminal.
- **Queue FIFO**: solicitudes posteriores quedan en cola.
- **UI no intrusiva**: banner/chip con contador + lista para aceptar/rechazar.
- **Expiración**: solicitudes viejas expiran automáticamente.
- **No bloqueo**: el staff puede ignorar la cola y seguir operando.

---

## Modelo de Datos (entrada BLE)

Se recibe desde BLE en JSON (centavos):

```
{
  "requestId": "uuid-optional",
  "amount": 12000,
  "tip": 2000,
  "rating": 5,
  "skipReview": true,
  "deviceId": "AA:BB:CC:DD:EE:FF"
}
```

Campos:
- `amount` (required): total base en centavos.
- `tip` (optional): propina en centavos.
- `rating` (optional): 1–5.
- `skipReview` (optional): true si el review/tip ya se hizo en el dispositivo externo.
- `requestId` (optional): UUID para deduplicación.
- `deviceId` (optional): identificador del dispositivo BLE.

> Si no se envía `requestId`, generar uno local.

---

## Estados de la Cola

`PENDING` → `ACTIVE` → `COMPLETED`
  
`PENDING` → `REJECTED`
  
`PENDING` → `EXPIRED`

Reglas:
- **Solo 1 ACTIVE** a la vez.
- `PENDING` se atienden en FIFO.
- `EXPIRED` si excede TTL (ej. 3–5 minutos).

---

## Dedupe / Idempotencia

Si llega una solicitud con el mismo `requestId`:
- Si ya existe en `PENDING` o `ACTIVE`, **ignorar** la nueva.
- Si está `COMPLETED/REJECTED/EXPIRED`, opcionalmente **rechazar** con respuesta BLE (futuro).

Si no hay `requestId`, usar clave compuesta:
`deviceId + amount + tip + rating + createdAt_window`.

---

## Flujo de UI

1. **Pago activo** → mantener flujo intacto.
2. **Nuevas solicitudes** → mostrar banner:  
   `🔵 Solicitudes BLE (N)`.
3. **Lista** (BottomSheet/Screen):
   - Monto, propina, rating, hora, deviceId.
   - Acciones: **Aceptar**, **Rechazar**, **Ver detalles**.
4. **Al terminar/cancelar pago activo**:
   - Mostrar prompt: “¿Atender siguiente solicitud?”.

---

## Integración con Payment Flow

Cuando se acepta una solicitud `BlePaymentRequest`:

- Si `skipReview=true` **o** trae `tip/rating`:
  - Ir directo a `SelectingMerchant` usando `submitAmountWithExternalInputs(...)`.
- Si faltan tip/rating y los settings los requieren:
  - Caer al flujo normal (`submitAmount(...)`) y pedir en el TPV.

> **Nunca** navegar automáticamente si ya estás en PaymentScreen.

---

## Persistencia (opcional)

Dos niveles:

1. **Memoria (StateFlow)**: cola temporal (default).
2. **Room**: si quieres sobrevivir a reinicios.

Si se persiste:
- Guardar `status`, `createdAt`, `processedAt`.
- Limpiar `EXPIRED` al iniciar.

---

## Testing Checklist

- Enviar 3 solicitudes seguidas mientras hay un pago activo:
  - Se encolan (no se bloquea ni se interrumpe).
- Finalizar pago activo:
  - Prompt de siguiente solicitud.
- Rechazar solicitud:
  - Desaparece de cola.
- Expiración:
  - Solicitud vieja no aparece.
- Dedupe:
  - Misma `requestId` no duplica.

---

## Archivos a tocar (cuando se implemente)

- `BluetoothPaymentService` → emite `BlePaymentRequest`.
- `BlePaymentQueueManager` (nuevo) → cola + estado.
- `AppNavigation` / `HomeViewModel` → banner y navegación controlada.
- `PaymentScreen` → aceptar request y llamar `submitAmountWithExternalInputs`.

---

## Nota de Seguridad

La cola **no ejecuta pagos automáticamente**.  
El operador siempre confirma o rechaza.

Esto evita cargos accidentales y mantiene el patrón POS esperado.
