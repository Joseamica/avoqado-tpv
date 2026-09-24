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
/** Una fila de bandeja PROCESSING sin intento correlacionado (pieza D). */
data class HuerfanaDeBandeja(val requestId: String, val createdAt: Long)

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
    // Fix 4 (D3b): las 72 h de una contradicción nacida de la EVIDENCIA positiva se cuentan desde `server_processor_evidence_at`
    // (una liberación de hace días no puede esconder el aviso al nacer); si después llega dinero (RECORDED re-fechado), manda esa
    // fecha más reciente. Repetir la evidencia no renueva el plazo: el escritor conserva la primera fecha (COALESCE).
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
            SELECT (amount_cents + tip_cents) AS total_centavos,
                -- 🔴 Codex r7 (P2-4): con VETO, la fecha más RECIENTE de las tres. El marcador del veto escribe `updated_at`
                -- (sólo la primera vez: `server_veto IS NULL` en su WHERE), pero aquí mandaba la liberación vieja
                -- (`server_verdict_at`): un veto recibido HOY sobre una liberación de hace 4 días nacía con 96 h y el filtro
                -- de 72 h lo escondía al instante. `updated_at` sólo avanza, así que un toque posterior deja el aviso MÁS
                -- visible, nunca menos.
                CASE WHEN server_veto IS NOT NULL
                     THEN MAX(COALESCE(server_verdict_at, 0), COALESCE(server_processor_evidence_at, 0), updated_at)
                     WHEN server_processor_evidence_at IS NOT NULL
                          AND (server_verdict_at IS NULL OR server_processor_evidence_at > server_verdict_at)
                     THEN server_processor_evidence_at ELSE COALESCE(server_verdict_at, updated_at) END AS desde_millis,
                1 AS contradiccion
            FROM payment_attempts
            WHERE venue_id = :venueId AND kind = 'SALE' AND legacy_shadow = 0
            AND """ + com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.SQL_CONTRADICCION + """
            -- Decisión del founder (23-sep): el cobro que SÍ pasó sale en su propio aviso, con «Entendido».
            AND NOT """ + com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity.SQL_COBRO_POR_RECONOCER + """
            ORDER BY desde_millis DESC LIMIT 50)
        ORDER BY desde_millis DESC""")
    fun observePendingObligations(venueId: String): kotlinx.coroutines.flow.Flow<List<ObligacionPendiente>>

    @Query("""UPDATE remote_payment_requests SET status = 'PROCESSING', updated_at = :now
        WHERE request_id = :requestId AND venue_id = :venueId AND status = 'RECEIVED'""")
    suspend fun markProcessingForVenue(requestId: String, venueId: String, now: Long): Int

    // ── Pieza D (22-sep): las filas de bandeja HUÉRFANAS, que ninguna recuperación alcanzaba ──────────────
    //
    // 🔴 Medido en hardware: una N86 llevaba 25 h avisando de «un cobro de $50 sin confirmar» sobre una solicitud
    // que el servidor ya había resuelto el día anterior. Su fila seguía PROCESSING y su libreta no tenía NINGÚN
    // intento de esa solicitud — el caso que el propio repo ya describía como «requiere conciliación explícita».
    // Toda la recuperación consulta por INTENTO, así que nada podía tocarla y el aviso no se podía quitar.
    //
    // El `NOT EXISTS` es EL MISMO de `observePendingObligations`: las candidatas son exactamente las filas que
    // producen ese aviso, ni una más.

    /** Filas PROCESSING de este venue SIN ningún intento correlacionado: las que hoy avisan para siempre. */
    @Query(
        """SELECT r.request_id AS requestId, r.created_at AS createdAt FROM remote_payment_requests r
           WHERE r.venue_id = :venueId AND r.status = 'PROCESSING'
           AND NOT EXISTS (SELECT 1 FROM payment_attempts p
               WHERE p.venue_id = :venueId
               AND p.state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO',
                               'REGISTRADO','CERRADA','DESCARTADA','ENTREGADA_A_COLA')
               AND instr(p.payment_context_json, '"terminalPaymentRequestId":"' || r.request_id || '"') > 0)
           -- 🔴 Codex r6 (P2-11): por `created_at` las 20 más VIEJAS salían siempre, así que 20 que nunca se
           -- resuelven —un 404 permanente, por ejemplo— dejaban a la 21 sin consultar nunca (reproducido en SQLite:
           -- pasadas sucesivas devuelven las mismas 20). Por `updated_at` hay ROTACIÓN: `estamparConsultaHuerfana`
           -- manda al final de la fila a la que se consultó y no se pudo cerrar, así que todas acaban pasando.
           -- La ANTIGÜEDAD que el aviso muestra sigue saliendo de `created_at`, que no se toca.
           ORDER BY r.updated_at ASC LIMIT 20""",
    )
    suspend fun candidatasHuerfanas(venueId: String): List<HuerfanaDeBandeja>

    /** Codex r6 (P2-11): la consultada que NO se pudo cerrar va al final de la fila. Sólo mientras siga huérfana. */
    @Query(
        """UPDATE remote_payment_requests SET updated_at = :now
           WHERE request_id = :requestId AND venue_id = :venueId AND status = 'PROCESSING'""",
    )
    suspend fun estamparConsultaHuerfana(requestId: String, venueId: String, now: Long): Int

    /**
     * Cierra una huérfana con el desenlace que el SERVIDOR acredita. 🔴 Sólo si el servidor la da por resuelta:
     * `:status` tiene que ser uno de sus estados finales. Con una solicitud en vuelo (`SENT`, `PENDING`…) devuelve 0
     * y la fila se conserva — cerrar la bandeja de un cobro que puede estar vivo es el defecto contrario, y ése
     * cuesta dinero. Tampoco toca una fila ya resuelta ni una de otro venue.
     */
    @Query(
        """UPDATE remote_payment_requests
           -- 🔴 Codex r7 (P1-4): `final_emitted_at` es la CERCA que consulta la reserva (guarda 3 de `reserveTerminal`) y
           -- el CAS del efectivo. Sin ella, tras cerrar la huérfana todavía se podía reservar un intento NUEVO de esta misma
           -- solicitud y llegar al SDK. La escriben todos los caminos que cierran una solicitud; éste no la escribía.
           SET status = 'RESOLVED', updated_at = :now, final_result_json = :resultadoReproducible, final_emitted_at = :now
           WHERE request_id = :requestId AND venue_id = :venueId AND status = 'PROCESSING'
             -- Una ejecución de efectivo/cripto ya arrancada tampoco es huérfana (misma guarda que el cancel tras reclamar).
             AND execution_started_at IS NULL AND cancel_accepted_at IS NULL AND final_emitted_at IS NULL
             -- 🔴 Codex r6 (P2-8): la orfandad se revalida AQUÍ, no sólo al seleccionar la candidata.
             -- 🔴 Codex r7 (P1-4): y es MÁS ESTRICTA que la del selector y el aviso: CUALQUIER intento correlacionado, en
             -- cualquier estado y de cualquier venue, impide cerrar. La lista vieja omitía PREPARANDO y KERNEL_ACTIVO, y
             -- `KERNEL_ACTIVO` ya puede aprobar en local: D cerraba la bandeja de un cobro VIVO con un negativo del servidor.
             -- Reproducido por Codex con el SQL real (cerraba y el intento pasaba a AUTORIZANDO). Un intento que sí existe
             -- lo resuelve la libreta, que es la que sabe de dinero.
             AND NOT EXISTS (SELECT 1 FROM payment_attempts p
                 WHERE instr(p.payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0)""",
    )
    suspend fun conciliarHuerfanaConElServidorConResultado(requestId: String, venueId: String, resultadoReproducible: String, now: Long): Int

    /**
     * 🔴 Codex r6 (P2-9): el resultado que se guarda tiene que ser REPRODUCIBLE, porque la bandeja lo emite tal cual en
     * reentregas y sondas: sin `requestId` y con un vocabulario propio (`FAILED`), el servidor lo rechazaba. Dejarlo en
     * NULL era peor — la reentrega contesta `Reject` y la sonda contesta **ACTIVE**, o sea que la ranura seguiría apartada
     * por algo que el servidor YA cerró, que es justo el aviso que esta pieza viene a apagar.
     *
     * 🔴 Y sólo se conciliesan los desenlaces NEGATIVOS. Un `COMPLETED` significa que hay DINERO, y de eso no se encarga
     * un limpiador de avisos: lo resuelve la libreta por INTENTO. Emitir `success` sin `paymentId` haría que el servidor
     * lo degradara a `timeout` y volviera a retener la ranura — el defecto contrario.
     */
    suspend fun conciliarHuerfanaConElServidor(
        requestId: String,
        venueId: String,
        status: String,
        failureCode: String?,
        now: Long,
    ): Int {
        val reproducible = when (status) {
            "FAILED" -> "failed"
            "CANCELLED" -> "cancelled"
            else -> return 0 // COMPLETED y cualquier estado en vuelo: no es trabajo de la pieza D
        }
        val motivo = failureCode?.let { ",\"failureCode\":\"" + it.replace("\\", "").replace("\"", "") + "\"" } ?: ""
        val json = "{\"requestId\":\"" + requestId + "\",\"status\":\"" + reproducible + "\"" + motivo +
            ",\"conciliadaPorElServidor\":true}"
        return conciliarHuerfanaConElServidorConResultado(requestId, venueId, json, now)
    }

    /** Cuántas obligaciones ve hoy el aviso de la pantalla. Para poder comprobar que una conciliación lo apaga. */
    @Query(
        """SELECT count(*) FROM remote_payment_requests r
           WHERE r.venue_id = :venueId AND r.status = 'PROCESSING'
           AND NOT EXISTS (SELECT 1 FROM payment_attempts p
               WHERE p.venue_id = :venueId
               AND p.state IN ('AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO',
                               'REGISTRADO','CERRADA','DESCARTADA','ENTREGADA_A_COLA')
               AND instr(p.payment_context_json, '"terminalPaymentRequestId":"' || r.request_id || '"') > 0)""",
    )
    suspend fun observePendingObligationsCount(venueId: String): Int

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
    // Fix 4 (D3a): también bloquea la evidencia positiva DURABLE del servidor (`server_processor_evidence IS 'APPROVED'`): una
    // DESCARTADA liberada cuyo banco aprobó después. Es el ÚNICO predicado — lo comparten el cancel y H.3.
    // 🔴 Codex r8 (P1-1): y CUALQUIER contradicción con el servidor ([PaymentAttemptEntity.SQL_CONTRADICCION]: un veto durable,
    // un veredicto con dinero sin promover). Una DESCARTADA cuyo veto llegó después del cierre dejaba al cancel y a
    // `resolverDesenlaceNegativo` fabricar «no se cobró» con la contradicción conocida (Codex lo reprodujo: 1 fila, 0 bloqueadores).
    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE instr(payment_context_json, '"terminalPaymentRequestId":"' || :requestId || '"') > 0
        AND (legacy_shadow = 1 OR state NOT IN ('PREPARANDO', 'DESCARTADA') OR server_payment_id IS NOT NULL
             OR server_processor_evidence IS 'APPROVED'
             OR """ + PaymentAttemptEntity.SQL_CONTRADICCION + """)""")
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
