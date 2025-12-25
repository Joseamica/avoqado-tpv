package com.jaac.avoqado_tpv.core.observability

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.core.observability.logger.FileLogger
import com.jaac.avoqado_tpv.core.observability.logger.RemoteLogger
import com.jaac.avoqado_tpv.core.observability.monitor.HealthMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ObservabilityManager - Enterprise-grade observability system for production TPV terminals
 *
 * Inspired by Toast, Square, and Clover terminal monitoring systems.
 * Provides comprehensive visibility into terminal health and issues in production.
 *
 * ## Features:
 * - 🔥 Firebase Crashlytics: Automatic crash reporting with stack traces
 * - 📡 Remote Logging: Real-time logs via Socket.IO to backend
 * - 💾 File Logging: Offline fallback when network unavailable
 * - 🏥 Health Monitoring: Proactive terminal health metrics (memory, battery, uptime)
 *
 * ## Usage:
 * ```kotlin
 * // Inject in ViewModels or Services
 * @Inject lateinit var observability: ObservabilityManager
 *
 * // Initialize with terminal context (after auth)
 * observability.initialize(venueId, terminalId, userId)
 *
 * // Log events (automatically routed to Crashlytics + Remote + File)
 * observability.logInfo("Payment", "Payment initiated", mapOf("amount" to amount))
 * observability.logError("Payment", "Payment failed", error, mapOf("orderId" to orderId))
 *
 * // Health monitoring starts automatically
 * ```
 *
 * ## Architecture:
 * - **Production builds**: All systems active (Crashlytics + Remote + File + Health)
 * - **Debug builds**: Only Timber console logs
 * - **Offline mode**: Logs buffered to file, synced when online
 *
 * @see RemoteLogger for Socket.IO real-time logging
 * @see FileLogger for offline buffer
 * @see HealthMonitor for proactive terminal health
 */
@Singleton
class ObservabilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remoteLogger: RemoteLogger,
    private val fileLogger: FileLogger,
    private val healthMonitor: HealthMonitor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isInitialized = false
    private var isEnabled = false  // Track if observability is enabled

    // Terminal context
    private var venueId: String? = null
    private var terminalId: String? = null
    private var userId: String? = null

    /**
     * Initialize observability system with terminal context
     *
     * Call this AFTER authentication to set terminal identity for logs.
     *
     * @param venueId Restaurant/business ID
     * @param terminalId Physical terminal ID (from Terminal.id)
     * @param userId Currently logged-in user ID
     * @param enableInDebug Enable observability in debug builds (for testing)
     */
    fun initialize(
        venueId: String,
        terminalId: String,
        userId: String,
        enableInDebug: Boolean = false
    ) {
        if (isInitialized) {
            Timber.w("ObservabilityManager already initialized")
            return
        }

        this.venueId = venueId
        this.terminalId = terminalId
        this.userId = userId

        // Enable in release builds OR if explicitly enabled for debug/testing
        isEnabled = !BuildConfig.DEBUG || enableInDebug

        if (isEnabled) {
            setupCrashlytics()
            setupRemoteLogger()
            setupHealthMonitoring()
            Timber.d("✅ Observability ENABLED (debug=$enableInDebug, release=${!BuildConfig.DEBUG})")
        } else {
            Timber.d("⚠️ Observability DISABLED (debug build, use enableInDebug=true for testing)")
        }

        isInitialized = true
        logInfo("Observability", "Observability system initialized", mapOf(
            "venueId" to venueId,
            "terminalId" to terminalId,
            "version" to BuildConfig.VERSION_NAME,
            "enabled" to isEnabled
        ))
    }

    /**
     * Configure Firebase Crashlytics with terminal context
     */
    private fun setupCrashlytics() {
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCrashlyticsCollectionEnabled(true)
                setUserId(userId ?: "unknown")
                setCustomKey("venue_id", venueId ?: "unknown")
                setCustomKey("terminal_id", terminalId ?: "unknown")
                setCustomKey("app_version", BuildConfig.VERSION_NAME)
                setCustomKey("blumon_env", BuildConfig.BLUMON_ENV)
            }
            Timber.d("✅ Crashlytics configured")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to configure Crashlytics")
        }
    }

    /**
     * Setup remote logging via Socket.IO
     */
    private fun setupRemoteLogger() {
        scope.launch {
            try {
                remoteLogger.initialize(
                    venueId = venueId ?: return@launch,
                    terminalId = terminalId ?: return@launch
                )
                Timber.d("✅ Remote logger configured")
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to configure remote logger")
            }
        }
    }

    /**
     * Start health monitoring (heartbeat every 5 minutes)
     */
    private fun setupHealthMonitoring() {
        scope.launch {
            try {
                healthMonitor.startMonitoring(
                    venueId = venueId ?: return@launch,
                    terminalId = terminalId ?: return@launch
                )
                Timber.d("✅ Health monitoring started")
            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to start health monitoring")
            }
        }
    }

    // ==========================================
    // PUBLIC API: Logging Methods
    // ==========================================

    /**
     * Log informational event
     *
     * Routes to: Timber (debug) + Remote (production) + File (offline)
     */
    fun logInfo(tag: String, message: String, metadata: Map<String, Any?> = emptyMap()) {
        Timber.tag(tag).i(message)

        if (isEnabled) {
            scope.launch {
                remoteLogger.logInfo(tag, message, metadata)
                fileLogger.logInfo(tag, message, metadata)
            }
        }
    }

    /**
     * Log warning event
     *
     * Routes to: Timber (debug) + Remote (production) + File (offline) + Crashlytics
     */
    fun logWarning(tag: String, message: String, metadata: Map<String, Any?> = emptyMap()) {
        Timber.tag(tag).w(message)

        if (isEnabled) {
            scope.launch {
                remoteLogger.logWarning(tag, message, metadata)
                fileLogger.logWarning(tag, message, metadata)

                // Record non-fatal in Crashlytics
                FirebaseCrashlytics.getInstance().apply {
                    metadata.forEach { (key, value) ->
                        setCustomKey(key, value.toString())
                    }
                    recordException(Exception("[$tag] $message"))
                }
            }
        }
    }

    /**
     * Log error event
     *
     * Routes to: Timber (debug) + Remote (production) + File (offline) + Crashlytics
     */
    fun logError(
        tag: String,
        message: String,
        error: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        Timber.tag(tag).e(error, message)

        if (isEnabled) {
            scope.launch {
                remoteLogger.logError(tag, message, error, metadata)
                fileLogger.logError(tag, message, error, metadata)

                // Record in Crashlytics with context
                FirebaseCrashlytics.getInstance().apply {
                    metadata.forEach { (key, value) ->
                        setCustomKey(key, value.toString())
                    }
                    recordException(error ?: Exception("[$tag] $message"))
                }
            }
        }
    }

    /**
     * Log critical event (payment failures, SDK errors, etc.)
     *
     * Same as logError but with "CRITICAL" severity flag for alerting
     */
    fun logCritical(
        tag: String,
        message: String,
        error: Throwable? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        val enrichedMetadata = metadata.toMutableMap().apply {
            put("severity", "CRITICAL")
        }

        logError(tag, message, error, enrichedMetadata)
    }

    /**
     * Add breadcrumb for debugging context
     *
     * Breadcrumbs help trace user actions leading to errors.
     */
    fun addBreadcrumb(category: String, message: String, data: Map<String, String> = emptyMap()) {
        Timber.tag("Breadcrumb").d("[$category] $message")

        if (isEnabled) {
            FirebaseCrashlytics.getInstance().log("[$category] $message: $data")
        }
    }

    /**
     * Force upload all pending logs to backend
     *
     * Useful before app termination or when going offline for extended period.
     */
    suspend fun flush() {
        if (isEnabled) {
            fileLogger.flush()
            remoteLogger.flush()
        }
    }

    /**
     * Stop all observability systems (cleanup)
     */
    fun shutdown() {
        if (isEnabled) {
            healthMonitor.stopMonitoring()
            scope.launch {
                flush()
            }
        }
        isInitialized = false
    }
}
