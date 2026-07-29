package com.jaac.avoqado_tpv.features.tables.data

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * `TableSession` (Plan C, Task 5) — la sesión de mesa activa (singleton),
 * offline-first: [TableSession.open] arranca PROVISIONAL con un UUID local;
 * [TableSession.promoteProvisional] lo intercambia por el id real cuando
 * llega el ack de OPEN_TABLE (vía `TableSyncCoordinator`).
 *
 * Sin dependencias (`@Inject constructor()` vacío) — se instancia directo,
 * sin mockk.
 */
class TableSessionTest {

    private lateinit var session: TableSession

    @Before
    fun setUp() {
        session = TableSession()
    }

    // region — estado inicial / start / clear

    @Test
    fun sin_sesion_activa_current_es_null() {
        assertThat(session.current()).isNull()
    }

    @Test
    fun start_arranca_directo_con_un_Active_completo() {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-1", version = 3, total = BigDecimal("250.0")))

        val active = session.current()
        assertThat(active).isNotNull()
        assertThat(active!!.orderId).isEqualTo("orden-real-1")
        assertThat(active.isProvisional).isFalse()
        assertThat(active.version).isEqualTo(3)
        assertThat(active.total).isEqualTo(BigDecimal("250.0"))
    }

    @Test
    fun clear_deja_la_sesion_en_null() {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-real-1"))

        session.clear()

        assertThat(session.current()).isNull()
    }

    // endregion

    // region — open(): arranque PROVISIONAL offline-first

    @Test
    fun open_crea_una_sesion_provisional_con_el_uuid_local() {
        session.open(tableId = "mesa-1", localOrderId = "local-uuid-123", tableNumber = "5", areaName = "Terraza")

        val active = session.current()
        assertThat(active).isNotNull()
        assertThat(active!!.orderId).isEqualTo("local-uuid-123")
        assertThat(active.isProvisional).isTrue()
        assertThat(active.tableId).isEqualTo("mesa-1")
        assertThat(active.tableNumber).isEqualTo("5")
        assertThat(active.areaName).isEqualTo("Terraza")
        assertThat(active.mode).isEqualTo(TableSession.Mode.ORDERING)
    }

    // endregion

    // region — promoteProvisional(): el ack de OPEN_TABLE promueve el uuid local al orderId real

    @Test
    fun el_ack_de_OPEN_TABLE_promueve_el_uuid_local_al_orderId_real() {
        // Sin esto, la mesa abierta offline queda con un id que el server no
        // conoce y el cobro apuntaría a la nada — literal del plan (Task 5, Step 1).
        val localId = "local-uuid-123"
        session.open(tableId = "mesa-1", localOrderId = localId)

        session.promoteProvisional(localOrderId = localId, orderId = "orden-real-456", orderNumber = "A-100", version = 2)

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("orden-real-456")
        assertThat(active.orderNumber).isEqualTo("A-100")
        assertThat(active.version).isEqualTo(2)
        assertThat(active.isProvisional).isFalse()
    }

    @Test
    fun promoteProvisional_con_localOrderId_que_no_coincide_es_no_op() {
        // La sesión activa es OTRA mesa (se cerró/reabrió mientras el intent
        // viajaba) — promover aquí pisaría una sesión que no le corresponde.
        session.open(tableId = "mesa-1", localOrderId = "local-uuid-A")

        session.promoteProvisional(localOrderId = "local-uuid-B", orderId = "orden-de-otra-mesa", orderNumber = null, version = 1)

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("local-uuid-A")
        assertThat(active.isProvisional).isTrue()
    }

    @Test
    fun promoteProvisional_sin_sesion_activa_no_truena() {
        session.promoteProvisional(localOrderId = "local-uuid-A", orderId = "orden-real", orderNumber = null, version = 1)

        assertThat(session.current()).isNull()
    }

    @Test
    fun promoteProvisional_conserva_el_orderNumber_previo_si_el_ack_no_manda_uno() {
        // Sesión provisional que YA traía un orderNumber (p.ej. sembrado por la
        // UI de forma optimista) — un ack de promoción sin `orderNumber` en su
        // `result` no debe borrarlo.
        session.start(
            TableSession.Active(tableId = "mesa-1", orderId = "local-uuid-A", orderNumber = "A-1", isProvisional = true),
        )

        session.promoteProvisional(localOrderId = "local-uuid-A", orderId = "orden-real", orderNumber = null, version = 2)

        val active = session.current()!!
        assertThat(active.orderId).isEqualTo("orden-real")
        assertThat(active.orderNumber).isEqualTo("A-1")
        assertThat(active.isProvisional).isFalse()
    }

    // endregion

    // region — updateVersion / updateTotal

    @Test
    fun updateVersion_actualiza_solo_la_version() {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 1, total = BigDecimal("100.0")))

        session.updateVersion(7)

        assertThat(session.current()!!.version).isEqualTo(7)
        assertThat(session.current()!!.total).isEqualTo(BigDecimal("100.0"))
    }

    @Test
    fun updateVersion_sin_sesion_activa_no_truena() {
        session.updateVersion(9)

        assertThat(session.current()).isNull()
    }

    @Test
    fun updateTotal_actualiza_solo_el_total_pesos() {
        session.start(TableSession.Active(tableId = "mesa-1", orderId = "orden-1", version = 4, total = BigDecimal("500.0")))

        session.updateTotal(BigDecimal("125.5"))

        assertThat(session.current()!!.total).isEqualTo(BigDecimal("125.5"))
        assertThat(session.current()!!.version).isEqualTo(4)
    }

    // endregion

    // region — buildAddItemsPayload(): los intents posteriores usan el orderId ya promovido

    @Test
    fun buildAddItemsPayload_usa_localOrderId_mientras_la_sesion_sigue_provisional() {
        // Sin esto, un ADD_ITEMS encolado justo después de un OPEN_TABLE
        // offline viajaría con `orderId = <uuid-local>`, que el reducer del
        // server trataría como un id real (inexistente) en vez de resolverlo
        // vía el localRefMap del batch.
        session.open(tableId = "mesa-1", localOrderId = "local-uuid-123")

        val payload = session.buildAddItemsPayload(listOf(AddOrderItemRequest(productId = "prod-1", quantity = 2)))

        assertThat(payload["localOrderId"].asString).isEqualTo("local-uuid-123")
        assertThat(payload.has("orderId")).isFalse()
        assertThat(payload["items"].asJsonArray).hasSize(1)
    }

    @Test
    fun los_intents_posteriores_usan_el_orderId_ya_promovido() {
        // Literal del plan (Task 5, Step 1) — tras el ack de OPEN_TABLE, el
        // payload de la siguiente ronda usa el id REAL, no el UUID local.
        val localId = "local-uuid-123"
        session.open(tableId = "mesa-1", localOrderId = localId)
        session.promoteProvisional(localOrderId = localId, orderId = "orden-real-456", orderNumber = null, version = 1)

        val payload = session.buildAddItemsPayload(listOf(AddOrderItemRequest(productId = "prod-1", quantity = 1)))

        assertThat(payload["orderId"].asString).isEqualTo("orden-real-456")
        assertThat(payload.has("localOrderId")).isFalse()
    }

    @Test(expected = IllegalStateException::class)
    fun buildAddItemsPayload_sin_sesion_activa_truena_en_vez_de_mandar_un_payload_invalido() {
        session.buildAddItemsPayload(emptyList())
    }

    // endregion
}
