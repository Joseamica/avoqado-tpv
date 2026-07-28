package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Espejo de tipos — la red contra el reducer del server. Fuente:
 * `avoqado-server/src/services/mobile/sync.mobile.service.ts:38-52` (`SyncIntentType`
 * union + `KNOWN_TYPES`), confirmada línea por línea 2026-07-28. Otra sesión sigue
 * tocando ese archivo en paralelo — si este test alguna vez falla, el primer paso es
 * releer la fuente, NUNCA asumir que esta lista quedó desactualizada por error.
 */
class SyncIntentTypesTest {

    @Test
    fun los_tipos_de_intent_son_espejo_exacto_del_server() {
        // Copiado literal de sync.mobile.service.ts:38-52 — no de offline-first-y-hub-lan.md
        // ni del plan, que son documentación derivada y pueden quedar atrás de la fuente.
        val delServer = setOf(
            "OPEN_TABLE", "ADD_ITEMS", "PAY_CASH", "APPLY_DISCOUNT", "APPLY_SERVICE_CHARGE",
            "COMP_ORDER", "UPDATE_DETAILS", "CANCEL_ORDER", "MOVE_ORDER", "ASSIGN_ORDER",
            "CLEAR_TABLE", "SPLIT_ORDER", "SPLIT_BY_SEAT", "MERGE_ORDERS",
        )

        assertThat(SyncIntentTypes.ALL).isEqualTo(delServer)
    }

    @Test
    fun son_14_tipos_sin_duplicados_colapsados_en_el_set() {
        // Defensa contra un bug silencioso: si dos constantes compartieran el mismo
        // valor de string por error de copy-paste, el Set las colapsaría a una sola
        // y el test de arriba podría seguir en verde por casualidad si además faltara
        // otro tipo distinto. Ancla el conteo exacto, no solo la igualdad de sets.
        assertThat(SyncIntentTypes.ALL).hasSize(14)
    }

    @Test
    fun split_by_seat_es_tipo_propio_no_un_flag_de_split_order() {
        // Trampa documentada en el plan: SPLIT_BY_SEAT es su propia entrada, no una
        // variante/flag de SPLIT_ORDER.
        assertThat(SyncIntentTypes.SPLIT_BY_SEAT).isNotEqualTo(SyncIntentTypes.SPLIT_ORDER)
        assertThat(SyncIntentTypes.ALL).containsAtLeast(SyncIntentTypes.SPLIT_ORDER, SyncIntentTypes.SPLIT_BY_SEAT)
    }
}
