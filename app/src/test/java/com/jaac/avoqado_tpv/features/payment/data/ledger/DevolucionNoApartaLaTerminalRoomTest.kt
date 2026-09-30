package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 Founder, 29-sep-2026: «nada puede detener las ventas». Una DEVOLUCIÓN —en cualquier estado y de cualquier
 * procesador— nunca aparta el aparato ni cuenta como «cobro sin resolver». Lo único que detiene una venta es un COBRO
 * que este proceso tiene corriendo: el lector es uno.
 *
 * Medido en la N86 QA el 29-sep (2.11.3): una devolución que falló sin red dejó su fila en PREPARANDO con el token del
 * proceso vivo y la siguiente venta murió en la barrera («Hay otro cobro en curso en esta terminal ($1.00, hace 3 min).
 * NO se inició este cobro…») hasta reiniciar la app. Con un rechazo de AngelPay la fila quedaba en AUTORIZANDO, igual.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DevolucionNoApartaLaTerminalRoomTest {

    private lateinit var db: AvoqadoDatabase
    private val settings = mockk<TpvSettingsRepository>(relaxed = true)

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun cerrar() = db.close()

    private fun proceso() = PaymentAttemptLedger(db.paymentAttemptDao(), settings)

    /** El mismo contexto que escribe RecordAngelPayRefundUseCase. */
    private fun contextoDeDevolucion(pago: String) =
        """{"originalPaymentId":"$pago","reference":"260929180039","processor":"angelpay"}"""

    private suspend fun abrirDevolucion(libreta: PaymentAttemptLedger, id: String, procesador: String, pago: String = "pago-1") =
        libreta.openAttempt(
            id, "venue", procesador, 100, 0, PaymentAttemptEntity.ROUTE_REFUND, contextoDeDevolucion(pago),
            kind = PaymentAttemptEntity.KIND_REFUND,
        )

    private fun forzarEstado(id: String, estado: String, motivo: String?) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE payment_attempts SET state = ?, last_error = ? WHERE attempt_id = ?", arrayOf(estado, motivo, id))
    }

    @Test fun `P1 una devolucion en CUALQUIER estado y de CUALQUIER procesador no detiene la venta siguiente`() = runTest {
        val estados = listOf(
            "PREPARANDO" to null,
            "KERNEL_ACTIVO" to null,
            "AUTORIZANDO" to null,
            "HOST_RESPONDIO" to null,
            "AUTORIZADO" to null,
            "REGISTRO_FALLIDO" to null,
            "ENTREGADA_A_COLA" to null,
            "INDETERMINADO" to "devolucion_sin_veredicto",
            "INDETERMINADO" to PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD,
        )
        // "angelpay" en minúsculas es como escribe sus filas RecordAngelPayRefundUseCase; "ANGELPAY" y "BLUMON" por si
        // algún día se normaliza o es la devolución de la PAX.
        for (procesador in listOf("angelpay", "ANGELPAY", "BLUMON")) for ((estado, motivo) in estados) {
            db.clearAllTables()
            val libreta = proceso()
            val caso = "[$procesador/$estado/${motivo ?: "-"}]"
            assertTrue("$caso la devolución abre su fila", abrirDevolucion(libreta, "dev", procesador))
            forzarEstado("dev", estado, motivo)

            assertNull("$caso una devolución nunca aparta el aparato", libreta.retencionDelAparato())
            assertNull("$caso una devolución no es un cobro sin resolver", libreta.cobroSinResolver())
            assertTrue("$caso la venta siguiente se admite",
                libreta.openAttempt("venta", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
            assertTrue("$caso y entra a autorizar", libreta.markAuthorizing("venta"))
            assertEquals("$caso la devolución sigue guardada tal cual",
                estado, db.paymentAttemptDao().getById("dev")?.state)
        }
    }

    @Test fun `P1 la devolucion que fallo sin red no detiene la venta - el caso medido en la N86`() = runTest {
        val libreta = proceso()
        assertTrue(abrirDevolucion(libreta, "dev", "angelpay"))
        // El historial de AngelPay no contestó (sin red) y la devolución regresó con error: su fila quedó PREPARANDO.
        assertEquals("PREPARANDO", db.paymentAttemptDao().getById("dev")?.state)

        assertTrue("la venta siguiente se admite sin reiniciar la app",
            libreta.openAttempt("venta", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("venta"))
        assertEquals("el lector lo ocupa la venta, no la devolución", "venta", libreta.retencionDelAparato()?.attemptId)
    }

    @Test fun `P1 la devolucion que AngelPay rechazo no detiene la venta`() = runTest {
        val libreta = proceso()
        assertTrue(abrirDevolucion(libreta, "dev", "angelpay"))
        assertTrue(libreta.markAuthorizing("dev"))
        assertTrue("la venta siguiente se admite",
            libreta.openAttempt("venta", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("venta"))
    }

    @Test fun `P1 una VENTA corriendo si detiene una devolucion - el lector es uno`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("venta", "venue", "BLUMON", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markKernelEntered("venta"))
        assertFalse("mientras el lector cobra no entra una devolución", abrirDevolucion(libreta, "dev", "BLUMON"))
        assertEquals("venta", libreta.retencionDelAparato()?.attemptId)
    }

    @Test fun `P1 una VENTA corriendo sigue deteniendo otra venta y sigue siendo un cobro sin resolver`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("a", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("a"))
        assertFalse("el lector es uno", libreta.openAttempt("b", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertEquals("a", libreta.cobroSinResolver()?.attemptId)
    }

    @Test fun `P1 la cuarentena por reloj se levanta cuando el SDK regresa sin veredicto`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("colgado", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("colgado"))
        assertEquals(1, db.paymentAttemptDao().quarantineStaleAuthorizing("venue", Long.MAX_VALUE, System.currentTimeMillis()))
        assertFalse("mientras nadie sabe si la llamada sigue viva, aparta",
            libreta.openAttempt("otro", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))

        // El SDK regresó sin veredicto 10+ minutos después: la llamada nativa YA terminó.
        libreta.markIndeterminate("colgado", "AngelPay U101")

        val fila = db.paymentAttemptDao().getById("colgado")
        assertEquals("sigue en duda", "INDETERMINADO", fila?.state)
        assertEquals("con el motivo real, ya sin la marca del reloj", "AngelPay U101", fila?.lastError)
        assertTrue("la llamada regresó: la terminal vuelve a cobrar sin reiniciar la app",
            libreta.openAttempt("otro", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
    }

    @Test fun `P1 una devolucion en duda bloquea otra devolucion del MISMO pago - no la de otro pago ni una venta`() = runTest {
        val libreta = proceso()
        assertTrue(abrirDevolucion(libreta, "dev-a", "angelpay", pago = "pago-1"))
        assertTrue(libreta.markAuthorizing("dev-a"))
        libreta.markIndeterminate("dev-a", "devolucion_sin_veredicto:excepcion")

        assertEquals("dev-a", libreta.devolucionSinResolver("pago-1")?.attemptId)
        assertNull("otro pago no", libreta.devolucionSinResolver("pago-2"))
        assertNull("ni uno cuyo id sólo empieza igual", libreta.devolucionSinResolver("pago-"))
        assertTrue("la devolución de otro pago entra", abrirDevolucion(libreta, "dev-b", "angelpay", pago = "pago-2"))
        assertTrue("y la venta siguiente también", libreta.openAttempt("venta", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
    }

    @Test fun `P1 una devolucion aprobada sin registrar tambien bloquea repetirla - ya salio el dinero`() = runTest {
        val libreta = proceso()
        assertTrue(abrirDevolucion(libreta, "dev", "angelpay"))
        assertTrue(libreta.markAuthorizing("dev"))
        assertTrue(libreta.markHostResponded("dev", true, null, "ref-dev", null))
        assertEquals("HOST_RESPONDIO", libreta.devolucionSinResolver("pago-1")?.state)
    }

    @Test fun `una devolucion cerrada - rechazada o que no llego a AngelPay - no bloquea nada`() = runTest {
        val libreta = proceso()
        assertTrue(abrirDevolucion(libreta, "rechazada", "angelpay"))
        assertTrue(libreta.markAuthorizing("rechazada"))
        assertTrue(libreta.markHostResponded("rechazada", false, null, null, null))
        assertTrue(abrirDevolucion(libreta, "antes-del-sdk", "angelpay"))
        assertTrue(libreta.markDiscardedBeforeCharge("antes-del-sdk", "antes_del_sdk:historial"))

        assertNull(libreta.devolucionSinResolver("pago-1"))
    }

    @Test fun `P1 las recuperaciones con forma de VENTA nunca toman una devolucion - aunque su procesador venga en mayusculas`() = runTest {
        val libreta = proceso()
        val dao = db.paymentAttemptDao()
        // Devoluciones de ayer, aprobada sin registrar y en duda, con el procesador que SÍ casa con esas consultas.
        assertTrue(abrirDevolucion(libreta, "dev-aprobada", "ANGELPAY", pago = "pago-1"))
        assertTrue(libreta.markAuthorizing("dev-aprobada"))
        assertTrue(libreta.markHostResponded("dev-aprobada", true, null, "ref-dev", null))
        assertTrue(abrirDevolucion(libreta, "dev-duda", "ANGELPAY", pago = "pago-2"))
        assertTrue(libreta.markAuthorizing("dev-duda"))
        libreta.markIndeterminate("dev-duda", "devolucion_sin_veredicto:G506/ERROR")
        // Control: una venta en duda y una aprobada sin registrar, del mismo procesador (la aprobada al final: una VENTA
        // respondida de este proceso sí aparta el aparato hasta registrarse).
        assertTrue(libreta.openAttempt("venta-duda", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("venta-duda"))
        libreta.markIndeterminate("venta-duda", "AngelPay G505")
        assertTrue(libreta.openAttempt("venta-aprobada", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("venta-aprobada"))
        assertTrue(libreta.markHostResponded("venta-aprobada", true, null, "ref-venta", null))

        val manana = System.currentTimeMillis() + 86_400_000L
        assertEquals(listOf("venta-aprobada"), dao.getApprovalRecoveryCandidates("venue", manana, manana).map { it.attemptId })
        assertEquals(listOf("venta-duda"), dao.getUnknownRecoveryCandidates("venue", manana, manana).map { it.attemptId })
    }

    @Test fun `P1 una duda que no es de reloj conserva su motivo`() = runTest {
        val libreta = proceso()
        assertTrue(libreta.openAttempt("duda", "venue", "ANGELPAY", 100, 0, "FAST", "{}"))
        assertTrue(libreta.markAuthorizing("duda"))
        libreta.markIndeterminate("duda", "AngelPay G505")
        libreta.markIndeterminate("duda", "otra llamada tardía")
        assertEquals("el primer motivo no se pisa", "AngelPay G505",
            db.paymentAttemptDao().getById("duda")?.lastError)
    }
}
