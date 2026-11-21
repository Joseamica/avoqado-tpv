package com.avoqado.cobropropio.fixtures

import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.ResultadoKernel

/**
 * Lecturas SINTÉTICAS para las pruebas (nunca datos de una tarjeta real): PAN de prueba públicos y criptogramas
 * inventados. La FORMA de cada una (resultado del kernel, código, CVM, 9F34, 9F27, 9C, tags que trae o no) es la medida
 * con $1 real el 9 y 10-nov en la PAX (`lectorlab/PilotCardPolicyTest.kt`, `BlumonPilotTest.kt` y la memoria
 * `sdk-propio-tpv-con-apis-de-blumon.md`). Compartidas por las tareas que siguen.
 */
object LecturasDePrueba {

    const val PAN_VISA = "4111111111111111"
    const val PAN_MASTERCARD = "5555555555554444"
    const val PAN_AMEX = "378282246310005"
    const val PESO = 100L

    /** Banda 57 como la entrega el kernel: PAN, `D`, vencimiento y servicio, y una `F` si queda de largo impar. */
    fun banda(pan: String): String = (pan + "D2912101" + "0".repeat(11)).let { if (it.length % 2 == 1) it + "F" else it }

    private fun tags(pan: String, importeCentavos: Long, cancelacion: Boolean, arqc: Boolean, tag9F34: String) = mapOf(
        0x57 to banda(pan),
        0x9F27 to if (arqc) "80" else "00",
        0x9F26 to "A1B2C3D4E5F60718",
        0x9F36 to "0001",
        0x9F37 to "A1B2C3D4",
        0x9F02 to importeCentavos.toString().padStart(12, '0'),
        0x9F03 to "000000000000",
        0x5F2A to "0484",
        0x9F1A to "0484",
        0x5F28 to "0484",
        0x9C to if (cancelacion) "20" else "00",
        0x82 to "2000",
        0x95 to "0000000000",
        0x9F10 to "06010A03A00000",
        0x9F34 to tag9F34,
    )

    private fun lectura(kernel: Marca, resultado: ResultadoKernel, codigo: Int, cvm: Cvm, tags: Map<Int, String>) =
        Lectura(kernel, Entrada.SIN_CONTACTO, resultado, codigo, cvm, tags)

    // ── Visa: el módulo EMV de PAX rellena el 9F34 con 1E0302 fijo y el kernel no devuelve el 9F03 ──
    private fun visa(importe: Long, cancelacion: Boolean, arqc: Boolean) =
        tags(PAN_VISA, importe, cancelacion, arqc, "1E0302") - 0x9F03

    fun visaPlasticoVenta(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, visa(importe, false, true))

    /** Visa mexicana de plástico al cancelar: «aprobada sin banco» con AAC, y sí se manda en línea (9-nov). */
    fun visaPlasticoCancelacion(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.APROBADA_SIN_BANCO, 0, Cvm.SIN_CVM, visa(importe, true, false))

    /** Visa extranjera de plástico al cancelar: pide banco con ARQC; 5F28 extranjero y 9F1A 0484 (10-nov, «**** 5159»). */
    fun visaPlasticoExtranjeraCancelacion(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, visa(importe, true, true) + (0x5F28 to "0840"))

    /** Visa en Apple Pay: el kernel avisa que el teléfono verificó (CVM_CONSUMER_DEVICE). */
    fun visaCelularVenta(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.PIDE_BANCO, 0, Cvm.DISPOSITIVO, visa(importe, false, true))

    /** Visa en Apple Pay al cancelar: CLSS_DECLINE -27, «denegada» y AAC, con todos los tags (10-nov). */
    fun visaCelularCancelacion(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.NEGADA_SIN_BANCO, -27, Cvm.DISPOSITIVO, visa(importe, true, false))

    // ── Mastercard: sí devuelve el 9F03; en el celular el 9F34 trae 3F0002 ──
    private fun mc(importe: Long, cancelacion: Boolean, arqc: Boolean, tag9F34: String) =
        tags(PAN_MASTERCARD, importe, cancelacion, arqc, tag9F34)

    fun mastercardPlasticoVenta(importe: Long = PESO) =
        lectura(Marca.MASTERCARD, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, mc(importe, false, true, "1F0002"))

    /** Mastercard al cancelar: «denegada» con AAC y código 0 (el módulo de PAX pide el AAC a propósito, 10-nov). */
    fun mastercardPlasticoCancelacion(importe: Long = PESO) =
        lectura(Marca.MASTERCARD, ResultadoKernel.NEGADA_SIN_BANCO, 0, Cvm.SIN_CVM, mc(importe, true, false, "1F0002"))

    fun mastercardCelularVenta(importe: Long = PESO) =
        lectura(Marca.MASTERCARD, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, mc(importe, false, true, "3F0002"))

    fun mastercardCelularCancelacion(importe: Long = PESO) =
        lectura(Marca.MASTERCARD, ResultadoKernel.NEGADA_SIN_BANCO, 0, Cvm.SIN_CVM, mc(importe, true, false, "3F0002"))

    // ── Amex: ARQC en venta y en cancelación, sin 9F03; el plástico trae 9F67 y el celular trae 9F71 sin 9F67 ──
    private fun amex(importe: Long, cancelacion: Boolean, celular: Boolean) =
        tags(PAN_AMEX, importe, cancelacion, true, "1F0002") - 0x9F03 + (0x84 to "A000000025010801") + (0x9F6E to "20700000") +
            if (celular) mapOf(0x9F71 to "010203") else mapOf(0x9F67 to "010203")

    fun amexPlasticoVenta(importe: Long = PESO) =
        lectura(Marca.AMEX, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, amex(importe, false, false))

    fun amexPlasticoCancelacion(importe: Long = PESO) =
        lectura(Marca.AMEX, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, amex(importe, true, false))

    fun amexCelularVenta(importe: Long = PESO) =
        lectura(Marca.AMEX, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, amex(importe, false, true) - 0x9F67)

    fun amexCelularCancelacion(importe: Long = PESO) =
        lectura(Marca.AMEX, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, amex(importe, true, true) - 0x9F67)

    // ── Sin medir o ambiguas ──

    /** Visa mexicana que pide banco al cancelar: no se midió (la mexicana da «aprobada sin banco»). */
    fun visaPlasticoMexicanaCancelacionPideBanco(importe: Long = PESO) =
        lectura(Marca.VISA, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, visa(importe, true, true))

    /** Amex que trae 9F71 y 9F67 a la vez: no encaja ni con el plástico ni con el celular medidos. */
    fun amexConLasDosSenales(importe: Long = PESO) =
        lectura(Marca.AMEX, ResultadoKernel.PIDE_BANCO, 0, Cvm.SIN_CVM, amex(importe, false, false) + (0x9F71 to "010203"))

    /** Una copia con lo que cambie; los tags se pisan o se quitan (`sin`). */
    fun Lectura.con(
        kernel: Marca? = this.kernel,
        entrada: Entrada = this.entrada,
        resultado: ResultadoKernel = this.resultado,
        codigoKernel: Int = this.codigoKernel,
        cvm: Cvm = this.cvm,
        tags: Map<Int, String> = emptyMap(),
        sin: Set<Int> = emptySet(),
    ) = Lectura(kernel, entrada, resultado, codigoKernel, cvm, this.tags + tags - sin)
}
