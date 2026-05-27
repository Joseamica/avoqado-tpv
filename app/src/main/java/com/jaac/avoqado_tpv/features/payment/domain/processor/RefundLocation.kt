package com.jaac.avoqado_tpv.features.payment.domain.processor

/**
 * Where a refund for a given payment must be executed, relative to the
 * TPV the user is currently looking at.
 *
 * Used by `PaymentsViewModel.getRefundLocation(payment)` to render a
 * warning badge on each payment card so the operator knows at-a-glance
 * whether a payment can be refunded from this terminal (silent), or has
 * to be refunded elsewhere (badge with hint).
 *
 * **Display contract**:
 * - [Here] → render NO badge (no noise — happy path)
 * - [OtherProcessor] → render orange "↗ Reembolsa en PAX/Nexgo" badge
 * - [OtherDevice] → render orange "↗ Otro dispositivo" badge
 * - [NotApplicable] → render NO badge (cash, already refunded, failed, etc.)
 *
 * **Decoupled from [com.jaac.avoqado_tpv.features.payments.presentation.PaymentsViewModel.RefundAvailability]**:
 * - `RefundAvailability` answers "can I tap the refund button right now?" (used by bottom sheet)
 * - `RefundLocation` answers "where does this refund happen?" (used by list badge)
 *
 * The two overlap conceptually but serve different UI contexts. Keeping
 * them separate avoids forcing badge logic into the existing refund
 * button gating (and its test coverage).
 */
sealed class RefundLocation {

    /** Refund can be processed on the current TPV. No badge shown. */
    data object Here : RefundLocation()

    /**
     * Refund must be processed on a TPV running a DIFFERENT processor.
     * Example: viewing a Blumon/PAX payment from a Nexgo/AngelPay terminal.
     */
    data class OtherProcessor(val processor: ProcessorType) : RefundLocation()

    /**
     * Refund must be processed on a DIFFERENT physical device, even
     * though the processor matches. Example: this Nexgo terminal sees a
     * payment from another Nexgo terminal in the same venue.
     *
     * @property serial Terminal serial of the device that originally
     *   processed the payment (already lowercased/trimmed).
     */
    data class OtherDevice(val serial: String) : RefundLocation()

    /**
     * Badge is not applicable to this payment at all. Reasons include:
     * cash payments, already fully refunded, failed/pending status, or
     * the row IS a refund (already shown with red "Reembolso" badge).
     */
    data object NotApplicable : RefundLocation()
}
