package com.avoqado.cobropropio.dominio

import com.avoqado.cobropropio.admision.MotivoNoAdmitida

/** Lo que el SDK le dice a la app. Sin textos de pantalla: la app los pone. Ningún campo lleva PAN, banda ni tags. */
public sealed interface EventoDeCobro {
    public data class Iniciado(val id: AttemptId, val identidad: Identidad, val operacion: Operacion, val importeCentavos: Long) :
        EventoDeCobro
    public data object PideTarjeta : EventoDeCobro
    public data object Autorizando : EventoDeCobro

    /**
     * El motor no tiene un desenlace definitivo del importe completo y va a intentar deshacer la venta; la venta pudo no haber
     * salido. No es un final ni un veredicto: nunca dice que salió ni que no se cobró. Sale a lo más una vez por intento, en el
     * instante en que el motor lo detecta (sin respuesta, una respuesta que no sirve o un parcial), siempre después de
     * `Autorizando` y antes del final. Quien se engancha al intento vivo lo recibe en su historia; una lápida sólo da su final.
     */
    public data object RevisandoConElBanco : EventoDeCobro

    public data class Aprobado(
        val operationNumber: Long?,
        val autorizacion: String?,
        val paymentId: String?,
        val marca: Marca?,
        val medio: Medio?,
        val bin: String?,
        val ultimos4: String?,
        val paisEmisor: String?,
    ) : EventoDeCobro
    public data class Cancelada(val operationNumber: Long) : EventoDeCobro
    public data class Rechazado(val codigo: String, val leyenda: String) : EventoDeCobro
    public data class NoSalioAlBanco(val motivo: MotivoNoSalio, val admision: MotivoNoAdmitida?) : EventoDeCobro
    /** Se deshizo: sólo con el `REVERSADA` con eco de Blumon; el acuse del SERVIDOR nunca lo da ni lo quita (bloque S). */
    public data object NoSeCobro : EventoDeCobro

    /**
     * El motor no tiene veredicto de ese folio: ni «se cobró» ni «no se cobró». Un definitivo puede llegar después (por
     * `resolucionesTardias` o al volver a preguntar) y lo reemplaza; un `PorConfirmar` nunca reemplaza a un definitivo.
     * En una cancelación, `importeCentavos = 0` es «importe todavía desconocido»: la pantalla no debe pintar «$0.00».
     */
    public data class PorConfirmar(val importeCentavos: Long) : EventoDeCobro

    /**
     * Ese folio ya existe con OTRO contrato (otro importe, otra operación, otra venta original, otro venue u otro ámbito). Se
     * le contesta sólo a quien preguntó y ahí termina. **No dice nada del dinero de ese folio: pudo salir al banco y hasta
     * aprobarse.** La app no lo trata como «no salió» ni ofrece cobrar de nuevo por esto: pide un folio nuevo.
     * (Ola final, 4ª parte, R24-2: antes era `NoSalioAlBanco(CONTRATO_DISTINTO)`, que se podía leer como «no se cobró».)
     */
    public data object ContratoDistinto : EventoDeCobro

    /** Sin `else`: un evento nuevo obliga a decidir si cierra el intento. */
    public val esTerminal: Boolean
        get() = when (this) {
            is Iniciado, PideTarjeta, Autorizando, RevisandoConElBanco -> false
            is Aprobado, is Cancelada, is Rechazado, is NoSalioAlBanco, NoSeCobro, is PorConfirmar, ContratoDistinto -> true
        }
}

public enum class MotivoNoSalio {
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
