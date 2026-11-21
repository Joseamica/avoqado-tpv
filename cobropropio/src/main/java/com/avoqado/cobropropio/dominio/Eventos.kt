package com.avoqado.cobropropio.dominio

import com.avoqado.cobropropio.admision.MotivoNoAdmitida

/** Lo que el SDK le dice a la app. Sin textos de pantalla: la app los pone. Ningún campo lleva PAN, banda ni tags. */
sealed interface EventoDeCobro {
    data class Iniciado(val id: AttemptId, val identidad: Identidad, val operacion: Operacion, val importeCentavos: Long) :
        EventoDeCobro
    data object PideTarjeta : EventoDeCobro
    data object Autorizando : EventoDeCobro
    data object RevisandoConElBanco : EventoDeCobro

    data class Aprobado(
        val operationNumber: Long?,
        val autorizacion: String?,
        val paymentId: String?,
        val marca: Marca?,
        val medio: Medio?,
        val bin: String?,
        val ultimos4: String?,
        val paisEmisor: String?,
    ) : EventoDeCobro
    data class Cancelada(val operationNumber: Long) : EventoDeCobro
    data class Rechazado(val codigo: String, val leyenda: String) : EventoDeCobro
    data class NoSalioAlBanco(val motivo: MotivoNoSalio, val admision: MotivoNoAdmitida?) : EventoDeCobro
    data object NoSeCobro : EventoDeCobro
    /** En una cancelación, `importeCentavos = 0` es «importe todavía desconocido»: la pantalla no debe pintar «$0.00». */
    data class PorConfirmar(val importeCentavos: Long) : EventoDeCobro

    /**
     * Ese folio ya existe con OTRO contrato (otro importe, otra operación, otra venta original, otro venue u otro ámbito). Se
     * le contesta sólo a quien preguntó y ahí termina. **No dice nada del dinero de ese folio: pudo salir al banco y hasta
     * aprobarse.** La app no lo trata como «no salió» ni ofrece cobrar de nuevo por esto: pide un folio nuevo.
     * (Ola final, 4ª parte, R24-2: antes era `NoSalioAlBanco(CONTRATO_DISTINTO)`, que se podía leer como «no se cobró».)
     */
    data object ContratoDistinto : EventoDeCobro

    /** Sin `else`: un evento nuevo obliga a decidir si cierra el intento. */
    val esTerminal: Boolean
        get() = when (this) {
            is Iniciado, PideTarjeta, Autorizando, RevisandoConElBanco -> false
            is Aprobado, is Cancelada, is Rechazado, is NoSalioAlBanco, NoSeCobro, is PorConfirmar, ContratoDistinto -> true
        }
}

enum class MotivoNoSalio {
    SIN_RED,
    LECTURA_CORTADA,
    LECTURA_VENCIDA,
    CANCELADA_EN_LECTOR,
    NO_ADMITIDA,
    TERMINAL_OCUPADA,
    SIN_LLAVES,
    CONTADOR_AGOTADO,
    INTERRUMPIDO,
    CANCELACION_YA_EXISTE,
    SIN_VENTA_APROBADA,
    OTRA_TARJETA,
    OTRO_AMBITO,
    LECTURA_REPETIDA,
    /** El preflight de Blumon (`validateCancelation`) no dijo que esa venta se pueda cancelar: la cancelación no salió. */
    CANCELACION_NO_PERMITIDA,
}
