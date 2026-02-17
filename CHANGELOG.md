# Avoqado TPV - Changelog

> **Version history and changes**
> Older entries archived in `CHANGELOG-archive-1.md`

---

## [Unreleased]

### **Added**

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

### **Fixed**

- **Terminal ID not saved during activation**: `ActivationRepositoryImpl` was not calling `saveTerminalId()` despite `terminalId` being available in the activation response. Also now saved from `TerminalConfigRepositoryImpl.fetchConfig()` on every startup (backfills for already-activated terminals). This fixes messaging API calls that were silently skipped due to null terminalId
- **Message REST endpoints 400 error**: All TPV message REST endpoints (`/messages/pending`, `/messages/history`, `/messages/:id/acknowledge`, `/messages/:id/dismiss`, `/messages/:id/respond`) now send `terminalId` as query parameter. Backend controller fixed to use `req.authContext.venueId` (was incorrectly using `req.user`) and accept `terminalId` from query params (JWT doesn't contain terminalId)
- **AttendanceVerificationTest**: Added missing `showReports`, `showPayments`, `showSupport`, `showGoals` params to TpvSettingsDto constructors in tests
- **HomeViewModelTest**: Added missing `timeEntryRepository` and `tpvSettingsRepository` mock dependencies
- **Timeclock clock-out from WelcomeScreen after LoginScreen clock-in**: Fixed bug where clocking in via LoginScreen's RequiresClockIn flow made it impossible to clock out from WelcomeScreen (`"No stored PIN or venueId for timeclock navigation"`). Root cause: complex LoginScreen → Timeclock → Home navigation path with `fromHome=false` could clear the stored PIN via `clearSession()` on back navigation. Fix: removed the entire RequiresClockIn flow from LoginScreen — attendance enforcement now handled solely by WelcomeScreen's button gating

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
