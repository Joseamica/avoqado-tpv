package com.avoqado.cobropropio.dominio

import java.util.UUID

/**
 * El id del intento: es a la vez la `reference` de Blumon, la `idempotencyKey` y el `:attemptId` del SERVIDOR, el MISMO texto
 * en los tres. 32 hexadecimales en minúsculas (el UUID v4 sin guiones): Blumon rechaza una `reference` de 36 (`E_000`,
 * medido en el sandbox el 20-nov) y acepta hasta 32 (ruling T8). Nunca trae 12 o más dígitos seguidos (la misma racha que
 * rechaza el lector de Blumon y que tapa `Enmascarar`), así que no puede parecer un PAN ni una banda: el constructor lo exige
 * y [nuevo] vuelve a generar hasta cumplirlo (~1 de cada 30).
 */
@JvmInline
public value class AttemptId(public val valor: String) {
    init {
        require(REGEX.matches(valor) && !RACHA.containsMatchIn(valor)) { "AttemptId sin forma de 32 hexadecimales" }
    }

    public companion object {
        internal val REGEX = Regex("^[0-9a-f]{32}$")
        private val RACHA = Regex("[0-9]{12,}")

        /** UUID v4 en 32 hexadecimales; si sale con una racha de 12 dígitos, se pide otro. */
        public fun nuevo(): AttemptId {
            while (true) {
                val u = UUID.randomUUID()
                val v = "%016x%016x".format(u.mostSignificantBits, u.leastSignificantBits)
                if (!RACHA.containsMatchIn(v)) return AttemptId(v)
            }
        }
    }
}

public enum class Marca { VISA, MASTERCARD, AMEX }
public enum class Medio { PLASTICO, CELULAR }
public enum class Entrada { SIN_CONTACTO, CHIP }
public enum class Operacion { VENTA, CANCELACION }
public enum class Ambiente { SANDBOX, PRODUCCION }

/** Lo que decidió el kernel: sólo `PIDE_BANCO` puede ser una venta; los «sin banco» sólo valen en la cancelación medida. */
public enum class ResultadoKernel { PIDE_BANCO, APROBADA_SIN_BANCO, NEGADA_SIN_BANCO, OTRO }

/** La verificación que pidió el kernel. `DISPOSITIVO` = el teléfono ya verificó al dueño (CVM_CONSUMER_DEVICE de Visa). */
public enum class Cvm { SIN_CVM, DISPOSITIVO, FIRMA, PIN, OTRO }

/**
 * Quién cobra (bloque 4-L · L-b). La sesión con Blumon nace sólo con el MISMO `posId` que devuelve `device/init`: se compara
 * siempre, y el motor exige su forma al armarse (`ArmadoDelMensaje`). [comercio] se compara sólo si viene: `null` = la
 * identidad no lo conoce. La contraseña del token es SHA-256 de [serieBlumon] + `PAX` + [modelo] (con `A910S`, la de
 * siempre). [serieFisica] va al 9F1E.
 */
public data class Identidad(
    val comercio: String?,
    val posId: String,
    val serieFisica: String,
    val serieBlumon: String,
    val modelo: String,
    val versionSdk: String,
    val ambiente: Ambiente,
) {
    /** Dónde vale una sesión, un contador o una venta: la misma caja con la misma serie de Blumon en el mismo ambiente. */
    public fun ambito(): String = "$posId|$serieBlumon|$ambiente"
}

/**
 * Una lectura del kernel sin contacto (o chip). Trae la banda y el criptograma: nunca se imprime (`toString` oculto) y lo
 * único que sale hacia afuera son `bin()`, `ultimos4()` y `paisEmisor()`, con su forma verificada. `tags` va al armado del
 * mensaje y a nada más.
 */
public class Lectura(
    public val kernel: Marca?,
    public val entrada: Entrada,
    public val resultado: ResultadoKernel,
    public val codigoKernel: Int,
    public val cvm: Cvm,
    tags: Map<Int, String>,
) {
    /**
     * Ola final · 2: `internal`, trae la banda (57) con el PAN y el criptograma; el constructor sigue público. `@JvmSynthetic`
     * (ola final, 2ª parte): sin él, Java vería el accesor con sufijo. Igual en `pan()` y `huellaDeLectura()`. 4ª parte (R24-3):
     * una COPIA propia; si el lector reusa y cambia su mapa, el cuerpo y las huellas siguen saliendo de la misma tarjeta.
     */
    @get:JvmSynthetic internal val tags: Map<Int, String> = tags.toMap()

    /** El PAN de la banda 57 (lo que va antes de la `D`), o null si la banda no tiene forma. Sólo para el mensaje y la huella. */
    @JvmSynthetic internal fun pan(): String? = tags[0x57]?.substringBefore('D')?.takeIf { PAN.matches(it) }

    public fun bin(): String? = pan()?.take(6)

    public fun ultimos4(): String? = pan()?.takeLast(4)

    /** El país del emisor que dice la tarjeta (5F28), nunca el nombre del producto del portal. */
    public fun paisEmisor(): String? = tags[0x5F28]?.takeIf { PAIS.matches(it) }

    /** 9F26 + 9F36: distingue una lectura nueva de una repetida. Va a la huella, nunca afuera. */
    @JvmSynthetic internal fun huellaDeLectura(): String? {
        val criptograma = tags[0x9F26] ?: return null
        val contador = tags[0x9F36] ?: return null
        return criptograma + contador
    }

    override fun toString(): String = "Lectura(oculta)"

    private companion object {
        val PAN = Regex("^[0-9]{13,19}$")
        val PAIS = Regex("^[0-9]{3,4}$")
    }
}

public sealed interface ResultadoDeLectura {
    public data class Leida(val lectura: Lectura) : ResultadoDeLectura
    public data class Cortada(val codigoKernel: Int) : ResultadoDeLectura
    public data object Cancelada : ResultadoDeLectura
    public data object Vencida : ResultadoDeLectura
}

public interface LectorDeTarjeta {
    /** `espera` en milisegundos; al vencer regresa `Vencida`. */
    public suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura
}

public interface Red {
    public fun hayRed(): Boolean
}

/**
 * `LOCAL_SIN_SOLICITUD` sólo cuando no hay solicitud del POS (la app lo acredita en la fase 3). En la duda, el llamador
 * manda `VINCULADO_AL_POS`, que es lo seguro.
 */
public enum class Origen { VINCULADO_AL_POS, LOCAL_SIN_SOLICITUD }

/** Lo que entra al motor. Cada folio es de un solo uso (regla N): para reintentar, la app pide uno nuevo. */
public sealed interface Solicitud {
    public val id: AttemptId
    public val venueId: String
}

/** El importe más grande que admite una venta (12 dígitos, el ancho del 9F02): `SolicitudDeVenta` y la admisión lo comparten. */
internal const val IMPORTE_MAXIMO_CENTAVOS = 999_999_999_999L

/**
 * `importeCentavos` es el TOTAL de la tarjeta, con propina, de 1 a [IMPORTE_MAXIMO_CENTAVOS] centavos (D11).
 *
 * @throws IllegalArgumentException si `importeCentavos` está fuera de `1..IMPORTE_MAXIMO_CENTAVOS`: la venta no se construye.
 */
public data class SolicitudDeVenta(
    override val id: AttemptId,
    override val venueId: String,
    val importeCentavos: Long,
    val origen: Origen,
) : Solicitud {
    init {
        require(importeCentavos in 1..IMPORTE_MAXIMO_CENTAVOS) { "importe fuera de rango" }
    }
}

/** La cancelación con la tarjeta de una venta aprobada: `id` es un folio NUEVO; `original`, el de la venta. */
public data class SolicitudDeCancelacion(override val id: AttemptId, override val venueId: String, val original: AttemptId) : Solicitud
