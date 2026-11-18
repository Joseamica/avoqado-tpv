package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.TableSession
import com.jaac.avoqado_tpv.features.tables.data.TablesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

/**
 * hz:nube0000:na286b5f (encargo 64000e): la salida del recibo de un cobro con tarjeta que saldó la mesa. «Nueva Orden»,
 * «Inicio» y (en la Nexgo) «Ver en Pagos» pasan por la MISMA función: libera la mesa con la cuenta y suelta «Tu mesa».
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
}
