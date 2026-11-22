package com.avoqado.cobropropio.simulador

import com.avoqado.cobropropio.dominio.AttemptId

/**
 * Lo que contesta el Blumon simulado, elegido por los dos últimos dígitos de los centavos del importe (como las tarjetas de
 * prueba de un procesador): `$100.20` ⇒ [SILENCIO_Y_REVERSADA]. Un importe sin escenario propio se aprueba. Las formas de los
 * sobres son las MEDIDAS en el sandbox (`recorrido-sandbox.md` §2.3 y §2.10): el éxito trae nuestro folio en
 * `dataResponse.batch` y el error un bloque `error` plano. «Silencio» = la venta sale y nadie la contesta; el escenario dice
 * qué contesta después el reverso. Una venta de los escenarios de CANCELACIÓN se aprueba; su escenario es lo que contesta la
 * cancelación de esa venta.
 */
internal enum class EscenarioDeBlumon(val centavos: Int) {
    /** La venta aprobada, con el eco. Su cancelación también sale bien, con el eco del folio de la cancelación. */
    APROBADO(0),

    /** Regla del eco: la venta aprobada SIN nuestro folio en ningún campo. */
    APROBADO_SIN_ECO(1),

    /** Regla de pureza: la venta aprobada, con eco, más `error: {httpStatusCode: 400}`. */
    APROBADO_CON_ERROR(2),

    /** Regla de pureza: la venta aprobada, con eco, más un `"error": "x"` suelto. */
    APROBADO_CON_ERROR_SUELTO(3),

    /** El rechazo `05`, con un error plano y `httpStatusCode` de negocio. */
    RECHAZO_05(5),

    /** Regla de pureza: el rechazo `05` más un `dataResponse` con datos. */
    RECHAZO_CON_DATOS(6),

    /** Regla de pureza: el rechazo `05` con `error.httpStatusCode` 504, de transporte. */
    RECHAZO_CON_HTTP_DE_TRANSPORTE(7),

    /** ISO 10: aprueba la mitad del importe. */
    APROBADO_PARCIAL(10),

    /** La venta se aprueba; el preflight de su cancelación dice `TX_024` con la forma medida. */
    CANCELACION_NO_PERMITIDA(11),

    /** La venta se aprueba; su cancelación sale y nadie la contesta. */
    CANCELACION_SIN_RESPUESTA(12),

    /** La venta se aprueba; su cancelación contesta un éxito con el eco del folio de la VENTA, no el de la cancelación. */
    CANCELACION_CON_ECO_DE_LA_VENTA(13),

    /** La venta se aprueba; su cancelación contesta un éxito con eco más `error: {httpStatusCode: 400}`. */
    CANCELACION_CON_ERROR(14),

    /** Silencio; el reverso contesta `REVERSADA` con el eco. */
    SILENCIO_Y_REVERSADA(20),

    /** Silencio; el primer reverso contesta `REVERSADA` SIN el eco y el segundo `TX_007`. */
    SILENCIO_Y_REVERSADA_SIN_ECO(21),

    /** Silencio; el reverso contesta `TX_007` (el reverso no entró). */
    SILENCIO_Y_TX007(30),

    /** Silencio; los dos primeros reversos contestan `RQ_006` y el tercero `REVERSADA`. */
    SILENCIO_RQ006_DOS_VECES_Y_REVERSADA(40),

    /** La venta contesta APROBADA cuando la prueba la suelta: después del plazo, ANTES del arbitraje. */
    APROBADO_TARDIO_ANTES_DEL_ARBITRAJE(50),

    /** La venta contesta APROBADA cuando la prueba la suelta: DESPUÉS del arbitraje. El reverso, `REVERSADA`. */
    APROBADO_TARDIO_DESPUES_DEL_ARBITRAJE(55),

    /** Silencio, como [SILENCIO_Y_REVERSADA]; va con [EscenarioDelServidor.YA_COBRADO]. */
    SILENCIO_Y_SERVIDOR_YA_COBRADO(60),

    /** Silencio; el primer reverso sale y se corta sin respuesta, el segundo contesta `TX_007`. */
    REVERSO_SIN_RESPUESTA_Y_TX007(70),

    /** Todo éxito trae `simulation: true`, como el sandbox: el primer reverso `REVERSADA`, el segundo `TX_007`. */
    SIMULATION_TRUE(80),

    /** La venta contesta `68` (del conjunto «no sé»); el reverso, `REVERSADA`. */
    CODIGO_68(85),

    /** Silencio y `REVERSADA`, como [SILENCIO_Y_REVERSADA]; va con [EscenarioDelServidor.ACUSA_CONTRADICCION]. */
    REVERSADA_Y_SERVIDOR_CONTRADICTION(90),
    ;

    companion object {
        fun de(importeCentavos: Long): EscenarioDeBlumon {
            val c = (importeCentavos % 100).toInt()
            return entries.firstOrNull { it.centavos == c } ?: APROBADO
        }
    }
}

/**
 * Lo que contesta el SERVIDOR simulado en `…/reverso/intencion` y `…/reverso/resultado`, elegido por el FINAL del `AttemptId`
 * (cuatro hexadecimales con letras, así que nunca arma una racha de dígitos; un folio al azar cae en uno de éstos 1 de cada
 * 65,536 veces). Cualquier otro folio ⇒ [CONCEDE_Y_ACUSA].
 */
internal enum class EscenarioDelServidor(val sufijo: String?) {
    /** Concede (`reversing`) y acusa lo reportado: `REVERSADA` ⇒ `reversed`, `SIN_RESPUESTA` ⇒ `reversing`, otro código ⇒ `reverse_failed`. */
    CONCEDE_Y_ACUSA(null),

    /** 409 `ATTEMPT_ALREADY_RECORDED`: el cobro ya está registrado en el SERVIDOR, con el total de la venta. */
    YA_COBRADO("c0b0"),

    /** Concede, y a cualquier reporte acusa `contradiction`. */
    ACUSA_CONTRADICCION("c047"),
    ;

    companion object {
        fun de(id: AttemptId): EscenarioDelServidor =
            entries.firstOrNull { e -> e.sufijo?.let(id.valor::endsWith) == true } ?: CONCEDE_Y_ACUSA
    }
}
