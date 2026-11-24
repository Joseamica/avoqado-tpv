package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.dao.PendingPaymentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Encargo f3cba0: un cobro que manda el POS para una cuenta que ESTA terminal ya cobró y que todavía no sube a Avoqado
 * (efectivo en el cajón con su fila en la cola, o un registro rechazado sin revisar) no se abre: el SERVIDOR aún la ve
 * «por pagar» y abrirlo sería cobrarle dos veces al cliente. Es el mismo predicado que ya usa «Por pagar» (R78/R83).
 */
class CuentaCobradaSinSubirTest {

    private val cola = mockk<PendingPaymentDao>()

    private fun libre() {
        coEvery { cola.hayCobroSinRegistrar(any(), any()) } returns false
        coEvery { cola.hayCobroSubiendose(any(), any()) } returns false
    }

    @Test
    fun `sin cuenta (pago rápido) no se revisa la cola y no se frena`() = runBlocking {
        assertThat(CuentaCobradaSinSubir.motivo(orderId = null, venueId = "v1", cola = cola)).isNull()
        coVerify(exactly = 0) { cola.hayCobroSubiendose(any(), any()) }
        coVerify(exactly = 0) { cola.hayCobroSinRegistrar(any(), any()) }
    }

    @Test
    fun `cuenta sin nada pendiente en esta terminal se admite`() = runBlocking {
        libre()
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola)).isNull()
    }

    @Test
    fun `P1 cuenta con un cobro de esta terminal todavía subiendo no se abre`() = runBlocking {
        libre()
        coEvery { cola.hayCobroSubiendose("v1", "o1") } returns true
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola))
            .isEqualTo(CuentaCobradaSinSubir.COBRO_SUBIENDOSE)
    }

    @Test
    fun `P1 cuenta con un registro rechazado sin revisar no se abre y lo nombra`() = runBlocking {
        libre()
        coEvery { cola.hayCobroSinRegistrar("v1", "o1") } returns true
        coEvery { cola.hayCobroSubiendose("v1", "o1") } returns true
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola))
            .isEqualTo(CuentaCobradaSinSubir.COBRO_SIN_REGISTRAR)
    }

    @Test
    fun `si la cola no se deja leer falla cerrado`() = runBlocking {
        coEvery { cola.hayCobroSinRegistrar(any(), any()) } throws IllegalStateException("disco")
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "v1", cola = cola))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
    }

    @Test
    fun `sin negocio en la sesión falla cerrado`() = runBlocking {
        libre()
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = null, cola = cola))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
        assertThat(CuentaCobradaSinSubir.motivo(orderId = "o1", venueId = "", cola = cola))
            .isEqualTo(CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR)
    }

    @Test
    fun `los textos dicen que este cobro NO se inició`() {
        listOf(
            CuentaCobradaSinSubir.COBRO_SUBIENDOSE,
            CuentaCobradaSinSubir.COBRO_SIN_REGISTRAR,
            CuentaCobradaSinSubir.NO_SE_PUDO_REVISAR,
        ).forEach { assertThat(it).contains("NO se inició") }
    }

    /** El cableado: la admisión del cobro del POS en la navegación pregunta esto ANTES de abrir la pantalla de cobro. */
    @Test
    fun `la navegación revisa la cuenta antes de abrir un cobro del POS`() {
        val fuente = listOf(
            File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt"),
            File("app/src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt"),
        ).first { it.exists() }.readText()
        val admision = fuente.substringAfter("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
            .substringBefore("if (motivoParaNoIniciar != null)")
        assertThat(admision).contains("?: cobroConSaldo.motivoParaNoCobrarRemoto(request.orderId)")
        assertThat(admision).contains("?: CuentaCobradaSinSubir.motivo(")
        assertThat(admision).contains("orderId = request.orderId")
        assertThat(admision).contains("cola = colaDeCobros")
        // Y la pantalla de cobro se abre DESPUÉS de esa revisión, nunca antes.
        val antesDeLaRevision = fuente.substringBefore("val motivoParaNoIniciar = CobroRemotoDelPos.motivoParaNoIniciar(")
            .substringAfter("remotePaymentCoordinator.paymentRequests.collect")
        assertThat(antesDeLaRevision).doesNotContain("handle.set(\"initialAmount\"")
    }
}
