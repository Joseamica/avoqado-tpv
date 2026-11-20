package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import com.jaac.avoqado_tpv.features.payment.data.repository.PaymentQueueRepositoryImpl
import com.jaac.avoqado_tpv.features.payment.domain.model.CardBrand
import com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails
import com.jaac.avoqado_tpv.features.payment.domain.model.CardEntryMode
import com.jaac.avoqado_tpv.features.payment.domain.model.CardNature
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext
import com.jaac.avoqado_tpv.features.payment.domain.model.QueuedPayment
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal

/**
 * v46 → v47 (D5, 17-nov): `pending_payments.card_nature`, la naturaleza de la tarjeta (crédito/débito) con la que se armó el
 * registro. Sin ella, un débito que se reproducía desde la cola salía CREDIT_CARD y contradecía al registro directo. Aditiva y
 * nullable: las filas de una cola vieja quedan en NULL y se reproducen como hoy (crédito).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV47RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME) }

    @Test fun `v47 desde la v46 conserva la fila de la cola y una fila vieja sin el dato se reproduce como hoy (credito)`() = runTest {
        crearBase("46.json") { old -> old.execSQL(filaVieja()) }

        val db = DatabaseModule.provideDatabase(context)
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(48)
            val queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
            val vieja = queue.getAllPending().single { it.referenceNumber == "REF-V46" }
            assertThat(vieja.idempotencyKey).isEqualTo("k-v46")
            assertThat(vieja.toCardDetails().toPaymentMethod()).isEqualTo("CREDIT_CARD")
        } finally { db.close() }
    }

    @Test fun `una fila nueva con debito pasa por la base real y se reproduce como DEBIT_CARD`() = runTest {
        val db = DatabaseModule.provideDatabase(context)
        try {
            val queue = PaymentQueueRepositoryImpl(db.pendingPaymentDao())
            val debito = CardDetails(maskedPan = "", cardBrand = CardBrand.VISA, entryMode = CardEntryMode.OTHER, cardNature = CardNature.DEBIT)
            val credito = debito.copy(cardNature = CardNature.CREDIT)
            for ((ref, tarjeta) in listOf("REF-DEB" to debito, "REF-CRED" to credito)) {
                val fila = QueuedPayment.desdeContexto(contexto(ref), null, tarjeta, "AUTH1", ref, null)
                assertThat(queue.enqueue(fila).isSuccess).isTrue()
            }
            val leidas = queue.getAllPending().associateBy { it.referenceNumber }
            assertThat(leidas.getValue("REF-DEB").toCardDetails().toPaymentMethod()).isEqualTo("DEBIT_CARD")
            assertThat(leidas.getValue("REF-CRED").toCardDetails().toPaymentMethod()).isEqualTo("CREDIT_CARD")
        } finally { db.close() }
    }

    @Test fun `la migracion 46 a 47 es idempotente`() {
        crearBase("46.json") {}
        val db = DatabaseModule.provideDatabase(context)
        try {
            AvoqadoDatabase.MIGRATION_46_47.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_46_47.migrate(db.openHelper.writableDatabase)
            db.openHelper.readableDatabase.query("SELECT card_nature FROM pending_payments").close()
        } finally { db.close() }
    }

    private fun contexto(ref: String) = PaymentContext.AngelPayPayment(
        venueId = "v1", staffId = "s1", shiftId = null, amount = BigDecimal("100.00"), tip = BigDecimal.ZERO,
        idempotencyKey = "k-$ref", authorizationCode = "AUTH1", referenceNumber = ref,
    )

    /** INSERT de una fila de la cola v46: sólo las columnas NOT NULL sin default, con una tarjeta de AngelPay. */
    private fun filaVieja() =
        "INSERT INTO pending_payments (reference_number, venue_id, staff_id, amount, tip, merchant_account_id, " +
            "blumon_serial_number, entry_mode, is_international, created_at, retry_count, sync_status, idempotency_key, " +
            "payment_processor, card_brand) VALUES ('REF-V46','v1','s1','100.00','0.00','cma-1','','OTHER',0,1,0,'PENDING'," +
            "'k-v46','ANGELPAY','VISA')"

    /** Crea en disco la base con el esquema exportado (app/schemas/…/<archivo>) y siembra filas. */
    private fun crearBase(archivo: String, sembrar: (SQLiteDatabase) -> Unit) {
        val schema = File("schemas/com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase/$archivo").let {
            if (it.exists()) it else File("app", it.path)
        }
        val definition = JSONObject(schema.readText()).getJSONObject("database")
        val file = context.getDatabasePath(AvoqadoDatabase.DATABASE_NAME)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = definition.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(definition.getString("identityHash")))
            old.version = definition.getInt("version")
            sembrar(old)
        }
    }
}
