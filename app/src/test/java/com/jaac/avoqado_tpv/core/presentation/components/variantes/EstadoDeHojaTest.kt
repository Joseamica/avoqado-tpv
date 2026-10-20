package com.jaac.avoqado_tpv.core.presentation.components.variantes

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.variantes.DatosDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.Familia
import com.jaac.avoqado_tpv.core.domain.variantes.OpcionDeVariante
import com.jaac.avoqado_tpv.core.domain.variantes.ReglasDeFamilia
import com.jaac.avoqado_tpv.core.domain.variantes.TextosVariantes
import org.junit.Test
import java.math.BigDecimal

/**
 * Variantes de producto, bloque 4 (TPV), tarea 4: la lógica de la hoja de elegir (`EstadoDeHoja`, sin Compose) y la etiqueta de precio
 * del botón de la familia (`textoDePrecio`, que es la regla de `Familia.textoDePrecio` de avoqado-android sin el caso «/kg»: la
 * terminal no vende por peso en estos modelos).
 */
class EstadoDeHojaTest {

    /** Una variante de prueba: Talla y Color son sus dos ejes; las posiciones salen del orden de [TALLAS] y [COLORES]. */
    private data class P(val id: String, val talla: String, val color: String, val precio: BigDecimal = BigDecimal("199.00"), val agotado: Boolean = false)

    private val TALLAS = listOf("S", "M", "L")
    private val COLORES = listOf("Azul", "Negro", "Rojo")

    private val reglas = ReglasDeFamilia<P>(
        datos = {
            DatosDeFamilia(
                groupId = "g1",
                groupName = "Playera",
                groupImageUrl = null,
                opciones = listOf(
                    OpcionDeVariante("Talla", it.talla, 0, TALLAS.indexOf(it.talla)),
                    OpcionDeVariante("Color", it.color, 1, COLORES.indexOf(it.color)),
                ),
            )
        },
        imagen = { null },
        precio = { it.precio },
        agotado = { it.agotado },
        nombre = { "Playera ${it.talla} ${it.color}" },
        codigos = { emptyList() },
    )

    private fun p(talla: String, color: String, precio: String = "199.00", agotado: Boolean = false) =
        P("$talla-$color", talla, color, BigDecimal(precio), agotado)

    private fun familia(vararg variantes: P): Familia<P> = reglas.familiasDe(variantes.toList()).getValue("g1")

    /** S·Azul, M·Azul, M·Negro, L·Negro: S no tiene Negro y L no tiene Azul. */
    private fun playeras() = familia(p("S", "Azul"), p("M", "Azul"), p("M", "Negro"), p("L", "Negro"))

    private fun estado(f: Familia<P> = playeras()) = EstadoDeHoja(f, reglas)

    // ---- Selección ----

    @Test
    fun `arranca con la seleccion inicial, el eje de un solo valor ya viene elegido`() {
        // Todas las playeras son Azul: el color ya está elegido, sólo falta la talla.
        val e = estado(familia(p("S", "Azul"), p("M", "Azul")))
        assertThat(e.seleccion).containsExactly("Color", "Azul")
        assertThat(e.elegida).isNull()
    }

    @Test
    fun `elegir Talla M y Color Negro da la variante M Negro`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        assertThat(e.elegida).isEqualTo(p("M", "Negro"))
    }

    @Test
    fun `cambiar a una talla sin Negro suelta el color`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        e.elegir("Talla", "S")
        assertThat(e.seleccion).containsExactly("Talla", "S")
        assertThat(e.elegida).isNull()
    }

    @Test
    fun `sin eleccion completa no hay variante elegida`() {
        val e = estado()
        assertThat(e.elegida).isNull()
        e.elegir("Talla", "M")
        assertThat(e.elegida).isNull()
    }

    @Test
    fun `una combinacion que no existe no es una variante elegida`() {
        val e = estado()
        e.elegir("Talla", "S")
        e.elegir("Color", "Negro")   // el chip se puede tocar aunque esté atenuado (Q3)…
        assertThat(e.elegida).isNull()   // …pero no hay S·Negro
    }

    // ---- Una sola entrega por apertura (Codex r1 #5) ----

    @Test
    fun `entregar dos veces devuelve la variante y luego null`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        assertThat(e.entregar()).isEqualTo(p("M", "Negro"))
        assertThat(e.entregar()).isNull()
    }

    @Test
    fun `entregar sin eleccion completa devuelve null y no gasta la entrega`() {
        val e = estado()
        assertThat(e.entregar()).isNull()
        e.elegir("Talla", "L")
        e.elegir("Color", "Negro")
        assertThat(e.entregar()).isEqualTo(p("L", "Negro"))
    }

    // ---- Catálogo que cambia con la hoja abierta ----

    @Test
    fun `si la familia cambia, la eleccion que sigue existiendo se conserva`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        e.actualizar(familia(p("S", "Azul"), p("M", "Azul"), p("M", "Negro"), p("L", "Negro"), p("L", "Azul")))
        assertThat(e.seleccion).containsExactly("Talla", "M", "Color", "Negro")
        assertThat(e.elegida).isEqualTo(p("M", "Negro"))
    }

    @Test
    fun `si un valor elegido ya no existe, se suelta y lo demas se conserva`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        e.actualizar(familia(p("S", "Azul"), p("M", "Azul"), p("L", "Azul"), p("S", "Rojo")))   // se acabó el Negro
        assertThat(e.seleccion).containsExactly("Talla", "M")
        assertThat(e.elegida).isNull()
    }

    @Test
    fun `si los dos valores existen pero ya no combinan, se queda la talla y se suelta el color`() {
        val e = estado()
        e.elegir("Talla", "L")
        e.elegir("Color", "Negro")
        e.actualizar(familia(p("S", "Azul"), p("L", "Azul"), p("M", "Negro")))   // L·Negro ya no existe
        assertThat(e.seleccion).containsExactly("Talla", "L")
        assertThat(e.elegida).isNull()
    }

    @Test
    fun `si la familia cambia y un eje queda con un solo valor, ese eje se elige solo`() {
        val e = estado()
        e.actualizar(familia(p("S", "Negro"), p("M", "Negro")))
        assertThat(e.seleccion).containsExactly("Color", "Negro")
    }

    @Test
    fun `cambiar la familia no devuelve la entrega`() {
        val e = estado()
        e.elegir("Talla", "M")
        e.elegir("Color", "Negro")
        assertThat(e.entregar()).isNotNull()
        e.actualizar(familia(p("S", "Azul"), p("M", "Azul"), p("M", "Negro"), p("L", "Negro"), p("L", "Azul")))
        assertThat(e.entregar()).isNull()
    }

    // ---- Etiqueta de precio del botón de la familia (port de FamiliasTest.kt:306-326 de Android, sin «/kg») ----

    private fun formato(precio: BigDecimal) = "$" + precio.setScale(2)

    private fun familiaDePrecios(vararg precios: String): Familia<P> =
        familia(*precios.mapIndexed { i, precio -> p(TALLAS[i], "Azul", precio) }.toTypedArray())

    @Test
    fun `la familia con precios distintos dice desde el minimo`() {
        assertThat(reglas.textoDePrecio(playerasDeDistintoPrecio(), ::formato)).isEqualTo("desde \$199.00")
    }

    @Test
    fun `la familia con todos los precios iguales dice el precio solo`() {
        assertThat(reglas.textoDePrecio(familiaDePrecios("199.00", "199.0"), ::formato)).isEqualTo("\$199.00")
    }

    @Test
    fun `el desde usa el precio mas bajo aunque no sea el de la primera variante`() {
        assertThat(reglas.textoDePrecio(familiaDePrecios("220.00", "180.00", "250.00"), ::formato)).isEqualTo("desde \$180.00")
    }

    @Test
    fun `el texto de precio usa TextosVariantes desde, igual que el POS Android`() {
        assertThat(reglas.textoDePrecio(playerasDeDistintoPrecio()) { "X" }).isEqualTo(TextosVariantes.desde("X"))
    }

    private fun playerasDeDistintoPrecio() =
        familia(p("S", "Azul", "199.00"), p("M", "Azul", "249.00"), p("M", "Negro", "249.00"), p("L", "Negro", "299.00"))
}
