package com.jaac.avoqado_tpv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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