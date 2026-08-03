package com.jaac.avoqado_tpv.features.tables.domain.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/**
 * El detalle completo de una cuenta — espejo por campo de lo que devuelven
 * (envuelto en `{ success, data }`):
 *  - `GET /tpv/venues/{venueId}/orders/{orderId}` (`order.tpv.controller.ts::getOrder`
 *    → `order.tpv.service.ts::getOrder`) — el shape COMPLETO, con `amount_left`,
 *    `tableName`, `lastSplitType`, `paidItemIds`.
 *  - `PATCH /tpv/venues/{venueId}/orders/{orderId}/items` (`addItemsToOrder`) — el
 *    mismo `Order` con `tableName`, pero SIN `amount_left`/`lastSplitType`/
 *    `paidItemIds` (ese servicio no los calcula). Por eso esos 3 campos son
 *    nullable: ausentes en la respuesta de PATCH, presentes en la de GET.
 *
 * Verificado contra el server 2026-07-28 (Plan C Task 2) — NO se copió el
 * `OrderDetail` de `avoqado-android` (que mira `/mobile` y trae `discounts`/
 * `serviceCharges` como listas).
 *
 * 🔴 ACTUALIZADO 2026-07-29 (Task 8, commit server `a7d2f7b6`): el `include` de
 * Prisma en `order.tpv.service.ts::getOrder` YA selecciona `orderDiscounts`
 * (`{id, name, amount}`) y `serviceCharges` (`OrderServiceCharge` completo) —
 * verificado directo contra el servicio, no contra el comentario viejo de este
 * archivo (que decía lo contrario y ya estaba desactualizado). Son las listas
 * que permiten desglosar el cheque en vez de solo mostrar los totales planos
 * (`discountAmount`, `serviceChargeAmount`, que siguen existiendo y no
 * cambian). Solo llegan en `getOrder` (GET) — `addItemsToOrder` (PATCH) no las
 * trae, por eso son listas vacías por default y no un campo obligatorio.
 */
data class OrderDetail(
    val id: String,
    val orderNumber: String = "",
    val status: String = "",
    val kitchenStatus: String = "",
    val paymentStatus: String = "",
    /**
     * Cumplimiento del cheque — el campo Prisma se llama `type`, no `orderType`
     * (`Order.type: OrderType`). Se remapea aquí para no chocar con la palabra
     * reservada `type` como nombre de propiedad idiomático en Kotlin.
     */
    @SerializedName("type")
    val orderType: String = "DINE_IN",
    /**
     * Pesos (major units), NUNCA `Double` — el server serializa `Decimal` como
     * Number y Gson deserializa un campo `BigDecimal` leyendo el literal JSON
     * tal cual (sin pasar por `Double`), preservando precisión exacta. Ver
     * `avoqado-server/.claude/rules/critical-warnings.md` ("Money = Decimal,
     * Never Float") — P1 real: con `Double`, 0.10 + 0.20 ≠ 0.30 en una cuenta.
     */
    val subtotal: BigDecimal = BigDecimal.ZERO,
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    /** Cobros por servicio — SUMAN al total (ingreso gravable, no propina). */
    val serviceChargeAmount: BigDecimal = BigDecimal.ZERO,
    val taxAmount: BigDecimal = BigDecimal.ZERO,
    val tipAmount: BigDecimal = BigDecimal.ZERO,
    val total: BigDecimal = BigDecimal.ZERO,
    val paidAmount: BigDecimal = BigDecimal.ZERO,
    val remainingBalance: BigDecimal = BigDecimal.ZERO,
    /**
     * Restante a cobrar — solo lo calcula `getOrder` (GET), no `addItemsToOrder`
     * (PATCH). Null en la respuesta de PATCH.
     */
    @SerializedName("amount_left")
    val amountLeft: BigDecimal? = null,
    /** Cómo se dividió el ÚLTIMO pago (o `Order.splitType` si no hay pagos aún). */
    val splitType: String? = null,
    /**
     * Igual que `splitType` pero ya resuelto por el server (payments[0].splitType
     * ?? order.splitType) — Android lo usa para restringir splits incompatibles
     * (EQUALPARTS → PERPRODUCT). Solo en `getOrder` (GET).
     */
    val lastSplitType: String? = null,
    /** CAS optimista: version esperada para el próximo `ADD_ITEMS`/`PATCH items`. */
    val version: Int = 1,
    val covers: Int? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val customerId: String? = null,
    val specialRequests: String? = null,
    val tableId: String? = null,
    /** "Mesa {number}" ya armado por el server — solo en `getOrder`/`addItemsToOrder`. */
    val tableName: String? = null,
    val createdBy: OrderStaffSummary? = null,
    val servedBy: OrderStaffSummary? = null,
    val items: List<OrderDetailItem> = emptyList(),
    val payments: List<OrderDetailPayment> = emptyList(),
    /**
     * Ids de `OrderItem` con al menos una `PaymentAllocation` — solo en `getOrder`
     * (GET). Usado para deshabilitar líneas ya pagadas en un split por producto.
     */
    val paidItemIds: List<String> = emptyList(),
    val createdAt: String? = null,
    /**
     * Desglose de descuentos aplicados — solo en `getOrder` (GET), ver KDoc de
     * la clase. `TableCheckoutScreen` (Task 8) los pinta como líneas propias
     * ("Descuentos" NUNCA se esconde dentro del total — regla del founder:
     * "si lo hace mal o feo puede enojar al cliente").
     */
    val orderDiscounts: List<OrderDiscountLine> = emptyList(),
    /** Desglose de cobros por servicio — SUMAN, no confundir con propina. Solo en `getOrder` (GET). */
    val serviceCharges: List<OrderServiceChargeLine> = emptyList(),
)

/** Espejo de `{id, name, amount}` — `OrderDiscount` seleccionado por `order.tpv.service.ts::getOrder`. */
data class OrderDiscountLine(
    val id: String = "",
    val name: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
)

/** Espejo del modelo Prisma `OrderServiceCharge` completo (`include: true`, ver schema.prisma:6351). */
data class OrderServiceChargeLine(
    val id: String = "",
    val name: String = "",
    /** `FIXED` | `PERCENTAGE` — ver `ServiceChargeType` en el schema del server. */
    val type: String = "FIXED",
    val value: BigDecimal = BigDecimal.ZERO,
    /** Monto ya calculado sobre la base del cheque — este es el que se muestra. */
    val amount: BigDecimal = BigDecimal.ZERO,
    val taxable: Boolean = true,
    /** Lo puso la regla de comensales, no una persona. */
    val isAutomatic: Boolean = false,
)

data class OrderStaffSummary(
    val id: String,
    val firstName: String = "",
    val lastName: String = "",
)

data class OrderDetailItem(
    val id: String,
    /** Null para líneas de monto libre (sin producto de catálogo). */
    val productId: String? = null,
    val productName: String? = null,
    val quantity: Int = 1,
    val unitPrice: BigDecimal = BigDecimal.ZERO,
    val total: BigDecimal = BigDecimal.ZERO,
    val notes: String? = null,
    val modifiers: List<OrderDetailModifier> = emptyList(),
    /** Curso/tiempo bajo el que se envió la línea. Null = "Inmediato". */
    val course: String? = null,
    /** Asiento/comensal de la línea (Square's seats). */
    val seat: Int? = null,
    /** Venta por peso: kilos pesados. Null en líneas no pesadas. */
    val weightQuantity: Double? = null,
    val isCortesia: Boolean = false,
    val cortesiaReason: String? = null,
    val createdAt: String? = null,
    /** Timestamp de la RONDA (compartido por todas las filas de un mismo "Enviar"). */
    val sentToKitchenAt: String? = null,
)

data class OrderDetailModifier(
    val id: String = "",
    val name: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
)

data class OrderDetailPayment(
    val id: String = "",
    val amount: BigDecimal = BigDecimal.ZERO,
    val tipAmount: BigDecimal = BigDecimal.ZERO,
    val method: String = "",
    val status: String = "",
    val splitType: String? = null,
)
