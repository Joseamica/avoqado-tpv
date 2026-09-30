# AngelPay App-to-App Integration

AngelPay payment processing via Android Intents on Nexgo N86 terminals. Completely isolated from Blumon SDK — parallel payment path with its own ViewModel, Screen, and state machine.

## Architecture: Parallel Paths

```
PAX terminals (UNTOUCHED):
  AppNavigation → PaymentScreen → PaymentViewModel → Blumon SDK
  (zero changes, zero risk)

Nexgo terminals (NEW, isolated):
  AppNavigation → AngelPayPaymentScreen → AngelPayPaymentViewModel → Intent → AngelPay app
  (new code only, shares stateless use cases via DI)
```

Routing decision: `BuildConfig.ENABLE_PAX_SDK` (compile-time per Gradle flavor)
- `true` (sandbox/production) → Blumon PaymentScreen
- `false` (nexgo/tutorialEmu) → AngelPayPaymentScreen

## Build Variants

| Flavor | Command | Device | Processor |
|--------|---------|--------|-----------|
| sandbox | `./gradlew installSandboxDebug` | PAX A910S | Blumon SDK (QA) |
| production | `./gradlew assembleProductionRelease` | PAX A910S | Blumon SDK (PROD) |
| tutorialEmu | `./gradlew installTutorialEmuDebug` | Emulator | None |
| **nexgo** | **`./gradlew installNexgoDebug`** | **Nexgo N86/N5** | **AngelPay (QA)** |

## Payment Flow

```
1. User taps "Cobrar" on WelcomeScreen
2. AppNavigation checks BuildConfig.ENABLE_PAX_SDK
3. nexgo → navigates to NavRoute.AngelPayPayment
4. AngelPayPaymentScreen renders
5. ViewModel validates shift (same as Blumon flow)
6. ViewModel builds DO_SALE Intent via AngelPayIntentBuilder
7. Screen launches Intent via ActivityResultLauncher
8. AngelPay app opens, processes card payment
9. AngelPay returns result via onActivityResult
10. ViewModel parses TransactionResult + CallResult
11. If approved → records payment to backend via RecordPaymentUseCase
12. Shows Success screen
```

## AngelPay API Reference (Manual v1.2, 17/03/2026)

### Request Objects

**TransactionRequest** (JSON in Intent extra):
```kotlin
{
  "operationType": "SALE",
  "subtotal": 150000,        // centavos ($1,500.00)
  "tip": 20000,              // centavos ($200.00), optional
  "waiter": "Carlos",        // optional
  "installments": 0,         // MSI months, optional
  "integratorReference": "REF-123",  // our reference, optional
  "timeOutApproved": 0,      // 0 = return immediately (skip AngelPay print)
  "timeOutDeclined": 3000    // 3s for declined
}
```

**AuthExternal** (JSON in Intent extra):
```kotlin
{
  "email": "contacto@avoqado.io",
  "password": "123456",
  "affiliation": "9814275 ultrathink",
  "commerceToken": "1773083056540lIE"
}
```

### Intent Actions

| Action | Purpose |
|--------|---------|
| `mx.angel_pay_prod.app.intent.action.DO_SALE` | Process card payment |
| `mx.angel_pay_prod.app.intent.action.SEE_HISTORY` | View transaction history |
| `mx.angel_pay_prod.app.intent.action.REPORTS` | View reports |

Package: `mx.angel_pay_prod.app` (prod) / `mx.angel_pay_prod.app.qa` (QA)

### Response Format

AngelPay returns **2 JSON String extras** via `onActivityResult`:

| Extra Key | Object |
|-----------|--------|
| `mx.angel_pay_prod.app.extra.RESULT_TRANSACTION` | TransactionResult |
| `mx.angel_pay_prod.app.extra.RESULT_CALL` | CallResult |

**TransactionResult**:
```kotlin
data class TransactionResult(
    val approved: Boolean,    // true = approved
    val authCode: String?,    // authorization code (e.g., "502511")
    val reference: String?,   // reference number
    val amount: Long,         // amount in centavos
    val message: String?,     // descriptive message
    val code: String?,        // response code (e.g., "S000")
)
```

**CallResult**:
```kotlin
data class CallResult(
    val code: String?,        // e.g., "S000", "U100", "E600"
    val status: String?,      // "OK", "ERROR", "CANCELLED"
    val category: String?,    // "SUCCESS", "AUTH", "USER", "CLIENT", "DEVICE", "EMV"
    val message: String?,     // human-readable message
)
```

### Result Logic

| Condition | Meaning |
|-----------|---------|
| `RESULT_OK` + `tx.approved == true` | Payment approved |
| `tx.approved == false` | Payment declined |
| `tx == null && call == null` | User cancelled |
| `call != null` (no approved tx) | Error with details |

### Error Catalog (key codes)

| Code | Status | Category | Description |
|------|--------|----------|-------------|
| S000 | OK | SUCCESS | Aprobada |
| U100 | CANCELLED | USER | Cancelada por usuario |
| U101 | CANCELLED | USER | Timeout |
| E600 | ERROR | EMV | Error lectura tarjeta |
| E606 | ERROR | EMV | Rechazo online |
| D308 | ERROR | DEVICE | Sesion expirada |
| C202 | ERROR | CLIENT | Monto invalido |

Full catalog in AngelPay Manual v1.2 pages 25-28.

## SDK 1.0.21 — devoluciones que no traban, corte de las 11 pm y firma encendida (29-sep-2026)

**El AAR:** `app/libs/angelpaySDK-v1.0.21-fat-release.aar` (SHA-256
`2cacce2b482d64726386479b7a10fadd092e9e048a60827c598997efa125fa8d`, el que publica AngelPay en su portal;
`AngelPaySDK.version() == "1.0.21"`). `AngelPaySdk119ReglaTest` fija las dos cosas; el 1.0.20 se queda en `libs/`
como los anteriores, pero ya no está auditado para la regla.

**Re-auditoría en bytecode contra el 1.0.20 (29-sep):**
- El orquestador pasó de `b0.h0` a `b0.v0`. `m` (`authorizationAttempted`) sigue naciendo `false` en cada cobro y
  subiendo a `true` pegado al envío; `l` (Cancelar) se sigue revisando antes de enviar. La regla
  (`decidirSegunElSdk119`) no cambia de lógica.
- Códigos nuevos: `D312` (falló el registro de la terminal ANTES del cobro; lo arma un paso previo al orquestador, sin
  referencia y con `attempted = false`; mismo texto que el D308 ⇒ paso 2 de la regla y rechazo cierto en
  `CODIGOS_RECHAZO_CONFIRMADO`), `G506` (no concluyente por intermitencia del servidor de AngelPay; sale DESPUÉS del
  envío ⇒ INCIERTO, en `CODIGOS_SIN_VEREDICTO`), `C230`-`C232` (AMEX/hotelería; no son rechazo confirmado).
- `-8028` (el chip rechaza el cierre de un cobro que el host aprobó) ahora llega `approved = true` / `S000`, sin reversa.
  Antes era el E699 incierto que el verificador tenía que rescatar del historial.
- Firma en pantalla (`captureSignature`): el SDK la pide SÓLO después de aprobar, con tope de 60 s, y no cambia el
  resultado. **Va encendida desde 2.12.0** (lo recomendó AngelPay el 29-sep). Ningún temporizador nuestro la corta: el
  vigilante de autorización sólo avisa en pantalla, nunca cancela.
- `PaymentResult.cardCountryCode` (EMV 5F28, parámetro 29): viaja al servidor como `issuerCountryCode` +
  `issuerCountrySource = EMV_5F28`, sólo evidencia para el clasificador sombra, igual que en la PAX. El historial de
  AngelPay no lo trae, así que un cobro confirmado por verificación va sin él.

### Devoluciones (Nexgo): lo que dijo AngelPay y cómo queda

**AngelPay (Norman, 29-sep):** no hay idempotencia de su lado; las **devoluciones están apagadas para todos los
comercios** (se piden a su soporte); sólo existe la **cancelación completa antes del corte de las 11 pm del día de la
venta**. La zona horaria del corte se le preguntó; mientras contesta se usa −06:00 fijo (`AngelPayCorte`).

**El defecto medido en la N86 (29-sep, 2.11.3):** una devolución que falló sin red dejó su fila de la libreta en
`PREPARANDO` con el token del proceso vivo, y la venta siguiente murió en la barrera («Hay otro cobro en curso en esta
terminal ($1.00, hace 3 min)») hasta reiniciar la app. Con un rechazo de AngelPay la fila quedaba en `AUTORIZANDO`, igual.

**Cómo queda (founder: «¡nada puede detener las ventas!», «ni trabar nada», «ni reiniciar»):**
- **Sólo una VENTA aparta el aparato** (`kind = 'SALE'` en `SQL_EJECUCION_VIVA` / `SQL_APARTA_EL_APARATO`, en
  `findUnresolvedCharge` / `countUnresolvedCharges`, y en las tres recuperaciones con forma de venta). Una devolución,
  en cualquier estado y de cualquier procesador —también la de la PAX—, nunca detiene un cobro.
- **Antes del corte:** cancelación. **Después:** primero se revisan los dos candados (la cola `pending_refunds` y la
  libreta): una devolución que AngelPay ya aprobó y no se registró, o que quedó en duda, se puede confirmar y registrar
  aunque haya pasado el corte, porque confirmarla no le pide nada nuevo a AngelPay (auditoría del 29-sep, P1). Si no hay
  nada pendiente, no se abre la libreta ni se llama a AngelPay y se dice «Ya pasó el corte de AngelPay (11 pm del día de
  la venta). La devolución se gestiona con soporte de AngelPay. No se reembolsó nada.». La lista de pagos apaga
  «Reembolsar» pasado el corte, salvo en los pagos con algo pendiente (`tieneDevolucionPorResolver`: los mismos dos
  candados, en un solo lugar). `AngelPayCorte.DEVOLUCION_POSTERIOR_HABILITADA` se cambia a `true` sólo si AngelPay
  reactiva la devolución.
- **La hora de AngelPay también cuenta:** tras leer la venta en su historial, si SU hora dice que el corte ya pasó (la venta
  pudo registrarse tarde en Avoqado, desde la cola sin red), no se llama a AngelPay. Sólo puede cerrar el corte, nunca
  abrirlo; una hora sin zona se lee como hora de México (si fuera UTC, el corte sólo se vuelve más permisivo).
- **Cada salida cierra su fila con lo que pasó:** falla antes de AngelPay (historial sin red, venta no encontrada, campos
  incompletos, la libreta no deja autorizar) ⇒ `DESCARTADA` y «No se reembolsó nada»; rechazo explícito (catálogo, emisor
  o «referencia inválida») ⇒ `DESCARTADA` por `markHostResponded(false)` y «No se devolvió nada»; sin veredicto (G505,
  G506, timeout, excepción, código desconocido) ⇒ `INDETERMINADO` y «NO la repitas: pudo haberse aplicado». Un `finally`
  no cancelable cierra lo que salga por excepción o por cancelación.
- **Sin fila escrita no se llama a AngelPay** (el `Boolean` de `openAttempt` y el de `markAuthorizing` se respetan).
- **Tras una duda no se prueba otra referencia** ni la devolución de respaldo: sólo tras «referencia inválida».
- **Una devolución en duda sólo cerca OTRA devolución del MISMO pago** (`devolucionSinResolver`). Al intentarla de nuevo:
  si AngelPay ya la había aprobado y no se registró, se entrega con SU llave sin volver a llamar a AngelPay; si el
  historial ya muestra la cancelación aplicada (`postOperation` aprobada), se da por aprobada con SU llave; si no, no se
  intenta otra.
- **La terminal sólo espera mientras la devolución habla con AngelPay:** marca en RAM (`PaymentStateHolder`, reloj
  monotónico) que se renueva antes de CADA llamada al SDK y vence sola a los 2 min de la última. `isCharging()` la incluye
  (no se cambia de sesión ni de comercio a media llamada) sin que la devolución escriba la bandera de las ventas: soltarla
  al terminar podía dejar sin protección a una venta que arrancó después (auditoría del 29-sep, P2). Un cobro del POS que
  llegue en ese rato recibe «La terminal está terminando una devolución: este cobro NO se inició…»
  (`failed + PRE_AUTHORIZATION`) en vez de arrancarle la pantalla.
- **La cuarentena por reloj se levanta cuando el SDK regresa** (PAX y Nexgo): si el barrido puso un cobro en cuarentena
  por antigüedad mientras el SDK seguía dentro y el SDK regresa sin veredicto, la duda se queda con su motivo real y la
  terminal vuelve a cobrar sin reiniciar.
- La recuperación inmediata (S6) ya no pregunta por una devolución: una «liberación» soltaría el candado que impide
  devolver dos veces.

### Medido en la N86 (29-sep, `nexgoDebug` 2.12.0 contra AngelPay QA, `AVQD-N860W175781`)
- **Devolución sin red** (WiFi y datos apagados, sin ruta): «No se pudo consultar la venta en AngelPay (Sin conexión a
  internet o servidor no disponible). No se reembolsó nada.»; la fila quedó `DESCARTADA · antes_del_sdk:historial`. En
  2.11.3 esa misma salida dejaba la fila en `PREPARANDO` y la venta siguiente moría en la barrera hasta reiniciar.
- **Cancelación del mismo día** (venta de $1 de las 17:00, cancelada a las 21:24, antes del corte de las 23:00):
  `S000 · APPROVED · «APROBADA»` al primer intento → libreta `HOST_RESPONDIO` → cola → `POST /tpv/venues/:id/refunds` 201
  (428 ms en el servidor, sin errores en su log) → libreta `REGISTRADO` → la lista la muestra «Reembolsado».
- **Formato real del historial:** `creationDate = "2026-09-29T23:00:41.000Z"` (instante con zona: `instanteEnAngelPay` lo
  lee con `Instant.parse`), `date` y `time` nulos; en una venta sin cancelar, `postOperation` viene nulo.
- ⬜ **Sin medir todavía:** cómo muestra el historial una venta YA cancelada (`postOperation.status`, del que depende
  confirmar una duda por historial — sólo se ve desde SuperAdmin, que pide TOTP); el `-8028` con el chip retirado; la firma
  (ninguna tarjeta de QA la pide: se prueba en el piloto); «la venta siguiente entra» en el aparato (la caja de QA estaba
  cerrada; lo cubren las pruebas Room).

### Sin red — las cuatro preguntas
1. **Qué ve el cajero:** «No se pudo consultar la venta en AngelPay (…). No se reembolsó nada.» — y puede seguir
   cobrando en el acto. Con N400 de AngelPay: «Cancelación rechazada por AngelPay (N400 · …). No se devolvió nada.»
2. **Si el proceso muere entre el toque y la llamada:** la fila ya está escrita; si murió antes de AngelPay queda en
   `PREPARANDO` (no aparta nada; el barrido la descarta), si murió durante la llamada queda `AUTORIZANDO` y cuenta como
   duda de ESE pago (nunca aparta la terminal).
3. **Orden de lo encolado:** la devolución aprobada sigue la cola de `pending_refunds` con la MISMA llave; nada nuevo
   se encola.
4. **Cuando vuelve la red:** una duda se resuelve con el historial de AngelPay al intentarlo de nuevo, o con su soporte;
   nunca se libera sola ni la libera el servidor.

## SDK 1.0.20 — el Cancelar se revisa antes de enviar (26-sep-2026)

**El AAR:** `app/libs/angelpaySDK-v1.0.20-fat-release.aar` (SHA-256
`7abbe8ea7f0cf12be6af3766c92a24c882acc3c2cd08d31bd034d2de572d2282`, el que publica AngelPay en
`developers.angelpay-qa.com.mx/docs/sdk`; `AngelPaySDK.version() == "1.0.20"`). El archivo que se descarga del
portal viene con el nombre equivocado (`angelpaySDK-v1.0.19-fat-release.aar.aar`): se identifica por el SHA y por
`version()`, nunca por el nombre. `AngelPaySdk119ReglaTest` fija las dos cosas.

**Re-auditoría en bytecode contra el 1.0.19** (reportes en `~/.claude/jobs/56954c8f/tmp/auditoria-productores-1.0.20.md`
y `api-diff-1.0.20.md`):
- El orquestador pasó de `b0.f0` a `b0.h0`; los productores de `PaymentResult` del 1.0.19 salen idénticos (diff
  normalizado) y hay **dos nuevos**: «cancelado antes de enviar la autorizacion» por chip (`h0` 3636→3761) y por banda
  (`h0` 4525→4628), `U100 CANCELLED` con `attempted = false` y nuestra referencia, **antes** de `m = true` (3767 /
  4636) y del envío (3828 / 4708). Es el «cambio de dos líneas» del diseño §4.4.
- El botón Cancelar (`PaymentActivity.onCreate$lambda$2`) ya ignoraba el Cancelar con `m = true` («Cancelar
  ignorado: la autorizacion ya fue enviada al procesador») desde el 1.0.19. Con la revisión nueva del orquestador, la
  carrera queda cerrada en las dos direcciones: pantalla y orquestador comparten hilo. **El U100 del botón deja de
  ser una apuesta (decisión U) y pasa a ser prueba.**
- 🔴 El 1.0.19 tenía la ventana abierta: entre la lectura de la tarjeta y el envío no se revisaba el Cancelar, así
  que un U100 del botón podía decir «no se cobró» de un cobro que sí salió. Por eso el 1.0.19 ya **no** está auditado.
- La regla (`decidirSegunElSdk119`, pasos 0-6d) no cambia de lógica: el U100 nuevo por banda cae en 6d (no se cobró),
  el de chip trae tarjeta leída y queda INCIERTO (6b, conservador).
- API: sólo se AGREGA (`getOperations()`, `MerchantInfo.aggregatorName`, 10 campos en `TransactionItem` —entre ellos
  `idOperation`—, 4 en `TransactionPostOperation`). `PaymentResult`, `PaymentRequest` (defaults incluidos: la firma
  sigue encendida por defecto), `AppErrorCatalog` y la base Room interna del SDK: sin cambios.
- `idOperation` se expone en `UnifiedTransaction` y se registra en el log del verificador; **no decide nada** hasta
  medir con una venta real qué número usa AngelPay para «venta» (el SDK no trae la tabla).

**Medido en la N86 (26-sep, `nexgoDebug` contra AngelPay QA, `AVQD-N860W175781`):**
- Cancelar sin tarjeta ⇒ «No se cobró», libreta `DESCARTADA` con `sin_autorizacion:sdk=1.0.20;code=U100`.
- Tiempo agotado ⇒ «Tiempo agotado: nadie acercó una tarjeta» (U101), sin bloquear la terminal.
- Chip + Cancelar en el NIP ⇒ kernel `-8020`, E699 con `attempted=false`, «No se cobró»; en el log del SDK no hay
  `SALE.charge … op=SALE` (nunca salió al banco).
- 🔴 Chip + NIP ⇒ el host APROBÓ (`SALE.charge ok code=00`) y el chip rechazó el cierre (`-8028`, TVR 0080008000): el
  SDK entregó `approved=false`/E699 y el historial siguió mostrando la venta APROBADA (sin reverso). La app lo dejó
  incierto, el verificador lo encontró en el historial y registró la venta una sola vez. Queda preguntarle a AngelPay
  si ese caso debería revertirse solo.
- `idOperation = 4` con `operationType = "VENTA"` (una muestra, QA): todavía no se usa para decidir.
- Pendiente: pulsar Cancelar justo en la ventana entre la lectura y el envío (W1/W2) no se logró a mano; lo respalda
  el bytecode.

## SDK 1.0.19 — «No se cobró» cierto (18-sep-2026)

**El AAR:** `app/libs/angelpaySDK-v1.0.19-fat-release.aar` (SHA-256
`fe5ef7683e9d8cbcf34d1785610c6c2e05f71d1ccad0ca35dc3f99da1853e777`, `AngelPaySDK.version() == "1.0.19"`).
`AngelPaySdk119ReglaTest` fija las dos cosas: 🔴 si llega otro AAR, esa prueba cae — NO se actualiza el hash a
ciegas; la regla de abajo se re-audita contra el binario nuevo.

**Lo nuevo en la API pública** (diff de `javap` 1.0.18 → 1.0.19): `PaymentResult.authorizationAttempted`,
`PaymentResult.requireSignature`, `PaymentRequest.captureSignature` y `AngelPaySDK.getLastSignatureBase64()`.

**`authorizationAttempted`** nace `false` en cada cobro y el orquestador del SDK (`b0.f0.m`) lo sube a `true`
pegado al envío al host (EMV y banda). Con eso el propio proveedor pone la frontera del «no se cobró» en el ENVÍO:

| Resultado del SDK | Qué hace la terminal |
|---|---|
| `false` + NUESTRA `integratorReference` + código de catálogo, sin campos del host ni tarjeta leída (U101, E618, E622, E699, I999, U100 del botón Cancelar) | **«No se cobró», cierto** (`AngelPayOutcomeClassifier.decidirSegunElSdk119` → `SIN_AUTORIZACION`) |
| `false` SIN nuestra referencia (respaldos del contrato: «Cancelled», «Error al parsear resultado», «Unknown error», validación; el C200 al abrir; un E608 ajeno) | **incierto** — un resultado sin correlación nunca es un negativo cierto (el C200 era hoy un rechazo con «Reintentar»). Única excepción sin referencia: la sesión expirada (D308 / «registrar la terminal»), que sigue como hoy |
| `false` con la referencia de OTRO intento, o sin intento propio con qué compararla | incierto |
| `false` con cualquier campo que sólo escribe el host (o `status = APPROVED`) | incierto + 🚨 `AngelPaySdkContradiccion` |
| `false` con tarjeta leída, salvo `E608` | incierto |
| `E608` (límite sin contacto) con nuestra referencia | como hoy: rechazo con «Reintentar» en la misma venta (inserta el chip) |
| `true` | como hoy. ⬜ Pendiente (§2.2 4a): un `G500`/`N400` sin respuesta del host sigue contando como rechazo |
| aprobado | como hoy — registra la venta (con `false` además grita 🚨) |

Con «no se cobró» la LIBRETA va primero (`PaymentAttemptLedger.markSinAutorizacion`: `AUTORIZANDO → DESCARTADA`
sin tocar `host_approved`, para que la bandeja mande `PRE_AUTHORIZATION` y nunca `PROCESSOR_DECLINED`). Si la
libreta no lo escribe, o el servidor ya acreditó dinero de ese intento (S5), el cobro sigue INCIERTO como siempre.
Y si S5 llega MIENTRAS el CAS está suspendido, al retomar se revalidan la fila y el veto ANTES de fijar banderas,
emitir o pintar: la pantalla termina en el cobro (fila REGISTRADO) o en la contradicción, nunca en «No se cobró».
Dos precisiones de Codex r2 sobre esa revalidación:

- **La relectura que falla no es «sin dinero».** Si después del CAS la fila no se puede releer (o no es la DESCARTADA
  que escribió el CAS), la terminal no afirma nada: reabre ESA fila a INDETERMINADO
  (`PaymentAttemptLedger.reabrirSinAutorizacion`, sólo con el mismo motivo) y el cobro sigue INCIERTO — nunca
  «No se cobró» y nunca `failed`. La recuperación por servidor le pregunta por ese intento.
- **El dinero que sólo consta en el veto se deja durable.** S5 publica su aviso aunque su propia escritura falle
  (`registrado = false` sin nada en la fila). En ese caso la pantalla aplica, ANTES de pintar, el MISMO veredicto que
  S5 habría escrito (`VeredictoDeIntento.desdeAvisoS5`, libreta y bandeja en una transacción: DESCARTADA + RECORDED es
  contradicción, y la bandeja queda resuelta con su ganador y se emite). Lo mismo si ese S5 llega con el «No se cobró»
  del POS ya en pantalla. Si tampoco así queda escrito, reabre la fila a INDETERMINADO **con el veto durable en el
  MISMO UPDATE** (`server_veto = VETO_SDK_CONTRADICTION`, Codex H3, 26-sep) y lo reporta (`AngelPaySdkContradiccion`):
  aunque la app se reinicie, esa venta sigue cercada (`SQL_CERCA_LA_VENTA`), el aviso de Inicio la muestra como
  contradicción y la fila ya no se promueve a REGISTRADO (Avoqado la concilia). La reapertura por una relectura
  fallida (punto anterior) va SIN veto: ahí no se sabe de dinero.

El mismo CAS tiene un segundo llamador, y sólo ése: la invocación que pasó el intento a AUTORIZANDO y sabe que NUNCA
lanzó el SDK — la validación (`createPaymentIntent`) falló y ni el fallback de propina ni el de app-a-app van a
continuar. Cierra la fila con `last_error = no_lanzado:validacion`; «Reintentar» abre un intento nuevo y, en un
cobro del POS, el final se retiene y lo cierra el reloj de abandono. Nunca es un cierre genérico de filas AUTORIZANDO.

- **Pago rápido:** pantalla «No se cobró» con el texto por código (`TextosNoSeCobro`) e «Intentar de nuevo», que
  abre un intento y una referencia NUEVOS. Sin verificador, sin cerca (la terminal no queda apartada), sin ticket de
  rechazo.
- **Cobro del POS:** `failed + PRE_AUTHORIZATION` al instante, texto para la tablet, y la terminal sale sola a los
  4 s.

**El panel de firma nuevo va APAGADO:** `captureSignature` vale `true` por defecto en 1.0.19; `AngelPaySdkGateway`
manda `false` en la ruta normal y en el fallback de propina (comportamiento del 1.0.18).

**Sin interruptor remoto** en esta entrega: el candado de versión es la salida (con otro AAR, la regla no corre).

**§3.8 — la barrera ya no es muda:** si la libreta rechaza un cobro que mandó el POS, la TPV emite
`failed + PRE_AUTHORIZATION` al instante y nombra lo que la aparta. Desde el 25-sep («ninguna duda apaga la
terminal») la barrera sólo rechaza por tres causas: otro cobro EN CURSO en esta terminal, la MISMA venta con dinero
en juego, o que no se pudo guardar el intento; una duda anterior sin dinero conocido ya no aparta la terminal. La
bandeja sólo lo escribe si ningún intento de ESA solicitud quedó fuera de PREPARANDO/DESCARTADA.

### Sin red — las cuatro preguntas (`.claude/rules/todo-funciona-sin-red.md`), con las precisiones de Codex r1 y r2

1. **Qué ve el cajero sin red.** El «no se cobró» acreditado por el SDK se clasifica LOCALMENTE (resultado del SDK +
   Room), así que la pantalla de la terminal es la misma con o sin red. Un POS desconectado **no se entera al
   instante**: su final queda en la bandeja y le llega al servidor cuando vuelve la red. Con `attempted = true` manda la
   tabla de hoy (decisión 4a): un rechazo del catálogo (`G500`, `N400`…) sigue siendo rechazo con «Reintentar» aunque el
   host no haya contestado (⬜ lo pendiente de 4a), y un código sin veredicto (`U101`, `N402`, `G502`, `G505`, `I999`)
   sigue INCIERTO (verificador y ventana del servidor, sin «Reintentar»). El cobro con tarjeta en sí es online-only a
   propósito.
2. **Qué se pierde si el proceso muere.** Antes del resultado del SDK: la fila queda AUTORIZANDO = incierto, como hoy.
   🔴 **Después del CAS y ANTES de persistir el final en la bandeja** (cobro del POS): la libreta queda DESCARTADA y la
   bandeja en PROCESSING — **se pierde la notificación pendiente** (la escritura de la bandeja corre en otra corrutina,
   `SocketManager.emitTerminalPaymentResult` → `socketScope`). Ver «Pendiente declarado» abajo. Y si S5 avisó dinero
   durante el CAS con su propia escritura fallida, el veredicto se deja durable ANTES de pintar; si el proceso muere
   entre ese aviso y esa escritura, el veto en memoria se pierde (residual de la pregunta 4).
3. **En qué orden se reproduce.** Un solo final por solicitud. DESPUÉS de persistirlo, la reentrega y la sonda
   reproducen el ganador y no ejecutan otra autorización. ANTES de persistirlo no existe un final que reproducir.
4. **Qué pasa si vuelve la red y el servidor ya cambió.** La evidencia positiva durable veta el negativo (la bandeja no
   lo escribe) y una bandeja ya resuelta conserva y reproduce su ganador. Si S5 llega durante el CAS o con el
   «No se cobró» del POS ya en pantalla, la pantalla pasa a la contradicción o al cobro, y ese dinero queda en la fila
   (su veredicto; si no se puede, la fila vuelve a INDETERMINADO con el veto `VETO_SDK_CONTRADICTION`): el aviso F0 lo muestra, y el cancel del POS y la
   sonda nunca contestan «limpio» **cuando la bandeja todavía no tiene final**: con el veredicto aplicado
   queda RESOLVED con el cobro, y con la reapertura sigue PROCESSING ⇒ ACTIVE. 🔴 **Si el negativo YA se había
   persistido en la bandeja, la reapertura de la libreta NO la cambia** (Codex r3): la bandeja conserva su
   `RESOLVED/failed`, la sonda reproduce ese negativo y el cancel contesta `ALREADY_RESOLVED`, aunque la fila quede
   INDETERMINADO y el aviso F0 sí la muestre. La obligación durable vive en la TERMINAL; el final ya entregado al POS
   no se reescribe. Si la relectura posterior al CAS falla, la terminal queda INCIERTA, no en
   «No se cobró». **Lo que NO cubre, declarado:** (a) si el proceso muere entre el aviso de S5 y la escritura de su
   veredicto, el veto en memoria se pierde; (b) si fallan esa escritura Y la reapertura, quedan el reporte y la
   contradicción en pantalla, pero no una obligación durable en la terminal; (c) si fallan la relectura Y la
   reapertura, la pantalla queda incierta pero la fila sigue DESCARTADA (un cancel del POS se aceptaría); (d) una
   reapertura POSTERIOR a que la bandeja ya persistió el negativo no revierte ese final: el POS conserva el `failed`
   que se le entregó. En (a) y
   (b) el servidor conserva su Payment: lo que se pierde es la evidencia en la TERMINAL, no el cobro. En (c) no hay
   dinero conocido: lo que se pierde es la obligación de confirmarlo.

### ⬜ Pendiente declarado: el cierre remoto partido entre libreta y bandeja (Codex r1, P2)

En un cobro del POS, el «no se cobró» se escribe en DOS pasos: el CAS de la libreta (`markSinAutorizacion`) y, después,
el final en la bandeja (`persistResult`, en `SocketManager`). No es una transacción única. Si el proceso muere entre los
dos, queda exactamente esto:

- **Ni el POS ni el servidor reciben un negativo:** la bandeja nunca escribió `failed`, así que la venta no se les
  declara «no cobrada». En la TERMINAL sí: la libreta ya dice DESCARTADA y la pantalla pudo haber mostrado «No se
  cobró» antes de morir (lo acreditó el SDK: la petición no salió al banco).
- **Se pierde la notificación:** la tablet no recibe el `failed + PRE_AUTHORIZATION` de esa solicitud.
- **La sonda contesta ACTIVE** (la bandeja sigue en PROCESSING): el servidor conserva la reserva.
- **En el servidor:** la solicitud vence a los 5 min y queda UNKNOWN; la ranura de la terminal la suelta el destrabe
  por tiempo (`AUTO_RELEASED`, 20 min después de que la terminal vuelve a latir) con la venta protegida, o un operador.
  Mientras tanto la tablet ve «cobro sin confirmar».
- En la terminal la fila DESCARTADA no aparta el aparato: el siguiente cobro local sí entra.

Arreglo mínimo (fuera de esta entrega): un commit conjunto del descarte y del final remoto, o una obligación durable de
completar ese final.

Diseño: `avoqado-server/.superpowers/sdd/2026-09-16-ventana-de-confirmacion-cobro-sin-evidencia/diseno-nexgo-sdk-1.0.19.md`.

## File Structure

```
features/payment/
├── domain/
│   └── processor/
│       └── ProcessorType.kt              # BLUMON, ANGELPAY enum
├── data/
│   └── processor/
│       └── angelpay/
│           ├── AngelPayCredentials.kt     # Data class for auth
│           ├── AngelPayIntentBuilder.kt   # Builds DO_SALE/HISTORY/REPORTS intents
│           └── AngelPayResultParser.kt    # Parses onActivityResult response
└── presentation/
    └── angelpay/
        ├── AngelPayPaymentState.kt        # ~10 state sealed class
        ├── AngelPayPaymentViewModel.kt    # Shift validation → intent → parse → record
        └── AngelPayPaymentScreen.kt       # Compose UI with ActivityResultLauncher
```

## QA Credentials

**Never commit credentials to this repo** (repo security rule). Where they actually live:

- **TPV runtime creds** (email/PIN por cuenta AngelPay): ya NO están hardcodeadas en el app —
  llegan del backend en la respuesta de configuración de terminal (`angelpayAccounts`) y las
  resuelve `AngelPayCredentialResolver`. Para dar de alta/rotar una cuenta QA se hace en el
  Dashboard (AngelPayUserAccount del venue), no en código.
- **Portal QA** (`https://portal.angelpay-qa.com.mx/`): usuario/contraseña en el vault del
  equipo (iCloud `Socios/AngelPay/` / gestor de contraseñas). Pedirlas a José Antonio si no
  tienes acceso.

> Nota histórica: versiones viejas de este doc listaban las credenciales QA en texto plano y
> describían un auto-provision en `AvoqadoTPVApplication.onCreate()` que ya no existe — ambos
> retirados 2026-07-09. Si esas credenciales de portal siguen vigentes, considerar rotarlas.

## Vendor Documentation

```
~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/
└── Manual de Integración App to App.pdf  # v1.0

~/Downloads/
├── Manual de Integración App Angel Pay-v1-2.pdf  # v1.2 (latest, 17/03/2026)
└── angel-pay-consumer/                           # Example integration app
```

## Limitations & Future Work

1. **Devoluciones** — sólo la CANCELACIÓN completa, el mismo día y antes del corte de las 23:00 (post-operación del SDK). Las devoluciones posteriores las tiene apagadas AngelPay para todos los comercios (29-sep): se piden a su soporte. Ver «SDK 1.0.21».
2. **No card details in response** — AngelPay doesn't return maskedPan, cardBrand, or entryMode. Backend records with UNKNOWN
3. **No ticket printing from TPV** — AngelPay auto-prints its own ticket. We set `timeOutApproved=0` to skip it. Future: use their BroadcastReceiver print API for our own receipts
4. **QA creds hardcoded** — Need backend terminal config or SuperAdmin UI for production credential management
5. **Firebase package** — nexgo flavor uses `.sandbox` suffix temporarily. Register `.nexgo` in Firebase Console for production
