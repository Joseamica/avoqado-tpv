package com.jaac.avoqado_campo.checada

import com.jaac.avoqado_campo.cola.TurnoLocal

/** La entrada abierta de ESTE celular que la salida nombra (B10). */
sealed interface RefEntrada {
    data class PorLlave(val llave: String) : RefEntrada
    data class PorId(val timeEntryId: String) : RefEntrada
}

/** El turno vive en Room (P3); aquí sólo se traduce. El de otra persona no cuenta (R5-2). */
fun refEntrada(t: TurnoLocal?, staffId: String?): RefEntrada? {
    if (t == null || staffId == null || t.staffId != staffId) return null
    return t.timeEntryId?.let { RefEntrada.PorId(it) } ?: t.llaveEntrada?.let { RefEntrada.PorLlave(it) }
}
