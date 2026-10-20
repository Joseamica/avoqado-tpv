package com.jaac.avoqado_tpv.core.data.network.variantes

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.features.kiosk.presentation.toKioskProduct
import com.jaac.avoqado_tpv.features.ordering.data.dto.ProductDto
import com.jaac.avoqado_tpv.features.ordering.data.dto.ProductsResponse
import com.jaac.avoqado_tpv.features.ordering.data.mappers.toDomain
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ProductCatalogDto
import com.jaac.avoqado_tpv.features.tables.data.api.dto.ProductsCatalogResponse
import com.jaac.avoqado_tpv.features.tables.data.toMenuProduct
import org.junit.Test

/**
 * La terminal lee los 4 campos de familia que manda `GET tpv/venues/{venueId}/products` en los DOS DTO (Órdenes/Cobro/Kiosco y
 * Mesas), con Gson real y sin GsonBuilder (como `NetworkModule`): un campo con forma rara NO puede tumbar el catálogo entero.
 */
class ProductoConFamiliaDecodeTest {

    private val gson = Gson()

    private val grupo = "cmgrpplayera00000000000001"

    private val opcionesReales = """[
        {"name":"Talla","value":"M","namePosition":0,"valuePosition":1},
        {"name":"Color","value":"Negro","namePosition":1,"valuePosition":0}
    ]"""

    private val esperadas = listOf(
        OpcionDeVariante(name = "Talla", value = "M", namePosition = 0, valuePosition = 1),
        OpcionDeVariante(name = "Color", value = "Negro", namePosition = 1, valuePosition = 0),
    )

    /** Un producto tal como lo manda el server; [extras] son los campos de familia (o nada, para el JSON de hoy). */
    private fun producto(id: String, extras: String = ""): String = """{
        "id":"$id","name":"Playera básica $id","description":null,"price":199.0,"type":"SIMPLE","sku":"SKU-$id",
        "categoryId":"cat1","active":true,"displayOrder":1,"trackInventory":false,"inventoryMethod":null,"imageUrl":null,
        "category":{"id":"cat1","name":"Ropa","venueId":"v1","displayOrder":1,"active":true},
        "inventory":null,"modifierGroups":[],"availableQuantity":null
        ${if (extras.isBlank()) "" else ",$extras"}
    }"""

    private fun respuesta(vararg productos: String) =
        """{"message":"ok","data":[${productos.joinToString(",")}],"correlationId":"x"}"""

    private fun conFamilia(variantOptions: String) = """
        "productGroupId":"$grupo","productGroupName":"Playera básica","productGroupImageUrl":null,
        "variantOptions":$variantOptions
    """

    private fun ordenes(json: String) = gson.fromJson(json, ProductsResponse::class.java).data
    private fun mesas(json: String) = gson.fromJson(json, ProductsCatalogResponse::class.java).data

    @Test
    fun `producto con familia decodifica los 4 campos y el mapper los lleva a Product, KioskProduct y MenuProduct`() {
        val json = respuesta(producto("p1", conFamilia(opcionesReales)))
        val esperada = DatosDeFamilia(grupo, "Playera básica", null, esperadas)

        val product = ordenes(json).single().toDomain()
        assertThat(product.familia).isEqualTo(esperada)
        assertThat(product.toKioskProduct().familia).isEqualTo(esperada)
        assertThat(mesas(json).single().toMenuProduct().familia).isEqualTo(esperada)
    }

    @Test
    fun `producto con imagen de familia la lleva a los tres modelos`() {
        val json = respuesta(
            producto("p1", conFamilia(opcionesReales).replace(""""productGroupImageUrl":null""", """"productGroupImageUrl":"https://x/y.png"""")),
        )
        assertThat(ordenes(json).single().toDomain().familia?.groupImageUrl).isEqualTo("https://x/y.png")
        assertThat(mesas(json).single().toMenuProduct().familia?.groupImageUrl).isEqualTo("https://x/y.png")
    }

    @Test
    fun `producto sin los campos queda suelto`() {
        val json = respuesta(producto("p1"))

        val product = ordenes(json).single().toDomain()
        assertThat(product.familia).isNull()
        assertThat(product.toKioskProduct().familia).isNull()
        assertThat(mesas(json).single().toMenuProduct().familia).isNull()
    }

    @Test
    fun `groupId vacio o nulo queda suelto aunque traiga opciones`() {
        val json = respuesta(
            producto("p1", """"productGroupId":null,"productGroupName":"X","variantOptions":$opcionesReales"""),
            producto("p2", """"productGroupId":"","productGroupName":"X","variantOptions":$opcionesReales"""),
        )
        assertThat(ordenes(json).map { it.toDomain().familia }).containsExactly(null, null)
        assertThat(mesas(json).map { it.toMenuProduct().familia }).containsExactly(null, null)
    }

    @Test
    fun `variantOptions raro no tumba el catalogo`() {
        val raros = listOf(
            """{"x":1}""",
            """[{}]""",
            """[{"name":"","value":"M","namePosition":0,"valuePosition":0}]""",
            """[{"name":"   ","value":"M","namePosition":0,"valuePosition":0}]""",
            """[{"name":"Talla","value":"","namePosition":0,"valuePosition":0}]""",
            """[{"name":"Talla","value":"M","namePosition":"0","valuePosition":0}]""",
            """[{"name":"Talla","value":"M","namePosition":0.5,"valuePosition":0}]""",
            """[{"name":"Talla","value":"M","namePosition":0}]""",
            """[{"name":"Talla","value":"M","namePosition":null,"valuePosition":0}]""",
            """[{"name":"Talla","value":7,"namePosition":0,"valuePosition":0}]""",
            """[1,2]""",
            """[null]""",
            "\"Talla\"",
            "42",
            "true",
            // una buena junto a una mala: no se adivina, la lista entera se descarta
            """[{"name":"Talla","value":"M","namePosition":0,"valuePosition":0},{}]""",
        )
        val json = respuesta(
            producto("bueno", conFamilia(opcionesReales)),
            *raros.mapIndexed { i, r -> producto("raro$i", conFamilia(r)) }.toTypedArray(),
            producto("suelto"),
        )

        val ordenesProductos = ordenes(json)
        assertThat(ordenesProductos).hasSize(raros.size + 2)
        assertThat(ordenesProductos.first().toDomain().familia?.opciones).isEqualTo(esperadas)
        ordenesProductos.drop(1).dropLast(1).forEachIndexed { i, dto ->
            assertWithMessage("caso %s", raros[i]).that(dto.toDomain().familia?.opciones).isNull()
        }

        val mesasProductos = mesas(json)
        assertThat(mesasProductos).hasSize(raros.size + 2)
        assertThat(mesasProductos.first().toMenuProduct().familia?.opciones).isEqualTo(esperadas)
        mesasProductos.drop(1).dropLast(1).forEach { assertThat(it.toMenuProduct().familia?.opciones).isNull() }
    }

    @Test
    fun `variantOptions nulo o vacio no inventa opciones`() {
        val json = respuesta(
            producto("p1", conFamilia("null")),
            producto("p2", conFamilia("[]")),
        )
        val (nulo, vacio) = ordenes(json).map { it.toDomain().familia }
        assertThat(nulo?.opciones).isNull()
        assertThat(vacio?.opciones).isEmpty()
        assertThat(mesas(json)[0].toMenuProduct().familia?.opciones).isNull()
        assertThat(mesas(json)[1].toMenuProduct().familia?.opciones).isEmpty()
    }

    @Test
    fun `posiciones enteras escritas como 1_0 se aceptan`() {
        val json = respuesta(
            producto("p1", conFamilia("""[{"name":"Talla","value":"M","namePosition":1.0,"valuePosition":2}]""")),
        )
        assertThat(ordenes(json).single().toDomain().familia?.opciones)
            .containsExactly(OpcionDeVariante("Talla", "M", 1, 2))
    }

    @Test
    fun `ningun DTO declara un campo variants`() {
        listOf(ProductDto::class.java, ProductCatalogDto::class.java).forEach { clase ->
            clase.declaredFields.forEach { campo ->
                assertThat(campo.name).isNotEqualTo("variants")
                assertThat(campo.getAnnotation(SerializedName::class.java)?.value).isNotEqualTo("variants")
            }
        }
    }
}
