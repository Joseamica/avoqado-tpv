package com.jaac.avoqado_tpv.features.tables.data.api.dto

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test
import java.math.BigDecimal

/**
 * Fase 3 — los DTOs de dinero de esta fase (`AvailableServiceChargeDto`,
 * `OrderTotals` — este último existía desde antes como código muerto sin
 * ningún test, ver `completeness-audit.md`) deserializan `BigDecimal` EXACTO,
 * mismo mecanismo que `Phase1MoneyDtoTest` ya prueba. `0.10 + 0.20 + 0.05` en
 * `Double` da `0.35000000000000003`, no `0.35` — el caso de siempre
 * (`avoqado-server/.claude/rules/critical-warnings.md`, "Money = Decimal,
 * Never Float").
 */
class Phase3MoneyDtoTest {

    private val gson = Gson()

    @Test
    fun availableServiceChargeDto_value_deserializa_bigdecimal_exacto() {
        val json = """{"id":"sc1","name":"Propina automática","type":"PERCENTAGE","value":15.5,"taxable":true,"autoApplyMinCovers":6}"""

        val charge = gson.fromJson(json, AvailableServiceChargeDto::class.java)

        assertThat(charge.value).isEqualTo(BigDecimal("15.5"))
        assertThat(charge.autoApplyMinCovers).isEqualTo(6)
    }

    @Test
    fun orderTotals_suma_de_montos_deserializados_da_035_exacto() {
        val json = """{"subtotal":100.00,"discountAmount":0.10,"serviceChargeAmount":0.20,"total":99.90,"version":2}"""

        val totals = gson.fromJson(json, OrderTotals::class.java)
        val sum = totals.discountAmount + totals.serviceChargeAmount + BigDecimal("0.05")

        assertThat(sum).isEqualTo(BigDecimal("0.35"))
        assertThat(sum.toPlainString()).isEqualTo("0.35")
    }

    @Test
    fun splitBySeatResult_totales_de_cada_asiento_deserializan_bigdecimal_exacto() {
        val json = """{"source":{"id":"o1","total":0.10,"seat":1},"created":[{"id":"o2","total":0.20,"seat":2}]}"""

        val result = gson.fromJson(json, SplitBySeatResult::class.java)

        assertThat(result.source.total).isEqualTo(BigDecimal("0.10"))
        assertThat(result.created[0].total).isEqualTo(BigDecimal("0.20"))
    }
}
