package com.jaac.avoqado_tpv.core.util

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalNetworkOperationManagerTest {
    private val cobro = mockk<PaymentStateProvider>().also {
        every { it.isCharging() } returns false
        every { it.isChargeAttemptActive() } returns false
        every { it.isRefundInFlight() } returns false
    }
    private val manager = CriticalNetworkOperationManager(cobro)

    @Test fun `sin nada en curso no hay operacion critica`() = assertFalse(manager.isAnyCriticalOperationInProgress())

    @Test fun `P1 cobro de AngelPay cuenta`() {
        every { cobro.isCharging() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `P1 pantalla de cobro trabajando cuenta`() {
        every { cobro.isChargeAttemptActive() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `P1 devolucion de AngelPay en curso cuenta`() {
        every { cobro.isRefundInFlight() } returns true
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }

    @Test fun `las banderas de la PAX siguen contando`() {
        manager.setPaymentFlowInProgress(true)
        assertTrue(manager.isAnyCriticalOperationInProgress())
    }
}
