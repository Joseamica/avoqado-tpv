package com.avoqado.cobropropio.admision

import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.IMPORTE_MAXIMO_CENTAVOS
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.ResultadoKernel

internal sealed interface Admision {
    data class Admitida(val combinacion: Combinacion) : Admision
    data class NoAdmitida(val motivo: MotivoNoAdmitida) : Admision
}

/** Sólo el motivo: nunca un texto armado con tags. */
public enum class MotivoNoAdmitida {
    PERFIL_NO_MEDIDO,
    PERFIL_AMBIGUO,
    PIDE_NIP,
    LECTURA_INCOMPLETA,
    IMPORTE_NO_COINCIDE,
    MONEDA_O_PAIS,
    TIPO_DE_OPERACION,
    MARCA_Y_BIN_NO_COINCIDEN,
    CRIPTOGRAMA,
    CHIP_TODAVIA_NO,
}

/**
 * ¿Esta lectura puede ir al banco? Primero las reglas comunes (generalizan `lectorlab/PilotCardPolicy.kt` con el importe
 * real); luego tiene que encajar en exactamente UNA combinación medida (`Perfiles.MEDIDOS`).
 */
internal object TablaDeAdmision {

    /** Largo en caracteres hex de cada tag obligatorio. */
    private val LARGOS = mapOf(0x9F27 to 2, 0x9F26 to 16, 0x9F36 to 4, 0x9F37 to 8, 0x9F02 to 12, 0x9F03 to 12,
        0x5F2A to 4, 0x9F1A to 4, 0x9C to 2, 0x82 to 4, 0x95 to 10, 0x9F34 to 6)

    /** Los kernels de Visa y Amex no devuelven el 9F03 (medido): falta = cero. El armado del mensaje lo manda en ceros. */
    private val SIN_9F03 = setOf(Marca.VISA, Marca.AMEX)

    private const val CERO_12 = "000000000000"
    private const val MEXICO = "0484"
    private val BANDA = Regex("[0-9]{13,19}D[0-9]{7,26}F?")
    private val PAIS_4 = Regex("[0-9]{4}")
    private val HEX_PAR = Regex("([0-9A-F]{2}){1,32}")

    fun evaluar(lectura: Lectura, operacion: Operacion, importeCentavos: Long): Admision =
        evaluar(lectura, operacion, importeCentavos, Perfiles.MEDIDOS)

    internal fun evaluar(lectura: Lectura, operacion: Operacion, importeCentavos: Long, perfiles: List<PerfilMedido>): Admision {
        reglaComun(lectura, operacion, importeCentavos)?.let { return Admision.NoAdmitida(it) }
        val combinaciones = perfiles.filter { encaja(it, lectura, operacion) }.map { it.combinacion }.toSet()
        return when (combinaciones.size) {
            0 -> Admision.NoAdmitida(MotivoNoAdmitida.PERFIL_NO_MEDIDO)
            1 -> Admision.Admitida(combinaciones.single())
            else -> Admision.NoAdmitida(MotivoNoAdmitida.PERFIL_AMBIGUO)
        }
    }

    private fun reglaComun(lectura: Lectura, operacion: Operacion, importeCentavos: Long): MotivoNoAdmitida? {
        val tags = lectura.tags
        if (lectura.entrada == Entrada.CHIP) return MotivoNoAdmitida.CHIP_TODAVIA_NO

        val faltaCero = lectura.kernel in SIN_9F03 && 0x9F03 !in tags
        val bienFormados = LARGOS.all { (tag, largo) ->
            (tag == 0x9F03 && faltaCero) || tags[tag]?.matches(Regex("[0-9A-F]{$largo}")) == true
        }
        if (!bienFormados || tags[0x9F10]?.matches(HEX_PAR) != true) return MotivoNoAdmitida.LECTURA_INCOMPLETA
        val banda = tags[0x57]
        if (banda == null || banda.length > 48 || banda.length % 2 != 0 || !BANDA.matches(banda))
            return MotivoNoAdmitida.LECTURA_INCOMPLETA

        if (importeCentavos !in 1..IMPORTE_MAXIMO_CENTAVOS || tags[0x9F02] != importeCentavos.toString().padStart(12, '0') ||
            (tags[0x9F03] ?: CERO_12) != CERO_12) return MotivoNoAdmitida.IMPORTE_NO_COINCIDE
        if (tags[0x5F2A] != MEXICO || tags[0x9F1A] != MEXICO) return MotivoNoAdmitida.MONEDA_O_PAIS
        if (tags[0x9C] != if (operacion == Operacion.VENTA) "00" else "20") return MotivoNoAdmitida.TIPO_DE_OPERACION

        // pedir banco ⇒ ARQC; resolver sin banco (sólo en la cancelación medida) ⇒ AAC. «Aprobada sin banco» jamás cobra.
        val clase = tags.getValue(0x9F27).toInt(16) and 0xC0
        val coherente = when (lectura.resultado) {
            ResultadoKernel.PIDE_BANCO -> clase == 0x80
            ResultadoKernel.APROBADA_SIN_BANCO, ResultadoKernel.NEGADA_SIN_BANCO -> clase == 0x00
            ResultadoKernel.OTRO -> false
        }
        if (!coherente || tags[0x9F26] == "0".repeat(16) || tags[0x9F37] == "0".repeat(8)) return MotivoNoAdmitida.CRIPTOGRAMA
        if (operacion == Operacion.VENTA && lectura.resultado != ResultadoKernel.PIDE_BANCO) return MotivoNoAdmitida.PERFIL_NO_MEDIDO

        val pan = lectura.pan() ?: return MotivoNoAdmitida.LECTURA_INCOMPLETA
        val coincide = when (lectura.kernel) {
            Marca.VISA -> pan.startsWith('4')
            Marca.AMEX -> pan.length == 15 && pan.take(2) in setOf("34", "37")
            Marca.MASTERCARD -> pan.take(2).toInt() in 51..55 || pan.take(4).toInt() in 2221..2720
            null -> true // sin marca no hay perfil: lo dice PERFIL_NO_MEDIDO
        }
        if (!coincide) return MotivoNoAdmitida.MARCA_Y_BIN_NO_COINCIDEN

        if (lectura.cvm == Cvm.PIN) return MotivoNoAdmitida.PIDE_NIP
        return null
    }

    private fun encaja(perfil: PerfilMedido, lectura: Lectura, operacion: Operacion): Boolean {
        val c = perfil.combinacion
        val tags = lectura.tags
        val tag9F34 = tags.getValue(0x9F34)
        val presentes = { nombres: Set<String> -> nombres.map { it.toInt(16) } }
        // exactamente 4 dígitos: un «484» recortado (como Blumon recorta 5F2A/9F1A) nunca cuenta como extranjero
        val pais = lectura.tags[0x5F28]?.takeIf { PAIS_4.matches(it) }
        return c.marca == lectura.kernel && c.entrada == lectura.entrada && c.operacion == operacion &&
            lectura.resultado in perfil.resultadoKernel && lectura.codigoKernel in perfil.codigoKernel &&
            lectura.cvm in perfil.cvm &&
            (perfil.tag9F34?.contains(tag9F34) ?: sinCvm(tag9F34)) &&
            presentes(perfil.exigeTags).all { it in tags } && presentes(perfil.prohibeTags).none { it in tags } &&
            (!perfil.emisorExtranjero || (pais != null && pais != MEXICO))
    }

    /** 9F34 de «sin CVM»: método 1F (sin importar los bits de control) y resultado 00 o 02. */
    private fun sinCvm(tag9F34: String) = tag9F34.take(2).toInt(16) and 0x3F == 0x1F && tag9F34.takeLast(2) in setOf("00", "02")
}
