package com.jaac.avoqado_campo.fotos

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.Recuadro
import com.jaac.avoqado_campo.ui.Tono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** La imagen elegida no se pudo abrir o no es una foto que el celular lea. */
class ImagenIlegible : IOException(
    "No se pudo leer esa imagen: puede estar dañada, no ser una foto o estar sólo en la nube. Elige otra o tómala con la cámara.",
)

internal const val NO_SE_GUARDO_LA_FOTO = "No se pudo guardar la foto en el celular. Revisa que tenga espacio e intenta otra vez."

/**
 * 0.6.0: la foto de evidencia también puede salir de la galería. Antes de nada se COPIA a `cacheDir/fotos/` (sin red, como la
 * cámara) y pasa por el mismo [comprimirCruda]; quien la recibe la trata igual que una de [Camara]. La copia cruda se borra.
 */
suspend fun copiarDeLaGaleria(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val (dir, nombre, cruda) = fotoCruda(context)
    try {
        val entrada = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: throw ImagenIlegible()
        entrada.use { i -> cruda.outputStream().use { i.copyTo(it) } }
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cruda.path, medidas)
        if (medidas.outWidth <= 0 || medidas.outHeight <= 0) throw ImagenIlegible()
        comprimirCruda(dir, nombre, cruda)
    } finally {
        cruda.delete()
    }
}

/** La carpeta de las fotos, un nombre nuevo y el archivo crudo (el de la cámara o la copia de la galería). */
internal fun fotoCruda(context: Context): Triple<File, String, File> {
    val dir = File(context.cacheDir, "fotos").apply { mkdirs() }
    val nombre = UUID.randomUUID().toString()
    return Triple(dir, nombre, File(dir, "$nombre-cruda.jpg"))
}

/** El mismo [Compresor] para la cámara y la galería; la cruda se borra y, si falla, no queda una comprimida a medias. */
internal fun comprimirCruda(dir: File, nombre: String, cruda: File): File {
    val lista = File(dir, "$nombre.jpg")
    try {
        Compresor.comprimir(cruda, lista)
    } catch (e: Exception) {
        lista.delete()
        throw e
    } finally {
        cruda.delete()
    }
    return lista
}

/**
 * «Elegir de la galería», junto a «📷 Tomar la foto»: el selector del sistema (sin permisos). Lo que no se pudo leer lo dice
 * aquí mismo, en llano.
 */
@Composable
fun BotonDeGaleria(enabled: Boolean, onFoto: (File) -> Unit) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()
    var copiando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // la persona no eligió nada
        copiando = true
        error = null
        alcance.launch {
            try {
                onFoto(copiarDeLaGaleria(context, uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImagenIlegible) {
                error = e.message
            } catch (e: Exception) {
                error = NO_SE_GUARDO_LA_FOTO
            } finally {
                copiando = false
            }
        }
    }
    BotonSecundario(
        if (copiando) "Guardando…" else "Elegir de la galería",
        { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled && !copiando,
    )
    error?.let { Recuadro(it, Tono.ERROR) }
}
