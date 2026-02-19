package com.jaac.avoqado_tpv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.receivers.AppUpdateReceiver
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.presentation.navigation.AppNavigation
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
import com.jaac.avoqado_tpv.features.remote_command.presentation.LockScreenOverlay
import com.jaac.avoqado_tpv.features.remote_command.presentation.MaintenanceOverlay
import com.jaac.avoqado_tpv.features.verification.presentation.components.ACTION_CAPTURE_PHOTO
import com.jaac.avoqado_tpv.features.verification.presentation.components.CameraState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Activity principal de Avoqado TPV
 *
 * Punto de entrada de la aplicación.
 * Configura el sistema de navegación con validación de activación.
 *
 * Flujo de navegación:
 * 1. Splash → Verifica activación del dispositivo
 * 2. Activation → Si no está activado, solicita código de 6 caracteres
 * 3. Login → Si está activado, solicita PIN de autenticación
 * 4. Home → Dashboard principal después de login exitoso
 *
 * Similar a Square POS activation flow.
 *
 * **Heartbeat Pattern (Square/Toast):**
 * - Heartbeat starts on app launch (if activated), NOT on login
 * - Heartbeat runs independently of login state
 * - Allows backend to monitor terminal health even when no user is logged in
 * - Prevents deadlock: user can login even if terminal is INACTIVE
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var deviceInfoManager: DeviceInfoManager

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var sessionManager: com.jaac.avoqado_tpv.core.session.SessionManager

    @Inject
    lateinit var terminalConfigRepository: TerminalConfigRepository

    @Inject
    lateinit var merchantRepository: MerchantRepository

    @Inject
    lateinit var tpvSettingsRepository: TpvSettingsRepository

    @Inject
    lateinit var lockScreenManager: LockScreenManager

    @Inject
    lateinit var maintenanceManager: MaintenanceManager

    @Inject
    lateinit var bluetoothPaymentService: com.jaac.avoqado_tpv.core.bluetooth.BluetoothPaymentService

    /**
     * State to track permission status
     * - null: Checking permission
     * - true: Permission granted, app can proceed
     * - false: Permission denied, show error screen
     */
    private var permissionGranted = mutableStateOf<Boolean?>(null)

    /**
     * Launcher for camera + location permissions (requested on startup)
     * If granted here, CameraPreviewScreen won't ask again.
     * If denied, CameraPreviewScreen will re-request when user opens camera.
     */
    private val cameraLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        Timber.d("📸 Startup permissions - Camera: $cameraGranted, Location: $locationGranted")
    }

    /**
     * Launcher for READ_PHONE_STATE permission request
     *
     * **CRITICAL REQUIREMENT:**
     * This permission is MANDATORY on Android 8+ to access Build.getSerial() for hardware serial.
     * NO fallback to ANDROID_ID - app REQUIRES hardware serial for terminal identification.
     *
     * **Why hardware serial is mandatory:**
     * - Professional POS systems (Square, Toast, Clover) ALWAYS use hardware serial
     * - Hardware serial persists forever (app reinstall, factory reset, OS updates)
     * - ANDROID_ID changes on app reinstall → breaks terminal identification
     * - Backend relies on consistent serial number for terminal management
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.i("✅ READ_PHONE_STATE permission granted - hardware serial: ${deviceInfoManager.getSerialNumber()}")
            permissionGranted.value = true

            // Request camera + location after phone state is resolved (avoid competing dialogs)
            requestCameraLocationPermissions()

            // 🐛 FIX: Start initialization AFTER permission is granted (not in onCreate)
            // On fresh install, permission callback fires AFTER onCreate() completes,
            // so we need to trigger heartbeat + config fetch + BLE server restore here too.
            lifecycleScope.launch(Dispatchers.IO) {
                startHeartbeatIfActivated()
                fetchTerminalConfigIfActivated()
                // TEMPORARILY DISABLED: BLE server restore - using API + WebSockets instead
                // Will re-enable when BLE functionality is needed again
                // restoreBleServerIfPreviouslyRunning()
            }
        } else {
            Timber.e("❌ READ_PHONE_STATE permission DENIED - app cannot function without hardware serial")
            permissionGranted.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: content draws behind system bars (status bar, nav bar)
        // This allows ModalBottomSheet scrim to cover the entire screen seamlessly
        enableEdgeToEdge()
        applyTutorialImmersiveNavigation()

        // Check/Request READ_PHONE_STATE permission (MANDATORY on Android 8+)
        checkAndRequestPhoneStatePermission()

        setContent {
            // Theme preference: persisted per-device, not per-session
            var isDarkTheme by remember { mutableStateOf(secureStorage.getIsDarkMode()) }

            AvoqadoTheme(darkTheme = isDarkTheme) {
                val permissionStatus by permissionGranted

                // 🔒 ROOT-LEVEL Lock/Maintenance state - covers ALL screens
                val isLocked by lockScreenManager.isLocked.collectAsStateWithLifecycle()
                val lockReason by lockScreenManager.lockReason.collectAsStateWithLifecycle()
                val lockMessage by lockScreenManager.lockMessage.collectAsStateWithLifecycle()
                val lockedBy by lockScreenManager.lockedBy.collectAsStateWithLifecycle()

                val isInMaintenance by maintenanceManager.isInMaintenance.collectAsStateWithLifecycle()
                val maintenanceReason by maintenanceManager.maintenanceReason.collectAsStateWithLifecycle()
                val maintenanceInitiatedBy by maintenanceManager.initiatedBy.collectAsStateWithLifecycle()

                // Use Box to layer overlays ABOVE all navigation content
                Box(modifier = Modifier.fillMaxSize()) {
                    when (permissionStatus) {
                        true -> {
                            // Permission granted → Show normal app
                            AppNavigation(
                                deviceInfoManager = deviceInfoManager,
                                secureStorage = secureStorage,
                                sessionManager = sessionManager,
                                isDarkMode = isDarkTheme,
                                onThemeToggle = {
                                    isDarkTheme = !isDarkTheme
                                    secureStorage.saveIsDarkMode(isDarkTheme)
                                }
                            )
                        }
                        false -> {
                            // Permission denied → Show error screen
                            PermissionDeniedScreen(
                                onOpenSettings = { openAppSettings() },
                                onRequestAgain = { checkAndRequestPhoneStatePermission() }
                            )
                        }
                        null -> {
                            // Checking permission → Show loading
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    // 🛠️ Maintenance Overlay - ROOT LEVEL (covers ALL screens)
                    // Shows when admin sends MAINTENANCE_MODE command
                    // Staff can exit locally via button
                    MaintenanceOverlay(
                        visible = isInMaintenance,
                        maintenanceReason = maintenanceReason,
                        initiatedBy = maintenanceInitiatedBy,
                        onExitMaintenance = { maintenanceManager.exitMaintenance() }
                    )

                    // 🔒 Lock Screen Overlay - ROOT LEVEL (covers ALL screens)
                    // Shows when admin sends LOCK command
                    // Can ONLY be unlocked via remote UNLOCK command
                    LockScreenOverlay(
                        visible = isLocked,
                        lockReason = lockReason,
                        lockMessage = lockMessage,
                        lockedBy = lockedBy
                    )
                }
            }
        }

        // ✅ Square/Toast Pattern: Start heartbeat if terminal is activated
        // This runs when permission was ALREADY granted (returning user)
        // For fresh installs, the permission callback handles this (see requestPermissionLauncher)
        if (permissionGranted.value == true) {
            // 🚀 Performance Optimization:
            // Move heavy initialization (SecureStorage disk reads + Network) to IO thread.
            // This prevents "Skipped frames" and ANRs during app startup.
            // Previously caused ~1.2s Main Thread block.
            lifecycleScope.launch(Dispatchers.IO) {
                startHeartbeatIfActivated()
                fetchTerminalConfigIfActivated()
                // TEMPORARILY DISABLED: BLE server restore - using API + WebSockets instead
                // Will re-enable when BLE functionality is needed again
                // restoreBleServerIfPreviouslyRunning()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyTutorialImmersiveNavigation()
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateReceiver.isActivityResumed = true
        applyTutorialImmersiveNavigation()
    }

    override fun onPause() {
        super.onPause()
        AppUpdateReceiver.isActivityResumed = false
    }

    /**
     * Tutorial emulator UX: hide 3-button nav bar so screenshots match PAX hardware.
     * Only applied when PAX SDK is disabled (tutorialEmu flavor).
     */
    private fun applyTutorialImmersiveNavigation() {
        if (BuildConfig.ENABLE_PAX_SDK) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Some transient surfaces (dialogs/sheets) can request bars again;
        // posting a second hide keeps tutorial screenshots clean.
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Intercept VOLUME_UP button for multiple actions:
     *
     * **Priority 1: Camera Photo Capture**
     * - When camera preview is active (CameraState.isActive = true)
     * - Broadcasts ACTION_CAPTURE_PHOTO for selfie capture
     * - Used for clock-in/out photo verification
     *
     * **Priority 2: Barcode Scanner (Square POS pattern)**
     * - When camera is NOT active
     * - Broadcasts OPEN_BARCODE_SCANNER for "Scan & Go" mode
     *
     * **Android Hardware Button Handling:**
     * - VOLUME_UP and VOLUME_DOWN can be intercepted (documented in Android Developers)
     * - Only works when app is in foreground
     * - Returning true consumes the event (prevents volume change)
     *
     * **PAX Terminal Compatibility:**
     * - PAX terminals use standard Android KeyEvent API
     * - No special PAX SDK required for volume buttons
     * - Works on PAX A910S and all PAX Android terminals
     *
     * **Limitations:**
     * - Only works when app is in foreground
     * - Cannot intercept HOME or POWER buttons (system-level)
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            // Priority 1: If camera is active, trigger photo capture
            if (CameraState.isActive) {
                Timber.d("📸 [MainActivity] VOLUME_UP pressed - camera active, broadcasting capture intent")
                val intent = Intent(ACTION_CAPTURE_PHOTO)
                sendBroadcast(intent)
                return true  // Consume event (don't change volume)
            }

            // Priority 2: Open barcode scanner (default behavior)
            Timber.d("🔊 [MainActivity] VOLUME_UP pressed - broadcasting scanner intent")
            val intent = Intent("com.jaac.avoqado_tpv.OPEN_BARCODE_SCANNER")
            sendBroadcast(intent)

            return true  // Consume event (don't change volume)
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * Check and request READ_PHONE_STATE permission (MANDATORY on Android 8+)
     *
     * **Why this permission is MANDATORY:**
     * - Required for Build.getSerial() to access hardware serial number
     * - Hardware serial persists forever (unlike ANDROID_ID which changes on reinstall)
     * - Professional POS systems (Square, Toast, Clover) ALWAYS use hardware serial for terminal identification
     * - NO fallback to ANDROID_ID - app cannot function without hardware serial
     *
     * **User experience:**
     * - Permission requested on app launch
     * - If denied: Show error screen with explanation and "Open Settings" button
     * - App blocks all functionality until permission is granted
     */
    private fun checkAndRequestPhoneStatePermission() {
        // Only required on Android 8+ (API 26+) where Build.getSerial() requires permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Timber.d("✅ READ_PHONE_STATE permission already granted")
                    permissionGranted.value = true
                    // Request camera + location now that phone state is resolved
                    requestCameraLocationPermissions()
                }
                else -> {
                    Timber.d("📱 Requesting READ_PHONE_STATE permission for hardware serial (MANDATORY)")
                    requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            }
        } else {
            // Android 7 and below: Build.SERIAL does not require permission
            Timber.d("📱 Android 7 or below - Build.SERIAL does not require permission")
            permissionGranted.value = true
        }
    }

    /**
     * Request camera + location permissions on startup (non-blocking)
     *
     * These are needed for proof-of-sale photos and verification.
     * If already granted → no dialog shown.
     * If denied → CameraPreviewScreen will ask again when user actually opens camera.
     */
    private fun requestCameraLocationPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            Timber.d("📸 Requesting camera + location permissions on startup: $permissionsToRequest")
            cameraLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Timber.d("📸 Camera + location permissions already granted")
        }
    }

    /**
     * Open app settings so user can manually grant permission
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    /**
     * Start heartbeat monitoring if terminal is activated
     *
     * **Design Pattern (Square/Toast):**
     * - Heartbeat runs whenever app is open AND terminal is activated
     * - Does NOT depend on login state
     * - Enables backend to track terminal online/offline status
     * - User can still login even if terminal was marked INACTIVE
     *
     * **Why here instead of in login?**
     * - Prevents deadlock: Terminal can be INACTIVE after logout, but login should still work
     * - Matches industry pattern: Square/Toast start heartbeat on app launch, not login
     * - Allows backend visibility into terminals even when no user is logged in
     */
    private fun startHeartbeatIfActivated() {
        val isActivated = secureStorage.isTerminalActivated()

        if (isActivated) {
            Timber.d("🟢 Terminal is activated - starting heartbeat scheduler")
            HeartbeatScheduler.start(applicationContext)
        } else {
            Timber.d("🔴 Terminal not activated - heartbeat will not start until activation")
        }
    }

    /**
     * Fetch terminal configuration from backend if activated
     *
     * **Phase 2: Dynamic Multi-Merchant Configuration**
     * - Fetches merchant accounts assigned to this terminal by superadmin
     * - Replaces hardcoded SANDBOX_ACCOUNT_A/B with backend-managed config
     * - Enables multi-merchant routing (Account A for main restaurant, Account B for bar)
     *
     * **Design Pattern (Square/Toast):**
     * - Configuration loaded on app startup (NOT on login)
     * - Updates MerchantRepository with fetched merchants
     * - Allows PaymentViewModel to show merchant selection dialog
     *
     * **Flow:**
     * 1. Get device serial number (e.g., "AVQD-2841548417")
     * 2. Call GET /tpv/terminals/:serial/config
     * 3. Update MerchantRepository with fetched merchants
     * 4. PaymentViewModel reactively updates via getMerchantsUseCase
     *
     * **Error Handling:**
     * - Silently fails with log warning (doesn't block app startup)
     * - Falls back to hardcoded sandbox accounts if backend unreachable
     * - User can still process payments with fallback accounts
     */
    private suspend fun fetchTerminalConfigIfActivated() {
        // Runs on Dispatchers.IO (called from onCreate)
        var isActivated = secureStorage.isTerminalActivated()

        // 🐛 FIX: On fresh install, local cache is empty but terminal may still be activated.
        // Check backend activation status to handle reinstall/cache clear scenarios.
        if (!isActivated) {
            Timber.d("🔍 Local activation cache empty - checking backend...")

            when (val result = deviceInfoManager.checkActivationStatusWithBackend()) {
                is Result.Success -> {
                    if (result.data.isActivated) {
                        Timber.i("✅ Backend confirms terminal is activated - proceeding with config fetch")
                        isActivated = true
                    } else {
                        Timber.d("🔴 Backend confirms terminal not activated - skipping config fetch")
                        return
                    }
                }
                is Result.Error -> {
                    Timber.w("⚠️ Could not verify activation with backend - skipping config fetch")
                    return
                }
            }
        }

        // Get device serial number (e.g., "AVQD-2841548417")
        val serialNumber = deviceInfoManager.getSerialNumber()

        Timber.d("🔧 [TerminalConfig] Fetching config from backend for serial: $serialNumber")

        val configResult = terminalConfigRepository.fetchConfig(serialNumber)

        configResult.onSuccess { (terminalInfo, merchantAccounts) ->
            Timber.i("✅ [TerminalConfig] Fetched ${merchantAccounts.size} merchant accounts")
            Timber.d("   📋 Terminal: ${terminalInfo.brand} ${terminalInfo.model}")
            Timber.d("   🏢 Venue: ${terminalInfo.venueName}")
            Timber.d("   🏷️ VenueType: ${terminalInfo.venueType ?: "N/A"}")

            // Replace MerchantRepository fallback accounts with fetched merchants from backend
            merchantRepository.updateMerchants(merchantAccounts)

            // Save venue type for conditional UI (table service visibility)
            secureStorage.saveVenueType(terminalInfo.venueType)

            Timber.i("✅ [TerminalConfig] Successfully loaded dynamic config from backend")
        }.onFailure { error ->
            // Silently fail with log warning (doesn't block app startup)
            Timber.w(
                error,
                "⚠️ [TerminalConfig] Failed to fetch config - using fallback accounts"
            )
            Timber.d("   ℹ️  This is normal if backend is unreachable")
            Timber.d("   ℹ️  App will use hardcoded sandbox accounts as fallback")
        }

        // 🔧 FIX: Also refresh TPV settings from backend (includes enableShifts)
        // This ensures settings are synced on app startup, not just after login
        // Note: This is called even if terminalConfig failed, as tpvSettings has its own endpoint
        if (configResult.isSuccess) {
            tpvSettingsRepository.refreshFromTerminalConfig(serialNumber)
                .onSuccess { settings ->
                    Timber.i("✅ [TpvSettings] Synced from backend: enableShifts=${settings.enableShifts}")
                }
                .onFailure { error ->
                    Timber.w(error, "⚠️ [TpvSettings] Failed to sync - using cached settings")
                }
        }
    }

    /**
     * Restore BLE Payment Server if it was running before app close (Opción 2)
     *
     * **Design Pattern (Square/Toast):**
     * - Server state persists across app restarts
     * - If server was running before, auto-restore on next launch
     * - Provides seamless UX without manual re-activation
     * - User decides if server is always active via toggle in Settings
     *
     * **Flow:**
     * 1. Check if server was running before (from SecureStorage)
     * 2. If yes, restart server automatically
     * 3. Server continues accepting payments from external devices (iPad, tablets)
     *
     * **Performance:**
     * - Runs on IO thread to avoid blocking app startup
     * - Silently fails if Bluetooth permissions are not granted
     * - Does not block app functionality if restore fails
     */
    private suspend fun restoreBleServerIfPreviouslyRunning() {
        // Runs on Dispatchers.IO (called from onCreate)
        try {
            // CRITICAL: Set Bluetooth adapter name BEFORE any BLE operations
            // This fixes the "null" name in pairing dialogs (Android 12/13 bug)
            // See: https://issuetracker.google.com/issues/240485116
            setBluetoothAdapterName()

            // Switch to Main thread for BluetoothPaymentService (needs Context)
            withContext(Dispatchers.Main) {
                bluetoothPaymentService.tryRestoreState(applicationContext) { request ->
                    Timber.i("💰 [MainActivity] BLE Payment received: ${request.amountCents} cents (auto-restored server)")
                    // Payment events are also published via SharedFlow for AppNavigation to handle
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [MainActivity] Failed to restore BLE server state")
            // Silently fail - doesn't block app startup
        }
    }

    /**
     * Set Bluetooth adapter name to "Avoqado-TPV"
     *
     * CRITICAL: This must be called BEFORE any BLE GATT server operations.
     * Android 12/13 has a known bug where the device name appears as "null"
     * in pairing dialogs if not set early enough.
     *
     * Reference: https://issuetracker.google.com/issues/240485116
     */
    private fun setBluetoothAdapterName() {
        // Check BLUETOOTH_CONNECT permission first (required on Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                Timber.w("⚠️ [MainActivity] BLUETOOTH_CONNECT permission not granted - cannot set name")
                return
            }
        }

        try {
            val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val bluetoothAdapter = bluetoothManager?.adapter

            if (bluetoothAdapter == null) {
                Timber.w("⚠️ [MainActivity] Bluetooth adapter not available")
                return
            }

            if (!bluetoothAdapter.isEnabled) {
                Timber.w("⚠️ [MainActivity] Bluetooth is disabled - cannot set name")
                return
            }

            val currentName = bluetoothAdapter.name
            Timber.i("📱 [MainActivity] Current Bluetooth adapter name: '$currentName'")

            if (currentName != "Avoqado-TPV") {
                val success = bluetoothAdapter.setName("Avoqado-TPV")
                val newName = bluetoothAdapter.name
                Timber.i("📱 [MainActivity] setName('Avoqado-TPV') success=$success, new name='$newName'")
            } else {
                Timber.i("📱 [MainActivity] Bluetooth name already set to 'Avoqado-TPV'")
            }
        } catch (e: SecurityException) {
            Timber.e(e, "❌ [MainActivity] SecurityException setting Bluetooth name - need BLUETOOTH_CONNECT permission")
        } catch (e: Exception) {
            Timber.e(e, "❌ [MainActivity] Failed to set Bluetooth adapter name")
        }
    }
}

/**
 * Screen shown when READ_PHONE_STATE permission is denied
 *
 * Explains why the permission is critical and provides actions:
 * - Open Settings: Direct link to app settings where user can grant permission
 * - Request Again: Trigger permission dialog again
 */
@Composable
private fun PermissionDeniedScreen(
    onOpenSettings: () -> Unit,
    onRequestAgain: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠️",
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Permiso Requerido",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Avoqado TPV requiere acceso al número de serie del dispositivo para identificar esta terminal.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Card(
                modifier = Modifier.padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "¿Por qué es necesario?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Text(
                        text = "• El número de serie identifica esta terminal de forma única\n" +
                                "• Persiste incluso después de reinstalar la aplicación\n" +
                                "• Es requerido para activación y procesamiento de pagos\n" +
                                "• Sistemas POS profesionales (Square, Toast) usan este método",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Abrir Configuración")
            }

            OutlinedButton(
                onClick = onRequestAgain,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Solicitar Nuevamente")
            }
        }
    }
}
