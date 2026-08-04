package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.math.BigDecimal

/**
 * Fase 1 — los 3 DTOs de dinero nuevos (`CompOrderResult`, `DiscountApplyResult`,
 * `AvailableDiscountDto`) deserializan `BigDecimal` EXACTO, mismo mecanismo
 * que `OrderDetailMoneyTest` ya prueba para `OrderDetail` — pero cada `data
 * class` es su propio TypeAdapter implícito de Gson, así que hay que probarlo
 * de nuevo: nada garantiza que uno reusa la maquinaria del otro. `0.10 + 0.20
 * + 0.05` en `Double` da `0.35000000000000003`, no `0.35` — el caso de
 * siempre (`avoqado-server/.claude/rules/critical-warnings.md`, "Money =
 * Decimal, Never Float").
 */
class Phase1MoneyDtoTest {

    private val gson = Gson()

    @Test
    fun compOrderResult_deserializa_total_bigdecimal_exacto() {
        val json = """{"id":"order-1","total":0.10,"version":2}"""

        val result = gson.fromJson(json, CompOrderResult::class.java)

        assertThat(result.total).isEqualTo(BigDecimal("0.10"))
    }

    @Test
    fun discountApplyResult_suma_de_montos_deserializados_da_035_exacto() {
        val json = """{"amount":0.10,"newOrderTotal":0.20}"""

        val result = gson.fromJson(json, DiscountApplyResult::class.java)
        val sum = result.amount + BigDecimal("0.20") + BigDecimal("0.05")

        assertThat(sum).isEqualTo(BigDecimal("0.35"))
        assertThat(sum.toPlainString()).isEqualTo("0.35")
    }

    @Test
    fun availableDiscountDto_estimatedSavings_deserializa_bigdecimal_exacto() {
        val json = """{"id":"d1","name":"Cliente frecuente","estimatedSavings":199.999}"""

        val discount = gson.fromJson(json, AvailableDiscountDto::class.java)

        assertThat(discount.estimatedSavings).isEqualTo(BigDecimal("199.999"))
    }

    @Test
    fun orderMoneySummary_total_deserializa_bigdecimal_exacto_no_via_double() {
        val json = """{"id":"order-2","orderNumber":"A-101","total":0.30}"""

        val summary = gson.fromJson(json, OrderMoneySummary::class.java)

        assertThat(summary.total).isEqualTo(BigDecimal("0.30"))
    }
}
