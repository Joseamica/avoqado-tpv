package com.jaac.avoqado_tpv.core.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented migration tests for [AvoqadoDatabase], focused on the v23 → v24 repair.
 *
 * **Why this exists:** MIGRATION_12_13 added a column named `color` to `products` while the
 * entity declares `category_color`, and MIGRATION_5_6 created an index on `historical_periods`
 * the entity never declared. Devices that reached the current schema *through the migration
 * chain* (installed at DB ≤12) therefore carry a shape Room rejects on the next update with
 * "Migration didn't properly handle: products" → crash-loop at startup. Fresh installs were
 * always fine, so the bug stayed latent. MIGRATION_23_24 repairs both.
 *
 * [runMigrationsAndValidate] runs the migration and then runs **Room's own schema validation**
 * against the exported `24.json` — i.e. the exact check that crash-loops in production. If these
 * tests pass on a real Android SQLite runtime, that production crash cannot happen.
 *
 * Run on the emulator or PAX (note: `--tests` does NOT work for connected/instrumented tests):
 * `./gradlew connectedTutorialEmuDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabaseMigrationTest`
 */
@RunWith(AndroidJUnit4::class)
class AvoqadoDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AvoqadoDatabase::class.java,
    )

    /**
     * Happy path: a v23 database with the schema Room itself generates (fresh-ish device)
     * migrates cleanly to v24. Guards the version bump for the common case.
     */
    @Test
    fun migrate23To24_freshSchema_validatesAgainstV24() {
        helper.createDatabase(TEST_DB, 23).close()
        // Throws if MIGRATION_23_24 leaves the schema diverging from 24.json.
        helper.runMigrationsAndValidate(TEST_DB, 24, true, AvoqadoDatabase.MIGRATION_23_24)
    }

    /**
     * The real bug: reproduce the shape of a device that reached v23 through the migration
     * chain from DB ≤12 — `products` has `color` (not `category_color`) under the legacy index
     * names, and `historical_periods` carries the orphan index from MIGRATION_5_6. This is the
     * exact state that crash-loops on update today. MIGRATION_23_24 must heal it.
     */
    @Test
    fun migrate23To24_driftedLegacyShape_recoversAndValidates() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            // --- products: drop the canonical table, recreate the DRIFTED legacy shape.
            db.execSQL("DROP TABLE products")
            db.execSQL(
                """
                CREATE TABLE products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    product_id TEXT NOT NULL, venue_id TEXT NOT NULL, name TEXT NOT NULL,
                    sku TEXT NOT NULL, price TEXT NOT NULL, category_id TEXT NOT NULL,
                    category_name TEXT NOT NULL, description TEXT, emoji TEXT NOT NULL,
                    image_url TEXT, available INTEGER NOT NULL, display_order INTEGER NOT NULL,
                    track_inventory INTEGER NOT NULL, inventory_method TEXT,
                    available_quantity INTEGER, modifier_groups_json TEXT NOT NULL,
                    color TEXT DEFAULT NULL,
                    category_is_active INTEGER NOT NULL DEFAULT 1,
                    cached_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX index_products_venue_product ON products(venue_id, product_id)")
            db.execSQL("CREATE INDEX index_products_venue_category ON products(venue_id, category_id)")
            db.execSQL("CREATE INDEX index_products_venue_available ON products(venue_id, available)")
            db.execSQL("CREATE INDEX index_products_cached_at ON products(cached_at)")
            // a row that should NOT block the rebuild (cache, repopulated from backend)
            db.execSQL(
                "INSERT INTO products (product_id, venue_id, name, sku, price, category_id, " +
                    "category_name, emoji, available, display_order, track_inventory, " +
                    "modifier_groups_json, cached_at, color) VALUES " +
                    "('p1','v1','Taco','SKU1','10.00','c1','Tacos','🌮',1,0,0,'[]',123,'#FF0000')",
            )

            // --- historical_periods: swap the canonical indices for the legacy v5_6 set
            // (orphan non-unique index + legacy-named unique index).
            db.execSQL("DROP INDEX IF EXISTS index_historical_periods_venue_id_grouping_period_start")
            db.execSQL("DROP INDEX IF EXISTS index_historical_periods_cached_at")
            db.execSQL("CREATE INDEX index_historical_periods_venue_grouping_period ON historical_periods(venue_id, grouping, period_start)")
            db.execSQL("CREATE INDEX index_historical_periods_cached_at ON historical_periods(cached_at)")
            db.execSQL("CREATE UNIQUE INDEX index_historical_periods_unique ON historical_periods(venue_id, grouping, period_start)")
            db.execSQL(
                "INSERT INTO historical_periods (venue_id, grouping, period_start, period_end, " +
                    "label, subtitle, total_sales, total_orders, total_products, " +
                    "average_order_value, cached_at) VALUES " +
                    "('v1','DAILY',1,2,'15 Enero','Martes','100.00',5,10,'20.00',123)",
            )
        }

        // Must NOT throw: MIGRATION_23_24 rebuilds products to the entity-exact shape and
        // normalizes the historical_periods indices, then Room validates against 24.json.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 24, true, AvoqadoDatabase.MIGRATION_23_24,
        )

        // products: the previously-missing column now exists and is queryable; the cache
        // was rebuilt empty (repopulated by the next ProductRepository sync).
        migrated.query("SELECT category_color FROM products").use { c ->
            assertThat(c.count).isEqualTo(0)
        }
        // historical_periods: rows preserved through the index normalization.
        migrated.query("SELECT COUNT(*) FROM historical_periods").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migrated.close()
    }

    /**
     * Idempotency: running MIGRATION_23_24 against an already-correct v24-shaped products table
     * (everything `IF EXISTS` / `IF NOT EXISTS`) is a no-op and still validates. Guards against a
     * half-applied migration being re-run.
     */
    @Test
    fun migrate23To24_isIdempotentOnCorrectShape() {
        helper.createDatabase(TEST_DB, 23).close()
        helper.runMigrationsAndValidate(TEST_DB, 24, true, AvoqadoDatabase.MIGRATION_23_24)
        // Re-validating at 24 with the migration again must not throw (no-op path).
        helper.runMigrationsAndValidate(TEST_DB, 24, true, AvoqadoDatabase.MIGRATION_23_24)
    }

    /**
     * The refinement: a HEALTHY v23 database (correct shape, with cached products) keeps its
     * product cache through the migration — the conditional rebuild only fires on drifted
     * devices, so the vast majority of terminals update without a menu re-sync blank.
     */
    @Test
    fun migrate23To24_healthyShape_preservesProductCache() {
        helper.createDatabase(TEST_DB, 23).use { db ->
            db.execSQL(
                "INSERT INTO products (product_id, venue_id, name, sku, price, category_id, " +
                    "category_name, emoji, available, display_order, track_inventory, " +
                    "modifier_groups_json, category_is_active, cached_at, category_color) VALUES " +
                    "('p1','v1','Taco','SKU1','10.00','c1','Tacos','X',1,0,0,'[]',1,123,'#FF0000')",
            )
        }
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 24, true, AvoqadoDatabase.MIGRATION_23_24,
        )
        // The cached product SURVIVED — products was not dropped on a healthy device.
        migrated.query("SELECT COUNT(*) FROM products").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(1)
        }
        migrated.close()
    }

    /**
     * End-to-end through the **real production builder**: seed a drifted v23 database at the
     * app's actual DB path, then open it via [DatabaseModule.provideDatabase] — the exact builder
     * the app uses (full migration chain + `fallbackToDestructiveMigrationFrom(1)`, no blanket
     * destructive fallback). This is the gap MigrationTestHelper cannot cover: it proves the
     * production builder migrates the drifted shape AND does NOT wipe data (the destructive
     * fallback would have silently dropped pending_payments). The emulator login screen never
     * opens Room, so this is the only deterministic way to exercise the real builder on-device.
     */
    @Test
    fun realDatabaseModuleBuilder_opensDriftedV23_migratesWithoutWipe() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        seedDriftedV23Database(appContext)

        // Open through the REAL app builder and force the migration by touching the DB.
        val db = DatabaseModule.provideDatabase(appContext)
        try {
            val open = db.openHelper.writableDatabase
            assertThat(open.version).isEqualTo(29) // full chain ran (…23→24→25→26→27→28→29)

            // products: previously-missing column now exists (cache rebuilt empty)
            open.query("SELECT category_color FROM products").use { c ->
                assertThat(c.count).isEqualTo(0)
            }
            // NOT wiped: the offline payment queue survived the upgrade (this is the row the
            // old blanket destructive fallback would have silently destroyed).
            open.query("SELECT COUNT(*) FROM pending_payments").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            open.query("SELECT COUNT(*) FROM historical_periods").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
        } finally {
            db.close()
        }
    }

    /**
     * v24 → v25: additive `pending_payments` columns for the processor-aware offline
     * queue (AngelPay order/SIM payments, 2026-07-09). A queued row written on v24
     * must survive the upgrade with `payment_processor` defaulting to BLUMON (its
     * pre-v25 semantics) and the new nullable columns empty.
     */
    @Test
    fun migrate24To25_preservesQueueRowsAndDefaultsProcessorToBlumon() {
        helper.createDatabase(TEST_DB, 24).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, retry_count, sync_status) VALUES " +
                    "('REF-V24-001','v1','s1','100.00','0.00','m1','SER1','CHIP',0,123,0,'PENDING')",
            )
        }

        // Throws if MIGRATION_24_25 leaves the schema diverging from 25.json.
        val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, AvoqadoDatabase.MIGRATION_24_25)
        db.query(
            "SELECT payment_processor, order_id, is_portabilidad, serial_numbers " +
                "FROM pending_payments WHERE reference_number='REF-V24-001'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("BLUMON")
            assertThat(c.isNull(1)).isTrue()   // order_id
            assertThat(c.getInt(2)).isEqualTo(0) // is_portabilidad
            assertThat(c.isNull(3)).isTrue()   // serial_numbers
        }
    }

    /** Deletes the real database between tests so seeding starts clean. */
    @After
    fun clearRealDatabase() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = ctx.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        listOf(f.path, f.path + "-wal", f.path + "-shm").forEach { File(it).delete() }
    }

    /**
     * Writes a complete v23 database in the DRIFTED legacy shape to the app's real DB path:
     * every table built from the exported `23.json`, except `products` (column `color`, no
     * `category_color`, legacy index names) and `historical_periods` (orphan + legacy indices) —
     * the exact state a device installed at DB ≤12 carries. Includes a pending_payments row so we
     * can assert it survives.
     */
    private fun seedDriftedV23Database(appContext: Context) {
        val dbFile = appContext.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        listOf(dbFile.path, dbFile.path + "-wal", dbFile.path + "-shm").forEach { File(it).delete() }

        val schemaJson = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/23.json")
            .bufferedReader().use { it.readText() }
        val database = JSONObject(schemaJson).getJSONObject("database")
        val entities = database.getJSONArray("entities")

        val raw = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            // Every table EXACTLY as 23.json declares (correct shape)…
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                if (table == "products" || table == "historical_periods") continue
                raw.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idxs ->
                    for (j in 0 until idxs.length()) {
                        raw.execSQL(idxs.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }

            // …products DRIFTED: `color` (not `category_color`), legacy index names.
            raw.execSQL(
                """
                CREATE TABLE products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    product_id TEXT NOT NULL, venue_id TEXT NOT NULL, name TEXT NOT NULL,
                    sku TEXT NOT NULL, price TEXT NOT NULL, category_id TEXT NOT NULL,
                    category_name TEXT NOT NULL, description TEXT, emoji TEXT NOT NULL,
                    image_url TEXT, available INTEGER NOT NULL, display_order INTEGER NOT NULL,
                    track_inventory INTEGER NOT NULL, inventory_method TEXT,
                    available_quantity INTEGER, modifier_groups_json TEXT NOT NULL,
                    color TEXT DEFAULT NULL,
                    category_is_active INTEGER NOT NULL DEFAULT 1,
                    cached_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            raw.execSQL("CREATE UNIQUE INDEX index_products_venue_product ON products(venue_id, product_id)")
            raw.execSQL("CREATE INDEX index_products_venue_category ON products(venue_id, category_id)")
            raw.execSQL("CREATE INDEX index_products_venue_available ON products(venue_id, available)")
            raw.execSQL("CREATE INDEX index_products_cached_at ON products(cached_at)")

            // …historical_periods: correct columns (from 23.json) but the drifted v5_6 index set.
            val hp = (0 until entities.length()).map { entities.getJSONObject(it) }
                .first { it.getString("tableName") == "historical_periods" }
            raw.execSQL(hp.getString("createSql").replace("\${TABLE_NAME}", "historical_periods"))
            raw.execSQL("CREATE INDEX index_historical_periods_venue_grouping_period ON historical_periods(venue_id, grouping, period_start)")
            raw.execSQL("CREATE INDEX index_historical_periods_cached_at ON historical_periods(cached_at)")
            raw.execSQL("CREATE UNIQUE INDEX index_historical_periods_unique ON historical_periods(venue_id, grouping, period_start)")
            raw.execSQL(
                "INSERT INTO historical_periods (venue_id, grouping, period_start, period_end, " +
                    "label, subtitle, total_sales, total_orders, total_products, " +
                    "average_order_value, cached_at) VALUES " +
                    "('v1','DAILY',1,2,'15 Enero','Martes','100.00',5,10,'20.00',123)",
            )

            // A queued payment — the row the old destructive fallback would have wiped.
            raw.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, retry_count, sync_status) VALUES " +
                    "('REF-TEST-001','v1','s1','250.00','25.00','m1','SER1','CHIP',0,123,'idem-1',0,'PENDING')",
            )

            // Room bookkeeping: v23 identity hash + user_version so Room runs onUpgrade(23, 24).
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$V23_IDENTITY_HASH')")
            raw.version = 23
        } finally {
            raw.close()
        }
    }

    /**
     * v25 → v26 adds `pending_payments.terminal_payment_request_id` (the POS→TPV arbitration
     * link). It is purely additive + nullable, so Room's own schema validation against 26.json
     * is the whole contract.
     */
    @Test
    fun migrate25To26_freshSchema_validatesAgainstV26() {
        helper.createDatabase(TEST_DB, 25).close()
        // Throws if MIGRATION_25_26 leaves the schema diverging from 26.json.
        helper.runMigrationsAndValidate(TEST_DB, 26, true, AvoqadoDatabase.MIGRATION_25_26)
    }

    /**
     * The money guarantee: a queued payment is REAL money that hasn't reached the backend yet.
     * v25 → v26 must preserve every existing row untouched (new column simply reads NULL, which
     * replays exactly as it did before). A destructive fallback here would silently delete
     * un-synced sales — the failure mode this whole suite exists to prevent.
     */
    @Test
    fun migrate25To26_preservesQueuedPaymentsAndDefaultsTheNewColumnToNull() {
        helper.createDatabase(TEST_DB, 25).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status) VALUES " +
                    "('REF-V26-001','v1','s1','250.00','25.00','m1','SER1','CHIP',0,123,'idem-1','BLUMON',0,'PENDING')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, AvoqadoDatabase.MIGRATION_25_26)

        migrated.query(
            "SELECT reference_number, amount, idempotency_key, terminal_payment_request_id " +
                "FROM pending_payments WHERE reference_number = 'REF-V26-001'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue() // the queued payment (real money) must survive the migration
            assertThat(c.getString(1)).isEqualTo("250.00") // amount intact
            assertThat(c.getString(2)).isEqualTo("idem-1") // dedup key intact → replay still de-dupes
            assertThat(c.isNull(3)).isTrue() // pre-v26 rows carry no arbitration link
        }
    }

    @Test
    fun migrate26To27_freshSchema_validatesAgainstV27() {
        helper.createDatabase(TEST_DB, 26).close()
        // Throws if MIGRATION_26_27 leaves the schema diverging from 27.json.
        helper.runMigrationsAndValidate(TEST_DB, 27, true, AvoqadoDatabase.MIGRATION_26_27)
    }

    /**
     * The money guarantee, v27 edition: queued payments (real money) and every
     * other table must survive untouched — v27 only ADDS payment_attempts.
     */
    @Test
    fun migrate26To27_preservesQueuedPaymentsAndCreatesEmptyLedger() {
        helper.createDatabase(TEST_DB, 26).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status) VALUES " +
                    "('REF-V27-001','v1','s1','980.00','98.00','m1','SER1','CHIP',0,123,'idem-27','BLUMON',0,'PENDING')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 27, true, AvoqadoDatabase.MIGRATION_26_27)

        migrated.query("SELECT amount, idempotency_key FROM pending_payments WHERE reference_number = 'REF-V27-001'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("980.00")
            assertThat(c.getString(1)).isEqualTo("idem-27")
        }
        migrated.query("SELECT COUNT(*) FROM payment_attempts").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    /**
     * v27 → v28 adds `pending_payments.claim_token` / `claimed_at` (claim por token, F-8). It is
     * purely additive + nullable, so Room's own schema validation against 28.json is the whole
     * contract.
     */
    @Test
    fun migrate27To28_freshSchema_validatesAgainstV28() {
        helper.createDatabase(TEST_DB, 27).close()
        // Throws if MIGRATION_27_28 leaves the schema diverging from 28.json.
        helper.runMigrationsAndValidate(TEST_DB, 28, true, AvoqadoDatabase.MIGRATION_27_28)
    }

    /**
     * The money guarantee, v28 edition: a queued payment (real money not yet synced) must
     * survive the upgrade untouched, and the new claim columns must default to NULL — i.e. the
     * row is "unclaimed", which is the correct state for a row that predates the claim system.
     */
    @Test
    fun migrate27To28_preservesQueuedPaymentsAndDefaultsClaimFieldsToNull() {
        helper.createDatabase(TEST_DB, 27).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status) VALUES " +
                    "('REF-V28-001','v1','s1','340.00','34.00','m1','SER1','CHIP',0,123,'idem-28','BLUMON',0,'PENDING')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 28, true, AvoqadoDatabase.MIGRATION_27_28)

        migrated.query(
            "SELECT amount, idempotency_key, claim_token, claimed_at, sync_status " +
                "FROM pending_payments WHERE reference_number = 'REF-V28-001'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue() // the queued payment (real money) must survive the migration
            assertThat(c.getString(0)).isEqualTo("340.00") // amount intact
            assertThat(c.getString(1)).isEqualTo("idem-28") // dedup key intact
            assertThat(c.isNull(2)).isTrue() // claim_token defaults to NULL — unclaimed
            assertThat(c.isNull(3)).isTrue() // claimed_at defaults to NULL
            assertThat(c.getString(4)).isEqualTo("PENDING") // still claimable
        }
    }

    /**
     * v28 → v29 adds `pending_payments.permanent` (F-10 — resetAllFailed() stops
     * resurrecting permanent 4xx business failures). Purely additive + defaulted, so
     * Room's own schema validation against 29.json is the whole contract.
     */
    @Test
    fun migrate28To29_freshSchema_validatesAgainstV29() {
        helper.createDatabase(TEST_DB, 28).close()
        // Throws if MIGRATION_28_29 leaves the schema diverging from 29.json.
        helper.runMigrationsAndValidate(TEST_DB, 29, true, AvoqadoDatabase.MIGRATION_28_29)
    }

    /**
     * v29 → v31 crea `pending_refunds`, la cola durable de REEMBOLSOS.
     *
     * Es una tabla NUEVA, así que el contrato entero es que el DDL escrito a mano en
     * `MIGRATION_29_31` coincida byte a byte con el que Room genera en `31.json`. Esa comparación
     * la hace `runMigrationsAndValidate` y es exactamente lo que faltó en la v23→v24, donde un
     * `color` contra `category_color` escrito a mano dejó en crash-loop a los aparatos que
     * actualizaban.
     *
     * 🔴 Salta el 30 a propósito: `develop` (nexgo-v2.9.0) se llevó ese número con otra tabla.
     * Ver la nota de la colisión en `AvoqadoDatabase`.
     */
    @Test
    fun migrate29To31_freshSchema_validatesAgainstV31() {
        helper.createDatabase(TEST_DB, 29).close()
        helper.runMigrationsAndValidate(TEST_DB, 31, true, AvoqadoDatabase.MIGRATION_29_31)
    }

    /**
     * La garantía de dinero, edición v31: la cola de COBROS que ya existía no se toca al crear la
     * de reembolsos. Son dos tablas hermanas y la nueva no puede llevarse por delante a la vieja
     * — que es justo lo que hacía el `fallbackToDestructiveMigration` que se quitó en junio.
     */
    @Test
    fun migrate29To31_preservesPendingPaymentsAndCreatesEmptyRefundQueue() {
        helper.createDatabase(TEST_DB, 29).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status, permanent) VALUES " +
                    "('REF-V31-001','v1','s1','410.00','41.00','m1','SER1','CHIP',0,123,'idem-31','BLUMON',10,'FAILED',0)",
            )
        }

        val open = helper.runMigrationsAndValidate(TEST_DB, 31, true, AvoqadoDatabase.MIGRATION_29_31)
        try {
            open.query("SELECT COUNT(*) FROM pending_payments").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
            // La cola de reembolsos nace vacía y consultable — si el CREATE TABLE fallara, esto
            // reventaría con "no such table" en vez de pasar silenciosamente.
            open.query("SELECT COUNT(*) FROM pending_refunds").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
        } finally {
            open.close()
        }
    }

    /**
     * v30 → v31 sobre el v30 de ESTE árbol: el que ya viajó en el APK Nexgo 2.8.5 y por tanto YA
     * tiene `pending_refunds`, con filas dentro.
     *
     * 🔴 Es el caso que obliga a que la 30→31 sea `IF NOT EXISTS`: un `CREATE TABLE` pelón la
     * tumbaría con «table already exists» en el arranque — el crash-loop que la renumeración viene
     * a evitar. Y una devolución encolada (dinero que YA salió del cajón y todavía no está anotado
     * en el servidor) tiene que sobrevivir la actualización.
     */
    @Test
    fun migrate30To31_sobreElV30DeEsteArbol_esNoOpYConservaLosReembolsosEncolados() {
        // `createDatabase(TEST_DB, 30)` levanta el esquema de `app/schemas/…/30.json`, que es
        // exactamente el v30 de ESTE árbol: `pending_refunds` ya viene creada.
        helper.createDatabase(TEST_DB, 30).use { db ->
            db.execSQL(
                "INSERT INTO pending_refunds (idempotency_key, venue_id, staff_id, processor, " +
                    "original_payment_id, amount, original_total_amount, is_partial_refund, " +
                    "refund_reason, merchant_account_id, blumon_serial_number, " +
                    "original_operation_number, authorization_number, reference_number, entry_mode, " +
                    "created_at, retry_count, sync_status, permanent, acknowledged) VALUES " +
                    "('idem-r31','v1','s1','BLUMON','pay-1','50.00','100.00',1,'CUSTOMER_REQUEST'," +
                    "'m1','SER1',75656,'502511','000000188231','CHIP',123,0,'PENDING',0,0)",
            )
        }

        val open = helper.runMigrationsAndValidate(TEST_DB, 31, true, AvoqadoDatabase.MIGRATION_30_31)
        try {
            open.query("SELECT idempotency_key FROM pending_refunds").use { c ->
                assertThat(c.count).isEqualTo(1)
                c.moveToFirst()
                assertThat(c.getString(0)).isEqualTo("idem-r31")
            }
        } finally {
            open.close()
        }
    }

    /**
     * v30 → v31 sobre el v30 de `develop` (nexgo-v2.9.0): ahí NO existe `pending_refunds` — existe
     * `remote_payment_requests`, que este árbol ni conoce. La migración tiene que crear la tabla
     * que falta y dejar en paz la que sobra.
     *
     * 🔴 Que la tabla huérfana sobreviva no es un descuido: Room sólo valida las tablas de sus
     * `@Entity`, así que una tabla de más no rompe la apertura — y borrarla sí destruiría la cola
     * de cobros POS→TPV de quien venga por ese camino.
     */
    @Test
    fun migrate30To31_sobreElV30DeDevelop_creaLaTablaQueFaltaYNoTocaLaAjena() {
        helper.createDatabase(TEST_DB, 30).use { db ->
            // Imitación del v30 de `develop`: su tabla existe, la nuestra NO. Hay que quitar la que
            // `30.json` (el v30 de este árbol) acaba de crear — si no, la prueba no ejercitaría el
            // caso que importa: llegar a la 30→31 SIN `pending_refunds`.
            db.execSQL("DROP TABLE IF EXISTS pending_refunds")
            db.execSQL("CREATE TABLE IF NOT EXISTS remote_payment_requests (id TEXT NOT NULL PRIMARY KEY, venue_id TEXT NOT NULL)")
            db.execSQL("INSERT INTO remote_payment_requests (id, venue_id) VALUES ('req-1','v1')")
        }

        val open = helper.runMigrationsAndValidate(TEST_DB, 31, true, AvoqadoDatabase.MIGRATION_30_31)
        try {
            open.query("SELECT COUNT(*) FROM pending_refunds").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(0)
            }
            open.query("SELECT COUNT(*) FROM remote_payment_requests").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(1)
            }
        } finally {
            open.close()
        }
    }

    /**
     * The money guarantee, v29 edition: a payment already marked FAILED before the upgrade
     * (real money the app already gave up retrying) must survive untouched, and the new
     * `permanent` column must default to 0/false — i.e. "unknown whether this 4xx was
     * permanent" resolves to "resurrectable", never to "silently excluded from
     * resetAllFailed() forever". See PendingPaymentEntity.permanent for why that default
     * (not 1/true) is the safe one.
     */
    @Test
    fun migrate28To29_preservesFailedPaymentsAndDefaultsPermanentToFalse() {
        helper.createDatabase(TEST_DB, 28).use { db ->
            db.execSQL(
                "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, " +
                    "merchant_account_id, blumon_serial_number, entry_mode, is_international, " +
                    "created_at, idempotency_key, payment_processor, retry_count, sync_status) VALUES " +
                    "('REF-V29-001','v1','s1','410.00','41.00','m1','SER1','CHIP',0,123,'idem-29','BLUMON',10,'FAILED')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 29, true, AvoqadoDatabase.MIGRATION_28_29)

        migrated.query(
            "SELECT amount, sync_status, retry_count, permanent " +
                "FROM pending_payments WHERE reference_number = 'REF-V29-001'",
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue() // the FAILED payment must survive the migration
            assertThat(c.getString(0)).isEqualTo("410.00") // amount intact
            assertThat(c.getString(1)).isEqualTo("FAILED") // status untouched by the migration itself
            assertThat(c.getInt(2)).isEqualTo(10) // retry_count untouched
            assertThat(c.getInt(3)).isEqualTo(0) // permanent defaults to false — resurrectable
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-avoqado"
        // database.identityHash from app/schemas/.../23.json
        private const val V23_IDENTITY_HASH = "94cda4dd6019c963834757e2ea725ec4"
    }
}
