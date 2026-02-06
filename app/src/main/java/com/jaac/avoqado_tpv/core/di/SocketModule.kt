package com.jaac.avoqado_tpv.core.di

import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * SocketModule - Hilt Dependency Injection module for Socket.IO
 *
 * Pattern: Singleton scope - one instance across entire app lifecycle
 *
 * Why Singleton?
 * - Socket connection should persist across screens
 * - Prevents multiple connections to same server
 * - Maintains connection state globally
 * - Events flow to all subscribers via SharedFlow
 *
 * Usage in ViewModel:
 * ```kotlin
 * @HiltViewModel
 * class PaymentViewModel @Inject constructor(
 *     private val socketManager: SocketManager
 * ) : ViewModel() {
 *     init {
 *         viewModelScope.launch {
 *             socketManager.events.collect { event ->
 *                 when (event) {
 *                     is SocketEvent.PaymentCompleted -> { ... }
 *                     else -> {}
 *                 }
 *             }
 *         }
 *     }
 * }
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object SocketModule {

    /**
     * Provide SocketManager singleton
     *
     * Scope: Singleton (one instance for entire app)
     *
     * Why NOT provide IO.Socket directly?
     * - SocketManager encapsulates connection management
     * - Provides type-safe event parsing (JSON → SocketEvent)
     * - Handles reconnection logic
     * - Better testability (can mock SocketManager)
     */
    @Provides
    @Singleton
    fun provideSocketManager(secureStorage: SecureStorage): SocketManager {
        return SocketManager(secureStorage)
    }
}
