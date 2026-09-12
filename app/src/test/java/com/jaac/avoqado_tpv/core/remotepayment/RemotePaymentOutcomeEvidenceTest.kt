package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.realtime.SocketManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RemotePaymentOutcomeEvidenceTest {
    @Test
    fun `navigation rejection carries preauthorization proof to durable result`() = runBlocking {
        val durable = CompletableDeferred<String>()
        val inbox = mockk<RemotePaymentInbox>(relaxed = true)
        coEvery { inbox.persistResult(any(), any()) } coAnswers { secondArg<String>().also { durable.complete(it) } }
        val manager = SocketManager(
            secureStorage = mockk(relaxed = true), authRepositoryLazy = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true), remotePaymentInbox = inbox,
            remotePaymentCoordinator = mockk(relaxed = true),
        )
        rejectRemotePaymentBeforeAuthorization(manager, "req-pre-sdk", "SDK not initialized")
        val json = JSONObject(withTimeout(5_000) { durable.await() })
        assertThat(json.getString("status")).isEqualTo("failed")
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PRE_AUTHORIZATION")
    }

    @Test
    fun `explicit outcome evidence survives durable result serialization`() = runBlocking {
        val durable = CompletableDeferred<String>()
        val inbox = mockk<RemotePaymentInbox>(relaxed = true)
        coEvery { inbox.persistResult(any(), any()) } coAnswers {
            secondArg<String>().also { durable.complete(it) }
        }
        val manager = SocketManager(
            secureStorage = mockk(relaxed = true), authRepositoryLazy = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true), remotePaymentInbox = inbox,
            remotePaymentCoordinator = mockk(relaxed = true),
        )
        manager.emitTerminalPaymentResult("req-proof", "failed", outcomeEvidence = "PROCESSOR_DECLINED")
        val json = JSONObject(withTimeout(5_000) { durable.await() })
        assertThat(json.optString("outcomeEvidence")).isEqualTo("PROCESSOR_DECLINED")
    }
}
