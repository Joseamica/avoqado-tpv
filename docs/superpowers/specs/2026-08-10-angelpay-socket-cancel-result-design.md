# Spec: cerrar el silencio en las salidas sin cobro del riel Nexgo/AngelPay

**Fecha:** 2026-08-10 · **Estado:** 🟢 APROBADO PARA IMPLEMENTAR
**Alcance:** `avoqado-tpv` únicamente (riel AngelPay). Cero cambios en `avoqado-server`.
**Tipo:** bugfix de arbitración POS→TPV. Sin capacidad nueva → sin decisión de tier. Bump sugerido: **PATCH** (2.8.0 → 2.8.1).

---

## 1. El caso que lo justifica

**2026-08-10, hardware real.** Un POS (Sunmi D3) le pide un cobro a una terminal Nexgo N86 ("TPV Prueba"). El operador de la Nexgo le da **atrás**. El POS se queda en "Procesando pago… Esperando respuesta de la terminal", **sin salida**.

El log del backend muestra la petición saliendo y nada de vuelta:

```
17:33:05.178  💳 [API] Terminal payment request
17:33:05.193  💳 [TerminalPayment] Sending payment request to terminal
17:33:05.195  📡 [TerminalPayment] Emitted to socket B61nfHvTK4qhI-LDAAAB
              ... ningún terminal:payment_result después
```

Con Blumon/PAX el mismo gesto **sí** cancelaba y el POS podía reintentar. No es un hueco del diseño general: es una asimetría entre los dos rieles.

## 2. Causa raíz — la asimetría, en una línea

| | Blumon (`PaymentViewModel`, sandbox+production) | AngelPay (`AngelPayPaymentViewModel`) |
|---|---|---|
| `resetPayment()` | **emite `"cancelled"`** antes de limpiar ([PaymentViewModel.kt:6150-6160](../../../app/src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt#L6150)) | **solo limpia** `_paymentSource` / `_socketRequestId` / `_socketResultEmitted` ([AngelPayPaymentViewModel.kt:2917-2919](../../../app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt#L2917)) |
| Disparo de emisión | colector de `_state` (`observeSocketPaymentResult`) que cubre Success / Error-no-reintentable / Cancelled | call sites explícitos por camino |

La función de emisión de AngelPay (`emitSocketResultIfSocketSourced`, VM:2846) es correcta y ya deduplica vía `_socketResultEmitted`. **El defecto no es la emisión: son los disparadores que faltan.**

## 3. Mapa de salidas sin cobro (auditado, no supuesto)

| Salida | ¿Avisa hoy? | Dónde |
|---|---|---|
| Cancelar dentro de la pantalla del SDK (`AngelPayResult.Cancelled`) | ✅ `"cancelled"` | VM:1786-1790 |
| Decline real del SDK (no recuperable) | ✅ `"failed"` | VM:1963 |
| Monto inválido · sin turno · merchant inválido | ✅ `"failed"` | VM:837, 873, 1082 |
| **Flecha atrás en Calificación / Propina / Método de Pago** | ❌ **silencio** | Screen:412 → `goBackOneStep()` → `resetPayment()` (VM:2330, 2341, 2359) |
| **Flecha atrás en Error / Cancelado / Idle** | ❌ **silencio** | Screen:419 → `resetPayment()` |
| **Botón atrás del sistema** (no hay `BackHandler` en la pantalla) | ❌ **silencio** | el NavController hace pop; el VM ni se entera |
| Error recuperable (`canRetry=true`) y el operador se sale | ❌ **silencio** | cae en `resetPayment()` |
| `"No hay venue activo"` / `"No hay staff activo"` | ❌ **silencio** | VM:844, 850 — sus vecinos sí emiten |
| App muerta a media transacción | ❌ imposible emitir | fuera de alcance, §6 |

**El caso reportado es la fila 4.** Con `skipReview=true` (lo que manda el POS) la Nexgo aterriza en "Método de Pago" esperando que toquen Tarjeta; darle atrás ahí llama a `resetPayment()`, que no emite.

## 4. Diseño — dos disparadores, una sola función de emisión

No se agrega un segundo camino de emisión. Se agregan **dos disparadores** que llaman a `emitSocketResultIfSocketSourced("cancelled", …)`, que ya existe y ya deduplica.

### 4.1 Capa 1 — `resetPayment()` emite antes de limpiar

Espejo exacto de Blumon. Cubre las filas 4, 5 y 7 de §3.

El orden importa: **emitir y después limpiar**. La limpieza deja `_paymentSource = null`, así que la Capa 2 encuentra nada que hacer — la deduplicación entre capas sale gratis del orden, sin bandera nueva.

### 4.2 Capa 2 — red de seguridad en `onCleared()`

Si el `NavBackStackEntry` muere sin que nadie haya emitido, se emite `"cancelled"`. Cubre la fila 6 (botón del sistema) y cualquier navegación lateral que se invente después.

`SocketManager.emitTerminalPaymentResult` es **síncrona y no lanza** (SocketManager.kt:1886-1920, try/catch interno), así que es segura desde `onCleared()`, donde el `viewModelScope` ya está cancelado.

Cuándo corre `onCleared()`, y por qué está bien en cada caso:

| Situación | ¿Corre? | Efecto |
|---|---|---|
| NavController hace pop del destino | ✅ | emite — **esto es lo que queremos** |
| Cambio de configuración (rotación) | ❌ | nada, correcto |
| Muerte del proceso | ❌ | nada; lo cubre el watchdog del server |
| Activity terminando (app cerrada) | ✅ | emite si es pre-dinero; best-effort |

### 4.3 🔴 El guardián: lista blanca de estados, nunca lista negra

La Capa 2 es la peligrosa. Android puede destruir `MainActivity` **mientras la Activity del SDK de AngelPay tiene el foreground** — está documentado en el propio VM (AngelPayPaymentViewModel.kt:416-424). Emitir `"cancelled"` ahí le diría al POS "cancelado" sobre un cobro cuyo dinero **sí se movió** → el operador recobra → doble cobro. Es el mismo incidente que ya costó una vez (device-QA 2026-07-14, documentado en VM:1952-1961).

Por eso la red se dispara con **lista blanca**:

| Emite `"cancelled"` | Nunca emite |
|---|---|
| `Idle` · `CollectingRating` · `CollectingTip` · `SelectingMerchant` · `Switching` · `GeneratingCryptoQR` · `Error` · `Cancelled` | `LaunchingAngelPaySdk` · `LaunchingAngelPay` · `WaitingForResult` · `Charging` · `RecordingPayment` · `ProcessingCash` · `AwaitingCryptoPayment` · `Success` · `Queued` |

La **dirección** de la lista es la decisión de diseño, no un detalle de implementación:

- Con lista blanca, un estado nuevo sin clasificar cae en **silencio** → el POS agota su timeout. Molesto, barato.
- Con lista negra, un estado nuevo sin clasificar cae en **mentira** → doble cobro. Caro, y descubierto tarde.

**Refuerzo en implementación:** el predicado se escribe como un `when` **exhaustivo sin `else`** sobre el `sealed class`. Así un estado nuevo no cae en ningún default: **rompe la compilación** y obliga a clasificarlo. La garantía deja de depender de que alguien recuerde actualizar una lista y pasa a ser del compilador.

`AwaitingCryptoPayment` queda fuera a propósito: el cliente puede estar transfiriendo en ese instante, y esa ruta ya tiene su propio `cancelCryptoPayment()` que notifica al backend (VM:2671).

`Switching` y `GeneratingCryptoQR` sí entran: en ambos no se ha lanzado ninguna autorización — `Switching` espera a que asiente el cambio de merchant (con su propio timeout de 8s a `Error`), y `GeneratingCryptoQR` ni siquiera ha mostrado el QR.

### 4.4 Seam de prueba

El cuerpo de `onCleared()` se extrae a `@VisibleForTesting internal fun emitCancelledIfAbandoned()`, que llaman tanto `onCleared()` como los tests. Mismo patrón de seams que ya usa el archivo (`emitSocketResultForTest`, `socketRequestIdForTest`, VM:2874-2884).

### 4.5 Los dos gates mudos

`initPayment` emite `"failed"` en monto inválido, sin turno y merchant inválido, pero no en `"No hay venue activo"` (VM:844) ni `"No hay staff activo"` (VM:850). Se alinean con sus vecinos: `"failed"` con el motivo.

Con la Capa 2 igual acabarían avisando, pero un `"failed"` inmediato **con la razón** le sirve mucho más al POS que un `"cancelled"` genérico treinta segundos después.

### 4.6 Interacción con reintento-tras-decline (verificado, no se rompe)

`_socketRequestId` **sobrevive** a la emisión a propósito (VM:433-440): es el enlace de arbitración que se hilvana al Payment registrado (`terminalPaymentRequestId`), y es lo que le permite al server reconciliar una fila `FAILED` a `COMPLETED` cuando el cajero reintenta en la terminal y aprueba.

Ese mecanismo no se toca. Tras un decline, `_socketResultEmitted = true`; si el operador se sale, ambas capas nuevas quedan suprimidas por ese mismo guard. Correcto: al POS ya se le dijo `"failed"`.

## 5. Server — cero cambios

`resultToStatus` ya mapea `'cancelled' → CANCELLED` y `closeRow` ya cierra la fila, incluso si el long-poll ya venció (`lateResult`) — [terminal-payment.service.ts:136-148](../../../../avoqado-server/src/services/terminal-payment.service.ts#L136). El contrato del socket ya acepta el status. No hay nada que desplegar del lado del backend, y por lo tanto no aplica la regla de orden de despliegue.

## 6. Fuera de alcance, con razón explícita

| Qué | Por qué no |
|---|---|
| **App muerta a media transacción** | No se puede emitir desde un proceso muerto. Emitir `"cancelled"` al siguiente arranque sería mentir: nadie sabe si el banco aprobó. Ese caso ya tiene dueño — el watchdog del server, que lo parquea `UNKNOWN`. |
| **Timeout del watchdog de autorización de la terminal** | Hoy solo publica avisos de UI y **jamás cancela la autorización**, por diseño explícito (VM:1327-1330). Emitir ahí sería la misma mentira. El long-poll del server (5 min) es la salida. |
| **Riel Blumon/PAX** | Tiene el mismo hueco de botón-atrás-del-sistema (tampoco tiene `BackHandler` ni red en `onCleared`; su `resetPayment` sí emite, pero un pop del NavController lo saltea igual). Se deja para su propia sesión: `PaymentViewModel` es la zona #1 de regresión del repo, 8 features comparten su máquina de estados, y hay que sincronizar sandbox+production y probar los 6 flujos en una PAX física. **Anotado en `CHANGELOG.md`.** |
| **`handleRecordFailure` pinta Error un éxito encolado** | **Ya está arreglado.** Hoy emite `"success"` primero (VM:533) y devuelve `AngelPayPaymentState.Queued`, no `Error` (VM:595). El defecto reportado es información rancia. |

## 7. Pruebas — TDD obligatorio

Toca dinero, así que los tests van **primero** (regla del workspace: dinero/pagos ⇒ TDD + suite del módulo, no negociable). Siete casos en `AngelPayPaymentViewModelTest`, sobre la infraestructura de socket que ese archivo ya tiene (`setSocketPaymentSource`, mock de `socketManager`, helper `createViewModel()`).

| # | Caso | Esperado |
|---|---|---|
| 1 | `resetPayment()` con fuente SOCKET en estado pre-dinero | exactamente un `"cancelled"` |
| 2 | `resetPayment()` tras un decline ya emitido | **no** emite un segundo resultado |
| 3 | `resetPayment()` sin fuente socket (cobro iniciado en la terminal) | silencio total |
| 4 | Red de `onCleared()` en `SelectingMerchant` | `"cancelled"` — **el caso reportado** |
| 5 | 🔴 Red de `onCleared()` en `WaitingForResult`, `LaunchingAngelPaySdk`, `Charging`, `RecordingPayment` | **cero emisiones** |
| 6 | Red de `onCleared()` tras un Success | silencio (ya emitido) |
| 7 | Gates de venue/staff nulos | `"failed"` con motivo |

**El caso 5 es el más importante de los siete**: es el que impide el doble cobro, y es el que debe fallar de forma ruidosa si alguien invierte la lista blanca en el futuro.

## 8. Verificación

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23)
./gradlew testSandboxDebugUnitTest    # suite completa, 0 failures
./gradlew compileNexgoDebugKotlin     # la variante que realmente corre este código
./gradlew lint --continue
```

Verificación en hardware (Nexgo N86, cuando haya terminal disponible): POS pide cobro → operador da atrás en "Método de Pago" → el POS debe recibir el resultado y liberarse. Contrastar contra el log del backend buscando `terminal:payment_result` con el mismo `requestId`.

## 9. Entregables

- `AngelPayPaymentViewModel.kt` — Capa 1 en `resetPayment()`, Capa 2 en `onCleared()` + `emitCancelledIfAbandoned()`, dos gates de §4.5.
- `AngelPayPaymentViewModelTest.kt` — los 7 casos de §7.
- `CHANGELOG.md` bajo `[Unreleased]` → **Fixed**, incluyendo la nota del hueco pendiente de PAX (§6).
