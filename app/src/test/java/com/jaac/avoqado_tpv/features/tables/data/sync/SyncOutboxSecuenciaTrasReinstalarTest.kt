package com.jaac.avoqado_tpv.features.tables.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * D6 (14-nov, PAX del Alien): tras reinstalar la TPV, el efectivo de Mesas se rechazaba con `STALE_DEVICE_SEQUENCE`.
 *
 * La base nueva numera desde 1 y el SERVIDOR (`sync.mobile.service.ts`, «Fence monotónico») rechaza todo número igual o
 * menor al último que ya vio de ese aparato, ANTES de reservar: no aplicó nada. Su rechazo trae `details.latestSeq`. La
 * TPV lo guardaba como definitivo y cada toque gastaba un número más, así que el efectivo seguía rechazado hasta alcanzar
 * sola el número viejo.
 *
 * Ahora, si `latestSeq` es MAYOR que cualquier número de la base (es de la instalación vieja: nada de esta base entró ni
 * quedó apartado) y nada de la respuesta entró antes, la cola en espera se renumera arriba, en el mismo orden, y se
 * reenvía en esa misma pasada. Si no, queda rechazado como antes (Codex r1: reenviarlo ejecutaría lo viejo después de lo
 * nuevo, o rompería la recuperación de una reserva, que el servidor retoma sólo con su número original).
 *
 * Room real bajo Robolectric (la renumeración choca con el índice único `venue_id, seq` si se hace mal).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SyncOutboxSecuenciaTrasReinstalarTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val secureStorage = mockk<SecureStorage>().also { every { it.getSerialNumber() } returns "AVQD-2841548417" }
    private val api = mockk<TablesApiService>()
    private val enviados = mutableListOf<SyncIntentsRequest>()
    private val db: AvoqadoDatabase = Room.inMemoryDatabaseBuilder(context, AvoqadoDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.syncIntentDao()
    private val outbox = SyncOutbox(dao, api, secureStorage, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())

    @After
    fun cerrar() = db.close()

    /** El servidor contesta cada envío con [responde], que recibe lo que se mandó. */
    private fun servidor(responde: (SyncIntentsRequest) -> List<SyncIntentAck>) {
        coEvery { api.syncIntents(VENUE, any()) } answers {
            val request = secondArg<SyncIntentsRequest>()
            enviados += request
            Response.success(SyncIntentsResponse(data = responde(request)))
        }
    }

    /**
     * Como el «Fence monotónico» del servidor: ya vio hasta [ultimo] de este aparato; procesa el lote por número, rechaza lo
     * igual o menor con su `latestSeq` (sin guardarlo) y acepta lo mayor, que sube el último.
     */
    private fun servidorQueYaVio(ultimo: Long, conPiso: Boolean = true) {
        var visto = ultimo
        servidor { request ->
            request.intents.sortedBy { it.seq }.map { intent ->
                val seq = intent.seq ?: 0L
                if (seq <= visto) stale(intent.id, if (conPiso) visto else null)
                else SyncIntentAck(intent.id, "ACKED").also { visto = seq }
            }.sortedBy { ack -> request.intents.indexOfFirst { it.id == ack.id } }
        }
    }

    private fun stale(id: String, latestSeq: Long?) = SyncIntentAck(
        id = id,
        status = "REJECTED",
        errorCode = "STALE_DEVICE_SEQUENCE",
        message = "La secuencia del dispositivo ya fue utilizada. Requiere revisión antes de repetir la operación.",
        details = latestSeq?.let { JsonObject().apply { addProperty("latestSeq", it) } },
    )

    private suspend fun encola(type: String = SyncIntentTypes.PAY_CASH): String = outbox.enqueue(VENUE, type, JsonObject())
    private suspend fun fila(id: String): SyncIntentEntity = dao.allForVenue(VENUE).first { it.id == id }

    @Test
    fun `tras reinstalar, el efectivo entra a la primera con un numero arriba del que ya vio el servidor`() = runTest {
        servidorQueYaVio(40)
        val cobro = encola()

        val acks = mutableListOf<SyncIntentAck>()
        outbox.replayNow(VENUE) { _, ack -> acks += ack }

        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(fila(cobro).seq).isEqualTo(41L)
        assertThat(enviados.map { r -> r.intents.map { it.id to it.seq } })
            .containsExactly(listOf(cobro to 1L), listOf(cobro to 41L)).inOrder()
        // La pantalla sólo se entera del resultado final, nunca del rechazo de número.
        assertThat(acks.map { it.status }).containsExactly("ACKED")
        assertThat(outbox.rejectedIntents(VENUE)).isEmpty()
    }

    @Test
    fun `la cola en espera se renumera en el mismo orden y sin huecos ni choques`() = runTest {
        servidorQueYaVio(100)
        val abrir = encola(SyncIntentTypes.OPEN_TABLE)
        val ronda = encola(SyncIntentTypes.ADD_ITEMS)
        val cobro = encola()

        outbox.replayNow(VENUE)

        assertThat(listOf(abrir, ronda, cobro).map { fila(it).seq }).containsExactly(101L, 102L, 103L).inOrder()
        assertThat(listOf(abrir, ronda, cobro).map { fila(it).status }.distinct()).containsExactly(SyncIntentEntity.STATUS_ACKED)
        assertThat(enviados.map { r -> r.intents.map { it.seq } })
            .containsExactly(listOf(1L, 2L, 3L), listOf(101L, 102L, 103L)).inOrder()
    }

    @Test
    fun `si algo de esta base entro despues, lo rechazado por numero no se reenvia (Codex r1 P2)`() = runTest {
        // La app anterior guardó un rechazo en seq=1. Sin red se encolaron A (2 comensales) y B (4 comensales). El
        // servidor ya vio hasta 2: rechaza A por número y aplica B. Reenviar A dejaría la cuenta con 2 comensales.
        val viejo = encola(SyncIntentTypes.UPDATE_DETAILS)
        outbox.applyAck(viejo, SyncIntentEntity.STATUS_REJECTED, "STALE_DEVICE_SEQUENCE")
        servidorQueYaVio(2)
        val a = encola(SyncIntentTypes.UPDATE_DETAILS)
        val b = encola(SyncIntentTypes.UPDATE_DETAILS)

        outbox.replayNow(VENUE)

        assertThat(enviados).hasSize(1)
        assertThat(fila(a).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        assertThat(fila(a).seq).isEqualTo(2L)
        assertThat(fila(b).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
    }

    @Test
    fun `una reserva que el servidor ya aparto conserva su numero para recuperarse (Codex r1 P1)`() = runTest {
        // A (2) y B (3, efectivo) se mandaron con la app anterior y se perdió la respuesta: el servidor rechazó A por
        // número y dejó B apartado con seq=3. Sólo lo retoma con ESE número.
        val a = encola(SyncIntentTypes.ADD_ITEMS)
        val b = encola()
        // a=1, b=2 en esta base; el servidor ya vio hasta 2 (la reserva de b).
        servidor { request ->
            request.intents.map { intent ->
                when (intent.id) {
                    a -> stale(a, 2L)
                    else -> if (intent.seq == 2L) SyncIntentAck(b, "ACKED") else SyncIntentAck(b, "REJECTED", "ACTOR_MISMATCH")
                }
            }
        }

        outbox.replayNow(VENUE)

        assertThat(enviados).hasSize(1)
        assertThat(fila(b).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(fila(b).seq).isEqualTo(2L)
        assertThat(fila(a).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `si el numero viejo del servidor cae dentro de esta base queda como antes`() = runTest {
        // Residuo declarado: la instalación vieja apenas había mandado 2; esta ya lleva 3 en espera. Las de 1 y 2 se
        // rechazan como hoy (la 3 sí entra); sin saber qué llegó, reenviarlas podría ejecutarlas tarde.
        servidorQueYaVio(2)
        val ids = (1..3).map { encola(SyncIntentTypes.ADD_ITEMS) }

        outbox.replayNow(VENUE)

        assertThat(enviados).hasSize(1)
        assertThat(ids.map { fila(it).status }).containsExactly(
            SyncIntentEntity.STATUS_REJECTED, SyncIntentEntity.STATUS_REJECTED, SyncIntentEntity.STATUS_ACKED,
        ).inOrder()
    }

    @Test
    fun `si algo entra despues de un rechazo por numero en la misma respuesta no se renumera`() = runTest {
        // Sólo con otra instalación del mismo serial a la vez: el primero se rechaza por número (50) y el segundo entra.
        val primero = encola(SyncIntentTypes.ADD_ITEMS)
        val segundo = encola(SyncIntentTypes.ADD_ITEMS)
        servidor { request -> request.intents.map { if (it.id == primero) stale(primero, 50L) else SyncIntentAck(it.id, "ACKED") } }

        val acks = mutableListOf<String>()
        outbox.replayNow(VENUE) { intent, ack -> acks += "${intent.id}:${ack.status}" }

        assertThat(enviados).hasSize(1)
        assertThat(fila(primero).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        assertThat(fila(primero).seq).isEqualTo(1L)
        assertThat(fila(segundo).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(acks).containsExactly("$segundo:ACKED", "$primero:REJECTED")
    }

    @Test
    fun `un segundo rechazo de numero en la misma pasada queda rechazado como antes`() = runTest {
        // Otra instalación con el mismo serial sigue adelantándose (en el laboratorio, dos TPV de prueba en la misma PAX).
        servidor { request -> request.intents.map { stale(it.id, (it.seq ?: 0L) + 500) } }
        val cobro = encola()

        val acks = mutableListOf<SyncIntentAck>()
        outbox.replayNow(VENUE) { _, ack -> acks += ack }

        assertThat(enviados).hasSize(2)
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        assertThat(fila(cobro).errorCode).isEqualTo("STALE_DEVICE_SEQUENCE")
        assertThat(acks.map { it.status }).containsExactly("REJECTED")
    }

    @Test
    fun `un rechazo de numero sin latestSeq queda rechazado como antes`() = runTest {
        servidorQueYaVio(40, conPiso = false)
        val cobro = encola()

        outbox.replayNow(VENUE)

        assertThat(enviados).hasSize(1)
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
        assertThat(fila(cobro).seq).isEqualTo(1L)
    }

    @Test
    fun `un latestSeq menor que el numero enviado no se cree`() = runTest {
        servidor { request -> request.intents.map { stale(it.id, 0L) } }
        val cobro = encola()

        outbox.replayNow(VENUE)

        assertThat(enviados).hasSize(1)
        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_REJECTED)
    }

    @Test
    fun `renumerar salta por encima de las filas ya resueltas aunque el piso sea menor`() = runTest {
        // Filas 1..5 ya resueltas (descartadas o descartada tras un rechazo) y la 6 en espera; el piso es 3: la que espera
        // va en 7 (arriba de TODO lo que hay en la base), sin chocar con el índice único.
        val ids = (1..6).map { encola() }
        ids.take(4).forEach { outbox.discardPending(it) }
        outbox.applyAck(ids[4], SyncIntentEntity.STATUS_REJECTED, "INVALID_PAYLOAD")
        outbox.dismissRejected(VENUE, ids[4])

        val renumeradas = dao.renumerarPendientesSobre(VENUE, piso = 3L)

        assertThat(renumeradas).isEqualTo(1)
        assertThat(fila(ids[5]).seq).isEqualTo(7L)
        assertThat(fila(ids[4]).seq).isEqualTo(5L) // lo resuelto no se toca
    }

    @Test
    fun `una venta nueva despues de renumerar queda arriba del piso`() = runTest {
        servidorQueYaVio(40)
        encola()
        outbox.replayNow(VENUE)

        val siguiente = encola()

        assertThat(fila(siguiente).seq).isEqualTo(42L)
    }

    @Test
    fun `en una pasada de recuperacion el rechazo de numero tambien se resuelve solo`() = runTest {
        every { secureStorage.getVenueId() } returns VENUE
        every { secureStorage.getStaffId() } returns "staff-1"
        every { secureStorage.getToken() } returns "token-1"
        servidorQueYaVio(40)
        val cobro = encola()

        val progreso = outbox.replayCurrentVenueBatch()

        assertThat(fila(cobro).status).isEqualTo(SyncIntentEntity.STATUS_ACKED)
        assertThat(progreso.resolved).isEqualTo(1)
        assertThat(progreso.moreAfterLimit).isFalse()
    }

    private companion object {
        const val VENUE = "venue-1"
    }
}
