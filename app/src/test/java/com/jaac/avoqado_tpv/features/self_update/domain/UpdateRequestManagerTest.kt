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
    private fun sinPagosPendientes() {
        coEvery { db.pendingPaymentDao().getPendingCount() } returns 0
        coEvery { db.pendingPaymentDao().getFailedCount() } returns 0
        coEvery { db.pendingRefundDao().getPendingCount() } returns 0
        coEvery { db.pendingRefundDao().getFailedCount() } returns 0
        coEvery { db.paymentAttemptDao().countUnresolvedCharges() } returns 0
    }

    private fun version(code: Int) = mockk<AvoqadoUpdateInfo>(relaxed = true).also { every { it.versionCode } returns code }

    @Test fun `P1 aceptar una version anterior que borraria ventas de mesas no descarga ni instala`() = runTest {
        val anterior = com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE - 1
        coEvery { repo.getSpecificVersion(anterior) } returns UpdateCheckResult.UpdateAvailable(version(anterior))
        sinPagosPendientes()
        coEvery { db.syncIntentDao().contarQueBajarDeVersionBorraria() } returns 1
        coEvery { db.localShiftSessionDao().contarQueBajarDeVersionBorraria() } returns 0
        CommandExecutionWindow.setForeground(true); CommandExecutionWindow.setRoute("home")
        manager.handleUpdateRequest("c3", versionCode = anterior)
        manager.acceptUpdate()
        coVerify(exactly = 0) { repo.downloadApk(any(), any()) }
        coVerify(exactly = 0) { installer.install(any()) }
        val state = manager.updateRequestState.value
        assertThat(state).isInstanceOf(UpdateRequestState.Error::class.java)
        assertThat((state as UpdateRequestState.Error).message)
            .isEqualTo(com.jaac.avoqado_tpv.features.remote_command.domain.MENSAJE_BAJAR_DE_VERSION_BORRARIA)
        assertThat(CommandExecutionWindow.executing.value).isFalse()
    }

    @Test fun `aceptar una version nueva no se frena por ventas de mesas sin subir`() = runTest {
        val nueva = com.jaac.avoqado_tpv.BuildConfig.VERSION_CODE + 1
        coEvery { repo.checkForUpdate() } returns UpdateCheckResult.UpdateAvailable(version(nueva))
        sinPagosPendientes()
        coEvery { db.syncIntentDao().contarQueBajarDeVersionBorraria() } returns 1
        coEvery { repo.downloadApk(any(), any()) } returns DownloadResult.Error("offline")
        CommandExecutionWindow.setForeground(true); CommandExecutionWindow.setRoute("home")
        manager.handleUpdateRequest("c4")
        manager.acceptUpdate()
        coVerify(exactly = 1) { repo.downloadApk(any(), any()) }
    }
}
