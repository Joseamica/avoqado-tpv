package com.jaac.avoqado_tpv.features.remote_command.domain

import android.content.Context
import android.content.pm.PackageManager
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.data.manager.LockScreenManager
import com.jaac.avoqado_tpv.core.data.manager.MaintenanceManager
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommand
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandPriority
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandResultStatus
import com.jaac.avoqado_tpv.features.remote_command.data.model.TpvCommandType
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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

    // System under test
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

        // Setup secure storage to return test terminal ID
        every { mockSecureStorage.getSerialNumber() } returns testTerminalId

        // Setup context mocks
        every { mockContext.packageName } returns "com.jaac.avoqado_tpv"
        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.cacheDir } returns File("/tmp/cache")
        every { mockContext.externalCacheDir } returns null

        // Setup package info - throw exception so getAppVersion() returns "unknown"
        // This avoids issues with mocking Android PackageInfo fields
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            android.content.pm.PackageManager.NameNotFoundException("Mocked for testing")

        // Create CommandExecutor
        commandExecutor = CommandExecutor(
            context = mockContext,
            lockScreenManager = mockLockScreenManager,
            maintenanceManager = mockMaintenanceManager,
            secureStorage = mockSecureStorage
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
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
    fun `MAINTENANCE_MODE command should be rejected when already in maintenance`() = runTest {
        // Given
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns true
        val command = createCommand(TpvCommandType.MAINTENANCE_MODE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("already in maintenance")
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
    fun `EXIT_MAINTENANCE command should be rejected when not in maintenance`() = runTest {
        // Given
        every { mockMaintenanceManager.isCurrentlyInMaintenance() } returns false
        val command = createCommand(TpvCommandType.EXIT_MAINTENANCE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.REJECTED)
        assertThat(result.message).contains("not in maintenance")
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
    fun `SYNC_DATA command should return success`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.SYNC_DATA)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
    }

    // ========================================
    // EXPORT LOGS TESTS
    // ========================================

    @Test
    fun `EXPORT_LOGS command should return success with terminal info`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.EXPORT_LOGS)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.data).containsKey("terminalId")
        assertThat(result.data?.get("terminalId")).isEqualTo(testTerminalId)
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
    fun `UPDATE_CONFIG command should return success with valid payload`() = runTest {
        // Given
        val command = createCommand(
            TpvCommandType.UPDATE_CONFIG,
            payload = mapOf("setting1" to "value1", "setting2" to "value2")
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.data).containsKey("configKeys")
    }

    // ========================================
    // REFRESH MENU TESTS
    // ========================================

    @Test
    fun `REFRESH_MENU command should return success`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.REFRESH_MENU)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
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
    fun `UPDATE_MERCHANT command should return success with valid payload`() = runTest {
        // Given
        val command = createCommand(
            TpvCommandType.UPDATE_MERCHANT,
            payload = mapOf("merchantId" to "merchant_123")
        )

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
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
    fun `FORCE_UPDATE command should return success with version info`() = runTest {
        // Given
        val command = createCommand(TpvCommandType.FORCE_UPDATE)

        // When
        val result = commandExecutor.execute(command)

        // Then
        assertThat(result.status).isEqualTo(TpvCommandResultStatus.SUCCESS)
        assertThat(result.data).containsKey("currentVersion")
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

}
