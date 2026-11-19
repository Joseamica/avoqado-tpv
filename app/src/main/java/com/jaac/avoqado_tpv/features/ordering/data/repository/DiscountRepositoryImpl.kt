package com.jaac.avoqado_tpv.features.ordering.data.repository

import com.jaac.avoqado_tpv.core.data.network.permisoRequeridoDe
import com.jaac.avoqado_tpv.features.ordering.data.api.DiscountApiService
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyCouponRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyManualDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyPredefinedDiscountRequest
import com.jaac.avoqado_tpv.features.ordering.data.dto.ValidateCouponRequest
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toCouponCode
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDiscount
import com.jaac.avoqado_tpv.features.ordering.domain.AcuseDeDescuento
import com.jaac.avoqado_tpv.features.ordering.domain.CouponCode
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderDiscount
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DiscountRepositoryImpl - Implementation of DiscountRepository
 *
 * Responsibilities:
 * - Fetch available discounts from backend
 * - Apply predefined, manual, and coupon discounts
 * - Validate coupon codes
 * - Handle errors and return Result<T>
 *
 * Backend Integration:
 * - Uses DiscountApiService for all operations
 * - Endpoints: /tpv/venues/{venueId}/orders/{orderId}/discounts/...
 *
 * @see avoqado-server/docs/clients&promotions/CUSTOMER_LOYALTY_PROMOTIONS_REFERENCE.md
 */
@Singleton
class DiscountRepositoryImpl @Inject constructor(
    private val apiService: DiscountApiService
) : DiscountRepository {

    // ==========================================
    // DISCOUNT OPERATIONS
    // ==========================================

    override suspend fun getAvailableDiscounts(
        venueId: String,
        orderId: String,
        customerId: String?
    ): Result<List<Discount>> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Getting available discounts (venueId=$venueId, orderId=$orderId)")

            val response = apiService.getAvailableDiscounts(venueId, orderId, customerId)

            if (response.isSuccessful && response.body() != null) {
                val discounts = response.body()!!.data.map { it.toDiscount() }
                Timber.i("✅ Got ${discounts.size} available discounts")
                Result.success(discounts)
            } else {
                val error = "Failed to get discounts: HTTP ${response.code()}"
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error getting discounts")
            Result.failure(e)
        }
    }

    override suspend fun applyPredefinedDiscount(
        venueId: String,
        orderId: String,
        discountId: String,
        authorizedById: String?,
        idempotencyKey: String?,
    ): Result<AcuseDeDescuento> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Applying predefined discount: $discountId to order: $orderId")

            val request = ApplyPredefinedDiscountRequest(
                discountId = discountId,
                authorizedById = authorizedById,
                idempotencyKey = idempotencyKey,
            )

            val response = apiService.applyPredefinedDiscount(venueId, orderId, request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // D16: la fila lleva el id que da el SERVIDOR (`orderDiscountId`); antes el del catálogo, que no se podía quitar
                    val discount = OrderDiscount(
                        id = body.data.orderDiscountId ?: ID_DE_FILA_SIN_CONFIRMAR,
                        orderId = orderId,
                        discountId = discountId,
                        couponCodeId = null,
                        name = "Descuento aplicado",
                        type = DiscountType.PERCENTAGE,  // Will be updated by socket event
                        value = BigDecimal.ZERO,
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = null,
                        appliedBy = "",
                        appliedAt = java.time.Instant.now()
                    )
                    Timber.i("✅ Discount applied: -$${body.data.amount}, new total: $${body.data.newOrderTotal}")
                    Result.success(acuse(discount, body.data))
                } else {
                    val error = body.error ?: "Failed to apply discount"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                if (response.code() == 403) {
                    val permiso = permisoRequeridoDe(403, response.errorBody()?.string(), TEXTO_HACE_FALTA_PERMISO)
                    if (permiso != null) return@withContext Result.failure(permiso)
                }
                // D16 (Codex r1 #7): un 5xx no dice si se guardó
                if (response.code() >= 500) return@withContext Result.failure(DescuentoSinConfirmarException())
                val error = when (response.code()) {
                    // D16: como en D20 (manual), el cuerpo de un 400 viene en `errorBody()`; `body()` siempre era null.
                    400 -> textoDelRechazo(response, "No se pudo aplicar el descuento.")
                    404 -> "Order or discount not found"
                    409 -> "Order was modified. Please refresh and try again."
                    else -> "Failed to apply discount: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "❌ Sin respuesta al aplicar el descuento del catálogo")
            Result.failure(DescuentoSinConfirmarException(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying predefined discount")
            Result.failure(e)
        }
    }

    override suspend fun applyManualDiscount(
        venueId: String,
        orderId: String,
        type: DiscountType,
        value: Double,
        reason: String?,
        authorizedById: String?,
        overrideToken: String?,
        idempotencyKey: String?,
    ): Result<AcuseDeDescuento> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🏷️ [Discount] Applying manual discount: $type $value to order: $orderId")

            // Map DiscountType to backend enum value
            val backendType = when (type) {
                DiscountType.PERCENTAGE -> "PERCENTAGE"
                DiscountType.FIXED -> "FIXED_AMOUNT"
            }

            val request = ApplyManualDiscountRequest(
                type = backendType,
                value = value,
                reason = reason,
                authorizedById = authorizedById,
                idempotencyKey = idempotencyKey,
            )

            val response = apiService.applyManualDiscount(venueId, orderId, request, overrideToken)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // Backend returns { amount, newOrderTotal } - create placeholder OrderDiscount
                    val discountName = when (type) {
                        DiscountType.PERCENTAGE -> "Descuento $value%"
                        DiscountType.FIXED -> "Descuento $$value"
                    }
                    val discount = OrderDiscount(
                        // D16: el id real de la fila; antes `manual_…`, que el SERVIDOR no reconoce al quitarla
                        id = body.data.orderDiscountId ?: ID_DE_FILA_SIN_CONFIRMAR,
                        orderId = orderId,
                        discountId = null,
                        couponCodeId = null,
                        name = discountName,
                        type = type,
                        value = BigDecimal.valueOf(value),
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = reason,
                        appliedBy = "",
                        appliedAt = Instant.now()
                    )
                    Timber.i("✅ Manual discount applied: -$${body.data.amount}, new total: $${body.data.newOrderTotal}")
                    Result.success(acuse(discount, body.data))
                } else {
                    val error = body.error ?: "Failed to apply manual discount"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                if (response.code() == 403) {
                    val permiso = permisoRequeridoDe(403, response.errorBody()?.string(), TEXTO_HACE_FALTA_PERMISO)
                    if (permiso != null) return@withContext Result.failure(permiso)
                }
                // D16 (Codex r1 #7): un 5xx no dice si se guardó
                if (response.code() >= 500) return@withContext Result.failure(DescuentoSinConfirmarException())
                val error = when (response.code()) {
                    // D20: el cuerpo de un 400 no llega en `body()` (Retrofit lo deja en `errorBody()`): sin esto nunca salía su motivo.
                    400 -> textoDelRechazo(response, "Descuento inválido. Verifica el valor ingresado.")
                    404 -> "Order not found"
                    409 -> "Order was modified. Please refresh and try again."
                    else -> "Failed to apply discount: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "❌ Sin respuesta al aplicar el descuento manual")
            Result.failure(DescuentoSinConfirmarException(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying manual discount")
            Result.failure(e)
        }
    }

    override suspend fun removeDiscount(
        venueId: String,
        orderId: String,
        orderDiscountId: String,
        expectedVersion: Int
    ): Result<java.math.BigDecimal?> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🗑️ [Discount] Removing discount: $orderDiscountId from order: $orderId (version=$expectedVersion)")

            val response = apiService.removeDiscount(venueId, orderId, orderDiscountId, expectedVersion)

            if (response.isSuccessful) {
                Timber.i("✅ Discount removed successfully")
                // D16 v4: el total de la cuenta según el SERVIDOR, para la cabecera si la lectura final no llega.
                Result.success(response.body()?.data?.newOrderTotal?.let { BigDecimal.valueOf(it) })
            } else if (response.code() >= 500) {
                Timber.e("❌ Remove discount: HTTP ${response.code()} (sin confirmar)")
                Result.failure(DescuentoSinConfirmarException())
            } else {
                // D16: antes «Failed to remove discount: HTTP 400» y sólo al log; ahora la razón en español para la caja.
                val error = motivoDelRechazoAlQuitar(response.errorBody()?.string())
                Timber.e("❌ Remove discount rejected: HTTP ${response.code()} → $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "❌ Sin respuesta al quitar el descuento")
            Result.failure(DescuentoSinConfirmarException(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error removing discount")
            Result.failure(e)
        }
    }

    // ==========================================
    // COUPON OPERATIONS
    // ==========================================

    override suspend fun validateCoupon(
        venueId: String,
        code: String,
        orderTotal: Double?,
        customerId: String?,
    ): Result<CouponCode> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🎟️ [Coupon] Validating code: $code (venueId=$venueId)")

            val request = ValidateCouponRequest(
                code = code,
                orderTotal = orderTotal,
                customerId = customerId,
            )

            val response = apiService.validateCoupon(venueId, request)

            if (response.isSuccessful && response.body() != null) {
                val coupon = response.body()!!.data.toCouponCode()
                if (coupon.isValid) {
                    Timber.i("✅ Coupon valid: ${coupon.name} (${coupon.formattedValue})")
                } else {
                    Timber.w("⚠️ Coupon invalid: ${coupon.invalidReason}")
                }
                Result.success(coupon)
            } else {
                val error = mensajeDelRechazoDelCupon(response.code(), response.errorBody()?.string())
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: java.io.IOException) {
            // Nadie contestó ESTA petición: se dice que falta la conexión, nunca que el cupón no sirve (ni «Unable to resolve host»).
            Timber.w(e, "🎟️ Sin respuesta al validar el cupón")
            Result.failure(Exception(TEXTO_CUPON_SIN_RED_AL_VALIDAR, e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "❌ Error validating coupon")
            Result.failure(Exception(TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON, e))
        }
    }

    override suspend fun applyCoupon(
        venueId: String,
        orderId: String,
        code: String
    ): Result<AcuseDeDescuento> = withContext(Dispatchers.IO) {
        try {
            Timber.d("🎟️ [Coupon] Applying code: $code to order: $orderId")

            val request = ApplyCouponRequest(couponCode = code)
            val response = apiService.applyCoupon(venueId, orderId, request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.data != null) {
                    // Backend returns { couponId, discountName, amount, newOrderTotal }
                    val discount = OrderDiscount(
                        // D16: el id de la FILA (antes el del cupón, que no se podía quitar); `couponCodeId` sigue siendo el cupón
                        id = body.data.orderDiscountId ?: ID_DE_FILA_SIN_CONFIRMAR,
                        orderId = orderId,
                        discountId = null,
                        couponCodeId = body.data.couponId,
                        name = "${body.data.discountName} ($code)",
                        type = DiscountType.PERCENTAGE,  // Will be updated by socket event
                        value = BigDecimal.ZERO,
                        amount = BigDecimal.valueOf(body.data.amount),
                        reason = null,
                        appliedBy = "",
                        appliedAt = Instant.now()
                    )
                    Timber.i("✅ Coupon applied: ${body.data.discountName} (-$${body.data.amount}), new total: $${body.data.newOrderTotal}")
                    Result.success(AcuseDeDescuento(discount, BigDecimal.valueOf(body.data.newOrderTotal)))
                } else {
                    val error = body.error ?: "Failed to apply coupon"
                    Timber.e("❌ $error")
                    Result.failure(Exception(error))
                }
            } else {
                // Parse error body to get specific error message from backend
                val errorBody = response.errorBody()?.string()
                val backendError = try {
                    errorBody?.let {
                        val json = Gson().fromJson(it, JsonObject::class.java)
                        json.get("error")?.asString
                    }
                } catch (e: Exception) {
                    Timber.w("Failed to parse error body: $errorBody")
                    null
                }

                // D16 v4 (Codex r3 #5): un 5xx no dice si el cupón quedó aplicado.
                if (response.code() >= 500) return@withContext Result.failure(DescuentoSinConfirmarException())
                val error = when (response.code()) {
                    400 -> backendError ?: "Coupon already applied or invalid"
                    404 -> backendError ?: "Coupon code not found"
                    409 -> backendError ?: "Order was modified. Please refresh and try again."
                    else -> backendError ?: "Failed to apply coupon: HTTP ${response.code()}"
                }
                Timber.e("❌ $error")
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.e(e, "❌ Sin respuesta al aplicar el cupón")
            Result.failure(DescuentoSinConfirmarException(e))
        } catch (e: Exception) {
            Timber.e(e, "❌ Error applying coupon")
            Result.failure(e)
        }
    }
}

const val TEXTO_CUPON_SIN_RED_AL_VALIDAR = "Sin conexión: el cupón necesita internet para validarse. Reintenta cuando vuelva la conexión."
const val TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON = "No se pudo validar el cupón. Intenta de nuevo."

/** Un 4xx trae su razón en `message` (código muy corto, sin permiso…); un 5xx o un intermediario, sólo que no se pudo. */
internal fun mensajeDelRechazoDelCupon(codigoHttp: Int, cuerpo: String?): String {
    if (codigoHttp !in 400..499) return TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON
    return runCatching { Gson().fromJson(cuerpo, JsonObject::class.java)?.get("message")?.asString }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: TEXTO_NO_SE_PUDO_VALIDAR_EL_CUPON
}

// ==========================================
// D16 (hz:e82938e1:D16): TPV SOLA → ÓRDENES toma la lista de descuentos del SERVIDOR
// ==========================================

/** Una fila nueva sin id del SERVIDOR (SERVIDOR anterior a D16): la pantalla no la agrega y espera a la relectura. */
const val ID_DE_FILA_SIN_CONFIRMAR = ""

/**
 * Aplicar o quitar sin respuesta (sin red, se cortó, o un 5xx): no sabemos si el SERVIDOR lo guardó. La pantalla lo dice, relee la
 * lista y no lo vuelve a mandar (dos toques crearían dos filas reales).
 */
class DescuentoSinConfirmarException(cause: Throwable? = null) : Exception("Sin confirmar", cause)

const val TEXTO_APLICAR_SIN_CONFIRMAR = "No pudimos confirmar el descuento. Revisa la lista antes de volver a aplicarlo."
const val TEXTO_QUITAR_SIN_CONFIRMAR = "No se pudo confirmar: quitar un descuento necesita conexión. Revisa la lista."
/** El mismo texto de D15 en Mesas: sólo la razón, sin mandar a cancelar ni cobrar (Codex D16 r1 #8, D15 r3 #1). */
const val TEXTO_REBAJA_FIJA_DE_VALES =
    "Esta rebaja quedó fija al cobrar los vales y no se quita desde la caja. Si hay que cambiarla, avisa a un encargado."
const val TEXTO_DESCUENTO_YA_NO_ESTABA = "Ese descuento ya no estaba en la cuenta. Ya actualizamos la lista."
const val TEXTO_NO_SE_PUDO_QUITAR = "No se pudo quitar el descuento."

// D16 v4 (fila de descuentos) y D21 (llave por envío)
const val TEXTO_ESPERA_EL_DESCUENTO = "Espera a que termine el descuento en curso."
const val TEXTO_ESPERA_EL_COBRO = "Espera a que termine de preparar el cobro."
const val TEXTO_DESCUENTO_YA_APLICADO = "Ese descuento ya está aplicado en esta cuenta."
const val TEXTO_NO_SE_PUDO_APLICAR = "No se pudo aplicar el descuento."
// D16 v4.2 · #7: un 403 sin permiso conocido ya no llega en inglés («Manager authorization required») a la caja.
const val TEXTO_HACE_FALTA_PERMISO = "Para esto hace falta el permiso de un gerente."
const val TEXTO_ENVIO_YA_HECHO = "Ese descuento ya había quedado aplicado. Si quieres otro igual, vuelve a darlo."

private fun acuse(fila: OrderDiscount, data: com.jaac.avoqado_tpv.features.ordering.data.dto.ApplyDiscountDataDto) =
    AcuseDeDescuento(fila = fila, totalDeLaCuenta = BigDecimal.valueOf(data.newOrderTotal), repetida = data.repetida == true)
/** La cuenta sigue con su id local (`local_…`): el SERVIDOR no la conoce todavía y el descuento no se manda. */
const val TEXTO_CUENTA_SIN_SUBIR =
    "Sin conexión: esta cuenta todavía no llega al servidor. Aplica el descuento cuando vuelva la conexión."

/**
 * D16: la razón de un «quitar» rechazado (4xx), en español. La rebaja fijada al cobrar vales da la razón de D15 (sólo la razón, sin
 * mandar a cancelar ni cobrar: Codex D16 r1 #8). Lo que el SERVIDOR contesta en inglés se cambia por un texto llano.
 */
internal fun motivoDelRechazoAlQuitar(cuerpo: String?): String {
    val json = runCatching { Gson().fromJson(cuerpo, JsonObject::class.java) }.getOrNull()
    fun texto(llave: String) = json?.get(llave)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    if (texto("code") == "AREA_TICKET_DISCOUNT_FROZEN") return TEXTO_REBAJA_FIJA_DE_VALES
    val motivo = texto("message") ?: texto("error") ?: return TEXTO_NO_SE_PUDO_QUITAR
    return when {
        motivo.startsWith("Discount not found") -> TEXTO_DESCUENTO_YA_NO_ESTABA
        RECHAZOS_EN_INGLES_AL_QUITAR.any { motivo.startsWith(it) } -> TEXTO_NO_SE_PUDO_QUITAR
        else -> motivo
    }
}

private val RECHAZOS_EN_INGLES_AL_QUITAR = listOf("Order not found", "Order was modified", "Failed", "Cannot ", "Validation", "Invalid")
