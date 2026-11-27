package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Red
import com.pax.jemv.clcommon.ACType
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
        @Volatile var antena = 0
        @Volatile var fallaAlCerrarIcc = false
        @Volatile var alCancelarNip: () -> Unit = {}
        @Volatile var alLeer: (IEmvTransProcessListener) -> Kernel = { o ->
            o.showConfirmCard()
            o.onCardHolderPwd(false, 3, null)
            Kernel(null, 0, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_SIG)
        }
        @Volatile var alCompletar: () -> Completado = {
            Completado(resultadoDeLaSegunda(0, ACType.AC_AAC), "AAC", null)
        }
        @Volatile var respuesta: IssuerRspData? = null
        val parado = CountDownLatch(1)

        override fun tarjetaPuesta(): Boolean = puesta(detects.incrementAndGet()).also { llamadas += "detect" }
        override fun prepararContacto(importeCentavos: Long): Int = 0.also { llamadas += "prepararContacto" }
        override fun prepararAntena(importeCentavos: Long): Int = antena.also { llamadas += "prepararAntena" }
        override fun sondearChip(conAntena: Boolean, esperaMs: Long): Gano {
            llamadas += "sondear ${if (conAntena) "ICC_PICC" else "ICC"} $esperaMs"
            return gano(sondeos.incrementAndGet())
        }
        override fun leerChip(oyente: IEmvTransProcessListener): Kernel = alLeer(oyente).also { llamadas += "leerChip" }
        override fun tagDelChip(tag: Int): ByteArray? {
            llamadas += "tag %X".format(tag)
            return mapOf(0x9F34 to byteArrayOf(0x1E, 0x03, 0x00), 0x95 to byteArrayOf(0, 0, 0, (0x80).toByte(), 0))[tag]
        }
        override fun completar(respuesta: IssuerRspData, alEntrar: () -> Unit, alSalir: () -> Unit): Completado {
            llamadas += "completar"
            this.respuesta = respuesta
            alEntrar()
            try {
                return alCompletar()
            } finally {
                alSalir()
            }
        }
        override fun pararSondeo() {
            llamadas += "pararSondeo"
            parado.countDown()
        }
        override fun cerrarIcc() {
            llamadas += "cerrarIcc"
            check(!fallaAlCerrarIcc)
        }
        override fun cerrarPicc() { llamadas += "cerrarPicc" }
        override fun cancelarNip() {
            llamadas += "cancelarNip"
            alCancelarNip()
        }
        fun cuantas(que: String) = synchronized(llamadas) { llamadas.count { it == que } }
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

    private fun MedidorDeChip.espera(vararg que: String): String =
        runBlocking { withTimeout(10_000) { texto.first { t -> que.all { it in t } } } }

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

    @Test fun `la segunda generacion se arma como el SDK y la regla del 91 sigue donde se busca`() {
        fun de(k: Kernel) = k.codigo to k.resultado
        assertEquals(0 to TransResultEnum.RESULT_ONLINE_APPROVED, de(resultadoDeLaSegunda(0, ACType.AC_TC)))
        assertEquals(1 to TransResultEnum.RESULT_ONLINE_CARD_DENIED, de(resultadoDeLaSegunda(0, ACType.AC_AAC)))
        assertEquals(-11 to TransResultEnum.RESULT_ONLINE_CARD_DENIED, de(resultadoDeLaSegunda(-11, ACType.AC_TC)))
        assertEquals(0 to TransResultEnum.RESULT_ONLINE_CARD_DENIED, de(resultadoDeLaSegunda(0, ACType.AC_ARQC)))
        assertEquals("ARQC", tipoDeAc(ACType.AC_ARQC))
        ReglaDel91.toString() // sus tres métodos privados de `EmvProcess` existen (si no, el objeto no arranca)
    }

    @Test fun `abrio solo con un sondeo que lo prueba`() {
        assertEquals("abrió · VENCIO", textoDeLaReapertura(Result.success(Gano.VENCIO)))
        assertEquals("abrió · ANTENA", textoDeLaReapertura(Result.success(Gano.ANTENA)))
        assertEquals("PARADO (no prueba que abrió)", textoDeLaReapertura(Result.success(Gano.PARADO)))
        assertEquals("OTRO (no prueba que abrió)", textoDeLaReapertura(Result.success(Gano.OTRO)))
        assertEquals("no abrió (IllegalStateException)", textoDeLaReapertura(Result.failure(IllegalStateException())))
    }

    @Test fun `el texto de una medicion terminada solo trae tiempos y codigos`() {
        val m = Medicion(1, ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO, t0 = 1_000, tope = 120_000)
        m.llamadas += Llamada(Paso.SONDEO, 10, 70_000).apply { hasta = 3_010; resultado = "CHIP" }
        m.llamadas += Llamada(Paso.PRIMERA, 3_010, 10_000).apply { hasta = 4_200; resultado = "código 0 · RESULT_REQ_ONLINE · CVM CVM_SIG" }
        m.msLectura = 3_400
        m.nip += "fuera de línea (intentos 3) a los 3500 ms"
        m.llamadas += Llamada(Paso.SEGUNDA, 4_210, 10_000).apply {
            hasta = 16_210
            resultado = "código 1 · RESULT_ONLINE_CARD_DENIED · AC AAC · 91: no hay (banco no contestó)"
        }
        m.llamadas += Llamada(Paso.NATIVA, 4_400, 10_000).apply { hasta = 15_900 }
        m.fin = 20_000
        m.desenlace = Desenlace.SUELTO
        assertEquals(
            """
            Medición n.º 1 · sólo chip · cierre: banco no contestó · sin dinero, sin red
            Sondeo (tope 60 s): CHIP · 3000 ms
            Lectura y primera generación: código 0 · RESULT_REQ_ONLINE · CVM CVM_SIG · 1190 ms
              inserción → lectura: 390 ms · lectura → fin (autenticación, CVM, riesgo y primera generación): 800 ms
            Oyente de NIP: fuera de línea (intentos 3) a los 3500 ms
            Segunda generación (paso completo): código 1 · RESULT_ONLINE_CARD_DENIED · AC AAC · 91: no hay (banco no contestó) · 12000 ms · SIGUIÓ OCUPADA más de 10 s
              EMVCompleteTrans (sólo la llamada nativa): 11500 ms · SIGUIÓ OCUPADA más de 10 s · resto del paso: 500 ms
            Terminada a los 20000 ms · cierre acreditado · lector suelto
            """.trimIndent(),
            textoDeLaMedicion(m, ahora = 21_000),
        )
    }

    @Test fun `el texto dice sigue ocupada, no regreso, cierre pendiente y cuando regreso cada cosa tras Cancelar`() {
        val m = Medicion(2, ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.APROBADO_DE_MENTIRA, t0 = 0, tope = 120_000)
        val segunda = Llamada(Paso.SEGUNDA, 1_000, 10_000)
        val nativa = Llamada(Paso.NATIVA, 1_200, 10_000)
        m.llamadas += segunda
        m.llamadas += nativa
        val antes = textoDeLaMedicion(m, ahora = 5_000)
        assertTrue(antes, antes.contains("Segunda generación (paso completo): … · en curso (4000 ms)"))
        assertTrue(antes, antes.contains("  EMVCompleteTrans (sólo la llamada nativa): en curso (3800 ms)\n"))
        assertTrue(textoDeLaMedicion(m, ahora = 12_000).contains("· SIGUE OCUPADA (11000 ms, más de 10 s)"))
        m.msCancelar = 15_000
        m.vivaAlCancelar = nativa
        m.cancelInput = "bien a los 15002 ms"
        m.stopPolling = "bien a los 15003 ms"
        segunda.abandonada = true
        nativa.abandonada = true
        m.fin = 121_000
        val atorada = textoDeLaMedicion(m, ahora = 121_000)
        assertTrue(atorada, atorada.contains("· NO REGRESÓ en 120 s (ALTO)"))
        assertTrue(
            atorada,
            atorada.contains(
                "Cancelar: a los 15000 ms · cancelInput bien a los 15002 ms · stopPolling bien a los 15003 ms · " +
                    "«EMVCompleteTrans (sólo la llamada nativa)» sigue sin regresar",
            ),
        )
        assertTrue(atorada, atorada.endsWith("Se dejó de esperar a los 121000 ms · cierre pendiente: el lector sigue apartado hasta que la llamada regrese y se cierre bien"))
        nativa.hasta = 129_000
        segunda.hasta = 130_000
        segunda.resultado = "código 0 · RESULT_ONLINE_APPROVED · AC TC · 91: no se entregó (el SDK lo omite: ni el AIP ni el CDOL2 lo piden)"
        val tarde = textoDeLaMedicion(m, ahora = 130_000)
        assertTrue(tarde, tarde.contains("· 129000 ms · SIGUIÓ OCUPADA más de 10 s y regresó después del tope de 120 s (ALTO)"))
        assertTrue(tarde, tarde.contains("«EMVCompleteTrans (sólo la llamada nativa)» regresó a los 129000 ms"))
        assertTrue(tarde, tarde.endsWith("Cerrando el lector antes de soltarlo (121000 ms)"))
        m.desenlace = Desenlace.CIERRE_PENDIENTE
        assertTrue(textoDeLaMedicion(m, ahora = 131_000).endsWith("CIERRE PENDIENTE: el lector sigue apartado («Cobrar», la lectura seca y «Medir» se rechazan); cierra y abre la app"))
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
            "Segunda generación (paso completo): código 1 · RESULT_ONLINE_CARD_DENIED · AC AAC · 91: no hay (banco no contestó)",
            "  EMVCompleteTrans (sólo la llamada nativa): ",
            "Retiro de la tarjeta (tope 60 s): retirada",
            "Cierre del lector: todo bien",
            "Segundo sondeo inmediato (0.5 s): abrió · VENCIO · cierre: todo bien",
            "cierre acreditado · lector suelto",
        ).forEach { assertTrue("falta «$it» en:\n$t", it in t) }
        assertFalse(t, "Cierre pendiente" in t)
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

    @Test fun `una segunda generacion que no regresa aparta el lector, y al regresar se cierra antes de soltarlo`() {
        val chip = ChipDeMentira()
        val suelta = CountDownLatch(1)
        chip.alCompletar = {
            suelta.await(10, TimeUnit.SECONDS)
            Completado(resultadoDeLaSegunda(0, ACType.AC_TC), "TC", false)
        }
        val medidor = MedidorDeChip(chip, lector, ::reloj, aviso = 100, tope = 400)
        medidor.medir(ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.APROBADO_DE_MENTIRA)
        medidor.espera("SIGUE OCUPADA")
        medidor.cancelar()
        val atorada = medidor.espera("Se dejó de esperar")
        assertTrue(atorada, "NO REGRESÓ" in atorada)
        assertTrue(atorada, "«EMVCompleteTrans (sólo la llamada nativa)» sigue sin regresar" in atorada)
        assertFalse("con la llamada viva el lector debe seguir apartado", lector.siguienteFolio(AttemptId.nuevo()))
        assertFalse("con la llamada atorada no se toca el lector", "cerrarIcc" in chip.llamadas)
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        medidor.espera("Aviso: no se acepta «Medir»: el lector tiene otro dueño")
        suelta.countDown()
        val tarde = medidor.espera("lector suelto")
        listOf(
            "regresó después del tope de 0 s (ALTO)",
            "AC TC · 91: no se entregó (el SDK lo omite",
            "Cierre pendiente (reintento): todo bien",
            "cancelInput bien a los ",
            "stopPolling bien a los ",
        ).forEach { assertTrue("falta «$it» en:\n$tarde", it in tarde) }
        assertEquals(OnlineResult.ONLINE_APPROVE.toByte(), chip.respuesta!!.onlineResult)
        assertTrue(chip.llamadas.toString(), "prepararAntena" in chip.llamadas && "sondear ICC_PICC 60000" in chip.llamadas)
        assertTrue("se cerró después de la llamada tardía", "cerrarIcc" in chip.llamadas && "cerrarPicc" in chip.llamadas)
        assertFalse("tras una llamada tardía no se sigue con el segundo sondeo", "sondear ICC_PICC 500" in chip.llamadas)
        assertTrue(lector.siguienteFolio(AttemptId.nuevo()))
    }

    @Test fun `un cierre que falla deja el lector apartado con cierre pendiente`() {
        val chip = ChipDeMentira().apply {
            fallaAlCerrarIcc = true
            gano = { Gano.VENCIO }
        }
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        val t = medidor.espera("CIERRE PENDIENTE")
        listOf(
            "Cierre del lector: falló: icc.close (IllegalStateException)",
            "Segundo sondeo inmediato (0.5 s): abrió · VENCIO · cierre: falló: icc.close (IllegalStateException)",
            "Cierre pendiente (reintento): falló: icc.close (IllegalStateException)",
        ).forEach { assertTrue("falta «$it» en:\n$t", it in t) }
        assertFalse(t, "lector suelto" in t)
        assertFalse("«Cobrar» y la lectura seca siguen rechazados", lector.siguienteFolio(AttemptId.nuevo()))
    }

    @Test fun `cancelar durante el sondeo no entra al kernel y anota cuando regreso cada cosa`() {
        val chip = ChipDeMentira()
        chip.gano = { n -> if (n == 1) Gano.CHIP.also { check(chip.parado.await(10, TimeUnit.SECONDS)) } else Gano.VENCIO }
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.BANCO_NO_CONTESTO)
        medidor.espera("Sondeo (tope 60 s): … · en curso")
        medidor.cancelar()
        val t = medidor.espera("lector suelto", "stopPolling bien") // el hilo de Cancelar anota su stopPolling a su ritmo
        assertTrue(t, "Sondeo (tope 60 s): CHIP" in t)
        assertTrue(t, "No entró al kernel: se tocó Cancelar" in t)
        assertTrue(
            t,
            Regex("Cancelar: a los \\d+ ms · cancelInput bien a los \\d+ ms · stopPolling bien a los \\d+ ms · «Sondeo \\(tope 60 s\\)» regresó a los \\d+ ms")
                .containsMatchIn(t),
        )
        assertTrue(t, "Segundo sondeo inmediato (0.5 s): abrió · VENCIO · cierre: todo bien" in t)
        assertFalse("cancelado: no se lee el chip", "leerChip" in chip.llamadas)
        assertTrue("el segundo sondeo usa el mismo modo", "sondear ICC_PICC 500" in chip.llamadas)
        assertEquals(2, chip.cuantas("cerrarPicc"))
    }

    @Test fun `el stopPolling de Cancelar no cae en el lector de otro`() {
        val chip = ChipDeMentira()
        val nip = CountDownLatch(1)
        val vence = CountDownLatch(1)
        chip.alCancelarNip = { nip.await(10, TimeUnit.SECONDS) }
        chip.gano = { n -> if (n == 1) Gano.VENCIO.also { vence.await(10, TimeUnit.SECONDS) } else Gano.VENCIO }
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        medidor.espera("Sondeo (tope 60 s): … · en curso")
        medidor.cancelar() // su cancelInput se queda esperando
        vence.countDown() // el sondeo vence solo, la medición cierra y suelta el lector
        medidor.espera("lector suelto")
        assertTrue("otro toma el lector", lector.siguienteFolio(AttemptId.nuevo()))
        nip.countDown()
        val t = medidor.espera("stopPolling no se llamó: el lector ya no era de esta medición")
        assertEquals(t, 2, chip.cuantas("pararSondeo")) // sólo los dos cierres de la medición
    }

    @Test fun `si falla preparar la antena no se sondea`() {
        val chip = ChipDeMentira().apply { antena = -1 }
        val medidor = MedidorDeChip(chip, lector, ::reloj)
        medidor.medir(ModoDeSondeo.CHIP_Y_ANTENA, ModoDeCierre.BANCO_NO_CONTESTO)
        val t = medidor.espera("lector suelto")
        assertTrue(t, "Preparar el kernel sin contacto: código -1" in t)
        assertTrue(t, "No medible: falló preparar la antena (no se sondeó)" in t)
        assertTrue(t, "Cierre del lector: todo bien" in t)
        assertFalse(chip.llamadas.toString(), chip.llamadas.any { it.startsWith("sondear") })
    }

    @Test fun `un detect que dice sin tarjeta pasado el plazo no es retirada`() {
        val chip = ChipDeMentira().apply {
            puesta = { n -> if (n == 2) false.also { Thread.sleep(300) } else false }
        }
        val medidor = MedidorDeChip(chip, lector, ::reloj, esperaRetiro = 100)
        medidor.medir(ModoDeSondeo.CHIP, ModoDeCierre.BANCO_NO_CONTESTO)
        val t = medidor.espera("lector suelto")
        assertTrue(t, "Retiro de la tarjeta (tope 60 s): no la retiraron en 0 s (detect dijo «sin tarjeta» ya vencido el plazo)" in t)
    }
}
