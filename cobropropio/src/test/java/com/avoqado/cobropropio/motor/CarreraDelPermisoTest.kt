package com.avoqado.cobropropio.motor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.PuertoBlumon
import com.avoqado.cobropropio.blumon.SesionBlumon
import com.avoqado.cobropropio.blumon.TokenDeBlumon
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.EventoDeCobro.PorConfirmar
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.CredencialDelServidor
import com.avoqado.cobropropio.servidor.ReversoDelServidorHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

/**
 * r2 familia B y R1-3, la carrera REAL (tiempo de verdad, OkHttp y MockWebServer) con el cliente PROPIO del SERVIDOR: llega el
 * 409 «ya está cobrado» con el cuerpo atorado. Con dos relojes (o un interceptor que lee el cuerpo antes de `onResponse`), el
 * de afuera vencía primero y el motor reversaba un cobro que el SERVIDOR ya registró; con uno solo, el código que llegó veta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarreraDelPermisoTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fondo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val arranque = System.nanoTime()
    private val ahora: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - arranque) }
    private var db: LibretaDatabase? = null

    @After fun cerrar() {
        fondo.cancel()
        server.shutdown()
        db?.close()
    }

    /** La venta nunca contesta; la venta y el reverso, si salieran, se cuentan. */
    private class BlumonMudo : PuertoBlumon {
        val reversos = AtomicInteger()
        val ventas = AtomicInteger()
        val ventaSalio = CompletableDeferred<Unit>()
        override suspend fun iniciarSesion(espera: Duration) =
            SesionBlumon("token", ByteArray(16) { (it + 1).toByte() }, "FFFF9876543210E00000".hexABytes(), 10)
        override suspend fun renovarToken(espera: Duration): TokenDeBlumon? = TokenDeBlumon("token-2")
        override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta {
            ventas.incrementAndGet()
            ventaSalio.complete(Unit)
            delay(600_000)
            return Respuesta.SinRespuesta("TIEMPO")
        }
        override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta {
            reversos.incrementAndGet()
            return Respuesta.Http(200, """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA","simulation":false}}""")
        }
        override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta =
            throw UnsupportedOperationException()
        override suspend fun cancelar(
            sesion: SesionBlumon, operationNumber: Long, cuerpo: CuerpoDeBlumon, espera: Duration, alLeer: (Respuesta) -> Unit,
        ): Respuesta = throw UnsupportedOperationException()
    }

    @Test(timeout = 30_000) fun `un 409 con el cuerpo atorado en el cliente propio no reversa`() = runBlocking {
        val relojes = Relojes(
            esperaSesion = 5_000, esperaLectura = 5_000, esperaRespuesta = 300, peorCasoCajera = 8_000, esperaPermiso = 3_000,
            esperaReverso = 1_000, pausaEntreReversos = 200, techoDelReverso = 9_000,
        )
        val yaCobrado = """{"payment":{"paymentId":"cpay123","amountCents":10000},"success":false,"code":"ATTEMPT_ALREADY_RECORDED"}"""
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/intencion") -> MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                    .setBody(yaCobrado).throttleBody(4, 1, TimeUnit.SECONDS)
                else -> MockResponse().setResponseCode(500)
            }
        }
        server.start()
        val servidor = ReversoDelServidorHttp(server.url("/").toString(), object : CredencialDelServidor {
            override fun vigente() = "jwt"
            override suspend fun renovar(rechazado: String, espera: Duration): String? = null
        })
        val blumon = BlumonMudo()
        val libreta = Libreta(LibretaDatabase.constructor(context, File(carpeta.root, "carrera.db")).allowMainThreadQueries().build().also { db = it }, ahora, Instant::now)
        val lector = object : LectorDeTarjeta {
            override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long) =
                ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(importeCentavos))
        }
        val motor = MotorDeCobro(
            Identidad("AVOQADO", "388", "1234567890", "9000000001", "A910S", "fase2", Ambiente.SANDBOX), lector,
            object : Red { override fun hayRed() = true }, blumon, servidor, libreta, ColaDeResultados(libreta, servidor),
            HuellaHmac(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256")), CatalogoDeRechazos.BLUMON, relojes, fondo, ahora, Instant::now,
        )
        val s = SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 10_000, Origen.VINCULADO_AL_POS)

        val eventos = motor.cobrar(s).toList()

        assertEquals(PorConfirmar(s.importeCentavos), eventos.last())
        assertEquals("el 409 que llegó veta: nunca se reversa", 0, blumon.reversos.get())
        assertEquals(EstadoDelIntento.NO_SE, libreta.buscar(s.id)?.estado)
    }

    private class Mundo(val motor: MotorDeCobro, val blumon: BlumonMudo, val libreta: Libreta)

    /** Tiempo real, el `fondo` de la clase y Room de verdad; [antesDelCommit] corre DENTRO de cada transacción. */
    private fun mundo(
        antesDelCommit: (String) -> Unit = {},
        antesDeLeer: suspend () -> Unit = {},
        esperaLibreta: Long = 1_000,
    ): Mundo {
        val relojes = Relojes(
            esperaSesion = 5_000, esperaLectura = 5_000, esperaRespuesta = 300, peorCasoCajera = 8_000, esperaPermiso = 3_000,
            esperaReverso = 1_000, pausaEntreReversos = 200, techoDelReverso = 9_000, esperaLibreta = esperaLibreta,
        )
        server.start()
        val servidor = ReversoDelServidorHttp(server.url("/").toString(), object : CredencialDelServidor {
            override fun vigente() = "jwt"
            override suspend fun renovar(rechazado: String, espera: Duration): String? = null
        })
        val blumon = BlumonMudo()
        val base = LibretaDatabase.constructor(context, File(carpeta.root, "mundo.db")).allowMainThreadQueries().build().also { db = it }
        // Ola final, 4ª parte: la base se abre AQUÍ (crear el archivo y el esquema). Si la primera escritura del motor la abría,
        // en una JVM fría (esta clase sola, o la primera del módulo) tardaba más que `esperaLibreta` (1 s de reloj real) y la
        // venta no alcanzaba a anotarse: «cortar y cancelar fondo…» salía roja con la fila en `null`.
        base.openHelper.writableDatabase
        val libreta = Libreta(base, ahora, Instant::now, Libreta.PROCESO_ID, antesDelCommit)
        val lector = object : LectorDeTarjeta {
            override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
                antesDeLeer()
                return ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(importeCentavos))
            }
        }
        val motor = MotorDeCobro(
            Identidad("AVOQADO", "388", "1234567890", "9000000001", "A910S", "fase2", Ambiente.SANDBOX), lector,
            object : Red { override fun hayRed() = true }, blumon, servidor, libreta, ColaDeResultados(libreta, servidor),
            HuellaHmac(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256")), CatalogoDeRechazos.BLUMON, relojes, fondo, ahora, Instant::now,
        )
        return Mundo(motor, blumon, libreta)
    }

    private fun esperarFila(m: Mundo, s: SolicitudDeVenta, estado: EstadoDelIntento) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (m.libreta.buscar(s.id)?.estado != estado && System.nanoTime() < limite) Thread.sleep(20)
    }

    /**
     * R6-1: la venta ya salió, Room está ocupado (4 s dentro de la transacción que compromete el reverso) y se cancela `fondo`.
     * La cajera recibe `PorConfirmar` sin esperar a Room.
     */
    @Test(timeout = 30_000) fun `cancelar fondo con Room ocupado suelta a la cajera sin esperar`() = runBlocking {
        val ocupado = CompletableDeferred<Unit>()
        val m = mundo(antesDelCommit = { op -> if (op == "comprometerAReversar") { ocupado.complete(Unit); Thread.sleep(4_000) } })
        val s = SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 10_000, Origen.VINCULADO_AL_POS)
        val eventos = async(Dispatchers.Default) { m.motor.cobrar(s).toList() }
        ocupado.await()
        val inicio = System.nanoTime()
        fondo.cancel()
        val vistos = withTimeout(10_000) { eventos.await() }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio)
        assertEquals(PorConfirmar(s.importeCentavos), vistos.last())
        // Ola final, 4ª parte: Room está ocupado 4 s y la cajera sale a `esperaLibreta` (1 s); 3 s de techo prueba lo mismo sin
        // depender de un margen de 500 ms.
        assertTrue("tardó $ms ms: la cajera no espera a Room", ms < 3_000)
        fondo.coroutineContext[Job]!!.join() // el cierre lee la fila cuando Room se desocupa
        assertEquals(1, m.blumon.ventas.get())
    }

    /**
     * Test 21 (reemplaza al de R7-1, que cancelaba la escucha): `cortar(s)` y cancelar `fondo` a la vez, en hilos de verdad y
     * con tope de tiempo real. La venta todavía no sale (la lectura espera): nadie se traba, cero POST, y `cortar` contesta
     * `CORTADO` o `SIN_CONFIRMAR` (si `fondo` ya no pudo escribir el veto; el `Registro` quedó frenado en RAM).
     */
    @Test(timeout = 30_000) fun `cortar y cancelar fondo a la vez nunca traba ningun hilo`() = runBlocking {
        val lectura = CompletableDeferred<Unit>()
        val m = mundo(antesDeLeer = { lectura.await() })
        val s = SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 10_000, Origen.VINCULADO_AL_POS)
        val quienEscucha = launch(Dispatchers.Unconfined) { m.motor.cobrar(s).collect {} }
        esperarFila(m, s, EstadoDelIntento.ANOTADO)
        val corte = java.util.concurrent.atomic.AtomicReference<ResultadoDelCorte?>(null)
        val a = Thread { corte.set(runBlocking { m.motor.cortar(s) }) }
        val b = Thread { fondo.cancel() }
        a.start()
        b.start()
        a.join(5_000)
        b.join(5_000)
        assertFalse("el hilo A se trabó", a.isAlive)
        assertFalse("el hilo B se trabó", b.isAlive)
        assertTrue("${corte.get()}", corte.get() == ResultadoDelCorte.CORTADO || corte.get() == ResultadoDelCorte.SIN_CONFIRMAR)
        lectura.complete(Unit)
        withTimeout(5_000) { quienEscucha.join() }
        esperarFila(m, s, EstadoDelIntento.NO_SALIO)
        assertEquals("cero POST de venta", 0, m.blumon.ventas.get())
        assertEquals(EstadoDelIntento.NO_SALIO, m.libreta.buscar(s.id)?.estado)
    }

    /**
     * Test 26 (R2-1): con el barrido de `recuperar()` detenido DENTRO de su transacción (ya tomó la foto de los vivos), se da de
     * alta un intento: su `anotar` espera a que la transacción termine, y el barrido no cierra la venta nueva.
     */
    @Test(timeout = 30_000) fun `el alta contra la foto de recuperar espera y no se cierra`() = runBlocking {
        val enElBarrido = java.util.concurrent.CountDownLatch(1)
        val orden = java.util.concurrent.CopyOnWriteArrayList<String>()
        val lectura = CompletableDeferred<Unit>()
        val m = mundo(
            antesDelCommit = { op ->
                orden += op
                // Menos que `esperaLibreta`: si la lectura de H también espera a la transacción, igual alcanza a admitir. Ola
                // final, 4ª parte: `esperaLibreta` de 5 s aquí (antes 1 s, que dejaba 300 ms de margen en una máquina lenta).
                if (op == "recuperarAlArrancar") { enElBarrido.countDown(); Thread.sleep(700) }
            },
            antesDeLeer = { lectura.await() },
            esperaLibreta = 5_000,
        )
        val s = SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 10_000, Origen.VINCULADO_AL_POS)
        val barrido = async(Dispatchers.Default) { m.motor.recuperar() }
        assertTrue(enElBarrido.await(5, TimeUnit.SECONDS))
        val quienCobra = launch(Dispatchers.Default) { m.motor.cobrar(s).collect {} }
        val cerrados = barrido.await()
        esperarFila(m, s, EstadoDelIntento.ANOTADO)
        assertFalse(s.id in cerrados)
        assertEquals(EstadoDelIntento.ANOTADO, m.libreta.buscar(s.id)?.estado)
        assertTrue("$orden", orden.indexOf("anotar") > orden.indexOf("recuperarAlArrancar"))
        lectura.complete(Unit)
        quienCobra.cancel()
    }
}
