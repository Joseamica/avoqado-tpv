package com.jaac.avoqado_tpv.features.tables.data.api

import com.jaac.avoqado_tpv.features.tables.data.api.dto.AddItemsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyDiscountResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ApplyServiceChargeResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.FloorElementsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.MergeOrdersResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OpenTableResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.OrderDetailResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SimpleSuccessResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderBySeatResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SplitOrderResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsRequest
import com.jaac.avoqado_tpv.features.tables.data.api.dto.SyncIntentsResponse
import com.jaac.avoqado_tpv.features.tables.data.api.dto.TablesResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit del módulo Mesas — **SOLO** `tpv/…` (regla dura del plan: "La TPV
 * habla solo con /api/v1/tpv"). `TablesApiServiceContractTest` falla si
 * alguna ruta se cuela con otro prefijo (`mobile/`, `dashboard/`).
 *
 * Separado a propósito del `ApiService.kt` legacy que usa
 * `features/ordering/` — el plan (D-3) prohíbe que `features/tables/` importe
 * de `features/ordering/` ni `features/checkout/`, así que este módulo tiene
 * su propio Retrofit aunque algunos endpoints coincidan en el server (p.ej.
 * `tpv/venues/{venueId}/tables`, que `ApiService.kt` también expone para el
 * plano legacy). Cada `@GET/@POST/@PATCH` está verificado 2026-07-28 contra
 * `avoqado-server/src/routes/tpv.routes.ts` — el KDoc de cada método cita el
 * controller real, no una suposición del plan.
 *
 * Todos los métodos usan `Response<T>` (no bodies "pelones") — mismo patrón
 * que `ApiService.kt` ya establecido en este repo, para que `TablesRepository`
 * (Task 4) pueda leer `response.code()`/`response.isSuccessful` y clasificar
 * fallas con `classifySyncFailure` (Plan A).
 */
interface TablesApiService {

    // ========== Plano y mesas ==========

    /**
     * `GET /tpv/venues/{venueId}/tables` → `tableController.getTables`
     * (`tpv.routes.ts:3499`). Sin `checkFeatureAccess` — leer el plano no
     * requiere TABLE_SERVICE, solo abrir/operar una mesa lo requiere.
     */
    @GET("tpv/venues/{venueId}/tables")
    suspend fun getTables(@Path("venueId") venueId: String): Response<TablesResponse>

    /**
     * `GET /tpv/venues/{venueId}/floor-elements` → `floorElementController.getFloorElements`
     * (`tpv.routes.ts:3650`).
     */
    @GET("tpv/venues/{venueId}/floor-elements")
    suspend fun getFloorElements(@Path("venueId") venueId: String): Response<FloorElementsResponse>

    /**
     * `POST /tpv/venues/{venueId}/tables/{tableId}/open` → `tableController.openTable`
     * (`tpv.routes.ts:3804`, `checkFeatureAccess('TABLE_SERVICE')`). Reusa la
     * cuenta activa si existe, o crea una orden DINE_IN vacía y marca la mesa
     * OCCUPIED. Body: `{ covers? }`.
     */
    @POST("tpv/venues/{venueId}/tables/{tableId}/open")
    suspend fun openTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
        @Body body: OpenTableRequest,
    ): Response<OpenTableResponse>

    /**
     * `POST /tpv/venues/{venueId}/tables/{tableId}/clear` → `tableController.clearTable`
     * (`tpv.routes.ts:3630`, `checkFeatureAccess('TABLE_SERVICE')`). Rechaza si
     * queda alguna cuenta abierta de la mesa sin pagar. Sin body.
     */
    @POST("tpv/venues/{venueId}/tables/{tableId}/clear")
    suspend fun clearTable(
        @Path("venueId") venueId: String,
        @Path("tableId") tableId: String,
    ): Response<SimpleSuccessResponse>

    // ========== Cuenta / cheque ==========

    /**
     * `GET /tpv/venues/{venueId}/orders/{orderId}` → `orderController.getOrder`
     * (`tpv.routes.ts:825`, `checkPermission('orders:read')`). El detalle
     * completo del cheque — usar para refrescar `TableOrderScreen` tras un
     * `VERSION_CONFLICT`.
     */
    @GET("tpv/venues/{venueId}/orders/{orderId}")
    suspend fun getOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
    ): Response<OrderDetailResponse>

    /**
     * `PATCH /tpv/venues/{venueId}/orders/{orderId}/items` → `orderController.addItemsToOrder`
     * (`tpv.routes.ts:3908`). 🔴 Es **PATCH**, no POST — el ejemplo del plan
     * (Task 2 Step 4) mostraba `@POST`; la ruta real registrada es
     * `router.patch(...)`. CAS real sobre `version` (409 si no coincide).
     */
    @PATCH("tpv/venues/{venueId}/orders/{orderId}/items")
    suspend fun addItems(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: AddItemsRequest,
    ): Response<OrderDetailResponse>

    /**
     * `POST /tpv/venues/{venueId}/orders/{orderId}/split` → `orderTableController.splitOrder`
     * (`tpv.routes.ts:3749`, `checkFeatureAccess('TABLE_SERVICE')` +
     * `checkPermission('orders:update')`). "Separar cuenta": mueve los items
     * seleccionados a una cuenta NUEVA en la misma mesa.
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/split")
    suspend fun splitOrder(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: SplitOrderRequest,
    ): Response<SplitOrderResponse>

    /**
     * `POST /tpv/venues/{venueId}/orders/{orderId}/split-by-seat` →
     * `orderTableController.splitOrderBySeat` (`tpv.routes.ts:3762`). "Dividir
     * por puesto": un cheque por asiento, atómico. Sin body.
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/split-by-seat")
    suspend fun splitOrderBySeat(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
    ): Response<SplitOrderBySeatResponse>

    /**
     * `POST /tpv/venues/{venueId}/orders/{orderId}/merge` → `orderTableController.mergeOrders`
     * (`tpv.routes.ts:3776`). "Fusionar cuentas": vuelca los items de
     * `sourceOrderId` en `orderId` (el destino) y cancela el origen.
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/merge")
    suspend fun mergeOrders(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: MergeOrdersRequest,
    ): Response<MergeOrdersResponse>

    /**
     * `POST /tpv/venues/{venueId}/orders/{orderId}/service-charges` →
     * `orderTableController.applyServiceCharge` (`tpv.routes.ts:3790`). Aplica
     * un cobro por servicio del catálogo (propina automática por comensales,
     * descorche...) — SUMA al total, es ingreso gravable (no confundir con propina).
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/service-charges")
    suspend fun applyServiceCharge(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: ApplyServiceChargeRequest,
    ): Response<ApplyServiceChargeResponse>

    /**
     * `POST /tpv/venues/{venueId}/orders/{orderId}/discounts/apply` →
     * `discountController.applyPredefinedDiscount` (`tpv.routes.ts:4806`,
     * `checkPermission('discounts:apply')`). 🔴 El ejemplo del plan (Task 2
     * Step 4) usaba `orders/{orderId}/discounts` — esa ruta NO EXISTE; la
     * familia real vive bajo `discounts/{apply,manual,coupon,auto}` (ver KDoc
     * de `ApplyDiscountRequest`). Solo se expone `apply` (descuento
     * predefinido) — es el único caso de uso de Mesas.
     */
    @POST("tpv/venues/{venueId}/orders/{orderId}/discounts/apply")
    suspend fun applyDiscount(
        @Path("venueId") venueId: String,
        @Path("orderId") orderId: String,
        @Body body: ApplyDiscountRequest,
    ): Response<ApplyDiscountResponse>

    // ========== Offline-first sync ==========

    /**
     * `POST /tpv/venues/{venueId}/sync/intents` → `syncTpvController.syncIntents`
     * (`tpv.routes.ts:3714`) — reexport BYTE-IDÉNTICO del reducer de `/mobile`
     * (`sync.mobile.service.ts`). Replay del outbox: batch FIFO por
     * dispositivo, un ack por intent. El gating de TABLE_SERVICE y la
     * propiedad de mesa se evalúan POR INTENT dentro del reducer — por eso
     * esta ruta NO lleva `checkFeatureAccess` a nivel de Express (rechazarla
     * ahí tumbaría un batch entero por un solo intent de mesa).
     */
    @POST("tpv/venues/{venueId}/sync/intents")
    suspend fun syncIntents(
        @Path("venueId") venueId: String,
        @Body body: SyncIntentsRequest,
    ): Response<SyncIntentsResponse>
}
