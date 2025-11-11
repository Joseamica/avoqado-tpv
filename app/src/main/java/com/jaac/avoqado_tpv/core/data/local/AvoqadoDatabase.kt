package com.jaac.avoqado_tpv.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity

/**
 * Room database for Avoqado TPV local data persistence.
 *
 * **Current Version:** 4
 * - v1 → v2: Added blumonSerialNumber to PendingPaymentEntity for merchant account tracking
 * - v2 → v3: Added merchantAccountId to PendingPaymentEntity (provider-agnostic migration)
 * - v3 → v4: Added rating to PendingPaymentEntity (user rating feature - 2025-01-11)
 *
 * **Entities:**
 * - PendingPaymentEntity: Offline queue for failed payment recordings
 *
 * **Future Entities:**
 * - PaymentHistoryEntity: Local cache of successful payments for offline access
 * - SyncLogEntity: Audit log of all sync attempts
 * - DeviceConfigEntity: Persistent device configuration (terminal ID, merchant accounts, etc.)
 *
 * **Migration Strategy:**
 * - For development: `fallbackToDestructiveMigration()` (data loss acceptable)
 * - For production: Define explicit migrations with `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, ...)`
 *
 * **Usage:**
 * ```kotlin
 * // Injected via Hilt
 * @Inject lateinit var database: AvoqadoDatabase
 *
 * // Access DAOs
 * val pendingPaymentDao = database.pendingPaymentDao()
 * val payments = pendingPaymentDao.getAllPending()
 * ```
 *
 * **World-Class Examples:**
 * - Square POS: Uses Room for offline order queue + inventory cache
 * - Toast POS: Room for payment history + shift summaries
 * - Stripe Terminal: Room for offline transaction queue
 */
@Database(
    entities = [
        PendingPaymentEntity::class,
    ],
    version = 4, // ⭐ Version 4: Added rating (user rating feature)
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
    }
}
