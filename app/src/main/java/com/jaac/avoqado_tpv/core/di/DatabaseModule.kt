package com.jaac.avoqado_tpv.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.dao.FloorElementDao
import com.jaac.avoqado_tpv.core.data.local.dao.HistoricalPeriodDao
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
import com.jaac.avoqado_tpv.core.data.local.dao.TableDao
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayMerchantCacheDao
import com.jaac.avoqado_tpv.features.verification.data.local.VerificationQueueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Room database dependency injection.
 *
 * **Provides:**
 * - AvoqadoDatabase singleton (app-wide)
 * - PendingPaymentDao (offline payment queue)
 * - DraftOrderDao (local-first order management)
 * - DraftOrderItemDao (order items with soft delete)
 *
 * **Scope:** SingletonComponent (one instance per app)
 *
 * **Usage in Repository:**
 * ```kotlin
 * @Inject constructor(
 *     private val draftOrderDao: DraftOrderDao,
 *     private val draftOrderItemDao: DraftOrderItemDao
 * )
 * ```
 *
 * **Usage in ViewModel:**
 * ```kotlin
 * @HiltViewModel
 * class MenuViewModel @Inject constructor(
 *     private val draftOrderDao: DraftOrderDao
 * )
 * ```
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Provides AvoqadoDatabase singleton.
     *
     * **Configuration:**
     * - Name: "avoqado_database"
     * - Migrations: explicit chain 2→27 (see AvoqadoDatabase companion object).
     *   NO blanket destructive fallback — a missing migration crashes loudly in QA
     *   instead of silently wiping pending_payments/draft_orders in production.
     *
     * **World-Class Pattern:**
     * - Square POS: Uses Room.databaseBuilder with explicit migrations
     * - Toast POS: Includes WAL (Write-Ahead Logging) for better concurrency
     * - Stripe Terminal: Enables auto-close for memory optimization
     *
     * @param context Application context (injected by Hilt)
     * @return AvoqadoDatabase singleton instance
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AvoqadoDatabase {
        return Room.databaseBuilder(
            context,
            AvoqadoDatabase::class.java,
            AvoqadoDatabase.DATABASE_NAME
        )
            // ⭐ MIGRATIONS: Explicit migrations for data preservation
            .addMigrations(
                AvoqadoDatabase.MIGRATION_2_3,
                AvoqadoDatabase.MIGRATION_3_4,  // Rating feature
                AvoqadoDatabase.MIGRATION_4_5,  // Local-first order management
                AvoqadoDatabase.MIGRATION_5_6,  // Historical reports cache
                AvoqadoDatabase.MIGRATION_6_7,  // Fix FOREIGN KEY with ON UPDATE CASCADE
                AvoqadoDatabase.MIGRATION_7_8,  // 🔒 Merchant account tracking (split payment validation)
                AvoqadoDatabase.MIGRATION_8_9,  // ⚡ Product cache (cache-first loading - 500ms → 10ms)
                AvoqadoDatabase.MIGRATION_9_10,  // 📶 Cached shift (offline status display - Square/Toast pattern)
                AvoqadoDatabase.MIGRATION_10_11, // 💰 Split payments (paidAmount/remainingBalance tracking)
                AvoqadoDatabase.MIGRATION_11_12, // 🔀 Split type restriction (lastSplitType)
                AvoqadoDatabase.MIGRATION_12_13, // 🎨 Color fields for products/categories
                AvoqadoDatabase.MIGRATION_13_14, // 📸 Step 4 verification queue (photos + barcodes)
                AvoqadoDatabase.MIGRATION_14_15, // 🚫 Filter inactive categories (isActive field in ProductCategory)
                AvoqadoDatabase.MIGRATION_15_16, // 🚫 Category active status in products (categoryIsActive field)
                AvoqadoDatabase.MIGRATION_16_17,  // 🔴 CRITICAL: Fix pending_payments missing columns (v1.1.x → v1.3.0 production crash)
                AvoqadoDatabase.MIGRATION_17_18,  // 🪑 Floor plan cache (tables + floor elements)
                AvoqadoDatabase.MIGRATION_18_19,  // 🛠️ Schema hash fix (idempotent)
                AvoqadoDatabase.MIGRATION_19_20,  // 🧾 Stable ordering (line_position)
                AvoqadoDatabase.MIGRATION_20_21,  // 🛒 Mosaic shortcuts for unified Checkout
                AvoqadoDatabase.MIGRATION_21_22,  // 💳 AngelPay multi-merchant offline cache (SDK 1.0.5)
                AvoqadoDatabase.MIGRATION_22_23,  // 🛡️ Persist idempotencyKey through offline payment queue
                AvoqadoDatabase.MIGRATION_23_24,  // 🔴 CRITICAL: Repair products/historical_periods schema drift
                AvoqadoDatabase.MIGRATION_24_25,  // 🔶 Processor-aware offline queue (AngelPay order/SIM payments)
                AvoqadoDatabase.MIGRATION_25_26,  // 📡 Offline queue carries the POS→TPV arbitration link
                AvoqadoDatabase.MIGRATION_26_27   // 📒 La libreta — write-ahead ledger de cobros
            )

            // 🛡️ NO blanket destructive fallback (removed 2026-06-12).
            // A missing migration must FAIL LOUDLY (IllegalStateException at startup,
            // caught in QA before the APK ships) instead of silently wiping the entire
            // database — including pending_payments (card payments already approved by
            // Blumon TPV but not yet recorded in the backend = real money) and
            // draft_orders. This exact silent-wipe path shipped in production until now.
            //
            // The ONLY destructive path kept is DB v1 (Dec-2024 schema): no MIGRATION_1_2
            // exists and the migration chain starts at 2→3, so v1 devices have always
            // been wiped on update — this preserves that exact behavior for them while
            // protecting v2+ from any future forgotten migration.
            //
            // DEV NOTE: if you change an @Entity without writing a migration, debug
            // builds now crash at startup instead of wiping the DB. That is intentional
            // (see .claude/rules/critical-warnings.md — Room Migration Checklist).
            // Recover on a dev device with: adb uninstall <package> or clear app data.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)

            // ⚠️ DOWNGRADE SUPPORT (INSTALL_VERSION command rollback)
            // When installing an older version, the database schema will be newer than
            // what the old app expects. This allows destructive downgrade (data loss).
            //
            // ⚠️ WARNING: This will DELETE all local data including:
            // - pending_payments (queued payments not yet synced)
            // - draft_orders (local orders)
            // - verification_queue (pending verifications)
            // - All cache tables (can be recovered from server)
            //
            // RECOMMENDATION: Before downgrading via INSTALL_VERSION:
            // 1. Ensure all pending payments are synced (PaymentSyncWorker)
            // 2. Ensure all draft orders are synced
            // 3. Ensure all verifications are synced
            // The dashboard should warn SUPERADMIN about potential data loss.
            .fallbackToDestructiveMigrationOnDowngrade()

            // ✅ Enable Write-Ahead Logging for better concurrency
            // Allows reads while writes are happening (recommended for POS systems)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

            .build()
    }

    /**
     * Provides PendingPaymentDao from database.
     *
     * **Injected Into:**
     * - PaymentQueueRepositoryImpl
     * - PaymentSyncWorker
     *
     * @param database AvoqadoDatabase instance
     * @return PendingPaymentDao for offline payment queue operations
     */
    @Provides
    fun providePendingPaymentDao(
        database: AvoqadoDatabase
    ): PendingPaymentDao {
        return database.pendingPaymentDao()
    }

    /**
     * Provides PaymentAttemptDao (la libreta — write-ahead ledger of card-charge attempts, v27).
     *
     * **Injected Into:**
     * - PaymentAttemptLedger (Task 2 — writes evidence before the SDK is invoked)
     */
    @Provides
    fun providePaymentAttemptDao(database: AvoqadoDatabase): com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptDao =
        database.paymentAttemptDao()

    /**
     * Provides MosaicShortcutDao for unified Checkout shortcut tiles.
     *
     * **Injected Into:**
     * - MosaicRepositoryImpl (features/checkout/data)
     */
    @Provides
    fun provideMosaicShortcutDao(
        database: AvoqadoDatabase
    ): com.jaac.avoqado_tpv.core.data.local.dao.MosaicShortcutDao {
        return database.mosaicShortcutDao()
    }

    /**
     * Provides DraftOrderDao from database.
     *
     * **Injected Into:**
     * - MenuViewModel (local-first order management)
     * - OrderSyncCoordinator (hybrid sync orchestration)
     *
     * @param database AvoqadoDatabase instance
     * @return DraftOrderDao for draft order operations
     */
    @Provides
    fun provideDraftOrderDao(
        database: AvoqadoDatabase
    ): DraftOrderDao {
        return database.draftOrderDao()
    }

    /**
     * Provides DraftOrderItemDao from database.
     *
     * **Injected Into:**
     * - MenuViewModel (local-first item management)
     * - OrderSyncCoordinator (batch sync operations)
     *
     * @param database AvoqadoDatabase instance
     * @return DraftOrderItemDao for draft order item operations
     */
    @Provides
    fun provideDraftOrderItemDao(
        database: AvoqadoDatabase
    ): DraftOrderItemDao {
        return database.draftOrderItemDao()
    }

    /**
     * Provides HistoricalPeriodDao from database.
     *
     * **Injected Into:**
     * - ReportsRepositoryImpl (offline caching for historical reports)
     *
     * @param database AvoqadoDatabase instance
     * @return HistoricalPeriodDao for historical period cache operations
     */
    @Provides
    fun provideHistoricalPeriodDao(
        database: AvoqadoDatabase
    ): HistoricalPeriodDao {
        return database.historicalPeriodDao()
    }

    /**
     * Provides ProductDao from database.
     *
     * **Injected Into:**
     * - MenuViewModel (cache-first product loading)
     * - ProductRepository (offline caching for products)
     *
     * @param database AvoqadoDatabase instance
     * @return ProductDao for product cache operations
     */
    @Provides
    fun provideProductDao(
        database: AvoqadoDatabase
    ): ProductDao {
        return database.productDao()
    }

    /**
     * Provides ProductCategoryDao from database.
     *
     * **Injected Into:**
     * - MenuViewModel (cache-first category loading)
     * - ProductRepository (offline caching for categories)
     *
     * @param database AvoqadoDatabase instance
     * @return ProductCategoryDao for product category cache operations
     */
    @Provides
    fun provideProductCategoryDao(
        database: AvoqadoDatabase
    ): ProductCategoryDao {
        return database.productCategoryDao()
    }

    /**
     * Provides TableDao from database.
     */
    @Provides
    fun provideTableDao(
        database: AvoqadoDatabase
    ): TableDao {
        return database.tableDao()
    }

    /**
     * Provides FloorElementDao from database.
     */
    @Provides
    fun provideFloorElementDao(
        database: AvoqadoDatabase
    ): FloorElementDao {
        return database.floorElementDao()
    }

    /**
     * Provides CachedShiftDao from database.
     *
     * **Injected Into:**
     * - ShiftViewModel (offline shift status display)
     *
     * **Pattern (Square/Toast POS - Prevention):**
     * - Cache shift state when online
     * - Display cached state when offline with "Último estado conocido"
     * - Block shift operations when offline
     *
     * @param database AvoqadoDatabase instance
     * @return CachedShiftDao for cached shift operations
     */
    @Provides
    fun provideCachedShiftDao(
        database: AvoqadoDatabase
    ): CachedShiftDao {
        return database.cachedShiftDao()
    }

    /**
     * Provides VerificationQueueDao from database.
     *
     * **Injected Into:**
     * - VerificationRepository (Step 4 sale verification queue)
     * - VerificationSyncWorker (background photo upload + sync)
     *
     * **Pattern (Offline-First):**
     * - Capture photos + barcodes locally
     * - Upload to Firebase Storage (UPLOADING_PHOTOS)
     * - Sync metadata to backend API (SYNCING)
     * - Cleanup after success (SYNCED)
     *
     * @param database AvoqadoDatabase instance
     * @return VerificationQueueDao for verification queue operations
     */
    @Provides
    fun provideVerificationQueueDao(
        database: AvoqadoDatabase
    ): VerificationQueueDao {
        return database.verificationQueueDao()
    }

    /**
     * Provides AngelPayMerchantCacheDao from database.
     *
     * **Injected Into:**
     * - AngelPay merchant switcher (instant cold-start render from cache)
     * - Periodic refresh job (D6 — spec §6.6 / §18.5) — replaces cache atomically
     *   after `AngelPaySdkGateway.getUserMerchants()`
     *
     * **Pattern (SDK 1.0.5 Multi-Merchant):**
     * - Cache-first: read on cold start, render instantly
     * - Background refresh: pull fresh list from SDK, replaceAll()
     * - Active-flag sync: markActive(id) after switchMerchant()
     *
     * @param database AvoqadoDatabase instance
     * @return AngelPayMerchantCacheDao for AngelPay user merchant cache operations
     */
    @Provides
    fun provideAngelPayMerchantCacheDao(
        database: AvoqadoDatabase
    ): AngelPayMerchantCacheDao {
        return database.angelPayMerchantCacheDao()
    }
}
