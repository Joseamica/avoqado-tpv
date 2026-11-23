package com.avoqado.cobropropio.admision

import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.ResultadoKernel
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.con
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TablaDeAdmisionTest {

    private val venta = Operacion.VENTA
    private val cancelacion = Operacion.CANCELACION

    private fun admite(lectura: Lectura, marca: Marca, medio: Medio, operacion: Operacion, importe: Long = 100) =
        assertEquals(Admision.Admitida(Combinacion(marca, medio, Entrada.SIN_CONTACTO, operacion)),
            TablaDeAdmision.evaluar(lectura, operacion, importe))

    private fun niega(motivo: MotivoNoAdmitida, lectura: Lectura, operacion: Operacion = venta, importe: Long = 100) =
        assertEquals(Admision.NoAdmitida(motivo), TablaDeAdmision.evaluar(lectura, operacion, importe))

    // ── Las 13 combinaciones medidas con $1 real ──

    @Test fun `visa plastico venta`() = admite(LecturasDePrueba.visaPlasticoVenta(), Marca.VISA, Medio.PLASTICO, venta)
    @Test fun `visa plastico cancelacion`() =
        admite(LecturasDePrueba.visaPlasticoCancelacion(), Marca.VISA, Medio.PLASTICO, cancelacion)
    @Test fun `visa plastico extranjera cancelacion pide banco`() =
        admite(LecturasDePrueba.visaPlasticoExtranjeraCancelacion(), Marca.VISA, Medio.PLASTICO, cancelacion)
    @Test fun `visa celular venta`() = admite(LecturasDePrueba.visaCelularVenta(), Marca.VISA, Medio.CELULAR, venta)
    @Test fun `visa celular cancelacion con codigo -27`() =
        admite(LecturasDePrueba.visaCelularCancelacion(), Marca.VISA, Medio.CELULAR, cancelacion)
    @Test fun `mastercard plastico venta`() =
        admite(LecturasDePrueba.mastercardPlasticoVenta(), Marca.MASTERCARD, Medio.PLASTICO, venta)
    @Test fun `mastercard plastico cancelacion`() =
        admite(LecturasDePrueba.mastercardPlasticoCancelacion(), Marca.MASTERCARD, Medio.PLASTICO, cancelacion)
    @Test fun `mastercard celular venta`() =
        admite(LecturasDePrueba.mastercardCelularVenta(), Marca.MASTERCARD, Medio.CELULAR, venta)
    @Test fun `mastercard celular cancelacion`() =
        admite(LecturasDePrueba.mastercardCelularCancelacion(), Marca.MASTERCARD, Medio.CELULAR, cancelacion)
    @Test fun `amex plastico venta`() = admite(LecturasDePrueba.amexPlasticoVenta(), Marca.AMEX, Medio.PLASTICO, venta)
    @Test fun `amex plastico cancelacion`() =
        admite(LecturasDePrueba.amexPlasticoCancelacion(), Marca.AMEX, Medio.PLASTICO, cancelacion)
    @Test fun `amex celular venta`() = admite(LecturasDePrueba.amexCelularVenta(), Marca.AMEX, Medio.CELULAR, venta)
    @Test fun `amex celular cancelacion`() =
        admite(LecturasDePrueba.amexCelularCancelacion(), Marca.AMEX, Medio.CELULAR, cancelacion)

    @Test fun `la tabla trae los 13 perfiles medidos y el importe real`() {
        assertEquals(13, Perfiles.MEDIDOS.size)
        admite(LecturasDePrueba.mastercardPlasticoVenta(12_345), Marca.MASTERCARD, Medio.PLASTICO, venta, 12_345)
        // Mastercard sí devuelve el 9F03: si falta, la lectura está incompleta (Visa y Amex nunca lo traen)
        niega(MotivoNoAdmitida.LECTURA_INCOMPLETA, LecturasDePrueba.mastercardPlasticoVenta().con(sin = setOf(0x9F03)))
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F03 to "000000000001")))
    }

    // ── Negativas ──

    @Test fun `lectura que no encaja en ningun perfil no se admite`() {
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.visaPlasticoMexicanaCancelacionPideBanco(), cancelacion)
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.amexConLasDosSenales())
        // sin marca conocida (Discover, JCB, Carnet…): no hay perfil
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.mastercardPlasticoVenta().con(kernel = null))
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.visaPlasticoVenta().con(cvm = Cvm.FIRMA))
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.visaPlasticoVenta().con(codigoKernel = -1))
        // el -27 sólo vale en la cancelación de Visa en el celular
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.mastercardCelularCancelacion().con(codigoKernel = -27), cancelacion)
        // el 3F0002 de Mastercard en el celular no es señal en Visa, ni el CVM del teléfono en Mastercard
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F34 to "3F0002")))
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, LecturasDePrueba.mastercardPlasticoVenta().con(cvm = Cvm.DISPOSITIVO))
        // la Visa extranjera exige un 5F28 que no sea el de México
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO,
            LecturasDePrueba.visaPlasticoExtranjeraCancelacion().con(sin = setOf(0x5F28)), cancelacion)
        // y falla cerrado: un 5F28 que no son 4 dígitos (el «484» de México recortado) nunca cuenta como extranjero
        for (pais in listOf("484", "840", "08400", "084A"))
            niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO,
                LecturasDePrueba.visaPlasticoExtranjeraCancelacion().con(tags = mapOf(0x5F28 to pais)), cancelacion)
    }

    @Test fun `lectura ambigua entre plastico y celular no se admite`() {
        val lectura = LecturasDePrueba.amexPlasticoVenta().con(sin = setOf(0x9F67))
        val plastico = PerfilMedido(Combinacion(Marca.AMEX, Medio.PLASTICO, Entrada.SIN_CONTACTO, venta),
            setOf(ResultadoKernel.PIDE_BANCO), setOf(0), setOf(Cvm.SIN_CVM), null, emptySet(), emptySet())
        val celular = plastico.copy(combinacion = plastico.combinacion.copy(medio = Medio.CELULAR))
        assertEquals(Admision.NoAdmitida(MotivoNoAdmitida.PERFIL_AMBIGUO),
            TablaDeAdmision.evaluar(lectura, venta, 100, listOf(plastico, celular)))
        // dos perfiles del mismo medio no son ambigüedad
        assertEquals(Admision.Admitida(plastico.combinacion),
            TablaDeAdmision.evaluar(lectura, venta, 100, listOf(plastico, plastico.copy(codigoKernel = setOf(0, -2)))))
        // y la tabla real no tiene dos perfiles que se pisen con las lecturas medidas
        niega(MotivoNoAdmitida.PERFIL_NO_MEDIDO, lectura)
    }

    @Test fun `chip no se admite todavia`() {
        niega(MotivoNoAdmitida.CHIP_TODAVIA_NO, LecturasDePrueba.visaPlasticoVenta().con(entrada = Entrada.CHIP))
        niega(MotivoNoAdmitida.CHIP_TODAVIA_NO, LecturasDePrueba.amexPlasticoCancelacion().con(entrada = Entrada.CHIP), cancelacion)
    }

    @Test fun `cvm pin no se admite`() {
        niega(MotivoNoAdmitida.PIDE_NIP, LecturasDePrueba.visaPlasticoVenta().con(cvm = Cvm.PIN))
        niega(MotivoNoAdmitida.PIDE_NIP, LecturasDePrueba.mastercardCelularVenta().con(cvm = Cvm.PIN))
        niega(MotivoNoAdmitida.PIDE_NIP, LecturasDePrueba.visaCelularCancelacion().con(cvm = Cvm.PIN), cancelacion)
    }

    @Test fun `importe distinto no se admite`() {
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.visaPlasticoVenta(), importe = 200)
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.amexPlasticoVenta(12_345), importe = 12_346)
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.visaPlasticoVenta(), importe = 0)
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.visaPlasticoVenta(), importe = -100)
        niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, LecturasDePrueba.mastercardPlasticoVenta().con(tags = mapOf(0x9F03 to "000000000001")))
    }

    /**
     * Bloque 4-L · L-b: el laboratorio cobra montos distintos de $1 (en la PAX, hasta $100 con la tarjeta de laboratorio).
     * `TablaDeAdmision` es `internal` y la app de laboratorio no la puede preguntar: se afirma aquí. Las seis ventas medidas,
     * Amex incluida, se admiten con el importe REAL de la venta; con el 9F02 de otro importe, no.
     */
    @Test fun `las seis ventas medidas se admiten con importes distintos de un peso`() {
        val ventas: List<Triple<Marca, Medio, (Long) -> Lectura>> = listOf(
            Triple(Marca.VISA, Medio.PLASTICO, LecturasDePrueba::visaPlasticoVenta),
            Triple(Marca.VISA, Medio.CELULAR, LecturasDePrueba::visaCelularVenta),
            Triple(Marca.MASTERCARD, Medio.PLASTICO, LecturasDePrueba::mastercardPlasticoVenta),
            Triple(Marca.MASTERCARD, Medio.CELULAR, LecturasDePrueba::mastercardCelularVenta),
            Triple(Marca.AMEX, Medio.PLASTICO, LecturasDePrueba::amexPlasticoVenta),
            Triple(Marca.AMEX, Medio.CELULAR, LecturasDePrueba::amexCelularVenta),
        )
        for ((marca, medio, lectura) in ventas) for (importe in listOf(1L, 99L, 101L, 2_550L, 10_000L, 999_999_999_999L)) {
            admite(lectura(importe), marca, medio, venta, importe)
            niega(MotivoNoAdmitida.IMPORTE_NO_COINCIDE, lectura(importe), importe = importe + 1)
        }
    }

    @Test fun `moneda distinta no se admite`() {
        niega(MotivoNoAdmitida.MONEDA_O_PAIS, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x5F2A to "0840")))
        niega(MotivoNoAdmitida.MONEDA_O_PAIS, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F1A to "0840")))
        niega(MotivoNoAdmitida.MONEDA_O_PAIS,
            LecturasDePrueba.visaPlasticoExtranjeraCancelacion().con(tags = mapOf(0x9F1A to "0840")), cancelacion)
    }

    @Test fun `venta exige 9C 00 y cancelacion 9C 20`() {
        niega(MotivoNoAdmitida.TIPO_DE_OPERACION, LecturasDePrueba.amexPlasticoCancelacion(), venta)
        niega(MotivoNoAdmitida.TIPO_DE_OPERACION, LecturasDePrueba.amexPlasticoVenta(), cancelacion)
        niega(MotivoNoAdmitida.TIPO_DE_OPERACION, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9C to "09")))
    }

    @Test fun `venta con APROBADA_SIN_BANCO no se admite`() {
        val visa = LecturasDePrueba.visaPlasticoCancelacion().con(tags = mapOf(0x9C to "00"))
        assertFalse(TablaDeAdmision.evaluar(visa, venta, 100) is Admision.Admitida)
        val mc = LecturasDePrueba.mastercardPlasticoCancelacion().con(tags = mapOf(0x9C to "00"))
        assertFalse(TablaDeAdmision.evaluar(mc, venta, 100) is Admision.Admitida)
        // y una venta que pide banco con AAC tampoco: pedir banco exige ARQC
        niega(MotivoNoAdmitida.CRIPTOGRAMA, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F27 to "00")))
        niega(MotivoNoAdmitida.CRIPTOGRAMA, LecturasDePrueba.visaPlasticoCancelacion().con(tags = mapOf(0x9F27 to "80")), cancelacion)
        niega(MotivoNoAdmitida.CRIPTOGRAMA, LecturasDePrueba.visaPlasticoVenta().con(resultado = ResultadoKernel.OTRO))
    }

    @Test fun `kernel visa con bin 5 no se admite`() {
        niega(MotivoNoAdmitida.MARCA_Y_BIN_NO_COINCIDEN, LecturasDePrueba.mastercardPlasticoVenta().con(kernel = Marca.VISA))
        niega(MotivoNoAdmitida.MARCA_Y_BIN_NO_COINCIDEN, LecturasDePrueba.visaPlasticoVenta().con(kernel = Marca.AMEX))
        niega(MotivoNoAdmitida.MARCA_Y_BIN_NO_COINCIDEN, LecturasDePrueba.amexPlasticoVenta()
            .con(kernel = Marca.MASTERCARD, tags = mapOf(0x9F03 to "000000000000")))
        niega(MotivoNoAdmitida.MARCA_Y_BIN_NO_COINCIDEN, LecturasDePrueba.amexPlasticoVenta()
            .con(tags = mapOf(0x57 to LecturasDePrueba.banda("3782822463100055"))))
    }

    @Test fun `9F26 en ceros no se admite`() {
        niega(MotivoNoAdmitida.CRIPTOGRAMA, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F26 to "0".repeat(16))))
        niega(MotivoNoAdmitida.CRIPTOGRAMA, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F37 to "0".repeat(8))))
        niega(MotivoNoAdmitida.LECTURA_INCOMPLETA, LecturasDePrueba.visaPlasticoVenta().con(sin = setOf(0x9F26)))
        niega(MotivoNoAdmitida.LECTURA_INCOMPLETA, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F10 to "06010A03A0000")))
        niega(MotivoNoAdmitida.LECTURA_INCOMPLETA, LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x57 to LecturasDePrueba.PAN_VISA)))
    }

    @Test fun `NoAdmitida no trae texto con tags`() {
        val secretos = listOf(LecturasDePrueba.PAN_VISA, LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA), "A1B2C3D4E5F60718",
            "06010A03A00000", "1E0302", "0840")
        val lecturas = listOf(
            LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F26 to "A1B2C3D4E5F6071")),
            LecturasDePrueba.visaPlasticoVenta().con(kernel = Marca.MASTERCARD),
            LecturasDePrueba.visaPlasticoVenta().con(tags = mapOf(0x9F34 to "3F0000")),
            LecturasDePrueba.visaPlasticoExtranjeraCancelacion().con(tags = mapOf(0x9C to "00")),
        )
        for (lectura in lecturas) {
            val texto = TablaDeAdmision.evaluar(lectura, venta, 100).toString()
            secretos.forEach { assertFalse("$texto deja ver un dato", texto.contains(it)) }
        }
    }
}
