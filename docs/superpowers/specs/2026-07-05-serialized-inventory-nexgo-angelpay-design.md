# Serialized Inventory (venta/alta de SIM) en Nexgo/AngelPay — Diseño

**Fecha:** 2026-07-05
**Alcance:** Paridad total de `SERIALIZED_INVENTORY` (venta y alta de SIM con verificación foto/código antes de cobrar) en terminales Nexgo N86, igual que hoy funciona en PAX/Blumon.
**Regla de oro:** NO tocar el `PaymentViewModel` de Blumon (8 flujos comparten ese código). Todo el trabajo va aislado en el path AngelPay, detrás de banderas apagadas por defecto.

---

## 1. Objetivo

Permitir dar de alta y vender SIMs desde terminales Nexgo N86 con la misma
verificación (foto y/o código de barras antes de cobrar) y el mismo registro
serializado (ICCID ligado, portabilidad, verificación pendiente en backend) que
hoy solo existe en el path de cobro Blumon/PAX.

## 2. Estado actual (verificado en código)

**Lo que YA existe y es agnóstico al hardware (no se toca):**

- Módulos/tiles: "Vender" (`SerializedSaleScreen`), "Mis SIMs"/"Mis Ventas"
  (`MySalesScreen`), registro por lote (`SerializedInventoryScreen`) — viven en
  `features/serialized_sale/` y `features/serialized_inventory/`, invocados desde
  `AppNavigation.kt` sin importar el procesador.
- Escaneo de la SIM (ICCID + categoría) y creación de orden vía quick-sell del
  backend — `SerializedSaleScreen.kt` / `SerializedSaleViewModel.kt`.
- Pantalla de foto/código de barras — `features/verification/presentation/VerificationScreen.kt`
  (648 líneas, composable **standalone**: recibe `paymentId?`, `amount`,
  `orderNumber`, `requirePhoto`, `requireBarcode`, callbacks; no está acoplada al
  `PaymentViewModel`).
- Subida de fotos al backend, no-bloqueante y con cola offline —
  `core/data/firebase/VerificationUploadManager.kt`.
- El backend ya recibe proof-of-sale (`serialNumbers`, `isPortabilidad`,
  `verificationPhotos`, `verificationBarcodes`) en el mismo endpoint de cobro que
  usa PAX hoy. **No requiere cambios de backend.**

**Lo que se pierde HOY al vender una SIM en Nexgo (los huecos):**

`SerializedSaleScreen` crea la orden y navega a `getPaymentRoute()`
(`AppNavigation.kt:2768` → devuelve `AngelPayPayment.route` en Nexgo), guardando
en `savedStateHandle`: `initialAmount`, `orderId`, `orderNumber`, `skipReview`,
`skipLocalOrderValidation`, `isPortabilidad`, `serialNumber`, `categoryName`
(`AppNavigation.kt:~2149`).

Pero el destino `AngelPayPayment` (`AppNavigation.kt:2271`) **solo lee**
`initialAmount`, `orderId`, `orderNumber`, `entryPoint`. Se caen:
`serialNumber`, `isPortabilidad`, `skipReview`, `skipLocalOrderValidation`,
`categoryName`. Además `AngelPayPaymentScreen` (`AngelPayPaymentScreen.kt:68`) ni
siquiera declara parámetros para recibirlos.

Consecuencia:
1. **La tarjeta se cobra pero la venta queda mal registrada** — el ICCID y la
   portabilidad no llegan al `PaymentContext.AngelPayPayment`, así que el backend
   no liga la SIM ni crea la verificación pendiente.
2. **No se respeta `skipReview`** — el flujo AngelPay puede mostrar propina/reseña
   en una venta de SIM.
3. **No hay verificación foto/código antes de cobrar** — no existe el paso.

## 3. Alcance

**Dentro:**
- Venta de SIM en Nexgo con registro serializado correcto (ICCID + portabilidad
  adjuntos al cobro).
- Respetar `skipReview` (sin propina/reseña en venta de SIM).
- Verificación pre-pago (foto y/o código) antes de cobrar, controlada por los
  flags de venue `showVerificationScreen` / `requireVerificationPhoto` /
  `requireVerificationBarcode`.
- Subida de fotos proof-of-sale no-bloqueante después del cobro (reusa el
  `VerificationUploadManager` existente).
- Recibo con folio (`orderNumber`) y serial de la SIM.

**Fuera (YAGNI / otra tarea):**
- Reembolsos de ventas serializadas en AngelPay.
- Modo kiosco en AngelPay (hueco distinto ya identificado).
- Verificación genérica retail (no-SIM) más allá de lo que venga "gratis" al
  reusar `VerificationScreen` — el foco es SIM.
- Cambios de backend (ninguno requerido).

## 4. Arquitectura elegida

> **Actualización 2026-07-05 (decisión final del founder):** Se elige el enfoque de
> **pantalla de verificación SEPARADA** (Opción 1, best practice) para aislar la
> cámara/verificación del flujo de cobro real. La verificación NO se mete dentro de
> `AngelPayPaymentViewModel` — vive en su propia ruta/pantalla entre el escaneo de SIM
> y el cobro:
>
> ```
> SerializedSaleScreen (escanea) → [SerializedVerification: foto/código] → AngelPayPayment (comercio → cobro)
> ```
>
> - La `SerializedVerification` reusa `VerificationScreen` + `CameraPreviewScreen` +
>   `BarcodeScannerScreen` + `VerificationUploadManager` (todo ya existe).
> - Sube las fotos a Firebase (keyed por `orderNumber`, que ya existe del quick-sell) y
>   pasa las **URLs** + códigos a `AngelPayPayment` vía `savedStateHandle`.
> - `AngelPayPaymentScreen` ya acepta `verificationPhotos`/`verificationBarcodes` (Task
>   2.3) → `setSerializedSaleInfo` → se adjuntan al registro del cobro.
> - **El flujo de cobro real (tarjeta/efectivo/crypto normal) NO se toca con lógica de
>   verificación.** La verificación solo existe en la ruta de venta de SIM → un cobro
>   normal jamás la ve, sin importar `showVerificationScreen`.
> - El hueco `VERIFY_PRE_PAYMENT` en `navigateToStep` (`AngelPayPaymentViewModel.kt`)
>   se deja como está (placeholder que salta a merchant); no se llena.
>
> **Validado en N86 (2026-07-05):** el adjunto de ICCID se probó end-to-end por
> **efectivo Y tarjeta** (SDK real, contactless Visa aprobada): `SaleVerification`
> PENDING con `serialNumbers` correcto en ambos casos. `skipReview` confirmado
> (`FlowGate → SELECT_MERCHANT`). Falta solo poblar `photos` (esta pantalla de
> verificación). Ver "Hallazgos Phase 0" + validación en el plan.
>
> El resto del diseño (campos aditivos en `PaymentContext.AngelPayPayment`, mapeo en
> recorders, banderas apagadas por defecto, matriz de regresión) se mantiene.

**[Enfoque original — superado por la nota de arriba] Verificación como PASO DE
NAVEGACIÓN antes de la pantalla de cobro, NO embebida en el ViewModel de cobro.**

Flujo objetivo en Nexgo:

```
SerializedSaleScreen (escanea SIM, crea orden)
        │
        ▼  (si el venue exige verificación foto/código)
VerificationScreen  ← componente standalone ya existente
        │            captura fotos/códigos, sube no-bloqueante
        ▼
AngelPayPayment (cobra) ── adjunta serialNumbers + isPortabilidad al registro
        │                   respeta skipReview (sin propina/reseña)
        ▼
Success ── recibo con folio + serial
```

Por qué este enfoque y no duplicar las ~2,100 líneas de lógica de Blumon dentro
de `AngelPayPaymentViewModel`:

1. **Menos código y menos riesgo.** La verificación vive en una pantalla aparte
   que ya existe; no se re-arquitecta la máquina de estados de AngelPay.
2. **El cobro normal de AngelPay no se entera.** La verificación es un destino de
   navegación separado; tarjeta/efectivo/crypto normales nunca pasan por ahí.
3. **Reusa lo compartido** (`VerificationScreen`, `VerificationUploadManager`,
   `RecordPaymentUseCase`, `PaymentContext`) en vez de clonarlo.

### Cambios concretos

**A) Ruteo (`AppNavigation.kt`):**
- Cuando `SerializedSaleScreen` requiere verificación (venue con
  `showVerificationScreen`/`requireVerificationPhoto`/`requireVerificationBarcode`
  y es Nexgo), navegar primero a un destino de verificación pre-pago que, al
  confirmar, continúe a `AngelPayPayment` llevando fotos/códigos capturados +
  `serialNumber`/`isPortabilidad`/`skipReview` en `savedStateHandle`.
- El destino `AngelPayPayment` debe **leer** `serialNumber`, `isPortabilidad`,
  `skipReview` (y las fotos/códigos capturados) del `savedStateHandle` y pasarlos
  al composable.

**B) `AngelPayPaymentScreen.kt`:**
- Agregar parámetros **opcionales con default** : `serialNumber: String? = null`,
  `isPortabilidad: Boolean = false`, `skipReview: Boolean = false`,
  `verificationPhotos: List<String> = emptyList()`,
  `verificationBarcodes: List<String> = emptyList()`.
- Si `skipReview == true` → saltar los estados de rating/tip.

**C) `AngelPayPaymentViewModel.kt`:**
- Recibir esos datos y, al construir `PaymentContext.AngelPayPayment`, poblar los
  campos de proof-of-sale.
- Guardar `serialNumber`/`isPortabilidad`/fotos en el snapshot del pago para que
  sobrevivan reintentos (idempotencia intacta), y limpiarlos en el reset.

**D) `PaymentContext.AngelPayPayment` (`PaymentContext.kt:263`):**
- Agregar campos **aditivos con default** (hoy no los tiene, `FastPayment`/
  `OrderPayment` sí): `orderReference: String? = null`,
  `verificationPhotos: List<String> = emptyList()`,
  `verificationBarcodes: List<String> = emptyList()`,
  `isPortabilidad: Boolean = false`,
  `serialNumbers: List<String> = emptyList()`.
- Confirmar/ajustar `RecordPaymentUseCase` para que serialice estos campos en el
  caso `AngelPayPayment` (el backend ya los acepta desde PAX).

**E) Subida de fotos:** después del cobro exitoso, disparar la subida
no-bloqueante vía `VerificationUploadManager` con el `paymentId` del recibo —
mismo patrón que Blumon.

## 5. Radio de impacto / seguridad del flujo AngelPay (sección central)

El `AngelPayPaymentViewModel` es un flujo compartido (tarjeta, efectivo, crypto).
El diseño protege los flujos normales así:

| Cambio | ¿Afecta cobro normal (no-SIM)? | Por qué |
|---|---|---|
| Params nuevos en `AngelPayPaymentScreen` | No | Default `null`/`false`/vacío; un cobro normal nunca los llena → mismo código |
| `skipReview` | No | Solo una venta de SIM lo pone en `true`; en cobro normal sigue `false` |
| Campos proof-of-sale en `PaymentContext.AngelPayPayment` | No | Aditivos con default; ningún caller existente cambia; vacíos en cobro normal |
| `VerificationScreen` como paso previo | No | Es un destino de navegación aparte; cobro normal no pasa por él |
| `PaymentViewModel` de Blumon | No | **No se toca en absoluto** |

Regla: todo camino nuevo va detrás de una bandera apagada por defecto (mismo
patrón usado hoy en los fixes de `showCryptoOption`/`showReceiptScreen`). El cobro
normal de AngelPay recorre un código idéntico al actual.

## 6. Validaciones abiertas (resolver durante implementación)

1. **`awaitPaxPaymentReady`** se llama en el `onNavigateToPayment` de la venta
   serializada (`AppNavigation.kt:~2151`) incluso en el path Nexgo. Confirmar que
   ese gate no bloquee/espere indefinidamente en Nexgo (parece pensado para PAX).
2. **Cámara y escáner de código de barras en hardware Nexgo N86** — validar en
   dispositivo físico que la captura funciona (la lib debe ser agnóstica, pero no
   se ha probado en Nexgo).
3. **Visibilidad del módulo `SERIALIZED_INVENTORY`** en terminales Nexgo —
   confirmar que el tile "Vender"/"Mis SIMs" aparece igual (observación de módulos
   es código compartido; probablemente sí, pero verificar).
4. **`skipReview` en AngelPay** — revisar si ya hay manejo parcial reusable
   (`AppNavigation.kt` lee `skipReview` en otros paths).
5. **`RecordPaymentUseCase`** — confirmar el mapeo por tipo de contexto: que el
   caso `AngelPayPayment` serialice `serialNumbers`/`isPortabilidad` al DTO del
   backend.

## 7. Plan de pruebas

**Regresión obligatoria de AngelPay (que NADA se rompió):**
1. Cobro normal con tarjeta (Nexgo)
2. Cobro en efectivo
3. Cobro con crypto
4. Reembolso (admin)

**Flujos nuevos:**
5. Venta de SIM SIN verificación exigida → cobra, registra ICCID+portabilidad, sin propina
6. Venta de SIM CON foto exigida → captura foto → cobra → foto sube no-bloqueante
7. Venta de SIM CON código exigido → escanea → cobra → registro correcto
8. Portabilidad (2 fotos) vs alta normal (1 foto)
9. Recibo muestra folio + serial

**Unitarias:** extender `AngelPayPaymentViewModelTest` con (a) casos de venta de
SIM y (b) aserciones de que el cobro normal no cambia cuando los params vienen en
default.

Comandos: `./gradlew testSandboxDebugUnitTest` (suite completa, 0 fallos) +
`./gradlew compileNexgoDebugKotlin` (la variante que empaqueta el AAR AngelPay) +
`lint --continue`. Verificación final en N86 físico.

## 8. Tier / gating

`SERIALIZED_INVENTORY` / `INVENTORY_TRACKING` es PREMIUM hoy. Esto es la **misma**
función sobre hardware nuevo — no cambia el tier. La TPV no enforce tiers todavía
(solo el dashboard), así que no hay código de gating nuevo; si se agrega un gate
client-side después, debe espejar el código de feature por nombre exacto.

## 9. Sincronización cross-repo (checklist CLAUDE.md)

- **Backend:** sin cambios (ya acepta proof-of-sale desde PAX).
- **MCP (`avoqado-server/scripts/mcp/`):** sin cambios — no hay modelo/endpoint
  nuevo; el registro de venta serializada ya existe.
- **Presentación de ventas:** revisar si "venta de SIM / inventario serializado"
  ya figura como capacidad; habilitarla en Nexgo no agrega una capacidad
  cliente-visible nueva (misma función, más hardware). Confirmar con el founder si
  amerita nota.
- **Deploy:** TPV tarda 3-5 días (firma PAX/Nexgo). Nexgo se distribuye por
  update in-place (misma firma).

## 10. Archivos afectados (estimado)

Nuevos/tocados (todos en el path AngelPay + ruteo):
- `core/presentation/navigation/AppNavigation.kt` — ruteo verificación→cobro, leer params
- `features/payment/presentation/angelpay/AngelPayPaymentScreen.kt` — params opcionales, honrar skipReview
- `features/payment/presentation/angelpay/AngelPayPaymentViewModel.kt` — poblar proof-of-sale, snapshot/reset
- `features/payment/domain/model/PaymentContext.kt` — campos aditivos en `AngelPayPayment`
- `features/payment/domain/usecase/RecordPaymentUseCase.kt` — mapear campos (si aplica)
- `NavRoute.kt` — posible ruta de verificación pre-pago Nexgo (o reuso)
- `AngelPayPaymentViewModelTest.kt` — tests nuevos + regresión

Reusados sin cambios: `VerificationScreen`, `VerificationUploadManager`,
`SerializedSaleScreen`, `MySalesScreen`, `SerializedInventoryScreen`.

**Estimado:** trabajo mediano (no reconstrucción). El grueso reusa componentes
existentes; el código nuevo son ~cientos de líneas de wiring + un paso de nav +
campos aditivos, no las ~2,100 líneas de lógica de Blumon.
