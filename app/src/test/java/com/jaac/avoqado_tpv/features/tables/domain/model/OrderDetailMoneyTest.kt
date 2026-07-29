package com.jaac.avoqado_tpv.features.tables.domain.model

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.math.BigDecimal

/**
 * P1 — el dinero de Mesas debe ser `BigDecimal`, NUNCA `Double` (ver
 * `avoqado-server/.claude/rules/critical-warnings.md`, "Money = Decimal, Never
 * Float"). Con `Double`, `0.10 + 0.20 + 0.05 != 0.35` — el clásico error de
 * coma flotante que en un cheque real se traduce en centavos que no cuadran.
 *
 * Lo que este test protege específicamente: Gson debe deserializar el literal
 * numérico del JSON DIRECTO a `BigDecimal` (via `JsonReader.nextString()` +
 * `new BigDecimal(s)`), sin pasar por `Double` primero. Un `TypeAdapter` mal
 * hecho que hiciera `json → Double → BigDecimal` ya perdió la precisión ANTES
 * de llegar a `BigDecimal` — el test de abajo seguiría en rojo con ese bug,
 * aunque el campo ya "sea" `BigDecimal` en el código.
 */
class OrderDetailMoneyTest {

    private val gson = Gson()

    @Test
    fun sumar_montos_deserializados_del_json_da_el_resultado_decimal_exacto() {
        // Tres líneas de $0.10, $0.20 y $0.05 — en Double, 0.10 + 0.20 + 0.05
        // = 0.35000000000000003 (NO 0.35 exacto). Si OrderDetailItem.total
        // fuera Double (o si la deserialización pasara por Double antes de
        // llegar a BigDecimal), esta suma NO daría 0.35 exacto y el test
        // fallaría.
        val json = """
            {
              "id": "order-1",
              "items": [
                {"id": "i1", "productId": "p1", "unitPrice": 0.10, "total": 0.10},
                {"id": "i2", "productId": "p2", "unitPrice": 0.20, "total": 0.20},
                {"id": "i3", "productId": "p3", "unitPrice": 0.05, "total": 0.05}
              ]
            }
        """.trimIndent()

        val order = gson.fromJson(json, OrderDetail::class.java)
        val sum = order.items.fold(BigDecimal.ZERO) { acc, item -> acc + item.total }

        assertThat(sum).isEqualTo(BigDecimal("0.35"))
        assertThat(sum.toPlainString()).isEqualTo("0.35")
    }

    @Test
    fun el_mismo_calculo_en_double_diverge_del_resultado_decimal_exacto() {
        // Documenta el bug que este módulo dejó de tener: el resultado en
        // punto flotante de la MISMA suma NO es 0.35 exacto — la razón por la
        // que este módulo no puede usar Double para dinero.
        val doubleSum = 0.10 + 0.20 + 0.05
        assertThat(doubleSum).isNotEqualTo(0.35)
    }

    @Test
    fun amountLeft_nullable_deserializa_a_bigdecimal_exacto_cuando_esta_presente() {
        val json = """{"id":"order-1","amount_left": 199.999}"""

        val order = gson.fromJson(json, OrderDetail::class.java)

        assertThat(order.amountLeft).isEqualTo(BigDecimal("199.999"))
    }

    @Test
    fun amountLeft_ausente_en_el_json_sigue_siendo_null() {
        // La respuesta de PATCH addItemsToOrder no manda amount_left — debe
        // seguir siendo null, no BigDecimal.ZERO (esos dos significan cosas
        // distintas: "no calculado" vs "cero pesos restantes").
        val json = """{"id":"order-1"}"""

        val order = gson.fromJson(json, OrderDetail::class.java)

        assertThat(order.amountLeft).isNull()
    }
}
