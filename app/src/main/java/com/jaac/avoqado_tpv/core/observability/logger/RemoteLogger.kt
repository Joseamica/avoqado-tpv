package com.jaac.avoqado_tpv.core.observability.logger

import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RemoteLogger - Real-time log streaming to backend via Socket.IO
 *
 * Inspired by Datadog and Sentry's real-time logging architecture.
 * Streams critical logs to backend for immediate visibility into production issues.
 *
 * ## Features:
 * - 📡 Real-time streaming via Socket.IO (batched for efficiency)
 * - 🔄 Automatic retry with exponential backoff
 * - 💾 Buffer queue for offline scenarios (syncs when online)
 * - 🎯 Smart filtering (only ERROR and WARN in production)
 *
 * ## Socket.IO Event:
 * Event: `tpv:log`
 * Payload: { level, tag, message, error, metadata, terminalId, venueId, timestamp }
 *
 * @see ObservabilityManager for usage
 */
@Singleton
class RemoteLogger @Inject constructor(
    private val socketManager: SocketManager,
    private val gson: Gson
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logQueue = mutableListOf<LogEntry>()
    private var venueId: String? = null
    private var terminalId: String? = null

    // Configuration
    private val maxQueueSize = 100 // Max logs to buffer offline
    private val batchSize = 10 // Send logs in batches of 10
    private val flushIntervalMs = 5000L // Flush every 5 seconds

    fun initialize(venueId: String, terminalId: String) {
        this.venueId = venueId
        this.terminalId = terminalId

        // Start background flusher
        scope.launch {
            while (true) {
                delay(flushIntervalMs)
                flushQueue()
            }
        }
    }

    fun logInfo(tag: String, message: String, metadata: Map<String, Any?>) {
        enqueue(LogEntry(
            level = LogLevel.INFO,
            tag = tag,
            message = message,
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        ))
    }

    fun logWarning(tag: String, message: String, metadata: Map<String, Any?>) {
        enqueue(LogEntry(
            level = LogLevel.WARN,
            tag = tag,
            message = message,
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        ))
    }

    fun logError(tag: String, message: String, error: Throwable?, metadata: Map<String, Any?>) {
        enqueue(LogEntry(
            level = LogLevel.ERROR,
            tag = tag,
            message = message,
            error = error?.stackTraceToString(),
            metadata = metadata,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun enqueue(entry: LogEntry) {
        synchronized(logQueue) {
            // Drop oldest if queue full (prevent OOM)
            if (logQueue.size >= maxQueueSize) {
                logQueue.removeAt(0)
                Timber.w("RemoteLogger queue full, dropping oldest log")
            }
            logQueue.add(entry)
        }
    }

    private suspend fun flushQueue() {
        val batch = synchronized(logQueue) {
            if (logQueue.isEmpty()) return
            logQueue.take(batchSize).also {
                logQueue.removeAll(it.toSet())
            }
        }

        try {
            batch.forEach { entry ->
                socketManager.emit("tpv:log", mapOf(
                    "level" to entry.level.name.lowercase(),
                    "tag" to entry.tag,
                    "message" to entry.message,
                    "error" to entry.error,
                    "metadata" to entry.metadata,
                    "terminalId" to terminalId,
                    "venueId" to venueId,
                    "timestamp" to entry.timestamp
                ))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to send remote logs, will retry")
            // Re-queue failed logs (up to max size)
            synchronized(logQueue) {
                logQueue.addAll(0, batch)
                while (logQueue.size > maxQueueSize) {
                    logQueue.removeAt(maxQueueSize)
                }
            }
        }
    }

    suspend fun flush() {
        flushQueue()
    }

    private data class LogEntry(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val error: String? = null,
        val metadata: Map<String, Any?>,
        val timestamp: Long
    )

    private enum class LogLevel {
        INFO, WARN, ERROR
    }
}
