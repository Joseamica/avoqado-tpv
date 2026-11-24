package com.avoqado.cobropropio.libreta

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen

/**
 * La conciliación del gerente NO es de la fase 2 (parada 2): la evidencia tardía y la del parcial sólo se guardan y nunca
 * promueven el estado. Por eso no hay `APROBADO_PARCIAL`.
 */
public enum class EstadoDelIntento { ANOTADO, ENVIADO, APROBADO, RECHAZADO, NO_SALIO, NO_SE, NO_SE_COBRO, CANCELADA }

public enum class EstadoDelReverso { NINGUNO, COMPROMETIDO, RECLAMADO, CONFIRMADO, RECHAZADO, NO_CONCLUYENTE }

public enum class EstadoDelReporte { NO_APLICA, PENDIENTE, ENTREGADO, CONCILIAR, FINAL_SIN_ENTREGAR }

public enum class ResultadoParaReportar { CONFIRMADO, RECHAZADO, PUDO_ENTRAR }

public enum class OrigenDeLaAprobacion { BANCO, SERVIDOR }

/** Lo que el SERVIDOR dice del reverso en su acuse (la Tarea 6 traduce su respuesta a esto). */
public enum class EstadoDelReversoEnServidor { REVERSING, REVERSED, REVERSE_FAILED, REVERSE_UNCONFIRMED, CONTRADICTION }

/**
 * Una fila por intento (venta o cancelación). 🔴 Nunca PAN, banda, tags EMV, token ni llaves: sólo campos con forma
 * verificada (la libreta los revisa en cada escritura) y huellas. `vinculado` = `origen == VINCULADO_AL_POS`, congelado al
 * anotar. Los `…Ms` son del reloj monotónico y sólo se comparan dentro del mismo `procesoId`.
 */
// Ola final · 3: `huellaLectura` lleva índice porque `LibretaDao.existeLectura` la busca en cada cancelación.
@Entity(tableName = "intentos", indices = [Index("operacionOriginal"), Index("reporte"), Index("huellaLectura")])
public data class IntentoEntity(
    @PrimaryKey val attemptId: String,
    val operacion: Operacion,
    val venueId: String,
    val importeCentavos: Long,
    val vinculado: Boolean,
    // El ámbito congelado (Identidad.ambito()) y la versión del SDK.
    val posId: String,
    val serieBlumon: String,
    val ambiente: Ambiente,
    val versionSdk: String,
    val origen: Origen,
    val estado: EstadoDelIntento = EstadoDelIntento.ANOTADO,
    val motivoNoSalio: MotivoNoSalio? = null,
    val reverso: EstadoDelReverso = EstadoDelReverso.NINGUNO,
    /** Sólo sube: un reverso que «pudo entrar» nunca se olvida entre vueltas. */
    val pudoEntrar: Boolean = false,
    val ultimoCodigoReverso: String? = null,
    val reporte: EstadoDelReporte = EstadoDelReporte.NO_APLICA,
    val resultadoParaReportar: ResultadoParaReportar? = null,
    val codigoParaReportar: String? = null,
    val horaObservadaIso: String? = null,
    val estadoEnServidor: EstadoDelReversoEnServidor? = null,
    val origenDeLaAprobacion: OrigenDeLaAprobacion? = null,
    /** Se guarda en la misma transacción que el `Concedido`, antes del primer POST de reverso (r4 R4-08). */
    val vinculoAcreditadoPorPermiso: Boolean = false,
    /** Hora de pared del último reclamo del reverso: la hora observada del aviso `PUDO_ENTRAR` tras un apagón. */
    val reclamadoEnIso: String? = null,
    /** El aprobado parcial (ISO 10) recibido ANTES del arbitraje: su folio. Se reversa igual. */
    val parcialOperacion: Long? = null,
    /**
     * El importe de un aprobado parcial, cotejado (`0 < parcial < importe`, sin datos de la tarjeta). A tiempo, junto a
     * `parcialOperacion`; tardío, junto a `evidenciaTardiaOperacion` (r2 R2-3).
     */
    val parcialImporteCentavos: Long? = null,
    /** La aprobación recibida ANTES del arbitraje. */
    val operationNumber: Long? = null,
    val autorizacion: String? = null,
    /** La aprobación recibida DESPUÉS del arbitraje: sólo se guarda (la fase 3 la muestra para conciliar). */
    val evidenciaTardiaOperacion: Long? = null,
    val evidenciaTardiaAutorizacion: String? = null,
    val paymentId: String? = null,
    /** En una cancelación, el `attemptId` de la venta original. */
    val operacionOriginal: String? = null,
    val codigo: String? = null,
    val leyenda: String? = null,
    val marca: Marca? = null,
    val medio: Medio? = null,
    val bin: String? = null,
    val ultimos4: String? = null,
    val paisEmisor: String? = null,
    val huellaTarjeta: String? = null,
    val huellaLectura: String? = null,
    val ksnBase: String? = null,
    val contadorUsado: Long? = null,
    val enviadoEnMs: Long? = null,
    val cortarAntesDeSalir: Boolean = false,
    /** Lo pone la libreta: el proceso de Android que escribió la fila. */
    val procesoId: String = "",
    val creadoEnMs: Long = 0,
    val actualizadoEnMs: Long = 0,
) {
    /** El mismo formato que `Identidad.ambito()`. */
    public fun ambito(): String = "$posId|$serieBlumon|$ambiente"
}
