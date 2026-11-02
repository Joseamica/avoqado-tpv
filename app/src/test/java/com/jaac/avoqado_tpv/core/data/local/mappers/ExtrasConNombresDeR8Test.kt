package com.jaac.avoqado_tpv.core.data.local.mappers

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jaac.avoqado_tpv.core.data.local.entities.DraftOrderItemEntity
import com.jaac.avoqado_tpv.core.data.local.entities.ProductEntity
import com.jaac.avoqado_tpv.features.ordering.domain.KitchenStatus
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.OrderItem
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * Los extras guardados con Gson (catálogo `products.modifier_groups_json` y renglones `draft_order_items.modifiers`) en las versiones
 * minificadas de antes de la v43 llevan los nombres que R8 les puso a los campos: ModifierGroup id→a … maxSelections→h y
 * ProductModifier id→a … displayOrder→f (mapping de la 2.13.0 production, de production del 24-sep y de nexgoProd del 8-sep: los
 * tres iguales). Desde la v43 un `-keep` deja los nombres reales, así que la terminal tiene que leer LOS DOS formatos: si no, al
 * actualizar los extras salían con todo en null (NPE al pintarlos, `modifierIds=[null]` al sincronizar una orden).
 */
class ExtrasConNombresDeR8Test {

    private val legadoGrupos =
        """[{"a":"g-1","b":"Término","c":[{"a":"m-1","b":"Medio","c":0,"d":"SINGLE_CHOICE","e":false,"f":0}],"d":"SINGLE_CHOICE","e":true,"f":0,"g":1,"h":1}]"""

    private val medio = ProductModifier("m-1", "Medio", BigDecimal("0"), ModifierType.SINGLE_CHOICE, required = false, displayOrder = 0)
    private val termino = ModifierGroup(
        id = "g-1", name = "Término", modifiers = listOf(medio), type = ModifierType.SINGLE_CHOICE, required = true, displayOrder = 0,
        minSelections = 1, maxSelections = 1,
    )

    private val legadoExtras =
        """[{"a":"m-1","b":"Medio","c":0,"d":"SINGLE_CHOICE","e":false,"f":0},{"a":"m-2","b":"Extra queso","c":15.50,"d":"MULTIPLE_CHOICE","e":true,"f":2}]"""
    private val queso = ProductModifier("m-2", "Extra queso", BigDecimal("15.50"), ModifierType.MULTIPLE_CHOICE, required = true, displayOrder = 2)

    @Test fun `catalogo guardado por una release vieja (nombres de R8) se lee completo`() {
        assertThat(entidad(legadoGrupos).toDomain().modifierGroups).containsExactly(termino)
    }

    @Test fun `renglon de una orden guardada por una release vieja se lee completo`() {
        val renglon = renglon(legadoExtras).toDomain()

        assertThat(renglon.modifiers).containsExactly(medio, queso).inOrder()
        assertThat(renglon.formattedModifiers).isEqualTo("Medio, Extra queso +$15.50")
    }

    // El mismo lector que OrderSyncCoordinator (Gson() sin configurar + TypeToken<List<ProductModifier>>): sus tres lectores son privados.
    @Test fun `el lector de OrderSyncCoordinator saca ids no nulos del formato viejo`() {
        val tipo = object : TypeToken<List<ProductModifier>>() {}.type
        val extras: List<ProductModifier> = Gson().fromJson(legadoExtras, tipo)

        assertThat(extras.map { it.id }).containsExactly("m-1", "m-2").inOrder()
        assertThat(extras).containsExactly(medio, queso).inOrder()
    }

    @Test fun `el formato nuevo se escribe con los nombres reales y se sigue leyendo`() {
        val json = Gson().toJson(listOf(termino))
        assertThat(json).contains("\"id\":\"g-1\"")
        assertThat(json).contains("\"minSelections\":1")
        assertThat(json).doesNotContain("\"a\":")

        val producto = Product(
            id = "p-1", name = "Hamburguesa", sku = "sku", price = BigDecimal("120.00"), categoryId = "c-1", categoryName = "Comida",
            description = null, emoji = "🍔", imageUrl = null, available = true, modifierGroups = listOf(termino),
        )
        assertThat(producto.toEntity("v1", 100L).toDomain().modifierGroups).containsExactly(termino)

        val item = OrderItem(
            id = "i-1", orderId = "o-1", productId = "p-1", productName = "Hamburguesa", productSku = null, quantity = 1,
            unitPrice = BigDecimal("135.50"), totalPrice = BigDecimal("135.50"), modifiers = listOf(medio, queso), notes = null,
            kitchenStatus = KitchenStatus.PENDING, createdAt = Instant.ofEpochMilli(100L), sentToKitchenAt = null,
        )
        assertThat(item.toEntity(DraftOrderItemEntity.SYNC_STATUS_PENDING, isServerCreated = false).toDomain().modifiers)
            .containsExactly(medio, queso).inOrder()
    }

    @Test fun `renglon con extras de verdad corruptos sale sin extras, sin tronar`() {
        for (raro in listOf("no es json", "{\"a\":1}", "[{\"x\":\"m-1\"}]", "[null]", "[{\"id\":\"m-1\"}]")) {
            val renglon = renglon(raro).toDomain()
            assertWithMessage("modifiers '$raro'").that(renglon.modifiers).isEmpty()
            assertWithMessage("el resto del renglón se conserva ('$raro')").that(renglon.productName).isEqualTo("Hamburguesa")
        }
    }

    private fun entidad(modifierGroupsJson: String) = ProductEntity(
        productId = "p-1", venueId = "v1", name = "Hamburguesa", sku = "sku", price = "120.00", categoryId = "c-1",
        categoryName = "Comida", description = null, emoji = "🍔", imageUrl = null, available = true, displayOrder = 0,
        trackInventory = false, inventoryMethod = null, availableQuantity = null, modifierGroupsJson = modifierGroupsJson, cachedAt = 100L,
    )

    private fun renglon(modifiers: String) = DraftOrderItemEntity(
        id = "i-1", orderId = "o-1", productId = "p-1", productName = "Hamburguesa", productSku = null, quantity = 1,
        unitPrice = "135.50", totalPrice = "135.50", modifiers = modifiers, notes = null, kitchenStatus = "PENDING", createdAt = 100L,
        sentToKitchenAt = null, syncStatus = DraftOrderItemEntity.SYNC_STATUS_PENDING, isServerCreated = false,
    )
}
