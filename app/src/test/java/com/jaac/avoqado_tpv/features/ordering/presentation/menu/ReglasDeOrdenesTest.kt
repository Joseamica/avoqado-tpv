package com.jaac.avoqado_tpv.features.ordering.presentation.menu

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.variantes.AccionAlElegir
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierGroup
import com.jaac.avoqado_tpv.features.ordering.domain.ModifierType
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductModifier
import org.junit.Test
import java.math.BigDecimal

/**
 * Las reglas puras de variantes que hoy usa «Cobrar» (`ReglasDeCobro`, `CheckoutScreen`). Nacieron en «Órdenes», que se quitó
 * de la terminal (D34, 20-nov): estas pruebas son las que de `MenuFamiliasTest` no dependían de su pantalla.
 */
class ReglasDeOrdenesTest {

    private val grupo = "g-playera"

    @Test fun `la variante elegida con extras abre su propia hoja`() {
        val sinExtras = playera("p-s", "S")
        val conExtras = playera("p-m", "M", extras = true)

        assertThat(accionAlElegir(conExtras)).isEqualTo(AccionAlElegir.AbrirExtras(conExtras))
        assertThat(accionAlElegir(sinExtras)).isEqualTo(AccionAlElegir.Agregar(sinExtras))
        // Sin variante en el catálogo vigente no hay nada que hacer.
        assertThat(accionAlElegir(null)).isEqualTo(AccionAlElegir.Cerrar)
    }

    @Test fun `la hoja dice cuantas quedan solo si la variante rastrea inventario y trae el numero`() {
        val base = playera("p-m", "M")
        // Rastrea y trae número: lo que hoy enseña la etiqueta de cada talla suelta.
        assertThat(avisoDeExistenciaEnOrdenes(base.copy(trackInventory = true, availableQuantity = 5))).isEqualTo("5 disponibles")
        assertThat(avisoDeExistenciaEnOrdenes(base.copy(trackInventory = true, availableQuantity = 1))).isEqualTo("1 disponible")
        // No rastrea (existencia ilimitada), aunque el server mande un número: nada que decir.
        assertThat(avisoDeExistenciaEnOrdenes(base.copy(trackInventory = false, availableQuantity = 5))).isNull()
        // Rastrea pero sin número: nunca un cero inventado. Y nunca «Agotado: se puede vender igual.» (eso es de Android).
        assertThat(avisoDeExistenciaEnOrdenes(base.copy(trackInventory = true, availableQuantity = null))).isNull()
    }

    @Test fun `la hoja no puede estar abierta mientras se prepara el pago ni con el escaner abierto`() {
        // Con «Preparando pago…» un artículo nuevo entraría mientras se sincroniza la orden y el cobro saldría corto.
        assertThat(hojaDeVariantesPermitida(preparandoPago = false, escanerAbierto = false)).isTrue()
        assertThat(hojaDeVariantesPermitida(preparandoPago = true, escanerAbierto = false)).isFalse()
        assertThat(hojaDeVariantesPermitida(preparandoPago = false, escanerAbierto = true)).isFalse()
        assertThat(hojaDeVariantesPermitida(preparandoPago = true, escanerAbierto = true)).isFalse()
    }

    private fun playera(
        id: String,
        talla: String,
        disponible: Boolean = true,
        extras: Boolean = false,
        categoria: String = "c-1",
    ) = Product(
        id = id, name = "Playera básica $talla Negro", sku = "sku-$id", price = BigDecimal("199.00"), categoryId = categoria,
        categoryName = "Ropa", description = "Algodón", emoji = "👕", imageUrl = null, available = disponible,
        modifierGroups = if (extras) listOf(grupoDeExtras()) else emptyList(),
        familia = DatosDeFamilia(
            groupId = grupo, groupName = "Playera básica", groupImageUrl = null,
            opciones = listOf(
                OpcionDeVariante("Talla", talla, 0, listOf("S", "M", "L").indexOf(talla)),
                OpcionDeVariante("Color", "Negro", 1, 0),
            ),
        ),
    )

    private fun grupoDeExtras() = ModifierGroup(
        id = "mg-1", name = "Extras", type = ModifierType.MULTIPLE_CHOICE,
        modifiers = listOf(ProductModifier("m-1", "Bordado", BigDecimal("30.00"), ModifierType.MULTIPLE_CHOICE)),
    )
}
