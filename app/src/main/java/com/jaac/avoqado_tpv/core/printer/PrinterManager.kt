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
     * --------------------------------
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
     * @param receiptUrl URL of digital receipt (for QR code)
     * @param amount Payment amount (formatted, e.g. "500.00")
     * @param authCode Authorization code from payment processor
     * @param tipAmount Optional tip amount (formatted)
     * @param cardDetails Optional card information (brand, masked PAN, entry mode)
     * @param referenceNumber Optional reference number
     * @param venueRfc Optional venue RFC for fiscal compliance
     * @param venueAddress Optional venue address
     * @return Result.success if printed, Result.failure if printer unavailable/error
     */
    fun printReceipt(
        receiptUrl: String,
        amount: String,
        authCode: String,
        tipAmount: String? = null,
        cardDetails: com.jaac.avoqado_tpv.features.payment.domain.model.CardDetails? = null,
        referenceNumber: String? = null,
        venueRfc: String? = null,
        venueAddress: String? = null
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

            printerInstance.printStr("    Comprobante de Venta\n\n", null)

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
            val tipValue = tipAmount?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            val totalValue = amountValue + tipValue

            printerInstance.printStr("Monto:         \$${amount} MXN\n", null)

            if (tipValue > java.math.BigDecimal.ZERO) {
                printerInstance.printStr("Propina:        \$${tipAmount} MXN\n", null)
            }

            printerInstance.printStr("================================\n", null)
            printerInstance.printStr("TOTAL:         \$${totalValue} MXN\n", null)
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
            // ========================================
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
}
