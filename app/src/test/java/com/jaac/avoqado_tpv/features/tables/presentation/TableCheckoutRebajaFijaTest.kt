package com.jaac.avoqado_tpv.features.tables.presentation

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDetail
import com.jaac.avoqado_tpv.features.tables.domain.model.OrderDiscountLine
import org.junit.Test
import java.math.BigDecimal

/**
 * D15 (pizarrón hz:e82938e1:D15, 18-nov): en TPV SOLA → MESAS, la rebaja que quedó fija al cobrar vales se sigue viendo con su monto,
 * pero su «Quitar» sale en gris con «Fija: viene del cobro de vales», y al tocar la fila sale sólo la razón. El SERVIDOR la marca con
 * `congeladoPorVales`; uno viejo no manda el campo y la fila queda como hoy (activa: el SERVIDOR contesta el 400 al tocar).
 */
class TableCheckoutRebajaFijaTest {

    private val fija = OrderDiscountLine(id = "od-vale", name = "10 % producto", amount = BigDecimal("10.00"), congeladoPorVales = true)
    private val normal = OrderDiscountLine(id = "od-cuenta", name = "Cliente frecuente", amount = BigDecimal("5.00"))

    @Test
    fun D15_la_rebaja_fija_ensena_quitar_en_gris_con_su_nota() {
        val q = quitarDeLaRebaja(fija, quitandoOtra = false)
        assertThat(q.ensenaQuitar).isTrue()
        assertThat(q.activo).isFalse()
        assertThat(q.nota).isEqualTo("Fija: viene del cobro de vales")
        assertThat(q.razon).isEqualTo(
            "Esta rebaja quedó fija al cobrar los vales y no se quita desde la caja. Si hay que cambiarla, avisa a un encargado.",
        )
    }

    @Test
    fun D15_una_rebaja_normal_sigue_como_hoy() {
        assertThat(quitarDeLaRebaja(normal, quitandoOtra = false)).isEqualTo(QuitarDeLaRebaja(ensenaQuitar = true, activo = true))
        // Mientras se quita otra, se apaga como hoy.
        assertThat(quitarDeLaRebaja(normal, quitandoOtra = true).activo).isFalse()
        // Una línea sin id real (el agregado sin desglose) no ofrece el control.
        assertThat(quitarDeLaRebaja(normal.copy(id = ""), quitandoOtra = false).ensenaQuitar).isFalse()
    }

    @Test
    fun D15_el_campo_llega_del_servidor_y_un_servidor_viejo_sin_el_deja_la_fila_como_hoy() {
        val nuevo = Gson().fromJson(
            """{"id":"o1","orderDiscounts":[{"id":"od-vale","name":"10 %","amount":10.0,"congeladoPorVales":true},""" +
                """{"id":"od-cuenta","name":"Cliente","amount":5.0,"congeladoPorVales":false}]}""",
            OrderDetail::class.java,
        )
        assertThat(nuevo.orderDiscounts.map { it.congeladoPorVales }).containsExactly(true, false).inOrder()

        val viejo = Gson().fromJson("""{"id":"o1","orderDiscounts":[{"id":"od-vale","name":"10 %","amount":10.0}]}""", OrderDetail::class.java)
        assertThat(viejo.orderDiscounts.single().congeladoPorVales).isFalse()
    }
}

/**
 * D15 (Codex r4 #1): el CABLEADO de la fila en «Cobrar», que aquí no se puede ejecutar (`createComposeRule` sólo está en `androidTest`).
 * Se lee la fuente, como en `NoSeCobroCableadoTest`: cada descuento de la cuenta pasa por [quitarDeLaRebaja] y la fila usa su `activo`
 * (el botón), su `nota` y su `razon` (tocar la fila abre la explicación).
 */
class TableCheckoutRebajaFijaCableadoTest {
    private val pantalla = java.io.File("src/main/java/com/jaac/avoqado_tpv/features/tables/presentation/TableCheckoutScreen.kt").readText()

    @Test
    fun D15_cada_descuento_de_la_cuenta_usa_la_regla_y_la_fila_la_respeta() {
        val bucle = pantalla.substring(pantalla.indexOf("check.orderDiscounts.forEach { discount ->"))
        val fila = bucle.substring(0, bucle.indexOf("if (check.orderDiscounts.isEmpty()"))
        assertThat(fila).contains("quitarDeLaRebaja(discount, quitandoOtra = isRemovingDiscount)")
        assertThat(fila).contains("removeEnabled = quitar.activo")
        assertThat(fila).contains("nota = quitar.nota")
        assertThat(fila).contains("onClickFila = quitar.razon?.let { razon -> { razonDeRebajaFija = razon } }")
        assertThat(fila).doesNotContain("removeEnabled = !isRemovingDiscount")

        val renglon = pantalla.substring(pantalla.indexOf("private fun SummaryRow("), pantalla.indexOf("private fun SplitTypeChips("))
        assertThat(renglon).contains("enabled = removeEnabled")
        assertThat(renglon).contains("Modifier.clickable(onClickLabel = \"Ver por qué está fija\", onClick = onClickFila)")
        assertThat(renglon).contains("if (nota != null)")

        val tarjeta = pantalla.substring(pantalla.indexOf("private fun CheckSummaryCard("), pantalla.indexOf("private fun SummaryRow("))
        assertThat(tarjeta).contains("razonDeRebajaFija?.let { razon ->")
        assertThat(tarjeta).contains("text = { Text(razon) }")
    }
}
