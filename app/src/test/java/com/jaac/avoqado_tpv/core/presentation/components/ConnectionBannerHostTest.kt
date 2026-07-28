package com.jaac.avoqado_tpv.core.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionBannerHostTest {

    @Test
    fun `con varias condiciones activas solo se muestra la de mayor prioridad`() {
        val visible = resolveVisibleBanner(
            offline = true,
            venueSuspended = true,
            shiftClosed = true,
        )
        // Sin conexion gana: es lo que explica por que todo lo demas falla.
        assertThat(visible).isEqualTo(BannerPriority.OFFLINE)
    }

    @Test
    fun `sin condiciones activas no gana ninguna prioridad de advertencia`() {
        val visible = resolveVisibleBanner(
            offline = false,
            venueSuspended = false,
            shiftClosed = false,
        )
        // OJO: esto prueba resolveVisibleBanner en aislamiento — "null" significa "ninguna
        // prioridad de ADVERTENCIA gana", NO "no se renderiza nada en pantalla". Con
        // showShiftBanner=true, ConnectionBannerHost sigue mostrando ShiftStatusBanner en su
        // estado normal (turno abierto, sin advertencia): es información útil, no desaparece.
        assertThat(visible).isNull()
    }

    @Test
    fun `el orden de prioridad es estable`() {
        assertThat(resolveVisibleBanner(offline = false, venueSuspended = true, shiftClosed = true))
            .isEqualTo(BannerPriority.VENUE_SUSPENDED)
        // NOTA sobre cobertura real: hoy OFFLINE, SHIFT_CLOSED y null son indistinguibles en
        // pantalla — los tres caen en la misma rama de ConnectionBannerHost (ShiftStatusBanner,
        // que internamente ya sabe pintar cada uno). Esta aserción sí prueba un hecho real y
        // documentado de resolveVisibleBanner (shiftClosed se resuelve a SHIFT_CLOSED, no se
        // traga en null ni en otro valor) — pero, a diferencia de la aserción de arriba
        // (VENUE_SUSPENDED gana, que sí cambia lo que se ve en pantalla: VenueStatusBanner vs
        // ShiftStatusBanner), esta distinción concreta NO tiene hoy una representación visual
        // separada. Se deja documentado así en vez de fingir que cubre una diferencia visible
        // que no existe.
        assertThat(resolveVisibleBanner(offline = false, venueSuspended = false, shiftClosed = true))
            .isEqualTo(BannerPriority.SHIFT_CLOSED)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // shouldShowVenueStatusBanner — fix round 1
    //
    // resolveVisibleBanner solo no basta para decidir qué se ve en pantalla: la franja
    // también depende de showShiftBanner (config por venue), que resolveVisibleBanner no
    // conoce. shouldShowVenueStatusBanner es la pieza pura que sí combina ambas señales —
    // y es la que se rompía en el bug de este round: con showShiftBanner=false, un venue no
    // ACTIVE + offline=true resolvía priority=OFFLINE (no VENUE_SUSPENDED), y sin banner de
    // turno de respaldo tampoco, la franja quedaba completamente vacía.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `venue suspendido sigue visible offline cuando no hay banner de turno de respaldo`() {
        // El caso del bug: showShiftBanner=false es la config PlayTelecom BAE (modo
        // simplificado / serialized-inventory), en wifi de tienda que se cae seguido —
        // exactamente donde offline+venue-no-activo coinciden más seguido y más importa
        // que el aviso de venue no desaparezca.
        val priority = resolveVisibleBanner(offline = true, venueSuspended = true, shiftClosed = true)
        assertThat(priority).isEqualTo(BannerPriority.OFFLINE) // confirma la premisa del bug

        val showVenueBanner = shouldShowVenueStatusBanner(
            priority = priority,
            venueSuspended = true,
            showShiftBanner = false,
        )
        assertThat(showVenueBanner).isTrue()
    }

    @Test
    fun `offline sigue ganando sobre venue suspendido cuando SI hay banner de turno`() {
        // Comportamiento intencional sin cambios: con showShiftBanner=true, offline gana —
        // ShiftStatusBanner (que ya sabe explicar "sin conexion") reemplaza a VenueStatusBanner,
        // no lo apila. LIVE_DEMO cae en el mismo caso: no está special-cased, ver kdoc de
        // shouldShowVenueStatusBanner.
        val priority = resolveVisibleBanner(offline = true, venueSuspended = true, shiftClosed = true)

        val showVenueBanner = shouldShowVenueStatusBanner(
            priority = priority,
            venueSuspended = true,
            showShiftBanner = true,
        )
        assertThat(showVenueBanner).isFalse()
    }

    @Test
    fun `venue suspendido gana normalmente cuando esta online y hay banner de turno`() {
        // El bug original que este host arregló: antes VenueStatusBanner tapaba a
        // ShiftStatusBanner en vez de reemplazarlo. Sigue resuelto.
        val priority = resolveVisibleBanner(offline = false, venueSuspended = true, shiftClosed = true)

        val showVenueBanner = shouldShowVenueStatusBanner(
            priority = priority,
            venueSuspended = true,
            showShiftBanner = true,
        )
        assertThat(showVenueBanner).isTrue()
    }

    @Test
    fun `venue activo sin banner de turno no muestra nada`() {
        // Sin advertencia de ningun tipo y sin banner de turno configurado: la franja se queda
        // vacia, igual que antes de este cambio (VenueStatusBanner ya se ocultaba para ACTIVE).
        val priority = resolveVisibleBanner(offline = false, venueSuspended = false, shiftClosed = false)

        val showVenueBanner = shouldShowVenueStatusBanner(
            priority = priority,
            venueSuspended = false,
            showShiftBanner = false,
        )
        assertThat(showVenueBanner).isFalse()
    }
}
