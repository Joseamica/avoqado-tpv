package com.jaac.avoqado_tpv.core.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DraftOrderItemDaoTest
 *
 * Integration tests for DraftOrderItemDao.
 *
 * Tests:
 * ✅ Insert and retrieve items
 * ✅ Soft delete (markAsDeleted)
 * ✅ Query excludes DELETED items
 * ✅ Get pending/deleted items for sync
 * ✅ Update order ID (foreign key update)
 * ✅ Cascade delete when parent order removed
 * ✅ Update quantity
 * ✅ Replace local ID with server CUID
 * ✅ Get item count
 * ✅ Query by product ID
 */
@RunWith(AndroidJUnit4::class)
class DraftOrderItemDaoTest {

    private lateinit var database: AvoqadoDatabase
    private lateinit var draftOrderDao: DraftOrderDao
    private lateinit var draftOrderItemDao: DraftOrderItemDao

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
        draftOrderItemDao = database.draftOrderItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ========================================
    // BASIC CRUD TESTS
    // ========================================

    @Test
    fun insertAndRetrieve_shouldReturnSameItem() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(
            id = "item_1",
            orderId = "order_1",
            productId = "prod_1",
            productName = "Pizza",
            quantity = 2,
            unitPrice = "1000",
            totalPrice = "2000"
        )

        // When
        draftOrderItemDao.insert(item)
        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.id).isEqualTo("item_1")
        assertThat(retrieved?.orderId).isEqualTo("order_1")
        assertThat(retrieved?.productName).isEqualTo("Pizza")
        assertThat(retrieved?.quantity).isEqualTo(2)
        assertThat(retrieved?.unitPrice).isEqualTo("1000")
        assertThat(retrieved?.totalPrice).isEqualTo("2000")
    }

    @Test
    fun insert_withDuplicateId_shouldReplace() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1", quantity = 2)
        val item2 = createTestItem(id = "item_1", orderId = "order_1", quantity = 5) // Same ID, different quantity

        // When
        draftOrderItemDao.insert(item1)
        draftOrderItemDao.insert(item2) // Should replace

        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved?.quantity).isEqualTo(5)
    }

    @Test
    fun insertAll_shouldInsertMultipleItems() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val items = listOf(
            createTestItem(id = "item_1", orderId = "order_1", productName = "Pizza"),
            createTestItem(id = "item_2", orderId = "order_1", productName = "Pasta"),
            createTestItem(id = "item_3", orderId = "order_1", productName = "Salad")
        )

        // When
        draftOrderItemDao.insertAll(items)
        val retrieved = draftOrderItemDao.getItemsByOrder("order_1")

        // Then
        assertThat(retrieved).hasSize(3)
        assertThat(retrieved.map { it.productName }).containsExactly("Pizza", "Pasta", "Salad")
    }

    @Test
    fun update_shouldModifyExistingItem() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(id = "item_1", orderId = "order_1", quantity = 2, notes = "Original notes")
        draftOrderItemDao.insert(item)

        // When
        val updated = item.copy(quantity = 5, notes = "Updated notes")
        draftOrderItemDao.update(updated)

        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved?.quantity).isEqualTo(5)
        assertThat(retrieved?.notes).isEqualTo("Updated notes")
    }

    @Test
    fun delete_shouldRemoveItem() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(id = "item_1", orderId = "order_1")
        draftOrderItemDao.insert(item)

        // When
        draftOrderItemDao.delete(item)
        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved).isNull()
    }

    // ========================================
    // SOFT DELETE TESTS
    // ========================================

    @Test
    fun markAsDeleted_shouldNotRemoveButHideFromQueries() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(id = "item_1", orderId = "order_1")
        draftOrderItemDao.insert(item)

        // When
        draftOrderItemDao.markAsDeleted("item_1")

        // Then - getItemsByOrder excludes DELETED
        val visibleItems = draftOrderItemDao.getItemsByOrder("order_1")
        assertThat(visibleItems).isEmpty()

        // But getAllItemsByOrder includes DELETED
        val allItems = draftOrderItemDao.getAllItemsByOrder("order_1")
        assertThat(allItems).hasSize(1)
        assertThat(allItems[0].syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_DELETED)

        // And getItemById still returns it
        val directFetch = draftOrderItemDao.getItemById("item_1")
        assertThat(directFetch).isNotNull()
        assertThat(directFetch?.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_DELETED)
    }

    @Test
    fun getDeletedItemsByOrder_shouldReturnOnlyDeleted() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_SYNCED)
        val item2 = createTestItem(id = "item_2", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)
        val item3 = createTestItem(id = "item_3", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)

        draftOrderItemDao.insertAll(listOf(item1, item2, item3))

        // When
        val deletedItems = draftOrderItemDao.getDeletedItemsByOrder("order_1")

        // Then
        assertThat(deletedItems).hasSize(2)
        assertThat(deletedItems.map { it.id }).containsExactly("item_2", "item_3")
    }

    @Test
    fun deleteAll_shouldHardDeleteMultipleItems() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)
        val item2 = createTestItem(id = "item_2", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)

        draftOrderItemDao.insertAll(listOf(item1, item2))

        // When - Hard delete after server confirms
        draftOrderItemDao.deleteAll(listOf(item1, item2))

        // Then - Should be completely removed
        val allItems = draftOrderItemDao.getAllItemsByOrder("order_1")
        assertThat(allItems).isEmpty()
    }

    // ========================================
    // QUERY TESTS
    // ========================================

    @Test
    fun getItemsByOrder_shouldExcludeSoftDeleted() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1", productName = "Pizza")
        val item2 = createTestItem(id = "item_2", orderId = "order_1", productName = "Pasta")
        val item3 = createTestItem(id = "item_3", orderId = "order_1", productName = "Salad", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)

        draftOrderItemDao.insertAll(listOf(item1, item2, item3))

        // When
        val visibleItems = draftOrderItemDao.getItemsByOrder("order_1")

        // Then - Should only return non-deleted items
        assertThat(visibleItems).hasSize(2)
        assertThat(visibleItems.map { it.productName }).containsExactly("Pizza", "Pasta")
    }

    @Test
    fun getPendingItemsByOrder_shouldReturnOnlyPending() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val pending1 = createTestItem(id = "item_1", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING)
        val pending2 = createTestItem(id = "item_2", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING)
        val synced = createTestItem(id = "item_3", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_SYNCED)

        draftOrderItemDao.insertAll(listOf(pending1, pending2, synced))

        // When
        val result = draftOrderItemDao.getPendingItemsByOrder("order_1")

        // Then
        assertThat(result).hasSize(2)
        assertThat(result.map { it.id }).containsExactly("item_1", "item_2")
    }

    @Test
    fun getItemsByProduct_shouldReturnItemsAcrossOrders() = runTest {
        // Given
        val order1 = createTestOrder(id = "order_1")
        val order2 = createTestOrder(id = "order_2")
        draftOrderDao.insert(order1)
        draftOrderDao.insert(order2)

        val item1 = createTestItem(id = "item_1", orderId = "order_1", productId = "prod_pizza")
        val item2 = createTestItem(id = "item_2", orderId = "order_2", productId = "prod_pizza")
        val item3 = createTestItem(id = "item_3", orderId = "order_1", productId = "prod_pasta")

        draftOrderItemDao.insertAll(listOf(item1, item2, item3))

        // When
        val pizzaItems = draftOrderItemDao.getItemsByProduct("prod_pizza")

        // Then
        assertThat(pizzaItems).hasSize(2)
        assertThat(pizzaItems.map { it.orderId }).containsExactly("order_1", "order_2")
    }

    @Test
    fun getItemCount_shouldExcludeDeleted() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1")
        val item2 = createTestItem(id = "item_2", orderId = "order_1")
        val item3 = createTestItem(id = "item_3", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_DELETED)

        draftOrderItemDao.insertAll(listOf(item1, item2, item3))

        // When
        val count = draftOrderItemDao.getItemCount("order_1")

        // Then
        assertThat(count).isEqualTo(2)
    }

    // ========================================
    // SYNC STATUS TESTS
    // ========================================

    @Test
    fun updateSyncStatus_shouldModifyStatus() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(id = "item_1", orderId = "order_1", syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING)
        draftOrderItemDao.insert(item)

        // When
        draftOrderItemDao.updateSyncStatus("item_1", DraftOrderItemEntity.SYNC_STATUS_SYNCED)

        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_SYNCED)
    }

    // ========================================
    // QUANTITY UPDATE TESTS
    // ========================================

    @Test
    fun updateQuantity_shouldRecalculateTotalAndMarkPending() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(
            id = "item_1",
            orderId = "order_1",
            quantity = 2,
            unitPrice = "1000",
            totalPrice = "2000",
            syncStatus = DraftOrderItemEntity.SYNC_STATUS_SYNCED
        )
        draftOrderItemDao.insert(item)

        // When
        draftOrderItemDao.updateQuantity("item_1", newQuantity = 5, newTotalPrice = "5000")

        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved?.quantity).isEqualTo(5)
        assertThat(retrieved?.totalPrice).isEqualTo("5000")
        assertThat(retrieved?.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_PENDING)
    }

    // ========================================
    // ID REPLACEMENT TESTS
    // ========================================

    @Test
    fun replaceLocalIdWithServerCuid_shouldUpdateIdAndMetadata() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item = createTestItem(
            id = "local_item_abc",
            orderId = "order_1",
            isServerCreated = false,
            syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING
        )
        draftOrderItemDao.insert(item)

        // When
        draftOrderItemDao.replaceLocalIdWithServerCuid(
            localId = "local_item_abc",
            newId = "clw3k9x2b001..."
        )

        // Then
        val oldIdItem = draftOrderItemDao.getItemById("local_item_abc")
        val newIdItem = draftOrderItemDao.getItemById("clw3k9x2b001...")

        assertThat(oldIdItem).isNull() // Old ID should not exist
        assertThat(newIdItem).isNotNull()
        assertThat(newIdItem?.id).isEqualTo("clw3k9x2b001...")
        assertThat(newIdItem?.isServerCreated).isTrue()
        assertThat(newIdItem?.syncStatus).isEqualTo(DraftOrderItemEntity.SYNC_STATUS_SYNCED)
    }

    // ========================================
    // FOREIGN KEY TESTS
    // ========================================

    @Test
    fun updateOrderId_shouldUpdateForeignKey() = runTest {
        // Given
        val order1 = createTestOrder(id = "local_order_abc")
        draftOrderDao.insert(order1)

        val item1 = createTestItem(id = "item_1", orderId = "local_order_abc")
        val item2 = createTestItem(id = "item_2", orderId = "local_order_abc")
        draftOrderItemDao.insertAll(listOf(item1, item2))

        // When - Parent order ID changes from local UUID to server CUID
        draftOrderItemDao.updateOrderId("local_order_abc", "clw3k9x2b000...")

        // Then - All items should have new order ID
        val updatedItem1 = draftOrderItemDao.getItemById("item_1")
        val updatedItem2 = draftOrderItemDao.getItemById("item_2")

        assertThat(updatedItem1?.orderId).isEqualTo("clw3k9x2b000...")
        assertThat(updatedItem2?.orderId).isEqualTo("clw3k9x2b000...")
    }

    @Test
    fun deleteOrder_shouldCascadeDeleteItems() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val item1 = createTestItem(id = "item_1", orderId = "order_1")
        val item2 = createTestItem(id = "item_2", orderId = "order_1")
        draftOrderItemDao.insertAll(listOf(item1, item2))

        // When - Delete parent order
        draftOrderDao.delete(order)

        // Then - Items should be automatically deleted (CASCADE)
        val items = draftOrderItemDao.getAllItemsByOrder("order_1")
        assertThat(items).isEmpty()
    }

    // ========================================
    // FLOW TESTS
    // ========================================

    @Test
    fun getItemsByOrderFlow_shouldEmitUpdates() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        draftOrderItemDao.getItemsByOrderFlow("order_1").test {
            // Initial state - empty
            assertThat(awaitItem()).isEmpty()

            // When - Insert item
            val item = createTestItem(id = "item_1", orderId = "order_1", productName = "Pizza")
            draftOrderItemDao.insert(item)

            // Then - Should emit updated list
            val emitted1 = awaitItem()
            assertThat(emitted1).hasSize(1)
            assertThat(emitted1[0].productName).isEqualTo("Pizza")

            // When - Insert another item
            val item2 = createTestItem(id = "item_2", orderId = "order_1", productName = "Pasta")
            draftOrderItemDao.insert(item2)

            // Then - Should emit updated list
            val emitted2 = awaitItem()
            assertThat(emitted2).hasSize(2)

            // When - Soft delete item
            draftOrderItemDao.markAsDeleted("item_1")

            // Then - Should emit list without deleted item
            val emitted3 = awaitItem()
            assertThat(emitted3).hasSize(1)
            assertThat(emitted3[0].productName).isEqualTo("Pasta")

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========================================
    // MODIFIERS JSON TESTS
    // ========================================

    @Test
    fun insert_withModifiersJson_shouldStoreAndRetrieveCorrectly() = runTest {
        // Given
        val order = createTestOrder(id = "order_1")
        draftOrderDao.insert(order)

        val modifiersJson = """[{"id":"mod_1","name":"Extra Cheese","price":"200"}]"""
        val item = createTestItem(
            id = "item_1",
            orderId = "order_1",
            modifiers = modifiersJson
        )

        // When
        draftOrderItemDao.insert(item)
        val retrieved = draftOrderItemDao.getItemById("item_1")

        // Then
        assertThat(retrieved?.modifiers).isEqualTo(modifiersJson)
    }

    // ========================================
    // HELPER FUNCTIONS
    // ========================================

    private fun createTestOrder(
        id: String = "test_order_${System.currentTimeMillis()}",
        venueId: String = "venue_test",
        orderNumber: String = "ORD-${System.currentTimeMillis()}"
    ): DraftOrderEntity {
        return DraftOrderEntity(
            id = id,
            venueId = venueId,
            orderNumber = orderNumber,
            tableId = null,
            tableName = null,
            covers = 1,
            waiterId = null,
            waiterName = null,
            customerName = null,
            customerPhone = null,
            specialRequests = null,
            status = "PENDING",
            kitchenStatus = "PENDING",
            paymentStatus = "PENDING",
            orderType = "DINE_IN",
            subtotal = "0",
            discountAmount = "0",
            tax = "0",
            total = "0",
            notes = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            version = 0,
            syncStatus = DraftOrderEntity.SYNC_STATUS_PENDING,
            isServerCreated = false,
            lastSyncAt = null,
            conflictData = null
        )
    }

    private fun createTestItem(
        id: String = "test_item_${System.currentTimeMillis()}",
        orderId: String,
        productId: String = "prod_test",
        productName: String = "Test Product",
        productSku: String? = null,
        quantity: Int = 1,
        unitPrice: String = "1000",
        totalPrice: String = "1000",
        modifiers: String = "[]",
        notes: String? = null,
        kitchenStatus: String = "PENDING",
        createdAt: Long = System.currentTimeMillis(),
        sentToKitchenAt: Long? = null,
        syncStatus: String = DraftOrderItemEntity.SYNC_STATUS_PENDING,
        isServerCreated: Boolean = false
    ): DraftOrderItemEntity {
        return DraftOrderItemEntity(
            id = id,
            orderId = orderId,
            productId = productId,
            productName = productName,
            productSku = productSku,
            quantity = quantity,
            unitPrice = unitPrice,
            totalPrice = totalPrice,
            modifiers = modifiers,
            notes = notes,
            kitchenStatus = kitchenStatus,
            createdAt = createdAt,
            sentToKitchenAt = sentToKitchenAt,
            syncStatus = syncStatus,
            isServerCreated = isServerCreated
        )
    }
}
