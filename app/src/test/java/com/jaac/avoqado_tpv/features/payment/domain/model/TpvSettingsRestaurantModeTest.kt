package com.jaac.avoqado_tpv.features.payment.domain.model

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.dto.TpvSettingsDto
import com.jaac.avoqado_tpv.core.data.network.dto.toDomain
import com.jaac.avoqado_tpv.core.data.network.dto.toDto
import org.junit.Test

/**
 * Restaurant mode flag ("Mesas" module entry point) on TpvSettings.
 *
 * Mirrors android's PosMode.RESTAURANT. Default `false` and additive: a
 * terminal on an old backend, or one that never sends the field, must never
 * see Mesas appear out of nowhere. Turning it ON hides the legacy "Órdenes"
 * tile — the two never coexist (spec D-4, 2026-07-24-tpv-mesas-offline-first-design.md).
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
    // NEW: visibility of the "Mesas" tile
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `modo restaurante ON oculta el tile de Ordenes`() {
        // Nunca conviven los dos caminos hacia la misma mesa (spec D-4).
        val settings = TpvSettings(restaurantModeEnabled = true, showOrderManagement = true)
        assertThat(shouldShowOrderManagementTile(settings)).isFalse()
        assertThat(shouldShowTablesTile(settings)).isTrue()
    }

    @Test
    fun `sin Cobrar y con modo restaurante OFF, Ordenes queda de respaldo`() {
        // Un negocio que apago «Cobrar» a proposito no se queda sin forma de tomar pedidos.
        val settings = TpvSettings(restaurantModeEnabled = false, showOrderManagement = true, showCheckout = false)
        assertThat(shouldShowOrderManagementTile(settings)).isTrue()
        assertThat(shouldShowTablesTile(settings)).isFalse()
    }

    @Test
    fun `P1 Cobrar sustituye a Ordenes, que es legacy (founder, 25-sep)`() {
        // «Ordenar ya es legacy, no deberia ni de aparecer». Con los defaults, Cobrar esta prendido.
        assertThat(shouldShowOrderManagementTile(TpvSettings())).isFalse()
        val conCobrar = TpvSettings(restaurantModeEnabled = false, showOrderManagement = true, showCheckout = true)
        assertThat(shouldShowOrderManagementTile(conCobrar)).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────
    // REGRESSION: existing showOrderManagement semantics untouched
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `showOrderManagement en false sigue ocultando Ordenes con modo restaurante OFF`() {
        // shouldShowOrderManagementTile debe seguir respetando el flag original,
        // no solo el nuevo — si alguien ya apagó "Ordenes" a mano, no debe reaparecer.
        val settings = TpvSettings(restaurantModeEnabled = false, showOrderManagement = false)
        assertThat(shouldShowOrderManagementTile(settings)).isFalse()
        assertThat(shouldShowTablesTile(settings)).isFalse()
    }

    @Test
    fun `showOrderManagement en false y modo restaurante ON- Ordenes sigue oculto`() {
        val settings = TpvSettings(restaurantModeEnabled = true, showOrderManagement = false)
        assertThat(shouldShowOrderManagementTile(settings)).isFalse()
        assertThat(shouldShowTablesTile(settings)).isTrue()
    }

    @Test
    fun `showOrderManagement por default sigue en true a nivel global`() {
        // El founder decidio explicitamente NO cambiar este default (rompería
        // terminales que hoy usan "Ordenes" y no van a tener Mesas). Ver spec
        // nota D-4.
        assertThat(TpvSettings().showOrderManagement).isTrue()
    }
}
