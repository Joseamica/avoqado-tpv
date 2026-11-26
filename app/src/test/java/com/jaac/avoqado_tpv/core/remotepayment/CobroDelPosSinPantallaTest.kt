package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    fun `contesta no se cobro con el lector no listo y suelta el cobro sólo después de guardar`() {
        val socket = mockk<SocketManager>(relaxed = true)
        val coordinador = mockk<RemotePaymentCoordinator>(relaxed = true)
        val alGuardar = slot<() -> Unit>()

        contestarCobroDelPosSinPantalla(socket, coordinador, "req-1")

        verify {
            socket.emitTerminalPaymentResult(
                "req-1", "failed", errorMessage = LECTOR_NO_LISTO_EN_LA_TERMINAL, outcomeEvidence = "PRE_AUTHORIZATION",
                alGuardar = capture(alGuardar),
            )
        }
        // Codex r1 #1: con la propiedad suelta antes de guardar, una sonda se quedaba con «Cancelado por conciliación».
        verify(exactly = 0) { coordinador.soltarPropiedad(any()) }
        alGuardar.captured()
        verify(exactly = 1) { coordinador.soltarPropiedad("req-1") }
    }

    @Test
    fun `el envio del resultado avisa despues de guardar en la bandeja`() {
        val socketManager = File("src/main/java/com/jaac/avoqado_tpv/core/data/realtime/SocketManager.kt").readText()
        val guardar = socketManager.indexOf("remotePaymentInbox.persistResult(requestId, payload.toString())")
        val aviso = socketManager.indexOf("alGuardar?.invoke()")
        val socket = socketManager.indexOf("emitPersistedTerminalPaymentResult(persisted)")
        assertThat(guardar).isAtLeast(0)
        assertThat(aviso).isGreaterThan(guardar)
        assertThat(aviso).isLessThan(socket)
        assertThat(socketManager.substring(guardar, aviso)).contains("} finally {")
    }

    @Test
    fun `la admision y la espera de la ruta dicen el mismo texto`() {
        assertThat(navegacion).contains("else com.jaac.avoqado_tpv.core.remotepayment.LECTOR_NO_LISTO_EN_LA_TERMINAL")
        assertThat(navegacion).doesNotContain("\"Sistema de pagos no inicializado en el terminal\"")
    }

    @Test
    fun `la espera de la ruta contesta al POS sólo sin pantalla y antes de salir`() {
        val espera = esperaDeLaRutaDePago()
        val contesta = espera.indexOf("contestarCobroDelPosSinPantalla(socketManager, remotePaymentCoordinator, solicitud)")
        val salida = espera.indexOf("navController.safePopBackStack()")
        assertThat(contesta).isAtLeast(0)
        assertThat(contesta).isLessThan(salida)
        // Con la pantalla ya mostrada contesta ella al morir (D26); sólo un cobro de la tablet trae `socketRequestId`.
        val guarda = espera.substring(0, contesta)
        assertThat(guarda).contains("if (!yaSalio.compareAndSet(false, true)) return@salir")
        assertThat(guarda).contains("if (!pantallaYaMostrada)")
        assertThat(guarda).contains("argumentosDelCobro.get<String>(\"socketRequestId\")")
    }

    @Test
    fun `el lector que no se prepara y Atras durante la espera salen por el mismo camino`() {
        val espera = esperaDeLaRutaDePago()
        assertThat(espera).contains("androidx.activity.compose.BackHandler { salirDeLaEspera() }")
        assertThat(espera).contains("if (!ready) salirDeLaEspera()")
        assertThat(espera.indexOf("awaitPaxPaymentReady(context, initializationManager, \"Payment route\")"))
            .isGreaterThan(espera.indexOf("val salirDeLaEspera"))
    }

    private val navegacion =
        File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()

    private fun esperaDeLaRutaDePago(): String {
        val inicio = navegacion.indexOf("val yaSalio = remember { java.util.concurrent.atomic.AtomicBoolean(false) }")
        require(inicio >= 0) { "No encontré la espera del lector de la ruta de pago" }
        return navegacion.substring(inicio, navegacion.indexOf("AvoqadoLoadingOverlay(message = \"Preparando sistema de pagos...\")", inicio))
    }
}
