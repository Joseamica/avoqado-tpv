package com.avoqado.cobropropio.blumon

import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.aHex
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.con
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmadoDelMensajeTest {
    // Identidad inventada (nunca la del piloto) y el vector público de ANSI X9.24-1 para el DUKPT.
    private val identidad = Identidad("COMERCIO DE PRUEBA", "12345", "0000000001", "00000000011", "A910S", "1.0", Ambiente.SANDBOX)
    private val armado = ArmadoDelMensaje(identidad)
    private val ipek = "6AC292FAA1315B4D858AB3A3D7D5933A".hexABytes()
    private val base = "FFFF9876543210E00000".hexABytes()
    private val id = AttemptId("0f8fad5bd9cb469fa165b0867728950e")
    private val importe = 12_345L

    private fun dukpt() = MaterialDukpt(ipek, base, 7)

    /** El cuerpo tal como sale al cable: se escribe a un Buffer y se parsea, sin ningún accesor al JSON. */
    private fun json(c: CuerpoDeBlumon): JsonObject =
        JsonParser.parseString(Buffer().also { c.comoRequestBody().writeTo(it) }.readUtf8()).asJsonObject

    private fun venta(l: Lectura) = json(armado.venta(id, importe, l, if (armado.necesitaDukpt(l)) dukpt() else null))
    private fun tags(l: Lectura) = venta(l).getAsJsonObject("presentCardData").get("emvTags").asString

    /** «0000000001» en ASCII-hex: 20 caracteres ⇒ largo «20». */
    private val SERIE_9F1E = "9F1E2030303030303030303031"

    private val tagsVisa = listOf(
        "9F270280", "9F2616A1B2C3D4E5F60718", "9F3708A1B2C3D4", "9F36040001", "9C0200", "82042000", "9F34061E0302",
        "5F2A03484", "9F0212000000012345", "9F0312000000000000", "9F101406010A03A00000", "9F1A03484", "95100000000000",
        SERIE_9F1E,
    ).joinToString("")

    @Test fun `venta trae posId amount reference currency 484 entryMode contactless authentication signature`() {
        val b = venta(LecturasDePrueba.visaPlasticoVenta(importe))
        assertTrue(b.get("posId").asJsonPrimitive.isNumber); assertEquals(12345, b.get("posId").asInt)
        assertTrue(b.get("amount").asJsonPrimitive.isNumber); assertEquals("123.45", b.get("amount").asString)
        assertEquals(id.valor, b.get("reference").asString)
        assertEquals("484", b.get("currency").asString)
        assertEquals("contactless", b.get("entryMode").asString)
        assertEquals("signature", b.get("authentication").asString)
        assertEquals("", b.get("idMembership").asString)
        val card = b.getAsJsonObject("presentCardData")
        assertEquals("411111", card.get("bin").asString); assertEquals("1111", card.get("last4").asString)
        assertEquals(LecturasDePrueba.PAN_VISA, card.get("data").asString)
        assertEquals("", card.get("cardholderName").asString)
        assertEquals(35, card.get("track2Length").asInt)  // la banda sin la F de relleno
        // un peso exacto viaja con sus dos decimales
        assertEquals("1.00", json(armado.venta(id, 100, LecturasDePrueba.visaPlasticoVenta(), dukpt())).get("amount").asString)
        // nada sensible en el texto de los objetos
        val c = armado.venta(id, importe, LecturasDePrueba.visaPlasticoVenta(importe), dukpt())
        assertEquals("CuerpoDeBlumon(oculto)", c.toString()); assertEquals("MaterialDukpt(oculto)", dukpt().toString())
    }

    @Test fun `cancelacion arma el mismo cuerpo que la venta`() {
        val l = LecturasDePrueba.visaPlasticoCancelacion(importe)
        assertEquals(json(armado.venta(id, importe, l, dukpt())), json(armado.cancelacion(id, importe, l, dukpt())))
    }

    @Test fun `largo de cada tag en decimal contando caracteres hex`() {
        val t = tags(LecturasDePrueba.visaPlasticoVenta(importe))
        assertEquals(tagsVisa, t)
        assertTrue(t.contains("9F101406010A03A00000"))  // 14 caracteres ⇒ «14», no 7 bytes
        assertTrue(t.contains("9F2616A1B2C3D4E5F60718"))
    }

    @Test fun `5F2A y 9F1A sin el primer caracter`() {
        val t = tags(LecturasDePrueba.mastercardPlasticoVenta(importe))
        assertTrue(t.contains("5F2A03484")); assertTrue(t.contains("9F1A03484"))
        assertFalse(t.contains("5F2A040484")); assertFalse(t.contains("9F1A040484"))
    }

    @Test fun `visa y mastercard omiten un tag faltante`() {
        for (l in listOf(LecturasDePrueba.visaPlasticoVenta(importe), LecturasDePrueba.mastercardCelularVenta(importe))) {
            val t = tags(l.con(sin = setOf(0x9F37)))
            for (falta in listOf("9F37", "9F3300", "9A00", "9F3500", "5F3400", "9F0900", "9F1E00", "5000")) assertFalse(falta, t.contains(falta))
        }
        assertTrue(tags(LecturasDePrueba.mastercardCelularVenta(importe)).contains("9F34063F0002"))
    }

    @Test fun `amex manda TAG00 y usa 9F71 9F67 sin 84`() {
        val plastico = tags(LecturasDePrueba.amexPlasticoVenta(importe))
        assertEquals(listOf(
            "9F270280", "9F2616A1B2C3D4E5F60718", "9F3708A1B2C3D4", "9F36040001", "9C0200", "82042000", "9F3300", "9F7100",
            "9A00", "5F2A03484", "9F0212000000012345", "9F0312000000000000", "9F3500", "5F3400", "9F101406010A03A00000",
            "9F0900", "9F1A03484", "95100000000000", SERIE_9F1E, "9F6706010203", "5000",
        ).joinToString(""), plastico)
        for (fuera in listOf("A000000025010801", "9F6E", "9F3406")) assertFalse(fuera, plastico.contains(fuera))
        val celular = tags(LecturasDePrueba.amexCelularVenta(importe))
        assertTrue(celular.contains("9F7106010203")); assertTrue(celular.contains("9F6700"))
    }

    @Test fun `amex sin dukpt ni track2Crc32`() {
        val b = venta(LecturasDePrueba.amexPlasticoVenta(importe))
        val card = b.getAsJsonObject("presentCardData")
        assertEquals(LecturasDePrueba.banda(LecturasDePrueba.PAN_AMEX), card.get("track2").asString)
        assertFalse(card.has("track2Crc32")); assertFalse(b.has("dukptData"))
        // la marca la dice el kernel, nunca el BIN
        assertFalse(armado.necesitaDukpt(LecturasDePrueba.amexPlasticoVenta()))
        assertFalse(armado.necesitaDukpt(LecturasDePrueba.visaPlasticoVenta().con(kernel = Marca.AMEX)))
        assertTrue(armado.necesitaDukpt(LecturasDePrueba.amexPlasticoVenta().con(kernel = Marca.VISA)))
        assertTrue(armado.necesitaDukpt(LecturasDePrueba.mastercardPlasticoVenta()))
        // Amex no gasta contador; Visa y Mastercard no salen sin él
        assertThrows(IllegalArgumentException::class.java) { armado.venta(id, importe, LecturasDePrueba.amexPlasticoVenta(), dukpt()) }
        assertThrows(IllegalArgumentException::class.java) { armado.venta(id, importe, LecturasDePrueba.visaPlasticoVenta(), null) }
        assertThrows(IllegalArgumentException::class.java) { armado.venta(id, importe, LecturasDePrueba.visaPlasticoVenta().con(kernel = null), dukpt()) }
    }

    @Test fun `visa viaja cifrada con crc y dukptData del contador`() {
        val b = venta(LecturasDePrueba.visaPlasticoVenta(importe))
        val card = b.getAsJsonObject("presentCardData")
        val banda = LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA)
        val esperado = Dukpt.cifrarTrack2(Dukpt.llaveDeTransaccion(ipek, Dukpt.ksnCon(base, 7)), banda)
        assertEquals(esperado, card.get("track2").asString); assertFalse(esperado.contains(LecturasDePrueba.PAN_VISA))
        assertEquals(Dukpt.crc32(esperado), card.get("track2Crc32").asString)
        val d = b.getAsJsonObject("dukptData")
        assertEquals(0, d.get("failedCounter").asInt); assertEquals(7, d.get("realCounter").asInt)
        assertEquals(Dukpt.ksnCon(base, 7).aHex(), d.get("serialKey").asString)
    }

    @Test fun `9F1E es siempre la serie fisica en ASCII hex en las tres marcas y pisa la de la lectura`() {
        val propia = mapOf(0x9F1E to "4142434445")
        val lecturas = listOf(
            LecturasDePrueba.visaPlasticoVenta(importe), LecturasDePrueba.mastercardPlasticoVenta(importe),
            LecturasDePrueba.amexPlasticoVenta(importe), LecturasDePrueba.amexCelularVenta(importe),
        )
        for (l in lecturas) {
            val d = if (armado.necesitaDukpt(l)) dukpt() else null
            val conPropia = l.con(tags = propia)
            for (c in listOf(armado.venta(id, importe, conPropia, d), armado.cancelacion(id, importe, conPropia, d))) {
                for (b in listOf(json(c), json(armado.reverso(c)))) {
                    val t = b.getAsJsonObject("presentCardData").get("emvTags").asString
                    assertTrue(t.contains(SERIE_9F1E)); assertFalse(t.contains("4142434445"))
                }
            }
        }
        val otra = ArmadoDelMensaje(identidad.copy(serieFisica = "PAX-A910S1"))
        assertTrue(json(otra.venta(id, importe, LecturasDePrueba.visaPlasticoVenta(importe), dukpt()))
            .getAsJsonObject("presentCardData").get("emvTags").asString.contains("9F1E205041582D413931305331"))
        for (mala in listOf("", "con espacio", "serie\u00f1", "A".repeat(33), "tab\t")) {
            assertThrows(mala, IllegalArgumentException::class.java) { ArmadoDelMensaje(identidad.copy(serieFisica = mala)) }
        }
    }

    @Test fun `9F03 en ceros si falta en visa y amex`() {
        assertTrue(tags(LecturasDePrueba.visaPlasticoVenta(importe)).contains("9F0312000000000000"))
        assertTrue(tags(LecturasDePrueba.amexPlasticoVenta(importe)).contains("9F0312000000000000"))
        // Mastercard que no lo trae lo omite; uno que sí vino nunca se pisa
        assertFalse(tags(LecturasDePrueba.mastercardPlasticoVenta(importe).con(sin = setOf(0x9F03))).contains("9F03"))
        assertTrue(tags(LecturasDePrueba.visaPlasticoVenta(importe).con(tags = mapOf(0x9F03 to "000000000001"))).contains("9F0312000000000001"))
    }

    @Test fun `reverso es la venta exacta mas reverseReason cancelled mas sendReverse true y amount texto 123_45`() {
        val c = armado.venta(id, importe, LecturasDePrueba.visaPlasticoVenta(importe), dukpt())
        val venta = json(c)
        val r = json(armado.reverso(c))
        assertEquals(MOTIVO_DEL_REVERSO, r.get("reverseReason").asString); assertEquals("cancelled", MOTIVO_DEL_REVERSO)
        assertTrue(r.get("sendReverse").asBoolean)
        assertTrue(r.get("amount").asJsonPrimitive.isString); assertEquals("123.45", r.get("amount").asString)
        val resto = r.deepCopy().apply { remove("reverseReason"); remove("sendReverse"); remove("amount") }
        assertEquals(venta.deepCopy().apply { remove("amount") }, resto)
        assertEquals(venta, json(c))  // la venta congelada no cambió
    }

    @Test fun `reverso no cambia reference ni dukptData`() {
        for (l in listOf(LecturasDePrueba.visaPlasticoVenta(importe), LecturasDePrueba.amexPlasticoVenta(importe))) {
            val c = armado.venta(id, importe, l, if (armado.necesitaDukpt(l)) dukpt() else null)
            val v = json(c); val r = json(armado.reverso(c))
            for (campo in listOf("reference", "dukptData", "presentCardData", "posId", "currency")) assertEquals(campo, v.get(campo), r.get(campo))
        }
    }
}
