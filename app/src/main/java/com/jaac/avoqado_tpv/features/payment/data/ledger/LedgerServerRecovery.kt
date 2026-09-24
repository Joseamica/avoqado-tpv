package com.jaac.avoqado_tpv.features.payment.data.ledger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checkpoint 2 · N3 (diseño v3, E2/E3): la recuperación por SERVIDOR. Barato y de Avoqado, antes que el procesador.
 *
 * Paso 0 (sin red): reaplica los veredictos FINALES ya guardados cuyo estado local cambió después (E2).
 * Paso 1 (con red): consulta S6 por las candidatas —sin veredicto o con veredicto no final— con avance y espaciado por
 * intento (E3), y aplica lo que conteste ([PaymentAttemptDao.aplicarVeredictoDelServidor]); `NOT_RECORDED`, 404 (intento
 * desconocido o ruta vieja), 401/403/5xx sólo estampan el turno de la fila; sin respuesta HTTP no se gasta el turno.
 *
 * Nunca autoriza nada, nunca discards por ausencia, nunca toca filas heredadas. Idempotente y sin lease propio: S6 es de
 * sólo lectura y toda transición es CAS. El resultado de la bandeja (un `success` durable nuevo) se devuelve para que
 * quien tenga socket lo EMITA después del commit.
 */
@Singleton
class LedgerServerRecovery @Inject constructor(
    private val dao: PaymentAttemptDao,
    private val ledger: PaymentAttemptLedger,
    private val api: TerminalAttemptApiService,
) {
    data class Resultado(
        val reaplicados: Int,
        val consultados: Int,
        val aplicados: Int,
        /** JSON de resultados de bandeja que quedaron RESOLVED `success` en esta pasada: hay que emitirlos al servidor. */
        val bandejasResueltas: List<String>,
        /** Codex (código, P2-1): consultas SIN respuesta HTTP (red, timeout) en esta pasada — el worker reintenta por ellas. */
        val sinRespuesta: Int = 0,
        /** Task 6: filas INDETERMINADO cerradas como DESCARTADA por la LIBERACIÓN del servidor (S6 `request.outcome = NOT_CHARGED`). */
        val liberadas: Int = 0,
        /** Fix 4: filas con evidencia POSITIVA del servidor sin Payment (banco APPROVED) marcadas de forma DURABLE en esta pasada. */
        val evidenciasMarcadas: Int = 0,
        /** Ronda 21: cuánto falta para que una duda local cumpla su espera del aviso del banco (null si ninguna espera). */
        val proximaLiberacionSolaEnMs: Long? = null,
    )

    suspend fun recover(venueId: String, now: Long = System.currentTimeMillis()): Resultado {
        var reaplicados = 0; var consultados = 0; var aplicados = 0; var sinRespuesta = 0; var liberadas = 0; var evidenciasMarcadas = 0; var vetosMarcados = 0
        val bandejas = mutableListOf<String>()
        // 🔴 Ronda 20: lo que dejó un proceso muerto, «en duda» al instante (la libreta sabe qué es de ESTE proceso).
        ledger.cuarentenaDeHuerfanos(now)
        // 🔴 Ronda 24 (Codex r22, P1-2): la evidencia del servidor que no se pudo escribir se reintenta primero; si sigue sin
        // escribirse, la pasada pide reintento (el worker vuelve).
        sinRespuesta += ledger.reintentarEvidenciaSinGuardar(now)
        // Paso 0 · E2: lo ya sabido se aplica sin gastar red.
        for (fila in runCatching { dao.veredictosPendientesDeAplicar(venueId) }.getOrDefault(emptyList())) {
            val r = ledger.reaplicarVeredictoGuardado(fila.attemptId, now) ?: continue
            if (r.transiciono) reaplicados++
            r.bandejaResueltaJson?.let(bandejas::add)
        }
        // Paso 1 · E3: consultar S6 con avance y espaciado.
        // 🔴 Codex r13 (P2-4): una fila que CONTESTÓ pero no se pudo guardar no avanza su turno (a propósito: tiene que volver
        // pronto). Si una página ENTERA se queda así, la pasada siguiente traería las mismas y la que sigue no se consultaría
        // nunca: se pide la página siguiente excluyendo lo ya procesado. Sólo si hubo atasco de ESCRITURA — sin red, paginar no
        // avanza nada y martillaría al servidor — y con tope de páginas por pasada.
        // 🔴 Codex r14 (P2-3) / r15 (P2): cada pasada da turno a lo sumo a UNA página de atoradas conocidas, en rueda por TODO el
        // conjunto, y excluye a las demás: lo que no está atorado siempre cabe, y cada atorada vuelve a tener turno. (Alternar dos
        // grupos dejaba sin turno para siempre a la fila 201.)
        // 🔴 Codex r16 (P2): las excluidas se SALTAN en memoria y la consulta pagina con cursor — su número de variables ya no
        // crece con la rueda (1.000 atoradas pasaban del tope de 999 de SQLite y la sana no volvía a tener turno). El tope de la
        // pasada es de filas ATENDIDAS, no de páginas: con el turno repartido entre páginas de excluidas, cuatro páginas podían
        // gastarse en una atorada cada una. ponytail: saltar la rueda cuesta leerla entera en cada pasada — es O(atoradas), y
        // una atorada es una fila cuya escritura falla; si algún día son miles, persistir un cursor por venue.
        val (turno, excluidas) = turnoDeAtoradas()
        val saltar = excluidas.toHashSet()
        val atoradasEnEstaPasada = mutableSetOf<String>()
        val consultadas = mutableSetOf<String>()
        val sinRed = mutableSetOf<String>()
        var seAgotaron = false
        var atendidas = 0
        var atascadas = 0
        var tras = Triple(Long.MIN_VALUE, Long.MIN_VALUE, "")
        // 🔴 Codex r17 (P3): cada lectura vuelve a ordenar las candidatas del venue (no hay índice por la clave del cursor). Con
        // la rueda llena, páginas de 25 eran ~N/25 ordenamientos por pasada: se lee de una vez lo que hay que saltar, con tope.
        // ponytail: sigue siendo ~N/500 lecturas; si la rueda pasa de unos miles, un índice de expresión por venue.
        val limite = CANDIDATAS_POR_PAGINA + minOf(saltar.size, SALTO_MAXIMO_POR_LECTURA)
        lectura@ while (true) {
            val leidas = try {
                dao.candidatasDeConsultaAlServidor(
                    venueId, now - VIVOS_MS, now, ESPACIADO_BASE_MS, ESPACIADO_TOPE_MS, tras.first, tras.second, tras.third, limite,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 🔴 Codex r16 (P2): un fallo de LECTURA no es «ya no hay más»: nada sale de la rueda y el worker reintenta.
                sinRespuesta++
                Timber.w(error, "🔎 [LedgerServer] no se pudieron leer las candidatas")
                break
            }
            leidas.lastOrNull()?.let { tras = Triple(it.serverCheckedAt ?: -1L, it.createdAt, it.attemptId) }
            for (fila in leidas) {
                if (fila.attemptId in saltar || fila.attemptId in consultadas) continue
                // 🔴 Codex r17 (P2): el tope se revisa ANTES de cada fila, no al terminar la página — revisado entre páginas,
                // una atorada + 24 sin red dejaban pasar otras 25 sin red (49), y una excluida desalineaba las páginas hasta 124.
                // Alcanzarlo NO es haber agotado las candidatas: no marca `seAgotaron`, así que nadie sale de la rueda por esto.
                if (atendidas >= MAX_ATENDIDAS_POR_PASADA || atendidas - atascadas >= CANDIDATAS_POR_PAGINA) break@lectura
                consultadas += fila.attemptId
                atendidas++
                var contesto = false   // Codex r14 (P2-4): distingue «sin red» de «contestó y falló una escritura»
                try {
                    // Codex (código, P2-1, ronda 2): el tope PROPIO de la consulta se expresa con `withTimeoutOrNull` — devuelve null
                    // en vez de lanzar una CancellationException indistinguible de la de un padre (worker cancelado, timeout externo),
                    // que SÍ tiene que propagarse. Sin respuesta = «no se gasta el turno», la pasada sigue con la siguiente candidata.
                    val respuesta = kotlinx.coroutines.withTimeoutOrNull(CONSULTA_TIMEOUT_MS) { api.getAttemptStatus(venueId, fila.attemptId) }
                    if (respuesta == null) {
                        sinRespuesta++; sinRed += fila.attemptId
                        Timber.w("🔎 [LedgerServer] consulta sin respuesta (tope de %d ms) para %s", CONSULTA_TIMEOUT_MS, fila.attemptId)
                        continue
                    }
                    consultados++
                    contesto = true
                    // 🔴 Codex r9 (P1-3): lo que trae la respuesta se decide por el CUERPO y se GUARDA antes de anotar que el servidor
                    // CONTESTÓ (Codex r8, P2-4). Esa marca es la que deja podar una declaración sin red: anotada primero, el barrido
                    // podía borrar la fila entre la marca y el veto —o la aprobación— que la MISMA respuesta traía. Un 2xx recibido
                    // no acredita que su evidencia haya quedado guardada.
                    val cuerpo = if (respuesta.isSuccessful) respuesta.body() else null
                    if (!respuesta.isSuccessful) {
                        // 404 (intento desconocido, otra terminal, ruta vieja), 401/403, 5xx: nada acredita nada. Sólo el turno.
                        Timber.d("🔎 [LedgerServer] %s ⇒ HTTP %d: la fila se conserva", fila.attemptId, respuesta.code())
                    }
                    val veredicto = cuerpo?.let { VeredictoDeIntento.desdeConsultaS6(venueId, fila.attemptId, it) }
                    if (veredicto == null) {
                        var guardado = true
                        // 🔴 Codex r6 (P1-2): el VETO se hace durable AQUÍ TAMBIÉN. `recover()` no pasa por `recoverOne()` —
                        // tiene su propio procesamiento— así que el arreglo r5-4 sólo cubría la consulta interactiva: el
                        // worker recibía `paymentContradiction`, conservaba la fila y dejaba `server_veto` en NULL, y la
                        // respuesta LIMPIA y ATRASADA de otro de los tres consumidores liberaba la venta. Sin reiniciar nada.
                        // Va ANTES de cualquier liberación, igual que en `recoverOne`.
                        // Fix 4 (D2): evidencia POSITIVA del intento sin veredicto aplicable (banco APPROVED sin Payment, outcome con dinero
                        // sin `paymentId`): se hace DURABLE en la fila ANTES de decidir nada — el CAS de liberación y la cancelación la leen.
                        // 🔴 Codex r13 (P1): el veto y la evidencia de ESTA respuesta van en UN tramo no cancelable ([guardarLoDelCuerpo]).
                        val (vetoEscrito, evidenciaEscrita) = guardarLoDelCuerpo(venueId, fila.attemptId, cuerpo, now)
                        if (vetoEscrito?.getOrDefault(false) == true) vetosMarcados++
                        if (evidenciaEscrita?.getOrDefault(false) == true) evidenciasMarcadas++
                        if (vetoEscrito?.isFailure == true || evidenciaEscrita?.isFailure == true) guardado = false
                        if (!guardado) {
                            // 🔴 Codex r12 (P2-2): el veto o la evidencia que trajo el cuerpo NO quedó durable. Gastar el turno lo espaciaba
                            // hasta ~21 h con la liberación vieja intacta: se pide reintento y el turno se conserva. (Una respuesta con veto
                            // o con dinero nunca trae liberación: no hay nada más que hacer en esta pasada.)
                            sinRespuesta++; atascadas++; atoradasEnEstaPasada += fila.attemptId
                            continue
                        }
                        if (cuerpo != null) dao.estamparRespuestaDelServidor(fila.attemptId, now)
                        // Task 6: sin veredicto sobre el INTENTO, la SOLICITUD puede venir liberada por el servidor (ventana / cajero).
                        val liberacion = cuerpo?.let { LiberacionDelServidor.desdeConsultaS6(venueId, fila.attemptId, it) }
                        if (liberacion != null && ledger.aplicarLiberacionDelServidor(liberacion, now).getOrDefault(false)) { liberadas++; continue }
                        dao.estamparConsultaAlServidor(fila.attemptId, now)
                        continue
                    }
                    // 🔴 Codex r12 (P2-1): aplicar y, si no quedó, marcar — en UN tramo no cancelable. Separados, detener el worker
                    // mientras Room trabajaba lanzaba en el regreso de «aplicar» y la marca nunca se escribía.
                    val (r, marca) = aplicarConRespaldo(veredicto, venueId, fila.attemptId, now)
                    if (marca.getOrDefault(false)) evidenciasMarcadas++
                    if (r == null) {
                        // 🔴 Codex r11 (P2-4) / r12 (P2-2) / r15: la marca que aparta el aparato FALLÓ (y entonces el veredicto ni se
                        // intentó), o falló la transacción del veredicto. No es «sin dinero»: sin respuesta, para volver a intentarlo — ni «contestó» ni el turno.
                        sinRespuesta++; atascadas++; atoradasEnEstaPasada += fila.attemptId
                        continue
                    }
                    if (!r.quedoEnLaFila) {
                        // 🔴 Codex r10 (P1-2): la libreta RECHAZÓ el veredicto sin escribirlo (otra solicitud, otro venue, la fila ya no
                        // está). La marca SÍ quedó (sin atribuir el Payment): no se anota «contestó» y sólo se gasta el turno (la
                        // anomalía no se consulta en cada pasada).
                        dao.estamparConsultaAlServidor(fila.attemptId, now)
                        continue
                    }
                    dao.estamparRespuestaDelServidor(fila.attemptId, now)   // Codex r9 (P1-3): contestó, y su veredicto YA está guardado
                    if (r.transiciono) aplicados++
                    r.bandejaResueltaJson?.let(bandejas::add)
                } catch (cancelled: CancellationException) {
                    throw cancelled // cancelación EXTERNA (el worker, un timeout del padre): se propaga, nunca se cuenta como «sin respuesta»
                } catch (error: Exception) {
                    // Sin respuesta HTTP (red): no se gasta el turno de la fila; el worker con CONNECTED reintenta por `sinRespuesta`.
                    // Codex r14 (P2-4): si SÍ contestó y lo que falló fue una escritura (una estampa), es atasco: habilita paginar.
                    sinRespuesta++
                    if (contesto) { atascadas++; atoradasEnEstaPasada += fila.attemptId } else sinRed += fila.attemptId
                    Timber.w(error, "🔎 [LedgerServer] consulta sin respuesta para %s", fila.attemptId)
                }
            }
            if (leidas.size < limite) { seAgotaron = true; break }
        }
        // 🔴 Ronda 20: las dudas LOCALES se liberan SOLAS si el servidor lo acepta. Va aparte del espaciado de S6 a propósito: la
        // espera del aviso del banco se mide en segundos, no en el turno de 10 minutos.
        val solas = liberarDudasLocales(venueId, now)
        val liberadasSolas = solas.liberadas
        sinRespuesta += solas.sinRespuesta
        liberadas += liberadasSolas
        synchronized(atoradas) {
            // Salen de la rueda las de su turno que AVANZARON (se consultaron y ni se atoraron ni se quedaron sin red) y las que ya no
            // son candidatas (la consulta se agotó sin traerlas). Entran las que se atoraron en esta pasada.
            for (id in turno) {
                val avanzo = id in consultadas && id !in atoradasEnEstaPasada && id !in sinRed
                if (avanzo || (seAgotaron && id !in consultadas)) atoradas.remove(id)
            }
            atoradas += atoradasEnEstaPasada
        }
        if (reaplicados + consultados + aplicados + sinRespuesta > 0) {
            Timber.i("🔎 [LedgerServer] pasada | reaplicados=%d consultados=%d aplicados=%d liberadas=%d evidencias=%d vetos=%d sinRespuesta=%d bandejas=%d",
                reaplicados, consultados, aplicados, liberadas, evidenciasMarcadas, vetosMarcados, sinRespuesta, bandejas.size)
        }
        return Resultado(reaplicados, consultados, aplicados, bandejas, sinRespuesta, liberadas, evidenciasMarcadas, solas.proximaEnMs)
    }

    /** Ronda 21: lo que dejó una pasada de [liberarDudasLocales]. */
    data class Solas(
        val liberadas: Int = 0,
        val sinRespuesta: Int = 0,
        /** Cuánto falta (reloj monotónico) para que la siguiente duda cumpla su espera; null si no queda ninguna esperando. */
        val proximaEnMs: Long? = null,
    )

    /**
     * 🔴 Ronda 20/21: las dudas LOCALES (Pago rápido) se liberan SOLAS si el servidor lo acepta. Aparte de [recover] para que el
     * arranque la corra EN EL PROCESO, sin esperar la cadena de WorkManager (Codex r19, P2-2).
     */
    suspend fun liberarDudasLocales(venueId: String, now: Long = System.currentTimeMillis()): Solas {
        var liberadasSolas = 0
        var sinRespuesta = 0
        var pedidas = 0
        var leidas = 0
        var proxima: Long? = null
        fun esperarA(ms: Long) { proxima = minOf(proxima ?: ms, ms) }
        // 🔴 Ronda 22 (Codex r20, P2-1): se pagina con cursor y las que esperan su reintento se saltan EN MEMORIA, sin gastar el
        // cupo: 100 dudas que no se pueden liberar ya no tapan a la 101.
        // 🔴 Ronda 24 (Codex r22, P2): y la pasada EMPIEZA donde se quedó la anterior (en rueda, dando la vuelta al principio). Empezar
        // siempre desde el principio dejaba sin turno a la 26 cuando las 25 primeras volvían a vencer su espera (el backoff del
        // worker es de 30 s), y a la 2 001 detrás de 2 000 que todavía esperan (el tope de lectura por pasada).
        val inicio = cursorSolas[venueId]
        var tras = inicio ?: (Long.MIN_VALUE to "")
        var segundaVuelta = false
        var completa = false
        var ultimaProcesada: Pair<Long, String>? = null
        pagina@ while (leidas < SOLAS_LEIDAS_POR_PASADA) {
            val porLiberar = try {
                dao.localesPorLiberarSolas(venueId, tras.first, tras.second, SOLAS_POR_LEER)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sinRespuesta++; Timber.w(error, "🔎 [LedgerServer] no se pudieron leer las dudas locales"); break
            }
            porLiberar.lastOrNull()?.let { tras = it.updatedAt to it.attemptId }
            for (fila in porLiberar) {
                val clave = fila.updatedAt to fila.attemptId
                // En la segunda vuelta se llega hasta donde empezó esta pasada: ya se vio todo.
                if (segundaVuelta && inicio != null && esPosterior(clave, inicio)) { completa = true; break@pagina }
                if (leidas >= SOLAS_LEIDAS_POR_PASADA) break@pagina
                leidas++
                // 🔴 Ronda 22 (Codex r20, P2-2): el reintento también va en el reloj MONOTÓNICO (una hora corregida hacia atrás lo
                // alargaba una hora), y lo que le falta cuenta para `proximaEnMs`: el seguimiento se agenda a su vencimiento.
                // (Sin `?: Long.MIN_VALUE`: restarle el reloj DESBORDA a un positivo enorme y toda duda parecía «esperando».)
                val reintentoEn = sinAvisoHasta[fila.attemptId]?.let { it - ledger.relojMonotonico() } ?: 0L
                if (reintentoEn > 0) { esperarA(reintentoEn); ultimaProcesada = clave; continue }
                // Ronda 21 (Codex r19, P2-1): la espera del aviso, en el reloj monotónico; la que no la cumple se salta sin POST.
                val falta = ledger.faltaParaLiberarSola(fila.attemptId, ESPERA_AL_AVISO_DEL_BANCO_MS)
                if (falta > 0) { esperarA(falta); ultimaProcesada = clave; continue }
                // Cupo lleno con dudas todavía por pedir: que quien llama vuelva YA, no al siguiente disparo. (Esta fila NO cuenta
                // como procesada: la pasada siguiente empieza por ella.)
                if (pedidas >= CANDIDATAS_POR_PAGINA) { esperarA(0); break@pagina }
                pedidas++
                when (liberarSinRastroDelBanco(venueId, fila.attemptId, now)) {
                    SinRastro.LIBERADA -> { liberadasSolas++; sinAvisoHasta.remove(fila.attemptId) }
                    // 🔴 Ronda 23 (Codex r21, P2): sin respuesta, una espera CORTA propia — sin ella, 25 dudas con 503 persistente
                    // gastaban el cupo de cada pasada y la 26 nunca recibía su turno.
                    SinRastro.SIN_RESPUESTA -> {
                        sinRespuesta++
                        sinAvisoHasta[fila.attemptId] = ledger.relojMonotonico() + REINTENTO_SIN_RESPUESTA_MS
                        esperarA(REINTENTO_SIN_RESPUESTA_MS)
                    }
                    SinRastro.TODAVIA_NO -> Unit
                    // ponytail: backoff en memoria; un proceso nuevo lo vuelve a pedir una vez, que es lo barato y lo correcto.
                    SinRastro.SIN_AVISO_COMPROBADO, SinRastro.NO_ELEGIBLE, SinRastro.CON_EVIDENCIA -> {
                        sinAvisoHasta[fila.attemptId] = ledger.relojMonotonico() + REINTENTO_SIN_AVISO_MS
                        esperarA(REINTENTO_SIN_AVISO_MS)
                    }
                }
                ultimaProcesada = clave
            }
            if (porLiberar.size < SOLAS_POR_LEER) {
                if (segundaVuelta || inicio == null) { completa = true; break }
                segundaVuelta = true; tras = Long.MIN_VALUE to ""
            }
        }
        // Se vio todo ⇒ la próxima empieza desde el principio; si no, justo después de la última que se atendió.
        if (completa) cursorSolas.remove(venueId) else ultimaProcesada?.let { cursorSolas[venueId] = it }
        return Solas(liberadasSolas, sinRespuesta, proxima)
    }

    /** Ronda 24: dónde se quedó la última pasada de dudas locales, por venue (la llave del orden: `updated_at, attempt_id`). */
    private val cursorSolas = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun esPosterior(a: Pair<Long, String>, b: Pair<Long, String>): Boolean = a.first > b.first || (a.first == b.first && a.second > b.second)

    /**
     * D3: UN intento, en el acto — primero lo guardado (E2), después S6 si hace falta. Un fallo es no-op (el worker lo
     * retoma). Devuelve el JSON de bandeja resuelto, si lo hubo, para emitirlo.
     *
     * [estampar] = si una respuesta SIN veredicto gasta el turno de E3 (`server_check_count`, espaciado `base × 2^n`). El
     * respaldo (trigger, worker, barrido) estampa; el SONDEO INTERACTIVO de la pantalla (cada 5 s durante la ventana de
     * confirmación, Task 7) pasa `false`: con siete sondeos «sin veredicto» la fila quedaba fuera del respaldo N3 ~21 h —
     * justo cuando la terminal se queda sin red al liberar el servidor y la venta sigue cercada. Los veredictos y las
     * liberaciones se guardan igual con o sin estampa.
     */
    suspend fun recoverOne(venueId: String, attemptId: String, now: Long = System.currentTimeMillis(), estampar: Boolean = true): LecturaDelIntento {
        ledger.reaplicarVeredictoGuardado(attemptId, now)?.let { if (it.transiciono || it.bandejaResueltaJson != null) return LecturaDelIntento(it.bandejaResueltaJson) }
        val fila = runCatching { dao.getById(attemptId) }.getOrNull() ?: return LecturaDelIntento(null)
        // 🔴 Pieza C (22-sep): se retira `terminalPaymentRequestId == null` del descarte. Un **Pago rápido** —cobro
        // iniciado EN la terminal— nunca tiene solicitud del POS, así que ese filtro dejaba fuera de la consulta
        // interactiva justo a los intentos que apartan el aparato entero y no tienen otra salida (medido en la N86: 13
        // de 27). El barrido (`candidatasDeConsultaAlServidor`) ya los dejaba entrar desde el 21-sep; faltaba aquí.
        // S6 contesta por INTENTO desde la pieza A, y lo que se aplique sigue pasando por los CAS de siempre.
        if (fila.legacyShadow || fila.venueId != venueId) return LecturaDelIntento(null)
        val outcomeGuardado = fila.serverOutcome
        if (outcomeGuardado == PaymentAttemptEntity.SERVER_RECORDED || outcomeGuardado == PaymentAttemptEntity.SERVER_SECOND_CAPTURE_EVIDENCE) return LecturaDelIntento(null)
        return try {
            val respuesta = kotlinx.coroutines.withTimeoutOrNull(CONSULTA_TIMEOUT_MS) { api.getAttemptStatus(venueId, attemptId) }
            if (respuesta == null) {
                Timber.w("🔎 [LedgerServer] consulta inmediata sin respuesta (tope) para %s", attemptId)
                return LecturaDelIntento(null)
            }
            // 🔴 Codex r9 (P1-2): TODO lo que decide la lectura sale del CUERPO antes de escribir nada. Anotar la respuesta iba
            // primero y, si esa escritura fallaba, el `catch` devolvía una lectura VACÍA: sin el veto, la pantalla leía una
            // liberación vieja de la fila y volvía a decir «se puede cobrar».
            val cuerpo = if (respuesta.isSuccessful) respuesta.body() else null
            val veredicto = cuerpo?.let { VeredictoDeIntento.desdeConsultaS6(venueId, attemptId, it) }
            if (veredicto == null) {
                // P1-2: evidencia positiva del intento que NO es un veredicto aplicable (sin `paymentId`, o el banco aprobó sin
                // Payment): no hay Payment y la fila conserva su estado — pero desde el fix 4 la evidencia SÍ se escribe, DURABLE,
                // ANTES de procesar cualquier liberación o devolver la lectura. Se decide por el CUERPO, nunca por el resultado de
                // la escritura: un fallo al guardar no se lee como «sin evidencia».
                // 🔴 Codex r5-4: los tres avisos del servidor se hacen DURABLES aquí, ANTES de procesar ninguna
                // liberación. Sin esto vivían en RAM: la consulta que trae la contradicción muere con el proceso (o con
                // el ViewModel) y una respuesta LIMPIA y ATRASADA —de otro de los tres consumidores concurrentes— pasa
                // el CAS y libera la venta. El veto se guarda aunque después no haya nada que liberar.
                // 🔴 Codex r8 (P1-2): el veto se decide por el CUERPO y se DEVUELVE, se haya podido guardar o no. Si la escritura
                // falla, la fila puede conservar una liberación VIEJA y la pantalla la leía como «puedes volver a cobrar».
                val veto = cuerpo?.let { LiberacionDelServidor.motivoDelVeto(it.attempt) }
                val evidenciaSinRegistro = cuerpo != null && LiberacionDelServidor.acreditaDinero(cuerpo.attempt)
                if (evidenciaSinRegistro) {
                    Timber.w("🔎 [LedgerServer] %s: el servidor acredita dinero sin Payment (outcome=%s, banco=%s) — evidencia durable, sin liberación", attemptId, cuerpo?.attempt?.outcome, cuerpo?.attempt?.processorEvidence)
                }
                // 🔴 Codex r13 (P1): el veto y la evidencia de ESTA respuesta, en UN tramo no cancelable ([guardarLoDelCuerpo]).
                val (vetoEscrito, evidenciaEscrita) = guardarLoDelCuerpo(venueId, attemptId, cuerpo, now)
                val guardado = vetoEscrito?.isFailure != true && evidenciaEscrita?.isFailure != true
                // Codex r8 (P2-4): CONTESTÓ — con o sin `estampar`: el sondeo de la pantalla no gasta el turno, pero una respuesta
                // es un hecho, y es lo que la declaración sin red y la poda necesitan saber. 🔴 Codex r9 (P1-3): y sólo DESPUÉS de
                // guardar lo que trae; si no quedó, la marca no se pone y la poda sigue sin poder tocar la fila.
                if (cuerpo != null && guardado) anotar(attemptId) { dao.estamparRespuestaDelServidor(attemptId, now) }
                val liberacion = cuerpo?.let { LiberacionDelServidor.desdeConsultaS6(venueId, attemptId, it) }
                val liberada = liberacion != null && ledger.aplicarLiberacionDelServidor(liberacion, now).getOrDefault(false)
                // Codex r12 (P2-2): si el veto o la evidencia no quedaron, el turno se conserva para que el worker vuelva.
                if (!liberada && estampar && guardado) anotar(attemptId) { dao.estamparConsultaAlServidor(attemptId, now) }
                LecturaDelIntento(null, evidenciaPositivaSinRegistro = evidenciaSinRegistro, servidorContesto = respuesta.isSuccessful, vetoDelServidor = veto)
            } else {
                // Un veredicto sólo sale de un 2xx: contestó. 🔴 Codex r9 (P1-2): todo veredicto trae un Payment; si no se pudo
                // GUARDAR, vuelve como evidencia de dinero (la fila puede conservar una liberación vieja) y no se anota la respuesta.
                // 🔴 Codex r10 (P1-2): un `Result.success` con la decisión RECHAZADO_PERTENENCIA (o SIN_FILA, FUERA_DE_ALCANCE) NO
                // escribió nada: tampoco es un veredicto guardado. Vuelve como evidencia de dinero y se deja la marca durable sin
                // atribuir el Payment ajeno; la marca de «contestó» sólo va con el veredicto guardado. 🔴 Codex r12 (P2-1): las
                // dos escrituras en UN tramo no cancelable — la pantalla que se cierra a medias ya no se lleva la marca.
                val (aplicado, _) = aplicarConRespaldo(veredicto, venueId, attemptId, now)
                val guardado = aplicado?.quedoEnLaFila == true
                if (guardado) anotar(attemptId) { dao.estamparRespuestaDelServidor(attemptId, now) }
                // Ronda 21 (Codex r19, P1-1): el veto de la respuesta sube a la pantalla también cuando hay veredicto.
                LecturaDelIntento(aplicado?.bandejaResueltaJson, evidenciaPositivaSinRegistro = !guardado, servidorContesto = true, vetoDelServidor = veredicto.veto)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "🔎 [LedgerServer] consulta inmediata sin respuesta para %s", attemptId)
            LecturaDelIntento(null)
        }
    }

    /**
     * 🔴 Codex r14 (P2-3) / r15 (P2): las filas que CONTESTARON pero no se pudieron guardar (no avanzan su turno en la base). Una
     * RUEDA: cada pasada da turno a lo sumo a [CANDIDATAS_POR_PAGINA] de ellas, a partir de la siguiente a [ultimaEnTurno], y
     * excluye al resto. En memoria A PROPÓSITO: si la escritura de esas filas falla, tampoco se podría guardar en la base un
     * cursor para ellas. El objeto es `@Singleton`; un proceso nuevo arranca con la rueda vacía.
     */
    private val atoradas = java.util.TreeSet<String>()
    private var ultimaEnTurno: String? = null

    /** El turno de esta pasada (hasta una página, en rueda) y las atoradas que se excluyen. */
    private fun turnoDeAtoradas(): Pair<List<String>, List<String>> = synchronized(atoradas) {
        val desde = ultimaEnTurno
        val enOrden = if (desde == null) atoradas.toList() else atoradas.tailSet(desde, false).toList() + atoradas.headSet(desde, true).toList()
        val turno = enOrden.take(CANDIDATAS_POR_PAGINA)
        if (turno.isNotEmpty()) ultimaEnTurno = turno.last()
        val enTurno = turno.toSet()
        turno to atoradas.filter { it !in enTurno }
    }

    /**
     * 🔴 Codex r12 (P2-1) / r14: deja la marca durable `APPROVED` (sin atribuir el Payment) y aplica el veredicto — las dos en
     * UN tramo no cancelable, la marca PRIMERO. `withContext(NonCancellable + IO)` protege el bloque de cada escritura,
     * pero el REGRESO al contexto cancelado lanza: separadas, una cancelación entre ambas se llevaba la marca. La cancelación
     * se propaga DESPUÉS de las dos. Devuelve el veredicto (null = la transacción falló) y el resultado de la marca.
     */
    private suspend fun aplicarConRespaldo(veredicto: VeredictoDeIntento, venueId: String, attemptId: String, now: Long):
        Pair<ResultadoDelVeredicto?, Result<Boolean>> = withContext(NonCancellable) {
        // 🔴 Codex r14 (P1-1, P1-2): la marca que APARTA el aparato va PRIMERO y SIEMPRE — todo veredicto trae un Payment, o sea
        // dinero del servidor. «No cancelable» no es «atómico»: con la marca después, entre los dos commits otra pantalla podía
        // reservar y autorizar; y un veredicto que se guarda sin promover la fila (GUARDADO_SIN_LIBERAR, RECORDED sobre una
        // DESCARTADA) «quedaba en la fila» sin que nada apartara el aparato — la reserva no mira `server_payment_id`.
        val marca = ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId, now)
        // 🔴 Codex r15 (P1): sin la marca, el veredicto NO se guarda. Guardarlo consume el turno (el DAO lo estampa), saca la fila
        // de las candidatas (RECORDED) y una DESCARTADA no se reaplica: quedaría fuera de toda recuperación sin nada que aparte el
        // aparato. Así la fila sigue candidata y la consulta siguiente vuelve a intentar las dos, en el mismo orden.
        (if (marca.isFailure) null else ledger.aplicarVeredictoDelServidor(veredicto, now).getOrNull()) to marca
    }.also { currentCoroutineContext().ensureActive() }   // 🔴 Codex r13 (P2-2): mismo dispatcher ⇒ el regreso NO comprueba solo

    /**
     * 🔴 Codex r13 (P1): lo que trae UNA respuesta sin veredicto —el veto y la evidencia de dinero— se escribe en UN tramo no
     * cancelable. Separados, cancelar entre los dos dejaba el veto sin la aprobación; y para un Pago rápido sin orden el veto
     * solo NO aparta la terminal (la reserva del aparato mira `APPROVED`; el veto sólo cerca la venta): otro cobro podía entrar.
     * Devuelve el resultado de cada escritura (null = el cuerpo no la traía).
     */
    private suspend fun guardarLoDelCuerpo(venueId: String, attemptId: String, cuerpo: TerminalAttemptStatusResponse?, now: Long):
        Pair<Result<Boolean>?, Result<Boolean>?> = withContext(NonCancellable) {
        // 🔴 Codex r14 (P1-1): la evidencia (lo que APARTA el aparato) PRIMERO, el veto después: no existe el instante «veto sin
        // aprobación» en el que otra pantalla podía reservar y autorizar.
        val evidencia = if (cuerpo != null && LiberacionDelServidor.acreditaDinero(cuerpo.attempt)) ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId, now) else null
        val veto = LiberacionDelServidor.motivoDelVeto(cuerpo?.attempt)?.let { ledger.marcarVetoDelServidor(venueId, attemptId, it, now) }
        veto to evidencia
    }.also { currentCoroutineContext().ensureActive() }

    /** Una marca de contabilidad (respuesta, turno) que falla no puede tumbar la lectura ni lo que ya quedó guardado. */
    private suspend fun anotar(attemptId: String, marca: suspend () -> Int) {
        try {
            marca()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "🔎 [LedgerServer] no se pudo anotar la consulta de %s", attemptId)
        }
    }

    /**
     * Lo que UNA consulta de [recoverOne] dejó para quien la pidió: el JSON de bandeja que quedó RESOLVED (hay que EMITIRLO),
     * y si el servidor acredita evidencia POSITIVA del intento SIN registro (el banco aprobó, sin Payment todavía — S6
     * `attempt.processorEvidence = APPROVED`, o un outcome con dinero sin `paymentId`): la pantalla lo lee para vetar el
     * recobro, y desde el fix 4 la libreta TAMBIÉN lo escribe (`server_processor_evidence`, durable): no hay Payment, la fila
     * conserva su estado y el aviso F0 la muestra como contradicción.
     */
    data class LecturaDelIntento(
        val bandejaResueltaJson: String?,
        val evidenciaPositivaSinRegistro: Boolean = false,
        /**
         * El servidor CONTESTÓ (2xx) esta consulta. Sin red, sin respuesta a tiempo o con un error del servidor es `false`:
         * para el cajero es lo mismo, porque su declaración por el servidor tampoco pasaría. Es lo que decide si se ofrece
         * el respaldo del aparato (founder, 22-sep: «con internet decide el servidor; si no contesta, el aparato»).
         */
        val servidorContesto: Boolean = false,
        /**
         * 🔴 Codex r8 (P1-2): el VETO que publica la RESPUESTA (`paymentContradiction`, `evidenceContradiction`,
         * `unattributedEvidence`), decidido por el CUERPO y no por el resultado de guardarlo. Si la escritura durable falla,
         * la fila puede conservar una liberación VIEJA; sin esto, la pantalla la leía y volvía a decir «puedes cobrar».
         */
        val vetoDelServidor: String? = null,
    )

    /** Ronda 20: lo que resultó de pedirle al servidor que libere SOLA una duda local. */
    enum class SinRastro {
        /** Sin red, sin respuesta a tiempo o un 5xx: no se sabe nada y se vuelve a intentar. Primero a propósito: es el «no sé». */
        SIN_RESPUESTA,
        /** El servidor aceptó: el aviso del banco de ese comercio está comprobado y no llegó. La fila quedó liberada. */
        LIBERADA,
        /** El servidor tiene dinero (o algo que lo contradice) de ese intento: nada se libera. Lo que trajo ya quedó guardado. */
        CON_EVIDENCIA,
        /** El servidor no puede sostener ese silencio (409 `WEBHOOK_NOT_CONFIRMED`): decide el cajero. */
        SIN_AVISO_COMPROBADO,
        /** No aplica (no es un Pago rápido en duda, es de otro negocio, la sesión no pertenece…): nada cambia. */
        NO_ELEGIBLE,
        /** Ronda 21 (Codex r19, P2-1): todavía no lleva la espera del aviso en duda — medida con el reloj monotónico. Sin POST. */
        TODAVIA_NO,
    }

    /** Ronda 20: las dudas a las que el servidor ya contestó que no (o que no aplican): cuándo volver a pedirlo. En memoria. */
    private val sinAvisoHasta = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 🔴 Ronda 20 (founder, 23-sep: «no debería trabarse nunca y todo es por webhook»). Un Pago rápido en duda —el lector murió
     * con la app, o el SDK contestó algo que no acredita nada— ya no espera a una persona: pasados [ESPERA_AL_AVISO_DEL_BANCO_MS]
     * sin aviso del banco, la TERMINAL declara «sin rastro del banco tras la ventana». Medido en producción el 23-sep: el aviso
     * llega en 1.6 s (p50), 3.4 s (p99) y 4.5 s como máximo, y lo manda el 100 % de los cobros donde está configurado.
     *
     * El SERVIDOR sostiene esa declaración: sólo la acepta donde el aviso del banco de los comercios de esta terminal está
     * comprobado, con el MISMO veto de dinero que la del cajero, y la guarda como testimonio de la terminal. Aquí sólo se pide y
     * se aplica lo que conteste, por el mismo camino que S6. Un rechazo que no sea «sin aviso» se CONSULTA: puede traer dinero.
     * El `resolutionId` es fijo por intento: pantalla y worker piden la MISMA declaración (replay idempotente en el servidor).
     */
    suspend fun liberarSinRastroDelBanco(venueId: String, attemptId: String, now: Long = System.currentTimeMillis()): SinRastro {
        val fila = try {
            dao.getById(attemptId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        } ?: return SinRastro.NO_ELEGIBLE
        if (!ledger.puedeLiberarseSola(fila, venueId)) return SinRastro.NO_ELEGIBLE
        // 🔴 Ronda 21 (Codex r19, P1-3): el servidor mide el aviso del COMERCIO de este cobro, no el que la terminal tenga
        // asignado hoy. Sin él en el contexto, ese silencio no se puede sostener: decide el cajero (sin POST).
        val comercio = comercioDelCobro(fila) ?: return SinRastro.SIN_AVISO_COMPROBADO
        if (ledger.faltaParaLiberarSola(attemptId, ESPERA_AL_AVISO_DEL_BANCO_MS) > 0) return SinRastro.TODAVIA_NO
        val respuesta = try {
            kotlinx.coroutines.withTimeoutOrNull(CONSULTA_TIMEOUT_MS) {
                api.resolveNoInstrument(
                    venueId, attemptId,
                    NoInstrumentResolutionRequest(
                        requestId = null, resolutionId = idDeLaLiberacionSola(attemptId), statement = STATEMENT_SIN_RASTRO,
                        merchantAccountId = comercio,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "🔎 [LedgerServer] liberación sola sin respuesta para %s", attemptId)
            null
        } ?: return SinRastro.SIN_RESPUESTA
        if (!respuesta.isSuccessful) {
            val codigo = runCatching { org.json.JSONObject(respuesta.errorBody()?.string().orEmpty()).optString("code") }.getOrNull()
            Timber.w("🔎 [LedgerServer] liberación sola de %s rechazada: HTTP %d %s", attemptId, respuesta.code(), codigo)
            return when {
                respuesta.code() >= 500 -> SinRastro.SIN_RESPUESTA
                codigo == "WEBHOOK_NOT_CONFIRMED" -> SinRastro.SIN_AVISO_COMPROBADO
                // 🔴 Ronda 23 (Codex r21, P1-2): el servidor YA dijo que hay evidencia de dinero. Eso veta DESDE aquí, durable y
                // antes de depender de otra petición: si la consulta siguiente falla, el cierre sin red del gerente pasaba.
                codigo == "POSITIVE_EVIDENCE_EXISTS" -> {
                    ledger.marcarEvidenciaPositivaDelServidor(venueId, attemptId, now)
                    // …y se consulta igual, para guardar el veredicto (el Payment) si el servidor lo da. Si la consulta falla, la
                    // marca ya quedó: el desenlace no depende de ella.
                    recoverOne(venueId, attemptId, now, estampar = false)
                    SinRastro.CON_EVIDENCIA
                }
                // Evidencia, otra declaración, fila ajena: se CONSULTA el intento y se guarda lo que diga (el dinero manda).
                else -> {
                    // 🔴 Ronda 22 (Codex r20, P1-3): la evidencia o el veto que trae la consulta mandan aunque NO se hayan podido
                    // guardar — la fila puede conservar una liberación VIEJA (la aplicó otra consulta mientras viajaba el POST) y
                    // leerla sola devolvía LIBERADA con el dinero ya a la vista.
                    val lectura = recoverOne(venueId, attemptId, now, estampar = false)
                    if (lectura.evidenciaPositivaSinRegistro || !lectura.vetoDelServidor.isNullOrBlank()) SinRastro.CON_EVIDENCIA
                    else desenlaceDeLaFila(attemptId)
                }
            }
        }
        val cuerpo = respuesta.body() ?: return SinRastro.SIN_RESPUESTA
        val veredicto = VeredictoDeIntento.desdeConsultaS6(venueId, attemptId, cuerpo)
        if (veredicto != null) {
            aplicarConRespaldo(veredicto, venueId, attemptId, now)
            return SinRastro.CON_EVIDENCIA
        }
        val (vetoEscrito, evidenciaEscrita) = guardarLoDelCuerpo(venueId, attemptId, cuerpo, now)
        if (LiberacionDelServidor.acreditaDinero(cuerpo.attempt) || LiberacionDelServidor.motivoDelVeto(cuerpo.attempt) != null) return SinRastro.CON_EVIDENCIA
        if (vetoEscrito?.isFailure == true || evidenciaEscrita?.isFailure == true) return SinRastro.SIN_RESPUESTA
        val liberacion = LiberacionDelServidor.desdeDeclaracion(venueId, attemptId, null, cuerpo) ?: return SinRastro.CON_EVIDENCIA
        if (ledger.aplicarLiberacionDelServidor(liberacion, now).getOrDefault(false)) {
            Timber.w("🔎 [LedgerServer] %s liberado SOLO: el banco no dejó rastro tras la espera del aviso", attemptId)
            return SinRastro.LIBERADA
        }
        return desenlaceDeLaFila(attemptId)
    }

    /** Ronda 21: el comercio con el que se hizo el cobro, del contexto que la libreta guardó al abrirlo. */
    private fun comercioDelCobro(fila: PaymentAttemptEntity): String? = runCatching {
        com.google.gson.JsonParser.parseString(fila.paymentContextJson).asJsonObject.get("merchantAccountId")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Ronda 20: lo que dice la fila después de consultar — el dinero primero. */
    private suspend fun desenlaceDeLaFila(attemptId: String): SinRastro {
        val f = try {
            dao.getById(attemptId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        } ?: return SinRastro.NO_ELEGIBLE
        return when {
            f.state == PaymentAttemptEntity.STATE_REGISTRADO || f.serverOutcome in PaymentAttemptEntity.SERVER_OUTCOMES_CON_DINERO ||
                f.serverProcessorEvidence == PaymentAttemptEntity.SERVER_PROCESSOR_EVIDENCE_APPROVED || !f.serverVeto.isNullOrBlank() -> SinRastro.CON_EVIDENCIA
            f.serverOutcome == PaymentAttemptEntity.SERVER_RELEASED_NO_EVIDENCE || f.serverOutcome == PaymentAttemptEntity.SERVER_OPERATOR_NO_INSTRUMENT -> SinRastro.LIBERADA
            else -> SinRastro.NO_ELEGIBLE
        }
    }

    companion object {
        /**
         * Ronda 20: cuánto se espera el aviso del banco antes de pedir la liberación sola. Decisión del founder (23-sep) con la
         * medición enfrente: el aviso llega en 3.4 s (p99), 4.5 s como máximo; 10 s es más del doble.
         */
        const val ESPERA_AL_AVISO_DEL_BANCO_MS = 10_000L
        /** Ronda 20: tras un «sin aviso comprobado» (o un rechazo), cuándo volver a pedirlo: no se martilla al servidor. */
        const val REINTENTO_SIN_AVISO_MS = 10 * 60_000L
        /** Ronda 23: tras una petición SIN respuesta (sin red, 5xx), cuándo volver a pedir ESA duda — corto: no es un «no». */
        const val REINTENTO_SIN_RESPUESTA_MS = 30_000L
        /** Ronda 20: dudas locales que se leen por pasada (se atienden hasta [CANDIDATAS_POR_PAGINA]; las que esperan se saltan). */
        const val SOLAS_POR_LEER = 100
        /** Ronda 22: tope de dudas locales leídas por pasada (páginas de [SOLAS_POR_LEER]). */
        const val SOLAS_LEIDAS_POR_PASADA = 2_000
        const val STATEMENT_SIN_RASTRO = "NO_BANK_TRACE_AFTER_WINDOW"
        /** Ronda 20: el MISMO id en cada petición (pantalla y worker): el servidor trata la segunda como replay idempotente. */
        fun idDeLaLiberacionSola(attemptId: String): String = java.util.UUID.nameUUIDFromBytes("liberacion-sola:$attemptId".toByteArray()).toString()

        /** Los estados con SDK dentro sólo se consultan pasados 120 s (mismo umbral que la recuperación por historial). */
        const val VIVOS_MS = 120_000L
        const val ESPACIADO_BASE_MS = 10 * 60_000L
        const val ESPACIADO_TOPE_MS = 24 * 60 * 60_000L
        const val CONSULTA_TIMEOUT_MS = 30_000L
        /** Codex r13 (P2-4): páginas de candidatas por pasada cuando la anterior se quedó entera atorada por escrituras. */
        /** Codex r14 (P2-3) / r16 (P2): tope de filas ATENDIDAS por pasada (cuatro páginas). */
        const val MAX_ATENDIDAS_POR_PASADA = 4 * CANDIDATAS_POR_PAGINA

        /** Codex r17 (P3): cuántas filas de la rueda se leen de más en una lectura para saltarlas (lecturas de hasta 500). */
        const val SALTO_MAXIMO_POR_LECTURA = 475
    }
}
