package com.jaac.avoqado_tpv.features.checkout.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.payment.domain.referenciaDeEfectivo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Cobrar» sin red, C2a: la venta local se guarda como UNA fila de `pending_payments` con su pedido adentro, en una sola escritura.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VentaLocalRepositoryTest {

    private lateinit var db: AvoqadoDatabase
    private lateinit var repo: VentaLocalRepository
    private val llave = "ckx0000000000000000000001"

    private val venta = VentaLocal(
        llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = "shift-1", createdAtLocal = 1_760_000_000_000L,
        totalCents = 5050,
        pedidoJson = """{"externalId":"$llave","total":5050,"items":[]}""",
        renglones = listOf(
            RenglonDeTicket("$llave:0", "Café", 1, 5050, 5050, listOf(ExtraDeTicket("m-1", "Leche", 50, "MULTIPLE_CHOICE")), "sin hielo"),
        ),
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = VentaLocalRepository(db.pendingPaymentDao(), shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
    }

    @After fun tearDown() { db.close() }

    @Test fun `la fila queda con el pedido, el ticket, la llave y la referencia de efectivo, en PENDING`() = runTest {
        val guardada = repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, blumonSerialNumber = "serie-1", deviceSerialNumber = "AVQD-1", relojMonotonoAlVender = 777L, arranqueAlVender = 3)
            .getOrThrow()

        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(guardada.id).isEqualTo(fila.id)
        assertThat(guardada.claimToken).isNull()
        assertThat(fila.orderRequestJson).isEqualTo(venta.pedidoJson)
        val renglones = Gson().fromJson<List<RenglonDeTicket>>(fila.ticketJson, object : TypeToken<List<RenglonDeTicket>>() {}.type)
        assertThat(renglones).isEqualTo(venta.renglones)
        assertThat(fila.idempotencyKey).isEqualTo(llave)
        assertThat(fila.referenceNumber).isEqualTo("CASH-" + llave.takeLast(16))
        assertThat(fila.orderId).isNull()
        assertThat(fila.shiftId).isEqualTo("shift-1")
        assertThat(fila.exitoMostrado).isFalse()
        assertThat(fila.syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_PENDING)
        assertThat(fila.claimToken).isNull()
        assertThat(fila.authorizationNumber).isEqualTo("EFECTIVO")
        assertThat(fila.amount).isEqualTo("50.50")
        assertThat(fila.tip).isEqualTo("0.00")
        assertThat(fila.merchantAccountId).isEqualTo("")
        assertThat(fila.cardBrand).isNull()
        assertThat(fila.entryMode).isEqualTo("MANUAL")
        assertThat(fila.staffId).isEqualTo("staff-1")
        assertThat(fila.createdAt).isEqualTo(venta.createdAtLocal)
        assertThat(fila.relojMonotonoAlVender).isEqualTo(777L)
        assertThat(fila.arranqueAlVender).isEqualTo(3)
        assertThat(fila.deviceSerialNumber).isEqualTo("AVQD-1")
        assertThat(fila.blumonSerialNumber).isEqualTo("serie-1")
    }

    @Test fun `insertada ya reclamada queda SYNCING con su claim`() = runTest {
        val guardada = repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1", reclamada = true).getOrThrow()

        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(fila.syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_SYNCING)
        assertThat(fila.claimToken).isNotEmpty()
        assertThat(fila.claimedAt).isNotNull()
        assertThat(guardada.claimToken).isEqualTo(fila.claimToken)
    }

    @Test fun `la consulta por llave la encuentra y exito mostrado se marca por llave`() = runTest {
        val dao = db.pendingPaymentDao()
        assertThat(dao.existeFilaConLlave(llave)).isFalse()

        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1").getOrThrow()

        assertThat(dao.existeFilaConLlave(llave)).isTrue()
        assertThat(dao.existeFilaConLlave("otra-llave")).isFalse()
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.exitoMostrado).isFalse()
        assertThat(dao.marcarExitoMostrado(llave)).isEqualTo(1)
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.exitoMostrado).isTrue()
        assertThat(dao.marcarExitoMostrado("otra-llave")).isEqualTo(0)
    }

    @Test fun `guardar la misma llave dos veces no duplica y avisa que ya estaba`() = runTest {
        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1").getOrThrow()

        val segunda = repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1", reclamada = true)

        assertThat(segunda.exceptionOrNull()).isInstanceOf(VentaLocalYaGuardadaException::class.java)
        assertThat(db.pendingPaymentDao().getPendingCount()).isEqualTo(1)
    }

    @Test fun `una venta en 0 se guarda con el formato de la columna`() = runTest {
        repo.guardar(venta.copy(totalCents = 0), PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1").getOrThrow()

        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(fila.amount).isEqualTo("0.00")
        assertThat(fila.tip).isEqualTo("0.00")
    }

    @Test fun `una propina se guarda en pesos con dos decimales`() = runTest {
        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1", propinaCents = 575).getOrThrow()

        val fila = db.pendingPaymentDao().findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(fila.tip).isEqualTo("5.75")
        assertThat(fila.amount).isEqualTo("50.50")
    }

    @Test fun `el procesador queda escrito tal cual, BLUMON y ANGELPAY`() = runTest {
        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1").getOrThrow()
        repo.guardar(
            venta.copy(llave = "ckx0000000000000000000002"), PendingPaymentEntity.PROCESSOR_ANGELPAY, "", "AVQD-1",
        ).getOrThrow()

        val dao = db.pendingPaymentDao()
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.paymentProcessor).isEqualTo("BLUMON")
        val angel = dao.findByReference(referenciaDeEfectivo("ckx0000000000000000000002"), "venue-1")!!
        assertThat(angel.paymentProcessor).isEqualTo("ANGELPAY")
        assertThat(angel.blumonSerialNumber).isEqualTo("")
    }

    @Test fun `un procesador desconocido no se guarda`() = runTest {
        val r = repo.guardar(venta, "OTRO", "serie-1", "AVQD-1")

        assertThat(r.isFailure).isTrue()
        assertThat(db.pendingPaymentDao().existeFilaConLlave(llave)).isFalse()
    }

    @Test fun `el worker reclama venta local y legacy una vez con el mismo contrato CAS`() = runTest {
        val dao = db.pendingPaymentDao()
        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1").getOrThrow()
        dao.insert(filaNormal("CASH-normal0000000001"))
        assertThat(dao.getAllPending().map { it.referenceNumber })
            .containsExactly("CASH-normal0000000001", referenciaDeEfectivo(llave))

        val reclamadas = dao.claimBatch(limit = 10, token = "t", now = 1_000L, staleBefore = 0L)

        assertThat(reclamadas.map { it.referenceNumber }).containsExactly("CASH-normal0000000001", referenciaDeEfectivo(llave))
        assertThat(reclamadas.map { it.claimToken }.toSet()).containsExactly("t")
        assertThat(dao.getAllPending().map { it.referenceNumber }).isEmpty()
        assertThat(dao.getPendingCount()).isEqualTo(2)
        val guardada = dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!
        assertThat(guardada.syncStatus).isEqualTo(PendingPaymentEntity.SYNC_STATUS_SYNCING)
        assertThat(guardada.claimToken).isEqualTo("t")
        assertThat(guardada.orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(dao.claimBatch(limit = 10, token = "otro", now = 2_000L, staleBefore = 0L)).isEmpty()
    }

    @Test fun `el worker recupera una venta local SYNCING abandonada conservando su pedido`() = runTest {
        val dao = db.pendingPaymentDao()
        repo.guardar(venta, PendingPaymentEntity.PROCESSOR_BLUMON, "serie-1", "AVQD-1", reclamada = true).getOrThrow()
        val anterior = dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!

        val reclamadas = dao.claimBatch(limit = 10, token = "t", now = Long.MAX_VALUE / 2, staleBefore = Long.MAX_VALUE / 2)

        assertThat(reclamadas).hasSize(1)
        assertThat(reclamadas.single().id).isEqualTo(anterior.id)
        assertThat(reclamadas.single().claimToken).isEqualTo("t")
        assertThat(reclamadas.single().orderRequestJson).isEqualTo(venta.pedidoJson)
        assertThat(dao.markSynced(anterior.id, anterior.claimToken!!)).isEqualTo(0)
        assertThat(dao.findByReference(referenciaDeEfectivo(llave), "venue-1")!!.claimToken).isEqualTo("t")
    }

    @Test fun `corregir un reloj adelantado conserva el claim y recupera el abandonado tras quince minutos completos`() = runTest {
        val dao = db.pendingPaymentDao()
        val ahora = 1_000_000_000L
        val margen = 15 * 60 * 1000L
        val referencia = "CASH-reloj-adelantado"
        val id = dao.insert(filaNormal(referencia).copy(
            syncStatus = "SYNCING", claimToken = "anterior", claimedAt = ahora + 3 * 86_400_000L,
        ))

        assertThat(dao.claimBatch(10, "detector", ahora, ahora - margen)).isEmpty()
        val ajustada = dao.findByReference(referencia, "venue-1")!!
        assertThat(ajustada.claimToken).isEqualTo("anterior")
        assertThat(ajustada.claimedAt).isEqualTo(ahora)
        assertThat(ajustada.syncStatus).isEqualTo("SYNCING")
        assertThat(dao.claimBatch(10, "temprano", ahora + 1, ahora + 1 - margen)).isEmpty()
        assertThat(dao.claimBatch(10, "limite", ahora + margen, ahora)).isEmpty()

        val recuperada = dao.claimBatch(10, "nuevo", ahora + margen + 1, ahora + 1).single()
        assertThat(recuperada.id).isEqualTo(id)
        assertThat(recuperada.claimToken).isEqualTo("nuevo")
        assertThat(dao.markSynced(id, "anterior")).isEqualTo(0)
        assertThat(dao.release(id, "anterior", 1, "tardio")).isEqualTo(0)
        assertThat(dao.markSynced(id, "nuevo")).isEqualTo(1)
    }

    @Test fun `el worker vivo aun puede terminar durante el margen tras corregir la hora`() = runTest {
        val dao = db.pendingPaymentDao()
        val ahora = 1_000_000_000L
        val margen = 15 * 60 * 1000L
        val id = dao.insert(filaNormal("CASH-worker-vivo").copy(
            syncStatus = "SYNCING", claimToken = "vivo", claimedAt = ahora + 3 * 86_400_000L,
        ))

        assertThat(dao.claimBatch(10, "otro", ahora, ahora - margen)).isEmpty()
        assertThat(dao.markSynced(id, "vivo")).isEqualTo(1)
        assertThat(dao.claimBatch(10, "tardio", ahora + margen + 1, ahora + 1)).isEmpty()
    }

    @Test fun `un desfase pequeno no ajusta ni roba un claim nuevo`() = runTest {
        val dao = db.pendingPaymentDao()
        val ahora = 1_000_000_000L
        val margen = 15 * 60 * 1000L
        for (desfase in listOf(1L, margen - 1, margen)) {
            val referencia = "CASH-desfase-$desfase"
            dao.insert(filaNormal(referencia).copy(
                syncStatus = "SYNCING", claimToken = "vivo-$desfase", claimedAt = ahora + desfase,
            ))
            assertThat(dao.claimBatch(10, "otro", ahora, ahora - margen)).isEmpty()
            assertThat(dao.claimBatch(10, "corto", ahora + 1_000, ahora + 1_000 - margen)).isEmpty()
            val intacta = dao.findByReference(referencia, "venue-1")!!
            assertThat(intacta.claimToken).isEqualTo("vivo-$desfase")
            assertThat(intacta.claimedAt).isEqualTo(ahora + desfase)
        }
    }

@Test fun `guardadas cuenta efectivo local y legacy del venue aunque ya este reconocido`() = runTest {
    val dao = db.pendingPaymentDao()
    val filas = listOf(
        filaNormal("CASH-local").copy(orderRequestJson = "{}"),
        filaNormal("cAsH-legacy").copy(authorizationNumber = null),
        filaNormal("legacy-por-autorizacion").copy(authorizationNumber = "eFeCtIvO manual", syncStatus = "SYNCING", acknowledged = true),
        filaNormal("CASH-local-vacio").copy(orderRequestJson = ""),
        filaNormal("CARD-pendiente").copy(authorizationNumber = "123", orderRequestJson = "{}"),
        filaNormal("CASH-otra-sucursal").copy(venueId = "venue-2"),
        filaNormal("CASH-terminada").copy(syncStatus = "SUCCESS"),
        filaNormal("CASH-fallida").copy(syncStatus = "FAILED"),
    )
    for (fila in filas) assertThat(dao.insert(fila)).isGreaterThan(0L)

    assertThat(dao.contarVentasGuardadas("venue-1").first()).isEqualTo(4)
    assertThat(dao.contarVentasGuardadas("venue-2").first()).isEqualTo(1)
    assertThat(dao.contarVentasGuardadas("sin-filas").first()).isEqualTo(0)
}

@Test fun `problemas conserva revisiones ya subidas y excluye solo reconocidas de ese venue`() = runTest {
    val dao = db.pendingPaymentDao()
    val filas = listOf(
        filaNormal("CASH-error").copy(syncStatus = "FAILED"),
        filaNormal("CASH-diferencia-subida").copy(syncStatus = "SUCCESS", requiereRevision = true),
        filaNormal("CASH-diferencia-pendiente").copy(requiereRevision = true),
        filaNormal("CARD-error-y-revision").copy(authorizationNumber = "123", syncStatus = "FAILED", requiereRevision = true),
        filaNormal("CASH-error-visto").copy(syncStatus = "FAILED", acknowledged = true),
        filaNormal("CASH-diferencia-vista").copy(syncStatus = "SUCCESS", requiereRevision = true, acknowledged = true),
        filaNormal("CASH-error-otro-venue").copy(venueId = "venue-2", syncStatus = "FAILED"),
        filaNormal("CASH-pendiente-sana"),
    )
    for (fila in filas) assertThat(dao.insert(fila)).isGreaterThan(0L)

    assertThat(dao.contarConProblema("venue-1").first()).isEqualTo(4)
    assertThat(dao.contarConProblema("venue-2").first()).isEqualTo(1)
    assertThat(dao.contarConProblema("sin-filas").first()).isEqualTo(0)
}

    private fun filaNormal(referencia: String) = PendingPaymentEntity(
        referenceNumber = referencia, venueId = "venue-1", staffId = "staff-1", amount = "10.00", tip = "0.00", rating = null,
        merchantAccountId = "", blumonSerialNumber = "serie-1", maskedPan = null, cardBrand = null, entryMode = "MANUAL",
        isInternational = false, authorizationNumber = "EFECTIVO", idempotencyKey = "otra-llave", createdAt = 1L,
    )
}
