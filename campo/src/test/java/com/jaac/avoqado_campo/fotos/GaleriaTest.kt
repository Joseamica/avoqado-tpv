package com.jaac.avoqado_campo.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** La foto elegida de la galería se copia al celular y pasa por el mismo `Compresor` que la de la cámara. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GaleriaTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fotos get() = File(context.cacheDir, "fotos")

    @Before fun limpiar() { fotos.deleteRecursively() }

    private fun elegida(bytes: ByteArray): Uri = Uri.parse("content://media/picker/0/${bytes.size}").also {
        shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(bytes))
    }

    private fun jpeg(ancho: Int, alto: Int): ByteArray {
        val b = Bitmap.createBitmap(ancho, alto, Bitmap.Config.RGB_565)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { b.recycle() }
    }

    @Test fun `la foto de la galeria queda comprimida en las fotos de la app y la copia cruda se borra`() = runBlocking {
        val lista = copiarDeLaGaleria(context, elegida(jpeg(4000, 3000)))
        assertEquals(fotos, lista.parentFile)
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(lista.path, it) }
        assertEquals(1920 to 1440, medidas.outWidth to medidas.outHeight)
        assertEquals("sólo queda la comprimida", listOf(lista.name), fotos.list()!!.toList())
    }

    @Test fun `una imagen que no se puede leer avisa en llano y no deja archivos`() = runBlocking {
        try {
            copiarDeLaGaleria(context, elegida("no soy una foto".toByteArray()))
            fail("debió avisar")
        } catch (e: ImagenIlegible) {
            assertTrue(e.message!!.startsWith("No se pudo leer esa imagen"))
        }
        assertTrue(fotos.list().orEmpty().isEmpty())
    }
}
