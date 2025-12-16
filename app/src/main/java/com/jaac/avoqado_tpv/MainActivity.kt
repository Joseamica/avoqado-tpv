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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.domain.repository.TerminalConfigRepository
import com.jaac.avoqado_tpv.core.presentation.navigation.AppNavigation
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import com.jaac.avoqado_tpv.features.payment.domain.repository.MerchantRepository
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

    /**
     * State to track permission status
     * - null: Checking permission
     * - true: Permission granted, app can proceed
     * - false: Permission denied, show error screen
     */
    private var permissionGranted = mutableStateOf<Boolean?>(null)

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

            // 🐛 FIX: Start initialization AFTER permission is granted (not in onCreate)
            // On fresh install, permission callback fires AFTER onCreate() completes,
            // so we need to trigger heartbeat + config fetch here too.
            lifecycleScope.launch(Dispatchers.IO) {
                startHeartbeatIfActivated()
                fetchTerminalConfigIfActivated()
            }
        } else {
            Timber.e("❌ READ_PHONE_STATE permission DENIED - app cannot function without hardware serial")
            permissionGranted.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check/Request READ_PHONE_STATE permission (MANDATORY on Android 8+)
        checkAndRequestPhoneStatePermission()

        setContent {
            AvoqadoTheme {
                val permissionStatus by permissionGranted

                when (permissionStatus) {
                    true -> {
                        // Permission granted → Show normal app
                        AppNavigation(
                            deviceInfoManager = deviceInfoManager,
                            secureStorage = secureStorage,
                            sessionManager = sessionManager
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
            }
        }
    }

    /**
     * Intercept VOLUME_UP button to open barcode scanner (Square POS pattern)
     * ✅ BARCODE QUICK ADD: Hardware button shortcut for "Scan & Go" mode
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
     * **Flow:**
     * 1. User presses VOLUME+ while on MenuScreen with active order
     * 2. Broadcast intent "com.jaac.avoqado_tpv.OPEN_BARCODE_SCANNER"
     * 3. MenuViewModel receives broadcast and opens BarcodeQuickAddScreen
     *
     * **Limitations:**
     * - Only works when app is in foreground
     * - Cannot intercept HOME or POWER buttons (system-level)
     * - MenuViewModel must register BroadcastReceiver to listen for intent
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            Timber.d("🔊 [MainActivity] VOLUME_UP pressed - broadcasting scanner intent")

            // Broadcast intent for MenuViewModel to open barcode scanner
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

        terminalConfigRepository.fetchConfig(serialNumber)
            .onSuccess { (terminalInfo, merchantAccounts) ->
                Timber.i("✅ [TerminalConfig] Fetched ${merchantAccounts.size} merchant accounts")
                Timber.d("   📋 Terminal: ${terminalInfo.brand} ${terminalInfo.model}")
                Timber.d("   🏢 Venue: ${terminalInfo.venueName}")
                Timber.d("   🏷️ VenueType: ${terminalInfo.venueType ?: "N/A"}")

                // Replace MerchantRepository fallback accounts with fetched merchants from backend
                merchantRepository.updateMerchants(merchantAccounts)

                // Save venue type for conditional UI (table service visibility)
                secureStorage.saveVenueType(terminalInfo.venueType)

                Timber.i("✅ [TerminalConfig] Successfully loaded dynamic config from backend")
            }
            .onFailure { error ->
                // Silently fail with log warning (doesn't block app startup)
                Timber.w(
                    error,
                    "⚠️ [TerminalConfig] Failed to fetch config - using fallback accounts"
                )
                Timber.d("   ℹ️  This is normal if backend is unreachable")
                Timber.d("   ℹ️  App will use hardcoded sandbox accounts as fallback")
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