package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import org.junit.Test
import java.io.File

/**
 * Restaurant mode flag ("Mesas" module entry point) on TpvSettings.
 *
 * Mirrors android's PosMode.RESTAURANT. Default `false` and additive: a
 * terminal on an old backend, or one that never sends the field, must never
 * see Mesas appear out of nowhere. «Órdenes», el camino viejo hacia la misma mesa,
 * ya no existe en la terminal (D34, 20-nov).
 */
class TpvSettingsRestaurantModeTest {

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────
    // NEW: the flag itself
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `restaurantModeEnabled es false por default`() {
        // Aditivo: las terminales viejas no deben ver Mesas de la nada.
        assertThat(TpvSettings().restaurantModeEnabled).isFalse()
    }

    @Test
    fun `el DTO sin el campo cae al default`() {
        // Backend viejo o respuesta parcial: nunca debe romper el parseo.
        val dto = gson.fromJson("""{"showTipScreen": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().restaurantModeEnabled).isFalse()
    }

    @Test
    fun `el DTO con el campo en true lo respeta`() {
        val dto = gson.fromJson("""{"restaurantModeEnabled": true}""", TpvSettingsDto::class.java)
        assertThat(dto.toDomain().restaurantModeEnabled).isTrue()
    }

    @Test
    fun `toDto round-trips la bandera (regresion - guardar settings no debe perderla)`() {
        val settings = TpvSettings(restaurantModeEnabled = true)
        assertThat(settings.toDto().restaurantModeEnabled).isTrue()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Visibilidad del tile de «Mesas»
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `modo restaurante ON muestra Mesas`() {
        assertThat(shouldShowTablesTile(TpvSettings(restaurantModeEnabled = true))).isTrue()
        // showOrderManagement ya no cuenta para nada en la pantalla (D34): ni prendido ni apagado cambia Mesas.
        assertThat(shouldShowTablesTile(TpvSettings(restaurantModeEnabled = true, showOrderManagement = true))).isTrue()
        assertThat(shouldShowTablesTile(TpvSettings(restaurantModeEnabled = true, showOrderManagement = false))).isTrue()
    }

    @Test
    fun `modo restaurante OFF no muestra Mesas`() {
        assertThat(shouldShowTablesTile(TpvSettings(restaurantModeEnabled = false))).isFalse()
        assertThat(shouldShowTablesTile(TpvSettings(restaurantModeEnabled = false, showOrderManagement = true, showCheckout = false))).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────
    // P1 «Órdenes» ya no existe en la terminal (founder, 25-sep, 20-oct y 20-nov: D34)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * «Para ordering se usa Mesas y Cobrar». El SERVIDOR ya manda `showOrderManagement=false` siempre; aquí se fija que la
     * TPV no tenga por dónde enseñarla aunque un servidor viejo o un ajuste guardado la manden prendida (la TPV la toma como
     * prendida si el campo falta). Estática a propósito, como `ArgumentosDelCobroSeLeenUnaVezTest`.
     */
    @Test
    fun `P1 el Inicio no tiene tarjeta de Ordenes ni la navegacion sus pantallas`() {
        val inicio = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt").readText()
        val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
        val rutas = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/NavRoute.kt").readText()
        assertThat(inicio).doesNotContain("label = \"Órdenes\"")
        assertThat(inicio).doesNotContain("onNavigateToOrdering")
        // Con límite de palabra: `TableMenuScreen(` (el menú de Mesas) contiene «MenuScreen(» y SÍ debe seguir (Codex r1 #6).
        listOf("""\bOrderingWelcome""", """\bNavRoute\.Menu\b""", """\bNavRoute\.OrderList\b""", """\bNavRoute\.FloorPlan\b""", """\bMenuScreen\(""").forEach {
            assertThat(navegacion).doesNotContainMatch(it)
        }
        assertThat(navegacion).contains("TableMenuScreen(")
        listOf("ordering_welcome", "\"menu/{orderId}\"", "order_list", "floor_plan").forEach { assertThat(rutas).doesNotContain(it) }
    }

    @Test
    fun `regresion el Inicio sigue teniendo Mesas, Cobrar y Pago rapido`() {
        val inicio = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/WelcomeScreen.kt").readText()
        listOf("label = \"Mesas\"", "label = \"Cobrar\"", "label = \"Pago rápido\"").forEach { assertThat(inicio).contains(it) }
    }
}
