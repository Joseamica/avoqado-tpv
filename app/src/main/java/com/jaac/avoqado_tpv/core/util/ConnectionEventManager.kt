package com.jaac.avoqado_tpv.core.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection Event Manager
 *
 * Singleton service for broadcasting connection restoration events.
 * Allows ViewModels to listen to connection changes without directly coupling them.
 *
 * **Architecture Pattern (Toast POS / Square POS):**
 * ```
 * ConnectionViewModel → detects reconnection → emits event via ConnectionEventManager
 * ShiftViewModel → listens to event → reloads shift data
 * HomeViewModel → listens to event → reloads config (future)
 * OrderViewModel → listens to event → reloads orders (future)
 * ```
 *
 * **Why not inject ViewModels?**
 * - Hilt prohibits injecting @HiltViewModel into other ViewModels
 * - ViewModels should not depend on each other
 * - Use singleton services for cross-ViewModel communication
 *
 * **Usage:**
 * ```kotlin
 * // In ConnectionViewModel (emitter):
 * connectionEventManager.emitConnectionRestored(
 *     attemptsBeforeReconnection = reconnectionAttempts
 * )
 *
 * // In ShiftViewModel (listener):
 * connectionEventManager.connectionRestoredEvents.collect { event ->
 *     loadCurrentShift() // Reload data
 * }
 * ```
 */
@Singleton
class ConnectionEventManager @Inject constructor() {

    private val _connectionRestoredEvents = MutableSharedFlow<ConnectionRestoredEvent>(
        replay = 0,
        extraBufferCapacity = 10
    )

    /**
     * Connection Restored Events
     *
     * Emitted when backend connection is restored after being disconnected.
     * Multiple ViewModels can subscribe to this event.
     */
    val connectionRestoredEvents: SharedFlow<ConnectionRestoredEvent> = _connectionRestoredEvents.asSharedFlow()

    /**
     * Emit connection restored event
     *
     * Called by ConnectionViewModel when heartbeat succeeds after failures.
     */
    suspend fun emitConnectionRestored(
        attemptsBeforeReconnection: Int
    ) {
        _connectionRestoredEvents.emit(
            ConnectionRestoredEvent(
                timestamp = java.time.Instant.now().toString(),
                attemptsBeforeReconnection = attemptsBeforeReconnection
            )
        )
    }
}

/**
 * Connection Restored Event
 *
 * Emitted when backend connection is restored after being disconnected.
 * Triggers "reconciliation sync" pattern (Toast POS / Square POS).
 *
 * **What to sync:**
 * - Shift status (critical - drives "Sin turno activo" UI)
 * - Terminal config (merchant accounts, settings)
 * - Open orders (if any)
 * - Pending transactions (if any)
 *
 * @param timestamp ISO-8601 timestamp of reconnection
 * @param attemptsBeforeReconnection Number of failed attempts before success
 */
data class ConnectionRestoredEvent(
    val timestamp: String,
    val attemptsBeforeReconnection: Int
)
