package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** E3 · cuántas candidatas trae cada página de la consulta S6 ([PaymentAttemptDao.candidatasDeConsultaAlServidor]). */
const val CANDIDATAS_POR_PAGINA = 25

@Dao
interface PaymentAttemptDao {

    /**
     * Re-revisión de F (I-1, 26-sep): una fila con VETO no entra (`server_veto IS NULL`). Es de Avoqado conciliar: se queda en
     * duda, cerca su venta y no aparta nada; subirla a HOST_RESPONDIO la dejaba atorada ahí (el veto impide REGISTRADO), y
     * HOST_RESPONDIO de este proceso aparta el aparato. Va en el SQL y no en quien llama: filtrada después del `LIMIT`, 50 filas
     * vetadas taparían para siempre a la 51.
     */
    // 🔴 29-sep-2026: sólo VENTAS. Una devolución la verifica su propio flujo; hoy sólo la dejaba fuera que su `processor`
    // se escribe en minúsculas, una coincidencia que no protege nada.
    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId AND kind = 'SALE'
        AND processor = 'ANGELPAY' AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND host_approved IS NULL AND server_veto IS NULL AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getUnknownRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    // 🔴 29-sep-2026: sólo VENTAS — esta recuperación registra con los registradores de VENTA. Una devolución aprobada sin
    // registrar la cierra su cola (`pending_refunds`); hoy sólo la dejaba fuera que su contexto no se deja leer como venta.
    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId AND kind = 'SALE'
        AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND (host_approved = 1 OR state = 'AUTORIZADO') AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getApprovalRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved IS NULL
        AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimUnknownRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

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
     *  2. **La venta**, por `orderId`, sólo con DINERO en juego; ver [PaymentAttemptEntity.SQL_CERCA_LA_VENTA] — el MISMO
     *     fragmento que leen [retencionDeLaVenta] y el CAS a AUTORIZANDO/KERNEL_ACTIVO, así que no pueden divergir. Cerca un
     *     cobro corriendo o ya respondido (de cualquier proceso), la evidencia del servidor o su veto y la cuarentena por reloj
     *     de ESTE proceso. 🔴 Founder, 25-sep: una duda sin dinero conocido ya no cerca nada, ni en la PAX ni en la Nexgo: se
     *     AVISA y decide el cajero. Una DESCARTADA con evidencia positiva DURABLE del servidor (fix 4, D3c: liberada por la
     *     ventana, con el banco aprobando después) sigue cercando su venta INCLUSO si la nueva solicitud del POS es otra;
     *     lo mismo con un VETO durable (Codex r7, P1-2), sin reabrir la fila. Sin identidad de venta nada se cerca (r6).
     *     🔴 Sin filtro de venue a propósito: la terminal es UNA, y un cobro de la cuenta 7 sigue siendo de la cuenta 7 aunque
     *     el turno haya cambiado de sucursal. Ésta es la que impide el cobro doble mientras el aparato cobra las demás cuentas.
     *  3. **La solicitud del POS**, por lápida/cancel/desenlace en la bandeja.
     *
     * 🛑 CERCA (C.5 / H.3, 11-sep): ningún intento nuevo de una solicitud remota cuya fila en la bandeja
     * tenga lápida (`NOT_FOUND_ANSWERED`), cancelación aceptada (`cancel_accepted_at`) o un desenlace final
     * ya escrito (`final_emitted_at`). Va DENTRO de esta sentencia, no en una lectura previa: un reintento
     * y un cancel simultáneos no pueden ganar los dos. 🔴 A propósito NO exige «la bandeja está en
     * PROCESSING»: tras un rechazo reintentable la solicitud sigue en PROCESSING y el reintento es legítimo.
     *
     * Las filas HEREDADAS (`legacy_shadow = 1`) no cuentan para reservar: en 2.9.2 no reservaban.
     */
    @Query("""INSERT OR IGNORE INTO payment_attempts (
            attempt_id, venue_id, processor, kind, state, state_version,
            amount_cents, tip_cents, currency, recording_route, context_schema_version,
            payment_context_json, verify_attempts, created_at, updated_at, terminal_payment_request_id, process_token)
        SELECT :attemptId, :venueId, :processor, :kind, 'PREPARANDO', 0,
            :amountCents, :tipCents, 'MXN', :recordingRoute, 1,
            :contextJson, 0, :now, :now, :terminalPaymentRequestId, :processToken
        WHERE NOT EXISTS (SELECT 1 FROM payment_attempts WHERE """ + PaymentAttemptEntity.SQL_APARTA_EL_APARATO + """)
        AND (:orderJsonFragment IS NULL OR NOT EXISTS (
            SELECT 1 FROM payment_attempts WHERE """ + PaymentAttemptEntity.SQL_CERCA_LA_VENTA + """
            AND instr(payment_context_json, :orderJsonFragment) > 0))
        AND (:terminalPaymentRequestId IS NULL OR NOT EXISTS (
            SELECT 1 FROM remote_payment_requests cerca
            WHERE cerca.request_id = :terminalPaymentRequestId
            AND (cerca.status = 'NOT_FOUND_ANSWERED' OR cerca.cancel_accepted_at IS NOT NULL
                 OR cerca.final_emitted_at IS NOT NULL)
        ))""")
    suspend fun reserveTerminal(
        attemptId: String, venueId: String, processor: String, kind: String,
        amountCents: Long, tipCents: Long, recordingRoute: String,
        contextJson: String, orderJsonFragment: String?, now: Long,
        terminalPaymentRequestId: String?, processToken: String,
    ): Long

    /**
     * Por qué una solicitud remota ya no admite otro intento, o 'LIBRE'. Sólo LEE la bandeja: la usa el
     * ViewModel para traducir un fallo de la cerca a lo que de verdad pasó («El POS canceló este cobro»
     * o «Este cobro ya se cerró»), nunca a «No se pudo guardar el intento». null = la bandeja no la tiene.
     */
    @Query("""SELECT CASE
            WHEN cancel_accepted_at IS NOT NULL THEN 'CANCELADA_POR_EL_POS'
            WHEN status = 'NOT_FOUND_ANSWERED' OR final_emitted_at IS NOT NULL THEN 'CERRADA'
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
     * pierde. Devuelve 1 si puede arrancar, 0 si no.
     */
    @Query("""UPDATE remote_payment_requests
        SET execution_started_at = COALESCE(execution_started_at, :now), updated_at = :now
        WHERE request_id = :requestId AND status = 'PROCESSING'
        AND cancel_accepted_at IS NULL AND final_emitted_at IS NULL""")
    suspend fun iniciarEjecucionNoTarjeta(requestId: String, now: Long): Int

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
    @Query("SELECT * FROM payment_attempts WHERE " + PaymentAttemptEntity.SQL_CERCA_LA_VENTA +
        " AND instr(payment_context_json, :orderJsonFragment) > 0 LIMIT 1")
    suspend fun retencionDeLaVenta(orderJsonFragment: String, processToken: String): PaymentAttemptEntity?

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId
        AND state IN ('HOST_RESPONDIO', 'AUTORIZADO', 'REGISTRO_FALLIDO')
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /**
     * 🔴 `last_error` CONSERVA la marca de cuarentena si ya la traía. La recuperación admite
     * `HOST_RESPONDIO` por ANTIGÜEDAD y, si el registro falla, escribía «Cobrado; registro
     * pendiente» encima — borrando la única señal de que nadie acreditó el final del SDK, y
     * dejando que F0 soltara el lector con la llamada nativa todavía posiblemente viva
     * (Codex, 2026-09-12). El mensaje nuevo se antepone para no perder el diagnóstico.
     */
    @Query("""UPDATE payment_attempts SET state = :newState, state_version = state_version + 1,
        updated_at = :now,
        last_error = CASE WHEN COALESCE(last_error,'') = 'cuarentena_por_antiguedad'
                          THEN 'cuarentena_por_antiguedad' ELSE :error END
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    suspend fun completeRecovery(attemptId: String, venueId: String, ownedLease: Long,
        newState: String, now: Long, error: String?): Int

    /** I-1: `server_veto IS NULL` también DENTRO del UPDATE — un veto que llega entre tomar la fila y escribir no la deja subir. */
    @Query("""UPDATE payment_attempts SET state = 'HOST_RESPONDIO', state_version = state_version + 1,
        updated_at = :now, host_approved = 1, reference_number = :reference, auth_code = :authorization,
        lease_until = NULL, verify_attempts = 0
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND host_approved IS NULL AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND server_veto IS NULL""")
    suspend fun completeUnknownRecovery(attemptId: String, venueId: String, ownedLease: Long,
        now: Long, reference: String, authorization: String): Int

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
               NOT EXISTS (SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
                           AND """ + PaymentAttemptEntity.SQL_EJECUCION_VIVA + """)
               AND NOT EXISTS (SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
                   AND """ + PaymentAttemptEntity.SQL_CERCA_LA_VENTA + """
                   AND instr(payment_attempts.payment_context_json, '"orderId":"') > 0
                   AND instr(payment_attempts.payment_context_json, '"orderId":""') = 0
                   AND instr(other.payment_context_json,
                             substr(payment_attempts.payment_context_json,
                                    instr(payment_attempts.payment_context_json, '"orderId":"'),
                                    11 + instr(substr(payment_attempts.payment_context_json,
                                                      instr(payment_attempts.payment_context_json, '"orderId":"') + 11), '"'))) > 0)))"""
    )
    suspend fun casTransition(attemptId: String, expectedStates: List<String>, newState: String, now: Long, processToken: String): Int

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
           SET state = :newState, state_version = state_version + 1, updated_at = :now, last_error = :error
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
             AND (:newState != 'DESCARTADA' OR (
                 host_approved IS NOT 1 AND server_processor_evidence IS NOT 'APPROVED'
                 AND server_veto IS NULL AND server_payment_id IS NULL
                 AND server_outcome IS NULL))"""
    )
    suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int

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
     *  2. `state = 'INDETERMINADO'` — un cobro que quedó sin veredicto; el único llamador, la PAX, sólo pasa INDETERMINADO
     *     (`puedeConciliarBlumon`). Nunca AUTORIZANDO (revisión final, M-4, 26-sep): puede ser un cobro en vuelo.
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
                  OR server_outcome NOT IN ('RECORDED','SECOND_CAPTURE_EVIDENCE','REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE'))"""
    )
    suspend fun declararSinCobroLocal(attemptId: String, venueId: String, motivo: String, now: Long): Int

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
     */
    @Query(
        """UPDATE payment_attempts
           SET state = 'INDETERMINADO', state_version = state_version + 1, updated_at = :now,
               last_error = 'cuarentena_por_antiguedad'
           WHERE venue_id = :venueId AND state = 'KERNEL_ACTIVO' AND updated_at < :olderThan"""
    )
    suspend fun quarantineStaleKernel(venueId: String, olderThan: Long, now: Long): Int

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
        marcarEvidenciaPositivaDelServidor(v.attemptId, v.venueId, now)
        // 🔴 Ronda 21 (Codex r19, P1-1): el VETO que trajo la MISMA respuesta, en la MISMA transacción. Aplicado sin él, un
        // RECORDED con `evidenceContradiction` dejaba la fila limpia y el aviso ofrecía «Entendido» (confirmar y volver a cobrar).
        v.veto?.let { marcarVetoDelServidor(v.attemptId, v.venueId, it, now) }
        val requestId = v.requestId ?: fila.terminalPaymentRequestId
        // Codex (código, P1-2/P1-6): fuera del checkpoint (heredada, procesador desconocido, devolución) NO es un rechazo — quien llama
        // sigue su camino anterior; un venue o una solicitud ajenos SÍ lo son (anomalía: no se pisa nada).
        if (fila.legacyShadow || fila.processor !in setOf(PaymentAttemptEntity.PROCESSOR_ANGELPAY, PaymentAttemptEntity.PROCESSOR_BLUMON) || fila.kind != PaymentAttemptEntity.KIND_SALE)
            return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.FUERA_DE_ALCANCE, false, null, false)
        if (fila.venueId != v.venueId ||
            (v.requestId != null && fila.terminalPaymentRequestId != null && fila.terminalPaymentRequestId != v.requestId)
        ) return ResultadoDelVeredicto(ResultadoDelVeredicto.Decision.RECHAZADO_PERTENENCIA, false, null, false)
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
             AND server_veto IS NULL""",
    )
    suspend fun cerrarPorLiberacionDelServidor(attemptId: String, venueId: String, requestId: String, serverOutcome: String, motivo: String, now: Long): Int

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
             AND server_veto IS NULL""",
    )
    suspend fun cerrarPorLiberacionLocalDelServidor(attemptId: String, venueId: String, serverOutcome: String, motivo: String, now: Long): Int

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
