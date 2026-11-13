package com.jaac.avoqado_tpv.features.payment.domain.usecase

import com.google.common.truth.Truth.assertThat
import android.content.Context
import com.jaac.avoqado_tpv.features.authentication.data.repository.AuthRepository
import com.jaac.avoqado_tpv.features.payment.data.repository.RefundRecorder
import com.jaac.avoqado_tpv.features.payment.domain.model.RefundReason
import com.jaac.avoqado_tpv.features.payment.domain.processor.PaymentPostOperationsAdapter
import com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationsAdapterFactory
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * P0 guard (2026-07-09): the AngelPay SDK post-operations refund/cancel the FULL
 * original sale amount — [RecordAngelPayRefundUseCase.processSdkRefund] must reject
 * anything that isn't a full, first-time refund BEFORE touching the SDK, otherwise
 * a "$100 partial" on a $1,000 sale returns $1,000 to the cardholder while Avoqado
 * books $100.
 */
class RecordAngelPayRefundUseCaseTest {

    private val refundRecorder: RefundRecorder = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val adapterFactory: PostOperationsAdapterFactory = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)
    private val refundQueue: com.jaac.avoqado_tpv.features.payment.domain.repository.RefundQueueRepository = mockk(relaxed = true)
    private val ledger: com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptLedger = mockk(relaxed = true)

    /** 12:00 del 1-jul en la Ciudad de México: el mismo día (antes del corte de las 23:00) que el cobro de los fixtures. */
    private val mismoDia = java.time.Clock.fixed(Instant.parse("2026-07-01T18:00:00Z"), java.time.ZoneOffset.UTC)
    private val estado = com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder()

    private fun casoDeUso(clock: java.time.Clock = mismoDia) = RecordAngelPayRefundUseCase(
        refundRecorder = refundRecorder,
        authRepository = authRepository,
        postOperationsAdapterFactory = adapterFactory,
        refundQueueRepository = refundQueue,
        paymentAttemptLedger = ledger,
        clock = clock,
        paymentStateHolder = estado, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())

    private val useCase = casoDeUso()

    init {
        com.jaac.avoqado_tpv.features.shift.data.bridgeLegacyLedgerFixture(ledger)
        // La libreta sana abre el intento y lo deja autorizar; sin duda previa de este pago.
        coEvery { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { ledger.markAuthorizing(any()) } returns true
        coEvery { ledger.devolucionSinResolver(any()) } returns null
        // Las escrituras de cierre quedan: así la red del `finally` sólo actúa cuando de verdad nada cerró la fila.
        coEvery { ledger.markHostResponded(any(), any(), any(), any(), any()) } returns true
        coEvery { ledger.markDiscardedBeforeCharge(any(), any()) } returns true
    }

    private suspend fun runRefund(
        requested: String,
        original: String,
        alreadyRefunded: String,
    ) = useCase.processSdkRefund(
        paymentReference = "000000188231",
        createdAt = Instant.parse("2026-07-01T12:00:00Z"),
        requestedReason = RefundReason.CUSTOMER_REQUEST,
        appContext = appContext,
        requestedAmount = BigDecimal(requested),
        originalAmount = BigDecimal(original),
        alreadyRefundedAmount = BigDecimal(alreadyRefunded),
        originalPaymentId = "pay-001",
        paymentVenueId = "venue-1",
    )

    @Test
    fun `partial refund is rejected before touching the SDK`() = runTest {
        val result = runRefund(requested = "100.00", original = "1000.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        // The guard must fire BEFORE any SDK/adapter interaction.
        verify(exactly = 0) { adapterFactory.get(any()) }
    }

    @Test
    fun `refund on a payment with a prior partial refund is rejected`() = runTest {
        // Even if the operator asks for exactly the remaining balance, the SDK
        // would return the FULL original amount — must be blocked.
        val result = runRefund(requested = "900.00", original = "1000.00", alreadyRefunded = "100.00")

        assertTrue(result.isFailure)
        assertEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        verify(exactly = 0) { adapterFactory.get(any()) }
    }

    @Test
    fun `full first-time refund passes the guard and reaches the SDK adapter`() = runTest {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        // Empty history → downstream "transaction not found" failure, proving we got past the guard.
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        val result = runRefund(requested = "1000.00", original = "1000.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertNotEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
        coVerify(atLeast = 1) { adapter.getTransactionHistory(any()) }
    }

    @Test
    fun `guard compares numeric value, not BigDecimal scale`() = runTest {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        // "1000" vs "1000.00" — equals() differs by scale, compareTo does not.
        val result = runRefund(requested = "1000", original = "1000.00", alreadyRefunded = "0")

        assertTrue(result.isFailure)
        assertNotEquals(
            RecordAngelPayRefundUseCase.PARTIAL_REFUND_UNSUPPORTED_MESSAGE,
            result.exceptionOrNull()?.message,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 💸 Task 7 + auditoría de Codex (4-sep): write-ahead, candado y desenlaces
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun recordBackend(key: String = "k-fixed") = useCase.recordInBackend(
        paymentId = "pay-001",
        orderId = null,
        paymentVenueId = "venue-1",
        merchantAccountId = "merchant-1",
        originalTotalAmount = BigDecimal("100.00"),
        refundAmount = BigDecimal("100.00"),
        refundReason = RefundReason.CUSTOMER_REQUEST,
        sdkReferenceNumber = "000000188231",
        tipRefundCents = null,
        refundedAmount = BigDecimal.ZERO,
        idempotencyKey = key,
    )

    private fun conSesion() { every { authRepository.getStaffId() } returns "staff-1" }
    private fun writeAheadOk() { coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.success(Unit) }
    private fun sinRed() {
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(java.io.IOException("sin red"))
    }

    private fun filaEncolada() = com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund(
        idempotencyKey = "k-prev", venueId = "venue-1", staffId = "s1", processor = ProcessorType.ANGELPAY,
        originalPaymentId = "pay-001", originalOrderId = null, amount = BigDecimal("100.00"),
        originalTotalAmount = BigDecimal("100.00"), tipRefundCents = null, isPartialRefund = false,
        refundReason = RefundReason.CUSTOMER_REQUEST, merchantAccountId = "merchant-1", blumonSerialNumber = "",
        originalOperationNumber = 0, authorizationNumber = "ANGELPAY_SDK", referenceNumber = "000000188231",
        maskedPan = null, cardBrand = null, entryMode = "OTHER", createdAt = 1_000L,
    )

    @Test
    fun `P1 la fila write-ahead se escribe ANTES del POST y lleva la MISMA llave que viaja al servidor`() = runTest {
        conSesion()
        val orden = mutableListOf<String>()
        val fila = slot<com.jaac.avoqado_tpv.features.payment.domain.model.QueuedRefund>()
        coEvery { refundQueue.enqueueClaimed(capture(fila), any()) } coAnswers { orden += "room"; Result.success(Unit) }
        val ctx = slot<com.jaac.avoqado_tpv.features.payment.domain.model.PaymentContext.RefundPayment>()
        coEvery { refundRecorder.recordRefund(capture(ctx), any(), any(), any(), any(), any()) } coAnswers {
            orden += "red"
            Result.failure(java.io.IOException("sin red"))
        }

        val r = recordBackend("k-9")

        assertThat(orden).containsExactly("room", "red").inOrder()
        assertThat(fila.captured.idempotencyKey).isEqualTo("k-9")
        assertThat(ctx.captured.idempotencyKey).isEqualTo("k-9")
        assertThat(fila.captured.processor).isEqualTo(ProcessorType.ANGELPAY)
        assertThat(fila.captured.authorizationNumber).isEqualTo("ANGELPAY_SDK")
        assertThat(fila.captured.referenceNumber).isEqualTo("000000188231")
        coVerify(exactly = 1) { refundQueue.release("k-9", any(), 1, any()) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
        assertTrue(!(r.exceptionOrNull() as RefundQueuedException).permanent)
    }

    @Test
    fun `P1 un 422 deja la fila FAILED permanente y el aviso lo dice`() = runTest {
        conSesion(); writeAheadOk()
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(com.jaac.avoqado_tpv.core.data.network.BackendHttpException(422, "monto excede"))

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.markPermanentlyFailed("k-fixed", any(), match { it.contains("monto excede") }) }
        coVerify(exactly = 0) { refundQueue.release(any(), any(), any(), any()) }
        assertTrue((r.exceptionOrNull() as RefundQueuedException).permanent)
    }

    @Test
    fun `P1 sin sesion se escribe la fila y no se toca la red`() = runTest {
        every { authRepository.getStaffId() } returns null
        writeAheadOk()

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.enqueueClaimed(any(), any()) }
        coVerify(exactly = 1) { refundQueue.release("k-fixed", any(), 1, any()) }
        coVerify(exactly = 0) { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
    }

    @Test
    fun `el exito del servidor cierra la fila write-ahead con el mismo token`() = runTest {
        conSesion()
        val token = slot<String>()
        coEvery { refundQueue.enqueueClaimed(any(), capture(token)) } returns Result.success(Unit)
        coEvery { refundRecorder.recordRefund(any(), any(), any(), any(), any(), any()) } returns
            Result.success(mockk(relaxed = true))

        val r = recordBackend()

        assertTrue(r.isSuccess)
        coVerify(exactly = 1) { refundQueue.markSuccess("k-fixed", token.captured) }
        coVerify(exactly = 0) { refundQueue.release(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si el write-ahead falla se cae al encolado de respaldo`() = runTest {
        conSesion(); sinRed()
        coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { refundQueue.enqueue(any()) } returns Result.success(Unit)

        val r = recordBackend()

        coVerify(exactly = 1) { refundQueue.enqueue(match { it.idempotencyKey == "k-fixed" && !it.permanent }) }
        assertTrue(r.exceptionOrNull() is RefundQueuedException)
    }

    @Test
    fun `P1 si write-ahead, backend Y respaldo fallan se reporta como perdido, jamas como exito`() = runTest {
        conSesion(); sinRed()
        coEvery { refundQueue.enqueueClaimed(any(), any()) } returns Result.failure(IllegalStateException("room"))
        coEvery { refundQueue.enqueue(any()) } returns Result.failure(IllegalStateException("disco lleno"))

        val r = recordBackend()

        assertTrue(r.exceptionOrNull() is RefundLostException)
    }

    @Test
    fun `P1 processSdkRefund se NIEGA si el pago ya tiene una devolucion sin registrar - antes del SDK`() = runTest {
        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns listOf(filaEncolada())

        val result = runRefund(requested = "100.00", original = "100.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("sin registrar"))
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `el candado dice la verdad si la devolucion previa fue RECHAZADA - no promete que se registre sola`() = runTest {
        // founder, N86, 7-sep-2026: con la fila FAILED permanente el texto decía «espera a que se
        // registre», una espera infinita — el servidor ya la rechazó y nadie la reintenta.
        val rechazada = filaEncolada().copy(
            syncStatus = com.jaac.avoqado_tpv.core.data.local.entity.PendingRefundEntity.SYNC_STATUS_FAILED,
            permanent = true,
            lastError = "HTTP 400: Datos de reembolso inválidos",
        )
        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns listOf(rechazada)

        val result = runRefund(requested = "100.00", original = "100.00", alreadyRefunded = "0.00")

        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("RECHAZÓ registrar"))
        assertTrue(msg.contains("HTTP 400: Datos de reembolso inválidos"))
        assertTrue(msg.contains("no se reintenta sola"))
        assertTrue(!msg.contains("Espera a que se registre"))
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `validateBeforeSdk rechaza venue o merchant vacios y deja pasar lo completo`() {
        assertTrue(useCase.validateBeforeSdk("", "merchant-1") != null)
        assertTrue(useCase.validateBeforeSdk("venue-1", "") != null)
        assertTrue(useCase.validateBeforeSdk("venue-1", "merchant-1") == null)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 🔴 Founder, 29-sep-2026: «nada puede detener las ventas». Cada salida de la devolución deja su fila de la libreta
    // en un estado que dice la verdad y que no aparta la terminal; el corte de AngelPay se decide antes de tocar nada.
    // ═══════════════════════════════════════════════════════════════════════

    private fun venta(postReferencia: String? = null, postEstado: String? = null, creadaEn: String = "2026-07-01T12:00:00Z") =
        com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction(
            processorType = ProcessorType.ANGELPAY, reference = "000000188231", amount = 1000.0,
            authorizationCode = "473067", status = "APROBADA", cardType = null, last4 = "8892", cardBin = "45551203",
            entryMode = "contactless", date = "2026-07-01", time = "06:00:00", operationType = "VENTA",
            merchantName = null, affiliation = null, terminal = null, folio = "F1", waiter = null, tip = 0.0,
            issuingBank = null, cardMethod = null, creationDate = creadaEn, aid = null, arqc = null,
            postOperationType = postReferencia?.let { "CANCELACION" }, postOperationReference = postReferencia,
            postOperationAuthorization = null, postOperationStatus = postEstado,
        )

    private fun adapterConLaVenta(venta: com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction = venta()):
        PaymentPostOperationsAdapter {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(venta))
        return adapter
    }

    private fun respuesta(aprobada: Boolean, codigo: String?, estado: String, mensaje: String? = null) =
        com.jaac.avoqado_tpv.features.payment.domain.processor.PostOperationResult(
            approved = aprobada, status = estado, message = mensaje,
            authorizationCode = if (aprobada) "A1" else null, reference = if (aprobada) "R1" else null, errorCode = codigo,
        )

    /** La llave del intento que abrió la devolución (nace dentro del caso de uso). */
    private fun llaveAbierta(): io.mockk.CapturingSlot<String> {
        val llave = slot<String>()
        coEvery { ledger.openAttempt(capture(llave), any(), any(), any(), any(), any(), any(), any()) } returns true
        return llave
    }

    private val alDiaSiguiente = java.time.Clock.fixed(Instant.parse("2026-07-02T18:00:00Z"), java.time.ZoneOffset.UTC)

    private suspend fun devolverTodo(caso: RecordAngelPayRefundUseCase = useCase) =
        caso.processSdkRefund(
            paymentReference = "000000188231",
            createdAt = Instant.parse("2026-07-01T12:00:00Z"),
            requestedReason = RefundReason.CUSTOMER_REQUEST,
            appContext = appContext,
            requestedAmount = BigDecimal("1000.00"),
            originalAmount = BigDecimal("1000.00"),
            alreadyRefundedAmount = BigDecimal.ZERO,
            originalPaymentId = "pay-001",
            paymentVenueId = "venue-1",
        )

    @Test
    fun `P1 despues del corte de AngelPay no se intenta nada - ni libreta ni AngelPay`() = runTest {
        val alDiaSiguiente = java.time.Clock.fixed(Instant.parse("2026-07-02T18:00:00Z"), java.time.ZoneOffset.UTC)

        val r = devolverTodo(casoDeUso(alDiaSiguiente))

        assertTrue(r.isFailure)
        assertEquals(
            com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO,
            r.exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { adapterFactory.get(any()) }
    }

    @Test
    fun `P1 si la libreta no puede abrir el intento no se llama a AngelPay`() = runTest {
        coEvery { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        val adapter = adapterConLaVenta()

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_NO_SE_GUARDO, r.exceptionOrNull()?.message)
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 sin red el historial falla y la fila se cierra como no enviada - el caso de la N86`() = runTest {
        val llave = llaveAbierta()
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns
            Result.failure(java.io.IOException("Sin conexión a internet o servidor no disponible"))

        val r = devolverTodo()

        assertTrue(r.isFailure)
        assertThat(r.exceptionOrNull()!!.message).contains("No se reembolsó nada")
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, match { it.startsWith("antes_del_sdk") }) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si la venta no aparece en el historial la fila se cierra como no enviada`() = runTest {
        val llave = llaveAbierta()
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(emptyList())

        devolverTodo()

        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, match { it.startsWith("antes_del_sdk") }) }
    }

    @Test
    fun `P1 un rechazo explicito de AngelPay cierra la fila como rechazada y no prueba la devolucion`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns
            Result.success(respuesta(false, "G500", "DECLINED", "Transaccion rechazada por el gateway"))

        val r = devolverTodo()

        assertTrue(r.isFailure)
        assertThat(r.exceptionOrNull()!!.message).contains("No se devolvió nada")
        coVerify(exactly = 1) { ledger.markHostResponded(llave.captured, false, any(), any(), any()) }
        coVerify(exactly = 0) { ledger.markIndeterminate(llave.captured, match { it.startsWith("devolucion_sin_veredicto") }) }
        coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
        // Un rechazo que NO es de referencia es la respuesta: otra referencia sería otro intento sobre la MISMA venta.
        coVerify(exactly = 1) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 una respuesta sin veredicto deja la devolucion en duda y no prueba otras referencias`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns
            Result.success(respuesta(false, "G506", "ERROR", "Resultado no concluyente por intermitencia del servidor de AngelPay"))

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA, r.exceptionOrNull()?.message)
        coVerify(exactly = 1) { ledger.markIndeterminate(llave.captured, match { it.startsWith("devolucion_sin_veredicto") }) }
        coVerify(exactly = 0) { ledger.markHostResponded(llave.captured, any(), any(), any(), any()) }
        coVerify(exactly = 1) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si el SDK truena la devolucion queda en duda`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns Result.failure(RuntimeException("socket cerrado"))

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA, r.exceptionOrNull()?.message)
        coVerify(exactly = 1) { ledger.markIndeterminate(llave.captured, match { it.startsWith("devolucion_sin_veredicto") }) }
    }

    @Test
    fun `P1 la venta cobrada en otra Nexgo llega al SDK con la terminal que la cobro`() = runTest {
        // D1 (12-nov, SDK 1.0.23): la venta se busca por referencia SIN filtro de terminal, así que puede ser de otra Nexgo
        // del mismo comercio. El adaptador manda `terminal` como `originalTerminal`; aquí se cuida que nadie la pierda en el
        // camino (p. ej., armando otra `UnifiedTransaction` en vez de copiar la del historial).
        val adapter = adapterConLaVenta(venta().copy(terminal = "N860W173233"))
        val enviada = slot<com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction>()
        coEvery { adapter.cancelTransaction(capture(enviada), any(), any(), any()) } returns
            Result.success(respuesta(true, "S000", "APPROVED"))

        devolverTodo()

        assertEquals("N860W173233", enviada.captured.terminal)
    }

    @Test
    fun `P1 referencia repetida en dos Nexgo - aunque el importe desempate, no se toca AngelPay`() = runTest {
        // Codex r1 #1 y r3 #1 (12-nov): con `originalTerminal`, AngelPay cancela EXACTAMENTE la venta que se le manda; y si la
        // cancelación queda en duda, el propio SDK la confirma buscando por la referencia SIN terminal (`resolveInDoubtPostOp`),
        // así que podría dar por buena la cancelación de la OTRA venta. Una referencia con dos ventas no se devuelve desde aquí.
        val llave = llaveAbierta()
        val otra = venta().copy(terminal = "N860W173233", amount = 500.0, authorizationCode = "111111", folio = "F9")
        val la = venta().copy(terminal = "N860W173400")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(otra, la))

        val resultado = devolverTodo()

        assertTrue(resultado.exceptionOrNull()!!.message!!.contains("más de una venta"))
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:venta_ambigua") }
    }

    @Test
    fun `P1 una sola venta con esa referencia pero de OTRO importe no se devuelve`() = runTest {
        // Codex r3 #2: la única candidata se mandaba aunque su importe no fuera el del pago.
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta(venta().copy(amount = 500.0))

        val resultado = devolverTodo()

        assertTrue(resultado.exceptionOrNull()!!.message!!.contains("es de \$500.00"))
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:importe_distinto") }
    }

    @Test
    fun `P1 si con esa referencia solo aparece una cancelacion, no hay venta que devolver`() = runTest {
        // Codex r3 #2: sin venta reconocida se elegía entre todos los renglones, incluida una cancelación sola.
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta(venta().copy(idOperation = 6, operationType = "CANCELACION"))

        devolverTodo()

        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:sin_venta") }
    }

    @Test
    fun `P1 referencia repetida sin desempate - no se adivina, no se toca AngelPay y no se devuelve nada`() = runTest {
        val llave = llaveAbierta()
        val una = venta().copy(terminal = "N860W173233", authorizationCode = "111111", folio = "F9")
        val otra = venta().copy(terminal = "N860W173400")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(una, otra))

        val resultado = devolverTodo()

        assertTrue(resultado.isFailure)
        assertTrue(resultado.exceptionOrNull()!!.message!!.contains("más de una venta"))
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:venta_ambigua") }
    }

    @Test
    fun `P1 la venta con OTRO renglon de su referencia (una cancelacion, aun sin terminal) no se devuelve`() = runTest {
        // Codex r2 #1 → r5 #1: si la llamada queda en duda, el SDK la confirma con el PRIMER renglón de la referencia
        // (`resolveInDoubtPostOp`), sin mirar terminal, autorización ni folio. Que coincida la terminal (o que las dos vengan
        // vacías) no prueba que esa cancelación sea de esta venta: cualquier otra operación con la referencia frena.
        val llave = llaveAbierta()
        val cancelacion = venta(postReferencia = "PR-9", postEstado = "APROBADA")
            .copy(idOperation = 6, operationType = "CANCELACION", authorizationCode = "999999", folio = "F2")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(cancelacion, venta()))

        devolverTodo()

        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:venta_ambigua") }
    }

    @Test
    fun `P1 otra operacion de la MISMA terminal con la misma referencia tambien frena`() = runTest {
        val llave = llaveAbierta()
        val a = venta().copy(terminal = "N860W173400")
        val otra = venta(postReferencia = "PR-9", postEstado = "APROBADA")
            .copy(terminal = "N860W173400", idOperation = 6, operationType = "CANCELACION", authorizationCode = "111111", folio = "F9")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(otra, a))

        devolverTodo()

        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:venta_ambigua") }
    }

    @Test
    fun `P1 un reintento por referencia invalida nunca usa la referencia de OTRA venta del mismo importe`() = runTest {
        // Codex r2 #2: el historial amplio puntuaba como «la misma venta» a cualquiera del mismo importe, y el reintento salía
        // con la referencia de B y la autorización, la tarjeta y la terminal de A.
        val a = venta().copy(terminal = "N860W173400")
        val b = venta().copy(terminal = "N860W173400", reference = "000000199999", authorizationCode = "222222", folio = "F7")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(match { it.reference != null }) } returns Result.success(listOf(a))
        coEvery { adapter.getTransactionHistory(match { it.reference == null }) } returns Result.success(listOf(a, b))
        val referencias = mutableListOf<String>()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            referencias += firstArg<com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction>().reference
            Result.success(respuesta(false, null, "DECLINED", "Referencia invalida"))
        }

        devolverTodo()

        assertTrue("se intentó: $referencias", referencias.isNotEmpty())
        assertTrue("salió la referencia de otra venta: $referencias", "000000199999" !in referencias)
    }

    @Test
    fun `P1 tras referencia invalida solo se reintenta EXACTAMENTE la misma referencia, normal y manual`() = runTest {
        // Codex r3 #3–#5 y r4 #2: cualquier otra cadena (de la base del SDK, del historial, el folio, la autorización o la
        // referencia sin ceros) puede ser la referencia literal de OTRA venta y salir con los datos de ésta.
        val a = venta().copy(terminal = "N860W173400")
        val alias = a.copy(reference = "999999")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(match { it.reference != null }) } returns Result.success(listOf(a))
        coEvery { adapter.getTransactionHistory(match { it.reference == null }) } returns Result.success(listOf(a, alias))
        val intentos = mutableListOf<Pair<String, Boolean>>()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            intentos += firstArg<com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction>().reference to arg<Boolean>(3)
            Result.success(respuesta(false, null, "DECLINED", "Referencia invalida"))
        }

        devolverTodo()

        assertEquals(listOf("000000188231" to false, "000000188231" to true), intentos)
    }

    @Test
    fun `P1 la referencia se busca al menos 31 dias hacia atras, lo mismo que mira el verificador del SDK`() = runTest {
        // Codex r4: el verificador del SDK (`resolveInDoubtPostOp`) mira los últimos 30 días; si nuestra búsqueda mirara menos,
        // una venta B de esa referencia fuera de nuestra ventana no frenaría la devolución y el SDK sí la vería.
        val adapter = adapterConLaVenta()
        val consulta = slot<com.jaac.avoqado_tpv.features.payment.domain.processor.TransactionHistoryQuery>()
        coEvery { adapter.getTransactionHistory(capture(consulta)) } returns Result.success(listOf(venta()))
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns Result.success(respuesta(true, "S000", "APPROVED"))

        devolverTodo()

        // mismoDia = 2026-07-01 12:00 en México ⇒ 31 días antes: 2026-05-31.
        assertTrue(consulta.captured.startDate, consulta.captured.startDate <= "2026-05-31")
    }

    @Test
    fun `P1 un renglon de OTRA terminal con la misma referencia (aunque sea cancelacion) frena la devolucion`() = runTest {
        // Codex r4 #1: si la llamada queda en duda, el SDK la confirma con el PRIMER renglón de esa referencia
        // (`resolveInDoubtPostOp`), sin mirar terminal ni tipo; la cancelación aprobada de B daría por devuelta a A.
        val llave = llaveAbierta()
        val cancelacionDeB = venta(postReferencia = "PR-9", postEstado = "APROBADA").copy(
            terminal = "N860W173233", idOperation = 6, operationType = "CANCELACION", authorizationCode = "111111", folio = "F9",
        )
        val a = venta().copy(terminal = "N860W173400")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(cancelacionDeB, a))

        val resultado = devolverTodo()

        assertTrue(resultado.exceptionOrNull()!!.message!!.contains("más de una venta"))
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:venta_ambigua") }
    }

    @Test
    fun `P1 una devolucion en duda no se da por aplicada con la cancelacion de OTRA venta de la misma referencia`() = runTest {
        // Con dos ventas aprobadas con la misma referencia e importe, la que trae la cancelación aplicada puede no ser la de este
        // pago: no se confirma nada y no se intenta otra.
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        val otraCancelada = venta(postReferencia = "PR-9", postEstado = "APROBADA")
            .copy(terminal = "N860W173233", authorizationCode = "111111", folio = "F9")
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(otraCancelada, venta()))

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA_PREVIA, r.exceptionOrNull()?.message)
        coVerify(exactly = 0) { ledger.markHostResponded("duda-1", any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 una devolucion en duda no se confirma con la cancelacion de la unica venta si es de OTRO importe`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        val adapter = adapterConLaVenta(venta(postReferencia = "PR-9", postEstado = "APROBADA").copy(amount = 500.0))

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA_PREVIA, r.exceptionOrNull()?.message)
        coVerify(exactly = 0) { ledger.markHostResponded("duda-1", any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 una operacion que no se reconoce como VENTA (check-in, reautorizacion, sin tipo) no se devuelve`() = runTest {
        // Codex r6 #1: «es venta» se decide EN POSITIVO, con la misma regla que ya confirma cobros en el historial
        // (`AngelPayChargeVerifier`: `operationType` VENTA o SALE; medido en QA con `idOperation = 4`).
        listOf(
            venta().copy(operationType = "CHECKIN", idOperation = null),
            venta().copy(operationType = "RE_AUTORIZACIÓN", idOperation = 11),
            venta().copy(operationType = null, idOperation = null),
            venta().copy(operationType = "VENTA", idOperation = 10),
        ).forEach { renglon ->
            io.mockk.clearMocks(ledger, answers = false)
            val llave = llaveAbierta()
            val adapter = adapterConLaVenta(renglon)

            devolverTodo()

            coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
            coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:sin_venta") }
        }
    }

    @Test
    fun `P2 la misma venta repetida en el historial no cuenta como ambigua`() = runTest {
        val adapter: PaymentPostOperationsAdapter = mockk(relaxed = true)
        every { adapterFactory.get(ProcessorType.ANGELPAY) } returns adapter
        coEvery { adapter.getTransactionHistory(any()) } returns Result.success(listOf(venta(), venta()))
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns Result.success(respuesta(true, "S000", "APPROVED"))

        devolverTodo()

        coVerify(exactly = 1) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 despues de aprobar no se prueba otra referencia ni otra operacion`() = runTest {
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns Result.success(respuesta(true, "S000", "APPROVED"))

        devolverTodo()

        coVerify(exactly = 1) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.refundTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 sin red AngelPay contesta N400 - rechazo cierto, la fila se cierra y el pago NO queda cercado`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns
            Result.success(respuesta(false, "N400", "ERROR", "Sin conexión a internet"))

        val r = devolverTodo()

        assertThat(r.exceptionOrNull()!!.message).contains("No se devolvió nada")
        coVerify(exactly = 1) { ledger.markHostResponded(llave.captured, false, any(), any(), any()) }
        coVerify(exactly = 0) { ledger.markIndeterminate(llave.captured, any()) }
    }

    @Test
    fun `P1 si la pantalla muere a media llamada la fila no queda autorizando - queda en duda`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } throws kotlinx.coroutines.CancellationException("pantalla cerrada")

        val lanzo = runCatching { devolverTodo() }.exceptionOrNull()

        assertTrue(lanzo is kotlinx.coroutines.CancellationException)
        coVerify(exactly = 1) { ledger.markIndeterminate(llave.captured, any()) }
        assertTrue("la marca de devolución en curso se soltó", !estado.isRefundInFlight())
    }

    @Test
    fun `P1 la referencia invalida en todas las variantes cierra como rechazada`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns
            Result.success(respuesta(false, null, "DECLINED", "Referencia invalida"))

        devolverTodo()

        coVerify(exactly = 1) { ledger.markHostResponded(llave.captured, false, any(), any(), any()) }
        coVerify(exactly = 0) { ledger.markIndeterminate(llave.captured, match { it.startsWith("devolucion_sin_veredicto") }) }
    }

    @Test
    fun `P1 la marca de devolucion en curso se renueva antes de CADA llamada a AngelPay`() = runTest {
        // Auditoría del 29-sep (P2): con muchas referencias el bucle puede pasar de 2 min; la marca vence a los 2 min de la
        // ÚLTIMA llamada, no de la primera, para que un cobro del POS no arranque a media devolución.
        val espia = io.mockk.spyk(com.jaac.avoqado_tpv.features.payment.data.processor.angelpay.PaymentStateHolder())
        val caso = RecordAngelPayRefundUseCase(
            refundRecorder, authRepository, adapterFactory, refundQueue, ledger, mismoDia, espia, shiftRepository = com.jaac.avoqado_tpv.features.shift.data.legacyShiftBoundaryFixture())
        val adapter = adapterConLaVenta()
        var llamadas = 0
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            llamadas++
            Result.success(respuesta(false, null, "DECLINED", "Referencia invalida"))
        }

        devolverTodo(caso)

        assertTrue("se probaron varias referencias", llamadas > 1)
        verify(atLeast = llamadas) { espia.marcarDevolucionEnCurso(any()) }
    }

    @Test
    fun `P1 la cancelacion aprobada deja la fila respondida y la llave viaja con la aprobacion`() = runTest {
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } returns Result.success(respuesta(true, "S000", "APPROVED"))

        val r = devolverTodo()

        assertTrue(r.isSuccess)
        assertEquals(llave.captured, r.getOrNull()?.idempotencyKey)
        coVerify(exactly = 1) { ledger.markHostResponded(llave.captured, true, any(), any(), any()) }
    }

    @Test
    fun `P1 mientras AngelPay contesta la terminal marca devolucion en curso y la suelta al terminar`() = runTest {
        val adapter = adapterConLaVenta()
        var enCursoDuranteLaLlamada = false
        var cobrandoDuranteLaLlamada = false
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            enCursoDuranteLaLlamada = estado.isRefundInFlight()
            cobrandoDuranteLaLlamada = estado.isCharging()
            Result.success(respuesta(true, "S000", "APPROVED"))
        }

        devolverTodo()

        assertTrue("durante la llamada", enCursoDuranteLaLlamada)
        assertTrue("la sesión de AngelPay no se cambia a media devolución", cobrandoDuranteLaLlamada)
        assertTrue("al terminar se suelta", !estado.isRefundInFlight() && !estado.isCharging())
    }

    @Test
    fun `P1 al terminar la devolucion no suelta la bandera de cobro de una venta que arranco despues`() = runTest {
        val adapter = adapterConLaVenta()
        coEvery { adapter.cancelTransaction(any(), any(), any(), any()) } coAnswers {
            estado.setCharging(true)   // una venta arrancó mientras AngelPay contestaba
            Result.success(respuesta(true, "S000", "APPROVED"))
        }

        devolverTodo()

        assertTrue("la devolución soltó SU marca", !estado.isRefundInFlight())
        assertTrue("la venta conserva su bandera de cobro", estado.isCharging())
    }

    @Test
    fun `P1 la hora de AngelPay cierra el corte aunque Avoqado registrara la venta despues`() = runTest {
        // Avoqado la registró el 1-jul (cola sin red); AngelPay la cobró el 30-jun a las 14:00: su corte fue el 30-jun 23:00.
        val llave = llaveAbierta()
        val adapter = adapterConLaVenta(venta(creadaEn = "2026-06-30T20:00:00Z"))

        val r = devolverTodo()

        assertEquals(
            com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO,
            r.exceptionOrNull()?.message,
        )
        coVerify(exactly = 1) { ledger.markDiscardedBeforeCharge(llave.captured, "antes_del_sdk:corte_angelpay") }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 la hora de AngelPay nunca ABRE un corte que la de Avoqado ya cerro`() = runTest {
        // Avoqado: cobrada el 1-jul 06:00 (corte 1-jul 23:00); ahora es el 2-jul. AngelPay trae una hora POSTERIOR (2-jul):
        // aun así no se abre la libreta ni se llama a AngelPay — su hora sólo puede cerrar el corte.
        val adapter = adapterConLaVenta(venta(creadaEn = "2026-07-02T17:00:00Z"))

        val r = devolverTodo(casoDeUso(alDiaSiguiente))

        assertEquals(
            com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO,
            r.exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `la hora de AngelPay sin zona se lee como hora de Mexico y nunca abre un corte vencido`() = runTest {
        // Avoqado dice 1-jul 06:00 (antes del corte); AngelPay trae «2026-06-30 22:59:00» sin zona ⇒ corte 30-jun 23:00 ⇒ vencido.
        val adapter = adapterConLaVenta(venta(creadaEn = "2026-06-30 22:59:00"))

        val r = devolverTodo()

        assertEquals(
            com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte.MENSAJE_FUERA_DE_CORTE_SIN_INTENTO,
            r.exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    private fun duda(id: String, estado: String) = com.jaac.avoqado_tpv.features.payment.data.ledger.PaymentAttemptEntity(
        attemptId = id, venueId = "venue-1", processor = "angelpay", state = estado, amountCents = 100_000, tipCents = 0,
        recordingRoute = "REFUND", paymentContextJson = """{"originalPaymentId":"pay-001"}""", createdAt = 1, updatedAt = 1,
        kind = "REFUND",
    )

    @Test
    fun `P1 con una devolucion en duda del MISMO pago no se intenta otra`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        val adapter = adapterConLaVenta(venta())

        val r = devolverTodo()

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA_PREVIA, r.exceptionOrNull()?.message)
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 si AngelPay ya muestra la cancelacion aplicada la duda se resuelve con la MISMA llave`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        val adapter = adapterConLaVenta(venta(postReferencia = "PR-1", postEstado = "APROBADA"))

        val r = devolverTodo()

        assertTrue(r.isSuccess)
        assertEquals("duda-1", r.getOrNull()?.idempotencyKey)
        coVerify(exactly = 1) { ledger.markHostResponded("duda-1", true, any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    // ═══ Auditoría del 29-sep (P1): pasado el corte, lo que ya salió hacia AngelPay SE PUEDE confirmar y registrar ═══

    @Test
    fun `la lista pregunta con los MISMOS candados si el pago tiene una devolucion por resolver`() = runTest {
        assertTrue("sin nada pendiente", !useCase.tieneDevolucionPorResolver("pay-001"))

        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns listOf(filaEncolada())
        assertTrue("una devolución encolada sin registrar", useCase.tieneDevolucionPorResolver("pay-001"))

        coEvery { refundQueue.unresolvedForPayment("pay-001") } returns emptyList()
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        assertTrue("una devolución en duda en la libreta", useCase.tieneDevolucionPorResolver("pay-001"))
    }

    @Test
    fun `P1 pasado el corte una devolucion en duda que AngelPay ya aplico se confirma con su historial y su llave`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        val adapter = adapterConLaVenta(venta(postReferencia = "PR-1", postEstado = "APROBADA"))

        val r = devolverTodo(casoDeUso(alDiaSiguiente))

        assertTrue(r.isSuccess)
        assertEquals("duda-1", r.getOrNull()?.idempotencyKey)
        coVerify(exactly = 1) { ledger.markHostResponded("duda-1", true, any(), any(), any()) }
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 pasado el corte una devolucion aprobada y sin registrar se entrega con su llave`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("aprobada-1", "HOST_RESPONDIO")
        val adapter = adapterConLaVenta()

        val r = devolverTodo(casoDeUso(alDiaSiguiente))

        assertTrue(r.isSuccess)
        assertEquals("aprobada-1", r.getOrNull()?.idempotencyKey)
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
    }

    @Test
    fun `P1 pasado el corte una duda que el historial no confirma dice que esta en duda - nunca que no se reembolso nada`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("duda-1", "INDETERMINADO")
        adapterConLaVenta(venta())

        val r = devolverTodo(casoDeUso(alDiaSiguiente))

        assertEquals(RecordAngelPayRefundUseCase.MENSAJE_DEVOLUCION_EN_DUDA_PREVIA, r.exceptionOrNull()?.message)
    }

    @Test
    fun `P1 una devolucion ya aprobada y sin registrar se vuelve a entregar con su llave - sin llamar a AngelPay`() = runTest {
        coEvery { ledger.devolucionSinResolver("pay-001") } returns duda("aprobada-1", "HOST_RESPONDIO")
        val adapter = adapterConLaVenta()

        val r = devolverTodo()

        assertTrue(r.isSuccess)
        assertEquals("aprobada-1", r.getOrNull()?.idempotencyKey)
        coVerify(exactly = 0) { adapter.cancelTransaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.openAttempt(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
