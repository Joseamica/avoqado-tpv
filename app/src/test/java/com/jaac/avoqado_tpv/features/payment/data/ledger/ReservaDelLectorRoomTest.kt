package com.jaac.avoqado_tpv.features.payment.data.ledger

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 🔴 F1 bloque 6 (I6, `plan-cierre-familias.md` §2): el lector de la PAX es UNO. Se ocupa mientras alguien puede usarlo; antes del primer
 * envío posible al banco (`PREPARANDO`, `KERNEL_ACTIVO`), cancelar, Atrás, una excepción o la muerte lo sueltan; después (`AUTORIZANDO`) la
 * duda es sólo de la cuenta A. Una salida tardía de A nunca suelta lo que ya tomó B. Sobre los DAO y la libreta REALES; la cuenta B es
 * otra cuenta del MISMO importe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ReservaDelLectorRoomTest {
    private val venue = "venue-1"
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: PaymentAttemptDao

    @Before fun abrir() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.paymentAttemptDao()
    }

    @After fun cerrar() = db.close()

    private fun libreta() = PaymentAttemptLedger(dao, mockk(relaxed = true))
    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)

    /** Reserva la terminal para la cuenta [orden] (mismo importe en todas). */
    private suspend fun reservar(l: PaymentAttemptLedger, id: String, orden: String): Boolean =
        l.openAttempt(id, venue, "BLUMON", 10_000, 0, "ORDER", """{"venueId":"$venue","orderId":"$orden"}""")

    private suspend fun estado(id: String) = dao.getById(id)!!.state

    @Test fun `CTLS_request_online_cuarentena_excepcion_de_tags_sin_sale_libera_B`() = runBlocking<Unit> {
        val l = libreta()
        assertThat(reservar(l, "a", "orden-A")).isTrue()
        assertThat(l.markKernelEntered("a")).isTrue()
        assertWithMessage("con el lector de A dentro, B espera").that(reservar(l, "b0", "orden-B")).isFalse()
        // El lector tarda más que el umbral del barrido (RESULT_REQ_ONLINE, la lectura de los datos sigue): el barrido corre.
        sql("UPDATE payment_attempts SET updated_at = 1, created_at = 1 WHERE attempt_id = 'a'")
        LedgerSweepLogic.sweepOnce(dao, venue, mockk(relaxed = true), System.currentTimeMillis(), procesoVivo = l.tokenDelProceso())
        assertWithMessage("el lector vivo de ESTE proceso no se pone en duda por reloj").that(estado("a")).isEqualTo("KERNEL_ACTIVO")
        // El kernel regresó y la extracción de los tags lanzó ANTES de SaleCtls: nada salió al banco. La salida del catch suelta.
        assertWithMessage("la salida del catch suelta el lector").that(l.markKernelRefused("a", "excepcion_antes_del_banco")).isTrue()
        assertThat(estado("a")).isEqualTo("DESCARTADA")
        assertWithMessage("B entra").that(reservar(l, "b", "orden-B")).isTrue()
        // Y A no queda cercada: no hubo envío.
        assertThat(l.retencionDeLaVenta("orden-A")).isNull()
        // Control: el KERNEL_ACTIVO de OTRO proceso (murió con el lector dentro) sí se pone en duda por reloj, como siempre.
        val otro = libreta()
        assertThat(reservar(otro, "viejo", "orden-V")).isTrue()
        otro.markKernelEntered("viejo")
        sql("UPDATE payment_attempts SET updated_at = 1 WHERE attempt_id = 'viejo'")
        LedgerSweepLogic.sweepOnce(dao, venue, mockk(relaxed = true), System.currentTimeMillis(), procesoVivo = l.tokenDelProceso())
        assertThat(estado("viejo")).isEqualTo("INDETERMINADO")
    }

    @Test fun `muerte_despues_de_armar_antes_de_sale_solo_cerca_A`() = runBlocking<Unit> {
        val antes = libreta()
        assertThat(reservar(antes, "a", "orden-A")).isTrue()
        assertThat(antes.markKernelEntered("a")).isTrue()
        assertThat(antes.markAuthorizing("a")).isTrue()   // «puede haber salido»: armado el envío
        // La app muere entre armar y la llamada nativa. Proceso nuevo.
        val despues = libreta()
        despues.cuarentenaDeHuerfanos()
        assertThat(estado("a")).isEqualTo("INDETERMINADO")
        assertWithMessage("B (otra cuenta, mismo importe) entra").that(reservar(despues, "b", "orden-B")).isTrue()
        despues.markDiscardedBeforeCharge("b", "fin")
        assertWithMessage("otra tarjeta de A espera al gerente").that(reservar(despues, "a2", "orden-A")).isFalse()
        assertWithMessage("el efectivo de A sigue permitido").that(despues.hayDineroEnJuegoDeLaVenta("orden-A")).isFalse()
    }

    @Test fun `callback_viejo_no_suelta_el_lector_de_B`() = runBlocking<Unit> {
        val l = libreta()
        assertThat(reservar(l, "a", "orden-A")).isTrue()
        l.markKernelEntered("a")
        assertThat(l.markKernelRefused("a", "excepcion_antes_del_banco")).isTrue()
        assertThat(reservar(l, "b", "orden-B")).isTrue()
        assertThat(l.markKernelEntered("b")).isTrue()
        // Llegan tarde las salidas de A (cancelar, Atrás, el catch, un resultado viejo): nada de eso toca a B.
        l.markKernelRefused("a", "excepcion_antes_del_banco")
        l.markDiscardedBeforeCharge("a", "user_cancel")
        l.markIndeterminate("a", "resultado viejo")
        l.markHostResponded("a", false, null, null, null)
        assertThat(estado("b")).isEqualTo("KERNEL_ACTIVO")
        assertWithMessage("el lector sigue siendo de B").that(l.retencionDelAparato()?.attemptId).isEqualTo("b")
        assertWithMessage("C espera a B").that(reservar(l, "c", "orden-C")).isFalse()
    }

    @Test fun `freno_entre_reserva_y_lector_cancelar_o_atras_libera_B`() = runBlocking<Unit> {
        val l = libreta()
        // Cancelar con la reserva hecha y el lector sin encender.
        assertThat(reservar(l, "a", "orden-A")).isTrue()
        assertThat(l.markDiscardedBeforeCharge("a", "user_cancel")).isTrue()
        assertWithMessage("tras Cancelar, B entra").that(reservar(l, "b", "orden-B")).isTrue()
        // Atrás desde la detección de la tarjeta.
        assertThat(l.markDiscardedBeforeCharge("b", "back_from_card_detection")).isTrue()
        assertWithMessage("tras Atrás, C entra").that(reservar(l, "c", "orden-C")).isTrue()
        // La barrera de la cuenta rechaza ENTRE la reserva y el lector (ya dentro del kernel): se suelta igual.
        l.markKernelEntered("c")
        assertThat(l.markKernelRefused("c", "barrera_antes_del_banco")).isTrue()
        assertWithMessage("tras el freno, D entra").that(reservar(l, "d", "orden-D")).isTrue()
    }

    /** Lo que pidió el coordinador: soltar el LECTOR para B no es acreditar el cese de la FIRMA (bloque 2). */
    @Test fun `excepcion_suelta_el_lector_para_B_pero_no_acredita_el_cese_de_la_firma`() = runBlocking<Unit> {
        val l = libreta()
        // Antes del envío: se suelta, y la marca del regreso del SDK NO se escribe (un catch nunca la acredita).
        assertThat(reservar(l, "pre", "orden-P")).isTrue()
        l.markKernelEntered("pre")
        assertThat(l.markKernelRefused("pre", "excepcion_antes_del_banco")).isTrue()
        assertThat(dao.getById("pre")!!.sdkReturnedAt).isNull()
        // Después de armar: la excepción deja la duda (el catch de la autorización); el lector queda para otra cuenta, la firma no.
        assertThat(reservar(l, "armada", "orden-A")).isTrue()
        l.markKernelEntered("armada")
        assertThat(l.markAuthorizing("armada")).isTrue()
        l.markIndeterminate("armada", "IOException")
        sql("UPDATE payment_attempts SET server_answered_at = 1 WHERE attempt_id = 'armada'")
        assertWithMessage("B entra").that(reservar(l, "b", "orden-B")).isTrue()
        l.markDiscardedBeforeCharge("b", "fin")
        val fila = dao.getById("armada")!!
        assertThat(fila.sdkReturnedAt).isNull()
        assertWithMessage("sin el regreso del SDK no se firma").that(l.puedeDeclararseEnDuda(fila, venue)).isFalse()
        val enLaLista = l.observarCobrosDeclarables(venue).first().single { it.attemptId == "armada" }
        assertThat(enLaLista.declarable).isFalse()
        assertThat(enLaLista.ejecucionViva).isTrue()
        assertWithMessage("y otra tarjeta de A espera").that(reservar(l, "a2", "orden-A")).isFalse()
    }
}
