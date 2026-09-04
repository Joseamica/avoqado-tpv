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
}
