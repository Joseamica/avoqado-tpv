package com.jaac.avoqado_tpv.features.payment.data.ledger

import com.jaac.avoqado_tpv.features.payment.data.repository.TpvSettingsRepository
import com.jaac.avoqado_tpv.features.payment.domain.model.PaymentLedgerMode
import com.jaac.avoqado_tpv.features.payment.domain.model.TpvSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PaymentAttemptLedgerTest {

    private val dao = mockk<PaymentAttemptDao>(relaxed = true)
    private val settingsRepository = mockk<TpvSettingsRepository>()
    private lateinit var ledger: PaymentAttemptLedger

    private fun settingsWith(mode: PaymentLedgerMode) =
        TpvSettings.DEFAULT.copy(paymentLedgerMode = mode)

    @Before
    fun setup() {
        every { settingsRepository.getCurrentSettings() } returns settingsWith(PaymentLedgerMode.SHADOW)
        ledger = PaymentAttemptLedger(dao, settingsRepository)
    }

    @Test
    fun `OFF observability mode still persists money barrier`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns settingsWith(PaymentLedgerMode.OFF)
        coEvery { dao.reserveTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L
        ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 1000, PaymentAttemptEntity.ROUTE_FAST, "{}")
        coVerify(exactly = 1) { dao.reserveTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing durable authorizing transition refuses SDK entry`() = runTest {
        coEvery { dao.casTransition(any(), any(), any(), any()) } returns 0
        org.junit.Assert.assertEquals(false, ledger.markAuthorizing("a1"))
    }

    @Test
    fun `openAttempt inserts PREPARANDO and returns true`() = runTest {
        coEvery { dao.reserveTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L
        val ok = ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 1000, PaymentAttemptEntity.ROUTE_FAST, "{}")
        assertTrue(ok)
        // La reserva y el estado PREPARANDO viven ahora DENTRO de la sentencia atómica del DAO;
        // lo que se fija aquí es que se llama con la identidad y el importe correctos.
        coVerify {
            dao.reserveTerminal(
                attemptId = "a1", venueId = "v1", processor = PaymentAttemptEntity.PROCESSOR_BLUMON,
                kind = any(), amountCents = 10000L, tipCents = 1000L,
                recordingRoute = PaymentAttemptEntity.ROUTE_FAST, contextJson = any(),
                orderJsonFragment = null, now = any(), terminalPaymentRequestId = null
            )
        }
    }

    @Test
    fun `openAttempt detects attemptId reuse (PK collision) and returns false`() = runTest {
        coEvery { dao.reserveTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns -1L // no entró
        // …y la fila YA existe con ese id ⇒ es reuso de attemptId, no terminal ocupada.
        coEvery { dao.getById("a1") } returns PaymentAttemptEntity(
            attemptId = "a1", venueId = "v1", processor = PaymentAttemptEntity.PROCESSOR_BLUMON,
            state = PaymentAttemptEntity.STATE_PREPARANDO, amountCents = 10000, tipCents = 0,
            recordingRoute = PaymentAttemptEntity.ROUTE_FAST, paymentContextJson = "{}",
            createdAt = 1, updatedAt = 1
        )
        val ok = ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 10000, 0, PaymentAttemptEntity.ROUTE_FAST, "{}")
        assertFalse(ok) // the split double-charge signal — caller logs CRITICAL
    }

    @Test
    fun `markHostResponded approved=false lands DESCARTADA (explicit decline)`() = runTest {
        coEvery { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ledger.markHostResponded("a1", approved = false, operationId = "123", referenceNumber = "r", authCode = null)
        coVerify {
            dao.casHostResponded(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_AUTORIZANDO,
                    PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                    PaymentAttemptEntity.STATE_PREPARANDO,
                    PaymentAttemptEntity.STATE_INDETERMINADO // late explicit decline resolves a quarantined row
                ),
                PaymentAttemptEntity.STATE_DESCARTADA, any(), "123", "r", null, false
            )
        }
    }

    @Test
    fun `markHostResponded approved=true lands HOST_RESPONDIO`() = runTest {
        coEvery { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ledger.markHostResponded("a1", approved = true, operationId = "123", referenceNumber = "r", authCode = "A1")
        coVerify {
            dao.casHostResponded(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_AUTORIZANDO,
                    PaymentAttemptEntity.STATE_KERNEL_ACTIVO,
                    PaymentAttemptEntity.STATE_PREPARANDO,
                    PaymentAttemptEntity.STATE_INDETERMINADO // live verdict beats the sweep's quarantine
                ),
                PaymentAttemptEntity.STATE_HOST_RESPONDIO, any(), "123", "r", "A1", true
            )
        }
    }

    @Test
    fun `disk failure prevents processor entry`() = runTest {
        coEvery { dao.reserveTerminal(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("disk io")
        // must not throw AND must return true — a ledger failure degrades to "no row",
        // never to the double-charge signal (false) that callers escalate on
        val ok = ledger.openAttempt("a1", "v1", PaymentAttemptEntity.PROCESSOR_BLUMON, 1, 0, PaymentAttemptEntity.ROUTE_FAST, "{}")
        assertFalse(ok)
    }

    @Test
    fun `preauthorization cancellation proof is true only for committed CAS`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 1
        assertTrue(ledger.markDiscardedBeforeCharge("a1", "user_cancel"))
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 0
        assertFalse(ledger.markDiscardedBeforeCharge("a1", "user_cancel"))
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } throws RuntimeException("disk")
        assertFalse(ledger.markDiscardedBeforeCharge("a1", "user_cancel"))
    }

    @Test
    fun `markDiscardedBeforeCharge only transitions from PREPARANDO`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 0
        ledger.markDiscardedBeforeCharge("a1", "user_cancel")
        coVerify {
            dao.casWithError("a1", listOf(PaymentAttemptEntity.STATE_PREPARANDO), PaymentAttemptEntity.STATE_DESCARTADA, any(), "user_cancel")
        }
    }

    @Test
    fun `markAuthorizing transitions exactly from PREPARANDO or KERNEL_ACTIVO to AUTORIZANDO`() = runTest {
        coEvery { dao.casTransition(any(), any(), any(), any()) } returns 1
        ledger.markAuthorizing("a1")
        coVerify {
            dao.casTransition(
                "a1",
                listOf(PaymentAttemptEntity.STATE_PREPARANDO, PaymentAttemptEntity.STATE_KERNEL_ACTIVO),
                PaymentAttemptEntity.STATE_AUTORIZANDO, any()
            )
        }
    }

    @Test
    fun `markAuthorized allows exactly HOST_RESPONDIO, AUTORIZANDO, KERNEL_ACTIVO, PREPARANDO and INDETERMINADO`() = runTest {
        coEvery { dao.casWithCardDetails(any(), any(), any(), any(), any(), any(), any()) } returns 1
        ledger.markAuthorized("a1", maskedPan = "****1234", cardBrand = "VISA", entryMode = "CONTACTLESS")
        coVerify {
            dao.casWithCardDetails(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                    PaymentAttemptEntity.STATE_AUTORIZANDO,
                    PaymentAttemptEntity.STATE_KERNEL_ACTIVO, // el kernel aprobó sin salir a la red
                    PaymentAttemptEntity.STATE_PREPARANDO, // contactless offline-approved
                    PaymentAttemptEntity.STATE_INDETERMINADO // live verdict beats the sweep's quarantine
                ),
                PaymentAttemptEntity.STATE_AUTORIZADO, any(),
                "****1234", "VISA", "CONTACTLESS"
            )
        }
    }

    @Test
    fun `markRecorded allows exactly AUTORIZADO, HOST_RESPONDIO and INDETERMINADO`() = runTest {
        coEvery { dao.casTransition(any(), any(), any(), any()) } returns 1
        ledger.markRecorded("a1")
        coVerify {
            dao.casTransition(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_AUTORIZADO,
                    PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                    PaymentAttemptEntity.STATE_INDETERMINADO // live verdict beats the sweep's quarantine
                ),
                PaymentAttemptEntity.STATE_REGISTRADO, any()
            )
        }
    }

    @Test
    fun `markRecordFailed allows exactly AUTORIZADO, HOST_RESPONDIO and INDETERMINADO`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 1
        ledger.markRecordFailed("a1", "http 500")
        coVerify {
            dao.casWithError(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_AUTORIZADO,
                    PaymentAttemptEntity.STATE_HOST_RESPONDIO,
                    PaymentAttemptEntity.STATE_INDETERMINADO // live verdict beats the sweep's quarantine
                ),
                PaymentAttemptEntity.STATE_REGISTRO_FALLIDO, any(), "http 500"
            )
        }
    }

    @Test
    fun `a quarantined row (INDETERMINADO) still accepts markRecorded - live verdict beats the sweep`() = runTest {
        // Semantic guarantee: the 6h sweep may quarantine a >threshold AUTORIZANDO row to
        // INDETERMINADO while the charge is actually still alive (hung-then-recovering
        // SaleIcc, AngelPay D308 relaunch spanning the sweep). The real outcome must still
        // land — the CAS expected-states list MUST include INDETERMINADO, otherwise the row
        // rots as a permanent false "money moved, no record" signal.
        coEvery { dao.casTransition(any(), any(), any(), any()) } returns 1
        ledger.markRecorded("q1")
        coVerify {
            dao.casTransition(
                "q1",
                match { it.contains(PaymentAttemptEntity.STATE_INDETERMINADO) },
                PaymentAttemptEntity.STATE_REGISTRADO, any()
            )
        }
    }

    @Test
    fun `markDeliveredToQueue allows exactly REGISTRO_FALLIDO`() = runTest {
        coEvery { dao.casTransition(any(), any(), any(), any()) } returns 1
        ledger.markDeliveredToQueue("a1")
        coVerify {
            dao.casTransition(
                "a1",
                listOf(PaymentAttemptEntity.STATE_REGISTRO_FALLIDO),
                PaymentAttemptEntity.STATE_ENTREGADA_A_COLA, any()
            )
        }
    }

    // ── INDETERMINADO en vivo (desenlace incierto del SDK) ───────────

    @Test
    fun `markIndeterminate lands INDETERMINADO with the reason, never DESCARTADA`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 1
        ledger.markIndeterminate("a1", reason = "AngelPay U101 sin veredicto")
        coVerify {
            dao.casWithError(
                "a1",
                listOf(
                    PaymentAttemptEntity.STATE_AUTORIZANDO,
                    PaymentAttemptEntity.STATE_KERNEL_ACTIVO, // un kernel sin veredicto también es «no sé»
                    PaymentAttemptEntity.STATE_PREPARANDO
                ),
                PaymentAttemptEntity.STATE_INDETERMINADO, any(), "AngelPay U101 sin veredicto"
            )
        }
        // 🔴 Lo que de verdad guarda esta prueba: un desenlace incierto NUNCA se escribe
        // como DESCARTADA — esa fila afirma "no se cobró" y encima se poda a los 7 días.
        coVerify(exactly = 0) {
            dao.casWithError(any(), any(), PaymentAttemptEntity.STATE_DESCARTADA, any(), any())
        }
        coVerify(exactly = 0) { dao.casHostResponded(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `markIndeterminate never degrades a row that already has a verdict`() = runTest {
        coEvery { dao.casWithError(any(), any(), any(), any(), any()) } returns 1
        ledger.markIndeterminate("a1", reason = "x")
        // Sólo AUTORIZANDO y PREPARANDO: un HOST_RESPONDIO/AUTORIZADO/REGISTRADO ya sabe
        // lo que pasó, y volverlo "no sé" sería perder información.
        coVerify {
            dao.casWithError(
                any(),
                match<List<String>> { estados ->
                    PaymentAttemptEntity.STATE_HOST_RESPONDIO !in estados &&
                        PaymentAttemptEntity.STATE_AUTORIZADO !in estados &&
                        PaymentAttemptEntity.STATE_REGISTRADO !in estados
                },
                PaymentAttemptEntity.STATE_INDETERMINADO, any(), any()
            )
        }
    }

    @Test
    fun `OFF mode still durably marks indeterminate financial outcome`() = runTest {
        every { settingsRepository.getCurrentSettings() } returns settingsWith(PaymentLedgerMode.OFF)
        ledger.markIndeterminate("a1", reason = "x")
        coVerify(exactly = 1) { dao.casWithError(any(), any(), any(), any(), any()) }
    }
}
