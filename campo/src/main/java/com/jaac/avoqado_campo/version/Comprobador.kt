package com.jaac.avoqado_campo.version

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Lo que dice el propio archivo: paquete, versionCode y la huella SHA-256 de cada certificado firmante. */
data class InfoDeArchivo(val paquete: String?, val versionCode: Long, val firmas: Set<String>)

/**
 * Antes de instalar: SHA-256 del archivo = el anunciado (vacío ⇒ Mala), y el archivo es de ESTA app (paquete propio y el
 * `versionCode` anunciado) firmado con las MISMAS llaves que la app instalada. Archivo ilegible o sin firmas ⇒ Mala (Codex
 * r1 #4). [leer] se inyecta para probarse sin un APK real; el de verdad es [lectorDelSistema].
 */
class Comprobador(
    private val paquetePropio: String,
    private val firmasPropias: () -> Set<String>,
    private val leer: (File) -> InfoDeArchivo?,
) {
    fun comprobar(archivo: File, v: VersionNueva): Bajada {
        val motivo = when {
            v.sha256.isBlank() -> "el SERVIDOR no mandó la huella del archivo"
            !sha256(archivo).equals(v.sha256.trim(), ignoreCase = true) -> "la huella del archivo no es la anunciada"
            else -> {
                val info = leer(archivo)
                val propias = firmasPropias()
                when {
                    info == null -> "Android no pudo leer el archivo"
                    info.paquete != paquetePropio -> "el archivo es de otra app (${info.paquete})"
                    info.versionCode != v.versionCode.toLong() -> "el archivo es la versión ${info.versionCode}, no la ${v.versionCode}"
                    info.firmas.isEmpty() || propias.isEmpty() -> "sin firmas que comparar"
                    info.firmas != propias -> "el archivo está firmado con otra llave"
                    else -> null
                }
            }
        }
        return if (motivo == null) Bajada.Lista(archivo) else Bajada.Mala(motivo)
    }
}

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun sha256(archivo: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    archivo.inputStream().use { e ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = e.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return hex(md.digest())
}

/**
 * Las firmas de un [PackageInfo] leído con [banderasDeFirma]. API 28+: los firmantes actuales de `signingInfo`; API 27:
 * `signatures`. Del archivo y de la app instalada se lee siempre lo mismo, así que se comparan manzanas con manzanas.
 */
@Suppress("DEPRECATION")
private fun firmasDe(info: PackageInfo): Set<String> {
    val firmas: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return firmas.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
}

/**
 * Codex r2 #1: en Android 9, `getPackageArchiveInfo` sólo junta los certificados del ARCHIVO si va `GET_SIGNATURES`; con
 * sólo `GET_SIGNING_CERTIFICATES` devuelve el paquete sin `signingInfo`. Por eso en 28+ van las dos.
 */
@Suppress("DEPRECATION")
internal fun banderasDeFirma(sdk: Int = Build.VERSION.SDK_INT) =
    if (sdk >= 28) PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

/** El lector real: lo que Android dice del archivo (verifica sus firmas), o null si no lo puede leer. */
fun lectorDelSistema(context: Context): (File) -> InfoDeArchivo? = { archivo ->
    try {
        context.packageManager.getPackageArchiveInfo(archivo.path, banderasDeFirma())
            ?.let { InfoDeArchivo(it.packageName, PackageInfoCompat.getLongVersionCode(it), firmasDe(it)) }
    } catch (e: Exception) {
        null
    }
}

/** Las firmas de la app instalada (la que corre); vacío si no se pueden leer (y entonces nada pasa la revisión). */
@SuppressLint("PackageManagerGetSignatures") // se comparan TODAS las firmas, no sólo la primera
fun firmasInstaladas(context: Context): () -> Set<String> = {
    try {
        firmasDe(context.packageManager.getPackageInfo(context.packageName, banderasDeFirma()))
    } catch (e: Exception) {
        emptySet()
    }
}
