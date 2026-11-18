package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * hz:nube0000:na286b5f (encargo 64000e): las salidas del recibo de un cobro con tarjeta de Mesas. «Nueva Orden» libera la mesa
 * con la cuenta y suelta «Tu mesa» (como siempre). «Inicio» y «Ver en Pagos» (rediseño tras Codex r2) NO liberan la mesa:
 * sólo sueltan la sesión si el SERVIDOR dice que su cuenta ya no debe.
 */
class SalidaDeLaMesaTrasElCobroTest {
    private val repository = mockk<TablesRepository>()
    private val tableSession = TableSession()

    @Test
    fun libera_la_mesa_con_su_cuenta_y_suelta_la_sesion() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.clearTable("venue-1", "m8", "b", false) } returns Result.success(Unit)

        soltarLaMesaTrasElCobroConTarjeta("venue-1", tableSession, repository, salida = "Inicio (PAX)")

        coVerify(exactly = 1) { repository.clearTable("venue-1", "m8", "b", false) }
        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun si_el_servidor_no_libera_la_mesa_la_sesion_se_suelta_igual_como_hacia_Nueva_Orden() = runTest {
        // La mesa sigue con OTRA cuenta que debe (D85): la mesa queda ocupada por esa cuenta; esta sesión ya se cobró.
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.clearTable("venue-1", "m8", "b", false) } returns
            Result.failure(BackendHttpException(statusCode = 400, message = "Cannot clear table with unpaid order ORD-A"))

        soltarLaMesaTrasElCobroConTarjeta("venue-1", tableSession, repository, salida = "Nueva Orden (PAX)")

        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun con_una_cuenta_provisional_manda_la_cuenta_local() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "local-1", total = BigDecimal("20.00"), isProvisional = true))
        coEvery { repository.clearTable("venue-1", "m8", "local-1", true) } returns Result.success(Unit)

        soltarLaMesaTrasElCobroConTarjeta("venue-1", tableSession, repository, salida = "Inicio (Nexgo)")

        coVerify(exactly = 1) { repository.clearTable("venue-1", "m8", "local-1", true) }
    }

    @Test
    fun sin_sesion_no_llama_al_servidor() = runTest {
        soltarLaMesaTrasElCobroConTarjeta("venue-1", tableSession, repository, salida = "Inicio (PAX)")

        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
    }

    // region — «Inicio» / «Ver en Pagos»: sólo la sesión, y sólo con la palabra del SERVIDOR

    private val pagada = Result.success(OrderDetail(id = "b", status = "COMPLETED", paymentStatus = "PAID"))

    @Test
    fun inicio_suelta_la_sesion_si_el_servidor_dice_pagada_y_NUNCA_libera_la_mesa() = runTest {
        // Codex r2 #1: con el plano atrasado la mesa de la sesión puede ser otra (o estar reservada): no se toca.
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder("venue-1", "b") } returns pagada

        soltarSesionSiLaCuentaYaNoDebe("venue-1", tableSession, repository, tableSession.epoch, salida = "Inicio (PAX)")

        assertThat(tableSession.current()).isNull()
        coVerify(exactly = 0) { repository.clearTable(any(), any(), any(), any()) }
    }

    @Test
    fun inicio_tras_un_abono_que_no_salda_deja_la_sesion() = runTest {
        // Codex r2 #6: el cobro fue «por el total» que vio la terminal, pero otro aparato agregó algo: la cuenta todavía debe.
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder("venue-1", "b") } returns
            Result.success(OrderDetail(id = "b", status = "PENDING", paymentStatus = "PARTIAL"))

        soltarSesionSiLaCuentaYaNoDebe("venue-1", tableSession, repository, tableSession.epoch, salida = "Inicio (PAX)")

        assertThat(tableSession.current()?.orderId).isEqualTo("b")
    }

    @Test
    fun inicio_sin_poder_preguntar_reintenta_y_se_rinde_dejando_la_sesion() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        coEvery { repository.getOrder("venue-1", "b") } returns Result.failure(IOException("sin red"))

        soltarSesionSiLaCuentaYaNoDebe("venue-1", tableSession, repository, tableSession.epoch, salida = "Inicio (Nexgo)")

        assertThat(tableSession.current()?.orderId).isEqualTo("b")
        coVerify(exactly = 3) { repository.getOrder("venue-1", "b") }
    }

    @Test
    fun inicio_pregunta_por_la_cuenta_ya_confirmada_si_la_apertura_sin_red_subio_mientras_tanto() = runTest {
        // Codex r1 #7: la sesión era provisional; la cola confirma la apertura (misma época) y el reintento usa el id real.
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "local-1", total = BigDecimal("20.00"), isProvisional = true))
        val epoca = tableSession.epoch
        coEvery { repository.getOrder("venue-1", "local-1") } coAnswers {
            tableSession.promoteProvisional("local-1", "b", null, 2)
            Result.failure(IOException("no existe todavía"))
        }
        coEvery { repository.getOrder("venue-1", "b") } returns pagada

        soltarSesionSiLaCuentaYaNoDebe("venue-1", tableSession, repository, epoca, salida = "Inicio (PAX)")

        assertThat(tableSession.current()).isNull()
    }

    @Test
    fun inicio_que_llega_tarde_no_toca_la_sesion_de_otra_mesa() = runTest {
        tableSession.start(TableSession.Active(tableId = "m8", orderId = "b", total = BigDecimal("20.00")))
        val epoca = tableSession.epoch
        coEvery { repository.getOrder("venue-1", "b") } coAnswers {
            tableSession.start(TableSession.Active(tableId = "m3", orderId = "otra"))
            pagada
        }

        soltarSesionSiLaCuentaYaNoDebe("venue-1", tableSession, repository, epoca, salida = "Inicio (PAX)")

        assertThat(tableSession.current()?.orderId).isEqualTo("otra")
    }

    // endregion
}
