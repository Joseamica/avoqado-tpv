package com.jaac.avoqado_tpv.core.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Una misma letra en ambas marcas; el alto completo conserva avisos e identidades. */
internal fun renderLocalShiftSummary(text: String): Bitmap {
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 360)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(6f, 1f).build()
    return Bitmap.createBitmap(384, layout.height + 24, Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply { drawColor(Color.WHITE); translate(12f, 12f); layout.draw(this) }
    }
}
