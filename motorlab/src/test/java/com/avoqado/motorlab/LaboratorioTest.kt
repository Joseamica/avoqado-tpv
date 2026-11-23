package com.avoqado.motorlab

import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.SaludDeLaLibreta
import com.avoqado.cobropropio.motor.ResultadoDelCorte
import com.pax.jemv.clcommon.KernType
import com.paxsz.module.emv.process.enums.CvmResultEnum
import com.paxsz.module.emv.process.enums.TransResultEnum
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bloque 4-L · L-d1: el carril con un motor y una libreta de mentira y el `LectorPax` DE VERDAD sobre una PAX de mentira. */
class LaboratorioTest {
    @get:Rule val carpeta = TemporaryFolder()
    private val carril = Executors.newSingleThreadExecutor { Thread(it, "carril") }
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val red = object : Red { override fun hayRed() = true }
    private val pax = PaxQueLee()
    private val motor = MotorDeMentira()
    private val filas = FilasDeMentira()
    private val fabricas = AtomicInteger()
    @Volatile private var fabrica: (Identidad) -> Pair<Motor, Filas> = { motor to filas }
    @Volatile private var enSeco: suspend () -> String = { "seca" }
    private lateinit var historial: HistorialLocal
    private lateinit var lector: LectorPax
    private lateinit var lab: Laboratorio

    @Before fun armarElMundo() {
        historial = HistorialLocal(File(carpeta.root, "historial"))
        lector = LectorPax(pax, red)
        lab = nuevo()
    }

    @After fun cerrar() {
        alcance.cancel()
        carril.shutdownNow()
    }

    private class MotorDeMentira : Motor {
        val llamadas: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var alCobrar: suspend FlowCollector<EventoDeCobro>.(SolicitudDeVenta) -> Unit = { emit(EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.SIN_RED, null)) }
        @Volatile var alCortar: suspend () -> Unit = {}
        @Volatile var alRecuperar: suspend () -> List<AttemptId> = { emptyList() }
        val tardias = MutableSharedFlow<Pair<AttemptId, EventoDeCobro>>() // sin búfer: `emit` regresa cuando el colector terminó con el anterior
        override val resolucionesTardias: SharedFlow<Pair<AttemptId, EventoDeCobro>> = tardias
        override fun cobrar(s: SolicitudDeVenta): Flow<EventoDeCobro> = flow {
            llamadas += "cobrar"
            alCobrar(this, s)
        }
        override suspend fun cortar(s: SolicitudDeVenta): ResultadoDelCorte {
            llamadas += "cortar"
            alCortar()
            return ResultadoDelCorte.CORTADO
        }
        override suspend fun recuperar(): List<AttemptId> = alRecuperar().also { llamadas += "recuperar" }
        fun cuantas(que: String) = synchronized(llamadas) { llamadas.count { it == que } }
    }

    private class FilasDeMentira : Filas {
        val filas = ConcurrentHashMap<AttemptId, IntentoEntity>()
        val ilegibles: MutableSet<AttemptId> = ConcurrentHashMap.newKeySet()
        override fun buscar(folio: AttemptId): IntentoEntity? = if (folio in ilegibles) throw IllegalStateException("fila ilegible") else filas[folio]
        override fun salud() = SaludDeLaLibreta(0, null, 0, ilegibles.size)
    }

    private class PaxQueLee : Pax {
        val pasos: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var sondeo: () -> Sondeo = { Sondeo.TARJETA }
        val enSondeo = CountDownLatch(1)
        val parado = CountDownLatch(1)
        override fun preparar(importeCentavos: Long): Int = 0
        override fun sondear(esperaMs: Long): Sondeo {
            pasos += "sondear"
            enSondeo.countDown()
            return sondeo()
        }
        override fun pararSondeo() {
            pasos += "pararSondeo"
            parado.countDown()
        }
        override fun leerConElKernel() = Kernel(KernType.KERNTYPE_VIS, 0, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_NO_CVM)
        override fun tag(tag: Int): ByteArray? = TARJETA[tag]
        override fun cerrarPicc() = Unit
    }

    private fun nuevo(h: HistorialLocal = historial, a: CoroutineScope = alcance) = Laboratorio(
        h, lector, ArchivoDeIdentidad(carpeta.root), { fabricas.incrementAndGet(); fabrica(it) }, { enSeco() }, a, carril, { System.nanoTime() / 1_000_000 },
    )

    private fun <T> espera(b: suspend () -> T): T = runBlocking { withTimeout(10_000) { b() } }
    private fun aguanta(l: CountDownLatch) = check(l.await(10, TimeUnit.SECONDS)) { "prueba colgada" }
    private fun esperar(l: Laboratorio = lab, c: (Situacion) -> Boolean) = espera { l.situacion.first(c) }
    private fun carrilVacio() = aguanta(CountDownLatch(1).also { l -> carril.execute { l.countDown() } })
    private fun armado(l: Laboratorio = lab) {
        l.orden(Orden.Guardar("SERIE0099", "4321", ""))
        esperar(l) { it.motor == EstadoDelMotor.Armado }
    }
    private fun cobrarYEsperar(l: Laboratorio = lab): AttemptId {
        l.orden(Orden.Cobrar(2_537))
        carrilVacio()
        esperar(l) { it.trabajo == null }
        return historial.leer().last().folio
    }
    private fun notas(folio: AttemptId) = historial.leer().single { it.folio == folio }.notas.map { it.second }
    private fun libre() = AttemptId.nuevo().let { otro -> lector.siguienteFolio(otro).also { if (it) lector.soltarFolio(otro) } }
    private fun leyendo(): suspend FlowCollector<EventoDeCobro>.(SolicitudDeVenta) -> Unit = { s ->
        emit(EventoDeCobro.PideTarjeta)
        val r = lector.leer(Operacion.VENTA, s.importeCentavos, 30_000)
        emit(if (r is ResultadoDeLectura.Leida) EventoDeCobro.PorConfirmar(s.importeCentavos) else EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.CANCELADA_EN_LECTOR, null))
    }
    /** Un laboratorio cuyo trabajo de fondo corre en UN hilo: vaciarlo es una barrera. */
    private fun conHiloLento(prueba: (Laboratorio, ExecutorService) -> Unit) {
        val hilo = Executors.newSingleThreadExecutor()
        val lento = CoroutineScope(SupervisorJob() + hilo.asCoroutineDispatcher())
        try {
            prueba(nuevo(a = lento).also { armado(it) }, hilo)
        } finally {
            lento.cancel()
            hilo.shutdownNow()
        }
    }

    /** El folio está ENTERO en su archivo cuando el motor lo recibe; el segundo toque, con el cobro en curso, se rechaza. */
    @Test fun `el folio va al historial antes de cobrar y dos toques son un folio`() {
        armado()
        val recibio = CompletableDeferred<AttemptId>()
        val suelta = CompletableDeferred<Unit>()
        val vio = AtomicBoolean(false)
        motor.alCobrar = { s ->
            vio.set(HistorialLocal(File(carpeta.root, "historial")).leer().any { it.folio == s.id && it.importeCentavos == 2_537L })
            recibio.complete(s.id)
            suelta.await()
            emit(EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.SIN_RED, null))
        }
        lab.orden(Orden.Cobrar(2_537))
        lab.orden(Orden.Cobrar(2_537))
        val folio = espera { recibio.await() }
        suelta.complete(Unit)
        esperar { it.trabajo == null }
        assertTrue(vio.get())
        assertEquals(listOf(folio), historial.leer().map { it.folio })
        assertEquals(1, motor.cuantas("cobrar"))
        assertTrue(lab.situacion.value.avisos.any { "no se acepta «Cobrar»: hay un cobro en curso" in it })
    }

    /** Se arma con la identidad aceptada en SIN_IDENTIDAD; guardar otra mientras se arma o ya armado sólo guarda; no se cobra hasta ARMADO. */
    @Test fun `un solo motor con la identidad del proceso y no se cobra mientras se arma`() {
        val usada = CompletableDeferred<Identidad>()
        val suelta = CompletableDeferred<Unit>()
        fabrica = { usada.complete(it); motor to filas }
        motor.alRecuperar = { suelta.await(); emptyList() }
        lab.orden(Orden.Guardar("SERIE0099", "4321", ""))
        espera { usada.await() }
        lab.orden(Orden.Guardar("SERIE0088", "8765", "FISICA02"))
        lab.orden(Orden.Cobrar(2_537))
        carrilVacio()
        assertEquals(EstadoDelMotor.Armando, lab.situacion.value.motor)
        suelta.complete(Unit)
        val s = esperar { it.motor == EstadoDelMotor.Armado && it.avisos.any { a -> "se usará al cerrar y abrir la app" in a } }
        assertEquals(IdentidadTecleada("SERIE0099", "4321", "SERIE0099").identidad(), espera { usada.await() })
        assertEquals(false, s.guardadaEsLaDelMotor)
        lab.orden(Orden.Guardar("SERIE0077", "1111", ""))
        esperar { it.avisos.count { a -> "se usará al cerrar y abrir la app" in a } == 2 }
        assertEquals(IdentidadTecleada("SERIE0077", "1111", "SERIE0077"), ArchivoDeIdentidad(carpeta.root).leer())
        assertEquals(1, fabricas.get())
        assertEquals(1, lab.situacion.value.motoresArmados)
        assertEquals(0, motor.cuantas("cobrar"))
    }

    /** Si fabricar o el `recuperar()` al armar lanzan, FALLO con su clase (nunca ARMADO) y «Cobrar» no se acepta. */
    @Test fun `si fabricar o recuperar al armar lanza queda FALLO y no se cobra`() {
        motor.alRecuperar = { error("la libreta tronó") }
        lab.orden(Orden.Guardar("SERIE0099", "4321", ""))
        val s = esperar { it.motor is EstadoDelMotor.Fallo && it.identidadGuardada }
        assertEquals(EstadoDelMotor.Fallo("IllegalStateException"), s.motor)
        assertEquals("no se armó el motor: IllegalStateException; cierra y abre la app", porQueNoCobra(s))
        lab.orden(Orden.Cobrar(2_537))
        carrilVacio()
        assertEquals(0, motor.cuantas("cobrar"))
        assertNull(lab.situacion.value.trabajo)
        fabrica = { error("no abrió") } // otro proceso: la identidad ya está guardada
        val otro = nuevo().also { it.arrancar() }
        assertEquals(EstadoDelMotor.Fallo("IllegalStateException"), esperar(otro) { it.motor is EstadoDelMotor.Fallo }.motor)
    }

    /** Contrato de L-c: se suelta al terminar o si el intento aborta, no mientras el motor lee; el informe y las tardías van a su folio; ocupado, ni folio ni motor. */
    @Test fun `el lector se suelta al terminar o si el intento aborta y ocupado no cobra`() {
        armado()
        cobrarYEsperar()
        assertTrue(libre())
        motor.alCobrar = { error("se cayó") }
        assertTrue("el intento tronó: IllegalStateException" in notas(cobrarYEsperar()))
        assertTrue(libre())
        val tarjeta = CountDownLatch(1)
        pax.sondeo = { aguanta(tarjeta); Sondeo.TARJETA }
        motor.alCobrar = leyendo()
        lab.orden(Orden.Cobrar(2_537))
        aguanta(pax.enSondeo)
        assertFalse(libre())
        tarjeta.countDown()
        esperar { it.trabajo == null }
        assertTrue(libre())
        val leido = historial.leer().last().folio // el informe de L-c y una tardía, en SU folio y aparte del Flow
        espera { lab.folios.first { "lector: LEIDA · código 0 · VISA · 9F02 2537 · lector cerrado" in it } }
        espera {
            motor.tardias.subscriptionCount.first { it > 0 }
            motor.tardias.emit(leido to EventoDeCobro.NoSeCobro)
            motor.tardias.emit(AttemptId.nuevo() to EventoDeCobro.NoSeCobro) // regresa cuando el colector terminó con la primera
        }
        assertTrue("tardía: NoSeCobro" in notas(leido))
        assertEquals(listOf("PideTarjeta", "PorConfirmar \$25.37"), notas(leido).filterNot { it.startsWith("lector:") || it.startsWith("tardía:") })
        val otro = AttemptId.nuevo()
        assertTrue(lector.siguienteFolio(otro))
        val antes = historial.leer().size
        lab.orden(Orden.Cobrar(2_537))
        esperar { it.trabajo == null && it.avisos.any { a -> "el lector sigue ocupado: toca otra vez" in a } }
        assertEquals(antes, historial.leer().size)
        assertEquals(3, motor.cuantas("cobrar"))
        lector.soltarFolio(otro)
    }

    /** «Cancelar» con el motor arrancado: `detener` antes de `cortar`, y el COBRO no termina mientras el corte no regresa (Codex r3 #3). */
    @Test fun `cancelar con el motor arrancado detiene el lector antes de cortar y el cobro espera al corte`() = conHiloLento { l, hilo ->
        pax.sondeo = { aguanta(pax.parado); Sondeo.PARADO }
        motor.alCobrar = leyendo()
        val vioLaParada = AtomicBoolean(false)
        val sueltaCorte = CompletableDeferred<Unit>()
        motor.alCortar = { vioLaParada.set("pararSondeo" in synchronized(pax.pasos) { pax.pasos.toList() }); sueltaCorte.await() }
        l.orden(Orden.Cobrar(2_537))
        aguanta(pax.enSondeo)
        l.orden(Orden.Cancelar)
        espera { l.folios.first { "NoSalioAlBanco · CANCELADA_EN_LECTOR" in it } } // el Flow ya terminó
        carrilVacio()
        hilo.submit {}.get(10, TimeUnit.SECONDS) // el intento avanzó todo lo que podía
        carrilVacio()
        assertTrue(l.situacion.value.trabajo is Trabajo.Cobro)
        sueltaCorte.complete(Unit)
        esperar(l) { it.trabajo == null }
        val folio = historial.leer().last().folio
        assertTrue(vioLaParada.get())
        assertTrue("corte: CORTADO" in notas(folio))
    }

    /** «Cancelar» antes de que exista el folio: el intento existe desde el toque y lo recuerda; nunca se llama al motor. */
    @Test fun `cancelar antes de que exista el folio no llama al motor`() = conHiloLento { l, hilo ->
        val tapon = CountDownLatch(1)
        hilo.execute { aguanta(tapon) }
        l.orden(Orden.Cobrar(2_537))
        l.orden(Orden.Cancelar)
        carrilVacio()
        assertTrue(historial.leer().isEmpty())
        tapon.countDown()
        esperar(l) { it.trabajo == null }
        assertEquals(0, motor.cuantas("cobrar"))
        assertEquals(0, motor.cuantas("cortar"))
        assertTrue(libre())
        assertTrue("cancelado antes de arrancar el motor" in notas(historial.leer().single().folio))
    }

    /** Si el folio no se puede guardar, no se llama al motor, el lector queda libre y la pantalla lo dice (Codex r3 #5). */
    @Test fun `si el folio no se puede guardar no se llama al motor`() {
        armado()
        File(carpeta.root, "historial").apply { deleteRecursively(); writeText("no es carpeta") }
        lab.orden(Orden.Cobrar(2_537))
        esperar { it.trabajo == null && it.avisos.any { a -> "no se pudo guardar el folio: " in a } }
        assertEquals(0, motor.cuantas("cobrar"))
        assertTrue(libre())
    }

    /** La lectura seca entra por el carril, con cualquier motor: no con un cobro en curso, ni un cobro con ella en curso (Codex r3 #2). */
    @Test fun `la lectura seca entra por el carril y no convive con un cobro`() {
        lab.orden(Orden.LecturaSeca) // sin motor
        assertEquals("Lectura n.º 1\nseca", esperar { it.trabajo == null && it.lecturaSeca?.endsWith("seca") == true }.lecturaSeca)
        armado()
        val enLectura = CompletableDeferred<Unit>()
        val suelta = CompletableDeferred<Unit>()
        enSeco = { enLectura.complete(Unit); suelta.await(); "Resultado: Leída" }
        lab.orden(Orden.LecturaSeca)
        espera { enLectura.await() }
        lab.orden(Orden.Cobrar(2_537))
        carrilVacio()
        assertEquals(Trabajo.LecturaSeca, lab.situacion.value.trabajo)
        assertEquals(0, motor.cuantas("cobrar"))
        suelta.complete(Unit)
        assertEquals("Lectura n.º 2\nResultado: Leída", esperar { it.trabajo == null }.lecturaSeca)
        val sigue = CompletableDeferred<Unit>()
        motor.alCobrar = { sigue.await(); emit(EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.SIN_RED, null)) }
        lab.orden(Orden.Cobrar(2_537))
        lab.orden(Orden.LecturaSeca)
        carrilVacio()
        assertTrue(lab.situacion.value.trabajo is Trabajo.Cobro)
        assertTrue(lab.situacion.value.avisos.any { "no se acepta «Lectura seca»: hay un cobro en curso" in it })
        sigue.complete(Unit)
        esperar { it.trabajo == null }
    }

    /** Un proceso nuevo con folios de antes: arma solo con la identidad guardada y relee la libreta; sin fila ≠ ilegible ≠ fila. */
    @Test fun `al abrir arma con la identidad guardada y relee la libreta sin depender de eventos`() {
        ArchivoDeIdentidad(carpeta.root).guardar(IdentidadTecleada.de("SERIE0099", "4321", ""))
        val (x, y, z) = List(3) { AttemptId.nuevo() }
        HistorialLocal(File(carpeta.root, "historial")).apply { anotarFolio(x, 100); anotarFolio(y, 200); anotarFolio(z, 300) }
        filas.filas[x] = IntentoEntity(x.valor, Operacion.VENTA, VENUE, 100, false, "1", "S1", Ambiente.SANDBOX, "x", Origen.LOCAL_SIN_SOLICITUD, estado = EstadoDelIntento.APROBADO, enviadoEnMs = 5)
        filas.ilegibles += z
        val otro = nuevo(HistorialLocal(File(carpeta.root, "historial")))
        espera { otro.folios.first { "— ${x.valor} \$1.00: la libreta se lee cuando el motor esté armado" in it } }
        otro.arrancar()
        val s = esperar(otro) { it.motor == EstadoDelMotor.Armado }
        val texto = espera { otro.folios.first { "fila APROBADO" in it } }
        assertTrue(texto, "— ${y.valor} \$2.00: sin fila guardada por el motor" in texto)
        assertTrue(texto, "— ${z.valor} \$3.00: fila ILEGIBLE (IllegalStateException): por confirmar" in texto)
        assertTrue(texto, "Libreta: reportes pendientes 0 · filas ilegibles 1" in texto)
        assertEquals(true, s.guardadaEsLaDelMotor)
        assertEquals(1, fabricas.get())
    }

    private companion object {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Sintética: el PAN de prueba público de Visa y $25.37. */
        val TARJETA = mapOf(0x57 to hex("4111111111111111D291210100000000000F"), 0x9F02 to hex("000000002537"))
    }
}
