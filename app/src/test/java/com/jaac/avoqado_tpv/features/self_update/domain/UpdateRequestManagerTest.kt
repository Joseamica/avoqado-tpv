package com.jaac.avoqado_tpv.features.self_update.domain

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.network.AvoqadoUpdateInfo
import com.jaac.avoqado_tpv.core.observability.ObservabilityManager
import com.jaac.avoqado_tpv.features.remote_command.domain.CommandExecutionWindow
import com.jaac.avoqado_tpv.features.self_update.data.AvoqadoUpdateRepository
import com.jaac.avoqado_tpv.features.self_update.data.UpdateCheckResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import javax.inject.Provider
import com.jaac.avoqado_tpv.features.self_update.data.DownloadResult

class UpdateRequestManagerTest {
    private val repo = mockk<AvoqadoUpdateRepository>(relaxed = true)
    private val installer = mockk<ApkInstaller>(relaxed = true)
    private val db = mockk<AvoqadoDatabase>(relaxed = true)
    private val manager = UpdateRequestManager(repo, installer, mockk<ObservabilityManager>(relaxed = true), mockk<Context>(relaxed = true), Provider { db })
    @After fun cleanup() { CommandExecutionWindow.setExecuting(false); CommandExecutionWindow.setForeground(false); CommandExecutionWindow.setRoute(null) }
    @Test fun `accepting an old update dialog on a payment route cannot download or install`() = runTest {
        val update = mockk<AvoqadoUpdateInfo>(relaxed = true)
        coEvery { repo.checkForUpdate() } returns UpdateCheckResult.UpdateAvailable(update)
        manager.handleUpdateRequest("c1")
        CommandExecutionWindow.setForeground(true)
        CommandExecutionWindow.setRoute("payment")
        manager.acceptUpdate()
        coVerify(exactly = 0) { repo.downloadApk(any(), any()) }
        coVerify(exactly = 0) { installer.install(any()) }
        assertThat(manager.updateRequestState.value).isInstanceOf(UpdateRequestState.Error::class.java)
    }
    @Test fun `accepted update keeps the gate during download and releases it on failure`() = runTest {
        coEvery { repo.checkForUpdate() } returns UpdateCheckResult.UpdateAvailable(mockk(relaxed = true))
        coEvery { db.pendingPaymentDao().getPendingCount() } returns 0
        coEvery { db.pendingPaymentDao().getFailedCount() } returns 0
        coEvery { db.pendingRefundDao().getPendingCount() } returns 0
        coEvery { db.pendingRefundDao().getFailedCount() } returns 0
        coEvery { db.paymentAttemptDao().countUnresolvedCharges() } returns 0
        coEvery { repo.downloadApk(any(), any()) } coAnswers {
            assertThat(CommandExecutionWindow.executing.value).isTrue()
            DownloadResult.Error("offline")
        }
        CommandExecutionWindow.setForeground(true); CommandExecutionWindow.setRoute("home")
        manager.handleUpdateRequest("c2")
        manager.acceptUpdate()
        assertThat(CommandExecutionWindow.executing.value).isFalse()
        assertThat(manager.updateRequestState.value).isInstanceOf(UpdateRequestState.Error::class.java)
    }
}
