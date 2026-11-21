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
}
