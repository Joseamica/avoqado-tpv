# Cierre del silencio en cancelaciones Nexgo/AngelPay — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la TPV Nexgo reporte por socket el desenlace de un cobro que el operador abandona, para que el POS que lo pidió deje de colgarse.

**Architecture:** Dos disparadores nuevos (`resetPayment()` y `onCleared()`) que llaman a la función de emisión que ya existe (`emitSocketResultIfSocketSourced`), ambos gateados por un predicado puro de lista blanca que garantiza que no hay una autorización en vuelo. Cero cambios en el server.

**Tech Stack:** Kotlin · Jetpack Compose · Hilt · MockK + Truth + `UnconfinedTestDispatcher` · Gradle (variante `nexgo`, tests en `sandboxDebug`)

**Spec:** [2026-08-10-angelpay-socket-cancel-result-design.md](../specs/2026-08-10-angelpay-socket-cancel-result-design.md)

## Global Constraints

- `export JAVA_HOME=$(/usr/libexec/java_home -v 23)` antes de cualquier comando de Gradle.
- **Riel AngelPay únicamente.** No tocar `PaymentViewModel` (ni sandbox ni production) — es el riel Blumon/PAX y es la zona #1 de regresión del repo.
- **Cero cambios en `avoqado-server`.** `resultToStatus` ya mapea `'cancelled' → CANCELLED`.
- **TDD no negociable:** toca dinero. Test primero, verlo fallar, implementar, verlo pasar.
- Dinero en `BigDecimal`, nunca `Float`. (No aplica a este cambio, pero rige el archivo.)
- Toda modificación va a `CHANGELOG.md` bajo `[Unreleased]` antes de considerar el trabajo terminado.
- **Git:** política del repo — no commitear sin permiso explícito del founder. Los pasos de commit están escritos pero quedan **GATEADOS**: al terminar cada tarea, dejar el árbol listo y pedir permiso, no ejecutar `git commit` por cuenta propia.
- Al commitear (cuando haya permiso): **sin `Co-Authored-By`**, y `git add` por rutas explícitas — nunca `git add -A` (hay otras sesiones de IA editando este árbol).

## File Structure

| Archivo | Responsabilidad | Acción |
|---|---|---|
| `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` | El predicado de dinero-en-vuelo (top-level, puro), los dos disparadores, los dos gates mudos | Modificar |
| `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt` | Los tests del predicado (exhaustivos) y del cableado | Modificar |
| `CHANGELOG.md` | Entrada bajo `[Unreleased] → Fixed`, incluida la deuda de PAX | Modificar |

El predicado va **top-level en el mismo archivo**, junto a `describirDivergenciaDeCobro` (VM:3013) — mismo idioma (español para lógica de dinero) y mismo patrón: función pura, probable sin levantar el ViewModel. No se crea archivo nuevo: son ~25 líneas fuertemente acopladas al `sealed class` de estados que ya vive en esa carpeta.

---

### Task 1: El predicado de dinero-en-vuelo (la lista blanca)

Es la pieza donde vive todo el riesgo del cambio. Va sola y primero, con tests exhaustivos, porque es lo único que separa "avisar una cancelación" de "mentirle al POS sobre un cobro que sí movió dinero".

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` (agregar función top-level al final, junto a `describirDivergenciaDeCobro`)
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt`

**Interfaces:**
- Consumes: `AngelPayPaymentState` (sealed class, 17 subclases) — ya existe.
- Produces: `internal fun sinDineroEnVuelo(state: AngelPayPaymentState): Boolean` — la Task 2 la usa como guard de sus dos disparadores.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar al final de `AngelPayPaymentViewModelTest.kt`, **antes** de la llave de cierre de la clase. Necesita **un** import nuevo (`android.content.Intent`); `PaymentRequest` y `mockk` ya están importados.

```kotlin
    // ----------------------------------------------------------------------
    // Predicado de dinero-en-vuelo — la lista blanca que impide el doble cobro
    // ----------------------------------------------------------------------

    @Test
    fun `estados pre-dinero permiten avisar cancelacion`() {
        val preDinero = listOf(
            AngelPayPaymentState.Idle,
            AngelPayPaymentState.Cancelled,
            AngelPayPaymentState.CollectingRating(amount = "100.00"),
            AngelPayPaymentState.CollectingTip(amount = "100.00", rating = 5),
            AngelPayPaymentState.SelectingMerchant(
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
            ),
            AngelPayPaymentState.Switching(targetMerchantId = 22, previousMerchantId = 11),
            AngelPayPaymentState.GeneratingCryptoQR(
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
            ),
            AngelPayPaymentState.Error(message = "Pago rechazado"),
        )

        preDinero.forEach { state ->
            assertThat(sinDineroEnVuelo(state)).isTrue()
        }
    }

    @Test
    fun `estados con dinero en vuelo o ya movido JAMAS permiten avisar cancelacion`() {
        // 🔴 Este es el test que impide el doble cobro. Si alguno de estos pasa a `true`,
        // el POS recibe "cancelado" sobre un cobro que puede haber capturado dinero, el
        // operador recobra, y el cliente paga dos veces.
        val dineroEnJuego = listOf(
            AngelPayPaymentState.LaunchingAngelPaySdk(
                request = mockk<PaymentRequest>(), amount = "100.00", tip = "0",
            ),
            AngelPayPaymentState.LaunchingAngelPay(
                intent = mockk<Intent>(), amount = "100.00", tip = "0",
            ),
            AngelPayPaymentState.WaitingForResult(),
            AngelPayPaymentState.Charging(merchantId = 11, startedAt = 0L),
            AngelPayPaymentState.RecordingPayment(),
            AngelPayPaymentState.ProcessingCash(),
            AngelPayPaymentState.AwaitingCryptoPayment(
                requestId = "req", paymentId = "pay", paymentUrl = "https://x",
                subtotal = "100.00", tipAmount = "0", totalAmount = "100.00", rating = null,
                expiresAt = "2026-08-10T18:00:00Z", expiresInSeconds = 600,
            ),
            AngelPayPaymentState.Success(authCode = "123456", amount = "100.00"),
            AngelPayPaymentState.Queued(
                message = "En cola", authCode = "123456", amount = "100.00",
            ),
        )

        dineroEnJuego.forEach { state ->
            assertThat(sinDineroEnVuelo(state)).isFalse()
        }
    }
```

Y este import al bloque de imports del archivo de test, junto a `android.content.Context` que ya está:

```kotlin
import android.content.Intent
```

`WaitingForResult()` se construye con todos sus defaults, así que no hace falta nombrar `AuthWatchdogLevel` (vive en `...features.payment.domain`, otro paquete).

- [ ] **Step 2: Correr los tests y verlos fallar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: **falla de COMPILACIÓN** — `Unresolved reference: sinDineroEnVuelo`. Eso es exactamente el rojo que buscamos: la función no existe todavía.

- [ ] **Step 3: Implementar el predicado**

Agregar al final de `AngelPayPaymentViewModel.kt`, después de `describirDivergenciaDeCobro` (que termina en la línea 3035):

```kotlin
/**
 * ¿Este estado garantiza que NO hay una autorización en vuelo ni dinero ya movido?
 *
 * Sólo desde estos estados es seguro avisarle al POS "cancelado" cuando el operador abandona la
 * pantalla. Si hay un cobro en curso —o ya terminó y capturó— mentirle al POS hace que el
 * operador recobre: doble cobro. Es el mismo incidente de device-QA 2026-07-14 que ya obligó a
 * dejar de emitir "failed" en los avisos EMV recuperables.
 *
 * 🔴 El `when` es EXHAUSTIVO A PROPÓSITO: **no tiene `else`, y no debe tenerlo**. Un estado nuevo
 * rompe la compilación y obliga a clasificarlo aquí, que es justo lo que queremos. Con `else` el
 * default sería "avisar", y un estado nuevo con dinero en vuelo se colaría en silencio hasta que
 * alguien cobrara dos veces. La dirección de esta lista es la decisión de diseño: un estado sin
 * clasificar debe caer en silencio (el POS agota su timeout — molesto y barato), nunca en mentira.
 *
 * Ver `docs/superpowers/specs/2026-08-10-angelpay-socket-cancel-result-design.md` §4.3.
 */
@VisibleForTesting
internal fun sinDineroEnVuelo(state: AngelPayPaymentState): Boolean = when (state) {
    // Pre-dinero: nada se ha lanzado al procesador todavía.
    // `Switching` espera a que asiente el cambio de merchant (con su propio timeout de 8s a Error);
    // `GeneratingCryptoQR` ni siquiera ha mostrado el QR al cliente.
    is AngelPayPaymentState.Idle,
    is AngelPayPaymentState.Cancelled,
    is AngelPayPaymentState.CollectingRating,
    is AngelPayPaymentState.CollectingTip,
    is AngelPayPaymentState.SelectingMerchant,
    is AngelPayPaymentState.Switching,
    is AngelPayPaymentState.GeneratingCryptoQR,
    is AngelPayPaymentState.Error -> true

    // Dinero en vuelo, o ya movido: JAMÁS avisar cancelación.
    // `AwaitingCryptoPayment` está aquí porque el cliente puede estar transfiriendo en este
    // instante; esa ruta ya tiene su propio `cancelCryptoPayment()` que notifica al backend.
    is AngelPayPaymentState.LaunchingAngelPaySdk,
    is AngelPayPaymentState.LaunchingAngelPay,
    is AngelPayPaymentState.WaitingForResult,
    is AngelPayPaymentState.Charging,
    is AngelPayPaymentState.RecordingPayment,
    is AngelPayPaymentState.ProcessingCash,
    is AngelPayPaymentState.AwaitingCryptoPayment,
    is AngelPayPaymentState.Success,
    is AngelPayPaymentState.Queued -> false
}
```

- [ ] **Step 4: Correr los tests y verlos pasar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: PASS. Si el compilador se queja de que el `when` no es exhaustivo, **no agregues `else`** — agrega el estado faltante a la rama que corresponda y déjalo comentado.

- [ ] **Step 5: Commit (GATEADO — pedir permiso primero)**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt
git commit -m "fix(angelpay): predicado de dinero-en-vuelo para avisos de cancelacion"
```

---

### Task 2: Los dos disparadores

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt:2888` (`resetPayment`) y agregar `emitCancelledIfAbandoned()` + `onCleared()` junto a los seams de socket (después de VM:2884)
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt`

**Interfaces:**
- Consumes: `sinDineroEnVuelo(state)` (Task 1) · `emitSocketResultIfSocketSourced(status, …)` (VM:2846, ya existe) · `emitSocketResultForTest(status, …)` (VM:2876, seam existente) · `setSocketPaymentSource(source, requestId)` (VM:220, público) · `onIntentLaunched()` (VM:1710, público — pone el estado en `WaitingForResult`)
- Produces: `internal fun emitCancelledIfAbandoned()` — sin parámetros, sin retorno. La Task 3 no la usa.

**Nota sobre cómo estos tests llegan a cada estado** (y una desviación deliberada del spec): el spec §7 caso 4 pide probar la red en `SelectingMerchant`. Llegar ahí de verdad exige mockear `initPayment` completo — turno abierto, venue, staff, credenciales AngelPay — y eso hace el test frágil sin probar nada más. Se parte en dos, que juntas cubren más:

- **Que `SelectingMerchant` (y los otros 7 pre-dinero) deban avisar** lo prueba la Task 1, exhaustivamente y sin VM.
- **Que el cableado funcione** lo prueban estos tests desde los dos estados a los que se llega en un paso por API pública: `Idle` (estado inicial del VM, en la lista blanca) y `WaitingForResult` (vía `onIntentLaunched()`, fuera de la lista).

Se prueban las dos direcciones del guard sin una sola línea de mockeo frágil.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar al final de `AngelPayPaymentViewModelTest.kt`, antes de la llave de cierre de la clase:

```kotlin
    // ----------------------------------------------------------------------
    // Disparadores de cancelación — resetPayment() y la red de onCleared()
    // ----------------------------------------------------------------------

    @Test
    fun `resetPayment con fuente SOCKET en estado pre-dinero emite cancelled`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-reset")

            vm.resetPayment()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-reset",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `resetPayment tras un desenlace ya emitido no emite un segundo resultado`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-ya-emitido")
            vm.emitSocketResultForTest(status = "failed", errorMessage = "Pago rechazado")
            clearMocks(socketManager, answers = false)

            vm.resetPayment()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `resetPayment sin fuente socket (cobro iniciado en la terminal) no emite nada`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            // Sin setSocketPaymentSource → _paymentSource queda null.
            vm.resetPayment()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared avisa cancelacion cuando la pantalla muere en estado pre-dinero`() = runTest(testDispatcher) {
        // El caso reportado 2026-08-10: el operador da atrás con el botón del sistema, el
        // NavController hace pop y la pantalla muere sin pasar por resetPayment().
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-abandonado")

            vm.emitCancelledIfAbandoned()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-abandonado",
                    status = "cancelled",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = any(),
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared NUNCA avisa cancelacion con una autorizacion en vuelo`() = runTest(testDispatcher) {
        // 🔴 El test que impide el doble cobro. Android puede destruir MainActivity mientras la
        // Activity del SDK de AngelPay tiene el foreground; si emitiéramos aquí, el POS vería
        // "cancelado" sobre un cobro que puede haber capturado dinero y el operador recobraría.
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-en-vuelo")
            vm.onIntentLaunched() // → AngelPayPaymentState.WaitingForResult
            runCurrent()

            vm.emitCancelledIfAbandoned()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `la red de onCleared no avisa cancelacion si ya se reporto el desenlace`() = runTest(testDispatcher) {
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-exitoso")
            vm.emitSocketResultForTest(status = "success", paymentId = "pay-1")
            clearMocks(socketManager, answers = false)

            vm.emitCancelledIfAbandoned()

            verify(exactly = 0) {
                socketManager.emitTerminalPaymentResult(
                    requestId = any(), status = any(), paymentId = any(), transactionId = any(),
                    cardDetails = any(), errorMessage = any(), receiptUrl = any(), receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
```

- [ ] **Step 2: Correr los tests y verlos fallar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: falla de compilación (`Unresolved reference: emitCancelledIfAbandoned`). Tras agregar sólo la firma vacía, los dos tests de "emite cancelled" fallarían con `Verification failed: call 1 of 1 ... was not called` — ése es el rojo real.

- [ ] **Step 3: Implementar los dos disparadores**

**3a.** Insertar en `AngelPayPaymentViewModel.kt` justo después de `socketRequestIdForTest()` (VM:2884) y antes del comentario `// ── Reset ──`:

```kotlin
    /**
     * 📡 Red de seguridad: la pantalla murió sin que nadie reportara el desenlace al POS.
     *
     * La llaman [resetPayment] (flecha atrás, `goBackOneStep` en el primer paso) y [onCleared]
     * (botón atrás del sistema, o cualquier navegación que haga pop del destino — ninguno de
     * esos dos pasa por código de la pantalla). Sin esto, el POS que pidió el cobro se queda en
     * "Esperando respuesta de la terminal" hasta que agote su propio timeout: es el caso
     * reportado en hardware el 2026-08-10.
     *
     * Gateada por [sinDineroEnVuelo]: si hay una autorización en curso NO se avisa nada y se deja
     * que el watchdog del server resuelva la fila. Mentirle al POS ahí provoca doble cobro.
     */
    @VisibleForTesting
    internal fun emitCancelledIfAbandoned() {
        if (_paymentSource != "SOCKET") return
        if (_socketRequestId == null) return
        if (_socketResultEmitted) return

        val state = _state.value
        if (!sinDineroEnVuelo(state)) {
            Timber.w(
                "📡 [AngelPay Socket] Pantalla abandonada en %s — NO se avisa cancelación " +
                    "(puede haber dinero en vuelo); la fila la resuelve el watchdog del server",
                state::class.simpleName,
            )
            return
        }

        emitSocketResultIfSocketSourced(
            status = "cancelled",
            errorMessage = "Pago cancelado en la terminal",
        )
    }

    override fun onCleared() {
        // Antes de super: el emit es síncrono (SocketManager.emitTerminalPaymentResult no
        // suspende ni lanza) y no depende del viewModelScope, que aquí ya está cancelado.
        emitCancelledIfAbandoned()
        super.onCleared()
    }
```

**3b.** En `resetPayment()` (VM:2888), insertar como **primera** línea del cuerpo, antes de `pendingAmount = BigDecimal.ZERO`:

```kotlin
    fun resetPayment() {
        // 📡 POS→TPV: si salimos sin haber reportado desenlace, el POS se queda colgado esperando.
        // Espejo del riel Blumon (PaymentViewModel.resetPayment). Va ANTES de la limpieza de abajo
        // a propósito: al dejar _paymentSource en null, esa limpieza es lo que deduplica contra la
        // red de onCleared() — sin bandera extra.
        emitCancelledIfAbandoned()
        pendingAmount = BigDecimal.ZERO
```

- [ ] **Step 4: Correr los tests y verlos pasar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: PASS, los 6 nuevos y los que ya existían. Prestar atención especial a que **no se rompa** `socket-sourced terminal cancellation emits cancelled` (test existente, ~línea 1539) ni los de retry-tras-decline (~líneas 419-500): son los que protegen que `_socketRequestId` sobreviva a la emisión.

- [ ] **Step 5: Commit (GATEADO — pedir permiso primero)**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt
git commit -m "fix(angelpay): la cancelacion en la terminal ya avisa al POS por socket"
```

---

### Task 3: Los dos gates mudos + CHANGELOG

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt:842-852`
- Modify: `CHANGELOG.md`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt`

**Interfaces:**
- Consumes: `emitSocketResultIfSocketSourced(status, errorMessage)` (VM:2846) · `authRepository.getVenueId(): String?` y `getStaffId(): String?` (**no** son `suspend` → mockear con `every`, no `coEvery`)
- Produces: nada nuevo.

- [ ] **Step 1: Escribir el test que falla**

Agregar al final de `AngelPayPaymentViewModelTest.kt`, antes de la llave de cierre:

```kotlin
    @Test
    fun `el gate de venue nulo avisa failed al POS con el motivo`() = runTest(testDispatcher) {
        // Vecinos del mismo bloque (monto inválido, sin turno, merchant inválido) ya emiten;
        // estos dos eran los únicos mudos del pre-cobro.
        every { authRepository.getVenueId() } returns null
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-sin-venue")

            vm.initPayment(amount = "100.00")
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-sin-venue",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = "No hay venue activo",
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `el gate de staff nulo avisa failed al POS con el motivo`() = runTest(testDispatcher) {
        every { authRepository.getVenueId() } returns "venue-1"
        every { authRepository.getStaffId() } returns null
        val vm = createViewModel()
        try {
            vm.setSocketPaymentSource("SOCKET", "req-sin-staff")

            vm.initPayment(amount = "100.00")
            runCurrent()

            verify(exactly = 1) {
                socketManager.emitTerminalPaymentResult(
                    requestId = "req-sin-staff",
                    status = "failed",
                    paymentId = any(),
                    transactionId = any(),
                    cardDetails = any(),
                    errorMessage = "No hay staff activo",
                    receiptUrl = any(),
                    receiptAccessKey = any(),
                )
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
```

- [ ] **Step 2: Correr los tests y verlos fallar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: FAIL con `Verification failed` — el `initPayment` corta en el gate sin emitir nada.

> Si en cambio falla porque el mock relajado de `authRepository.getVenueId()` devolvía `""` en vez de `null` y el flujo siguió de largo, es señal de que el `every { … } returns null` no se aplicó antes de construir el VM. `createViewModel()` debe llamarse **después** de los `every`.

- [ ] **Step 3: Implementar**

En `AngelPayPaymentViewModel.kt`, reemplazar las líneas 842-852 (los dos gates dentro de `initPayment`):

```kotlin
            val venueId = authRepository.getVenueId()
            if (venueId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay venue activo")
                // 📡 POS→TPV: gate pre-cobro — no se movió dinero (no-op salvo socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "No hay venue activo")
                return@launch
            }

            val staffId = authRepository.getStaffId()
            if (staffId == null) {
                _state.value = AngelPayPaymentState.Error("Error: No hay staff activo")
                // 📡 POS→TPV: gate pre-cobro — no se movió dinero (no-op salvo socket-sourced).
                emitSocketResultIfSocketSourced(status = "failed", errorMessage = "No hay staff activo")
                return@launch
            }
```

- [ ] **Step 4: Correr los tests y verlos pasar**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"
```

Esperado: PASS.

- [ ] **Step 5: Escribir el CHANGELOG**

En `CHANGELOG.md`, bajo `## [Unreleased]` → sección `### **Fixed**` (crearla si no existe):

```markdown
- **[Nexgo] Cancelar un cobro en la terminal ya avisa al POS**: al darle atrás en la TPV, la app emite `terminal:payment_result` con estado `cancelled` por el mismo canal de socket que ya usaba para reportar éxito, así el server cierra la solicitud y el POS deja de quedarse en "Esperando respuesta de la terminal". Antes sólo el riel Blumon/PAX avisaba: el `resetPayment()` de AngelPay limpiaba el `requestId` sin emitir nada.
  - Cubre también el botón atrás del sistema (que no pasa por código de la pantalla) vía una red en `onCleared()`.
  - Gateado por una lista blanca de estados pre-dinero: con una autorización en vuelo NO se avisa nada — mentirle al POS ahí provocaría un doble cobro. La fila la resuelve el watchdog del server.
  - Los gates de "No hay venue activo" / "No hay staff activo" ahora también reportan `failed` con el motivo, igual que sus vecinos del mismo bloque.
- **Pendiente conocido (riel Blumon/PAX)**: el botón atrás del sistema tiene el mismo hueco en `PaymentScreen` — su `resetPayment()` sí emite, pero un pop del NavController lo saltea. Se dejó fuera de este cambio a propósito: requiere sincronizar las variantes sandbox+production y probar los 6 flujos de pago en una PAX física.
```

- [ ] **Step 6: Verificación completa**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew testSandboxDebugUnitTest
```

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew compileNexgoDebugKotlin
```

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 23) && ./gradlew lint --continue
```

Esperado: suite completa en verde (0 failures), la variante `nexgo` —la que realmente corre este código— compila, y lint pasa.

> La máquina está compartida con otras sesiones de IA. Si la suite tarda varios minutos, **esperar, no abortar**: subir el timeout antes que rendirse. Si truena por memoria, partir por `--tests` en vez de declararlo imposible.

- [ ] **Step 7: Commit (GATEADO — pedir permiso primero)**

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt CHANGELOG.md
git commit -m "fix(angelpay): gates pre-cobro mudos + changelog"
```

---

## Verificación en hardware (post-merge, cuando haya Nexgo disponible)

No bloquea la implementación, pero el arreglo no está confirmado hasta que se corra:

1. POS (Sunmi D3) pide un cobro a la Nexgo N86.
2. El operador da atrás en "Método de Pago".
3. El POS debe liberarse de inmediato, no por vencimiento.
4. Contrastar contra el log del backend — buscar `terminal:payment_result` con el mismo `requestId` que apareció en `📡 [TerminalPayment] Emitted to socket`:

```bash
grep "terminal:payment_result" "$(ls -t /Users/amieva/Documents/Programming/Avoqado/avoqado-server/logs/development*.log | head -1)"
```

Repetir el paso 2 con el **botón atrás del sistema** en vez de la flecha: ése es el camino que sólo cubre la red de `onCleared()`.

## Notas de release

- Bump sugerido: **PATCH** 2.8.0 → 2.8.1 (`versionCode` +1). No hay capacidad nueva → no aplica decisión de tier.
- Las Nexgo **no** pasan por el sistema de updates de Avoqado: el APK firmado se le entrega al equipo de AngelPay y ellos lo despliegan por su TMS. Archivar en `~/Library/Mobile Documents/com~apple~CloudDocs/Avoqado/AngelPay/APK/<version>/nexgoProd/`, **nunca** bajo `Blumon/`.
- No hay nada que desplegar en el backend, así que no aplica la regla de orden de despliegue.
