# Efectivo con el SERVIDOR mudo: no esperar cuando ya se sabe (encargo 97042c · hz:nube0000:naa40500)

## Promesa (5 líneas)

- **Qué va a pasar:** en la TPV (PAX y Nexgo), con la franja roja «Sin conexión al servidor» o sin internet, cobrar en
  efectivo (Pago rápido, Mesas y Cobrar) guarda la venta en la terminal y termina en ~1–2 s sin llamar al servidor; la
  venta sube sola y una sola vez cuando vuelve. Antes de que salga la franja, si el servidor no contesta, se intenta 2
  veces (no 5) y la venta pasa a la cola.
- **No entra:** la tarjeta (sigue igual), los ~25 s de consultar el turno y el «Preparando…» de Cobrar ANTES de que salga
  la franja, el arranque en frío (`hz:nube0000:nab7fc93`), el texto del recibo de Pago rápido (`hz:nube0000:nac9e359`),
  ANDROID (ya lo hace) e iOS.
- **Se prueba así:** TPV `.lab` en la PAX contra el banco; `kill -STOP` al SERVIDOR; esperar la franja roja; Pago rápido
  $13 en efectivo y Cobrar con un producto en efectivo, midiendo en logcat del toque a «queued»; `kill -CONT`; en la base
  1 pago por venta. Además, con el SERVIDOR sano, una venta normal sigue saliendo con su recibo.
- **Depende de:** la fila de efectivo guardada ANTES del POST (`RecordPaymentUseCase`, `enqueueAndClaim`) y la venta local
  guardada antes de subir (`VentaLocalRepository.guardar`); el worker que sube al volver (`ConnectionEventManager` →
  `PaymentSyncScheduler.runNow`); la franja (`ConnectionStateManager.hasServer`, 2 latidos fallidos). Todo ya existe.
- **Responsable:** la conversación de la nube a3c75d (encargo 97042c).

## Mapa de entradas (quién escribe la fila de efectivo y qué cambia)

| Camino | Escritor de la fila | Hoy con el SERVIDOR mudo | Con el cambio |
|---|---|---|---|
| Pago rápido / Mesas, efectivo en vivo (PAX `PaymentViewModel` ×2 variantes, Nexgo `AngelPayPaymentViewModel`) | `RecordPaymentUseCase` → `enqueueAndClaim` antes del POST; luego `markSynced` o `release` | 5 intentos × ~11 s ≈ 58 s, luego `release` + `handleRecordFailure` (cola) | con la franja: 0 POST, `release` inmediato con un `ConnectException` (Retryable) → la misma cola; sin franja: ≤2 intentos si nadie contesta |
| Replay del worker (`PaymentSyncWorker`, `alreadyQueued = true`) | el worker con su claim | igual | **sin cambio** (sigue intentando: es quien sube al volver) |
| Tarjeta (Blumon/AngelPay), registro tras la aprobación | libreta + cola de tarjeta | igual | **sin cambio** |
| Cobrar → venta local (3 VM): `guardar` (fila reclamada) → `SubirVentaGuardadaUseCase` → `markSynced` / `markPermanentlyFailed` / `release(+1)` | `VentaLocalRepository` | 1 intento ≈ 25 s | con la franja: no se sube; `release` sin sumar intento + `runNow` |
| Cobrar → decidir el camino (`CheckoutViewModel.prepararCobro`) | guarda el carrito con su llave antes del POST | intenta crear la orden (≈25 s) y cae a venta local con la MISMA llave | con la franja: va directo a venta local (como sin internet; cupón, descuento del cliente y cortesía sin permiso se rechazan igual que sin red) |

Reintentos, cancelaciones y recuperaciones: la fila ya existe con su claim antes de decidir; `release` la deja PENDING
con la misma llave e identidad; si el proceso muere entre la decisión y el `release`, el claim vence y el worker la
retoma (igual que hoy). Nada sale por el cable en el atajo, así que nada se puede duplicar.
