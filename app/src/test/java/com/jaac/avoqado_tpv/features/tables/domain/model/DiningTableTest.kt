package com.jaac.avoqado_tpv.features.tables.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

/**
 * P0 — el dinero real, no `Table.status` (columna denormalizada que se puede
 * desincronizar), es lo único que decide si una mesa está ocupada. Mismo bug
 * y mismo fix que `avoqado-android`/`avoqado-ios` (2026-07-28): mesa M2 traía
 * una orden PENDING de $603.50 con `status = AVAILABLE`, el plano la pintaba
 * LIBRE, un mesero sentaba gente nueva ahí y la cuenta anterior quedaba
 * huérfana — nadie la cobraba. Este archivo es el test que habría atrapado
 * ese bug ANTES de que llegara a un dispositivo real.
 */
class DiningTableTest {

    private fun order(
        id: String = "order-1",
        orderNumber: String = "A-100",
        total: BigDecimal = BigDecimal("603.50"),
        version: Int = 3,
    ) = TableOrder(id = id, orderNumber = orderNumber, total = total, version = version)

    private fun check(
        id: String,
        orderNumber: String = "",
        total: BigDecimal = BigDecimal.ZERO,
        version: Int = 1,
        waiterName: String? = null,
        createdAt: String? = null,
    ) = OpenCheckSummary(id = id, orderNumber = orderNumber, total = total, version = version, waiterName = waiterName, createdAt = createdAt)

    // region — hasOpenCheck: el dinero manda, en las DOS direcciones

    @Test
    fun mesa_M2_status_AVAILABLE_pero_con_una_orden_PENDING_debe_reportarse_OCUPADA() {
        // El bug real, medido en dispositivo: status miente "libre", el
        // dinero dice lo contrario.
        val m2 = DiningTable(
            id = "m2",
            number = "M2",
            status = "AVAILABLE",
            currentOrder = order(total = BigDecimal("603.50")),
        )

        assertThat(m2.hasOpenCheck).isTrue()
        assertThat(m2.isOccupied).isTrue()
        assertThat(m2.isAvailable).isFalse()
    }

    @Test
    fun status_AVAILABLE_con_openOrders_pero_sin_currentOrder_tambien_es_OCUPADA() {
        // M9-style: dos cuentas abiertas, currentOrderId nunca se llenó.
        val table = DiningTable(
            id = "m9",
            number = "M9",
            status = "AVAILABLE",
            currentOrder = null,
            openOrders = listOf(check(id = "c1"), check(id = "c2")),
        )

        assertThat(table.hasOpenCheck).isTrue()
        assertThat(table.isOccupied).isTrue()
    }

    @Test
    fun drift_inverso_status_OCCUPIED_sin_ninguna_cuenta_abierta_debe_verse_LIBRE() {
        // La otra dirección del mismo bug: el status quedó pegado en OCCUPIED
        // (p.ej. liberar la mesa falló) pero ya no hay dinero vivo. Sin este
        // fix la mesa queda huérfana para siempre — nadie puede sentar gente
        // ahí ni tocar nada.
        val table = DiningTable(
            id = "ghost",
            number = "7",
            status = "OCCUPIED",
            currentOrder = null,
            openOrders = emptyList(),
        )

        assertThat(table.hasOpenCheck).isFalse()
        assertThat(table.isAvailable).isTrue()
        assertThat(table.isOccupied).isFalse()
    }

    // endregion

    // region — RESERVED: la única ocupación legítima sin cuenta

    @Test
    fun reserved_sin_cuenta_gana_como_reservada_y_no_disponible() {
        val table = DiningTable(id = "r1", number = "9", status = "RESERVED")

        assertThat(table.isReserved).isTrue()
        assertThat(table.isAvailable).isFalse()
        assertThat(table.hasOpenCheck).isFalse()
    }

    @Test
    fun reserved_CON_cuenta_abierta_sigue_reportando_ocupada_por_hasOpenCheck() {
        // isOccupied es puro hasOpenCheck — no se apaga por RESERVED.
        val table = DiningTable(
            id = "r2",
            number = "10",
            status = "RESERVED",
            currentOrder = order(),
        )

        assertThat(table.isOccupied).isTrue()
        assertThat(table.isReserved).isTrue()
        assertThat(table.isAvailable).isFalse()
    }

    // endregion

    // region — primaryCheck: el complemento obligatorio de hasOpenCheck

    @Test
    fun primaryCheck_prefiere_la_orden_que_coincide_con_currentOrder_en_openOrders() {
        val matching = check(id = "order-1", orderNumber = "A-100", total = BigDecimal("200.00"), version = 7)
        val table = DiningTable(
            id = "t1",
            number = "1",
            currentOrder = order(id = "order-1", version = 3),
            openOrders = listOf(check(id = "otra"), matching),
        )

        // La versión/total de openOrders manda (fuente de verdad única), no
        // la del puntero currentOrder desactualizado.
        assertThat(table.primaryCheck?.id).isEqualTo("order-1")
        assertThat(table.primaryCheck?.version).isEqualTo(7)
        assertThat(table.primaryCheck?.total).isEqualTo(BigDecimal("200.00"))
    }

    @Test
    fun primaryCheck_cae_a_la_cuenta_mas_antigua_cuando_currentOrder_apunta_a_una_orden_ya_cobrada() {
        // El caso real: se cobró la mitad de un cheque dividido. El server
        // deja `currentOrderId` apuntando a la orden que YA se pagó (no
        // filtra por estado) — sin este fallback, "Pagar" mostraría algo ya
        // cobrado y el mesero no podría llegar al resto.
        val segunda = check(id = "segunda", total = BigDecimal("144.00"))
        val table = DiningTable(
            id = "t2",
            number = "2",
            currentOrder = order(id = "ya-pagada"),
            openOrders = listOf(segunda),
        )

        assertThat(table.primaryCheck?.id).isEqualTo("segunda")
    }

    @Test
    fun primaryCheck_null_cuando_no_hay_ninguna_cuenta_abierta() {
        val table = DiningTable(id = "libre", number = "3", status = "AVAILABLE")

        assertThat(table.primaryCheck).isNull()
    }

    @Test
    fun primaryCheck_construye_desde_currentOrder_si_openOrders_llega_vacio_server_viejo() {
        val table = DiningTable(
            id = "t3",
            number = "4",
            currentOrder = order(id = "solo", orderNumber = "ORD-SOLO", version = 3, total = BigDecimal("89.50")),
            openOrders = emptyList(),
        )

        assertThat(table.primaryCheck?.id).isEqualTo("solo")
        assertThat(table.primaryCheck?.orderNumber).isEqualTo("ORD-SOLO")
        assertThat(table.primaryCheck?.version).isEqualTo(3)
        assertThat(table.primaryCheck?.total).isEqualTo(BigDecimal("89.50"))
    }

    @Test
    fun primaryCheck_cae_a_openOrders_cuando_currentOrder_es_null() {
        val unica = check(id = "viva", total = BigDecimal("144.00"))
        val table = DiningTable(id = "t4", number = "5", currentOrder = null, openOrders = listOf(unica))

        assertThat(table.primaryCheck?.id).isEqualTo("viva")
    }

    // endregion

    // region — totalDisplay de OpenCheckSummary

    @Test
    fun totalDisplay_formatea_a_dos_decimales_con_signo_de_pesos() {
        val c = check(id = "x", total = BigDecimal("144"))
        assertThat(c.totalDisplay).isEqualTo("$144.00")
    }

    // endregion
}
