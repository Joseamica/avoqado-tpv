package com.jaac.avoqado_tpv

import android.app.Application
import android.os.Build
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.angelpay.angelpaysdk.AngelPaySDK
import com.nexgo.oaf.apiv3.device.pinpad.P2PEUtils
import com.blumonpay.pax.utils.AppManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
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

    @Inject
    lateinit var secureStorage: SecureStorage

    override fun onCreate() {
        super.onCreate()

        // ✅ Initialize critical components only (startup optimization)
        initializeTimber()

        // 🔶 NEXGO: Provision AngelPay QA credentials for testing
        if (!BuildConfig.ENABLE_PAX_SDK) {
            provisionAngelPayQACredentials()
        }
        initializeAngelPaySdkIfEnabled()

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
    /**
     * Provision AngelPay QA credentials for testing on Nexgo terminals.
     * Only runs when ENABLE_PAX_SDK=false (nexgo and tutorialEmu flavors).
     * Saves QA creds if not already present.
     */
    private fun provisionAngelPayQACredentials() {
        val email = BuildConfig.ANGELPAY_QA_EMAIL
        if (email.isBlank()) return // No QA creds configured for this flavor

        secureStorage.saveAngelPayCredentials(
            email = email,
            password = BuildConfig.ANGELPAY_QA_PASSWORD,
            affiliation = BuildConfig.ANGELPAY_QA_AFFILIATION,
            commerceToken = BuildConfig.ANGELPAY_QA_COMMERCE_TOKEN,
        )
        Timber.i("🔶 [AngelPay] QA credentials provisioned from BuildConfig")
    }

    private fun initializeTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("🚀 Avoqado TPV initialized in DEBUG mode")
        } else {
            Timber.plant(com.jaac.avoqado_tpv.core.observability.CrashReportingTree())
        }

        // 🛡️ Static Crashlytics keys — tag every report with the build variant,
        // environment, and (when available) terminal serial so Operations doesn't
        // have to guess which device. Safe in DEBUG too — runCatching swallows
        // missing-Firebase failures.
        com.jaac.avoqado_tpv.core.observability.CrashlyticsContext.setAppContext(
            buildVariant = BuildConfig.BUILD_TYPE + "/" + BuildConfig.FLAVOR,
            environment = BuildConfig.BLUMON_ENV,
            terminalSerial = runCatching { com.jaac.avoqado_tpv.core.domain.TerminalConfig.serialNumber }.getOrNull(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    private fun initializeAngelPaySdkIfEnabled() {
        if (!BuildConfig.ANGELPAY_SDK_ENABLED) {
            Timber.d("🔶 [AngelPay SDK] Disabled by build flag")
            return
        }

        try {
            val env = if (BuildConfig.BLUMON_ENV == "PROD") "PROD" else "QA"
            AngelPaySDK.initialize(context = applicationContext, env = env)
            applyAngelPayN62Compatibility()
            Timber.i("🔶 [AngelPay SDK] Initialized successfully (env=$env)")
        } catch (e: Throwable) {
            Timber.e(e, "❌ [AngelPay SDK] Failed to initialize")
        }
    }

    /**
     * AngelPay SDK 1.0.3 still bundles a Nexgo EMV stack that probes P2PE/SRED
     * by default. On N62 firmware the bundled stack calls `ddi_sys_get_sred_state()`,
     * a symbol that is missing from the terminal framework. The probe throws
     * `NoSuchMethodError`, the SDK catches it and sets `isP2PE = -1`, and the
     * EMV chip flow then aborts with `SDK error -8020` after `emvProcessFlow1`.
     *
     * Confirmed against AVQD-N620W100220 with SDK 1.0.3 + chip card on 2026-04-30.
     * Workaround: force `P2PEUtils.isUseP2PE = false` so the bundled stack skips
     * the SRED probe entirely. ICC processing continues normally; contactless and
     * magstripe were never affected.
     */
    private fun applyAngelPayN62Compatibility() {
        if (!isNexgoN62()) return

        try {
            if (P2PEUtils.isUseP2PE) {
                Timber.w(
                    "⚠️ [AngelPay SDK] Disabling Nexgo P2PE probe on N62 to avoid ICC -8020 error"
                )
            }
            P2PEUtils.isUseP2PE = false
            Timber.i("🔶 [AngelPay SDK] N62 ICC compatibility applied | isUseP2PE=${P2PEUtils.isUseP2PE}")
        } catch (error: Throwable) {
            Timber.e(error, "❌ [AngelPay SDK] Failed to apply N62 ICC compatibility")
        }
    }

    private fun isNexgoN62(): Boolean {
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return manufacturer.contains("nexgo", ignoreCase = true) &&
            model.contains("N62", ignoreCase = true)
    }

    /**
     * Initialize non-critical components in background thread
     * This prevents blocking the main thread during app startup
     */
    private suspend fun initializeNonCritical() = withContext(Dispatchers.IO) {
        try {
            if (!BuildConfig.ENABLE_PAX_SDK) {
                Timber.w("🧪 PAX SDK initialization disabled for this flavor")
                return@withContext
            }

            // Initialize Blumon PAX SDK
            AppManager.init(this@AvoqadoTPVApplication)
            Timber.d("✅ Blumon PAX SDK initialized")
        } catch (e: Throwable) {
            // Catches UnsatisfiedLinkError (Error, not Exception) on non-PAX devices
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
