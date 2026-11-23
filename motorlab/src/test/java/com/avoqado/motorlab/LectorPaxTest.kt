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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * El informe dice QUÉ tags llegaron, nunca sus valores: `tagsPresentes` es una copia de los números. La vista `keys` de un
     * mapa es una clase interna del mapa y lo retiene entero (la banda 57 y el 9F26), aunque no se vea por la API.
     */
    @Test fun `el informe guarda una copia de los numeros de tag y no una vista del mapa`() {
        val lector = lector(PaxDeMentira()) // la Visa sintética: varios tags, así que el mapa crudo no es de un solo elemento
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        val i = lector.informes.value!!
        assertEquals(VISA.keys, i.tagsPresentes)
        val dueno = i.tagsPresentes.javaClass.enclosingClass
        assertFalse("tagsPresentes no puede ser una vista del mapa de tags", dueno != null && Map::class.java.isAssignableFrom(dueno))
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

    /**
     * Una excepción de la PAX no sube al motor ni deja ver su mensaje: el informe sólo trae su clase. Al preparar todavía no se
     * abrió el lector; desde el sondeo ya se abrió, y se cierra.
     */
    @Test fun `un error de la PAX no deja salir su mensaje y cierra el lector`() {
        fun truena(): Nothing = throw IllegalStateException("banda 4111111111111111D2912101")
        data class Punto(val donde: String, val arma: (PaxDeMentira) -> Unit, val pasos: List<String>, val cierre: Cierre)
        val puntos = listOf(
            Punto("al preparar", { it.alPreparar = { truena() } }, listOf("preparar 2550"), Cierre.NO_SE_ABRIO),
            Punto("al sondear", { it.sondeo = { truena() } }, listOf("preparar 2550", "sondear 30000", "pararSondeo", "cerrarPicc"), Cierre.ACREDITADO),
            Punto("en el kernel", { it.alLeer = { truena() } }, listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), Cierre.ACREDITADO),
        )
        for (p in puntos) {
            val pax = PaxDeMentira().also(p.arma)
            val lector = lector(pax)
            assertEquals(p.donde, ResultadoDeLectura.Cortada(SIN_CODIGO), lector.leerYa())
            val i = lector.informes.value!!
            assertEquals(p.donde, Causa.ERROR, i.causa)
            assertEquals(p.donde, "IllegalStateException", i.error)
            assertFalse(p.donde, "4111111111111111" in i.toString())
            assertEquals(p.donde, p.cierre, i.cierre)
            assertEquals(p.donde, p.pasos, pax.pasos())
        }
    }

    /** El importe que llega a la PAX es el que pidió el motor y no uno fijo (todas las demás pruebas leen con 2550). */
    @Test fun `el importe que llega a la PAX es el pedido`() {
        val pax = PaxDeMentira()
        val lector = lector(pax)
        assertTrue(runBlocking { lector.leer(Operacion.VENTA, 9_900L, ESPERA) } is ResultadoDeLectura.Leida)
        assertEquals(listOf("preparar 9900", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
    }

    /**
     * Una espera ya agotada (0 o menos) no llega a la PAX: con 0 su sondeo no tiene plazo, y el folio se quedaría sondeando y
     * rechazaría a todos los siguientes. Sale `Vencida`, sin abrir el lector, y el dueño se suelta.
     */
    @Test fun `una espera agotada no toca la PAX y suelta al dueno`() {
        for (espera in listOf(0L, -1L)) {
            val pax = PaxDeMentira()
            val lector = lector(pax)
            assertEquals("espera $espera", ResultadoDeLectura.Vencida, runBlocking { lector.leer(Operacion.VENTA, IMPORTE, espera) })
            assertEquals("espera $espera", emptyList<String>(), pax.llamadas) // ni una llamada a la PAX, tampoco de tags
            val i = lector.informes.value!!
            assertEquals("espera $espera", Causa.VENCIDA, i.causa)
            assertEquals("espera $espera", Cierre.NO_SE_ABRIO, i.cierre)
            assertTrue("espera $espera", lector.siguienteFolio(AttemptId.nuevo()))
        }
    }

    /**
     * Quien escucha `informes` desde el hilo que publica ya encuentra al dueño suelto: puede apuntar el folio siguiente, y su
     * `detener` del folio que terminó no cambia la lectura ya decidida. `Dispatchers.Unconfined` con `UNDISPATCHED`: el colector
     * queda suscrito ANTES de leer, y el `StateFlow` lo reanuda en el hilo que asigna el valor (el del lector), dentro del
     * candado, que es reentrante. Se anota el nombre del hilo, para que la prueba no pase si el colector corriera en otro lado.
     */
    @Test fun `al salir el informe el dueno ya esta suelto`() {
        val lector = lector(PaxDeMentira())
        val b = AttemptId.nuevo()
        val visto = AtomicReference<Pair<String, Boolean>?>(null)
        val r = runBlocking {
            val observador = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                lector.informes.collect { i ->
                    if (i?.folio == folio) {
                        lector.detener(folio)
                        visto.set(Thread.currentThread().name to lector.siguienteFolio(b))
                    }
                }
            }
            lector.leer(Operacion.VENTA, IMPORTE, ESPERA).also { observador.cancel() }
        }
        // Con el modo de depuración de corrutinas (activo con -ea) el nombre lleva « @coroutine#N»: se compara el principio.
        val (hilo, siguienteDeB) = visto.get()!!
        assertTrue("el colector corrió en «$hilo» y no en el hilo que publica", hilo.startsWith("motorlab-lector"))
        assertTrue("al publicar el informe, el dueño seguía tomado y B se rechazó", siguienteDeB)
        assertTrue(r is ResultadoDeLectura.Leida)
        assertEquals(Causa.LEIDA, lector.informes.value!!.causa)
        assertTrue(lector.soltarFolio(b)) // B no leyó: se suelta y no queda dueño
    }

    private fun esperar(l: CountDownLatch) = assertTrue("la PAX de mentira no llegó", l.await(5, TimeUnit.SECONDS))

    /** `leer` en otro hilo; [mientras] corre con la PAX de mentira parada donde la prueba quiera. */
    private fun LectorPax.leerMientras(mientras: suspend () -> Unit): ResultadoDeLectura = runBlocking {
        val leyendo = async(Dispatchers.Default) { leer(Operacion.VENTA, IMPORTE, ESPERA) }
        mientras()
        leyendo.await()
    }

    /**
     * Momento 1: detener antes de que el motor pida la tarjeta (p. ej., mientras abre la sesión). No inicia el sondeo, y la marca
     * manda sobre toda salida anterior al sondeo: también sin red o con `preparar` fallida sale `Cancelada`, no `Cortada`.
     */
    @Test fun `detener antes de leer y el sondeo no arranca`() {
        val sinRed = object : Red { override fun hayRed() = false }
        for ((nombre, red, preparacion) in listOf(Triple("con red", conRed, 0), Triple("sin red", sinRed, 0), Triple("preparar falla", conRed, -3))) {
            val pax = PaxDeMentira().apply { codigoDePreparacion = preparacion }
            val lector = lector(pax, red)
            lector.detener(folio)
            assertEquals(nombre, ResultadoDeLectura.Cancelada, lector.leerYa())
            assertEquals(nombre, listOf("preparar 2550"), pax.pasos()) // ni sondeo, ni stopPolling, ni cierre
            val i = lector.informes.value!!
            assertEquals(nombre, Causa.DETENIDA, i.causa)
            assertEquals(nombre, Cierre.NO_SE_ABRIO, i.cierre)
            assertTrue(nombre, lector.siguienteFolio(AttemptId.nuevo())) // lo detuvieron sin haber leído: al regresar su leer, se suelta
        }
    }

    /** Momento 2: detener mientras prepara; al terminar ya no sondea, y nadie llama `stopPolling` (no había sondeo). */
    @Test fun `detener entre preparar y sondear y el sondeo no arranca`() {
        val enPreparar = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        val pax = PaxDeMentira().apply { alPreparar = { enPreparar.countDown(); seguir.await(5, TimeUnit.SECONDS) } }
        val lector = lector(pax)
        val r = lector.leerMientras {
            esperar(enPreparar)
            lector.detener(folio)
            seguir.countDown()
        }
        assertEquals(ResultadoDeLectura.Cancelada, r)
        assertEquals(listOf("preparar 2550"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(Causa.DETENIDA, i.causa)
        assertEquals(Cierre.NO_SE_ABRIO, i.cierre)
    }

    /**
     * Momento 3: con el sondeo ya decidido, detener ORDENA pararlo (`stopPolling`); al volver ya marcado, no se llama al kernel,
     * y el lector se cierra. Mientras lee: otro folio se rechaza, el dueño no se suelta y una segunda `leer` no lo reclama (regresa
     * sin tocar la PAX). (Que la PAX obedezca una parada que cae justo cuando empieza a sondear se mide en el aparato.)
     */
    @Test fun `detener con el sondeo activo lo para y cierra el lector`() {
        val enSondeo = CountDownLatch(1)
        val pax = PaxDeMentira()
        pax.sondeo = { enSondeo.countDown(); pax.parado.await(2, TimeUnit.SECONDS); Sondeo.PARADO }
        val lector = lector(pax)
        val r = lector.leerMientras {
            esperar(enSondeo)
            assertFalse(lector.siguienteFolio(AttemptId.nuevo())) // mientras lee, otro folio se rechaza
            assertFalse(lector.soltarFolio(folio)) // y el que lee no se suelta a media lectura
            assertEquals(ResultadoDeLectura.Cancelada, lector.leer(Operacion.VENTA, IMPORTE, ESPERA)) // una segunda leer no reclama al dueño
            assertEquals(Causa.SIN_FOLIO, lector.informes.value!!.causa)
            assertEquals(listOf("preparar 2550", "sondear 30000"), pax.pasos()) // y no tocó la PAX
            lector.detener(folio)
        }
        assertEquals(ResultadoDeLectura.Cancelada, r)
        // El primer pararSondeo es la orden de detener; el segundo, el del cierre.
        assertEquals(listOf("preparar 2550", "sondear 30000", "pararSondeo", "pararSondeo", "cerrarPicc"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(Causa.DETENIDA, i.causa)
        assertEquals(Cierre.ACREDITADO, i.cierre)
    }

    /**
     * Detener durante el sondeo, con la orden de parar perdida (la PAX de mentira no se despierta con ella): el sondeo dura hasta
     * que hay tarjeta o vence su plazo; al volver ya marcado, no se llama al kernel. (Si la marca cayera DESPUÉS de esa revisión,
     * el kernel correría y la frenaría la decisión final: `detener durante el kernel…`.)
     */
    @Test fun `detener durante el sondeo aunque se pierda la parada no llama al kernel`() {
        val enSondeo = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        val pax = PaxDeMentira().apply { sondeo = { enSondeo.countDown(); seguir.await(5, TimeUnit.SECONDS); Sondeo.TARJETA } }
        val lector = lector(pax)
        val r = lector.leerMientras {
            esperar(enSondeo)
            lector.detener(folio)
            seguir.countDown()
        }
        assertEquals(ResultadoDeLectura.Cancelada, r)
        assertFalse("kernel" in pax.llamadas)
        assertEquals(listOf("preparar 2550", "sondear 30000", "pararSondeo", "pararSondeo", "cerrarPicc"), pax.pasos())
    }

    /** Detener mientras el kernel lee (o justo después del sondeo): el kernel corre, pero la lectura ya no se entrega. */
    @Test fun `detener durante el kernel no entrega la lectura`() {
        val enKernel = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        val pax = PaxDeMentira().apply { alLeer = { enKernel.countDown(); seguir.await(5, TimeUnit.SECONDS) } }
        val lector = lector(pax)
        val r = lector.leerMientras {
            esperar(enKernel)
            lector.detener(folio)
            seguir.countDown()
        }
        assertEquals(ResultadoDeLectura.Cancelada, r)
        assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "pararSondeo", "cerrarPicc"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(Causa.DETENIDA, i.causa)
        assertEquals(Cierre.ACREDITADO, i.cierre)
    }

    /** Detener mientras se cierra el lector: la decisión final va después del cierre, así que la lectura ya no se entrega. */
    @Test fun `detener durante el cierre no entrega la lectura`() {
        val enCierre = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        val pax = PaxDeMentira().apply { alCerrar = { enCierre.countDown(); seguir.await(5, TimeUnit.SECONDS) } }
        val lector = lector(pax)
        val r = lector.leerMientras {
            esperar(enCierre)
            lector.detener(folio)
            seguir.countDown()
        }
        assertEquals(ResultadoDeLectura.Cancelada, r)
        // Cerrando, detener ya no llama stopPolling: sólo marca.
        assertEquals(listOf("preparar 2550", "sondear 30000", "kernel", "pararSondeo", "cerrarPicc"), pax.pasos())
        val i = lector.informes.value!!
        assertEquals(Causa.DETENIDA, i.causa)
        assertEquals(Cierre.ACREDITADO, i.cierre)
    }

    /** Detener le llega al dueño, y a un folio rechazado no le hace nada. */
    @Test fun `detener le llega al dueno y no a un folio rechazado`() {
        val pax = PaxDeMentira()
        val lector = LectorPax(pax, conRed)
        val a = AttemptId.nuevo()
        val b = AttemptId.nuevo()
        assertTrue(lector.siguienteFolio(a))
        assertFalse(lector.siguienteFolio(b))
        lector.detener(b) // B no es dueño: A queda intacto
        assertTrue(lector.leerYa() is ResultadoDeLectura.Leida)
        val c = AttemptId.nuevo()
        assertTrue(lector.siguienteFolio(c))
        assertFalse(lector.siguienteFolio(AttemptId.nuevo()))
        lector.detener(c) // el dueño sí
        assertEquals(ResultadoDeLectura.Cancelada, lector.leerYa())
        val i = lector.informes.value!!
        assertEquals(c, i.folio)
        assertEquals(Causa.DETENIDA, i.causa)
    }

    /**
     * El motor deja de esperar (vence su reloj de lectura o se cancela su `fondo`). Sondeando: el sondeo se para y el lector se
     * cierra. Preparando: ya no sondea ni abre el lector. La marca cae dentro de `cancel()`, donde corre `invokeOnCancellation`.
     */
    @Test fun `el motor que deja de esperar detiene al folio sondeando o preparando`() {
        val enSondeo = CountDownLatch(1)
        val pax = PaxDeMentira()
        pax.sondeo = { enSondeo.countDown(); pax.parado.await(2, TimeUnit.SECONDS); Sondeo.PARADO }
        val lector = lector(pax)
        runBlocking {
            val leyendo = launch(Dispatchers.Default) { lector.leer(Operacion.VENTA, IMPORTE, ESPERA) }
            esperar(enSondeo)
            leyendo.cancel()
            val i = withTimeout(5_000) { lector.informes.first { it?.folio == folio }!! }
            assertTrue(leyendo.isCancelled)
            assertEquals(Causa.DETENIDA, i.causa)
            assertEquals(Cierre.ACREDITADO, i.cierre)
        }
        assertFalse("kernel" in pax.llamadas)
        assertEquals(listOf("preparar 2550", "sondear 30000", "pararSondeo", "pararSondeo", "cerrarPicc"), pax.pasos())

        // Preparando (p. ej., mientras el motor abre la sesión): la PAX de mentira se queda en `preparar` hasta que se cancela.
        val enPreparar = CountDownLatch(1)
        val seguir = CountDownLatch(1)
        val paxPreparando = PaxDeMentira().apply { alPreparar = { enPreparar.countDown(); seguir.await(5, TimeUnit.SECONDS) } }
        val preparando = lector(paxPreparando)
        runBlocking {
            val leyendo = launch(Dispatchers.Default) { preparando.leer(Operacion.VENTA, IMPORTE, ESPERA) }
            esperar(enPreparar)
            leyendo.cancel()
            seguir.countDown()
            val i = withTimeout(5_000) { preparando.informes.first { it?.folio == folio }!! }
            assertTrue(leyendo.isCancelled)
            assertEquals(Causa.DETENIDA, i.causa)
            assertEquals(Cierre.NO_SE_ABRIO, i.cierre)
        }
        assertEquals(listOf("preparar 2550"), paxPreparando.pasos())
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
