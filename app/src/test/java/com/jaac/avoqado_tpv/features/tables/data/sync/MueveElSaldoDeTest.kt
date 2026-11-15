package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import org.junit.Test

/**
 * D8 · [mueveElSaldoDe]: qué intents pendientes impiden tomar una lectura de la cuenta. Codex r2 #2: un pendiente que no cambia
 * lo que se debe (cambiar de mesero o de mesa) no puede dejar al cajero sin la lectura buena.
 */
class MueveElSaldoDeTest {

    private val cuentas = setOf("orden-1", "local-1")

    private fun intent(type: String, payload: String) =
        SyncIntentEntity(id = "i-$type", venueId = "venue-1", seq = 1, type = type, payloadJson = payload)

    @Test
    fun una_ronda_un_cobro_un_descuento_o_los_comensales_de_esta_cuenta_si_la_mueven() {
        assertThat(intent(SyncIntentTypes.ADD_ITEMS, """{"orderId":"orden-1","items":[]}""").mueveElSaldoDe(cuentas)).isTrue()
        assertThat(intent(SyncIntentTypes.PAY_CASH, """{"localOrderId":"local-1","amountCents":1000}""").mueveElSaldoDe(cuentas)).isTrue()
        assertThat(intent(SyncIntentTypes.APPLY_DISCOUNT, """{"orderId":"orden-1","discountId":"d"}""").mueveElSaldoDe(cuentas)).isTrue()
        assertThat(intent(SyncIntentTypes.UPDATE_DETAILS, """{"orderId":"orden-1","covers":6}""").mueveElSaldoDe(cuentas)).isTrue()
        assertThat(intent("UN_TIPO_NUEVO", """{"orderId":"orden-1"}""").mueveElSaldoDe(cuentas)).isTrue()
    }

    @Test
    fun cambiar_de_mesero_o_de_mesa_abrir_o_liberar_no_la_mueven() {
        assertThat(intent(SyncIntentTypes.ASSIGN_ORDER, """{"orderId":"orden-1","staffId":"s"}""").mueveElSaldoDe(cuentas)).isFalse()
        assertThat(intent(SyncIntentTypes.MOVE_ORDER, """{"orderId":"orden-1","tableId":"t"}""").mueveElSaldoDe(cuentas)).isFalse()
        assertThat(intent(SyncIntentTypes.CLEAR_TABLE, """{"tableId":"t","orderId":"orden-1"}""").mueveElSaldoDe(cuentas)).isFalse()
        assertThat(intent(SyncIntentTypes.OPEN_TABLE, """{"tableId":"t","localOrderId":"local-1"}""").mueveElSaldoDe(cuentas)).isFalse()
    }

    @Test
    fun lo_de_otra_cuenta_o_un_payload_ilegible_no_cuenta() {
        assertThat(intent(SyncIntentTypes.ADD_ITEMS, """{"orderId":"orden-2"}""").mueveElSaldoDe(cuentas)).isFalse()
        assertThat(intent(SyncIntentTypes.PAY_CASH, "no es json").mueveElSaldoDe(cuentas)).isFalse()
    }
}
