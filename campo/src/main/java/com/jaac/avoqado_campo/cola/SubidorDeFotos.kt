package com.jaac.avoqado_campo.cola

import com.jaac.avoqado_campo.red.objetoJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

sealed interface ResultadoFoto {
    object Subida : ResultadoFoto
    object Reintentable : ResultadoFoto
    data class Definitiva(val mensaje: String) : ResultadoFoto
}

/**
 * B1 v2: subida simple por la API REST de Storage, sin SDK ni token (las reglas dejan escribir bajo `<env>/venues/`). Subir otra
 * vez la misma ruta deja el mismo archivo. La URL de la foto ya está en la pieza (`urlDe`, calculada al encolar).
 */
class SubidorDeFotos(private val cliente: OkHttpClient, private val baseStorage: String = BASE_STORAGE) {

    suspend fun subir(f: FotoPorSubir): ResultadoFoto = withContext(Dispatchers.IO) {
        val archivo = File(f.archivo)
        if (!archivo.isFile) return@withContext ResultadoFoto.Definitiva("La foto ya no está en el celular")
        val pedido = Request.Builder()
            .url("${baseStorage}v0/b/$BUCKET/o?name=${codificar(f.ruta)}")
            .post(archivo.asRequestBody(JPEG))
            .build()
        try {
            cliente.newCall(pedido).execute().use { r ->
                when {
                    // Sólo cuenta si Storage dice que guardó una imagen del mismo tamaño (un portal cautivo también da 200).
                    r.isSuccessful -> {
                        val o = objetoJson(r.body?.string())
                        val tipo = o?.get("contentType")?.takeIf { it.isJsonPrimitive }?.asString
                        val size = o?.get("size")?.takeIf { it.isJsonPrimitive }?.asString
                        if (tipo == "image/jpeg" && size == f.bytes.toString()) ResultadoFoto.Subida else ResultadoFoto.Reintentable
                    }
                    r.code == 408 || r.code == 429 || r.code >= 500 || r.code !in 400..499 -> ResultadoFoto.Reintentable // 408 = se acabó el tiempo
                    else -> ResultadoFoto.Definitiva("No se pudo subir la foto (HTTP ${r.code})")
                }
            }
        } catch (e: IOException) {
            ResultadoFoto.Reintentable
        }
    }

    private companion object { val JPEG = "image/jpeg".toMediaType() }
}
