package com.jaac.avoqado_tpv.features.serialized_inventory.presentation

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.MainDispatcherRule
import com.jaac.avoqado_tpv.features.modules.domain.repository.ModulesRepository
import com.jaac.avoqado_tpv.features.permissions.data.repository.PermissionsRepository
import com.jaac.avoqado_tpv.features.serialized_inventory.domain.model.InventoryScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.model.ScanResult
import com.jaac.avoqado_tpv.features.serialized_sale.domain.repository.SerializedSaleRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * SerializedInventoryViewModelTest
 *
 * Three layers of defense converge in onBarcodeScanned() — this test pins each one:
 *
 * 1. **Format guard** (Mexican ICCID regex `^8952\d{15,16}F?$`): hard-rejects malformed
 *    strings that ZXing might emit on damaged/dirty barcodes (letters mid-string,
 *    non-MX prefixes, wrong length, special chars).
 *
 * 2. **Luhn checksum** (ISO/IEC 7812 mod-10): flag-only — surfaces as
 *    [InventoryScanResult.NeedsConfirmation] so the promotor visually verifies the
 *    sticker before adding to batch. NOT a hard reject because empirically ~0.1% of
 *    legit carrier SIMs (verified: 1020/1021 ALTAN samples) fail Luhn.
 *
 * 3. **Canonicalization**: trim + uppercase before validation, so equivalent forms
 *    don't slip past the duplicate check (the original production bug where ZXing
 *    misread produced two distinct strings for the same physical sticker).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerializedInventoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var serializedSaleRepository: SerializedSaleRepository
    private lateinit var modulesRepository: ModulesRepository
    private lateinit var permissionsRepository: PermissionsRepository

    /**
     * Real ALTAN ICCID from production incident (the "correct" reading of the sticker
     * that ZXing also misread as 895214006363166018BF). Verified to pass both regex
     * and Luhn — useful as a happy-path control.
     */
    private val realIccid = "8952140063631660183F"

    private fun createViewModel(): SerializedInventoryViewModel {
        return SerializedInventoryViewModel(
            serializedSaleRepository = serializedSaleRepository,
            modulesRepository = modulesRepository,
            permissionsRepository = permissionsRepository,
        )
    }

    @Before
    fun setup() {
        serializedSaleRepository = mockk(relaxed = true)
        modulesRepository = mockk(relaxed = true)
        permissionsRepository = mockk(relaxed = true)

        coEvery { serializedSaleRepository.getCategories() } returns Result.success(emptyList())
        coEvery { permissionsRepository.hasPermission(any()) } returns false
        every { modulesRepository.getModuleConfig(any()) } returns null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 1 — Format guard (regex)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `format guard rejects ICCID with letter mid-string (the original production misread)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        // The actual misread from production: B at position 19 instead of 3
        viewModel.onBarcodeScanned("895214006363166018BF") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Error::class.java)
        val error = capturedResult as InventoryScanResult.Error
        assertThat(error.message).contains("Formato")
        assertThat(viewModel.uiState.value.error).contains("8952")
    }

    @Test
    fun `format guard rejects ICCID with double quote (manual entry typo)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        viewModel.onBarcodeScanned("8952140063631660183\"F") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Error::class.java)
    }

    @Test
    fun `format guard rejects non-Mexican prefix`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        // 8957 = Brazil country code, not Mexico
        viewModel.onBarcodeScanned("8957140063631660183F") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Error::class.java)
    }

    @Test
    fun `format guard rejects too-short alphanumeric (e g , partial scan)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        viewModel.onBarcodeScanned("89521400") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Error::class.java)
    }

    @Test
    fun `format guard accepts canonical ALTAN ICCID with F padding`() = runTest {
        coEvery { serializedSaleRepository.scanItem(any()) } returns Result.success(
            ScanResult.NotRegistered(realIccid)
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        viewModel.onBarcodeScanned(realIccid) { capturedResult = it }
        advanceUntilIdle()

        // Should pass format and Luhn, hit repository, then add to batch
        assertThat(capturedResult).isNotInstanceOf(InventoryScanResult.Error::class.java)
        assertThat(capturedResult).isNotInstanceOf(InventoryScanResult.NeedsConfirmation::class.java)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 2 — Luhn checksum (flag-only)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Luhn fail surfaces NeedsConfirmation (NOT Error) so legit-but-Luhn-fail SIMs aren't lost`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        // Real ICCID with valid format (matches regex) but FAILS Luhn — this is the
        // exact failing sample from the user's production inventory (1 of 1,021 ALTAN
        // SIMs). If Luhn were a hard reject, we'd lose this legit SIM.
        viewModel.onBarcodeScanned("8952140064116106929F") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.NeedsConfirmation::class.java)
        assertThat(viewModel.uiState.value.pendingLuhnConfirmation).isEqualTo("8952140064116106929F")
        // Should NOT show inline error — the dialog handles UX
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `confirmLuhnWarning proceeds to batch validation as if Luhn had passed`() = runTest {
        val luhnFailingIccid = "8952140064116106929F"
        coEvery { serializedSaleRepository.scanItem(luhnFailingIccid) } returns Result.success(
            ScanResult.NotRegistered(luhnFailingIccid)
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Step 1: scan → triggers Luhn warning
        viewModel.onBarcodeScanned(luhnFailingIccid) { /* ignore */ }
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.pendingLuhnConfirmation).isEqualTo(luhnFailingIccid)

        // Step 2: user confirms (visually verified sticker matches)
        var confirmResult: InventoryScanResult? = null
        viewModel.confirmLuhnWarning(luhnFailingIccid) { confirmResult = it }
        advanceUntilIdle()

        // Pending cleared, item added to batch
        assertThat(viewModel.uiState.value.pendingLuhnConfirmation).isNull()
        assertThat(confirmResult).isInstanceOf(InventoryScanResult.Added::class.java)
        assertThat(viewModel.uiState.value.scannedSerialNumbers).contains(luhnFailingIccid)
    }

    @Test
    fun `dismissLuhnWarning clears pending state without adding to batch`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBarcodeScanned("8952140064116106929F") { /* ignore */ }
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.pendingLuhnConfirmation).isNotNull()

        viewModel.dismissLuhnWarning()

        assertThat(viewModel.uiState.value.pendingLuhnConfirmation).isNull()
        assertThat(viewModel.uiState.value.scannedSerialNumbers).isEmpty()
    }

    @Test
    fun `confirmLuhnWarning ignores stale ICCID (defensive against double-tap)`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        var confirmResult: InventoryScanResult? = null
        // No pending — confirm should be no-op
        viewModel.confirmLuhnWarning("8952140064116106929F") { confirmResult = it }
        advanceUntilIdle()

        assertThat(confirmResult).isNull()
        assertThat(viewModel.uiState.value.scannedSerialNumbers).isEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layer 3 — Canonicalization
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `canonicalize trims surrounding whitespace before validation`() = runTest {
        coEvery { serializedSaleRepository.scanItem(realIccid) } returns Result.success(
            ScanResult.NotRegistered(realIccid)
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        // Some hardware scanners append CR/LF/whitespace
        viewModel.onBarcodeScanned("  $realIccid\n") { capturedResult = it }
        advanceUntilIdle()

        // Trimmed value passes format + Luhn, gets added
        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Added::class.java)
        assertThat(viewModel.uiState.value.scannedSerialNumbers).contains(realIccid)
    }

    @Test
    fun `canonicalize uppercases F so lowercase f scanner output still matches`() = runTest {
        coEvery { serializedSaleRepository.scanItem(realIccid) } returns Result.success(
            ScanResult.NotRegistered(realIccid)
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        var capturedResult: InventoryScanResult? = null
        // Lowercase 'f' from scanner — should still be canonicalized to uppercase F
        viewModel.onBarcodeScanned("8952140063631660183f") { capturedResult = it }
        advanceUntilIdle()

        assertThat(capturedResult).isInstanceOf(InventoryScanResult.Added::class.java)
        // Stored canonical form should have uppercase F
        assertThat(viewModel.uiState.value.scannedSerialNumbers).contains(realIccid)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Companion object — pure functions
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Luhn passes for the real ICCID from production incident`() {
        // Body without F padding
        val body = "8952140063631660183"
        assertThat(SerializedInventoryViewModel.isLuhnValid(body)).isTrue()
    }

    @Test
    fun `Luhn fails for a single-digit-flip misread`() {
        // Same body but last digit changed 3 → 4
        val tampered = "8952140063631660184"
        assertThat(SerializedInventoryViewModel.isLuhnValid(tampered)).isFalse()
    }

    @Test
    fun `MX_ICCID_REGEX matches all real ALTAN ICCID samples`() {
        // Sample of 5 real ICCIDs from production inventory (Excel)
        val samples = listOf(
            "8952140063677021126F",
            "8952140063677021175F",
            "8952140063812462177F",
            "8952140064116106929F", // this one fails Luhn but passes regex
            "8952140063631660183F", // production incident "real" reading
        )
        for (iccid in samples) {
            assertThat(SerializedInventoryViewModel.MX_ICCID_REGEX.matches(iccid))
                .isTrue()
        }
    }
}
