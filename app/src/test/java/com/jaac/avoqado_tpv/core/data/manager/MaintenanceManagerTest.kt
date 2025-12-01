package com.jaac.avoqado_tpv.core.data.manager

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * MaintenanceManagerTest
 *
 * Unit tests for terminal maintenance mode state management.
 *
 * Tests:
 * - Enter/exit maintenance state transitions
 * - Maintenance reason and initiatedBy storage
 * - StateFlow emissions
 * - Edge cases (double enter, exit when not in maintenance)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceManagerTest {

    private lateinit var maintenanceManager: MaintenanceManager

    @Before
    fun setup() {
        maintenanceManager = MaintenanceManager()
    }

    // ========================================
    // INITIAL STATE TESTS
    // ========================================

    @Test
    fun `initial state should not be in maintenance`() {
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isFalse()
    }

    @Test
    fun `initial maintenanceReason should be null`() = runTest {
        maintenanceManager.maintenanceReason.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initial initiatedBy should be null`() = runTest {
        maintenanceManager.initiatedBy.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // ENTER MAINTENANCE TESTS
    // ========================================

    @Test
    fun `enterMaintenance() should set isInMaintenance to true`() {
        // When
        maintenanceManager.enterMaintenance()

        // Then
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()
    }

    @Test
    fun `enterMaintenance() should set maintenanceReason`() = runTest {
        // Given
        val reason = "Software update"

        // When
        maintenanceManager.enterMaintenance(reason, null)

        // Then
        maintenanceManager.maintenanceReason.test {
            assertThat(awaitItem()).isEqualTo(reason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enterMaintenance() should set initiatedBy`() = runTest {
        // Given
        val initiatedBy = "System"

        // When
        maintenanceManager.enterMaintenance(null, initiatedBy)

        // Then
        maintenanceManager.initiatedBy.test {
            assertThat(awaitItem()).isEqualTo(initiatedBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `enterMaintenance() with all parameters should set all values`() = runTest {
        // Given
        val reason = "Hardware maintenance"
        val initiatedBy = "Tech Support"

        // When
        maintenanceManager.enterMaintenance(reason, initiatedBy)

        // Then
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()

        maintenanceManager.maintenanceReason.test {
            assertThat(awaitItem()).isEqualTo(reason)
            cancelAndIgnoreRemainingEvents()
        }

        maintenanceManager.initiatedBy.test {
            assertThat(awaitItem()).isEqualTo(initiatedBy)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isInMaintenance StateFlow should emit true when entering maintenance`() = runTest {
        maintenanceManager.isInMaintenance.test {
            // Initial state
            assertThat(awaitItem()).isFalse()

            // Enter maintenance
            maintenanceManager.enterMaintenance()
            assertThat(awaitItem()).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // EXIT MAINTENANCE TESTS
    // ========================================

    @Test
    fun `exitMaintenance() should set isInMaintenance to false`() {
        // Given
        maintenanceManager.enterMaintenance()
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()

        // When
        maintenanceManager.exitMaintenance()

        // Then
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isFalse()
    }

    @Test
    fun `exitMaintenance() should clear maintenanceReason`() = runTest {
        // Given
        maintenanceManager.enterMaintenance("Test reason", null)

        // When
        maintenanceManager.exitMaintenance()

        // Then
        maintenanceManager.maintenanceReason.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `exitMaintenance() should clear initiatedBy`() = runTest {
        // Given
        maintenanceManager.enterMaintenance(null, "Admin")

        // When
        maintenanceManager.exitMaintenance()

        // Then
        maintenanceManager.initiatedBy.test {
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isInMaintenance StateFlow should emit false when exiting maintenance`() = runTest {
        maintenanceManager.isInMaintenance.test {
            // Initial state
            assertThat(awaitItem()).isFalse()

            // Enter maintenance
            maintenanceManager.enterMaintenance()
            assertThat(awaitItem()).isTrue()

            // Exit maintenance
            maintenanceManager.exitMaintenance()
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // EDGE CASE TESTS
    // ========================================

    @Test
    fun `exitMaintenance() when not in maintenance should not throw`() {
        // Given - Not in maintenance

        // When/Then - Should not throw
        maintenanceManager.exitMaintenance()
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isFalse()
    }

    @Test
    fun `double enterMaintenance() should update values`() = runTest {
        // Given
        maintenanceManager.enterMaintenance("First reason", "First initiator")

        // When - Enter again with different values
        maintenanceManager.enterMaintenance("Second reason", "Second initiator")

        // Then - Should have second values
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()

        maintenanceManager.maintenanceReason.test {
            assertThat(awaitItem()).isEqualTo("Second reason")
            cancelAndIgnoreRemainingEvents()
        }

        maintenanceManager.initiatedBy.test {
            assertThat(awaitItem()).isEqualTo("Second initiator")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isCurrentlyInMaintenance() should reflect current state`() {
        // Initial
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isFalse()

        // Enter maintenance
        maintenanceManager.enterMaintenance()
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()

        // Exit maintenance
        maintenanceManager.exitMaintenance()
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isFalse()
    }

    @Test
    fun `enterMaintenance with null parameters should work`() {
        // When
        maintenanceManager.enterMaintenance(null, null)

        // Then
        assertThat(maintenanceManager.isCurrentlyInMaintenance()).isTrue()
    }

    @Test
    fun `multiple enter and exit cycles should work correctly`() = runTest {
        maintenanceManager.isInMaintenance.test {
            // Initial
            assertThat(awaitItem()).isFalse()

            // Cycle 1
            maintenanceManager.enterMaintenance("Reason 1", "Admin 1")
            assertThat(awaitItem()).isTrue()
            maintenanceManager.exitMaintenance()
            assertThat(awaitItem()).isFalse()

            // Cycle 2
            maintenanceManager.enterMaintenance("Reason 2", "Admin 2")
            assertThat(awaitItem()).isTrue()
            maintenanceManager.exitMaintenance()
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
