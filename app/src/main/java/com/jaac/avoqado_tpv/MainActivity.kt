package com.jaac.avoqado_tpv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.presentation.navigation.AppNavigation
import com.jaac.avoqado_tpv.core.presentation.theme.AvoqadoTheme
import com.jaac.avoqado_tpv.core.util.DeviceInfoManager
import com.jaac.avoqado_tpv.core.util.HeartbeatScheduler
import dagger.hilt.android.AndroidEntryPoint
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

    /**
     * Launcher for READ_PHONE_STATE permission request
     *
     * This permission is required on Android 8+ to access Build.getSerial() for hardware serial.
     * If denied, the app gracefully falls back to ANDROID_ID.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.i("✅ READ_PHONE_STATE permission granted - hardware serial will be used")
        } else {
            Timber.w("⚠️ READ_PHONE_STATE permission denied - falling back to ANDROID_ID")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request READ_PHONE_STATE permission if needed (Android 8+)
        requestPhoneStatePermissionIfNeeded()

        setContent {
            AvoqadoTheme {
                AppNavigation(
                    deviceInfoManager = deviceInfoManager,
                    secureStorage = secureStorage
                )
            }
        }

        // ✅ Square/Toast Pattern: Start heartbeat if terminal is activated
        // This runs BEFORE user logs in, enabling health monitoring
        startHeartbeatIfActivated()
    }

    /**
     * Request READ_PHONE_STATE permission if needed (Android 8+)
     *
     * **Why this permission:**
     * - Required for Build.getSerial() to access hardware serial number
     * - Hardware serial persists forever (unlike ANDROID_ID which changes on reinstall)
     * - Professional POS systems (Square, Toast, Clover) use hardware serial for terminal identification
     *
     * **Graceful degradation:**
     * - If permission granted: Uses hardware serial (AVQD-2841548417)
     * - If permission denied: Falls back to ANDROID_ID (AVQD-6D52CB5103BB42DC)
     *
     * **User experience:**
     * - Permission requested silently on app launch
     * - No blocking dialog for critical functionality
     * - App works with or without permission
     */
    private fun requestPhoneStatePermissionIfNeeded() {
        // Only request on Android 8+ (API 26+) where Build.getSerial() requires permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Timber.d("✅ READ_PHONE_STATE permission already granted")
                }
                else -> {
                    Timber.d("📱 Requesting READ_PHONE_STATE permission for hardware serial")
                    requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            }
        } else {
            Timber.d("📱 Android 7 or below - Build.SERIAL does not require permission")
        }
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
}