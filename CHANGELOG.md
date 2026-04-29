# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Added**

### **Changed**

### **Fixed**

---

## [1.13.1] - 2026-04-28

### **Fixed**

- **Cobro vía socket cancelado contaminaba el siguiente cobro manual**: Cuando una request `terminal:payment_request` desde dashboard/iOS llegaba al TPV y luego se cancelaba (vía `terminal:payment_cancel`), el handler de cancel en `AppNavigation.kt:298-310` solo navegaba de vuelta a Home — **pero no limpiaba** los args `paymentSource`, `socketRequestId`, `skipReview`, `externalTipCents`, `initialAmount`, etc. del `savedStateHandle` de Home. Resultado: si el cajero tocaba "Cobro Rápido" inmediatamente después del cancel, el `PaymentScreen` leía esos args viejos y procesaba el cobro como si fuera socket — saltándose la pantalla de propina (`skipReview=true`) y aplicando el monto/propina del request cancelado. Reproducido en producción 28-abr-2026: socket pidió $50+$5, iOS canceló a los 6s, cajero hizo Cobro Rápido manual de $5, pero el TPV cobró $5+$5 (100% propina) usando los valores residuales. Fix: el handler de cancel ahora llama a `clearPaymentArgs(homeHandle)` antes de navegar; además `clearPaymentArgs` ahora también remueve `initialAmount` y `skipReview` que faltaban.

---

## [1.13.0] - 2026-04-28

### **Added**

- **Remote receipt printing desde dashboard / iOS**: El TPV ahora puede imprimir recibos a petición vía Socket.IO. Backend (dashboard, iOS app) emite `terminal:print_receipt_request` con el receipt serializado; el TPV lo arma con `PrinterManager` y responde con `terminal:print_receipt_result` (`status: success | error`). Habilita el caso de uso "el cliente quiere otra copia del recibo desde la app móvil" sin necesidad de tocar el terminal.
  - `SocketEvent.TerminalReceiptPrintRequest`: nueva data class
  - `SocketManager.onTerminalReceiptPrintRequest` + `emitTerminalReceiptPrintResult`
  - `HomeViewModel.printRemoteReceipt`: parsea el JSON del receipt (items, modifiers, totales en centavos) y delega a `PrinterManager`. Inyectada nueva dependencia `PrinterManager` en `HomeViewModel`

### **Changed**

- **Terminal payment requests vía Socket: manejo de cancelaciones y errores no-reintentables**: Cuando un cobro originado por iOS (vía BLE bridge) se cancela en el TPV, ahora `PaymentViewModel` (sandbox + production) emite `terminal:payment_result` con `status: "cancelled"` para que el iOS sepa que pasó. Errores reintentables (que muestran botón "Reintentar") ya no emiten resultado FAILED inmediatamente — se mantiene la request pendiente hasta que el usuario decida. Antes el iOS recibía un FAILED prematuro y descartaba la request aunque el cajero todavía pudiera reintentar.

---

## [1.12.1] - 2026-04-28

### **Added**

- **Crashlytics observability — context keys en cada reporte**: Nuevo helper `core/observability/CrashlyticsContext.kt` que centraliza la inyección de custom keys a Firebase Crashlytics. Tres niveles:
  - **`setAppContext`** (en `AvoqadoTPVApplication.initializeTimber`): tag estático por terminal — `app_build_variant`, `app_environment`, `app_terminal_serial`, `app_version_name`, `app_version_code`. Aplica desde el primer log del proceso
  - **`setSessionContext` / `clearSessionContext`** (en `AuthRepository.loginWithPin` / `logout`): `session_venue_id`, `session_staff_id`, `session_staff_role` + Firebase `userId`. Operations puede filtrar por venue o staff afectado
  - **`setPaymentContext`** (en `PaymentViewModel.startPayment` / `processCashPayment` / `processCryptoPayment` sandbox+production y `AngelPayPaymentViewModel` 3 paths): `payment_processor` (BLUMON / ANGELPAY / B4BIT), `payment_method` (CARD / CARD_MSI_X / CASH / CRYPTO), `payment_merchant_id`, `payment_amount`, `payment_order_id`, `payment_attempt_id`. Cada reporte de Crashlytics durante un cobro incluye el contexto exacto del pago en curso

### **Fixed**

- **Logs sin throwable perdían stack trace en Crashlytics**: 5 sitios en `TimeclockViewModel` (`PIN verification failed`, `Clock in failed`, `Clock out failed`, `Start break failed`, `End break failed`) usaban `Timber.e("...: ${error.message}")` sin pasar el `throwable` como primer argumento. El bridge `CrashReportingTree` reportaba el mensaje pero sin stack trace, dificultando el triage. Convertido a `Timber.e(error, "...")` para que Crashlytics tenga el stack completo. El resto del codebase ya usaba el patrón correcto.

- **`SocketManager.isConnected()` renombrado a `isCurrentlyConnected()`**: La clase tenía a la vez una property `val isConnected: SharedFlow<Boolean>` (uso reactivo, `collect`) y una function `fun isConnected(): Boolean` (snapshot síncrono). Aunque Kotlin permite ambos, en bytecode JVM esto crea un getter `getIsConnected()` y un método `isConnected()` con el mismo nombre que confundían a MockK durante tests — 11 tests de `HomeViewModelTest` fallaban. La function se renombró a `isCurrentlyConnected()` para desambiguar; la property `isConnected` queda como única referencia para el flow reactivo. Callsites actualizados en `HomeViewModel.kt:607` y `HealthMonitor.kt:195`.

---

## [1.12.0] - 2026-04-28

### **Added**

- **Blumon TPV — meses sin intereses**: La pantalla de pago con tarjeta en variants `sandboxDebug` y `productionDebug` ahora muestra promociones MSI cuando el terminal config incluye `providerConfig.promotions.msi`. El usuario puede elegir pago directo (`msi = null`) o una opción MSI; la opción seleccionada se envía a `SaleIccParams`/`SaleCtlsParams`. No aplica para AngelPay/Nexgo.
- **[Nexgo] Pago crypto B4Bit habilitado**: Implementado el flujo completo de pagos crypto en `AngelPayPaymentViewModel` (Nexgo N86). Antes el botón "Cripto" estaba hardcoded como `showCryptoOption = false` porque el ViewModel no soportaba crypto. Cambios:
  - `AngelPayPaymentState.kt`: agregados estados `GeneratingCryptoQR` (loading mientras backend crea la orden B4Bit) y `AwaitingCryptoPayment` (mostrando QR, esperando webhook vía Socket.IO)
  - `AngelPayPaymentViewModel.kt`: agregadas dependencias `ApiService` + `SocketManager`, field `currentCryptoRequestId`, suscripción a `SocketEvent.CryptoPaymentConfirmed`/`Failed`, y funciones `processCryptoPayment`, `cancelCryptoPayment`, `handleCryptoTimeout`, `handleCryptoPaymentConfirmed`, `handleCryptoPaymentFailed` — porteadas del `PaymentViewModel` (PAX/Blumon) ajustando los tipos de estado a `AngelPayPaymentState`
  - `AngelPayPaymentScreen.kt`: pasa `showCryptoOption = true` y `onStartCryptoPayment = { viewModel.processCryptoPayment(...) }` al `MerchantSelectionContent`. Renderiza los nuevos states reusando `CryptoPaymentLoadingScreen` y `CryptoPaymentQrScreen` que viven en `main/` (compartidos con PAX). Crypto es hardware-agnostic — solo necesita backend + QR — así que funciona idéntico en Nexgo

### **Changed**

- **Acciones de orden gateadas por permisos del backend**: Los botones "Descuentos", "Cortesía" y "Void Items" del tab Acciones ahora se ven disabled cuando el staff no tiene el permiso correspondiente, con leyenda inline "Sin permisos para descuentos / cortesía / anular". Antes se mostraban activos y al presionar el backend rechazaba con 403, dejando al cajero sin entender por qué. `MenuViewModel` ahora inyecta `PermissionsRepository` y expone `canApplyDiscount`/`canCompItems`/`canVoidItems` (`StateFlow<Boolean>`) leyendo `orders:discount`, `orders:comp`, `orders:void` — los mismos strings que `tpv.routes.ts` valida con `checkPermission(...)`. El `ShortcutAction` recibió un nuevo campo `disabledReason: String?` que el `ShortcutActionCard` renderiza debajo del título cuando aplica.

### **Fixed**

- **Descuento manual fallaba en silencio**: `MenuViewModel.applyManualDiscount` solo loggeaba con `Timber.e(...)` cuando el backend rechazaba (403/400/etc). El cajero veía el dialog cerrarse sin feedback, sin entender que la operación había fallado. Ahora emite `MenuUiEvent.ShowSnackbar` tanto en éxito ("Descuento aplicado") como en error ("Error al aplicar descuento: ${error.message}"). El mismo patrón ya existía en `applyDiscount` legacy — solo faltaba propagarse al método nuevo.

### **Tooling**

- **Slash command `/check-permissions-sync`**: Auditoría reusable que escanea los 3 repos (`avoqado-server`, `avoqado-tpv`, `avoqado-web-dashboard`) y reporta tres categorías:
  1. 🔴 Permisos que el backend exige pero ninguna UI gateaa (causa 403 silenciosos)
  2. 🟡 Permisos que la UI chequea pero el backend no enforce (over-gating, posible refactor stale)
  3. 🟢 Mismatches de naming entre repos (ej: `orders:discount` vs `tpv-orders:discount`)
  Bonus: detecta `onFailure { Timber.e(...) }` sin emisión a UI events — los "errores invisibles" que motivaron este audit. Vive en `.claude/commands/check-permissions-sync.md`. Ejecutar antes de cada release y después de refactors que toquen `permissions.ts`.

- **Cripto — mínimo $20 MXN con feedback visual**: En `MerchantSelectionContent`, el botón "Cripto" ahora se ve deshabilitado (mismo look que merchant switching loading) cuando el monto total es menor a $20 MXN. El botón **sigue siendo tappable** — al tocarlo se abre un `AlertDialog` explicando que el mínimo para B4Bit son $20 MXN. Si el monto es ≥ $20, el botón se comporta normal y lanza el flujo de pago crypto. Mantiene el server validándolo también (`initiateCryptoPayment` en `avoqado-server`) como red de seguridad

### **Fixed**

- **[Nexgo] Reintentar pago no funcionaba con un solo merchant**: Al fallar un cobro con AngelPay, el botón "Reintentar" regresaba al usuario a la pantalla de selección de merchant pero con el botón de Tarjeta deshabilitado. Root cause: `AngelPayPaymentViewModel.resetPayment()` limpiaba `_currentMerchant.value = null`, y el auto-select en `init{}` solo dispara cuando la lista de merchants emite un valor nuevo (no pasa en retry). En tiendas con un solo merchant, el selector está oculto (`merchants.size <= 1`), así que el usuario quedaba trabado sin forma de re-seleccionar. Fix: `resetPayment()` ya no limpia `_currentMerchant` — la selección del merchant es ortogonal al intento de pago, no debería tumbarse al reintentar

---

## [1.11.1] - 2026-04-23

### **Changed**

- **Mis SIMs — ICCID siempre visible**: El número de SIM ahora se muestra completo en todas las tarjetas de `MisSimsScreen` (PENDING y HELD), sin necesidad de expandir. Eliminado el toggle "Ver ICCID completo / Ocultar" que antes ocultaba el número a solo 4 caracteres
- **Alta/Venta SIM — filtro de caracteres inválidos**: El campo de escaneo de código SIM ahora filtra en tiempo real cualquier carácter que no sea alfanumérico (letras y números). Comas, espacios, guiones y otros símbolos son descartados al momento de escribir o escanear, tanto en Alta de Productos (`SerializedInventoryScreen`) como en Vender SIM (`SerializedSaleScreen`). El ViewModel ya rechazaba códigos menores a 20 caracteres; esta fix cierra el frente de la entrada sucia antes de que llegue a validación
- **Buscar Cliente — comportamiento del teclado**: Eliminado auto-focus al abrir el modal de búsqueda de clientes. Agregar toque en cualquier área fuera del campo de texto cierra el teclado sin cerrar el modal

### **Fixed**

- **Dev build URL rota (`https://https://...`)**: Typo en `app/build.gradle.kts` duplicaba el prefijo `https://` en `API_BASE_URL_DEV` y `SOCKET_URL_DEV`, dejando la URL del ngrok malformada → Retrofit no podía parsearla y los requests nunca salían del dispositivo (ngrok no veía tráfico). Fix: quitar el `https://` extra de ambos campos. También se limpió el mismo typo en comentarios docstring de `ApiService.kt`, `PaymentApiService.kt`, `CustomerApiService.kt`, `DiscountApiService.kt`, `TableApiService.kt`, `FloorElementApiService.kt` y `OrderApiService.kt` (no afectaban runtime, sólo docs). Afecta sólo builds sandbox — production usa `api.avoqado.io` y no tenía el bug.

- **Pago cripto: propina $0.00 en pantalla de éxito**: Al completar un pago con criptomoneda, la pantalla de éxito del TPV mostraba "Propina $0.00" y "Total pagado = subtotal" aunque el usuario sí hubiera dejado propina. El dashboard registraba el total correcto (subtotal + tip) porque el recorder va por otra vía. Root cause: `handleCryptoPaymentConfirmed` en `PaymentViewModel.kt` (sandbox + production) construía el `PaymentReceipt` con `tipAmount = BigDecimal.ZERO` hardcoded (comentario legacy: "Crypto payments don't have separate tip in B4Bit") y tomaba `amount` del webhook de B4Bit — el composable de éxito lee del receipt, no del state. Fix: el receipt ahora se construye con `currentState.subtotal` y `currentState.tipAmount`, respetando el contrato `PaymentReceipt` (`amount = subtotal SIN propina`, `tipAmount = propina`). Sincronizado entre variants sandbox y production.

---

## [1.11.0] - 2026-04-16

### **Added**

- **Mis SIMs (PlayTelecom chain-of-custody)**: Nueva pantalla `MisSimsScreen` para que el Promotor vea los SIMs asignados por su Supervisor, los acepte (individualmente o con "Aceptar todos") o los rechace. Incluye búsqueda por últimos dígitos del ICCID, filtros (Todos / Pendientes / Míos / Vendidos hoy), badges de estado (`PROMOTER_PENDING` ámbar, `PROMOTER_HELD` verde, `SOLD` violeta) y confirmación obligatoria antes de aceptar masivo. Se envía `Idempotency-Key` en cada aceptación para evitar dobles clics. Nav route `NavRoute.MisSims` + gating backend con permisos `tpv-sim-custody:accept` / `tpv-sim-custody:reject`. DTOs, repository y ViewModel en `features/sim_custody/`. Plan §3.1–§3.3.
- **Tile "Mis SIMs" en pantalla principal**: Botón `SimCard` agregado al grid de `WelcomeScreen` para Promotores (gated por `hasInventorySellPermission`). Navegación cableada desde `AppNavigation.kt` hacia `NavRoute.MisSims`.
- **Diálogo "SIM no aceptado" en flujo de venta**: Cuando el backend responde `SIM_NOT_ACCEPTED` en `/tpv/serialized-inventory/sell` (modo ENFORCE), `SerializedSaleViewModel` detecta el código y `SerializedSaleScreen` muestra un `AlertDialog` con CTA "Ir a Mis SIMs" que hace deep-link a `MisSimsScreen` para que el Promotor acepte el SIM antes de vender. Nuevo flag `simNotAcceptedError` en `SerializedSaleUiState`.

### **Changed**

- **Rediseño UI "Mis SIMs"**: Se arregló el layout roto en PAX A910S donde (1) el placeholder del buscador se partía en 2 líneas y (2) el chip "Vendidos hoy" se truncaba letra-por-letra porque el Row no scrolleaba. Ahora: placeholder compacto ("Buscar ICCID…"), filtros en `LazyRow` horizontal con `FilterPill` custom (rounded-full, badge numérico para pendientes, acento ámbar/violeta según estado), banner "Pendientes de aceptar" con borde + CTA de mayor contraste, cards de SIM con borde sutil y tipografía jerarquizada, empty state con icono circular en superficie variant. Se eliminó el `Modifier.height8()` y el custom `Modifier.height(Dp)` que duplicaba el built-in.
- **Validación de SIM: 20 dígitos mínimo**: Cambiado el mínimo de caracteres del código SIM de 15 a 20 dígitos en Vender SIM y Alta SIM. El ID SIM completo de Bait/Play Telecom contiene 20 dígitos. Texto actualizado a "El código debe tener al menos 20 dígitos".
- **Skip de voucher ya no salta selfie de checkout**: `skipCheckoutWithReason` ahora solo omite el voucher y continúa a la selfie de salida (que es obligatoria), en lugar de saltar todo y cerrar el registro directamente. Razón del skip se preserva en `pendingSkipReason` y se pasa a `performClockOut` cuando la cola se vacía.

### **Backend (cross-repo)**

- **`my-sales` solo incluye ventas PAID**: El endpoint `/tpv/v1/serialized-inventory/my-sales` ahora filtra solo órdenes con `paymentStatus = PAID` (antes incluía PENDING) y excluye `status = CANCELLED`. Fix para el reporte de SIM duplicado en Jesus Maria Qro (13/04): una venta PENDING por pago fallido se mostraba como venta válida en "Mis Ventas", pidiendo depósito por $300 cuando solo se cobró $200.

---

## [1.10.13] - 2026-04-13

### **Added**

- **Preflight connectivity check (Phase 2)**: `startPayment()` verifica `connectionStateManager.isFullyConnected()` antes de iniciar pago con tarjeta. Si no hay conexión, muestra error con opción de cobrar en efectivo.
- **Cash fallback desde error de conectividad**: Botón "Cobrar en Efectivo" en `PaymentErrorContent` cuando `showCashFallback = true`. Llama `processCashPaymentFromError()` que redirige al flujo de efectivo.
- **Offline queue para pagos en efectivo**: Cuando `recordFastPayment`/`recordOrderPayment` falla para pagos en efectivo, se encola en `paymentQueueRepository` y muestra `PaymentState.Success` inmediatamente. `PaymentSyncWorker` sincroniza después.
- **Payment-specific network client (Phase 3)**: `@PaymentClient` qualifier con OkHttpClient de timeouts agresivos (5s connect, 10s read/write) para `PaymentApiService`. No afecta otros API calls ni Blumon SDK.
- **Cellular failover rollout flags (Phase 0)**: Flags en `TpvSettings` (`cellularFailoverMode`, thresholds, cooldowns) con default `OFF`. Wiring en DTO + SecureStorage.
- **`QueuedPayment.isCashQueuedPayment()`**: Detecta pagos en efectivo encolados y normaliza `merchantAccountId` a null para retries correctos.

### **Changed**

- **Check-out selfie obligatoria**: Eliminada la opción "Continuar sin foto" para selfies de check-out. La foto ahora es obligatoria para verificar presencia en el venue. Solo el voucher de depósito bancario mantiene la opción de omitir.
- **`PaymentState.Error`**: Nuevo campo `showCashFallback: Boolean = false` para indicar cuándo mostrar opción de efectivo.

---

## [1.10.12] - 2026-04-13

### **Fixed**

- **WiFi Failover Spike visible en producción**: Removida la condición `BuildConfig.DEBUG` que ocultaba la herramienta de WiFi toggle en SuperAdmin en builds de producción. Validado exitosamente en PAX A910S producción.

---

## [1.10.11] - 2026-04-10

### **Added**

- **Phase 1 WiFi Failover Spike (SuperAdmin)**: Herramienta de diagnóstico en SuperAdmin para probar toggle WiFi programático en PAX A910S usando Neptune DAL API (`EChannelType.WIFI`). Valida que la terminal puede alternar entre WiFi y celular automáticamente. Solo visible en builds DEBUG.
- **`CHANGE_WIFI_STATE` permission**: Requerido para control programático de WiFi en la funcionalidad de failover celular.
- **Cellular Failover Plan doc**: Documento de arquitectura `docs/CELLULAR_FAILOVER_PLAN.md` con plan de 3 fases para resiliencia de red en pagos.

### **Changed**

- **Check-in/check-out selfie obligatoria**: Eliminada la opción "Continuar sin foto" para selfies de check-in y check-out. La foto ahora es obligatoria para verificar presencia en el venue. Solo el voucher de depósito bancario mantiene la opción de omitir foto (puede no haber venta).

---

## [1.10.10] - 2026-04-09

### **Fixed**

- **Payment idempotency (Stripe/Square/Toast pattern)**: Prevent duplicate payment recording from concurrent TPV retries. Client generates UUID per payment attempt, backend deduplicates atomically via unique constraint. Fixes: Testarudo Cafe 5x duplicate charge incident (2026-04-08).
- **Crash on startup with poor internet (Doña Simona)**: Defensive `AppManager.init()` call before Hilt resolves `UpdateRequestManager`, preventing `lateinit property dal has not been initialized` fatal crash when SDK init is slow due to poor connectivity. Follows v1.7.9 proven pattern.
- **Blumon init retry with backoff**: Improved SDK initialization with 4 retry attempts and exponential backoff for network failures during startup.
- **WelcomeScreen clickable deprecation**: Fixed `MutableInteractionSource` warning on SDK init overlay.

### **Added**

- **Crashlytics network diagnostics**: Automatic custom keys (`network_internet`, `network_server`, `network_latency_ms`, `network_slow`, `blumon_sdk_status`) updated on every connection state change. Eliminates need for WhatsApp screenshots to diagnose connectivity issues.

### **Changed**

- **Mis Ventas (My Sales) screen**: New screen showing a promoter's serialized item sales history grouped by day with monthly totals. Includes month navigation, summary card with total count and amount, daily groupings, and gift item indicators. Accessible from simplified mode home screen for users with `serialized-inventory:sell` permission.
- **Restrict "+ categoría" button by permission**: The "+ categoría" button in Alta de SIM and Vender screens was visible to all roles. Now only users with `inventory:org-manage` permission (ADMIN/OWNER) can see it. CASHIER/Promotor roles can still register and sell but cannot create new categories.

---

## [1.10.1] - 2026-03-23

### **Added**

- **Serialized Sale $0 gift**: Allow selling SIMs at $0 for giveaways. Button shows "Regalar (Gratis)" with confirmation dialog. Full payment flow (efectivo → success → fotos → QR)

### **Changed**

- **Serialized Sale price validation**: `price <= 0` → `price < 0` (accepts $0)
- **Serialized Sale button enabled**: Empty price field now enables button (treated as $0)

---

## [1.10.0] - 2026-03-23

### **Added**

- **WhatsApp receipt sending (Blumon + AngelPay)**: Send payment receipts via WhatsApp Business API. New backend endpoint `POST /tpv/venues/{venueId}/payments/{paymentId}/send-whatsapp` using `receipt_link` template. Country code picker with MX/US/CO/AR/CL/PE, 10-digit phone validation, WhatsApp green UI. 3-way button row (Print | Email | WhatsApp) replaces 2-way row on success screens for both Blumon and AngelPay flows
- **WhatsAppReceiptDialog composable**: Reusable dialog with customer search (auto-fill phone), country dial code dropdown, numeric phone input, and digit count validation per country
- **AngelPay email receipt support**: Added `PaymentApiService` dependency to `AngelPayPaymentViewModel` with `sendReceiptByEmail()` and `sendReceiptByWhatsApp()` methods, matching Blumon behavior

### **Fixed**

- **Blumon SDK not re-initializing after offline startup**: When app started without internet, SDK initialization failed and was never retried when connectivity restored — causing `RSADataEntity.getTk()` NPE on first payment. Now `ConnectionViewModel` calls `ensureInitialized()` when network restores and SDK is not initialized
- **AngelPay app-to-app payment integration**: New isolated payment flow for Nexgo N86 terminals via Android Intent bridge to AngelPay app. Completely separate from Blumon SDK — own ViewModel (`AngelPayPaymentViewModel`), Screen (`AngelPayPaymentScreen`), state machine (`AngelPayPaymentState`), and navigation route (`NavRoute.AngelPayPayment`)
- **ProcessorType enum**: `BLUMON` and `ANGELPAY` processor types for future multi-processor support. Added as default `BLUMON` field on `MerchantAccount` (backward-compatible)
- **AngelPayPayment sealed subclass**: New `PaymentContext.AngelPayPayment` for recording AngelPay transactions to backend. Routes to `FastPaymentRecorder` or `OrderPaymentRecorder` based on presence of `orderId`
- **AngelPay Intent builder**: `AngelPayIntentBuilder` constructs DO_SALE, SEE_HISTORY, and REPORTS intents per AngelPay API spec. Amounts in centavos, JSON-encoded extras, QA/prod package auto-selection via `BuildConfig.BLUMON_ENV`
- **AngelPay result parser (STUB)**: `AngelPayResultParser` handles `onActivityResult` — currently returns stub values. Blocked on AngelPay docs for response format
- **AngelPay credentials in SecureStorage**: `saveAngelPayCredentials()`, `getAngelPayCredentials()`, `clearAngelPayCredentials()` using existing EncryptedSharedPreferences
- **Device-based payment routing**: `isNexgoDevice()` helper detects Nexgo N86/N5 terminals via `Build.MODEL`. Payment navigation automatically routes to AngelPay screen on Nexgo, Blumon screen on PAX/other
- **AngelPay full pre-payment flow (match Blumon UX)**: Complete rewrite of AngelPay payment flow with Rating → Tip → Merchant Selection → Card/Cash → Success with QR receipt. Reuses existing composables (`ReviewScreen`, `TipScreen`, `MerchantSelectionContent`, `PaymentApprovedScreen`) and `PaymentFlowGate` for screen gating
- **AngelPay cash payment support**: Cash payments via AngelPay flow — records with `CardDetails.CASH`, `merchantAccountId=null`, `authorizationCode="EFECTIVO"` to backend
- **AngelPaySuccessContent composable**: New simplified success screen with QR receipt code, amount/tip breakdown, cash indicator, print button, and Home/Nuevo Cobro navigation buttons (matching Blumon success layout)
- **AngelPay receipt printing**: `PrinterManager` integration for printing receipts from AngelPay success screen

### **Fixed**

- **Serialized Inventory false "Ya registrado"**: Network errors during barcode validation were incorrectly treated as `InventoryScanResult.Duplicate`, showing "Ya registrado" when the real issue was a connection error. Now shows "Error de conexión, reintenta" and uses new `InventoryScanResult.Error` type
- **Serialized Inventory race condition**: Rapid barcode scanning could lose items from batch due to stale `currentList` capture outside coroutine. Now reads latest state inside `_uiState.update {}` block and guards against concurrent validation of the same serial number
- **Camera scanner permanent block on re-scan**: `lastScannedBarcode` permanently prevented re-scanning the same barcode (e.g., after network error). Replaced with 2-second time-based debounce — same barcode can be re-scanned after cooldown
- **ZXing false positives from unused formats**: Removed 7 high-false-positive barcode formats (CODE_39, CODABAR, ITF, CODE_93, DATA_MATRIX, PDF_417, AZTEC) and `ALSO_INVERTED` hint. Kept EAN-13, EAN-8, UPC-A, UPC-E, CODE_128, QR_CODE
- **Silent scan drop on double-tap**: `_validatingSerials` guard now returns `AlreadyScanned` feedback instead of silently discarding the scan
- **Register while validating**: Added `isValidating` state to prevent tapping "Registrar" while barcode validations are still in-flight

### **Changed**

- **Serialized Sale allows $0 price**: SIM sales at $0 now allowed (for giveaways/gifts). Validation changed from `price <= 0` to `price < 0`. Backend already accepted $0
- **RecordPaymentUseCase**: Added `AngelPayPayment` branch — routes to `FastPaymentRecorder` (no orderId) or `OrderPaymentRecorder` (with orderId)
- **FastPaymentRecorder**: Accepts both `FastPayment` and `AngelPayPayment` contexts. New `buildAngelPayFastPaymentRequest()` method
- **OrderPaymentRecorder**: Accepts both `OrderPayment` and `AngelPayPayment` (with orderId) contexts. New `buildAngelPayOrderPaymentRequest()` method
- **AppNavigation payment routing**: Fast payment, order payment, BLE payment, and serialized sale navigation now use `getPaymentRoute()` to auto-select Blumon or AngelPay based on device. BLE cancel and in-progress checks also cover AngelPay route
- **PaymentViewModel (sandbox + production)**: Added `AngelPayPayment` branch to exhaustive `when` on `PaymentContext` for `blumonOperationNumber` trace logging
- **AngelPayPaymentState**: Rewritten sealed class with pre-payment states (`CollectingRating`, `CollectingTip`, `SelectingMerchant`, `ProcessingCash`) mirroring Blumon flow. Removed `ReadyToLaunch` state
- **AngelPayPaymentViewModel**: Full rewrite with `PaymentFlowGate` integration, `TpvSettingsRepository` for screen gating, merchant loading/selection, cash payment flow, `goBackOneStep()` navigation, and `PrinterManager` receipt printing. Removed `startPayment(amount, tip)` in favor of `initPayment(amount)` (tip collected within flow)
- **AngelPayPaymentScreen**: Rewritten with `when(state)` routing to reusable composables (`ReviewScreen`, `TipScreen`, `MerchantSelectionContent`). Removed Scaffold/TopAppBar — full-screen states. Added `PaymentApprovedScreen` confetti animation before success content

---

## [1.9.0] - 2026-03-11

### **Added**

- **Non-blocking proof-of-sale verification for SERIALIZED_INVENTORY**: Staff can now immediately start new sales after payment — "Nueva Venta" button is always enabled. Payments with missing proof-of-sale photos appear in a new "Pendientes Verificación" card on WelcomeScreen. Staff-scoped: only the staff who processed the sale sees their pending verifications
- **Pending verifications screen**: New `PendingVerificationsScreen` with expandable cards showing date, ICCID(s), amount, and photo status (0/1 or 0/2). Camera integration for uploading missing photos. Pull-to-refresh support. Photos upload to Firebase Storage then notify backend
- **Backend PENDING verification record at payment time**: `createPendingSaleVerification()` creates a SaleVerification with status PENDING immediately after SERIALIZED_INVENTORY payment. Stores `isPortabilidad` and `serialNumbers` fields for display in TPV pending screen
- **Backend pending verifications endpoint**: `GET /tpv/verification/pending` returns staff-scoped pending verifications with payment details (amount, orderNumber, date, serialNumbers, photo status)
- **Backend auto-completion on photo upload**: Modified `createOrUpdateProofOfSale()` to accept optional `verificationId`, append photos to existing PENDING record, and auto-transition to COMPLETED when required photo count is met (1 for non-portabilidad, 2 for portabilidad)

### **Changed**

- **Renamed "Registro de línea" → "Vinculación"**: Label renamed in PaymentScreen success (proof-of-sale section) and PendingVerificationsScreen photo slots for consistency with business terminology
- **Hide customer search in email receipt dialog for SERIALIZED_INVENTORY**: `EmailReceiptDialog` now accepts `showCustomerSearch` param; when flow origin is SERIALIZED, the "Buscar cliente" search field and customer list are hidden — only manual email input is shown
- **Photo label banner in camera**: `CameraPreviewScreen` now accepts optional `photoLabel` param. When capturing proof-of-sale photos, a colored banner shows "1. Vinculación" or "2. Portabilidad" at the top of the camera screen so staff knows which photo they're taking. Applied to both PaymentScreen (success state) and PendingVerificationsScreen cameras
- **CASHIER/Promotor role can now register inventory and create categories**: Added `serialized-inventory:create` permission to CASHIER default permissions in backend, enabling promotors to access "Alta de Productos" and create categories in the "Vender" screen
- **FastPaymentRequest includes serialized inventory metadata**: Added optional `isPortabilidad` and `serialNumbers` fields to `FastPaymentRequest` and `PaymentContext.FastPayment`, passed from PaymentViewModel scanned barcode data
- **HomeViewModel tracks pending verification count**: New `pendingVerificationsCount` StateFlow with deferred fetch (3s delay). Refreshes on dashboard refresh and lifecycle resume
- **SaleVerification schema migration**: Added `isPortabilidad` (Boolean, default false) and `serialNumbers` (String[]) fields to Prisma SaleVerification model

### **Fixed**

- **No back button in PAX camera for PendingVerificationsScreen**: Replaced system camera intent (`TakePicture`) with in-app CameraX `CameraPreviewScreen` which has a visible close button (X). PAX system camera hides navigation bar making it impossible to cancel without taking a photo. Also eliminates OOM kill issues since CameraX runs in-process (no Activity transition)
- **Camera double-tap freeze in PendingVerificationsScreen**: Photo slots are disabled while camera is active via `showCamera` state guard
- **Photo replace treated as second photo**: When retaking a photo that already exists (e.g., "Vinculacion" already uploaded), the new photo now **replaces** the existing one instead of appending as a second photo. Added `replaceIndex` parameter to `ProofOfSaleRequest` → backend `createOrUpdateProofOfSale()` → replaces `photos[index]` when provided, appends when null (backward compatible)
- **Partial photo upload lost when leaving PaymentScreen**: For portabilidad (2 photos required), if user uploaded 1 photo in PaymentScreen and navigated away, the photo was in Firebase but never sent to backend — PendingVerificationsScreen showed 0/2. Changed `uploadProofOfSale()` to send each photo to backend immediately after Firebase upload (not batch). Backend appends to PENDING record. Also added `_sentToBackendUrls` tracking so `cleanupOrphanedProofOfSalePhotos()` doesn't delete photos already registered with backend
- **Photo slot mismatch when uploading out of order**: If user uploaded portabilidad photo first (without vinculacion) from PaymentScreen, it was stored at `photos[0]` but PendingVerificationsScreen always mapped `photos[0]` = Vinculacion — so the photo appeared in the wrong slot. Fixed by adding `photoLabel` parameter to `ProofOfSaleRequest`. Backend now places photos at fixed indices based on label (Vinculacion=0, Portabilidad=1), padding with empty strings for unfilled slots. PendingVerificationsScreen filters empty strings with `takeIf { it.isNotEmpty() }`. Completion checks use non-empty photo count instead of array size
- **PaymentViewModel sent wrong photoLabel to backend**: Labels from PaymentScreen ("linea"/"portabilidad") didn't match backend Zod enum (`Vinculacion`/`Portabilidad`), so `photoLabel` was silently stripped by validation and photos always appended instead of being placed at fixed slots. Added label mapping in `sendSinglePhotoToBackend`: "linea" → "Vinculacion", "portabilidad" → "Portabilidad"
- **No photo preview in PendingVerificationsScreen**: Camera captured and uploaded directly without letting the user review the photo. Added `PhotoPreviewDialog` with full-screen preview showing the label, confirm and retake buttons — matching PaymentScreen's existing `ProofOfSalePhotoPreviewDialog` pattern
- **Upload cancelled when navigating back in PendingVerificationsScreen**: Pressing back during photo upload cancelled the ViewModel coroutine, losing the photo. Added `BackHandler` that blocks back press while uploading, disabled top bar back button during upload, and added a semi-transparent "Subiendo foto..." overlay with spinner so the user knows to wait

---

## [1.8.1] - 2026-03-09

### **Changed**

- **AID-based brand detection for contactless payments**: Per Edgardo's recommendation ("Tendrías que considerar hacer este ajuste por AID, si intentas detectarlas manualmente puede implicar que mandes mal algún tag por marca"), contactless brand detection now uses AID (tag 0x4F) as primary method instead of PAN prefix. AID prefix maps directly to contactless kernel: `A000000004`=Mastercard(K2), `A000000003`=Visa(K3), `A000000025`=AMEX(K4). PAN-based detection kept as fallback if AID is unavailable. Applied to both sandbox and production variants

---

## [1.8.0] - 2026-03-09

### **Added**

- **Per-staff sales filter in Reports**: New staff filter chips in Summary tab allow multi-select filtering by staff member. "Ventas por Usuario" breakdown section shows each staff member's total sales, order count, and tips — sorted by highest sales. Metric cards (Ventas, Órdenes, Ticket Promedio) recalculate when staff filter is active. Print option "Ventas por usuario" includes per-staff breakdown on thermal receipt. Graceful degradation when backend doesn't provide `staffSales` data (old backend versions)
- **Backend `staffSales` in shifts-summary**: New `staffSales` array in `/tpv/venues/:id/shifts-summary` response tracks per-staff total sales, order count, and tips from both shift and orphan payments. Backward compatible — old TPV versions ignore the new field

### **Fixed**

- **Contactless payments registering as "chip" in Blumon**: Root cause found by decompiling Blumon SDK: `SaleIccUseCase` hardcodes `entryMode = EntryMode.CHIP`, while `SaleCtlsUseCase` hardcodes `entryMode = EntryMode.CONTACTLESS`. We were calling `SaleIccUseCase` for ALL transactions (both chip and contactless), so Blumon's platform always saw "chip". Fix: inject `SaleCtlsUseCase` in PaymentViewModel, add `isContactless` flag to `performOnlineAuthorization()`, use `SaleCtlsUseCase` + `SaleCtlsParams` for contactless payments. `SaleCtlsResponse` is converted to `SaleIccResponse` (identical structure) so all downstream code works unchanged. Also includes brand-specific contactless EMV tag lists per Edgardo's specification: Visa K3 / Mastercard K2 with 0x9F6E (Form Factor), AMEX K4 with 0x9F71/0x9F67 (different CVM/Form Factor tags). Track2 extracted first to detect card brand, then kernel-specific tag list applied. Both sandbox and production variants synced
- **Permission errors silently swallowed on shift operations**: ShiftViewModel used fragile string matching on `exception.message` and had no 403 case — users without `shifts:create` permission saw generic "Error al procesar la solicitud" instead of a permission error. Rewrote `translateError()` to use `ApiException` type matching (HttpError, PermissionDenied, NetworkError). Added proactive permission checks via `PermissionsRepository` with `canOpenShift`/`canCloseShift` StateFlows — buttons are disabled with red helper text when user lacks permission. Guards in `openShift()`/`closeShift()` provide fallback error if button is somehow clicked
- **403 errors unhandled in OrderRepositoryImpl**: All `when (response.code())` blocks in OrderRepositoryImpl (compItems, voidItems, applyDiscount, createOrder, addItemsToOrder, removeOrderItem, updateGuest, customer operations) had cases for 400/401/404/500 but NOT 403. Added 403 case to all 10 error handling blocks with specific permission-denied messages in Spanish
- **Comp/discount/guest operations fail silently in MenuViewModel**: `compItems()`, `applyDiscount()`, and `updateGuest()` had `// TODO Step 10: Show error/success Snackbar` comments instead of actual snackbar emissions. Replaced all 9 TODO comments with proper `MenuUiEvent.ShowSnackbar` emissions for success, failure, and exception cases — matching the pattern already used by `voidItems()`
- **Shift state not updating on WelcomeScreen after closing shift**: WelcomeScreen and ShiftScreen use separate `ShiftViewModel` instances (Hilt scoped to NavBackStackEntry). When a shift was closed in ShiftScreen, WelcomeScreen's ViewModel retained stale state. Added `shiftViewModel.loadCurrentShift()` to the existing `ON_RESUME` lifecycle observer alongside salesGoal and attendance refresh
- **Switch toggle invisible in dark mode (Reports)**: All `Switch` components in Reports (print dialog, comparison toggle, historical print dialog) used default colors where the unchecked thumb was invisible against dark backgrounds. Applied explicit `SwitchDefaults.colors()` with `onSurfaceVariant`/`surfaceVariant` for unchecked state
- **Dashboard data not loading after cold start without network**: Several HomeViewModel init tasks (`fetchAttendanceState`, `fetchSalesGoal`, `warmUpProductCache`) make API calls on startup, but if the network is down (e.g., DNS not yet resolved), they fail silently and never retry. When connection is restored, `listenForConnectionRestored()` re-fetched merchants, updates, and payments — but NOT attendance, sales goals, or product cache. Added all three to the connection restored handler so they load automatically once network recovers, without requiring a manual pull-to-refresh. Also added `skipDelay` parameter to `warmUpProductCache()` to avoid unnecessary 3s stagger delay on retry

---

## [1.7.9] - 2026-03-03

### **Changed**

- **Self-updater replaced with PackageInstaller Session API**: PAX `ISys.installApp()` (NeptuneService system process) cannot read APK files from app-private storage on Android 10+ due to FUSE/SELinux cross-process restrictions ("Unzip error"). Replaced the entire multi-path file-copy strategy with Android's PackageInstaller Session API, which streams APK bytes via IPC — the app reads its own file and writes bytes into a session. No cross-process file path access needed. Works on all Android versions we support (minSdk 27+). PAX SDK `InstallerAppUseCase` kept as fallback on all Android versions. New centralized `ApkInstaller` singleton + `InstallResultReceiver` broadcast receiver used by both `SelfUpdateViewModel` and `UpdateRequestManager`, removing ~350 lines of duplicated multi-path install code. SessionId validation prevents stale callbacks from completing the wrong install. Includes 2-minute install timeout (prevents indefinite "Installing..." hang), `STATUS_PENDING_USER_ACTION` launches system confirm dialog and waits for follow-up callback (120s timeout protection), removal of dead `canRequestPackageInstalls()` check from SelfUpdateScreen, and Mutex for concurrent install protection
- **Comprehensive install observability**: Every step of the APK install process is now logged via `ObservabilityManager` (routes to Firebase Crashlytics + Socket.IO backend + File). Metadata includes: APK path, file size, Android version, device model, session ID, bytes written, strategy used (PackageInstaller vs PAX SDK), duration, and error details. `InstallResultReceiver` logs every `PackageInstaller.STATUS_*` code with structured metadata. New `tpv/report-install-attempt` API endpoint sends structured install attempt reports (success/failure, strategy, timing, error message) to backend after every install — called from both `SelfUpdateViewModel` and `UpdateRequestManager`. Install failures in production are now visible in Crashlytics, Socket.IO logs, and backend API logs simultaneously

### **Fixed**

- **Smart merchant retry on fallback detection**: When PAX terminal switches from SIM to WiFi during app startup, the terminal config fetch fails silently and fallback merchant accounts (hardcoded) remain active with wrong Blumon credentials. Now `proceedToMerchantSelection()` detects fallback accounts via `MerchantRepository.isUsingFallback()`, waits up to 8s for network connectivity, and re-fetches real merchants from backend before proceeding to payment. Shows "Verificando cuentas de pago..." / "Esperando conexion..." loading overlay. If connectivity or fetch fails, shows actionable error instead of silently using wrong credentials. `MerchantRepositoryImpl.refreshMerchants()` now delegates to `TerminalConfigRepository.fetchConfig()`. `HomeViewModel.retryFetchMerchantsAndReinitSDK()` simplified to use the same `refreshMerchants()` method. Both sandbox and production variants synced
- **Crashlytics `lateinit property dal has not been initialized`**: Race condition between `AvoqadoTPVApplication.initializeNonCritical()` (calls `AppManager.init(context)` on background `Dispatchers.IO`) and `InitializationManager.executeInitialization()` (calls `InitializerUseCase` which accesses `AppManager.dal`). If the SDK use case ran before the Application's background init completed, `AppManager.dal` (a `lateinit var` in `com.blumonpay.pax.utils.AppManager`) was still uninitialized. Fix: defensive `AppManager.init(context)` call at the start of `executeInitialization()` — idempotent (safe if already called), ensures DAL is always set before any SDK operations. Both sandbox and production variants synced
- **Connection banner spam (WiFi lento / sin conexión)**: Offline banner appeared too frequently on transient network flickers (screen lock/unlock, WiFi roaming). Implemented asymmetric hysteresis pattern (Square/Stripe/Uber industry standard): go offline **slowly** (3s grace period with network re-validation), go online **quickly** (immediate). Centralized `scheduleOfflineTransition()` covers all 3 offline paths (network observer, connectivity probe, heartbeat). `cancelOfflineTransition()` called on all "internet confirmed" paths including server-down states (prevents stale NoInternet jobs). Constants `OFFLINE_GRACE_MS` (3s) and `ONLINE_STABILIZATION_MS` (2s) extracted for testability. Added 3 virtual-time unit tests with `StandardTestDispatcher` + `advanceTimeBy` validating grace period behavior
- **False "Señal WiFi débil" alert on cellular**: Two bugs: (1) `NetworkMonitor.getSignalStrength()` mapped `SIGNAL_STRENGTH_UNSPECIFIED` (Integer.MIN_VALUE) to level 0 ("very poor") instead of `null`. On PAX A910S via LTE, `NetworkCapabilities.signalStrength` returns unspecified, causing phantom weak signal. (2) `DeviceHealthViewModel.buildAlertsList()` didn't check `networkType` — showed "WiFi débil" even on cellular. Fix: return `null` for unspecified signal, filter by network type, show correct message/icon per transport ("Señal WiFi débil" vs "Señal celular débil")
- **Missing PAX permission `com.pax.permission.UPDATE_APP`**: Required by `ISys.installApp()` when PAX permission checking is enabled. Added to AndroidManifest.xml
- **Cell tower location accuracy improved**: Include neighbor towers (not just serving tower) and signal strength (`signalStrength` dBm) in cell tower requests for better triangulation. Reduced GPS timeout from 20s to 8s (3s for Android LocationManager) since PAX terminals are usually indoors

---

## [1.7.6] - 2026-02-24

### **Fixed**

- **Self-updater INSTALL_FAILED on Android 10 PAX terminals**: `ISys.installApp()` (PAX system process) cannot read APK from `getExternalFilesDir` on Android 10 due to FUSE inter-process restrictions. Fix: copy APK to `context.filesDir/apk_install/` (internal storage, ext4) and `setReadable(true, false)` before installing. Applied to both `SelfUpdateViewModel.installUpdate()` and `UpdateRequestManager.installUpdate()`. Android 12 unaffected (FUSE allows app-scoped reads)
- **CancellationException swallowed in HeartbeatWorker and PaymentSyncWorker**: Both workers caught generic `Exception` without rethrowing `CancellationException`, causing false FAILED ACKs for remote commands. Added `catch (CancellationException) { throw }` before generic catch blocks
- **Large payment amounts cause line break in merchant selection screen**: Amounts with 5+ digits overflowed single line in `SelectingMerchant` state. Reduced font from `displayLarge` to `displayMedium` and added `maxLines = 1` with `TextOverflow.Ellipsis`

---

## [1.7.4] - 2026-02-19

### **Fixed**

- **"Sin conexión" banner stuck after screen unlock / "Reintentar" unresponsive**: `checkConnection()` ran concurrently from 3 paths (monitoring loop, network observer, forceCheck) with no serialization, so a stale 30s-timeout error could overwrite a newer success. Split into `probeConnectivity()` (fast 8s UI probe for forceCheck/network observer) and `performFullHeartbeat()` (full heartbeat + command processing for monitoring loop). Added monotonic `checkVersion` counter — stale results skip all UI side effects. Commands always execute regardless of staleness. Reconnected banner uses cancellable `Job` to prevent duplicates. Added `CancellationException` rethrow in `processPendingCommands` to prevent false FAILED ACKs on coroutine cancellation

---

## [1.7.3] - 2026-02-19

### **Fixed**

- **Proof-of-sale photos deleted from Firebase after successful upload**: After `sendProofOfSaleToBackend()` succeeded, `_pendingProofOfSaleUrls` was not cleared. When the next sale started (`resetPayment()` → `cleanupOrphanedProofOfSalePhotos()`), the cleanup deleted the already-saved photos from Firebase Storage. Dashboard showed broken image thumbnails because the URLs in the DB pointed to deleted files. Fix: clear `_pendingProofOfSaleUrls` immediately after successful backend confirmation
- **GPS location registering in Texas instead of actual location**: LocationService accepted locations with any accuracy (including 2,635,258m). Added `MAX_ACCURACY_METERS = 1000f` threshold — locations with accuracy worse than 1km are rejected and fall through to the next provider. Defense-in-depth alongside the backend fix (`considerIp: false`)
- **Permission dialogs dismissed during app initialization**: Camera/location permission dialogs were dismissed when `fetchTerminalConfigIfActivated()` triggered UI recomposition. Moved initialization to `startPostPermissionInitialization()` which runs AFTER all permission dialogs are resolved

---

## [1.7.2] - 2026-02-19

### **Fixed**

- **AppUpdateReceiver duplicate activity on ADB install**: During `adb install` / `./gradlew installSandboxDebug`, the system launcher starts the app immediately, then `AppUpdateReceiver` fires 100ms later and creates a second `MainActivity` with `FLAG_ACTIVITY_CLEAR_TOP`, destroying the first one. This canceled all running coroutines, causing `LeftCompositionCancellationException`, `JobCancellationException`, canceled HTTP requests, and brief `SERVER_DOWN` alerts. Added foreground guard: if the app is already in the foreground (ADB install scenario), the relaunch is skipped. During real PAX SDK self-updates, the process is killed first so the app won't be in foreground — relaunch still works correctly
- **Self-update download crash on Android 10+**: Both Avoqado and Blumon update downloads failed with `EACCES (Permission denied)` on Android 10+ because `WRITE_EXTERNAL_STORAGE` is ignored by scoped storage. Changed download directory from public Downloads (`/storage/emulated/0/download/`) to app-scoped external storage (`getExternalFilesDir/apk_updates/`). No permissions needed, readable by PAX system installer, works on all Android versions (7-13+)
- **LoginScreen PIN pad adaptive sizing (Square/Clover pattern)**: PIN pad and "Ir" button were crushed on small screens due to fixed 80dp button sizes. Replaced with adaptive sizing via ResponsiveSizes: small screens 56dp, medium (PAX A910S) 68dp, large 80dp. Font sizes and icon sizes scale proportionally. No scroll needed — everything fits on screen. Spacers reduced from `spacingMedium` to `spacingSmall`. Previews updated to PAX A910S dimensions (360x640dp)
- **SuperAdmin button missing in simplified mode**: SuperAdmin tools button only appeared in normal mode (restaurant/retail). Now appears for SUPERADMIN role in both simplified (telecom) and normal modes
- **LoginScreen unused imports**: Removed unused imports (`AsyncImage`, `Timber`, `clip`, `ContentScale`, `painterResource`, `R`) left from venue logo feature that was never rendered

### **Changed**

- **Payment history list denser layout + card brand icons**: Reduced spacing between payment cards (8dp horizontal padding, 6dp vertical gap vs previous 16dp/12dp). Reduced internal card padding (12x10dp vs 16dp). Card payments now show actual brand logo icons (SVG paths from dashboard's `getIcon.tsx`): Visa (blue wordmark), Mastercard (red/yellow overlapping circles), Amex (blue AMERICAN EXPRESS text) inside 34x24dp bordered containers. Other brands get text-in-box fallback. Cash/voucher keep text badges with theme colors. New `CardBrandIcon` composable in `payments/presentation/components/`. New `CardBrand` enum in domain model maps from backend Prisma `cardBrand` field. Added `cardBrand` to `PaymentDto` and `Payment` domain model
- **LoginScreen "Ir" button moved to right of PIN pad**: Moved login button from below the PIN pad to a column on the right side, spanning the full height of the 4-row pad. Same width as PIN buttons, pill-shaped (RoundedCornerShape). Saves one full row of vertical space on PAX A910S. Fixed text breaking into 2 lines on real device by removing default 24dp content padding (`contentPadding = PaddingValues(0.dp)`)
- **PinDisplay counter right-aligned**: Moved "0/10" character counter from centered to right-aligned under the eye toggle icon
- **ResponsiveScaffold size category stable with banners**: `BoxWithConstraints.maxHeight` was used for size category calculation, so banners (e.g. "Sin conexion a internet" ~56dp) reduced available height from 640dp to 584dp, dropping category from "medium" to "small" and shrinking all PIN buttons from 68dp to 56dp. Now uses `LocalConfiguration.screenHeightDp` (physical screen dimensions) so the size category stays "medium" on PAX A910S regardless of banners or overlays
- **PAX A910S target device documented**: Added PAX A910S specs (720x1280px, 320dpi, 360x640dp) as priority UI pattern rule in CLAUDE.md, critical-warnings.md, and MEMORY.md. Every screen must have `@Preview(widthDp=360, heightDp=640)` and use adaptive sizing via ResponsiveSizes
- **SelfUpdateScreen "¡Estás al día!" UI refresh**: Redesigned the UpToDate state with a polished card layout, glow success icon, version pill, and a larger Avoqado logo badge with light background so branding remains readable in dark theme. Added dedicated A910S preview for this state (`app/src/main/java/com/jaac/avoqado_tpv/features/self_update/presentation/SelfUpdateScreen.kt`)

---

## [1.7.0] - 2026-02-18

### **Changed**

- **SerializedSaleScreen (Vender SIM) UX redesign**: Made layout scrollable to prevent overflow on PAX A80 when all form fields are visible. Added visual step progression with numbered circles (primary-colored 24dp badges) that dynamically adjust based on current state (Available, NotRegistered, portabilidad). Replaced flat "Escanear Otro" button with divider pattern ("o escanea otro") matching SerializedInventoryScreen. Changed category dropdown and price input to pill-shaped (RoundedCornerShape(50)) for cleaner look. Removed `Spacer(weight(1f))` incompatible with scrollable Column
- **SerializedInventoryScreen (Alta de SIM) UX redesign**: Added visual step progression with numbered circle badges (StepRow) for steps 1 (category selection) and 2 (scan barcodes), matching Vender SIM pattern. Changed category dropdown to pill-shaped (RoundedCornerShape(50)) for both loading and active states

### **Added**

- **Multi-photo proof-of-sale wizard for portabilidad**: Portabilidad sales now require 2 mandatory photos (registro de línea + registro de portabilidad) instead of skipping proof-of-sale. Wizard auto-advances from first to second photo. Warning banner shown until all photos are complete. Navigation blocked ("Nueva Venta" disabled) until all required photos are uploaded. Firebase cleanup for orphaned photos on payment reset or ViewModel destruction. Tapping a filled thumbnail opens full-screen preview with "Retomar foto" option (deletes old Firebase photo, re-opens camera)
- **Quiz explanation per answer**: New `explanation` field on quiz questions (nullable), shown in review screen after quiz submission. Backend Prisma schema, Zod validation, service layer, dashboard question dialog (textarea), and TPV review screen all updated
- **Quiz review screen**: After submitting a quiz, staff can tap "Revisar respuestas" to see each question with correct (green) / wrong (red) answer indicators and explanation callouts. Paginated one-question-at-a-time navigation
- **Configurable quiz pass threshold**: Superadmin can set pass threshold per training (60%, 70%, 80%, 100%). Backend `quizPassThreshold` field on TrainingModule (default 70). Dashboard settings section in TrainingDetail edit form. TPV uses training's threshold instead of hardcoded 70%
- **Quiz attempt limits**: Superadmin can set max attempts per training (1, 2, 3, or unlimited). Backend `quizMaxAttempts` field (default 0 = unlimited) + `attemptNumber` on TrainingProgress. TPV tracks attempts, shows "Intento N de M" on completion screen, disables retry when exhausted
- **TrainingViewModelTest**: 24 unit tests covering paginated quiz navigation, configurable threshold, attempt limits, review mode, API integration, multi-select scoring, mixed question types
- **True/False question type**: New `TRUE_FALSE` question type across full stack. Backend Prisma enum `TrainingQuestionType`, Zod validation, service layer. Dashboard question dialog has type selector dropdown; selecting "Verdadero / Falso" pre-fills options with ["Verdadero", "Falso"] and disables option editing. TPV renders radio buttons same as multiple choice
- **Multi-Select question type**: New `MULTI_SELECT` question type. Backend stores `correctIndices: Int[]` for questions with multiple correct answers. Dashboard uses checkboxes instead of radio for marking correct answers; validates at least one selected. TPV renders Checkbox instead of RadioButton, with `toggleQuizAnswer()` for add/remove behavior. All-or-nothing scoring: must select exactly the correct options. Review screen shows all correct answers in green
- **Question type badges**: Dashboard quiz question list shows type badge (Opción múltiple / Verdadero-Falso / Selección múltiple) next to each question. Correct answer indicators updated for multi-select (multiple green checks)

- **showMessages / showTrainings visibility toggles**: New `showMessages` and `showTrainings` boolean settings in TpvSettings (default: true). Controlled from dashboard Home Screen section. Both simplified and normal mode WelcomeScreen buttons are conditionally shown based on these settings. In normal mode, Messages (with pending badge) and Trainings buttons appear after Support. Added fields to `TpvSettings.kt`, `TpvSettingsDto.kt` (with `toDomain`/`toDto` mapping), and wrapped button rendering with `if (tpvSettings.showMessages)` / `if (tpvSettings.showTrainings)` guards in both modes
- **Proof-of-sale camera icon on success screen**: In SERIALIZED_INVENTORY mode (non-portabilidad), the QR code area now shows a dashed-border camera placeholder (72dp, no background) that opens the proof-of-sale camera. After confirming a photo, the thumbnail replaces the placeholder inside the same box. Tapping the thumbnail reopens the camera to retake. Portabilidad flows (where `showProofOfSaleButton` is false) keep the clean layout without icon
- **Training system (full-stack)**: Complete LMS/training module system across backend, dashboard, and TPV
  - **Backend**: New Prisma models (`TrainingModule`, `TrainingStep`, `TrainingQuizQuestion`, `TrainingProgress`), superadmin CRUD routes with media upload (Firebase Storage), TPV consumption routes with auto-filtering by organization's enabled modules (feature tags)
  - **Dashboard**: `TrainingManagement` list page with stats/filters/DataTable and `TrainingDetail` editor page with steps management, quiz builder, media upload, cover image, and TPV preview mockup. Added "Entrenamientos" navigation item under Platform section. Organization and venue-level assignment with "Todas las sucursales / Sucursales específicas" toggle, feature tag selector for module-based auto-filtering
  - **TPV DTOs**: `TrainingModuleDto`, `TrainingStepDto`, `TrainingQuizQuestionDto`, `TrainingProgressDto` with response wrappers
  - **TPV API endpoints**: `getTrainings`, `getTrainingDetail`, `updateTrainingProgress`, `getTrainingProgress` in ApiService
  - **TrainingViewModel**: Manages list state (trainings + progress map) and detail state (step navigation, quiz answers, 70% pass threshold, progress saving)
  - **TrainingsListScreen**: Card list with cover images, category/duration badges, required indicator, progress bar, completed checkmark
  - **TrainingStepViewer**: Step-by-step viewer with media area (ExoPlayer for video, AsyncImage for images), step indicator dots, instruction text, tip callouts, navigation buttons (48dp touch targets), quiz integration, and completion screen with score
  - **TrainingQuizScreen**: Radio-button quiz with question cards, selected/unselected styling, submit button enabled only when all answered
  - **TrainingVideoPlayer**: Reusable ExoPlayer composable component extracted from POC, with error handling and lifecycle cleanup
  - **FullscreenMediaDialog**: New composable dialog for viewing training media (videos and images) in fullscreen. Videos get full-width ExoPlayer with autoplay. Images support pinch-to-zoom and pan gestures. Tap outside or close button to dismiss
  - **Navigation**: New `NavRoute.Trainings` and `NavRoute.TrainingViewer` routes wired in AppNavigation
- **MessagesScreen (full-screen)**: New `NavRoute.Messages` screen replaces `MessageInboxDialog`. Features filter chips (Todos / No vistos / Vistos), paginated loading via `LazyColumn` infinite scroll (20 per page), empty states per filter, and TopAppBar with back + refresh buttons
- **Message pagination**: `TpvMessageViewModel` now supports `fetchMoreMessages()` with offset-based pagination, `refreshMessages()` for pull-to-refresh, `filteredMessages` derived StateFlow, and `MessageFilter` enum
- **Entrenamientos video player**: Replaced placeholder dialog with ExoPlayer/Media3 video player dialog. Plays Big Buck Bunny test video to verify PAX A80 hardware video decoding. Includes transport controls, error fallback UI, and proper player lifecycle cleanup
- **ExoPlayer dependency**: Added `media3-exoplayer` and `media3-ui` (1.2.1) for video playback
- **Mensajes button (simplified mode)**: New "Mensajes" ActionButton in SERIALIZED_INVENTORY grid (same size as Vender/Alta), positioned after Alta. Shows pending message count badge. Tapping now navigates to full MessagesScreen
- **Entrenamientos button (simplified mode)**: New "Entrenamientos" ActionButton for video tutorials. Opens video player dialog for PAX hardware testing
- **Message history API endpoint**: `GET /api/v1/tpv/messages/history` returns paginated list of all messages delivered to a terminal with delivery status (PENDING, DELIVERED, ACKNOWLEDGED, DISMISSED)
- **Read-only message dialog**: Tapping a message from the inbox opens TpvMessageDialog in read-only mode for already-acknowledged/dismissed messages (only "Cerrar" button, no interactive actions)
- **Timeclock on WelcomeScreen**: New `TimeclockStatusCard` component showing real-time attendance status (clock-in/out state, elapsed work timer) directly on the home screen
- **Attendance gating on action buttons**: When `requireClockInToLogin` is enabled and user hasn't clocked in, operational buttons (Vender, Alta, Pago rapido, Ordenes) are disabled with "Registra tu entrada" badge — applies globally across simplified and normal modes
- **PIN persistence for timeclock navigation**: Staff PIN stored in SecureStorage after login so WelcomeScreen can navigate to TimeclockScreen without re-entering PIN; cleared on logout
- **fromHome navigation parameter**: TimeclockScreen `fromHome` query parameter prevents session clearing when navigating back from WelcomeScreen (vs LoginScreen)

### **Changed**

- **Proof-of-sale always mandatory for SERIALIZED_INVENTORY**: Both portabilidad and non-portabilidad flows now require proof-of-sale photos (1 photo for normal, 2 for portabilidad). `skipProofOfSale` flag replaced by `isPortabilidad` throughout navigation chain (SerializedSaleScreen → AppNavigation → PaymentScreen → PaymentViewModel). Firebase upload filenames now include photo label suffix for differentiation
- **Serialized inventory receipt sale type**: Printed receipt now shows `TIPO: PORTABILIDAD` or `TIPO: LINEA NUEVA` after CAJERO line for serialized inventory sales. Non-serialized receipts are unaffected (`isPortabilidad = null`)
- **Serialized inventory receipt ICCID/category**: Printed receipt now shows `PRODUCTO: <category>` and `ICCID: <serial>` in the header section. Serial number and category name passed through full navigation chain (SerializedSaleScreen → AppNavigation → PaymentScreen → PaymentViewModel → PrinterManager)
- **Proof-of-sale placeholder red warning border**: Empty photo placeholders show red dashed border with "Toma foto" hint when photos are incomplete. Separate error banner removed — warning is integrated into the placeholder itself
- **Proof-of-sale photo section spacing**: Added 48dp top padding to photo placeholders on payment success screen so they sit comfortably inside the receipt ticket background. Increased receipt background height from 200dp to 220dp for serialized proof-of-sale flows
- **Quiz screen paginated**: TrainingQuizScreen rewritten from scrollable all-questions view to one-question-at-a-time with step dots, progress header, and Anterior/Siguiente/Enviar navigation. Better UX for PAX A80 small screen
- **CompletionScreen enhanced**: Now shows configurable pass threshold text, attempt counter ("Intento N de M"), "Revisar respuestas" button (always visible when quiz exists), and "Intentos agotados" text when max attempts reached. Added verticalScroll for PAX A80 overflow safety
- **TrainingStepViewer top bar**: Subtitle now shows "Quiz" during quiz and "Revisión del quiz" during review mode
- **Dashboard quiz settings**: TrainingDetail edit form shows "Configuración del Quiz" section (pass threshold + max attempts selects) when quiz questions exist. Read-only view shows threshold and attempt badges
- **Dashboard explanation display**: Quiz question cards in TrainingDetail show explanation preview with lightbulb icon below options
- **Entrenamientos navigation**: Entrenamientos button in simplified mode now navigates to full `TrainingsListScreen` instead of opening ExoPlayer POC dialog. `TrainingVideoDialog` removed from WelcomeScreen, replaced by reusable `TrainingVideoPlayer` component in training feature module
- **TrainingStepViewer media UX**: Media area (video/image) now tappable to open fullscreen dialog. Added "Ampliar" overlay hint with fullscreen icon. Images use `ContentScale.Fit` instead of `Crop` for better visibility. Dashboard TrainingDetail step list has larger clickable media previews with hover overlay and fullscreen lightbox dialog (dark background, autoplay for video, pinch-to-zoom concept for images)
- **Mensajes navigation**: Mensajes button in simplified mode now navigates to full `MessagesScreen` instead of opening `MessageInboxDialog`. Dialog removed from WelcomeScreen
- **MessageInboxCard**: `MessageRow` and `formatRelativeTime` changed from `private` to `internal` visibility for reuse in `MessagesScreen`
- **Auto-action timeclock from WelcomeScreen**: Tapping "Sin registro de entrada" or "Registrar Salida" on TimeclockStatusCard now auto-triggers clockIn/clockOut, skipping the Ready UI. Shows loading overlay, runs photo flow if required, then auto-navigates back to WelcomeScreen. On error, falls back to manual PulseContent UI
- **LoginScreen simplified**: Removed `RequiresClockIn` and `OnBreak` blocking overlays from LoginScreen. Login now always succeeds regardless of clock-in status. Attendance enforcement is handled entirely by WelcomeScreen (action button gating + TimeclockStatusCard). This eliminates the complex LoginScreen → Timeclock → Home navigation path that caused PIN persistence bugs
- **LoginViewModel**: Removed `checkClockInStatus()`, `tpvSettingsRepository` and `timeEntryRepository` dependencies. LoginState sealed class simplified (removed `RequiresClockIn` and `OnBreak` states)
- **LoginScreen timeclock button removed**: Removed the ⏱ timeclock button from LoginScreen. All timeclock functionality is now accessed exclusively from WelcomeScreen via `TimeclockStatusCard`. LoginScreen only has a single "Ir" button for login. Removed `onTimeclockClick` callback from LoginScreen and AppNavigation
- **TimeclockScreen**: Removed DESCANSO (break) and FIN DESCANSO buttons — only ENTRADA, SALIDA, and LISTO remain
- **HomeViewModel**: Now exposes `currentTimeEntry`, `requireClockInToLogin`, and `isAttendanceLoading` StateFlows for WelcomeScreen attendance display
- **WelcomeScreen**: Added lifecycle-aware attendance refresh (ON_RESUME) and `TimeclockStatusCard` between ShiftStatusBanner and SalesGoalsPager
- **SalesGoalProgressCard collapsible**: Card is now collapsible — collapsed state shows only header row (icon + title + badges) and progress bar; expanded shows full detail (sold/percentage/goal + remaining text). Tap card to toggle. Chevron icon indicates state. Default is collapsed
- **PaymentSuccessContent SERIALIZED_INVENTORY mode**: Home icon, receipt/menu button, and QR code section are now hidden when `flowOrigin == PaymentFlowOrigin.SERIALIZED`. Ticket background padding adjusted (70dp → 16dp) for cleaner layout without QR

### **Fixed**

- **Serialized inventory receipt missing FOLIO**: `orderNumber` from `QuickSellResult` was not passed through the navigation chain (SerializedSaleScreen → AppNavigation → PaymentScreen). Receipt now prints `FOLIO: ORD-XXX` for serialized inventory sales
- **Proof-of-sale placeholder label truncated**: "Registro de línea" was cut to "Registro de" because placeholder was only 72dp. Single photo mode now 140x100dp with full label visible. Two-photo mode uses abbreviated "Reg. línea" label. Icon size increased (20→24dp), text allows 2 lines. Warning banner moved inside receipt card below photo area with shorter text "Toma foto para validar tu venta"
- **Print options dialog for sales report**: New AlertDialog before printing lets user toggle optional sections: "Propinas por mesero" (waiter tips breakdown with name, amount, count) and "Resenas" (ratings count, only shown when reviews enabled in TpvSettings). Average tip percentage always included with waiter tips section
- **Waiter tips on receipt**: Optional PROPINAS POR MESERO section shows each waiter's name (truncated to 14 chars), tip amount, and order count. Includes average tip percentage line
- **Ratings count on receipt**: Optional RESENAS section shows total ratings count. Gated by `TpvSettings.showReviewScreen`
- **SalesSummary domain model extended**: Added `WaiterTip` data class, `averageTipPercentage`, `ratingsCount`, and `waiterTips` list to `SalesSummary`. ShiftsSummaryDto now maps all three fields from backend response
- **Sales goal progress bar white dot**: Removed Material3 `LinearProgressIndicator` default stop indicator (white dot at end of track) by setting `drawStopIndicator = {}`
- **Sales goal progress bar dark theme border**: Added subtle 1dp `White/12%` border around the progress bar track in dark theme for better visual definition
- **Sales report receipt line wrap**: Payment methods section exceeded 32-char thermal printer width (was 35 chars), causing "100%" to split as "1\n00%". Reduced label padding from 20 to 14 chars to fit within printer width
- **Sales report receipt missing tips**: Added `totalTips` to `printReport()` and `ReportsViewModel.printReport()`. Tips now shown in RESUMEN DE VENTAS section as "Total Propinas: $X.XX" (hidden when $0.00)
- **Terminal ID not saved during activation**: `ActivationRepositoryImpl` was not calling `saveTerminalId()` despite `terminalId` being available in the activation response. Also now saved from `TerminalConfigRepositoryImpl.fetchConfig()` on every startup (backfills for already-activated terminals). This fixes messaging API calls that were silently skipped due to null terminalId
- **Message REST endpoints 400 error**: All TPV message REST endpoints (`/messages/pending`, `/messages/history`, `/messages/:id/acknowledge`, `/messages/:id/dismiss`, `/messages/:id/respond`) now send `terminalId` as query parameter. Backend controller fixed to use `req.authContext.venueId` (was incorrectly using `req.user`) and accept `terminalId` from query params (JWT doesn't contain terminalId)
- **AttendanceVerificationTest**: Added missing `showReports`, `showPayments`, `showSupport`, `showGoals` params to TpvSettingsDto constructors in tests
- **HomeViewModelTest**: Added missing `timeEntryRepository` and `tpvSettingsRepository` mock dependencies
- **Timeclock clock-out from WelcomeScreen after LoginScreen clock-in**: Fixed bug where clocking in via LoginScreen's RequiresClockIn flow made it impossible to clock out from WelcomeScreen (`"No stored PIN or venueId for timeclock navigation"`). Root cause: complex LoginScreen → Timeclock → Home navigation path with `fromHome=false` could clear the stored PIN via `clearSession()` on back navigation. Fix: removed the entire RequiresClockIn flow from LoginScreen — attendance enforcement now handled solely by WelcomeScreen's button gating
- **SelfUpdateScreen content overflow on PAX A80**: Update/download screens had no scroll and oversized elements (80dp icons, 56dp buttons, 24dp spacing) causing the "Actualizar" button to be crushed on small PAX screens. Fixed with scrollable layout (`BoxWithConstraints` + `verticalScroll`), reduced icon sizes (56dp), button heights (48dp), and tighter spacing (16dp). Applies to all 7 content states (checking, available, downloading, installing, upToDate, error, noWifi)
- **ForceUpdateDialog content overflow on PAX A80**: Same overflow issue in the force update modal dialog. Added `verticalScroll` to inner Column, reduced icon (56dp), button height (48dp), and spacing throughout
- **FullscreenMediaDialog video tiny on portrait videos**: Two issues — (1) `aspectRatio(16f/9f)` forced landscape container on portrait videos, (2) Compose Dialog window didn't fill full screen. Fixed with `decorFitsSystemWindows = false`, `DialogWindowProvider.window.setLayout(MATCH_PARENT, MATCH_PARENT)`, `PlayerView.layoutParams = MATCH_PARENT`, and fully opaque black background
- **ClockInPhotoPrompt "Cancelar" button crushed on PAX A80**: Photo prompt dialog (selfie/facade/deposit) had 80dp icon, 56dp button, 16dp spacing between 8 items = overflow. Added `verticalScroll`, reduced icon (56dp), button (48dp), spacing (12dp), and tighter typography
- **FullscreenMediaDialog close button unreachable**: Close button was rendered before the video player in the Box z-order, making it covered and untappable. Moved close button after media content so it draws on top. Also increased button size (40→44dp), icon (20→24dp), and background opacity (`White/15%` → `Black/60%`) for better visibility

---

## [1.4.0] - 2026-02-04

### **Added**

- **Ordering offline cache (Table Service)**: Table + floor element cache with cache-first UI and background refresh
- **Ordering resync on reconnect**: `syncPendingOrders()` with dirty tracking to resync pending local orders
- **High-signal logs**: Sync timeline logs (`syncRunId`, versions, dirty state) + floor plan cache hits/misses
- **Slow network testing tool**: `SlowNetworkInterceptor` toggle from SuperAdmin for simulated poor connectivity

### **Changed**

- **Order sync debounce**: Debounce no longer cancels in-flight sync; dirty changes trigger follow-up sync
- **Stable item ordering**: `line_position` added for draft order items to avoid UI reordering
- **Refund flow guards**: Refunds use original payment venue, backfill missing serial, and require `payments:refund` permission

### **Fixed**

- **Ordering count drift**: Prevents version conflicts caused by canceling sync mid-flight
- **Table Service flicker**: Cache-first rendering prevents constant refresh under slow networks
- **Refund 404**: Correct venueId used for refund recording
