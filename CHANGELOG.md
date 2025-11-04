# Changelog

All notable changes to Avoqado TPV will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Professional PIN Pad UI** (2025-11-03) - World-class PIN entry following Square POS and Toast POS patterns
  - PinPad.kt: Custom numeric keypad component (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/presentation/components/PinPad.kt)
    - 3x4 grid layout (1-9, Clear, 0, Backspace)
    - Large touch targets (80dp) for busy restaurant/kitchen environments
    - ElevatedButton with Material3 styling and ripple effects
    - Disabled state handling during authentication
    - Clear (C) and Backspace (⌫) buttons for error correction
  - PinIndicator.kt: Visual PIN length indicator (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/presentation/components/PinIndicator.kt)
    - Filled circles for entered digits
    - Outline circles for remaining digits
    - Standard 4-digit PIN (Square/Toast standard)
    - Implicit animation on state change
  - LoginScreen.kt: Updated to use custom PIN pad (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/presentation/LoginScreen.kt:13-14,64-124)
    - Removed OutlinedTextField (no system keyboard)
    - Added PinIndicator for visual feedback (4 circles)
    - Added PinPad for input
    - Auto-submit on exactly 4 digits (Square/Toast standard)
    - Improved error display with Cards
    - 3 @Preview variants for testing
  - **WHY**: Square/Toast use custom PIN pads for better security, UX, and professionalism
  - **BENEFITS**:
    - 🔒 Security: No PIN visible in system keyboard (prevents shoulder surfing)
    - ⚡ Speed: Faster than typing on system keyboard (tap tap tap vs swipe-type-dismiss)
    - 🎨 Professional: Looks like real POS terminal (not generic app)
    - ♿ Accessibility: Large buttons for touch accuracy in fast-paced environments
    - 📱 Consistency: Same UI on all devices (iOS, Android, tablets)

- **Refresh Token Functionality** (2025-11-03) - World-class session management following Square POS pattern
  - Result.kt: Add ValidationError to ApiException (app/src/main/java/com/jaac/avoqado_tpv/core/domain/models/Result.kt:176-190)
    - New exception type for client-side validation errors
    - Used for terminal activation checks, missing required fields
    - Separates local validation from HTTP errors
  - SecureStorage.kt: Add refresh token storage methods (app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt:50,155-169,190)
    - saveRefreshToken() - Encrypts and stores refresh token
    - getRefreshToken() - Retrieves refresh token securely
    - clearSession() updated to remove refresh token on logout
  - ApiService.kt: Add refresh token endpoint (app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt:114-137)
    - POST /tpv/venues/{venueId}/auth/refresh
    - Exchanges refresh token for new access token
    - Extends session from 24h to 7-30 days (configurable)
  - AuthDto.kt: Add RefreshToken DTOs (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/data/dto/AuthDto.kt:78-91,108-110,165-171)
    - RefreshTokenRequestDto with serialNumber field
    - RefreshTokenResponseDto with new access token
    - Domain ↔ DTO mappers for type safety
  - AuthRepository.kt: Implement refreshAccessToken() (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/data/repository/AuthRepository.kt:137,219-286)
    - Silent token renewal without PIN re-entry
    - Handles expired refresh tokens by forcing re-login
    - Network error retry with exponential backoff
    - Clears session on refresh failure for security
  - **WHY**: Prevents users from re-entering PIN every 24 hours (Square allows 7 days, Toast allows 30 days)
  - **FLOW**: Access token expires → AuthInterceptor detects 401 → Call refreshAccessToken() → Save new token → Retry original request

- **Certificate Pinning** (2025-11-03) - MITM attack protection following Square/Toast security standards
  - NetworkModule.kt: Add CertificatePinner configuration (app/src/main/java/com/jaac/avoqado_tpv/core/di/NetworkModule.kt:12,27,59-128)
    - provideCertificatePinner() - Creates CertificatePinner for PRODUCTION builds only
    - Pins SHA256 hashes of api.avoqado.io certificates
    - Supports certificate rotation with multiple pins (primary + backup)
    - Disabled in DEBUG to allow dev/staging servers
    - Documentation on how to obtain certificate pins via openssl
  - ⚠️ TODO: Update placeholder SHA256 hashes before production deployment
  - **WHY**: Prevents man-in-the-middle attacks on public WiFi networks (required for PCI compliance)
  - **PATTERN**: Same as Square POS, Toast POS, Stripe SDK

### Added
- **Terminal Activation Validation on Login** (2025-01-03) - Security improvement to prevent login on deactivated terminals
  - AuthDto.kt: Add serialNumber to PinLoginRequestDto (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/data/dto/AuthDto.kt:16-19)
    - ⚠️ BREAKING CHANGE: Login requests now require serialNumber field
    - Maps to backend schema: `{ pin: string, serialNumber: string }`
    - Prevents login on terminals deactivated by admin
  - AuthModels.kt: Add serialNumber to PinLoginRequest domain model (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/domain/models/AuthModels.kt:15-18)
    - Terminal serial number retrieved from SecureStorage (set during activation)
    - Backend validates terminal activation status on every login
  - AuthRepository.kt: Get serialNumber from SecureStorage before login (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/data/repository/AuthRepository.kt:85-93)
    - Returns validation error if serialNumber missing (device not activated)
    - Passes serialNumber to backend API for activation validation
    - Backend checks: terminal exists + activatedAt is not null + status is ACTIVE
  - LoginViewModel.kt: Add TerminalNotActivated state (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/presentation/LoginViewModel.kt:72-85)
    - New sealed class state for deactivated terminals
    - Detects "TERMINAL_NOT_ACTIVATED" error from backend (case-insensitive)
    - Emits TerminalNotActivated state instead of generic error
  - LoginScreen.kt: Add navigation and UI for deactivated terminals (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/presentation/LoginScreen.kt:28,35-39,132-143)
    - New callback: onNavigateToActivation() for terminal deactivation flow
    - LaunchedEffect navigates to activation screen when TerminalNotActivated state detected
    - User-friendly message: "Este terminal ha sido desactivado. Solicita un nuevo código de activación al administrador."
    - Shows loading indicator while redirecting to activation
  - **WHY**: Prevents staff from logging in after admin manually deactivates a terminal (Square POS pattern)
  - **FLOW**: Admin deactivates terminal → User logs out → User tries to login → Backend rejects with TERMINAL_NOT_ACTIVATED → App navigates to activation screen

- HomeViewModel.kt: Add logout functionality (app/src/main/java/com/jaac/avoqado_tpv/core/presentation/viewmodels/HomeViewModel.kt)
  - Logout method calls AuthRepository.logout() to clear session
  - Integrates with AppNavigation to stop heartbeat and navigate to login
  - Hilt dependency injection (@HiltViewModel)
  - Timber logging for audit trail

- **Heartbeat System (Phase 1: Foundation)** - World-class device monitoring following Square/Toast/Shopify POS patterns
  - DeviceHealthMonitor.kt: Collect battery, storage, memory metrics (app/src/main/java/com/jaac/avoqado_tpv/core/util/DeviceHealthMonitor.kt)
    - Tracks battery level, charging status, device uptime
    - Monitors storage (GB) and memory (MB) availability
    - Detects critical health states (low battery + not charging, low storage, low memory)
  - NetworkMonitor.kt: Real-time network monitoring with Kotlin Flow (app/src/main/java/com/jaac/avoqado_tpv/core/util/NetworkMonitor.kt)
    - Flow-based network state updates (WiFi, Cellular, Ethernet, None)
    - Signal strength monitoring (0-4 scale)
    - Metered network detection
    - Adaptive heartbeat interval calculation (15s-120s based on battery/network)
  - Heartbeat.kt: Domain model for heartbeat data (app/src/main/java/com/jaac/avoqado_tpv/core/domain/models/Heartbeat.kt)
    - Terminal ID, timestamp, status, version
    - System health metrics (battery, storage, memory)
    - Network info (type, metered, signal strength)
    - Removes "AVQD-" prefix from serial number for backend compatibility
  - HeartbeatDto.kt: API DTOs with mappers (app/src/main/java/com/jaac/avoqado_tpv/core/data/network/dto/HeartbeatDto.kt)
    - Request/response DTOs for `/tpv/heartbeat` endpoint
    - Extension functions for domain ↔ DTO mapping
  - HeartbeatRepository.kt: Sends heartbeat to backend (app/src/main/java/com/jaac/avoqado_tpv/core/data/repository/HeartbeatRepository.kt)
    - Graceful error handling with Result pattern
    - Network failure retry logic via WorkManager
    - Offline-first design (terminal works without heartbeat)
  - HeartbeatWorker.kt: Background worker executing every 30s (app/src/main/java/com/jaac/avoqado_tpv/core/data/workers/HeartbeatWorker.kt)
    - Hilt dependency injection (@HiltWorker + @AssistedInject)
    - Safety checks (only runs if device activated AND user logged in)
    - Automatic retry with exponential backoff on failure
    - Collects all metrics and sends to backend
  - HeartbeatScheduler.kt: Lifecycle manager (app/src/main/java/com/jaac/avoqado_tpv/core/util/HeartbeatScheduler.kt)
    - start() - Enqueues periodic WorkManager task
    - stop() - Cancels heartbeat on logout
    - isRunning() - Status check
    - Network constraint (only runs when connected)
- CLAUDE.md: Add comprehensive changelog guidelines (CLAUDE.md:820)
  - Mandatory CHANGELOG.md updates for all code changes
  - Keep a Changelog format with strict entry structure
  - Rotation strategy when file exceeds 2000 lines
  - Integration with Git workflow (code + changelog in single commit)
  - AI usage instructions for automated tracking

### Fixed
- **Login Error Visibility: Fullscreen Overlay** (2025-11-03) - CRITICAL UX FIX: Error messages now impossible to miss
  - **PROBLEM**: Error Card was inside Column with `Arrangement.Center`, getting pushed off-screen or hidden
  - **SYMPTOM**: Backend sent error `{"message": "Staff member not found..."}`, logs showed it was parsed, but user saw NOTHING
  - **ROOT CAUSE**: Error Card buried in scrollable content, not prominent enough
  - **SOLUTION**: LoginScreen.kt: Show error as fullscreen overlay (like loading) (LoginScreen.kt:168-219)
    - Moved error Card from inline (line 131-160) to overlay (after loading overlay)
    - Black semi-transparent background (60% opacity) to focus attention
    - Large ⚠️ emoji icon for immediate recognition
    - Backend message displayed in large, centered text
    - Full-width "Reintentar" button (easy tap target)
    - Card takes 85% of screen width for readability
  - **PATTERN**: Square POS, Toast POS - Critical errors ALWAYS shown as modal overlays
  - **UX IMPROVEMENT**: User can't miss errors, can't accidentally tap buttons while error is showing
  - Added imports: `androidx.compose.foundation.background`, `androidx.compose.ui.graphics.Color`

- **Login Error Messages: Backend Integration** (2025-11-03) - CRITICAL FIX: Display actual backend error messages to users
  - **PROBLEM DISCOVERED (3 chained bugs):**
    1. ❌ AuthRepository ignored `response.errorBody()` - used hardcoded messages
    2. ❌ HttpError's `customUserMessage` couldn't be set - always generic based on HTTP code
    3. ❌ LoginViewModel used `.message` (technical) instead of `.userMessage` (user-friendly)
  - **IMPACT:** Users saw generic "PIN incorrecto" instead of backend's detailed "PIN incorrecto, 3 intentos restantes antes del bloqueo"
  - **FIXES IMPLEMENTED:**
    - Result.kt: Add `customUserMessage: String? = null` parameter to HttpError (Result.kt:118)
      - Allows backend message to override generic message
      - Falls back to generic message if backend doesn't provide one
      - User sees: backend message > fallback > generic (priority order)
    - AuthRepository.kt: Parse `response.errorBody()` to extract backend message (AuthRepository.kt:109-147)
      - Reads errorBody JSON
      - Tries multiple fields: "message" → "error" → "detail"
      - Passes backend message as `customUserMessage` to HttpError
      - Fallback messages still available if parse fails
      - Added JSONObject import for JSON parsing
    - LoginViewModel.kt: Use `.userMessage` instead of `.message` (LoginViewModel.kt:48-65)
      - `.userMessage` = user-friendly message (shown in UI)
      - `.message` = technical message (logged with Timber)
      - Terminal activation errors still check technical message for keywords
  - **EXAMPLES OF IMPROVED UX:**
    - Backend: `{"message": "PIN incorrecto. 3 intentos restantes"}` → User sees exact message
    - Backend: `{"error": "Rate limit: espera 5 minutos"}` → User sees exact message
    - Backend: No body → User sees fallback "PIN incorrecto. Intenta de nuevo."
  - **PATTERN**: Square POS, Toast POS - Always show backend messages when available

- **Splash Screen UX Bug** (2025-11-03) - Fixed "Home screen flashing before Login" issue
  - AppNavigation.kt: Replace WelcomeScreen with true SplashScreenContent (AppNavigation.kt:224-278)
    - **PROBLEM**: SplashScreen was showing full Home screen (WelcomeScreen) while checking auth
    - **SYMPTOM**: User saw "Home → Login" flash on app start (confusing UX)
    - **ROOT CAUSE**: Line 209 showed `WelcomeScreen()` (complete Home UI) instead of minimal splash
    - **SOLUTION**: Created dedicated SplashScreenContent composable
      - Minimal design: Logo + "Avoqado TPV" + CircularProgressIndicator
      - No buttons, no checklist, no distractions
      - Centered layout with MaterialTheme colors
    - **FLOW NOW**:
      - App starts → TRUE Splash (logo + loading) → Check auth → Navigate to Login/Home
      - NO MORE intermediate Home screen flash
    - **PATTERN**: Square POS / Toast POS - Clean, professional splash screen
  - Added Timber logging for better navigation debugging
  - Removed unused `var isCheckingActivation` variable

- **SecureStorage Corruption Handling** (2025-11-03) - Graceful degradation following Square POS pattern
  - SecureStorage.kt: Add corruption recovery logic (app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt:80-132)
    - Detects EncryptedSharedPreferences corruption (device key change, factory reset)
    - Deletes corrupted storage files automatically
    - Creates fresh encrypted storage instance
    - Prevents app crashes with SecurityException
    - Logs recovery attempt for debugging
  - createEncryptedPreferences() - Separated into method for reusability
  - **PROBLEM**: Device key changes (factory reset, OS upgrade) corrupt encrypted storage → App crashes
  - **SOLUTION**: Delete corrupted files + recreate → User re-logs in (better than crash)
  - **PATTERN**: Square POS uses same approach (graceful degradation > hard crashes)

- **AuthRepository Validation Error Handling** (2025-11-03) - Proper error categorization
  - AuthRepository.kt: Use ValidationError for serial number check (app/src/main/java/com/jaac/avoqado_tpv/features/authentication/data/repository/AuthRepository.kt:86-87)
    - Returns ValidationError instead of HttpError 400 for missing serial number
    - User-friendly message: "El dispositivo debe activarse primero..."
    - Separates client-side validation from server errors
  - ActivationViewModel.kt: Add ValidationError handling (app/src/main/java/com/jaac/avoqado_tpv/features/activation/presentation/ActivationViewModel.kt:193-194)
    - Exhaustive when expression for ApiException sealed class
    - Displays ValidationError.userMessage directly

- **ApplicationScope Memory Leak** (2025-11-03) - Proper coroutine scope cleanup
  - AvoqadoTPVApplication.kt: Add onTerminate() with scope cancellation (app/src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt:11,83-100)
    - Cancels applicationScope on app termination
    - Prevents coroutine leaks in tests/emulators
    - Note: onTerminate() not called on real devices (only emulators)
    - Production cleanup handled by ViewModel.onCleared() and process death

### Changed
- **Splash Screen Design: Professional Animations** (2025-11-03) - Upgraded to animated, branded splash experience
  - AppNavigation.kt: Add professional animations to SplashScreenContent (AppNavigation.kt:258-326)
    - **DESIGN IMPROVEMENTS**:
      - ✨ Logo scale animation: 0.5 → 1.0 scale with FastOutSlowInEasing (800ms)
      - ✨ Text fade-in animation: Sequential appearance after logo (400ms delay)
      - 🎨 Larger logo: 200.dp (was 120.dp) for better visibility
      - 🎨 Larger text: 35.sp bold (was headlineLarge) for professional look
      - 🎨 Better spacing: 32dp after logo, 48dp before loading (was 24dp/48dp)
      - 🎨 Light gray background: #F5F5F5 (was theme background) for softer appearance
    - **ANIMATION SEQUENCE**:
      - 0ms: Logo starts scaling up smoothly
      - 400ms: Text fades in elegantly
      - Total polish time: ~1200ms
    - **PATTERN**: Square POS, Toast POS - Polished, branded first impression
  - Added animation imports: AnimatedVisibility, animateFloatAsState, FastOutSlowInEasing, fadeIn, tween
  - Added kotlinx.coroutines.delay for animation sequencing

- **Splash Screen: Real Avoqado Logo** (2025-11-03) - Replaced placeholder icon with actual brand logo
  - isotipo.png: Added real Avoqado logo (avocado graphic) to drawable resources (app/src/main/res/drawable/isotipo.png)
    - Copied from AvoqadoPOS project
    - File size: 18KB PNG with transparency
    - Green avocado design with brown seed - recognizable brand identity
  - AppNavigation.kt: Replace Icon with Image component (AppNavigation.kt:293-299)
    - **BEFORE**: Icon(Icons.Default.Restaurant) - Generic placeholder
    - **AFTER**: Image(painterResource(R.drawable.isotipo)) - Real Avoqado brand
    - Removed Icon and Icons imports (no longer needed)
    - Added Image and painterResource imports
    - Added R import for drawable resource access
  - **VISUAL IMPACT**: Users now see actual Avoqado avocado logo with smooth scale animation on app launch

- **CLAUDE.md: Add comprehensive rate limiting documentation** (2025-11-03) - Environment-specific rate limits for DEV vs PROD
  - CLAUDE.md: New "Rate Limiting" section in Backend Integration (CLAUDE.md:708-794)
    - Production limits: 10 PIN login attempts / 15 min (brute force protection)
    - Development limits: 100 PIN login attempts / 1 min (rapid testing)
    - Backend configuration examples (TypeScript rate-limiter config)
    - Testing commands for validating rate limits
    - Action items for backend team (environment-based config, rate limit headers, logging)
  - **CONTEXT**: User reported 429 rate limit errors blocking development testing
  - **PROBLEM**: Backend production rate limits (10/15min) too strict for DEV environment
  - **SOLUTION**: Document recommended DEV limits (100/1min) with backend implementation examples
  - **PATTERN**: Square/Toast use higher DEV rate limits to prevent development friction
  - **ANDROID ERROR HANDLING**: Already updated in AuthRepository.kt:110-115 with helpful DEV message

- ApiService.kt: Add heartbeat endpoint (app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt:63-86)
  - POST /tpv/heartbeat (public, no auth required)
  - Accepts HeartbeatRequestDto, returns HeartbeatResponseDto
- AppNavigation.kt: Integrate heartbeat on login (app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt:192)
  - Call HeartbeatScheduler.start() after successful login
  - Add TODO comments for real login integration
  - Stop heartbeat on logout (future)
- AvoqadoTPVApplication.kt: Configure WorkManager with Hilt (app/src/main/java/com/jaac/avoqado_tpv/AvoqadoTPVApplication.kt:27,33-34,54-57)
  - Implement Configuration.Provider for custom WorkerFactory
  - Inject HiltWorkerFactory for dependency injection in Workers
  - Enable Workers to receive dependencies via @Inject constructor
- AndroidManifest.xml: Add network permissions and WorkManager setup (app/src/main/AndroidManifest.xml:6-7,31-41)
  - Add INTERNET and ACCESS_NETWORK_STATE permissions
  - Disable WorkManager auto-initialization (use custom Hilt config)
- build.gradle.kts: Add WorkManager dependencies (app/build.gradle.kts:158-161)
  - androidx.work:work-runtime-ktx:2.9.0
  - androidx.hilt:hilt-work:1.2.0 + KSP annotation processing
- CLAUDE.md: Update development workflow checklist (CLAUDE.md:773)
  - Add CHANGELOG.md mandatory checklist item
  - Include file size check for rotation
  - Require proper categorization (Added/Changed/Fixed/Removed/Security)

- **PIN Login System** - TPV authentication with rate limiting and secure token storage
  - AuthModels.kt: Domain models for authentication (features/authentication/domain/models/AuthModels.kt)
    - PinLoginRequest, AuthResponse, StaffMember, VenueInfo
    - StaffRole enum with 9 hierarchical roles (SUPERADMIN → VIEWER)
    - Matches backend authentication contract
  - AuthDto.kt: API DTOs with domain mappers (features/authentication/data/dto/AuthDto.kt)
    - PinLoginRequestDto, AuthResponseDto with @SerializedName annotations
    - Extension functions for domain ↔ DTO conversion
  - AuthRepository.kt: Authentication repository (features/authentication/data/repository/AuthRepository.kt)
    - loginWithPin() - Send PIN to backend, save tokens to SecureStorage
    - logout() - Clear session
    - isAuthenticated() - Check session state
    - hasPermission() - Permission validation
    - Graceful error handling with user-friendly messages
  - LoginViewModel.kt: Login state management (features/authentication/presentation/LoginViewModel.kt)
    - StateFlow for reactive UI updates
    - LoginState sealed class (Idle, Loading, Success, Error)
    - Hilt dependency injection (@HiltViewModel)
  - LoginScreen.kt: PIN entry UI (features/authentication/presentation/LoginScreen.kt)
    - Simple 4-6 digit PIN input field
    - Auto-submit when complete
    - Loading indicator and error messages
    - Material3 design with masked PIN display

### Changed
- WelcomeScreen.kt: Update Logout icon to AutoMirrored version (app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt:13,109)
  - Change from Icons.Filled.Logout to Icons.AutoMirrored.Filled.Logout
  - Fixes deprecation warning in Android build
  - Proper RTL (right-to-left) language support
- WelcomeScreen.kt: Add logout button to home screen (app/src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt:40,101-114)
  - Red error-colored button for destructive action
  - Logout icon with "Cerrar Sesión" text
  - onLogout callback parameter for navigation
  - Updated status text to reflect completed features
- AppNavigation.kt: Integrate logout flow with HeartbeatScheduler (app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt:118-136)
  - Stop heartbeat worker on logout
  - Clear session via HomeViewModel
  - Navigate to Login screen with proper backstack clearing
  - Prevents memory leaks and ensures clean state
- ApiService.kt: Fix PIN login endpoint path (app/src/main/java/com/jaac/avoqado_tpv/core/data/network/ApiService.kt:108-112)
  - Correct path: POST /tpv/venues/{venueId}/auth (not /login-pin)
  - Use DTOs instead of non-existent domain types
  - Add rate limiting documentation (10 attempts per 15 min)
- AppNavigation.kt: Replace login placeholder with real LoginScreen (core/presentation/navigation/AppNavigation.kt:97-115)
  - Integrate LoginScreen with venueId from activation
  - Start heartbeat after successful login
  - Navigate to home screen with proper backstack clearing
  - Remove placeholder LoginScreenPlaceholder composable
- DeviceInfoManager.kt: Add public getVenueId() method (core/util/DeviceInfoManager.kt:116-118)
  - Expose venueId from SecureStorage for navigation and tenant isolation
  - Used by AppNavigation to pass venueId to LoginScreen
  - Maintains encapsulation by providing controlled access to private secureStorage

### Security
- **Terminal Activation Enforcement** - Devices cannot login after manual deactivation
  - Prevents reuse of deactivated terminals (admin can remotely disable lost/stolen devices)
  - Logs warning when login attempted on non-activated terminal
  - Forces re-activation flow through admin dashboard
  - Backend validation: Check terminal.activatedAt is not null AND status is ACTIVE
  - Android app returns validation error if serialNumber missing from SecureStorage
  - User-friendly error redirects to activation screen with clear instructions

### Fixed
- SecureStorage.kt: Fix logout clearing venueId (critical bug) (app/src/main/java/com/jaac/avoqado_tpv/core/data/local/SecureStorage.kt:131-140)
  - venueId is now preserved during logout (device activation data)
  - Only user session data cleared (token, staffId, name, permissions)
  - Prevents 404 error on re-login: POST /tpv/venues//auth → /tpv/venues/{venueId}/auth
  - Device remains activated to venue across staff member logouts
  - Matches Square POS pattern (terminal activation persists)
  - Bug: After logout, venueId was null causing empty URL path
- AndroidManifest.xml: Remove CoreComponentFactory causing API level warning (app/src/main/AndroidManifest.xml:9-10)
  - Removed explicit android:appComponentFactory declaration
  - AndroidX handles CoreComponentFactory automatically
  - Fixes warning: "requires API level 28 (current min is 27)"

## [1.0.0] - 2025-01-30

### Added
- Initial project setup with Clean Architecture structure
  - Presentation layer: Jetpack Compose + ViewModels + StateFlow
  - Domain layer: UseCases + Repository interfaces
  - Data layer: Repository implementations + API/Database/SDK sources
- Hilt dependency injection configuration (Hilt 2.57)
  - @HiltAndroidApp in AvoqadoTPVApplication
  - NetworkModule, DatabaseModule, RepositoryModule
  - @HiltViewModel injection for all ViewModels
- Blumon PAX SDK integration (blumon-pay-android-2.1.3.aar)
  - NDK configuration: armeabi ABI filter
  - Payment processing flow with credential caching
  - Event listeners for PIN dialog, card removal, transaction states
- Feature modules structure
  - authorization: PIN authentication + biometric (future)
  - payment: Blumon PAX integration + backend sync
  - management: Table/order management
  - menu: Product catalog
  - cart: Shopping cart
  - timeclock: Shift management
- Jetpack Compose UI (100% Compose, no XML)
  - MainActivity with Compose navigation
  - PaymentScreen with composable components:
    - AmountInput.kt: Amount entry with decimal support
    - CardReaderAnimation.kt: Animated card reading indicator
    - PaymentStateIndicator.kt: Transaction state display
    - PaymentSuccessContent.kt: Success confirmation UI
    - PaymentErrorContent.kt: Error handling UI
  - Material3 theming with semantic colors
- Security infrastructure
  - EncryptedSharedPreferences for credential storage
  - Certificate pinning configuration (NetworkModule)
  - Tenant isolation (venueId filtering)
- Backend integration
  - REST API: Retrofit + OkHttp
  - Real-time: Socket.IO with room-based events
  - Base URLs: Production (api.avoqado.io) + Dev (ngrok)
- Development documentation
  - CLAUDE.md: Complete development context and standards
  - GREENFIELD_BLUEPRINT.md: 28-day implementation plan
  - Anti-hallucination protocol with best practices enforcement
  - Orphaned files prevention strategy with lint configuration

### Security
- Encrypted credential storage using EncryptedSharedPreferences (AES256-GCM)
- Certificate pinning for api.avoqado.io (NetworkModule.kt)
- No hardcoded secrets (using environment variables)
- Tenant isolation enforced in all repository queries

[Unreleased]: https://github.com/yourusername/avoqado-tpv/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/yourusername/avoqado-tpv/releases/tag/v1.0.0