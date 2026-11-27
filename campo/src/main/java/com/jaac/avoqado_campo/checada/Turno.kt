package com.jaac.avoqado_campo.checada

import com.jaac.avoqado_campo.cola.TurnoLocal
import java.time.Instant
import java.time.LocalTime
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

/** Con hora de corte, el corte cae al menos esto después de la entrada (la del SERVIDOR). */
private const val HORAS_ANTES_DEL_CORTE = 1L

/**
 * La salida olvidada de UNA sucursal, como la manda `GET tpv/modules` en `asistencia`. [activa] = false: el SERVIDOR no cierra
 * turnos solo, así que el turno no vence. [corte] null: la regla de siempre (04:00 y 12 h).
 */
data class ReglaDeSalida(val activa: Boolean = true, val corte: LocalTime? = null) {
    companion object { val DE_HOY = ReglaDeSalida() }
}

/** El mismo «HH:mm» que acepta el SERVIDOR (`leerCorte`); cualquier otra cosa es la regla de fábrica. */
private val HORA_DE_CORTE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

/** Lo que llega del SERVIDOR (`salidaOlvidadaEnabled`, `salidaOlvidadaCorte` «HH:mm»); sin dato o mal escrito, la de hoy. */
fun reglaDe(activa: Boolean?, corte: String?): ReglaDeSalida {
    val hm = HORA_DE_CORTE.find(corte.orEmpty())?.groupValues
    return ReglaDeSalida(activa != false, hm?.let { LocalTime.of(it[1].toInt(), it[2].toInt()) })
}

/**
 * La regla de la sucursal de la última copia (la pone [com.jaac.avoqado_campo.copia.RepositorioDeCopia] al leerla, bajarla
 * o borrarla), para que sin red se use la última que bajó. ponytail: un solo valor del proceso, así la checada, la cola, el
 * GPS, la venta, el Inicio y «Mis pendientes» deciden con la MISMA regla sin pasarla por cada constructor; sólo conoce la
 * sucursal de la copia (un turno de otra usa la de hoy). Si hace falta la de varias a la vez, va un mapa.
 */
@Volatile private var reglaGuardada: Pair<String, ReglaDeSalida>? = null

fun recordarReglaDeSalida(venueId: String?, regla: ReglaDeSalida?) {
    reglaGuardada = if (venueId != null && regla != null) venueId to regla else null
}

fun reglaDeSalida(venueId: String?): ReglaDeSalida =
    reglaGuardada?.takeIf { it.first == venueId }?.second ?: ReglaDeSalida.DE_HOY

/**
 * «Salida olvidada» (hz:f0a89d19:D3): hasta cuándo una entrada sigue siendo «su día», igual que el SERVIDOR
 * (`limiteDelTurno`). Sin hora de corte: el siguiente 04:00 y nunca antes de 12 h. Con hora de corte («22:00»): el siguiente
 * corte que caiga al menos 1 h después de la entrada, sin el mínimo de 12 h. Apagada: no vence. Después el SERVIDOR la cierra solo.
 */
fun limiteDelTurno(abiertoAtMs: Long, zona: ZoneId = ZoneId.systemDefault(), regla: ReglaDeSalida = ReglaDeSalida.DE_HOY): Long {
    if (!regla.activa) return Long.MAX_VALUE
    val local = Instant.ofEpochMilli(abiertoAtMs).atZone(zona)
    val corte = regla.corte
    if (corte != null) {
        var c = local.toLocalDate().atTime(corte).atZone(zona)
        while (c.toInstant().toEpochMilli() < abiertoAtMs + HORAS_ANTES_DEL_CORTE * 3_600_000L) c = c.plusDays(1)
        return c.toInstant().toEpochMilli()
    }
    var c = local.toLocalDate().atTime(CORTE_DEL_DIA, 0).atZone(zona)
    if (!c.isAfter(local)) c = c.plusDays(1)
    return maxOf(c.toInstant().toEpochMilli(), abiertoAtMs + HORAS_MINIMAS * 3_600_000L)
}

/**
 * Margen del celular sobre el límite (Codex r1 #3): el celular no conoce la zona de la sucursal ni el horario de la persona,
 * y nunca debe declarar olvidado un turno ANTES que el SERVIDOR (las zonas de México difieren hasta 2 h; un nocturno con
 * horario largo alarga el límite del SERVIDOR una hora después de su salida).
 */
private const val MARGEN_DEL_CELULAR_MS = 3 * 3_600_000L

/** El límite con el que el celular decide: el del SERVIDOR más su margen. */
fun limiteEnElCelular(abiertoAtMs: Long, zona: ZoneId = ZoneId.systemDefault(), regla: ReglaDeSalida = ReglaDeSalida.DE_HOY): Long =
    limiteDelTurno(abiertoAtMs, zona, regla).let { if (it == Long.MAX_VALUE) it else it + MARGEN_DEL_CELULAR_MS }

/**
 * ¿El turno local sigue vivo a [ahoraMs]? Uno de un día ya terminado es «olvidado»: deja de ofrecer «Checar salida» y la
 * siguiente entrada se acepta (el SERVIDOR lo cerró o lo cierra con su regla). Sin hora (filas viejas) cuenta como vivo.
 * La regla es la de SU sucursal ([reglaDeSalida]).
 */
fun turnoVigente(
    t: TurnoLocal, ahoraMs: Long, zona: ZoneId = ZoneId.systemDefault(), regla: ReglaDeSalida = reglaDeSalida(t.venueId),
): Boolean = t.abierto && (t.abiertoAt == null || ahoraMs < limiteEnElCelular(t.abiertoAt, zona, regla))

/** Su turno abierto y vigente: sin él no se vende (0.6.0). Una entrada checada sin red ya cuenta (vive en Room). */
fun tieneTurno(t: TurnoLocal?, staffId: String?, ahoraMs: Long): Boolean =
    t != null && staffId != null && t.staffId == staffId && turnoVigente(t, ahoraMs)

/** El turno abierto que se quedó de un día anterior: la hora de su entrada, para decírselo a la persona; null = no hay. */
fun turnoOlvidado(t: TurnoLocal?, staffId: String?, ahoraMs: Long, zona: ZoneId = ZoneId.systemDefault()): Long? =
    t?.takeIf { it.abierto && staffId != null && it.staffId == staffId && !turnoVigente(it, ahoraMs, zona) }?.abiertoAt

/** El turno vive en Room (P3); aquí sólo se traduce. El de otra persona no cuenta (R5-2), ni el de un día ya terminado (D3). */
fun refEntrada(t: TurnoLocal?, staffId: String?, ahoraMs: Long): RefEntrada? {
    if (t == null || !tieneTurno(t, staffId, ahoraMs)) return null
    return t.timeEntryId?.let { RefEntrada.PorId(it) } ?: t.llaveEntrada?.let { RefEntrada.PorLlave(it) }
}
