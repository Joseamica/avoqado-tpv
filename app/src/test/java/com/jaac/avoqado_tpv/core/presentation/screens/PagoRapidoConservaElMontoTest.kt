package com.jaac.avoqado_tpv.core.presentation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Ruling 109 (Codex r2). En Pago rápido, «Ver» (la banda de pagados en línea) pone «Pedidos por pagar» ENCIMA y «atrás» regresa a la
 * MISMA entrada de la pantalla; con `remember` el importe tecleado se borraba al salir de composición y regresaba a $0. Con
 * `rememberSaveable`, el NavHost lo guarda con su entrada y lo devuelve al regresar ($75 sigue siendo $75). Un Pago rápido NUEVO (del
 * Inicio o de «Nuevo Pago») es otra entrada y empieza en $0, como siempre.
 *
 * La pantalla no se puede ejecutar aquí (`createComposeRule` sólo está en `androidTest`): se lee la fuente, como en
 * `NoSeCobroCableadoTest`.
 */
class PagoRapidoConservaElMontoTest {
    private val pantalla = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/screens/FastPaymentEntryScreen.kt").readText()

    @Test
    fun `Ruling 109 el importe tecleado sobrevive a Ver y regresar`() {
        assertThat(pantalla).contains("var amount by rememberSaveable { mutableStateOf(\"0\") }")
        assertThat(pantalla).doesNotContain("var amount by remember { mutableStateOf(\"0\") }")
    }

    /**
     * Ruling 115 (regresión del 109). El importe se guarda con la entrada, así que regresaba en CADA vuelta a ella, también después de un
     * cobro (la flecha de «Sin papel» tras un cobro aprobado, «atrás» en el recibo de la PAX o en la Nexgo): «$75» quedaba listo detrás de
     * «Cobrar», como el P1 de `RegresoDeCobrarTest`. «Cobrar» lo manda y deja la pantalla en $0; «Ver» no pasa por «Cobrar» y lo conserva.
     */
    @Test
    fun `Ruling 115 Cobrar manda el importe y al regresar del cobro la pantalla esta en cero`() {
        assertThat(cobrarPagoRapido("75")).isEqualTo("75.00" to "0")
        assertThat(cobrarPagoRapido("75.5")).isEqualTo("75.50" to "0")
        // $0 no manda nada ni cambia lo tecleado (sale «Ingresa un monto mayor a $0.00»).
        assertThat(cobrarPagoRapido("0")).isEqualTo(null to "0")
        assertThat(cobrarPagoRapido("0.")).isEqualTo(null to "0.")
        // La pantalla se queda con lo que regresa ANTES de mandar.
        assertThat(pantalla).containsMatch(
            """val \(aCobrar, queda\) = cobrarPagoRapido\(amount\)\s+amount = queda\s+if \(aCobrar != null\) onAmountSubmit\(aCobrar\) else showError = true"""
        )
    }
}
