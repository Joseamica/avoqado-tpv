package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Cvm
import com.avoqado.cobropropio.dominio.Entrada
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.ResultadoKernel
import com.pax.jemv.clcommon.KernType
import com.paxsz.module.emv.process.enums.CvmResultEnum
import com.paxsz.module.emv.process.enums.TransResultEnum
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * Bloque 4-L · L-c: el adaptador con una PAX de mentira. Datos SINTÉTICOS: el PAN de prueba público de Visa (y el de Amex) y
 * valores inventados, con la forma de `cobropropio/.../fixtures/LecturasDePrueba.kt`; nunca una captura de una tarjeta.
 */
class LectorPaxTest {

    /** La PAX de mentira: anota cada llamada y deja guionizar lo que contesta. */
    private class PaxDeMentira : Pax {
        val llamadas: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var codigoDePreparacion = 0
        var alPreparar: () -> Unit = {}
        var sondeo: () -> Sondeo = { Sondeo.TARJETA }
        var kernel = Kernel(KernType.KERNTYPE_VIS, 0, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_NO_CVM)
        var alLeer: () -> Unit = {}
        var tags: Map<Int, ByteArray> = VISA
        var fallaAlParar = false
        var fallaAlCerrar = false
        var alCerrar: () -> Unit = {}

        /** Se abre con el primer `pararSondeo`: así un sondeo «activo» de mentira sabe que lo pararon. */
        val parado = CountDownLatch(1)

        override fun preparar(importeCentavos: Long): Int {
            llamadas += "preparar $importeCentavos"
            alPreparar()
            return codigoDePreparacion
        }

        override fun sondear(esperaMs: Long): Sondeo {
            llamadas += "sondear $esperaMs"
            return sondeo()
        }

        override fun pararSondeo() {
            llamadas += "pararSondeo"
            parado.countDown()
            check(!fallaAlParar)
        }

        override fun leerConElKernel(): Kernel {
            llamadas += "kernel"
            alLeer()
            return kernel
        }

        override fun tag(tag: Int): ByteArray? {
            llamadas += "tag"
            return tags[tag]
        }

        override fun cerrarPicc() {
            llamadas += "cerrarPicc"
            alCerrar()
            check(!fallaAlCerrar)
        }

        /** Las llamadas sin las de los tags: el orden de la secuencia. */
        fun pasos(): List<String> = synchronized(llamadas) { llamadas.filter { it != "tag" } }
    }

    private val folio = AttemptId.nuevo()
    private val conRed = object : Red { override fun hayRed() = true }

    private fun lector(pax: PaxDeMentira, red: Red = conRed) = LectorPax(pax, red).apply { assertTrue(siguienteFolio(folio)) }

    private fun LectorPax.leerYa(operacion: Operacion = Operacion.VENTA) = runBlocking { leer(operacion, IMPORTE, ESPERA) }

    /** La lectura entera: el importe REAL (no $1), sólo el lector sin contacto, tags crudos y el cierre acreditado. */
    @Test fun `una venta lee la tarjeta con el importe real y cierra el lector acreditado`() {
        val pax = PaxDeMentira()
        val lector = lector(pax)
        val lectura = (lector.leerYa() as ResultadoDeLectura.Leida).lectura
        assertEquals(Marca.VISA, lectura.kernel)
        assertEquals(Entrada.SIN_CONTACTO, lectura.entrada)
        assertEquals(ResultadoKernel.PIDE_BANCO, lectura.resultado)
        assertEquals(0, lectura.codigoKernel)
        assertEquals(Cvm.SIN_CVM, lectura.cvm)
        assertEquals("411111", lectura.bin()) // la banda 57 llegó tal cual a la Lectura
        assertEquals("1111", lectura.ultimos4())
        assertEquals("0484", lectura.paisEmisor())
        assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(folio, i.folio)
        assertEquals(Causa.LEIDA, i.causa)
        assertEquals(0, i.codigo)
        assertEquals(Marca.VISA, i.marca)
        assertEquals(2_550L, i.importeDelKernel)
        assertEquals(VISA.keys, i.tagsPresentes)
        assertEquals(Cierre.ACREDITADO, i.cierre)
        // Lo que se enseña nunca trae la banda, el PAN ni el criptograma (el folio va en minúsculas y sin 12 dígitos seguidos).
        for (secreto in listOf("4111111111111111", "D2912101", "A1B2C3D4E5F60718")) assertFalse(secreto, secreto in i.toString())
    }

    /** Un solo dueño: mientras A es dueño, B se rechaza y A queda intacto; A se suelta al cerrar, o la app lo suelta si el motor no leyó. */
    @Test fun `un solo dueno del lector desde que se apunta el folio`() {
        val pax = PaxDeMentira()
        val lector = LectorPax(pax, conRed)
        val a = AttemptId.nuevo()
        val b = AttemptId.nuevo()
        val c = AttemptId.nuevo()
        assertTrue(lector.siguienteFolio(a))
        assertFalse(lector.siguienteFolio(b)) // A sigue siendo dueño: B se rechaza y nada cambia
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        assertEquals(a, lector.informes.value!!.folio)
        assertTrue(lector.siguienteFolio(b)) // A cerró: el lector quedó libre
        assertTrue(lector.soltarFolio(b)) // el motor terminó B sin llamar a leer (p. ej., no salió sin red)
        assertFalse(lector.soltarFolio(b)) // ya no hay nada que soltar
        assertTrue(lector.siguienteFolio(c))
        assertFalse(lector.soltarFolio(a)) // A ya no es dueño
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        assertEquals(c, lector.informes.value!!.folio)
    }

    /** Crudos: sólo lo que el kernel devolvió y con algo, en hex mayúsculas. Un Amex de celular trae 9F71 y no 9F67. */
    @Test fun `los tags van crudos en hex mayusculas y sin los que el kernel no devolvio`() {
        val pax = PaxDeMentira().apply {
            tags = mapOf(
                0x57 to hex("378282246310005D291210100000000000"), 0x9F26 to hex("A1B2C3D4E5F60718"),
                0x9F71 to hex("010203"), 0x9F67 to ByteArray(0), 0x9F34 to hex("1F0002"),
            )
        }
        assertEquals(
            mapOf(0x57 to "378282246310005D291210100000000000", 0x9F26 to "A1B2C3D4E5F60718", 0x9F71 to "010203", 0x9F34 to "1F0002"),
            leerTags(pax),
        )
        assertEquals(TAGS.size, pax.llamadas.count { it == "tag" }) // se pide cada tag una vez
        assertEquals(TAGS.size, TAGS.toSet().size)
        assertFalse(0x9F1E in TAGS) // el 9F1E sale de la identidad, no de la tarjeta
        assertTrue(listOf(0x57, 0x5F28, 0x9F34, 0x9F67, 0x9F71, 0x9F02, 0x9F10).all { it in TAGS })
    }

    /** El kernel se traduce sin adivinar: lo que no es una marca medida queda sin marca, y un NIP de cualquier tipo es NIP. */
    @Test fun `el kernel se traduce sin adivinar`() {
        assertEquals(Marca.MASTERCARD, marcaDe(KernType.KERNTYPE_MC))
        assertEquals(Marca.VISA, marcaDe(KernType.KERNTYPE_VIS))
        assertEquals(Marca.AMEX, marcaDe(KernType.KERNTYPE_AE))
        for (otro in listOf(null, 0, 1, 4, 6, 7)) assertNull("tipo $otro", marcaDe(otro))
        assertEquals(
            mapOf(
                TransResultEnum.RESULT_REQ_ONLINE to ResultadoKernel.PIDE_BANCO,
                TransResultEnum.RESULT_OFFLINE_APPROVED to ResultadoKernel.APROBADA_SIN_BANCO,
                TransResultEnum.RESULT_OFFLINE_DENIED to ResultadoKernel.NEGADA_SIN_BANCO,
            ),
            TransResultEnum.values().associateWith(::resultadoDe).filterValues { it != ResultadoKernel.OTRO },
        )
        assertEquals(ResultadoKernel.OTRO, resultadoDe(null))
        assertEquals(
            mapOf(
                CvmResultEnum.CVM_NO_CVM to Cvm.SIN_CVM,
                CvmResultEnum.CVM_OFFLINE_PIN to Cvm.PIN,
                CvmResultEnum.CVM_ONLINE_PIN to Cvm.PIN,
                CvmResultEnum.CVM_SIG to Cvm.FIRMA,
                CvmResultEnum.CVM_ONLINE_PIN_SIG to Cvm.PIN,
                CvmResultEnum.CVM_CONSUMER_DEVICE to Cvm.DISPOSITIVO,
            ),
            CvmResultEnum.values().associateWith(::cvmDe),
        )
        assertEquals(Cvm.OTRO, cvmDe(null))
    }

    /** Sin red justo antes de sondear: no sondea, no abre nada y lo dice. */
    @Test fun `sin red no sondea`() {
        val pax = PaxDeMentira()
        val lector = lector(pax, red = object : Red { override fun hayRed() = false })
        assertEquals(ResultadoDeLectura.Cortada(SIN_CODIGO), lector.leerYa())
        assertEquals(listOf("preparar 2550"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(Causa.SIN_RED, i.causa)
        assertEquals(Cierre.NO_SE_ABRIO, i.cierre) // no se abrió nada, y no dice «lector cerrado»
    }

    /** Un paso del cierre que truena se dice por su nombre, y el otro paso igual se intenta (no como `cerrarLectores()` de LAB0). */
    @Test fun `un paso del cierre que falla deja el cierre sin acreditar`() {
        for ((alParar, alCerrar, fallas) in listOf(
            Triple(true, false, listOf("stopPolling")),
            Triple(false, true, listOf("picc.close")),
            Triple(true, true, listOf("stopPolling", "picc.close")),
        )) {
            val pax = PaxDeMentira().apply { fallaAlParar = alParar; fallaAlCerrar = alCerrar }
            val lector = lector(pax)
            assertTrue(lector.leerYa() is ResultadoDeLectura.Leida) // la lectura ya estaba hecha: el cierre no la cambia
            assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
            val i = lector.informes.value!!
            assertEquals(Cierre.SIN_ACREDITAR, i.cierre)
            assertEquals(fallas, i.fallasDelCierre)
        }
    }

    /** Un cierre fallido se conserva: el siguiente intento lo reintenta ANTES de preparar; si vuelve a fallar, no prepara ni sondea. */
    @Test fun `un cierre fallido se conserva y el siguiente intento lo reintenta antes de preparar`() {
        val pax = PaxDeMentira().apply { fallaAlCerrar = true }
        var conRedAhora = true
        val lector = LectorPax(pax, object : Red { override fun hayRed() = conRedAhora })
        val a = AttemptId.nuevo()
        val b = AttemptId.nuevo()
        val c = AttemptId.nuevo()
        assertTrue(lector.siguienteFolio(a))
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        assertEquals(Cierre.SIN_ACREDITAR, lector.informes.value!!.cierre)
        // B, sin red: reintenta el cierre de A, vuelve a fallar, no prepara ni sondea, y nunca dice «lector cerrado».
        conRedAhora = false
        pax.llamadas.clear()
        assertTrue(lector.siguienteFolio(b))
        assertEquals(ResultadoDeLectura.Cortada(SIN_CODIGO), lector.leerYa())
        val ib = lector.informes.value!!
        assertEquals(b, ib.folio)
        assertEquals(Causa.CIERRE_PENDIENTE, ib.causa)
        assertEquals(Cierre.SIN_ACREDITAR, ib.cierre)
        assertEquals(listOf("picc.close"), ib.fallasDelCierre)
        assertEquals(listOf("pararSondeo", "cerrarPicc"), pax.pasos())
        // C, con la PAX ya sana y con red: el reintento cierra y la lectura sigue.
        pax.fallaAlCerrar = false
        conRedAhora = true
        pax.llamadas.clear()
        assertTrue(lector.siguienteFolio(c))
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        assertEquals(Cierre.ACREDITADO, lector.informes.value!!.cierre)
        assertEquals(
            listOf("pararSondeo", "cerrarPicc", "preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"),
            pax.pasos(),
        )
    }

    /** Ninguna de estas salidas entrega una tarjeta, y cada una dice por qué (con el código de la PAX cuando lo hay). */
    @Test fun `las salidas sin lectura no entregan tarjeta y dicen por que`() {
        val sondeoYCierre = listOf("preparar 2550", "sondear 30000", "pararSondeo", "cerrarPicc")
        val conKernel = listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc")

        data class Caso(
            val nombre: String,
            val operacion: Operacion,
            val arma: (PaxDeMentira) -> Unit,
            val resultado: ResultadoDeLectura,
            val causa: Causa,
            val codigo: Int?,
            val cierre: Cierre,
            val pasos: List<String>,
        )
        val casos = listOf(
            Caso("cancelacion con tarjeta", Operacion.CANCELACION, {}, ResultadoDeLectura.Cortada(SIN_CODIGO), Causa.NO_ES_VENTA, null, Cierre.NO_SE_ABRIO, emptyList()),
            Caso("preparacion que falla", Operacion.VENTA, { it.codigoDePreparacion = -3 }, ResultadoDeLectura.Cortada(-3), Causa.PREPARACION, -3, Cierre.NO_SE_ABRIO, listOf("preparar 2550")),
            Caso("sondeo vencido", Operacion.VENTA, { it.sondeo = { Sondeo.VENCIO } }, ResultadoDeLectura.Vencida, Causa.VENCIDA, null, Cierre.ACREDITADO, sondeoYCierre),
            Caso("sondeo raro", Operacion.VENTA, { it.sondeo = { Sondeo.OTRO } }, ResultadoDeLectura.Cortada(SIN_CODIGO), Causa.SONDEO, null, Cierre.ACREDITADO, sondeoYCierre),
            // Con código ≠ 0 los tags serían de la tarjeta ANTERIOR (la PAX de mentira los sigue dando): no se leen.
            Caso(
                "kernel con codigo", Operacion.VENTA,
                { it.kernel = Kernel(KernType.KERNTYPE_VIS, -8, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM) },
                ResultadoDeLectura.Cortada(-8), Causa.KERNEL, -8, Cierre.ACREDITADO, conKernel,
            ),
            Caso("sin banda", Operacion.VENTA, { it.tags = VISA - 0x57 }, ResultadoDeLectura.Cortada(0), Causa.SIN_BANDA, 0, Cierre.ACREDITADO, conKernel),
        )
        for (c in casos) {
            val pax = PaxDeMentira().also(c.arma)
            val lector = lector(pax)
            assertEquals(c.nombre, c.resultado, lector.leerYa(c.operacion))
            assertEquals(c.nombre, c.pasos, pax.pasos())
            val i = lector.informes.value!!
            assertEquals(c.nombre, c.causa, i.causa)
            assertEquals(c.nombre, c.codigo, i.codigo)
            assertEquals(c.nombre, c.cierre, i.cierre)
            if (c.causa == Causa.KERNEL) assertFalse(c.nombre, "tag" in pax.llamadas)
        }
        // Sin folio apuntado, y con el folio ya usado: no se toca la PAX ni se dice «lector cerrado».
        val pax = PaxDeMentira()
        val sinFolio = LectorPax(pax, conRed)
        assertEquals(ResultadoDeLectura.Cancelada, sinFolio.leerYa())
        val i = sinFolio.informes.value!!
        assertEquals(Causa.SIN_FOLIO, i.causa)
        assertNull(i.folio)
        assertEquals(Cierre.NO_SE_ABRIO, i.cierre)
        val usado = lector(pax)
        assertTrue(usado.leerYa() is ResultadoDeLectura.Leida)
        assertEquals(ResultadoDeLectura.Cancelada, usado.leerYa())
        assertEquals(Causa.SIN_FOLIO, usado.informes.value!!.causa)
        assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
    }

    /** Una excepción de la PAX no sube al motor ni deja ver su mensaje: el informe sólo trae su clase, y el lector se cierra. */
    @Test fun `un error de la PAX no deja salir su mensaje y cierra el lector`() {
        val pax = PaxDeMentira().apply { alLeer = { throw IllegalStateException("banda 4111111111111111D2912101") } }
        val lector = lector(pax)
        assertEquals(ResultadoDeLectura.Cortada(SIN_CODIGO), lector.leerYa())
        val i = lector.informes.value!!
        assertEquals(Causa.ERROR, i.causa)
        assertEquals("IllegalStateException", i.error)
        assertFalse("4111111111111111" in i.toString())
        assertEquals(Cierre.ACREDITADO, i.cierre)
        assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
    }

    private companion object {
        const val IMPORTE = 2_550L
        const val ESPERA = 30_000L

        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Una Visa de plástico como la mide la PAX: el 9F34 relleno 1E0302 y sin 9F03. PAN de prueba público. */
        val VISA: Map<Int, ByteArray> = mapOf(
            0x57 to "4111111111111111D291210100000000000F", 0x5F28 to "0484", 0x9F27 to "80", 0x9F26 to "A1B2C3D4E5F60718",
            0x9F37 to "A1B2C3D4", 0x9F36 to "0001", 0x9C to "00", 0x82 to "2000", 0x9F34 to "1E0302", 0x5F2A to "0484",
            0x9F02 to "000000002550", 0x9F1A to "0484", 0x95 to "0000000000", 0x9F10 to "06010A03A00000",
        ).mapValues { hex(it.value) }
    }
}
