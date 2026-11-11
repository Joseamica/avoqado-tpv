package com.jaac.avoqado_campo.version

import com.jaac.avoqado_campo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Baja el APK a `<carpeta>/campo-<versionCode>.part` y sólo cuando llegó completo (y del tamaño anunciado) lo renombra a
 * `.apk`: nunca queda un archivo a medias que parezca listo. Antes de empezar borra lo previo. OkHttp PROPIO, sin
 * interceptores (ni el del registro ni ninguno que agregue cabeceras), y en release sólo `https`, también tras una
 * redirección. Sin red, corte o 5xx = [Bajada.SinRed] (se reintenta); una liga que no es https o un 4xx = [Bajada.Mala].
 */
class Descargador(private val carpeta: File, private val soloHttps: Boolean = !BuildConfig.DEBUG) {
    private val cliente = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(!soloHttps)
        .build()

    suspend fun bajar(v: VersionNueva): Bajada = withContext(Dispatchers.IO) {
        val url = v.url.toHttpUrlOrNull() ?: return@withContext Bajada.Mala("liga ilegible")
        if (soloHttps && !url.isHttps) return@withContext Bajada.Mala("la liga no es https")
        limpiar()
        carpeta.mkdirs()
        val parte = File(carpeta, "campo-${v.versionCode}.part")
        val apk = File(carpeta, "campo-${v.versionCode}.apk")
        try {
            cliente.newCall(Request.Builder().url(url).build()).execute().use { r ->
                when {
                    r.code == 408 || r.code == 429 || r.code >= 500 -> return@withContext Bajada.SinRed
                    !r.isSuccessful -> return@withContext Bajada.Mala("el archivo contestó HTTP ${r.code}")
                    soloHttps && !r.request.url.isHttps -> return@withContext Bajada.Mala("la liga no es https")
                }
                r.body!!.byteStream().use { entrada -> parte.outputStream().use { entrada.copyTo(it) } }
            }
            // Un cuerpo que se cortó sin avisar (o un portal en medio) no llega con el tamaño anunciado.
            if (v.tamano > 0 && parte.length() != v.tamano) return@withContext Bajada.SinRed
            if (!parte.renameTo(apk)) return@withContext Bajada.SinRed
            Bajada.Lista(apk)
        } catch (e: IOException) {
            Bajada.SinRed
        } finally {
            parte.delete()
        }
    }

    /** Borra lo bajado (al empezar otra bajada y al arrancar la app). */
    fun limpiar() {
        carpeta.deleteRecursively()
    }
}
