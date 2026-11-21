package com.avoqado.cobropropio.cripto

import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * DUKPT de ANSI X9.24-1 (TDES, contador de 21 bits), como lo usa Blumon TPV para cifrar la banda 2 de cada venta:
 * llave de la transacción → variante de DATOS → 3DES de esa variante consigo misma → 3DES-ECB de la banda rellenada con F.
 * Rescatado del piloto (`lectorlab/Dukpt.kt`). Ningún mensaje de error lleva llaves, KSN ni la banda.
 */
object Dukpt {

    const val CONTADOR_MAXIMO = 0x1FFFFF

    /** Sólo las pruebas lo usan: ve cada arreglo intermedio de llave justo antes de que se borre. */
    @get:JvmSynthetic @set:JvmSynthetic @Volatile internal var antesDeBorrar: ((ByteArray) -> Unit)? = null

    /** La llave de la transacción para ese KSN (de 10 bytes, ya con contador), a partir de la IPEK de 16 bytes. */
    @JvmSynthetic internal fun llaveDeTransaccion(ipek: ByteArray, ksn: ByteArray): ByteArray {
        require(ipek.size == 16 && ksn.size == 10) { "IPEK o KSN de largo inválido" }
        val contador = contadorDe(ksn)
        require(contador in 1..CONTADOR_MAXIMO) { "contador fuera de rango" }
        val registro = ksn.copyOfRange(2, 10)
        registro[5] = (registro[5].toInt() and 0xE0).toByte(); registro[6] = 0; registro[7] = 0
        var llave = ipek
        var bit = 0x100000
        while (bit > 0) {
            if (contador and bit != 0) {
                registro[5] = (registro[5].toInt() or ((bit shr 16) and 0x1F)).toByte()
                registro[6] = (registro[6].toInt() or ((bit shr 8) and 0xFF)).toByte()
                registro[7] = (registro[7].toInt() or (bit and 0xFF)).toByte()
                val siguiente = noReversible(llave, registro)
                if (llave !== ipek) borrar(llave) // la IPEK es del llamador; las intermedias, nuestras
                llave = siguiente
            }
            bit = bit shr 1
        }
        borrar(registro)
        return llave
    }

    /** Banda 2 en hex (tag 57, separador `D`) rellenada con F a 48 y cifrada con la llave de la transacción. Regresa hex. */
    @JvmSynthetic internal fun cifrarTrack2(llave: ByteArray, track2Hex: String): String {
        require(llave.size == 16) { "llave de largo inválido" }
        require(HEX_HASTA_48.matches(track2Hex)) { "banda 2 inválida" }
        val datos = llaveDeDatos(llave)
        val banda = track2Hex.uppercase().padEnd(48, 'F').hexABytes()
        try {
            return tdes(datos, banda).aHex()
        } finally {
            borrar(datos, banda)
        }
    }

    /** CRC-32 del texto (los caracteres hex tal cual, en ASCII), en 8 hex mayúsculas. */
    fun crc32(textoHex: String): String =
        CRC32().run { update(textoHex.toByteArray(Charsets.US_ASCII)); "%08X".format(value) }

    /** Valor de verificación: los primeros 3 bytes (6 hex) de 3DES de 8 bytes en cero, como lo manda Blumon. */
    fun kcv(llave: ByteArray): String {
        require(llave.size == 16) { "llave de largo inválido" }
        return tdes(llave, ByteArray(8)).aHex().take(6)
    }

    /** El KSN de 10 bytes con el contador en sus 21 bits bajos (lo que viaja como `serialKey`). Falla cerrado. */
    fun ksnCon(base: ByteArray, contador: Int): ByteArray {
        require(base.size == 10) { "KSN de largo inválido" }
        require(contador in 1..CONTADOR_MAXIMO) { "contador fuera de rango" }
        val k = base.copyOf()
        k[7] = ((k[7].toInt() and 0xE0) or ((contador shr 16) and 0x1F)).toByte()
        k[8] = (contador shr 8).toByte()
        k[9] = contador.toByte()
        return k
    }

    /** El KSN con el contador en cero, en hex mayúsculas: la llave del contador por base. */
    fun baseCanonica(ksn: ByteArray): String {
        require(ksn.size == 10) { "KSN de largo inválido" }
        return ksn.copyOf().also { it[7] = (it[7].toInt() and 0xE0).toByte(); it[8] = 0; it[9] = 0 }.aHex()
    }

    // ponytail: `SecretKeySpec` guarda su propia copia de la llave y no se puede borrar (su `destroy()` no está implementado);
    // es el techo conocido. Si hace falta más, cifrar con una llave del Keystore (sin bytes en el proceso).
    @JvmSynthetic internal fun tdes(llave16: ByteArray, datos: ByteArray, cifrar: Boolean = true): ByteArray {
        val k1 = llave16.copyOfRange(0, 8)
        val k = llave16 + k1
        try {
            val c = Cipher.getInstance("DESede/ECB/NoPadding")
            c.init(if (cifrar) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(k, "DESede"))
            return c.doFinal(datos)
        } finally {
            borrar(k1, k)
        }
    }

    /** Variante de DATOS (petición) y luego 3DES de esa llave consigo misma (Blumon: DUKPT.getVariantKey). */
    @JvmSynthetic internal fun llaveDeDatos(llaveTx: ByteArray): ByteArray {
        val variante = xor(llaveTx, MASCARA_DATOS)
        try {
            return tdes(variante, variante)
        } finally {
            borrar(variante)
        }
    }

    private fun contadorDe(ksn: ByteArray) =
        ((ksn[7].toInt() and 0x1F) shl 16) or ((ksn[8].toInt() and 0xFF) shl 8) or (ksn[9].toInt() and 0xFF)

    private fun des(llave8: ByteArray, datos8: ByteArray): ByteArray {
        val c = Cipher.getInstance("DES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(llave8, "DES"))
        return c.doFinal(datos8)
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun noReversible(llave: ByteArray, registro: ByteArray): ByteArray {
        fun mitad(k: ByteArray): ByteArray {
            val izquierda = k.copyOfRange(0, 8)
            val derecha = k.copyOfRange(8, 16)
            val entrada = xor(registro, derecha)
            val cifrada = des(izquierda, entrada)
            try {
                return xor(cifrada, derecha)
            } finally {
                borrar(izquierda, derecha, entrada, cifrada)
            }
        }
        val conMascara = xor(llave, MASCARA_C0)
        val alta = mitad(conMascara)
        val baja = mitad(llave)
        try {
            return alta + baja
        } finally {
            borrar(conMascara, alta, baja)
        }
    }

    /** Pone en cero cada arreglo intermedio de llave (r1 R1-9). */
    private fun borrar(vararg arreglos: ByteArray) {
        for (a in arreglos) {
            antesDeBorrar?.invoke(a)
            a.fill(0)
        }
    }

    private val HEX_HASTA_48 = Regex("^[0-9A-Fa-f]{1,48}$")
    private val MASCARA_C0 = "C0C0C0C000000000C0C0C0C000000000".hexABytes()
    private val MASCARA_DATOS = "0000000000FF00000000000000FF0000".hexABytes()
}

internal fun ByteArray.aHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexABytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
