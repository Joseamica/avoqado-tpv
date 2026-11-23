package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Bloque 4-L · L-d1: quién cobra en el laboratorio, tecleado: la serie de Blumon, el `posId` y la serie física (va al 9F1E).
 * Sin forma no nace (`IllegalArgumentException`, sin el valor). Nunca se enseña: su `toString` la oculta.
 */
internal data class IdentidadTecleada(val serieBlumon: String, val posId: String, val serieFisica: String) {
    init {
        require(SERIE.matches(serieBlumon) && SERIE.matches(serieFisica) && POS_ID.matches(posId))
    }

    override fun toString() = "IdentidadTecleada(oculta)"

    /** La del motor: sin comercio (el motor no lo compara si no viene), en el sandbox. */
    fun identidad() = Identidad(
        comercio = null,
        posId = posId,
        serieFisica = serieFisica,
        serieBlumon = serieBlumon,
        modelo = "A910S",
        versionSdk = "motorlab-0.1.0",
        ambiente = Ambiente.SANDBOX,
    )

    companion object {
        private val SERIE = Regex("^[0-9A-Za-z]{4,32}$")

        /** La forma que exige el motor al armarse (`ArmadoDelMensaje.kt:149`). */
        private val POS_ID = Regex("^[0-9]{1,9}$")

        /** Lo tecleado, sin los espacios de los lados; la física vacía es la de Blumon (`RecorridoSandboxTest:238`). */
        fun de(serie: String, posId: String, fisica: String): IdentidadTecleada {
            val s = serie.trim()
            return IdentidadTecleada(s, posId.trim(), fisica.trim().ifEmpty { s })
        }
    }
}

/** La identidad guardada en la carpeta privada de la app, tres líneas: la serie de Blumon, el `posId` y la serie física. */
internal class ArchivoDeIdentidad(dir: File) {
    private val archivo = File(dir, "identidad-del-laboratorio.txt")

    /** UNA lectura que nunca lanza: la identidad; `null` sólo si no hay archivo (`NoSuchFileException`); fallo = ilegible, por lo que sea. */
    fun leer(): Result<IdentidadTecleada?> = runCatching {
        val l = String(Files.readAllBytes(archivo.toPath()), Charsets.US_ASCII).split('\n')
        require(l.size == 4 && l[3].isEmpty())
        IdentidadTecleada(l[0], l[1], l[2])
    }.recoverCatching { if (it is NoSuchFileException) null else throw it }

    fun guardar(t: IdentidadTecleada) =
        escribirEntero(archivo, "${t.serieBlumon}\n${t.posId}\n${t.serieFisica}\n".toByteArray(Charsets.US_ASCII))
}

/**
 * Entero o nada: escribe `<destino>.nuevo`, lo baja al disco y lo renombra encima de [destino]. Un proceso que muere a medias
 * deja el anterior (o ninguno), nunca uno cortado. Si no se puede, lanza.
 *
 * ponytail: no sincroniza la carpeta tras renombrar; un apagón justo después puede devolver el anterior. Basta para el
 * laboratorio; si algún día importa, sincronizar también la carpeta.
 */
internal fun escribirEntero(destino: File, bytes: ByteArray) {
    val nuevo = File(destino.path + ".nuevo")
    FileOutputStream(nuevo).use { s ->
        s.write(bytes)
        s.fd.sync()
    }
    if (!nuevo.renameTo(destino)) throw IOException("no se pudo renombrar")
}

/**
 * La llave de las huellas del motor: 32 bytes al azar que nacen UNA vez en la carpeta privada y se releen igual. Una llave que
 * no mide 32 bytes nunca se reemplaza: lanza (otra llave cambiaría las huellas de lo que ya está en la libreta).
 *
 * ponytail: revisa y luego crea; con un solo llamador por proceso (la fábrica del único motor) basta.
 */
internal fun llaveDeHuellas(dir: File): SecretKey {
    val archivo = File(dir, "llave-de-huellas")
    if (!archivo.exists()) escribirEntero(archivo, ByteArray(32).also { SecureRandom().nextBytes(it) })
    val bytes = archivo.readBytes()
    if (bytes.size != 32) throw IOException("la llave de huellas no mide 32 bytes")
    return SecretKeySpec(bytes, "HmacSHA256")
}
