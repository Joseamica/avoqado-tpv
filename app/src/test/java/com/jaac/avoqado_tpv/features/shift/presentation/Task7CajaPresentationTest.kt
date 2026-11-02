package com.jaac.avoqado_tpv.features.shift.presentation

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.jaac.avoqado_tpv.core.data.local.entity.LocalShiftSessionEntity
import com.jaac.avoqado_tpv.features.shift.domain.LocalShiftSummary
import com.jaac.avoqado_tpv.features.shift.domain.Shift
import org.junit.Test
import java.math.BigDecimal

class Task7CajaPresentationTest {
    private val row = LocalShiftSessionEntity("clocal00000000000000000001", "venue", "LOCAL", openingStatus = "PENDING",
        originalOpenedAt = "2026-10-28T10:00:00Z", startingCash = "50.00", openedByStaffId = "authorA",
        localStatus = "OPEN", createdAt = 1, updatedAt = 1)
    private fun ui(r: LocalShiftSessionEntity = row, start: BigDecimal? = r.startingCash?.toBigDecimal()) =
        CajaLocalPresentation(r, LocalShiftSummary(start, r.countedCash?.toBigDecimal(), r.localStatus, r.openingStatus, r.closeStatus, r.adoptionNotice))

    @Test fun `terminal uses short titles without losing confirmation state`() {
        assertThat(ui().title).isEqualTo("Caja abierta")
        assertThat(ui().notice).contains("Pendiente de confirmar")
        assertThat(ui(row.copy(localStatus = "CLOSED", closeStatus = "PENDING", closeOperationId = "closeA")).title)
            .isEqualTo("Cierre pendiente")
        assertThat(ui(row.copy(localStatus = "CLOSED", closeStatus = "CONFIRMED", closeOperationId = "closeA")).title)
            .isEqualTo("Caja cerrada")
    }

    @Test fun `confirmed opening needs no technical explanation in the first view`() {
        val p = ui(row.copy(openingStatus = "CONFIRMED"))
        assertThat(p.notice).isNull()
        assertThat(p.description).isNotEmpty()
        assertThat(p.knownLines).contains("Fondo capturado: $50.00")
    }

    @Test fun `pending and review notices remain distinct after compacting the screen`() {
        val pending = row.copy(localStatus = "CLOSED", closeOperationId = "closeA", closeStatus = "PENDING")
        assertThat(ui(pending).notice).contains("después de las ventas")
        val review = pending.copy(closeStatus = "REVIEW", lastError = "Otra operación cerró esta caja; tu conteo se conserva sin aplicar.")
        assertThat(ui(review).notice).isEqualTo(review.lastError)
        assertThat(ui(row.copy(openingStatus = "REVIEW")).notice).contains("revisar")
    }

    @Test fun `compact adopted notice never hides the two original funds`() {
        val p = ui(row.copy(openingStatus = "CONFIRMED", openingDisposition = "ADOPTED", remoteStartingCash = "100.00"))
        assertThat(p.notice).contains("dos fondos")
        assertThat(p.knownLines).containsAtLeast("Fondo capturado: $50.00", "Fondo de la caja vinculada: $100.00")
    }

    @Test fun `ignored count stays visible but skipped and legacy never invent a count`() {
        val closed = row.copy(localStatus = "CLOSED", closeOperationId = "closeA", closeStatus = "CONFIRMED",
            closeEvidenceJson = "{\"operation\":{\"countApplied\":false},\"reconciliation\":{\"outcome\":\"IGNORED_DISABLED\"}}")
        val counted = ui(closed.copy(cashReconciliationAction = "COUNTED", countedCash = "72.00"))
        assertThat(counted.notice).contains("no se aplicó")
        assertThat(counted.knownLines).contains("Conteo capturado: $72.00")
        assertThat(ui(closed.copy(cashReconciliationAction = "SKIPPED")).notice).isNull()
        assertThat(ui(closed).notice).isNull()
    }

    @Test fun `unknown starting cash never becomes a zero on screen or paper`() {
        val p = ui(row.copy(startingCash = null, openingKind = "REMOTE", openingStatus = "CONFIRMED"))
        assertThat(p.knownLines.joinToString()).contains("Fondo no disponible")
        assertThat(p.paper()).doesNotContain("$0.00")
        assertThat(p.paper()).doesNotContain("Diferencia: $0")
        assertThat(p.paper()).contains("PROVISIONAL")
    }
    @Test fun `known zero cash is still displayed`() {
        assertThat(ui(row.copy(startingCash = "0.00")).knownLines.joinToString()).contains("$0.00")
    }
    @Test fun `adopted opening preserves captured fifty and applied hundred separately`() {
        val p = ui(row.copy(openingDisposition = "ADOPTED", remoteStartingCash = "100.00", openingStatus = "CONFIRMED"))
        assertThat(p.knownLines.joinToString()).contains("Fondo capturado: $50.00")
        assertThat(p.knownLines.joinToString()).contains("Fondo de la caja vinculada: $100.00")
        assertThat(p.paper()).doesNotContain("$150")
        assertThat(p.canAdopt).isTrue()
    }
    @Test fun `count survives serializer restart without being called applied`() {
        val r = row.copy(localStatus = "CLOSED", closeStatus = "PENDING", closeOperationId = "closeA",
            countedCash = "72.00", cashReconciliationAction = "COUNTED", closedByStaffId = "closerA")
        val restored = Gson().fromJson(Gson().toJson(r), LocalShiftSessionEntity::class.java)
        val p = ui(restored)
        assertThat(p.knownLines.joinToString()).contains("Conteo capturado: $72.00")
        assertThat(p.description).contains("antes del cierre")
        assertThat(p.paper()).doesNotContain("cuadrad")
        assertThat(restored.closedByStaffId).isEqualTo("closerA")
    }
    @Test fun `remote closing has no invented close gesture or count`() {
        val p = ui(row.copy(localStatus = "CLOSED", closeStatus = "REVIEW", lastError = "Cierre observado en el servidor (CLOSING)."))
        assertThat(p.title).contains("otro aparato")
        assertThat(p.knownLines.joinToString()).doesNotContain("Conteo capturado")
        assertThat(p.hasLocalClose).isFalse()
    }
    @Test fun `ignored disabled result does not apply the captured count`() {
        val p = ui(row.copy(localStatus = "CLOSED", closeStatus = "CONFIRMED", closeOperationId = "closeA",
            cashReconciliationAction = "COUNTED", countedCash = "72.00",
            closeEvidenceJson = "{\"operation\":{\"countApplied\":false},\"reconciliation\":{\"outcome\":\"IGNORED_DISABLED\"}}"))
        assertThat(p.description).contains("no se aplicó")
        assertThat(p.knownLines.joinToString()).contains("Conteo capturado: $72.00")
    }
    @Test fun `skipped is not counted zero`() {
        val p = ui(row.copy(localStatus = "CLOSED", closeStatus = "REVIEW", closeOperationId = "closeA", cashReconciliationAction = "SKIPPED"))
        assertThat(p.knownLines.joinToString()).contains("Cierre sin conteo")
        assertThat(p.knownLines.joinToString()).doesNotContain("Conteo capturado: $0")
    }
    @Test fun `conflict preserves original count and never claims it was applied`() {
        val p = ui(row.copy(localStatus = "CLOSED", closeStatus = "REVIEW", closeOperationId = "closeA",
            countedCash = "72.00", cashReconciliationAction = "COUNTED",
            closeEvidenceJson = "{\"operation\":{\"operationId\":\"closeA\",\"kind\":\"CLOSE\",\"state\":\"CONFLICT\",\"submittedCountedCash\":\"72.00\",\"countApplied\":false}}",
            lastError = "Otra operación cerró esta caja; tu conteo se conserva sin aplicar."))
        assertThat(p.description).contains("sin aplicar")
        assertThat(p.paper()).contains("Conteo capturado: $72.00")
        assertThat(p.paper()).doesNotContain("Conteo aplicado")
    }
    @Test fun `legacy gson missing marker retains legacy contract while false stays false`() {
        val json = "{\"id\":\"remoteA\",\"venueId\":\"venue\",\"staffId\":\"staff\",\"staffName\":\"Ana\",\"startTime\":\"2026-10-28T10:00:00Z\",\"status\":\"OPEN\",\"startingCash\":0,\"totalSales\":0,\"totalTips\":0,\"totalOrders\":0,\"totalCashPayments\":0,\"totalCardPayments\":0,\"totalVoucherPayments\":0,\"totalOtherPayments\":0,\"totalProductsSold\":0}"
        val old = Gson().fromJson(json, Shift::class.java)
        assertThat(old.financialTotalsKnown).isNull()
        assertThat(old.hasKnownFinancialTotals()).isTrue()
        // The real text adapter is consumed by ActiveShiftContent and LocalCajaContent.
        assertThat(old.financialDisplayLines()).contains("Ventas" to "$0.00")
        val unknown = Gson().fromJson(Gson().toJson(old.copy(financialTotalsKnown = false)), Shift::class.java)
        assertThat(unknown.hasKnownFinancialTotals()).isFalse()
        assertThat(unknown.financialDisplayLines()).isNull()
    }
}
