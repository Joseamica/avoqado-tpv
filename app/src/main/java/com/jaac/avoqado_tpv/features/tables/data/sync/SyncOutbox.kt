package com.jaac.avoqado_tpv.features.tables.data.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentInput
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentDao
import com.jaac.avoqado_tpv.features.tables.data.local.SyncIntentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El outbox de Mesas — corazón del offline (Plan C, Task 3). Puerto deliberado de
 * `avoqado-android/core/data/sync/SyncOutbox.kt` (ya probado en producción): mismo
 * contrato de wire, mismas tres garantías de dinero/orden. Ver
 * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` antes de tocar esto.
 *
 * **Qué se reusó de la cola de pagos (Plan A) y por qué:**
 * - `NonCancellable` alrededor de la escritura del intent en [enqueue] — mismo
 *   patrón que `PaymentQueueRepositoryImpl.enqueue()`: el efecto de negocio (abrir
 *   mesa, mandar ronda, cobrar efectivo) ya ocurrió en la UI antes de llamar
 *   `enqueue()`; esta fila es la ÚNICA prueba de esa acción mientras no hay red, no
 *   trabajo opcional que se pueda perder si el ViewModel se cancela a media
 *   escritura.
 * - `catch (e: CancellationException) { throw e }` ANTES de cualquier `catch
 *   (e: Exception)` — `CancellationException` ES una `RuntimeException` en
 *   Kotlin; un catch desnudo se la traga. Ver `PaymentSyncWorker.markSyncedChecked`.
 * - Clasificación de fallo de red vs. servidor por código, nunca por texto —
 *   aquí no aplica `classifySyncFailure` directo (esa es la Task 4,
 *   `TablesRepository.addItems`) pero [replayNow] seguía la misma disciplina: una
 *   excepción de red o un HTTP no-exitoso deja el batch PENDING; nunca se
 *   interpreta un fallo de transporte como un rechazo de negocio.
 *
 * **Qué se hizo DELIBERADAMENTE distinto de `avoqado-android`:**
 * - Sin lifecycle propio (`start()`/`stop()`, timer de seguridad, listener de
 *   conectividad). El "Produces" de la Task 3 es `enqueue` · `replayNow` ·
 *   `rejectedIntents` · `dismissRejected` — CUÁNDO llamar `replayNow` es decisión
 *   de la Task 4 (`TablesRepository.addItems`) y la Task 5
 *   (`TableSyncCoordinator`), que sí conocen la señal de conectividad de Mesas.
 *   Cablear eso aquí habría sido diseñar una tarea que todavía no existe.
 * - `deviceId` NO es un UUID generado y guardado en SharedPreferences propio
 *   (como hace android) — se REUSA el serial de terminal ya persistido por la
 *   cola de pagos (`SecureStorage.getSerialNumber()`, poblado en
 *   `PendingPaymentEntity.deviceSerialNumber` y usado en heartbeats/comandos
 *   remotos). Instrucción explícita de la Task 3: si el id cambiara al reiniciar,
 *   el mismo POS se vería como dos peers para el árbitro del hub LAN y para el
 *   reducer del server.
 * - `applyAck` es un método público de primera clase (no un detalle interno de
 *   `replayNow`) para que los tests de la Task 3 puedan ejercer las tres
 *   transiciones de ack sin levantar HTTP — y para que la Task 5
 *   (`TableSyncCoordinator`) pueda reaccionar a un ack sin duplicar la lógica de
 *   persistencia.
 * - [replayNow] (Task 5) acepta un callback `onAck` opcional, invocado DESPUÉS
 *   de que [applyAck] ya persistió — `TableSyncCoordinator` lo usa para
 *   reconciliar `TableSession` (promover el UUID local de un OPEN_TABLE
 *   ACKED, refrescar la `version` tras un ADD_ITEMS) SIN reimplementar el
 *   batching/FIFO/HTTP de este método. Default `{ _, _ -> }` — cero cambio de
 *   comportamiento para los callers existentes (`SyncOutboxReplayTest`).
 */
@Singleton
class SyncOutbox @Inject constructor(
    private val dao: SyncIntentDao,
    private val api: TablesApiService,
    private val secureStorage: SecureStorage,
    private val shiftRepository: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftRepository,
    private val shiftReplayGate: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftReplayGate? = null,
) {
    private val replayMutex = Mutex()
    private val gson = Gson()

    /**
     * Identidad del dispositivo para el server — REUSADA del outbox de pagos, ver
     * KDoc de la clase. `"UNKNOWN-DEVICE"` solo puede ocurrir si Mesas se usa
     * antes de que la terminal se active (no debería pasar: abrir/operar una mesa
     * ya requiere sesión de venue), pero un fallback explícito es preferible a un
     * NPE en medio de una ronda.
     */
    private val deviceId: String
        get() = secureStorage.getSerialNumber() ?: "UNKNOWN-DEVICE"

    /**
     * Escribe el intent ANTES de cualquier efecto (write-ahead) y devuelve su id.
     *
     * Ese id ES el `idempotencyKey` que el server usa tal cual — ver
     * `applyPayCash` en `sync.mobile.service.ts`, que pasa `intent.id` directo a
     * `payCashOrder({ idempotencyKey: intent.id })`. Un PAY_CASH que se reproduce
     * (retry de red, batch reenviado) nunca duplica el cobro porque el id nunca
     * cambia entre reintentos: se asigna UNA vez aquí y de ahí en adelante viaja
     * tal cual en cada replay ([toWire]).
     *
     * @param id idempotencyKey a usar — por default un UUID nuevo. Un caller
     *   write-ahead (p.ej. [com.jaac.avoqado_tpv.features.tables.data.TablesRepository.addItems])
     *   puede pasar uno YA generado para poder incrustar la MISMA llave en el
     *   payload (`externalId` por línea) antes de escribirlo aquí — así el
     *   intent persistido y el intento online (si lo hay) comparten
     *   idempotencyKey byte-idéntico.
     * @return el id del intent (UUID = idempotencyKey).
     */
    suspend fun enqueue(venueId: String, type: String, payload: JsonObject, id: String = UUID.randomUUID().toString()): String {
        val entity = SyncIntentEntity(
            id = id,
            venueId = venueId,
            seq = 0, // reemplazado dentro de insertWithSeq, dentro de la misma transacción
            type = type,
            payloadJson = gson.toJson(payload),
        )
        val seq = withContext(NonCancellable + Dispatchers.IO) {
            if (type == SyncIntentTypes.PAY_CASH) {
                shiftRepository.guardarNuevaObligacionDeCaja(venueId) { belonging ->
                    dao.insertWithSeq(entity.copy(shiftMembership = belonging.kind, localShiftSessionId = belonging.localSessionId,
                        cashDrawerSessionId = belonging.cashDrawerSessionId))
                }
            } else dao.insertWithSeq(entity)
        }
        Timber.i("📥 [Tables] Encolado %s seq=%d id=%s venue=%s", type, seq, id, venueId)
        return id
    }

    /**
     * Descarta un intent PENDING que [enqueue] escribió por adelantado
     * (write-ahead) y que resultó innecesario — el caller ya sabe por otra vía
     * que no hay nada que reproducir: el intento online tuvo éxito, o el
     * server lo rechazó de forma permanente. `NonCancellable` por la misma
     * razón que [enqueue]: esto es limpieza del registro de una acción que
     * YA ocurrió, no trabajo opcional que se pueda perder a media cancelación.
     *
     * Seguro de llamar aunque el intent ya no exista o ya no esté PENDING
     * (p.ej. un replay concurrente lo ganó de mano) — [dao]'s `discardPending`
     * está acotado a `status = 'PENDING'`, así que nunca borra un ACKED/REJECTED.
     */
    suspend fun discardPending(id: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            dao.discardPending(id)
        }
    }

    /**
     * Aplica el ack del server a un intent.
     *
     * - `ACKED`/`REJECTED` → estados terminales, se persisten.
     * - `RETRY` (hoy solo `VERSION_CONFLICT`) → NUNCA se persiste. El intent se
     *   deja tal cual (PENDING); el próximo `replayNow` lo reintenta solo.
     *   Convertir esto en `REJECTED` perdería el intent PARA SIEMPRE — fue un P1
     *   real (`.claude/rules/offline-first-y-hub-lan.md` §2.2).
     * - Cualquier otro string → no-op defensivo (se queda PENDING). Un server más
     *   nuevo que mande un status que este cliente no conoce NUNCA debe
     *   interpretarse como éxito ni como rechazo permanente por default.
     *
     * Reproducir el mismo ack dos veces (mismo `intentId`, mismo `status`) tiene
     * un solo efecto: es un `UPDATE ... WHERE id = :id`, no un `INSERT` — la
     * segunda llamada conserva los valores terminales existentes.
     */
    suspend fun applyAck(
        intentId: String,
        status: String,
        errorCode: String?,
        message: String? = null,
        resultJson: String? = null,
    ) {
        applyAckChecked(intentId, status, errorCode, message, resultJson)
    }

    /** El mismo adjudicador para el caller público y el replay; true sólo si Room avanzó. */
    private suspend fun applyAckChecked(
        intentId: String, status: String, errorCode: String?, message: String?, resultJson: String?,
        vigia: VigiaDelIntent? = null,
    ): Boolean = when (status) {
        SyncIntentEntity.STATUS_ACKED, SyncIntentEntity.STATUS_REJECTED -> {
            val persisted = persistTerminalAck(intentId, status, errorCode, message, resultJson, vigia)
            if (persisted && status == SyncIntentEntity.STATUS_REJECTED) {
                Timber.w("🚫 [Tables] Intent %s RECHAZADO: %s — %s", intentId, errorCode, message)
            }
            persisted
        }
        STATUS_RETRY -> {
            Timber.d("🔁 [Tables] Intent %s en RETRY (%s) — sigue PENDING, se reintenta solo", intentId, errorCode)
            false
        }
        else -> {
            Timber.w("⚠️ [Tables] Ack con status desconocido '%s' para intent %s — ignorado, se queda PENDING", status, intentId)
            false
        }
    }

    /**
     * Escritura del ack terminal, aislada para poder blindarla igual que
     * `PaymentQueueRepositoryImpl.markSynced`: `CancellationException` se
     * repropaga SIN tocar la fila (un worker/caller cancelado no debe dejar un
     * ack a medio escribir); cualquier otra excepción se traga y se loguea — la
     * fila se queda PENDING, y como el `id` es el idempotencyKey, el próximo
     * replay vuelve a mandar el MISMO intent: el server lo dedupea y regresa el
     * mismo ack (ver dedup en `processIntents`), así que reintentar aquí es
     * seguro, nunca duplica el efecto.
     */
    private suspend fun persistTerminalAck(
        intentId: String, status: String, errorCode: String?, message: String?, resultJson: String?,
        vigia: VigiaDelIntent? = null,
    ): Boolean = try {
        dao.resolve(intentId, status, errorCode, message, resultJson) == 1
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] No se pudo persistir el ack de %s — queda PENDING, se reintenta", intentId)
        // D19: sólo una excepción cuenta como «la base no dejó guardar»; el `0` de una fila ya resuelta o descartada no.
        vigia?.escrituraFallida = true
        false
    }

    /**
     * Reproduce el outbox de [venueId] contra el reducer: batch FIFO por `seq`,
     * `BATCH_SIZE` a la vez.
     *
     * Un fallo de RED o un HTTP no-exitoso deja el batch INTACTO (todo sigue
     * PENDING) y retorna sin lanzar — "red caída ⇒ outbox, nunca un error"
     * también aplica al lado del replay: quien haya disparado esta llamada
     * (Task 4/5) no debe tratar esto como una falla, solo como "todavía no".
     *
     * Al primer ack `RETRY` la reproducción se CORTA: el server ya dejó de
     * procesar el resto del batch ahí mismo (ver `processIntents` — corta FIFO
     * porque los intents posteriores dependen del que quedó en RETRY), así que
     * `response.data` nunca trae acks después de uno RETRY. Cortar aquí evita
     * además re-consultar el mismo PENDING en un hot-loop.
     *
     * `CancellationException` se repropaga sin tocar nada — el batch se queda
     * como estaba, listo para el próximo trigger.
     *
     * D6 (14-nov): tras reinstalar la app (o borrar sus datos, o regresar a una versión anterior, que borra la base), la
     * cola vuelve a contar desde 1 y el server rechaza por número (`STALE_DEVICE_SEQUENCE`, con `details.latestSeq`,
     * antes de aplicar nada). Si `latestSeq` es MAYOR que cualquier número de esta base, ese número es de otra base (la
     * instalación vieja): todo lo que esta base mandó se rechazó sin aplicarse ni apartarse. Sólo entonces, y sólo si
     * el envío llevó toda la cola en espera y nada de esta respuesta entró antes del rechazo, la cola en espera se
     * renumera arriba de `latestSeq`, en el mismo orden, y se reenvía en esta misma pasada (a lo más una vez por
     * pasada). Si algo entra después de un rechazo así, el rechazo se guarda primero (Codex r3). Si no (algo de esta base pudo entrar:
     * Codex r1, `docs/audit/2026-11-14-secuencia-tras-reinstalar/`), el rechazo queda como antes: reenviarlo ejecutaría
     * una operación vieja después de una nueva, o rompería la recuperación de una reserva (el server la retoma sólo con
     * su número original).
     *
     * @param onAck invocado por cada ack YA persistido (Task 5,
     *   `TableSyncCoordinator`) — recibe el [SyncIntentEntity] original (para
     *   su `type`) y el [SyncIntentAck] del wire. Se llama en el MISMO orden
     *   FIFO en que el server los regresó, y NUNCA para un intent posterior a
     *   un `RETRY` en la misma respuesta (el server ya no lo tocó). No
     *   participa en la clasificación ACKED/REJECTED/RETRY — eso lo decide
     *   [applyAck] arriba; este callback es solo notificación.
     */
    suspend fun replayNow(venueId: String, onAck: suspend (SyncIntentEntity, SyncIntentAck) -> Unit = { _, _ -> }) {
        replay(venueId, singleBatch = false, onAck = onAck, canEmit = { true })
    }

    /** D19: qué contestó el SERVIDOR de UN intent (ver [reproducirYResolver]). */
    sealed interface DesenlaceDelIntent {
        data class Aceptado(val result: JsonObject?) : DesenlaceDelIntent
        data class Rechazado(val errorCode: String?, val message: String?) : DesenlaceDelIntent
        data object EnEspera : DesenlaceDelIntent
        data class NoSeSabe(val motivo: String) : DesenlaceDelIntent
    }

    /**
     * D19 (`na78d478`, PAX 18-nov): reproduce la cola como [replayNow] y, ANTES de soltar el candado, dice qué contestó el
     * SERVIDOR de [intentId]. Antes, quien encolaba un cobro sólo veía el acuse que le llegaba a SU callback: si otro
     * reproductor (el plano por un aviso de socket, la mesa, el trabajador) mandó el intent mientras éste esperaba el candado,
     * su lote ya no lo traía y contestaba «guardado» aunque el SERVIDOR lo hubiera rechazado («Cobro guardado · Cobrado
     * $203» con 0 pagos en la base).
     *
     * Orden de la decisión, todo bajo [replayMutex] (ningún otro reproductor puede escribir la fila en medio):
     * 1. el acuse ACKED/REJECTED de ESE intent en la respuesta de esta pasada, se haya podido guardar o no (un rechazo por
     *    número que D6 renumera y reenvía no cuenta: sólo el que queda guardado como rechazo); RETRY nunca cuenta;
     * 2. si no, su fila: PENDING ⇒ [DesenlaceDelIntent.EnEspera]; ACKED ⇒ aceptado con su `resultJson`; REJECTED o
     *    DISMISSED ⇒ rechazado;
     * 3. [DesenlaceDelIntent.NoSeSabe] si la fila no está o está en otro estado, si no se puede leer, o si sigue PENDING
     *    pero en esta pasada la base no dejó guardar un acuse (o falló leer la cola): entonces la cola no es de fiar.
     *
     * Nunca lanza salvo cancelación. Residuo declarado (`docs/audit/2026-11-18-d19-efectivo-mesas-rechazado/promesa.md`):
     * OTRO reproductor recibió la respuesta y la base no lo dejó guardarla, y además esta pasada perdió la suya.
     */
    suspend fun reproducirYResolver(venueId: String, intentId: String): DesenlaceDelIntent = replayMutex.withLock {
        val vigia = VigiaDelIntent(intentId)
        val replayFallo = try {
            replayBajoCandado(venueId, singleBatch = false, onAck = { _, _ -> }, canEmit = { true }, vigia = vigia)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "❌ [Tables] Falló reproducir la cola antes de decidir %s (venue=%s)", intentId, venueId)
            true
        }
        decidir(venueId, intentId, vigia, replayFallo)
    }

    /** D19: lo que una pasada vio de UN intent. Vive sólo dentro del candado de [reproducirYResolver]. */
    private class VigiaDelIntent(val intentId: String) {
        /** El acuse ACKED/REJECTED definitivo de [intentId] que contestó el SERVIDOR en esta pasada. */
        var acuseFinal: SyncIntentAck? = null

        /** En esta pasada la base lanzó al guardar un acuse (no cuenta el `0` de una fila ya resuelta o descartada). */
        var escrituraFallida = false

        /** Lo que trae la respuesta completa, antes de guardar nada: un rechazo por número todavía no es definitivo (D6). */
        fun alRecibir(acuses: List<SyncIntentAck>) {
            acuses.lastOrNull { it.id == intentId && esDefinitivo(it) && it.errorCode != SyncIntentAck.STALE_DEVICE_SEQUENCE }
                ?.let { acuseFinal = it }
        }

        /** Un acuse que esta pasada guarda como definitivo (también el rechazo por número que D6 ya no reenvía). */
        fun alGuardar(ack: SyncIntentAck) {
            if (ack.id == intentId && esDefinitivo(ack)) acuseFinal = ack
        }

        private fun esDefinitivo(ack: SyncIntentAck) = ack.isAcked || ack.isRejected
    }

    private suspend fun decidir(venueId: String, intentId: String, vigia: VigiaDelIntent, replayFallo: Boolean): DesenlaceDelIntent {
        vigia.acuseFinal?.let { ack ->
            return if (ack.isAcked) DesenlaceDelIntent.Aceptado(ack.result) else DesenlaceDelIntent.Rechazado(ack.errorCode, ack.message)
        }
        val fila = try {
            dao.leer(venueId, intentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.e(error, "❌ [Tables] No se pudo leer en la cola %s (venue=%s)", intentId, venueId)
            return DesenlaceDelIntent.NoSeSabe("No se pudo leer la cola de esta terminal")
        }
        return when (fila?.status) {
            SyncIntentEntity.STATUS_PENDING ->
                if (vigia.escrituraFallida || replayFallo) DesenlaceDelIntent.NoSeSabe("La cola de esta terminal no se pudo actualizar")
                else DesenlaceDelIntent.EnEspera
            SyncIntentEntity.STATUS_ACKED -> DesenlaceDelIntent.Aceptado(
                fila.resultJson?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() },
            )
            SyncIntentEntity.STATUS_REJECTED, SyncIntentEntity.STATUS_DISMISSED -> DesenlaceDelIntent.Rechazado(fila.errorCode, fila.message)
            else -> {
                Timber.e("❌ [Tables] El intent %s no está en espera ni resuelto (%s) — no se sabe", intentId, fila?.status)
                DesenlaceDelIntent.NoSeSabe("El cobro no está en la cola de esta terminal")
            }
        }
    }

    /** Un lote del MISMO replayer para recuperación fuera de Mesas. No crea otro emisor. */
    data class ReplayProgress(val resolved: Int, val moreAfterLimit: Boolean)

    suspend fun replayCurrentVenueBatch(
        onAck: suspend (SyncIntentEntity, SyncIntentAck) -> Unit = { _, _ -> },
    ): ReplayProgress {
        val venue = secureStorage.getVenueId()?.takeIf { it.isNotBlank() } ?: return ReplayProgress(0, false)
        val staff = secureStorage.getStaffId()?.takeIf { it.isNotBlank() } ?: return ReplayProgress(0, false)
        val token = secureStorage.getToken()?.takeIf { it.isNotBlank() } ?: return ReplayProgress(0, false)
        return replay(venue, singleBatch = true, onAck = onAck, canEmit = {
            secureStorage.getVenueId() == venue && secureStorage.getStaffId() == staff && secureStorage.getToken() == token
        })
    }

    private suspend fun replay(
        venueId: String,
        singleBatch: Boolean,
        onAck: suspend (SyncIntentEntity, SyncIntentAck) -> Unit,
        canEmit: () -> Boolean,
    ): ReplayProgress = replayMutex.withLock { replayBajoCandado(venueId, singleBatch, onAck, canEmit, vigia = null) }

    /** D19: cómo sigue la pasada después de guardar una respuesta (ver [replayBajoCandado]). */
    private sealed interface Paso {
        data object Termina : Paso
        data object OtraVuelta : Paso
        data class Sigue(val next: Int) : Paso
    }

    /** El cuerpo de [replay]; quien llama ya tiene [replayMutex]. [vigia] (D19) sólo mira: no cambia nada de la pasada. */
    private suspend fun replayBajoCandado(
        venueId: String,
        singleBatch: Boolean,
        onAck: suspend (SyncIntentEntity, SyncIntentAck) -> Unit,
        canEmit: () -> Boolean,
        vigia: VigiaDelIntent?,
    ): ReplayProgress {
        var resolved = 0
        // D6: la cola se renumera por un rechazo de número a lo más UNA vez por pasada. Si el servidor vuelve a rechazar
        // por número (otra instalación con el mismo serial sigue adelante), se queda rechazado como antes: sin hot-loop.
        var yaRenumerada = false
        var otraVuelta: Boolean
        do {
            otraVuelta = !singleBatch
            if (!canEmit()) return ReplayProgress(resolved, false)
            val batch = dao.pendingBatchForVenue(venueId, BATCH_SIZE)
            if (batch.isEmpty()) return ReplayProgress(resolved, false)
            val ready = mutableListOf<SyncIntentEntity>()
            var waitingForMapping = false
            for (intent in batch) {
                try {
                    ready += if (intent.type == SyncIntentTypes.PAY_CASH && intent.shiftMembership == "MANAGED")
                        (shiftReplayGate ?: throw com.jaac.avoqado_tpv.features.shift.data.repository.ShiftMappingPendingException()).table(intent)
                    else intent
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (waiting: com.jaac.avoqado_tpv.features.shift.data.repository.ShiftMappingPendingException) {
                    waitingForMapping = true
                    break
                }
            }
            if (ready.isEmpty() || !canEmit()) return ReplayProgress(resolved, false)
            val request = SyncIntentsRequest(deviceId = deviceId, intents = ready.map { it.toWire() })
            val response = try {
                val http = api.syncIntents(venueId, request)
                if (!http.isSuccessful) {
                    Timber.w("⚠️ [Tables] Replay interrumpido — HTTP %d (venue=%s)", http.code(), venueId)
                    return ReplayProgress(resolved, false)
                }
                http.body()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                Timber.w(error, "⚠️ [Tables] Replay interrumpido — conserva el prefijo (venue=%s)", venueId)
                return ReplayProgress(resolved, false)
            } ?: return ReplayProgress(resolved, false)

            // D19: el acuse del intent que se vigila se toma de la respuesta COMPLETA, aunque una fila anterior no se pueda
            // guardar y el bucle de abajo salga antes de llegar a él.
            vigia?.alRecibir(response.data)
            // D19 (Codex r3 #1): una respuesta que YA llegó se guarda completa aunque cancelen a quien la pidió (WorkManager
            // detiene al trabajador cuando la red parpadea). Antes, el rechazo de un cobro que venía detrás quedaba sin escribir
            // y «Cobrar», ya sin red, leía la fila en espera y decía «guardado». La cancelación se nota al terminar de guardarla.
            val paso = withContext(NonCancellable) procesar@{
                var next = 0
                // D6: rechazos por número que se renumeran en vez de guardarse, y si esta respuesta los deja renumerar.
                val porNumero = mutableListOf<Pair<Int, SyncIntentAck>>()
                var piso: Long? = null
                var renumerable: Boolean? = null
                var entraronEnEstaRespuesta = 0
                // D6 (Codex r3): sólo si este envío llevó TODA la cola en espera (el lote no se llenó ni se cortó esperando el
                // mapa de la caja): así, si algo de la cola ya estaba en el servidor, su respuesta viene en ESTA misma respuesta.
                val todaLaCola = !waitingForMapping && batch.size < BATCH_SIZE
                for (ack in response.data) {
                    // Sólo el siguiente id solicitado: no aplicar ACK ajeno, duplicado o fuera de FIFO.
                    val intent = ready.getOrNull(next)
                    if (intent == null || ack.id != intent.id) return@procesar Paso.Termina
                    val ultimoDelServidor = if (yaRenumerada) null else ack.latestSeqDelRechazoDeNumero(intent.seq)
                    if (ultimoDelServidor != null) {
                        // D6: sólo si el servidor vio un número MAYOR que todos los de esta base (es de la instalación vieja)
                        // y nada de esta respuesta entró antes: entonces nada de esta base se aplicó ni se apartó.
                        if (renumerable == null) {
                            renumerable = todaLaCola && entraronEnEstaRespuesta == 0 &&
                                ultimoDelServidor > (dao.maxSeq(venueId) ?: 0L)
                        }
                        if (renumerable == true) {
                            // Rechaza por número ANTES de reservar: no aplicó nada. Se queda en espera sin avisar a nadie, y
                            // abajo se renumera y se reenvía.
                            porNumero += next to ack
                            piso = maxOf(piso ?: ultimoDelServidor, ultimoDelServidor)
                            next++
                            continue
                        }
                    }
                    if (porNumero.isNotEmpty()) {
                        // Algo de esta base entra DESPUÉS de un rechazo por número (sólo con otra instalación del mismo serial
                        // a la vez): reenviar lo rechazado lo ejecutaría tarde. Se guarda como el rechazo que es, y ANTES que
                        // lo que entra, en orden: si el proceso muere en medio, nunca queda lo nuevo aplicado y lo viejo en
                        // espera (Codex r3).
                        for ((indice, rechazo) in porNumero) {
                            val guardado = applyAckChecked(rechazo.id, rechazo.status, rechazo.errorCode, rechazo.message, null, vigia)
                            vigia?.alGuardar(rechazo)
                            if (!guardado) return@procesar Paso.Termina
                            resolved++
                            onAck(batch[indice], rechazo)
                        }
                        porNumero.clear()
                        piso = null
                        renumerable = false
                    }
                    val persisted = applyAckChecked(ack.id, ack.status, ack.errorCode, ack.message, ack.result?.let { gson.toJson(it) }, vigia)
                    vigia?.alGuardar(ack)
                    if (ack.isRetry) {
                        onAck(batch[next], ack)
                        return@procesar Paso.Termina
                    }
                    if (!persisted) return@procesar Paso.Termina
                    resolved++
                    entraronEnEstaRespuesta++
                    onAck(batch[next], ack) // La transición CAS ya fue duradera; entrega la entidad original.
                    next++
                }
                if (piso != null && porNumero.isNotEmpty()) {
                    // Si la base no deja renumerar, todo sigue en espera con su número viejo: la próxima pasada lo vuelve a
                    // intentar. `replay` nunca lanza salvo cancelación (ver KDoc de [replayNow]).
                    val renumeradas = try {
                        dao.renumerarPendientesSobre(venueId, piso!!)
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (error: Exception) {
                        Timber.e(error, "❌ [Tables] No se pudo renumerar la cola sobre seq=%d — queda en espera (venue=%s)", piso, venueId)
                        return@procesar Paso.Termina
                    }
                    yaRenumerada = true
                    Timber.w(
                        "🔢 [Tables] El servidor ya vio hasta seq=%d de este aparato, más que toda esta base (¿se reinstaló?): %d en espera renumeradas arriba, se reenvían (venue=%s)",
                        piso, renumeradas, venueId,
                    )
                    return@procesar Paso.OtraVuelta
                }
                Paso.Sigue(next)
            }
            when (paso) {
                Paso.Termina -> return ReplayProgress(resolved, false)
                Paso.OtraVuelta -> {
                    otraVuelta = true
                    continue
                }
                is Paso.Sigue -> {
                    // Sin ACK de todo el prefijo no volver a consultar el mismo PENDING en un hot-loop.
                    if (paso.next != ready.size || waitingForMapping) return ReplayProgress(resolved, false)
                }
            }
            if (singleBatch) return ReplayProgress(resolved,
                batch.size == BATCH_SIZE && canEmit() && dao.hasPendingForVenue(venueId))
        } while (otraVuelta)
        return ReplayProgress(resolved, false)
    }

    // MARK: - Cuarentena

    /** Un intent rechazado, ya legible para la pantalla de resolución (Task 9). */
    data class RejectedIntent(
        val id: String,
        val type: String,
        val errorCode: String?,
        val message: String?,
        val createdAt: Long,
    )

    suspend fun rejectedIntents(venueId: String): List<RejectedIntent> =
        dao.rejectedForVenue(venueId).map {
            RejectedIntent(id = it.id, type = it.type, errorCode = it.errorCode, message = it.message, createdAt = it.createdAt)
        }

    /**
     * D86: las filas PENDING o REJECTED de [venueId] que mencionan [cuenta] en su payload (filtro grueso; quien llama revisa
     * el JSON). Para calcular sin red el saldo de una cuenta que esta terminal abrió sin red.
     */
    suspend fun vivasQueMencionan(venueId: String, cuenta: String): List<SyncIntentEntity> =
        withContext(Dispatchers.IO) { dao.vivasQueMencionan(venueId, cuenta) }

    /** D86: las aperturas ya confirmadas que mencionan [cuenta] (para promover una sesión cuyo ACK llegó por otro camino). */
    suspend fun aperturasConfirmadasQueMencionan(venueId: String, cuenta: String): List<SyncIntentEntity> =
        withContext(Dispatchers.IO) { dao.aperturasConfirmadasQueMencionan(venueId, cuenta) }

    /** D86 (Codex r7): un aviso por cada escritura de la cola de [venueId] (ver [SyncIntentDao.observarCambios]). */
    fun observarCambios(venueId: String): kotlinx.coroutines.flow.Flow<Int> = dao.observarCambios(venueId)

    /** D1 (Codex D88-D90 r7): ver [SyncIntentDao.ultimaSecuencia]. `0` con la cola vacía. */
    suspend fun ultimaSecuencia(venueId: String): Long = dao.ultimaSecuencia(venueId) ?: 0L

    /** D86 (Codex r1 #2): las aperturas confirmadas cuyo resultado menciona [orderId] (el id real). */
    suspend fun aperturasConfirmadasConResultado(venueId: String, orderId: String): List<SyncIntentEntity> =
        withContext(Dispatchers.IO) { dao.aperturasConfirmadasConResultado(venueId, orderId) }

    /** D16: las rondas confirmadas de [venueId] que el SERVIDOR puso en una cuenta aparte y mencionan [orderId] (filtro grueso). */
    suspend fun confirmadasConCuentaAparte(venueId: String, orderId: String): List<SyncIntentEntity> =
        withContext(Dispatchers.IO) { dao.confirmadasConCuentaAparte(venueId, orderId) }

    /** El mesero/gerente descartó un rechazo tras resolverlo a mano. */
    suspend fun dismissRejected(venueId: String, id: String) {
        dao.dismiss(id, venueId)
    }

    private fun SyncIntentEntity.toWire(): SyncIntentInput = SyncIntentInput(
        id = id,
        seq = seq,
        type = type,
        payload = gson.fromJson(payloadJson, JsonObject::class.java),
        createdAtLocal = createdAt,
    )

    private companion object {
        const val BATCH_SIZE = 50

        /**
         * NO vive en [SyncIntentEntity] porque `RETRY` nunca se persiste — no es
         * un valor válido de la columna `status`, solo un status transitorio del
         * wire (`SyncIntentAck.status`).
         */
        const val STATUS_RETRY = "RETRY"
    }
}
