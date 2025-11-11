package com.jaac.avoqado_tpv.core.presentation.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Reusable composable function to generate QR code from string content.
 *
 * **Usage:**
 * ```kotlin
 * Image(
 *     painter = rememberQrBitmapPainter(
 *         content = "https://avoqado.io/receipt/abc123",
 *         size = 200.dp,
 *         padding = 8.dp
 *     ),
 *     contentDescription = "QR Code",
 *     modifier = Modifier.size(200.dp)
 * )
 * ```
 *
 * **Features:**
 * - Uses ZXing library for QR code generation
 * - Generates bitmap asynchronously on IO dispatcher
 * - Remembers QR code to avoid regeneration
 * - Supports custom size and padding
 *
 * **Dependencies:**
 * - com.google.zxing:core:3.5.3 (already in build.gradle.kts:194)
 *
 * @param content The text content to encode in the QR code (usually a URL)
 * @param size The size of the QR code in Dp (default: 150.dp)
 * @param padding The padding around the QR code in Dp (default: 0.dp)
 * @return BitmapPainter containing the QR code image
 */
@Composable
fun rememberQrBitmapPainter(
    content: String,
    size: Dp = 150.dp,
    padding: Dp = 0.dp
): BitmapPainter {
    val density = LocalDensity.current
    val sizePx = with(density) { size.roundToPx() }
    val paddingPx = with(density) { padding.roundToPx() }

    var bitmap by remember(content) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(bitmap) {
        if (bitmap != null) return@LaunchedEffect

        launch(Dispatchers.IO) {
            val qrCodeWriter = QRCodeWriter()
            val encodeHints = mutableMapOf<EncodeHintType, Any?>()
                .apply {
                    this[EncodeHintType.MARGIN] = paddingPx
                }

            val bitmapMatrix = try {
                qrCodeWriter.encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    sizePx,
                    sizePx,
                    encodeHints
                )
            } catch (ex: WriterException) {
                Timber.e(ex, "❌ Failed to encode QR code")
                null
            }

            val matrixWidth = bitmapMatrix?.width ?: sizePx
            val matrixHeight = bitmapMatrix?.height ?: sizePx

            val newBitmap = Bitmap.createBitmap(
                matrixWidth,
                matrixHeight,
                Bitmap.Config.ARGB_8888,
            )

            for (x in 0 until matrixWidth) {
                for (y in 0 until matrixHeight) {
                    val shouldColorPixel = bitmapMatrix?.get(x, y) ?: false
                    val pixelColor = if (shouldColorPixel) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                    newBitmap.setPixel(x, y, pixelColor)
                }
            }

            bitmap = newBitmap
            Timber.d("✅ QR code generated successfully (${sizePx}x${sizePx})")
        }
    }

    return remember(bitmap) {
        val currentBitmap = bitmap ?: Bitmap.createBitmap(
            sizePx, sizePx,
            Bitmap.Config.ARGB_8888,
        ).apply {
            eraseColor(android.graphics.Color.TRANSPARENT)
        }

        BitmapPainter(currentBitmap.asImageBitmap())
    }
}
