package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * D9: qué le dice la «Cobrar mesa» al cajero según lo que la libreta y la cola tienen de ESA cuenta (la consulta única se
 * prueba con la base real en `DineroDeTarjetaDeLaCuentaRoomTest`).
 */
class DineroDeTarjetaDeLaCuentaTest {

    private val libreta = mockk<PaymentAttemptLedger>()
    private val dinero = DineroDeTarjetaDeLaCuenta(libreta)

    private fun responde(codigo: String?) {
        coEvery { libreta.dineroDeTarjetaSinConfirmarDeLaCuenta("venue-1", "orden-1") } returns codigo
    }

    @Test
    fun sin_nada_de_tarjeta_pendiente_no_hay_motivo() = runTest {
        responde(null)
        assertThat(dinero.motivo("venue-1", "orden-1")).isNull()
    }

    @Test
    fun cada_fuente_dice_su_motivo() = runTest {
        responde("SIN_REGISTRAR")
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_SIN_REGISTRAR)
        responde("SUBIENDO")
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_SUBIENDOSE)
        responde("LIBRETA")
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_REGISTRANDOSE)
    }

    @Test
    fun una_respuesta_que_no_se_conoce_tambien_frena() = runTest {
        responde("ALGO_NUEVO")
        assertThat(dinero.motivo("venue-1", "orden-1")).isEqualTo(TARJETA_REGISTRANDOSE)
    }

    @Test
    fun si_no_se_puede_leer_lanza_para_que_quien_pregunta_falle_cerrado() {
        coEvery { libreta.dineroDeTarjetaSinConfirmarDeLaCuenta(any(), any()) } throws IllegalStateException("libreta ilegible")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { dinero.motivo("venue-1", "orden-1") } }
    }
}
