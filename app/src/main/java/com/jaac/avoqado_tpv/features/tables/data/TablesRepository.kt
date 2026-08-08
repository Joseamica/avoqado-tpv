package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.CancelOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.CompOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenedOrder
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ProductCatalogDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TablesSyncOutcome
import com.jaac.avoqado_tpv.features.tables.data.sync.classifyTablesSyncFailure
import com.jaac.avoqado_tpv.features.tables.domain.model.ActiveStaffMember
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableDiscount
import com.jaac.avoqado_tpv.features.tables.domain.model.AvailableServiceCharge
import com.jaac.avoqado_tpv.features.tables.domain.model.DiningTable
import com.jaac.avoqado_tpv.features.tables.domain.model.FloorElement
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuCategory
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifier
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuModifierGroup
import com.jaac.avoqado_tpv.features.tables.domain.model.MenuProduct
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentAck
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetailItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
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
     * Propiedad de mesa ("Solo el propietario puede modificar sus mesas") —
     * puerto de `avoqado-android/tables/data/TableServiceRepository.TableOwnership`
     * (Plan C, Task 7). Poblada por [getTables] desde el sobre `settings`/`viewer`
     * (server gap fix `a7d2f7b6`) — nunca por [openTable]/[addItems], que no
     * traen esos campos. Default `enforced = false` = "no aplica la regla",
     * NUNCA "bloqueado": un fallo de red o un server viejo sin estos campos no
     * debe dejar la pantalla en read-only por accidente.
     */
    data class TableOwnership(
        val enforced: Boolean = false,
        val canManageAll: Boolean = true,
        val staffId: String? = null,
    ) {
        /** Espejo EXACTO de `isLockedForMe` en android — mismo orden de condiciones. */
        fun isLockedForMe(ownerId: String?): Boolean = enforced && !canManageAll && ownerId != null && ownerId != staffId
    }

    private val _ownership = MutableStateFlow(TableOwnership())
    val ownership: StateFlow<TableOwnership> = _ownership.asStateFlow()

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
        val body = response.body()
        val data = body?.data
        if (response.isSuccessful && data != null) {
            // Side-effect deliberado: cada refresco del plano es también la
            // fuente MÁS FRESCA de propiedad de mesa — ni [openTable] ni
            // [addItems] traen `settings`/`viewer`, así que si no se captura
            // aquí no hay otro lugar que lo haga.
            _ownership.value = TableOwnership(
                enforced = body.settings?.enforceTableOwnership ?: false,
                canManageAll = body.viewer?.canManageAllTables ?: true,
                staffId = body.viewer?.staffId,
            )
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
     * `GET tpv/venues/{venueId}/orders/{orderId}` — el cheque completo (Plan C,
     * Task 7). Lectura pura, igual criterio que [getTables]: nunca se encola.
     * Usado para la carga inicial de `TableOrderScreen` y para recargar tras un
     * `VERSION_CONFLICT` (409) de [addItems].
     */
    suspend fun getOrder(venueId: String, orderId: String): Result<OrderDetail> = try {
        api.getOrder(venueId, orderId).toOrderDetailResult()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getOrder (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getOrder (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    }

    /**
     * `POST tpv/venues/{venueId}/tables/{tableId}/open` — abre una mesa (Plan C,
     * Task 7). `assignTable` (el servicio detrás de esta ruta, tanto online como
     * en el reducer de replay) es idempotente POR MESA: si la mesa ya tiene una
     * orden activa, la reusa en vez de crear una segunda — así que, a diferencia
     * de [addItems], este método NO necesita el patrón write-ahead: un
     * DROP_RESPONSE que reintente offline sobre la MISMA mesa jamás duplica la
     * orden, el server regresa la misma que ya existía.
     *
     * - Éxito online: [TableOpenOutcome.isProvisional] = false, con el id/version
     *   reales del server.
     * - Infraestructura transitoria ([TablesSyncOutcome.Retryable]): se encola un
     *   intent `OPEN_TABLE` con un UUID local recién generado y se regresa éxito
     *   con ese UUID como `orderId` provisional — el caller ([TableSession.open])
     *   arranca la sesión provisional; [TableSyncCoordinator] la promueve cuando
     *   llegue el ack.
     * - Rechazo de negocio (403 `TABLE_OWNED_BY_OTHER`, 404 mesa no existe, etc.)
     *   o throwable desconocido: se propaga tal cual, nunca se abre offline.
     */
    suspend fun openTable(venueId: String, tableId: String, covers: Int): Result<TableOpenOutcome> {
        val onlineResult = try {
            api.openTable(venueId, tableId, OpenTableRequest(covers)).toOpenedOrderResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en openTable (venue=%s, table=%s) — se abrirá offline", venueId, tableId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en openTable (venue=%s, table=%s)", venueId, tableId)
            Result.failure(e)
        }

        onlineResult.onSuccess { order ->
            return Result.success(
                TableOpenOutcome(orderId = order.id, orderNumber = order.orderNumber, version = order.version, isProvisional = false),
            )
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val localOrderId = UUID.randomUUID().toString()
                val payload = JsonObject().apply {
                    addProperty("tableId", tableId)
                    addProperty("covers", covers)
                    addProperty("localOrderId", localOrderId)
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.OPEN_TABLE, payload)
                Timber.i("📤 [Tables] Sin conexión — OPEN_TABLE encolado (venue=%s, table=%s)", venueId, tableId)
                Result.success(TableOpenOutcome(orderId = localOrderId, orderNumber = null, version = 1, isProvisional = true))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("openTable sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableResponse>.toOpenedOrderResult(): Result<OpenedOrder> {
        val order = body()?.data?.order
        return if (isSuccessful && order != null) {
            Result.success(order)
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
     * `GET tpv/venues/{venueId}/products` — catálogo para `TableMenuScreen`
     * (Plan C, Task 7). Lectura pura. `includeModifiers`/`includeRecipe` quedan
     * en su default del server (ambos ON) — el grid de mesas SÍ necesita
     * `modifierGroups` para el sheet de modificadores.
     */
    suspend fun getProducts(venueId: String): Result<List<MenuProduct>> = try {
        val response = api.getProducts(venueId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(data.filter { it.active }.map { it.toDomain() })
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
        Timber.w(e, "⚠️ [Tables] Sin red en getProducts (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getProducts (venue=%s)", venueId)
        Result.failure(e)
    }

    /** `GET tpv/venues/{venueId}/categories` — arreglo plano, sin sobre `{success,data}`. */
    suspend fun getCategories(venueId: String): Result<List<MenuCategory>> = try {
        val response = api.getCategories(venueId)
        val data = response.body()
        if (response.isSuccessful && data != null) {
            Result.success(
                data.filter { it.active }
                    .sortedBy { it.displayOrder ?: 0 }
                    .map { MenuCategory(id = it.id, name = it.name, displayOrder = it.displayOrder ?: 0, color = it.color, active = it.active) },
            )
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
        Timber.w(e, "⚠️ [Tables] Sin red en getCategories (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getCategories (venue=%s)", venueId)
        Result.failure(e)
    }

    private fun ProductCatalogDto.toDomain(): MenuProduct = MenuProduct(
        id = id,
        name = name,
        price = price,
        categoryId = categoryId,
        imageUrl = imageUrl,
        color = color,
        active = active,
        modifierGroups = modifierGroups.orEmpty().mapNotNull { wrapper ->
            val group = wrapper.group
            MenuModifierGroup(
                id = group.id,
                name = group.name,
                type = group.type,
                required = group.required,
                modifiers = group.modifiers.orEmpty().map { MenuModifier(id = it.id, name = it.name, price = it.price) },
            )
        },
    )

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
                    id = "$OPTIMISTIC_ITEM_ID_PREFIX$index",
                    productId = item.productId,
                    quantity = item.quantity,
                    notes = item.notes,
                    weightQuantity = item.weightQuantity,
                )
            },
        )

    /**
     * "Cobro en efectivo" — Plan C, Task 8. A diferencia de [addItems], NO hay
     * una ruta online dedicada bajo `/tpv` para registrar un pago en efectivo
     * de Mesas (la única ruta genérica de pago, `POST
     * tpv/venues/{venueId}/orders/{orderId}`, es territorio de "Cobrar" —
     * intocable por regla dura del founder). En vez de eso, `PAY_CASH` viaja
     * SIEMPRE como intent — online u offline, el mecanismo es el MISMO:
     *
     * 1. [SyncOutbox.enqueue] escribe el intent (write-ahead, mismo patrón que
     *    [addItems]) — su `id` ES el `idempotencyKey` que el server usa tal
     *    cual (`applyPayCash` → `payCashOrder({ idempotencyKey: intent.id })`
     *    en `sync.mobile.service.ts`). Sin este id, un reintento de red
     *    cobraría dos veces — regla dura de
     *    `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.5.
     * 2. [SyncOutbox.replayNow] intenta reproducir el outbox COMPLETO del
     *    venue de inmediato (respeta el FIFO — si hay intents más viejos
     *    pendientes, este PAY_CASH espera su turno, igual que le tocaría
     *    esperar offline). Si hay red, el ack de ESTE intent llega en la misma
     *    llamada. Si no hay red (o el server cortó el batch en un RETRY
     *    anterior), `replayNow` regresa sin lanzar y sin ack — el intent queda
     *    PENDING, se drena solo después.
     *
     * Nunca se llama [classifyTablesSyncFailure] aquí — `replayNow` ya absorbe
     * la distinción red-caída-vs-servidor-respondió (nunca lanza salvo
     * `CancellationException`) y el ack mismo (ACKED/REJECTED/RETRY) es la
     * única fuente de verdad de negocio, aplicada por [SyncOutbox.applyAck].
     *
     * @param amount PESOS (major units) — la conversión a centavos ocurre
     *   SOLO en el payload de este intent, porque el contrato del reducer
     *   (`applyPayCash`) exige `amountCents`/`tipCents` enteros — ver
     *   `avoqado-server/.claude/rules/critical-warnings.md` ("* 100 SOLO en un
     *   boundary externo, luego inmediatamente de vuelta a pesos"). El
     *   dominio (`OrderDetail`, `TableSession`, este ViewModel) sigue en
     *   `BigDecimal` pesos en todo lo demás.
     */
    suspend fun payCash(
        venueId: String,
        orderId: String,
        isProvisional: Boolean,
        amount: BigDecimal,
        tip: BigDecimal = BigDecimal.ZERO,
    ): Result<PayCashOutcome> {
        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            if (isProvisional) addProperty("localOrderId", orderId) else addProperty("orderId", orderId)
            addProperty("amountCents", amount.toCents())
            addProperty("tipCents", tip.toCents())
            addProperty("method", "CASH")
        }
        syncOutbox.enqueue(venueId, SyncIntentTypes.PAY_CASH, payload, id = intentId)

        var capturedAck: SyncIntentAck? = null
        // CancellationException se repropaga tal cual — el intent YA está
        // escrito (write-ahead), así que una cancelación a medio replay no
        // pierde nada: el próximo replay lo reproduce solo.
        syncOutbox.replayNow(venueId) { intent, ack ->
            if (intent.id == intentId) capturedAck = ack
        }

        val ack = capturedAck
        return when {
            // Sin ack para NUESTRO intent: o no hay red (replayNow no lanzó,
            // solo no llegó a mandar nada), o un intent más viejo en el
            // mismo batch cortó en RETRY antes de llegar al nuestro. En
            // ambos casos el efecto es el mismo: sigue PENDING, se verá
            // como "guardado" — NUNCA como error (regla de oro offline).
            ack == null -> Result.success(PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
            ack.isAcked -> Result.success(
                PayCashOutcome(
                    paymentId = ack.result?.stringOrNull("paymentId"),
                    orderNumber = ack.result?.stringOrNull("orderNumber"),
                    queued = false,
                    // 🧾 `applyPayCash` (sync.mobile.service.ts) YA regresa
                    // `digitalReceipt: { accessKey, receiptUrl, autofacturaAvailable }`
                    // en el ack — el mismo shape que usan avoqado-android/ios. Antes de
                    // este cambio el cliente TPV lo descartaba por completo (solo leía
                    // paymentId/orderNumber), así que un cobro en EFECTIVO de Mesas
                    // nunca tenía forma de mostrar/compartir su recibo digital ni el
                    // acceso a autofactura — el hueco que este cambio cierra.
                    receiptUrl = ack.result?.jsonObjectOrNull("digitalReceipt")?.stringOrNull("receiptUrl"),
                    autofacturaAvailable = ack.result?.jsonObjectOrNull("digitalReceipt")?.booleanOrNull("autofacturaAvailable") ?: false,
                ),
            )
            ack.isRejected -> Result.failure(PayCashRejectedException(ack.message ?: "El cobro fue rechazado", ack.errorCode))
            // RETRY (VERSION_CONFLICT u otro transitorio): SyncOutbox.applyAck
            // ya lo dejó PENDING — nunca se convierte en error ni se pierde.
            else -> Result.success(PayCashOutcome(paymentId = null, orderNumber = null, queued = true))
        }
    }

    /**
     * "Liberar mesa" — Plan C, Task 8. Mismo patrón idempotente-online-u-offline
     * que [openTable]: `tableController.clearTable` es idempotente (liberar una
     * mesa ya libre es un no-op), así que no necesita write-ahead — un
     * DROP_RESPONSE que reintente offline sobre la MISMA mesa nunca duplica
     * nada. El server RECHAZA la liberación si queda una cuenta sin pagar
     * (`applyClearTable`/`table.tpv.service.ts::clearTable`) — ese rechazo se
     * propaga tal cual, nunca se disfraza de éxito.
     */
    suspend fun clearTable(venueId: String, tableId: String): Result<Unit> {
        val onlineResult = try {
            val response = api.clearTable(venueId, tableId)
            if (response.isSuccessful) {
                Result.success(Unit)
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
            Timber.w(e, "⚠️ [Tables] Sin red en clearTable (venue=%s, table=%s) — se liberará offline", venueId, tableId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en clearTable (venue=%s, table=%s)", venueId, tableId)
            Result.failure(e)
        }

        onlineResult.onSuccess { return Result.success(Unit) }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply { addProperty("tableId", tableId) }
                syncOutbox.enqueue(venueId, SyncIntentTypes.CLEAR_TABLE, payload)
                Timber.i("📤 [Tables] Sin conexión — CLEAR_TABLE encolado (venue=%s, table=%s)", venueId, tableId)
                Result.success(Unit)
            }

            else -> onlineResult
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 1 (2026-08-03) — SPLIT_ORDER · APPLY_DISCOUNT · COMP_ORDER · MOVE_ORDER
    // Los 4 intents que la auditoría de completitud encontró sin UI. Ver
    // `.superpowers/sdd/2026-07-24-tpv-plan-c-modulo-mesas/completeness-audit.md`.
    // ══════════════════════════════════════════════════════════════════

    /**
     * "Separar cuenta" — `POST tpv/venues/{venueId}/orders/{orderId}/split`.
     * Solo alcanzable desde [com.jaac.avoqado_tpv.features.tables.presentation.TableOrderScreen]
     * con el cheque YA cargado (nunca en sesión provisional — no hay items
     * que mostrar todavía, ver KDoc de `TableOrderViewModel.loadCheck`), así
     * que a diferencia de [openTable]/[payCash] este método siempre recibe un
     * `orderId` real.
     *
     * 🔴 TODO-O-NADA es una garantía del SERVER (`resolveItemIds` en
     * `sync.mobile.service.ts`: si falta una sola referencia, `REJECTED` con
     * `ITEMS_NOT_RESOLVED` — nunca separa el subconjunto que sí resolvió). El
     * cliente solo tiene que preservar esa garantía: manda [itemIds] completo
     * en UNA sola llamada/intent, nunca en llamadas por artículo.
     *
     * Sin write-ahead — igual razonamiento que [openTable]/[clearTable]: la
     * ruta online de split no acepta ninguna llave de idempotencia (a
     * diferencia de `PAY_CASH`), así que escribir el intent antes no cierra
     * ningún hueco adicional. Si la respuesta se pierde (`DROP_RESPONSE`) y
     * el reintento offline llega a reproducirse, el server ya no encuentra
     * los items bajo el `orderId` original (se movieron) → `REJECTED`
     * `ITEMS_NOT_RESOLVED`, cuarentena visible — confuso pero NUNCA duplica
     * dinero (nunca separa dos veces).
     */
    suspend fun splitOrder(venueId: String, orderId: String, itemIds: List<String>): Result<SplitOutcome> {
        if (itemIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("splitOrder requiere al menos un artículo"))
        }
        val onlineResult = try {
            api.splitOrder(venueId, orderId, SplitOrderRequest(itemIds)).toSplitOrderResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en splitOrder (venue=%s, order=%s) — se encolará", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en splitOrder (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { result ->
            return Result.success(
                SplitOutcome(
                    queued = false,
                    createdOrderId = result.created.id,
                    createdOrderNumber = result.created.orderNumber,
                    sourceVersion = result.source.version,
                ),
            )
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply {
                    addProperty("orderId", orderId)
                    add("itemRefs", gson.toJsonTree(itemIds))
                    addProperty("newLocalOrderId", UUID.randomUUID().toString())
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.SPLIT_ORDER, payload)
                Timber.i("📤 [Tables] Sin conexión — SPLIT_ORDER encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(SplitOutcome(queued = true, createdOrderId = null, createdOrderNumber = null, sourceVersion = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("splitOrder sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResponse>.toSplitOrderResult():
        Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResult> {
        val data = body()?.data
        return if (isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(statusCode = code(), message = parseBackendErrorMessage(errorBody()?.string(), message())),
            )
        }
    }

    /**
     * "Descuentos disponibles" — `GET tpv/venues/{venueId}/orders/{orderId}/discounts/available`.
     * Lectura pura, mismo criterio que [getTables]: nunca se encola. Solo
     * alcanzable con el cheque cargado (no provisional) — ver
     * [com.jaac.avoqado_tpv.features.tables.presentation.DiscountPickerSheet].
     */
    suspend fun getAvailableDiscounts(venueId: String, orderId: String): Result<List<AvailableDiscount>> = try {
        val response = api.getAvailableDiscounts(venueId, orderId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(
                data.map {
                    AvailableDiscount(
                        id = it.id,
                        name = it.name,
                        type = it.type,
                        value = it.value,
                        requiresApproval = it.requiresApproval,
                        estimatedSavings = it.estimatedSavings,
                    )
                },
            )
        } else {
            Result.failure(
                BackendHttpException(statusCode = response.code(), message = parseBackendErrorMessage(response.errorBody()?.string(), response.message())),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getAvailableDiscounts (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getAvailableDiscounts (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    }

    /**
     * "Aplicar descuento" — `POST tpv/venues/{venueId}/orders/{orderId}/discounts/apply`.
     * Aditivo y reversible desde el dashboard — no requiere confirmación de
     * dos pasos (los números exactos ya se muestran en
     * [com.jaac.avoqado_tpv.features.tables.presentation.DiscountPickerSheet]
     * antes de tocar la fila, que ya funciona como el "preview" — la regla de
     * doble-confirmación es del MCP, no de la TPV, pero el espíritu — nunca
     * aplicar dinero a ciegas — se respeta igual).
     *
     * `requiresApproval=true` — sin flujo de autorización de gerente en esta
     * versión de la TPV, la fila se deshabilita en la UI con la razón visible
     * (ver KDoc de `AvailableDiscountDto`); este método nunca se llama para
     * esos ids desde la UI actual, pero no los rechaza aquí tampoco — es una
     * decisión de presentación, no de esta capa.
     */
    suspend fun applyDiscount(venueId: String, orderId: String, discountId: String): Result<DiscountOutcome> {
        val onlineResult = try {
            api.applyDiscount(venueId, orderId, ApplyDiscountRequest(discountId = discountId)).toApplyDiscountResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en applyDiscount (venue=%s, order=%s) — se encolará", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en applyDiscount (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { result ->
            return Result.success(DiscountOutcome(queued = false, amount = result.amount, newOrderTotal = result.newOrderTotal))
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply {
                    addProperty("orderId", orderId)
                    addProperty("discountId", discountId)
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.APPLY_DISCOUNT, payload)
                Timber.i("📤 [Tables] Sin conexión — APPLY_DISCOUNT encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(DiscountOutcome(queued = true, amount = null, newOrderTotal = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("applyDiscount sin causa"))
        }
    }

    /**
     * "Quitar descuento aplicado" — `DELETE
     * tpv/venues/{venueId}/orders/{orderId}/discounts/{discountId}`. SIEMPRE
     * online — paridad Android 2026-08-06 (`paridad-android-tpv.md`,
     * Hallazgo #4): el contrato de 14 intents
     * (`avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.1) solo
     * incluye `APPLY_DISCOUNT`, nunca "quitar" — Android's
     * `TableOrderViewModel.removeDiscount` confirma la misma asimetría
     * (`requireOnline("quitar un descuento aplicado")`, sin equivalente
     * offline). Un fallo de red NUNCA se encola — mismo patrón ya establecido
     * en este archivo para cortesía de artículo puntual
     * ([ItemCompRequiresConnectionException]): se propaga como
     * [RemoveDiscountRequiresConnectionException] para que la UI lo
     * distinga de un rechazo de negocio real (orden pagada, descuento ya
     * removido por otro dispositivo, etc.) — nunca un error genérico.
     *
     * El equivalente "quitar cargo por servicio" (`DELETE
     * .../service-charges/{id}`) YA existe bajo `/tpv` desde el commit
     * `a0470a74` de `avoqado-server` — ver [removeServiceCharge] abajo, mismo
     * patrón exacto.
     */
    suspend fun removeDiscount(venueId: String, orderId: String, discountId: String): Result<DiscountOutcome> {
        val onlineResult = try {
            api.removeDiscount(venueId, orderId, discountId).toApplyDiscountResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en removeDiscount (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en removeDiscount (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { result ->
            return Result.success(DiscountOutcome(queued = false, amount = result.amount, newOrderTotal = result.newOrderTotal))
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> Result.failure(RemoveDiscountRequiresConnectionException())
            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("removeDiscount sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountResponse>.toApplyDiscountResult():
        Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.DiscountApplyResult> {
        val body = body()
        val data = body?.data
        return if (isSuccessful && body?.success == true && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(
                    statusCode = code(),
                    message = body?.error ?: parseBackendErrorMessage(errorBody()?.string(), message()),
                ),
            )
        }
    }

    /**
     * "Dar de cortesía" — `POST tpv/venues/{venueId}/orders/{orderId}/comp`.
     *
     * 🔴 La asimetría que manda aquí (regla dura,
     * `avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §5): cortesía
     * de TODA la cuenta ([itemIds] vacío) tiene equivalente offline (intent
     * `COMP_ORDER`, delega en `compWholeOrder` — ver
     * `sync.mobile.service.ts`); cortesía de artículo(s) puntual(es) YA
     * enviados ([itemIds] no vacío) es SIEMPRE online — el reducer de replay
     * NUNCA lee `itemIds`, así que encolarla aplicaría cortesía a la cuenta
     * ENTERA cuando por fin reproduzca, no solo a los artículos elegidos. Por
     * eso la rama con [itemIds] nunca llama [SyncOutbox.enqueue] pase lo que
     * pase — un fallo transitorio se propaga con un mensaje claro en vez de
     * disfrazarse de "guardado offline".
     */
    suspend fun compOrder(venueId: String, orderId: String, itemIds: List<String>, reason: String, staffId: String): Result<CompOutcome> {
        val onlineResult = callCompOrder(venueId, orderId, itemIds, reason, staffId)
        onlineResult.onSuccess { result ->
            return Result.success(CompOutcome(queued = false, total = result.total, version = result.version))
        }

        if (itemIds.isNotEmpty()) {
            val outcome = classifyTablesSyncFailure(onlineResult.exceptionOrNull())
            return Result.failure(
                if (outcome is TablesSyncOutcome.Retryable) {
                    ItemCompRequiresConnectionException()
                } else {
                    onlineResult.exceptionOrNull() ?: IllegalStateException("compOrder sin causa")
                },
            )
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply {
                    addProperty("orderId", orderId)
                    addProperty("reason", reason)
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.COMP_ORDER, payload)
                Timber.i("📤 [Tables] Sin conexión — COMP_ORDER (toda la cuenta) encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(CompOutcome(queued = true, total = null, version = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("compOrder sin causa"))
        }
    }

    private suspend fun callCompOrder(
        venueId: String,
        orderId: String,
        itemIds: List<String>,
        reason: String,
        staffId: String,
    ): Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.CompOrderResult> = try {
        val response = api.compOrder(venueId, orderId, CompOrderRequest(itemIds = itemIds, reason = reason, staffId = staffId))
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(
                BackendHttpException(statusCode = response.code(), message = parseBackendErrorMessage(response.errorBody()?.string(), response.message())),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en compOrder (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en compOrder (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    }

    /**
     * "Mover mesa" — Fase 1. Igual que [payCash], NO hay ruta online dedicada
     * bajo `/tpv` para mover una cuenta de mesa (`tableService.moveOrderToTable`
     * solo existe del lado del reducer de replay, verificado 2026-08-03 contra
     * `table.tpv.service.ts` — cero `router.post` con "move" en `tpv.routes.ts`).
     * `MOVE_ORDER` viaja SIEMPRE como intent — write-ahead + replay inmediato,
     * MISMO patrón que [payCash]: funciona online y offline con el mismo
     * código, y la idempotencia de `[venueId, idempotencyKey]` a nivel de
     * `sync/intents` (no solo la de `PAY_CASH`) hace que reproducir el mismo
     * intent nunca mueva la cuenta dos veces.
     */
    suspend fun moveOrder(venueId: String, orderId: String, isProvisional: Boolean, targetTableId: String): Result<MoveOutcome> {
        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            if (isProvisional) addProperty("localOrderId", orderId) else addProperty("orderId", orderId)
            addProperty("targetTableId", targetTableId)
        }
        syncOutbox.enqueue(venueId, SyncIntentTypes.MOVE_ORDER, payload, id = intentId)

        var capturedAck: SyncIntentAck? = null
        syncOutbox.replayNow(venueId) { intent, ack ->
            if (intent.id == intentId) capturedAck = ack
        }

        val ack = capturedAck
        return when {
            ack == null -> Result.success(MoveOutcome(queued = true, targetTableId = targetTableId, version = null))
            ack.isAcked -> Result.success(MoveOutcome(queued = false, targetTableId = targetTableId, version = ack.result?.intOrNull("version")))
            ack.isRejected -> Result.failure(MoveOrderRejectedException(ack.message ?: "No se pudo mover la mesa", ack.errorCode))
            else -> Result.success(MoveOutcome(queued = true, targetTableId = targetTableId, version = null))
        }
    }

    /** Rechazo de NEGOCIO de un `MOVE_ORDER` (ack `REJECTED`) — viaja dentro de un 200 de `sync/intents`, no es HTTP. */
    class MoveOrderRejectedException(message: String, val errorCode: String?) : Exception(message)

    // ══════════════════════════════════════════════════════════════════
    // Fase 2 (2026-08-03) — MERGE_ORDERS · CANCEL_ORDER · ASSIGN_ORDER
    // Las 3 acciones que quedaban de la auditoría de completitud original
    // (10 huecos; Fase 1 cerró 4). Ver `.superpowers/sdd/2026-07-24-tpv-plan-c-modulo-mesas/fase-2-report.md`.
    // ══════════════════════════════════════════════════════════════════

    /**
     * "Fusionar cuentas" — `POST tpv/venues/{venueId}/orders/{orderId}/merge`.
     * Body `{sourceOrderId}` — la cuenta de ESTA pantalla ([targetOrderId], el
     * DESTINO que sobrevive) absorbe [sourceOrderId] (el ORIGEN, elegido en
     * [com.jaac.avoqado_tpv.features.tables.presentation.MergeOrdersSheet] de
     * una mesa OCUPADA distinta) y el origen queda `CANCELLED`.
     *
     * Mismo criterio que [splitOrder]: solo alcanzable con AMBAS cuentas
     * REALES — el target porque la sesión no puede ser provisional (mismo
     * gate que `hasSentItems`/`!session.isProvisional` en el overflow menu),
     * el source porque siempre viene de [com.jaac.avoqado_tpv.features.tables.presentation.TableOrderViewModel.loadMergeCandidateTables]
     * ([getTables], que solo lista cuentas que el server YA conoce — nunca un
     * UUID local). Por eso, a diferencia de [moveOrder]/[cancelOrder]/[assignOrder],
     * este método nunca necesita `isProvisional` ni `localOrderId`.
     *
     * Sin write-ahead — mismo razonamiento que [splitOrder]: la ruta online no
     * acepta ninguna llave de idempotencia. Si la respuesta se pierde
     * (DROP_RESPONSE) y el replay offline reproduce el intent encolado, el
     * server revalida AMBAS cuentas DENTRO de la transacción
     * (`orderMobileService.mergeOrders`, verificado 2026-08-03) — la de
     * origen ya quedó `CANCELLED` por el primer intento, así que el segundo
     * se rechaza ("La cuenta origen ya está cerrada", permanente →
     * cuarentena). Confuso, pero NUNCA fusiona dos veces ni duplica dinero.
     *
     * El server también rechaza (permanente) si el origen tiene descuentos o
     * cargos por servicio MANUALES sin quitar primero, o si cualquiera de las
     * dos cuentas ya tiene un pago — esos mensajes se propagan tal cual, esta
     * función no los reinterpreta.
     */
    suspend fun mergeOrders(venueId: String, targetOrderId: String, sourceOrderId: String): Result<MergeOutcome> {
        if (sourceOrderId.isBlank()) {
            return Result.failure(IllegalArgumentException("mergeOrders requiere sourceOrderId"))
        }
        val onlineResult = try {
            api.mergeOrders(venueId, targetOrderId, MergeOrdersRequest(sourceOrderId)).toMergeOrdersResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en mergeOrders (venue=%s, target=%s, source=%s) — se encolará", venueId, targetOrderId, sourceOrderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en mergeOrders (venue=%s, target=%s, source=%s)", venueId, targetOrderId, sourceOrderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { result ->
            return Result.success(
                MergeOutcome(
                    queued = false,
                    targetTotal = result.target.total,
                    mergedOrderNumber = result.merged.orderNumber,
                    tableFreed = result.tableFreed,
                ),
            )
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply {
                    addProperty("orderId", targetOrderId)
                    addProperty("sourceOrderId", sourceOrderId)
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.MERGE_ORDERS, payload)
                Timber.i("📤 [Tables] Sin conexión — MERGE_ORDERS encolado (venue=%s, target=%s, source=%s)", venueId, targetOrderId, sourceOrderId)
                Result.success(MergeOutcome(queued = true, targetTotal = null, mergedOrderNumber = null, tableFreed = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("mergeOrders sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResponse>.toMergeOrdersResult():
        Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResult> {
        val data = body()?.data
        return if (isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(BackendHttpException(statusCode = code(), message = parseBackendErrorMessage(errorBody()?.string(), message())))
        }
    }

    /**
     * "Cancelar cuenta" — online-primero con write-ahead (Fix "mesa fantasma"
     * en cancelación, 2026-08-07). Hasta esta fecha viajaba SIEMPRE como
     * intent porque no existía ninguna ruta online bajo `/tpv` para cancelar
     * una orden (verificado 2026-08-03 contra `tpv.routes.ts` — cero
     * `router.*` con "cancel" para `orders/:orderId`). ¿Por qué agregarla
     * ahora? El server ya cerró la "mesa fantasma" de `mergeOrders`
     * reconciliando por `tableId` en vez de `Table.currentOrderId` — pero
     * `cancelOrder` tiene el MISMO hueco (una cuenta hija de SPLIT_ORDER
     * nunca tiene `currentOrderId` apuntándole) y hasta ahora NO había
     * superficie `/tpv` no-congelada donde reconciliar, porque cancelar
     * SIEMPRE pasaba por el reducer congelado. `POST
     * tpv/venues/{venueId}/orders/{orderId}/cancel` (`orderTableController.cancelOrder`)
     * cierra ese hueco.
     *
     * Mismo patrón que [addItems] (write-ahead + online-primero, NO el de
     * [moveOrder]/[assignOrder] que siempre pasan por `sync/intents`):
     *
     * 1. [SyncOutbox.enqueue] escribe el intent PRIMERO (write-ahead) — si la
     *    respuesta online se pierde (DROP_RESPONSE), el intent ya existe y el
     *    próximo replay lo reproduce. `cancelOrder` es idempotente del lado
     *    del server (poner `status: CANCELLED` dos veces no duplica nada, a
     *    diferencia de un cobro), pero write-ahead sigue siendo la regla dura
     *    del repo para cualquier escritura online-primero.
     * 2. Intenta la ruta online. Éxito → [SyncOutbox.discardPending]: el
     *    intent write-ahead ya no hace falta.
     * 3. Fallo de RED ([TablesSyncOutcome.Retryable]) → se deja el intent
     *    PENDING tal cual, se reproduce solo al reconectar.
     * 4. Rechazo del server ([TablesSyncOutcome.Propagate] — p.ej. cuenta ya
     *    pagada, o cobro de terminal en curso) → se descarta el intent
     *    write-ahead (nunca se debe reproducir un rechazo permanente) y se
     *    propaga el [BackendHttpException] tal cual.
     *
     * Sesión PROVISIONAL (mesa recién abierta offline, [orderId] es un UUID
     * local que el server nunca vio): la ruta online exige un id REAL en la
     * URL, así que se salta directo al intent — mismo comportamiento de
     * siempre, el reducer resuelve `localOrderId` → orderId real al
     * reproducir.
     *
     * Al aplicarse libera la mesa (o la re-apunta a un cheque hermano si hay
     * multi-cheque) — el caller ([com.jaac.avoqado_tpv.features.tables.presentation.TableOrderViewModel.cancelOrder])
     * es quien limpia [TableSession] al ver éxito; esta función solo reporta
     * el resultado, nunca toca la sesión.
     *
     * Requiere el permiso `orders:cancel` — NO todo rol lo tiene por default
     * (WAITER/CASHIER no; MANAGER+ sí, ver `avoqado-server/src/lib/permissions.ts`).
     * Sin permiso: online devuelve 403 ([BackendHttpException]); reproducido
     * offline vuelve `REJECTED PERMISSION_DENIED` ([CancelOrderRejectedException]),
     * visible en cuarentena — el botón nunca se oculta por rol en esta
     * pantalla (mismo criterio que el resto de Fase 1/2: el server es el
     * candado real, esta UI no duplica esa lógica).
     */
    suspend fun cancelOrder(venueId: String, orderId: String, isProvisional: Boolean, reason: String?): Result<CancelOutcome> {
        if (isProvisional) {
            return cancelOrderViaIntent(venueId, orderId, reason)
        }

        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("orderId", orderId)
            if (!reason.isNullOrBlank()) addProperty("reason", reason)
        }
        // Write-ahead: el intent existe ANTES de intentar la ruta online.
        syncOutbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, payload, id = intentId)

        val onlineResult = callCancelOrder(venueId, orderId, reason)
        if (onlineResult.isSuccess) {
            // El intento online SÍ llegó — el intent write-ahead era solo la
            // red de seguridad, se descarta para que el outbox no lo reproduzca.
            syncOutbox.discardPending(intentId)
            return Result.success(CancelOutcome(queued = false))
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                // El intent ya está escrito — nada más que hacer, el próximo
                // replay lo reproduce solo.
                Timber.i("📤 [Tables] Sin conexión — CANCEL_ORDER sigue encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(CancelOutcome(queued = true))
            }

            else -> {
                // El server rechazó (o el fallo es desconocido) — nada que
                // reproducir: se descarta el intent write-ahead y se propaga
                // el error tal cual, NUNCA se deja pendiente.
                syncOutbox.discardPending(intentId)
                Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("cancelOrder sin causa"))
            }
        }
    }

    private suspend fun callCancelOrder(venueId: String, orderId: String, reason: String?): Result<Unit> = try {
        val response = api.cancelOrder(venueId, orderId, CancelOrderRequest(reason = reason))
        if (response.isSuccessful) {
            Result.success(Unit)
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
        Timber.w(e, "⚠️ [Tables] Sin red en cancelOrder (venue=%s, order=%s) — se encolará", venueId, orderId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en cancelOrder (venue=%s, order=%s)", venueId, orderId)
        Result.failure(e)
    }

    /**
     * Sesión provisional: [orderId] es un UUID local que el server nunca vio,
     * así que la ruta online (que exige un id real en la URL) no aplica — se
     * salta directo al intent, mismo mecanismo write-ahead + replay inmediato
     * que [moveOrder]/[assignOrder]. Único caller: [cancelOrder] con
     * `isProvisional = true` — por eso el payload siempre manda `localOrderId`,
     * nunca `orderId`.
     */
    private suspend fun cancelOrderViaIntent(venueId: String, orderId: String, reason: String?): Result<CancelOutcome> {
        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            addProperty("localOrderId", orderId)
            if (!reason.isNullOrBlank()) addProperty("reason", reason)
        }
        syncOutbox.enqueue(venueId, SyncIntentTypes.CANCEL_ORDER, payload, id = intentId)

        var capturedAck: SyncIntentAck? = null
        syncOutbox.replayNow(venueId) { intent, ack ->
            if (intent.id == intentId) capturedAck = ack
        }

        val ack = capturedAck
        return when {
            ack == null -> Result.success(CancelOutcome(queued = true))
            ack.isAcked -> Result.success(CancelOutcome(queued = false))
            ack.isRejected -> Result.failure(CancelOrderRejectedException(ack.message ?: "No se pudo cancelar la cuenta", ack.errorCode))
            // RETRY (VERSION_CONFLICT u otro transitorio): SyncOutbox.applyAck ya lo dejó PENDING.
            else -> Result.success(CancelOutcome(queued = true))
        }
    }

    /** Rechazo de NEGOCIO de un `CANCEL_ORDER` (ack `REJECTED`) — viaja dentro de un 200 de `sync/intents`, no es HTTP. */
    class CancelOrderRejectedException(message: String, val errorCode: String?) : Exception(message)

    /**
     * "Reasignar mesero" — mismo patrón write-ahead + replay inmediato, mismo
     * motivo que [cancelOrder]: sin ruta online bajo `/tpv` (la única
     * existente, `assignOrderWaiter` vía `table.mobile.controller.ts`, vive en
     * `/mobile` — fuera de alcance para la TPV, regla dura "la TPV habla solo
     * con /api/v1/tpv").
     *
     * 🔴 Esto es lo que más importa de las 3 acciones de esta fase: la
     * atribución de propina/comisión de la plataforma cuelga de `servedById`
     * (`avoqado-server/src/services/tpv/table.tpv.service.ts::assignOrderWaiter`)
     * — un [staffId] equivocado no solo "se ve mal", desvía dinero de una
     * persona a otra. [staffId] SIEMPRE debe venir de una fila tocada en
     * [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet]
     * (poblada por [getActiveStaff]) — nunca tecleado a mano ni adivinado
     * aquí; esta función solo valida que no venga vacío.
     */
    suspend fun assignOrder(venueId: String, orderId: String, isProvisional: Boolean, staffId: String): Result<AssignOutcome> {
        if (staffId.isBlank()) {
            return Result.failure(IllegalArgumentException("assignOrder requiere staffId"))
        }
        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            if (isProvisional) addProperty("localOrderId", orderId) else addProperty("orderId", orderId)
            addProperty("staffId", staffId)
        }
        syncOutbox.enqueue(venueId, SyncIntentTypes.ASSIGN_ORDER, payload, id = intentId)

        var capturedAck: SyncIntentAck? = null
        syncOutbox.replayNow(venueId) { intent, ack ->
            if (intent.id == intentId) capturedAck = ack
        }

        val ack = capturedAck
        return when {
            ack == null -> Result.success(AssignOutcome(queued = true, version = null))
            ack.isAcked -> Result.success(AssignOutcome(queued = false, version = ack.result?.intOrNull("version")))
            ack.isRejected -> Result.failure(AssignOrderRejectedException(ack.message ?: "No se pudo reasignar la cuenta", ack.errorCode))
            else -> Result.success(AssignOutcome(queued = true, version = null))
        }
    }

    /** Rechazo de NEGOCIO de un `ASSIGN_ORDER` (ack `REJECTED`) — viaja dentro de un 200 de `sync/intents`, no es HTTP. */
    class AssignOrderRejectedException(message: String, val errorCode: String?) : Exception(message)

    /**
     * "Personal asignable" — `GET tpv/venues/{venueId}/staff/assignable`.
     * Lectura pura, mismo criterio que [getTables]: nunca se encola. Fuente
     * del picker de [com.jaac.avoqado_tpv.features.tables.presentation.AssignWaiterSheet]
     * — gateada `orders:update` (el MISMO permiso que exige `ASSIGN_ORDER`,
     * WAITER lo tiene por default). Ver KDoc de
     * [com.jaac.avoqado_tpv.features.tables.data.api.dto.ActiveStaffResponse]
     * para la historia (Fix 2, 2026-08-07): antes apuntaba a
     * `time-entries/active`, MANAGER+, que dejaba a un mesero reasignar sin
     * poder ver a quién.
     */
    suspend fun getActiveStaff(venueId: String): Result<List<ActiveStaffMember>> = try {
        val response = api.getActiveStaff(venueId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(
                data.filter { it.staffId.isNotBlank() }.map {
                    ActiveStaffMember(
                        staffId = it.staffId,
                        name = "${it.staff.firstName} ${it.staff.lastName}".trim(),
                        onBreak = it.status == "ON_BREAK",
                        employeeCode = it.staff.employeeCode,
                    )
                },
            )
        } else {
            Result.failure(
                BackendHttpException(statusCode = response.code(), message = parseBackendErrorMessage(response.errorBody()?.string(), response.message())),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getActiveStaff (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getActiveStaff (venue=%s)", venueId)
        Result.failure(e)
    }

    // ══════════════════════════════════════════════════════════════════
    // Fase 3 (2026-08-03) — SPLIT_BY_SEAT · APPLY_SERVICE_CHARGE · UPDATE_DETAILS
    // Los últimos 3 intents de los 14 — cierran la auditoría de completitud
    // original. Ver `.superpowers/sdd/2026-07-24-tpv-plan-c-modulo-mesas/fase-3-report.md`.
    // ══════════════════════════════════════════════════════════════════

    /**
     * "Cargos por servicio disponibles" — `GET tpv/venues/{venueId}/service-charges`
     * (🆕 companion de lectura agregado en esta fase — ver KDoc de
     * [com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService.getServiceCharges]).
     * Lectura pura, mismo criterio que [getAvailableDiscounts]: nunca se encola.
     */
    suspend fun getAvailableServiceCharges(venueId: String): Result<List<AvailableServiceCharge>> = try {
        val response = api.getServiceCharges(venueId)
        val data = response.body()?.data
        if (response.isSuccessful && data != null) {
            Result.success(
                data.map {
                    AvailableServiceCharge(
                        id = it.id,
                        name = it.name,
                        type = it.type,
                        value = it.value,
                        taxable = it.taxable,
                        autoApplyMinCovers = it.autoApplyMinCovers,
                    )
                },
            )
        } else {
            Result.failure(
                BackendHttpException(statusCode = response.code(), message = parseBackendErrorMessage(response.errorBody()?.string(), response.message())),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Timber.w(e, "⚠️ [Tables] Sin red en getAvailableServiceCharges (venue=%s)", venueId)
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e(e, "❌ [Tables] Fallo inesperado en getAvailableServiceCharges (venue=%s)", venueId)
        Result.failure(e)
    }

    /**
     * "Aplicar cargo por servicio" — `POST tpv/venues/{venueId}/orders/{orderId}/service-charges`.
     * Manual override del auto-apply por comensales (`ServiceCharge.autoApplyMinCovers`)
     * — vive en "Cobrar" ([com.jaac.avoqado_tpv.features.tables.presentation.TableCheckoutViewModel],
     * no [TableOrderViewModel]) por el MISMO motivo que [applyDiscount]: afecta el
     * TOTAL que se está por cobrar, y solo se alcanza con el cheque YA cargado
     * (`orderId` siempre real, nunca un UUID local — mismo criterio que [applyDiscount]/[splitOrder]).
     *
     * Online-first, se encola (`APPLY_SERVICE_CHARGE`) si la falla es transitoria
     * — mismo patrón exacto que [applyDiscount]. Sin write-ahead: la ruta online
     * no acepta ninguna llave de idempotencia; el server rechaza (400, "Ese cobro
     * ya está aplicado a la cuenta") un DROP_RESPONSE reproducido dos veces —
     * cuarentena visible, nunca cobra el cargo por servicio dos veces.
     */
    suspend fun applyServiceCharge(venueId: String, orderId: String, serviceChargeId: String): Result<ServiceChargeOutcome> {
        if (serviceChargeId.isBlank()) {
            return Result.failure(IllegalArgumentException("applyServiceCharge requiere serviceChargeId"))
        }
        val onlineResult = try {
            api.applyServiceCharge(venueId, orderId, ApplyServiceChargeRequest(serviceChargeId = serviceChargeId)).toApplyServiceChargeResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en applyServiceCharge (venue=%s, order=%s) — se encolará", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en applyServiceCharge (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { totals ->
            return Result.success(ServiceChargeOutcome(queued = false, total = totals.total, serviceChargeAmount = totals.serviceChargeAmount))
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply {
                    addProperty("orderId", orderId)
                    addProperty("serviceChargeId", serviceChargeId)
                }
                syncOutbox.enqueue(venueId, SyncIntentTypes.APPLY_SERVICE_CHARGE, payload)
                Timber.i("📤 [Tables] Sin conexión — APPLY_SERVICE_CHARGE encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(ServiceChargeOutcome(queued = true, total = null, serviceChargeAmount = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("applyServiceCharge sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeResponse>.toApplyServiceChargeResult():
        Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderTotals> {
        val data = body()?.data
        return if (isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(BackendHttpException(statusCode = code(), message = parseBackendErrorMessage(errorBody()?.string(), message())))
        }
    }

    data class ServiceChargeOutcome(val queued: Boolean, val total: BigDecimal?, val serviceChargeAmount: BigDecimal?)

    /**
     * "Quitar cargo por servicio YA aplicado" — `DELETE
     * tpv/venues/{venueId}/orders/{orderId}/service-charges/{orderServiceChargeId}`.
     * Cierra el ÚLTIMO hueco de paridad con Android
     * (`paridad-android-tpv.md`, Hallazgo #4, 2026-08-06) — hasta el commit
     * `a0470a74` de `avoqado-server` el servicio existía
     * (`service-charge.mobile.service.ts::removeServiceCharge`) pero la ruta
     * DELETE solo estaba montada bajo `/mobile`; ahora existe bajo `/tpv`,
     * espejo BYTE-IDÉNTICO de esa cadena incluido `checkTableOwnership('order')`
     * (deshacer un cargo en la mesa de otro mesero es exactamente el cruce
     * que ese guard existe para impedir).
     *
     * SIEMPRE online — MISMO razonamiento que [removeDiscount]: no existe
     * intent `REMOVE_SERVICE_CHARGE` en el contrato de 14 tipos
     * (`avoqado-server/.claude/rules/offline-first-y-hub-lan.md` §2.1/§5) —
     * aplicar un cargo se puede encolar (`APPLY_SERVICE_CHARGE`, ver
     * [applyServiceCharge]); quitarlo no. Un fallo de red NUNCA se encola ni
     * se disfraza de "guardado" — se propaga como
     * [RemoveServiceChargeRequiresConnectionException] con un mensaje
     * explícito, mismo patrón que [RemoveDiscountRequiresConnectionException].
     */
    suspend fun removeServiceCharge(venueId: String, orderId: String, orderServiceChargeId: String): Result<ServiceChargeOutcome> {
        val onlineResult = try {
            api.removeServiceCharge(venueId, orderId, orderServiceChargeId).toApplyServiceChargeResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en removeServiceCharge (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en removeServiceCharge (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { totals ->
            return Result.success(ServiceChargeOutcome(queued = false, total = totals.total, serviceChargeAmount = totals.serviceChargeAmount))
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> Result.failure(RemoveServiceChargeRequiresConnectionException())
            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("removeServiceCharge sin causa"))
        }
    }

    /**
     * "Dividir por puesto" — `POST tpv/venues/{venueId}/orders/{orderId}/split-by-seat`.
     * Sin body: el servicio agrupa los items YA enviados por `seat` — el asiento
     * más bajo se queda en la cuenta original, el resto se mueve a cuentas nuevas
     * (una por asiento), TODO dentro de una sola `prisma.$transaction` del lado
     * del server (`orderMobileService.splitOrderBySeat`) — o se dividen TODOS los
     * asientos, o ninguno, mismo principio todo-o-nada que [splitOrder]/[mergeOrders]
     * (contrato rules §2.5: "SPLIT_ORDER resuelve las referencias todo-o-nada").
     * Aquí no hay ninguna referencia (`itemIds`) que el cliente arme — la única
     * que existe es `orderId` mismo, resuelto una sola vez por
     * [com.jaac.avoqado_tpv.features.tables.presentation.TableOrderViewModel]
     * ANTES de llamar, así que "todo o nada" lo garantiza la transacción del
     * server sin que este método necesite ensamblar ni fragmentar nada — UNA
     * sola llamada, nunca una por asiento.
     *
     * ⚠️ Gap real documentado (no arreglable desde este repo): `addOrderItemsSchema`
     * del server (`avoqado-server/src/schemas/tpv.schema.ts:334`) NO acepta
     * `seat` al mandar una ronda por `/tpv` — verificado 2026-08-03 contra el
     * Zod real. Una cuenta que SOLO recibió rondas desde esta TPV nunca tiene
     * items con `seat` asignado, así que el server siempre rechaza con "Se
     * necesitan al menos dos asientos con artículos para dividir por puesto"
     * (`order.mobile.service.ts:1320`) — un rechazo de negocio limpio, propagado
     * tal cual, NUNCA un crash. La acción es real y queda reachable para el día
     * en que otro cliente (Android/iOS, que SÍ soportan `seat`) sembró los
     * asientos de esta cuenta antes de que la TPV la cobre.
     *
     * Sin write-ahead — mismo razonamiento que [splitOrder]/[mergeOrders]: la
     * ruta online no acepta ninguna llave de idempotencia. Un DROP_RESPONSE
     * reproducido encuentra los items YA movidos → mismo rechazo de "menos de
     * dos asientos" en el segundo intento, cuarentena visible, nunca divide dos
     * veces.
     */
    suspend fun splitOrderBySeat(venueId: String, orderId: String): Result<SplitBySeatOutcome> {
        val onlineResult = try {
            api.splitOrderBySeat(venueId, orderId).toSplitBySeatResult()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "⚠️ [Tables] Sin red en splitOrderBySeat (venue=%s, order=%s) — se encolará", venueId, orderId)
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "❌ [Tables] Fallo inesperado en splitOrderBySeat (venue=%s, order=%s)", venueId, orderId)
            Result.failure(e)
        }

        onlineResult.onSuccess { result ->
            return Result.success(
                SplitBySeatOutcome(queued = false, createdCount = result.created.size, sourceSeat = result.source.seat),
            )
        }

        return when (classifyTablesSyncFailure(onlineResult.exceptionOrNull())) {
            is TablesSyncOutcome.Retryable -> {
                val payload = JsonObject().apply { addProperty("orderId", orderId) }
                syncOutbox.enqueue(venueId, SyncIntentTypes.SPLIT_BY_SEAT, payload)
                Timber.i("📤 [Tables] Sin conexión — SPLIT_BY_SEAT encolado (venue=%s, order=%s)", venueId, orderId)
                Result.success(SplitBySeatOutcome(queued = true, createdCount = null, sourceSeat = null))
            }

            else -> Result.failure(onlineResult.exceptionOrNull() ?: IllegalStateException("splitOrderBySeat sin causa"))
        }
    }

    private fun Response<com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderBySeatResponse>.toSplitBySeatResult():
        Result<com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitBySeatResult> {
        val data = body()?.data
        return if (isSuccessful && data != null) {
            Result.success(data)
        } else {
            Result.failure(BackendHttpException(statusCode = code(), message = parseBackendErrorMessage(errorBody()?.string(), message())))
        }
    }

    data class SplitBySeatOutcome(val queued: Boolean, val createdCount: Int?, val sourceSeat: Int?)

    /**
     * "Editar detalles de la cuenta" (comensales, nombre) — SIEMPRE vía intent,
     * mismo patrón write-ahead + replay inmediato que [moveOrder]/[cancelOrder]/
     * [assignOrder]: **cero ruta online** bajo `/tpv` para `UPDATE_DETAILS`
     * (verificado 2026-08-03 — `orderMobileService.updateOrderDetails` solo
     * existe del lado del reducer de replay, sin ningún `router.*` que la llame
     * en `tpv.routes.ts`). Funciona con sesión provisional también (mismo
     * criterio que `moveOrder`: `resolveOrderId` del reducer acepta `localOrderId`
     * para los 7 tipos que delegan en `applyOrderMutation`).
     *
     * Aditivo y parcial: solo los campos no-null cambian (`name`/`covers` en el
     * payload) — el server (`updateOrderDetails`) trata null/ausente como "no
     * tocar", nunca como "borrar". Al menos uno de los dos debe venir para que
     * la llamada tenga sentido (guard abajo).
     */
    suspend fun updateOrderDetails(
        venueId: String,
        orderId: String,
        isProvisional: Boolean,
        name: String?,
        covers: Int?,
    ): Result<UpdateDetailsOutcome> {
        if (name == null && covers == null) {
            return Result.failure(IllegalArgumentException("updateOrderDetails requiere al menos un campo a cambiar"))
        }
        val intentId = UUID.randomUUID().toString()
        val payload = JsonObject().apply {
            if (isProvisional) addProperty("localOrderId", orderId) else addProperty("orderId", orderId)
            if (name != null) addProperty("name", name)
            if (covers != null) addProperty("covers", covers)
        }
        syncOutbox.enqueue(venueId, SyncIntentTypes.UPDATE_DETAILS, payload, id = intentId)

        var capturedAck: SyncIntentAck? = null
        syncOutbox.replayNow(venueId) { intent, ack ->
            if (intent.id == intentId) capturedAck = ack
        }

        val ack = capturedAck
        return when {
            ack == null -> Result.success(UpdateDetailsOutcome(queued = true))
            ack.isAcked -> Result.success(UpdateDetailsOutcome(queued = false))
            ack.isRejected -> Result.failure(UpdateDetailsRejectedException(ack.message ?: "No se pudieron actualizar los detalles", ack.errorCode))
            // RETRY (VERSION_CONFLICT u otro transitorio): SyncOutbox.applyAck ya lo dejó PENDING.
            else -> Result.success(UpdateDetailsOutcome(queued = true))
        }
    }

    /** Rechazo de NEGOCIO de un `UPDATE_DETAILS` (ack `REJECTED`) — viaja dentro de un 200 de `sync/intents`, no es HTTP. */
    class UpdateDetailsRejectedException(message: String, val errorCode: String?) : Exception(message)

    data class UpdateDetailsOutcome(val queued: Boolean)

    data class MergeOutcome(val queued: Boolean, val targetTotal: BigDecimal?, val mergedOrderNumber: String?, val tableFreed: Boolean?)

    data class CancelOutcome(val queued: Boolean)

    data class AssignOutcome(val queued: Boolean, val version: Int?)

    /** Cortesía de un artículo puntual falló y NO es un rechazo de negocio del server — nunca se encola, ver KDoc de [compOrder]. */
    class ItemCompRequiresConnectionException :
        Exception("La cortesía de artículos ya enviados necesita conexión — inténtalo cuando el equipo esté en línea.")

    /** Quitar un descuento aplicado falló y NO es un rechazo de negocio del server — nunca se encola, ver KDoc de [removeDiscount]. */
    class RemoveDiscountRequiresConnectionException :
        Exception("Quitar un descuento aplicado necesita conexión — inténtalo cuando el equipo esté en línea.")

    /** Quitar un cargo por servicio aplicado falló y NO es un rechazo de negocio del server — nunca se encola, ver KDoc de [removeServiceCharge]. */
    class RemoveServiceChargeRequiresConnectionException :
        Exception("Quitar un cargo por servicio necesita conexión — inténtalo cuando el equipo esté en línea.")

    data class SplitOutcome(
        val queued: Boolean,
        val createdOrderId: String?,
        val createdOrderNumber: String?,
        val sourceVersion: Int?,
    )

    data class DiscountOutcome(val queued: Boolean, val amount: BigDecimal?, val newOrderTotal: BigDecimal?)

    data class CompOutcome(val queued: Boolean, val total: BigDecimal?, val version: Int?)

    data class MoveOutcome(val queued: Boolean, val targetTableId: String, val version: Int?)

    /** PESOS → centavos, SOLO para el wire de [payCash] — ver su KDoc. */
    private fun BigDecimal.toCents(): Int = this.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /**
     * Resultado de [payCash]. `queued=true` = escrito/sigue en el outbox, NUNCA
     * un error para la UI. `receiptUrl`/`autofacturaAvailable` solo llegan
     * pobladas cuando `queued=false` (el server ya generó el `digitalReceipt`
     * — un intent que sigue PENDING todavía no tiene uno).
     */
    data class PayCashOutcome(
        val paymentId: String?,
        val orderNumber: String?,
        val queued: Boolean,
        val receiptUrl: String? = null,
        val autofacturaAvailable: Boolean = false,
    )

    /** Rechazo de NEGOCIO de un `PAY_CASH` (ack `REJECTED`) — no es HTTP, viaja dentro de un 200 de `sync/intents`. */
    class PayCashRejectedException(message: String, val errorCode: String?) : Exception(message)

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

    /** Resultado de [openTable] — ver su KDoc para la semántica de cada campo. */
    data class TableOpenOutcome(
        val orderId: String,
        val orderNumber: String?,
        val version: Int,
        val isProvisional: Boolean,
    )

    companion object {
        private const val OPTIMISTIC_ITEM_ID_PREFIX = "pending-"

        /**
         * True si [detail] es el `OrderDetail` OPTIMISTA que [addItems] arma
         * cuando la ronda se encoló sin red (ver KDoc de `optimisticAddItemsResult`
         * arriba) — `TableOrderScreen` (Task 7) lo usa para pintar "Por
         * sincronizar" en vez de "Enviada a cocina", sin adivinar por su cuenta
         * cuál es la señal: el prefijo `"pending-"` en los ids de línea es el
         * contrato DOCUMENTADO de esa función, no un accidente de
         * implementación que la pantalla no debería conocer.
         */
        fun wasQueuedOffline(detail: OrderDetail): Boolean =
            detail.items.isNotEmpty() && detail.items.all { it.id.startsWith(OPTIMISTIC_ITEM_ID_PREFIX) }
    }
}
