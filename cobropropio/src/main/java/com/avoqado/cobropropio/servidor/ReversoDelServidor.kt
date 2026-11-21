package com.avoqado.cobropropio.servidor

import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import kotlin.time.Duration

/** La respuesta del SERVIDOR a `…/reverso/intencion` (CON §2.1). Sólo `Concedido` autoriza a reversar con permiso. */
sealed interface PermisoDeReverso {
    /** 200 + `reversing`. */
    data class Concedido(val eventLogId: String) : PermisoDeReverso {
        override fun toString() = "Concedido(oculto)"
    }

    /** 200 con otro estado: la lápida ya tiene desenlace y no se reversa otra vez. */
    data class YaResuelto(val estado: EstadoDelReversoEnServidor, val eventLogId: String) : PermisoDeReverso {
        override fun toString() = "YaResuelto(estado=$estado)"
    }

    /** 409 `ATTEMPT_ALREADY_RECORDED`. `paymentId` y la autorización todavía no se cotejan con el PAN: nunca se imprimen. */
    data class YaCobrado(val paymentId: String, val amountCents: Long?, val tipCents: Long?, val authorizationNumber: String?) :
        PermisoDeReverso {
        override fun toString() = "YaCobrado(oculto)"
    }

    /** 409 `ATTEMPT_HAS_PENDING_EVIDENCE`. */
    data class EvidenciaPendiente(val paymentStatus: String?, val tipo: String?) : PermisoDeReverso {
        override fun toString() = "EvidenciaPendiente(oculta)"
    }

    /** 404 `ATTEMPT_NOT_FOUND`: sin vínculo, de otro negocio o de otra terminal. */
    data object NoEncontrado : PermisoDeReverso

    /** 404 sin `code`: el candado del SDK propio está apagado en el SERVIDOR. */
    data object ServidorSinSdkPropio : PermisoDeReverso

    /** 409 `ATTEMPT_NOT_BLUMON`. */
    data object NoEsBlumon : PermisoDeReverso

    /** Sin JWT vigente: no sale ningún POST y nunca autoriza (veta). */
    data object SinCredencial : PermisoDeReverso

    /** 500 y cualquier otro 5xx, o un 200/409 sin forma. Nunca autoriza. */
    data object ErrorDelServidor : PermisoDeReverso

    /** 503 `REVERSO_NO_DISPONIBLE`, sin red o tiempo vencido: puede reversar sin permiso (diseño §16.3). */
    data object SinRespuesta : PermisoDeReverso

    /** 400, 401 (también tras renovar el JWT una vez), 403 y cualquier otra respuesta que no es de las de arriba. */
    data class Invalido(val http: Int, val code: String?) : PermisoDeReverso {
        override fun toString() = "Invalido(http=$http)"
    }
}

/** La respuesta del SERVIDOR a `…/reverso/resultado` (CON §2.2). */
sealed interface AcuseDelResultado {
    data class Guardado(val estado: EstadoDelReversoEnServidor, val eventLogId: String, val paymentId: String?) : AcuseDelResultado {
        override fun toString() = "Guardado(estado=$estado)"
    }

    /** 409 `REVERSAL_RESULT_CONFLICT`: el SERVIDOR ya tiene otro desenlace. */
    data class Conflicto(val estadoGuardado: EstadoDelReversoEnServidor) : AcuseDelResultado

    /** 404, con o sin `code`. */
    data object NoEncontrado : AcuseDelResultado

    /** 400, 403, 409 `ATTEMPT_NOT_BLUMON` y cualquier otro 4xx definitivo: no se reintenta. */
    data class Final(val http: Int, val code: String?) : AcuseDelResultado {
        override fun toString() = "Final(http=$http)"
    }

    /** 401 (token vencido, ya renovado), sin JWT, 503, 5xx, 408, 429, sin red, o un 200 sin forma: la cola lo vuelve a mandar. */
    data object Reintentar : AcuseDelResultado
}

/** El cuerpo de `/resultado`. `reversadoEn` es la hora OBSERVADA guardada (ISO 8601 con zona), nunca la hora de la cola. */
data class ResultadoDelReverso(
    val descripcion: String?,
    val codigo: String?,
    val operationNumber: Long?,
    val reversadoEn: String,
) {
    companion object {
        /**
         * Lo que la fila tiene que reportar: `CONFIRMADO` ⇒ «REVERSADA»; `RECHAZADO` ⇒ su código; `PUDO_ENTRAR` ⇒
         * `SIN_RESPUESTA`, que el SERVIDOR clasifica no concluyente y deja la lápida `reversing` (r3 R3-08). Un `RECHAZADO` sin
         * código también va como `SIN_RESPUESTA`: nunca se afirma un rechazo sin su código. null si no hay nada que reportar.
         */
        fun deLaFila(f: IntentoEntity): ResultadoDelReverso? {
            val hora = f.horaObservadaIso ?: return null
            return when (f.resultadoParaReportar ?: return null) {
                ResultadoParaReportar.CONFIRMADO -> ResultadoDelReverso(REVERSADA, null, null, hora)
                ResultadoParaReportar.RECHAZADO -> ResultadoDelReverso(null, f.codigoParaReportar ?: Clasificador.SIN_RESPUESTA, null, hora)
                ResultadoParaReportar.PUDO_ENTRAR -> ResultadoDelReverso(null, Clasificador.SIN_RESPUESTA, null, hora)
            }
        }

        private const val REVERSADA = "REVERSADA"
    }
}

/**
 * El JWT de ESTA terminal para el SERVIDOR (v5 §2.5). Lo implementa la app en la fase 3 con el refresco de `TokenAuthenticator`.
 * Nunca el de otra terminal ni de otra sucursal: si fallara, el SERVIDOR contesta 404 y eso nunca da un desenlace falso.
 */
interface CredencialDelServidor {
    /** El JWT vigente, sin red. `null` = no hay sesión. */
    fun vigente(): String?

    /** Un JWT nuevo de la MISMA terminal, dado el que el SERVIDOR rechazó; `null` si no se pudo. Se acota con [espera]. */
    suspend fun renovar(rechazado: String, espera: Duration): String?
}

/** El contrato del reverso con el SERVIDOR. La identidad (la terminal) sale del JWT de [CredencialDelServidor]. */
interface ReversoDelServidor {
    /**
     * Regresa a más tardar en `espera`: ése es el ÚNICO reloj de la llamada (r2 familia B). Si llegó un código HTTP, nunca es
     * `SinRespuesta`, aunque el cuerpo no alcance a leerse; `SinRespuesta` sólo si no llegó nada.
     */
    suspend fun pedirPermiso(venueId: String, id: AttemptId, espera: Duration): PermisoDeReverso

    suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado
}
