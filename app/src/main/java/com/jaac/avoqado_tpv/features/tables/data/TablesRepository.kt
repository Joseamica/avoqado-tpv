package com.jaac.avoqado_tpv.features.tables.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jaac.avoqado_tpv.core.data.network.BackendHttpException
import com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddOrderItemRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenedOrder
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ProductCatalogDto
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncIntentTypes
import com.jaac.avoqado_tpv.features.tables.data.sync.SyncOutbox
import com.jaac.avoqado_tpv.features.tables.data.sync.TablesSyncOutcome
import com.jaac.avoqado_tpv.features.tables.data.sync.classifyTablesSyncFailure
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

    /** PESOS → centavos, SOLO para el wire de [payCash] — ver su KDoc. */
    private fun BigDecimal.toCents(): Int = this.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toInt()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** Resultado de [payCash]. `queued=true` = escrito/sigue en el outbox, NUNCA un error para la UI. */
    data class PayCashOutcome(
        val paymentId: String?,
        val orderNumber: String?,
        val queued: Boolean,
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
