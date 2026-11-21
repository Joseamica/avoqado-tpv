package com.avoqado.cobropropio.admision

import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.ResultadoKernel

internal data class Combinacion(val marca: Marca, val medio: Medio, val entrada: Entrada, val operacion: Operacion)

/**
 * La forma exacta en que llegó una lectura medida con $1 real. Una lectura entra si encaja en TODO:
 * - `tag9F34`: null = el 9F34 tiene que decir «sin CVM» (primer byte & 3F = 1F, último 00 o 02); un conjunto = sólo esos;
 * - `exigeTags` / `prohibeTags`: tags que tienen que venir o no venir (así se separa la Amex del celular del plástico);
 * - `emisorExtranjero`: el 5F28 tiene que venir y no ser 0484 (la Visa extranjera que pide banco al cancelar).
 */
internal data class PerfilMedido(
    val combinacion: Combinacion,
    val resultadoKernel: Set<ResultadoKernel>,
    val codigoKernel: Set<Int>,
    val cvm: Set<Cvm>,
    val tag9F34: Set<String>?,
    val exigeTags: Set<String>,
    val prohibeTags: Set<String>,
    val emisorExtranjero: Boolean = false,
)

internal object Perfiles {

    /** El módulo EMV de PAX rellena el 9F34 de Visa con este valor fijo (`ClssPayWaveProcess.getTlv`); no viene de la tarjeta. */
    private val RELLENO_VISA = setOf("1E0302")

    /** Mastercard en Apple Pay: «la terminal no hizo CVM, resultado exitoso». */
    private val MASTERCARD_CELULAR = setOf("3F0002")

    /** CLSS_DECLINE: la cancelación de una Visa dentro del celular (10-nov). El único código ≠ 0 que se manda. */
    private const val CLSS_DECLINE = -27

    private val PIDE_BANCO = setOf(ResultadoKernel.PIDE_BANCO)
    private val SIN_CVM = setOf(Cvm.SIN_CVM)
    private val CERO = setOf(0)

    private fun perfil(
        marca: Marca, medio: Medio, operacion: Operacion,
        resultado: Set<ResultadoKernel> = PIDE_BANCO, codigo: Set<Int> = CERO, cvm: Set<Cvm> = SIN_CVM,
        tag9F34: Set<String>? = null, exige: Set<String> = emptySet(), prohibe: Set<String> = emptySet(),
        extranjero: Boolean = false,
    ) = PerfilMedido(Combinacion(marca, medio, Entrada.SIN_CONTACTO, operacion), resultado, codigo, cvm, tag9F34, exige, prohibe,
        extranjero)

    /**
     * Las 13 filas medidas con $1 real, sin contacto y sin NIP (9 y 10-nov, cruzadas en ELEMENT; DEC §4 y LAB §4 5 y 7).
     * Una lectura que no encaja en exactamente una no sale al banco.
     */
    val MEDIDOS: List<PerfilMedido> = listOf(
        // Visa plástico (9-nov): venta con ARQC; la mexicana cancela «aprobada sin banco» con AAC y la extranjera pide banco.
        perfil(Marca.VISA, Medio.PLASTICO, Operacion.VENTA, tag9F34 = RELLENO_VISA),
        perfil(Marca.VISA, Medio.PLASTICO, Operacion.CANCELACION, resultado = setOf(ResultadoKernel.APROBADA_SIN_BANCO),
            tag9F34 = RELLENO_VISA),
        perfil(Marca.VISA, Medio.PLASTICO, Operacion.CANCELACION, tag9F34 = RELLENO_VISA, extranjero = true),
        // Visa en Apple Pay (10-nov): el kernel avisa CVM_CONSUMER_DEVICE; al cancelar, -27 + «denegada» + AAC.
        perfil(Marca.VISA, Medio.CELULAR, Operacion.VENTA, cvm = setOf(Cvm.DISPOSITIVO), tag9F34 = RELLENO_VISA),
        perfil(Marca.VISA, Medio.CELULAR, Operacion.CANCELACION, resultado = setOf(ResultadoKernel.NEGADA_SIN_BANCO),
            codigo = setOf(CLSS_DECLINE), cvm = setOf(Cvm.SIN_CVM, Cvm.DISPOSITIVO), tag9F34 = RELLENO_VISA),
        // Mastercard (10-nov): cancela «denegada» con AAC y código 0; en el celular el 9F34 trae 3F0002.
        perfil(Marca.MASTERCARD, Medio.PLASTICO, Operacion.VENTA),
        perfil(Marca.MASTERCARD, Medio.PLASTICO, Operacion.CANCELACION, resultado = setOf(ResultadoKernel.NEGADA_SIN_BANCO)),
        perfil(Marca.MASTERCARD, Medio.CELULAR, Operacion.VENTA, tag9F34 = MASTERCARD_CELULAR),
        perfil(Marca.MASTERCARD, Medio.CELULAR, Operacion.CANCELACION, resultado = setOf(ResultadoKernel.NEGADA_SIN_BANCO),
            tag9F34 = MASTERCARD_CELULAR),
        // Amex (10-nov): ARQC en venta y en cancelación; el plástico trae 9F67 y no 9F71, el celular al revés.
        perfil(Marca.AMEX, Medio.PLASTICO, Operacion.VENTA, exige = setOf("9F67"), prohibe = setOf("9F71")),
        perfil(Marca.AMEX, Medio.PLASTICO, Operacion.CANCELACION, exige = setOf("9F67"), prohibe = setOf("9F71")),
        perfil(Marca.AMEX, Medio.CELULAR, Operacion.VENTA, exige = setOf("9F71"), prohibe = setOf("9F67")),
        perfil(Marca.AMEX, Medio.CELULAR, Operacion.CANCELACION, exige = setOf("9F71"), prohibe = setOf("9F67")),
    )

    /** Sólo documentación: lo que todavía NO se admite y cómo entra (lectura que no sale → regla → revisión → $1 real). */
    val PENDIENTES: List<String> = listOf(
        "Google Pay: hoy entra como CELULAR si presenta el perfil de Apple Pay (el kernel no lo distingue); " +
            "se mide con \$1 real en la fase 4, antes de prender cualquier terminal",
        "Chip de cualquier marca (ARPC y segunda generación): sin \$1 real por chip no se admite",
        "Carnet: falta saber qué kernel lo lee y medirlo con \$1 real",
        "Vales (Visa, Mastercard o Carnet de débito): \$1 real con un vale",
        "NIP, banda, meses sin intereses y montos arriba del límite sin CVM",
    )
}
