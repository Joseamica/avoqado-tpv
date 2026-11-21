package com.avoqado.cobropropio.libreta

import android.content.Context
import android.os.SystemClock
import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface Arbitraje {
    data object Vigente : Arbitraje
    data object Tardia : Arbitraje
    data class YaResuelta(val fila: IntentoEntity) : Arbitraje
    data object Comprometido : Arbitraje
}

/**
 * I3: la fila de la libreta no se puede leer (Room no la convierte, o no pasa la validación de hoy). Mensaje FIJO y sin la
 * excepción original como causa: Room pone el valor crudo en su mensaje (Codex r4 #3). Quien la recibe nunca da un desenlace.
 *
 * Pública porque [Libreta.buscar] la lanza (fase 3, bloque 1). Quien la atrape trata ese folio como «por confirmar», nunca
 * como un desenlace (ni aprobado, ni rechazado, ni «no se cobró», ni «no salió»), y no muestra ni registra la fila. Sólo el
 * módulo la construye.
 */
public class FilaIlegible internal constructor() : IllegalStateException("fila ilegible")

/** `FolioUsado`: ese id ya tenía fila (venta o cancelación) y no se tocó; se contesta desde ella (regla N). */
internal enum class ResultadoDeAnotarCancelacion { Anotada, YaExiste, SinVentaAprobada, OtroAmbito, FolioUsado }

/**
 * `YaNoAplica`: la PROPIA fila ya no puede salir (no está, no es cancelación, no está `ANOTADO` o la cortaron). `SinVentaAprobada`:
 * la original no existe o ya no es una venta aprobada. `YaExiste`: otra cancelación viva de la misma original (r1 C).
 */
internal enum class ResultadoDeEnviarCancelacion { Enviada, OtraTarjeta, LecturaRepetida, OtroAmbito, YaNoAplica, SinVentaAprobada, YaExiste }

/** Lo único de la lectura que se guarda al salir al banco: formas verificadas y huellas. Amex no gasta contador. */
internal data class DatosDelEnvio(
    val ksnBase: String?,
    val contadorUsado: Long?,
    val marca: Marca?,
    val medio: Medio?,
    val bin: String?,
    val ultimos4: String?,
    val paisEmisor: String?,
    val huellaTarjeta: String?,
    val huellaLectura: String?,
)

/**
 * Lo que la app manda alarmar (otro bloque). `reportePendienteMasViejo` es la hora observada del banco (pared), no la de la cola.
 * Un aviso sin hora nunca se entrega (`ResultadoDelReverso.deLaFila` da null): va aparte en `reportesSinHora`.
 * `filasIlegibles` cuenta las que tienen trabajo abierto (en negativo, Codex r1 #6) y que Room no lee o no pasan la validación.
 */
public data class SaludDeLaLibreta(
    val reportesPendientes: Int,
    val reportePendienteMasViejo: Instant?,
    val reportesSinHora: Int,
    val filasIlegibles: Int,
)

/**
 * La libreta durable del cobro propio. Cada método de escritura es UNA transacción: o queda todo o no queda nada, también
 * si el proceso muere a la mitad. Las transiciones son CAS sobre la fila (el estado esperado se vuelve a leer DENTRO de la
 * transacción). Nunca guarda PAN, banda, tags EMV ni llaves: cada escritura revisa la forma de los campos permitidos y
 * truena sin el valor en el mensaje.
 *
 * Una libreta tiene un solo motor dueño ([tomarDueno]), que vive todo el proceso. `procesoId` es uno por proceso de Android:
 * las filas de un proceso muerto ya no pueden reversar (los datos de la tarjeta vivían en su RAM) y [recuperarAlArrancar]
 * les cierra el envío.
 *
 * r2 familia A: la única entrada pública es [abrir] (una por archivo en el proceso), y por fuera del módulo sólo se lee
 * ([buscar], [pendientesDeReporte], [salud]). Toda escritura, el constructor y [cerrar] son `internal`: ninguna fila cambia sin pasar
 * por el motor, y nadie cierra ni reabre la libreta debajo de un motor vivo.
 */
public class Libreta internal constructor(
    /** A3: con la base, cualquiera escribe una fila sin el motor; `@JvmSynthetic` la esconde también de Java. */
    @get:JvmSynthetic internal val db: LibretaDatabase,
    private val ahora: () -> Long,
    private val relojDePared: () -> Instant = Instant::now,
    /** Sólo las pruebas lo cambian, para simular otro proceso. */
    internal val procesoId: String = PROCESO_ID,
    /** Sólo las pruebas lo usan: corre DENTRO de cada transacción de escritura, antes del commit. */
    private val antesDelCommit: (operacion: String) -> Unit = {},
) {
    private val dao = db.dao()
    private val dueno = AtomicBoolean(false)

    /** El motor lo llama en su constructor: un segundo motor sobre la misma libreta truena. */
    internal fun tomarDueno() {
        check(dueno.compareAndSet(false, true)) { "la libreta ya tiene un motor dueño" }
    }

    /** Sólo una VENTA: una cancelación entra por [anotarCancelacion], que veta una segunda cancelación viva. */
    internal fun anotar(i: IntentoEntity): Boolean {
        require(i.operacion == Operacion.VENTA) { "una cancelación se anota con anotarCancelacion" }
        return escribir("anotar") { anotarDentro(i) }
    }

    /**
     * La original: `VENTA` + `APROBADO` + `operationNumber`, mismo ámbito y mismo venue; una sola cancelación viva a la vez.
     * El importe, el origen y `vinculado` de la fila salen de la original (quien llama no los sabe).
     *
     * T9, regla N: el folio es de un solo uso. Si ya tiene fila, `FolioUsado` sin tocarla. Si no se admite, su fila queda
     * `NO_SALIO` con su motivo en ESTA transacción, y nunca puede salir después. Esa fila conserva `operacionOriginal` y toma
     * el importe de la original si la original existe y es del mismo venue y ámbito (aunque no sea una venta aprobada); si no,
     * queda en 0 (R14-5).
     */
    internal fun anotarCancelacion(i: IntentoEntity, ambito: String): ResultadoDeAnotarCancelacion = escribir("anotarCancelacion") {
        require(i.operacion == Operacion.CANCELACION && i.operacionOriginal != null) { "no es una cancelación" }
        if (fila(i.attemptId) != null) return@escribir ResultadoDeAnotarCancelacion.FolioUsado
        val o = fila(i.operacionOriginal)
        val r = when {
            o == null || !esVentaAprobada(o) -> ResultadoDeAnotarCancelacion.SinVentaAprobada
            o.ambito() != ambito || i.ambito() != ambito || o.venueId != i.venueId -> ResultadoDeAnotarCancelacion.OtroAmbito
            cancelacionesDe(o.attemptId).any { it.estado in VETAN_OTRA_CANCELACION } -> ResultadoDeAnotarCancelacion.YaExiste
            else -> ResultadoDeAnotarCancelacion.Anotada
        }
        // R14-5: el importe y el origen de la original sólo si es del MISMO venue y ámbito: nunca se le enseñan a otra sucursal.
        val mismaCasa = o != null && o.ambito() == ambito && i.ambito() == ambito && o.venueId == i.venueId
        val n = if (mismaCasa) i.copy(importeCentavos = o!!.importeCentavos, vinculado = o.vinculado, origen = o.origen) else i
        val motivo = when (r) {
            ResultadoDeAnotarCancelacion.Anotada -> return@escribir r.also { check(anotarDentro(n)) }
            ResultadoDeAnotarCancelacion.SinVentaAprobada -> MotivoNoSalio.SIN_VENTA_APROBADA
            ResultadoDeAnotarCancelacion.OtroAmbito -> MotivoNoSalio.OTRO_AMBITO
            else -> MotivoNoSalio.CANCELACION_YA_EXISTE
        }
        val t = ahora()
        val cerrada = n.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = motivo, procesoId = procesoId, creadoEnMs = t, actualizadoEnMs = t)
        check(dao.insertar(cerrada.also(::validar)) != -1L)
        r
    }

    /** El mismo CAS `ANOTADO → ENVIADO` que [marcarEnviado], que además vuelve a revisar la original y las huellas (r3 R3-07). */
    internal fun marcarCancelacionEnviada(id: AttemptId, t0: Long, datos: DatosDelEnvio): ResultadoDeEnviarCancelacion =
        escribir("marcarCancelacionEnviada") {
            val c = fila(id)
            val o = c?.operacionOriginal?.let { fila(it) }
            when {
                c == null || c.operacion != Operacion.CANCELACION || c.estado != EstadoDelIntento.ANOTADO ||
                    c.cortarAntesDeSalir -> ResultadoDeEnviarCancelacion.YaNoAplica
                o == null || !esVentaAprobada(o) -> ResultadoDeEnviarCancelacion.SinVentaAprobada
                // Segundo candado: otra cancelación viva de la misma original (aunque haya entrado sin anotarCancelacion).
                cancelacionesDe(o.attemptId).any { it.attemptId != c.attemptId && it.estado in VETAN_OTRA_CANCELACION } ->
                    ResultadoDeEnviarCancelacion.YaExiste
                o.ambito() != c.ambito() || o.venueId != c.venueId -> ResultadoDeEnviarCancelacion.OtroAmbito
                datos.huellaTarjeta == null || datos.huellaTarjeta != o.huellaTarjeta -> ResultadoDeEnviarCancelacion.OtraTarjeta
                // Ronda 2 · S (R15-2): una lectura ya enviada no vuelve a salir en NINGUNA cancelación (otra fila cualquiera).
                datos.huellaLectura == null || dao.existeLectura(datos.huellaLectura, c.attemptId) ->
                    ResultadoDeEnviarCancelacion.LecturaRepetida
                else -> {
                    guardar(enviar(c, t0, datos))
                    ResultadoDeEnviarCancelacion.Enviada
                }
            }
        }

    /**
     * ¿Queda en ESTE proceso algo que todavía pueda necesitar las llaves de hoy? Un `ENVIADO`, o un reverso `COMPROMETIDO`,
     * `RECLAMADO` o `NO_CONCLUYENTE` cuyo `t0 + techo` no ha pasado (r3 R3-04, r5 R5-01). Las filas de otro proceso no cuentan.
     * El motor pasa como `techo` el techo del reverso MÁS una vuelta (`Relojes.finDelVeto`, r2 familia E).
     */
    internal fun hayIntentosQuePuedenNecesitarReverso(procesoActual: String, ahora: Long, techo: Long): Boolean =
        dao.hayQuePuedenNecesitarReverso(procesoActual, ahora, techo)

    /**
     * El contador DUKPT por base canónica, guardado ANTES de cifrar: `max(siguiente, ultimoDeBlumon + 1)` con histórico, y
     * `ultimoDeBlumon + SALTO_SIN_HISTORIAL` sin él. `null` = agotado (`CONTADOR_AGOTADO`), y no se guarda nada.
     */
    internal fun reservarContador(ksnBase: ByteArray, ultimoDeBlumon: Long): Long? = escribir("reservarContador") {
        val base = Dukpt.baseCanonica(ksnBase)
        val previo = dao.contador(base)
        val c = when {
            ultimoDeBlumon !in 0L..CONTADOR_MAXIMO -> null
            previo == null -> ultimoDeBlumon + SALTO_SIN_HISTORIAL
            else -> maxOf(previo.siguiente, ultimoDeBlumon + 1)
        }
        c?.takeIf { it <= CONTADOR_MAXIMO }?.also { dao.guardarContador(ContadorEntity(base, it + 1)) }
    }

    /**
     * El veto durable de un corte (regla C), en UNA transacción que compite por CAS con `ANOTADO → ENVIADO`. Sin fila, inserta
     * `i` ya como `NO_SALIO(INTERRUMPIDO)`; con fila `ANOTADO`, la pasa a eso; con fila `NO_SALIO`, ya está. `true` = ese folio
     * nunca sale. Cualquier otro estado ⇒ `false`, sin tocar nada. Un `anotar` que llega después pierde contra la fila.
     */
    internal fun vetarPorCorte(i: IntentoEntity): Boolean = escribir("vetarPorCorte") {
        val f = fila(i.attemptId)
        when {
            f == null -> {
                // T9: también una cancelación sin fila (sin original leída, con importe 0: nunca sale).
                val t = ahora()
                dao.insertar(vetada(i).copy(procesoId = procesoId, creadoEnMs = t, actualizadoEnMs = t).also(::validar)) != -1L
            }
            f.estado == EstadoDelIntento.ANOTADO -> guardar(vetada(f)).let { true }
            else -> f.estado == EstadoDelIntento.NO_SALIO
        }
    }

    private fun vetada(f: IntentoEntity) =
        f.copy(cortarAntesDeSalir = true, estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO)

    /** t0: el CAS `ANOTADO → ENVIADO` de una VENTA. Falla si se pidió cortar. Una cancelación va por [marcarCancelacionEnviada]. */
    internal fun marcarEnviado(id: AttemptId, t0: Long, datos: DatosDelEnvio): Boolean = escribir("marcarEnviado") {
        val f = fila(id)
        if (f == null || f.operacion != Operacion.VENTA || f.estado != EstadoDelIntento.ANOTADO || f.cortarAntesDeSalir) false
        else {
            guardar(enviar(f, t0, datos))
            true
        }
    }

    /** CAS genérico: aplica `cambio` sólo si la fila sigue en `desde`. Regresa la fila nueva, o null si no aplicó. */
    internal fun transicionar(id: AttemptId, desde: EstadoDelIntento, cambio: (IntentoEntity) -> IntentoEntity): IntentoEntity? =
        escribir("transicionar") {
            fila(id)?.takeIf { it.estado == desde }?.let { f ->
                val n = cambio(f)
                require(n.attemptId == f.attemptId) { "transicionar no cambia el id" }
                guardar(n)
            }
        }

    /**
     * Antes del compromiso, la aprobación cuenta (`Vigente`); después sólo se guarda como evidencia (`Tardia`). Si el
     * SERVIDOR ya la había aprobado («ya cobrado», sin folio del banco), el folio del banco se guarda como evidencia y sigue
     * `YaResuelta` sin cambiar el estado (r1 R1-6).
     */
    internal fun guardarAprobacion(id: AttemptId, operationNumber: Long, autorizacion: String?): Arbitraje = escribir("guardarAprobacion") {
        val f = filaQueExiste(id)
        require(f.operacion == Operacion.VENTA) { "sólo una venta se aprueba" }
        when {
            f.estado == EstadoDelIntento.APROBADO && f.origenDeLaAprobacion == OrigenDeLaAprobacion.SERVIDOR &&
                f.evidenciaTardiaOperacion == null && f.evidenciaTardiaAutorizacion == null ->
                Arbitraje.YaResuelta(guardar(f.copy(evidenciaTardiaOperacion = operationNumber, evidenciaTardiaAutorizacion = autorizacion)))
            f.estado in RESUELTOS -> Arbitraje.YaResuelta(f)
            f.estado == EstadoDelIntento.ENVIADO && f.reverso == EstadoDelReverso.NINGUNO -> {
                guardar(
                    f.copy(
                        estado = EstadoDelIntento.APROBADO, operationNumber = operationNumber, autorizacion = autorizacion,
                        origenDeLaAprobacion = OrigenDeLaAprobacion.BANCO,
                    ),
                )
                Arbitraje.Vigente
            }
            else -> {
                if (f.evidenciaTardiaOperacion == null) {
                    guardar(f.copy(evidenciaTardiaOperacion = operationNumber, evidenciaTardiaAutorizacion = autorizacion))
                }
                Arbitraje.Tardia
            }
        }
    }

    /** Igual que [guardarAprobacion]: un rechazo después del compromiso no cambia nada y da `Tardia`. */
    internal fun guardarRechazo(id: AttemptId, codigo: String, leyenda: String): Arbitraje = escribir("guardarRechazo") {
        val f = filaQueExiste(id)
        when {
            f.estado in RESUELTOS -> Arbitraje.YaResuelta(f)
            f.estado == EstadoDelIntento.ENVIADO && f.reverso == EstadoDelReverso.NINGUNO -> {
                guardar(f.copy(estado = EstadoDelIntento.RECHAZADO, codigo = codigo, leyenda = leyenda))
                Arbitraje.Vigente
            }
            else -> Arbitraje.Tardia
        }
    }

    /**
     * Un aprobado parcial (ISO 10) que llegó después del arbitraje (r2 R2-3): su folio en `evidenciaTardiaOperacion` y su importe
     * en `parcialImporteCentavos`, ya cotejados (familia C), si están vacíos. Nunca cambia el estado ni el reverso.
     * ponytail: el importe tardío comparte columna con el de a tiempo (el de a tiempo trae `parcialOperacion`); sin folio no se
     * sabe cuándo llegó. Si la fase 3 lo necesita, una columna propia con su migración.
     */
    internal fun guardarParcialTardio(id: AttemptId, operationNumber: Long?, importeCentavos: Long?): IntentoEntity? =
        escribir("guardarParcialTardio") {
            fila(id)?.let { f ->
                val n = f.copy(
                    evidenciaTardiaOperacion = f.evidenciaTardiaOperacion ?: operationNumber,
                    parcialImporteCentavos = f.parcialImporteCentavos ?: importeCentavos,
                )
                if (n == f) f else guardar(n)
            }
        }

    /**
     * El arbitraje, atómico (r4 R4-13): `Comprometido` sólo para quien compromete una venta `ENVIADO` de este proceso sin
     * respuesta guardada; si no, `YaResuelta` con la fila tal como está (la aprobación o el rechazo que ganó).
     */
    internal fun comprometerAReversar(id: AttemptId): Arbitraje = escribir("comprometerAReversar") {
        val f = filaQueExiste(id)
        if (f.operacion == Operacion.VENTA && f.estado == EstadoDelIntento.ENVIADO && f.reverso == EstadoDelReverso.NINGUNO &&
            f.procesoId == procesoId
        ) {
            guardar(f.copy(reverso = EstadoDelReverso.COMPROMETIDO))
            Arbitraje.Comprometido
        } else {
            Arbitraje.YaResuelta(f)
        }
    }

    /** El `Concedido` del SERVIDOR, durable ANTES del primer POST de reverso (r4 R4-08): el vínculo queda acreditado. */
    internal fun guardarPermisoConcedido(id: AttemptId): Boolean = escribir("guardarPermisoConcedido") {
        val f = fila(id)
        if (f?.reverso != EstadoDelReverso.COMPROMETIDO) false
        else {
            if (!f.vinculoAcreditadoPorPermiso) guardar(f.copy(vinculoAcreditadoPorPermiso = true))
            true
        }
    }

    /** Justo antes de cada POST de reverso: `COMPROMETIDO` o `NO_CONCLUYENTE` → `RECLAMADO`, con la hora del reclamo. */
    internal fun reclamarReverso(id: AttemptId): Boolean = escribir("reclamarReverso") {
        val f = fila(id)
        if (f == null || f.procesoId != procesoId || f.reverso !in RECLAMABLES) false
        else {
            guardar(f.copy(reverso = EstadoDelReverso.RECLAMADO, reclamadoEnIso = relojDePared().toString()))
            true
        }
    }

    /**
     * Cierra una vuelta del reverso (desde `RECLAMADO`, o desde `NO_CONCLUYENTE` al techo) en UNA transacción: el reverso,
     * `pudoEntrar` (sólo sube), el último código con su hora observada, el reporte y el estado (r2 R2-11, r3 R3-10). Un
     * `RECHAZADO` con `pudoEntrar` se reporta como `PUDO_ENTRAR`: el SERVIDOR siempre se entera de que un reverso pudo entrar.
     */
    internal fun cerrarVuelta(id: AttemptId, clase: ClaseDelReverso, reportar: ResultadoParaReportar?, estado: EstadoDelIntento?): Boolean =
        escribir("cerrarVuelta") {
            val f = fila(id)
            if (f == null || (f.reverso != EstadoDelReverso.RECLAMADO && f.reverso != EstadoDelReverso.NO_CONCLUYENTE)) false
            else {
                var n = when (clase) {
                    is ClaseDelReverso.Confirmado -> f.copy(reverso = EstadoDelReverso.CONFIRMADO, horaObservadaIso = clase.hora.toString())
                    is ClaseDelReverso.Rechazado -> f.copy(
                        reverso = EstadoDelReverso.RECHAZADO, ultimoCodigoReverso = clase.codigo, horaObservadaIso = clase.hora.toString(),
                    )
                    is ClaseDelReverso.Reintentar -> f.copy(
                        reverso = EstadoDelReverso.NO_CONCLUYENTE, pudoEntrar = f.pudoEntrar || !clase.pruebaQueNoEntro,
                        ultimoCodigoReverso = clase.codigo, horaObservadaIso = clase.hora.toString(),
                    )
                    ClaseDelReverso.NoEnviado -> f.copy(reverso = EstadoDelReverso.NO_CONCLUYENTE)
                }
                if (reportar != null) {
                    val r = if (reportar == ResultadoParaReportar.RECHAZADO && n.pudoEntrar) ResultadoParaReportar.PUDO_ENTRAR else reportar
                    n = n.copy(
                        reporte = EstadoDelReporte.PENDIENTE, resultadoParaReportar = r,
                        codigoParaReportar = n.ultimoCodigoReverso.takeIf { r == ResultadoParaReportar.RECHAZADO },
                    )
                }
                guardar(if (estado != null) n.copy(estado = estado) else n)
                true
            }
        }

    /** El plazo venció antes del primer reclamo: no salió ningún POST, así que no hay resultado bancario ni reporte (r3 R3-10). */
    internal fun cerrarSinVuelta(id: AttemptId): Boolean = escribir("cerrarSinVuelta") {
        val f = fila(id)
        if (f?.reverso != EstadoDelReverso.COMPROMETIDO) false
        else {
            guardar(f.copy(reverso = EstadoDelReverso.NO_CONCLUYENTE, estado = EstadoDelIntento.NO_SE))
            true
        }
    }

    /**
     * El acuse del SERVIDOR y el estado nuevo, en UNA transacción por id (r3 R3-09). Es un CAS: sólo escribe si la fila sigue
     * `PENDIENTE` con el MISMO resultado que se acusa (`acusado`). Un acuse tardío de un reporte viejo nunca marca una
     * obligación más nueva como entregada: regresa null y la fila queda igual. Los valores los decide [ColaDeResultados].
     */
    internal fun aplicarAcuse(
        id: AttemptId,
        acusado: ResultadoParaReportar,
        estadoEnServidor: EstadoDelReversoEnServidor?,
        reporte: EstadoDelReporte,
        estado: EstadoDelIntento?,
    ): IntentoEntity? = escribir("aplicarAcuse") {
        require(reporte != EstadoDelReporte.PENDIENTE && reporte != EstadoDelReporte.NO_APLICA) { "un acuse cierra el reporte" }
        fila(id)?.takeIf { it.reporte == EstadoDelReporte.PENDIENTE && it.resultadoParaReportar == acusado }?.let { f ->
            guardar(f.copy(estadoEnServidor = estadoEnServidor ?: f.estadoEnServidor, reporte = reporte, estado = estado ?: f.estado))
        }
    }

    /**
     * Los avisos `PENDIENTE` al SERVIDOR, en orden; cada fila se lee aparte, con [legible]. Una fila ilegible (Room no la lee,
     * no pasa la validación de hoy o trae un valor desconocido que la deja abierta) nunca se entrega: [buscar] y las escrituras
     * lanzan; los barridos la saltan sin tocarla; `salud()` la cuenta (I3). m1: el listado es sólo de ids; cada fila se vuelve a
     * revisar al leerla, y la que ya no está `PENDIENTE` no se entrega. Lee la base: llámala fuera del hilo principal.
     */
    public fun pendientesDeReporte(): List<IntentoEntity> =
        dao.rowidsPendientesDeReporte().mapNotNull { legible(it)?.takeIf { f -> f.reporte == EstadoDelReporte.PENDIENTE } }

    /**
     * La fila de ese folio, validada, o null si no existe. Una fila ilegible (Room no la lee, no pasa la validación de hoy o
     * trae un valor desconocido que la deja abierta) nunca se entrega: [buscar] y las escrituras lanzan; los barridos la saltan
     * sin tocarla; `salud()` la cuenta (I3).
     *
     * Lee la base: llámala fuera del hilo principal.
     *
     * @throws FilaIlegible si la fila de ese folio existe pero no se puede leer o no pasa la validación: quien llama trata el
     *   intento como «por confirmar», nunca como un desenlace.
     */
    public fun buscar(id: AttemptId): IntentoEntity? = fila(id)

    /**
     * Los números para alarmar ([SaludDeLaLibreta]). Síncrona y de sólo lectura: no cambia ninguna fila. Lee la base: llámala
     * fuera del hilo principal. Cuenta como ilegible todo lo que [legible] salta, sea cual sea la causa.
     */
    public fun salud(): SaludDeLaLibreta {
        // Codex r2: un solo recorrido, leyendo cada fila una vez (`rowidsConTrabajoAbierto` ya incluye los `PENDIENTE`).
        var pendientes = 0
        var sinHora = 0
        var ilegibles = 0
        var masViejo: Instant? = null
        for (rowid in dao.rowidsConTrabajoAbierto()) {
            val f = legible(rowid)
            if (f == null) { ilegibles++; continue }
            if (f.reporte != EstadoDelReporte.PENDIENTE) continue
            pendientes++
            val hora = f.horaObservadaIso?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (hora == null) sinHora++ else if (masViejo == null || hora < masViejo) masViejo = hora
        }
        return SaludDeLaLibreta(pendientes, masViejo, sinHora, ilegibles)
    }

    /**
     * En UNA transacción, para las filas de OTROS procesos (ya muertos) y las de ESTE proceso sin intento vivo en [vivos]
     * (una falla o un `fondo` cancelado las dejó sin camino, r1 R1-5): `ANOTADO` ⇒ `NO_SALIO(INTERRUMPIDO)`, `ENVIADO` ⇒
     * `NO_SE`, reverso `COMPROMETIDO` ⇒ `NO_CONCLUYENTE` sin reporte (no salió ningún POST). Un reverso `RECLAMADO`, o
     * `NO_CONCLUYENTE` con `pudoEntrar`, sin reporte todavía ⇒ `pudoEntrar` y el aviso `PUDO_ENTRAR` con la hora del último
     * reclamo (r4 R4-08). No inventa una respuesta de Blumon. Regresa los ids que cambió. Sin valor por defecto (r2 R2-1): el
     * motor siempre pasa sus vivos; al arrancar, esa lista va vacía. La foto de [vivos] se toma DENTRO de la transacción
     * (r7 C4): un intento que se registra después todavía no puede haber anotado su fila. Una fila ilegible (Room no la lee,
     * no pasa la validación de hoy o trae un valor desconocido que la deja abierta) nunca se entrega: [buscar] y las escrituras
     * lanzan; los barridos la saltan sin tocarla; `salud()` la cuenta (I3).
     */
    internal fun recuperarAlArrancar(vivos: () -> Collection<AttemptId>): List<AttemptId> = escribir("recuperarAlArrancar") {
        dao.rowidsPorRecuperar(procesoId, vivos().map { it.valor })
            .mapNotNull { r -> legible(r)?.let(::comoHuerfana)?.let { AttemptId(guardar(it).attemptId) } }
    }

    /**
     * R1-5 (a): un intento de ESTE proceso que una falla dejó a medio camino después de salir se cierra como el de un proceso
     * muerto (ver [recuperarAlArrancar]), en UNA transacción. Regresa la fila como quedó.
     */
    internal fun cerrarHuerfana(id: AttemptId): IntentoEntity? = escribir("cerrarHuerfana") {
        fila(id)?.let { f -> comoHuerfana(f)?.let(::guardar) ?: f }
    }

    /** El cierre de una fila sin camino; `null` si no hay nada que cerrar. */
    private fun comoHuerfana(f: IntentoEntity): IntentoEntity? {
        var n = f
        if (n.estado == EstadoDelIntento.ANOTADO) n = n.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.INTERRUMPIDO)
        if (n.estado == EstadoDelIntento.ENVIADO) n = n.copy(estado = EstadoDelIntento.NO_SE)
        if (n.reverso == EstadoDelReverso.COMPROMETIDO) {
            n = n.copy(reverso = EstadoDelReverso.NO_CONCLUYENTE)
        } else if (n.reverso == EstadoDelReverso.RECLAMADO || (n.reverso == EstadoDelReverso.NO_CONCLUYENTE && n.pudoEntrar)) {
            n = n.copy(reverso = EstadoDelReverso.NO_CONCLUYENTE, pudoEntrar = true)
            if (n.reporte == EstadoDelReporte.NO_APLICA) {
                n = n.copy(
                    reporte = EstadoDelReporte.PENDIENTE, resultadoParaReportar = ResultadoParaReportar.PUDO_ENTRAR,
                    codigoParaReportar = null, horaObservadaIso = n.reclamadoEnIso,
                )
            }
        }
        return if (n == f) null else n
    }

    /** Cierra la base y la saca de las abiertas del proceso: un [abrir] después abre otra. */
    internal fun cerrar() {
        synchronized(ABIERTAS) { ABIERTAS.values.remove(this) }
        db.close()
    }

    private fun <T> escribir(operacion: String, bloque: () -> T): T =
        db.runInTransaction(Callable { bloque().also { antesDelCommit(operacion) } })

    /**
     * El único punto de lectura por folio; si la fila es ilegible (Room no la lee o no pasa [validar]) lanza [FilaIlegible], de
     * mensaje fijo y sin la causa original, que puede traer el valor dañado. Una fila ilegible (Room no la lee, no pasa la
     * validación de hoy o trae un valor desconocido que la deja abierta) nunca se entrega: [buscar] y las escrituras lanzan;
     * los barridos la saltan sin tocarla; `salud()` la cuenta (I3).
     */
    private fun fila(id: String): IntentoEntity? = try {
        dao.buscar(id)?.also(::validar)
    } catch (e: Exception) {
        throw FilaIlegible()
    }

    private fun fila(id: AttemptId): IntentoEntity? = fila(id.valor)

    /** Las cancelaciones de [original], cada una validada como en [fila]: si una es ilegible, lanza [FilaIlegible]. */
    private fun cancelacionesDe(original: String): List<IntentoEntity> = try {
        dao.cancelacionesDe(original).onEach(::validar)
    } catch (e: Exception) {
        throw FilaIlegible()
    }

    /**
     * La lectura por lote (los barridos y la salud): la fila de ese `rowid`, validada, o `null` si no existe o es ilegible. El
     * folio se lee AQUÍ, dentro del aislamiento (Codex r1 #4). Una fila ilegible (Room no la lee, no pasa la validación de hoy
     * o trae un valor desconocido que la deja abierta) nunca se entrega: [buscar] y las escrituras lanzan; los barridos la
     * saltan sin tocarla; `salud()` la cuenta (I3).
     */
    private fun legible(rowid: Long): IntentoEntity? = try {
        dao.porRowid(rowid)?.also(::validar)
    } catch (e: Exception) {
        null
    }

    private fun filaQueExiste(id: AttemptId): IntentoEntity = requireNotNull(fila(id)) { "intento desconocido" }

    private fun anotarDentro(i: IntentoEntity): Boolean {
        require(
            i.estado == EstadoDelIntento.ANOTADO && i.reverso == EstadoDelReverso.NINGUNO &&
                i.reporte == EstadoDelReporte.NO_APLICA && !i.cortarAntesDeSalir && i.enviadoEnMs == null,
        ) { "un intento se anota en ANOTADO" }
        val t = ahora()
        val n = i.copy(procesoId = procesoId, creadoEnMs = t, actualizadoEnMs = t)
        validar(n)
        return dao.insertar(n) != -1L
    }

    private fun enviar(f: IntentoEntity, t0: Long, d: DatosDelEnvio) = f.copy(
        estado = EstadoDelIntento.ENVIADO, enviadoEnMs = t0, procesoId = procesoId,
        ksnBase = d.ksnBase, contadorUsado = d.contadorUsado, marca = d.marca, medio = d.medio, bin = d.bin,
        ultimos4 = d.ultimos4, paisEmisor = d.paisEmisor, huellaTarjeta = d.huellaTarjeta, huellaLectura = d.huellaLectura,
    )

    private fun guardar(f: IntentoEntity): IntentoEntity {
        val n = f.copy(actualizadoEnMs = ahora())
        validar(n)
        check(dao.actualizar(n) == 1) { "la fila no existe" }
        return n
    }

    private fun esVentaAprobada(o: IntentoEntity) =
        o.operacion == Operacion.VENTA && o.estado == EstadoDelIntento.APROBADO && o.operationNumber != null

    /** La última barrera de privacidad: sólo campos con su forma verificada. El mensaje nunca lleva el valor. */
    private fun validar(i: IntentoEntity) {
        AttemptId(i.attemptId)
        i.operacionOriginal?.let(::AttemptId)
        // T9: sólo una cancelación que no salió puede quedar sin importe (sin original, o de otro venue/ámbito, R14-5): 0.
        require(i.importeCentavos > 0 || (i.operacion == Operacion.CANCELACION && i.estado == EstadoDelIntento.NO_SALIO)) { "importe inválido" }
        require(i.vinculado == (i.origen == Origen.VINCULADO_AL_POS)) { "vinculado no cuadra con el origen" }
        forma(i.bin, BIN, "bin")
        forma(i.ultimos4, ULTIMOS_4, "ultimos4")
        forma(i.paisEmisor, PAIS, "paisEmisor")
        forma(i.autorizacion, AUTORIZACION, "autorizacion")
        forma(i.evidenciaTardiaAutorizacion, AUTORIZACION, "evidenciaTardiaAutorizacion")
        forma(i.codigo, CODIGO, "codigo")
        forma(i.ultimoCodigoReverso, CODIGO, "ultimoCodigoReverso")
        forma(i.codigoParaReportar, CODIGO, "codigoParaReportar")
        forma(i.paymentId, PAYMENT_ID, "paymentId")
        forma(i.ksnBase, KSN, "ksnBase")
        for (op in listOf(i.operationNumber, i.evidenciaTardiaOperacion, i.parcialOperacion)) {
            require(op == null || op in 1..OPERACION_MAXIMA) { "operación fuera de forma" }
        }
        require(i.contadorUsado == null || i.contadorUsado in 1L..CONTADOR_MAXIMO) { "contador fuera de rango" }
        // r2 familia C: un importe parcial es menor que el pedido y mayor que cero; nunca se guarda un número sin cotejar.
        require(i.parcialImporteCentavos == null || i.parcialImporteCentavos in 1 until i.importeCentavos) { "parcialImporteCentavos fuera de rango" }
        // Codex r4 #2, las dos reglas de dinero: el aviso «REVERSADA» al SERVIDOR exige el reverso confirmado, y «Aprobado» exige
        // el folio bancario o el id de pago del SERVIDOR.
        require(i.resultadoParaReportar != ResultadoParaReportar.CONFIRMADO || i.reverso == EstadoDelReverso.CONFIRMADO) {
            "aviso confirmado sin reverso confirmado"
        }
        require(i.estado != EstadoDelIntento.APROBADO || i.operationNumber != null || i.paymentId != null) { "aprobado sin folio ni pago" }
    }

    private fun forma(valor: String?, r: Regex, campo: String) = require(valor == null || r.matches(valor)) { "$campo sin forma" }

    public companion object {
        /** Uno por PROCESO de Android, no por motor: los tiempos monotónicos sólo se comparan dentro del mismo proceso. */
        internal val PROCESO_ID: String = UUID.randomUUID().toString()

        /** Sin fila de contador para esa base (nueva o libreta perdida), se salta hacia adelante (ANSI X9.24 lo permite). */
        internal const val SALTO_SIN_HISTORIAL = 256L

        private val RESUELTOS = setOf(EstadoDelIntento.APROBADO, EstadoDelIntento.RECHAZADO, EstadoDelIntento.CANCELADA)
        private val RECLAMABLES = setOf(EstadoDelReverso.COMPROMETIDO, EstadoDelReverso.NO_CONCLUYENTE)
        private val VETAN_OTRA_CANCELACION =
            setOf(EstadoDelIntento.ANOTADO, EstadoDelIntento.ENVIADO, EstadoDelIntento.CANCELADA, EstadoDelIntento.NO_SE)
        private const val CONTADOR_MAXIMO = Dukpt.CONTADOR_MAXIMO.toLong()
        private const val OPERACION_MAXIMA = 9_999_999_999L
        private val BIN = Regex("^[0-9]{6}$")
        private val ULTIMOS_4 = Regex("^[0-9]{4}$")
        private val PAIS = Regex("^[0-9]{3,4}$")
        private val AUTORIZACION = Regex("^[A-Za-z0-9]{1,12}$")
        private val CODIGO = Regex("^[A-Z0-9_]{1,16}$")
        private val PAYMENT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val KSN = Regex("^[0-9A-F]{20}$")

        /** Las libretas abiertas del proceso, por ruta canónica del archivo: una por archivo, con un solo dueño (r1 R1-4). */
        private val ABIERTAS = HashMap<String, Libreta>()

        /** La libreta de producción: el archivo fijo `cobro_propio.db` en `noBackupFilesDir` de la aplicación. */
        public fun abrir(context: Context): Libreta = abrir(context, "cobro_propio.db")

        /**
         * Una por archivo en todo el proceso: un segundo `abrir` del mismo archivo regresa LA MISMA (y su `ahora`). El directorio
         * sale de `applicationContext` (Codex r1 #7): otro `Context` (p. ej. de almacenamiento protegido) no abre otra libreta.
         */
        internal fun abrir(context: Context, nombre: String, ahora: () -> Long = SystemClock::elapsedRealtime): Libreta {
            val archivo = File(context.applicationContext.noBackupFilesDir, nombre)
            return synchronized(ABIERTAS) {
                ABIERTAS.getOrPut(archivo.canonicalPath) { Libreta(LibretaDatabase.constructor(context, archivo).build(), ahora) }
            }
        }
    }
}
