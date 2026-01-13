package com.jaac.avoqado_tpv.core.printer

import android.content.Context
import android.graphics.Bitmap
import com.jaac.avoqado_tpv.R
import com.pax.dal.IDAL
import com.pax.dal.IPrinter
import com.pax.neptunelite.api.NeptuneLiteUser
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for PAX thermal printer access via Neptune SDK.
 *
 * **Why bypass Blumon SDK:**
 * - Blumon SDK's `getPrinter()` returns `null` (not implemented in version 1.11.0.2)
 * - Need direct access to PAX Neptune API for receipt printing
 *
 * **Implementation:**
 * - Uses `NeptuneLiteUser.getInstance().getDal(context)` to get IDAL
 * - Accesses `IPrinter` directly from IDAL
 * - Formats receipt text for thermal printer (ESC/POS compatible)
 *
 * **Injection:**
 * ```kotlin
 * @HiltViewModel
 * class PaymentViewModel @Inject constructor(
 *     private val printerManager: PrinterManager
 * )
 * ```
 *
 * **Usage:**
 * ```kotlin
 * printerManager.printReceipt(
 *     receiptUrl = "https://api.avoqado.io/receipt/abc123",
 *     amount = "500.00",
 *     authCode = "123456"
 * )
 * ```
 */
@Singleton
class PrinterManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dal: IDAL? by lazy {
        try {
            val instance = NeptuneLiteUser.getInstance().getDal(context)
            Timber.d("✅ IDAL instance obtained successfully")
            instance
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get IDAL from Neptune SDK")
            null
        }
    }

    private val printer: IPrinter? by lazy {
        try {
            dal?.getPrinter()?.also {
                it.init()
                Timber.d("✅ Printer initialized successfully")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to initialize printer")
            null
        }
    }

    /**
     * Print professional receipt (Toast/Square/Clip style adapted for Mexico).
     *
     * **Receipt Layout:**
     * ```
     *      [AVOQADO LOGO]
     *    RFC: ABC123456789
     *   Dirección del venue
     *
     * Fecha: 10/11/2025  13:03:27
     *
     * Orden #ORD-123        (only if order payment)
     *
     * 2x Pizza Margherita    $360.00
     * 1x Coca-Cola            $35.00
     * 3x Alitas Buffalo      $270.00
     *    Sin salsa picante
     * --------------------------------
     *
     * Mastercard ****7182
     * Tarjeta Contactless (NFC)
     * --------------------------------
     *
     * Monto:         $500.00 MXN
     * Propina:        $50.00 MXN
     * ================================
     * TOTAL:         $550.00 MXN
     * ================================
     *
     * Autorizacion:  ABC123
     * Referencia:    757355196496
     *
     *     [QR CODE BITMAP]
     *
     * Escanea para ver recibo digital
     *
     * ================================
     *   Gracias por su compra
     * ================================
     * ```
     *
     * @param receiptUrl URL of digital receipt (for QR code) - NULL if backend registration failed
     * @param amount Payment amount (formatted, e.g. "500.00")
     * @param authCode Authorization code from payment processor
     * @param tipAmount Optional tip amount (formatted)
     * @param cardDetails Optional card information (brand, masked PAN, entry mode)
     * @param referenceNumber Optional reference number
     * @param venueRfc Optional venue RFC for fiscal compliance
     * @param venueAddress Optional venue address
     * @param orderNumber Optional order number (only for order payments)
     * @param orderItems Optional order items list (only for Pedido Rápido or Servicio de Mesa)
     * @return Result.success if printed, Result.failure if printer unavailable/error
     */
    fun printReceipt(
        receiptUrl: String?,  // ✅ FIX: Nullable for generic receipts when backend fails
        amount: String,
        authCode: String,
        tipAmount: String? = null,
        cardDetails: com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails? = null,
        referenceNumber: String? = null,
        venueRfc: String? = null,
        venueAddress: String? = null,
        orderNumber: String? = null,  // 🆕 Order number (for display)
        orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>? = null,  // 🆕 Order items (for itemized receipt)
        discountAmount: String? = null,  // 🆕 Discount applied to order
        isRefund: Boolean = false  // 💸 Refund mode - changes header and labels
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Starting professional receipt print")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER - Avoqado Logo (professional branding - black version for thermal printer)
            // ========================================
            try {
                val originalLogo = android.graphics.BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.logo_avoqado_black  // Black logo optimized for thermal printer
                )
                if (originalLogo != null) {
                    // Scale logo to reasonable width (220px for visibility)
                    val targetWidth = 220
                    val aspectRatio = originalLogo.height.toFloat() / originalLogo.width.toFloat()
                    val targetHeight = (targetWidth * aspectRatio).toInt()

                    val scaledLogo = Bitmap.createScaledBitmap(originalLogo, targetWidth, targetHeight, true)

                    // Convert RGBA to RGB with white background (for thermal printer)
                    // Step 1: Create white background bitmap in RGB_565 (thermal printer format)
                    val logoWithWhiteBg = Bitmap.createBitmap(scaledLogo.width, scaledLogo.height, Bitmap.Config.RGB_565)
                    val canvas = android.graphics.Canvas(logoWithWhiteBg)

                    // Step 2: Fill with white background
                    canvas.drawColor(android.graphics.Color.WHITE)

                    // Step 3: Draw logo on top with proper alpha blending
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        isFilterBitmap = true
                    }
                    canvas.drawBitmap(scaledLogo, 0f, 0f, paint)

                    // Center the logo horizontally on thermal paper (384px width)
                    val centeredLogo = centerBitmap(logoWithWhiteBg, targetWidth = 384)

                    printerInstance.printBitmap(centeredLogo)
                    printerInstance.printStr("\n", null)
                    Timber.d("✅ [Printer] Centered black logo with white background printed (${centeredLogo.width}x${centeredLogo.height})")
                } else {
                    Timber.w("⚠️ [Printer] Logo resource is null, using text fallback")
                    printerInstance.printStr("          AVOQADO\n", null)
                }
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [Printer] Could not print logo, using text fallback")
                printerInstance.printStr("          AVOQADO\n", null)
            }

            // 💸 Different header for refunds vs sales
            val receiptTitle = if (isRefund) "  Comprobante de Reembolso" else "    Comprobante de Venta"
            printerInstance.printStr("$receiptTitle\n\n", null)

            // RFC and Address (if available) - small text
            if (venueRfc != null) {
                printerInstance.printStr("RFC: $venueRfc\n", null)
            }
            if (venueAddress != null) {
                printerInstance.printStr("$venueAddress\n", null)
            }
            if (venueRfc != null || venueAddress != null) {
                printerInstance.printStr("\n", null)
            }

            printerInstance.printStr("================================\n\n", null)

            // Date & Time
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale("es", "MX"))
            val currentDateTime = dateFormat.format(java.util.Date())
            printerInstance.printStr("Fecha: $currentDateTime\n\n", null)

            // ========================================
            // 📦 ORDER ITEMS (only for order payments - Pedido Rápido or Servicio de Mesa)
            // ========================================
            if (!orderItems.isNullOrEmpty()) {
                // Order number header
                if (!orderNumber.isNullOrBlank()) {
                    printerInstance.printStr("Orden #$orderNumber\n\n", null)
                }

                // Print each item
                orderItems.forEach { item ->
                    // Product line: "2x Pizza Margherita    $360.00"
                    val itemLine = "${item.quantity}x ${item.productName}"
                    val itemPrice = item.formattedTotalPrice

                    // Pad to align prices on the right (32 chars total width for thermal printer)
                    val paddedLine = itemLine.padEnd(20, ' ') + itemPrice.padStart(12, ' ')
                    printerInstance.printStr("$paddedLine\n", null)

                    // Modifiers (if any) - indented with price
                    if (item.modifiers.isNotEmpty()) {
                        item.modifiers.forEach { modifier ->
                            val modLine = "   • ${modifier.name}"
                            val modPrice = modifier.formattedPrice
                            val paddedModLine = modLine.padEnd(20, ' ') + modPrice.padStart(12, ' ')
                            printerInstance.printStr("$paddedModLine\n", null)
                        }
                    }

                    // Notes (if any) - indented with smaller text
                    if (!item.notes.isNullOrBlank()) {
                        printerInstance.printStr("   ${item.notes}\n", null)
                    }
                }

                printerInstance.printStr("\n--------------------------------\n", null)
            }

            // ========================================
            // CARD INFORMATION (if available)
            // ========================================
            if (cardDetails != null) {
                printerInstance.printStr("--------------------------------\n", null)
                printerInstance.printStr("${cardDetails.cardBrand.displayName} ${cardDetails.maskedPan}\n", null)
                printerInstance.printStr("Tarjeta ${cardDetails.entryMode.displayName}\n", null)
                printerInstance.printStr("--------------------------------\n\n", null)
            }

            // ========================================
            // TRANSACTION DETAILS
            // ========================================
            val amountValue = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            // 💸 Skip tip calculation for refunds
            val tipValue = if (isRefund) java.math.BigDecimal.ZERO else (tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            val totalValue = amountValue + tipValue

            // 💸 Different label for refunds
            val amountLabel = if (isRefund) "Reembolso:     " else "Monto:         "
            printerInstance.printStr("$amountLabel\$${amount} MXN\n", null)

            // 🆕 Descuento (si aplica) - not applicable for refunds
            if (!isRefund && !discountAmount.isNullOrEmpty() && discountAmount != "0" && discountAmount != "0.00") {
                printerInstance.printStr("Descuento:     -\$${discountAmount} MXN\n", null)
            }

            // 💸 Skip tip for refunds
            if (!isRefund && tipValue > java.math.BigDecimal.ZERO) {
                printerInstance.printStr("Propina:        \$${tipAmount} MXN\n", null)
            }

            printerInstance.printStr("================================\n", null)
            // 💸 Different total label for refunds
            val totalLabel = if (isRefund) "TOTAL REEMBOLSO:" else "TOTAL:         "
            printerInstance.printStr("$totalLabel \$${totalValue} MXN\n", null)
            printerInstance.printStr("================================\n\n", null)

            // ========================================
            // AUTHORIZATION & REFERENCE
            // ========================================
            printerInstance.printStr("Autorizacion:  $authCode\n", null)
            if (referenceNumber != null) {
                printerInstance.printStr("Referencia:    $referenceNumber\n\n", null)
            } else {
                printerInstance.printStr("\n", null)
            }

            // ========================================
            // QR CODE BITMAP (Centered - Clip/MercadoPago style)
            // Only print QR code if receiptUrl exists (backend registration succeeded)
            // ========================================
            if (receiptUrl != null) {
                try {
                    val qrBitmap = generateQrBitmap(receiptUrl, size = 200)
                    if (qrBitmap != null) {
                        Timber.d("✅ [Printer] QR bitmap generated (${qrBitmap.width}x${qrBitmap.height})")

                        // Center the QR code by adding left padding
                        // PAX thermal printer width is typically 384px, QR is 200px
                        // Left margin = (384 - 200) / 2 = 92px ≈ centered
                        val centeredQr = centerBitmap(qrBitmap, targetWidth = 384)
                        printerInstance.printBitmap(centeredQr)
                        printerInstance.printStr("\n", null)
                        Timber.d("✅ [Printer] Centered QR bitmap printed")
                    } else {
                        Timber.w("⚠️ [Printer] QR bitmap generation returned null")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [Printer] Could not generate/print QR bitmap")
                }

                printerInstance.printStr(" Escanea para ver recibo digital\n\n", null)
            } else {
                // Backend registration failed - print generic message instead of QR
                Timber.i("📄 [Printer] No receipt URL - printing generic receipt")
                printerInstance.printStr("\n Recibo genérico\n", null)
                printerInstance.printStr(" Pendiente de registro en sistema\n\n", null)
            }

            // ========================================
            // FOOTER (Professional thank you)
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("   Gracias por su compra\n", null)
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("\n\n\n", null) // Feed paper

            // Start printing
            val printResult = printerInstance.start()

            if (printResult == 0) {
                Timber.i("✅ [Printer] Professional receipt printed successfully")
                Result.success(Unit)
            } else {
                Timber.w("⚠️ [Printer] Print returned code: $printResult")
                Result.failure(Exception("Error al imprimir (código: $printResult)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print receipt")
            Result.failure(
                Exception("Error al imprimir: ${e.message ?: "Error desconocido"}")
            )
        }
    }

    /**
     * Generate QR code bitmap from URL using ZXing library.
     *
     * @param content URL or text to encode
     * @param size QR code size in pixels (default: 200)
     * @return Bitmap of QR code, or null if generation failed
     */
    private fun generateQrBitmap(content: String, size: Int = 200): Bitmap? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1, // Minimal margin
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )

            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix.get(x, y)) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to generate QR bitmap")
            null
        }
    }

    /**
     * Center a bitmap horizontally by adding white padding on both sides.
     *
     * @param source Original bitmap to center
     * @param targetWidth Target width for the centered bitmap (default: 384px for PAX printer)
     * @return New bitmap with centered content and white background
     */
    private fun centerBitmap(source: Bitmap, targetWidth: Int = 384): Bitmap {
        return try {
            // If source is already wider than target, return as-is
            if (source.width >= targetWidth) {
                return source
            }

            // Calculate left margin to center the bitmap
            val leftMargin = (targetWidth - source.width) / 2

            // Create new bitmap with target width
            val centeredBitmap = Bitmap.createBitmap(targetWidth, source.height, Bitmap.Config.RGB_565)

            // Fill with white background
            val canvas = android.graphics.Canvas(centeredBitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            // Draw source bitmap centered
            canvas.drawBitmap(source, leftMargin.toFloat(), 0f, null)

            centeredBitmap
        } catch (e: Exception) {
            Timber.w(e, "⚠️ [Printer] Could not center bitmap, returning original")
            source
        }
    }

    /**
     * Print sales report receipt (Toast/Square POS style).
     *
     * Prints a compact, professional summary of sales data optimized for thermal printers.
     * Follows Toast POS receipt formatting standards.
     *
     * **Receipt Layout:**
     * ```
     * ================================
     *     REPORTE DE VENTAS
     * ================================
     * Venue Name
     * 12 Nov - 19 Nov 2024
     * (Últimos 7 días)
     *
     * Impreso: 19 Nov 2024, 14:30
     * --------------------------------
     *
     * RESUMEN DE VENTAS
     * Total Ventas:      $8,228.28
     * Total Órdenes:           145
     * ...
     * ================================
     * ```
     *
     * @param periodLabel Human-readable period label (e.g., "Últimos 7 días")
     * @param dateRange Date range string (e.g., "12 Nov - 19 Nov 2024")
     * @param totalSales Total sales amount formatted
     * @param totalOrders Total number of orders
     * @param totalProducts Total products sold
     * @param avgOrderValue Average order value formatted
     * @param avgProductsPerOrder Average products per order
     * @param cashAmount Cash payment amount
     * @param cardAmount Card payment amount
     * @param voucherAmount Voucher payment amount
     * @param cashPercentage Cash percentage
     * @param cardPercentage Card percentage
     * @param voucherPercentage Voucher percentage
     * @param comparisonText Optional comparison text (e.g., "Ventas: +12.5% ↑")
     * @param venueName Optional venue name for header
     * @return Result.success if printed, Result.failure if printer unavailable/error
     */
    fun printReport(
        periodLabel: String,
        dateRange: String,
        totalSales: String,
        totalOrders: Int,
        totalProducts: Int,
        avgOrderValue: String,
        avgProductsPerOrder: String,
        cashAmount: String,
        cardAmount: String,
        voucherAmount: String,
        cashPercentage: String,
        cardPercentage: String,
        voucherPercentage: String,
        comparisonText: String? = null,
        venueName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Starting sales report print")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("    REPORTE DE VENTAS\n", null)
            printerInstance.printStr("================================\n", null)

            if (venueName != null) {
                printerInstance.printStr("$venueName\n", null)
            }

            printerInstance.printStr("$dateRange\n", null)
            printerInstance.printStr("($periodLabel)\n\n", null)

            // Print timestamp
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("es", "ES"))
            val now = dateFormat.format(java.util.Date())
            printerInstance.printStr("Impreso: $now\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // SALES SUMMARY
            // ========================================
            printerInstance.printStr("RESUMEN DE VENTAS\n", null)
            printerInstance.printStr(String.format("%-18s %12s\n", "Total Ventas:", "$$totalSales"), null)
            printerInstance.printStr(String.format("%-18s %12d\n", "Total Ordenes:", totalOrders), null)
            printerInstance.printStr(String.format("%-18s %12d\n", "Total Productos:", totalProducts), null)
            printerInstance.printStr(String.format("%-18s %12s\n", "Ticket Promedio:", "$$avgOrderValue"), null)
            printerInstance.printStr(String.format("%-18s %12s\n\n", "Productos/Orden:", avgProductsPerOrder), null)

            // ========================================
            // PAYMENT METHODS
            // ========================================
            printerInstance.printStr("--------------------------------\n", null)
            printerInstance.printStr("METODOS DE PAGO\n\n", null)

            if (cashAmount != "0.00") {
                printerInstance.printStr(String.format("%-20s %9s %3s%%\n", "Efectivo:", "$$cashAmount", cashPercentage), null)
            }
            if (cardAmount != "0.00") {
                printerInstance.printStr(String.format("%-20s %9s %3s%%\n", "Tarjeta:", "$$cardAmount", cardPercentage), null)
            }
            if (voucherAmount != "0.00") {
                printerInstance.printStr(String.format("%-20s %9s %3s%%\n", "Voucher:", "$$voucherAmount", voucherPercentage), null)
            }

            printerInstance.printStr(String.format("%20s -----------\n", ""), null)
            printerInstance.printStr(String.format("%-20s %9s\n\n", "Total:", "$$totalSales"), null)

            // ========================================
            // COMPARISON (if enabled)
            // ========================================
            if (comparisonText != null && comparisonText.isNotBlank()) {
                printerInstance.printStr("--------------------------------\n", null)
                printerInstance.printStr("COMPARACION\n", null)
                printerInstance.printStr("(vs periodo anterior)\n\n", null)
                printerInstance.printStr("$comparisonText\n\n", null)
            }

            // ========================================
            // FOOTER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("Generado por Avoqado TPV\n", null)
            printerInstance.printStr("================================\n\n\n", null)

            // Send to printer
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Sales report printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir reporte (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print report")
            Result.failure(e)
        }
    }

    /**
     * Print a test receipt to verify printer functionality.
     *
     * @return Result.success if test print succeeded, Result.failure otherwise
     */
    fun printTest(): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible")
            )

            printerInstance.init()
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("   IMPRESION DE PRUEBA\n", null)
            printerInstance.printStr("================================\n\n", null)
            printerInstance.printStr("Impresora PAX funcionando\n", null)
            printerInstance.printStr("correctamente.\n\n", null)
            printerInstance.printStr("Fecha: ${System.currentTimeMillis()}\n\n\n", null)

            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Test print successful")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Test print failed with code: $result"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Test print failed")
            Result.failure(e)
        }
    }

    /**
     * Print a single historical period report
     *
     * Prints metrics for one historical period (day/week/month/quarter/year).
     * Used when printing periods individually (INDIVIDUAL mode).
     *
     * **Receipt format:**
     * - Header with venue name and period label
     * - Sales metrics (total sales, orders, products, averages)
     * - Period-over-period comparison (if enabled)
     * - Timestamp
     *
     * @param period Historical period to print
     * @param includeComparison Whether to print comparison metrics
     * @param venueId Venue ID for audit trail
     * @param venueName Optional venue name for header
     * @return Result.success if printed, Result.failure if error
     */
    fun printHistoricalPeriod(
        period: com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod,
        includeComparison: Boolean = true,
        venueId: String,
        venueName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Printing historical period: ${period.label}")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("  REPORTE HISTORICO\n", null)
            printerInstance.printStr("================================\n", null)

            if (venueName != null) {
                printerInstance.printStr("$venueName\n", null)
            }

            printerInstance.printStr("\n${period.label}\n", null)
            printerInstance.printStr("${period.subtitle}\n\n", null)

            // Print timestamp
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("es", "ES"))
            val now = dateFormat.format(java.util.Date())
            printerInstance.printStr("Impreso: $now\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // METRICS
            // ========================================
            printerInstance.printStr("METRICAS\n", null)
            printerInstance.printStr("--------------------------------\n", null)

            // Total sales
            printerInstance.printStr("Ventas Totales:\n", null)
            printerInstance.printStr("  $${period.formatTotalSales()}\n", null)

            // Comparison for sales
            if (includeComparison && period.salesChange != null) {
                val salesChange = period.formatSalesChange()
                printerInstance.printStr("  $salesChange\n", null)
            }
            printerInstance.printStr("\n", null)

            // Total orders
            printerInstance.printStr("Ordenes:    ${period.totalOrders}\n", null)
            if (includeComparison && period.ordersChange != null) {
                val ordersChange = period.formatOrdersChange()
                printerInstance.printStr("  $ordersChange\n", null)
            }
            printerInstance.printStr("\n", null)

            // Total products
            printerInstance.printStr("Productos:  ${period.totalProducts}\n", null)
            printerInstance.printStr("\n", null)

            // Average order value
            printerInstance.printStr("Ticket Promedio:\n", null)
            printerInstance.printStr("  $${period.formatAverageOrderValue()}\n\n", null)

            printerInstance.printStr("--------------------------------\n\n\n", null)

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Historical period printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir período (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print historical period")
            Result.failure(e)
        }
    }

    /**
     * Print multiple historical periods in batch
     *
     * Prints summary table of multiple periods in one receipt.
     * Used when printing periods in batch (BATCH mode).
     *
     * **Receipt format:**
     * - Header with venue name and date range
     * - Summary table (all periods in chronological order)
     * - Totals and averages across all periods
     * - Timestamp
     *
     * **Performance safeguards:**
     * - Max 20 periods per batch (memory safety)
     * - Truncates long labels to fit receipt width
     *
     * @param periods List of historical periods to print
     * @param includeComparisons Whether to print comparison metrics
     * @param venueId Venue ID for audit trail
     * @param venueName Optional venue name for header
     * @return Result.success if printed, Result.failure if error
     */
    fun printHistoricalReport(
        periods: List<com.jaac.avoqado_tpv.features.reports.domain.models.HistoricalPeriod>,
        includeComparisons: Boolean = true,
        venueId: String,
        venueName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            if (periods.isEmpty()) {
                return Result.failure(Exception("No hay períodos para imprimir"))
            }

            Timber.i("🖨️ [Printer] Printing ${periods.size} historical periods in batch")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("  REPORTE HISTORICO\n", null)
            printerInstance.printStr("================================\n", null)

            if (venueName != null) {
                printerInstance.printStr("$venueName\n", null)
            }

            // Date range (first to last period)
            val firstPeriod = periods.first()
            val lastPeriod = periods.last()
            printerInstance.printStr("\n${firstPeriod.label} - ${lastPeriod.label}\n", null)
            printerInstance.printStr("${periods.size} períodos\n\n", null)

            // Print timestamp
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("es", "ES"))
            val now = dateFormat.format(java.util.Date())
            printerInstance.printStr("Impreso: $now\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // PERIODS TABLE
            // ========================================
            printerInstance.printStr("PERIODOS\n", null)
            printerInstance.printStr("--------------------------------\n", null)

            periods.forEach { period ->
                // Truncate label if too long (receipt is 32 chars wide)
                val label = if (period.label.length > 18) {
                    period.label.take(15) + "..."
                } else {
                    period.label.padEnd(18)
                }

                // Format sales
                val sales = period.formatTotalSales().padStart(10)

                printerInstance.printStr("$label $sales\n", null)

                // Print comparison if enabled
                if (includeComparisons && period.salesChange != null) {
                    val change = period.formatSalesChange().padStart(28)
                    printerInstance.printStr("$change\n", null)
                }
            }

            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // TOTALS
            // ========================================
            printerInstance.printStr("TOTALES\n", null)
            printerInstance.printStr("--------------------------------\n", null)

            // Calculate totals across all periods
            val totalSales = periods.sumOf { it.totalSales }
            val totalOrders = periods.sumOf { it.totalOrders }
            val totalProducts = periods.sumOf { it.totalProducts }
            val avgOrderValue = if (totalOrders > 0) {
                totalSales.divide(
                    java.math.BigDecimal(totalOrders),
                    2,
                    java.math.RoundingMode.HALF_UP
                )
            } else {
                java.math.BigDecimal.ZERO
            }

            printerInstance.printStr("Ventas:    $$totalSales\n", null)
            printerInstance.printStr("Ordenes:   $totalOrders\n", null)
            printerInstance.printStr("Productos: $totalProducts\n", null)
            printerInstance.printStr("Ticket Promedio: $$avgOrderValue\n", null)

            printerInstance.printStr("\n--------------------------------\n\n\n", null)

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Historical report printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir reporte histórico (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print historical report")
            Result.failure(e)
        }
    }

    /**
     * Print a single payment from payment history
     *
     * Prints a compact receipt for one payment.
     * Used when printing payments individually (INDIVIDUAL mode).
     *
     * **Receipt format:**
     * - Header: "COMPROBANTE DE PAGO"
     * - Date and time
     * - Order number and table (if available)
     * - Payment method
     * - Amount breakdown (base + tip = total)
     * - Staff member
     * - Timestamp
     *
     * @param payment Payment to print
     * @param venueName Optional venue name for header
     * @return Result.success if printed, Result.failure if error
     */
    fun printPaymentHistoryReceipt(
        payment: com.jaac.avoqado_tpv.features.payments.domain.models.Payment,
        venueName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Printing payment history receipt: ${payment.id}")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("    COMPROBANTE DE PAGO\n", null)
            printerInstance.printStr("================================\n", null)

            if (venueName != null) {
                printerInstance.printStr("$venueName\n", null)
            }

            // Date & Time from payment
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale("es", "MX"))
            val paymentDate = java.util.Date.from(payment.createdAt)
            printerInstance.printStr("\nFecha: ${dateFormat.format(paymentDate)}\n\n", null)

            // ========================================
            // ORDER INFO
            // ========================================
            if (!payment.orderNumber.isNullOrBlank()) {
                printerInstance.printStr("Orden: #${payment.orderNumber}\n", null)
            }
            if (!payment.tableName.isNullOrBlank()) {
                printerInstance.printStr("Mesa: ${payment.tableName}\n", null)
            }
            printerInstance.printStr("\n", null)

            // ========================================
            // PAYMENT METHOD
            // ========================================
            printerInstance.printStr("Metodo: ${payment.getMethodLabel()}\n", null)
            printerInstance.printStr("--------------------------------\n", null)

            // ========================================
            // AMOUNTS
            // ========================================
            printerInstance.printStr(String.format("%-18s %12s\n", "Monto:", "$${payment.formatAmount()} MXN"), null)

            if (payment.tipAmount > java.math.BigDecimal.ZERO) {
                printerInstance.printStr(String.format("%-18s %12s\n", "Propina:", "$${payment.formatTipAmount()} MXN"), null)
            }

            printerInstance.printStr("================================\n", null)
            printerInstance.printStr(String.format("%-18s %12s\n", "TOTAL:", "$${payment.formatTotalAmount()} MXN"), null)
            printerInstance.printStr("================================\n\n", null)

            // ========================================
            // STAFF
            // ========================================
            if (payment.processedBy != null) {
                printerInstance.printStr("Procesado por: ${payment.processedBy.getFullName()}\n", null)
            }

            printerInstance.printStr("\n================================\n\n\n", null)

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Payment history receipt printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir comprobante (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print payment history receipt")
            Result.failure(e)
        }
    }

    /**
     * Print summary of multiple payments
     *
     * Prints a consolidated summary of multiple payments in one receipt.
     * Used when printing payments in batch (SUMMARY mode).
     *
     * **Receipt format:**
     * - Header: "RESUMEN DE PAGOS"
     * - Date range and count
     * - Payments table (date, method, amount)
     * - Totals by payment method
     * - Grand total
     * - Timestamp
     *
     * **Performance safeguards:**
     * - Max 20 payments per batch (memory safety)
     *
     * @param payments List of payments to print
     * @param dateRangeLabel Date range label (e.g., "Últimos 7 días")
     * @param venueName Optional venue name for header
     * @return Result.success if printed, Result.failure if error
     */
    fun printPaymentsSummary(
        payments: List<com.jaac.avoqado_tpv.features.payments.domain.models.Payment>,
        dateRangeLabel: String,
        venueName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            if (payments.isEmpty()) {
                return Result.failure(Exception("No hay pagos para imprimir"))
            }

            Timber.i("🖨️ [Printer] Printing ${payments.size} payments summary")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("   RESUMEN DE PAGOS\n", null)
            printerInstance.printStr("================================\n", null)

            if (venueName != null) {
                printerInstance.printStr("$venueName\n", null)
            }

            printerInstance.printStr("\nPeríodo: $dateRangeLabel\n", null)
            printerInstance.printStr("Pagos seleccionados: ${payments.size}\n\n", null)

            // Print timestamp
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("es", "ES"))
            val now = dateFormat.format(java.util.Date())
            printerInstance.printStr("Impreso: $now\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // PAYMENTS TABLE
            // ========================================
            val paymentDateFormat = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale("es", "ES"))

            payments.sortedByDescending { it.createdAt }.forEach { payment ->
                val date = paymentDateFormat.format(java.util.Date.from(payment.createdAt))
                val method = when (payment.method) {
                    com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.CASH -> "Efect"
                    com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.CARD -> "Tarjt"
                    com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.VOUCHER -> "Vouch"
                    com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.OTHER -> "Otro"
                }
                val amount = payment.formatTotalAmount()

                // Format: "15 Ene 14:30  Tarjt  $150.50"
                printerInstance.printStr("$date  $method  $amount\n", null)
            }

            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // TOTALS BY METHOD
            // ========================================
            printerInstance.printStr("TOTALES:\n", null)

            // Calculate totals by method
            val cashPayments = payments.filter { it.method == com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.CASH }
            val cardPayments = payments.filter { it.method == com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.CARD }
            val voucherPayments = payments.filter { it.method == com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.VOUCHER }
            val otherPayments = payments.filter { it.method == com.jaac.avoqado_tpv.features.payments.domain.models.PaymentMethod.OTHER }

            val cashTotal = cashPayments.sumOf { it.totalAmount }
            val cardTotal = cardPayments.sumOf { it.totalAmount }
            val voucherTotal = voucherPayments.sumOf { it.totalAmount }
            val otherTotal = otherPayments.sumOf { it.totalAmount }

            if (cashPayments.isNotEmpty()) {
                printerInstance.printStr(String.format("%-12s $%8s  (%d)\n", "Efectivo:", cashTotal.setScale(2, java.math.RoundingMode.HALF_UP), cashPayments.size), null)
            }
            if (cardPayments.isNotEmpty()) {
                printerInstance.printStr(String.format("%-12s $%8s  (%d)\n", "Tarjeta:", cardTotal.setScale(2, java.math.RoundingMode.HALF_UP), cardPayments.size), null)
            }
            if (voucherPayments.isNotEmpty()) {
                printerInstance.printStr(String.format("%-12s $%8s  (%d)\n", "Voucher:", voucherTotal.setScale(2, java.math.RoundingMode.HALF_UP), voucherPayments.size), null)
            }
            if (otherPayments.isNotEmpty()) {
                printerInstance.printStr(String.format("%-12s $%8s  (%d)\n", "Otro:", otherTotal.setScale(2, java.math.RoundingMode.HALF_UP), otherPayments.size), null)
            }

            printerInstance.printStr("--------------------------------\n", null)

            // Grand total
            val grandTotal = payments.sumOf { it.totalAmount }
            printerInstance.printStr(String.format("%-12s $%8s  (%d pagos)\n\n", "TOTAL:", grandTotal.setScale(2, java.math.RoundingMode.HALF_UP), payments.size), null)

            // ========================================
            // FOOTER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("Generado por Avoqado TPV\n", null)
            printerInstance.printStr("================================\n\n\n", null)

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Payments summary printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir resumen de pagos (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print payments summary")
            Result.failure(e)
        }
    }

    /**
     * Print kitchen ticket (comanda) for food preparation.
     *
     * **Purpose:** Informative ticket for kitchen staff - NO prices, NO payment info.
     * Focus on products, modifiers, and special instructions.
     *
     * **Ticket Layout:**
     * ```
     * ================================
     *       COMANDA DE COCINA
     * ================================
     * Orden #ORD-123
     * Mesa: Mesa 5
     *
     * Fecha: 15/01/2025  14:30:22
     * --------------------------------
     *
     * 3x Pizza Margherita
     *    • Extra queso
     *    • Sin cebolla
     *    > Bien cocida
     *
     * 2x Hamburguesa Clásica
     *    • Papas grandes
     *    > Sin pepinillos
     *
     * --------------------------------
     * Enviada por: Juan Pérez
     * ================================
     * ```
     *
     * @param orderNumber Order number for identification
     * @param tableName Optional table name (e.g., "Mesa 5")
     * @param orderItems List of order items with modifiers and notes
     * @param staffName Optional staff name who sent the ticket
     * @return Result.success if printed, Result.failure if error
     */
    fun printKitchenTicket(
        orderNumber: String?,
        tableName: String? = null,
        orderItems: List<com.jaac.avoqado_tpv.features.ordering.domain.OrderItem>,
        staffName: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            if (orderItems.isEmpty()) {
                return Result.failure(Exception("No hay productos para imprimir"))
            }

            Timber.i("🖨️ [Printer] Printing kitchen ticket for order: $orderNumber")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("     COMANDA DE COCINA\n", null)
            printerInstance.printStr("================================\n", null)

            // Order number (prominent)
            if (!orderNumber.isNullOrBlank()) {
                printerInstance.printStr("Orden #$orderNumber\n", null)
            }

            // Table name (if available)
            if (!tableName.isNullOrBlank()) {
                printerInstance.printStr("Mesa: $tableName\n", null)
            }

            printerInstance.printStr("\n", null)

            // Timestamp
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale("es", "MX"))
            val currentDateTime = dateFormat.format(java.util.Date())
            printerInstance.printStr("Fecha: $currentDateTime\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // ORDER ITEMS (No prices - kitchen doesn't need them)
            // ========================================
            orderItems.forEach { item ->
                // Product line: "3x Pizza Margherita"
                printerInstance.printStr("${item.quantity}x ${item.productName}\n", null)

                // Modifiers (if any) - indented with bullet
                if (item.modifiers.isNotEmpty()) {
                    item.modifiers.forEach { modifier ->
                        printerInstance.printStr("   • ${modifier.name}\n", null)
                    }
                }

                // Notes/special instructions (if any) - indented with arrow
                if (!item.notes.isNullOrBlank()) {
                    printerInstance.printStr("   > ${item.notes}\n", null)
                }

                // Blank line between items for readability
                printerInstance.printStr("\n", null)
            }

            // ========================================
            // FOOTER
            // ========================================
            printerInstance.printStr("--------------------------------\n", null)

            if (!staffName.isNullOrBlank()) {
                printerInstance.printStr("Enviada por: $staffName\n", null)
            }

            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("\n\n\n", null) // Feed paper

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Kitchen ticket printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir comanda (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print kitchen ticket")
            Result.failure(e)
        }
    }

    /**
     * Print simplified kiosk receipt for self-service customers.
     *
     * **Purpose:** Simple receipt for kiosk customers - shows order number, QR code, and totals.
     * More compact than full staff receipt since kiosk customers don't need detailed card info.
     *
     * **Ticket Layout:**
     * ```
     * ================================
     *       RECIBO DE COMPRA
     * ================================
     * Orden #ORD-123
     *
     * Fecha: 15/01/2025  14:30:22
     * --------------------------------
     *
     * Subtotal:        $480.00 MXN
     * Propina:          $50.00 MXN
     * ================================
     * TOTAL:           $530.00 MXN
     * ================================
     *
     *        [QR CODE]
     *
     * Escanea para ver tu recibo
     * ================================
     *    Gracias por tu compra
     * ================================
     * ```
     *
     * @param orderNumber Order number for display
     * @param receiptUrl URL of digital receipt (for QR code)
     * @param amount Total payment amount (formatted, e.g. "530.00")
     * @param tipAmount Tip amount (formatted, e.g. "50.00")
     * @return Result.success if printed, Result.failure if error
     */
    fun printKioskReceipt(
        orderNumber: String,
        receiptUrl: String?,
        amount: String,
        tipAmount: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Printing kiosk receipt for order: $orderNumber")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER - Logo
            // ========================================
            try {
                val originalLogo = android.graphics.BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.logo_avoqado_black
                )
                if (originalLogo != null) {
                    val targetWidth = 220
                    val aspectRatio = originalLogo.height.toFloat() / originalLogo.width.toFloat()
                    val targetHeight = (targetWidth * aspectRatio).toInt()

                    val scaledLogo = Bitmap.createScaledBitmap(originalLogo, targetWidth, targetHeight, true)
                    val logoWithWhiteBg = Bitmap.createBitmap(scaledLogo.width, scaledLogo.height, Bitmap.Config.RGB_565)
                    val canvas = android.graphics.Canvas(logoWithWhiteBg)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.drawBitmap(scaledLogo, 0f, 0f, null)
                    val centeredLogo = centerBitmap(logoWithWhiteBg, targetWidth = 384)
                    printerInstance.printBitmap(centeredLogo)
                    printerInstance.printStr("\n", null)
                } else {
                    printerInstance.printStr("          AVOQADO\n", null)
                }
            } catch (e: Exception) {
                Timber.w(e, "⚠️ [Printer] Could not print logo, using text fallback")
                printerInstance.printStr("          AVOQADO\n", null)
            }

            printerInstance.printStr("    Comprobante de Venta\n\n", null)
            printerInstance.printStr("================================\n\n", null)

            // Order number
            if (orderNumber.isNotBlank()) {
                printerInstance.printStr("Orden #$orderNumber\n\n", null)
            }

            // Timestamp
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale("es", "MX"))
            val currentDateTime = dateFormat.format(java.util.Date())
            printerInstance.printStr("Fecha: $currentDateTime\n", null)
            printerInstance.printStr("--------------------------------\n\n", null)

            // ========================================
            // AMOUNTS
            // ========================================
            val amountValue = amount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val tipValue = tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val subtotal = amountValue - tipValue

            // Only show subtotal if there's a tip
            if (tipValue > java.math.BigDecimal.ZERO) {
                printerInstance.printStr("Subtotal:       \$${subtotal.setScale(2, java.math.RoundingMode.HALF_UP)} MXN\n", null)
                printerInstance.printStr("Propina:        \$${tipValue.setScale(2, java.math.RoundingMode.HALF_UP)} MXN\n", null)
            }

            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("TOTAL:          \$${amountValue.setScale(2, java.math.RoundingMode.HALF_UP)} MXN\n", null)
            printerInstance.printStr("================================\n\n", null)

            // ========================================
            // QR CODE (if receipt URL available)
            // ========================================
            if (receiptUrl != null) {
                try {
                    val qrBitmap = generateQrBitmap(receiptUrl, size = 200)
                    if (qrBitmap != null) {
                        val centeredQr = centerBitmap(qrBitmap, targetWidth = 384)
                        printerInstance.printBitmap(centeredQr)
                        printerInstance.printStr("\n", null)
                        Timber.d("✅ [Printer] Kiosk receipt QR printed")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ [Printer] Could not print QR code")
                }
                printerInstance.printStr(" Escanea para ver tu recibo\n\n", null)
            }

            // ========================================
            // FOOTER
            // ========================================
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("   Gracias por tu compra\n", null)
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("\n\n\n", null) // Feed paper

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Kiosk receipt printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir recibo (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print kiosk receipt")
            Result.failure(e)
        }
    }

    /**
     * 🥝 KIOSK CASH: Print cash confirmation receipt for staff
     *
     * Prints a receipt showing the amount the customer owes in cash.
     * Staff uses this receipt to verify they received the correct amount.
     *
     * **Receipt Layout:**
     * ```
     * ================================
     *    PAGO EN EFECTIVO
     * ================================
     *
     * Orden #123456
     * Fecha: 12/01/2026  14:30:00
     *
     * ================================
     *   MONTO A COBRAR:
     *      $150.00 MXN
     * ================================
     *
     * Propina: $15.00 MXN
     *
     * ENTREGUE ESTE RECIBO AL
     * EMPLEADO JUNTO CON EL DINERO
     *
     * ================================
     * ```
     *
     * @param totalAmount Total amount to collect (formatted, e.g. "150.00")
     * @param tipAmount Optional tip amount (formatted)
     * @param orderNumber Optional order number for reference
     * @return Result.success if printed, Result.failure if error
     */
    fun printCashConfirmationReceipt(
        totalAmount: String,
        tipAmount: String? = null,
        orderNumber: String? = null
    ): Result<Unit> {
        return try {
            val printerInstance = printer ?: return Result.failure(
                Exception("Impresora no disponible. Verifica que el dispositivo PAX esté correctamente configurado.")
            )

            Timber.i("🖨️ [Printer] Printing kiosk cash confirmation receipt")

            // Reset printer state
            printerInstance.init()

            // ========================================
            // HEADER - Cash Payment Notice
            // ========================================
            printerInstance.printStr("\n", null)
            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("     PAGO EN EFECTIVO\n", null)
            printerInstance.printStr("================================\n\n", null)

            // Order number (if available)
            if (!orderNumber.isNullOrBlank()) {
                // Show only last 6 digits if order number is long
                val displayOrderNumber = if (orderNumber.length > 6) {
                    orderNumber.takeLast(6)
                } else {
                    orderNumber
                }
                printerInstance.printStr("Orden #$displayOrderNumber\n", null)
            }

            // Timestamp
            val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale("es", "MX"))
            val currentDateTime = dateFormat.format(java.util.Date())
            printerInstance.printStr("Fecha: $currentDateTime\n\n", null)

            // ========================================
            // AMOUNT TO COLLECT (Large & Bold)
            // ========================================
            val amountValue = totalAmount.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val tipValue = tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO

            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("    MONTO A COBRAR:\n", null)
            printerInstance.printStr("\n", null)
            printerInstance.printStr("    \$${amountValue.setScale(2, java.math.RoundingMode.HALF_UP)} MXN\n", null)
            printerInstance.printStr("\n", null)
            printerInstance.printStr("================================\n\n", null)

            // Show tip if present
            if (tipValue > java.math.BigDecimal.ZERO) {
                printerInstance.printStr("Propina incluida: \$${tipValue.setScale(2, java.math.RoundingMode.HALF_UP)} MXN\n\n", null)
            }

            // ========================================
            // INSTRUCTIONS FOR CUSTOMER
            // ========================================
            printerInstance.printStr("--------------------------------\n", null)
            printerInstance.printStr("  ENTREGUE ESTE RECIBO AL\n", null)
            printerInstance.printStr(" EMPLEADO JUNTO CON EL DINERO\n", null)
            printerInstance.printStr("--------------------------------\n", null)
            printerInstance.printStr("\n\n\n", null) // Feed paper

            // Execute print
            val result = printerInstance.start()

            if (result == 0) {
                Timber.i("✅ [Printer] Cash confirmation receipt printed successfully")
                Result.success(Unit)
            } else {
                Timber.e("❌ [Printer] Print failed with code: $result")
                Result.failure(Exception("Error al imprimir recibo (código: $result)"))
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to print cash confirmation receipt")
            Result.failure(e)
        }
    }

    /**
     * Check if printer is available and ready.
     *
     * @return true if printer is available, false otherwise
     */
    fun isPrinterAvailable(): Boolean {
        return printer != null
    }

    /**
     * Get printer status message for UI display.
     *
     * @return Status message describing printer availability
     */
    fun getPrinterStatus(): String {
        return when {
            dal == null -> "SDK no disponible"
            printer == null -> "Impresora no disponible"
            else -> "Impresora lista"
        }
    }

    /**
     * 🖨️ Check detailed printer status including paper level.
     *
     * PAX IPrinter.getStatus() return codes:
     * - 0: OK / Ready
     * - 1: Busy
     * - 2: Out of paper
     * - 3: Format print data error
     * - 4: Voltage too low
     * - 8: Paper almost running out (low paper)
     * - 9: Printer head too hot
     *
     * @return PrinterStatusResult with status code and human-readable message
     */
    fun checkPrinterStatus(): PrinterStatusResult {
        val printerInstance = printer
        if (printerInstance == null) {
            return PrinterStatusResult(
                code = -1,
                isReady = false,
                hasPaper = false,
                message = "Impresora no disponible"
            )
        }

        return try {
            val statusCode = printerInstance.getStatus()
            Timber.d("🖨️ [Printer] Status code: $statusCode")

            when (statusCode) {
                0 -> PrinterStatusResult(
                    code = 0,
                    isReady = true,
                    hasPaper = true,
                    message = "Impresora lista"
                )
                1 -> PrinterStatusResult(
                    code = 1,
                    isReady = false,
                    hasPaper = true,
                    message = "Impresora ocupada"
                )
                2 -> PrinterStatusResult(
                    code = 2,
                    isReady = false,
                    hasPaper = false,
                    message = "Sin papel"
                )
                3 -> PrinterStatusResult(
                    code = 3,
                    isReady = false,
                    hasPaper = true,
                    message = "Error de formato"
                )
                4 -> PrinterStatusResult(
                    code = 4,
                    isReady = false,
                    hasPaper = true,
                    message = "Voltaje bajo"
                )
                8 -> PrinterStatusResult(
                    code = 8,
                    isReady = true,
                    hasPaper = true,
                    isPaperLow = true,
                    message = "Papel bajo"
                )
                9 -> PrinterStatusResult(
                    code = 9,
                    isReady = false,
                    hasPaper = true,
                    message = "Cabezal caliente"
                )
                else -> PrinterStatusResult(
                    code = statusCode,
                    isReady = false,
                    hasPaper = true,
                    message = "Error desconocido ($statusCode)"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ [Printer] Failed to get status")
            PrinterStatusResult(
                code = -1,
                isReady = false,
                hasPaper = false,
                message = "Error al verificar: ${e.message}"
            )
        }
    }

    /**
     * Quick check if printer has paper and is ready to print.
     *
     * @return true if printer is ready and has paper, false otherwise
     */
    fun canPrint(): Boolean {
        val status = checkPrinterStatus()
        return status.isReady && status.hasPaper
    }
}

/**
 * Result of printer status check.
 */
data class PrinterStatusResult(
    val code: Int,
    val isReady: Boolean,
    val hasPaper: Boolean,
    val isPaperLow: Boolean = false,
    val message: String
)
