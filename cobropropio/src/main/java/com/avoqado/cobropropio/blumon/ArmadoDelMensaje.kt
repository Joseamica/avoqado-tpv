package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.aHex
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.security.MessageDigest

/** Sin la bandera `sendReverse` Blumon contesta `RQ_005` (Blumon, 10-nov); el motivo es fijo. */
const val MOTIVO_DEL_REVERSO = "cancelled"

/** La IPEK, la base KSN y el contador ya reservado en la libreta para ESTE mensaje. Sólo en RAM. */
class MaterialDukpt(@get:JvmSynthetic internal val ipek: ByteArray, val ksnBase: ByteArray, val contador: Int) {
    override fun toString() = "MaterialDukpt(oculto)"
}

/**
 * El cuerpo de Blumon, opaco: lleva el PAN y la banda, así que nadie del módulo lo lee. Sólo se escribe al cable
 * (`comoRequestBody`) o se convierte en su reverso.
 */
class CuerpoDeBlumon private constructor(private val json: JsonObject) {

    @JvmSynthetic internal fun comoRequestBody(): RequestBody = json.toString().toRequestBody(JSON)

    /**
     * El cuerpo exacto de la venta (misma referencia, misma banda cifrada, mismo KSN) + motivo + `sendReverse`, con el
     * importe en texto, como `ReverseApiRequest.amount: String` del SDK de Blumon (piloto, `BlumonPilot.kt:153-161`).
     */
    fun conMotivoDeReverso(): CuerpoDeBlumon = CuerpoDeBlumon(
        json.deepCopy().apply {
            addProperty("reverseReason", MOTIVO_DEL_REVERSO)
            addProperty("amount", get("amount").asBigDecimal.setScale(2).toPlainString())
            addProperty("sendReverse", true)
        },
    )

    override fun toString() = "CuerpoDeBlumon(oculto)"

    internal companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun desde(json: JsonObject) = CuerpoDeBlumon(json)
    }
}

/**
 * Arma el mensaje de venta, cancelación y reverso por marca, rescatado del piloto que cobró en producción
 * (`BlumonPilot.kt:204-232`). La marca la dice el kernel (`lectura.kernel`), nunca el BIN.
 */
internal class ArmadoDelMensaje(identidad: Identidad) {

    private val posId = identidad.posId.also { require(POS_ID.matches(it)) { "posId sin forma numérica" } }.toInt()

    /** 9F1E = la serie FÍSICA de la terminal en ASCII-hex, siempre, como la pone producción (piloto, `MainActivity.kt:299`). */
    private val tag9F1E = identidad.serieFisica.also { require(SERIE_FISICA.matches(it)) { "serie física sin forma" } }
        .toByteArray(Charsets.US_ASCII).aHex()

    /** Amex viaja sin DUKPT (la banda en claro dentro del TLS, como `SaleCtlsUseCase` de Blumon) y no gasta contador. */
    fun necesitaDukpt(lectura: Lectura): Boolean = lectura.kernel != Marca.AMEX

    fun venta(id: AttemptId, importeCentavos: Long, lectura: Lectura, dukpt: MaterialDukpt?): CuerpoDeBlumon =
        cuerpo(id, importeCentavos, lectura, dukpt)

    /** La cancelación lleva el mismo cuerpo; la operación va en la ruta (`retail/present/cancel/{op}`). */
    fun cancelacion(id: AttemptId, importeCentavos: Long, lectura: Lectura, dukpt: MaterialDukpt?): CuerpoDeBlumon =
        cuerpo(id, importeCentavos, lectura, dukpt)

    fun reverso(cuerpoDeLaVenta: CuerpoDeBlumon): CuerpoDeBlumon = cuerpoDeLaVenta.conMotivoDeReverso()

    private fun cuerpo(id: AttemptId, importeCentavos: Long, lectura: Lectura, dukpt: MaterialDukpt?): CuerpoDeBlumon {
        val marca = requireNotNull(lectura.kernel) { "lectura sin marca del kernel" }
        require(importeCentavos > 0) { "importe inválido" }
        require((dukpt != null) == necesitaDukpt(lectura)) { "material DUKPT que no corresponde a la marca" }
        val pan = requireNotNull(lectura.pan()) { "banda sin forma" }
        val banda = lectura.tags.getValue(0x57)
        val amex = marca == Marca.AMEX

        var cifrada: String? = null
        var serialKey: String? = null
        if (dukpt != null) {
            val ksn = Dukpt.ksnCon(dukpt.ksnBase, dukpt.contador)
            val llave = Dukpt.llaveDeTransaccion(dukpt.ipek, ksn)
            try { cifrada = Dukpt.cifrarTrack2(llave, banda) } finally { llave.fill(0) }
            serialKey = ksn.aHex()
        }

        val tags = buildString {
            for (tag in if (amex) TAGS_AMEX else TAGS_VISA_MC) {
                // Visa y Amex: su kernel no devuelve el 9F03; va en ceros sólo si falta (un 9F03 que vino nunca se pisa).
                // Tag faltante: Amex lo manda con largo 00, como `GetEmvTagTlvUseCase` de Blumon; Visa y Mastercard lo omiten.
                // 9F1E nunca viene de la lectura: es la serie física de la identidad.
                var valor = (if (tag == 0x9F1E) tag9F1E else lectura.tags[tag])
                    ?: (if (tag == 0x9F03 && (marca == Marca.VISA || marca == Marca.AMEX)) "000000000000" else null)
                    ?: if (amex) "" else continue
                require(valor.isEmpty() || TAG_VALIDO.matches(valor)) { "tag no válido" }
                if (tag == 0x5F2A || tag == 0x9F1A) valor = valor.drop(1)
                // El largo va en decimal y cuenta caracteres hex, no bytes.
                append("%X".format(tag)).append("%02d".format(valor.length)).append(valor)
            }
        }

        return CuerpoDeBlumon.desde(
            JsonObject().apply {
                addProperty("idMembership", "")
                addProperty("posId", posId)
                addProperty("amount", BigDecimal.valueOf(importeCentavos, 2))
                addProperty("reference", id.valor)
                addProperty("currency", "484")
                addProperty("entryMode", "contactless")
                addProperty("authentication", "signature")
                add(
                    "presentCardData",
                    JsonObject().apply {
                        addProperty("track2Length", banda.trimEnd('F').length)
                        addProperty("cardholderName", "")
                        addProperty("emvTags", tags)
                        addProperty("bin", pan.take(6))
                        addProperty("last4", pan.takeLast(4))
                        addProperty("data", pan) // como el SDK de Blumon y el piloto: sólo viaja dentro del TLS
                        addProperty("encryptedData", sha256(banda.take(20)))
                        addProperty("track2", cifrada ?: banda)
                        if (cifrada != null) addProperty("track2Crc32", Dukpt.crc32(cifrada))
                    },
                )
                if (dukpt != null) {
                    add(
                        "dukptData",
                        JsonObject().apply {
                            addProperty("failedCounter", 0)
                            addProperty("realCounter", dukpt.contador)
                            addProperty("serialKey", serialKey)
                        },
                    )
                }
            },
        )
    }

    private fun sha256(texto: String) =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.US_ASCII)).aHex()

    private companion object {
        val POS_ID = Regex("^[0-9]{1,9}$")
        /** ASCII imprimible sin espacios, de 1 a 32 caracteres (la de la PAX medida trae 10 ⇒ 20 hex). */
        val SERIE_FISICA = Regex("^[\\x21-\\x7E]{1,32}$")
        val TAG_VALIDO = Regex("^[0-9A-F]{2,98}$")

        val TAGS_VISA_MC = listOf(
            0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82, 0x9F33, 0x9F34, 0x9A, 0x5F2A, 0x9F02, 0x9F03,
            0x9F35, 0x5F34, 0x9F10, 0x84, 0x9F09, 0x9F1A, 0x95, 0x9F1E, 0x9F6E, 0x50,
        )

        /** La lista Amex K4 de Blumon: 9F71 en vez de 9F34, sin 84, y 9F67 en vez de 9F6E. */
        val TAGS_AMEX = listOf(
            0x9F27, 0x9F26, 0x9F37, 0x9F36, 0x9C, 0x82, 0x9F33, 0x9F71, 0x9A, 0x5F2A, 0x9F02, 0x9F03,
            0x9F35, 0x5F34, 0x9F10, 0x9F09, 0x9F1A, 0x95, 0x9F1E, 0x9F67, 0x50,
        )
    }
}
