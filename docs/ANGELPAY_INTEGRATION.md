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
  del POS ya en pantalla. Si tampoco así queda escrito, reabre la fila a INDETERMINADO y lo reporta
  (`AngelPaySdkContradiccion`).

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

**§3.8 — la barrera ya no es muda:** si la libreta rechaza un cobro que mandó el POS (la terminal está apartada
por un cobro anterior sin confirmar), la TPV emite `failed + PRE_AUTHORIZATION` al instante y nombra lo que la
aparta. La bandeja sólo lo escribe si ningún intento de ESA solicitud quedó fuera de PREPARANDO/DESCARTADA.

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
   (su veredicto; si no se puede, la fila vuelve a INDETERMINADO): el aviso F0 lo muestra, y el cancel del POS y la
   sonda nunca contestan «limpio» (con el veredicto, RESOLVED con el cobro; con la reapertura, ACTIVE). Si la
   relectura posterior al CAS falla, la terminal queda INCIERTA, no en
   «No se cobró». **Lo que NO cubre, declarado:** (a) si el proceso muere entre el aviso de S5 y la escritura de su
   veredicto, el veto en memoria se pierde; (b) si fallan esa escritura Y la reapertura, quedan el reporte y la
   contradicción en pantalla, pero no una obligación durable en la terminal; (c) si fallan la relectura Y la
   reapertura, la pantalla queda incierta pero la fila sigue DESCARTADA (un cancel del POS se aceptaría). En (a) y
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

1. **No refunds** — AngelPay doesn't expose refund intent to merchants. Cancellations (same-day before 23:00) coming in 1-2 weeks
2. **No card details in response** — AngelPay doesn't return maskedPan, cardBrand, or entryMode. Backend records with UNKNOWN
3. **No ticket printing from TPV** — AngelPay auto-prints its own ticket. We set `timeOutApproved=0` to skip it. Future: use their BroadcastReceiver print API for our own receipts
4. **QA creds hardcoded** — Need backend terminal config or SuperAdmin UI for production credential management
5. **Firebase package** — nexgo flavor uses `.sandbox` suffix temporarily. Register `.nexgo` in Firebase Console for production
