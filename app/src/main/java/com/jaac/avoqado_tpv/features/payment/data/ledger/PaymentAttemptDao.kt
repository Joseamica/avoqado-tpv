package com.jaac.avoqado_tpv.features.payment.data.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PaymentAttemptDao {

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND processor = 'ANGELPAY' AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND host_approved IS NULL AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getUnknownRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    @Query("""SELECT * FROM payment_attempts WHERE venue_id = :venueId
        AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND (host_approved = 1 OR state = 'AUTORIZADO') AND created_at < :olderThan AND verify_attempts < 5
        AND (lease_until IS NULL OR lease_until < :now) ORDER BY created_at ASC LIMIT 50""")
    suspend fun getApprovalRecoveryCandidates(venueId: String, olderThan: Long, now: Long): List<PaymentAttemptEntity>

    @Query("""UPDATE payment_attempts SET lease_until = :leaseUntil, verify_attempts = verify_attempts + 1
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND host_approved IS NULL
        AND state IN ('AUTORIZANDO','INDETERMINADO')
        AND (lease_until IS NULL OR lease_until < :now)""")
    suspend fun claimUnknownRecovery(attemptId: String, venueId: String, now: Long, leaseUntil: Long): Int

    /**
     * 🔴 ¿Qué está apartando el APARATO ahora mismo? El lector es UNO: sin venue y sin orden.
     *
     * Dos cosas distintas lo apartan, y conviene no mezclarlas:
     *
     *  1. **Una ejecución en curso** (`PREPARANDO`, `KERNEL_ACTIVO`, `AUTORIZANDO` y también
     *     `HOST_RESPONDIO`). Es físico: mientras alguien está en la fila del lector no entra
     *     nadie más. Sin `PREPARANDO` y `KERNEL_ACTIVO` dos intentos reservaban a la vez
     *     (medido: `expected:<1> but was:<2>`) y un ViewModel recreado entraba al kernel con la
     *     reserva anterior viva — dos cobros por un toque.
     *     🔴 `HOST_RESPONDIO` está aquí y NO abajo por un hallazgo de Codex (2026-09-12): la PAX
     *     lo escribe ANTES de terminar el kernel — `CompleteEmvTrans` corre después—, así que
     *     ahí «el SDK ya salió» sería falso. Se paga con que una fila atorada en ese estado
     *     aparta la caja; es el lado conservador, y la salida general es la consulta del
     *     desenlace (F2), no relajar esto.
     *  2. **Una obligación pendiente que NO acredita que el SDK terminó.** Y aquí la regla es
     *     estrecha a propósito, porque cada vez que se amplió apareció un hueco de dinero:
     *     **lo único que suelta el aparato es un `INDETERMINADO` con motivo escrito y con
     *     `orderId`**. Todo lo demás lo sigue apartando:
     *      - `AUTORIZADO` y `REGISTRO_FALLIDO`: la recuperación llega a ellos **por antigüedad**
     *        desde un `HOST_RESPONDIO` de la PAX que todavía puede tener `CompleteEmvTrans`
     *        colgado. Antes de F0 esos estados retenían; volver a soltarlos abría el lector con
     *        la llamada nativa posiblemente viva (Codex, 12-sep, reproducido en SQLite). En el
     *        camino normal duran segundos: pasan a `REGISTRADO` o a `ENTREGADA_A_COLA`, que no
     *        están en esta lista.
     *      - Un `INDETERMINADO` **sin motivo**: en esta libreta todo `markIndeterminate` escribe
     *        uno, así que vacío sólo puede venir de la cuarentena por reloj de una versión
     *        anterior — las filas que ya viven en los aparatos instalados.
     *      - Un `INDETERMINADO` marcado `cuarentena_por_antiguedad`: lo decidió el reloj, no el
     *        procesador.
     *      - Un `INDETERMINADO` sin `orderId`: no hay venta que cercar, así que se cerca el aparato.
     *
     * 🔴 Lo que NO aparta el aparato: una obligación pendiente **CON** `orderId`. Ésa cerca SU
     * venta —la guarda 2 de [reserveTerminal], por orden y sin filtro de venue— y el negocio
     * sigue cobrando las demás. Apagar la terminal entera por una venta con dueño no protegía de
     * nada y dejó a una Nexgo inservible con tres cobros pendientes (Testarudo, 2026-09-12).
     * El criterio de «identidad utilizable» es EXACTAMENTE el que hace efectiva la guarda 2:
     * `"orderId":"…"` presente y no vacío, que es cuando `PaymentAttemptLedger.openAttempt`
     * arma el fragmento — por eso allí se usa `isNullOrEmpty` y no `isNullOrBlank`. Si cambia
     * uno, cambia el otro o se abre un hueco de doble cobro.
     *
     * ⚠️ Límite conocido y declarado: esto compara TEXTO, así que dos escrituras distintas del
     * MISMO id (un `\u0061` en vez de una `a`, o el JSON con espacios tras los dos puntos) no se
     * reconocerían como la misma venta. Hoy no ocurre porque el único productor es
     * `Gson().toJsonTree(...).toString()`, compacto y sin escapar ASCII. Si algún día otro
     * camino escribe ese contexto, esto deja de ser cierto.
     *
     * Una fila HEREDADA (`legacy_shadow = 1`, la libreta SHADOW de 2.9.x) no aparta la terminal:
     * en esa versión no reservaba. Sigue visible en los conteos (ver [PaymentAttemptEntity.legacyShadow]).
     */
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0
        AND (
            state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','HOST_RESPONDIO')
            OR state IN ('AUTORIZADO','REGISTRO_FALLIDO')
            OR (state = 'INDETERMINADO'
                AND (last_error IS NULL
                     OR last_error = 'cuarentena_por_antiguedad'
                     OR instr(payment_context_json, '"orderId":"') = 0
                     OR instr(payment_context_json, '"orderId":""') > 0))
        )
        LIMIT 1""")
    suspend fun findTerminalHold(): PaymentAttemptEntity?

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
    @Query("""SELECT * FROM payment_attempts
        WHERE legacy_shadow = 0
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
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
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
        AND instr(payment_context_json, :requestJsonFragment) > 0
        ORDER BY updated_at DESC LIMIT 1""")
    suspend fun findUnresolvedForRequest(requestJsonFragment: String): PaymentAttemptEntity?

    /**
     * Cuántas obligaciones pendientes hay en el APARATO. Es lo que permite distinguir «no hay con
     * qué confundirse» (una sola, se puede adoptar a ciegas como siempre) de «hay varias», donde
     * adivinar sería atribuir dinero. Sin venue: la terminal es UNA.
     */
    @Query("""SELECT COUNT(*) FROM payment_attempts
        WHERE legacy_shadow = 0
        AND state IN ('KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')""")
    suspend fun countUnresolvedCharges(): Int


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
     *  1. **El aparato**, mientras hay una ejecución en curso o una obligación pendiente que no
     *     sabe de qué venta es (ver [findTerminalHold]). Es el único candado que apaga la caja
     *     entera, y por eso es el más angosto posible.
     *     🔴 **Salvo en el KIOSCO** (`esKiosco`), donde CUALQUIER obligación pendiente vuelve a
     *     apartar el aparato. Todo F0 se apoya en que el cajero puede distinguir «reintento de la
     *     venta incierta» de «venta nueva idéntica» — y en autoservicio **no hay cajero**: la
     *     pantalla la ve el cliente, así que ni se le puede preguntar ni se le puede enseñar el
     *     importe de la venta de otro. Sin esto, un kiosco que se reinicia pierde su orden en
     *     memoria, crea otra y cobra dos veces la misma compra (Codex, 2026-09-12).
     *  2. **La venta**, por `orderId`, para TODO estado no resuelto. 🔴 Sin filtro de venue a
     *     propósito: la terminal es UNA, y un cobro sin desenlace de la cuenta 7 sigue siendo de
     *     la cuenta 7 aunque el turno haya cambiado de sucursal. Ésta es la que impide el cobro
     *     doble mientras el aparato sigue cobrando las demás cuentas.
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
            payment_context_json, verify_attempts, created_at, updated_at, terminal_payment_request_id)
        SELECT :attemptId, :venueId, :processor, :kind, 'PREPARANDO', 0,
            :amountCents, :tipCents, 'MXN', :recordingRoute, 1,
            :contextJson, 0, :now, :now, :terminalPaymentRequestId
        WHERE NOT EXISTS (
            SELECT 1 FROM payment_attempts hold
            WHERE hold.legacy_shadow = 0
            AND (
                hold.state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','HOST_RESPONDIO')
                OR hold.state IN ('AUTORIZADO','REGISTRO_FALLIDO')
                OR (hold.state = 'INDETERMINADO'
                    AND (:esKiosco = 1
                         OR hold.last_error IS NULL
                         OR hold.last_error = 'cuarentena_por_antiguedad'
                         OR instr(hold.payment_context_json, '"orderId":"') = 0
                         OR instr(hold.payment_context_json, '"orderId":""') > 0))
            )
        )
        AND (:orderJsonFragment IS NULL OR NOT EXISTS (
            SELECT 1 FROM payment_attempts dup
            WHERE dup.legacy_shadow = 0
            AND dup.state IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO','INDETERMINADO','HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO')
            AND instr(dup.payment_context_json, :orderJsonFragment) > 0
        ))
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
        terminalPaymentRequestId: String?, esKiosco: Boolean,
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

    @Query("""UPDATE payment_attempts SET state = 'HOST_RESPONDIO', state_version = state_version + 1,
        updated_at = :now, host_approved = 1, reference_number = :reference, auth_code = :authorization,
        lease_until = NULL, verify_attempts = 0
        WHERE attempt_id = :attemptId AND venue_id = :venueId AND lease_until = :ownedLease
        AND lease_until > :now AND host_approved IS NULL AND state IN ('AUTORIZANDO','INDETERMINADO')""")
    suspend fun completeUnknownRecovery(attemptId: String, venueId: String, ownedLease: Long,
        now: Long, reference: String, authorization: String): Int

    /** Returns -1 when the PK already exists (attemptId reuse — double-charge signal, never silent). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(attempt: PaymentAttemptEntity): Long

    @Query("SELECT * FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun getById(attemptId: String): PaymentAttemptEntity?

    /**
     * The CAS everything rides on (spec §4.4): a transition only lands if the row
     * is still in one of the expected states. Returns 0 when it didn't match —
     * caller logs and moves on (worker/callback/manual races resolve themselves).
     *
     * 🔴 La condición extra de `AUTORIZANDO` es la MISMA cerca de aparato de [findTerminalHold],
     * un piso más abajo: sin ella, un ViewModel recreado entraba a la autorización online encima
     * de un cobro en vuelo. Sigue cerrando mientras otro intento **está ejecutando**
     * (`KERNEL_ACTIVO`, `AUTORIZANDO`) o tiene una obligación pendiente **sin identidad de venta**.
     * Ya NO cierra por una obligación pendiente CON `orderId`: ésa cerca su propia venta en la
     * guarda 2 de [reserveTerminal], y apagar la autorización de TODAS las demás cuentas por ella
     * es el mismo defecto que dejó inservible una Nexgo (Testarudo, 2026-09-12).
     *
     * ⚠️ `PREPARANDO` queda fuera a propósito, igual que en [findUnresolvedCharge]: una fila que
     * sólo reservó no puede haber movido dinero, y meterla aquí trabaría cada reintento normal.
     *
     * 🔴 El predicado de «identidad utilizable» vive IDÉNTICO en tres sitios —éste,
     * [findTerminalHold] y la guarda 1 de [reserveTerminal]— y tiene que seguir igual en los
     * tres: si uno se relaja y otro no, o la caja se apaga sin motivo o se abre un doble cobro.
     */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)
           AND (:newState != 'AUTORIZANDO' OR NOT EXISTS (
               SELECT 1 FROM payment_attempts other WHERE other.attempt_id != :attemptId
               AND other.legacy_shadow = 0
               AND (
                   other.state IN ('KERNEL_ACTIVO','AUTORIZANDO','HOST_RESPONDIO')
                   OR other.state IN ('AUTORIZADO','REGISTRO_FALLIDO')
                   OR (other.state = 'INDETERMINADO'
                       AND (other.last_error IS NULL
                            OR other.last_error = 'cuarentena_por_antiguedad'
                            OR instr(other.payment_context_json, '"orderId":"') = 0
                            OR instr(other.payment_context_json, '"orderId":""') > 0))
               )
           ))"""
    )
    suspend fun casTransition(attemptId: String, expectedStates: List<String>, newState: String, now: Long): Int

    /** CAS + host outcome in one statement — used the instant the host responds. */
    @Query(
        """UPDATE payment_attempts
           SET state = :newState, state_version = state_version + 1, updated_at = :now,
               operation_id = :operationId, reference_number = :referenceNumber,
               auth_code = :authCode, host_approved = :hostApproved,
               lease_until = NULL, verify_attempts = 0
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
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
           WHERE attempt_id = :attemptId AND state IN (:expectedStates)"""
    )
    suspend fun casWithError(attemptId: String, expectedStates: List<String>, newState: String, now: Long, error: String?): Int

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

    /** Prune terminal rows at ~7 days (mirror of deleteOldSyncedPayments). INDETERMINADO is NEVER deleted. */
    @Query(
        """DELETE FROM payment_attempts WHERE venue_id = :venueId AND state IN ('CERRADA','DESCARTADA') AND updated_at < :olderThan
           AND NOT """ + PaymentAttemptEntity.SQL_CONTRADICCION
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
                OR (server_outcome = 'SECOND_CAPTURE_EVIDENCE' AND server_winner_payment_id IS NOT NULL))"""
    )
    suspend fun registrarPorVeredictoDelServidor(attemptId: String, now: Long): Int

    /** Una consulta S6 sin veredicto aplicable (NOT_RECORDED, 404, error HTTP) sólo gasta el turno de la fila (E3). */
    @Query("UPDATE payment_attempts SET server_checked_at = :now, server_check_count = server_check_count + 1 WHERE attempt_id = :attemptId")
    suspend fun estamparConsultaAlServidor(attemptId: String, now: Long): Int

    @Query("SELECT " + PaymentAttemptEntity.SQL_CONTRADICCION + " FROM payment_attempts WHERE attempt_id = :attemptId")
    suspend fun esContradiccion(attemptId: String): Boolean?

    /**
     * E3 · candidatas de CONSULTA S6: con solicitud, sin veredicto o con veredicto NO final (el servidor puede volverlo
     * RECORDED al conciliar), fuera de los estados con SDK dentro salvo que lleven más de :vivosAntesDe, y espaciadas por
     * intento (`min(base × 2^count, tope)`). Orden ESTABLE con avance: la consultada va al final (`server_checked_at`
     * ascendente, NULL primero por defecto en SQLite — nada de `NULLS FIRST`, que exige SQLite 3.30). 25 por pasada.
     */
    @Query(
        """SELECT * FROM payment_attempts
           WHERE venue_id = :venueId AND legacy_shadow = 0 AND processor = 'ANGELPAY' AND kind = 'SALE'
           AND terminal_payment_request_id IS NOT NULL AND state != 'CERRADA'
           AND (state NOT IN ('PREPARANDO','KERNEL_ACTIVO','AUTORIZANDO') OR updated_at < :vivosAntesDe)
           AND (server_outcome IS NULL OR server_outcome IN ('REFERENCE_COLLISION_EVIDENCE','PENDING_EVIDENCE','RELEASED_NO_EVIDENCE','OPERATOR_NO_INSTRUMENT'))
           AND (server_checked_at IS NULL
                OR server_checked_at < :now - MIN(:espaciadoBaseMs * (1 << MIN(server_check_count, 7)), :espaciadoTopeMs))
           ORDER BY server_checked_at ASC, created_at ASC, attempt_id ASC
           LIMIT 25"""
    )
    suspend fun candidatasDeConsultaAlServidor(venueId: String, vivosAntesDe: Long, now: Long, espaciadoBaseMs: Long, espaciadoTopeMs: Long): List<PaymentAttemptEntity>

    /**
     * E2 · veredictos FINALES ya guardados que todavía no se aplicaron: la fila cambió de estado (el SDK volvió incierto,
     * el registro falló…) después de guardar la evidencia. Se REAPLICAN sin otra consulta.
     */
    // Codex (código, P1-3): sólo entran al lote los veredictos que la transición PUEDE aplicar (mismas condiciones que
    // `registrarPorVeredictoDelServidor`): una contradicción (montos distintos, rechazo del host) o una segunda captura sin
    // ganador nunca sale de este conjunto por sí sola y, con 50 de ellas delante, el intento 51 no se reaplicaba jamás.
    @Query(
        """SELECT * FROM payment_attempts
           WHERE venue_id = :venueId AND legacy_shadow = 0 AND processor = 'ANGELPAY' AND kind = 'SALE'
           AND server_outcome IN ('RECORDED','SECOND_CAPTURE_EVIDENCE')
           AND state IN ('HOST_RESPONDIO','AUTORIZADO','REGISTRO_FALLIDO','ENTREGADA_A_COLA','INDETERMINADO')
           AND host_approved IS NOT 0
           AND server_amount_cents = amount_cents AND server_tip_cents = tip_cents
           AND (server_outcome = 'RECORDED' OR server_winner_payment_id IS NOT NULL)
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
        val requestId = v.requestId ?: fila.terminalPaymentRequestId
        // Codex (código, P1-2/P1-6): fuera del checkpoint (heredada, Blumon/PAX, devolución) NO es un rechazo — quien llama
        // sigue su camino anterior; un venue o una solicitud ajenos SÍ lo son (anomalía: no se pisa nada).
        if (fila.legacyShadow || fila.processor != PaymentAttemptEntity.PROCESSOR_ANGELPAY || fila.kind != PaymentAttemptEntity.KIND_SALE)
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
     */
    @Query(
        """UPDATE payment_attempts SET state = 'DESCARTADA', last_error = :motivo, server_outcome = :serverOutcome,
           server_verdict_at = :now, updated_at = :now, state_version = state_version + 1
           WHERE attempt_id = :attemptId AND venue_id = :venueId AND legacy_shadow = 0 AND processor = 'ANGELPAY' AND kind = 'SALE'
             AND state = 'INDETERMINADO' AND host_approved IS NOT 1 AND server_outcome IS NULL""",
    )
    suspend fun cerrarPorLiberacionDelServidor(attemptId: String, venueId: String, serverOutcome: String, motivo: String, now: Long): Int

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
