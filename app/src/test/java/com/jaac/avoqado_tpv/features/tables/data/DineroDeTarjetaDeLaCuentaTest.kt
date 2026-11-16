package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * D9 (Codex r1 de la unión, 16-nov): la «Cobrar mesa» no cobra mientras la terminal tenga dinero de TARJETA de esa cuenta que
 * el SERVIDOR todavía no confirma. Mismas fuentes que la tienda híbrida (R89).
 */
class DineroDeTarjetaDeLaCuentaTest {

    private val cobroEnCurso = PaymentStateHolder()
    private val cola = mockk<PendingPaymentDao>()
    private val libreta = mockk<PaymentAttemptLedger>()
    private val dinero = DineroDeTarjetaDeLaCuenta(cobroEnCurso, cola, libreta)

    @Before
    fun setUp() {
        coEvery { cola.hayCobroSinRegistrar(any(), any()) } returns false
        coEvery { cola.hayCobroSubiendose(any(), any()) } returns false
        coEvery { libreta.hayDineroEnJuegoDeLaVenta(any()) } returns false
    }

    @Test
    fun sin_nada_de_tarjeta_pendiente_no_hay_motivo() = runTest {
        assertThat(dinero.motivo("venue-1", "orden-1")).isNull()
    }

    @Test
    fun una_tarjeta_que_todavia_se_registra_en_esta_terminal_frena() = runTest {
        cobroEnCurso.setCharging(true)
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_REGISTRANDOSE)
    }

    @Test
    fun la_libreta_con_una_tarjeta_aprobada_y_sin_registrar_de_esa_cuenta_frena() = runTest {
        coEvery { libreta.hayDineroEnJuegoDeLaVenta("orden-1") } returns true
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_REGISTRANDOSE)
        assertThat(dinero.motivo("venue-1", "orden-2")).isNull()
    }

    @Test
    fun la_cola_con_una_tarjeta_de_esa_cuenta_que_todavia_no_sube_frena() = runTest {
        coEvery { cola.hayCobroSubiendose("venue-1", "orden-1") } returns true
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_SUBIENDOSE)
    }

    @Test
    fun una_tarjeta_que_el_servidor_no_registro_frena_y_pide_al_gerente() = runTest {
        coEvery { cola.hayCobroSinRegistrar("venue-1", "orden-1") } returns true
        coEvery { cola.hayCobroSubiendose("venue-1", "orden-1") } returns true
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_SIN_REGISTRAR)
    }

    @Test
    fun si_no_se_puede_leer_lanza_para_que_quien_pregunta_falle_cerrado() {
        coEvery { libreta.hayDineroEnJuegoDeLaVenta(any()) } throws IllegalStateException("libreta ilegible")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { dinero.motivo("venue-1", "orden-1") } }
    }
}
