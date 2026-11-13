package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantAccount
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEligibility
import com.jaac.avoqado_tpv.features.payment.domain.model.MerchantEnvironment
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import org.junit.Test

/**
 * 🧭 D3 (12/13-nov): qué cuentas ofrece «Método de pago» de la Nexgo, si la cuenta elegida puede cobrar y a cuál debe
 * pasar sola. Regla del founder (13-nov): nunca se cobra en una cuenta que la regla excluye. Criterio para elegir entre
 * varias permitidas, el de la PAX (`proceedToMerchantSelection`): la que la regla marca para preseleccionar y, si no marca
 * ninguna, la primera permitida en el orden de la lista.
 */
class SelectorDeCuentasTest {

    private val cuentaA = cuenta("merchant_a", "cma-001", "Bar", "11")
    private val cuentaB = cuenta("merchant_b", "cma-002", "Restaurant", "22")
    private val cuentaC = cuenta("merchant_c", "cma-003", "Terraza", "33")

    private fun cuenta(id: String, cma: String, nombre: String, angelpay: String) = MerchantAccount(
        id = id, merchantAccountId = cma, serialNumber = "N86-$angelpay", displayName = nombre,
        environment = MerchantEnvironment.SANDBOX, processorType = ProcessorType.ANGELPAY, externalMerchantId = angelpay,
    )

    private fun reglas(vararg permitidas: String, marcada: String? = permitidas.singleOrNull(), fallbackAll: Boolean = false) =
        MerchantEligibility(
            evaluated = true,
            routingFeatureActive = true,
            eligibleMerchantAccountIds = permitidas.toSet(),
            autoSelectMerchantAccountId = marcada,
            fallbackAll = fallbackAll,
        )

    @Test
    fun `la regla deja una y no hay cuenta elegida - pasa sola a esa, y mientras la lista se ve para tocarla`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = null)

        assertThat(s.cuentas).containsExactly(cuentaB)
        assertThat(s.cuentaAPasar).isEqualTo(cuentaB)
        assertThat(s.elegidaPermitida).isFalse()
        assertThat(s.ocultarLista).isFalse()
    }

    @Test
    fun `la cuenta activa la EXCLUYE la regla - pasa sola a la permitida y no puede cobrar en la excluida`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = cuentaA)

        assertThat(s.cuentas).containsExactly(cuentaB)
        assertThat(s.cuentaAPasar).isEqualTo(cuentaB)
        assertThat(s.elegidaPermitida).isFalse()
        assertThat(s.ocultarLista).isFalse()   // si el cambio no se puede, la cajera toca la permitida
    }

    @Test
    fun `la regla deja una y ya es la elegida - nada que cambiar, vista simple`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = cuentaB)

        assertThat(s.cuentaAPasar).isNull()
        assertThat(s.elegidaPermitida).isTrue()
        assertThat(s.ocultarLista).isTrue()
    }

    @Test
    fun `varias permitidas sin marcada y la activa excluida - la primera permitida en el orden de la lista (como la PAX)`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB, cuentaC), reglas("cma-003", "cma-002", marcada = null), elegida = cuentaA)

        assertThat(s.cuentas).containsExactly(cuentaB, cuentaC).inOrder()
        assertThat(s.cuentaAPasar).isEqualTo(cuentaB)
        assertThat(s.ocultarLista).isFalse()
    }

    @Test
    fun `varias permitidas con una marcada - pasa a la marcada (como la PAX)`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB, cuentaC), reglas("cma-002", "cma-003", marcada = "cma-003"), elegida = cuentaA)

        assertThat(s.cuentaAPasar).isEqualTo(cuentaC)
    }

    @Test
    fun `varias permitidas y la elegida es una de ellas - nada que cambiar`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB, cuentaC), reglas("cma-002", "cma-003", marcada = null), elegida = cuentaC)

        assertThat(s.cuentaAPasar).isNull()
        assertThat(s.elegidaPermitida).isTrue()
        assertThat(s.ocultarLista).isFalse()
    }

    @Test
    fun `sin respuesta de las reglas todavia (o sin red) - todas, la elegida cobra y nada cambia solo`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas = null, elegida = cuentaA)

        assertThat(s.cuentas).containsExactly(cuentaA, cuentaB).inOrder()
        assertThat(s.elegidaPermitida).isTrue()
        assertThat(s.cuentaAPasar).isNull()
    }

    @Test
    fun `ninguna regla aplica (fallbackAll) o la funcion esta apagada - todas y nada cambia solo`() {
        for (r in listOf(reglas(fallbackAll = true), MerchantEligibility.disabled())) {
            val s = selectorDeCuentas(listOf(cuentaA, cuentaB), r, elegida = cuentaA)
            assertThat(s.cuentas).containsExactly(cuentaA, cuentaB)
            assertThat(s.elegidaPermitida).isTrue()
            assertThat(s.cuentaAPasar).isNull()
        }
    }

    @Test
    fun `la regla permite una cuenta que no esta cargada - todas, nunca una lista vacia ni un cambio a ciegas`() {
        val s = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-999"), elegida = cuentaA)

        assertThat(s.cuentas).containsExactly(cuentaA, cuentaB)
        assertThat(s.elegidaPermitida).isTrue()
        assertThat(s.cuentaAPasar).isNull()
    }

    @Test
    fun `las cuentas todavia no cargan - nada que mostrar ni a donde pasar, y al cargar sale la permitida`() {
        val antes = selectorDeCuentas(emptyList(), reglas("cma-002"), elegida = null)
        assertThat(antes.cuentas).isEmpty()
        assertThat(antes.cuentaAPasar).isNull()
        assertThat(antes.ocultarLista).isTrue()

        val despues = selectorDeCuentas(listOf(cuentaA, cuentaB), reglas("cma-002"), elegida = null)
        assertThat(despues.cuentaAPasar).isEqualTo(cuentaB)
        assertThat(despues.ocultarLista).isFalse()
    }

    @Test
    fun `un negocio con una sola cuenta ya elegida - vista simple como siempre`() {
        val s = selectorDeCuentas(listOf(cuentaA), reglas = null, elegida = cuentaA)

        assertThat(s.ocultarLista).isTrue()
        assertThat(s.elegidaPermitida).isTrue()
    }

    @Test
    fun `la elegida se compara por su id, no por la instancia (la lista se vuelve a emitir desde la base)`() {
        val bReemitida = cuentaB.copy(displayName = "Restaurant (renombrada)")
        val s = selectorDeCuentas(listOf(cuentaA, bReemitida), reglas("cma-002"), elegida = cuentaB)

        assertThat(s.elegidaPermitida).isTrue()
        assertThat(s.cuentaAPasar).isNull()
    }
}
