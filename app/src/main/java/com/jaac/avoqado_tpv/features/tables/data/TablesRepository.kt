package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TablesSyncOutcome
import com.jaac.avoqado_tpv.features.tables.data.sync.classifyTablesSyncFailure
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio de Mesas — dueño de [addItems]'s write-ahead (Plan C, Task 4;
 * endurecido por un fix P1 posterior — ver KDoc de [addItems]), la
 * separación que gobierna el módulo entero: **"sin red" es un intent
 * encolado y NUNCA un error; "el server rechazó" es un error normal y
 * NUNCA se encola.** Confundir los dos es el bug clásico de offline-first —
 * ver `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.3.
 *
 * Cada operación mutable de Mesas (hoy solo [addItems]; Tasks 5-8 agregan
 * `openTable`/`payCash`/etc. sobre este mismo repositorio) llama el endpoint
 * online directo (`tpv/…`), nunca `sync/intents` directamente — ese es solo
 * el camino de REPLAY del outbox ([SyncOutbox.replayNow]) — y convierte el
 * `Response<T>` de Retrofit a `Result<T>`, preservando el código HTTP en
 * [BackendHttpException] para que [classifyTablesSyncFailure] pueda
 * clasificar por código, nunca por texto.
 *
 * [classifyTablesSyncFailure] es un clasificador PROPIO de Mesas — a propósito
 * NO reusa `classifySyncFailure` de Pagos (Plan A). Pagos es permisivo ante la
 * duda (la tarjeta ya se cobró); Mesas es estricto ante la duda (nada
 * irreversible pasó todavía, así que un 401/403/500/desconocido se PROPAGA en
 * vez de disfrazarse de "guardado offline"). Ver KDoc de
 * [TablesSyncOutcome].
 */
@Singleton
class TablesRepository @Inject constructor(
    private val api: TablesApiService,
    private val syncOutbox: SyncOutbox,
) {
    private val gson = Gson()

    /**
     * `GET tpv/venues/{venueId}/tables` — el plano en vivo (Plan C, Task 6).
     * Lectura pura: a diferencia de [addItems], una falla aquí NUNCA se
     * encola — no hay ningún intent que reproducir por leer el plano, solo
     * éxito o fallo. El offline-first de LECTURA (seguir mostrando la última
     * copia buena cuando el refresco falla) vive en
     * [com.jaac.avoqado_tpv.features.tables.presentation.TablesViewModel],
     * no aquí — este método solo reporta qué pasó.
     */
    suspend fun getTables(venueId: String): Result<List<DiningTable>> = try {
        val response = api.getTables(venueId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(
                    statusCode = response.code(),
                    message = parseBackendErrorMessage(response.errorBody()?.string(), response.message()),
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getTables (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getTables (venue=%s)", venueId)
        Result.failure(e)
    }

    /**
     * `GET tpv/venues/{venueId}/floor-elements` — decoración del canvas
     * (paredes, barra, área de servicio, puertas, etiquetas). Mismo criterio
     * de lectura pura que [getTables].
     */
    suspend fun getFloorElements(venueId: String): Result<List<FloorElement>> = try {
        val response = api.getFloorElements(venueId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(
                    statusCode = response.code(),
                    message = parseBackendErrorMessage(response.errorBody()?.string(), response.message()),
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getFloorElements (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getFloorElements (venue=%s)", venueId)
        Result.failure(e)
    }

    /**
     * `PATCH tpv/venues/{venueId}/orders/{orderId}/items` — envía una ronda.
     *
     * 🔴 P1 fix (auditoría independiente): el outbox de Mesas debe ser
     * write-ahead DE VERDAD — el intent se persiste ANTES de intentar el
     * camino online, no solo después de que ese intento falle. La versión
     * anterior llamaba al server primero y solo creaba el intent si esa
     * llamada fallaba; si el server SÍ aplicaba la ronda pero la respuesta se
     * perdía en el camino (`IOException` de lectura — DROP_RESPONSE), esta
     * función lo interpretaba como "sin red", encolaba un intent NUEVO sin
     * ninguna relación con el intento perdido, y el replay de ESE intent
     * agregaba la ronda una SEGUNDA vez — el server no tenía forma de
     * reconocer que ya la había aplicado porque el intento online nunca
     * mandó ninguna llave de idempotencia.
     *
     * El fix: [intentId] se genera y el intent se escribe ([SyncOutbox.enqueue])
     * ANTES de la primera llamada online, con el MISMO `externalId` por línea
     * (`sync:<intentId>:<idx>`, igual formato que el fallback de
     * `applyAddItems` en `avoqado-server/src/services/mobile/sync.mobile.service.ts`)
     * incrustado tanto en lo que se manda online como en lo que queda
     * encolado — si la respuesta del intento online se pierde, el replay
     * reproduce EXACTAMENTE la misma llave, así que el server (cuando su
     * schema la acepte también en la ruta online — ver nota abajo) dedupea
     * por `externalId` en vez de duplicar.
     *
     * - Éxito online: el intent write-ahead ya no hace falta —
     *   [SyncOutbox.discardPending] lo borra (nunca se reproduce) y regresa
     *   el `OrderDetail` real del server, CAS incluido.
     * - Sin red (excepción de transporte) o 502/503/504/408/429
     *   (infraestructura transitoria, ver [TablesSyncOutcome.Retryable]): el
     *   intent YA está escrito — no hay nada más que hacer aquí, se deja
     *   PENDING y se regresa éxito con un `OrderDetail` OPTIMISTA — el mesero
     *   sigue viendo su ronda como enviada, el outbox drena solo.
     * - 401/403 (sesión/permiso — el usuario debe verlo), cualquier otro
     *   4xx (rechazo de negocio, incluye 400/404/422 permanentes y 409 CAS:
     *   la `version` local ya no coincide — el reducer del server la vuelve
     *   a leer al aplicar, así que un 409 aquí es SIEMPRE de la llamada
     *   online, nunca del replay), 500 (podría ser un bug real del server),
     *   o un throwable desconocido (podría ser un bug propio): el intent
     *   write-ahead se descarta (nada que reproducir) y el error se propaga
     *   TAL CUAL — encolarlo reintentaría para siempre contra algo que no va
     *   a cambiar solo, o peor, disfrazaría un bug de "guardado offline"; la
     *   pantalla (Task 7) es quien debe recargar la cuenta y avisar.
     *
     * ⚠️ Nota honesta: `addOrderItemsSchema` del server
     * (`avoqado-server/src/schemas/tpv.schema.ts`), que valida ESTA ruta
     * online, hoy NO declara `externalId` por línea — Zod lo descarta en
     * silencio antes de llegar al controller/servicio (que sí lo soporta, ver
     * `AddOrderItemInput.externalId` en `order.tpv.service.ts`). Hasta que ese
     * schema acepte el campo (cambio aditivo, server-side, fuera del alcance
     * de este repo), el cierre TOTAL del hueco DROP_RESPONSE en el intento
     * ONLINE queda pendiente de ese cambio — este fix sí deja al cliente
     * listo para el día que el server lo acepte, y sí protege por completo
     * el caso de un REPLAY reproducido más de una vez (ya soportado hoy por
     * `sync/intents`) y el caso de un crash entre "el server aplicó" y "el
     * cliente lo hubiera encolado" (que antes perdía la ronda en silencio).
     *
     * El `OrderDetail` optimista de fallback solo describe la RONDA nueva
     * (no el cheque completo — este repositorio no mantiene un caché local
     * del pedido) porque `items` es toda la información con la que cuenta
     * esta función. Fusionarlo con el estado real del cheque es
     * responsabilidad de quien tiene esa vista completa (`TableSession` /
     * `TableOrderViewModel`, Tasks 5 y 7).
     */
    suspend fun addItems(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        version: Int,
    ): Result<OrderDetail> {
        val intentId = UUID.randomUUID().toString()
        val itemsWithKey = items.mapIndexed { index, item ->
            item.copy(externalId = item.externalId ?: "sync:$intentId:$index")
        }
        val payload = gson.toJsonTree(AddItemsIntentPayload(orderId = orderId, items = itemsWithKey)).asJsonObject

        // Write-ahead: el intent existe ANTES de que exista ningún intento
        // online — ver KDoc arriba para el bug exacto que esto cierra.
        syncOutbox.enqueue(venueId, SyncIntentTypes.ADD_ITEMS, payload, id = intentId)

        val onlineResult = callAddItems(venueId, orderId, itemsWithKey, version)
        if (onlineResult.isSuccess) {
            // El intento online SÍ llegó — el intent write-ahead era solo la
            // red de seguridad, se descarta para que el outbox no lo reproduzca.
            syncOutbox.discardPending(intentId)
            return onlineResult
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                // El intent ya está escrito — nada más que hacer, el próximo
                // replay lo reproduce solo.
                Timber.i("📤 [Tables] Sin conexión — ADD_ITEMS sigue encolado (venue=%s)", venueId)
                Result.success(optimisticAddItemsResult(orderId, itemsWithKey))
            }

            else -> {
                // El server rechazó (o el fallo es desconocido) — nada que
                // reproducir: se descarta el intent write-ahead y se propaga
                // el error tal cual, NUNCA se deja pendiente.
                syncOutbox.discardPending(intentId)
                onlineResult
            }
        }
    }

    private suspend fun callAddItems(
        venueId: String,
        orderId: String,
        items: List<AddOrderItemRequest>,
        version: Int,
    ): Result<OrderDetail> = try {
        api.addItems(venueId, orderId, AddItemsRequest(items = items, version = version)).toOrderDetailResult()
    } catch (e: CancellationException) {
        // CancellationException ES una RuntimeException — un catch(Exception) desnudo
        // se la traga. Repropagar SIEMPRE antes de cualquier catch genérico.
        throw e
    } catch (e: IOException) {
        // NO envolver: classifyTablesSyncFailure() necesita la IOException intacta
        // para clasificarla como Retryable (mismo patrón que FastPaymentRecorder/OrderPaymentRecorder).
        Timber.w(e, "⚠️ [Tables] Sin red en addItems (venue=%s, order=%s) — se encolará", venueId, orderId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en addItems (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    }

    private fun Response<OrderDetailResponse>.toOrderDetailResult(): Result<OrderDetail> {
        val data = body()?.data
        return if (isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(
                    statusCode = code(),
                    message = parseBackendErrorMessage(errorBody()?.string(), message()),
                ),
            )
        }
    }

    /**
     * Optimista: SOLO la ronda que se acaba de intentar mandar, con
     * `id`/`unitPrice`/`total` vacíos — Task 5/7 (que sí tienen el cheque
     * completo en memoria) son quienes fusionan esto contra el estado real.
     * Nunca se persiste ni se muestra tal cual: es la señal mínima de
     * "esto ya se aceptó localmente, sigue trabajando".
     */
    private fun optimisticAddItemsResult(orderId: String, items: List<AddOrderItemRequest>): OrderDetail =
        OrderDetail(
            id = orderId,
            items = items.mapIndexed { index, item ->
                OrderDetailItem(
                    id = "pending-$index",
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    weightQuantity = item.weightQuantity,
                )
            },
        )

    private fun parseBackendErrorMessage(rawBody: String?, fallback: String): String {
        val trimmedBody = rawBody?.trim().orEmpty()
        if (trimmedBody.isBlank()) {
            return fallback.ifBlank { "Sin detalle del servidor" }
        }

        val parsedMessage = runCatching {
            val json = com.google.gson.JsonParser.parseString(trimmedBody)
            if (!json.isJsonObject) return@runCatching null

            val obj = json.asJsonObject
            obj.get("message")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("error")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("code")?.takeUnless { it.isJsonNull }?.asString
        }.getOrNull()

        return parsedMessage?.takeIf { it.isNotBlank() } ?: trimmedBody.take(300)
    }

    /** Espejo del payload que el reducer del server espera para `ADD_ITEMS` — ver `applyAddItems` en `sync.mobile.service.ts`. */
    private data class AddItemsIntentPayload(
        val orderId: String,
        val items: List<AddOrderItemRequest>,
    )
}
