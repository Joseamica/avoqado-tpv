package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.entities.CachedTimeEntryEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

/**
 * Migración 42 → 43 («Cobrar» sin red): la ÚNICA subida de base del proyecto.
 *
 * - `products` gana `gtin` y `catalog_at` (la hora del SERVIDOR en que se leyó el catálogo: el precio visto).
 * - `pending_payments` gana lo de la venta guardada: `order_request_json`, `exito_mostrado` (1), `acknowledged` (0), `ticket_json`,
 *   `reloj_monotono_al_vender`, `arranque_al_vender` y `requiere_revision` (0).
 * - Tabla nueva `cached_time_entry` (el checador guardado).
 *
 * Arranca de una base v42 REAL (`42.json`, la de la 2.13.0) con catálogo y un pago en cola, y la abre el MISMO builder de producción:
 * Room valida esquema e identity hash como en una terminal. Nada se pierde y las filas viejas quedan como «ya mostradas, sin revisión».
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV43RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v43 conserva catalogo, pagos pendientes y deja columnas nuevas en null o default`() = runTest {
        crearBaseV42 { db ->
            producto(db, "p-1", "Playera S", "199.00")
            producto(db, "p-2", "Taza", "85.50")
            pagoEnCola(db, "ref-1")
            dineroEnVuelo(db)
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val filas = db.productDao().getAllProducts("v1")
            assertWithMessage("el catálogo guardado sobrevive").that(filas.map { it.productId }).containsExactly("p-1", "p-2")
            assertThat(filas.map { it.gtin }).containsExactly(null, null)
            assertThat(filas.map { it.catalogAt }).containsExactly(null, null)

            db.openHelper.readableDatabase.query(
                "SELECT amount, sync_status, order_request_json, exito_mostrado, acknowledged, ticket_json, reloj_monotono_al_vender, " +
                    "arranque_al_vender, requiere_revision FROM pending_payments",
            ).use { c ->
                assertThat(c.moveToNext()).isTrue()
                assertWithMessage("el pago en cola sobrevive").that(c.getString(0)).isEqualTo("150.00")
                assertThat(c.getString(1)).isEqualTo("PENDING")
                assertWithMessage("order_request_json").that(c.isNull(2)).isTrue()
                assertWithMessage("exito_mostrado").that(c.getInt(3)).isEqualTo(1)
                assertWithMessage("acknowledged").that(c.getInt(4)).isEqualTo(0)
                assertWithMessage("ticket_json").that(c.isNull(5)).isTrue()
                assertWithMessage("reloj_monotono_al_vender").that(c.isNull(6)).isTrue()
                assertWithMessage("arranque_al_vender").that(c.isNull(7)).isTrue()
                assertWithMessage("requiere_revision").that(c.getInt(8)).isEqualTo(0)
                assertThat(c.moveToNext()).isFalse()
            }
            assertWithMessage("el checador guardado nace vacío").that(db.cachedTimeEntryDao().leer("v1", "s-1")).isNull()

            // Lo demás que es dinero en vuelo (la libreta, los reembolsos y la bandeja de cobros remotos) no lo toca la migración.
            assertThat(unaFila(db, "SELECT attempt_id || ':' || state || ':' || amount_cents FROM payment_attempts"))
                .isEqualTo("att-1:AUTORIZADO:15000")
            assertThat(unaFila(db, "SELECT idempotency_key || ':' || amount || ':' || sync_status FROM pending_refunds"))
                .isEqualTo("ref-dev-1:50.00:PENDING")
            assertThat(unaFila(db, "SELECT request_id || ':' || status || ':' || amount_cents FROM remote_payment_requests"))
                .isEqualTo("req-1:EXECUTING:20000")
        } finally {
            db.close()
        }
    }

    @Test fun `una fila de pending_payments vieja queda con exito_mostrado=1 y acknowledged=0`() = runTest {
        crearBaseV42 { db -> pagoEnCola(db, "ref-1") }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val fila = db.pendingPaymentDao().findByReference("ref-1", "v1")!!
            // Una venta de antes de actualizar no es una «venta guardada sin mostrar» ni una diferencia por revisar.
            assertThat(fila.exitoMostrado).isTrue()
            assertThat(fila.acknowledged).isFalse()
            assertThat(fila.requiereRevision).isFalse()
            assertThat(fila.orderRequestJson).isNull()
            assertThat(fila.ticketJson).isNull()
            assertThat(fila.relojMonotonoAlVender).isNull()
            assertThat(fila.arranqueAlVender).isNull()
        } finally {
            db.close()
        }
    }

    @Test fun `la revision nueva nace sin actor ni fecha al migrar desde 42`() = runTest {
        crearBaseV42 { db -> pagoEnCola(db, "ref-actor") }
        val db = DatabaseModule.provideDatabase(context)
        try {
            db.openHelper.readableDatabase.query("SELECT acknowledged_by, acknowledged_at FROM pending_payments").use { c ->
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.isNull(0)).isTrue()
                assertThat(c.isNull(1)).isTrue()
            }
        } finally { db.close() }
    }

    @Test fun `la base migrada reabre con dinero turno JSON y revision persistidos sin cambiar al vendedor`() = runTest {
        crearBaseV42 { db ->
            pagoEnCola(db, "ref-reopen")
            db.execSQL("UPDATE pending_payments SET sync_status = 'FAILED', tip = '5.25', shift_id = 'turno-original', " +
                "idempotency_key = 'llave-original', order_id = 'orden-original', order_number = 'folio-original'")
        }
        val migrated = DatabaseModule.provideDatabase(context)
        val saved: PendingPaymentEntity
        try {
            val before = migrated.pendingPaymentDao().findByReference("ref-reopen", "v1")!!
            assertThat(before.amount).isEqualTo("150.00")
            assertThat(before.tip).isEqualTo("5.25")
            assertThat(before.shiftId).isEqualTo("turno-original")
            assertThat(before.idempotencyKey).isEqualTo("llave-original")
            assertThat(before.orderId).isEqualTo("orden-original")
            assertThat(before.orderNumber).isEqualTo("folio-original")
            assertThat(before.staffId).isEqualTo("s-1")
            migrated.openHelper.writableDatabase.execSQL(
                "UPDATE pending_payments SET order_request_json = ?, ticket_json = ?, requiere_revision = 1",
                arrayOf("{\"total\":155.25,\"items\":[\"Taza\"]}", "{\"total\":155.25,\"shift\":\"turno-original\"}"),
            )
            assertThat(migrated.pendingPaymentDao().reconocer(before.id, "v1", "supervisora", 987L)).isEqualTo(1)
            saved = migrated.pendingPaymentDao().findByReference("ref-reopen", "v1")!!
            assertThat(saved.acknowledgedBy).isEqualTo("supervisora")
            assertThat(saved.acknowledgedAt).isEqualTo(987L)
            assertThat(saved.staffId).isEqualTo("s-1")
        } finally { migrated.close() }
        val reopened = DatabaseModule.provideDatabase(context)
        try {
            assertThat(reopened.pendingPaymentDao().findByReference("ref-reopen", "v1")).isEqualTo(saved)
            assertThat(reopened.pendingPaymentDao().contarBloqueanCierre("v1")).isEqualTo(0)
        } finally { reopened.close() }
    }

    @Test fun `la migracion 42 a 43 es idempotente`() = runTest {
        crearBaseV42 { db ->
            producto(db, "p-1", "Playera S", "199.00")
            pagoEnCola(db, "ref-1")
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            db.cachedTimeEntryDao().guardar(CachedTimeEntryEntity("v1", "s-1", "te-1", "CLOCKED_IN", "2026-10-20T15:00:00.000Z", 100L))
            // Room ya migró al abrir; correrla otra vez (instalación a medias, reintento) no truena ni toca lo guardado.
            AvoqadoDatabase.MIGRATION_42_43.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_42_43.migrate(db.openHelper.writableDatabase)

            assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("p-1")
            assertThat(db.pendingPaymentDao().findByReference("ref-1", "v1")).isNotNull()
            assertThat(db.cachedTimeEntryDao().leer("v1", "s-1")?.entryId).isEqualTo("te-1")
            assertThat(columnas(db, "products").filter { it == "gtin" || it == "catalog_at" }).containsExactly("gtin", "catalog_at")
            assertThat(columnas(db, "pending_payments").filter { it in NUEVAS_DE_PAGOS }).containsExactlyElementsIn(NUEVAS_DE_PAGOS)
        } finally {
            db.close()
        }
    }

    @Test fun `ida y vuelta de gtin y catalogAt`() = runTest {
        val db = enMemoria()
        try {
            val conCodigo = producto("p-1", "Agua").copy(gtin = "7501055300075", catalogAt = "2026-10-20T18:00:00.000Z")
            val sinCodigo = producto("p-2", "Taza")
            db.productDao().upsertProducts(listOf(conCodigo, sinCodigo).map { it.toEntity("v1", 100L) })

            val leidos = db.productDao().getAllProducts("v1").toDomain().associateBy { it.id }
            assertThat(leidos.getValue("p-1")).isEqualTo(conCodigo)
            assertThat(leidos.getValue("p-2").gtin).isNull()
            assertThat(leidos.getValue("p-2").catalogAt).isNull()
        } finally {
            db.close()
        }
    }

    @Test fun `getProductByCodigo encuentra por gtin, primero por sku y nunca por un codigo vacio`() = runTest {
        val db = enMemoria()
        try {
            db.productDao().upsertProducts(
                listOf(
                    producto("p-1", "Agua").copy(sku = "AGUA-1", gtin = "7501055300075"),
                    // Su SKU es el GTIN de otro: gana quien lo tiene como SKU.
                    producto("p-2", "Agua de la casa").copy(sku = "7501055300099", gtin = null),
                    producto("p-3", "Agua importada").copy(sku = "AGUA-3", gtin = "7501055300099"),
                    producto("p-4", "Sin código").copy(sku = "", gtin = null),
                ).map { it.toEntity("v1", 100L) } + producto("p-9", "De otro negocio").copy(gtin = "7501055300075").toEntity("v2", 100L),
            )

            assertThat(db.productDao().getProductByCodigo("v1", "7501055300075")?.productId).isEqualTo("p-1")
            assertThat(db.productDao().getProductByCodigo("v1", "AGUA-1")?.productId).isEqualTo("p-1")
            assertThat(db.productDao().getProductByCodigo("v1", "7501055300099")?.productId).isEqualTo("p-2")
            assertThat(db.productDao().getProductByCodigo("v1", "no-existe")).isNull()
            assertWithMessage("un escaneo vacío no agrega un producto al azar").that(db.productDao().getProductByCodigo("v1", "")).isNull()
            assertThat(db.productDao().getProductByCodigo("v2", "7501055300075")?.productId).isEqualTo("p-9")
        } finally {
            db.close()
        }
    }

    // Un JSON dañado de verdad (no el formato de R8 de antes de la v43, que sí se lee: ExtrasConNombresDeR8Test) no tumba a quien lee
    // el catálogo guardado: el producto sale sin extras hasta el siguiente refresco con red.
    @Test fun `modifier_groups_json corrupto sale como producto sin extras, sin tronar`() {
        for (raro in listOf("no es json", "{\"a\":1}", "[{\"a\":\"g-1\",\"b\":\"Término\",\"c\":[]}]", "[null]", "[{\"id\":\"g-1\"}]")) {
            val p = entidad(modifierGroupsJson = raro).toDomain()
            assertWithMessage("modifier_groups_json '$raro'").that(p.modifierGroups).isEmpty()
            assertWithMessage("el resto del producto se conserva ('$raro')").that(p.name).isEqualTo("Playera")
        }

        val grupos = listOf(
            ModifierGroup(
                id = "g-1", name = "Término", type = ModifierType.SINGLE_CHOICE, required = true, minSelections = 1, maxSelections = 1,
                modifiers = listOf(ProductModifier("m-1", "Medio", BigDecimal("0"), ModifierType.SINGLE_CHOICE)),
            ),
        )
        val guardado = producto("p-1", "Hamburguesa").copy(modifierGroups = grupos).toEntity("v1", 100L)
        assertWithMessage("un JSON bueno se lee igual que antes").that(guardado.toDomain().modifierGroups).isEqualTo(grupos)
    }

    @Test fun `el checador guardado se guarda, se reemplaza, se lee por negocio y persona y se borra`() = runTest {
        val db = enMemoria()
        try {
            val dao = db.cachedTimeEntryDao()
            dao.guardar(CachedTimeEntryEntity("v1", "s-1", "te-1", "CLOCKED_IN", "2026-10-20T15:00:00.000Z", 100L))
            dao.guardar(CachedTimeEntryEntity("v1", "s-1", "te-2", "ON_BREAK", null, 200L))
            dao.guardar(CachedTimeEntryEntity("v2", "s-1", "te-9", "CLOCKED_IN", null, 300L))

            assertThat(dao.leer("v1", "s-1")).isEqualTo(CachedTimeEntryEntity("v1", "s-1", "te-2", "ON_BREAK", null, 200L))
            assertWithMessage("otra persona no lee la entrada ajena").that(dao.leer("v1", "s-2")).isNull()
            dao.borrar("v1", "s-1")
            assertThat(dao.leer("v1", "s-1")).isNull()
            assertWithMessage("borrar no toca otro negocio").that(dao.leer("v2", "s-1")?.entryId).isEqualTo("te-9")
        } finally {
            db.close()
        }
    }

    @Test fun `la limpieza de pagos viejos no borra una venta con diferencia sin revisar`() = runTest {
        val db = enMemoria()
        try {
            val dao = db.pendingPaymentDao()
            dao.insert(pago("ok-viejo", sincronizado = true))
            dao.insert(pago("diferencia-sin-revisar", sincronizado = true).copy(requiereRevision = true))
            dao.insert(pago("diferencia-revisada", sincronizado = true).copy(requiereRevision = true, acknowledged = true))

            dao.deleteOldSyncedPayments(timestampMs = 1_000L)

            assertThat(dao.findByReference("ok-viejo", "v1")).isNull()
            assertThat(dao.findByReference("diferencia-sin-revisar", "v1")).isNotNull()
            assertThat(dao.findByReference("diferencia-revisada", "v1")).isNull()
        } finally {
            db.close()
        }
    }

    private fun enMemoria() =
        Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()

    private fun columnas(db: AvoqadoDatabase, tabla: String): List<String> {
        val columnas = mutableListOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info($tabla)").use { c ->
            val nombre = c.getColumnIndex("name")
            while (c.moveToNext()) columnas += c.getString(nombre)
        }
        return columnas
    }

    private fun producto(id: String, nombre: String) = Product(
        id = id, name = nombre, sku = "sku-$id", price = BigDecimal("25.00"), categoryId = "c-1", categoryName = "Bebidas",
        description = null, emoji = "🥤", imageUrl = null, available = true,
    )

    private fun entidad(modifierGroupsJson: String) = ProductEntity(
        productId = "p-1", venueId = "v1", name = "Playera", sku = "sku", price = "199.00", categoryId = "c-1", categoryName = "Ropa",
        description = null, emoji = "👕", imageUrl = null, available = true, displayOrder = 0, trackInventory = false,
        inventoryMethod = null, availableQuantity = null, modifierGroupsJson = modifierGroupsJson, cachedAt = 100L,
    )

    private fun pago(referencia: String, sincronizado: Boolean) = PendingPaymentEntity(
        referenceNumber = referencia, venueId = "v1", staffId = "s-1", amount = "150.00", tip = "0.00", rating = null,
        merchantAccountId = "", blumonSerialNumber = "", maskedPan = null, cardBrand = null, entryMode = "CASH",
        isInternational = false, authorizationNumber = null, createdAt = 10L,
        syncStatus = if (sincronizado) PendingPaymentEntity.SYNC_STATUS_SUCCESS else PendingPaymentEntity.SYNC_STATUS_PENDING,
    )

    /** Una fila de `products` con la forma de la v42 (sin `gtin` ni `catalog_at`). */
    private fun producto(db: SQLiteDatabase, productId: String, nombre: String, precio: String) {
        db.execSQL(
            "INSERT INTO products (product_id, venue_id, name, sku, price, category_id, category_name, description, emoji, image_url, " +
                "available, display_order, track_inventory, inventory_method, available_quantity, modifier_groups_json, " +
                "category_color, category_is_active, cached_at, product_group_id, product_group_name, product_group_image_url, " +
                "variant_options_json) VALUES (?,'v1',?,?,?,'c-1','Ropa',NULL,'👕',NULL,1,0,0,NULL,NULL,'[]',NULL,1,100,NULL,NULL,NULL,NULL)",
            arrayOf(productId, nombre, "sku-$productId", precio),
        )
    }

    /** Un pago en efectivo en cola con la forma de la v42 (sin las columnas de la venta guardada). */
    private fun pagoEnCola(db: SQLiteDatabase, referencia: String) {
        db.execSQL(
            "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, rating, merchant_account_id, " +
                "blumon_serial_number, entry_mode, is_international, payment_processor, is_portabilidad, created_at, retry_count, " +
                "sync_status, permanent) VALUES (?,'v1','s-1','150.00','0.00',NULL,'','','CASH',0,'BLUMON',0,10,0,'PENDING',0)",
            arrayOf(referencia),
        )
    }

    /** Una fila de la libreta, un reembolso en cola y un cobro remoto en curso, con la forma de la v42. */
    private fun dineroEnVuelo(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, state, amount_cents, tip_cents, recording_route, " +
                "payment_context_json, created_at, updated_at) VALUES ('att-1','v1','BLUMON','AUTORIZADO',15000,0,'FAST','{}',10,10)",
        )
        db.execSQL(
            "INSERT INTO pending_refunds (idempotency_key, venue_id, staff_id, processor, original_payment_id, amount, " +
                "original_total_amount, is_partial_refund, refund_reason, merchant_account_id, blumon_serial_number, " +
                "original_operation_number, authorization_number, reference_number, entry_mode, created_at, retry_count, sync_status) " +
                "VALUES ('ref-dev-1','v1','s-1','BLUMON','pay-1','50.00','150.00',1,'CUSTOMER_REQUEST','','',7,'502511','000000188231'," +
                "'CHIP',10,0,'PENDING')",
        )
        db.execSQL(
            "INSERT INTO remote_payment_requests (request_id, venue_id, amount_cents, tip_cents, skip_review, source_timestamp, status, " +
                "created_at, updated_at) VALUES ('req-1','v1',20000,0,0,'2026-10-20T18:00:00.000Z','EXECUTING',10,10)",
        )
    }

    private fun unaFila(db: AvoqadoDatabase, sql: String): String? =
        db.openHelper.readableDatabase.query(sql).use { c ->
            val filas = mutableListOf<String?>()
            while (c.moveToNext()) filas += c.getString(0)
            assertWithMessage(sql).that(filas).hasSize(1)
            filas.single()
        }

    /** Crea en disco la base v42 con el esquema exportado (app/schemas/…/42.json) y siembra filas. */
    private fun crearBaseV42(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("42.json").readText()).getJSONObject("database")
        val ruta = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        ruta.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(ruta, null)
        try {
            val entidades = esquema.getJSONArray("entities")
            for (i in 0 until entidades.length()) {
                val entidad = entidades.getJSONObject(i)
                val tabla = entidad.getString("tableName")
                db.execSQL(entidad.getString("createSql").replace("\${TABLE_NAME}", tabla))
                val indices = entidad.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tabla))
                }
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            // 42 aquí es el id fijo de la fila de Room (no la versión): la versión va en `db.version`.
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(esquema.getString("identityHash")),
            )
            db.version = esquema.getInt("version")
            sembrar(db)
        } finally {
            db.close()
        }
    }

    private fun archivoDeEsquema(nombre: String): File {
        val carpeta = "schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase"
        return listOf(File("$carpeta/$nombre"), File("app/$carpeta/$nombre"), File("../app/$carpeta/$nombre"))
            .firstOrNull { it.exists() }
            ?: error("No se encontró el esquema exportado $nombre (cwd=${File(".").absolutePath})")
    }

    private companion object {
        val NUEVAS_DE_PAGOS = listOf(
            "order_request_json", "exito_mostrado", "acknowledged", "ticket_json", "reloj_monotono_al_vender", "arranque_al_vender",
            "requiere_revision", "acknowledged_by", "acknowledged_at",
        )
    }
}
