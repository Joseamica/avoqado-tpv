package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.angelpay.angelpaysdk.models.PrintAlignmentRequest
import com.angelpay.angelpaysdk.models.PrintFontSizeRequest
import com.angelpay.angelpaysdk.models.PrintStyleRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemRequest
import com.angelpay.angelpaysdk.models.PrintTicketItemType
import com.angelpay.angelpaysdk.models.PrintTicketRequest
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.jaac.avoqado_tpv.features.payment.domain.processor.UnifiedTransaction
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Single source of truth for building [PrintTicketRequest] payloads sent
 * to `AngelPaySDK.printTicket(...)` (manual SDK 1.0.8 §12).
 *
 * Both code paths that need to print a ticket from a Nexgo terminal call
 * into this builder:
 * - [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentViewModel.printReceipt]
 *   — prints the receipt of a payment that just completed, from the
 *   AngelPay success screen. Has rich context (venue, staff, order, tip).
 * - [AngelPaySdkPostOperationsAdapter.printTicket] — prints a ticket
 *   from the SuperAdmin "Reconciliación con el Procesador" lookup, where
 *   the only data available is a [UnifiedTransaction] returned by the
 *   SDK's transaction history.
 *
 * Keeping the layout in one place avoids the classic copy-paste drift
 * where the ticket header reads "AVOQADO TPV" from one entry point and
 * something different from the other after a UX tweak.
 */
@Singleton
class AngelPayTicketBuilder @Inject constructor() {

    /**
     * Build the ticket printed right after a successful AngelPay payment
     * (called from the success screen's print button on Nexgo terminals
     * that have a built-in thermal printer — e.g. N86).
     *
     * @param amount Base amount in pesos (without tip).
     * @param tipAmount Tip portion in pesos. Pass [BigDecimal.ZERO] when
     *   there is no tip — that row is hidden automatically.
     * @param authCode SDK-issued authorization code from the [com.jaac.avoqado_tpv.features.payment.presentation.angelpay.AngelPayPaymentState.Success] state.
     * @param referenceNumber SDK-issued reference for this transaction.
     * @param venueName Display name of the merchant's venue (header).
     * @param staffName Operator who processed the payment (audit row).
     * @param orderNumber Order/fast-payment reference, null for one-off
     *   fast payments without an order.
     */
    fun buildPaymentTicket(
        amount: BigDecimal,
        tipAmount: BigDecimal,
        authCode: String,
        referenceNumber: String?,
        venueName: String?,
        venueLegalName: String? = null,
        venueRfc: String? = null,
        venueAddress: String? = null,
        venueCity: String? = null,
        venueState: String? = null,
        venueZipCode: String? = null,
        venueTimeZone: TimeZone = TimeZone.getTimeZone("America/Mexico_City"),
        staffName: String?,
        orderNumber: String?,
        receiptUrl: String? = null,
        appVersionName: String? = null,
        cardBrand: String? = null,
        maskedPan: String? = null,
        entryMode: String? = null,
    ): PrintTicketRequest {
        val centerBold = PrintStyleRequest(
            alignment = PrintAlignmentRequest.CENTER,
            isBold = true,
            fontSize = PrintFontSizeRequest.LARGE,
        )
        val centerNormalBold = PrintStyleRequest(
            alignment = PrintAlignmentRequest.CENTER,
            isBold = true,
        )
        val centerNormal = PrintStyleRequest(alignment = PrintAlignmentRequest.CENTER)
        val centerSmall = PrintStyleRequest(
            alignment = PrintAlignmentRequest.CENTER,
            fontSize = PrintFontSizeRequest.SMALL,
        )
        val normal = PrintStyleRequest()
        val totalStyle = PrintStyleRequest(isBold = true, fontSize = PrintFontSizeRequest.LARGE)

        val total = amount + tipAmount

        // Fiscal IVA breakdown (Mexico): prices include IVA 16%.
        // Base = Total / 1.16, IVA = Total - Base. Matches PAX receipt.
        val ivaRate = BigDecimal("1.16")
        val baseBeforeIva = total.divide(ivaRate, 2, RoundingMode.HALF_UP)
        val ivaAmount = total - baseBeforeIva

        // Current date/time in venue timezone (matches PAX printReceipt).
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "MX"))
            .apply { timeZone = venueTimeZone }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("es", "MX"))
            .apply { timeZone = venueTimeZone }
        val now = Date()

        val header = buildList {
            // ── Venue identity (header) ────────────────────────────────
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = venueName?.takeIf { it.isNotBlank() } ?: "AVOQADO TPV",
                    style = centerBold,
                )
            )
            if (!venueLegalName.isNullOrBlank() && venueLegalName != venueName) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TEXT,
                        text = venueLegalName,
                        style = centerNormalBold,
                    )
                )
            }
            if (!venueRfc.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TEXT,
                        text = "RFC: $venueRfc",
                        style = centerNormal,
                    )
                )
            }
            val addressLine = buildAddressLine(venueAddress, venueCity, venueState, venueZipCode)
            if (!addressLine.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.PARAGRAPH,
                        text = addressLine,
                        style = centerSmall,
                    )
                )
            }
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))
        }

        val body = buildList {
            // ── Folio + Fecha + Cajero (sale identifiers) ──────────────
            if (!orderNumber.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "FOLIO",
                        right = orderNumber,
                        style = normal,
                    )
                )
            }
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "FECHA",
                    right = "${dateFormat.format(now)} ${timeFormat.format(now)}",
                    style = normal,
                )
            )
            if (!staffName.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "CAJERO",
                        right = staffName,
                        style = normal,
                    )
                )
            }
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))

            // ── Amounts (subtotal / propina / total) ───────────────────
            // Show subtotal explicitly only if tip > 0 (matches PAX).
            if (tipAmount > BigDecimal.ZERO) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Subtotal",
                        right = "${formatMoney(amount)} MXN",
                        style = normal,
                    )
                )
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Propina",
                        right = "${formatMoney(tipAmount)} MXN",
                        style = normal,
                    )
                )
            }
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "TOTAL",
                    right = "${formatMoney(total)} MXN",
                    style = totalStyle,
                )
            )

            // ── IVA breakdown (Mexican fiscal format) ──────────────────
            add(PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 1))
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "Desglose IVA (incluido):",
                    style = normal,
                )
            )
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "  Base",
                    right = "${formatMoney(baseBeforeIva)} MXN",
                    style = normal,
                )
            )
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "  IVA 16%",
                    right = "${formatMoney(ivaAmount)} MXN",
                    style = normal,
                )
            )
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))

            // ── Payment method + auth/reference ────────────────────────
            val paymentMethodText = buildPaymentMethodText(cardBrand, maskedPan, entryMode)
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "Forma de pago: $paymentMethodText",
                    style = normal,
                )
            )
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Autorizacion",
                    right = authCode.ifBlank { "-" },
                    style = normal,
                )
            )
            if (!referenceNumber.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TWO_COLUMNS,
                        left = "Referencia",
                        right = referenceNumber,
                        style = normal,
                    )
                )
            }
        }

        val footer = buildList {
            // ── Fiscal disclaimer (Mexican requirement) ────────────────
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "ESTE NO ES UN COMPROBANTE",
                    style = centerNormalBold,
                )
            )
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "FISCAL",
                    style = centerNormalBold,
                )
            )

            // ── Receipt URL (QR code via IMAGE_BASE64) ─────────────────
            // The SDK 1.0.8 supports IMAGE_BASE64 / IMAGE_URI item types
            // (confirmed by inspecting com.angelpay.angelpaysdk.models.
            // PrintTicketItemType from the AAR — manual §12 only lists
            // text-based items but the enum has more entries).
            //
            // Falls back to plain-text URL if QR generation throws (e.g.
            // ZXing OOM on extremely long URLs) so the customer can still
            // type the link.
            if (!receiptUrl.isNullOrBlank()) {
                add(PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 1))
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TEXT,
                        text = "Escanea para recibo digital",
                        style = centerNormal,
                    )
                )
                val qrItem = buildQrImageItem(receiptUrl)
                if (qrItem != null) {
                    add(qrItem)
                } else {
                    // QR generation failed — surface URL as text so the
                    // customer still has the link.
                    add(
                        PrintTicketItemRequest(
                            type = PrintTicketItemType.PARAGRAPH,
                            text = receiptUrl,
                            style = centerSmall,
                        )
                    )
                }
            }

            // ── Closing ────────────────────────────────────────────────
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))
            add(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "Gracias por su compra",
                    style = centerNormalBold,
                )
            )
            add(PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER))
            if (!appVersionName.isNullOrBlank()) {
                add(
                    PrintTicketItemRequest(
                        type = PrintTicketItemType.TEXT,
                        text = "AVOQADO TPV v$appVersionName",
                        style = centerSmall,
                    )
                )
            }
            add(PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 3))
        }

        return PrintTicketRequest(header = header, body = body, footer = footer)
    }

    /**
     * Compose the "Forma de pago" line. Matches the PAX recipe but tolerates
     * AngelPay's lack of card details — falls back to "TARJETA" when the SDK
     * didn't surface brand/PAN/entry mode (manual §5 PaymentResult does not
     * include those fields in 1.0.8).
     */
    private fun buildPaymentMethodText(
        cardBrand: String?,
        maskedPan: String?,
        entryMode: String?,
    ): String {
        val brand = cardBrand?.trim().orEmpty()
        val pan = maskedPan?.trim().orEmpty()
        val entry = entryMode?.trim().orEmpty()
        return when {
            brand.isNotEmpty() && pan.isNotEmpty() && entry.isNotEmpty() -> "$brand $pan ($entry)"
            brand.isNotEmpty() && pan.isNotEmpty() -> "$brand $pan"
            brand.isNotEmpty() -> brand
            else -> "TARJETA"
        }
    }

    /**
     * Join address fragments into one printable line. Mirrors the helper
     * inside [com.jaac.avoqado_tpv.core.printer.PrinterManager.buildAddressLine].
     */
    private fun buildAddressLine(
        address: String?,
        city: String?,
        state: String?,
        zipCode: String?,
    ): String? {
        val addressLine = address?.trim().orEmpty()
        val cityStateZip = listOfNotNull(
            city?.trim()?.takeIf { it.isNotEmpty() },
            state?.trim()?.takeIf { it.isNotEmpty() },
            zipCode?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" ")
        return when {
            addressLine.isNotEmpty() && cityStateZip.isNotEmpty() -> "$addressLine, $cityStateZip"
            addressLine.isNotEmpty() -> addressLine
            cityStateZip.isNotEmpty() -> cityStateZip
            else -> null
        }
    }

    /**
     * Build the ticket printed from the SuperAdmin "Reconciliación con el
     * Procesador" screen, where the only available data is a
     * [UnifiedTransaction] returned by the SDK's transaction history
     * lookup (no payment context). Lighter than [buildPaymentTicket].
     */
    fun buildHistoryTicket(transaction: UnifiedTransaction): PrintTicketRequest {
        val centerBold = PrintStyleRequest(
            alignment = PrintAlignmentRequest.CENTER,
            isBold = true,
            fontSize = PrintFontSizeRequest.LARGE,
        )
        val normal = PrintStyleRequest()

        return PrintTicketRequest(
            header = listOf(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TEXT,
                    text = "AVOQADO TPV",
                    style = centerBold,
                ),
                PrintTicketItemRequest(type = PrintTicketItemType.DIVIDER),
            ),
            body = listOf(
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Referencia",
                    right = transaction.reference,
                    style = normal,
                ),
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Monto",
                    right = formatMoney(transaction.amount),
                ),
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Autorización",
                    right = transaction.authorizationCode.ifBlank { "-" },
                ),
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Estatus",
                    right = transaction.status.ifBlank { "-" },
                ),
                PrintTicketItemRequest(
                    type = PrintTicketItemType.TWO_COLUMNS,
                    left = "Tarjeta",
                    right = listOfNotNull(transaction.cardType, transaction.last4?.let { "****$it" })
                        .joinToString(" ")
                        .ifBlank { "-" },
                ),
                PrintTicketItemRequest(type = PrintTicketItemType.SPACER, lines = 3),
            ),
            footer = emptyList(),
        )
    }

    private fun formatMoney(value: BigDecimal): String =
        "$" + String.format(Locale.US, "%.2f", value)

    /** Overload for [UnifiedTransaction.amount] which uses `Double`. */
    private fun formatMoney(value: Double): String =
        "$" + String.format(Locale.US, "%.2f", value)

    /**
     * Build an [PrintTicketItemRequest] with the receipt URL encoded as a
     * QR code (PNG → base64). Returns null if either the bitmap or its
     * base64 encoding fails so the caller can fall back to plain text.
     *
     * Reuses the same QR pipeline that [com.jaac.avoqado_tpv.core.printer.PrinterManager.generateQrBitmap]
     * uses for PAX (ZXing, RGB_565, ErrorCorrectionLevel.M, margin 1) so
     * both terminal families render visually identical QRs.
     */
    private fun buildQrImageItem(content: String): PrintTicketItemRequest? {
        val bitmap = generateQrBitmap(content) ?: return null
        return try {
            val base64 = bitmap.toPngBase64() ?: return null
            PrintTicketItemRequest(
                type = PrintTicketItemType.IMAGE_BASE64,
                imageBase64 = base64,
                // 200px matches PrinterManager's centerBitmap target for PAX
                // (PAPER_WIDTH on most thermal printers is 384px → 200 is a
                // safe centered footprint without scaling artifacts).
                targetWidthPx = 200,
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun generateQrBitmap(content: String, size: Int = 200): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Timber.w(e, "🔶 [AngelPayTicketBuilder] QR bitmap generation failed for content len=%d", content.length)
            null
        }
    }

    private fun Bitmap.toPngBase64(): String? = try {
        ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Timber.w(e, "🔶 [AngelPayTicketBuilder] PNG/base64 encoding failed")
        null
    }
}
