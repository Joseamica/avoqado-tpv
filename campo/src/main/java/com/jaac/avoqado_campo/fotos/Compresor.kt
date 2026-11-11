package com.jaac.avoqado_campo.fotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Como `VerificationUploadManager.compressImage` de la PAX: lado mayor a 1920 px, girada según su EXIF (la de salida ya
 * viene de pie y sin EXIF) y JPEG al 80 %. Corre fuera del hilo de la pantalla.
 */
object Compresor {
    const val LADO_MAXIMO = 1920
    private const val CALIDAD = 80

    fun comprimir(origen: File, destino: File) {
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(origen.path, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) throw IOException("No se pudo leer la foto")
        var muestra = 1
        while (maxOf(medidas.outWidth, medidas.outHeight) / muestra > LADO_MAXIMO * 2) muestra *= 2

        var b = BitmapFactory.decodeFile(origen.path, BitmapFactory.Options().apply { inSampleSize = muestra })
            ?: throw IOException("No se pudo leer la foto")
        val escala = LADO_MAXIMO.toFloat() / maxOf(b.width, b.height)
        val m = Matrix()
        if (escala < 1f) m.postScale(escala, escala)
        val grados = when (ExifInterface(origen.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        m.postRotate(grados)
        if (!m.isIdentity) {
            val nueva = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (nueva !== b) b.recycle()
            b = nueva
        }
        destino.parentFile?.mkdirs()
        destino.outputStream().use { if (!b.compress(Bitmap.CompressFormat.JPEG, CALIDAD, it)) throw IOException("No se pudo guardar la foto") }
        b.recycle()
    }
}
