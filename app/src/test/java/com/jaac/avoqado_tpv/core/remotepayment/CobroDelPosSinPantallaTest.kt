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
        // Codex r3 #4 y r4 #1: si guardar truena, no se cierra la app y tampoco se manda nada (ni el `timeout`).
        val falla = socketManager.substring(guardar, aviso)
        val atrapa = falla.indexOf("} catch (error: Exception) {")
        assertThat(atrapa).isAtLeast(0)
        assertThat(falla.substring(atrapa)).contains("return@launch")
    }

    @Test
    fun `la admision y la espera de la ruta dicen el mismo texto`() {
        assertThat(navegacion).contains("else com.jaac.avoqado_tpv.core.remotepayment.LECTOR_NO_LISTO_EN_LA_TERMINAL")
        assertThat(navegacion).doesNotContain("\"Sistema de pagos no inicializado en el terminal\"")
    }

    @Test
    fun `la espera de la ruta contesta al POS sólo sin pantalla, una vez y antes de salir`() {
        val espera = esperaDeLaRutaDePago()
        // Con la pantalla ya mostrada contesta ella al morir (D26); sólo un cobro de la tablet trae `socketRequestId`.
        assertThat(espera).contains(
            "val cobroDelPosSinPantalla = if (pantallaYaMostrada) null else argumentosDelCobro.get<String>(\"socketRequestId\")",
        )
        assertThat(espera).contains("if (cobroDelPosSinPantalla != null && yaContesto.compareAndSet(false, true)) {")
        val noListo = espera.indexOf("if (!ready) {")
        val contesta = espera.indexOf("contestarAlPos()", noListo)
        val salida = espera.indexOf("navController.safePopBackStack()", noListo)
        assertThat(noListo).isAtLeast(0)
        assertThat(contesta).isGreaterThan(noListo)
        assertThat(contesta).isLessThan(salida)
    }

    @Test
    fun `Atras durante la espera contesta sólo en el cobro de la tablet sin pantalla`() {
        val espera = esperaDeLaRutaDePago()
        val atras = espera.indexOf("androidx.activity.compose.BackHandler(enabled = cobroDelPosSinPantalla != null) {")
        assertThat(atras).isAtLeast(0)
        val cuerpo = espera.substring(atras, espera.indexOf("LaunchedEffect(Unit) {", atras))
        // Codex r2 #2: sin el filtro de toques de safePopBackStack (con él saltaba a Inicio); el mismo regreso que el NavHost.
        assertThat(cuerpo.indexOf("contestarAlPos()")).isLessThan(cuerpo.indexOf("navController.popBackStack()"))
        assertThat(cuerpo).doesNotContain("NavRoute.Home.route")
    }

    private val navegacion =
        File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()

    private fun esperaDeLaRutaDePago(): String {
        val inicio = navegacion.indexOf("val yaContesto = remember { java.util.concurrent.atomic.AtomicBoolean(false) }")
        require(inicio >= 0) { "No encontré la espera del lector de la ruta de pago" }
        return navegacion.substring(inicio, navegacion.indexOf("AvoqadoLoadingOverlay(message = \"Preparando sistema de pagos...\")", inicio))
    }
}
