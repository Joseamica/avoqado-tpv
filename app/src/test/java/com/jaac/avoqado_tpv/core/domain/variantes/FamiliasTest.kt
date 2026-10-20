package com.jaac.avoqado_tpv.core.domain.variantes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.math.BigDecimal

/**
 * Variantes de producto, bloque 4 (TPV): las reglas puras de familias de `Familias.kt`.
 * Los casos A1–B8 (salvo B7) son LOS MISMOS (y con los mismos nombres) que `FamiliasTest.kt` de avoqado-android y
 * `ProductGroupTests.swift` de avoqado-ios; B7 es sólo del POS Android (no recorta espacios al buscar) y aquí se conserva igual.
 * Lo nuevo de la terminal: `BigDecimal` en vez de `Double` y `entradasParaMostrar` (un código nunca muestra la familia).
 */
class FamiliasTest {

    /** Un producto de prueba: la terminal tiene tres modelos distintos, las reglas sólo piden estos accesos. */
    private data class P(
        val id: String,
        val nombre: String,
        val precio: BigDecimal,
        val sku: String? = null,
        val barcode: String? = null,
        val gtin: String? = null,
        val groupId: String? = null,
        val groupName: String? = null,
        val groupImageUrl: String? = null,
        val opciones: List<OpcionDeVariante>? = null,
        val agotado: Boolean = false,
        val imagen: String? = null,
    )

    private val reglas = ReglasDeFamilia<P>(
        datos = { DatosDeFamilia(it.groupId, it.groupName, it.groupImageUrl, it.opciones) },
        imagen = { it.imagen },
        precio = { it.precio },
        agotado = { it.agotado },
        nombre = { it.nombre },
        codigos = { listOf(it.sku, it.barcode, it.gtin) },
    )

    // ---- Montaje ----

    private fun op(nombre: String, valor: String, np: Int, vp: Int) = OpcionDeVariante(nombre, valor, np, vp)

    private fun m(pesos: String) = BigDecimal(pesos)

    private fun prod(
        id: String,
        nombre: String,
        precio: String,
        sku: String? = null,
        gtin: String? = null,
        grupo: String? = null,
        nombreGrupo: String? = null,
        opciones: List<OpcionDeVariante>? = null,
        agotado: Boolean = false,
        imagen: String? = null,
        imagenGrupo: String? = null,
    ) = P(
        id = id, nombre = nombre, precio = m(precio), sku = sku, gtin = gtin, groupId = grupo, groupName = nombreGrupo,
        groupImageUrl = imagenGrupo, opciones = opciones, agotado = agotado, imagen = imagen,
    )

    /** `Playera · S · Azul` y compañía: familia `g1` «Playera». */
    private fun playera(
        id: String,
        talla: Pair<String, Int>,
        color: Pair<String, Int>,
        precio: String,
        sku: String,
        gtin: String? = null,
        agotado: Boolean = false,
    ) = prod(
        id, "Playera · ${talla.first} · ${color.first}", precio, sku = sku, gtin = gtin, grupo = "g1", nombreGrupo = "Playera",
        opciones = listOf(op("Talla", talla.first, 0, talla.second), op("Color", color.first, 1, color.second)),
        agotado = agotado,
    )

    /** El catálogo de `casos-compartidos.md`, en este orden. */
    private fun catalogo() = listOf(
        prod("agua", "Agua", "20.00"),
        playera("pl-s-az", "S" to 0, "Azul" to 0, "199.00", "PL-S-AZ"),
        prod("gorra", "Gorra", "150.00", sku = "GO-1"),
        playera("pl-m-az", "M" to 1, "Azul" to 0, "199.00", "PL-M-AZ"),
        playera("pl-s-ro", "S" to 0, "Rojo" to 1, "219.00", "PL-S-RO", gtin = "7501000000017", agotado = true),
        prod("taza", "Taza · Grande", "99.00", grupo = "g2", nombreGrupo = "Taza", opciones = listOf(op("Tamaño", "Grande", 0, 1))),
    )

    private fun por(id: String, productos: List<P>) = productos.first { it.id == id }

    // ---- Formas legibles para comparar ----

    private fun formaEntradas(entradas: List<EntradaDeCatalogo<P>>) = entradas.map {
        when (it) {
            is EntradaDeCatalogo.Suelto -> "suelto:${it.producto.id}"
            is EntradaDeCatalogo.DeFamilia -> "familia:${it.familia.groupId}[${it.familia.variantes.joinToString(",") { v -> v.id }}]"
        }
    }

    private fun formaResultados(resultados: List<ResultadoDeBusqueda<P>>) = resultados.map {
        when (it) {
            is ResultadoDeBusqueda.DeProducto -> "producto:${it.producto.id}"
            is ResultadoDeBusqueda.DeFamilia -> "familia:${it.familia.groupId}"
        }
    }

    private fun agrupar(x: List<P>) = reglas.agruparCatalogo(x, reglas.familiasDe(catalogo()))

    private fun buscar(x: List<P>, q: String) = reglas.buscar(x, reglas.familiasDe(x), q)

    private fun familiaG1() = reglas.familiasDe(catalogo()).getValue("g1")

    private val todasSueltas =
        listOf("suelto:agua", "suelto:pl-s-az", "suelto:gorra", "suelto:pl-m-az", "suelto:pl-s-ro", "suelto:taza")

    // ---- Q1 · qué es una familia ----

    @Test
    fun `familia valida necesita 2 o mas variantes con los mismos ejes`() {
        val una = listOf(playera("solo", "S" to 0, "Azul" to 0, "199.00", "PL-S-AZ"))
        assertThat(reglas.familiasDe(una)).isEmpty()

        val dos = listOf(
            playera("a", "S" to 0, "Azul" to 0, "199.00", "A"),
            playera("b", "M" to 1, "Azul" to 0, "199.00", "B"),
        )
        val familias = reglas.familiasDe(dos)
        assertThat(familias.keys).containsExactly("g1")
        assertThat(familias.getValue("g1").ejes.map { it.nombre }).containsExactly("Talla", "Color").inOrder()
    }

    @Test
    fun `ejes distintos o datos incompletos dejan la familia suelta`() {
        val sinColor = listOf(
            playera("a", "S" to 0, "Azul" to 0, "199.00", "A"),
            prod("b", "Playera · M", "199.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1))),
        )
        assertThat(reglas.familiasDe(sinColor)).isEmpty()

        val valorVacio = listOf(
            playera("a", "S" to 0, "Azul" to 0, "199.00", "A"),
            prod("b", "Playera · M", "199.00", grupo = "g1", nombreGrupo = "Playera",
                opciones = listOf(op("Talla", "", 0, 1), op("Color", "Azul", 1, 0))),
        )
        assertThat(reglas.familiasDe(valorVacio)).isEmpty()
    }

    @Test
    fun `ejes ordenados por namePosition y valores por valuePosition`() {
        // Llegan desordenados: Color antes que Talla, y M, L, S.
        val desordenadas = listOf(
            prod("l", "L", "100.00", grupo = "g", nombreGrupo = "G",
                opciones = listOf(op("Color", "Azul", 1, 0), op("Talla", "L", 0, 2))),
            prod("s", "S", "100.00", grupo = "g", nombreGrupo = "G",
                opciones = listOf(op("Color", "Azul", 1, 0), op("Talla", "S", 0, 0))),
            prod("m", "M", "100.00", grupo = "g", nombreGrupo = "G",
                opciones = listOf(op("Color", "Azul", 1, 0), op("Talla", "M", 0, 1))),
        )
        val ejes = reglas.familiasDe(desordenadas).getValue("g").ejes
        assertThat(ejes.map { it.nombre }).containsExactly("Talla", "Color").inOrder()
        assertThat(ejes.first().valores).containsExactly("S", "M", "L").inOrder()
    }

    // ---- A · agrupar el catálogo ----

    @Test
    fun `A1 sin familias la lista sale identica y en el mismo orden`() {
        val c = catalogo()
        val sueltos = listOf(por("agua", c), por("gorra", c))
        assertThat(agrupar(sueltos)).containsExactly(
            EntradaDeCatalogo.Suelto(por("agua", c)),
            EntradaDeCatalogo.Suelto(por("gorra", c)),
        ).inOrder()
    }

    @Test
    fun `A2 la familia va donde aparece su primera variante`() {
        assertThat(formaEntradas(agrupar(catalogo())))
            .containsExactly("suelto:agua", "familia:g1[pl-s-az,pl-m-az,pl-s-ro]", "suelto:gorra", "suelto:taza").inOrder()
    }

    @Test
    fun `agrupar coloca la familia en el lugar de su primera variante y una sola vez`() {
        val a = prod("A", "Agua", "20.00")
        val b = prod("B", "Gorra", "150.00")
        val mNegro = prod("mn", "Playera · M · Negro", "299.00", sku = "PLAYERA-BASICA-M-NEGRO", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Negro", 1, 0)))
        val lNegro = prod("ln", "Playera · L · Negro", "299.00", sku = "PLAYERA-BASICA-L-NEGRO", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", "L", 0, 2), op("Color", "Negro", 1, 0)))
        val lista = listOf(a, mNegro, b, lNegro)

        val entradas = reglas.agruparCatalogo(lista, reglas.familiasDe(lista))
        assertThat(formaEntradas(entradas)).containsExactly("suelto:A", "familia:g1[mn,ln]", "suelto:B").inOrder()
    }

    @Test
    fun `A3 una variante sin nombre de familia deja sueltas las tres de su familia`() {
        val c = catalogo().toMutableList()
        c[3] = prod(
            "pl-m-az", "Playera · M · Azul", "199.00", sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "",
            opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertThat(formaEntradas(reglas.agruparCatalogo(c, reglas.familiasDe(c)))).containsExactlyElementsIn(todasSueltas).inOrder()
    }

    @Test
    fun `A4 una variante sin opciones deja sueltas las tres de su familia`() {
        val c = catalogo().toMutableList()
        c[3] = prod("pl-m-az", "Playera · M · Azul", "199.00", sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera", opciones = null)
        assertThat(c[3].opciones).isNull()
        assertThat(formaEntradas(reglas.agruparCatalogo(c, reglas.familiasDe(c)))).containsExactlyElementsIn(todasSueltas).inOrder()
    }

    @Test
    fun `A5 ejes distintos entre variantes dejan sueltas las tres`() {
        val c = catalogo().toMutableList()
        c[3] = prod(
            "pl-m-az", "Playera · M", "199.00", sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", "M", 0, 1)),
        )
        assertThat(formaEntradas(reglas.agruparCatalogo(c, reglas.familiasDe(c)))).containsExactlyElementsIn(todasSueltas).inOrder()
    }

    @Test
    fun `A6 una opcion con nombre o valor en blanco deja sueltas las tres`() {
        val valorEnBlanco = catalogo().toMutableList()
        valorEnBlanco[3] = prod(
            "pl-m-az", "Playera · M · Azul", "199.00", sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op("Talla", " ", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertThat(formaEntradas(reglas.agruparCatalogo(valorEnBlanco, reglas.familiasDe(valorEnBlanco))))
            .containsExactlyElementsIn(todasSueltas).inOrder()

        val nombreEnBlanco = catalogo().toMutableList()
        nombreEnBlanco[3] = prod(
            "pl-m-az", "Playera · M · Azul", "199.00", sku = "PL-M-AZ", grupo = "g1", nombreGrupo = "Playera",
            opciones = listOf(op(" ", "M", 0, 1), op("Color", "Azul", 1, 0)),
        )
        assertThat(formaEntradas(reglas.agruparCatalogo(nombreEnBlanco, reglas.familiasDe(nombreEnBlanco))))
            .containsExactlyElementsIn(todasSueltas).inOrder()
    }

    @Test
    fun `A7 la familia se valida contra el catalogo completo aunque la lista traiga una sola variante`() {
        val completo = catalogo()
        val mostrada = listOf(por("pl-m-az", completo), por("gorra", completo))
        assertThat(formaEntradas(reglas.agruparCatalogo(mostrada, reglas.familiasDe(completo))))
            .containsExactly("familia:g1[pl-s-az,pl-m-az,pl-s-ro]", "suelto:gorra").inOrder()
    }

    @Test
    fun `familia partida por filtro sigue saliendo como familia`() {
        // Review Focus 1: las familias se calculan con las 3 variantes; la categoría sólo deja 1 a la vista.
        val completo = listOf(
            prod("m", "Playera · M", "299.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1))),
            prod("s", "Playera · S", "299.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "S", 0, 0))),
            prod("l", "Playera · L", "299.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "L", 0, 2))),
        )
        val familias = reglas.familiasDe(completo)
        val filtrada = listOf(completo[1])
        val entradas = reglas.agruparCatalogo(filtrada, familias)
        assertThat(entradas).hasSize(1)
        val unica = entradas.single() as EntradaDeCatalogo.DeFamilia
        assertThat(unica.familia.variantes.map { it.id }).containsExactly("m", "s", "l").inOrder()
    }

    @Test
    fun `A8 un eje con el mismo nombre en dos posiciones deja sueltas sus variantes`() {
        // Misma firma de ejes (Talla, Color) en las dos, pero «Color» llega con namePosition 1 y 2: ejesDe daría Color dos veces.
        val c = listOf(
            prod("gx-1", "Camisa · S · Azul", "100.00", grupo = "gx", nombreGrupo = "Camisa",
                opciones = listOf(op("Talla", "S", 0, 0), op("Color", "Azul", 1, 0))),
            prod("gx-2", "Camisa · M · Azul", "100.00", grupo = "gx", nombreGrupo = "Camisa",
                opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Azul", 2, 0))),
        )
        assertThat(formaEntradas(reglas.agruparCatalogo(c, reglas.familiasDe(c)))).containsExactly("suelto:gx-1", "suelto:gx-2").inOrder()
    }

    // ---- E · ejes y selección inicial ----

    @Test
    fun `E1 los ejes van por posicion y sus valores por posicion`() {
        val f = familiaG1()
        assertThat(f.ejes).containsExactly(
            EjeDeFamilia("Talla", listOf("S", "M")),
            EjeDeFamilia("Color", listOf("Azul", "Rojo")),
        ).inOrder()
        assertThat(reglas.ejesDe(f.variantes)).isEqualTo(f.ejes)
    }

    @Test
    fun `E2 el eje de un solo valor viene elegido`() {
        val c = listOf(
            prod("t1", "Taza · Grande · Té", "80.00", grupo = "g2", nombreGrupo = "Taza",
                opciones = listOf(op("Tamaño", "Grande", 0, 0), op("Sabor", "Té", 1, 0))),
            prod("t2", "Taza · Grande · Café", "85.00", grupo = "g2", nombreGrupo = "Taza",
                opciones = listOf(op("Tamaño", "Grande", 0, 0), op("Sabor", "Café", 1, 1))),
        )
        val f = reglas.familiasDe(c).getValue("g2")
        assertThat(f.ejes).containsExactly(
            EjeDeFamilia("Tamaño", listOf("Grande")),
            EjeDeFamilia("Sabor", listOf("Té", "Café")),
        ).inOrder()
        assertThat(reglas.seleccionInicial(f)).containsExactly("Tamaño", "Grande")
        assertThat(reglas.seleccionInicial(familiaG1())).isEmpty()
    }

    // ---- S · selección ----

    @Test
    fun `S1 la eleccion completa da la variante`() {
        assertThat(reglas.varianteElegida(familiaG1(), mapOf("Talla" to "S", "Color" to "Rojo"))?.id).isEqualTo("pl-s-ro")
    }

    @Test
    fun `S2 una eleccion incompleta no da variante`() {
        assertThat(reglas.varianteElegida(familiaG1(), mapOf("Talla" to "M"))).isNull()
    }

    @Test
    fun `S3 un valor que no combina con lo elegido no esta disponible`() {
        val f = familiaG1()
        assertThat(reglas.valorDisponible(f, mapOf("Talla" to "M"), "Color", "Rojo")).isFalse()
        assertThat(reglas.valorDisponible(f, mapOf("Talla" to "M"), "Color", "Azul")).isTrue()
        assertThat(reglas.valorDisponible(f, mapOf("Talla" to "S"), "Color", "Rojo")).isTrue()
    }

    @Test
    fun `S4 elegir un valor que no combina suelta la eleccion del otro eje`() {
        assertThat(reglas.elegir(familiaG1(), mapOf("Talla" to "M", "Color" to "Azul"), "Color", "Rojo"))
            .containsExactly("Color", "Rojo")
    }

    @Test
    fun `S5 elegir un valor que combina conserva lo ya elegido`() {
        assertThat(reglas.elegir(familiaG1(), mapOf("Talla" to "S"), "Color", "Rojo"))
            .containsExactly("Talla", "S", "Color", "Rojo")
    }

    @Test
    fun `S6 con tres ejes elegir conserva en orden de eje lo que sigue combinando`() {
        // g3 = (S,Azul,Algodon), (S,Rojo,Poliester), (M,Azul,Poliester); ejes Talla(0), Color(1), Material(2).
        fun camiseta(id: String, talla: Pair<String, Int>, color: Pair<String, Int>, material: Pair<String, Int>) = prod(
            id, "Camiseta · ${talla.first} · ${color.first} · ${material.first}", "120.00", grupo = "g3", nombreGrupo = "Camiseta",
            opciones = listOf(
                op("Talla", talla.first, 0, talla.second),
                op("Color", color.first, 1, color.second),
                op("Material", material.first, 2, material.second),
            ),
        )
        val c = listOf(
            camiseta("c-s-az-al", "S" to 0, "Azul" to 0, "Algodón" to 0),
            camiseta("c-s-ro-po", "S" to 0, "Rojo" to 1, "Poliéster" to 1),
            camiseta("c-m-az-po", "M" to 1, "Azul" to 0, "Poliéster" to 1),
        )
        val g3 = reglas.familiasDe(c).getValue("g3")
        // S·Azul·Poliéster no existe, pero S·Poliéster sí: Talla:S se conserva y sólo Color:Azul se suelta.
        assertThat(reglas.elegir(g3, mapOf("Talla" to "S", "Color" to "Azul"), "Material", "Poliéster"))
            .containsExactly("Material", "Poliéster", "Talla", "S")
    }

    // ---- P · precio (BigDecimal) ----

    @Test
    fun `P1 el precio es el minimo y no todos son iguales`() {
        val f = familiaG1()
        assertThat(reglas.precioMinimo(f)).isEqualTo(m("199.00"))
        assertThat(reglas.preciosIguales(f)).isFalse()
    }

    @Test
    fun `P2 con todos los precios iguales el boton dice el precio solo`() {
        val c = listOf(
            playera("a", "S" to 0, "Azul" to 0, "199.00", "A"),
            playera("b", "M" to 1, "Azul" to 0, "199.00", "B"),
        )
        val f = reglas.familiasDe(c).getValue("g1")
        assertThat(reglas.precioMinimo(f)).isEqualTo(m("199.00"))
        assertThat(reglas.preciosIguales(f)).isTrue()
    }

    @Test
    fun `precio minimo 299 y no iguales con XL a 329`() {
        fun camisa(id: String, talla: String, pos: Int, precio: String) = prod(
            id, "Camisa · $talla", precio, grupo = "gc", nombreGrupo = "Camisa", opciones = listOf(op("Talla", talla, 0, pos)),
        )
        val igual = reglas.familiasDe(listOf(camisa("s", "S", 0, "299.00"), camisa("m", "M", 1, "299.00"))).getValue("gc")
        assertThat(reglas.precioMinimo(igual)).isEqualTo(m("299.00"))
        assertThat(reglas.preciosIguales(igual)).isTrue()

        val conXl = reglas.familiasDe(
            listOf(camisa("s", "S", 0, "299.00"), camisa("m", "M", 1, "299.00"), camisa("xl", "XL", 2, "329.00")),
        ).getValue("gc")
        assertThat(reglas.precioMinimo(conXl)).isEqualTo(m("299.00"))
        assertThat(reglas.preciosIguales(conXl)).isFalse()
    }

    @Test
    fun `el mismo precio con distinta escala sigue siendo igual`() {
        // 199.0 y 199.00 son el mismo dinero: BigDecimal.equals los distingue, compareTo no.
        val f = reglas.familiasDe(
            listOf(
                prod("a", "Camisa · S", "199.0", grupo = "gc", nombreGrupo = "Camisa", opciones = listOf(op("Talla", "S", 0, 0))),
                prod("b", "Camisa · M", "199.00", grupo = "gc", nombreGrupo = "Camisa", opciones = listOf(op("Talla", "M", 0, 1))),
            ),
        ).getValue("gc")
        assertThat(reglas.preciosIguales(f)).isTrue()
    }

    // ---- G · agotada ----

    @Test
    fun `agotada solo si todas lo estan`() {
        assertThat(reglas.agotada(familiaG1())).isFalse()
        val todas = listOf(
            playera("a", "S" to 0, "Azul" to 0, "199.00", "A", agotado = true),
            playera("b", "M" to 1, "Azul" to 0, "199.00", "B", agotado = true),
        )
        assertThat(reglas.agotada(reglas.familiasDe(todas).getValue("g1"))).isTrue()
    }

    // ---- B · búsqueda ----

    @Test
    fun `B1 por nombre sale la familia una sola vez`() {
        assertThat(formaResultados(buscar(catalogo(), "play"))).containsExactly("familia:g1")
    }

    @Test
    fun `B2 por sku sale la variante y no la familia`() {
        assertThat(formaResultados(buscar(catalogo(), "PL-S"))).containsExactly("producto:pl-s-az", "producto:pl-s-ro").inOrder()
    }

    @Test
    fun `buscar por nombre devuelve la familia una vez y por sku la variante`() {
        val c = catalogo()
        assertThat(formaResultados(buscar(c, "playera"))).containsExactly("familia:g1")
        assertThat(formaResultados(buscar(c, "pl-m-az"))).containsExactly("producto:pl-m-az")
    }

    @Test
    fun `B3 por gtin sale la variante`() {
        assertThat(formaResultados(buscar(catalogo(), "7501000000017"))).containsExactly("producto:pl-s-ro")
    }

    @Test
    fun `B4 un suelto sigue saliendo como producto`() {
        assertThat(formaResultados(buscar(catalogo(), "gor"))).containsExactly("producto:gorra")
    }

    @Test
    fun `B5 un texto en blanco no busca nada`() {
        assertThat(formaResultados(buscar(catalogo(), "  "))).isEmpty()
        assertThat(formaResultados(buscar(catalogo(), ""))).isEmpty()
    }

    @Test
    fun `B6 sin familias la busqueda es la de hoy`() {
        val c = catalogo()
        assertThat(formaResultados(buscar(listOf(por("agua", c), por("gorra", c)), "go-1"))).containsExactly("producto:gorra")
    }

    @Test
    fun `B8 por barcode sale la variante`() {
        val c = catalogo().toMutableList().also { it[1] = it[1].copy(barcode = "BARRA-0042") }
        assertThat(formaResultados(buscar(c, "barra-0042"))).containsExactly("producto:pl-s-az")
    }

    /** Sólo el POS Android: hoy no recorta espacios al buscar (Codex 8); aquí se conserva igual. */
    @Test
    fun `B7 los espacios de los lados no se recortan como hoy`() {
        val c = catalogo()
        assertThat(formaResultados(buscar(listOf(por("agua", c), por("gorra", c)), " gor "))).isEmpty()
    }

    // ---- entradasParaMostrar (lo nuevo de la terminal) ----

    private fun playeraBasica(): List<P> = listOf(
        prod("m-negro", "Playera básica · M · Negro", "299.00", sku = "PLAYERA-BASICA-M-NEGRO", grupo = "g1", nombreGrupo = "Playera básica",
            opciones = listOf(op("Talla", "M", 0, 1), op("Color", "Negro", 1, 0))),
        prod("l-negro", "Playera básica · L · Negro", "299.00", sku = "PLAYERA-BASICA-L-NEGRO", grupo = "g1", nombreGrupo = "Playera básica",
            opciones = listOf(op("Talla", "L", 0, 2), op("Color", "Negro", 1, 0))),
        prod("gorra", "Gorra", "150.00", sku = "GO-1"),
    )

    @Test
    fun `entradas con texto de codigo muestran la talla suelta y no la familia`() {
        // Codex r1 #1: quien escanea o teclea «PLAYERA-BASICA-M-NEGRO» quiere ESA talla, no el botón de la familia.
        val visibles = playeraBasica()
        val familias = reglas.familiasDe(visibles)
        val entradas = reglas.entradasParaMostrar(visibles, familias, "PLAYERA-BASICA-M-NEGRO")
        assertThat(entradas).containsExactly(EntradaDeCatalogo.Suelto(visibles[0]))
    }

    @Test
    fun `entradas con texto de nombre muestran la familia una vez`() {
        val visibles = playeraBasica()
        val entradas = reglas.entradasParaMostrar(visibles, reglas.familiasDe(visibles), "básica")
        assertThat(formaEntradas(entradas)).containsExactly("familia:g1[m-negro,l-negro]")
    }

    @Test
    fun `entradas con un suelto por nombre lo muestran suelto`() {
        val visibles = playeraBasica()
        val entradas = reglas.entradasParaMostrar(visibles, reglas.familiasDe(visibles), "gor")
        assertThat(formaEntradas(entradas)).containsExactly("suelto:gorra")
    }

    @Test
    fun `entradas sin texto es el catalogo agrupado`() {
        val visibles = playeraBasica()
        val familias = reglas.familiasDe(visibles)
        assertThat(reglas.entradasParaMostrar(visibles, familias, "")).isEqualTo(reglas.agruparCatalogo(visibles, familias))
        // Un texto en blanco no es una búsqueda (como B5): se ve el catálogo, no una lista vacía.
        assertThat(reglas.entradasParaMostrar(visibles, familias, "   ")).isEqualTo(reglas.agruparCatalogo(visibles, familias))
        assertThat(formaEntradas(reglas.entradasParaMostrar(visibles, familias, "")))
            .containsExactly("familia:g1[m-negro,l-negro]", "suelto:gorra").inOrder()
    }

    @Test
    fun `entradas sin coincidencias no muestran nada`() {
        val visibles = playeraBasica()
        assertThat(reglas.entradasParaMostrar(visibles, reglas.familiasDe(visibles), "zzz")).isEmpty()
    }

    // ---- Más allá de la tabla ----

    @Test
    fun `el nombre de la familia tambien encuentra su familia aunque la variante no lo lleve`() {
        val c = listOf(
            prod("a", "Chica azul", "199.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "S", 0, 0))),
            prod("b", "Mediana azul", "199.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1))),
        )
        assertThat(formaResultados(buscar(c, "playera"))).containsExactly("familia:g1")
    }

    @Test
    fun `una familia invalida se busca como sus variantes sueltas`() {
        val c = listOf(playera("solo", "S" to 0, "Azul" to 0, "199.00", "PL-S-AZ"))
        assertThat(reglas.familiasDe(c)).isEmpty()
        assertThat(formaResultados(buscar(c, "play"))).containsExactly("producto:solo")
    }

    @Test
    fun `familiasDe no cuenta una familia de una sola variante`() {
        assertThat(reglas.familiasDe(catalogo()).keys).containsExactly("g1")
    }

    @Test
    fun `la imagen de la familia es la del grupo o la de la primera variante con imagen`() {
        val a = prod("a", "Playera · S", "199.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "S", 0, 0)), imagen = "https://x/a.jpg")
        val b = prod("b", "Playera · M", "199.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", "M", 0, 1)))
        assertThat(reglas.familiasDe(listOf(a, b)).getValue("g1").imagenUrl).isEqualTo("https://x/a.jpg")

        val c = b.copy(groupImageUrl = "https://x/grupo.jpg")
        assertThat(reglas.familiasDe(listOf(a, c)).getValue("g1").imagenUrl).isEqualTo("https://x/grupo.jpg")
    }

    @Test
    fun `el empate de valuePosition conserva el orden de aparicion`() {
        val c = listOf("Chica", "Mediana", "Grande").mapIndexed { i, t ->
            prod("p$i", "Playera · $t", "1.00", grupo = "g1", nombreGrupo = "Playera", opciones = listOf(op("Talla", t, 0, 0)))
        }
        assertThat(reglas.ejesDe(c)).containsExactly(EjeDeFamilia("Talla", listOf("Chica", "Mediana", "Grande")))
    }

    @Test
    fun `valoresDe arma el mapa eje a valor de una variante`() {
        assertThat(reglas.valoresDe(por("pl-s-ro", catalogo()))).containsExactly("Talla", "S", "Color", "Rojo")
        assertThat(reglas.valoresDe(por("agua", catalogo()))).isEmpty()
    }

    /**
     * Sanidad del O(n), no medición del aparato: 5,000 variantes en 2,500 familias se validan, se acomodan en la lista y se buscan
     * en una fracción de segundo. El tope (3 s, con holgura para una Mac cargada) vigila contra regresiones grandes.
     */
    @Test
    fun `escala cinco mil variantes en dos mil quinientas familias en menos de tres segundos`() {
        val c = (0 until 2500).flatMap { g ->
            (0 until 2).map { v ->
                prod("p$g-$v", "Familia $g · $v", "10.00", sku = "SKU-$g-$v", grupo = "g$g", nombreGrupo = "Familia $g",
                    opciones = listOf(op("Talla", "T$v", 0, v)))
            }
        }
        val t0 = System.nanoTime()
        val familias = reglas.familiasDe(c)
        val lista = reglas.agruparCatalogo(c, familias)
        val hallados = reglas.buscar(c, familias, "familia 1249")
        val segundos = (System.nanoTime() - t0) / 1e9
        assertThat(familias).hasSize(2500)
        assertThat(lista).hasSize(2500)
        assertThat(formaResultados(hallados)).containsExactly("familia:g1249")
        assertThat(segundos).isLessThan(3.0)
        assertThat(familias["g0"]).isNotNull()
    }
}
