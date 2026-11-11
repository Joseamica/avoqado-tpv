package com.jaac.avoqado_tpv.features.checkout.data

import android.content.Context
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.DescuentoDelCliente
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountConditions
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.DayOfWeek
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 💵 «Cobrar» sin red: el carrito y SU llave de venta, durables.
 *
 * Se escribe ANTES del primer intento de red (y antes de navegar con una venta local): si el sistema mata el proceso entre el toque
 * y la respuesta, al reabrir vuelve el MISMO carrito con la MISMA llave y el reintento encuentra la misma orden por `externalId`.
 * Un carrito por negocio. Se borra cuando la fila de pago queda insertada, cuando la venta en línea termina o cuando el cajero vacía
 * el carrito (eso lo decide quien lo llama).
 *
 * La regla «si ya hay una fila de `pending_payments` con esa llave NO se restaura» NO vive aquí: quien abre Cobrar la decide con
 * `PendingPaymentDao.existeFilaConLlave(llave)` sobre lo que devuelve [leer].
 *
 * SharedPreferences (como `SavedCartsRepositoryImpl`: sin datos sensibles, sólo referencias de producto y centavos) y un DTO propio:
 * [CartItemType] es sellado y Gson no lo reconstruye. Se escribe con `commit()` (síncrono) en IO: `apply()` puede perderse si el
 * proceso muere justo después.
 */
@Singleton
class CarritoEnCurso @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * `true` si quedó escrito en disco (resultado de `commit()`); `false` ⇒ quien llama no debe seguir como si estuviera guardado.
     * [cliente] es el seleccionado en Cobrar (se guarda lo mínimo para volver a mostrarlo); [guardadoEn] decide la edad al restaurar.
     */
    suspend fun guardar(
        venueId: String,
        llave: String,
        cart: CartState,
        cliente: Customer? = null,
        guardadoEn: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val dto = CarritoDto.desde(llave, cart).copy(guardadoEn = guardadoEn, cliente = cliente?.let(ClienteDto::desde))
        val ok = prefs.edit().putString(clave(venueId), gson.toJson(dto)).commit()
        if (!ok) Timber.e("🛒 [CarritoEnCurso] No se pudo guardar el carrito en curso")
        ok
    }

    /** La llave y el carrito guardados de [venueId], o null si no hay (un fallo de lectura se propaga sin borrar). */
    suspend fun leer(venueId: String): Pair<String, CartState>? = leerGuardado(venueId)?.let { it.llave to it.cart }

    /** Todo lo guardado de [venueId] (llave, carrito, cuándo y el cliente), o null si no hay; un contenido ilegible se conserva y se propaga. */
    suspend fun leerGuardado(venueId: String): CarritoGuardado? = withContext(Dispatchers.IO) {
        val crudo = prefs.getString(clave(venueId), null) ?: return@withContext null
        try {
            val dto = gson.fromJson(crudo, CarritoDto::class.java)
            require(dto != null && !dto.llave.isNullOrBlank()) { "La venta a medias no se pudo leer" }
            CarritoGuardado(dto.llave, dto.aCarrito(), dto.guardadoEn, dto.cliente?.aCliente())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "🛒 [CarritoEnCurso] Carrito guardado ilegible; se conserva")
            throw IllegalStateException("No se pudo leer la venta a medias. Se conserva en la terminal; vuelve a intentar.", e)
        }
    }

    suspend fun borrar(venueId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(clave(venueId)).commit()
        Unit
    }

    private fun clave(venueId: String) = "carrito_$venueId"

    private companion object {
        const val PREFS_NAME = "avoqado_carrito_en_curso"
    }
}

/** Lo que [CarritoEnCurso.leerGuardado] devuelve. [guardadoEn] es null en un carrito guardado sin hora (no se restaura). */
data class CarritoGuardado(
    val llave: String,
    val cart: CartState,
    val guardadoEn: Long?,
    val cliente: Customer?,
)

/** DTO propio del carrito durable: tipos simples, sin clases selladas. `version` deja leer formatos futuros sin adivinar. */
internal data class CarritoDto(
    val version: Int = 1,
    val llave: String,
    val items: List<ItemDto>,
    val orderDiscount: DescuentoDto?,
    val manualDiscountCents: Int,
    val manualDiscountReason: String?,
    val manualDiscountSource: String?,
    val orderNote: String?,
    val orderTaxPercent: Int?,
    val selectedStaffId: String,
    val selectedStaffName: String,
    /** Epoch ms del aparato al guardar; null en lo guardado antes de este campo. */
    val guardadoEn: Long? = null,
    val cliente: ClienteDto? = null,
    /** Nulos/false en lo guardado antes de estos campos. */
    val descuentoEsDelCliente: Boolean = false,
    /** Una marca booleana de una versión de prueba anterior (`descuentoDelClienteQuitado`) se ignora: queda null. */
    val descuentoDelClienteQuitadoDe: String? = null,
    val autorizacionDelDescuento: String? = null,
) {
    fun aCarrito() = CartState(
        items = items.map { it.aRenglon() },
        orderDiscount = orderDiscount?.aDescuento(),
        manualDiscountCents = manualDiscountCents,
        manualDiscountReason = manualDiscountReason,
        manualDiscountSource = manualDiscountSource,
        orderNote = orderNote,
        orderTaxPercent = orderTaxPercent,
        selectedStaffId = selectedStaffId,
        selectedStaffName = selectedStaffName,
        descuentoEsDelCliente = descuentoEsDelCliente,
        descuentoDelClienteQuitadoDe = descuentoDelClienteQuitadoDe,
        autorizacionDelDescuento = autorizacionDelDescuento,
    )

    companion object {
        fun desde(llave: String, cart: CartState) = CarritoDto(
            llave = llave,
            items = cart.items.map(ItemDto::desde),
            orderDiscount = cart.orderDiscount?.let(DescuentoDto::desde),
            manualDiscountCents = cart.manualDiscountCents,
            manualDiscountReason = cart.manualDiscountReason,
            manualDiscountSource = cart.manualDiscountSource,
            orderNote = cart.orderNote,
            orderTaxPercent = cart.orderTaxPercent,
            selectedStaffId = cart.selectedStaffId,
            selectedStaffName = cart.selectedStaffName,
            descuentoEsDelCliente = cart.descuentoEsDelCliente,
            descuentoDelClienteQuitadoDe = cart.descuentoDelClienteQuitadoDe,
            autorizacionDelDescuento = cart.autorizacionDelDescuento,
        )
    }
}

internal data class ItemDto(
    val id: String,
    /** null = importe tecleado ([CartItemType.CustomAmount]). */
    val productId: String?,
    val name: String,
    val subtitle: String?,
    val unitPriceCents: Int,
    val quantity: Int,
    val imageUrl: String?,
    val colorHex: String?,
    val categoryId: String?,
    val modifiers: List<SelectedModifier>,
    val itemNote: String?,
    val priceAdjustmentCents: Int?,
    val isCortesia: Boolean,
    val cortesiaReason: String?,
    val itemDiscountId: String?,
    val catalogAt: String?,
) {
    fun aRenglon() = CartItem(
        id = id,
        type = productId?.let { CartItemType.ProductItem(it) } ?: CartItemType.CustomAmount,
        name = name, subtitle = subtitle, unitPriceCents = unitPriceCents, quantity = quantity,
        imageUrl = imageUrl, colorHex = colorHex, categoryId = categoryId,
        selectedModifiers = modifiers, itemNote = itemNote, priceAdjustmentCents = priceAdjustmentCents,
        isCortesia = isCortesia, cortesiaReason = cortesiaReason, itemDiscountId = itemDiscountId, catalogAt = catalogAt,
    )

    companion object {
        fun desde(item: CartItem) = ItemDto(
            id = item.id,
            productId = (item.type as? CartItemType.ProductItem)?.productId,
            name = item.name, subtitle = item.subtitle, unitPriceCents = item.unitPriceCents, quantity = item.quantity,
            imageUrl = item.imageUrl, colorHex = item.colorHex, categoryId = item.categoryId,
            modifiers = item.selectedModifiers, itemNote = item.itemNote, priceAdjustmentCents = item.priceAdjustmentCents,
            isCortesia = item.isCortesia, cortesiaReason = item.cortesiaReason, itemDiscountId = item.itemDiscountId,
            catalogAt = item.catalogAt,
        )
    }
}

/**
 * El cliente seleccionado, lo mínimo para volver a mostrarlo SIN red (nombre y contacto). Puntos, visitas y grupo vienen del servidor:
 * al restaurar quedan en cero/vacíos hasta que se vuelva a buscar.
 */
internal data class ClienteDto(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    /** Nulo en lo guardado antes de este campo. */
    val descuento: DescuentoDelClienteGuardadoDto? = null,
) {
    fun aCliente() = Customer(
        id = id, firstName = firstName, lastName = lastName, email = email, phone = phone,
        loyaltyPoints = 0, totalVisits = 0, totalSpent = BigDecimal.ZERO, customerGroup = null,
        descuentoDelCliente = descuento?.let {
            DescuentoDelCliente(it.discountId, it.nombre, DiscountType.fromString(it.tipo), it.valor, it.tope)
        },
    )

    companion object {
        fun desde(c: Customer) = ClienteDto(
            c.id, c.firstName, c.lastName, c.email, c.phone,
            c.descuentoDelCliente?.let { DescuentoDelClienteGuardadoDto(it.discountId, it.nombre, it.tipo.name, it.valor, it.tope) },
        )
    }
}

internal data class DescuentoDelClienteGuardadoDto(
    val discountId: String,
    val nombre: String,
    val tipo: String,
    val valor: Double,
    val tope: Double?,
)

/** El descuento de cuenta, con los `BigDecimal` como texto plano para que Gson los vuelva a leer igual. */
internal data class DescuentoDto(
    val id: String,
    val name: String,
    val type: String,
    val value: String,
    val scope: String,
    val active: Boolean,
    val requiresAuthorization: Boolean,
    val minOrderAmount: String?,
    val maxOrderAmount: String?,
    val validDays: List<String>?,
    val validHoursStart: String?,
    val validHoursEnd: String?,
    val tieneCondiciones: Boolean,
    /** D5. Nulos en lo guardado antes de estos campos. */
    val maxDiscountAmount: String? = null,
    val couponCode: String? = null,
) {
    fun aDescuento() = Discount(
        id = id, name = name, type = DiscountType.fromString(type), value = BigDecimal(value),
        scope = DiscountScope.fromString(scope),
        conditions = if (!tieneCondiciones) null else DiscountConditions(
            minOrderAmount = minOrderAmount?.let(::BigDecimal),
            maxOrderAmount = maxOrderAmount?.let(::BigDecimal),
            validDays = validDays?.map { DayOfWeek.valueOf(it) },
            validHoursStart = validHoursStart,
            validHoursEnd = validHoursEnd,
        ),
        active = active, requiresAuthorization = requiresAuthorization,
        maxDiscountAmount = maxDiscountAmount?.let(::BigDecimal),
        couponCode = couponCode,
    )

    companion object {
        fun desde(d: Discount) = DescuentoDto(
            id = d.id, name = d.name, type = d.type.name, value = d.value.toPlainString(), scope = d.scope.name,
            active = d.active, requiresAuthorization = d.requiresAuthorization,
            minOrderAmount = d.conditions?.minOrderAmount?.toPlainString(),
            maxOrderAmount = d.conditions?.maxOrderAmount?.toPlainString(),
            validDays = d.conditions?.validDays?.map { it.name },
            validHoursStart = d.conditions?.validHoursStart,
            validHoursEnd = d.conditions?.validHoursEnd,
            tieneCondiciones = d.conditions != null,
            maxDiscountAmount = d.maxDiscountAmount?.toPlainString(),
            couponCode = d.couponCode,
        )
    }
}
