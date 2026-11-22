package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.red.Respuesta
import java.io.Closeable
import kotlin.time.Duration

/** La credencial del cliente de Blumon (Basic del tokener). Se inyecta; nunca vive en el código ni se imprime. */
public class CredencialDelCliente(@get:JvmSynthetic internal val usuario: String, @get:JvmSynthetic internal val contrasena: String) {
    override fun toString(): String = "CredencialDelCliente(oculta)"
}

/**
 * La IPEK y la base KSN de una sesión, con UN dueño (R2-5, R3-4; v5 §4.1). Se queda los arreglos sin copiarlos: quien los
 * crea se los entrega en la misma línea. [close] los pone en ceros y suelta las referencias; [usar] los presta sin que el
 * bloque se quede con ellos. Comparten el monitor: un cierre nunca deja a medias una copia en curso.
 */
internal class MaterialDeSesion internal constructor(ipek: ByteArray, ksnBase: ByteArray) : Closeable {
    private var ipek: ByteArray? = ipek
    private var ksn: ByteArray? = ksnBase

    /** `null` si ya se cerró. */
    @JvmSynthetic @Synchronized internal fun <T> usar(bloque: (ipek: ByteArray, ksnBase: ByteArray) -> T): T? {
        val i = ipek ?: return null
        return bloque(i, ksn!!)
    }

    @Synchronized override fun close() {
        ipek?.fill(0)
        ksn?.fill(0)
        ipek = null
        ksn = null
    }

    override fun toString() = "MaterialDeSesion(oculto)"
}

/**
 * Una sesión de Blumon: el token y el material verificado con su KCV, sólo en RAM. `ksnBaseCanonica` es la base (contador en
 * cero, no es secreta: va en la libreta) y `ultimoContadorDeBlumon` el contador del KSN que devolvió `initDukptKeys`.
 * Renovar el token ([conToken]) reúsa el MISMO material: ni lo copia ni lo cierra (v4-8).
 */
internal class SesionBlumon internal constructor(
    @get:JvmSynthetic internal val token: String,
    internal val material: MaterialDeSesion,
    internal val ksnBaseCanonica: String,
    internal val ultimoContadorDeBlumon: Int,
) {
    /** El material se queda los arreglos (sin copia). */
    internal constructor(token: String, ipek: ByteArray, ksnBase: ByteArray, ultimoContadorDeBlumon: Int) :
        this(token, MaterialDeSesion(ipek, ksnBase), Dukpt.baseCanonica(ksnBase), ultimoContadorDeBlumon)

    internal fun conToken(nuevo: String) = SesionBlumon(nuevo, material, ksnBaseCanonica, ultimoContadorDeBlumon)

    override fun toString() = "SesionBlumon(oculta)"
}

/** El cuerpo EXACTO de la venta como salió y el token con que salió: lo único con que se arma su reverso. Sólo en RAM. */
internal class CuerpoCongelado(@get:JvmSynthetic internal val cuerpo: CuerpoDeBlumon, @get:JvmSynthetic internal val token: String) {
    override fun toString() = "CuerpoCongelado(oculto)"
}

/**
 * Ola final, 3ª parte: el `access_token` de Blumon, opaco. Es lo que [PuertoBlumon.renovarToken] entrega a quien esté fuera
 * del módulo; sólo el módulo lee [valor], y Java tampoco lo ve.
 */
internal class TokenDeBlumon internal constructor(@get:JvmSynthetic internal val valor: String) {
    override fun toString() = "TokenDeBlumon(oculto)"
}

internal interface PuertoBlumon {
    /** `null` si no coinciden `posId` y comercio, si `qps != false`, si el KCV no cuadra, si algo falla o si vence `espera`. */
    suspend fun iniciarSesion(espera: Duration): SesionBlumon?

    /** Sólo el tokener (`grant_type=password`): un token nuevo, sin init ni llaves. `null` si algo falla o vence `espera`. */
    suspend fun renovarToken(espera: Duration): TokenDeBlumon?

    suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta

    /**
     * Reenvía el cuerpo congelado de la venta + motivo + `sendReverse`, con el token congelado. [alLeer] recibe la respuesta
     * en el instante en que el transporte la lee, ANTES de reanudar a quien llamó (R3-2): un reloj de afuera que vence
     * después ya no la puede tirar.
     */
    suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta

    /** El preflight de la cancelación. [alLeer], como en [reversar]: lo leído llega antes de reanudar a quien llamó. */
    suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit = {}): Respuesta

    /** La cancelación con la tarjeta. [alLeer], como en [reversar]. */
    suspend fun cancelar(
        sesion: SesionBlumon,
        operationNumber: Long,
        cuerpo: CuerpoDeBlumon,
        espera: Duration,
        alLeer: (Respuesta) -> Unit = {},
    ): Respuesta
}
