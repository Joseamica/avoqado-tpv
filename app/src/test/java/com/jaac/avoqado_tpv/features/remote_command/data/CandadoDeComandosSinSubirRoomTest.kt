package com.jaac.avoqado_tpv.features.remote_command.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D4 (encargo dd4185): bajar de versión borra la base entera (`fallbackToDestructiveMigrationOnDowngrade`). El candado de
 * los comandos remotos pregunta estas dos cuentas; aquí se prueba contra Room de verdad QUÉ filas cuentan: sólo lo que
 * sube solo, de la sucursal activa. Un rechazo en cuarentena, una caja en revisión (y el cierre que queda detrás de ella)
 * o lo de otra sucursal no se drenan sin una persona y bloquearían la terminal para siempre (Codex r1 #1 y #2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CandadoDeComandosSinSubirRoomTest {
    private lateinit var db: AvoqadoDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun intent(id: String, venue: String, seq: Long, status: String) =
        SyncIntentEntity(id = id, venueId = venue, seq = seq, type = "PAY_CASH", payloadJson = "{}", status = status)

    private fun caja(id: String, venue: String, opening: String, close: String = "NONE", local: String = "OPEN") =
        LocalShiftSessionEntity(localSessionId = id, venueId = venue, openingKind = "LOCAL", openingOperationId = id,
            openingStatus = opening, originalOpenedAt = "2026-11-16T10:00:00Z", startingCash = "50.00",
            openedByStaffId = "staff", localStatus = local, openSlot = if (local == "OPEN") 1 else null,
            closeStatus = close, createdAt = 1, updatedAt = 1)

    @Test fun `ventas de mesas sin subir cuentan sólo en la sucursal activa y las ya resueltas no`() = runTest {
        val dao = db.syncIntentDao()
        assertThat(dao.contarSinSubir("v1")).isEqualTo(0)
        dao.insert(intent("a", "v1", 1, SyncIntentEntity.STATUS_ACKED))
        dao.insert(intent("b", "v1", 2, SyncIntentEntity.STATUS_REJECTED))
        dao.insert(intent("otra", "v2", 1, SyncIntentEntity.STATUS_PENDING))
        assertThat(dao.contarSinSubir("v1")).isEqualTo(0)
        dao.insert(intent("c", "v1", 3, SyncIntentEntity.STATUS_PENDING))
        dao.insert(intent("d", "v1", 4, SyncIntentEntity.STATUS_PENDING))
        assertThat(dao.contarSinSubir("v1")).isEqualTo(2)
    }

    @Test fun `cajas que suben solas cuentan y las confirmadas, en revision o de otra sucursal no`() = runTest {
        val dao = db.localShiftSessionDao()
        dao.insertar(caja("c1", "v1", opening = "CONFIRMED", close = "CONFIRMED", local = "CLOSED"))
        dao.insertar(caja("c2", "v1", opening = "REVIEW", local = "CLOSED"))
        dao.insertar(caja("c3", "v1", opening = "CONFIRMED", close = "REVIEW", local = "CLOSED"))
        // Codex r1 #1: la apertura quedó en revisión y su cierre ya no sube solo (el cierre exige la apertura CONFIRMED).
        dao.insertar(caja("c4", "v1", opening = "REVIEW", close = "PENDING", local = "CLOSED"))
        dao.insertar(caja("c5", "v1", opening = "CONFIRMED", local = "OPEN"))
        dao.insertar(caja("otra", "v2", opening = "PENDING", local = "CLOSED"))
        assertThat(dao.contarSinSubir("v1")).isEqualTo(0)
        dao.insertar(caja("c6", "v1", opening = "PENDING", local = "CLOSED"))
        dao.insertar(caja("c7", "v1", opening = "SYNCING", local = "OPEN"))
        dao.insertar(caja("c8", "v1", opening = "CONFIRMED", close = "SYNCING", local = "CLOSED"))
        dao.insertar(caja("c9", "v1", opening = "CONFIRMED", close = "PENDING", local = "CLOSED"))
        assertThat(dao.contarSinSubir("v1")).isEqualTo(4)
    }
}
