package com.jaac.avoqado_tpv.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
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
 * - PendingPaymentDao (injected into repositories)
 *
 * **Scope:** SingletonComponent (one instance per app)
 *
 * **Usage in Repository:**
 * ```kotlin
 * @Inject constructor(
 *     private val pendingPaymentDao: PendingPaymentDao
 * )
 * ```
 *
 * **Usage in ViewModel:**
 * ```kotlin
 * @HiltViewModel
 * class PaymentViewModel @Inject constructor(
 *     private val paymentQueueRepository: PaymentQueueRepository
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
                AvoqadoDatabase.MIGRATION_3_4  // 🆕 Rating feature
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
}
