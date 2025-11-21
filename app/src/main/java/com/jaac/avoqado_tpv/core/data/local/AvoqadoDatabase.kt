package com.jaac.avoqado_tpv.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.dao.HistoricalPeriodDao
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.entities.HistoricalPeriodEntity
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity

/**
 * Room database for Avoqado TPV local data persistence.
 *
 * **Current Version:** 8
 * - v1 → v2: Added blumonSerialNumber to PendingPaymentEntity for merchant account tracking
 * - v2 → v3: Added merchantAccountId to PendingPaymentEntity (provider-agnostic migration)
 * - v3 → v4: Added rating to PendingPaymentEntity (user rating feature - 2025-01-11)
 * - v4 → v5: Added DraftOrderEntity + DraftOrderItemEntity (local-first order management - 2025-01-19)
 * - v5 → v6: Added HistoricalPeriodEntity (offline cache for historical reports - 2025-01-19)
 * - v6 → v7: Fixed FOREIGN KEY with ON UPDATE CASCADE (fixes sync errors - 2025-11-20)
 * - v7 → v8: Added merchant account tracking to DraftOrderEntity (split payment validation - 2025-11-20)
 *
 * **Entities:**
 * - PendingPaymentEntity: Offline queue for failed payment recordings
 * - DraftOrderEntity: Local-first order storage with hybrid sync (Toast POS approach)
 * - DraftOrderItemEntity: Order items with soft delete and debounced sync
 * - HistoricalPeriodEntity: Offline cache for historical sales data (network-first + cache-fallback)
 *
 * **Future Entities:**
 * - PaymentHistoryEntity: Local cache of successful payments for offline access
 * - SyncLogEntity: Audit log of all sync attempts
 * - DeviceConfigEntity: Persistent device configuration (terminal ID, merchant accounts, etc.)
 *
 * **Migration Strategy:**
 * - For development: `fallbackToDestructiveMigration()` (data loss acceptable)
 * - For production: Define explicit migrations with `addMigrations(MIGRATION_2_3, ..., MIGRATION_7_8)`
 *
 * **Usage:**
 * ```kotlin
 * // Injected via Hilt
 * @Inject lateinit var database: AvoqadoDatabase
 *
 * // Access DAOs
 * val pendingPaymentDao = database.pendingPaymentDao()
 * val draftOrderDao = database.draftOrderDao()
 * val draftOrderItemDao = database.draftOrderItemDao()
 * val historicalPeriodDao = database.historicalPeriodDao()
 * ```
 *
 * **World-Class Examples:**
 * - Square POS: Uses Room for offline order queue + inventory cache + historical reports cache
 * - Toast POS: Room for payment history + shift summaries + debounced order sync + sales analytics cache
 * - Stripe Terminal: Room for offline transaction queue + reports cache
 */
@Database(
    entities = [
        PendingPaymentEntity::class,
        DraftOrderEntity::class,
        DraftOrderItemEntity::class,
        HistoricalPeriodEntity::class
    ],
    version = 8, // ⭐ Version 8: Added merchant account tracking for split payment validation
    exportSchema = false // Set to true when adding migrations for production
)
abstract class AvoqadoDatabase : RoomDatabase() {

    /**
     * DAO for offline payment queue operations.
     *
     * **Use Cases:**
     * - Insert failed payment recordings
     * - Fetch pending payments for retry
     * - Mark payments as synced after success
     * - Cleanup old synced payments
     */
    abstract fun pendingPaymentDao(): PendingPaymentDao

    /**
     * DAO for local-first draft orders.
     *
     * **Use Cases:**
     * - Create orders locally (instant UI, no network latency)
     * - Sync orders to backend (debounced auto-save)
     * - Track sync status (PENDING, SYNCED, CONFLICT)
     * - Handle multi-terminal conflicts
     */
    abstract fun draftOrderDao(): DraftOrderDao

    /**
     * DAO for draft order items.
     *
     * **Use Cases:**
     * - Add items to orders (local-first)
     * - Soft delete items (mark as DELETED, sync later)
     * - Batch sync pending items to backend
     * - Track item-level sync status
     */
    abstract fun draftOrderItemDao(): DraftOrderItemDao

    /**
     * DAO for historical period cache.
     *
     * **Use Cases:**
     * - Cache historical sales data from network (network-first strategy)
     * - Serve cached data when offline (24h TTL)
     * - Clear stale cache (data older than 24h)
     * - Clear cache on venue switch/logout
     */
    abstract fun historicalPeriodDao(): HistoricalPeriodDao

    companion object {
        const val DATABASE_NAME = "avoqado_database"

        /**
         * Migration from version 2 to version 3: Add merchantAccountId column.
         *
         * **Provider-Agnostic Migration (2025-01-10)**
         * - Adds merchant_account_id column (structured FK to MerchantAccount)
         * - Preserves existing blumon_serial_number column (legacy fallback)
         * - Default value: "" (empty string) for backward compatibility
         *
         * **Why Migration (Not Destructive):**
         * - Production devices may have queued payments in pending_payments table
         * - Destructive migration would lose offline payment queue
         * - Migration preserves data integrity
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE pending_payments ADD COLUMN merchant_account_id TEXT NOT NULL DEFAULT '';
         * ```
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add merchantAccountId column with default empty string (backward compatibility)
                database.execSQL(
                    "ALTER TABLE pending_payments ADD COLUMN merchant_account_id TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * Migration from version 3 to version 4: Add rating column.
         *
         * **User Rating Feature (2025-01-11)**
         * - Adds rating column (nullable Int for 1-5 stars rating)
         * - Default value: NULL (rating is optional)
         * - Preserves existing queued payments
         *
         * **Why Migration (Not Destructive):**
         * - Production devices may have queued payments without rating
         * - Migration preserves offline payment queue integrity
         * - NULL is acceptable for payments without rating
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE pending_payments ADD COLUMN rating INTEGER DEFAULT NULL;
         * ```
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add rating column (nullable)
                database.execSQL(
                    "ALTER TABLE pending_payments ADD COLUMN rating INTEGER DEFAULT NULL"
                )
            }
        }

        /**
         * Migration from version 4 to version 5: Add draft orders tables.
         *
         * **Local-First Order Management (2025-01-19)**
         * - Creates draft_orders table for hybrid sync order storage
         * - Creates draft_order_items table with foreign key to draft_orders
         * - Enables Toast POS approach: local-first + debounced auto-save
         *
         * **Why This Migration:**
         * - Transforms architecture from immediate backend persistence to local-first
         * - Improves UI responsiveness (0ms latency for add/remove items)
         * - Reduces server load (5s debounced sync batches changes)
         * - Enables offline order creation and modification
         *
         * **Tables Created:**
         * 1. **draft_orders**: Order header with sync status tracking
         *    - Indexes: (venue_id, order_number), table_id, sync_status, updated_at
         *    - Supports SYNCED, PENDING, SYNCING, CONFLICT states
         *
         * 2. **draft_order_items**: Order line items with soft delete
         *    - Foreign key: order_id → draft_orders(id) ON DELETE CASCADE
         *    - Indexes: order_id, product_id, sync_status
         *    - Supports SYNCED, PENDING, SYNCING, DELETED states
         *
         * **Sync Strategy:**
         * - Create order locally with UUID (e.g., "local_abc123")
         * - After first sync, replace UUID with server CUID
         * - Debounced auto-save: 5s delay after last change
         * - Immediate sync: Send to Kitchen, Payment, Conflicts
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create draft_orders table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS draft_orders (
                        id TEXT PRIMARY KEY NOT NULL,
                        venue_id TEXT NOT NULL,
                        order_number TEXT NOT NULL,
                        table_id TEXT,
                        table_name TEXT,
                        covers INTEGER NOT NULL,
                        waiter_id TEXT,
                        waiter_name TEXT,
                        customer_name TEXT,
                        customer_phone TEXT,
                        special_requests TEXT,
                        status TEXT NOT NULL,
                        kitchen_status TEXT NOT NULL,
                        payment_status TEXT NOT NULL,
                        order_type TEXT NOT NULL,
                        subtotal TEXT NOT NULL,
                        discount_amount TEXT NOT NULL,
                        tax TEXT NOT NULL,
                        total TEXT NOT NULL,
                        notes TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        sync_status TEXT NOT NULL,
                        is_server_created INTEGER NOT NULL,
                        last_sync_at INTEGER,
                        conflict_data TEXT
                    )
                    """.trimIndent()
                )

                // Create indexes for draft_orders
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_draft_orders_venue_order ON draft_orders(venue_id, order_number)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_orders_table_id ON draft_orders(table_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_orders_sync_status ON draft_orders(sync_status)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_orders_updated_at ON draft_orders(updated_at)"
                )

                // Create draft_order_items table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS draft_order_items (
                        id TEXT PRIMARY KEY NOT NULL,
                        order_id TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        product_name TEXT NOT NULL,
                        product_sku TEXT,
                        quantity INTEGER NOT NULL,
                        unit_price TEXT NOT NULL,
                        total_price TEXT NOT NULL,
                        modifiers TEXT NOT NULL,
                        notes TEXT,
                        kitchen_status TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        sent_to_kitchen_at INTEGER,
                        sync_status TEXT NOT NULL,
                        is_server_created INTEGER NOT NULL,
                        FOREIGN KEY(order_id) REFERENCES draft_orders(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // Create indexes for draft_order_items
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_order_id ON draft_order_items(order_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_product_id ON draft_order_items(product_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_sync_status ON draft_order_items(sync_status)"
                )
            }
        }

        /**
         * Migration from version 5 to version 6: Add historical periods cache table.
         *
         * **Offline Cache for Historical Reports (2025-01-19)**
         * - Creates historical_periods table for network-first + cache-fallback strategy
         * - Enables instant offline access to historical sales data
         * - 24-hour TTL for automatic cache expiration
         *
         * **Why This Migration:**
         * - Improves UX: Show historical data immediately when offline
         * - Reduces API calls: Serve cached data while fetching fresh data
         * - Enables offline analytics: Users can view historical trends without internet
         * - Tenant isolation: Cache scoped to venue_id
         *
         * **Table Created:**
         * **historical_periods**: Cached historical sales data by period
         * - Composite unique index: (venue_id, grouping, period_start) prevents duplicates
         * - Index on cached_at: Fast TTL-based cleanup queries
         * - BigDecimal fields stored as TEXT (Room limitation)
         * - Timestamps stored as INTEGER (Unix milliseconds)
         *
         * **Cache Strategy (Toast POS Pattern):**
         * - Network-first: Try API, cache response, serve cached on offline
         * - Upsert: REPLACE on conflict via unique index
         * - TTL: 24 hours (stale data auto-expires)
         * - Invalidation: Clear on venue switch, logout, or manual refresh
         *
         * **SQL:**
         * ```sql
         * CREATE TABLE historical_periods (
         *   id INTEGER PRIMARY KEY AUTOINCREMENT,
         *   venue_id TEXT NOT NULL,
         *   grouping TEXT NOT NULL,
         *   period_start INTEGER NOT NULL,
         *   period_end INTEGER NOT NULL,
         *   label TEXT NOT NULL,
         *   subtitle TEXT NOT NULL,
         *   total_sales TEXT NOT NULL,
         *   total_orders INTEGER NOT NULL,
         *   total_products INTEGER NOT NULL,
         *   average_order_value TEXT NOT NULL,
         *   sales_change TEXT,
         *   orders_change TEXT,
         *   cached_at INTEGER NOT NULL
         * );
         * CREATE INDEX idx_venue_grouping_period ON historical_periods(venue_id, grouping, period_start);
         * CREATE INDEX idx_cached_at ON historical_periods(cached_at);
         * CREATE UNIQUE INDEX idx_unique_period ON historical_periods(venue_id, grouping, period_start);
         * ```
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create historical_periods table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS historical_periods (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        venue_id TEXT NOT NULL,
                        grouping TEXT NOT NULL,
                        period_start INTEGER NOT NULL,
                        period_end INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        subtitle TEXT NOT NULL,
                        total_sales TEXT NOT NULL,
                        total_orders INTEGER NOT NULL,
                        total_products INTEGER NOT NULL,
                        average_order_value TEXT NOT NULL,
                        sales_change TEXT,
                        orders_change TEXT,
                        cached_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create composite index for efficient queries (venue + grouping + time range)
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_historical_periods_venue_grouping_period ON historical_periods(venue_id, grouping, period_start)"
                )

                // Create index for cache expiration queries
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_historical_periods_cached_at ON historical_periods(cached_at)"
                )

                // Create unique index to prevent duplicate periods (upsert strategy)
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_historical_periods_unique ON historical_periods(venue_id, grouping, period_start)"
                )
            }
        }

        /**
         * Migration from version 6 to version 7: Fix FOREIGN KEY with ON UPDATE CASCADE.
         *
         * **FOREIGN KEY Constraint Fix (2025-11-20)**
         * - Adds ON UPDATE CASCADE to draft_order_items foreign key
         * - Fixes FOREIGN KEY constraint error when syncing orders to server
         * - Enables automatic update of child items when parent order ID changes
         *
         * **Why This Migration:**
         * - Current FK: `FOREIGN KEY(order_id) REFERENCES draft_orders(id) ON DELETE CASCADE`
         * - Problem: When order ID changes from local UUID → server CUID, items can't update
         * - Solution: Add ON UPDATE CASCADE to automatically propagate ID changes
         * - Error fixed: `android.database.sqlite.SQLiteConstraintException: FOREIGN KEY constraint failed`
         *
         * **Migration Strategy (SQLite Limitation):**
         * SQLite doesn't support ALTER FOREIGN KEY, so we must:
         * 1. Create new table with correct FK constraint
         * 2. Copy all data from old table
         * 3. Drop old table
         * 4. Rename new table to original name
         * 5. Recreate indexes
         *
         * **Data Safety:**
         * - All existing data is preserved during table recreation
         * - Transaction ensures atomic operation (all-or-nothing)
         * - Indexes recreated to maintain query performance
         *
         * **SQL:**
         * ```sql
         * -- Create new table with ON UPDATE CASCADE
         * CREATE TABLE draft_order_items_new (
         *   ...,
         *   FOREIGN KEY(order_id) REFERENCES draft_orders(id)
         *     ON DELETE CASCADE
         *     ON UPDATE CASCADE  ← NEW!
         * );
         * -- Copy data
         * INSERT INTO draft_order_items_new SELECT * FROM draft_order_items;
         * -- Replace table
         * DROP TABLE draft_order_items;
         * ALTER TABLE draft_order_items_new RENAME TO draft_order_items;
         * -- Recreate indexes
         * ...
         * ```
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1: Create new table with ON UPDATE CASCADE
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS draft_order_items_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        order_id TEXT NOT NULL,
                        product_id TEXT NOT NULL,
                        product_name TEXT NOT NULL,
                        product_sku TEXT,
                        quantity INTEGER NOT NULL,
                        unit_price TEXT NOT NULL,
                        total_price TEXT NOT NULL,
                        modifiers TEXT NOT NULL,
                        notes TEXT,
                        kitchen_status TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        sent_to_kitchen_at INTEGER,
                        sync_status TEXT NOT NULL,
                        is_server_created INTEGER NOT NULL,
                        FOREIGN KEY(order_id) REFERENCES draft_orders(id)
                            ON DELETE CASCADE
                            ON UPDATE CASCADE
                    )
                    """.trimIndent()
                )

                // Step 2: Copy all data from old table to new table
                database.execSQL(
                    """
                    INSERT INTO draft_order_items_new
                    SELECT * FROM draft_order_items
                    """.trimIndent()
                )

                // Step 3: Drop old table
                database.execSQL("DROP TABLE draft_order_items")

                // Step 4: Rename new table to original name
                database.execSQL("ALTER TABLE draft_order_items_new RENAME TO draft_order_items")

                // Step 5: Recreate indexes
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_order_id ON draft_order_items(order_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_product_id ON draft_order_items(product_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_draft_order_items_sync_status ON draft_order_items(sync_status)"
                )
            }
        }

        /**
         * Migration from version 7 to version 8: Add merchant account tracking to orders.
         *
         * **Merchant Account Locking for Split Payments (2025-11-20)**
         * - Adds merchant_account_id column (tracks which merchant processed payment)
         * - Adds merchant_account_name column (for user-friendly error messages)
         * - Enables split payment validation (same merchant must be used)
         *
         * **Why This Migration:**
         * - Prevents split payment reconciliation issues (mixing merchants)
         * - Tracks payment history per merchant account
         * - Enables merchant-locked orders (already paid with Merchant A → must continue with A)
         * - Fixes P0 issue: merchant account mismatch in multi-merchant system
         *
         * **Business Logic:**
         * ```
         * Order created → merchantAccountId = null (no lock yet)
         * First payment processed → merchantAccountId = "merchant_A" (locked)
         * Second payment (split) → MUST use "merchant_A" (validation enforced)
         * ```
         *
         * **Data Safety:**
         * - Both columns nullable (backwards compatible)
         * - Existing orders: merchantAccountId = null (no restriction)
         * - New orders: merchantAccountId set after first payment
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE draft_orders ADD COLUMN merchant_account_id TEXT DEFAULT NULL;
         * ALTER TABLE draft_orders ADD COLUMN merchant_account_name TEXT DEFAULT NULL;
         * ```
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add merchant_account_id column (nullable - backwards compatible)
                database.execSQL(
                    "ALTER TABLE draft_orders ADD COLUMN merchant_account_id TEXT DEFAULT NULL"
                )

                // Add merchant_account_name column (nullable - for display purposes)
                database.execSQL(
                    "ALTER TABLE draft_orders ADD COLUMN merchant_account_name TEXT DEFAULT NULL"
                )
            }
        }
    }
}
