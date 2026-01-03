package com.jaac.avoqado_tpv.core.data.manager

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * LockScreenManagerTest
 *
 * Unit tests for remote terminal lock state management.
 *
 * Tests:
 * - Lock/unlock state transitions
 * - Lock reason and message storage
 * - StateFlow emissions
 * - Edge cases (double lock, unlock when not locked)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockScreenManagerTest {

    private lateinit var lockScreenManager: LockScreenManager
    private lateinit var mockSecureStorage: SecureStorage

    @Before
    fun setup() {
        mockSecureStorage = mockk(relaxed = true)
        // Return unlocked state by default
        every { mockSecureStorage.getIsLocked() } returns false
        every { mockSecureStorage.getLockReason() } returns null
        every { mockSecureStorage.getLockMessage() } returns null
        every { mockSecureStorage.getLockedBy() } returns null
        lockScreenManager = LockScreenManager(mockSecureStorage)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========================================
    // INITIAL STATE TESTS
    // ========================================

    @Test
    fun `initial state should be unlocked`() {
        assertThat(lockScreenManager.isCurrentlyLocked()).isFalse()
    }

    @Test
    fun `initial lockReason should be null`() = runTest {
        lockScreenManager.lockReason.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial lockMessage should be null`() = runTest {
        lockScreenManager.lockMessage.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial lockedBy should be null`() = runTest {
        lockScreenManager.lockedBy.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // LOCK TESTS
    // ========================================

    @Test
    fun `lock() should set isLocked to true`() {
        // When
        lockScreenManager.lock(null, null, null)

        // Then
        assertThat(lockScreenManager.isCurrentlyLocked()).isTrue()
    }

    @Test
    fun `lock() should set lockReason`() = runTest {
        // Given
        val reason = "Device reported stolen"

        // When
        lockScreenManager.lock(reason, null, null)

        // Then
        lockScreenManager.lockReason.test {
            assertThat(awaitItem()).isEqualTo(reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lock() should set lockMessage`() = runTest {
        // Given
        val message = "Contact support at 800-123-4567"

        // When
        lockScreenManager.lock(null, message, null)

        // Then
        lockScreenManager.lockMessage.test {
            assertThat(awaitItem()).isEqualTo(message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lock() should set lockedBy`() = runTest {
        // Given
        val admin = "Admin Principal"

        // When
        lockScreenManager.lock(null, null, admin)

        // Then
        lockScreenManager.lockedBy.test {
            assertThat(awaitItem()).isEqualTo(admin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `lock() with all parameters should set all values`() = runTest {
        // Given
        val reason = "Security breach"
        val message = "Please wait for investigation"
        val lockedBy = "Security Team"

        // When
        lockScreenManager.lock(reason, message, lockedBy)

        // Then
        assertThat(lockScreenManager.isCurrentlyLocked()).isTrue()

        lockScreenManager.lockReason.test {
            assertThat(awaitItem()).isEqualTo(reason)
            cancelAndIgnoreRemainingEvents()
        }

        lockScreenManager.lockMessage.test {
            assertThat(awaitItem()).isEqualTo(message)
            cancelAndIgnoreRemainingEvents()
        }

        lockScreenManager.lockedBy.test {
            assertThat(awaitItem()).isEqualTo(lockedBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isLocked StateFlow should emit true when locked`() = runTest {
        lockScreenManager.isLocked.test {
            // Initial state
            assertThat(awaitItem()).isFalse()

            // Lock
            lockScreenManager.lock("Test", null, null)
            assertThat(awaitItem()).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // UNLOCK TESTS
    // ========================================

    @Test
    fun `unlock() should set isLocked to false`() {
        // Given
        lockScreenManager.lock("Test", null, null)
        assertThat(lockScreenManager.isCurrentlyLocked()).isTrue()

        // When
        lockScreenManager.unlock()

        // Then
        assertThat(lockScreenManager.isCurrentlyLocked()).isFalse()
    }

    @Test
    fun `unlock() should clear lockReason`() = runTest {
        // Given
        lockScreenManager.lock("Device stolen", null, null)

        // When
        lockScreenManager.unlock()

        // Then
        lockScreenManager.lockReason.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unlock() should clear lockMessage`() = runTest {
        // Given
        lockScreenManager.lock(null, "Contact support", null)

        // When
        lockScreenManager.unlock()

        // Then
        lockScreenManager.lockMessage.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unlock() should clear lockedBy`() = runTest {
        // Given
        lockScreenManager.lock(null, null, "Admin")

        // When
        lockScreenManager.unlock()

        // Then
        lockScreenManager.lockedBy.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isLocked StateFlow should emit false when unlocked`() = runTest {
        lockScreenManager.isLocked.test {
            // Initial state
            assertThat(awaitItem()).isFalse()

            // Lock
            lockScreenManager.lock("Test", null, null)
            assertThat(awaitItem()).isTrue()

            // Unlock
            lockScreenManager.unlock()
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // EDGE CASE TESTS
    // ========================================

    @Test
    fun `unlock() when not locked should not throw`() {
        // Given - Not locked

        // When/Then - Should not throw
        lockScreenManager.unlock()
        assertThat(lockScreenManager.isCurrentlyLocked()).isFalse()
    }

    @Test
    fun `double lock() should update values`() = runTest {
        // Given
        lockScreenManager.lock("First reason", "First message", "First admin")

        // When - Lock again with different values
        lockScreenManager.lock("Second reason", "Second message", "Second admin")

        // Then - Should have second values
        assertThat(lockScreenManager.isCurrentlyLocked()).isTrue()

        lockScreenManager.lockReason.test {
            assertThat(awaitItem()).isEqualTo("Second reason")
            cancelAndIgnoreRemainingEvents()
        }

        lockScreenManager.lockMessage.test {
            assertThat(awaitItem()).isEqualTo("Second message")
            cancelAndIgnoreRemainingEvents()
        }

        lockScreenManager.lockedBy.test {
            assertThat(awaitItem()).isEqualTo("Second admin")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isCurrentlyLocked() should reflect current state`() {
        // Initial
        assertThat(lockScreenManager.isCurrentlyLocked()).isFalse()

        // Lock
        lockScreenManager.lock(null, null, null)
        assertThat(lockScreenManager.isCurrentlyLocked()).isTrue()

        // Unlock
        lockScreenManager.unlock()
        assertThat(lockScreenManager.isCurrentlyLocked()).isFalse()
    }
}
