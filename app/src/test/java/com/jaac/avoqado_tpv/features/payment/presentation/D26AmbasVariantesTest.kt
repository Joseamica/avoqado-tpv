package com.jaac.avoqado_tpv.features.payment.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D26 (22-nov): lo que la PAX hace para no quedarse «cobrando», en las DOS variantes. Las pruebas de comportamiento
 * (`PaymentViewModelKernelDurabilityTest`, `BlumonSaleReferenceTest`) sólo alcanzan la sandbox; producción es la que mueve dinero real.
 */
class D26AmbasVariantesTest {
    private val variantes = listOf(
        "sandbox" to File("src/sandbox/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
        "production" to File("src/production/java/com/jaac/avoqado_tpv/features/payment/presentation/PaymentViewModel.kt"),
    )

    private val lineas = listOf(
        // el flujo de tarjeta que terminó sin cancelarse suelta el lector
        "if (isActive && preparationAttemptId != null) paymentAttemptLedger.sueltaElLectorAlTerminarElFlujo(preparationAttemptId)",
        // la duda con el SDK ya regresado no deja la bandera de cobro activo arriba
        "if (authorizationReturned && !authorizationApproved) paymentStateHolder.setChargeAttemptActive(false)",
        // al morir la pantalla dueña se suelta el cobro del POS
        "socketManager.alMorirUnaPantallaDeCobro(solicitudVinculada, currentVenueId.ifBlank { authRepository.getVenueId() ?: secureStorage.getVenueId() })",
        // el registro del rechazo lleva el código y el httpCode de Momentum
        "val (codigoDelHost, httpDelHost) = blumonCodigoYHttp(failure)",
    )

    @Test
    fun `D26 las dos variantes sueltan el lector, bajan la bandera en la duda, sueltan el cobro al morir y registran el codigo`() {
        val conteos = variantes.map { (nombre, archivo) ->
            assertTrue("no existe $archivo", archivo.exists())
            val fuente = archivo.readText()
            lineas.map { linea ->
                val n = fuente.split(linea).size - 1
                assertEquals("[$nombre] «$linea» debe aparecer una vez", 1, n)
                n
            }
        }
        assertEquals(conteos[0], conteos[1])
    }
}
