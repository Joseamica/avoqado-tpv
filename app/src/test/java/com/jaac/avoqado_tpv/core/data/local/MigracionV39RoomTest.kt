package com.jaac.avoqado_tpv.core.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.di.DatabaseModule
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migración 38 → 39 (Codex r17, P1): repara las filas que la versión instalada dejó con un veredicto CON DINERO del
 * servidor pero sin la marca que aparta el aparato (`server_processor_evidence`). Desde la ronda 17 la marca va dentro de
 * la transacción del veredicto, pero una fila escrita ANTES no vuelve a pasar por ahí: la recuperación E2 no reaplica una
 * DESCARTADA y la E3 no consulta un RECORDED. Sin esta reparación, al actualizar, esa DESCARTADA con dinero dejaba
 * reservar y autorizar otro cobro.
 *
 * Arranca de una base v36 REAL (el esquema exportado de la versión en la calle) y la abre el MISMO builder de producción,
 * así que corre la cadena 36 → 37 → 38 → 39 que va a correr en una terminal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class MigracionV39RoomTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun limpiar() {
        context.deleteDatabase(AvoqadoDatabase.DATABASE_NAME)
    }

    @Test fun `la v39 marca las filas con dinero del servidor que la version anterior dejo sin marca, y nada mas`() = runTest {
        crearBaseV36 { db ->
            // El caso de Codex: Pago rápido liberado (DESCARTADA, sin orden) sobre el que el S5 viejo guardó RECORDED sin marca.
            fila(db, "a-descartada", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-a", verdictAt = 700L)
            fila(db, "colision", "INDETERMINADO", outcome = "REFERENCE_COLLISION_EVIDENCE", paymentId = "pay-c", verdictAt = 710L)
            // No se tocan: lo ya registrado, una liberación (no es dinero), otro procesador, una heredada y una ya marcada.
            fila(db, "registrada", "REGISTRADO", outcome = "RECORDED", paymentId = "pay-r", verdictAt = 720L)
            fila(db, "liberada", "DESCARTADA", outcome = "OPERATOR_NO_INSTRUMENT", paymentId = null, verdictAt = 730L)
            fila(db, "blumon", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-b", verdictAt = 740L, processor = "BLUMON")
            fila(db, "heredada", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-h", verdictAt = 750L, legacy = 1)
            fila(db, "ya-marcada", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-y", verdictAt = 760L, evidenciaAt = 111L)
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            val a = dao.getById("a-descartada")!!
            assertWithMessage("la DESCARTADA con RECORDED queda marcada").that(a.serverProcessorEvidence).isEqualTo("APPROVED")
            assertWithMessage("fechada desde el veredicto, no desde la migración").that(a.serverProcessorEvidenceAt).isEqualTo(700L)
            assertThat(a.state).isEqualTo("DESCARTADA")          // la migración no toca el estado…
            assertThat(a.serverOutcome).isEqualTo("RECORDED")    // …ni el veredicto
            assertThat(dao.getById("colision")!!.serverProcessorEvidence).isEqualTo("APPROVED")
            for (id in listOf("registrada", "liberada", "blumon", "heredada")) {
                assertWithMessage(id).that(dao.getById(id)!!.serverProcessorEvidence).isNull()
            }
            assertWithMessage("la primera fecha se conserva").that(dao.getById("ya-marcada")!!.serverProcessorEvidenceAt).isEqualTo(111L)
            assertThat(db.openHelper.readableDatabase.version).isAtLeast(39)

            // Idempotente: otra pasada no re-fecha lo marcado ni alcanza lo que quedó fuera.
            AvoqadoDatabase.MIGRATION_38_39.migrate(db.openHelper.writableDatabase)
            assertThat(dao.getById("a-descartada")!!.serverProcessorEvidenceAt).isEqualTo(700L)
            assertThat(dao.getById("registrada")!!.serverProcessorEvidence).isNull()
        } finally {
            db.close()
        }
    }

    @Test fun `el caso de Codex - tras actualizar, la DESCARTADA con RECORDED de la version anterior vuelve a apartar la terminal`() = runTest {
        crearBaseV36 { db -> fila(db, "a-descartada", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-a", verdictAt = 700L) }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val otro = db.paymentAttemptDao().reserveTerminal(
                "otro-cobro", "v1", "ANGELPAY", "SALE", 4000, 0, "FAST", """{"amount":40.00}""", null, 900L, null, false,
            )
            assertWithMessage("otro cobro no aparta la terminal con dinero pendiente de conciliar").that(otro).isEqualTo(-1L)
        } finally {
            db.close()
        }
    }

    // ── Codex r18 (P3): los estados con el SDK dentro, los otros dos desenlaces con dinero y los controles ──

    @Test fun `r18 · una AUTORIZANDO con RECORDED queda marcada, y si despues el host la rechaza la terminal sigue apartada`() = runTest {
        crearBaseV36 { db -> fila(db, "sdk-dentro", "AUTORIZANDO", outcome = "RECORDED", paymentId = "pay-s", verdictAt = 700L) }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            assertThat(dao.getById("sdk-dentro")!!.serverProcessorEvidence).isEqualTo("APPROVED")
            // El lector regresa con un rechazo (el CAS del host no mira el veredicto guardado): la fila pasa a DESCARTADA.
            assertThat(dao.casHostResponded("sdk-dentro", listOf("AUTORIZANDO"), "DESCARTADA", 900L, null, null, null, false)).isEqualTo(1)
            val otro = dao.reserveTerminal("otro-cobro", "v1", "ANGELPAY", "SALE", 4000, 0, "FAST", """{"amount":40.00}""", null, 950L, null, false)
            assertWithMessage("con la marca, el rechazo del host no deja cobrar encima del dinero").that(otro).isEqualTo(-1L)
        } finally {
            db.close()
        }
    }

    @Test fun `r18 · controles - KERNEL_ACTIVO, SECOND_CAPTURE y PENDING se marcan · CERRADA y una devolucion no · sin fecha de veredicto usa la de la fila`() = runTest {
        crearBaseV36 { db ->
            fila(db, "kernel", "KERNEL_ACTIVO", outcome = "SECOND_CAPTURE_EVIDENCE", paymentId = "pay-k", verdictAt = 700L)
            fila(db, "pendiente", "DESCARTADA", outcome = "PENDING_EVIDENCE", paymentId = "pay-p", verdictAt = null)
            fila(db, "cerrada", "CERRADA", outcome = "RECORDED", paymentId = "pay-c", verdictAt = 700L)
            fila(db, "devolucion", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-d", verdictAt = 700L, kind = "REFUND")
        }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            assertThat(dao.getById("kernel")!!.serverProcessorEvidence).isEqualTo("APPROVED")
            val pendiente = dao.getById("pendiente")!!
            assertThat(pendiente.serverProcessorEvidence).isEqualTo("APPROVED")
            assertWithMessage("sin fecha de veredicto, la de la última escritura de la fila").that(pendiente.serverProcessorEvidenceAt).isEqualTo(800L)
            assertThat(dao.getById("cerrada")!!.serverProcessorEvidence).isNull()
            assertThat(dao.getById("devolucion")!!.serverProcessorEvidence).isNull()
        } finally {
            db.close()
        }
    }

    @Test fun `el recorrido completo - tras actualizar, el cajero confirma que el cobro SI paso y la terminal vuelve a cobrar`() = runTest {
        crearBaseV36 { db -> fila(db, "a-descartada", "DESCARTADA", outcome = "RECORDED", paymentId = "pay-a", verdictAt = 700L) }

        val db = DatabaseModule.provideDatabase(context)
        try {
            val dao = db.paymentAttemptDao()
            val ledger = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger(dao, io.mockk.mockk(relaxed = true))
            assertThat(ledger.reconocerCobroRegistrado("v1", "a-descartada", "Ana (stf-1)", 1_000L)).isTrue()
            val otro = dao.reserveTerminal("otro-cobro", "v1", "ANGELPAY", "SALE", 4000, 0, "FAST", """{"amount":40.00}""", null, 1_100L, null, false)
            assertWithMessage("confirmado, la terminal vuelve a cobrar").that(otro).isNotEqualTo(-1L)
        } finally {
            db.close()
        }
    }

    private fun fila(
        db: SQLiteDatabase, attemptId: String, state: String, outcome: String?, paymentId: String?, verdictAt: Long?,
        processor: String = "ANGELPAY", legacy: Int = 0, evidenciaAt: Long? = null, kind: String = "SALE",
    ) {
        db.execSQL(
            "INSERT INTO payment_attempts (attempt_id, venue_id, processor, kind, state, state_version, " +
                "amount_cents, tip_cents, currency, recording_route, context_schema_version, payment_context_json, " +
                "verify_attempts, created_at, updated_at, legacy_shadow, server_outcome, server_payment_id, server_verdict_at, " +
                "server_amount_cents, server_tip_cents, server_recorded_via, server_processor_evidence, server_processor_evidence_at) VALUES " +
                "(?,'v1',?,?,?,1,4000,0,'MXN','FAST',1,'{\"amount\":40.00}',0,100,800,?,?,?,?,4000,0,'webhook',?,?)",
            arrayOf(attemptId, processor, kind, state, legacy, outcome, paymentId, verdictAt, evidenciaAt?.let { "APPROVED" }, evidenciaAt),
        )
    }

    /** Crea en disco la base v36 con el esquema exportado (app/schemas/…/36.json) y siembra filas. */
    private fun crearBaseV36(sembrar: (SQLiteDatabase) -> Unit) {
        val esquema = JSONObject(archivoDeEsquema("36.json").readText()).getJSONObject("database")
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
