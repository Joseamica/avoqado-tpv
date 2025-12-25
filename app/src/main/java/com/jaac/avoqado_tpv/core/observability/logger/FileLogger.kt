package com.jaac.avoqado_tpv.core.observability.logger

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FileLogger - Offline log buffer for terminal diagnostics
 *
 * Writes logs to local storage when network unavailable.
 * Provides historical debugging data even without connectivity.
 *
 * ## Features:
 * - 💾 Rolling file logs (max 7 days retention)
 * - 📦 Max 10MB total size (automatic cleanup)
 * - 🔄 Auto-sync to backend when online
 * - 📄 Structured JSON format for parsing
 *
 * ## File Structure:
 * ```
 * /data/data/com.jaac.avoqado_tpv/files/logs/
 *   ├── 2025-12-18.log  (today's logs)
 *   ├── 2025-12-17.log
 *   └── ...
 * ```
 *
 * ## Log Format (JSON per line):
 * ```json
 * {"timestamp":1734567890,"level":"ERROR","tag":"Payment","message":"Payment failed","error":"...","metadata":{...}}
 * ```
 *
 * @see ObservabilityManager for usage
 */
@Singleton
class FileLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Configuration
    private val maxLogFileSizeBytes = 5 * 1024 * 1024 // 5MB per file
    private val maxTotalSizeBytes = 10 * 1024 * 1024 // 10MB total
    private val maxRetentionDays = 7

    suspend fun logInfo(tag: String, message: String, metadata: Map<String, Any?>) {
        writeLog(LogLevel.INFO, tag, message, null, metadata)
    }

    suspend fun logWarning(tag: String, message: String, metadata: Map<String, Any?>) {
        writeLog(LogLevel.WARN, tag, message, null, metadata)
    }

    suspend fun logError(tag: String, message: String, error: Throwable?, metadata: Map<String, Any?>) {
        writeLog(LogLevel.ERROR, tag, message, error?.stackTraceToString(), metadata)
    }

    private suspend fun writeLog(
        level: LogLevel,
        tag: String,
        message: String,
        error: String?,
        metadata: Map<String, Any?>
    ) = withContext(Dispatchers.IO) {
        try {
            val logFile = getCurrentLogFile()

            // Rotate if file too large
            if (logFile.length() > maxLogFileSizeBytes) {
                rotateLogFile(logFile)
            }

            val logEntry = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "datetime" to timestampFormat.format(Date()),
                "level" to level.name,
                "tag" to tag,
                "message" to message,
                "error" to error,
                "metadata" to metadata
            )

            val json = gson.toJson(logEntry)
            logFile.appendText("$json\n")

            // Cleanup old logs
            cleanupOldLogs()
        } catch (e: Exception) {
            Timber.e(e, "Failed to write to file logger")
        }
    }

    private fun getCurrentLogFile(): File {
        val today = dateFormat.format(Date())
        return File(logsDir, "$today.log")
    }

    private fun rotateLogFile(file: File) {
        val timestamp = System.currentTimeMillis()
        val newName = "${file.nameWithoutExtension}_$timestamp.log"
        file.renameTo(File(logsDir, newName))
    }

    private fun cleanupOldLogs() {
        try {
            val now = System.currentTimeMillis()
            val maxAge = maxRetentionDays * 24 * 60 * 60 * 1000L

            // Delete logs older than retention period
            logsDir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > maxAge) {
                    file.delete()
                    Timber.d("Deleted old log file: ${file.name}")
                }
            }

            // If total size still > max, delete oldest files
            var totalSize = logsDir.listFiles()?.sumOf { it.length() } ?: 0
            if (totalSize > maxTotalSizeBytes) {
                val files = logsDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
                for (file in files) {
                    if (totalSize <= maxTotalSizeBytes) break
                    totalSize -= file.length()
                    file.delete()
                    Timber.d("Deleted log file to free space: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to cleanup old logs")
        }
    }

    /**
     * Get all log files (for manual export/debugging)
     */
    fun getLogFiles(): List<File> {
        return logsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Get logs for specific date
     */
    suspend fun readLogsForDate(date: Date): List<String> = withContext(Dispatchers.IO) {
        val filename = "${dateFormat.format(date)}.log"
        val file = File(logsDir, filename)
        if (file.exists()) {
            file.readLines()
        } else {
            emptyList()
        }
    }

    /**
     * Get recent logs (last N lines)
     */
    suspend fun getRecentLogs(maxLines: Int = 100): List<String> = withContext(Dispatchers.IO) {
        val currentLog = getCurrentLogFile()
        if (currentLog.exists()) {
            currentLog.readLines().takeLast(maxLines)
        } else {
            emptyList()
        }
    }

    /**
     * Clear all logs (for privacy/compliance)
     */
    suspend fun clearAllLogs() = withContext(Dispatchers.IO) {
        logsDir.listFiles()?.forEach { it.delete() }
        Timber.d("All logs cleared")
    }

    suspend fun flush() {
        // File writes are synchronous, nothing to flush
    }

    private enum class LogLevel {
        INFO, WARN, ERROR
    }
}
