package com.jaac.avoqado_tpv

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.blumonpay.pax.utils.AppManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class principal para Avoqado TPV
 *
 * Responsabilidades:
 * - Inicialización de Hilt para inyección de dependencias
 * - Configuración de WorkManager con Hilt (para HeartbeatWorker)
 * - Inicialización del SDK BlumonPay
 * - Configuración de Timber para logging
 * - Optimización de startup (< 2 segundos)
 */
@HiltAndroidApp
class AvoqadoTPVApplication : Application(), Configuration.Provider, CameraXConfig.Provider {

    // Application-wide coroutine scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Hilt-provided WorkerFactory for dependency injection in Workers
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // ✅ Initialize critical components only (startup optimization)
        initializeTimber()

        // ⚠️ Defer non-critical initialization to background
        applicationScope.launch {
            initializeNonCritical()
        }
    }

    /**
     * Provide WorkManager configuration with Hilt support
     *
     * This allows Workers to receive dependencies via @Inject constructor.
     * Required for HeartbeatWorker to get DeviceInfoManager, NetworkMonitor, etc.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Provide CameraX configuration optimized for PAX devices
     *
     * ⚠️ CRITICAL FIX: PAX A910S only has ONE camera (back camera, id=0).
     * CameraX by default validates that both front and back cameras exist,
     * which causes 6-8 second delays due to retry loops.
     *
     * This configuration:
     * 1. Uses Camera2 implementation
     * 2. Limits available cameras to BACK camera only (skips front camera validation)
     * 3. Reduces camera initialization from ~8 seconds to ~1 second
     */
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .setMinimumLoggingLevel(android.util.Log.ERROR)  // Reduce log spam
            .build()
    }

    /**
     * Initialize Timber logging
     *
     * - DEBUG: DebugTree (full logcat output)
     * - RELEASE: CrashReportingTree (W/E → Firebase Crashlytics as non-fatal exceptions)
     *
     * This ensures ALL existing Timber.e() calls throughout the codebase
     * (payment errors, Blumon failures, network issues, etc.) are automatically
     * captured in Crashlytics Console for production debugging.
     */
    private fun initializeTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("🚀 Avoqado TPV initialized in DEBUG mode")
        } else {
            Timber.plant(com.jaac.avoqado_tpv.core.observability.CrashReportingTree())
        }
    }

    /**
     * Initialize non-critical components in background thread
     * This prevents blocking the main thread during app startup
     */
    private suspend fun initializeNonCritical() = withContext(Dispatchers.IO) {
        try {
            // Initialize Blumon PAX SDK
            AppManager.init(this@AvoqadoTPVApplication)
            Timber.d("✅ Blumon PAX SDK initialized")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error initializing Blumon PAX SDK")
        }
    }

    /**
     * Cleanup resources on app termination
     *
     * ⚠️ Note: onTerminate() is NOT called on real devices (only in emulators for testing).
     * For production cleanup, rely on:
     * - ViewModel.onCleared() for scoped cleanup
     * - Process death handling for app-wide cleanup
     *
     * This method exists for completeness and testing purposes.
     */
    override fun onTerminate() {
        super.onTerminate()

        // Cancel application-wide coroutine scope
        applicationScope.cancel("Application terminating")

        Timber.d("🛑 Application terminated")
    }
}
