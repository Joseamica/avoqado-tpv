package com.jaac.avoqado_campo.checada

import com.jaac.avoqado_campo.cola.TurnoLocal
import java.time.Instant
import java.time.ZoneId

/** La entrada abierta de ESTE celular que la salida nombra (B10). */
sealed interface RefEntrada {
    data class PorLlave(val llave: String) : RefEntrada
    data class PorId(val timeEntryId: String) : RefEntrada
}

/** Corte del día de negocio, hora local: el mismo del SERVIDOR (`BUSINESS_DAY_START_HOUR`). */
private const val CORTE_DEL_DIA = 4
/** Un turno nunca se da por olvidado antes de estas horas (turnos nocturnos que cruzan el corte). */
private const val HORAS_MINIMAS = 12L

/**
 * «Salida olvidada» (hz:f0a89d19:D3): hasta cuándo una entrada sigue siendo «su día», igual que el SERVIDOR
 * (`limiteDelTurno`): el siguiente corte de las 04:00 y nunca antes de 12 h. Después de eso el SERVIDOR la cierra solo.
 */
fun limiteDelTurno(abiertoAtMs: Long, zona: ZoneId = ZoneId.systemDefault()): Long {
    val local = Instant.ofEpochMilli(abiertoAtMs).atZone(zona)
    var corte = local.toLocalDate().atTime(CORTE_DEL_DIA, 0).atZone(zona)
    if (!corte.isAfter(local)) corte = corte.plusDays(1)
    return maxOf(corte.toInstant().toEpochMilli(), abiertoAtMs + HORAS_MINIMAS * 3_600_000L)
}

/**
 * ¿El turno local sigue vivo a [ahoraMs]? Uno de un día ya terminado es «olvidado»: deja de ofrecer «Checar salida» y la
 * siguiente entrada se acepta (el SERVIDOR lo cerró o lo cierra con su regla). Sin hora (filas viejas) cuenta como vivo.
 */
fun turnoVigente(t: TurnoLocal, ahoraMs: Long, zona: ZoneId = ZoneId.systemDefault()): Boolean =
    t.abierto && (t.abiertoAt == null || ahoraMs < limiteDelTurno(t.abiertoAt, zona))

/** El turno abierto que se quedó de un día anterior: la hora de su entrada, para decírselo a la persona; null = no hay. */
fun turnoOlvidado(t: TurnoLocal?, staffId: String?, ahoraMs: Long, zona: ZoneId = ZoneId.systemDefault()): Long? =
    t?.takeIf { it.abierto && staffId != null && it.staffId == staffId && !turnoVigente(it, ahoraMs, zona) }?.abiertoAt

/** El turno vive en Room (P3); aquí sólo se traduce. El de otra persona no cuenta (R5-2), ni el de un día ya terminado (D3). */
fun refEntrada(t: TurnoLocal?, staffId: String?, ahoraMs: Long): RefEntrada? {
    if (t == null || staffId == null || t.staffId != staffId || !turnoVigente(t, ahoraMs)) return null
    return t.timeEntryId?.let { RefEntrada.PorId(it) } ?: t.llaveEntrada?.let { RefEntrada.PorLlave(it) }
}
