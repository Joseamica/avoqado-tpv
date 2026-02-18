# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-1.md`

---

## [Unreleased]

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
