package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Red
import com.pax.jemv.clcommon.OnlineResult
import com.paxsz.module.emv.process.contact.IEmvTransProcessListener
import com.paxsz.module.emv.process.entity.IssuerRspData
import com.paxsz.module.emv.process.enums.CvmResultEnum
import com.paxsz.module.emv.process.enums.TransResultEnum
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * La medición de chip (rumbo A del 26-nov) con un chip de mentira: lo nativo se mide en la PAX. Aquí sólo la lógica: la decisión
 * «siguió ocupada», el texto, la respuesta inventada y el orden de los pasos. Ningún dato de una tarjeta: 9F34 y 95 inventados.
 */
class MedicionDeChipTest {

    private fun reloj() = System.nanoTime() / 1_000_000

    private class ChipDeMentira : ChipDePrueba {
        val llamadas: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val detects = AtomicInteger()
        private val sondeos = AtomicInteger()
        @Volatile var puesta: (Int) -> Boolean = { n -> n in 2..3 } // al empezar no; luego dos veces puesta y se retira
        @Volatile var gano: (Int) -> Gano = { n -> if (n == 1) Gano.CHIP else Gano.VENCIO }
        @Volatile var alLeer: (IEmvTransProcessListener) -> Kernel = { o ->
            o.showConfirmCard()
            o.onCardHolderPwd(false, 3, null)
            Kernel(null, 0, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_SIG)
        }
        @Volatile var alCompletar: () -> Kernel = { Kernel(null, 1, TransResultEnum.RESULT_ONLINE_CARD_DENIED, CvmResultEnum.CVM_SIG) }
        @Volatile var respuesta: IssuerRspData? = null
        val parado = CountDownLatch(1)

        override fun tarjetaPuesta(): Boolean = puesta(detects.incrementAndGet()).also { llamadas += "detect" }
        override fun prepararContacto(importeCentavos: Long): Int = 0.also { llamadas += "prepararContacto" }
        override fun prepararAntena(importeCentavos: Long): Int = 0.also { llamadas += "prepararAntena" }
        override fun sondearChip(conAntena: Boolean, esperaMs: Long): Gano {
            llamadas += "sondear ${if (conAntena) "ICC_PICC" else "ICC"} $esperaMs"
            return gano(sondeos.incrementAndGet())
        }
        override fun leerChip(oyente: IEmvTransProcessListener): Kernel = alLeer(oyente).also { llamadas += "leerChip" }
        override fun tagDelChip(tag: Int): ByteArray? {
            llamadas += "tag %X".format(tag)
            return mapOf(0x9F34 to byteArrayOf(0x1E, 0x03, 0x00), 0x95 to byteArrayOf(0, 0, 0, (0x80).toByte(), 0))[tag]
        }
        override fun completar(respuesta: IssuerRspData): Kernel {
            llamadas += "completar"
            this.respuesta = respuesta
            return alCompletar()
        }
        override fun pararSondeo() {
            llamadas += "pararSondeo"
            parado.countDown()
        }
        override fun cerrarIcc() { llamadas += "cerrarIcc" }
        override fun cerrarPicc() { llamadas += "cerrarPicc" }
        override fun cancelarNip() { llamadas += "cancelarNip" }
    }

    private val lector = LectorPax(
        object : Pax {
            override fun preparar(importeCentavos: Long) = 0
            override fun sondear(esperaMs: Long) = Sondeo.VENCIO
            override fun pararSondeo() = Unit
            override fun leerConElKernel() = Kernel(null, 0, null, null)
            override fun tag(tag: Int): ByteArray? = null
            override fun cerrarPicc() = Unit
        },
        object : Red { override fun hayRed() = true },
    )

    private fun MedidorDeChip.espera(que: String): String = runBlocking { withTimeout(10_000) { texto.first { que in it } } }

    @Test fun `la decision siguio ocupada`() {
        assertEquals(Ocupacion.A_TIEMPO, ocupacion(dura = 9_999, regreso = true, abandonada = false, aviso = 10_000))
        assertEquals(Ocupacion.TARDE, ocupacion(dura = 10_000, regreso = true, abandonada = false, aviso = 10_000))
        assertEquals(Ocupacion.TARDE, ocupacion(dura = 130_000, regreso = true, abandonada = true, aviso = 10_000))
        assertEquals(Ocupacion.EN_CURSO, ocupacion(dura = 9_999, regreso = false, abandonada = false, aviso = 10_000))
        assertEquals(Ocupacion.SIGUE_OCUPADA, ocupacion(dura = 10_000, regreso = false, abandonada = false, aviso = 10_000))
        assertEquals(Ocupacion.NO_REGRESO, ocupacion(dura = 120_000, regreso = false, abandonada = true, aviso = 10_000))
    }

    @Test fun `la respuesta del banco es inventada y sin red`() {
        val mudo = respuestaDeMentira(ModoDeCierre.BANCO_NO_CONTESTO)
        assertEquals(OnlineResult.ONLINE_FAILED.toByte(), mudo.onlineResult)
        assertEquals(0, mudo.authData.size + mudo.respCode.size + mudo.authCode.size + mudo.script.size)
        val aprobado = respuestaDeMentira(ModoDeCierre.APROBADO_DE_MENTIRA)
        assertEquals(OnlineResult.ONLINE_APPROVE.toByte(), aprobado.onlineResult)
        assertEquals("0A1B2C3D4E5F6A7B3030", hex(aprobado.authData)) // 91 = ARPC inventado + 8A, como el SDK
        assertEquals("3030", hex(aprobado.respCode))
        assertEquals("LAB001", String(aprobado.authCode, Charsets.US_ASCII))
        assertEquals(0, aprobado.script.size)
    }

    @Test fun `el texto de una medicion terminada solo trae tiempos y codigos`() {
        val m = Medicion(1, ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO, t0 = 1_000, tope = 120_000)
        m.llamadas += Llamada(Paso.SONDEO, 10, 70_000).apply { hasta = 3_010; resultado = "CHIP" }
        m.llamadas += Llamada(Paso.PRIMERA, 3_010, 10_000).apply { hasta = 4_200; resultado = "código 0 · RESULT_REQ_ONLINE · CVM CVM_SIG" }
        m.msLectura = 3_400
        m.nip += "fuera de línea (intentos 3) a los 3500 ms"
        m.llamadas += Llamada(Paso.SEGUNDA, 4_210, 10_000).apply {
            hasta = 16_210
            resultado = "código 1 · RESULT_ONLINE_CARD_DENIED · CVM CVM_SIG"
        }
        m.fin = 20_000
        m.lectorSuelto = true
        assertEquals(
            """
            Medición n.º 1 · sólo chip · cierre: banco no contestó · sin dinero, sin red
            Sondeo (tope 60 s): CHIP · 3000 ms
            Lectura y primera generación: código 0 · RESULT_REQ_ONLINE · CVM CVM_SIG · 1190 ms
              inserción → lectura: 390 ms · primera generación: 800 ms
            Oyente de NIP: fuera de línea (intentos 3) a los 3500 ms
            Segunda generación (EMVCompleteTrans): código 1 · RESULT_ONLINE_CARD_DENIED · CVM CVM_SIG · 12000 ms · SIGUIÓ OCUPADA más de 10 s
            Terminada a los 20000 ms · lector suelto
            """.trimIndent(),
            textoDeLaMedicion(m, ahora = 21_000),
        )
    }

    @Test fun `el texto dice sigue ocupada, no regreso y que destrabo cancelar`() {
        val m = Medicion(2, ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.APROBADO_DE_MENTIRA, t0 = 0, tope = 120_000)
        val segunda = Llamada(Paso.SEGUNDA, 1_000, 10_000)
        m.llamadas += segunda
        assertTrue(textoDeLaMedicion(m, ahora = 5_000).contains("Segunda generación (EMVCompleteTrans): … · en curso (4000 ms)"))
        assertTrue(textoDeLaMedicion(m, ahora = 12_000).contains("· SIGUE OCUPADA (11000 ms, más de 10 s)"))
        m.msCancelar = 15_000
        m.cancelar = "cancelInput bien · stopPolling bien"
        segunda.abandonada = true
        m.fin = 121_000
        val atorada = textoDeLaMedicion(m, ahora = 121_000)
        assertTrue(atorada, atorada.contains("· NO REGRESÓ en 120 s (ALTO)"))
        assertTrue(atorada, atorada.contains("Cancelar: a los 15000 ms · cancelInput bien · stopPolling bien · «Segunda generación (EMVCompleteTrans)» sigue sin regresar"))
        assertTrue(atorada, atorada.endsWith("Se dejó de esperar a los 121000 ms: el lector sigue apartado hasta que regrese la llamada"))
        segunda.hasta = 130_000
        segunda.resultado = "código 0 · RESULT_ONLINE_APPROVED · CVM CVM_NO_CVM"
        val tarde = textoDeLaMedicion(m, ahora = 130_000)
        assertTrue(tarde, tarde.contains("· 129000 ms · SIGUIÓ OCUPADA más de 10 s y regresó después del tope de 120 s (ALTO)"))
        assertTrue(tarde, tarde.contains("«Segunda generación (EMVCompleteTrans)» regresó 115000 ms después"))
    }

    @Test fun `una medicion completa en orden, con el banco que no contesto, y suelta el lector`() {
        val chip = ChipDeMentira()
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        val t = medidor.espera("lector suelto")
        listOf(
            "Medición n.º 1 · sólo chip · cierre: banco no contestó",
            "Tarjeta puesta al empezar (icc.detect): no",
            "Preparar el kernel de contacto: código 0",
            "Sondeo (tope 60 s): CHIP",
            "Lectura y primera generación: código 0 · RESULT_REQ_ONLINE · CVM CVM_SIG",
            "inserción → lectura: ",
            "Códigos: 9F34 1E0300 · 95 0000008000",
            "Oyente de NIP: fuera de línea (intentos 3) a los ",
            "Segunda generación (EMVCompleteTrans): código 1 · RESULT_ONLINE_CARD_DENIED",
            "Retiro de la tarjeta (tope 60 s): retirada",
            "Cierre del lector: todo bien",
            "Segundo sondeo inmediato (0.5 s): abrió · VENCIO · cierre: todo bien",
        ).forEach { assertTrue("falta «$it» en:\n$t", it in t) }
        assertEquals(OnlineResult.ONLINE_FAILED.toByte(), chip.respuesta!!.onlineResult)
        assertEquals(
            listOf(
                "detect", "prepararContacto", "sondear ICC 60000", "leerChip", "tag 9F34", "tag 95", "completar",
                "detect", "detect", "detect", "pararSondeo", "cerrarIcc", "sondear ICC 500", "pararSondeo", "cerrarIcc",
            ),
            chip.llamadas.toList(),
        )
        assertTrue("el lector quedó apartado", lector.siguienteFolio(AttemptId.nuevo()))
    }

    @Test fun `una segunda generacion que no regresa aparta el lector hasta que regresa y cancelar no la salta`() {
        val chip = ChipDeMentira()
        val suelta = CountDownLatch(1)
        chip.alCompletar = {
            suelta.await(10, TimeUnit.SECONDS)
            Kernel(null, 0, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_SIG)
        }
        val medidor = MedidorDeChip(chip, lector, ::reloj, aviso = 100, tope = 400)
        medidor.medir(ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.APROBADO_DE_MENTIRA)
        medidor.espera("SIGUE OCUPADA")
        medidor.cancelar()
        val atorada = medidor.espera("Se dejó de esperar")
        assertTrue(atorada, "NO REGRESÓ" in atorada)
        assertTrue(atorada, "«Segunda generación (EMVCompleteTrans)» sigue sin regresar" in atorada)
        assertFalse("con la llamada viva el lector debe seguir apartado", lector.siguienteFolio(AttemptId.nuevo()))
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        medidor.espera("Aviso: no se acepta «Medir»: el lector tiene otro dueño")
        suelta.countDown()
        val tarde = medidor.espera("lector suelto")
        assertTrue(tarde, "regresó después del tope de 0 s (ALTO)" in tarde)
        assertTrue(tarde, "cancelInput bien · stopPolling bien" in tarde)
        assertEquals(OnlineResult.ONLINE_APPROVE.toByte(), chip.respuesta!!.onlineResult)
        assertTrue(chip.llamadas.toString(), "prepararAntena" in chip.llamadas && "sondear ICC_PICC 60000" in chip.llamadas)
        assertFalse("con la llamada atorada no se toca el lector", "cerrarIcc" in chip.llamadas)
        assertTrue(lector.siguienteFolio(AttemptId.nuevo()))
    }

    @Test fun `cancelar durante el sondeo llama cancelInput y stopPolling y dice cuanto tardo en soltar`() {
        val chip = ChipDeMentira()
        chip.gano = { n ->
            if (n == 1) Gano.PARADO.also { check(chip.parado.await(10, TimeUnit.SECONDS)) } else Gano.VENCIO
        }
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.BANCO_NO_CONTESTO)
        medidor.espera("Sondeo (tope 60 s): … · en curso")
        medidor.cancelar()
        val t = medidor.espera("lector suelto")
        assertTrue(t, "Sondeo (tope 60 s): PARADO" in t)
        assertTrue(t, Regex("Cancelar: a los \\d+ ms · cancelInput bien · stopPolling bien · «Sondeo \\(tope 60 s\\)» regresó \\d+ ms después").containsMatchIn(t))
        assertTrue(t, "Cierre del lector: todo bien" in t)
        assertFalse("cancelado: no se lee el chip", "leerChip" in chip.llamadas)
        assertTrue(chip.llamadas.toString(), "cancelarNip" in chip.llamadas && "cerrarPicc" in chip.llamadas)
    }
}
