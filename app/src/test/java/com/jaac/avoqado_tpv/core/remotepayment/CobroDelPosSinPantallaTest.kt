package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Test
import java.io.File

/**
 * 📡 hz:1450a1e9:D3 (25-nov). POS → TPV en la PAX: la admisión vio el lector listo y la ruta de cobro, antes de su primer dibujo,
 * lo encontró caído; la segunda preparación falló y la espera de la ruta hizo `safePopBackStack()` sin ViewModel. Nadie
 * contestaba: el POS esperaba hasta que su propio reloj se rendía y la sonda seguía diciendo «viva» por la propiedad de este
 * proceso. Ahora se contesta como la admisión con el lector no listo y se suelta la propiedad, como al morir una pantalla (D26).
 * La espera vive en una `composable` de la navegación: se lee la fuente, como en `ArgumentosDelCobroSeLeenUnaVezTest`.
 */
class CobroDelPosSinPantallaTest {
    @Test
    fun `contesta no se cobro con el lector no listo y suelta el cobro`() {
        val socket = mockk<SocketManager>(relaxed = true)
        val coordinador = mockk<RemotePaymentCoordinator>(relaxed = true)

        contestarCobroDelPosSinPantalla(socket, coordinador, "req-1")

        // Primero el «no» (se guarda con el candado de la libreta antes de salir), después se suelta la propiedad.
        verifyOrder {
            socket.emitTerminalPaymentResult(
                "req-1", "failed", errorMessage = LECTOR_NO_LISTO_EN_LA_TERMINAL, outcomeEvidence = "PRE_AUTHORIZATION",
            )
            coordinador.soltarPropiedad("req-1")
        }
    }

    @Test
    fun `la admision y la espera de la ruta dicen el mismo texto`() {
        assertThat(navegacion).contains("else com.jaac.avoqado_tpv.core.remotepayment.LECTOR_NO_LISTO_EN_LA_TERMINAL")
        assertThat(navegacion).doesNotContain("\"Sistema de pagos no inicializado en el terminal\"")
    }

    @Test
    fun `la espera de la ruta contesta al POS sólo sin pantalla y antes de salir`() {
        val espera = esperaDeLaRutaDePago()
        val noListo = espera.indexOf("if (!ready) {")
        val contesta = espera.indexOf("contestarCobroDelPosSinPantalla(socketManager, remotePaymentCoordinator, solicitud)")
        val salida = espera.indexOf("navController.safePopBackStack()")
        assertThat(noListo).isAtLeast(0)
        assertThat(contesta).isGreaterThan(noListo)
        assertThat(contesta).isLessThan(salida)
        // Con la pantalla ya mostrada contesta ella al morir (D26); sólo un cobro de la tablet trae `socketRequestId`.
        val guarda = espera.substring(noListo, contesta)
        assertThat(guarda).contains("if (!pantallaYaMostrada)")
        assertThat(guarda).contains("argumentosDelCobro.get<String>(\"socketRequestId\")")
    }

    private val navegacion =
        File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()

    private fun esperaDeLaRutaDePago(): String {
        val inicio = navegacion.indexOf("awaitPaxPaymentReady(context, initializationManager, \"Payment route\")")
        require(inicio >= 0) { "No encontré la espera del lector de la ruta de pago" }
        return navegacion.substring(inicio, navegacion.indexOf("AvoqadoLoadingOverlay(message = \"Preparando sistema de pagos...\")", inicio))
    }
}
