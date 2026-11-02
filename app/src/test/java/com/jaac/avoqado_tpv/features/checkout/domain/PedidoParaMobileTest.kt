package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.ExtraDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.RenglonDeTicket
import com.jaac.avoqado_tpv.features.checkout.domain.model.SelectedModifier
import com.jaac.avoqado_tpv.features.checkout.domain.model.VentaLocal
import com.jaac.avoqado_tpv.features.ordering.domain.Discount
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountScope
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountType
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import org.junit.Test
import java.math.BigDecimal

/**
 * «Cobrar» sin red, C2a: el cuerpo de `POST mobile/venues/{v}/orders` (centavos) y los renglones del ticket salen del MISMO
 * carrito con las MISMAS llaves por renglón (`"<llave>:<i>"`), para que C3 compare renglón contra renglón.
 */
class PedidoParaMobileTest {

    private val llave = "ckx0000000000000000000001"
    private val catalogoViejo = "2026-10-20T10:00:00.000Z"
    private val creadaEn = 1_760_000_000_000L

    private fun cafe(catalogAt: String? = catalogoViejo) = CartItem(
        id = "i-0", type = CartItemType.ProductItem("p-1"), name = "Café", unitPriceCents = 5000, quantity = 2,
        selectedModifiers = listOf(SelectedModifier("g-1", "Extras", "m-1", "Leche de avena", 1000)),
        itemNote = "sin hielo", catalogAt = catalogAt,
    )

    private fun cortesia() = CartItem(
        id = "i-1", type = CartItemType.ProductItem("p-2"), name = "Galleta", unitPriceCents = 3000, quantity = 1,
        isCortesia = true, cortesiaReason = "Cliente VIP", catalogAt = catalogoViejo,
    )

    private fun importe() = CartItem(
        id = "i-2", type = CartItemType.CustomAmount, name = "Otro importe", unitPriceCents = 12000,
    )

    private fun productos(): Map<String, Product> = mapOf(
        "p-1" to Product(
            id = "p-1", name = "Café", sku = "CF", price = BigDecimal("50.00"), categoryId = "c", categoryName = "Bebidas",
            description = null, emoji = "☕", imageUrl = null, available = true,
            modifierGroups = listOf(
                ModifierGroup(
                    id = "g-1", name = "Extras", type = ModifierType.MULTIPLE_CHOICE,
                    modifiers = listOf(ProductModifier("m-1", "Leche de avena", BigDecimal("10.00"), ModifierType.MULTIPLE_CHOICE)),
                ),
            ),
        ),
    )

    private fun json(texto: String) = JsonParser.parseString(texto)

    @Test fun `producto con extra, cortesia e importe libre dan el JSON esperado`() {
        val cart = CartState(items = listOf(cafe(), cortesia(), importe()), selectedStaffId = "staff-1")

        val pedido = armarPedidoParaMobile(cart, llave, "staff-1", "shift-1", creadaEn, productos())

        val esperado = """
            {
              "staffId": "staff-1", "orderType": "TAKEOUT", "source": "TPV", "externalId": "$llave",
              "subtotal": 27000, "tip": 0, "total": 24000, "discount": 0,
              "isOfflineReplay": true, "createdAtLocal": 1760000000000, "shiftIdDeLaVenta": "shift-1",
              "items": [
                {"externalId": "$llave:0", "productId": "p-1", "quantity": 2, "modifierIds": ["m-1"],
                 "displayedUnitPrice": 5000, "catalogAt": "$catalogoViejo", "displayedModifierPrices": {"m-1": 1000},
                 "notes": "sin hielo"},
                {"externalId": "$llave:1", "productId": "p-2", "quantity": 1, "modifierIds": [],
                 "displayedUnitPrice": 3000, "catalogAt": "$catalogoViejo",
                 "isCortesia": true, "cortesiaReason": "Cliente VIP"},
                {"externalId": "$llave:2", "name": "Otro importe", "unitPrice": 12000, "quantity": 1, "modifierIds": []}
              ]
            }
        """.trimIndent()
        assertThat(json(pedido)).isEqualTo(json(esperado))
    }

    @Test fun `el descuento de cuenta y el de linea viajan como ids`() {
        val diezPorCiento = Discount(
            id = "d-cuenta", name = "10%", type = DiscountType.PERCENTAGE, value = BigDecimal("10"),
            scope = DiscountScope.ORDER, conditions = null, active = true, requiresAuthorization = false,
        )
        val conDescuentoDeLinea = cafe().copy(itemDiscountId = "d-linea")
        val cart = CartState(items = listOf(conDescuentoDeLinea), orderDiscount = diezPorCiento)

        val raiz = json(armarPedidoParaMobile(cart, llave, "staff-1", null, creadaEn, productos())).asJsonObject

        assertThat(raiz["orderDiscountId"].asString).isEqualTo("d-cuenta")
        // Con descuento de catálogo el servidor lo recalcula: el importe libre no se suma.
        assertThat(raiz["discount"].asInt).isEqualTo(0)
        assertThat(raiz["items"].asJsonArray[0].asJsonObject["discountId"].asString).isEqualTo("d-linea")
        assertThat(raiz.has("shiftIdDeLaVenta")).isFalse()
    }

    @Test fun `el descuento manual viaja en centavos y sin id`() {
        val cart = CartState(items = listOf(cafe()), manualDiscountCents = 500)

        val raiz = json(armarPedidoParaMobile(cart, llave, "staff-1", null, creadaEn, productos())).asJsonObject

        assertThat(raiz["discount"].asInt).isEqualTo(500)
        assertThat(raiz.has("orderDiscountId")).isFalse()
        assertThat(raiz["total"].asInt).isEqualTo(11500)
        assertThat(raiz["subtotal"].asInt).isEqualTo(12000)
    }

    @Test fun `el precio visto viaja solo junto con catalogAt`() {
        val sinCatalogAt = cafe(catalogAt = null)

        val linea = json(armarPedidoParaMobile(CartState(items = listOf(sinCatalogAt)), llave, "s", null, creadaEn, productos()))
            .asJsonObject["items"].asJsonArray[0].asJsonObject

        assertThat(linea.has("displayedUnitPrice")).isFalse()
        assertThat(linea.has("catalogAt")).isFalse()
        assertThat(linea.has("displayedModifierPrices")).isFalse()
    }

    @Test fun `el catalogAt y el precio son los congelados en el renglon aunque el catalogo vigente cambie`() {
        // El catálogo "vigente" (productos) ya trae otro catalogAt y otro precio: el pedido manda lo que vio el cliente.
        val vigente = productos().getValue("p-1").copy(catalogAt = "2026-10-21T09:00:00.000Z", price = BigDecimal("80.00"))

        val linea = json(
            armarPedidoParaMobile(CartState(items = listOf(cafe())), llave, "s", null, creadaEn, mapOf("p-1" to vigente)),
        ).asJsonObject["items"].asJsonArray[0].asJsonObject

        assertThat(linea["catalogAt"].asString).isEqualTo(catalogoViejo)
        assertThat(linea["displayedUnitPrice"].asInt).isEqualTo(5000)
    }

    @Test fun `un importe tecleado es linea libre con nombre y precio sin productId`() {
        val linea = json(armarPedidoParaMobile(CartState(items = listOf(importe())), llave, "s", null, creadaEn, emptyMap()))
            .asJsonObject["items"].asJsonArray[0].asJsonObject

        assertThat(linea["name"].asString).isEqualTo("Otro importe")
        assertThat(linea["unitPrice"].asInt).isEqualTo(12000)
        assertThat(linea.has("productId")).isFalse()
        assertThat(linea.has("displayedUnitPrice")).isFalse()
    }

    @Test fun `el cliente y la nota de la cuenta viajan cuando existen`() {
        val cart = CartState(items = listOf(cafe()), orderNote = "para llevar")

        val raiz = json(armarPedidoParaMobile(cart, llave, "s", null, creadaEn, productos(), customerId = "cust-1")).asJsonObject

        assertThat(raiz["customerId"].asString).isEqualTo("cust-1")
        assertThat(raiz["note"].asString).isEqualTo("para llevar")
    }

    @Test fun `los renglones del ticket llevan las mismas llaves que el pedido y el bruto de la cortesia no es cero`() {
        val cart = CartState(items = listOf(cafe(), cortesia(), importe()))

        val renglones = renglonesDeTicket(cart, llave, productos())
        val llavesDelPedido = json(armarPedidoParaMobile(cart, llave, "s", null, creadaEn, productos()))
            .asJsonObject["items"].asJsonArray.map { it.asJsonObject["externalId"].asString }

        assertThat(renglones.map { it.externalId }).containsExactlyElementsIn(llavesDelPedido).inOrder()
        assertThat(renglones[0]).isEqualTo(
            RenglonDeTicket(
                externalId = "$llave:0", nombre = "Café", cantidad = 2, totalCents = 12000, brutoCents = 12000,
                extras = listOf(ExtraDeTicket("m-1", "Leche de avena", 1000, "MULTIPLE_CHOICE")), nota = "sin hielo",
            ),
        )
        assertThat(renglones[1].totalCents).isEqualTo(0)
        assertThat(renglones[1].brutoCents).isEqualTo(3000)
        assertThat(renglones[2].extras).isEmpty()
        assertThat(renglones[2].nota).isNull()
    }

    @Test fun `ida y vuelta de VentaLocal con extras conserva id, nombre, precio y tipo`() {
        val cart = CartState(items = listOf(cafe(), importe()))
        val venta = VentaLocal(
            llave = llave, venueId = "venue-1", staffId = "staff-1", shiftId = "shift-1", createdAtLocal = creadaEn,
            totalCents = cart.totalCents,
            pedidoJson = armarPedidoParaMobile(cart, llave, "staff-1", "shift-1", creadaEn, productos()),
            renglones = renglonesDeTicket(cart, llave, productos()),
        )

        val vuelta = Gson().fromJson(Gson().toJson(venta), VentaLocal::class.java)

        assertThat(vuelta).isEqualTo(venta)
        assertThat(vuelta.renglones[0].extras.single()).isEqualTo(ExtraDeTicket("m-1", "Leche de avena", 1000, "MULTIPLE_CHOICE"))
    }
}
