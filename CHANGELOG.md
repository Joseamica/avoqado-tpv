# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Added**

- **Mis Ventas (My Sales) screen**: New screen showing a promoter's serialized item sales history grouped by day with monthly totals. Includes month navigation, summary card with total count and amount, daily groupings, and gift item indicators. Accessible from simplified mode home screen for users with `serialized-inventory:sell` permission.

### **Fixed**

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
