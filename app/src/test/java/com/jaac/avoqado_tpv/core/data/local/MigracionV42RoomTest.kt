package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.core.data.local.mappers.toDomain
import com.jaac.avoqado_tpv.core.data.local.mappers.toEntity
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.features.ordering.domain.Product
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
 * Migración 41 → 42 (variantes, bloque 4): `products` gana `product_group_id`, `product_group_name`, `product_group_image_url` y
 * `variant_options_json` para que Órdenes arme los botones de familia SIN red. Aditiva y nullable.
 *
 * Arranca de una base v41 REAL (el esquema exportado de la versión en la calle: `41.json`) con el catálogo ya guardado, y la abre el
 * MISMO builder de producción, o sea que Room valida el esquema y el identity hash como lo hará en una terminal. Una terminal que se
 * actualiza con catálogo guardado no pierde filas: las columnas nuevas quedan NULL (cada producto sale suelto) hasta el próximo
 * refresco.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV42RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v42 conserva el catalogo guardado y deja la familia en null`() = runTest {
        crearBaseV41 { db ->
            producto(db, "p-1", "Playera S", "199.00")
            producto(db, "p-2", "Taza", "85.50")
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val filas = db.productDao().getAllProducts("v1")
            assertWithMessage("las dos filas del catálogo guardado sobreviven").that(filas.map { it.productId }).containsExactly("p-1", "p-2")
            assertThat(filas.associate { it.productId to it.price }).containsExactly("p-1", "199.00", "p-2", "85.50")
            assertWithMessage("sin familia hasta el siguiente refresco").that(filas.map { it.toDomain().familia }).containsExactly(null, null)

            db.openHelper.readableDatabase.query(
                "SELECT product_group_id, product_group_name, product_group_image_url, variant_options_json FROM products",
            ).use { c ->
                var n = 0
                while (c.moveToNext()) {
                    n++
                    for (i in 0..3) assertWithMessage("columna $i de la fila $n").that(c.isNull(i)).isTrue()
                }
                assertThat(n).isEqualTo(2)
            }
        } finally {
            db.close()
        }
    }

    @Test fun `la migracion 41 a 42 es idempotente`() = runTest {
        crearBaseV41 { db -> producto(db, "p-1", "Playera S", "199.00") }

        val db = DatabaseModule.provideDatabase(context)
        try {
            // Room ya migró al abrir; correrla otra vez (instalación a medias, reintento) no truena ni toca lo guardado.
            AvoqadoDatabase.MIGRATION_41_42.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_41_42.migrate(db.openHelper.writableDatabase)
            assertThat(db.productDao().getAllProducts("v1").map { it.productId }).containsExactly("p-1")
            val columnas = mutableListOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA table_info(products)").use { c ->
                val nombre = c.getColumnIndex("name")
                while (c.moveToNext()) columnas += c.getString(nombre)
            }
            assertThat(columnas.filter { it.startsWith("product_group") || it == "variant_options_json" })
                .containsExactly("product_group_id", "product_group_name", "product_group_image_url", "variant_options_json")
        } finally {
            db.close()
        }
    }

    @Test fun `ida y vuelta de la familia por Room`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java).allowMainThreadQueries().build()
        try {
            val familia = DatosDeFamilia(
                groupId = "g-1",
                groupName = "Playera básica",
                groupImageUrl = "https://img/playera.png",
                opciones = listOf(OpcionDeVariante("Talla", "M", 0, 1), OpcionDeVariante("Color", "Rojo", 1, 0)),
            )
            val conFamilia = producto("p-1", "Playera M", familia)
            val suelto = producto("p-2", "Taza", null)
            val sinOpciones = producto("p-3", "Playera rara", familia.copy(opciones = null))

            db.productDao().upsertProducts(listOf(conFamilia, suelto, sinOpciones).map { it.toEntity("v1", 100L) })

            val leidos = db.productDao().getAllProducts("v1").toDomain().associateBy { it.id }
            assertThat(leidos.getValue("p-1").familia).isEqualTo(familia)
            assertThat(leidos.getValue("p-2").familia).isNull()
            assertWithMessage("opciones null viaja como null (el producto sale suelto, igual que desde la red)")
                .that(leidos.getValue("p-3").familia).isEqualTo(familia.copy(opciones = null))
        } finally {
            db.close()
        }
    }

    // La fila se lee con la MISMA regla que el payload de la red (adaptador tolerante + datosDeFamiliaDeDto): sin segunda regla.
    @Test fun `una fila con opciones de forma rara sale con opciones null y con group id en blanco sale sin familia`() {
        val base = entidad(groupId = "g-1", groupName = "Playera", opcionesJson = null)
        for (raro in listOf("", "   ", "no es json", "{\"name\":\"Talla\"}", "[{}]", "[{\"name\":\"\",\"value\":\"M\",\"namePosition\":0,\"valuePosition\":0}]", "null")) {
            val f = base.copy(variantOptionsJson = raro).toDomain().familia
            assertWithMessage("opciones '$raro'").that(f?.opciones).isNull()
            assertWithMessage("el resto de la familia se conserva ('$raro')").that(f?.groupId).isEqualTo("g-1")
        }
        val valido = base.copy(variantOptionsJson = "[{\"name\":\"Talla\",\"value\":\"M\",\"namePosition\":0,\"valuePosition\":2}]").toDomain().familia
        assertThat(valido?.opciones).containsExactly(OpcionDeVariante("Talla", "M", 0, 2))
        for (id in listOf(null, "", "  ")) {
            assertWithMessage("group id '$id'").that(base.copy(productGroupId = id).toDomain().familia).isNull()
        }
    }

    private fun producto(id: String, nombre: String, familia: DatosDeFamilia?) = Product(
        id = id, name = nombre, sku = "sku-$id", price = BigDecimal("199.00"), categoryId = "c-1", categoryName = "Ropa",
        description = null, emoji = "👕", imageUrl = null, available = true, familia = familia,
    )

    private fun entidad(groupId: String?, groupName: String?, opcionesJson: String?) = ProductEntity(
        productId = "p-1", venueId = "v1", name = "Playera", sku = "sku", price = "199.00", categoryId = "c-1", categoryName = "Ropa",
        description = null, emoji = "👕", imageUrl = null, available = true, displayOrder = 0, trackInventory = false,
        inventoryMethod = null, availableQuantity = null, modifierGroupsJson = "[]", cachedAt = 100L,
        productGroupId = groupId, productGroupName = groupName, productGroupImageUrl = null, variantOptionsJson = opcionesJson,
    )

    /** Una fila de `products` con la forma de la v41 (sin las columnas de familia). */
    private fun producto(db: SQLiteDatabase, productId: String, nombre: String, precio: String) {
        db.execSQL(
            "INSERT INTO products (product_id, venue_id, name, sku, price, category_id, category_name, description, emoji, image_url, " +
                "available, display_order, track_inventory, inventory_method, available_quantity, modifier_groups_json, " +
                "category_color, category_is_active, cached_at) VALUES (?,'v1',?,?,?,'c-1','Ropa',NULL,'👕',NULL,1,0,0,NULL,NULL,'[]',NULL,1,100)",
            arrayOf(productId, nombre, "sku-$productId", precio),
        )
    }

    /** Crea en disco la base v41 con el esquema exportado (app/schemas/…/41.json) y siembra filas. */
    private fun crearBaseV41(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("41.json").readText()).getJSONObject("database")
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
}
