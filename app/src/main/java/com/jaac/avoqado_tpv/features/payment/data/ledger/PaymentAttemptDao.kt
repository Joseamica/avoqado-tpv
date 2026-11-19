package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity

/** E3 · cuántas candidatas trae cada página de la consulta S6 ([PaymentAttemptDao.candidatasDeConsultaAlServidor]). */
const val CANDIDATAS_POR_PAGINA = 25

/**
 * 🔴 F1 bloque 4: la venta de AngelPay que la recuperación (E1) todavía pregunta al historial — en duda, o declarada por una PERSONA
 * (la firma no la saca: un «cobrado» posterior debe aparecer), sin veto, con turnos (`history_checks < 5`). Sin alias.
 */
const val SQL_CONSULTABLE_EN_EL_HISTORIAL = "((state IN ('AUTORIZANDO','INDETERMINADO') OR " +
    PaymentAttemptEntity.SQL_DECLARADA_POR_UNA_PERSONA + ") AND server_veto IS NULL AND history_checks < 5)"

/**
 * 🔴 F1 bloque 3 (I2): el aprobado final de AngelPay escrito sobre una fila que cerró la VENTANA (no una persona): conserva su
 * `DESCARTADA` y su motivo, y es una obligación de registro que el registrador toma con la llave del intento. Sin alias.
 */
const val SQL_APROBADO_TRAS_LA_VENTANA = "(state = 'DESCARTADA' AND processor = 'ANGELPAY' AND kind = 'SALE' AND legacy_shadow = 0 " +
    "AND host_approved IS 1 AND server_payment_id IS NULL AND server_veto IS NULL)"

/**
 * 🔴 F1 bloque 5 (I5): «Por revisar» = la unión, POR IDENTIDAD, de los intentos que cuenta Caja
 * ([com.jaac.avoqado_tpv.features.shift.domain.SQL_INTENTO_QUE_CUENTA_CAJA]), los que frenan otra tarjeta de su cuenta
 * ([PaymentAttemptEntity.SQL_CERCA_LA_TARJETA]) y las solicitudes del POS pendientes sin intento. Clave del renglón: `(ATTEMPT,
 * attemptId)` o `(REMOTE, requestId)`: un `requestId` sirve para mirar a los hermanos, nunca quita un renglón. `declarable` = admite la
 * firma (lo demás sólo se consulta o lo resuelve su dueño). Usa `:venueId` y `:processToken`.
 */
const val SQL_POR_REVISAR = """
    SELECT 'ATTEMPT' AS clase, attempt_id AS clave, attempt_id AS attempt_id, terminal_payment_request_id AS request_id,
        (amount_cents + tip_cents) AS total_centavos, created_at AS desde_millis, server_answered_at AS server_answered_at,
        state AS estado, kind AS tipo,
        CASE WHEN state = 'INDETERMINADO' AND legacy_shadow = 0 AND kind = 'SALE'
            AND host_approved IS NOT 1 AND server_processor_evidence IS NOT 'APPROVED' AND server_veto IS NULL
            AND (server_outcome IS NULL
                 OR server_outcome NOT IN ('RECORDED','SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE'))
            AND NOT """ + PaymentAttemptEntity.SQL_CONTRADICCION + """
            AND NOT (last_error IS 'cuarentena_por_antiguedad' AND process_token IS :processToken)
            AND NOT (processor = 'BLUMON' AND process_token IS :processToken AND sdk_returned_at IS NULL)
            -- Dentro de este FROM los nombres sin prefijo son los del hermano; `payment_attempts.` es la fila de afuera.
            AND NOT EXISTS (SELECT 1 FROM payment_attempts hermano
                WHERE hermano.terminal_payment_request_id = payment_attempts.terminal_payment_request_id
                AND hermano.attempt_id != payment_attempts.attempt_id
                AND (hermano.legacy_shadow = 1 OR hermano.state NOT IN ('DESCARTADA','INDETERMINADO') OR hermano.host_approved IS 1
                     OR """ + PaymentAttemptEntity.SQL_CONTRADICCION + """))
            THEN 1 ELSE 0 END AS declarable,
        CASE WHEN state = 'INDETERMINADO' AND process_token IS :processToken AND sdk_returned_at IS NULL
            AND (processor = 'BLUMON' OR last_error IS 'cuarentena_por_antiguedad') THEN 1 ELSE 0 END AS ejecucion_viva
    FROM payment_attempts
    WHERE venue_id = :venueId
    AND (""" + com.jaac.avoqado_tpv.features.shift.domain.SQL_INTENTO_QUE_CUENTA_CAJA + """ OR """ + PaymentAttemptEntity.SQL_CERCA_LA_TARJETA + """)
    UNION ALL
    SELECT 'REMOTE' AS clase, r.request_id AS clave, NULL AS attempt_id, r.request_id AS request_id,
        (r.amount_cents + r.tip_cents) AS total_centavos, r.created_at AS desde_millis, NULL AS server_answered_at,
        NULL AS estado, 'SALE' AS tipo, 1 AS declarable, 0 AS ejecucion_viva
    FROM remote_payment_requests r
    WHERE r.venue_id = :venueId AND r.status = 'PROCESSING' AND r.execution_started_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM payment_attempts p
        WHERE instr(p.payment_context_json, '"terminalPaymentRequestId":"' || r.request_id || '"') > 0)
"""

/** 🔴 F1 bloque 3: quién trajo el aprobado final de AngelPay; se guarda en `operation_id` (que AngelPay no usa). */
enum class OrigenDelAprobado(val columna: String) {
    SDK("angelpay:sdk"), APP_A_APP("angelpay:app_a_app"),
    HISTORIAL_PANTALLA("angelpay:historial_k19a"), HISTORIAL_RECUPERACION("angelpay:historial_e1"),
}

/** 🔴 F1 bloque 3: qué dejó la escritura del aprobado final de AngelPay ([PaymentAttemptDao.escribirAprobadoFinalAngelPay]). */
enum class AprobadoFinal {
    /** Quedó en `HOST_RESPONDIO`: lo registra la pantalla o la recuperación, con la llave del intento. */
    REGISTRABLE,
    /** La cerró la ventana: sigue `DESCARTADA` con su motivo, con el aprobado escrito y elegible para el registrador. */
    REGISTRABLE_TRAS_LA_VENTANA,
    /** Se escribió el aprobado; el estado no cambia (veto: Avoqado concilia; o ya estaba registrada / en la cola). */
    GUARDADO,
    /** El mismo aprobado ya estaba escrito. */
    YA_ESTABA,
    /** Un segundo par incompatible (o un aprobado sobre un rechazo del host): se anotó, la fila queda en revisión. */
    INCOMPATIBLE,
    /** Otra sucursal, otro procesador, una devolución o una fila heredada: no se toca. */
    AJENO,
    /** No hay fila. */
    SIN_FILA;

    /** ¿La aprobación quedó escrita en la fila (y desde ese commit protege su cuenta)? */
    val quedoEscrito: Boolean get() = this in setOf(REGISTRABLE, REGISTRABLE_TRAS_LA_VENTANA, GUARDADO, YA_ESTABA, INCOMPATIBLE)
}

/** B1: revierte la transacción de [PaymentAttemptDao.cerrarSolicitudSiCeso] cuando la bandeja no aceptó el cierre. */
class CierreParaEjecucionNoAceptado : RuntimeException("La bandeja no aceptó el cierre para ejecución; se revierte la transacción")

@Dao
interface PaymentAttemptDao {

    /** Diagnóstico de restauración; conserva estados/evidencias y motivos financieros anteriores. */
    @Query("""UPDATE payment_attempts SET last_error = COALESCE(last_error, :reason)
        WHERE attempt_id = :key AND venue_id = :venueId""")
    suspend fun anotarProblemaDeSnapshot(venueId: String, key: String, reason: String): Int

    /**
     * Re-revisión de F (I-1, 26-sep): una fila con VETO no entra (`server_veto IS NULL`). Es de Avoqado conciliar: se queda en
     * duda, cerca su venta y no aparta nada; subirla a HOST_RESPONDIO la dejaba atorada ahí (el veto impide REGISTRADO), y
     * HOST_RESPONDIO de este proceso aparta el aparato. Va en el SQL y no en quien llama: filtrada después del `LIMIT`, 50 filas
     * vetadas taparían para siempre a la 51.
     */
    // 🔴 29-sep-2026: sólo VENTAS. Una devolución la verifica su propio flujo; hoy sólo la dejaba fuera que su `processor`
    // se escribe en minúsculas, una coincidencia que no protege nada.
    // 🔴 F1 (Codex código r2 #1): también la venta que una persona declaró «no se cobró» (la firma pudo llegar antes de la primera
    // consulta, o tras reiniciar): AngelPay se sigue consultando con la MISMA pausa y el MISMO tope de 5.
    // 🔴 F1 bloque 4 (I3): el tope cuenta RESPUESTAS escritas (`history_checks`), no consultas tomadas; y PRIMERO va la lectura
    // despachada que nadie terminó (`history_read_at`), en cualquier estado — aunque cerró la ventana o la firmó una persona —, en
    // cuanto vence la reserva de quien la despachó.
    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId AND kind = 'SALE' AND legacy_shadow = 0
        AND processor = 'ANGELPAY' AND host_approved IS NULL AND server_payment_id IS NULL
        AND (lease_until IS NULL OR lease_until < :now)
        AND (history_read_at IS NOT NULL OR (""" + SQL_CONSULTABLE_EN_EL_HISTORIAL + """ AND created_at < :olderThan))
        ORDER BY (history_read_at IS NULL), created_at ASC LIMIT 50""")
    suspend fun getUnknownRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    // 🔴 29-sep-2026: sólo VENTAS — esta recuperación registra con los registradores de VENTA. Una devolución aprobada sin
    // registrar la cierra su cola (`pending_refunds`); hoy sólo la dejaba fuera que su contexto no se deja leer como venta.
    // 🔴 F1 bloque 3 (I2): y la DESCARTADA de AngelPay con su aprobado final escrito (la cerró la ventana y conserva su motivo):
    // desde ese commit es una obligación de registro, con la llave del intento ([SQL_APROBADO_TRAS_LA_VENTANA]).
    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId AND kind = 'SALE'
        AND ((state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO') AND (host_approved = 1 OR state = 'AUTORIZADO'))
             OR """ + SQL_APROBADO_TRAS_LA_VENTANA + """)
        AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getApprovalRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    /**
     * 🔴 F1 bloque 4 (I3): tomar la consulta NO gasta el turno. Deja la lectura DESPACHADA en la fila (`history_read_at`) con la
     * reserva de quien la toma; el turno se gasta en [terminarConsultaSinCoincidencia] cuando su respuesta queda escrita.
     */
    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, history_read_at = :now
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved IS NULL AND server_payment_id IS NULL
        AND kind = 'SALE' AND legacy_shadow = 0 AND processor = 'ANGELPAY'
        AND (history_read_at IS NOT NULL OR """ + SQL_CONSULTABLE_EN_EL_HISTORIAL + """)
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimUnknownRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /**
     * 🔴 F1 bloque 4: la respuesta VÁLIDA del historial sin coincidencia («todavía no aparece») de la lectura de [ownedLease] quedó
     * escrita: se gasta UN turno (tope 5) y la fila descansa hasta [pausaHasta]. Un trabajador con la reserva vencida también la
     * termina si nadie la volvió a tomar; si otro la tomó, su reserva es otra y ésta no cuenta dos veces.
     */
    @Query("""UPDATE payment_attempts SET history_read_at = NULL, history_checks = MIN(history_checks + 1, 5),
        lease_until = :pausaHasta
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease AND history_read_at IS NOT NULL""")
    suspend fun terminarConsultaSinCoincidencia(attemptId: String, venueId: String, ownedLease: Long, pausaHasta: Long): Int

    /** 🔴 F1 bloque 4: fallo técnico (red, credenciales, lectura ilegible): pausa SIN gastar turno. */
    @Query("""UPDATE payment_attempts SET history_read_at = NULL, lease_until = :pausaHasta
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease AND history_read_at IS NOT NULL""")
    suspend fun pausarConsultaSinGastarTurno(attemptId: String, venueId: String, ownedLease: Long, pausaHasta: Long): Int

    /**
     * 🔴 F1 bloque 4: la lectura de la PANTALLA (K19a) queda despachada en la fila ANTES de preguntar, sin importar su estado ni
     * quién tenga la reserva: si la app muere o la ventana cierra mientras pregunta, la recuperación la retoma. K19a conserva su
     * presupuesto interactivo: no gasta turnos de E1. Devuelve la marca a usar al terminar (la que ya hubiera, si E1 la tenía).
     */
    @Query("""UPDATE payment_attempts SET history_read_at = COALESCE(history_read_at, :now),
        lease_until = MAX(IFNULL(lease_until, 0), :leaseUntil)
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved IS NULL AND server_payment_id IS NULL
        AND kind = 'SALE' AND legacy_shadow = 0 AND processor = 'ANGELPAY'""")
    suspend fun despacharLecturaDeLaPantalla(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /** 🔴 F1 bloque 4: la lectura de la pantalla terminó sin aprobado: se quita su marca (sólo la suya). */
    @Query("""UPDATE payment_attempts SET history_read_at = NULL
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND history_read_at = :despachadaEn""")
    suspend fun terminarLecturaDeLaPantalla(attemptId: String, venueId: String, despachadaEn: Long): Int

    /** 🔴 ¿Qué cobro de ESTE proceso está usando el lector ahora? ([PaymentAttemptEntity.SQL_APARTA_EL_APARATO]) */
    @Query("SELECT * FROM payment_attempts WHERE " + PaymentAttemptEntity.SQL_APARTA_EL_APARATO + " LIMIT 1")
    suspend fun findTerminalHold(processToken: String): PaymentAttemptEntity?

    /**
     * 🔴 ¿Hay un cobro cuyo desenlace financiero NO se conoce?
     *
     * Hermana de [findTerminalHold] pero NO la misma pregunta, y confundirlas cuesta dinero
     * en las dos direcciones:
     *  - [findTerminalHold] contesta «¿está apartada la terminal?» e incluye PREPARANDO,
     *    porque para no admitir un segundo cobro basta con que alguien esté en la fila.
     *  - Ésta contesta «¿pudo haberse movido dinero?» y deja PREPARANDO FUERA a propósito:
     *    en ese estado, por construcción, ninguna llamada capaz de autorizar empezó, así que
     *    sí es prueba de que no hubo cobro. Meterlo aquí volvería incierta toda cancelación
     *    legítima antes de acercar la tarjeta.
     *
     * Sin filtro de venue: la terminal es UNA. Un cobro sin resolver del aparato importa
     * aunque el turno haya cambiado de sucursal.
     *
     * Excluye las filas HEREDADAS (`legacy_shadow = 1`): son de un APK anterior, ningún ViewModel
     * vivo puede ser su dueño, y adoptarlas como «mi cobro pendiente» (AngelPay recreado) o dejar que
     * tapen para siempre un «no se cobró» legítimo sería un bloqueo que en 2.9.2 no existía. Siguen
     * contadas en [observeUnresolvedCount].
     */
    // 🔴 29-sep-2026: sólo VENTAS. Una devolución sin resolver no es «un cobro»: contarla aquí la hacía pasar por el cobro
    // pendiente de una solicitud del POS o de un callback vacío. Las devoluciones tienen su propia consulta, por pago
    // ([devolucionSinResolver]).
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0 AND kind = 'SALE'
        AND (state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
             OR (state = 'DESCARTADA' AND server_processor_evidence IS 'APPROVED'))
        ORDER BY updated_at DESC LIMIT 1""")
    suspend fun findUnresolvedCharge(): PaymentAttemptEntity?

    /**
     * 🔴 ¿Cuál de los cobros sin resolver es EL DE ESTA SOLICITUD del POS?
     *
     * Nació el 2026-09-12 junto con la cerca por venta: desde que una obligación pendiente ya no
     * apaga el aparato, pueden convivir VARIAS a la vez, y entonces «el más reciente» deja de ser
     * una respuesta — sería atribuirle a una cuenta el desenlace de otra. Se busca por la identidad
     * que el ViewModel recreado SÍ conserva (`_socketRequestId` vive en el SavedStateHandle) y que
     * la fila guarda en su contexto.
     *
     * El fragmento lo arma [PaymentAttemptLedger]; aquí sólo se compara texto, igual que la cerca
     * por orden. `PREPARANDO` queda fuera por el mismo motivo que en [findUnresolvedCharge].
     */
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0
        AND (state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
             OR (state = 'DESCARTADA' AND server_processor_evidence IS 'APPROVED')
             -- Codex r7 (P1-2): una liberada con VETO del servidor también es de ESTA solicitud al recrear la pantalla.
             OR (state = 'DESCARTADA' AND server_veto IS NOT NULL))
        AND instr(payment_context_json, :requestJsonFragment) > 0
        ORDER BY updated_at DESC LIMIT 1""")
    suspend fun findUnresolvedForRequest(requestJsonFragment: String): PaymentAttemptEntity?

    /**
     * 🔴 F1 bloque 3 (hueco 4): cuántos intentos de ESTA solicitud pueden ser dueños de un resultado del SDK que llega tarde — el
     * banco no les contestó y no hay Payment: en curso, en duda, o cerrados por una persona o por la ventana. Con más de uno, la
     * solicitud no identifica el intento.
     */
    @Query("""SELECT COUNT(*) FROM payment_attempts WHERE legacy_shadow = 0 AND kind = 'SALE'
        AND terminal_payment_request_id = :requestId AND host_approved IS NULL AND server_payment_id IS NULL
        AND (state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO')
             OR (state = 'DESCARTADA' AND (IFNULL(last_error, '') LIKE 'declarado_sin_cobro:%' OR server_outcome IS NOT NULL)))""")
    suspend fun duenosPosiblesDeUnResultado(requestId: String): Int

    /**
     * Cuántas obligaciones pendientes hay en el APARATO. Es lo que permite distinguir «no hay con
     * qué confundirse» (una sola, se puede adoptar a ciegas como siempre) de «hay varias», donde
     * adivinar sería atribuir dinero. Sin venue: la terminal es UNA.
     */
    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE legacy_shadow = 0 AND kind = 'SALE'
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    suspend fun countUnresolvedCharges(): Int

    /**
     * 🔴 Founder, 29-sep-2026: una devolución SIN RESOLVER de ESTE pago —la llamada salió y no se supo el desenlace, o
     * AngelPay la aprobó y falta registrarla— impide intentar OTRA devolución del MISMO pago: sería devolver dos veces.
     * Nunca detiene una venta ni la devolución de otro pago. El fragmento lo arma [PaymentAttemptLedger.devolucionSinResolver].
     */
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0 AND kind = 'REFUND'
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND instr(payment_context_json, :fragmentoDelPago) > 0
        ORDER BY updated_at DESC LIMIT 1""")
    suspend fun devolucionSinResolver(fragmentoDelPago: String): PaymentAttemptEntity?


    /**
     * Reserva atómica: comprobar-y-insertar en UNA sentencia.
     *
     * 🔴 Un `SELECT` seguido de un `INSERT` separado NO es una reserva: dos hilos leen
     * «libre» y los dos insertan. `INSERT … SELECT … WHERE NOT EXISTS` lo decide SQLite
     * en una sola sentencia, así que exactamente uno gana.
     *
     * Devuelve el rowid insertado, o -1 cuando otro intento ya tiene la terminal
     * (o cuando la orden ya tiene un cobro sin resolver, o cuando la solicitud remota está CERCADA).
     *
     * Son TRES cercas distintas y cada una protege algo distinto:
     *  1. **El aparato**: sólo un cobro corriendo en ESTE proceso; ver [PaymentAttemptEntity.SQL_APARTA_EL_APARATO].
     *  2. **La venta**, por `orderId`: desde F1 (17-nov) la TARJETA de una cuenta con dinero en juego o con una duda; ver
     *     [PaymentAttemptEntity.SQL_CERCA_LA_TARJETA]. Lo de abajo es la regla del dinero ([PaymentAttemptEntity.SQL_CERCA_LA_VENTA]), el MISMO
     *     fragmento que leen [retencionDeLaVenta] y el CAS a AUTORIZANDO/KERNEL_ACTIVO, así que no pueden divergir. Cerca un
     *     cobro corriendo o ya respondido (de cualquier proceso), la evidencia del servidor o su veto y la cuarentena por reloj
     *     de ESTE proceso. 🔴 Founder, 25-sep: una duda sin dinero conocido ya no cerca nada, ni en la PAX ni en la Nexgo: se
     *     AVISA y decide el cajero. Una DESCARTADA con evidencia positiva DURABLE del servidor (fix 4, D3c: liberada por la
     *     ventana, con el banco aprobando después) sigue cercando su venta INCLUSO si la nueva solicitud del POS es otra;
     *     lo mismo con un VETO durable (Codex r7, P1-2), sin reabrir la fila. Sin identidad de venta nada se cerca (r6).
     *     🔴 Sin filtro de venue a propósito: la terminal es UNA, y un cobro de la cuenta 7 sigue siendo de la cuenta 7 aunque
     *     el turno haya cambiado de sucursal. Ésta es la que impide el cobro doble mientras el aparato cobra las demás cuentas.
     *  3. **La solicitud del POS**, por lápida/cancel/desenlace/cierre para ejecución en la bandeja.
     *
     * 🛑 CERCA (C.5 / H.3, 11-sep): ningún intento nuevo de una solicitud remota cuya fila en la bandeja
     * tenga lápida (`NOT_FOUND_ANSWERED`), cancelación aceptada (`cancel_accepted_at`) o un desenlace final
     * ya escrito (`final_emitted_at`). Va DENTRO de esta sentencia, no en una lectura previa: un reintento
     * y un cancel simultáneos no pueden ganar los dos. 🔴 A propósito NO exige «la bandeja está en
     * PROCESSING»: tras un rechazo reintentable la solicitud sigue en PROCESSING y el reintento es legítimo.
     * 🔒 B1 (21-oct): tampoco de una solicitud CERRADA PARA EJECUCIÓN (`execution_closed_at`, [cerrarSolicitudSiCeso]):
     * la terminal ya certificó que nada suyo sigue vivo, y esa certificación sólo es verdad si nada vuelve a empezar.
     *
     * Las filas HEREDADAS (`legacy_shadow = 1`) no cuentan para reservar: en 2.9.2 no reservaban.
     */
    @Query("""INSERT OR IGNORE INTO payment_attempts (
            attempt_id, venue_id, processor, kind, state, state_version,
            amount_cents, tip_cents, currency, recording_route, context_schema_version,
            payment_context_json, verify_attempts, created_at, updated_at, terminal_payment_request_id, process_token,
            shift_membership, local_shift_session_id, cash_drawer_session_id)
        SELECT :attemptId, :venueId, :processor, :kind, 'PREPARANDO', 0,
            :amountCents, :tipCents, 'MXN', :recordingRoute, 1,
            :contextJson, 0, :now, :now, :terminalPaymentRequestId, :processToken,
            :shiftMembership, :localShiftSessionId, :cashDrawerSessionId
        WHERE (:shiftMembership != 'MANAGED' OR EXISTS (SELECT 1 FROM local_shift_sessions
            WHERE venue_id = :venueId AND local_session_id = :localShiftSessionId AND local_status = 'OPEN'))
        AND NOT EXISTS (SELECT 1 FROM payment_attempts WHERE """ + PaymentAttemptEntity.SQL_APARTA_EL_APARATO + """)
        AND (:orderJsonFragment IS NULL OR NOT EXISTS (
            SELECT 1 FROM payment_attempts WHERE """ + PaymentAttemptEntity.SQL_CERCA_LA_TARJETA + """
            AND instr(payment_context_json, :orderJsonFragment) > 0))
        AND (:terminalPaymentRequestId IS NULL OR NOT EXISTS (
            SELECT 1 FROM remote_payment_requests cerca
            WHERE cerca.request_id = :terminalPaymentRequestId
            AND (cerca.status = 'NOT_FOUND_ANSWERED' OR cerca.cancel_accepted_at IS NOT NULL
                 OR cerca.final_emitted_at IS NOT NULL OR cerca.execution_closed_at IS NOT NULL)
        ))""")
    suspend fun reserveTerminal(
        attemptId: String, venueId: String, processor: String, kind: String,
        amountCents: Long, tipCents: Long, recordingRoute: String,
        contextJson: String, orderJsonFragment: String?, now: Long,
        terminalPaymentRequestId: String?, processToken: String,
        shiftMembership: String = "UNKNOWN", localShiftSessionId: String? = null, cashDrawerSessionId: String? = null,
    ): Long

    /**
     * Por qué una solicitud remota ya no admite otro intento, o 'LIBRE'. Sólo LEE la bandeja: la usa el
     * ViewModel para traducir un fallo de la cerca a lo que de verdad pasó («El POS canceló este cobro»
     * o «Este cobro ya se cerró»), nunca a «No se pudo guardar el intento». null = la bandeja no la tiene.
     * B1: una solicitud cerrada para ejecución (`execution_closed_at`) también es 'CERRADA'.
     */
    @Query("""SELECT CASE
            WHEN cancel_accepted_at IS NOT NULL THEN 'CANCELADA_POR_EL_POS'
            WHEN status = 'NOT_FOUND_ANSWERED' OR final_emitted_at IS NOT NULL OR execution_closed_at IS NOT NULL THEN 'CERRADA'
            ELSE 'LIBRE' END
        FROM remote_payment_requests WHERE request_id = :requestId""")
    suspend fun cercaDeSolicitud(requestId: String): String?

    /** N0/E6: la capacidad DURABLE del servidor que entregó la solicitud (0 si no la trajo o no existe). */
    @Query("SELECT attempt_link_version FROM remote_payment_requests WHERE request_id = :requestId")
    suspend fun attemptLinkVersionDeSolicitud(requestId: String): Int?

    /**
     * C.5 — CAS del EFECTIVO/CRIPTO de un cobro remoto, ANTES de registrar. Gana sólo si la solicitud
     * sigue reclamada (PROCESSING) y nadie la canceló ni la cerró; desde aquí el cancel remoto contesta
     * ACTIVE. Idempotente para la misma solicitud (COALESCE): un segundo arranque del mismo cobro no
     * pierde. Devuelve 1 si puede arrancar, 0 si no. B1: tampoco sobre una solicitud cerrada para ejecución.
     */
    @Query("""UPDATE remote_payment_requests
        SET execution_started_at = COALESCE(execution_started_at, :now), updated_at = :now
        WHERE request_id = :requestId AND status = 'PROCESSING'
        AND cancel_accepted_at IS NULL AND final_emitted_at IS NULL AND execution_closed_at IS NULL""")
    suspend fun iniciarEjecucionNoTarjeta(requestId: String, now: Long): Int

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // B1 (21-oct, «ninguna duda de cobro sin salida»): «el SDK regresó» y la solicitud «cerrada para ejecución».
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * B1: toda la interacción de ESTE intento con el SDK terminó ([PaymentAttemptEntity.sdkReturnedAt]). Conserva la PRIMERA
     * vez (COALESCE) y NO toca `state`, `state_version` ni `updated_at`: es un hecho, no una transición (la cuarentena y la
     * poda siguen midiendo lo de siempre). Devuelve 1 si la fila existe.
     */
    @Query("UPDATE payment_attempts SET sdk_returned_at = COALESCE(sdk_returned_at, :now) WHERE attempt_id = :attemptId")
    suspend fun marcarSdkRegreso(attemptId: String, now: Long): Int

    /**
     * B1: cuántos intentos de ESTA solicitud PUEDEN SEGUIR VIVOS ([PaymentAttemptEntity.SQL_PUEDE_SEGUIR_VIVO]). La solicitud
     * se reconoce por su columna o por el `instr` de siempre ([com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestDao.contarIntentosBloqueadores]):
     * las filas anteriores a la v35 sólo la llevan en el contexto.
     */
    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE (terminal_payment_request_id = :requestId
               OR instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0)
        AND """ + PaymentAttemptEntity.SQL_PUEDE_SEGUIR_VIVO)
    suspend fun intentosQuePuedenSeguirVivos(requestId: String, processToken: String): Int

    /**
     * B1: las reservas (PREPARANDO) de esta solicitud que dejó OTRO proceso pasan a DESCARTADA: por construcción ninguna llamada
     * capaz de cobrar empezó, y su proceso murió, así que ya no pueden avanzar (su `markKernelEntered`/`markAuthorizing` exige
     * PREPARANDO). Nunca una fila HEREDADA: su PREPARANDO no prueba nada (en 2.9.2 el contactless entraba al kernel así).
     */
    @Query("""UPDATE payment_attempts
        SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now, last_error = 'preparando_de_proceso_terminado'
        WHERE (terminal_payment_request_id = :requestId
               OR instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0)
        AND state = 'PREPARANDO' AND legacy_shadow = 0 AND process_token IS NOT :processToken""")
    suspend fun descartarPreparandoDeOtrosProcesos(requestId: String, processToken: String, now: Long): Int

    /**
     * B1: escribe el cierre en la bandeja. Las MISMAS condiciones que lee [cerrarSolicitudSiCeso], otra vez dentro del UPDATE:
     * reclamada (PROCESSING), sin efectivo/cripto arrancado, sin final emitido y sin cierre previo. El status NO cambia.
     */
    @Query("""UPDATE remote_payment_requests
        SET execution_closed_at = :now, updated_at = :now
        WHERE request_id = :requestId AND status = 'PROCESSING' AND execution_started_at IS NULL
        AND final_emitted_at IS NULL AND execution_closed_at IS NULL""")
    suspend fun bandejaCerrarParaEjecucion(requestId: String, now: Long): Int

    /**
     * 🔒 B1 — «cerrada para ejecución»: la terminal CERTIFICA que nada de esta solicitud puede seguir ejecutándose ni volver a
     * empezar. Es la evidencia que el SERVIDOR pide para soltar la RANURA de la terminal (`executionAlive=false` +
     * `closedForExecution=true`); nunca dice «no se cobró» — el dinero de esa venta lo deciden el banco o una persona.
     *
     * En UNA transacción:
     *  1. la bandeja está reclamada (PROCESSING), sin efectivo/cripto arrancado (`execution_started_at`) y sin final emitido;
     *  2. NINGÚN intento de la solicitud puede seguir vivo ([intentosQuePuedenSeguirVivos]);
     *  3. se descartan las reservas de OTROS procesos ([descartarPreparandoDeOtrosProcesos]);
     *  4. se escribe `execution_closed_at` ([bandejaCerrarParaEjecucion]); si no aterriza, se revierte todo.
     * El status de la bandeja sigue PROCESSING a propósito: un aprobado tardío la resuelve por `resolverBandejaConGanador`.
     *
     * Devuelve true si cerró o si YA estaba cerrada (sin volver a escribir; se revalida que nada siga vivo: un cierre nunca
     * certifica lo que la libreta desmiente). False en cualquier otro caso, sin escribir nada.
     *
     * ⚠️ No conoce la PROPIEDAD en memoria de la solicitud: con CERO intentos la cierra aunque ESTE proceso tenga abierta la
     * pantalla esperando «Tarjeta». Esa pregunta es de quien llama (la sonda, B2), igual que en [cancelarTrasReclamar].
     * ⚠️ Sólo vale con el SDK EMBEBIDO: [PaymentAttemptLedger.cerrarSolicitudSiCeso] lo filtra antes de llegar aquí.
     */
    @Transaction
    suspend fun cerrarSolicitudSiCeso(requestId: String, processToken: String, now: Long): Boolean {
        val bandeja = bandejaDe(requestId) ?: return false
        if (bandeja.executionClosedAt != null) return intentosQuePuedenSeguirVivos(requestId, processToken) == 0
        if (bandeja.status != RemotePaymentRequestEntity.STATUS_PROCESSING ||
            bandeja.executionStartedAt != null || bandeja.finalEmittedAt != null
        ) return false
        if (intentosQuePuedenSeguirVivos(requestId, processToken) > 0) return false
        descartarPreparandoDeOtrosProcesos(requestId, processToken, now)
        // Nada escrito sin su pareja: si la bandeja no acepta el cierre, el descarte se revierte con ella.
        if (bandejaCerrarParaEjecucion(requestId, now) != 1) throw CierreParaEjecucionNoAceptado()
        return true
    }

    @Query("""SELECT COUNT(*) FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    fun observeUnresolvedCount(venueId: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND instr(payment_context_json, :orderJsonFragment) > 0 LIMIT 1""")
    suspend fun findUnresolvedOrder(venueId: String, orderJsonFragment: String): PaymentAttemptEntity?

    /**
     * 🔴 Gemela de LECTURA de la cerca 2 de [reserveTerminal] («la venta»): la fila que impide cobrar ESTA venta otra vez,
     * para NOMBRAR lo que aquella sentencia rechazó. Sólo con DINERO en juego ([PaymentAttemptEntity.SQL_CERCA_LA_VENTA]):
     * una duda sin dinero conocido ya no cerca nada, se avisa (founder, 25-sep). Es el MISMO fragmento que la guarda 2 y el
     * CAS, así que no pueden divergir — si divergieran, la pantalla diría «no se pudo guardar el intento» sobre una venta
     * que SÍ está cercada (founder, 21-sep). Sin filtro de venue a propósito. 🔴 [findUnresolvedOrder] NO sirve para esto:
     * filtra por venue y deja fuera PREPARANDO y DESCARTADA.
     */
    // F1 (17-nov): la gemela de la cerca 2, que desde F1 es la de la TARJETA ([PaymentAttemptEntity.SQL_CERCA_LA_TARJETA]).
    @Query("SELECT * FROM payment_attempts WHERE " + PaymentAttemptEntity.SQL_CERCA_LA_TARJETA +
        " AND instr(payment_context_json, :orderJsonFragment) > 0 LIMIT 1")
    suspend fun retencionDeLaVenta(orderJsonFragment: String, processToken: String): PaymentAttemptEntity?

    /**
     * 🔴 F1 (Codex código r1 #5): la cerca de la TARJETA de esta cuenta que pone OTRO intento, nunca el propio (su reserva PREPARANDO
     * también entra en [PaymentAttemptEntity.SQL_CERCA_LA_VENTA]). Es la misma condición del CAS ([casTransitionRow]), leída ANTES de
     * encender el lector: la evidencia de otra tarjeta de la cuenta puede llegar después de reservar.
     */
    @Query("SELECT * FROM payment_attempts WHERE " + PaymentAttemptEntity.SQL_CERCA_LA_TARJETA +
        " AND instr(payment_context_json, :orderJsonFragment) > 0 AND attempt_id != :attemptIdPropio LIMIT 1")
    suspend fun otraCercaDeLaTarjeta(orderJsonFragment: String, attemptIdPropio: String, processToken: String): PaymentAttemptEntity?

    /** F1: sólo el DINERO en juego de la venta ([PaymentAttemptEntity.SQL_CERCA_LA_VENTA]); una duda sin dinero no cuenta. */
    @Query("SELECT * FROM payment_attempts WHERE " + PaymentAttemptEntity.SQL_CERCA_LA_VENTA +
        " AND instr(payment_context_json, :orderJsonFragment) > 0 LIMIT 1")
    suspend fun dineroEnJuegoDeLaVenta(orderJsonFragment: String, processToken: String): PaymentAttemptEntity?

    /**
     * D9 (Mesas, Codex r3, 16-nov): ¿esta terminal tiene dinero de TARJETA de esta cuenta que el SERVIDOR todavía no confirma?
     * Junta en UNA sentencia —una sola fotografía de la base— las dos fuentes por cuenta: la cola (el mismo predicado que
     * `PendingPaymentDao.hayCobroSinRegistrar` y `hayCobroSubiendose`) y la libreta ([retencionDeLaVenta], la cerca 2). Leídas en
     * dos consultas, el traspaso libreta → cola (una transacción) podía caer ENTRE las dos y ninguna lo veía.
     * 'SIN_REGISTRAR' · 'SUBIENDO' · 'LIBRETA' · null (nada).
     */
    @Query("""SELECT CASE
        WHEN EXISTS(SELECT 1 FROM pending_payments WHERE venue_id = :venueId AND order_id = :orderId
            AND (sync_status = 'FAILED' OR requiere_revision = 1) AND acknowledged = 0) THEN 'SIN_REGISTRAR'
        WHEN EXISTS(SELECT 1 FROM pending_payments WHERE venue_id = :venueId AND order_id = :orderId
            AND sync_status IN ('PENDING', 'SYNCING')) THEN 'SUBIENDO'
        WHEN EXISTS(SELECT 1 FROM payment_attempts WHERE """ + PaymentAttemptEntity.SQL_CERCA_LA_VENTA + """
            AND instr(payment_context_json, :orderJsonFragment) > 0) THEN 'LIBRETA'
        END""")
    suspend fun dineroDeTarjetaDeLaCuenta(venueId: String, orderId: String, orderJsonFragment: String, processToken: String): String?

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId
        AND (state IN ('HOST_RESPONDIO', 'AUTORIZADO', 'REGISTRO_FALLIDO') OR """ + SQL_APROBADO_TRAS_LA_VENTANA + """)
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /**
     * 🔴 D5 (encargo 533d77, Codex r1 #1): el MISMO reclamo, pero nunca sobre una venta de la PAX cuyo SDK puede seguir dentro de
     * ESTE proceso: `HOST_RESPONDIO` sin `sdk_returned_at`, el cierre EMV pendiente. Sólo ese estado (Codex r2 #2): `AUTORIZADO` y
     * `REGISTRO_FALLIDO` ya pasaron el cierre aunque la marca del fin del SDK no se haya podido escribir, y se recuperan como siempre.
     * Con un chip lento, la fila pasaba de 2 min mientras `CompleteEmvTrans` seguía corriendo; la recuperación la reclamaba, el
     * chip rechazaba, la pantalla la dejaba en duda y la recuperación registraba igual, con el contexto que ya tenía en memoria.
     * La condición va DENTRO del UPDATE: la pantalla deja la fila en duda ANTES de marcar el fin del SDK, así que no hay un
     * instante en que la recuperación pueda reclamarla. Sólo Blumon: la Nexgo no marca así su fin del SDK.
     */
    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId
        AND (state IN ('HOST_RESPONDIO', 'AUTORIZADO', 'REGISTRO_FALLIDO') OR """ + SQL_APROBADO_TRAS_LA_VENTANA + """)
        AND (lease_until IS NULL OR lease_until < :now)
        AND NOT (processor = 'BLUMON' AND state = 'HOST_RESPONDIO' AND process_token IS :processToken AND sdk_returned_at IS NULL)""")
    suspend fun claimRecoveryConElSdkFuera(attemptId: String, venueId: String, now: Long, leaseUntil: Long, processToken: String): Int

    /**
     * 🔴 `last_error` CONSERVA la marca de cuarentena si ya la traía. La recuperación admite
     * `HOST_RESPONDIO` por ANTIGÜEDAD y, si el registro falla, escribía «Cobrado; registro
     * pendiente» encima — borrando la única señal de que nadie acreditó el final del SDK, y
     * dejando que F0 soltara el lector con la llamada nativa todavía posiblemente viva
     * (Codex, 2026-09-12). El mensaje nuevo se antepone para no perder el diagnóstico.
     */
    @Query("""UPDATE payment_attempts SET state = :newState, state_version = state_version + 1,
        updated_at = :now,
        last_error = CASE WHEN instr(COALESCE(last_error,''), 'handoff_contrato_contradictorio') > 0 THEN last_error
                          WHEN COALESCE(last_error,'') = 'cuarentena_por_antiguedad'
                          THEN 'cuarentena_por_antiguedad' ELSE :error END
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND (state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO') OR """ + SQL_APROBADO_TRAS_LA_VENTANA + """)""")
    suspend fun completeRecovery(attemptId: String, venueId: String, ownedLease: Long,
        newState: String, now: Long, error: String?): Int

    /**
     * 🔴 F1 bloque 3 (R2/I1): la PRIMERA escritura del aprobado FINAL de AngelPay (SDK, SDK con la pantalla recreada, K19a y E1). No
     * depende del estado, de la reserva ni de quién sea dueño de la consulta: sólo de la sucursal, el intento, el procesador y la
     * venta. Guarda la referencia, la autorización y el origen (`operation_id`, que AngelPay no usa), y quita la lectura despachada.
     * Repetir el mismo par es inocuo; un par distinto no pisa el primero (lo anota [anotarAprobadoIncompatible]).
     */
    @Query("""UPDATE payment_attempts SET host_approved = 1, reference_number = :referencia, auth_code = :autorizacion,
        operation_id = COALESCE(operation_id, :origen), history_read_at = NULL,
        updated_at = :now, state_version = state_version + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND processor = 'ANGELPAY' AND kind = 'SALE' AND legacy_shadow = 0
        AND host_approved IS NULL""")
    suspend fun sellarAprobadoFinalAngelPay(attemptId: String, venueId: String, referencia: String?, autorizacion: String?,
        origen: String, now: Long): Int

    /**
     * 🔴 F1 bloque 3: la transición del aprobado recién sellado, en la MISMA transacción. Lo que esperaba al banco y lo que cerró una
     * PERSONA ([PaymentAttemptEntity.SQL_DECLARADA_POR_UNA_PERSONA] sin `host_approved`) pasan a `HOST_RESPONDIO` (registro de
     * siempre). Lo que cerró la ventana se queda `DESCARTADA` con su motivo y es elegible para el registrador
     * ([SQL_APROBADO_TRAS_LA_VENTANA]). Con veto, nada sube: Avoqado lo concilia. Rastro en `last_error`, nunca un control.
     */
    @Query("""UPDATE payment_attempts SET state = 'HOST_RESPONDIO', lease_until = NULL, updated_at = :now,
        state_version = state_version + 1,
        last_error = CASE WHEN state = 'DESCARTADA' THEN 'aprobado_tras_declaracion:' || IFNULL(last_error, '') ELSE last_error END
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved = 1 AND server_payment_id IS NULL
        -- r9 P1-1 (se conserva): el veredicto VIVO del SDK aterriza aunque haya veto; una duda o una fila cerrada con veto no sube (I-1).
        AND (state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO')
             OR (server_veto IS NULL AND (state = 'INDETERMINADO'
                 OR (state = 'DESCARTADA' AND legacy_shadow = 0 AND kind = 'SALE'
                     AND (IFNULL(last_error, '') LIKE 'declarado_sin_cobro:%' OR server_outcome IS 'OPERATOR_NO_INSTRUMENT')))))""")
    suspend fun abrirRegistroDelAprobado(attemptId: String, venueId: String, now: Long): Int

    /** La DESCARTADA por la ventana con su aprobado: se suelta la reserva para que el registrador la tome ya. */
    @Query("""UPDATE payment_attempts SET lease_until = NULL WHERE attempt_id = :attemptId AND venue_id = :venueId
        AND """ + SQL_APROBADO_TRAS_LA_VENTANA)
    suspend fun soltarReservaDelAprobadoTrasLaVentana(attemptId: String, venueId: String): Int

    /**
     * 🔴 F1 bloque 3 (I1, contradicción): un SEGUNDO par incompatible para el mismo intento (otra referencia o autorización, o un
     * aprobado sobre un rechazo del host). No se pisa la primera prueba: la segunda queda en `last_error` y la fila queda en revisión
     * con el veto local de la terminal (cerca su cuenta y sale en el aviso). No observado en un negocio.
     */
    @Query("""UPDATE payment_attempts SET server_veto = COALESCE(server_veto, 'SDK_CONTRADICTION'),
        last_error = 'aprobado_incompatible:' || IFNULL(:referencia, '') || '/' || IFNULL(:autorizacion, '') || ':' || IFNULL(last_error, ''),
        updated_at = :now, state_version = state_version + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND processor = 'ANGELPAY' AND kind = 'SALE'""")
    suspend fun anotarAprobadoIncompatible(attemptId: String, venueId: String, referencia: String?, autorizacion: String?, now: Long): Int

    /**
     * 🔴 F1 bloque 3: [sellarAprobadoFinalAngelPay] y su transición en UNA transacción. Devuelve qué quedó; un error de escritura
     * revierte las dos y quien llama conserva la protección en memoria.
     */
    @Transaction
    suspend fun escribirAprobadoFinalAngelPay(venueId: String, attemptId: String, referencia: String?, autorizacion: String?,
        origen: String, now: Long): AprobadoFinal {
        val fila = getById(attemptId) ?: return AprobadoFinal.SIN_FILA
        if (fila.venueId != venueId || fila.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY ||
            fila.kind != PaymentAttemptEntity.KIND_SALE || fila.legacyShadow) return AprobadoFinal.AJENO
        if (fila.hostApproved == true) {
            val mismo = (fila.referenceNumber == null || fila.referenceNumber == referencia) &&
                (fila.authCode == null || fila.authCode == autorizacion)
            if (mismo) return AprobadoFinal.YA_ESTABA
            anotarAprobadoIncompatible(attemptId, venueId, referencia, autorizacion, now)
            return AprobadoFinal.INCOMPATIBLE
        }
        if (fila.hostApproved == false) {
            anotarAprobadoIncompatible(attemptId, venueId, referencia, autorizacion, now)
            return AprobadoFinal.INCOMPATIBLE
        }
        if (sellarAprobadoFinalAngelPay(attemptId, venueId, referencia, autorizacion, origen, now) != 1) return AprobadoFinal.SIN_FILA
        if (abrirRegistroDelAprobado(attemptId, venueId, now) == 1) return AprobadoFinal.REGISTRABLE
        if (soltarReservaDelAprobadoTrasLaVentana(attemptId, venueId) == 1) return AprobadoFinal.REGISTRABLE_TRAS_LA_VENTANA
        return AprobadoFinal.GUARDADO
    }

    /** Returns -1 when the PK already exists (attemptId reuse — double-charge signal, never silent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attempt: PaymentAttemptEntity): Long

    @Query("SELECT * FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun getById(attemptId: String): PaymentAttemptEntity?

    @Query("SELECT * FROM payment_attempts WHERE attempt_id = :attemptId AND venue_id = :venueId")
    fun observeAttempt(attemptId: String, venueId: String): kotlinx.coroutines.flow.Flow<PaymentAttemptEntity?>

    /**
     * The CAS everything rides on (spec §4.4): a transition only lands if the row
     * is still in one of the expected states. Returns 0 when it didn't match —
     * caller logs and moves on (worker/callback/manual races resolve themselves).
     *
     * 🔴 La condición extra de `AUTORIZANDO` es la cerca de aparato de [findTerminalHold], un piso
     * más abajo: sin ella, un ViewModel recreado entraba a la autorización online encima de un cobro
     * en vuelo. Es el MISMO fragmento, sin la reserva ([PaymentAttemptEntity.SQL_EJECUCION_VIVA]):
     * sólo cierra el paso mientras OTRO cobro de ESTE proceso está corriendo. Founder, 25-sep:
     * «ninguna duda apaga la terminal» — una duda guardada o lo que dejó un proceso muerto no cierra nada.
     *
     * ⚠️ En esa cerca del APARATO `PREPARANDO` queda fuera a propósito, igual que en [findUnresolvedCharge]: una fila
     * que sólo reservó no puede haber movido dinero, y meterla aquí trabaría cada reintento normal.
     *
     * 🔴 Fix 5 (Codex r5, P1-A): la cerca de la MISMA VENTA también aquí, ATÓMICA en el mismo UPDATE. Carrera
     * reproducida: A liberada (DESCARTADA sin marca) → B RESERVA la misma venta (la guarda 2 la deja pasar) → llega la
     * aprobación bancaria tardía de A (marca) → B pasaba a AUTORIZANDO. Entre reservar y autorizar hay una espera real (el
     * vínculo N1). Es el MISMO fragmento de la guarda 2 ([PaymentAttemptEntity.SQL_CERCA_LA_VENTA]): el dinero que llega a
     * una duda de ESA venta también cierra el paso; una duda sin dinero no (founder, 25-sep). Sin alias: dentro de
     * `FROM payment_attempts other` los nombres sin prefijo son de `other` (la fila de al lado), nunca de la que se
     * actualiza (`payment_attempts.…`). El `"orderId":"…"` se recorta de la propia fila: 11 = longitud de `"orderId":"`,
     * y termina en la comilla siguiente — la misma forma compacta que escribe Gson y que ya comparan la guarda 2 y la
     * migración 34→35. Otras ventas siguen entrando.
     */
    // 🔴 Codex final-3 (23-sep): la MISMA cerca también para entrar al LECTOR (KERNEL_ACTIVO). El lector puede aprobar SOLO
    // (contactless offline, EMV local) sin pasar por AUTORIZANDO; con la cerca sólo en la autorización, B entraba al lector sobre
    // la venta de A en cuanto la puerta escribía el dinero de A (KERNEL_ACTIVO = 1, reproducido con el esquema 40).
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
           AND (:newState NOT IN ('AUTORIZANDO','KERNEL_ACTIVO') OR (
               (shift_membership != 'MANAGED' OR EXISTS (SELECT 1 FROM local_shift_sessions
                   WHERE venue_id = payment_attempts.venue_id AND local_session_id = payment_attempts.local_shift_session_id
                   AND local_status = 'OPEN'))
               AND NOT EXISTS (SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
                           AND """ + PaymentAttemptEntity.SQL_EJECUCION_VIVA + """)
               AND NOT EXISTS (SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
                   AND """ + PaymentAttemptEntity.SQL_CERCA_LA_TARJETA + """
                   AND instr(payment_attempts.payment_context_json, '"orderId":"') > 0
                   AND instr(payment_attempts.payment_context_json, '"orderId":""') = 0
                   AND instr(other.payment_context_json,
                             substr(payment_attempts.payment_context_json,
                                    instr(payment_attempts.payment_context_json, '"orderId":"'),
                                    11 + instr(substr(payment_attempts.payment_context_json,
                                                      instr(payment_attempts.payment_context_json, '"orderId":"') + 11), '"'))) > 0)))"""
    )
    suspend fun casTransitionRow(attemptId: String, expectedStates: List<String>, newState: String, now: Long, processToken: String): Int

    @Query("SELECT * FROM pending_payments WHERE venue_id = :venueId AND idempotency_key = :key LIMIT 2")
    suspend fun queuedSalesForHandoff(venueId: String, key: String): List<com.jaac.avoqado_tpv.core.data.local.entity.PendingPaymentEntity>

    @Query("SELECT * FROM pending_refunds WHERE venue_id = :venueId AND idempotency_key = :key LIMIT 1")
    suspend fun queuedRefundForHandoff(venueId: String, key: String): com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity?

    @Query("SELECT COUNT(*) FROM payment_attempts WHERE venue_id = :venueId AND attempt_id = :attemptId AND " +
        PaymentAttemptEntity.SQL_REGISTRO_POR_VEREDICTO)
    suspend fun registroLoDecideVeredicto(venueId: String, attemptId: String): Int

    /** Compatibilidad de callers antiguos: sólo un sucesor durable y demostrado puede asumir la obligación. */
    @Transaction
    suspend fun casTransition(attemptId: String, expectedStates: List<String>, newState: String, now: Long, processToken: String): Int {
        if (newState != PaymentAttemptEntity.STATE_ENTREGADA_A_COLA)
            return casTransitionRow(attemptId, expectedStates, newState, now, processToken)
        val owner = getById(attemptId) ?: return 0
        if (owner.state !in expectedStates) return 0
        val status = if (owner.kind == PaymentAttemptEntity.KIND_REFUND) {
            val row = queuedRefundForHandoff(owner.venueId, attemptId) ?: return 0
            if (!owner.checkHandoff(row).proven) return 0
            row.syncStatus
        } else {
            val row = queuedSalesForHandoff(owner.venueId, attemptId).singleOrNull() ?: return 0
            if (!owner.checkHandoff(row).proven) return 0
            row.syncStatus
        }
        if (status == "SUCCESS" && registroLoDecideVeredicto(owner.venueId, attemptId) == 1)
            return registrarPorVeredictoDelServidor(attemptId, now)
        // Sin veredicto se conserva la confirmación HTTP del sucesor.
        val target = if (status == "SUCCESS") PaymentAttemptEntity.STATE_REGISTRADO else newState
        return casTransitionRow(attemptId, expectedStates, target, now, processToken)
    }

    /**
     * CAS + host outcome in one statement — used the instant the host responds.
     *
     * 🔴 Codex r9 (P1-1): un RECHAZO (`hostApproved = 0`) no cierra un intento con VETO durable. El worker dejaba el veto y
     * un rechazo normal del SDK lo tapaba con DESCARTADA ⇒ «Reintentar», y la reserva del siguiente cobro pasaba: una
     * DESCARTADA con veto no aparta nada (a propósito, r6 P1-4). La aprobación bancaria que el servidor acreditó NO se
     * excluye aquí: la DESCARTADA con `server_processor_evidence = 'APPROVED'` YA cerca su venta en las reservas
     * (el aparato, desde el 25-sep, sólo lo aparta un cobro corriendo), y el rechazo del host es un dato verdadero que
     * conviene anotar. Una APROBACIÓN aterriza siempre.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               operation_id = :operationId, reference_number = :referenceNumber,
               auth_code = :authCode, host_approved = :hostApproved,
               lease_until = NULL, verify_attempts = 0
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
             AND (:hostApproved = 1 OR server_veto IS NULL)"""
    )
    suspend fun casHostResponded(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        operationId: String?, referenceNumber: String?, authCode: String?, hostApproved: Boolean
    ): Int

    /**
     * 🔴 D13 (encargo ff3278, Codex r16 #1): la marca «el banco aprobó» que no entró al contestar el banco, sellada por la libreta
     * en el MISMO paso en que la fila pasa a AUTORIZADO (nunca desde otro camino). No cambia el estado: deja `host_approved = 1` y
     * lo que dijo el banco si faltaba, para que un REGISTRO_FALLIDO posterior siga siendo candidato de la recuperación.
     */
    @Query(
        """UPDATE payment_attempts
           SET host_approved = 1, state_version = state_version + 1, updated_at = :now,
               operation_id = COALESCE(operation_id, :operationId),
               reference_number = COALESCE(reference_number, :referenceNumber),
               auth_code = COALESCE(auth_code, :authCode)
           WHERE attempt_id = :attemptId AND host_approved IS NULL AND state = 'AUTORIZADO'"""
    )
    suspend fun sellarAprobacionDelHost(attemptId: String, operationId: String?, referenceNumber: String?, authCode: String?, now: Long): Int

    /** CAS + card details (available at record time). */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               masked_pan = :maskedPan, card_brand = :cardBrand, entry_mode = :entryMode
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithCardDetails(
        attemptId: String, expectedStates: List<String>, newState: String, now: Long,
        maskedPan: String?, cardBrand: String?, entryMode: String?
    ): Int

    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               last_error = CASE WHEN instr(COALESCE(last_error,''), 'handoff_contrato_contradictorio') > 0 THEN last_error ELSE :error END
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
             AND (:newState != 'DESCARTADA' OR (
                 host_approved IS NOT 1 AND server_processor_evidence IS NOT 'APPROVED'
                 AND server_veto IS NULL AND server_payment_id IS NULL
                 AND server_outcome IS NULL))"""
    )
    suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int

    /**
     * 🔴 D5 (encargo 533d77, opción C, 16-nov): el banco APROBÓ esta venta de la PAX y después la tarjeta rechazó el cierre
     * (`CompleteEmvTrans`). No se sabe si Blumon TPV revierte el cargo, así que la fila deja de decir «el banco aprobó = dinero»:
     * HOST_RESPONDIO → INDETERMINADO, `host_approved` a NULL y la marca [PaymentAttemptEntity.MOTIVO_CHIP_RECHAZO_TRAS_APROBACION].
     * Desde ahí es una duda como las demás —la recuperación de aprobaciones ya no la registra sola; la cierran la evidencia del
     * servidor o la declaración de una persona— pero con dinero en juego: la marca cerca su venta ([PaymentAttemptEntity.SQL_CERCA_LA_VENTA]).
     * No toca `operation_id`, `reference_number` ni `auth_code`: lo que dijo el banco se conserva para conciliar con su portal.
     *
     * Todo DENTRO del UPDATE: sólo una VENTA de la PAX, no heredada, aprobada por el host y SIN nada del servidor (veredicto,
     * evidencia, veto ni Payment): con evidencia de cobro, quitar la aprobación sería perder información.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now,
               host_approved = NULL, last_error = 'chip_rechazo_tras_aprobacion'
           WHERE attempt_id = :attemptId AND legacy_shadow = 0 AND kind = 'SALE' AND processor = 'BLUMON'
             -- Codex r1 #2: también si la escritura de «el banco aprobó» falló y la fila se quedó en AUTORIZANDO, o si el barrido
             -- ya la puso en cuarentena por reloj antes del reintento (Codex r2 #1): el SDK ya regresó y la duda tiene su motivo real.
             AND ((state = 'HOST_RESPONDIO' AND host_approved = 1)
                  OR (state = 'AUTORIZANDO' AND host_approved IS NULL)
                  OR (state = 'INDETERMINADO' AND host_approved IS NULL AND last_error = 'cuarentena_por_antiguedad'))
             AND server_outcome IS NULL AND server_processor_evidence IS NOT 'APPROVED'
             AND server_veto IS NULL AND server_payment_id IS NULL"""
    )
    suspend fun casChipRechazoTrasAprobacion(attemptId: String, now: Long): Int

    /**
     * 🔴 «Ya revisé la terminal: no se cobró» para un cobro **LOCAL** (21-sep-2026, decisión del founder:
     * «los negocios normalmente cobran con tarjeta y no pueden quedar trabados»).
     *
     * Un cobro local no tiene solicitud del POS, así que **el servidor no retiene nada**: el candado es
     * enteramente de esta libreta. Por eso la salida vive aquí y no en el servidor — y por eso se escribe con
     * el mismo cuidado que cualquier CAS de dinero.
     *
     * 🔑 **Las cinco condiciones van DENTRO del UPDATE, no en una lectura previa.** Leer y luego escribir deja
     * la ventana en la que el veredicto del servidor llega entre una cosa y la otra; es el defecto que este
     * módulo ya pagó varias veces:
     *  1. `terminal_payment_request_id IS NULL` — sólo cobros locales. Los del POS conservan su camino, que
     *     además libera la ranura del servidor y deja su propia bitácora.
     *  2. `state = 'INDETERMINADO'` — un cobro que quedó sin veredicto; su único llamador, `declareChecked`, sólo pasa INDETERMINADO
     *     (`puedeDeclararseEnDuda`). Nunca AUTORIZANDO (revisión final, M-4, 26-sep): puede ser un cobro en vuelo.
     *  3. `host_approved` no verdadero — jamás sobre una aprobación del host: eso es dinero.
     *  4. `server_processor_evidence IS NOT 'APPROVED'` — el veto DURABLE manda sobre el testimonio de una persona.
     *  5. `server_answered_at IS NOT NULL` y sin veredicto con dinero — **primero el servidor tiene que haber
     *     CONTESTADO**; sin esa respuesta la salida ni se ofrece. 🔴 Codex r8 (P2-4): era `server_checked_at`, que es el
     *     turno de la recuperación y también se estampa tras un 401/403/404/5xx — un 503 bastaba para declarar.
     *
     * Devuelve 1 si cerró; 0 si alguna condición falla, y entonces la fila y su retención quedan intactas.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now, last_error = :motivo
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND legacy_shadow = 0
             AND terminal_payment_request_id IS NULL
             AND state = 'INDETERMINADO'
             AND (host_approved IS NULL OR host_approved = 0)
             AND server_processor_evidence IS NOT 'APPROVED'
             -- 🔴 Codex r6 (P1-3): y ningún VETO durable. Reproducido por Codex con las consultas reales en SQLite:
             -- sobre la MISMA fila consultada y con PAYMENT_CONTRADICTION, el CAS del servidor devolvía 0 y éste 1.
             -- Que el veto valga para la liberación del servidor y NO para el testimonio de una persona es al revés
             -- de lo que pesa cada evidencia: el del cajero es el más débil de los tres.
             AND server_veto IS NULL
             AND server_answered_at IS NOT NULL
             AND (server_outcome IS NULL
                  OR server_outcome NOT IN ('RECORDED','SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE'))
             -- 🔴 F1 bloque 2 (R1): en la PAX, nunca mientras el SDK de ESTE intento pueda regresar (misma ejecución, sin regreso).
             AND NOT (processor = 'BLUMON' AND process_token IS :processToken AND sdk_returned_at IS NULL)"""
    )
    suspend fun declararSinCobroLocal(attemptId: String, venueId: String, motivo: String, now: Long, processToken: String): Int

    /** F1 bloque 2: los intentos de venta de una solicitud del POS (por la columna o, en filas anteriores a la v35, por el contexto). */
    @Query("""SELECT * FROM payment_attempts WHERE kind = 'SALE'
        AND (terminal_payment_request_id = :requestId
             OR instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0) LIMIT 50""")
    suspend fun intentosDeLaSolicitud(requestId: String): List<PaymentAttemptEntity>

    /**
     * 🔴 D22 (Codex plan r1, bloqueante 1): la respuesta del envío se perdió, la terminal cerró la duda SÓLO en la libreta (sin
     * la marca) y después S6 trae la declaración que Avoqado sí guardó. El cierre ya no aplica (la fila no está INDETERMINADO):
     * esto sólo AGREGA la marca [PaymentAttemptEntity.SUFIJO_DECLARADO_EN_AVOQADO] a una declaración local ya cerrada, para que la
     * pantalla no siga diciendo «no aparece en Avoqado». No cambia estado, hora ni retención (la poda mide desde `updated_at`),
     * es idempotente y nunca toca una fila con dinero, veto o solicitud del POS.
     */
    @Query(
        """UPDATE payment_attempts SET last_error = last_error || :sufijo
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND legacy_shadow = 0
             AND terminal_payment_request_id IS NULL AND state = 'DESCARTADA'
             AND last_error LIKE 'declarado_sin_cobro:%' AND last_error NOT LIKE '%' || :sufijo
             AND (host_approved IS NULL OR host_approved = 0)
             AND server_processor_evidence IS NOT 'APPROVED' AND server_veto IS NULL
             AND (server_outcome IS NULL
                  OR server_outcome NOT IN ('RECORDED','SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE'))"""
    )
    suspend fun marcarDeclaracionEnAvoqado(attemptId: String, venueId: String, sufijo: String): Int

    /**
     * 🔴 Botón «No se cobró» del banner verde (founder, 3-nov: «A»): a qué cobros se les puede afirmar que no se cobraron. Lista para
     * ofrecer el botón, no para decidir: quien decide es el servidor o el CAS de [declararSinCobroLocal].
     *
     * **Intentos:** lo mismo que excluye [declararSinCobroLocal], salvo `terminal_payment_request_id` y `server_answered_at`, que deciden el
     * camino; `kind = 'SALE'` y sin contradicción. Quedan fuera `AUTORIZANDO`, `HOST_RESPONDIO`, `AUTORIZADO` y `REGISTRO_FALLIDO`, y la
     * cuarentena por reloj de ESTE proceso: nadie comprobó que el lector terminara. La declaración es de toda la solicitud, así que otro
     * intento suyo corriendo o con dinero también la deja fuera: el servidor no sabe lo que la libreta sabe de un aprobado sin registrar.
     *
     * **Solicitudes de la bandeja:** `PROCESSING` sin NINGÚN intento, y sin efectivo o cripto arrancado. Más estricto que el aviso, que cuenta
     * como «sin intento» a un `PREPARANDO` o un `KERNEL_ACTIVO`: el lector puede estar corriendo (la misma guarda de
     * [com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestDao.conciliarHuerfanaConElServidorConResultado]).
     *
     * La ventana de 10 min va en Kotlin, con la misma constante del aviso ([DeclaracionDesdeElAviso.vigentes]).
     */
    @Query("SELECT * FROM (" + SQL_POR_REVISAR + ") ORDER BY desde_millis DESC, clase ASC, clave ASC LIMIT :limite")
    // D20 (Codex r2) y F1 bloque 5: un solo orden estable (fecha, clase, ID) y [limite] para TODA la lista: «Ver anteriores» la amplía de
    // 50 en 50 hasta el último renglón ([contarPorRevisar] dice cuántos son).
    fun observarCobrosDeclarables(venueId: String, processToken: String, limite: Int): kotlinx.coroutines.flow.Flow<List<CobroDeclarable>>

    /** F1 bloque 5: el total EXACTO de «Por revisar» (la misma unión, sin tope). */
    @Query("SELECT COUNT(*) FROM (" + SQL_POR_REVISAR + ")")
    fun contarPorRevisar(venueId: String, processToken: String): kotlinx.coroutines.flow.Flow<Int>

    /** F1 bloque 5: UN renglón de «Por revisar» por su clave (sin tope: lo de «Ver anteriores» también se encuentra). */
    @Query("SELECT * FROM (" + SQL_POR_REVISAR + ") WHERE clase = :clase AND clave = :clave LIMIT 1")
    suspend fun renglonPorRevisar(venueId: String, processToken: String, clase: String, clave: String): CobroDeclarable?

    /**
     * SDK 1.0.19 (18-sep): la petición NO salió al host — lo acreditó el SDK de AngelPay (`authorizationAttempted = false`
     * con NUESTRA referencia) o la invocación que sabe que nunca lanzó el SDK ([PaymentAttemptLedger.markSinAutorizacion]
     * dice quién). CAS SÓLO desde `AUTORIZANDO` (el estado en que un intento AngelPay espera el resultado
     * del SDK) a `DESCARTADA`, **sin tocar `host_approved`** —por eso la bandeja deriva `PRE_AUTHORIZATION`
     * (`RemotePaymentRequestDao.resolverDesenlaceNegativo`) y nunca `PROCESSOR_DECLINED`— y con el motivo en `last_error`.
     *
     * No gana sobre: otro estado (`INDETERMINADO` —de la cuarentena por reloj o de un desenlace incierto—,
     * `HOST_RESPONDIO`, `REGISTRADO`…), otro venue, una fila heredada, una devolución, otro procesador, un veredicto
     * del host ya escrito, ni NINGUNA evidencia del servidor (Payment, veredicto o aprobación bancaria): con evidencia
     * de dinero, «no se cobró» sería mentira. Sin migración: el motivo vive en `last_error`.
     *
     * 🔴 Codex r8 (P1-1): ni un VETO durable (`server_veto`). El worker consulta S6 con el SDK dentro y guarda la
     * contradicción sin pasar por la RAM de la pantalla; sin esta condición el CAS cerraba igual, la pantalla ofrecía
     * reintentar y un Pago rápido sin venta dejaba el aparato libre con la contradicción conocida.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now, last_error = :motivo
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND state = 'AUTORIZANDO'
           AND processor = 'ANGELPAY' AND kind = 'SALE' AND legacy_shadow = 0
           AND host_approved IS NULL AND server_payment_id IS NULL AND server_outcome IS NULL
           AND server_processor_evidence IS NULL AND server_veto IS NULL"""
    )
    suspend fun marcarSinAutorizacion(attemptId: String, venueId: String, motivo: String, now: Long): Int

    /**
     * Codex r2 (P1-2 residual, 18-sep): REABRE la fila que cerró [marcarSinAutorizacion] — `DESCARTADA → INDETERMINADO` —
     * cuando la pantalla no puede sostener ese «no se cobró»: la relectura posterior al CAS falló (nada garantiza que S5/S6
     * no dejaran dinero en ese hueco) o el servidor acreditó dinero que no se pudo dejar escrito. SÓLO la fila que escribió
     * ESE cierre: mismo intento, venue y motivo (`last_error`), sin veredicto del host, AngelPay SALE no heredada. No toca
     * `host_approved` ni la evidencia del servidor. INDETERMINADO vuelve a vetar un negativo de su solicitud
     * (`RemotePaymentRequestDao.contarIntentosBloqueadores`) y a salir en el aviso F0 (desde el 25-sep no aparta la terminal).
     *
     * 🔴 Codex H3 (26-sep): con `:veto` —dinero del servidor de ESTE intento que no quedó escrito— el MISMO UPDATE lo deja en
     * `server_veto` (conserva uno previo). Sin él la fila reabierta era una duda más y, muerto el proceso con el veto en
     * memoria, [PaymentAttemptEntity.SQL_CERCA_LA_VENTA] dejaba pasar su venta. Con NULL (un simple fallo de relectura) la
     * columna no cambia.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now, last_error = :razon,
               server_veto = COALESCE(server_veto, :veto)
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND state = 'DESCARTADA' AND last_error = :motivoDelCierre
           AND processor = 'ANGELPAY' AND kind = 'SALE' AND legacy_shadow = 0 AND host_approved IS NULL"""
    )
    suspend fun reabrirSinAutorizacion(attemptId: String, venueId: String, motivoDelCierre: String, razon: String, veto: String?, now: Long): Int

    // ── Sweep / shadow observability (ALWAYS venue-scoped — tenant isolation) ──

    @Query("SELECT * FROM payment_attempts WHERE venue_id = :venueId AND state IN (:states) AND created_at < :olderThan ORDER BY created_at ASC LIMIT 50")
    suspend fun getOpenOlderThan(venueId: String, states: List<String>, olderThan: Long): List<PaymentAttemptEntity>

    /**
     * AUTORIZANDO stuck past the threshold = process died mid-auth → quarantine (spec §4.5).
     *
     * 🔴 Deja la marca [PaymentAttemptEntity.CUARENTENA_POR_ANTIGUEDAD] porque esto decide por
     * RELOJ, no por evidencia: nadie comprobó que la llamada nativa terminara. Sin la marca, F0
     * leía este `INDETERMINADO` como «el SDK ya salió» y soltaba el lector para otra venta
     * mientras la primera podía seguir dentro — un cobro doble sobre un aparato que es UNO
     * (Codex, 2026-09-12, reproducido en SQLite).
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now,
               last_error = 'cuarentena_por_antiguedad'
           WHERE venue_id = :venueId AND state = 'AUTORIZANDO' AND updated_at < :olderThan"""
    )
    suspend fun quarantineStaleAuthorizing(venueId: String, olderThan: Long, now: Long): Int

    /**
     * 🔴 Founder, 29-sep-2026 («eso de reiniciar está pésimo»): la cuarentena POR RELOJ aparta el aparato porque nadie sabe si
     * la llamada nativa sigue viva. Cuando el SDK REGRESA sin veredicto ya se sabe: terminó. La fila sigue en duda, ahora con
     * su motivo real, y deja de apartar el aparato — antes sólo se salía reiniciando la app. No toca otra duda.
     */
    @Query(
        """UPDATE payment_attempts
           SET last_error = :error, state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND state = 'INDETERMINADO' AND last_error = 'cuarentena_por_antiguedad'"""
    )
    suspend fun levantarCuarentenaPorReloj(attemptId: String, now: Long, error: String): Int

    /**
     * 🔴 KERNEL_ACTIVO colgado = el proceso murió DENTRO de una llamada capaz de aprobar.
     * Va a INDETERMINADO, jamás a DESCARTADA: el dinero pudo moverse.
     *
     * 🔴 F1 bloque 6 (I6): NUNCA la venta cuyo lector sigue en ESTE proceso ([procesoVivo]). `KERNEL_ACTIVO` es «lector activo sin
     * envío» (la autorización se arma después, en `AUTORIZANDO`): ponerla en duda por reloj borraba esa distinción, y cuando el
     * lector regresaba y fallaba la lectura de los datos, ya no se podía soltar (Codex código r3 #7: «Reintentar» con la terminal
     * todavía apartada). Mientras el lector siga dentro, la fila ya aparta el aparato tal cual; ningún cobro sano se aborta por reloj.
     * `""` = sin proceso que excluir (como antes).
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now,
               last_error = 'cuarentena_por_antiguedad'
           WHERE venue_id = :venueId AND state = 'KERNEL_ACTIVO' AND updated_at < :olderThan
             AND NOT (kind = 'SALE' AND process_token IS :procesoVivo)"""
    )
    suspend fun quarantineStaleKernel(venueId: String, olderThan: Long, now: Long, procesoVivo: String): Int

    /**
     * 🔴 Ronda 20: lo que un proceso pudo dejar a medias (nunca las heredadas: en 2.9.2 el contactless entraba al kernel en
     * PREPARANDO). Quién es huérfano lo decide la libreta con SU lista en memoria; aquí sólo se leen candidatas, acotadas.
     */
    @Query(
        """SELECT * FROM payment_attempts
           WHERE legacy_shadow = 0 AND state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO')
           ORDER BY updated_at ASC, attempt_id ASC LIMIT 200"""
    )
    suspend fun posiblesHuerfanos(): List<PaymentAttemptEntity>

    /**
     * Ronda 20: un KERNEL_ACTIVO / AUTORIZANDO de un proceso MUERTO pasa a «en duda» con su motivo. CAS por estado y versión:
     * si la fila cambió entre la lectura y aquí, no se toca. Nunca a DESCARTADA: el dinero pudo moverse.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now, last_error = 'proceso_terminado'
           WHERE attempt_id = :attemptId AND legacy_shadow = 0 AND state IN ('KERNEL_ACTIVO','AUTORIZANDO')
             AND state = :estado AND state_version = :version"""
    )
    suspend fun cuarentenarHuerfano(attemptId: String, estado: String, version: Int, now: Long): Int

    /** Ronda 20: un PREPARANDO de un proceso muerto se descarta: por construcción ninguna llamada capaz de cobrar empezó. */
    @Query(
        """UPDATE payment_attempts
           SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now, last_error = 'preparando_de_proceso_terminado'
           WHERE attempt_id = :attemptId AND legacy_shadow = 0 AND state = 'PREPARANDO' AND state_version = :version"""
    )
    suspend fun descartarPreparandoHuerfano(attemptId: String, version: Int, now: Long): Int

    /**
     * Ronda 20: las dudas LOCALES (Pago rápido) que pueden liberarse SOLAS — las MISMAS condiciones que
     * [cerrarPorLiberacionLocalDelServidor], que es quien de verdad decide dentro del UPDATE. Acotada.
     * 🔴 Ronda 21 (Codex r19, P2-1): SIN reloj de pared. La espera del aviso la mide el reloj monotónico de la libreta
     * ([PaymentAttemptLedger.faltaParaLiberarSola]); `updated_at <= ahora − 10 s` se adelantaba con una corrección de hora
     * hacia adelante y, hacia atrás, dejaba la duda apartada tanto como se hubiera movido la hora.
     */
    @Query(
        """SELECT * FROM payment_attempts
           WHERE venue_id = :venueId AND legacy_shadow = 0 AND processor = 'ANGELPAY' AND kind = 'SALE'
             AND terminal_payment_request_id IS NULL AND state = 'INDETERMINADO' AND host_approved IS NOT 1
             AND server_outcome IS NULL AND server_processor_evidence IS NOT 'APPROVED' AND server_veto IS NULL
             -- F1 (17-nov): la duda de una cuenta (folio) no se libera por silencio: la revisa el gerente.
             AND NOT (instr(payment_context_json, '"orderId":"') > 0 AND instr(payment_context_json, '"orderId":""') = 0)
             -- Ronda 22 (Codex r20, P2-1): la página siguiente va DESPUÉS de la última leída. Con un LIMIT fijo, 100 dudas que no
             -- se pueden liberar tapaban para siempre a la 101 (las saltadas por su reintento se descartaban DESPUÉS del LIMIT).
             AND (updated_at > :trasActualizada OR (updated_at = :trasActualizada AND attempt_id > :trasId))
           ORDER BY updated_at ASC, attempt_id ASC LIMIT :limite"""
    )
    suspend fun localesPorLiberarSolas(venueId: String, trasActualizada: Long, trasId: String, limite: Int): List<PaymentAttemptEntity>

    /**
     * 🔴 Y su contrapeso, que es de DISPONIBILIDAD, no de dinero: ahora que PREPARANDO
     * retiene la terminal, una fila huérfana (proceso muerto entre la reserva y el kernel)
     * dejaría la caja sin poder cobrar para siempre. PREPARANDO significa, por construcción,
     * que ninguna llamada capaz de autorizar empezó — así que liberarla es seguro y es lo
     * ÚNICO que puede liberarse por tiempo.
     *
     * 🔴 NUNCA una fila HEREDADA (`legacy_shadow = 1`): en 2.9.2 el contactless entraba al kernel con
     * la fila en PREPARANDO, así que ese PREPARANDO no prueba que no hubo cobro. Tampoco reserva la
     * terminal, así que dejarla quieta no quita disponibilidad: sólo queda como obligación que conciliar.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now,
               last_error = 'stale_preparing_released'
           WHERE venue_id = :venueId AND state = 'PREPARANDO' AND legacy_shadow = 0 AND updated_at < :olderThan"""
    )
    suspend fun discardStalePreparing(venueId: String, olderThan: Long, now: Long): Int

    /** Happy-path rows close silently after a day (spec §4.3). */
    /** Checkpoint 2 (E1): una CONTRADICCIÓN con el servidor nunca se cierra ni se poda por tiempo — es evidencia. */
    @Query(
        """UPDATE payment_attempts
           SET state = 'CERRADA', state_version = state_version + 1, updated_at = :now
           WHERE venue_id = :venueId AND state = 'REGISTRADO' AND updated_at < :olderThan
           AND NOT """ + PaymentAttemptEntity.SQL_CONTRADICCION
    )
    suspend fun closeRecordedOlderThan(venueId: String, olderThan: Long, now: Long): Int

    /**
     * Prune terminal rows at ~7 days (mirror of deleteOldSyncedPayments). INDETERMINADO is NEVER deleted.
     *
     * 🔴 Codex r7 (P2-5): tampoco una DECLARACIÓN hecha SIN RED (`declarado_sin_cobro:…`, [declararSinCobroLocal]) mientras
     * el servidor no la haya vigilado una semana DESPUÉS de hecha. Es el testimonio de una persona sin conciliar, y la
     * pantalla promete «si el banco sí lo cobró, la terminal te avisa al volver la conexión»: ese aviso sale de consultar
     * ESTA fila (la recuperación por servidor sigue preguntando por ella). Borrarla a los 7 días sin red rompía la promesa.
     * `server_answered_at` avanza con cada RESPUESTA (2xx) del servidor y `updated_at` queda en la hora de la declaración,
     * así que la resta mide cuánto se vigiló después. Con dinero, la fila ya es contradicción y nunca se poda.
     * 🔴 Codex r8 (P2-4): era `server_checked_at`, y un 503 del día ocho —que también gasta el turno— la borraba.
     */
    @Query(
        """DELETE FROM payment_attempts WHERE venue_id = :venueId AND state IN ('CERRADA','DESCARTADA') AND updated_at < :olderThan
           AND NOT """ + PaymentAttemptEntity.SQL_CONTRADICCION + """
           -- F1 bloques 3 y 4: ni una lectura del historial despachada sin respuesta, ni un aprobado propio sin registrar.
           AND history_read_at IS NULL AND NOT """ + SQL_APROBADO_TRAS_LA_VENTANA + """
           -- `IFNULL`: con `last_error` NULL, `NULL LIKE …` es NULL, `NOT (NULL AND …)` es NULL, y el WHERE excluía EN
           -- SILENCIO toda fila sin motivo — la poda dejó de podar (lo cazaron las pruebas de la poda de siempre).
           AND NOT (IFNULL(last_error, '') LIKE 'declarado_sin_cobro:%'
                    AND (server_answered_at IS NULL OR server_answered_at < updated_at + 604800000))"""
    )
    suspend fun pruneTerminalOlderThan(venueId: String, olderThan: Long): Int

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Checkpoint 2 (webhook como primer confirmador, diseño v3 E1–E4): el VEREDICTO DEL SERVIDOR sobre un intento
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * La evidencia del servidor, idempotente por identidad Y datos; `server_verdict_at` conserva la PRIMERA vez — salvo cuando
     * el dinero SUSTITUYE a una liberación (`RELEASED_NO_EVIDENCE` / `OPERATOR_NO_INSTRUMENT`): ahí se re-fecha, para que el aviso
     * F0 de contradicción cuente sus 72 h desde la EVIDENCIA nueva, no desde la liberación (Codex, Task 0, P5).
     */
    @Query(
        """UPDATE payment_attempts
           SET server_payment_id = :paymentId, server_outcome = :outcome, server_recorded_via = :via,
               server_amount_cents = :amountCents, server_tip_cents = :tipCents, server_winner_payment_id = :winnerPaymentId,
               server_verdict_at = CASE WHEN server_outcome IN ('RELEASED_NO_EVIDENCE','OPERATOR_NO_INSTRUMENT') THEN :now ELSE COALESCE(server_verdict_at, :now) END,
               server_checked_at = :now, server_check_count = server_check_count + 1, updated_at = :now
           WHERE attempt_id = :attemptId"""
    )
    suspend fun guardarVeredictoDelServidor(
        attemptId: String, paymentId: String?, outcome: String, via: String,
        amountCents: Long?, tipCents: Long?, winnerPaymentId: String?, now: Long,
    ): Int

    /**
     * 🔴 LA regla de liberación (E1), una sola para S5, S6 y REST: REGISTRADO sólo desde un estado posterior al host y
     * con un veredicto FINAL y APROBADO para ESTE intento — `RECORDED` con los mismos montos, o segunda captura con ganador
     * acreditado y los mismos montos — y nunca sobre un rechazo explícito del host ni sobre una fila heredada. Una
     * autorización ya aprobada por el host no puede volver a autorizar: por eso es la prueba de salida del SDK, la misma
     * con la que hoy libera la cadena historial→REST. Cualquier otro veredicto deja la fila y su retención como estaban.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'REGISTRADO', state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND legacy_shadow = 0 AND host_approved IS NOT 0
           AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO','ENTREGADA_A_COLA','INDETERMINADO')
           AND server_amount_cents = amount_cents AND server_tip_cents = tip_cents
           AND (server_outcome = 'RECORDED'
                OR (server_outcome = 'SECOND_CAPTURE_EVIDENCE' AND server_winner_payment_id IS NOT NULL))
           -- 🔴 Ronda 22 (Codex r20, P1-1): con un VETO del servidor no se promueve. REGISTRADO sale de la contradicción y de
           -- toda retención: el veto quedaba guardado pero inofensivo, y la pantalla pintaba «cobrado».
           AND server_veto IS NULL"""
    )
    suspend fun registrarPorVeredictoDelServidor(attemptId: String, now: Long): Int

    /**
     * Task 7 · fix 4 (D2): la evidencia POSITIVA del servidor sin veredicto aplicable (banco APPROVED sin Payment, o un outcome con
     * dinero sin `paymentId`) se hace DURABLE. UPDATE idempotente por `attempt_id + venue_id`, en CUALQUIER estado (INDETERMINADO,
     * DESCARTADA, REGISTRO_FALLIDO…), sin tocar `state`, `state_version`, `host_approved`, `server_outcome` ni `updated_at`;
     * `server_processor_evidence_at` conserva la PRIMERA vez (repetir la evidencia no renueva las 72 h del aviso F0). Mismo alcance
     * que el checkpoint: AngelPay/Blumon, SALE, no heredada. La marca NUNCA se degrada a NULL ni se convierte en `host_approved`.
     */
    @Query(
        """UPDATE payment_attempts
           SET server_processor_evidence = 'APPROVED',
               server_processor_evidence_at = COALESCE(server_processor_evidence_at, :at)
           WHERE attempt_id = :attemptId AND venue_id = :venueId
             AND legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE'""",
    )
    suspend fun marcarEvidenciaPositivaDelServidor(attemptId: String, venueId: String, at: Long): Int

    /** Una consulta S6 sin veredicto aplicable (NOT_RECORDED, 404, error HTTP) sólo gasta el turno de la fila (E3). */
    @Query("UPDATE payment_attempts SET server_checked_at = :now, server_check_count = server_check_count + 1 WHERE attempt_id = :attemptId")
    suspend fun estamparConsultaAlServidor(attemptId: String, now: Long): Int

    /**
     * 🔴 Codex r8 (P2-4): el servidor CONTESTÓ (2xx) por esta fila. Distinto del turno ([estamparConsultaAlServidor]), que
     * también se gasta con un 401/403/404/5xx. Lo leen el candado 5 de [declararSinCobroLocal] y la poda de una declaración
     * hecha sin red. No toca `updated_at`: la poda mide la vigilancia DESPUÉS de la declaración contra esa hora.
     */
    @Query("UPDATE payment_attempts SET server_answered_at = :now WHERE attempt_id = :attemptId")
    suspend fun estamparRespuestaDelServidor(attemptId: String, now: Long): Int

    @Query("SELECT " + PaymentAttemptEntity.SQL_CONTRADICCION + " FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun esContradiccion(attemptId: String): Boolean?

    /** Decisión del founder (23-sep): los cobros que SÍ pasaron y el cajero puede confirmar ([PaymentAttemptEntity.SQL_COBRO_POR_RECONOCER]). */
    @Query(
        "SELECT * FROM payment_attempts WHERE venue_id = :venueId AND " + PaymentAttemptEntity.SQL_COBRO_POR_RECONOCER +
            " ORDER BY created_at DESC LIMIT 20"
    )
    fun observarCobrosPorReconocer(venueId: String): kotlinx.coroutines.flow.Flow<List<PaymentAttemptEntity>>

    /**
     * «Entendido»: la fila pasa a REGISTRADO y deja quién y cuándo. Conserva `host_approved` y `last_error` (lo que dijo el
     * lector) y NO toca la evidencia del servidor. La regla entera va DENTRO del UPDATE: si algo cambió desde que el aviso la
     * mostró (un veto, otro ganador), no se confirma. `acknowledged_at IS NULL` en la regla: confirmar dos veces no re-fecha.
     */
    @Query(
        """UPDATE payment_attempts SET state = 'REGISTRADO', state_version = state_version + 1, updated_at = :now,
              acknowledged_at = :now, acknowledged_by = :quien
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND """ + PaymentAttemptEntity.SQL_COBRO_POR_RECONOCER
    )
    suspend fun reconocerCobroRegistrado(attemptId: String, venueId: String, quien: String, now: Long): Int

    @Query("SELECT " + PaymentAttemptEntity.SQL_COBRO_POR_RECONOCER + " FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun esCobroPorReconocer(attemptId: String): Boolean?

    /**
     * E3 · candidatas de CONSULTA S6: sin veredicto o con veredicto NO final (el servidor puede volverlo
     * RECORDED al conciliar), fuera de los estados con SDK dentro salvo que lleven más de :vivosAntesDe, y espaciadas por
     * intento (`min(base × 2^count, tope)`). Orden ESTABLE con avance: la consultada va al final (`server_checked_at`
     * ascendente, NULL primero por defecto en SQLite — nada de `NULLS FIRST`, que exige SQLite 3.30). 25 por pasada.
     */
    @Query(
        """SELECT * FROM payment_attempts
           WHERE venue_id = :venueId AND legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE'
           AND state != 'CERRADA'
           AND (state NOT IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO') OR updated_at < :vivosAntesDe)
           AND (server_outcome IS NULL OR server_outcome IN ('REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE','RELEASED_NO_EVIDENCE','OPERATOR_NO_INSTRUMENT'))
           AND (server_checked_at IS NULL
                OR server_checked_at < :now - MIN(:espaciadoBaseMs * (1 << MIN(server_check_count, 7)), :espaciadoTopeMs))
           -- 🔴 Codex r16 (P2): la página siguiente se pide DESPUÉS de la última leída (cursor por la clave del orden). La lista
           -- `NOT IN (:excluir)` crecía con la rueda de atoradas y pasaba del tope de 999 variables de SQLite anterior a 3.32
           -- (Android API 27 trae 3.19). Así el número de variables es FIJO; lo que hay que saltar se salta en memoria.
           AND (IFNULL(server_checked_at, -1) > :trasTurno
                OR (IFNULL(server_checked_at, -1) = :trasTurno
                    AND (created_at > :trasCreado OR (created_at = :trasCreado AND attempt_id > :trasId))))
           ORDER BY IFNULL(server_checked_at, -1) ASC, created_at ASC, attempt_id ASC
           LIMIT :limite"""
    )
    /**
     * 🔴 21-sep-2026: se RETIRÓ `terminal_payment_request_id IS NOT NULL`. Un cobro LOCAL (Pago rápido, «Cobrar»
     * de la propia terminal) no lleva solicitud del POS, así que ese filtro lo dejaba FUERA de toda recuperación:
     * medido en una N86 — al morir la app con el lector activo, la fila quedó `AUTORIZANDO` apartando la terminal
     * y a los 3 min `server_check_count` seguía en 0. No era un job mal agendado: no estaba en la lista.
     *
     * 🔑 Ampliar la lista NO la vuelve liberable, y eso es lo que lo hace seguro: el veredicto
     * ([VeredictoDeIntento.desdeConsultaS6]) sólo acredita con `paymentId`, y la LIBERACIÓN
     * ([LiberacionDelServidor.desdeConsultaS6]) exige solicitud a propósito («sin solicitud no hay pertenencia que
     * comprobar»). Así que un cobro local puede cerrarse **como cobrado**, nunca liberarse en falso.
     */
    suspend fun candidatasDeConsultaAlServidor(
        venueId: String, vivosAntesDe: Long, now: Long, espaciadoBaseMs: Long, espaciadoTopeMs: Long,
        trasTurno: Long, trasCreado: Long, trasId: String, limite: Int,
    ): List<PaymentAttemptEntity>

    /** La primera página (Codex r16, P2: las siguientes se piden tras la clave de la última leída). */
    suspend fun candidatasDeConsultaAlServidor(venueId: String, vivosAntesDe: Long, now: Long, espaciadoBaseMs: Long, espaciadoTopeMs: Long): List<PaymentAttemptEntity> =
        candidatasDeConsultaAlServidor(venueId, vivosAntesDe, now, espaciadoBaseMs, espaciadoTopeMs, Long.MIN_VALUE, Long.MIN_VALUE, "", CANDIDATAS_POR_PAGINA)

    /**
     * E2 · veredictos FINALES ya guardados que todavía no se aplicaron: la fila cambió de estado (el SDK volvió incierto,
     * el registro falló…) después de guardar la evidencia. Se REAPLICAN sin otra consulta.
     */
    // Codex (código, P1-3): sólo entran al lote los veredictos que la transición PUEDE aplicar (mismas condiciones que
    // `registrarPorVeredictoDelServidor`): una contradicción (montos distintos, rechazo del host) o una segunda captura sin
    // ganador nunca sale de este conjunto por sí sola y, con 50 de ellas delante, el intento 51 no se reaplicaba jamás.
    @Query(
        """SELECT * FROM payment_attempts
           WHERE venue_id = :venueId AND legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE'
           AND server_outcome IN ('RECORDED','SECOND_CAPTURE_EVIDENCE')
           AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO','ENTREGADA_A_COLA','INDETERMINADO')
           AND host_approved IS NOT 0
           AND server_amount_cents = amount_cents AND server_tip_cents = tip_cents
           AND (server_outcome = 'RECORDED' OR server_winner_payment_id IS NOT NULL)
           AND server_veto IS NULL   -- Ronda 22: la transición ya no la aplica; con 50 delante, la 51 no se reaplicaría
           ORDER BY server_verdict_at ASC, attempt_id ASC LIMIT 50"""
    )
    suspend fun veredictosPendientesDeAplicar(venueId: String): List<PaymentAttemptEntity>

    // ── La bandeja, dentro de la misma transacción (E4). Mismo SQL que `RemotePaymentRequestDao.markResolved` /
    //    `replaceResolvedResult`: viven aquí para que libreta y bandeja se escriban en UNA transacción (P1-4). ──
    @Query("SELECT * FROM remote_payment_requests WHERE request_id = :requestId")
    suspend fun bandejaDe(requestId: String): com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity?

    @Query(
        """UPDATE remote_payment_requests
           SET status = 'RESOLVED', final_result_json = :finalResultJson, final_emitted_at = :now, updated_at = :now
           WHERE request_id = :requestId AND status = 'PROCESSING'"""
    )
    suspend fun bandejaResolverProcessing(requestId: String, finalResultJson: String, now: Long): Int

    @Query(
        """UPDATE remote_payment_requests
           SET final_result_json = :finalResultJson, updated_at = :now
           WHERE request_id = :requestId AND status = 'RESOLVED' AND final_result_json = :previousResultJson"""
    )
    suspend fun bandejaReemplazarResuelto(requestId: String, previousResultJson: String, finalResultJson: String, now: Long): Int

    /**
     * 🔴 UNA operación durable de conciliación con el servidor (E1–E4), libreta y bandeja en la MISMA transacción (P1-4):
     * pertenencia antes de escribir → evidencia previa se preserva → la evidencia se guarda en cualquier estado → la
     * transición sólo por [registrarPorVeredictoDelServidor] → la bandeja sólo con un GANADOR acreditado. Devuelve un
     * resultado verificable; la EMISIÓN al servidor y la pantalla van después del commit, fuera de aquí.
     * Recibir el MISMO Payment con los mismos datos vuelve a evaluar las proyecciones (E2), nunca es un no-op ciego.
     */
    @Transaction
    suspend fun aplicarVeredictoDelServidor(v: VeredictoDeIntento, now: Long): ResultadoDelVeredicto {
        val fila = getById(v.attemptId) ?: return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.SIN_FILA, false, null, false)
        // 🔴 Codex r16 (P1): la marca que APARTA el aparato va dentro de ESTA transacción. Todo veredicto trae dinero de este
        // intento, y bastaba con que UN llamador olvidara marcar antes (el respaldo de S5 de la pantalla) para dejar una
        // DESCARTADA con RECORDED que no apartaba nada. Aquí nadie la salta y no hay ventana entre las dos escrituras: si falla,
        // falla todo. Su propio WHERE la acota (AngelPay/Blumon · SALE · no heredada · ESTE venue); sin atribuir el Payment.
        val requestId = v.requestId ?: fila.terminalPaymentRequestId
        // Codex (código, P1-2/P1-6): fuera del checkpoint (heredada, procesador desconocido, devolución) NO es un rechazo — quien llama
        // sigue su camino anterior; un venue o una solicitud ajenos SÍ lo son (anomalía: no se pisa nada).
        if (fila.legacyShadow || fila.processor !in setOf(PaymentAttemptEntity.PROCESSOR_ANGELPAY, PaymentAttemptEntity.PROCESSOR_BLUMON) || fila.kind != PaymentAttemptEntity.KIND_SALE)
            return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE, false, null, false)
        // 🔴 F1 bloque 7 (I4): la PERTENENCIA se verifica ANTES de marcar nada. Antes la marca «hubo dinero» iba primero, y un aviso
        // (S5) de OTRA solicitud dejaba este intento cercado con una aprobación que no era suya.
        if (fila.venueId != v.venueId ||
            (v.requestId != null && fila.terminalPaymentRequestId != null && fila.terminalPaymentRequestId != v.requestId)
        ) return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, null, false)
        marcarEvidenciaPositivaDelServidor(v.attemptId, v.venueId, now)
        // 🔴 Ronda 21 (Codex r19, P1-1): el VETO que trajo la MISMA respuesta, en la MISMA transacción. Aplicado sin él, un
        // RECORDED con `evidenceContradiction` dejaba la fila limpia y el aviso ofrecía «Entendido» (confirmar y volver a cobrar).
        v.veto?.let { marcarVetoDelServidor(v.attemptId, v.venueId, it, now) }
        if (fila.serverPaymentId != null && v.paymentId != null && fila.serverPaymentId != v.paymentId) {
            estamparConsultaAlServidor(v.attemptId, now)
            return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.RECHAZADO_OTRO_PAYMENT, false, null, esContradiccion(v.attemptId) == true)
        }
        val mismoPayment = fila.serverPaymentId != null && fila.serverPaymentId == v.paymentId
        if (mismoPayment && (fila.serverRecordedVia != v.recordedVia || fila.serverAmountCents != v.amountCents || fila.serverTipCents != v.tipCents)) {
            // Identidad, origen o importes distintos para el MISMO Payment: no se pisa nada (E2).
            estamparConsultaAlServidor(v.attemptId, now)
            return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.RECHAZADO_DATOS_DISTINTOS, false, null, esContradiccion(v.attemptId) == true)
        }
        // Codex (v3, cambio 2 · código, P1-4): «final aprobado» guardado = RECORDED, o segunda captura CON ganador. Una respuesta
        // menos completa —evidencia no final, o el MISMO outcome sin ganador— nunca lo degrada ni borra el ganador; se decide por
        // la acreditación, no por el nombre del outcome (una segunda captura sin `winnerPaymentId` borraba `server_winner_payment_id`).
        val guardadoEsFinalAprobado = fila.serverOutcome == PaymentAttemptEntity.SERVER_RECORDED ||
            (fila.serverOutcome == PaymentAttemptEntity.SERVER_SECOND_CAPTURE_EVIDENCE && fila.serverWinnerPaymentId != null)
        if (mismoPayment && guardadoEsFinalAprobado && !v.esFinalAprobado) {
            // Una respuesta ANTIGUA o incompleta nunca degrada un veredicto final ya guardado; el guardado se REEVALÚA (E2).
            estamparConsultaAlServidor(v.attemptId, now)
            val transiciono = registrarPorVeredictoDelServidor(v.attemptId, now) == 1
            val bandejaJson = fila.serverWinnerPaymentId?.let { g -> requestId?.let { resolverBandejaConGanador(it, g, fila.serverRecordedVia ?: "terminal", now) } }
            val estado = getById(v.attemptId)?.state
            val decision = if (estado == PaymentAttemptEntity.STATE_REGISTRADO || estado == PaymentAttemptEntity.STATE_CERRADA) {
                ResultadoDelVeredicto.Decision.APLICADO
            } else {
                ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR
            }
            return ResultadoDelVeredicto(decision, transiciono, bandejaJson, esContradiccion(v.attemptId) == true)
        }
        // Mismo Payment con el mismo veredicto, o PROMOCIÓN válida de evidencia no final → final (el servidor concilió y S6
        // calcula el outcome sobre el estado vigente del Payment): se guarda y se reevalúan las proyecciones.
        guardarVeredictoDelServidor(
            v.attemptId, v.paymentId, v.outcome.name, v.recordedVia, v.amountCents, v.tipCents, v.ganadorAcreditado, now,
        )
        val transiciono = v.esFinalAprobado && registrarPorVeredictoDelServidor(v.attemptId, now) == 1
        val bandejaJson = v.ganadorAcreditado?.let { ganador -> requestId?.let { resolverBandejaConGanador(it, ganador, v.recordedVia, now) } }
        val estadoFinal = getById(v.attemptId)?.state
        val decision = if (estadoFinal == PaymentAttemptEntity.STATE_REGISTRADO || estadoFinal == PaymentAttemptEntity.STATE_CERRADA) {
            ResultadoDelVeredicto.Decision.APLICADO
        } else {
            ResultadoDelVeredicto.Decision.GUARDADO_SIN_LIBERAR
        }
        return ResultadoDelVeredicto(decision, transiciono, bandejaJson, esContradiccion(v.attemptId) == true)
    }

    /** E2: vuelve a evaluar un veredicto FINAL ya guardado (sin red) cuando el estado local cambió. */
    @Transaction
    suspend fun reaplicarVeredictoGuardado(attemptId: String, now: Long): ResultadoDelVeredicto? {
        val fila = getById(attemptId) ?: return null
        val outcome = com.jaac.avoqado_tpv.features.payment.domain.model.VeredictoDelServidor.porNombre(fila.serverOutcome) ?: return null
        if (fila.serverPaymentId == null) return null
        val v = VeredictoDeIntento(
            venueId = fila.venueId, attemptId = attemptId, requestId = fila.terminalPaymentRequestId, outcome = outcome,
            paymentId = fila.serverPaymentId, recordedVia = fila.serverRecordedVia ?: "terminal",
            amountCents = fila.serverAmountCents, tipCents = fila.serverTipCents,
            ganadorAcreditado = fila.serverWinnerPaymentId, fuente = VeredictoDeIntento.Fuente.CONSULTA_S6,
        )
        return aplicarVeredictoDelServidor(v, now)
    }

    /**
     * Liberación del SERVIDOR (ventana de confirmación / declaración del cajero): sólo una fila INDETERMINADO sin veredicto y sin
     * `host_approved` — el host nunca contestó — pasa a DESCARTADA. NO toca `host_approved`: un RECORDED posterior entra por
     * `aplicarVeredictoDelServidor` como contradicción («RECORDED sobre DESCARTADA») y el aviso lo grita.
     * Pertenencia (Task 7 · B): la liberación es de UNA solicitud; la fila sólo se cierra si su `terminal_payment_request_id`
     * es exactamente esa — el mismo criterio con que `aplicarVeredictoDelServidor` rechaza un veredicto ajeno.
     * Fix 4 (D3f): tampoco cierra una fila con evidencia positiva DURABLE (`server_processor_evidence`): una respuesta ATRASADA
     * sin evidencia no puede descartar una INDETERMINADO que ya recibió la aprobación — `server_outcome` sigue NULL ahí, así que
     * la guarda anterior no bastaba. El veto del parser o del VM no sustituye esta guarda transaccional.
     */
    @Query(
        """UPDATE payment_attempts SET state = 'DESCARTADA', last_error = :motivo, server_outcome = :serverOutcome,
           server_verdict_at = :now, updated_at = :now, state_version = state_version + 1
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND terminal_payment_request_id = :requestId
             AND legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE'
             AND state = 'INDETERMINADO' AND host_approved IS NOT 1 AND server_outcome IS NULL
             AND (processor = 'ANGELPAY' OR :serverOutcome = 'OPERATOR_NO_INSTRUMENT')
             AND server_processor_evidence IS NOT 'APPROVED'
             -- r5-4: y ningún VETO durable del servidor (pago no atribuible, evidencia ajena, evidencia sin dueño).
             AND server_veto IS NULL
             -- 🔴 F1 (aplicarAcuse único): la misma cláusula de cese que la firma local (bloque 2, R1): en la PAX, nunca mientras el
             -- SDK de ESTE intento pueda regresar (misma ejecución, sin regreso). Tras el regreso o un reinicio, sí.
             AND NOT (processor = 'BLUMON' AND process_token IS :processToken AND sdk_returned_at IS NULL)""",
    )
    suspend fun cerrarPorLiberacionDelServidor(attemptId: String, venueId: String, requestId: String, serverOutcome: String, motivo: String, now: Long, processToken: String): Int

    /**
     * 🔴 «Ninguna terminal muerta» (22-sep), pieza C: la MISMA liberación, pero de un cobro **LOCAL** — un Pago rápido,
     * iniciado EN la terminal, que no tiene solicitud del POS y por eso no puede casar con `:requestId`.
     *
     * Es una consulta APARTE, no un `IS NULL` condicional metido en la de arriba, por la misma razón por la que los
     * candados van dentro del UPDATE: un `requestId` nulo por accidente en el camino del POS cerraría filas locales que
     * no le tocan. Aquí la pertenencia es explícita — `terminal_payment_request_id IS NULL` — y el resto de guardas son
     * palabra por palabra las de su hermana: nunca sobre una aprobación del host, nunca con veredicto ya guardado, nunca
     * con la evidencia durable del procesador.
     */
    @Query(
        """UPDATE payment_attempts SET state = 'DESCARTADA', last_error = :motivo, server_outcome = :serverOutcome,
           server_verdict_at = :now, updated_at = :now, state_version = state_version + 1
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND terminal_payment_request_id IS NULL
             AND legacy_shadow = 0 AND processor IN ('ANGELPAY','BLUMON') AND kind = 'SALE'
             AND state = 'INDETERMINADO' AND host_approved IS NOT 1 AND server_outcome IS NULL
             AND (processor = 'ANGELPAY' OR :serverOutcome = 'OPERATOR_NO_INSTRUMENT')
             AND server_processor_evidence IS NOT 'APPROVED'
             -- r5-4: y ningún VETO durable del servidor (pago no atribuible, evidencia ajena, evidencia sin dueño).
             AND server_veto IS NULL
             -- 🔴 F1 (aplicarAcuse único): la misma cláusula de cese que la firma local (bloque 2, R1): en la PAX, nunca mientras el
             -- SDK de ESTE intento pueda regresar (misma ejecución, sin regreso). Tras el regreso o un reinicio, sí.
             AND NOT (processor = 'BLUMON' AND process_token IS :processToken AND sdk_returned_at IS NULL)""",
    )
    suspend fun cerrarPorLiberacionLocalDelServidor(attemptId: String, venueId: String, serverOutcome: String, motivo: String, now: Long, processToken: String): Int

    /**
     * 🔴 Codex r5-4 (22-sep): guarda el VETO del servidor de forma DURABLE. `server_veto IS NULL` conserva el PRIMER
     * motivo: lo que importa es que algo contradice, no cuál fue el último aviso. No toca el estado ni la retención —
     * esto no cierra nada, sólo impide que una respuesta LIMPIA y ATRASADA libere lo que otra ya contradijo.
     */
    @Query(
        """UPDATE payment_attempts SET server_veto = :motivo, updated_at = :now
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND legacy_shadow = 0 AND server_veto IS NULL""",
    )
    suspend fun marcarVetoDelServidor(attemptId: String, venueId: String, motivo: String, now: Long): Int

    /**
     * E4 · la bandeja responde por la SOLICITUD y sólo con un ganador acreditado: PROCESSING ⇒ RESOLVED `success`;
     * RESOLVED con negativo ⇒ el éxito lo reemplaza (un éxito nunca se degrada); RESOLVED `success` sin `paymentId` ⇒ se
     * enriquece con el ganador; con el mismo ganador ⇒ no-op; RECEIVED (sin intento) o lápida ⇒ no se toca. Devuelve el
     * JSON que quedó escrito (para EMITIRLO tras el commit) o null si no hubo cambio.
     */
    private suspend fun resolverBandejaConGanador(requestId: String, ganador: String, via: String, now: Long): String? {
        val bandeja = bandejaDe(requestId) ?: return null
        val json = org.json.JSONObject().put("requestId", requestId).put("status", "success")
            .put("paymentId", ganador).put("transactionId", ganador).put("errorMessage", org.json.JSONObject.NULL)
            .put("via", via).put("completedAt", java.time.Instant.ofEpochMilli(now).toString()).toString()
        return when (bandeja.status) {
            com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity.STATUS_PROCESSING ->
                json.takeIf { bandejaResolverProcessing(requestId, it, now) == 1 }
            com.jaac.avoqado_tpv.core.remotepayment.RemotePaymentRequestEntity.STATUS_RESOLVED -> {
                val previo = bandeja.finalResultJson ?: return null
                val previoJson = runCatching { org.json.JSONObject(previo) }.getOrNull() ?: return null
                val previoEsExito = previoJson.optString("status") == "success"
                val previoPaymentId = previoJson.optString("paymentId").takeIf { it.isNotBlank() && it != "null" }
                when {
                    previoEsExito && previoPaymentId == ganador -> null // mismo ganador: nada que cambiar
                    previoEsExito && previoPaymentId != null -> null // otro éxito con identidad: no se pisa
                    else -> json.takeIf { bandejaReemplazarResuelto(requestId, previo, it, now) == 1 }
                }
            }
            else -> null
        }
    }
}
