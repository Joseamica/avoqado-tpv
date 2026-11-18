package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItem
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartItemType
import com.jaac.avoqado_tpv.features.checkout.domain.model.CartState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * na6094ff · encargo 1df453 (18-nov-2026): en la PAX el teclado de Cobrar decía «$10,00» (coma), el carrito y «Cobrar» igual,
 * y la calificación «Paso 1 de 3 · $10.0». En México el decimal es punto. La causa: `String.format("%.2f", …)` sin `Locale`
 * toma el idioma del aparato, y el del laboratorio pone coma.
 */
class PesosConPuntoTest {

    private lateinit var idiomaDelAparato: Locale

    @Before fun guardarIdioma() { idiomaDelAparato = Locale.getDefault() }

    @After fun regresarIdioma() { Locale.setDefault(idiomaDelAparato) }

    @Test
    fun `P2 el carrito de Cobrar dice 10 punto 00 aunque el aparato use coma decimal`() {
        Locale.setDefault(Locale("es", "ES"))
        val carrito = CartState(items = listOf(CartItem(type = CartItemType.CustomAmount, name = "Importe", unitPriceCents = 1000)))

        assertEquals("$10.00", carrito.totalDisplay)
        assertEquals("$10.00", carrito.subtotalDisplay)
    }

    @Test
    fun `P2 ningun importe en pantalla se formatea con el idioma del aparato`() {
        val sinLocale = Regex("""(String\.format\(\s*"[^"]*%\.2f|"[^"]*%\.2f[^"]*"\.format\()""")
        val culpables = listOf("src/main/java", "src/sandbox/java", "src/production/java")
            .map(::File).filter { it.exists() }
            .flatMap { raiz -> raiz.walkTopDown().filter { it.extension == "kt" } }
            .flatMap { archivo ->
                archivo.readLines().mapIndexedNotNull { i, linea ->
                    if (sinLocale.containsMatchIn(linea) && !linea.contains("Locale")) "${archivo.path}:${i + 1}" else null
                }
            }
        assertEquals("importes con %.2f sin Locale (salen con coma en un aparato en español de España)", emptyList<String>(), culpables)
    }

    @Test
    fun `P2 los encabezados del cobro no pintan el importe crudo`() {
        // «Paso 1 de 3 · $10.0»: el importe de la calificación (y de propina, cuenta y verificación) se pintaba tal cual llegaba.
        val pantalla = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentScreen.kt").readText()
        val crudos = Regex("""\$\$\{currentState\.(amount|totalAmount)\}""").findAll(pantalla).map { it.value }.toList()
        assertEquals("encabezados con el importe sin formatear", emptyList<String>(), crudos)
    }

    @Test
    fun `importe de texto con dos decimales y punto, y lo que no es numero se deja como llego`() {
        Locale.setDefault(Locale("es", "ES"))
        assertEquals("10.00", importeConDosDecimales("10.0"))
        assertEquals("25.00", importeConDosDecimales("25"))
        assertEquals("11.50", importeConDosDecimales(" 11.5 "))
        assertEquals("1500.00", importeConDosDecimales("1500"))
        assertEquals("", importeConDosDecimales(null))
        assertEquals("N/D", importeConDosDecimales("N/D"))
    }
}
