package com.jaac.avoqado_tpv.features.tables.data.sync

import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import java.io.IOException

/**
 * Resultado de clasificar un fallo de un intent de Mesas (`ADD_ITEMS`, `OPEN_TABLE`, …).
 *
 * Clasificador PROPIO de Mesas — a propósito NO es
 * [com.jaac.avoqado_tpv.features.payment.domain.sync.classifySyncFailure] (Plan A,
 * pagos). Los dos dominios tienen el sesgo opuesto ante la duda:
 *
 * - **Pagos**: la tarjeta YA se cobró — algo irreversible ya pasó. Perder el registro
 *   de un cobro real es el peor caso, así que ante la duda se reintenta (`Retryable`
 *   es el default permisivo: hasta un 401/403/500/desconocido cae ahí).
 * - **Mesas**: nada irreversible ha pasado todavía. Decirle al mesero "guardado"
 *   cuando NO se guardó es el peor caso — la ronda deja de existir para el mesero y
 *   para el server. Así que ante la duda se PROPAGA: que el mesero vea el error y
 *   reintente a mano es más seguro que un "éxito" silencioso encolado.
 *
 * Ver `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3: "Un fallo de RED
 * se convierte en intent. Un rechazo de NEGOCIO (403/409/4xx) se propaga tal cual al
 * usuario." — la misma idea aplica a un 500 (podría ser un bug real del server) y a
 * cualquier throwable desconocido (podría ser un bug propio, p.ej. NPE): ninguno de
 * los dos debe disfrazarse de "offline success".
 *
 * NUNCA reusar [com.jaac.avoqado_tpv.features.payment.domain.sync.SyncOutcome] /
 * `classifySyncFailure` aquí — ese archivo se queda intacto, verificado en hardware
 * para Pagos, y sus reglas permisivas están mal para Mesas.
 */
sealed class TablesSyncOutcome {
    /** Fallo transitorio de infraestructura (red, gateway, rate-limit) — se encola como intent offline. */
    data object Retryable : TablesSyncOutcome()

    /** El server rechazó, o el fallo es de origen desconocido — se propaga tal cual al usuario. */
    data class Propagate(val reason: String) : TablesSyncOutcome()
}

/**
 * Códigos HTTP que significan "infraestructura transitoria, no es tu culpa" — nunca
 * "el server dijo que no". 502/503/504 = gateway/servicio caído; 408/429 = timeout o
 * rate-limit (nuestra propia ráfaga de intents al reconectar puede provocar el 429).
 *
 * A propósito NO incluye 500: un 500 puede ser un bug real del server, y un mesero
 * viendo el error y reintentando a mano es más seguro que encolarlo como si fuera
 * solo tráfico. Tampoco incluye 401/403: el usuario debe verlos, no que se disfracen
 * de "guardado offline".
 */
private val QUEUEABLE_HTTP_CODES = setOf(502, 503, 504, 408, 429)

/**
 * Clasifica un fallo de un intent de Mesas **por código HTTP, nunca por texto del
 * mensaje** — un reference number como "000000409231" contiene "409" y ese error de
 * texto ya causó que se marcaran como sincronizadas ventas que nunca llegaron al
 * backend (ver KDoc de [BackendHttpException]). La misma trampa aplica aquí.
 *
 * Regla de seguridad de Mesas — la opuesta a la de Pagos: ante la duda →
 * [TablesSyncOutcome.Propagate]. Un throwable desconocido (incluido un bug de
 * programación propio) DEBE propagarse: enmascararlo como "guardado offline" deja al
 * mesero pensando que la ronda se envió cuando nunca existió.
 */
fun classifyTablesSyncFailure(error: Throwable?): TablesSyncOutcome = when {
    error is BackendHttpException && error.statusCode in QUEUEABLE_HTTP_CODES ->
        TablesSyncOutcome.Retryable

    error is BackendHttpException ->
        // 401/403/405... otros 4xx, y 500: todos son "el server respondió que no" o
        // "posible bug real" — nunca se encolan.
        TablesSyncOutcome.Propagate("HTTP ${error.statusCode}: ${error.message}")

    error is IOException -> TablesSyncOutcome.Retryable // sin red, timeout de transporte, DNS

    else -> TablesSyncOutcome.Propagate(error?.message ?: "Fallo desconocido") // bug propio: nunca disfrazar de éxito
}
