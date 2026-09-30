package com.jaac.avoqado_tpv.features.payments.presentation

import com.jaac.avoqado_tpv.BuildConfig
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.core.domain.models.Result
import com.jaac.avoqado_tpv.core.printer.PrinterManager
import com.jaac.avoqado_tpv.features.payments.domain.models.PaginatedPayments
import com.jaac.avoqado_tpv.features.payments.domain.models.Payment
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod
import com.jaac.avoqado_tpv.features.payments.domain.models.PaymentStatus
import com.jaac.avoqado_tpv.features.payment.domain.processor.ProcessorType
import com.jaac.avoqado_tpv.features.payment.domain.processor.RefundLocation
import com.jaac.avoqado_tpv.features.payments.domain.repository.PaymentRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsViewModelRefundAvailabilityTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val paymentRepository: PaymentRepository = mockk()
    private val secureStorage: SecureStorage = mockk()
    private val permissionsRepository: PermissionsRepository = mockk()
    private val printerManager: PrinterManager = mockk(relaxed = true)

    @Test
    fun getRefundAvailability_onNexgo_blocksPaxBlumonPaymentsWithDeviceMessage() = runTest {
        assumeFalse(
            "Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)",
            BuildConfig.ENABLE_PAX_SDK
        )

        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "N62"
        coEvery {
            paymentRepository.getPaymentHistory(
                venueId = any(),
                pageNumber = any(),
                pageSize = any(),
                fromDate = any(),
                toDate = any(),
                staffId = any()
            )
        } returns Result.Success(
            PaginatedPayments(
                payments = emptyList(),
                total = 0,
                page = 1,
                pageSize = 20
            )
        )

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager
        )
        advanceUntilIdle()

        val paxPayment = Payment(
            id = "pay_1",
            orderId = "ord_1",
            orderNumber = "1001",
            venueId = "venue_1",
            amount = BigDecimal("100.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("100.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            merchantAccountId = "merchant_1",
            blumonSerialNumber = "PAX-A920-001",
            blumonOperationNumber = 12345
        )

        val availability = viewModel.getRefundAvailability(paxPayment)

        assertThat(availability.canRefund).isFalse()
        assertThat(availability.reason)
            .isEqualTo("Este pago se procesó en PAX/Blumon. Debes hacer el reembolso en ese dispositivo.")
    }

    @Test
    fun getRefundAvailability_onNexgo_allowsAngelPaySameDeviceWhenCardPaymentIsValid() = runTest {
        assumeFalse(
            "Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)",
            BuildConfig.ENABLE_PAX_SDK
        )

        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "N62"
        coEvery {
            paymentRepository.getPaymentHistory(
                venueId = any(),
                pageNumber = any(),
                pageSize = any(),
                fromDate = any(),
                toDate = any(),
                staffId = any()
            )
        } returns Result.Success(
            PaginatedPayments(
                payments = emptyList(),
                total = 0,
                page = 1,
                pageSize = 20
            )
        )

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager
        )
        advanceUntilIdle()

        val angelPayLikePayment = Payment(
            id = "pay_777",
            orderId = "ord_777",
            orderNumber = "777",
            venueId = "venue_1",
            amount = BigDecimal("777.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("777.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            merchantAccountId = "merchant_1",
            // This field can exist even on non-Blumon flows; should not force BLUMON classification.
            blumonSerialNumber = "2841548417",
            blumonOperationNumber = null,
            processor = "TBD"
        )

        val availability = viewModel.getRefundAvailability(angelPayLikePayment)

        assertThat(availability.canRefund).isTrue()
        assertThat(availability.reason).isNull()
    }

    // ═══════════════════════════════════════════════════════════════════
    // getRefundLocation tests — drives the payment-card warning badge
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun getRefundLocation_onNexgo_returnsOtherProcessorForBlumonPayment() = runTest {
        assumeFalse(
            "Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)",
            BuildConfig.ENABLE_PAX_SDK
        )

        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "N62"
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val blumonPayment = Payment(
            id = "pay_blumon",
            orderId = "ord_1",
            orderNumber = "1001",
            venueId = "venue_1",
            amount = BigDecimal("100.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("100.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            merchantAccountId = "merchant_1",
            blumonOperationNumber = 12345, // Blumon signal → classified as BLUMON
        )

        val location = viewModel.getRefundLocation(blumonPayment)

        assertThat(location).isEqualTo(RefundLocation.OtherProcessor(ProcessorType.BLUMON))
    }

    @Test
    fun getRefundLocation_cashPayment_returnsNotApplicable() = runTest {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "N62"
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val cashPayment = Payment(
            id = "pay_cash",
            orderId = null,
            orderNumber = null,
            venueId = "venue_1",
            amount = BigDecimal("50.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("50.00"),
            method = PaymentMethod.CASH,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
        )

        val location = viewModel.getRefundLocation(cashPayment)

        assertThat(location).isEqualTo(RefundLocation.NotApplicable)
    }

    @Test
    fun getRefundLocation_alreadyFullyRefunded_returnsNotApplicable() = runTest {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "N62"
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val refundedPayment = Payment(
            id = "pay_refunded",
            orderId = "ord_1",
            orderNumber = "1001",
            venueId = "venue_1",
            amount = BigDecimal("100.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("100.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            isFullyRefunded = true,
            blumonOperationNumber = 12345,
        )

        val location = viewModel.getRefundLocation(refundedPayment)

        // Already-refunded payments rely on the existing "Reembolsado" badge.
        // The location warning would be redundant noise.
        assertThat(location).isEqualTo(RefundLocation.NotApplicable)
    }

    @Test
    fun getRefundLocation_sameProcessorOtherDevice_returnsOtherDevice() = runTest {
        assumeFalse(
            "Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)",
            BuildConfig.ENABLE_PAX_SDK
        )

        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "NEXGO-A"
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val otherDevicePayment = Payment(
            id = "pay_other_dev",
            orderId = "ord_1",
            orderNumber = "1001",
            venueId = "venue_1",
            amount = BigDecimal("80.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("80.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            merchantAccountId = "merchant_1",
            processor = "ANGELPAY",
            // Different from current device serial (NEXGO-A).
            deviceSerialNumber = "NEXGO-B",
        )

        val location = viewModel.getRefundLocation(otherDevicePayment)

        assertThat(location).isEqualTo(RefundLocation.OtherDevice("NEXGO-B"))
    }

    @Test
    fun getRefundLocation_sameProcessorSameDevice_returnsHere() = runTest {
        assumeFalse(
            "Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)",
            BuildConfig.ENABLE_PAX_SDK
        )

        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "NEXGO-A"
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(emptyList(), 0, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val samePayment = Payment(
            id = "pay_same",
            orderId = "ord_1",
            orderNumber = "1001",
            venueId = "venue_1",
            amount = BigDecimal("100.00"),
            tipAmount = BigDecimal.ZERO,
            totalAmount = BigDecimal("100.00"),
            method = PaymentMethod.CARD,
            processedBy = null,
            createdAt = Instant.now(),
            status = PaymentStatus.COMPLETED,
            tableName = null,
            merchantAccountId = "merchant_1",
            processor = "ANGELPAY",
            deviceSerialNumber = "NEXGO-A",
        )

        val location = viewModel.getRefundLocation(samePayment)

        assertThat(location).isEqualTo(RefundLocation.Here)
    }

    @Test
    fun `P1 un reembolso FALLIDO no deja el pago como reembolsado ni se muestra`() = runTest {
        // 🔴 25-sep, PAX de pruebas: se anuló en Avoqado un reembolso que Blumon nunca hizo (queda como fila FAILED) y la
        // lista lo seguía sumando: el pago de $254 salía «Reembolsado» y la terminal no dejaba reembolsarlo de verdad.
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "2841548417"
        fun pago(id: String, total: String, status: PaymentStatus, reembolso: Boolean) = Payment(
            id = id, orderId = "ord_254", orderNumber = "955058", venueId = "venue_1",
            amount = BigDecimal(total), tipAmount = BigDecimal.ZERO, totalAmount = BigDecimal(total),
            method = PaymentMethod.CARD, processedBy = null, createdAt = Instant.now(), status = status,
            tableName = null, isRefundTransaction = reembolso,
        )
        coEvery {
            paymentRepository.getPaymentHistory(any(), any(), any(), any(), any(), any())
        } returns Result.Success(PaginatedPayments(listOf(
            pago("pay_visa", "254.00", PaymentStatus.COMPLETED, reembolso = false),
            pago("ref_falso", "-254.00", PaymentStatus.FAILED, reembolso = true),
        ), 2, 1, 20))

        val viewModel = PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
        )
        advanceUntilIdle()

        val mostrados = (viewModel.state.value as PaymentsState.Success).payments
        assertThat(mostrados.map { it.id }).containsExactly("pay_visa")
        assertThat(mostrados.single().isFullyRefunded).isFalse()
    }

    // ═══════════════════════════════════════════════════════════════════
    // 🔴 Founder, 29-sep-2026: AngelPay sólo CANCELA completa antes del corte de las 11 pm del día de la venta; las
    // devoluciones posteriores las tiene apagadas para todos los comercios. Pasado el corte, la lista no ofrece
    // «Reembolsar»: dice a quién pedírselo, en vez de dejar que el cajero lo intente y falle.
    // ═══════════════════════════════════════════════════════════════════

    private fun vmConReloj(
        ahora: String,
        devoluciones: com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase? = null,
    ): PaymentsViewModel {
        coEvery { permissionsRepository.hasPermission("payments:refund") } returns true
        coEvery { secureStorage.getVenueId() } returns "venue_1"
        coEvery { secureStorage.getSerialNumber() } returns "NEXGO-A"
        coEvery {
            paymentRepository.getPaymentHistory(
                venueId = any(), pageNumber = any(), pageSize = any(), fromDate = any(), toDate = any(), staffId = any()
            )
        } returns Result.Success(PaginatedPayments(payments = emptyList(), total = 0, page = 1, pageSize = 20))
        return PaymentsViewModel(
            paymentRepository = paymentRepository,
            secureStorage = secureStorage,
            permissionsRepository = permissionsRepository,
            printerManager = printerManager,
            clock = java.time.Clock.fixed(Instant.parse(ahora), java.time.ZoneOffset.UTC),
            recordAngelPayRefundUseCase = devoluciones,
        )
    }

    private fun ventaAngelPay(cobradaEn: String) = Payment(
        id = "pay_corte", orderId = "ord_corte", orderNumber = "88", venueId = "venue_1",
        amount = BigDecimal("1.00"), tipAmount = BigDecimal.ZERO, totalAmount = BigDecimal("1.00"),
        method = PaymentMethod.CARD, processedBy = null, createdAt = Instant.parse(cobradaEn),
        status = PaymentStatus.COMPLETED, tableName = null, merchantAccountId = "merchant_1",
        processor = "ANGELPAY", deviceSerialNumber = "NEXGO-A",
    )

    @Test
    fun `P1 en Nexgo una venta de AngelPay de AYER no ofrece reembolsar y dice a quien pedirselo`() = runTest {
        assumeFalse("Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)", BuildConfig.ENABLE_PAX_SDK)
        // Venta a las 18:00 del 28-sep (CDMX); ahora son las 12:00 del 29-sep: ya pasó el corte de las 23:00 del 28.
        val viewModel = vmConReloj("2026-09-29T18:00:00Z")
        advanceUntilIdle()

        val availability = viewModel.getRefundAvailability(ventaAngelPay("2026-09-29T00:00:00Z"))

        assertThat(availability.canRefund).isFalse()
        assertThat(availability.reason)
            .isEqualTo(com.jaac.avoqado_tpv.features.payment.domain.processor.AngelPayCorte.MENSAJE_FUERA_DE_CORTE)
    }

    @Test
    fun `P1 en Nexgo pasado el corte una devolucion POR CONFIRMAR si se deja abrir - se confirma y se registra`() = runTest {
        assumeFalse("Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)", BuildConfig.ENABLE_PAX_SDK)
        val devoluciones = mockk<com.jaac.avoqado_tpv.features.payment.domain.usecase.RecordAngelPayRefundUseCase>()
        coEvery { devoluciones.tieneDevolucionPorResolver("pay_corte") } returns true
        val viewModel = vmConReloj("2026-09-29T18:00:00Z", devoluciones)
        advanceUntilIdle()
        val venta = ventaAngelPay("2026-09-29T00:00:00Z")

        viewModel.showPaymentDetail(venta)
        advanceUntilIdle()

        val availability = viewModel.getRefundAvailability(venta)
        assertThat(availability.canRefund).isTrue()
        assertThat(availability.reason).isNull()
    }

    @Test
    fun `en Nexgo una venta de AngelPay de HOY antes del corte si se puede reembolsar`() = runTest {
        assumeFalse("Este test aplica para variante Nexgo (ENABLE_PAX_SDK=false)", BuildConfig.ENABLE_PAX_SDK)
        // Venta a las 12:00 del 29-sep (CDMX); ahora son las 22:59 del mismo día.
        val viewModel = vmConReloj("2026-09-30T04:59:00Z")
        advanceUntilIdle()

        val availability = viewModel.getRefundAvailability(ventaAngelPay("2026-09-29T18:00:00Z"))

        assertThat(availability.canRefund).isTrue()
        assertThat(availability.reason).isNull()
    }
}
