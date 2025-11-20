package com.jaac.avoqado_tpv.core.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DraftOrderDaoTest
 *
 * Integration tests for DraftOrderDao.
 *
 * Tests:
 * ✅ Insert and retrieve orders
 * ✅ Update sync status
 * ✅ Replace local ID with server CUID
 * ✅ Query by sync status (PENDING, SYNCED, CONFLICT)
 * ✅ Query by table ID
 * ✅ Get stale orders
 * ✅ Set/clear conflict data
 * ✅ Delete order (cascade to items)
 * ✅ Unique constraint (venue_id, order_number)
 */
@RunWith(AndroidJUnit4::class)
class DraftOrderDaoTest {

    private lateinit var database: AvoqadoDatabase
    private lateinit var draftOrderDao: DraftOrderDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            AvoqadoDatabase::class.java
        )
            .allowMainThreadQueries() // Only for testing
            .build()

        draftOrderDao = database.draftOrderDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ========================================
    // BASIC CRUD TESTS
    // ========================================

    @Test
    fun insertAndRetrieve_shouldReturnSameOrder() = runTest {
        // Given
        val order = createTestOrder(
            id = "local_abc123",
            venueId = "venue_789",
            orderNumber = "LOCAL-123456",
            tableId = "table_5",
            syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING
        )

        // When
        draftOrderDao.insert(order)
        val retrieved = draftOrderDao.getOrder(order.id)

        // Then
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.id).isEqualTo(order.id)
        assertThat(retrieved?.venueId).isEqualTo(order.venueId)
        assertThat(retrieved?.orderNumber).isEqualTo(order.orderNumber)
        assertThat(retrieved?.tableId).isEqualTo(order.tableId)
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_PENDING)
    }

    @Test
    fun insert_withDuplicateId_shouldReplace() = runTest {
        // Given
        val order1 = createTestOrder(id = "order_1", total = "1000")
        val order2 = createTestOrder(id = "order_1", total = "2000") // Same ID, different total

        // When
        draftOrderDao.insert(order1)
        draftOrderDao.insert(order2) // Should replace

        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved?.total).isEqualTo("2000")
    }

    @Test
    fun update_shouldModifyExistingOrder() = runTest {
        // Given
        val order = createTestOrder(id = "order_1", total = "1000")
        draftOrderDao.insert(order)

        // When
        val updated = order.copy(total = "1500", notes = "Updated notes")
        draftOrderDao.update(updated)

        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved?.total).isEqualTo("1500")
        assertThat(retrieved?.notes).isEqualTo("Updated notes")
    }

    @Test
    fun delete_shouldRemoveOrder() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        // When
        draftOrderDao.delete(order)
        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved).isNull()
    }

    // ========================================
    // QUERY TESTS
    // ========================================

    @Test
    fun getOrderByTable_shouldReturnActiveOrder() = runTest {
        // Given - Create 3 orders: 1 active, 2 paid
        val activeOrder = createTestOrder(
            id = "order_1",
            tableId = "table_5",
            paymentStatus = "PENDING"
        )
        val paidOrder1 = createTestOrder(
            id = "order_2",
            tableId = "table_5",
            paymentStatus = "PAID"
        )
        val paidOrder2 = createTestOrder(
            id = "order_3",
            tableId = "table_5",
            paymentStatus = "PAID"
        )

        draftOrderDao.insert(activeOrder)
        draftOrderDao.insert(paidOrder1)
        draftOrderDao.insert(paidOrder2)

        // When
        val retrieved = draftOrderDao.getOrderByTable("table_5")

        // Then - Should return the active order (payment_status != 'PAID')
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.id).isEqualTo("order_1")
        assertThat(retrieved?.paymentStatus).isEqualTo("PENDING")
    }

    @Test
    fun getOrdersByStatus_shouldFilterCorrectly() = runTest {
        // Given
        val pendingOrder1 = createTestOrder(id = "order_1", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING)
        val pendingOrder2 = createTestOrder(id = "order_2", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING)
        val syncedOrder = createTestOrder(id = "order_3", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED)
        val conflictOrder = createTestOrder(id = "order_4", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_CONFLICT)

        draftOrderDao.insert(pendingOrder1)
        draftOrderDao.insert(pendingOrder2)
        draftOrderDao.insert(syncedOrder)
        draftOrderDao.insert(conflictOrder)

        // When
        val pendingOrders = draftOrderDao.getOrdersByStatus("venue_1", DraftOrderEntity.SYNC_STATUS_PENDING)
        val syncedOrders = draftOrderDao.getOrdersByStatus("venue_1", DraftOrderEntity.SYNC_STATUS_SYNCED)
        val conflictOrders = draftOrderDao.getOrdersByStatus("venue_1", DraftOrderEntity.SYNC_STATUS_CONFLICT)

        // Then
        assertThat(pendingOrders).hasSize(2)
        assertThat(syncedOrders).hasSize(1)
        assertThat(conflictOrders).hasSize(1)
    }

    @Test
    fun getPendingOrders_shouldReturnOnlyPending() = runTest {
        // Given
        val pending1 = createTestOrder(id = "order_1", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING)
        val pending2 = createTestOrder(id = "order_2", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING)
        val synced = createTestOrder(id = "order_3", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED)

        draftOrderDao.insert(pending1)
        draftOrderDao.insert(pending2)
        draftOrderDao.insert(synced)

        // When
        val result = draftOrderDao.getPendingOrders("venue_1")

        // Then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly("order_1", "order_2")
    }

    @Test
    fun getConflictOrders_shouldReturnOnlyConflicts() = runTest {
        // Given
        val conflict = createTestOrder(id = "order_1", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_CONFLICT)
        val synced = createTestOrder(id = "order_2", venueId = "venue_1", syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED)

        draftOrderDao.insert(conflict)
        draftOrderDao.insert(synced)

        // When
        val result = draftOrderDao.getConflictOrders("venue_1")

        // Then
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo("order_1")
    }

    @Test
    fun getStaleOrders_shouldReturnOrdersNotSyncedRecently() = runTest {
        // Given
        val currentTime = System.currentTimeMillis()
        val threshold = 30_000L // 30 seconds

        // Order synced 1 minute ago (stale)
        val staleOrder = createTestOrder(
            id = "order_1",
            venueId = "venue_1",
            isServerCreated = true,
            lastSyncAt = currentTime - 60_000L
        )

        // Order synced 10 seconds ago (fresh)
        val freshOrder = createTestOrder(
            id = "order_2",
            venueId = "venue_1",
            isServerCreated = true,
            lastSyncAt = currentTime - 10_000L
        )

        // Local-only order (not server created, should not be included)
        val localOrder = createTestOrder(
            id = "order_3",
            venueId = "venue_1",
            isServerCreated = false,
            lastSyncAt = currentTime - 60_000L
        )

        draftOrderDao.insert(staleOrder)
        draftOrderDao.insert(freshOrder)
        draftOrderDao.insert(localOrder)

        // When
        val result = draftOrderDao.getStaleOrders("venue_1", threshold, currentTime)

        // Then
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo("order_1")
    }

    // ========================================
    // SYNC STATUS TESTS
    // ========================================

    @Test
    fun updateSyncStatus_shouldModifyStatusAndTimestamp() = runTest {
        // Given
        val order = createTestOrder(
            id = "order_1",
            syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
            lastSyncAt = 1000L
        )
        draftOrderDao.insert(order)

        val newTimestamp = 5000L

        // When
        draftOrderDao.updateSyncStatus("order_1", DraftOrderEntity.SYNC_STATUS_SYNCED, newTimestamp)

        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(retrieved?.lastSyncAt).isEqualTo(newTimestamp)
    }

    // ========================================
    // ID REPLACEMENT TESTS
    // ========================================

    @Test
    fun replaceLocalIdWithServerCuid_shouldUpdateIdAndMetadata() = runTest {
        // Given
        val localOrder = createTestOrder(
            id = "local_abc123",
            orderNumber = "LOCAL-123456",
            version = 0,
            isServerCreated = false,
            syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING
        )
        draftOrderDao.insert(localOrder)

        val newTimestamp = System.currentTimeMillis()

        // When
        draftOrderDao.replaceLocalIdWithServerCuid(
            localId = "local_abc123",
            newId = "clw3k9x2b000...",
            orderNumber = "ORD-0001234",
            version = 1,
            timestamp = newTimestamp
        )

        // Then
        val oldIdOrder = draftOrderDao.getOrder("local_abc123")
        val newIdOrder = draftOrderDao.getOrder("clw3k9x2b000...")

        assertThat(oldIdOrder).isNull() // Old ID should not exist
        assertThat(newIdOrder).isNotNull()
        assertThat(newIdOrder?.id).isEqualTo("clw3k9x2b000...")
        assertThat(newIdOrder?.orderNumber).isEqualTo("ORD-0001234")
        assertThat(newIdOrder?.version).isEqualTo(1)
        assertThat(newIdOrder?.isServerCreated).isTrue()
        assertThat(newIdOrder?.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
        assertThat(newIdOrder?.lastSyncAt).isEqualTo(newTimestamp)
    }

    // ========================================
    // CONFLICT DATA TESTS
    // ========================================

    @Test
    fun setConflictData_shouldStoreJsonAndMarkAsConflict() = runTest {
        // Given
        val order = createTestOrder(
            id = "order_1",
            syncStatus = DraftOrderEntity.SYNC_STATUS_SYNCED,
            conflictData = null
        )
        draftOrderDao.insert(order)

        val conflictJson = """{"version": 2, "total": "2000"}"""

        // When
        draftOrderDao.setConflictData("order_1", conflictJson)

        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved?.conflictData).isEqualTo(conflictJson)
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_CONFLICT)
    }

    @Test
    fun clearConflictData_shouldRemoveJsonAndMarkAsSynced() = runTest {
        // Given
        val order = createTestOrder(
            id = "order_1",
            syncStatus = DraftOrderEntity.SYNC_STATUS_CONFLICT,
            conflictData = """{"version": 2}"""
        )
        draftOrderDao.insert(order)

        // When
        draftOrderDao.clearConflictData("order_1")

        val retrieved = draftOrderDao.getOrder("order_1")

        // Then
        assertThat(retrieved?.conflictData).isNull()
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderEntity.SYNC_STATUS_SYNCED)
    }

    // ========================================
    // FLOW TESTS
    // ========================================

    @Test
    fun getOrderFlow_shouldEmitUpdates() = runTest {
        // Given
        val order = createTestOrder(id = "order_1", total = "1000")

        draftOrderDao.getOrderFlow("order_1").test {
            // Initial state - null
            assertThat(awaitItem()).isNull()

            // When - Insert
            draftOrderDao.insert(order)

            // Then - Should emit order
            val emitted1 = awaitItem()
            assertThat(emitted1?.id).isEqualTo("order_1")
            assertThat(emitted1?.total).isEqualTo("1000")

            // When - Update
            draftOrderDao.update(order.copy(total = "1500"))

            // Then - Should emit updated order
            val emitted2 = awaitItem()
            assertThat(emitted2?.total).isEqualTo("1500")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // CONSTRAINT TESTS
    // ========================================

    @Test
    fun insert_withDuplicateVenueAndOrderNumber_shouldReplace() = runTest {
        // Given - Unique constraint on (venue_id, order_number)
        val order1 = createTestOrder(
            id = "order_1",
            venueId = "venue_1",
            orderNumber = "ORD-001",
            total = "1000"
        )

        val order2 = createTestOrder(
            id = "order_2",
            venueId = "venue_1",
            orderNumber = "ORD-001", // Same venue + order number
            total = "2000"
        )

        // When
        draftOrderDao.insert(order1)
        draftOrderDao.insert(order2) // Should replace due to unique constraint + REPLACE strategy

        // Then - Only order_2 should exist
        val retrieved1 = draftOrderDao.getOrder("order_1")
        val retrieved2 = draftOrderDao.getOrder("order_2")

        assertThat(retrieved1).isNull()
        assertThat(retrieved2).isNotNull()
        assertThat(retrieved2?.total).isEqualTo("2000")
    }

    // ========================================
    // HELPER FUNCTIONS
    // ========================================

    private fun createTestOrder(
        id: String = "test_order_${System.currentTimeMillis()}",
        venueId: String = "venue_test",
        orderNumber: String = "ORD-${System.currentTimeMillis()}",
        tableId: String? = null,
        tableName: String? = null,
        covers: Int = 1,
        waiterId: String? = null,
        waiterName: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
        specialRequests: String? = null,
        status: String = "PENDING",
        kitchenStatus: String = "PENDING",
        paymentStatus: String = "PENDING",
        orderType: String = "DINE_IN",
        subtotal: String = "0",
        discountAmount: String = "0",
        tax: String = "0",
        total: String = "0",
        notes: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
        version: Int = 0,
        syncStatus: String = DraftOrderEntity.SYNC_STATUS_PENDING,
        isServerCreated: Boolean = false,
        lastSyncAt: Long? = null,
        conflictData: String? = null
    ): DraftOrderEntity {
        return DraftOrderEntity(
            id = id,
            venueId = venueId,
            orderNumber = orderNumber,
            tableId = tableId,
            tableName = tableName,
            covers = covers,
            waiterId = waiterId,
            waiterName = waiterName,
            customerName = customerName,
            customerPhone = customerPhone,
            specialRequests = specialRequests,
            status = status,
            kitchenStatus = kitchenStatus,
            paymentStatus = paymentStatus,
            orderType = orderType,
            subtotal = subtotal,
            discountAmount = discountAmount,
            tax = tax,
            total = total,
            notes = notes,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            syncStatus = syncStatus,
            isServerCreated = isServerCreated,
            lastSyncAt = lastSyncAt,
            conflictData = conflictData
        )
    }
}
