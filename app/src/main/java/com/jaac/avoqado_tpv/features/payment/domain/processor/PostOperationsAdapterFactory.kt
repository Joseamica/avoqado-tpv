package com.jaac.avoqado_tpv.features.payment.domain.processor

import com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.AngelPaySdkPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.data.processor.blumon.BlumonPostOperationsAdapter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostOperationsAdapterFactory @Inject constructor(
    private val angelPayAdapter: AngelPaySdkPostOperationsAdapter,
    private val blumonAdapter: BlumonPostOperationsAdapter,
) {
    fun get(processorType: ProcessorType): PaymentPostOperationsAdapter {
        return when (processorType) {
            ProcessorType.ANGELPAY -> angelPayAdapter
            ProcessorType.BLUMON -> blumonAdapter
        }
    }
}
