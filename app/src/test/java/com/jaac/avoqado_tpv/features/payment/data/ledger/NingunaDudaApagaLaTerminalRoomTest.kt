package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 Founder, 25-sep-2026: «ninguna duda apaga la terminal». Lo único que aparta el APARATO es un cobro que ESTE proceso
 * tiene corriendo (el lector es uno). Una duda guardada se avisa; lo que dejó un proceso muerto no aparta nada porque su
 * llamada nativa murió con él. Cada instancia de [PaymentAttemptLedger] es un proceso (en la app es @Singleton).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class NingunaDudaApagaLaTerminalRoomTest {

    private lateinit var db: AvoqadoDatabase
    private val settings = mockk<TpvSettingsRepository>(relaxed = true)

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun cerrar() = db.close()

    private fun proceso() = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

    private val orden7 = """{"orderId":"orden-7"}"""
    private val orden8 = """{"orderId":"orden-8"}"""

    private suspend fun dudaDeLaOrden(libreta: PaymentAttemptLedger, id: String, procesador: String, contexto: String) {
        assertTrue(libreta.openAttempt(id, "venue", procesador, 100, 0, "ORDER", contexto))
        assertTrue(libreta.markAuthorizing(id))
        libreta.markIndeterminate(id, "sin veredicto")
    }

    @Test fun `P1 una duda guardada no aparta la terminal - la siguiente venta se admite`() = runTest {
        for (procesador in listOf("ANGELPAY", "BLUMON")) {
            val libreta = proceso()
            assertTrue(libreta.openAttempt("duda-$procesador", "venue", procesador, 100, 0, "FAST", "{}"))
            assertTrue(libreta.markAuthorizing("duda-$procesador"))
            libreta.markIndeterminate("duda-$procesador", "sin veredicto")
            assertTrue("[$procesador] una duda sin dinero conocido ya no apaga la terminal",
                libreta.openAttempt("siguiente-$procesador", "venue", procesador, 100, 0, "FAST", "{}"))
            assertEquals("la duda sigue guardada para el aviso y la recuperación",
                "INDETERMINADO", db.paymentAttemptDao().getById("duda-$procesador")?.state)
            libreta.markDiscardedBeforeCharge("siguiente-$procesador", "fin de la prueba")
        }
    }

    @Test fun `P1 un cobro corriendo en ESTE proceso sigue apartando el aparato`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("corriendo", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markKernelEntered("corriendo"))
        assertFalse("mientras el lector trabaja no entra otro cobro",
            libreta.openAttempt("otro", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertEquals("corriendo", libreta.retencionDelAparato()?.attemptId)
    }

    @Test fun `P1 lo que dejo un proceso muerto no aparta, ni siquiera aprobado sin registrar`() = runTest {
        val muerto = proceso()
        assertTrue(muerto.openAttempt("aprobado", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertTrue(muerto.markAuthorizing("aprobado"))
        assertTrue(muerto.markHostResponded("aprobado", true, "op-1", "ref-1", "auth-1"))
        muerto.markRecordFailed("aprobado", "sin red")
        assertEquals("REGISTRO_FALLIDO", db.paymentAttemptDao().getById("aprobado")?.state)

        val nuevo = proceso()
        assertTrue("la llamada nativa murió con el proceso: la terminal cobra",
            nuevo.openAttempt("siguiente", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertEquals("el cobro aprobado sigue esperando su registro",
            "REGISTRO_FALLIDO", db.paymentAttemptDao().getById("aprobado")?.state)
    }

    @Test fun `P1 la cuarentena por reloj de ESTE proceso aparta y se suelta al reiniciar`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("colgado", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("colgado"))
        assertEquals(1, db.paymentAttemptDao().quarantineStaleAuthorizing("venue", Long.MAX_VALUE, System.currentTimeMillis()))
        assertFalse("la llamada nativa pudo seguir viva dentro de este proceso",
            libreta.openAttempt("otro", "venue", "BLUMON", 100, 0, "FAST", "{}"))

        val reiniciada = proceso()
        assertTrue("reiniciar la app mata la llamada nativa: la terminal vuelve a cobrar",
            reiniciada.openAttempt("tras-reiniciar", "venue", "BLUMON", 100, 0, "FAST", "{}"))
    }

    @Test fun `P1 dos cobros de este proceso que llegan a la vez - gana exactamente uno`() = runTest {
        val libreta = proceso()
        val arranque = CompletableDeferred<Unit>()
        val uno = async(Dispatchers.IO) { arranque.await(); libreta.openAttempt("uno", "venue-a", "BLUMON", 100, 0, "FAST", "{}") }
        val dos = async(Dispatchers.IO) { arranque.await(); libreta.openAttempt("dos", "venue-b", "ANGELPAY", 100, 0, "FAST", "{}") }
        arranque.complete(Unit)
        assertEquals("el lector es uno: una sola reserva gana", 1, listOf(uno.await(), dos.await()).count { it })
    }

    @Test fun `P1 un aprobado que el servidor dio por cobrado en un Pago rapido tampoco aparta el aparato`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("ya-cobrado", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("ya-cobrado"))
        libreta.markIndeterminate("ya-cobrado", "AngelPay U100")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = 'DESCARTADA', server_processor_evidence = 'APPROVED' WHERE attempt_id = 'ya-cobrado'")
        assertTrue("el dinero conocido se AVISA («SÍ pasó»), no apaga la terminal",
            libreta.openAttempt("siguiente", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
    }

    @Test fun `P1 una duda SIN dinero ya no cerca su venta - PAX y Nexgo por igual`() = runTest {
        for (procesador in listOf("ANGELPAY", "BLUMON")) {
            val libreta = proceso()
            dudaDeLaOrden(libreta, "a-$procesador", procesador, orden7)
            assertTrue("[$procesador] se avisa y decide el cajero",
                libreta.openAttempt("b-$procesador", "venue", procesador, 100, 0, "ORDER", orden7))
            libreta.markDiscardedBeforeCharge("b-$procesador", "fin de la prueba")
        }
    }

    @Test fun `P1 la venta con dinero conocido sigue cercada`() = runTest {
        val libreta = proceso()
        dudaDeLaOrden(libreta, "a", "ANGELPAY", orden7)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET server_processor_evidence = 'APPROVED' WHERE attempt_id = 'a'")
        assertFalse("el banco sí cobró esa cuenta: cobrarla otra vez es el cobro doble",
            libreta.openAttempt("b", "venue", "ANGELPAY", 100, 0, "ORDER", orden7))
        assertEquals("a", libreta.retencionDeLaVenta("orden-7")?.attemptId)
        assertTrue("las demás cuentas cobran",
            libreta.openAttempt("c", "venue", "ANGELPAY", 100, 0, "ORDER", orden8))
    }

    @Test fun `P1 el dinero que llega entre reservar y autorizar cierra el paso de ESA venta`() = runTest {
        val libreta = proceso()
        dudaDeLaOrden(libreta, "a", "ANGELPAY", orden7)
        assertTrue(libreta.openAttempt("b", "venue", "ANGELPAY", 100, 0, "ORDER", orden7))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET server_processor_evidence = 'APPROVED' WHERE attempt_id = 'a'")
        assertFalse("la aprobación tardía de A llegó antes de autorizar B", libreta.markAuthorizing("b"))
    }

    @Test fun `P1 la duda de OTRA venta no impide autorizar esta`() = runTest {
        val libreta = proceso()
        dudaDeLaOrden(libreta, "a", "ANGELPAY", orden7)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET server_processor_evidence = 'APPROVED' WHERE attempt_id = 'a'")
        assertTrue(libreta.openAttempt("b", "venue", "ANGELPAY", 100, 0, "ORDER", orden8))
        assertTrue(libreta.markAuthorizing("b"))
    }

    @Test fun `P1 el CAS a AUTORIZANDO mira el cobro corriendo de la OTRA fila y su token`() = runTest {
        val dao = db.paymentAttemptDao()
        val ahora = System.currentTimeMillis()
        assertTrue(dao.reserveTerminal("a", "venue", "BLUMON", "SALE", 100L, 0L, "FAST", "{}", null, ahora, null, "T") > 0)
        assertEquals(1, dao.casTransition("a", listOf("PREPARANDO"), "KERNEL_ACTIVO", ahora, "T"))
        assertTrue("otro proceso reserva: la ejecución de T no es suya",
            dao.reserveTerminal("b", "venue", "BLUMON", "SALE", 100L, 0L, "FAST", "{}", null, ahora, null, "otro") > 0)
        assertEquals("en el proceso T, A sigue en el lector", 0,
            dao.casTransition("b", listOf("PREPARANDO"), "AUTORIZANDO", ahora, "T"))
        assertEquals("para otro proceso, la ejecución de T murió con él", 1,
            dao.casTransition("b", listOf("PREPARANDO"), "AUTORIZANDO", ahora, "otro"))
    }

    @Test fun `P1 una duda SIN dinero de la MISMA venta no impide autorizar - el CAS lee la OTRA fila`() = runTest {
        val libreta = proceso()
        dudaDeLaOrden(libreta, "a", "ANGELPAY", orden7)
        assertTrue(libreta.openAttempt("b", "venue", "ANGELPAY", 100, 0, "ORDER", orden7))
        assertTrue("la duda de A no es dinero: B entra a autorizar", libreta.markAuthorizing("b"))
    }

    @Test fun `P1 la cuarentena por reloj cerca SU venta mientras vive el proceso, y al reiniciar sólo avisa`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("colgado", "venue", "ANGELPAY", 100, 0, "ORDER", orden7))
        assertTrue(libreta.markAuthorizing("colgado"))
        assertEquals(1, db.paymentAttemptDao().quarantineStaleAuthorizing("venue", Long.MAX_VALUE, System.currentTimeMillis()))
        assertEquals("la llamada nativa pudo seguir viva: la venta sigue cercada",
            "colgado", libreta.retencionDeLaVenta("orden-7")?.attemptId)

        val reiniciada = proceso()
        assertEquals("tras reiniciar, la cuarentena es una duda más", null, reiniciada.retencionDeLaVenta("orden-7"))
        assertTrue("y la misma cuenta se puede cobrar (el aviso la nombra)",
            reiniciada.openAttempt("misma", "venue", "ANGELPAY", 100, 0, "ORDER", orden7))
    }
}
