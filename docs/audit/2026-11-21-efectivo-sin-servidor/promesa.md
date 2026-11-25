# Efectivo con el SERVIDOR mudo: no esperar cuando ya se sabe (encargo 97042c · hz:nube0000:naa40500)

## Promesa (5 líneas)

- **Qué va a pasar:** en la TPV (PAX y Nexgo), con la franja roja «Sin conexión al servidor» (hay internet, el servidor no
  contesta), cobrar en efectivo en Pago rápido y en Cobrar guarda la venta en la terminal y termina en ~1–2 s sin llamar al
  servidor; la venta sube sola y una sola vez cuando vuelve. Mientras la franja diga «Sin conexión», la terminal se porta
  como sin conexión (también si el servidor ya volvió y la franja todavía no se quita, ≤30 s). Sin internet, sin la franja
  y con el servidor sano, nada cambia.
- **No entra:** la tarjeta (sigue igual), **Mesas** (su efectivo va por otra cola, `TablesRepository`/`SyncOutbox`: anotado),
  el kiosco (legacy), el tope de 10 intentos de la cola en un corte largo (ya existía: anotado), lo que pasa ANTES de que
  salga la franja (la 1.ª venta del corte sigue esperando: turno ~25 s + 5 intentos; se probó cortarlo a 2 intentos y Codex
  r2 mostró que tocaba respuestas parciales y el caso sin internet, así que se quitó), el arranque en frío (`hz:nube0000:nab7fc93`), el texto del recibo de Pago rápido (`hz:nube0000:nac9e359`),
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
| Pago rápido y orden de Cobrar ya creada, efectivo en vivo (PAX `PaymentViewModel` ×2 variantes, Nexgo `AngelPayPaymentViewModel`) | `RecordPaymentUseCase` → `enqueueAndClaim` antes del POST; luego `markSynced` o `release` | 5 intentos × ~11 s ≈ 58 s, luego `release` + `handleRecordFailure` (cola) | con la franja: 0 POST, `release` inmediato con un `ConnectException` (Retryable) → la misma cola; sin franja: igual que hoy. PAX: si quedó EN COLA y era de una orden, el GET de la orden se salta con la franja (sólo ese camino; tarjeta igual) |
| Worker (`PaymentSyncWorker`) | el worker | sin cambio. Su aviso (`runNow`, KEEP) se pierde si ya corre una tanda: una venta puede esperar la periódica de 15 min (ya existía; anotado, Codex r3 #2 / r4) | sin cambio |
| Replay del worker (`PaymentSyncWorker`, `alreadyQueued = true`) | el worker con su claim | igual | **sin cambio** (sigue intentando: es quien sube al volver) |
| Tarjeta (Blumon/AngelPay), registro tras la aprobación | libreta + cola de tarjeta | igual | **sin cambio** |
| Cobrar → venta local (3 VM): `guardar` (fila reclamada) → `SubirVentaGuardadaUseCase` → `markSynced` / `markPermanentlyFailed` / `release(+1)` | `VentaLocalRepository` | 1 intento ≈ 25 s | con la franja: no se sube; `release` sin sumar intento + `runNow` (como hoy) |
| Cobrar → decidir el camino (`CheckoutViewModel.prepararCobro`) | guarda el carrito con su llave antes del POST | intenta crear la orden (≈25 s) y cae a venta local con la MISMA llave | con la franja y algo que lleva orden (productos, cupón, descuento del cliente): directo a venta local (cupón y descuento del cliente se rechazan como sin internet). Sólo importes: Fast como hoy (conserva la tarjeta); con la franja no espera la captura del referido (como sin internet) |
| Encolar tras el `release` (`PaymentQueueRepositoryImpl.enqueue`) | la pantalla | si el worker ya la subió (SUCCESS) en medio, «fila bloqueada» → error y «Reintentar» | efectivo con la misma llave y el mismo dinero ya SUCCESS = esta venta: éxito (Codex r1 #8) |

Reintentos, cancelaciones y recuperaciones: la fila ya existe con su claim antes de decidir; `release` la deja PENDING
con la misma llave e identidad; si el proceso muere entre la decisión y el `release`, el claim vence y el worker la
retoma (igual que hoy). Nada sale por el cable en el atajo, así que nada se puede duplicar.
