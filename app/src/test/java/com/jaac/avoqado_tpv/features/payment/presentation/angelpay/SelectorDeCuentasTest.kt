package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import org.junit.Test

/**
 * 🧭 D3 (12-nov): qué cuentas ofrece «Método de pago» de la Nexgo y cuándo se oculta la lista. La pantalla
 * (`AngelPayPaymentScreen`) lo recalcula en cada recomposición, así que también vale cuando las cuentas cargan después de
 * la respuesta de las reglas (Codex r2 #1).
 */
class SelectorDeCuentasTest {

    private val cuentaA = MerchantAccount(
        id = "merchant_a", merchantAccountId = "cma-001", serialNumber = "N86-001", displayName = "Bar",
        environment = MerchantEnvironment.SANDBOX, processorType = ProcessorType.ANGELPAY, externalMerchantId = "11",
    )
    private val cuentaB = MerchantAccount(
        id = "merchant_b", merchantAccountId = "cma-002", serialNumber = "N86-002", displayName = "Restaurant",
        environment = MerchantEnvironment.SANDBOX, processorType = ProcessorType.ANGELPAY, externalMerchantId = "22",
    )

    private fun reglas(vararg permitidas: String, fallbackAll: Boolean = false) = MerchantEligibility(
        evaluated = true,
        routingFeatureActive = true,
        eligibleMerchantAccountIds = permitidas.toSet(),
        autoSelectMerchantAccountId = permitidas.singleOrNull(),
        fallbackAll = fallbackAll,
    )

    @Test
    fun `la regla deja una sola y no hay cuenta elegida - se ve la lista para tocarla`() {
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = null)

        assertThat(selector.cuentas).containsExactly(cuentaB)
        assertThat(selector.ocultarLista).isFalse()
    }

    @Test
    fun `la regla deja una sola y ya es la elegida - vista simple`() {
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = cuentaB)

        assertThat(selector.cuentas).containsExactly(cuentaB)
        assertThat(selector.ocultarLista).isTrue()
    }

    @Test
    fun `la regla deja varias - lista con las permitidas`() {
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-001", "cma-002"), elegida = null)

        assertThat(selector.cuentas).containsExactly(cuentaA, cuentaB).inOrder()
        assertThat(selector.ocultarLista).isFalse()
    }

    @Test
    fun `sin respuesta de las reglas todavia (o sin red) - todas las cuentas`() {
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas = null, elegida = cuentaA)

        assertThat(selector.cuentas).containsExactly(cuentaA, cuentaB).inOrder()
        assertThat(selector.ocultarLista).isFalse()
    }

    @Test
    fun `ninguna regla aplica (fallbackAll) o la funcion esta apagada - todas`() {
        assertThat(selectorDeCuentas(listOf(cuentaA, cuentaB), reglas(fallbackAll = true), cuentaA).cuentas)
            .containsExactly(cuentaA, cuentaB)
        assertThat(selectorDeCuentas(listOf(cuentaA, cuentaB), MerchantEligibility.disabled(), cuentaA).cuentas)
            .containsExactly(cuentaA, cuentaB)
    }

    @Test
    fun `la regla permite una cuenta que no esta cargada - todas, nunca una lista vacia`() {
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-999"), elegida = null)

        assertThat(selector.cuentas).containsExactly(cuentaA, cuentaB)
        assertThat(selector.ocultarLista).isFalse()
    }

    @Test
    fun `las cuentas todavia no cargan - nada que mostrar, y al cargar sale la lista`() {
        val antes = selectorDeCuentas(emptyList(), reglas("cma-002"), elegida = null)
        assertThat(antes.cuentas).isEmpty()
        assertThat(antes.ocultarLista).isTrue()

        val despues = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = null)
        assertThat(despues.cuentas).containsExactly(cuentaB)
        assertThat(despues.ocultarLista).isFalse()
    }

    @Test
    fun `un negocio con una sola cuenta ya elegida - vista simple como siempre`() {
        val selector = selectorDeCuentas(listOf(cuentaA), reglas = null, elegida = cuentaA)

        assertThat(selector.ocultarLista).isTrue()
    }

    @Test
    fun `deuda declarada - una cuenta elegida que la regla excluye no se cambia y la lista se oculta`() {
        // La Nexgo sólo filtra (13-jul). Si el founder decide que elija la permitida, esta prueba cambia a propósito.
        val selector = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = cuentaA)

        assertThat(selector.cuentas).containsExactly(cuentaB)
        assertThat(selector.ocultarLista).isTrue()
    }
}
