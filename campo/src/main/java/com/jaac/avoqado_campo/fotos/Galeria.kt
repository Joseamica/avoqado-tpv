package com.jaac.avoqado_campo.fotos

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaac.avoqado_campo.ui.BotonSecundario
import com.jaac.avoqado_campo.ui.tema.ColoresCampo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

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

/** La copia la hace el ViewModel con esto: su alcance sobrevive a abrir la cámara, que desmonta el botón. */
class Galeria @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun copiar(uri: Uri): File = copiarDeLaGaleria(context, uri)
}

/** Copia [uri] y se la da a [ponerFoto]; devuelve el aviso en llano si falló (la foto anterior no se toca), o null. */
internal suspend fun copiarDeGaleria(galeria: Galeria, uri: Uri, ponerFoto: (String) -> Unit): String? = try {
    ponerFoto(galeria.copiar(uri).absolutePath)
    null
} catch (c: CancellationException) {
    throw c
} catch (e: ImagenIlegible) {
    e.message
} catch (e: Exception) {
    NO_SE_GUARDO_LA_FOTO
}

/** «Elegir de la galería», junto a «📷 Tomar la foto»: el selector del sistema (sin permisos). La copia es del ViewModel. */
@Composable
fun BotonDeGaleria(enabled: Boolean, onElegida: (Uri) -> Unit) {
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onElegida(uri) // null = la persona no eligió nada
    }
    BotonSecundario(
        "Elegir de la galería", { elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled,
    )
}

/**
 * Mientras se copia la foto de la galería (~1 s): encima de la pantalla, «Guardando la foto…»; no se toca nada y «atrás» no
 * sale. Así nada se registra ni se envía con la foto anterior (founder, 26-nov: simplificar).
 */
@Composable
fun BloqueoGuardandoFoto(activo: Boolean) {
    if (!activo) return
    BackHandler {}
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = ColoresCampo.Blanco) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = ColoresCampo.Verde, strokeWidth = 2.dp)
                Text("Guardando la foto…", Modifier.padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
