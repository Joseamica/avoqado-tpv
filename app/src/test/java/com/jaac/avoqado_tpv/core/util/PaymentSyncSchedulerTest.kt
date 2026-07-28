package com.jaac.avoqado_tpv.core.util

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class PaymentSyncSchedulerTest {

    @Test
    fun `runNow encola trabajo UNICO con politica KEEP`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager

        PaymentSyncScheduler.runNow(context)

        // KEEP y no REPLACE: si ya hay uno corriendo queremos que TERMINE,
        // no reiniciarlo a media tanda.
        verify {
            workManager.enqueueUniqueWork(
                PaymentSyncScheduler.IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }
}
