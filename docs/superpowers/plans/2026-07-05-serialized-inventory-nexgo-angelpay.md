# Serialized Inventory (SIM) en Nexgo/AngelPay — Plan de Implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Habilitar venta/alta de SIM con verificación foto/código y registro serializado en terminales Nexgo/AngelPay, con paridad total respecto a PAX/Blumon, sin alterar ningún flujo de cobro existente.

**Architecture:** Verificación foto/código como paso de navegación previo a la pantalla de cobro (reusando el `VerificationScreen` standalone). Los datos serializados (ICCID, portabilidad, fotos, `skipReview`) viajan por `savedStateHandle` a `AngelPayPaymentScreen` como parámetros opcionales con default apagado, y se adjuntan al `PaymentContext.AngelPayPayment` (campos aditivos) que los recorders ya rutean al backend. El `PaymentViewModel` de Blumon NO se toca.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Navigation-Compose, JUnit + MockK, coroutines-test. Variante de build relevante: `nexgo`/`nexgoProd` (empaqueta el AAR AngelPay); tests corren en `sandboxDebug`.

## Global Constraints

- **PaymentViewModel de Blumon (`app/src/sandbox/` y `app/src/production/`) NO se modifica.** Ni una línea.
- **Todo camino nuevo va detrás de banderas apagadas por defecto** (`serialNumber = null`, `isPortabilidad = false`, `skipReview = false`, listas vacías). Un cobro normal de AngelPay (tarjeta/efectivo/crypto sin SIM) debe recorrer código idéntico al actual.
- **Money = `BigDecimal`**, nunca Float.
- **Timezone:** nunca `ZoneId.systemDefault()` ni `LocalDate.now()` sin zona — usar `VenueTimeZone.get(secureStorage)`.
- **Toda modificación se registra en `CHANGELOG.md` bajo `[Unreleased]`** con etiqueta `[Nexgo]`.
- **Comandos de verificación:** `export JAVA_HOME=$(/usr/libexec/java_home -v 23)` antes de cualquier gradle. Compilar con `compileSandboxDebugKotlin` Y `compileNexgoDebugKotlin`. Tests: `./gradlew testSandboxDebugUnitTest`. Lint: `./gradlew lint --continue`.
- **Commits:** seguir la política del repo — no commitear sin permiso del usuario; sin `Co-Authored-By`. Los pasos "Commit" del plan se ejecutan cuando el usuario lo autorice.
- **Ruta AngelPay aislada:** cambios solo en `features/payment/presentation/angelpay/`, `features/payment/domain/model/PaymentContext.kt`, los dos recorders, y el wiring en `AppNavigation.kt`/`NavRoute.kt`.

---

## Phase 0 — Validaciones abiertas (ANTES de escribir código de cobro)

Estas tareas son investigación/verificación en dispositivo y código. **No escribir código de las fases 1+ hasta cerrar las 4.** Cada una puede cambiar el diseño de una tarea posterior.

### Task 0.1: Confirmar comportamiento de `awaitPaxPaymentReady` en Nexgo

**Files:**
- Read: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt:~2149-2170` (bloque `onNavigateToPayment` de SerializedSale)
- Read: definición de `awaitPaxPaymentReady` (grep para localizarla)

- [ ] **Step 1:** `grep -rn "fun awaitPaxPaymentReady" app/src/main` y leer su cuerpo completo.
- [ ] **Step 2:** Determinar qué hace cuando `isAppToAppPayment()==true` (Nexgo): ¿retorna `true` inmediato, espera un init de PAX que nunca ocurre, o se salta? Documentar el hallazgo.
- [ ] **Step 3:** Registrar la conclusión al final de este archivo bajo "## Hallazgos Phase 0". Si BLOQUEA en Nexgo → la Task 3.x debe usar un gate distinto (o ninguno) para el path Nexgo. Si es no-op/true → no requiere cambio.

**Expected:** Conclusión escrita: "awaitPaxPaymentReady en Nexgo = {no-op | bloquea | espera}". Sin conclusión, no avanzar.

### Task 0.2: Validar cámara + escáner de código de barras en hardware Nexgo N86

**Files:** ninguno (prueba de dispositivo)

- [ ] **Step 1:** Localizar qué usa `VerificationScreen` para foto/código: `grep -rn "CameraX\|ImageCapture\|BarcodeScanning\|MLKit\|GmsBarcode\|zxing" app/src/main/java/com/jaac/avoqado_tpv/features/verification`.
- [ ] **Step 2:** Instalar en N86 físico: `./gradlew installNexgoDebug`. Abrir cualquier pantalla que use `VerificationScreen` (o crear un preview/entry temporal de prueba) y verificar que (a) la cámara abre y captura foto, (b) el escáner lee un código de barras.
- [ ] **Step 3:** Documentar en "## Hallazgos Phase 0": "Cámara N86 = {OK|falla:motivo}", "Escáner N86 = {OK|falla:motivo}". Si falla, escalar antes de continuar (puede requerir lib alterna) — el resto del plan asume que jalan.

**Expected:** Ambos confirmados OK en dispositivo, o motivo documentado.

### Task 0.3: Confirmar visibilidad del módulo SERIALIZED_INVENTORY en Nexgo

**Files:**
- Read: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt` (dónde se muestra el tile "Vender"/"Mis SIMs")

- [ ] **Step 1:** `grep -rn "SERIALIZED_INVENTORY\|onNavigateToSerializedSale\|MisSims" app/src/main/java/.../navigation app/src/main/java/.../home` y verificar que el gate del tile es por módulo/rol, NO por procesador (`ENABLE_PAX_SDK`/`isAppToAppPayment`).
- [ ] **Step 2:** En N86 con un staff/venue que tenga el módulo activo, confirmar que el tile aparece. (Recordatorio de memoria: "Mis SIMs" es WAITER-only — usar cuenta con `StaffVenue.role == WAITER`.)
- [ ] **Step 3:** Documentar en "## Hallazgos Phase 0". Si el tile NO aparece en Nexgo por un gate de procesador, agregar una tarea para removerlo de ese gate.

**Expected:** Tile visible en Nexgo con la cuenta correcta, o gap documentado.

### Task 0.4: Mapear el manejo existente de `skipReview` en el flujo AngelPay

**Files:**
- Read: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` (flujo de rating/tip)
- Read: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt`

- [ ] **Step 1:** `grep -n "skipReview\|CollectingRating\|CollectingTip\|showTipScreen\|showReviewScreen" app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/*.kt`
- [ ] **Step 2:** Determinar el punto exacto donde el ViewModel decide entrar a `CollectingRating`/`CollectingTip` (el `startPayment`/entrypoint). Documentar la firma del método y la línea.
- [ ] **Step 3:** Documentar en "## Hallazgos Phase 0": la línea/método donde se debe insertar el bypass `if (skipReview) → saltar a SelectingMerchant`. Esto alimenta Task 2.2.

**Expected:** Punto de inserción de `skipReview` identificado con archivo:línea.

---

## Phase 1 — Capa de datos: proof-of-sale en el contexto AngelPay (aditivo, testeable, sin UI)

Esta fase es 100% aditiva y no cambia comportamiento (los campos default vacíos). Es lo más seguro; se hace primero.

### Task 1.1: Agregar campos proof-of-sale a `PaymentContext.AngelPayPayment`

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/PaymentContext.kt:263-280`

**Interfaces:**
- Produces: `PaymentContext.AngelPayPayment` con nuevos campos opcionales `orderReference: String? = null`, `verificationPhotos: List<String> = emptyList()`, `verificationBarcodes: List<String> = emptyList()`, `isPortabilidad: Boolean = false`, `serialNumbers: List<String> = emptyList()`.

- [ ] **Step 1: Escribir el test que falla** (nuevo archivo si no existe `PaymentContextTest.kt`, o agregar caso):

```kotlin
// app/src/test/java/com/jaac/avoqado_tpv/features/payment/domain/model/PaymentContextAngelPaySerializedTest.kt
package com.jaac.avoqado_tpv.features.payment.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PaymentContextAngelPaySerializedTest {
    @Test
    fun `AngelPayPayment carries serialized proof-of-sale fields`() {
        val ctx = PaymentContext.AngelPayPayment(
            venueId = "v1",
            staffId = "s1",
            amount = BigDecimal("100.00"),
            orderId = "order_1",
            isPortabilidad = true,
            serialNumbers = listOf("8952140061234567890"),
            verificationPhotos = listOf("file:///photo1.jpg"),
        )
        assertEquals(true, ctx.isPortabilidad)
        assertEquals(listOf("8952140061234567890"), ctx.serialNumbers)
        assertEquals(listOf("file:///photo1.jpg"), ctx.verificationPhotos)
    }

    @Test
    fun `AngelPayPayment defaults leave proof-of-sale empty`() {
        val ctx = PaymentContext.AngelPayPayment(
            venueId = "v1", staffId = "s1", amount = BigDecimal("100.00"),
        )
        assertEquals(false, ctx.isPortabilidad)
        assertEquals(emptyList<String>(), ctx.serialNumbers)
        assertEquals(emptyList<String>(), ctx.verificationPhotos)
    }
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew testSandboxDebugUnitTest --tests "*PaymentContextAngelPaySerializedTest*"`
Expected: FAIL de compilación ("no parameter isPortabilidad").

- [ ] **Step 3: Implementar** — agregar los campos al final de `AngelPayPayment` (después de `orderNumber`, línea 279):

```kotlin
    data class AngelPayPayment(
        override val venueId: String,
        override val staffId: String,
        override val shiftId: String? = null,
        override val amount: BigDecimal,
        override val tip: BigDecimal = BigDecimal.ZERO,
        override val rating: Int? = null,
        override val merchantAccountId: String? = null,
        override val blumonSerialNumber: String = "",
        override val deviceSerialNumber: String? = null,
        override val idempotencyKey: String? = null,
        val cardDetails: CardDetails? = null,
        val authorizationCode: String = "",
        val referenceNumber: String = "",
        val angelPayTransactionId: String? = null,
        val orderId: String? = null,
        val orderNumber: String? = null,
        // 📸 NON-BLOCKING PROOF-OF-SALE (serialized inventory / SIM) — additive, default off
        val orderReference: String? = null,
        val verificationPhotos: List<String> = emptyList(),
        val verificationBarcodes: List<String> = emptyList(),
        val isPortabilidad: Boolean = false,
        val serialNumbers: List<String> = emptyList(),
    ) : PaymentContext()
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew testSandboxDebugUnitTest --tests "*PaymentContextAngelPaySerializedTest*"`
Expected: PASS.

- [ ] **Step 5: Commit** (con permiso del usuario)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/domain/model/PaymentContext.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/domain/model/PaymentContextAngelPaySerializedTest.kt
git commit -m "feat(nexgo): add proof-of-sale fields to AngelPayPayment context"
```

### Task 1.2: Mapear proof-of-sale en `buildAngelPayOrderPaymentRequest`

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/repository/OrderPaymentRecorder.kt:305-332`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/repository/OrderPaymentRecorderAngelPayTest.kt` (crear)

**Interfaces:**
- Consumes: `PaymentContext.AngelPayPayment` con campos de Task 1.1.
- Produces: `buildAngelPayOrderPaymentRequest` que setea `isPortabilidad`/`serialNumbers` en el `OrderPaymentRequest`.

- [ ] **Step 1: Escribir el test que falla.** Localizar cómo se construyen los mocks de `OrderPaymentRecorder` (apiService MockK). Test:

```kotlin
// Verifica que el request enviado al backend incluye serialNumbers/isPortabilidad
@Test
fun `AngelPay order payment with SIM attaches serialNumbers and isPortabilidad`() = runTest {
    val requestSlot = slot<OrderPaymentRequest>()
    coEvery { apiService.recordOrderPayment(any(), capture(requestSlot)) } returns mockSuccessResponse()

    val context = PaymentContext.AngelPayPayment(
        venueId = "v1", staffId = "s1", amount = BigDecimal("100.00"),
        orderId = "order_1", isPortabilidad = true,
        serialNumbers = listOf("8952140061234567890"),
    )
    recorder.recordPayment(context, CardDetails.CASH, "AUTH", "REF")

    assertEquals(true, requestSlot.captured.isPortabilidad)
    assertEquals(listOf("8952140061234567890"), requestSlot.captured.serialNumbers)
}

@Test
fun `AngelPay order payment without SIM leaves serialNumbers null`() = runTest {
    val requestSlot = slot<OrderPaymentRequest>()
    coEvery { apiService.recordOrderPayment(any(), capture(requestSlot)) } returns mockSuccessResponse()

    val context = PaymentContext.AngelPayPayment(
        venueId = "v1", staffId = "s1", amount = BigDecimal("100.00"), orderId = "order_1",
    )
    recorder.recordPayment(context, CardDetails.CASH, "AUTH", "REF")

    assertEquals(null, requestSlot.captured.isPortabilidad)
    assertEquals(null, requestSlot.captured.serialNumbers)
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew testSandboxDebugUnitTest --tests "*OrderPaymentRecorderAngelPayTest*"`
Expected: FAIL (isPortabilidad viene null cuando debería ser true).

- [ ] **Step 3: Implementar** — agregar dos líneas al `return OrderPaymentRequest(...)` de `buildAngelPayOrderPaymentRequest` (antes de `idempotencyKey`, línea 330):

```kotlin
            deviceSerialNumber = context.deviceSerialNumber,
            // 📸 NON-BLOCKING PROOF-OF-SALE (serialized inventory / SIM)
            isPortabilidad = context.isPortabilidad.takeIf { it },
            serialNumbers = context.serialNumbers.takeIf { it.isNotEmpty() },
            idempotencyKey = context.idempotencyKey,
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew testSandboxDebugUnitTest --tests "*OrderPaymentRecorderAngelPayTest*"`
Expected: PASS (ambos).

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/repository/OrderPaymentRecorder.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/repository/OrderPaymentRecorderAngelPayTest.kt
git commit -m "feat(nexgo): map serialNumbers/isPortabilidad in AngelPay order recorder"
```

### Task 1.3: Mapear proof-of-sale en `buildAngelPayFastPaymentRequest` (simetría)

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/repository/FastPaymentRecorder.kt:299-327`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/repository/FastPaymentRecorderAngelPayTest.kt` (crear)

**Interfaces:**
- Produces: `buildAngelPayFastPaymentRequest` que setea `orderReference`/`verificationPhotos`/`verificationBarcodes`/`isPortabilidad`/`serialNumbers`.

- [ ] **Step 1: Escribir el test que falla** — análogo a Task 1.2 pero con `AngelPayPayment` SIN `orderId` (rutea a FastPaymentRecorder) y verificando `FastPaymentRequest`:

```kotlin
@Test
fun `AngelPay fast payment with SIM attaches proof-of-sale fields`() = runTest {
    val slot = slot<FastPaymentRequest>()
    coEvery { apiService.recordFastPayment(capture(slot)) } returns mockSuccessResponse()

    val context = PaymentContext.AngelPayPayment(
        venueId = "v1", staffId = "s1", amount = BigDecimal("100.00"),
        isPortabilidad = true, serialNumbers = listOf("8952140061234567890"),
        verificationPhotos = listOf("file:///p1.jpg"),
    )
    recorder.recordPayment(context, CardDetails.CASH, "AUTH", "REF")

    assertEquals(true, slot.captured.isPortabilidad)
    assertEquals(listOf("8952140061234567890"), slot.captured.serialNumbers)
    assertEquals(listOf("file:///p1.jpg"), slot.captured.verificationPhotos)
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew testSandboxDebugUnitTest --tests "*FastPaymentRecorderAngelPayTest*"`
Expected: FAIL.

- [ ] **Step 3: Implementar** — agregar al `return FastPaymentRequest(...)` de `buildAngelPayFastPaymentRequest` (antes de `idempotencyKey`):

```kotlin
            deviceSerialNumber = context.deviceSerialNumber,
            // 📸 NON-BLOCKING PROOF-OF-SALE (serialized inventory / SIM)
            orderReference = context.orderReference,
            verificationPhotos = context.verificationPhotos.takeIf { it.isNotEmpty() },
            verificationBarcodes = context.verificationBarcodes.takeIf { it.isNotEmpty() },
            isPortabilidad = context.isPortabilidad.takeIf { it },
            serialNumbers = context.serialNumbers.takeIf { it.isNotEmpty() },
            idempotencyKey = context.idempotencyKey,
```

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew testSandboxDebugUnitTest --tests "*FastPaymentRecorderAngelPayTest*"`
Expected: PASS.

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/data/repository/FastPaymentRecorder.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/data/repository/FastPaymentRecorderAngelPayTest.kt
git commit -m "feat(nexgo): map proof-of-sale in AngelPay fast recorder"
```

---

## Phase 2 — AngelPay ViewModel/Screen: recibir y adjuntar datos serializados (detrás de banderas)

### Task 2.1: `AngelPayPaymentViewModel` acepta y persiste datos serializados

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt`
- Test: `app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt`

**Interfaces:**
- Produces: método `fun setSerializedSaleInfo(serialNumber: String?, isPortabilidad: Boolean, verificationPhotos: List<String>, verificationBarcodes: List<String>)` que guarda en campos privados; los builders de contexto (métodos `PaymentContext.AngelPayPayment(...)` en ~980, ~1177, ~1248) pasan esos campos.

- [ ] **Step 1: Escribir el test que falla.** Usar el seam `@VisibleForTesting launchSdkRequest` existente (sandbox compila con `ANGELPAY_SDK_ENABLED=false`). Test: tras `setSerializedSaleInfo(...)` + cobro (card u order), el `PaymentContext.AngelPayPayment` construido lleva `serialNumbers`/`isPortabilidad`. Priorizar verificar vía el `recordPaymentUseCase` mockeado (capturar el context):

```kotlin
@Test
fun `SIM sale info is attached to AngelPay payment context`() = runTest {
    val ctxSlot = slot<PaymentContext>()
    coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(mockReceipt())
    val vm = createViewModel()
    vm.setSerializedSaleInfo(
        serialNumber = "8952140061234567890", isPortabilidad = true,
        verificationPhotos = listOf("file:///p1.jpg"), verificationBarcodes = emptyList(),
    )
    // prime state to a chargeable point and drive the record path via the test seam
    vm.startCardPaymentForTest(orderId = "order_1", amount = BigDecimal("100.00"))
    simulateSdkApproved(vm)   // helper using launchSdkRequest seam

    val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
    assertEquals(listOf("8952140061234567890"), ctx.serialNumbers)
    assertEquals(true, ctx.isPortabilidad)
    vm.viewModelScope.cancel()
}

@Test
fun `normal AngelPay payment leaves serialized fields empty`() = runTest {
    val ctxSlot = slot<PaymentContext>()
    coEvery { recordPaymentUseCase(capture(ctxSlot), any(), any(), any()) } returns Result.success(mockReceipt())
    val vm = createViewModel()
    vm.startCardPaymentForTest(orderId = null, amount = BigDecimal("50.00"))
    simulateSdkApproved(vm)

    val ctx = ctxSlot.captured as PaymentContext.AngelPayPayment
    assertEquals(emptyList<String>(), ctx.serialNumbers)
    assertEquals(false, ctx.isPortabilidad)
    vm.viewModelScope.cancel()
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"`
Expected: FAIL ("setSerializedSaleInfo unresolved").

- [ ] **Step 3: Implementar.** Agregar campos privados + setter, y pasarlos en LOS TRES sitios donde se construye `PaymentContext.AngelPayPayment` (cash ~980, card ~1177, order ~1248). Ejemplo del setter y del uso:

```kotlin
    // 📸 Serialized inventory (SIM) — set from navigation before charging. Default off.
    private var pendingSerialNumbers: List<String> = emptyList()
    private var pendingIsPortabilidad: Boolean = false
    private var pendingVerificationPhotos: List<String> = emptyList()
    private var pendingVerificationBarcodes: List<String> = emptyList()

    fun setSerializedSaleInfo(
        serialNumber: String?,
        isPortabilidad: Boolean,
        verificationPhotos: List<String>,
        verificationBarcodes: List<String>,
    ) {
        pendingSerialNumbers = listOfNotNull(serialNumber)
        pendingIsPortabilidad = isPortabilidad
        pendingVerificationPhotos = verificationPhotos
        pendingVerificationBarcodes = verificationBarcodes
    }
```

En cada `PaymentContext.AngelPayPayment(...)` agregar:

```kotlin
                orderId = pendingOrderId,
                orderNumber = pendingOrderNumber,
                isPortabilidad = pendingIsPortabilidad,
                serialNumbers = pendingSerialNumbers,
                verificationPhotos = pendingVerificationPhotos,
                verificationBarcodes = pendingVerificationBarcodes,
```

Limpiar en `resetPayment()`: `pendingSerialNumbers = emptyList(); pendingIsPortabilidad = false; pendingVerificationPhotos = emptyList(); pendingVerificationBarcodes = emptyList()`.

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"`
Expected: PASS.

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt
git commit -m "feat(nexgo): attach serialized SIM info to AngelPay payment context"
```

### Task 2.2: `skipReview` salta rating/tip en el flujo AngelPay

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` (punto identificado en Task 0.4)
- Test: `AngelPayPaymentViewModelTest.kt`

**Interfaces:**
- Consumes: bandera `skipReview` pasada al entrypoint (nuevo param en el método de arranque de cobro, default `false`).
- Produces: cuando `skipReview==true`, el ViewModel NO entra a `CollectingRating`/`CollectingTip`; va directo a `SelectingMerchant` (o al estado que corresponda según Task 0.4).

- [ ] **Step 1: Escribir el test que falla:**

```kotlin
@Test
fun `skipReview true bypasses rating and tip states`() = runTest {
    val vm = createViewModel(merchants = listOf(mockMerchant()))
    vm.beginPayment(amount = BigDecimal("100.00"), orderId = "order_1", skipReview = true)
    // el primer estado tras iniciar NO debe ser CollectingRating/CollectingTip
    val state = vm.state.value
    assertTrue(state !is AngelPayPaymentState.CollectingRating)
    assertTrue(state !is AngelPayPaymentState.CollectingTip)
    vm.viewModelScope.cancel()
}

@Test
fun `skipReview false keeps rating and tip when enabled`() = runTest {
    val vm = createViewModel(merchants = listOf(mockMerchant()),
        settings = settings(showReviewScreen = true, showTipScreen = true))
    vm.beginPayment(amount = BigDecimal("100.00"), orderId = "order_1", skipReview = false)
    assertTrue(vm.state.value is AngelPayPaymentState.CollectingRating ||
               vm.state.value is AngelPayPaymentState.CollectingTip)
    vm.viewModelScope.cancel()
}
```

- [ ] **Step 2: Correr y verificar que falla**

Run: `./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*skipReview*"`
Expected: FAIL.

- [ ] **Step 3: Implementar** — en el método de arranque identificado en Task 0.4, envolver la decisión rating/tip:

```kotlin
    // skipReview default false → cobro normal recorre el mismo camino de siempre.
    if (skipReview) {
        // Venta de SIM: sin rating/tip → ir directo a selección de comercio/cobro
        transitionToMerchantSelection(amount, orderId, orderNumber)
        return
    }
    // ... lógica existente de rating/tip sin cambios ...
```

Guardar `skipReview` en un campo si el flujo lo necesita más adelante; limpiarlo en `resetPayment()`.

- [ ] **Step 4: Correr y verificar que pasa**

Run: `./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"`
Expected: PASS (incluye regresión de flujos existentes).

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt
git commit -m "feat(nexgo): honor skipReview in AngelPay payment flow"
```

### Task 2.3: `AngelPayPaymentScreen` acepta params serializados y los cablea al ViewModel

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt:68-92`

**Interfaces:**
- Consumes: `setSerializedSaleInfo` (Task 2.1) y `beginPayment(..., skipReview)` (Task 2.2).
- Produces: `AngelPayPaymentScreen(..., serialNumber: String? = null, isPortabilidad: Boolean = false, skipReview: Boolean = false, verificationPhotos: List<String> = emptyList(), verificationBarcodes: List<String> = emptyList())`.

- [ ] **Step 1:** Agregar los parámetros opcionales (default apagado) a la firma del composable (después de `orderNumber`, línea 71):

```kotlin
    orderNumber: String? = null,
    serialNumber: String? = null,
    isPortabilidad: Boolean = false,
    skipReview: Boolean = false,
    verificationPhotos: List<String> = emptyList(),
    verificationBarcodes: List<String> = emptyList(),
```

- [ ] **Step 2:** En el `LaunchedEffect`/init del screen que arranca el pago, antes de arrancar, empujar la info serializada y pasar `skipReview`:

```kotlin
    LaunchedEffect(Unit) {
        viewModel.setSerializedSaleInfo(serialNumber, isPortabilidad, verificationPhotos, verificationBarcodes)
        // ... arranque existente, ahora con skipReview ...
    }
```

- [ ] **Step 3: Verificar compilación de AMBAS variantes**

Run: `./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt
git commit -m "feat(nexgo): AngelPayPaymentScreen accepts serialized SIM params (default off)"
```

---

## Phase 3 — Navegación: verificación previa + traspaso de datos a AngelPay

### Task 3.1: El destino `AngelPayPayment` lee los datos serializados del `savedStateHandle`

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt:2271-2330`

**Interfaces:**
- Consumes: keys ya guardadas por SerializedSale (`serialNumber`, `isPortabilidad`, `skipReview`) + nuevas (`verificationPhotos`, `verificationBarcodes`) de Task 3.2.
- Produces: pasa esos valores al `AngelPayPaymentScreen(...)`.

- [ ] **Step 1:** En el `composable(NavRoute.AngelPayPayment.route)`, leer los keys adicionales:

```kotlin
            val serialNumber = navController.previousBackStackEntry?.savedStateHandle?.get<String>("serialNumber")
            val isPortabilidad = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("isPortabilidad") ?: false
            val skipReview = navController.previousBackStackEntry?.savedStateHandle?.get<Boolean>("skipReview") ?: false
            val verificationPhotos = navController.previousBackStackEntry?.savedStateHandle?.get<ArrayList<String>>("verificationPhotos") ?: arrayListOf()
            val verificationBarcodes = navController.previousBackStackEntry?.savedStateHandle?.get<ArrayList<String>>("verificationBarcodes") ?: arrayListOf()
```

- [ ] **Step 2:** Pasarlos al `AngelPayPaymentScreen(...)`:

```kotlin
                serialNumber = serialNumber,
                isPortabilidad = isPortabilidad,
                skipReview = skipReview,
                verificationPhotos = verificationPhotos,
                verificationBarcodes = verificationBarcodes,
```

- [ ] **Step 3: Verificar compilación de ambas variantes**

Run: `./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4:** Manual smoke (N86): venta de SIM SIN verificación exigida → confirmar en logs/backend que el cobro lleva ICCID (`serialNumbers`) y NO muestra propina. Documentar.

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt
git commit -m "feat(nexgo): thread serialized SIM data into AngelPay payment route"
```

### Task 3.2: Insertar `VerificationScreen` como paso previo al cobro en Nexgo

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt` (bloque `onNavigateToPayment` de SerializedSale, ~2149; y nuevo/reusado destino de verificación)
- Read/Reuse: `app/src/main/java/com/jaac/avoqado_tpv/features/verification/presentation/VerificationScreen.kt`
- Possibly Modify: `app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/NavRoute.kt` (ruta de verificación pre-pago si no existe una reusable)

**Interfaces:**
- Produces: cuando el venue exige verificación (`showVerificationScreen || requireVerificationPhoto || requireVerificationBarcode`) y es Nexgo (`isAppToAppPayment()`), la venta de SIM navega primero a `VerificationScreen`; al confirmar, guarda `verificationPhotos`/`verificationBarcodes` + los keys serializados en `savedStateHandle` y navega a `AngelPayPayment`.

- [ ] **Step 1:** Determinar si ya existe un destino de verificación pre-pago reusable (Blumon lo maneja INLINE en `PaymentScreen`, así que probablemente haya que exponer `VerificationScreen` como destino de nav propio para Nexgo). Si no existe, agregar `NavRoute.SerializedVerification` en `NavRoute.kt`.
- [ ] **Step 2:** En el `onNavigateToPayment` de SerializedSale, bifurcar por config del venue:

```kotlin
    val settings = tpvSettingsProvider.current()  // fuente ya disponible en scope
    val needsVerification = settings.showVerificationScreen ||
        settings.requireVerificationPhoto || settings.requireVerificationBarcode
    if (isAppToAppPayment() && needsVerification) {
        // guardar keys serializados (serialNumber/isPortabilidad/skipReview/amount/orderId/orderNumber)
        // navegar a la pantalla de verificación; su onConfirm continúa a AngelPayPayment
        navController.navigate(NavRoute.SerializedVerification.route)
    } else {
        // camino directo actual (ya cableado en Task 3.1)
        navController.navigate(getPaymentRoute())
    }
```

- [ ] **Step 3:** Definir el `composable(NavRoute.SerializedVerification.route)` que renderiza `VerificationScreen` (pre-pago: `paymentId = null`, `requirePhoto`/`requireBarcode` desde settings, `canSkip = !mandatorio`). Su `onConfirm(photos, barcodes)` guarda todo en `savedStateHandle` y navega a `AngelPayPayment`; su `onSkip` navega directo (si `canSkip`).
- [ ] **Step 4: Verificar compilación de ambas variantes**

Run: `./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt \
        app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/NavRoute.kt
git commit -m "feat(nexgo): pre-payment verification step before AngelPay SIM charge"
```

---

## Phase 4 — Subida de fotos proof-of-sale después del cobro

### Task 4.1: Disparar `VerificationUploadManager` tras cobro exitoso de SIM en Nexgo

**Files:**
- Modify: `app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` (handler de éxito)
- Read/Reuse: `app/src/main/java/com/jaac/avoqado_tpv/core/data/firebase/VerificationUploadManager.kt`

**Interfaces:**
- Consumes: `paymentId` del recibo de éxito + `pendingVerificationPhotos`.
- Produces: subida no-bloqueante (fire-and-forget) idéntica al patrón Blumon.

- [ ] **Step 1:** Localizar cómo Blumon dispara la subida no-bloqueante (buscar `VerificationUploadManager` en el `PaymentViewModel` de sandbox — SOLO lectura, referencia) para replicar la firma exacta.
- [ ] **Step 2:** En el handler de `Success` de AngelPay, si `pendingVerificationPhotos.isNotEmpty()`, encolar subida con el `paymentId` del recibo. Envolver en `if (pendingVerificationPhotos.isNotEmpty())` → cobro normal no dispara nada.

```kotlin
    if (pendingVerificationPhotos.isNotEmpty()) {
        val paymentId = successState.receipt?.paymentId
        if (paymentId != null) {
            verificationUploadManager.enqueueProofOfSale(paymentId, pendingVerificationPhotos)
        }
    }
```

- [ ] **Step 3: Test** — con `pendingVerificationPhotos` vacío, `verificationUploadManager` NO se llama (aserción de "cobro normal intacto"); con fotos, se llama una vez con el `paymentId`.

Run: `./gradlew testSandboxDebugUnitTest --tests "*AngelPayPaymentViewModelTest*"`
Expected: PASS.

- [ ] **Step 4: Commit** (con permiso)

```bash
git add app/src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt \
        app/src/test/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentViewModelTest.kt
git commit -m "feat(nexgo): non-blocking proof-of-sale photo upload after AngelPay SIM sale"
```

---

## Phase 5 — Regresión completa + verificación en dispositivo

### Task 5.1: Suite unitaria completa + lint + ambas variantes

**Files:** ninguno (verificación)

- [ ] **Step 1:** `./gradlew testSandboxDebugUnitTest --rerun-tasks` → 0 fallos (contar con el script python del `test-results`).
- [ ] **Step 2:** `./gradlew compileSandboxDebugKotlin compileNexgoDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 3:** `./gradlew lint --continue` → sin errores nuevos.

**Expected:** Todo verde.

### Task 5.2: Matriz de regresión en dispositivo (los 9 flujos del §7 del spec)

**Files:** ninguno (prueba manual en N86 + una PAX para el punto 0)

- [ ] **Step 0 (PAX intacto):** En una terminal PAX, cobro normal con tarjeta + venta de SIM con verificación → siguen funcionando igual (confirma que no rompimos Blumon).
- [ ] **Step 1:** Nexgo — cobro normal con tarjeta
- [ ] **Step 2:** Nexgo — cobro en efectivo
- [ ] **Step 3:** Nexgo — cobro con crypto
- [ ] **Step 4:** Nexgo — reembolso (admin)
- [ ] **Step 5:** Nexgo — venta de SIM SIN verificación exigida (cobra, registra ICCID+portabilidad, sin propina)
- [ ] **Step 6:** Nexgo — venta de SIM CON foto exigida (captura → cobra → foto sube no-bloqueante)
- [ ] **Step 7:** Nexgo — venta de SIM CON código exigido (escanea → cobra → registro correcto)
- [ ] **Step 8:** Nexgo — portabilidad (2 fotos) vs alta normal (1 foto)
- [ ] **Step 9:** Nexgo — recibo muestra folio + serial; verificar en Dashboard que la venta aparece con verificación pendiente

Monitoreo: `adb logcat -s AngelPayPaymentViewModel,OrderPaymentRecorder,FastPaymentRecorder,VerificationUploadManager`.

**Expected:** Los 4 flujos normales de Nexgo idénticos a antes; los 5 flujos de SIM funcionando; PAX intacto.

### Task 5.3: CHANGELOG + cierre

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1:** Entrada bajo `[Unreleased]` → `### Added`, etiqueta `[Nexgo]`, describiendo la paridad de venta/alta de SIM con verificación en Nexgo, mencionando que el cobro normal de AngelPay/PAX no cambió y la matriz de regresión ejecutada.
- [ ] **Step 2:** Recomendar bump: **MINOR** (nueva capacidad: el usuario ahora puede vender SIMs desde Nexgo — algo que antes no podía).
- [ ] **Step 3: Commit** (con permiso).

---

## Hallazgos Phase 0 (ejecutado 2026-07-05)

- **0.1 awaitPaxPaymentReady en Nexgo — RESUELTO, sin cambio.**
  `AppNavigation.kt:2717`: `if (!BuildConfig.ENABLE_PAX_SDK) return true`. En Nexgo
  (`ENABLE_PAX_SDK==false`) retorna `true` inmediato → NO bloquea. "Vender" no
  cuelga en Nexgo. No se toca.

- **0.2 Cámara / Escáner N86 — cámara CONFIRMADA por código; escáner + prueba de
  dispositivo PENDIENTE (requiere N86 físico).**
  La captura de foto usa **CameraX** (`androidx.camera.*` en
  `features/verification/presentation/components/CameraPreviewScreen.kt`) — estándar,
  agnóstico al hardware, se espera OK en N86. El escáner de código (`QrCodeScanner`
  + `ScannedProduct`) no se pineó la lib exacta; validar en dispositivo (0.2 Step 2/3).

- **0.3 Visibilidad módulo SERIALIZED_INVENTORY en Nexgo — RESUELTO por código.**
  `WelcomeScreen.kt:588` gatea el tile "Vender" por
  `moduleCode == MODULE_SERIALIZED_INVENTORY` + Plan Premium + permiso — NO por
  procesador. Debe aparecer en Nexgo. Confirmar en dispositivo con cuenta WAITER.

- **0.4 skipReview — punto identificado + HALLAZGO QUE CAMBIA EL ENFOQUE.**
  El flujo AngelPay entra a rating/tip vía `navigateToStep(step, amount)`
  (`AngelPayPaymentViewModel.kt:1831`), y el paso se calcula con el **`PaymentFlowGate`
  compartido** (`features/payment/domain/PaymentFlowGate.kt`), que AngelPay ya invoca
  (`nextAfterAmount(settings)` línea 440, `nextAfterRating(settings)` 452/460).

### ⚠️ Revisión de arquitectura (deriva de 0.4)

`PaymentFlowGate` YA contempla la verificación: `nextAfterAmount(settings)` devuelve
`VERIFY_PRE_PAYMENT` cuando `settings.showVerificationScreen` está activo
(`PaymentFlowGate.kt:23`). Y `navigateToStep` YA tiene la rama
`PrePaymentNextStep.VERIFY_PRE_PAYMENT` (`AngelPayPaymentViewModel.kt:1842`) — pero es
un **placeholder** que dice *"AngelPay doesn't support pre-payment verification — skip
to merchant"* y salta directo a `SelectingMerchant`.

**Implicación:** el enfoque de "ruta de navegación separada" del §4 del spec queda
SUPERADO. La forma más limpia, consistente y de menor riesgo es **llenar el hueco que
ya existe**:
1. Agregar estado `AngelPayPaymentState.VerifyingPrePayment`.
2. Cambiar la rama `VERIFY_PRE_PAYMENT` de `navigateToStep` para entrar a ese estado
   (en vez de saltar a merchant).
3. Renderizar el `VerificationScreen` standalone inline en `AngelPayPaymentScreen`
   para ese estado; al confirmar, guardar fotos/códigos y continuar a
   `SELECT_MERCHANT` (vía el mismo `PaymentFlowGate`).

**Seguridad (igual o mejor que el enfoque anterior):** el estado nuevo solo se alcanza
cuando `PaymentFlowGate` devuelve `VERIFY_PRE_PAYMENT`, lo que solo pasa si
`showVerificationScreen` (o feature `PRE_VERIFICATION`) está activo — exactamente el
mismo gate que usa Blumon. Un cobro normal (sin verificación) nunca alcanza el estado.

**skipReview:** `PaymentFlowGate` tiene una variante basada en `Set<PaymentFeature>`
(`nextAfterAmount(features)`, `nextAfterRating(features)`, `nextAfterTip(features)`).
Para venta de SIM, AngelPay debe usar la variante de **features** construyendo un set
que EXCLUYA `RATING_COLLECTION`/`TIP_COLLECTION` (por `skipReview`) e INCLUYA
`PRE_VERIFICATION` si aplica — en vez de la variante `settings`. Esto reusa el gate
compartido y es idéntico a como Blumon maneja `skipReview` con features.

**Tareas afectadas (a reescribir tras aprobación del usuario):**
- Phase 2 Task 2.2 (skipReview) → usar la variante `features` del gate, no un bypass ad-hoc.
- Phase 3 Task 3.2 → NO crear ruta de nav separada; en su lugar: estado
  `VerifyingPrePayment` + rama en `navigateToStep` + render inline del `VerificationScreen`.
- El spec §4 debe actualizarse para reflejar "llenar el hueco `VERIFY_PRE_PAYMENT`" en
  vez de "ruta de navegación separada".
