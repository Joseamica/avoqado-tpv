package com.jaac.avoqado_tpv.features.remote_command.domain

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.core.data.repository.HeartbeatRepository
import com.jaac.avoqado_tpv.features.remote_command.data.model.CommandResult
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandPriority
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandResultStatus
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayAuthRepository
import com.jaac.avoqado_tpv.features.self_update.data.AvoqadoUpdateRepository
import com.jaac.avoqado_tpv.features.self_update.domain.UpdateRequestManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import javax.inject.Provider
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * CommandExecutorTest
 *
 * Unit tests for remote command execution logic.
 *
 * Tests:
 * - Lock/Unlock commands
 * - Maintenance mode commands
 * - Command expiration
 * - ACK emission
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutorTest {

    // Mocks
    private lateinit var mockContext: Context
    private lateinit var mockLockScreenManager: LockScreenManager
    private lateinit var mockMaintenanceManager: MaintenanceManager
    private lateinit var mockSecureStorage: SecureStorage
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockUpdateRequestManager: UpdateRequestManager
    private lateinit var mockAvoqadoUpdateRepository: AvoqadoUpdateRepository
    private lateinit var mockAngelPayAuthRepository: AngelPayAuthRepository
    private lateinit var mockHeartbeatRepository: HeartbeatRepository

    // System under test
    private val mockDatabase = mockk<com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase>(relaxed = true)
    private lateinit var commandExecutor: CommandExecutor

    // Test terminal ID
    private val testTerminalId = "TEST-TERMINAL-001"

    @Before
    fun setup() {
        // Create mocks
        mockContext = mockk(relaxed = true)
        mockLockScreenManager = mockk(relaxed = true)
        mockMaintenanceManager = mockk(relaxed = true)
        mockSecureStorage = mockk(relaxed = true)
        mockPackageManager = mockk(relaxed = true)
        mockUpdateRequestManager = mockk(relaxed = true)
        mockAvoqadoUpdateRepository = mockk(relaxed = true)
        mockAngelPayAuthRepository = mockk(relaxed = true)
        mockHeartbeatRepository = mockk(relaxed = true)

        coEvery { mockHeartbeatRepository.permitCommand(any(), any()) } returns com.jaac.avoqado_tpv.core.domain.models.Result.Success(true)
        // Setup secure storage to return test terminal ID
        every { mockSecureStorage.getSerialNumber() } returns testTerminalId

        // Setup context mocks
        every { mockContext.packageName } returns "com.jaac.avoqado_tpv"
        every { mockContext.packageManager } returns mockPackageManager
        every { mockPackageManager.getLaunchIntentForPackage(any()) } returns mockk(relaxed = true)
        every { mockContext.cacheDir } returns File("/tmp/cache")
        every { mockContext.externalCacheDir } returns null

        // Setup package info - throw exception so getAppVersion() returns "unknown"
        // This avoids issues with mocking Android PackageInfo fields
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            android.content.pm.PackageManager.NameNotFoundException("Mocked for testing")

        coEvery { mockDatabase.pendingPaymentDao().getPendingCount() } returns 0
        coEvery { mockDatabase.pendingPaymentDao().getFailedCount() } returns 0
        coEvery { mockDatabase.pendingRefundDao().getPendingCount() } returns 0
        coEvery { mockDatabase.pendingRefundDao().getFailedCount() } returns 0
        coEvery { mockDatabase.paymentAttemptDao().countUnresolvedCharges() } returns 0
        CommandExecutionWindow.setForeground(true)
        CommandExecutionWindow.setRoute("home")
        val persisted = mutableMapOf<String, String>()
        every { mockSecureStorage.getString(any(), any()) } answers { persisted[firstArg()] }
        every { mockSecureStorage.putStringDurably(any(), any()) } answers { persisted[firstArg()] = secondArg(); Unit }

        // Create CommandExecutor (updateRequestManager is Provider<> in production code)
        commandExecutor = CommandExecutor(
            context = mockContext,
            commandInbox = CommandInbox(mockSecureStorage),
            databaseProvider = Provider { mockDatabase },
            paymentStateProvider = mockk(relaxed = true),
            lockScreenManager = mockLockScreenManager,
            maintenanceManager = mockMaintenanceManager,
            secureStorage = mockSecureStorage,
            updateRequestManager = Provider { mockUpdateRequestManager },
            avoqadoUpdateRepository = mockAvoqadoUpdateRepository,
            angelPayAuthRepositoryProvider = Provider { mockAngelPayAuthRepository },
            heartbeatRepositoryProvider = Provider { mockHeartbeatRepository },
        )
    }

    @After
    fun tearDown() {
        CommandExecutionWindow.setForeground(false)
        CommandExecutionWindow.setRoute(null)
        unmockkAll()
    }

    @Test fun `recovery drains a full page only after confirmed progress without a timer`() = runTest {
        val page = (1..10).map { id -> com.jaac.avoqado_tpv.core.data.network.dto.PendingCommandDto(
            commandId = "page-$id", correlationId = "corr-$id", type = "LOCK", payload = null,
            priority = "NORMAL", requiresPin = false, expiresAt = null, requestedBy = "sa",
            requestedByName = null, createdAt = java.time.Instant.now().toString()
        ) }
        coEvery { mockHeartbeatRepository.commandsReady(any()) } returnsMany listOf(
            com.jaac.avoqado_tpv.core.domain.models.Result.Success(page),
            com.jaac.avoqado_tpv.core.domain.models.Result.Success(emptyList())
        )
        coEvery { mockHeartbeatRepository.sendCommandAck(any(), any(), any()) } answers {
            CommandInbox(mockSecureStorage).acknowledge(firstArg())
            com.jaac.avoqado_tpv.core.domain.models.Result.Success(Unit)
        }
        commandExecutor.recoverPending()
        coVerify(exactly = 2) { mockHeartbeatRepository.commandsReady(any()) }
        assertThat(CommandInbox(mockSecureStorage).pending()).isEmpty()
    }

    @Test fun `P1 duplicate command replays its receipt instead of executing twice`() = runTest {
        val command = createCommand(TpvCommandType.LOCK)
        val first = commandExecutor.execute(command)
        assertThat(commandExecutor.execute(command)).isEqualTo(first)
        verify(exactly = 1) { mockLockScreenManager.lock(any(), any(), any()) }
    }
    @Test fun `P1 command received on a payment screen waits in durable storage`() = runTest {
        val command = createCommand(TpvCommandType.LOCK)
        CommandExecutionWindow.setRoute("payment")
        assertThat(commandExecutor.execute(command).status).isEqualTo(TpvCommandResultStatus.DEFERRED)
        verify(exactly = 0) { mockLockScreenManager.lock(any(), any(), any()) }
        CommandExecutionWindow.setRoute("home")
        assertThat(commandExecutor.execute(command).status).isEqualTo(TpvCommandResultStatus.SUCCESS)
    }
    @Test fun `P1 factory reset preserves the financial database`() = runTest {
        every { mockContext.databaseList() } returns arrayOf(com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase.DATABASE_NAME)
        commandExecutor.execute(createCommand(TpvCommandType.FACTORY_RESET))
        verify(exactly = 0) { mockContext.deleteDatabase(com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase.DATABASE_NAME) }
    }

    @Test fun `factory reset defers while a financial record is unresolved`() = runTest {
        coEvery { mockDatabase.pendingPaymentDao().getPendingCount() } returns 1
        assertThat(commandExecutor.execute(createCommand(TpvCommandType.FACTORY_RESET)).status).isEqualTo(TpvCommandResultStatus.DEFERRED)
        verify(exactly = 0) { mockSecureStorage.clearAll() }
    }

    // ========================================
    // HELPER FUNCTIONS
    // ========================================

    private fun createCommand(
        type: TpvCommandType,
        payload: Map<String, Any>? = null,
        expiresAt: Instant = Instant.now().plusSeconds(3600)
    ): TpvCommand {
        return TpvCommand(
            commandId = "cmd_${System.currentTimeMillis()}",
            correlationId = "corr_${System.currentTimeMillis()}",
            type = type,
            payload = payload,
            requiresPin = false,
            priority = TpvCommandPriority.NORMAL,
            expiresAt = expiresAt,
            requestedBy = "test@example.com",
            requestedByName = "Test Admin"
        )
    }

    @Test fun `restart stores a receipt and reports only on the next app boot`() = runTest {
        commandExecutor.stopProcess = { throw kotlinx.coroutines.CancellationException("process stopped") }
        val command = createCommand(TpvCommandType.RESTART)
        try { commandExecutor.execute(command) } catch (_: kotlinx.coroutines.CancellationException) {}
        assertThat(CommandInbox(mockSecureStorage).previousResult(command.commandId)?.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        coVerify(exactly = 0) { mockHeartbeatRepository.sendCommandAck(any(), any(), any()) }
    }

    // ========================================
    // COMMAND EXPIRATION TESTS
    // ========================================

    @Test
    fun `execute() should reject expired command`() = runTest {
        // Given - Command that expired 1 hour ago
        val expiredCommand = createCommand(
            type = TpvCommandType.LOCK,
            expiresAt = Instant.now().minusSeconds(3600)
        )

        // When
        val result = commandExecutor.execute(expiredCommand)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("expired")
    }

    @Test
    fun `execute() should not call lock manager for expired command`() = runTest {
        // Given - Expired command
        val expiredCommand = createCommand(
            type = TpvCommandType.LOCK,
            expiresAt = Instant.now().minusSeconds(3600)
        )

        // When
        commandExecutor.execute(expiredCommand)

        // Then - Lock manager should NOT be called
        verify(exactly = 0) { mockLockScreenManager.lock(any(), any(), any()) }
    }

    // ========================================
    // LOCK COMMAND TESTS
    // ========================================

    @Test
    fun `LOCK command should call lockScreenManager lock()`() = runTest {
        // Given
        val command = createCommand(
            type = TpvCommandType.LOCK,
            payload = mapOf(
                "reason" to "Security breach",
                "message" to "Contact support",
                "lockedBy" to "Admin"
            )
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        verify { mockLockScreenManager.lock("Security breach", "Contact support", "Admin") }
    }

    @Test
    fun `LOCK command with no payload should still lock`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.LOCK)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        verify { mockLockScreenManager.lock(null, null, null) }
    }

    @Test
    fun `LOCK command result should contain lockedAt timestamp`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.LOCK)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.data).containsKey("lockedAt")
    }

    // ========================================
    // UNLOCK COMMAND TESTS
    // ========================================

    @Test
    fun `UNLOCK command should call lockScreenManager unlock() when locked`() = runTest {
        // Given
        every { mockLockScreenManager.isCurrentlyLocked() } returns true
        val command = createCommand(TpvCommandType.UNLOCK)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        verify { mockLockScreenManager.unlock() }
    }

    @Test
    fun `UNLOCK command should be rejected when not locked`() = runTest {
        // Given
        every { mockLockScreenManager.isCurrentlyLocked() } returns false
        val command = createCommand(TpvCommandType.UNLOCK)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("not locked")
    }

    // ========================================
    // MAINTENANCE MODE TESTS
    // ========================================

    @Test
    fun `MAINTENANCE_MODE command should call maintenanceManager enterMaintenance()`() = runTest {
        // Given
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns false
        val command = createCommand(
            type = TpvCommandType.MAINTENANCE_MODE,
            payload = mapOf("reason" to "Software update")
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        verify { mockMaintenanceManager.enterMaintenance("Software update", "Test Admin") }
    }

    @Test
    fun `MAINTENANCE_MODE command is idempotent when already in maintenance`() = runTest {
        // Given - Already in maintenance
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns true
        val command = createCommand(TpvCommandType.MAINTENANCE_MODE)

        // When
        val result = commandExecutor.execute(command)

        // Then - Idempotent: always succeeds (2025-12-01 change)
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.data?.get("wasAlreadyInMaintenance")).isEqualTo(true)
    }

    // ========================================
    // EXIT MAINTENANCE TESTS
    // ========================================

    @Test
    fun `EXIT_MAINTENANCE command should call maintenanceManager exitMaintenance() when in maintenance`() = runTest {
        // Given
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns true
        val command = createCommand(TpvCommandType.EXIT_MAINTENANCE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        verify { mockMaintenanceManager.exitMaintenance() }
    }

    @Test
    fun `EXIT_MAINTENANCE command is idempotent when not in maintenance`() = runTest {
        // Given - Not in maintenance
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns false
        val command = createCommand(TpvCommandType.EXIT_MAINTENANCE)

        // When
        val result = commandExecutor.execute(command)

        // Then - Idempotent: always succeeds (2025-12-01 change)
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.data?.get("wasInMaintenance")).isEqualTo(false)
    }

    // ========================================
    // CLEAR CACHE TESTS
    // ========================================

    @Test
    fun `CLEAR_CACHE command should return success`() = runTest {
        // Given
        val cacheDir = mockk<File>(relaxed = true)
        every { mockContext.cacheDir } returns cacheDir
        every { cacheDir.deleteRecursively() } returns true
        every { cacheDir.mkdir() } returns true

        val command = createCommand(TpvCommandType.CLEAR_CACHE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.message).contains("Cache cleared")
    }

    // ========================================
    // SYNC DATA TESTS
    // ========================================

    @Test
    fun `SYNC_DATA command should reject an unsupported action`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.SYNC_DATA)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // EXPORT LOGS TESTS
    // ========================================

    @Test
    fun `EXPORT_LOGS command should reject an unsupported action with terminal info`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.EXPORT_LOGS)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // UPDATE CONFIG TESTS
    // ========================================

    @Test
    fun `UPDATE_CONFIG command should be rejected with no payload`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.UPDATE_CONFIG, payload = null)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("No configuration provided")
    }

    @Test
    fun `UPDATE_CONFIG command should be rejected with empty payload`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.UPDATE_CONFIG, payload = emptyMap())

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    @Test
    fun `UPDATE_CONFIG command should reject an unsupported action with valid payload`() = runTest {
        // Given
        val command = createCommand(
            TpvCommandType.UPDATE_CONFIG,
            payload = mapOf("setting1" to "value1", "setting2" to "value2")
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // REFRESH MENU TESTS
    // ========================================

    @Test
    fun `REFRESH_MENU command should reject an unsupported action`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.REFRESH_MENU)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // UPDATE MERCHANT TESTS
    // ========================================

    @Test
    fun `UPDATE_MERCHANT command should be rejected with no payload`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.UPDATE_MERCHANT, payload = null)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("No merchant data provided")
    }

    @Test
    fun `UPDATE_MERCHANT command should reject an unsupported action with valid payload`() = runTest {
        // Given
        val command = createCommand(
            TpvCommandType.UPDATE_MERCHANT,
            payload = mapOf("merchantId" to "merchant_123")
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // REACTIVATE TESTS
    // ========================================

    @Test
    fun `REACTIVATE command should return success`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.REACTIVATE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
    }

    // ========================================
    // FORCE UPDATE TESTS
    // ========================================

    @Test
    fun `FORCE_UPDATE command handles missing Firebase gracefully`() = runTest {
        // Given - Firebase App Distribution is not available in unit tests
        val command = createCommand(TpvCommandType.FORCE_UPDATE)

        // When
        val result = commandExecutor.execute(command)

        // Then - Returns FAILED because FirebaseAppDistribution.getInstance() throws in unit tests
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.FAILED)
        assertThat(result.message).isNotNull()
    }

    // ========================================
    // AUTOMATION COMMANDS TESTS
    // ========================================

    @Test
    fun `SCHEDULE command should be rejected as server-side only`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.SCHEDULE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("server-side")
    }

    @Test
    fun `GEOFENCE_TRIGGER command should be rejected as server-side only`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.GEOFENCE_TRIGGER)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    @Test
    fun `TIME_RULE command should be rejected as server-side only`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.TIME_RULE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
    }

    // ========================================
    // REMOTE_ACTIVATE — P0 fix 2026-07-11: re-parenting must re-initialize
    // the processor (restart → config refetch → NEW venue's merchants).
    // ========================================

    private fun remoteActivatePayload() = mapOf<String, Any>(
        "venueId" to "venue-NEW",
        "venueName" to "Venue Nuevo",
        "venueSlug" to "venue-nuevo",
        "venueTimezone" to "America/Mexico_City",
        "terminalId" to "term-1",
        "terminalName" to "TPV 1",
        "serialNumber" to "AVQD-TEST-001",
    )

    @Test
    fun `REMOTE_ACTIVATE restarts while holding the safe window and preserves its receipt`() = runTest {
        var stopped = false
        commandExecutor.stopProcess = {
            assertThat(CommandExecutionWindow.executing.value).isTrue()
            stopped = true
        }
        val command = createCommand(TpvCommandType.REMOTE_ACTIVATE, remoteActivatePayload())
        val result = commandExecutor.execute(command)
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(stopped).isTrue()
        assertThat(CommandInbox(mockSecureStorage).previousResult(command.commandId)?.data?.get("venueId")).isEqualTo("venue-NEW")
        verify { mockSecureStorage.saveVenueId("venue-NEW") }
        verify { mockSecureStorage.saveVenueSlug("venue-nuevo") }
        verify(exactly = 0) { mockAngelPayAuthRepository.logout() }
    }

    @Test
    fun `REMOTE_ACTIVATE with missing venue info is rejected and does NOT restart`() = runTest {
        // Given
        var restartScheduled = false
        commandExecutor.stopProcess = { restartScheduled = true }
        val command = createCommand(
            TpvCommandType.REMOTE_ACTIVATE,
            payload = mapOf("venueName" to "Sin venueId ni slug"),
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(restartScheduled).isFalse()
        verify(exactly = 0) { mockSecureStorage.saveVenueId(any()) }
    }

}
