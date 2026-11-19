package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v47 → v48 (F1 bloque 4, 18-nov): `payment_attempts.history_checks` y `history_read_at`. El turno de una consulta al historial de
 * AngelPay ya no se gasta al tomarla: de una fila vieja sólo el último turno pudo morir sin respuesta, y es el único que se devuelve.
 * `verify_attempts` queda para registrar la venta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV48RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun close() { context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME) }

    @Test fun `v48 desde la v47 devuelve solo el ultimo turno heredado y deja el contador de registro aparte`() = runTest {
        crearBase("47.json") { old ->
            old.execSQL(fila("duda-5", "ANGELPAY", "INDETERMINADO", hostApproved = "NULL", verifyAttempts = 5))
            old.execSQL(fila("duda-0", "ANGELPAY", "INDETERMINADO", hostApproved = "NULL", verifyAttempts = 0))
            old.execSQL(fila("aprobada", "ANGELPAY", "REGISTRO_FALLIDO", hostApproved = "1", verifyAttempts = 3))
            old.execSQL(fila("pax", "BLUMON", "INDETERMINADO", hostApproved = "NULL", verifyAttempts = 2))
        }
        val db = DatabaseModule.provideDatabase(context)
        try {
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(48)
            val dao = db.paymentAttemptDao()
            dao.getById("duda-5")!!.let {
                assertThat(it.historyChecks).isEqualTo(4)   // el quinto pudo morir sin respuesta: vuelve
                assertThat(it.verifyAttempts).isEqualTo(0)  // registrar empieza su propio contador
                assertThat(it.historyReadAt).isNull()
            }
            assertThat(dao.getById("duda-0")!!.historyChecks).isEqualTo(0)
            dao.getById("aprobada")!!.let {
                assertThat(it.historyChecks).isEqualTo(0)
                assertThat(it.verifyAttempts).isEqualTo(3)  // es del registro: no se toca
            }
            assertThat(dao.getById("pax")!!.verifyAttempts).isEqualTo(2)
        } finally { db.close() }
    }

    /**
     * 🔴 Codex final r1 #3: la app (v47) muere durante la QUINTA consulta al historial de una fila que la ventana automática ya cerró, y
     * se actualiza a v48. La migración devuelve ese último turno; además la deja como lectura DESPACHADA para que se retome aunque la
     * ventana la cerró (si no, ninguna consulta la volvía a elegir y la tarjeta de A quedaba sin cerca).
     */
    @Test fun `N86_migracion_v47_con_quinta_en_vuelo_se_retoma`() = runTest {
        val contexto = """{"venueId":"v1","idempotencyKey":"ventana-5","deviceSerialNumber":"terminal",""" +
            """"processorAffiliation":"afiliacion","orderId":"orden-A","amount":100.00,"tip":0}"""
        crearBase("47.json") { old ->
            old.execSQL(fila("ventana-5", "ANGELPAY", "DESCARTADA", hostApproved = "NULL", verifyAttempts = 5,
                serverOutcome = "'RELEASED_NO_EVIDENCE'", contexto = contexto))
            old.execSQL(fila("firmada-5", "ANGELPAY", "DESCARTADA", hostApproved = "NULL", verifyAttempts = 5,
                serverOutcome = "'OPERATOR_NO_INSTRUMENT'"))
        }
        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            dao.getById("ventana-5")!!.let {
                assertThat(it.historyChecks).isEqualTo(4)
                assertThat(it.historyReadAt).isNotNull()   // la quinta queda despachada: se retoma
            }
            // La firmada ya la sigue eligiendo la consulta por sí misma: no se le agrega cerca.
            assertThat(dao.getById("firmada-5")!!.historyReadAt).isNull()
            val ahora = System.currentTimeMillis()
            assertThat(dao.getUnknownRecoveryCandidates("v1", ahora, ahora).map { it.attemptId }).contains("ventana-5")

            val verificador = io.mockk.mockk<com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPayChargeVerifier>()
            io.mockk.coEvery { verificador.verificar(any(), any(), any(), any(), any(), any()) } returns
                com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.VerificacionDelCobro.Cobrado("AUTH", "REF", null)
            val libreta = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(dao, io.mockk.mockk(relaxed = true))
            com.jaac.avoqado_tpv.features.payment.data.ledger.LedgerUnknownRecovery(dao, libreta, verificador).recover("v1", ahora)
            dao.getById("ventana-5")!!.let {
                assertThat(it.hostApproved).isTrue()
                assertThat(it.referenceNumber).isEqualTo("REF")
            }
        } finally { db.close() }
    }

    @Test fun `la migracion 47 a 48 es idempotente`() {
        crearBase("47.json") { old -> old.execSQL(fila("duda-5", "ANGELPAY", "INDETERMINADO", hostApproved = "NULL", verifyAttempts = 5)) }
        val db = DatabaseModule.provideDatabase(context)
        try {
            AvoqadoDatabase.MIGRATION_47_48.migrate(db.openHelper.writableDatabase)
            AvoqadoDatabase.MIGRATION_47_48.migrate(db.openHelper.writableDatabase)
            db.openHelper.readableDatabase.query("SELECT history_checks, history_read_at FROM payment_attempts WHERE attempt_id = 'duda-5'").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getInt(0)).isEqualTo(4)
            }
        } finally { db.close() }
    }

    private fun fila(
        id: String, processor: String, state: String, hostApproved: String, verifyAttempts: Int,
        serverOutcome: String = "NULL", contexto: String = "{}",
    ) =
        "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, amount_cents, tip_cents, " +
            "currency, recording_route, context_schema_version, payment_context_json, host_approved, verify_attempts, created_at, " +
            "updated_at, legacy_shadow, server_check_count, shift_membership, server_outcome) VALUES ('$id','v1','$processor','SALE'," +
            "'$state',0,10000,0,'MXN','FAST',1,'$contexto',$hostApproved,$verifyAttempts,1,1,0,0,'UNKNOWN',$serverOutcome)"

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
