package com.jaac.avoqado_tpv.features.checkout.presentation

import androidx.lifecycle.viewModelScope
import com.jaac.avoqado_tpv.core.data.local.SecureStorage
import com.jaac.avoqado_tpv.features.checkout.data.ActiveCartState
import com.jaac.avoqado_tpv.features.checkout.domain.model.MosaicShortcut
import com.jaac.avoqado_tpv.features.checkout.domain.model.SavedCart
import com.jaac.avoqado_tpv.features.checkout.domain.repository.MosaicRepository
import com.jaac.avoqado_tpv.features.checkout.domain.repository.SavedCartsRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Customer
import com.jaac.avoqado_tpv.features.ordering.domain.CustomerRepository
import com.jaac.avoqado_tpv.features.ordering.domain.DiscountRepository
import com.jaac.avoqado_tpv.features.ordering.domain.OrderRepository
import com.jaac.avoqado_tpv.features.ordering.domain.Product
import com.jaac.avoqado_tpv.features.ordering.domain.ProductRepository
import com.jaac.avoqado_tpv.features.referrals.domain.model.ValidationResult as ReferralValidationResult
import com.jaac.avoqado_tpv.features.referrals.domain.repository.ReferralValidationException
import com.jaac.avoqado_tpv.features.referrals.domain.usecase.CaptureReferralUseCase
import com.jaac.avoqado_tpv.features.referrals.domain.usecase.ValidateReferralUseCase
import com.jaac.avoqado_tpv.features.referrals.presentation.ReferralCaptureUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * Plan 5A — TPV Referral Capture: ViewModel-level coverage of the
 * referral validation + capture wiring. Mocks the use cases (which wrap
 * the Retrofit repository) so we only test the orchestration that lives
 * in CheckoutViewModel.
 *
 * Mirrors the setup pattern of `CheckoutViewModelTest` (UnconfinedTestDispatcher
 * for eager init, `viewModelScope.cancel()` to keep `runTest` happy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelReferralTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var productRepository: ProductRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var orderRepository: OrderRepository
    private lateinit var discountRepository: DiscountRepository
    private lateinit var savedCartsRepository: SavedCartsRepository
    private lateinit var mosaicRepository: MosaicRepository
    private lateinit var secureStorage: SecureStorage
    private lateinit var activeCartState: ActiveCartState
    private lateinit var validateReferralUseCase: ValidateReferralUseCase
    private lateinit var captureReferralUseCase: CaptureReferralUseCase

    private val savedCartsFlow = MutableStateFlow<List<SavedCart>>(emptyList())
    private val shortcutsFlow = MutableStateFlow<List<MosaicShortcut>>(emptyList())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        productRepository = mockk(relaxed = true)
        customerRepository = mockk(relaxed = true)
        orderRepository = mockk(relaxed = true)
        discountRepository = mockk(relaxed = true)
        savedCartsRepository = mockk(relaxed = true)
        mosaicRepository = mockk(relaxed = true)
        secureStorage = mockk(relaxed = true)
        activeCartState = ActiveCartState()
        validateReferralUseCase = mockk()
        captureReferralUseCase = mockk()

        coEvery { secureStorage.getVenueId() } returns "venue-1"
        coEvery { secureStorage.getStaffId() } returns "staff-1"
        coEvery { secureStorage.getStaffName() } returns "María"
        coEvery { productRepository.getProducts(any(), any()) } returns Result.success(emptyList())
        coEvery { productRepository.getCategories(any()) } returns Result.success(emptyList())
        coEvery { savedCartsRepository.observeAll() } returns savedCartsFlow
        coEvery { mosaicRepository.observe(any()) } returns shortcutsFlow
        coEvery { customerRepository.getRecentCustomers(any(), any()) } returns Result.success(emptyList())
        coEvery { customerRepository.searchCustomers(any(), any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = CheckoutViewModel(
        productRepository = productRepository,
        customerRepository = customerRepository,
        orderRepository = orderRepository,
        discountRepository = discountRepository,
        savedCartsRepository = savedCartsRepository,
        mosaicRepository = mosaicRepository,
        activeCartState = activeCartState,
        secureStorage = secureStorage,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
    )

    private fun fakeProduct(priceCents: Int = 10_000) = Product(
        id = "p-1",
        name = "Café",
        sku = "P1",
        price = BigDecimal(priceCents).divide(BigDecimal(100)),
        categoryId = "cat-1",
        categoryName = "Bebidas",
        description = null,
        emoji = "☕",
        imageUrl = null,
        available = true,
    )

    private fun fakeCustomer(id: String = "c-1") = Customer(
        id = id,
        firstName = "Beto",
        lastName = null,
        email = null,
        phone = "5551112222",
        loyaltyPoints = 0,
        totalVisits = 0,
        totalSpent = BigDecimal.ZERO,
        customerGroup = null,
    )

    // ─────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `valid code applies referral discount to cart and sets Valid state`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000)) // subtotal $100

        coEvery {
            validateReferralUseCase(
                venueId = "venue-1",
                referralCode = "ANA-2026",
                newCustomerId = "c-1",
            )
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana López",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()

        val ui = viewModel.referralValidation.value
        assertTrue("expected Valid, got $ui", ui is ReferralCaptureUiState.Valid)
        ui as ReferralCaptureUiState.Valid
        assertEquals("Ana López", ui.referrerName)
        assertEquals(10, ui.discountPercent)

        val cart = viewModel.cartState.value
        assertEquals(1000, cart.manualDiscountCents) // 10% of $100
        assertEquals("REFERRAL_NEW_CUSTOMER", cart.manualDiscountSource)
        assertEquals(9_000, cart.totalCents)

        viewModel.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Rejection reasons — one test per branch
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `PROGRAM_INACTIVE rejection flips state to Invalid and leaves cart untouched`() = runTest {
        rejectionPathFor(ReferralValidationResult.Reason.PROGRAM_INACTIVE)
    }

    @Test
    fun `CODE_NOT_FOUND rejection flips state to Invalid`() = runTest {
        rejectionPathFor(ReferralValidationResult.Reason.CODE_NOT_FOUND)
    }

    @Test
    fun `SELF_REFERRAL rejection flips state to Invalid`() = runTest {
        rejectionPathFor(ReferralValidationResult.Reason.SELF_REFERRAL)
    }

    @Test
    fun `EXISTING_CUSTOMER rejection flips state to Invalid`() = runTest {
        rejectionPathFor(ReferralValidationResult.Reason.EXISTING_CUSTOMER)
    }

    private suspend fun rejectionPathFor(reason: ReferralValidationResult.Reason) {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(
                venueId = "venue-1",
                referralCode = "BAD",
                newCustomerId = "c-1",
            )
        } returns Result.success(ReferralValidationResult.Invalid(reason))

        viewModel.onReferralCodeChange("BAD")
        viewModel.validateReferralCode()

        val ui = viewModel.referralValidation.value
        assertTrue("expected Invalid($reason), got $ui", ui is ReferralCaptureUiState.Invalid)
        assertEquals(reason, (ui as ReferralCaptureUiState.Invalid).reason)

        val cart = viewModel.cartState.value
        assertEquals(0, cart.manualDiscountCents)
        // The subtotal should remain untouched, no discount applied.
        assertEquals(10_000, cart.totalCents)

        viewModel.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Edge cases
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `validateReferralCode is a noop when no customer is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()

        // No customer means the use case must NOT be called and the state
        // must remain Idle.
        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        coVerify(exactly = 0) { validateReferralUseCase(any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `editing the code after a Valid result resets state to Idle`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        assertTrue(viewModel.referralValidation.value is ReferralCaptureUiState.Valid)

        viewModel.onReferralCodeChange("ANA-2027")

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `switching customer clears referral state and discount`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer(id = "c-1"))
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        assertEquals(1000, viewModel.cartState.value.manualDiscountCents)

        viewModel.selectCustomer(fakeCustomer(id = "c-3"))

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        assertEquals("", viewModel.referralCode.value)
        assertEquals(0, viewModel.cartState.value.manualDiscountCents)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearReferral wipes the manual discount when source is the referral tag`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        assertEquals(1000, viewModel.cartState.value.manualDiscountCents)

        viewModel.clearReferral()

        assertEquals(0, viewModel.cartState.value.manualDiscountCents)
        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        assertEquals("", viewModel.referralCode.value)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `clearReferral does NOT wipe a manual discount that was set by the operator`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        // Operator-typed manual discount (no source tag).
        viewModel.applyManualOrderDiscount(amountCents = 500, reason = "Cliente frecuente")
        assertEquals(500, viewModel.cartState.value.manualDiscountCents)

        viewModel.clearReferral()

        // The manual discount stays — only referral state is reset.
        assertEquals(500, viewModel.cartState.value.manualDiscountCents)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `network failure during validate surfaces UNKNOWN reason`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.failure(RuntimeException("boom"))

        viewModel.onReferralCodeChange("ANY")
        viewModel.validateReferralCode()

        val ui = viewModel.referralValidation.value
        assertTrue(ui is ReferralCaptureUiState.Invalid)
        assertEquals(
            ReferralValidationResult.Reason.UNKNOWN,
            (ui as ReferralCaptureUiState.Invalid).reason,
        )

        viewModel.viewModelScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Capture-on-payment behavior
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `captureReferralOnPayment is a noop when state is not Valid`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())

        val ok = viewModel.captureReferralOnPayment(orderId = "ord_1")

        assertTrue(ok)
        coVerify(exactly = 0) { captureReferralUseCase(any(), any(), any(), any(), any()) }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `captureReferralOnPayment calls use case when state is Valid`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )
        coEvery {
            captureReferralUseCase(
                venueId = "venue-1",
                referralCode = "ANA-2026",
                newCustomerId = "c-1",
                capturedByStaffVenueId = "staff-1",
                intendedOrderId = "ord_1",
            )
        } returns Result.success("ref_1")

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()

        val ok = viewModel.captureReferralOnPayment(orderId = "ord_1")

        assertTrue(ok)
        coVerify(exactly = 1) {
            captureReferralUseCase(
                venueId = "venue-1",
                referralCode = "ANA-2026",
                newCustomerId = "c-1",
                capturedByStaffVenueId = "staff-1",
                intendedOrderId = "ord_1",
            )
        }

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `captureReferralOnPayment surfaces ReferralValidationException as Invalid state`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectCustomer(fakeCustomer())
        viewModel.addProduct(fakeProduct(priceCents = 10_000))

        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ReferralValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "c-2",
            ),
        )
        // Backend now says EXISTING_CUSTOMER between validate + capture
        // (e.g., a duplicate order race). The ViewModel should reflect it.
        coEvery {
            captureReferralUseCase(any(), any(), any(), any(), any())
        } returns Result.failure(
            ReferralValidationException(ReferralValidationResult.Reason.EXISTING_CUSTOMER),
        )

        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()

        val ok = viewModel.captureReferralOnPayment(orderId = "ord_2")

        assertFalse(ok)
        val ui = viewModel.referralValidation.value
        assertTrue(ui is ReferralCaptureUiState.Invalid)
        assertEquals(
            ReferralValidationResult.Reason.EXISTING_CUSTOMER,
            (ui as ReferralCaptureUiState.Invalid).reason,
        )
        // Discount was wiped because capture rejected.
        assertEquals(0, viewModel.cartState.value.manualDiscountCents)

        viewModel.viewModelScope.cancel()
    }
}
