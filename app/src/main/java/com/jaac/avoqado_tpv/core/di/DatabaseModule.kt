package com.jaac.avoqado_tpv.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderDao
import com.jaac.avoqado_tpv.core.data.local.dao.DraftOrderItemDao
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
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
                AvoqadoDatabase.MIGRATION_4_5   // 🆕 Local-first order management
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
}
