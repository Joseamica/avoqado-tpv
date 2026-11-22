package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.aHex
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.Apariciones
import com.avoqado.cobropropio.red.EnvioUnico
import com.avoqado.cobropropio.red.Escalar
import com.avoqado.cobropropio.red.Respuesta
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * El cliente de Blumon sobre `EnvioUnico` (rescate de `lectorlab/BlumonPilot.kt:49-85` y `BlumonSandbox.kt`). La sesión
 * sólo se arma si `device/init` devuelve ESTA identidad con `qps=false`, y si la IPEK cuadra con su KCV.
 */
internal class ClienteBlumon(
    private val endpoints: Endpoints,
    private val envio: EnvioUnico,
    private val credencial: CredencialDelCliente,
    private val identidad: Identidad,
    private val aleatorio: SecureRandom,
) : PuertoBlumon {

    private val basic: String
        get() = "Basic " + Base64.getEncoder()
            .encodeToString("${credencial.usuario}:${credencial.contrasena}".toByteArray(Charsets.UTF_8))

    /**
     * Sin `withTimeout` (R3-4, v5 §4.2): un reloj así puede tirar la sesión ya nacida justo al regresar. El plazo de la cadena
     * es el del transporte: cada POST sale con lo que queda de [espera] y ninguno empieza después.
     */
    override suspend fun iniciarSesion(espera: Duration): SesionBlumon? = sinExcepciones {
        val fin = TimeSource.Monotonic.markNow() + espera
        val token = token(fin) ?: return@sinExcepciones null
        val init = datos(core("device/init", token, json("serialNumber" to identidad.serieBlumon), fin))
            ?: return@sinExcepciones null
        val mismaIdentidad = init.texto("dataResponse", "posId") == identidad.posId &&
            init.texto("dataResponse", "commerceName") == identidad.comercio &&
            init.booleano("dataResponse", "contactless") == true && init.booleano("dataResponse", "initializeKeys") == true &&
            init.booleano("dataResponse", "qps") == false
        if (!mismaIdentidad) return@sinExcepciones null
        llaves(token, identidad.posId, fin)?.sesion
    }

    override suspend fun renovarToken(espera: Duration): TokenDeBlumon? =
        sinExcepciones { token(TimeSource.Monotonic.markNow() + espera)?.let(::TokenDeBlumon) }

    /** Lo que el recorrido del sandbox puede guardar: nada de credencial, token, TK ni IPEK. */
    internal class Descubierto(val posId: String, val comercio: String, val kcvCuadra: Boolean, val baseKsn: String?)

    /**
     * Sólo para el recorrido del sandbox: el `posId` y el comercio que devuelve `device/init` (sin compararlos con la
     * identidad), y el intercambio de llaves con ESE `posId`: sólo si el KCV cuadró y la base KSN canónica. La sesión se borra.
     */
    internal suspend fun descubrir(espera: Duration): Descubierto? = sinExcepciones {
        val fin = TimeSource.Monotonic.markNow() + espera
        val token = token(fin) ?: return@sinExcepciones null
        val init = datos(core("device/init", token, json("serialNumber" to identidad.serieBlumon), fin))
            ?: return@sinExcepciones null
        val posId = init.texto("dataResponse", "posId")?.takeIf { POS_ID.matches(it) } ?: return@sinExcepciones null
        val comercio = init.texto("dataResponse", "commerceName")?.takeIf { COMERCIO.matches(it) } ?: return@sinExcepciones null
        val intercambio = llaves(token, posId, fin)
        intercambio?.sesion?.material?.close()
        Descubierto(posId, comercio, intercambio?.sesion != null, intercambio?.baseKsn)
    }

    override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long) =
        envio.post(endpoints.core + "retail/present/charge", "Bearer ${sesion.token}", cuerpo, espera, noEmpezarDespuesDe)

    override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit) =
        envio.post(
            endpoints.core + "retail/present/reverse/",
            "Bearer ${congelado.token}",
            congelado.cuerpo.conMotivoDeReverso(),
            espera,
            noEmpezarDespuesDe,
            alLeer,
        )

    override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta {
        if (operationNumber !in 1..FOLIO_MAXIMO) return Respuesta.NoEnviada
        val cuerpo = CuerpoDeBlumon.desde(
            JsonObject().apply {
                addProperty("posId", identidad.posId.toInt())
                addProperty("operationNumber", operationNumber)
            },
        )
        return envio.post(endpoints.core + "device/validateCancelation", "Bearer ${sesion.token}", cuerpo, espera, null, alLeer)
    }

    override suspend fun cancelar(
        sesion: SesionBlumon,
        operationNumber: Long,
        cuerpo: CuerpoDeBlumon,
        espera: Duration,
        alLeer: (Respuesta) -> Unit,
    ): Respuesta {
        if (operationNumber !in 1..FOLIO_MAXIMO) return Respuesta.NoEnviada
        return envio.post(endpoints.core + "retail/present/cancel/$operationNumber", "Bearer ${sesion.token}", cuerpo, espera, null, alLeer)
    }

    /** Token: por query en producción y por formulario en el sandbox, como se midió. Un solo `access_token`, en la raíz. */
    private suspend fun token(fin: TimeMark): String? {
        val espera = restante(fin) ?: return null
        val serie = identidad.serieBlumon
        val clave = MessageDigest.getInstance("SHA-256").digest((serie + "PAXA910S").toByteArray(Charsets.US_ASCII)).aHex()
        val r = if (identidad.ambiente == Ambiente.PRODUCCION) {
            val url = endpoints.tokener + "?grant_type=password&username=${enc(serie)}&password=${enc(clave)}"
            envio.post(url, basic, CuerpoDeBlumon.desde(JsonObject()), espera, null)
        } else {
            envio.postFormulario(endpoints.tokener, basic, mapOf("grant_type" to "password", "username" to serie, "password" to clave), espera)
        }
        if (r !is Respuesta.Http || r.codigo !in 200..299) return null
        val a = r.apariciones()?.takeIf { !it.ambigua } ?: return null
        return (a.unica("access_token") as? Escalar.Texto)?.v?.takeIf(TOKEN::matches)
    }

    /** El resultado del intercambio de llaves: la sesión sólo si el KCV cuadró; la base KSN canónica en los dos casos. */
    private class Intercambio(val sesion: SesionBlumon?, val baseKsn: String) {
        override fun toString() = "Intercambio(oculto)"
    }

    /** Sólo pruebas: corre con el material ya nacido, antes de entregarlo (R3-4: ahí no puede quedar suelto). */
    internal var conElMaterialNacido: suspend (MaterialDeSesion) -> Unit = {}

    /**
     * RSA → TK → IPEK con su KCV. La TK se borra siempre. La IPEK nace DENTRO de su [MaterialDeSesion] (sin copia) y éste se
     * cierra en TODA salida que no lo entrega: el KCV que no cuadra, una excepción o una cancelación (R3-4, v5-10).
     */
    private suspend fun llaves(token: String, posId: String, fin: TimeMark): Intercambio? {
        val rsaHex = (datos(core("device/getKey", token, json("posId" to posId), fin))?.unica("dataResponse", "rsa") as? Escalar.Texto)
            ?.v?.takeIf { HEX.matches(it) && it.length % 2 == 0 } ?: return null
        val publica = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(rsaHex.hexABytes()))
        val tk = ByteArray(16).also(aleatorio::nextBytes)
        try {
            val cifrada = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publica) }.doFinal(tk).aHex()
            val pedido = json("posId" to posId, "rsa" to cifrada, "checkValue" to Dukpt.kcv(tk), "crc32" to Dukpt.crc32(cifrada))
            val m = datos(core("device/initDukptKeys", token, pedido, fin)) ?: return null
            val llave = m.texto("dataResponse", "key")?.takeIf { LLAVE.matches(it) } ?: return null
            val ksn = m.texto("dataResponse", "ksn")?.takeIf { KSN.matches(it) }?.hexABytes() ?: return null
            val base = Dukpt.baseCanonica(ksn)
            val kcv = m.texto("dataResponse", "keyCheckValue")?.takeIf { KCV.matches(it) } ?: return Intercambio(null, base)
            val material = MaterialDeSesion(Dukpt.tdes(tk, llave.hexABytes(), cifrar = false), base.hexABytes())
            try {
                conElMaterialNacido(material)
                val cuadra = material.usar { ipek, _ -> Dukpt.kcv(ipek).equals(kcv, ignoreCase = true) } == true
                if (!cuadra) {
                    material.close()
                    return Intercambio(null, base)
                }
                val contador = ((ksn[7].toInt() and 0x1F) shl 16) or ((ksn[8].toInt() and 0xFF) shl 8) or (ksn[9].toInt() and 0xFF)
                return Intercambio(SesionBlumon(token, material, base, contador), base)
            } catch (t: Throwable) {
                material.close()
                throw t
            }
        } finally {
            tk.fill(0)
        }
    }

    private suspend fun core(ruta: String, token: String, cuerpo: CuerpoDeBlumon, fin: TimeMark): Respuesta {
        val espera = restante(fin) ?: return Respuesta.NoEnviada
        return envio.post(endpoints.core + ruta, "Bearer $token", cuerpo, espera, null)
    }

    /** Lo que queda hasta [fin], o `null` si ya venció. */
    private fun restante(fin: TimeMark): Duration? = (-fin.elapsedNow()).takeIf { it.isPositive() }

    /** Un 2xx, sin claves repetidas y con `status=true` (único, en la raíz); lo demás da `null`. */
    private fun datos(r: Respuesta): Apariciones? {
        if (r !is Respuesta.Http || r.codigo !in 200..299) return null
        val a = r.apariciones()?.takeIf { !it.ambigua } ?: return null
        return a.takeIf { (it.unica("status") as? Escalar.Booleano)?.v == true }
    }

    /** Ninguna excepción sale (sus mensajes pueden traer datos); la cancelación de quien llama sí se respeta. */
    private suspend fun <T> sinExcepciones(bloque: suspend () -> T?): T? = try {
        bloque()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun json(vararg campos: Pair<String, String>) =
        CuerpoDeBlumon.desde(JsonObject().apply { campos.forEach { (k, v) -> addProperty(k, v) } })

    /** El valor ÚNICO de [ruta], texto o número como texto. */
    private fun Apariciones.texto(vararg ruta: String): String? = when (val e = unica(*ruta)) {
        is Escalar.Texto -> e.v
        is Escalar.Numero -> e.crudo
        else -> null
    }

    private fun Apariciones.booleano(vararg ruta: String): Boolean? = (unica(*ruta) as? Escalar.Booleano)?.v

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val FOLIO_MAXIMO = 9_999_999_999L
        val HEX = Regex("^[0-9A-Fa-f]+$")
        val LLAVE = Regex("^[0-9A-Fa-f]{32}$")
        val KSN = Regex("^[0-9A-Fa-f]{20}$")
        val KCV = Regex("^[0-9A-Fa-f]{6}$")
        val POS_ID = Regex("^[0-9]{1,9}$")
        val COMERCIO = Regex("^[\\x20-\\x7E]{1,64}$")
        val TOKEN = Regex("^[A-Za-z0-9._~+/=-]{1,4096}$")
    }
}
