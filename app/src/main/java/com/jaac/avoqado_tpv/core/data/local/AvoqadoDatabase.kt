package com.jaac.avoqado_tpv.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jaac.avoqado_tpv.core.data.local.converters.ProductTypeConverters
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.dao.HistoricalPeriodDao
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.entities.CachedShiftEntity
import com.jaac.avoqado_tpv.features.verification.data.local.VerificationQueueDao
import com.jaac.avoqado_tpv.features.verification.data.local.VerificationQueueEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderEntity
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.entities.HistoricalPeriodEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductCategoryEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity

/**
 * Room database for Avoqado TPV local data persistence.
 *
 * **Current Version:** 12
 * - v1 → v2: Added blumonSerialNumber to PendingPaymentEntity for merchant account tracking
 * - v2 → v3: Added merchantAccountId to PendingPaymentEntity (provider-agnostic migration)
 * - v3 → v4: Added rating to PendingPaymentEntity (user rating feature - 2025-01-11)
 * - v4 → v5: Added DraftOrderEntity + DraftOrderItemEntity (local-first order management - 2025-01-19)
 * - v5 → v6: Added HistoricalPeriodEntity (offline cache for historical reports - 2025-01-19)
 * - v6 → v7: Fixed FOREIGN KEY with ON UPDATE CASCADE (fixes sync errors - 2025-11-20)
 * - v7 → v8: Added merchant account tracking to DraftOrderEntity (split payment validation - 2025-11-20)
 * - v8 → v9: Added ProductEntity + ProductCategoryEntity (cache-first product loading - 2025-11-24)
 * - v9 → v10: Added CachedShiftEntity (offline shift status display - Square/Toast prevention pattern - 2025-11-25)
 * - v10 → v11: Added paidAmount/remainingBalance for split payments (2025-11-26)
 * - v11 → v12: Added lastSplitType for split payment type restriction (2025-11-28)
 * - v12 → v13: Added color fields to ProductCategoryEntity and ProductEntity (2025-12-01)
 * - v13 → v14: Added VerificationQueueEntity for Step 4 sale verification queue (2025-12-11)
 *
 * **Entities:**
 * - PendingPaymentEntity: Offline queue for failed payment recordings
 * - DraftOrderEntity: Local-first order storage with hybrid sync (Toast POS approach)
 * - DraftOrderItemEntity: Order items with soft delete and debounced sync
 * - HistoricalPeriodEntity: Offline cache for historical sales data (network-first + cache-fallback)
 * - ProductEntity: Cache products for instant offline access (Toast POS pattern)
 * - ProductCategoryEntity: Cache product categories for instant offline access
 * - CachedShiftEntity: Offline cache for shift status (prevention pattern - shows last known state)
 * - VerificationQueueEntity: Offline queue for Step 4 sale verification (photos, barcodes)
 *
 * **Future Entities:**
 * - PaymentHistoryEntity: Local cache of successful payments for offline access
 * - SyncLogEntity: Audit log of all sync attempts
 * - DeviceConfigEntity: Persistent device configuration (terminal ID, merchant accounts, etc.)
 *
 * **Migration Strategy:**
 * - For development: `fallbackToDestructiveMigration()` (data loss acceptable)
 * - For production: Define explicit migrations with `addMigrations(MIGRATION_2_3, ..., MIGRATION_8_9)`
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
 * val productDao = database.productDao()
 * val productCategoryDao = database.productCategoryDao()
 * val cachedShiftDao = database.cachedShiftDao()
 * val verificationQueueDao = database.verificationQueueDao()
 * ```
 *
 * **World-Class Examples:**
 * - Square POS: Uses Room for offline order queue + inventory cache + historical reports cache
 * - Toast POS: Room for payment history + shift summaries + debounced order sync + sales analytics cache + product cache
 * - Stripe Terminal: Room for offline transaction queue + reports cache
 */
@Database(
    entities = [
        PendingPaymentEntity::class,
        DraftOrderEntity::class,
        DraftOrderItemEntity::class,
        HistoricalPeriodEntity::class,
        ProductEntity::class,
        ProductCategoryEntity::class,
        CachedShiftEntity::class,
        VerificationQueueEntity::class
    ],
    version = 14, // ⭐ Version 14: Added VerificationQueueEntity for Step 4 sale verification
    exportSchema = false // Set to true when adding migrations for production
)
@TypeConverters(ProductTypeConverters::class)  // Add ProductTypeConverters for ModifierGroups
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

    /**
     * DAO for product cache.
     *
     * **Use Cases:**
     * - Cache products from backend (cache-first strategy)
     * - Instant offline access (10ms vs 500-1000ms HTTP)
     * - Filter by category, availability
     * - 24h TTL auto-expiration
     */
    abstract fun productDao(): ProductDao

    /**
     * DAO for product category cache.
     *
     * **Use Cases:**
     * - Cache categories from backend
     * - Instant offline access
     * - 24h TTL auto-expiration
     */
    abstract fun productCategoryDao(): ProductCategoryDao

    /**
     * DAO for cached shift status.
     *
     * **Use Cases:**
     * - Cache last known shift state when online
     * - Display cached state when offline (prevention pattern)
     * - Show "Último estado conocido (hace X min)" when offline
     * - Block shift open/close operations when offline
     *
     * **Pattern (Square/Toast POS):**
     * - Online: Fetch from network, cache locally
     * - Offline: Show last known state, block operations
     * - Reconnection: Auto-sync via ConnectionEventManager
     */
    abstract fun cachedShiftDao(): CachedShiftDao

    /**
     * DAO for verification queue (Step 4 sale verification).
     *
     * **Use Cases:**
     * - Store verification photos and scanned barcodes locally
     * - Queue verifications for upload when offline
     * - Sync photos to Firebase Storage, metadata to backend
     * - Track sync status (PENDING, UPLOADING_PHOTOS, SYNCING, SYNCED, FAILED)
     *
     * **Pattern (Offline-First):**
     * - Capture: Save photos locally + barcode data
     * - Upload: Photos → Firebase Storage, get URLs
     * - Sync: Send metadata + URLs to backend API
     * - Cleanup: Delete synced records after confirmation
     */
    abstract fun verificationQueueDao(): VerificationQueueDao

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

        /**
         * Migration from version 8 to version 9: Add product cache tables.
         *
         * **Cache-First Product Loading (2025-11-24)**
         * - Creates products table for instant offline access
         * - Creates product_categories table for category filtering
         * - Reduces first paint from 500-1000ms → ~10ms
         *
         * **Why This Migration:**
         * - Eliminates HTTP blocking on first paint (network-first → cache-first)
         * - Improves perceived performance (products appear instantly)
         * - Enables offline product browsing
         * - Reduces server load (cache-first reduces API calls)
         *
         * **Tables Created:**
         * 1. **products**: Product cache with 24h TTL
         *    - Indexed by (venue_id, product_id) for uniqueness
         *    - Indexed by (venue_id, category_id) for fast filtering
         *    - Indexed by (venue_id, available) for available products
         *    - Indexed by cached_at for TTL cleanup
         *
         * 2. **product_categories**: Category cache with 24h TTL
         *    - Indexed by (venue_id, category_id) for uniqueness
         *    - Indexed by cached_at for TTL cleanup
         *
         * **Cache Strategy (Toast POS Pattern):**
         * - Cache-first: Emit cached data immediately (~10ms)
         * - Background refresh: Fetch fresh data from API and update cache
         * - TTL: 24 hours (auto-expire stale data)
         * - Invalidation: Clear on venue switch or explicit refresh
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create products table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS products (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        product_id TEXT NOT NULL,
                        venue_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        sku TEXT NOT NULL,
                        price TEXT NOT NULL,
                        category_id TEXT NOT NULL,
                        category_name TEXT NOT NULL,
                        description TEXT,
                        emoji TEXT NOT NULL,
                        image_url TEXT,
                        available INTEGER NOT NULL,
                        display_order INTEGER NOT NULL,
                        track_inventory INTEGER NOT NULL,
                        inventory_method TEXT,
                        available_quantity INTEGER,
                        modifier_groups_json TEXT NOT NULL,
                        cached_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create indexes for products
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_products_venue_product ON products(venue_id, product_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_products_venue_category ON products(venue_id, category_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_products_venue_available ON products(venue_id, available)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_products_cached_at ON products(cached_at)"
                )

                // Create product_categories table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS product_categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        category_id TEXT NOT NULL,
                        venue_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        display_order INTEGER NOT NULL,
                        product_count INTEGER NOT NULL,
                        emoji TEXT NOT NULL,
                        cached_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create indexes for product_categories
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_product_categories_venue_category ON product_categories(venue_id, category_id)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_product_categories_cached_at ON product_categories(cached_at)"
                )
            }
        }

        /**
         * Migration from version 9 to version 10: Add cached shift table.
         *
         * **Offline Shift Status (Square/Toast Prevention Pattern) - 2025-11-25**
         * - Creates cached_shift table for offline shift display
         * - Shows "Último estado conocido" when offline instead of "Sin turno activo"
         * - Blocks shift open/close operations when offline
         *
         * **Why This Migration:**
         * - Improves UX: Don't show misleading "Sin turno activo" when simply offline
         * - Prevents user confusion: Cache shows last known state with timestamp
         * - Prevention pattern: Block operations that could cause conflicts
         * - Square/Toast/Clover use this exact pattern
         *
         * **Table Created:**
         * **cached_shift**: Cached last known shift state
         * - Primary key: shift ID (from server)
         * - Unique index: (venue_id) - only one cached shift per venue
         * - No TTL: Always show last known state
         *
         * **Cache Strategy (Prevention Pattern):**
         * - Online: Fetch from network, cache response
         * - Offline: Show cached state with "hace X min"
         * - Block operations: Shift open/close disabled with tooltip
         * - Auto-sync: ConnectionEventManager triggers refresh on reconnection
         *
         * **SQL:**
         * ```sql
         * CREATE TABLE cached_shift (
         *   id TEXT PRIMARY KEY NOT NULL,
         *   venue_id TEXT NOT NULL,
         *   status TEXT NOT NULL,
         *   staff_id TEXT NOT NULL,
         *   staff_name TEXT NOT NULL,
         *   start_time TEXT NOT NULL,
         *   total_sales TEXT NOT NULL,
         *   total_orders INTEGER NOT NULL,
         *   duration_minutes INTEGER,
         *   cached_at INTEGER NOT NULL
         * );
         * CREATE UNIQUE INDEX idx_venue ON cached_shift(venue_id);
         * ```
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create cached_shift table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cached_shift (
                        id TEXT PRIMARY KEY NOT NULL,
                        venue_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        staff_id TEXT NOT NULL,
                        staff_name TEXT NOT NULL,
                        start_time TEXT NOT NULL,
                        total_sales TEXT NOT NULL,
                        total_orders INTEGER NOT NULL,
                        duration_minutes INTEGER,
                        cached_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                // Create unique index for venue_id (only one cached shift per venue)
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_cached_shift_venue_id ON cached_shift(venue_id)"
                )
            }
        }

        /**
         * Migration from version 10 to version 11: Add partial payment tracking to orders.
         *
         * **Split Payments - Partial Payment Tracking (2025-11-26)**
         * - Adds paid_amount column (BigDecimal as String - amount already paid)
         * - Adds remaining_balance column (BigDecimal as String - amount left to pay)
         * - Enables split payment UI to show correct remaining balance
         *
         * **Why This Migration:**
         * - After partial payment, UI should show remaining balance (not total)
         * - Enables "Continuar pagando" flow to display correct amount
         * - Fixes issue: CheckTab/MenuScreen showing total ($168) instead of remaining ($89)
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE draft_orders ADD COLUMN paid_amount TEXT NOT NULL DEFAULT '0';
         * ALTER TABLE draft_orders ADD COLUMN remaining_balance TEXT NOT NULL DEFAULT '0';
         * ```
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add paid_amount column (BigDecimal stored as String)
                database.execSQL(
                    "ALTER TABLE draft_orders ADD COLUMN paid_amount TEXT NOT NULL DEFAULT '0'"
                )

                // Add remaining_balance column (BigDecimal stored as String)
                database.execSQL(
                    "ALTER TABLE draft_orders ADD COLUMN remaining_balance TEXT NOT NULL DEFAULT '0'"
                )
            }
        }

        /**
         * Migration from version 11 to version 12: Add split payment type restriction tracking.
         *
         * **Split Payment Type Restriction (2025-11-28)**
         * - Adds last_split_type column (stores SplitType enum as String: PERPRODUCT, EQUALPARTS, CUSTOMAMOUNT, FULLPAYMENT)
         * - Enables restricting incompatible split options after partial payment
         * - E.g., if first payment was EQUALPARTS, user cannot switch to PERPRODUCT
         *
         * **Why This Migration:**
         * - Bug fix: After EQUALPARTS payment, "Por Productos" option should be hidden
         * - Business logic: Can't mix split methods (causes reconciliation chaos)
         * - UI enforcement: SplitOptionsOverlay filters options based on lastSplitType
         *
         * **Usage:**
         * ```
         * Order with no payments → lastSplitType = null → All options available
         * First payment with EQUALPARTS → lastSplitType = "EQUALPARTS" → Only EQUALPARTS/CUSTOMAMOUNT allowed
         * First payment with PERPRODUCT → lastSplitType = "PERPRODUCT" → Only PERPRODUCT/CUSTOMAMOUNT allowed
         * ```
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE draft_orders ADD COLUMN last_split_type TEXT DEFAULT NULL;
         * ```
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add last_split_type column (nullable - null means no restriction yet)
                database.execSQL(
                    "ALTER TABLE draft_orders ADD COLUMN last_split_type TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Migration from version 12 to version 13: Add color fields to products and categories.
         *
         * **Color Support for Products/Categories (2025-12-01)**
         * - Adds color column to products table
         * - Adds color column to product_categories table
         * - Enables visual distinction in product catalog
         *
         * **SQL:**
         * ```sql
         * ALTER TABLE products ADD COLUMN color TEXT DEFAULT NULL;
         * ALTER TABLE product_categories ADD COLUMN color TEXT DEFAULT NULL;
         * ```
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add color column to products (nullable)
                database.execSQL(
                    "ALTER TABLE products ADD COLUMN color TEXT DEFAULT NULL"
                )
                // Add color column to product_categories (nullable)
                database.execSQL(
                    "ALTER TABLE product_categories ADD COLUMN color TEXT DEFAULT NULL"
                )
            }
        }

        /**
         * Migration from version 13 to version 14: Add verification queue table.
         *
         * **Step 4 Sale Verification (2025-12-11)**
         * - Creates verification_queue table for offline verification storage
         * - Stores captured photos (local paths + Firebase URLs) and scanned barcodes
         * - Tracks sync status: PENDING → UPLOADING_PHOTOS → SYNCING → SYNCED | FAILED
         *
         * **Why This Migration:**
         * - Enables retail/telecomunicaciones venues to capture sale evidence
         * - Offline-first: Photos stored locally, synced when connection available
         * - Automatic inventory deduction when verification syncs
         *
         * **Table Created:**
         * **verification_queue**: Offline queue for sale verifications
         * - Primary key: UUID (local generation)
         * - Indexes: venueId, paymentId, syncStatus for efficient queries
         * - Supports PENDING, UPLOADING_PHOTOS, SYNCING, SYNCED, FAILED states
         *
         * **Sync Flow:**
         * 1. User captures photo/barcode → saved locally (PENDING)
         * 2. Upload photos to Firebase Storage → (UPLOADING_PHOTOS)
         * 3. Send metadata to backend API → (SYNCING)
         * 4. Backend confirms + deducts inventory → (SYNCED)
         * 5. WorkManager retries failed items periodically
         *
         * **SQL:**
         * ```sql
         * CREATE TABLE verification_queue (
         *   id TEXT PRIMARY KEY NOT NULL,
         *   venueId TEXT NOT NULL,
         *   staffId TEXT NOT NULL,
         *   paymentId TEXT NOT NULL,
         *   orderId TEXT,
         *   photoLocalPaths TEXT NOT NULL,
         *   photoUrls TEXT NOT NULL,
         *   scannedBarcodes TEXT NOT NULL,
         *   syncStatus TEXT NOT NULL,
         *   createdAt INTEGER NOT NULL,
         *   syncAttempts INTEGER NOT NULL DEFAULT 0,
         *   lastSyncError TEXT
         * );
         * CREATE INDEX idx_verification_venue ON verification_queue(venueId);
         * CREATE INDEX idx_verification_payment ON verification_queue(paymentId);
         * CREATE INDEX idx_verification_status ON verification_queue(syncStatus);
         * ```
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create verification_queue table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS verification_queue (
                        id TEXT PRIMARY KEY NOT NULL,
                        venueId TEXT NOT NULL,
                        staffId TEXT NOT NULL,
                        paymentId TEXT NOT NULL,
                        orderId TEXT,
                        photoLocalPaths TEXT NOT NULL,
                        photoUrls TEXT NOT NULL,
                        scannedBarcodes TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        syncAttempts INTEGER NOT NULL DEFAULT 0,
                        lastSyncError TEXT
                    )
                    """.trimIndent()
                )

                // Create index for venue queries
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_verification_queue_venueId ON verification_queue(venueId)"
                )

                // Create index for payment lookups
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_verification_queue_paymentId ON verification_queue(paymentId)"
                )

                // Create index for sync status queries (find pending items)
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_verification_queue_syncStatus ON verification_queue(syncStatus)"
                )
            }
        }
    }
}
