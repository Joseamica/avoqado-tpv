package com.jaac.avoqado_tpv.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.CachedShiftDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.dao.HistoricalPeriodDao
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductCategoryDao
import com.jaac.avoqado_tpv.core.data.local.dao.ProductDao
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
     * - Migration: fallbackToDestructiveMigration() for development
     *   → TODO: Add explicit migrations for production (preserves data across app updates)
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
                AvoqadoDatabase.MIGRATION_13_14  // 📸 Step 4 verification queue (photos + barcodes)
            )

            // ⚠️ DEVELOPMENT ONLY: Destructive migration (data loss on schema change)
            // Fallback for cases where explicit migration is missing
            .fallbackToDestructiveMigration()

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
}
