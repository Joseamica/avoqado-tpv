package com.jaac.avoqado_tpv.core.remotepayment

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity
import org.json.JSONObject

/**
 * 🔴 Lo que el aviso de la pantalla necesita para ser ÚTIL: cuánto y desde cuándo.
 *
 * «Hay 2 cobros pendientes» no le sirve al cajero, porque no le deja distinguir «es esta misma
 * venta» de «es una venta nueva» — y desde la libreta esas dos cosas son indistinguibles: sólo
 * él lo sabe. Nombrar el importe y la antigüedad es lo que convierte el aviso en una decisión
 * que alguien puede tomar (hallazgo de Codex sobre F0, 2026-09-12).
 */
data class ObligacionPendiente(
    @ColumnInfo(name = "total_centavos") val totalCentavos: Long,
    @ColumnInfo(name = "desde_millis") val desdeMillis: Long,
    /** Checkpoint 2 (E1): 1 = CONTRADICCIÓN con el servidor (dinero acreditado para un intento que la terminal dio por no cobrado, o evidencia de posible cobro doble). */
    @ColumnInfo(name = "contradiccion") val contradiccion: Int = 0,
)

@Dao
interface RemotePaymentRequestDao {
    /** Una sola consulta y una sola verdad: no hay doble conteo mientras una fila del buzón gana
     * su fila de libreta. PREPARANDO por sí solo no resuelve un cobro remoto reclamado: sigue
     * visible. Gson escribe la identidad exacta de la solicitud; `instr` no tiene comodines.
     *
     * 🔴 `kind = 'SALE'`: una DEVOLUCIÓN pendiente no es un cobro por repetir. Contarla hacía que
     * el aviso dijera «no repitas esas ventas» sobre dinero que va en la dirección contraria, y un
     * aviso que miente deja de leerse.
     *
     * 🔴 Las filas HEREDADAS (`legacy_shadow = 1`) SÍ cuentan: no apartan la terminal, pero son
     * dinero de desenlace desconocido igual que las demás, y de eso es de lo que avisa esto.
     *
     * El tope de 50 es para no traer una lista sin fin a la pantalla; con 50 obligaciones
     * pendientes el problema ya no es el aviso. ⚠️ Declarado: pasadas 50, el conteo del aviso
     * dice 50; no hay ninguna decisión de dinero colgada de ese número.
     *
     * 🔴 La antigüedad sale de `created_at`, NO de `updated_at`: lo segundo es la última vez que
     * alguien tocó la fila —una cuarentena, un reintento de recuperación— y hacía que una venta
     * de hace tres horas apareciera como «hace unos segundos», que es justo la pista que el
     * cajero necesita para reconocerla (Codex, 2026-09-12).
     */
    // Codex (código, P1-5): el cupo de 50 es POR FAMILIA (pendientes / solicitudes sin intento / contradicciones), no global —
    // 50 contradicciones viejas (que el aviso ya no muestra pasadas 72 h) dejaban fuera del `LIMIT` a un cobro incierto más
    // antiguo, y el aviso salía vacío con la obligación viva. Cada familia va en su subconsulta con su propio `LIMIT 50`
    // (la sintaxis compuesta de SQLite sólo admite `ORDER BY`/`LIMIT` al final, por eso los `SELECT * FROM (…)`).
    @Query("""SELECT * FROM (
            SELECT (amount_cents + tip_cents) AS total_centavos, created_at AS desde_millis, 0 AS contradiccion
            FROM payment_attempts
            WHERE venue_id = :venueId AND kind = 'SALE'
            AND state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
            AND NOT """ + com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.SQL_CONTRADICCION + """
            ORDER BY created_at DESC LIMIT 50)
        UNION ALL
        SELECT * FROM (
            SELECT (r.amount_cents + r.tip_cents) AS total_centavos, r.created_at AS desde_millis, 0 AS contradiccion
            FROM remote_payment_requests r
            WHERE r.venue_id = :venueId AND r.status = 'PROCESSING'
            AND NOT EXISTS (SELECT 1 FROM payment_attempts p
                WHERE p.venue_id = :venueId
                AND p.state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO',
                                'REGISTRADO','CERRADA','DESCARTADA','ENTREGADA_A_COLA')
                AND instr(p.payment_context_json, '"terminalPaymentRequestId":"' || r.request_id || '"') > 0)
            ORDER BY r.created_at DESC LIMIT 50)
        UNION ALL
        SELECT * FROM (
            SELECT (amount_cents + tip_cents) AS total_centavos, COALESCE(server_verdict_at, updated_at) AS desde_millis, 1 AS contradiccion
            FROM payment_attempts
            WHERE venue_id = :venueId AND kind = 'SALE' AND legacy_shadow = 0
            AND """ + com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.SQL_CONTRADICCION + """
            ORDER BY desde_millis DESC LIMIT 50)
        ORDER BY desde_millis DESC""")
    fun observePendingObligations(venueId: String): kotlinx.coroutines.flow.Flow<List<ObligacionPendiente>>

    @Query("""UPDATE remote_payment_requests SET status = 'PROCESSING', updated_at = :now
        WHERE request_id = :requestId AND venue_id = :venueId AND status = 'RECEIVED'""")
    suspend fun markProcessingForVenue(requestId: String, venueId: String, now: Long): Int

    /** -1 = requestId ya existe; nunca reemplazar porque podría cambiar el dinero. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(request: RemotePaymentRequestEntity): Long

    @Query("SELECT * FROM remote_payment_requests WHERE request_id = :requestId")
    suspend fun getById(requestId: String): RemotePaymentRequestEntity?

    @Query(
        """UPDATE remote_payment_requests
           SET status = 'PROCESSING', updated_at = :now
           WHERE request_id = :requestId AND status = 'RECEIVED'""",
    )
    suspend fun markProcessing(requestId: String, now: Long): Int

    /**
     * Compite atómicamente con el reclamo (sólo desde RECEIVED). Deja el desenlace FINAL escrito
     * (`final_emitted_at`, H.3): es cerca aunque una solicitud nunca reclamada no pueda tener intentos.
     * Una solicitud YA reclamada se cancela por [cancelarTrasReclamar], nunca por aquí.
     */
    @Query(
        """UPDATE remote_payment_requests
           SET status = 'RESOLVED', final_result_json = :finalResultJson, final_emitted_at = :now, updated_at = :now
           WHERE request_id = :requestId AND status = 'RECEIVED'""",
    )
    suspend fun resolveReceived(requestId: String, finalResultJson: String, now: Long): Int

    @Query(
        """UPDATE remote_payment_requests
           SET final_result_json = :finalResultJson, updated_at = :now
           WHERE request_id = :requestId AND status = 'RESOLVED' AND final_result_json = :previousResultJson""",
    )
    suspend fun replaceResolvedResult(requestId: String, previousResultJson: String, finalResultJson: String, now: Long): Int

    /**
     * Nunca alcanza una LÁPIDA: esta bandeja ya declaró no haber recibido esa solicitud. Lista POSITIVA
     * (RECEIVED/PROCESSING): un estado que no conoce no se resuelve por aquí. Escribe `final_emitted_at`:
     * desde este commit la terminal ya no ejecuta esta solicitud (H.3).
     */
    @Query(
        """UPDATE remote_payment_requests
           SET status = 'RESOLVED', final_result_json = :finalResultJson, final_emitted_at = :now, updated_at = :now
           WHERE request_id = :requestId AND status IN ('RECEIVED', 'PROCESSING')""",
    )
    suspend fun markResolved(requestId: String, finalResultJson: String, now: Long): Int

    /** N0: sólo SUBE la versión de capacidad, y sólo mientras la solicitud siga viva (RECEIVED/PROCESSING). */
    @Query(
        """UPDATE remote_payment_requests
           SET attempt_link_version = :version, updated_at = :now
           WHERE request_id = :requestId AND attempt_link_version < :version AND status IN ('RECEIVED', 'PROCESSING')""",
    )
    suspend fun raiseAttemptLinkVersion(requestId: String, version: Int, now: Long): Int

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // C.5 / H.3 (11-sep): la libreta y la bandeja se leen JUNTAS, en la misma transacción.
    // El intento se liga a su solicitud por `"terminalPaymentRequestId":"<id>"` dentro de
    // `payment_context_json` (el mismo `instr` que [observePendingObligationCount]).
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Intentos de la solicitud que IMPIDEN afirmar «no se cobró»: cualquiera que no sea PREPARANDO
     * (ninguna llamada capaz de autorizar empezó) ni DESCARTADA (negativa explícita del kernel o del
     * host, o descarte previo al procesador), y TODA fila heredada — su estado lo escribió una versión
     * con otra semántica.
     */
    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0
        AND (legacy_shadow = 1 OR state NOT IN ('PREPARANDO', 'DESCARTADA') OR server_payment_id IS NOT NULL)""")
    suspend fun contarIntentosBloqueadores(requestId: String): Int

    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0""")
    suspend fun contarIntentosDeSolicitud(requestId: String): Int

    /** El intento todavía en PREPARANDO pasa a DESCARTADA: ya no puede entrar al kernel ni autorizar. */
    @Query("""UPDATE payment_attempts
        SET state = 'DESCARTADA', state_version = state_version + 1, updated_at = :now, last_error = :motivo
        WHERE instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0
        AND state = 'PREPARANDO' AND legacy_shadow = 0""")
    suspend fun descartarPreparandoDeSolicitud(requestId: String, now: Long, motivo: String): Int

    /** El ÚLTIMO intento de la solicitud (orden de inserción). La evidencia se toma de ÉL, nunca de la pantalla. */
    @Query("""SELECT * FROM payment_attempts
        WHERE instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0
        ORDER BY rowid DESC LIMIT 1""")
    suspend fun ultimoIntentoDeSolicitud(requestId: String): PaymentAttemptEntity?

    @Query("""UPDATE remote_payment_requests
        SET status = 'RESOLVED', final_result_json = :finalResultJson, cancel_accepted_at = :now,
            final_emitted_at = :now, updated_at = :now
        WHERE request_id = :requestId AND status = 'PROCESSING' AND execution_started_at IS NULL
        AND cancel_accepted_at IS NULL AND final_emitted_at IS NULL""")
    suspend fun aceptarCancelTrasReclamar(requestId: String, finalResultJson: String, now: Long): Int

    /**
     * 🛑 C.5 — cancelación segura de una solicitud YA reclamada (PROCESSING), en UNA transacción con la
     * libreta. Gana (true) sólo si ninguna ejecución capaz de autorizar puede estar en curso NI empezar:
     *  - la solicitud sigue PROCESSING, sin efectivo/cripto arrancado, sin cancel ni final previos;
     *  - ningún intento correlacionado bloquea ([contarIntentosBloqueadores]);
     *  - si NO hay intentos, sólo con PRUEBA DE PROPIEDAD en este proceso (el `requestId` lo reclamó este
     *    proceso): sin ella, tras un reinicio nadie sabe si algo quedó a medias ⇒ ACTIVE;
     *  - «entre intentos» (sólo DESCARTADA, p. ej. tras un rechazo reintentable): se acepta, porque en la
     *    MISMA transacción se escribe `cancel_accepted_at` y la cerca de [PaymentAttemptDao.reserveTerminal]
     *    impide ese reintento.
     * El intento en PREPARANDO pasa a DESCARTADA ('remote_cancel'): su `markKernelEntered`/`markAuthorizing`
     * ya no puede ganar. SQLite serializa las escrituras: exactamente uno gana entre este CAS y esos.
     */
    @Transaction
    suspend fun cancelarTrasReclamar(
        requestId: String,
        finalResultJson: String,
        now: Long,
        propiedadEnEsteProceso: Boolean,
    ): Boolean {
        val fila = getById(requestId) ?: return false
        if (fila.status != RemotePaymentRequestEntity.STATUS_PROCESSING || fila.executionStartedAt != null ||
            fila.cancelAcceptedAt != null || fila.finalEmittedAt != null
        ) return false
        if (contarIntentosBloqueadores(requestId) > 0) return false
        if (contarIntentosDeSolicitud(requestId) == 0 && !propiedadEnEsteProceso) return false
        descartarPreparandoDeSolicitud(requestId, now, "remote_cancel")
        if (aceptarCancelTrasReclamar(requestId, finalResultJson, now) != 1) {
            // Nada escrito sin su pareja: revierte el descarte de la libreta junto con la bandeja.
            throw CancelNoAceptado()
        }
        return true
    }

    /**
     * 🛑 H.3 + evidencia POR INTENTO — el ÚNICO punto que escribe un desenlace NEGATIVO (failed/cancelled)
     * de una solicitud remota. La evidencia no la decide la pantalla sino la libreta, en la misma
     * transacción que el commit del final:
     *  - un intento correlacionado incierto, en curso o ya cobrado, una fila heredada, o efectivo/cripto
     *    arrancado ⇒ NO se escribe nada (null): la solicitud se conserva como incierta y nunca viaja con
     *    la evidencia de un intento anterior;
     *  - el intento en PREPARANDO se descarta ('final_emitted') antes de afirmar nada;
     *  - ÚLTIMO intento DESCARTADO por el host (`host_approved = 0`) ⇒ `failed + PROCESSOR_DECLINED`
     *    (la ÚNICA combinación con esa evidencia que el servidor acepta como «no se cobró»; un
     *    `cancelled + PROCESSOR_DECLINED` lo degrada a UNKNOWN y deja terminal y tablet bloqueadas);
     *  - ningún intento, o el último descartado antes del procesador ⇒ `PRE_AUTHORIZATION` con el
     *    status que pidió quien sale (failed/cancelled).
     * Devuelve el JSON que quedó escrito (con `final_emitted_at`: desde aquí la cerca rige), o null.
     */
    @Transaction
    suspend fun resolverDesenlaceNegativo(requestId: String, resultadoBase: String, now: Long): String? {
        val fila = getById(requestId) ?: return null
        if (fila.status != RemotePaymentRequestEntity.STATUS_RECEIVED &&
            fila.status != RemotePaymentRequestEntity.STATUS_PROCESSING
        ) return null
        if (fila.executionStartedAt != null) return null
        if (contarIntentosBloqueadores(requestId) > 0) return null
        descartarPreparandoDeSolicitud(requestId, now, "final_emitted")
        val ultimo = ultimoIntentoDeSolicitud(requestId)
        val evidencia = if (ultimo?.hostApproved == false) EVIDENCIA_RECHAZO_DEL_PROCESADOR else EVIDENCIA_PRE_AUTORIZACION
        val json = JSONObject(resultadoBase)
        if (evidencia == EVIDENCIA_RECHAZO_DEL_PROCESADOR) json.put("status", "failed")
        json.put("outcomeEvidence", evidencia)
        val escrito = json.toString()
        if (markResolved(requestId, escrito, now) != 1) throw CancelNoAceptado()
        return escrito
    }

    /** Revierte la transacción cuando la escritura de la bandeja no ganó: nunca un descarte suelto. */
    class CancelNoAceptado : RuntimeException("La bandeja no aceptó el desenlace; se revierte la transacción")

    companion object {
        const val EVIDENCIA_PRE_AUTORIZACION = "PRE_AUTHORIZATION"
        const val EVIDENCIA_RECHAZO_DEL_PROCESADOR = "PROCESSOR_DECLINED"
    }
}
