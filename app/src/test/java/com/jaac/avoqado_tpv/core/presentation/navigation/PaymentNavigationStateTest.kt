package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaymentNavigationStateTest {

    @Test
    fun `manual fast payment replaces stale remote payment context`() {
        val handle = SavedStateHandle(
            mapOf(
                "initialAmount" to "475.00",
                "skipReview" to true,
                "externalTipCents" to 4_750L,
                "externalRating" to 5,
                "externalSkipReview" to true,
                "paymentSource" to "SOCKET",
                "socketRequestId" to "req-cancelled",
                "orderId" to "order-cancelled",
            ),
        )

        prepareManualPaymentArgs(handle, "120.00")

        assertThat(handle.get<String>("initialAmount")).isEqualTo("120.00")
        assertThat(handle.get<Boolean>("skipReview")).isFalse()
        assertThat(handle.get<Long>("externalTipCents")).isNull()
        assertThat(handle.get<Int>("externalRating")).isNull()
        assertThat(handle.get<Boolean>("externalSkipReview")).isNull()
        assertThat(handle.get<String>("paymentSource")).isNull()
        assertThat(handle.get<String>("socketRequestId")).isNull()
        assertThat(handle.get<String>("orderId")).isNull()
    }

    // ------------------------------------------------------------------------------------------
    // 🔴 D.7 (2026-09-11) — los argumentos de un cobro son de UN solo uso
    // ------------------------------------------------------------------------------------------

    private fun remotoB() = mapOf(
        "initialAmount" to "475.00",
        "skipReview" to true,
        "externalTipCents" to 4_750L,
        "externalRating" to 5,
        "externalSkipReview" to true,
        "paymentSource" to "SOCKET",
        "socketRequestId" to "REQ-B",
        "socketProcessedByStaffId" to "staff-B",
        "orderId" to "ord-B",
        "skipLocalOrderValidation" to true,
    )

    @Test
    fun `P1 congelar copia los argumentos a la entrada del cobro y los borra del lanzador`() {
        val lanzador = SavedStateHandle(remotoB() + ("entryPoint" to "checkout"))
        val entrada = SavedStateHandle()

        congelarArgumentosDeCobro(propio = entrada, lanzador = lanzador)

        assertThat(entrada.get<String>("initialAmount")).isEqualTo("475.00")
        assertThat(entrada.get<String>("paymentSource")).isEqualTo("SOCKET")
        assertThat(entrada.get<String>("socketRequestId")).isEqualTo("REQ-B")
        assertThat(entrada.get<Long>("externalTipCents")).isEqualTo(4_750L)
        assertThat(entrada.get<String>("orderId")).isEqualTo("ord-B")
        assertThat(entrada.get<String>("entryPoint")).isEqualTo("checkout")
        CLAVES_DE_COBRO.forEach { clave -> assertThat(lanzador.contains(clave)).isFalse() }
    }

    @Test
    fun `P1 la pantalla que sale no se lleva los argumentos del cobro siguiente`() {
        // Camino 1: A se congeló con lo suyo; después el colector escribe los de B en el MISMO
        // handle (Home) antes de empujar la entrada de B.
        val home = SavedStateHandle(
            mapOf("initialAmount" to "260.00", "paymentSource" to "SOCKET", "socketRequestId" to "REQ-A", "externalTipCents" to 2_600L),
        )
        val entradaA = SavedStateHandle()
        congelarArgumentosDeCobro(propio = entradaA, lanzador = home)
        remotoB().forEach { (clave, valor) -> home[clave] = valor }

        // La pantalla de A se recompone mientras sale: vuelve a pasar por el congelado.
        congelarArgumentosDeCobro(propio = entradaA, lanzador = home)

        assertThat(entradaA.get<String>("socketRequestId")).isEqualTo("REQ-A")
        assertThat(entradaA.get<Long>("externalTipCents")).isEqualTo(2_600L)
        assertThat(entradaA.get<String>("initialAmount")).isEqualTo("260.00")
        // …y los argumentos de B siguen ahí para la entrada de B.
        val entradaB = SavedStateHandle()
        congelarArgumentosDeCobro(propio = entradaB, lanzador = home)
        assertThat(entradaB.get<String>("socketRequestId")).isEqualTo("REQ-B")
    }

    @Test
    fun `P1 tras salir con atras del sistema el siguiente cobro local no nace remoto`() {
        // Camino 2: un remoto B se abrió sobre Cobrar (el colector escribió en el handle de Cobrar).
        val cobrar = SavedStateHandle(remotoB())
        congelarArgumentosDeCobro(propio = SavedStateHandle(), lanzador = cobrar)
        // Atrás del sistema: la entrada de B muere sin pasar por ningún callback.

        // Cobrar arma su siguiente cobro LOCAL de venta rápida: sólo monto y entryPoint, como hoy.
        cobrar["initialAmount"] = "120.00"
        cobrar["entryPoint"] = "checkout"
        val siguiente = congelarArgumentosDeCobro(propio = SavedStateHandle(), lanzador = cobrar)

        assertThat(siguiente.get<String>("initialAmount")).isEqualTo("120.00")
        assertThat(siguiente.get<String>("paymentSource")).isNull()
        assertThat(siguiente.get<String>("socketRequestId")).isNull()
        assertThat(siguiente.get<String>("socketProcessedByStaffId")).isNull()
        assertThat(siguiente.get<Long>("externalTipCents")).isNull()
        assertThat(siguiente.get<Int>("externalRating")).isNull()
        assertThat(siguiente.get<Boolean>("skipReview")).isNull()
        assertThat(siguiente.get<String>("orderId")).isNull()
        assertThat(siguiente.get<Boolean>("skipLocalOrderValidation")).isNull()
    }

    @Test
    fun `congelar sobrevive a la recreacion de la entrada`() {
        val entrada = SavedStateHandle()
        congelarArgumentosDeCobro(propio = entrada, lanzador = SavedStateHandle(remotoB()))
        // Muerte del proceso: la entrada vuelve con su estado guardado; el lanzador, con otra cosa.
        val restaurada = SavedStateHandle(entrada.keys().associateWith { entrada.get<Any?>(it) })

        congelarArgumentosDeCobro(propio = restaurada, lanzador = SavedStateHandle(mapOf("initialAmount" to "1.00")))

        assertThat(restaurada.get<String>("socketRequestId")).isEqualTo("REQ-B")
        assertThat(restaurada.get<String>("initialAmount")).isEqualTo("475.00")
    }
}
