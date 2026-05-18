package com.jaac.avoqado_tpv

import android.app.Application
import android.os.Build
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nexgo.oaf.apiv3.device.pinpad.P2PEUtils
import com.blumonpay.pax.utils.AppManager
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkGateway
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
import javax.inject.Provider

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

    // Refreshes Blumon's 24h OAuth token before it expires server-side (per Edgardo, 2026-05-12).
    // Started in onCreate when the PAX SDK is enabled. See SdkTokenRefreshScheduler kdoc.
    @Inject
    lateinit var sdkTokenRefreshScheduler: com.jaac.avoqado_tpv.features.payment.data.SdkTokenRefreshScheduler

    // D4 Pattern A (spec §6.4): lazy Provider<T> so the AngelPaySdkGateway is only resolved
    // on flavors where `SUPPORTED_PROCESSOR == "ANGELPAY"`. PAX flavors (sandbox/production)
    // never call `.get()`, so the underlying `AngelPaySDK` static class is never loaded by the
    // VM on PAX startup — preventing a ClassNotFoundException risk if the AAR were ever
    // excluded from the PAX classpath in the future. Today the AAR is on the classpath of all
    // flavors for Hilt graph-resolution purposes; Pattern A is a forward-compatibility hardening.
    @Inject
    lateinit var angelPaySdkGatewayProvider: Provider<AngelPaySdkGateway>

    override fun onCreate() {
        // ⚠️ CRITICAL: Prepare Blumon's `AppManager.dal` BEFORE super.onCreate().
        // super.onCreate() triggers Hilt's `hiltInternalInject()` which eagerly
        // resolves the Singleton graph. Some of those singletons (e.g.
        // `NeptunePollingRepositoryImpl`) read `AppManager.dal` in their
        // constructor regardless of flavor — even Nexgo, because the Blumon
        // AAR is on the Nexgo classpath (Java stubs only; native .so libs
        // never invoked). If `dal` is still uninitialized at that point, the
        // whole app crashes at startup with UninitializedPropertyAccessException.
        //
        // PAX flavors: call the real `AppManager.init(this)` which loads
        // native libs and binds `dal` to the PAX SDK's IDAL. On Nexgo /
        // emulator this throws UnsatisfiedLinkError before binding `dal`.
        //
        // Nexgo flavor: install a no-op `IDAL` proxy as `dal` so Blumon
        // singletons can be eagerly resolved by Hilt without crashing. These
        // singletons are never actually invoked on Nexgo (the payment flow
        // routes through AngelPay via `BuildConfig.ENABLE_PAX_SDK=false`).
        if (BuildConfig.ENABLE_PAX_SDK) {
            try {
                com.blumonpay.pax.utils.AppManager.init(this)
            } catch (e: Throwable) {
                android.util.Log.w(
                    "AvoqadoTPVApp",
                    "AppManager.init() failed at app create (non-PAX device?): ${e.message}",
                )
            }
        } else {
            installBlumonStubForNonPax()
        }

        super.onCreate()

        // ✅ Initialize critical components only (startup optimization)
        initializeTimber()

        initializeAngelPaySdkIfEnabled()

        // ⏰ Start the Blumon OAuth token refresh scheduler (PAX builds only). No-op on Nexgo
        // because the Blumon SDK is not initialized there. Safe to call before
        // initializeNonCritical() — the scheduler only acts after the first successful init.
        if (BuildConfig.ENABLE_PAX_SDK) {
            sdkTokenRefreshScheduler.start(applicationScope)
        }

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
        // D4 Pattern A (spec §6.4): only resolve the Hilt Provider on AngelPay flavors.
        // On PAX (sandbox/production) the `SUPPORTED_PROCESSOR == "BLUMON"` guard short-circuits
        // here, so `.get()` is never called and the `AngelPaySdkGateway` class (along with the
        // static `AngelPaySDK` it pulls in) is never class-loaded at Application.onCreate.
        // This eliminates a startup-time ClassNotFoundException risk if a future build ever
        // excludes the AngelPay AAR from the PAX classpath.
        if (BuildConfig.SUPPORTED_PROCESSOR != "ANGELPAY") {
            Timber.d("🔶 [AngelPay SDK] Skipped — SUPPORTED_PROCESSOR=${BuildConfig.SUPPORTED_PROCESSOR}")
            return
        }
        if (!BuildConfig.ANGELPAY_SDK_ENABLED) {
            Timber.d("🔶 [AngelPay SDK] Disabled by build flag")
            return
        }

        try {
            // Mirror the legacy env mapping (BLUMON_ENV=PROD → PROD, else QA) so behavior is
            // identical to the pre-refactor inline AngelPaySDK.initialize(...) call.
            val env = if (BuildConfig.BLUMON_ENV == "PROD") "PROD" else "QA"
            angelPaySdkGatewayProvider.get()
                .ensureInitialized(context = applicationContext, env = env)
                .onSuccess {
                    applyAngelPayN62Compatibility()
                    Timber.i("🔶 [AngelPay SDK] Initialized successfully (env=$env)")
                }
                .onFailure { error ->
                    Timber.e(error, "❌ [AngelPay SDK] Failed to initialize")
                }
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
     * Installs a no-op `IDAL` proxy as `AppManager.dal` so Hilt can eagerly
     * resolve Blumon singletons (NeptunePollingRepositoryImpl,
     * StopDetectCardUseCase, etc.) on Nexgo/emulator without crashing.
     *
     * These singletons end up in the graph because they're reachable from
     * the Application's @Inject deps and Hilt resolves them at app create
     * time. Their constructors read `AppManager.dal` and `dal.getCardReaderHelper()`
     * — both must return non-null. On Nexgo we never invoke any of their
     * methods (the payment flow routes through AngelPay), so empty proxies
     * are sufficient.
     *
     * Uses `java.lang.reflect.Proxy` so we don't have to take a hard
     * compile-time dep on PAX SDK internals. If the IDAL interface isn't
     * on the classpath (unlikely — the Blumon AAR is included in all
     * flavors for Hilt class-resolution purposes), we swallow and let the
     * original crash surface so we can debug.
     */
    private fun installBlumonStubForNonPax() {
        try {
            val classLoader = AvoqadoTPVApplication::class.java.classLoader
                ?: return
            val idalClass = Class.forName("com.pax.dal.IDAL", true, classLoader)
            val cardReaderHelperClass = Class.forName("com.pax.dal.ICardReaderHelper", true, classLoader)

            // No-op handler — returns null for any unknown method.
            val noopHandler = java.lang.reflect.InvocationHandler { _, _, _ -> null }

            val cardReaderStub = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(cardReaderHelperClass),
                noopHandler,
            )

            val idalStub = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(idalClass),
            ) { _, method, _ ->
                // NeptunePollingRepositoryImpl.<init> reads
                // `dal.getCardReaderHelper()` and Intrinsics.checkNotNull's
                // the result. Return our stub so the null-check passes.
                when (method.name) {
                    "getCardReaderHelper" -> cardReaderStub
                    else -> null
                }
            }

            com.blumonpay.pax.utils.AppManager.dal = idalStub as com.pax.dal.IDAL
            android.util.Log.i(
                "AvoqadoTPVApp",
                "Installed Blumon IDAL no-op stub for non-PAX flavor (${BuildConfig.FLAVOR})",
            )
        } catch (e: Throwable) {
            android.util.Log.e(
                "AvoqadoTPVApp",
                "Failed to install Blumon stub for non-PAX flavor: ${e.message}",
                e,
            )
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
