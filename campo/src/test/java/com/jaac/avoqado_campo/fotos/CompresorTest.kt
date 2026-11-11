package com.jaac.avoqado_campo.fotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE) // decodifica y escala de verdad
class CompresorTest {
    @get:Rule val carpeta = TemporaryFolder()

    private fun jpeg(ancho: Int, alto: Int): File {
        val f = carpeta.newFile("cruda.jpg")
        val b = Bitmap.createBitmap(ancho, alto, Bitmap.Config.RGB_565)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        b.recycle()
        return f
    }

    private fun medidas(f: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(f.path, it) }.let { it.outWidth to it.outHeight }

    @Test fun `una foto de 4000x3000 sale con su lado mayor en 1920`() {
        val salida = File(carpeta.root, "lista.jpg")
        Compresor.comprimir(jpeg(4000, 3000), salida)
        assertEquals(1920 to 1440, medidas(salida))
    }

    @Test fun `la foto girada por EXIF sale de pie`() {
        val cruda = jpeg(4000, 3000)
        ExifInterface(cruda.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val salida = File(carpeta.root, "lista.jpg")
        Compresor.comprimir(cruda, salida)
        assertEquals(1440 to 1920, medidas(salida))
    }
}
