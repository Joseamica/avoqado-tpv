package com.jaac.avoqado_tpv.features.tables.data.api.dto

import java.math.BigDecimal

/**
 * "Aplicar cobro por servicio" — `POST /tpv/venues/{venueId}/orders/{orderId}/service-charges`
 * (`order-table.tpv.controller.ts::applyServiceCharge`, delega en
 * `service-charge.mobile.service.ts::applyServiceCharge`). Body: `{ serviceChargeId }`.
 */
data class ApplyServiceChargeRequest(val serviceChargeId: String)

/** `{ success, data: { subtotal, discountAmount, serviceChargeAmount, total, version } }`. */
data class ApplyServiceChargeResponse(
    val success: Boolean = true,
    val data: OrderTotals? = null,
)

/**
 * Espejo de lo que `comp-item.mobile.service.ts::recalculateOrderTotals`
 * devuelve — el mismo shape que reusan `applyServiceCharge`, `removeServiceCharge`
 * y las funciones internas de split/merge.
 */
data class OrderTotals(
    val subtotal: BigDecimal = BigDecimal.ZERO,
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    val serviceChargeAmount: BigDecimal = BigDecimal.ZERO,
    val total: BigDecimal = BigDecimal.ZERO,
    val version: Int = 1,
)

/**
 * "Cargos por servicio disponibles" — `GET tpv/venues/{venueId}/service-charges`.
 *
 * 🆕 Fase 3 (completitud del módulo Mesas): la ruta `POST .../service-charges`
 * de arriba existía SIN caller — [com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService.applyServiceCharge]
 * es exactamente el código muerto que `completeness-audit.md` encontró. A
 * diferencia de [AvailableDiscountsResponse] (que YA existía server-side desde
 * antes de Fase 1), esta ruta GET **no existía** — se agregó como companion
 * mínimo y aditivo (`order-table.tpv.controller.ts::listServiceCharges`,
 * verificado 2026-08-03) porque la TPV no tenía NINGUNA forma de listar el
 * catálogo bajo `/tpv` (solo `/mobile` lo tenía, fuera de alcance). Ver KDoc
 * de [com.jaac.avoqado_tpv.features.tables.data.api.TablesApiService.getServiceCharges].
 *
 * ⚠️ A diferencia de `discounts/available`, esta lista es el catálogo COMPLETO
 * del venue (activos) — no filtrado por elegibilidad de la cuenta. Aplicar un
 * cargo ya presente en la cuenta es un 400 normal del server ("Ese cobro ya
 * está aplicado a la cuenta"), propagado tal cual — el picker no necesita
 * adivinar cuáles ya aplican.
 */
data class AvailableServiceChargesResponse(
    val success: Boolean = true,
    val data: List<AvailableServiceChargeDto> = emptyList(),
)

/** Espejo de `listServiceCharges` (`service-charge.mobile.service.ts`) — `{id, name, type, value, taxable, autoApplyMinCovers}`. */
data class AvailableServiceChargeDto(
    val id: String,
    val name: String,
    /** PERCENTAGE | FIXED_AMOUNT (Prisma `ServiceChargeType`, schema.prisma:6381). */
    val type: String = "FIXED_AMOUNT",
    val value: BigDecimal = BigDecimal.ZERO,
    val taxable: Boolean = true,
    /** Null = solo manual. No-null = el server YA lo auto-aplica a cuentas con al menos estos comensales. */
    val autoApplyMinCovers: Int? = null,
)

/**
 * "Aplicar descuento predefinido" — `POST
 * /tpv/venues/{venueId}/orders/{orderId}/discounts/apply` (`discountController.applyPredefinedDiscount`).
 *
 * 🔴 El ejemplo del plan (Task 2 Step 4) usaba `orders/{orderId}/discounts` — esa
 * ruta NO EXISTE. La familia real de descuentos del `/tpv` vive bajo
 * `orders/{orderId}/discounts/{apply,manual,coupon,auto}` (ver comentario en
 * `tpv.routes.ts:3739-3742`, que explícitamente dice que esa familia ya existe
 * y es territorio de "Cobrar" — intocable). Solo se modela `discounts/apply`
 * (descuento predefinido del catálogo) porque es el caso de uso de Mesas
 * (aplicar un descuento existente a la cuenta); `manual`/`coupon`/`auto` quedan
 * fuera del alcance de esta tarea.
 */
data class ApplyDiscountRequest(
    val discountId: String,
    /** Requerido solo si el descuento exige aprobación (`Discount.requiresApproval`). */
    val authorizedById: String? = null,
)

/**
 * A diferencia de las otras mutaciones de esta familia, `applyPredefinedDiscount`
 * NO lanza `AppError` cuando el descuento no aplica: responde 400 con
 * `{ success: false, error }` (`discount.tpv.controller.ts:94-98`). Por eso
 * `error` es un campo de este DTO y no una excepción — quien llame esta ruta
 * debe checar `success` en el body, no solo `response.isSuccessful`.
 */
data class ApplyDiscountResponse(
    val success: Boolean = true,
    val data: DiscountApplyResult? = null,
    val message: String? = null,
    val error: String? = null,
)

data class DiscountApplyResult(
    val amount: BigDecimal = BigDecimal.ZERO,
    val newOrderTotal: BigDecimal = BigDecimal.ZERO,
)

/**
 * "Descuentos disponibles" — `GET
 * /tpv/venues/{venueId}/orders/{orderId}/discounts/available`
 * (`discountController.getAvailableDiscounts` → `discount.tpv.service.ts`,
 * permiso `orders:read`). Verificado 2026-08-03 contra `tpv.routes.ts:4713` y
 * `AvailableDiscount` (`src/services/tpv/discount.tpv.service.ts:21-31`) —
 * espejo EXACTO de campo, no una suposición.
 */
data class AvailableDiscountsResponse(
    val success: Boolean = true,
    val data: List<AvailableDiscountDto> = emptyList(),
    val count: Int = 0,
)

/**
 * `requiresApproval` — el catálogo del venue puede marcar un descuento como
 * "necesita autorización de gerente" (`Discount.requiresApproval`). Esta
 * versión de la TPV NO tiene un flujo de autorización de gerente (PIN/login
 * de un segundo staff) — [com.jaac.avoqado_tpv.features.tables.presentation.DiscountPickerSheet]
 * muestra estos descuentos visibles pero deshabilitados con la razón, nunca
 * los oculta ni los aplica sin autorización (sería una regla de negocio
 * inventada por el cliente). Gap documentado, no un olvido silencioso.
 */
data class AvailableDiscountDto(
    val id: String,
    val name: String,
    /** PERCENTAGE | FIXED_AMOUNT | COMP (Prisma `DiscountType`). */
    val type: String = "FIXED_AMOUNT",
    val value: BigDecimal = BigDecimal.ZERO,
    val scope: String = "ORDER",
    val description: String? = null,
    val isAutomatic: Boolean = false,
    val requiresApproval: Boolean = false,
    val estimatedSavings: BigDecimal = BigDecimal.ZERO,
)
