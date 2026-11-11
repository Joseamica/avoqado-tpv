package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import org.junit.Test
import java.math.BigDecimal

/**
 * Mesa M1 (`cmsds40wp000cc9ixmm8u71f4`, real, encontrado en vivo en el PAX
 * del founder): "Ver cuenta" mostraba "Todavía no hay nada en esta cuenta"
 * sobre una orden con 3 artículos y $199.00 REALES en el server — el mesero
 * no podía cobrar. La causa NO era que `TableOrderScreen` solo pintara el
 * carrito pendiente (esa hipótesis se descartó leyendo el código y
 * reproduciendo en hardware: [OrderDetail.roundsSentToKitchen] SÍ agrupa y
 * expone los items ya enviados). La causa real: [TableOrderViewModel.loadCheck]
 * fallando (blip de red, timeout, un 500) en la carga INICIAL dejaba
 * `check == null` sin ninguna señal — `resolveTableOrderBodyState` no
 * distinguía "cuenta vacía" de "no se pudo cargar" y caía siempre en la rama
 * de contenido, que con `rounds`/`pendingLines`/`queuedLines` los tres vacíos
 * pinta [EmptyOrderState].
 *
 * Estos tests cubren, en este orden, lo que pidió el enunciado:
 *  1. Una orden cargada con items + carrito pendiente vacío → SÍ se renderiza
 *     contenido (nunca la pantalla vacía ni la de "no se pudo cargar").
 *  2. El reverso: carrito pendiente con líneas, sin cheque cargado todavía →
 *     también se renderiza contenido — un load-failed NUNCA debe tapar lo
 *     que el mesero ya tecleó.
 *  3. Ambos juntos.
 *  4. La regresión real: [TableOrderViewModel.checkLoadFailed] encendido y
 *     SIN nada más que mostrar → rama dedicada `LOAD_FAILED`, nunca la misma
 *     que una cuenta genuinamente vacía.
 */
class TableOrderScreenStateTest {

    // region — resolveTableOrderBodyState: la regresión real (Mesa M1)

    @Test
    fun cuenta_cargada_con_items_y_carrito_vacio_resuelve_CONTENT() {
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = orderWithItems(),
            checkLoadFailed = false,
            isProvisional = false,
            hasPendingOrQueuedLines = false,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    @Test
    fun reverso_carrito_pendiente_sin_cheque_cargado_TODAVIA_resuelve_CONTENT() {
        // check == null porque todavía no hay ÉXITO ni FALLA registrados para
        // esta orden (p.ej. sesión recién arrancada) — el carrito local ya
        // tiene algo. No debe ser tratado como "no hay nada".
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = null,
            checkLoadFailed = false,
            isProvisional = false,
            hasPendingOrQueuedLines = true,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    @Test
    fun reverso_load_failed_pero_HAY_carrito_pendiente_resuelve_CONTENT_nunca_LOAD_FAILED() {
        // El bug que el propio fix podía introducir si no se cubre: un
        // mesero agrega productos al carrito ANTES de que la carga inicial
        // de la cuenta falle — el carrito NUNCA debe desaparecer detrás de
        // la pantalla de "no se pudo cargar".
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = null,
            checkLoadFailed = true,
            isProvisional = false,
            hasPendingOrQueuedLines = true,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    @Test
    fun ambos_juntos_cheque_cargado_Y_carrito_pendiente_resuelve_CONTENT() {
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = orderWithItems(),
            checkLoadFailed = false,
            isProvisional = false,
            hasPendingOrQueuedLines = true,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    @Test
    fun mesa_M1_load_failed_SIN_nada_mas_que_mostrar_resuelve_LOAD_FAILED_nunca_como_cuenta_vacia() {
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = null,
            checkLoadFailed = true,
            isProvisional = false,
            hasPendingOrQueuedLines = false,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.LOAD_FAILED)
    }

    @Test
    fun un_refresh_fallido_sobre_una_cuenta_YA_cargada_no_la_reemplaza_por_LOAD_FAILED() {
        // checkLoadFailed=true pero `check` YA no es null (el `if (_check.value
        // == null)` en TableOrderViewModel.loadCheck ya evita que esto pase en
        // la práctica, pero el resolver es la última línea de defensa).
        val state = resolveTableOrderBodyState(
            isLoadingCheck = false,
            check = orderWithItems(),
            checkLoadFailed = true,
            isProvisional = false,
            hasPendingOrQueuedLines = false,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    @Test
    fun sesion_provisional_cargando_nunca_muestra_el_spinner_de_LOADING() {
        // Mesa abierta offline: check siempre null a propósito (ver KDoc de
        // TableOrderViewModel.loadCheck) — no debe confundirse con "cargando".
        val state = resolveTableOrderBodyState(
            isLoadingCheck = true,
            check = null,
            checkLoadFailed = false,
            isProvisional = true,
            hasPendingOrQueuedLines = false,
        )

        assertThat(state).isEqualTo(TableOrderBodyState.CONTENT)
    }

    // endregion

    // region — roundsSentToKitchen(): los items YA enviados sí se agrupan (la hipótesis descartada)

    @Test
    fun orden_con_items_ya_enviados_se_agrupa_en_una_ronda_no_desaparece() {
        // Espejo exacto del payload real de Mesa M1: 3 items, los 3 con
        // sentToKitchenAt=null (server real, verificado en logcat) — deben
        // seguir siendo UNA ronda visible, nunca cero.
        val order = orderWithItems()

        val rounds = order.roundsSentToKitchen()

        assertThat(rounds).hasSize(1)
        assertThat(rounds.first().items).hasSize(3)
        assertThat(rounds.first().items.sumOf { it.total }).isEqualTo(BigDecimal("199"))
    }

    @Test
    fun items_de_rondas_distintas_se_separan_por_sentToKitchenAt() {
        val order = OrderDetail(
            id = "order-1",
            items = listOf(
                OrderDetailItem(id = "i1", productName = "Café", quantity = 1, total = BigDecimal("45"), sentToKitchenAt = "2026-08-03T10:00:00Z"),
                OrderDetailItem(id = "i2", productName = "Croissant", quantity = 1, total = BigDecimal("55"), sentToKitchenAt = "2026-08-03T11:00:00Z"),
            ),
        )

        val rounds = order.roundsSentToKitchen()

        assertThat(rounds).hasSize(2)
        // Más reciente primero.
        assertThat(rounds.first().sentAt).isEqualTo("2026-08-03T11:00:00Z")
    }

    // endregion

    // region — splitBySeatEnabled: paridad Android 2026-08-06 (Hallazgo #1) —
    // la TPV nunca manda `seat` al agregar items, así que una cuenta armada
    // ÍNTEGRAMENTE en esta terminal debe deshabilitar "Dividir por puesto"
    // (siempre fallaría), pero una que llegó de Android/iOS CON asientos sí
    // debe habilitarlo — ese caso SÍ funciona.

    @Test
    fun sin_ningun_item_con_asiento_dividir_por_puesto_se_deshabilita() {
        // El caso real que rompía: una cuenta armada solo en la TPV — todos
        // sus items tienen seat=null porque el cliente nunca lo manda.
        val enabled = splitBySeatEnabled(readOnly = false, hasSentItems = true, check = orderWithItems())

        assertThat(enabled).isFalse()
    }

    @Test
    fun con_al_menos_un_item_con_asiento_dividir_por_puesto_se_habilita() {
        // Llegó de Android/iOS, que SÍ asignan seat al agregar items — ahí
        // la acción funciona de verdad hoy.
        val order = orderWithItems().copy(
            items = orderWithItems().items.mapIndexed { index, item -> item.copy(seat = if (index == 0) 1 else null) },
        )

        val enabled = splitBySeatEnabled(readOnly = false, hasSentItems = true, check = order)

        assertThat(enabled).isTrue()
    }

    @Test
    fun readOnly_deshabilita_incluso_con_asientos() {
        val order = orderWithItems().copy(
            items = orderWithItems().items.map { it.copy(seat = 1) },
        )

        val enabled = splitBySeatEnabled(readOnly = true, hasSentItems = true, check = order)

        assertThat(enabled).isFalse()
    }

    @Test
    fun sin_items_enviados_se_deshabilita_aunque_check_sea_null() {
        val enabled = splitBySeatEnabled(readOnly = false, hasSentItems = false, check = null)

        assertThat(enabled).isFalse()
    }

    // endregion

    private fun orderWithItems(): OrderDetail = OrderDetail(
        id = "cmsds40wp000cc9ixmm8u71f4",
        orderNumber = "ORD-1785794792856",
        total = BigDecimal("199"),
        items = listOf(
            OrderDetailItem(id = "i1", productName = "Coca-Cola 600ml", quantity = 1, total = BigDecimal("25"), sentToKitchenAt = null),
            OrderDetailItem(id = "i2", productName = "Alitas Buffalo", quantity = 1, total = BigDecimal("129"), sentToKitchenAt = null),
            OrderDetailItem(id = "i3", productName = "Cerveza Corona", quantity = 1, total = BigDecimal("45"), sentToKitchenAt = null),
        ),
    )
    // D10 (3-nov, N86): la ronda decía «11:21» cuando eran las 05:21: se pintaba el desfase que trae el servidor.
    @Test fun `la hora de la ronda sale en la hora del negocio`() {
        val mexico = com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO
        assertThat(roundLabel("2026-11-03T11:21:00.000Z", 2, mexico)).isEqualTo("Ronda 2 · 05:21")
        assertThat(roundLabel("2026-11-03T11:21:00+00:00", 2, mexico)).isEqualTo("Ronda 2 · 05:21")
        assertThat(roundLabel("2026-11-03T11:21:00Z", 1, java.time.ZoneOffset.ofHours(-7))).isEqualTo("Ronda 1 · 04:21")
    }

    @Test fun `una ronda sin hora legible sigue diciendo su numero`() {
        val mexico = com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO
        assertThat(roundLabel(null, 3, mexico)).isEqualTo("Ronda 3")
        assertThat(roundLabel("ayer", 3, mexico)).isEqualTo("Ronda 3")
    }
    // Codex r1 (D10): «Acciones por revisar» usaba la zona del aparato, y una N86 o una PAX traen reglas viejas.
    @Test fun `la hora de una accion por revisar tambien sale en la hora del negocio`() {
        val instante = java.time.Instant.parse("2026-11-03T11:21:00Z").toEpochMilli()
        assertThat(formatQuarantineTime(instante, com.jaac.avoqado_tpv.core.util.VenueTimeZone.MEXICO)).endsWith("05:21")
        assertThat(formatQuarantineTime(instante, java.time.ZoneOffset.ofHours(-7))).endsWith("04:21")
    }

    /**
     * D4 (N86, 10-nov): la hoja de cortesía se cierra con el contador del ViewModel, no con la bajada de `isComping`
     * (que Compose no ve si la cortesía termina dentro del mismo toque). Prueba estática, como
     * `CheckoutScreenErrorConLaHojaTest`: el repo no corre `createComposeRule` en las pruebas de JVM.
     */
    @Test
    fun D4_la_hoja_de_cortesia_se_cierra_con_el_contador_y_no_con_isComping() {
        val pantalla = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/tables/presentation/TableOrderScreen.kt").readText()
        assertThat(pantalla).doesNotContain("LaunchedEffect(isComping)")
        val i = pantalla.indexOf("LaunchedEffect(compTerminadas) {")
        assertThat(i).isAtLeast(0)
        val efecto = pantalla.substring(i, pantalla.indexOf("\n    }", i))
        assertThat(efecto).contains("showCompSheet = false")
    }

    /**
     * D4 (Codex r1 y r2): el aviso de error también se vuelve a mostrar cuando termina una cortesía, para que la 2.ª
     * negada con el mismo texto (el 1.er aviso aún en pantalla) salga completa. Se sigue vaciando DESPUÉS de mostrarse:
     * vaciarlo antes lo perdía si la pantalla se recreaba (Codex r2).
     */
    @Test
    fun D4_el_aviso_de_error_tambien_se_repite_al_terminar_la_cortesia_y_se_vacia_despues_de_mostrarse() {
        val pantalla = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/tables/presentation/TableOrderScreen.kt").readText()
        assertThat(pantalla).contains("val cortesias = compTerminadas")
        val i = pantalla.indexOf("LaunchedEffect(error, cortesias) {")
        assertThat(i).isAtLeast(0)
        val efecto = pantalla.substring(i, pantalla.indexOf("\n    }", i))
        val avisar = efecto.indexOf("showSnackbar(")
        val vaciar = efecto.indexOf("viewModel.consumeError(it, cortesias)")
        assertThat(avisar).isAtLeast(0)
        assertThat(vaciar).isGreaterThan(avisar)
    }
}
