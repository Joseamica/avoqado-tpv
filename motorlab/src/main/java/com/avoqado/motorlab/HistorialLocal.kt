package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale

/** Un folio del laboratorio. [importeCentavos] null = su archivo no tiene el formato completo. Sin datos de la tarjeta. */
internal data class FolioDelHistorial(val folio: AttemptId, val importeCentavos: Long?, val notas: List<Pair<Long, String>>)

/**
 * El historial del laboratorio (Ruling 10): UN ARCHIVO POR FOLIO, `<nnnnnn>-<folio>`, con creación exclusiva; [anotarFolio]
 * escribe el búfer COMPLETO y sincroniza antes de regresar, o lanza. Uno a medias se lista sin importe y nunca se come a otro.
 * Las notas (cada evento del motor, el corte, el lector, los finales publicados) viven en memoria.
 *
 * ponytail: sincroniza el archivo, no la carpeta; tras un apagón (no tras una caída del proceso) la entrada nueva podría no
 * estar. Basta para el laboratorio; si algún día importa, sincronizar también la carpeta.
 */
internal class HistorialLocal(private val carpeta: File) {
    private val notas = HashMap<AttemptId, MutableList<Pair<Long, String>>>()
    private var siguiente: Int

    init {
        carpeta.mkdirs()
        siguiente = 1 + (archivos().mapNotNull { it.name.substringBefore('-').toIntOrNull() }.maxOrNull() ?: 0)
    }

    @Synchronized fun anotarFolio(folio: AttemptId, importeCentavos: Long) {
        val ruta = File(carpeta, String.format(Locale.ROOT, "%06d-%s", siguiente++, folio.valor)).toPath()
        val b = ByteBuffer.wrap("$importeCentavos\n".toByteArray(Charsets.US_ASCII))
        FileChannel.open(ruta, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            while (b.hasRemaining()) it.write(b) // un `write` puede quedarse corto: el búfer COMPLETO, luego sincronizar
            it.force(true)
        }
        notas[folio] = mutableListOf()
    }

    @Synchronized fun nota(folio: AttemptId, ms: Long, texto: String) {
        notas[folio]?.add(ms to texto.replace('\n', ' '))
    }

    /**
     * Bajo el monitor sólo se listan los nombres y se copian las notas; los archivos se leen FUERA, así el `anotarFolio` de un
     * cobro nunca espera a la pantalla. Lo listado ya está completo: se escribe entero bajo el mismo monitor.
     */
    fun leer(): List<FolioDelHistorial> = synchronized(this) {
        archivos().sortedBy { it.name }.mapNotNull { a ->
            val folio = runCatching { AttemptId(a.name.substringAfter('-')) }.getOrNull() ?: return@mapNotNull null
            Triple(a, folio, notas[folio].orEmpty().toList())
        }
    }.map { (a, folio, n) ->
        val importe = runCatching { a.readText(Charsets.US_ASCII) }.getOrNull()?.let { FORMATO.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        FolioDelHistorial(folio, importe, n)
    }

    private fun archivos(): List<File> = carpeta.listFiles()?.filter { it.isFile }.orEmpty()

    private companion object {
        val FORMATO = Regex("^([0-9]{1,7})\n$")
    }
}
