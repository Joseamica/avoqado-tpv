package com.jaac.avoqado_tpv.core.domain.variantes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Los textos de variantes: cada literal igual al de `TextosVariantes.kt` de avoqado-android (y de iOS). */
class TextosVariantesTest {

    @Test
    fun `cada constante es identica al literal del POS Android`() {
        assertThat(TextosVariantes.VARIANTES).isEqualTo("{n} variantes")
        assertThat(TextosVariantes.DESDE).isEqualTo("desde {precio}")
        assertThat(TextosVariantes.AGREGAR).isEqualTo("Agregar al carrito")
        assertThat(TextosVariantes.AGREGAR_CON_PRECIO).isEqualTo("Agregar al carrito · {precio}")
        assertThat(TextosVariantes.FALTA_ELEGIR).isEqualTo("Elige una opción de cada grupo para agregar.")
        assertThat(TextosVariantes.DISPONIBLE_UNO).isEqualTo("1 disponible")
        assertThat(TextosVariantes.DISPONIBLES).isEqualTo("{n} disponibles")
        assertThat(TextosVariantes.AGOTADO).isEqualTo("Agotado")
        assertThat(TextosVariantes.AGOTADO_AVISO).isEqualTo("Agotado: se puede vender igual.")
        assertThat(TextosVariantes.SIN_COMBINACION).isEqualTo("No hay esa combinación")
        assertThat(TextosVariantes.CERRAR).isEqualTo("Cerrar")
        assertThat(TextosVariantes.ACCESIBLE_FAMILIA).isEqualTo("{nombre}, {n} variantes, {precio}")
    }

    @Test
    fun `variantes pone el numero en la plantilla`() {
        assertThat(TextosVariantes.variantes(3)).isEqualTo("3 variantes")
    }

    @Test
    fun `desde pone el precio en la plantilla`() {
        assertThat(TextosVariantes.desde("\$299.00")).isEqualTo("desde \$299.00")
    }

    @Test
    fun `disponibles distingue uno de varios`() {
        assertThat(TextosVariantes.disponibles(1)).isEqualTo("1 disponible")
        assertThat(TextosVariantes.disponibles(3)).isEqualTo("3 disponibles")
        assertThat(TextosVariantes.disponibles(4)).isEqualTo("4 disponibles")
    }

    @Test
    fun `accesibleFamilia junta nombre, cantidad y precio`() {
        assertThat(TextosVariantes.accesibleFamilia("Playera", 3, "desde \$199.00"))
            .isEqualTo("Playera, 3 variantes, desde \$199.00")
    }

    @Test
    fun `agregarCon pone el precio en el boton`() {
        assertThat(TextosVariantes.agregarCon("\$199.00")).isEqualTo("Agregar al carrito · \$199.00")
    }
}
