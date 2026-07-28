package com.jaac.avoqado_tpv.features.tables.data.sync

/**
 * Espejo EXACTO de `SyncIntentType` en el server
 * (`avoqado-server/src/services/mobile/sync.mobile.service.ts:38-52`, confirmado
 * 2026-07-28 — 14 tipos). Un desfase falla EN SILENCIO: el server rechaza el tipo
 * desconocido con `UNKNOWN_INTENT_TYPE` (cuarentena, no se pierde), pero un tipo
 * que el server agregó y la TPV no conoce simplemente nunca se puede encolar
 * desde acá.
 *
 * `SyncIntentTypesTest.los_tipos_de_intent_son_espejo_exacto_del_server` es la
 * red: otra sesión sigue tocando `sync.mobile.service.ts` en paralelo — al
 * ejecutar cualquier tarea que dependa de esto, releer la fuente, no esta lista.
 */
object SyncIntentTypes {
    const val OPEN_TABLE = "OPEN_TABLE"
    const val ADD_ITEMS = "ADD_ITEMS"
    const val PAY_CASH = "PAY_CASH"
    const val APPLY_DISCOUNT = "APPLY_DISCOUNT"
    const val APPLY_SERVICE_CHARGE = "APPLY_SERVICE_CHARGE"
    const val COMP_ORDER = "COMP_ORDER"
    const val UPDATE_DETAILS = "UPDATE_DETAILS"
    const val CANCEL_ORDER = "CANCEL_ORDER"
    const val MOVE_ORDER = "MOVE_ORDER"
    const val ASSIGN_ORDER = "ASSIGN_ORDER"
    const val CLEAR_TABLE = "CLEAR_TABLE"
    const val SPLIT_ORDER = "SPLIT_ORDER"
    /** Tipo propio — NO es un flag de [SPLIT_ORDER]. "Dividir por puesto", atómico. */
    const val SPLIT_BY_SEAT = "SPLIT_BY_SEAT"
    const val MERGE_ORDERS = "MERGE_ORDERS"

    val ALL: Set<String> = setOf(
        OPEN_TABLE, ADD_ITEMS, PAY_CASH, APPLY_DISCOUNT, APPLY_SERVICE_CHARGE,
        COMP_ORDER, UPDATE_DETAILS, CANCEL_ORDER, MOVE_ORDER, ASSIGN_ORDER,
        CLEAR_TABLE, SPLIT_ORDER, SPLIT_BY_SEAT, MERGE_ORDERS,
    )
}
