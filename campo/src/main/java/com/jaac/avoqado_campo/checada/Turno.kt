package com.jaac.avoqado_campo.checada

import com.jaac.avoqado_campo.cola.EstadoPieza
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
 * turnos solo, así que el turno no vence. [corte] null: la regla de siempre (04:00 y 12 h). [zona]: la de la sucursal (Codex r3
 * #3: el SERVIDOR decide con ésa); null = la del celular.
 */
data class ReglaDeSalida(val activa: Boolean = true, val corte: LocalTime? = null, val zona: ZoneId? = null) {
    companion object { val DE_HOY = ReglaDeSalida() }
}

/** El mismo «HH:mm» que acepta el SERVIDOR (`leerCorte`); cualquier otra cosa es la regla de fábrica. */
private val HORA_DE_CORTE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

/**
 * Lo que llega del SERVIDOR (`salidaOlvidadaEnabled`, `salidaOlvidadaCorte` «HH:mm», `timezone`); sin dato o mal escrito, la de
 * hoy (y la zona del celular).
 */
fun reglaDe(activa: Boolean?, corte: String?, zona: String? = null): ReglaDeSalida {
    val hm = HORA_DE_CORTE.find(corte.orEmpty())?.groupValues
    return ReglaDeSalida(
        activa != false, hm?.let { LocalTime.of(it[1].toInt(), it[2].toInt()) }, zona?.let { runCatching { ZoneId.of(it) }.getOrNull() },
    )
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
 * (`limiteDelTurno`), en la zona de la sucursal si la mandó (si no, [zona], la del celular). Sin hora de corte: el siguiente
 * 04:00 y nunca antes de 12 h. Con hora de corte («22:00»): el siguiente corte que caiga al menos 1 h después de la entrada, sin
 * el mínimo de 12 h. Apagada: no vence. Después el SERVIDOR la cierra solo.
 */
fun limiteDelTurno(abiertoAtMs: Long, zona: ZoneId = ZoneId.systemDefault(), regla: ReglaDeSalida = ReglaDeSalida.DE_HOY): Long {
    if (!regla.activa) return Long.MAX_VALUE
    val z = regla.zona ?: zona
    val corte = regla.corte
    if (corte != null) {
        // Codex r3 #4: como el SERVIDOR, el corte de cada día se arma desde su FECHA (nunca sumando días a una hora ya corrida:
        // el salto de primavera la movería). La hora que no existe ese día pasa a la siguiente que sí; la que ocurre dos veces
        // (otoño) cuenta la primera, también como el SERVIDOR.
        val desde = abiertoAtMs + HORAS_ANTES_DEL_CORTE * 3_600_000L
        val dia = Instant.ofEpochMilli(desde).atZone(z).toLocalDate()
        fun corteDel(d: java.time.LocalDate) = d.atTime(corte).atZone(z).toInstant().toEpochMilli()
        return corteDel(dia).takeIf { it >= desde } ?: corteDel(dia.plusDays(1))
    }
    val local = Instant.ofEpochMilli(abiertoAtMs).atZone(z)
    var c = local.toLocalDate().atTime(CORTE_DEL_DIA, 0).atZone(z)
    if (!c.isAfter(local)) c = c.plusDays(1)
    return maxOf(c.toInstant().toEpochMilli(), abiertoAtMs + HORAS_MINIMAS * 3_600_000L)
}

/**
 * Margen del celular sobre el límite (Codex r1 #3): el celular no conoce el horario de la persona (ni, sin `timezone`, la zona
 * de la sucursal) y nunca debe declarar olvidado un turno ANTES que el SERVIDOR (un horario largo alarga su límite hasta una
 * hora después de la salida). Founder, 26-nov, «B» (Codex r4 #1): la app sólo exige una entrada válida; la hora exacta del
 * corte la aplica el SERVIDOR. Por eso el margen va también con hora de corte propia: con corte a las 22:00, hasta la 01:00.
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

/** Su turno abierto y vigente. Una entrada checada sin red ya cuenta (vive en Room). */
fun tieneTurno(t: TurnoLocal?, staffId: String?, ahoraMs: Long, regla: ReglaDeSalida = reglaDeSalida(t?.venueId)): Boolean =
    t != null && staffId != null && t.staffId == staffId && turnoVigente(t, ahoraMs, regla = regla)

/**
 * Sin esto no se vende en [venueId] (0.6.0): su turno vigente, de ESA sucursal (Codex r4 #3, como la salida), y que el
 * SERVIDOR no haya rechazado su entrada (Codex r3 #1: la entrada sin red rechazada sigue en el celular, en «Ventas pendientes
 * por subir»). [estadoDeSuEntrada]: el de la pieza de la entrada (`llaveEntrada`); con `timeEntryId` el SERVIDOR ya la confirmó.
 */
fun puedeVender(
    t: TurnoLocal?, staffId: String?, venueId: String?, ahoraMs: Long, estadoDeSuEntrada: String?,
    regla: ReglaDeSalida = reglaDeSalida(t?.venueId),
): Boolean = tieneTurno(t, staffId, ahoraMs, regla) && venueId != null && t?.venueId == venueId &&
    (t?.timeEntryId != null || estadoDeSuEntrada != EstadoPieza.RECHAZADA)

/** El turno abierto que se quedó de un día anterior: la hora de su entrada, para decírselo a la persona; null = no hay. */
fun turnoOlvidado(t: TurnoLocal?, staffId: String?, ahoraMs: Long, zona: ZoneId = ZoneId.systemDefault()): Long? =
    t?.takeIf { it.abierto && staffId != null && it.staffId == staffId && !turnoVigente(it, ahoraMs, zona) }?.abiertoAt

/** El turno vive en Room (P3); aquí sólo se traduce. El de otra persona no cuenta (R5-2), ni el de un día ya terminado (D3). */
fun refEntrada(t: TurnoLocal?, staffId: String?, ahoraMs: Long): RefEntrada? {
    if (t == null || !tieneTurno(t, staffId, ahoraMs)) return null
    return t.timeEntryId?.let { RefEntrada.PorId(it) } ?: t.llaveEntrada?.let { RefEntrada.PorLlave(it) }
}
