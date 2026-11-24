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
import com.avoqado.cobropropio.dominio.EventoDeCobro.Aprobado
import com.avoqado.cobropropio.dominio.EventoDeCobro.Autorizando
import com.avoqado.cobropropio.dominio.EventoDeCobro.Cancelada
import com.avoqado.cobropropio.dominio.EventoDeCobro.Iniciado
import com.avoqado.cobropropio.dominio.EventoDeCobro.NoSalioAlBanco
import com.avoqado.cobropropio.dominio.EventoDeCobro.PideTarjeta
import com.avoqado.cobropropio.dominio.EventoDeCobro.PorConfirmar
import com.avoqado.cobropropio.dominio.EventoDeCobro.Rechazado
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.SolicitudDeCancelacion
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.con
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.toque
import com.avoqado.cobropropio.libreta.DatosDelEnvio
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.AcuseDelResultado
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.PermisoDeReverso
import com.avoqado.cobropropio.servidor.ResultadoDelReverso
import com.avoqado.cobropropio.servidor.ReversoDelServidor
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Tarea 9: la cancelación con la tarjeta de una venta aprobada, por el MISMO motor (entrada, `Registro`, corte y salidas de
 * `cobrar`). La venta original sale por el motor (así su fila trae las huellas reales); los dobles de Blumon y del SERVIDOR
 * son programables y el tiempo es virtual.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MotorDeCobroCancelacionTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pared = Instant.parse("2026-11-20T18:00:00Z")
    private val abiertas = mutableListOf<LibretaDatabase>()
    private val sandbox = Identidad("AVOQADO", "388", "1234567890", "9000000001", "A910S", "fase2", Ambiente.SANDBOX)
    private val produccion = sandbox.copy(ambiente = Ambiente.PRODUCCION)
    private val huella = HuellaHmac(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256"))

    @After fun cerrar() {
        abiertas.forEach { if (it.isOpen) it.close() }
    }

    // ───────────── El mundo ─────────────

    /** El `io` de la libreta, que se puede detener: lo que llega mientras está detenido espera («Room detenido»). */
    private class IoDetenible(private val adentro: CoroutineDispatcher) : CoroutineDispatcher() {
        private val pendientes = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var detenido = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) { if (detenido) return run { pendientes += context to block } }
            adentro.dispatch(context, block)
        }

        fun detener() = synchronized(this) { detenido = true }

        fun seguir() {
            val p = synchronized(this) { detenido = false; pendientes.toList().also { pendientes.clear() } }
            p.forEach { (c, b) -> adentro.dispatch(c, b) }
        }
    }

    /** La cancelación es otra lectura de la MISMA tarjeta: otro criptograma y otro contador (9F26, 9F36). */
    private fun Lectura.otraVez(n: Int) = con(tags = mapOf(0x9F36 to "000$n", 0x9F26 to "B1B2C3D4E5F6071$n"))

    private class LectorFalso : LectorDeTarjeta {
        val operaciones = mutableListOf<Operacion>()
        var demora = 1_000L
        var ventas = 0
        var venta: (Long) -> Lectura = { LecturasDePrueba.visaPlasticoVenta(it).toque(++ventas) }
        var cancelacion: (Long) -> Lectura = { LecturasDePrueba.visaPlasticoCancelacion(it).con(tags = mapOf(0x9F36 to "0002")) }

        override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
            operaciones += operacion
            delay(demora)
            return ResultadoDeLectura.Leida(if (operacion == Operacion.VENTA) venta(importeCentavos) else cancelacion(importeCentavos))
        }
    }

    private class BlumonFalso(private val ahora: () -> Long) : PuertoBlumon {
        var venta: suspend () -> Respuesta = { delay(1_000); http(200, APROBADA) }
        var preflight: suspend () -> Respuesta = { delay(500); http(200, PREFLIGHT_SI) }
        var cancelacion: suspend () -> Respuesta = { delay(1_000); http(200, CANCELADA) }
        val preflights = mutableListOf<Long>()
        /** (folio de la original, momento) de cada POST de cancelación. */
        val cancelaciones = mutableListOf<Pair<Long, Long>>()
        val referenciasDeCancelacion = mutableListOf<String>()
        var reversos = 0
        /** Lo que el transporte entrega al leer, de la cancelación en curso (el buzón). */
        var alLeer: (Respuesta) -> Unit = {}

        override suspend fun iniciarSesion(espera: Duration): SesionBlumon? =
            SesionBlumon("token-inicial", ByteArray(16) { (it + 1).toByte() }, BASE_KSN.hexABytes(), 10)

        override suspend fun renovarToken(espera: Duration): TokenDeBlumon? = TokenDeBlumon("token-2")

        override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta =
            conEco(venta(), referenciaDe(cuerpo))

        override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta {
            reversos++
            return conEco(http(200, REVERSADA), referenciaDe(congelado.cuerpo))
        }

        override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta {
            preflights += operationNumber
            return preflight()
        }

        override suspend fun cancelar(
            sesion: SesionBlumon, operationNumber: Long, cuerpo: CuerpoDeBlumon, espera: Duration, alLeer: (Respuesta) -> Unit,
        ): Respuesta {
            cancelaciones += operationNumber to ahora()
            val ref = referenciaDe(cuerpo)
            referenciasDeCancelacion += ref
            this.alLeer = { alLeer(conEco(it, ref)) }
            return conEco(cancelacion(), ref)
        }
    }

    private class ServidorFalso : ReversoDelServidor {
        val permisos = mutableListOf<AttemptId>()
        override suspend fun pedirPermiso(venueId: String, id: AttemptId, espera: Duration): PermisoDeReverso {
            permisos += id
            return PermisoDeReverso.Concedido("evt-1")
        }

        override suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado =
            throw UnsupportedOperationException("la cancelación no reporta reversos")
    }

    private inner class Mundo(
        val prueba: TestScope,
        val identidad: Identidad = sandbox,
        val archivo: File = File(carpeta.root, "${UUID.randomUUID()}.db"),
        proceso: String = Libreta.PROCESO_ID,
    ) {
        val ahora: () -> Long = { prueba.testScheduler.currentTime }
        val io = IoDetenible(StandardTestDispatcher(prueba.testScheduler))
        val trabajo = Job(prueba.coroutineContext[Job])
        val fondo = CoroutineScope(prueba.coroutineContext + trabajo)
        val db = LibretaDatabase.constructor(context, archivo).allowMainThreadQueries().build().also { abiertas += it }
        /** Corren DENTRO de la transacción de esa operación de la libreta, antes del commit. */
        val ganchos = mutableMapOf<String, () -> Unit>()
        val libreta = Libreta(db, ahora, { pared }, proceso) { op -> ganchos[op]?.invoke() }
        val lector = LectorFalso()
        val blumon = BlumonFalso(ahora)
        val servidor = ServidorFalso()
        val motor = MotorDeCobro(
            identidad, lector, object : Red { override fun hayRed() = true }, blumon, servidor, libreta,
            ColaDeResultados(libreta, servidor, io), huella, CatalogoDeRechazos.BLUMON, Relojes(), fondo, ahora, { pared }, io,
        )
        val tardias = mutableListOf<Pair<AttemptId, EventoDeCobro>>()
        private val escucha = fondo.launch(start = CoroutineStart.UNDISPATCHED) { motor.resolucionesTardias.collect { tardias += it } }

        fun cerrar() {
            escucha.cancel()
            trabajo.complete()
        }

        suspend fun cancelar(c: SolicitudDeCancelacion): List<Pair<EventoDeCobro, Long>> =
            motor.cancelar(c).map { it to prueba.currentTime }.toList()

        suspend fun terminal(c: SolicitudDeCancelacion): EventoDeCobro = cancelar(c).last().first

        /** Una venta aprobada por el motor (la fila trae sus huellas de verdad). */
        suspend fun ventaAprobada(venue: String = "venue-1"): SolicitudDeVenta {
            val v = SolicitudDeVenta(AttemptId.nuevo(), venue, 10_000, Origen.VINCULADO_AL_POS)
            assertEquals(APROBADO_VISA, motor.cobrar(v).toList().last())
            esperarTodo()
            return v
        }

        fun cancelacionDe(v: SolicitudDeVenta, venue: String = v.venueId) = SolicitudDeCancelacion(AttemptId.nuevo(), venue, v.id)

        fun fila(id: AttemptId): IntentoEntity = requireNotNull(libreta.buscar(id))

        fun esperarTodo() = prueba.advanceUntilIdle()

        fun tardiasDe(id: AttemptId) = tardias.filter { it.first == id }.map { it.second }

        /** Una venta escrita directo en la libreta, `ENVIADO`, con las huellas de la tarjeta de prueba. */
        fun ventaEnviadaDirecta(posId: String = identidad.posId): SolicitudDeVenta {
            val v = SolicitudDeVenta(AttemptId.nuevo(), "venue-1", 10_000, Origen.VINCULADO_AL_POS)
            val f = IntentoEntity(
                attemptId = v.id.valor, operacion = Operacion.VENTA, venueId = v.venueId, importeCentavos = v.importeCentavos,
                vinculado = true, posId = posId, serieBlumon = identidad.serieBlumon, ambiente = identidad.ambiente,
                versionSdk = identidad.versionSdk, origen = v.origen,
            )
            assertTrue(libreta.anotar(f))
            val datos = DatosDelEnvio(
                null, null, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484", huella.de(LecturasDePrueba.PAN_VISA),
                huella.de(LecturasDePrueba.visaPlasticoVenta().huellaDeLectura()!!),
            )
            assertTrue(libreta.marcarEnviado(v.id, 1, datos))
            return v
        }
    }

    private fun prueba(identidad: Identidad = sandbox, bloque: suspend Mundo.() -> Unit) = runTest {
        val m = Mundo(this, identidad)
        try {
            m.bloque()
        } finally {
            m.cerrar()
        }
    }

    private companion object {
        const val BASE_KSN = "FFFF9876543210E00000"
        const val ECO = "@REF@"
        const val APROBADA = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","batch":"$ECO","simulation":false}}"""
        const val REVERSADA = """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA","batch":"$ECO","simulation":false}}"""
        /** El éxito de la cancelación: sin autorización, con el eco del folio de la cancelación. */
        const val CANCELADA = """{"status":true,"id":22222,"dataResponse":{"batch":"$ECO","simulation":false}}"""
        /** El «sí» sin bloque `error` (R14-1) y el «no» del piloto. */
        const val PREFLIGHT_SI = """{"status":true,"dataResponse":{"simulation":false}}"""
        const val PREFLIGHT_NO = """{"status":false,"error":{"code":"TX_024"}}"""
        val APROBADO_VISA = Aprobado(12345, "ABC123", null, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484")
        val SILENCIO: suspend () -> Respuesta = { delay(600_000); http(200, CANCELADA) }
        const val CAJERA = 30_000L
        /** Un folio con el formato viejo (36): Room lo lee, la validación de hoy no lo pasa. */
        const val ID_DE_36 = "0f8fad5b-d9cb-469f-a165-b0867728950e"

        fun http(codigo: Int, cuerpo: String) = Respuesta.Http(codigo, cuerpo)
        fun error(codigo: String) = """{"status":false,"error":{"code":"$codigo"}}"""
        fun noSalio(m: MotivoNoSalio) = NoSalioAlBanco(m, null)

        fun referenciaDe(cuerpo: CuerpoDeBlumon): String {
            val bytes = Buffer().also { cuerpo.comoRequestBody().writeTo(it) }.readByteArray()
            return JsonParser.parseString(String(bytes)).asJsonObject.get("reference").asString
        }

        /** Pone el eco en un sobre con `@REF@`. Sólo pruebas: lee el cuerpo por reflexión. */
        fun conEco(r: Respuesta, ref: String): Respuesta {
            if (r !is Respuesta.Http) return r
            val cuerpo = Respuesta.Http::class.java.getDeclaredField("cuerpo").apply { isAccessible = true }.get(r) as String
            return if (ECO in cuerpo) Respuesta.Http(r.codigo, cuerpo.replace(ECO, ref)) else r
        }
    }

    // ───────────── El brief ─────────────

    /**
     * Ola final, 4ª parte · 3 (R24-4): tras una venta y su cancelación, las dos filas guardan sólo HMAC (64 hex) en
     * `huellaTarjeta` y `huellaLectura`, y ningún campo de la fila trae el PAN, la banda ni el criptograma.
     */
    @Test fun `las filas de la venta y la cancelacion solo guardan HMAC`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(Cancelada(22222), cancelar(c).last().first)
        val hmac = Regex("^[0-9A-F]{64}$")
        val prohibidos = listOf(LecturasDePrueba.PAN_VISA, LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA), "A1B2C3D4E5F60718")
        for (f in listOf(fila(v.id), fila(c.id))) {
            assertTrue("${f.operacion} huellaTarjeta", hmac.matches(requireNotNull(f.huellaTarjeta)))
            assertTrue("${f.operacion} huellaLectura", hmac.matches(requireNotNull(f.huellaLectura)))
            val campos = IntentoEntity::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.isAccessible = true; it.get(f)?.toString().orEmpty() }
            for ((i, p) in prohibidos.withIndex()) assertTrue("${f.operacion}: dato prohibido #$i", campos.none { p in it })
        }
    }

    @Test fun `cancela con la misma tarjeta`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        val inicio = prueba.currentTime
        val eventos = cancelar(c)
        assertEquals(
            listOf(Iniciado(c.id, sandbox, Operacion.CANCELACION, v.importeCentavos), PideTarjeta, Autorizando, Cancelada(22222)),
            eventos.map { it.first },
        )
        assertEquals(inicio + 2_500, eventos.last().second) // lectura 1 s, preflight 0.5 s, cancelación 1 s
        val f = fila(c.id)
        assertEquals(EstadoDelIntento.CANCELADA, f.estado)
        assertEquals(Operacion.CANCELACION, f.operacion)
        assertEquals(22222L, f.operationNumber)
        assertEquals(v.id.valor, f.operacionOriginal)
        assertEquals(v.importeCentavos, f.importeCentavos)
        assertEquals(huella.de(LecturasDePrueba.PAN_VISA), f.huellaTarjeta)
        assertEquals(listOf(12345L), blumon.preflights)
        assertEquals(listOf(12345L), blumon.cancelaciones.map { it.first })
        assertEquals("la reference es el folio de la cancelación", listOf(c.id.valor), blumon.referenciasDeCancelacion)
        assertEquals(listOf(Operacion.VENTA, Operacion.CANCELACION), lector.operaciones)
        esperarTodo()
        assertEquals(0, blumon.reversos)
        assertTrue(servidor.permisos.isEmpty())
        assertEquals(listOf<EventoDeCobro>(Cancelada(22222)), tardiasDe(c.id))
        // Regla 7: la fila de la venta original no cambia al cancelarla, así que su lápida sigue diciendo Aprobado.
        assertEquals(EstadoDelIntento.APROBADO, fila(v.id).estado)
        assertEquals(listOf<EventoDeCobro>(APROBADO_VISA), motor.cobrar(v).toList())
    }

    /** También el preflight contra el CAS: el preflight dijo que sí y aun así la tarjeta no es la de la venta. */
    @Test fun `otra tarjeta no sale`() = prueba {
        val v = ventaAprobada()
        lector.cancelacion = { LecturasDePrueba.mastercardPlasticoCancelacion(it).otraVez(2) }
        val c = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.OTRA_TARJETA), terminal(c))
        assertEquals("el preflight sí salió", 1, blumon.preflights.size)
        assertTrue("cero llamadas a cancelar", blumon.cancelaciones.isEmpty())
        assertEquals(EstadoDelIntento.NO_SALIO, fila(c.id).estado)
        assertEquals(MotivoNoSalio.OTRA_TARJETA, fila(c.id).motivoNoSalio)
    }

    @Test fun `otro ambito no sale`() = prueba {
        val v = ventaEnviadaDirecta(posId = "999")
        assertTrue(libreta.guardarAprobacion(v.id, 777, "ABC") is com.avoqado.cobropropio.libreta.Arbitraje.Vigente)
        val c = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.OTRO_AMBITO), terminal(c))
        assertEquals(MotivoNoSalio.OTRO_AMBITO, fila(c.id).motivoNoSalio)
        assertTrue(lector.operaciones.isEmpty())
        assertTrue(blumon.preflights.isEmpty())
    }

    @Test fun `lectura repetida no sale`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = { delay(1_000); http(200, error("51")) }
        assertEquals(Rechazado("51", "FONDOS INSUFICIENTES"), terminal(cancelacionDe(v)))
        val otra = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.LECTURA_REPETIDA), terminal(otra)) // la misma lectura de la primera cancelación
        assertEquals(1, blumon.cancelaciones.size)
        assertEquals(MotivoNoSalio.LECTURA_REPETIDA, fila(otra.id).motivoNoSalio)
    }

    /** Regla N: la fila queda `NO_SALIO` y el folio contesta lo mismo, aunque la original se apruebe después y tras reiniciar. */
    @Test fun `sin venta aprobada no sale`() = prueba {
        val nadie = SolicitudDeCancelacion(AttemptId.nuevo(), "venue-1", AttemptId.nuevo())
        assertEquals(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA), terminal(nadie))
        assertEquals(EstadoDelIntento.NO_SALIO, fila(nadie.id).estado)
        assertEquals(MotivoNoSalio.SIN_VENTA_APROBADA, fila(nadie.id).motivoNoSalio)

        val v = ventaEnviadaDirecta()
        val c = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA), terminal(c))
        assertTrue(libreta.guardarAprobacion(v.id, 777, "ABC") is com.avoqado.cobropropio.libreta.Arbitraje.Vigente)
        assertEquals(listOf(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA)), cancelar(c).map { it.first })
        assertTrue(lector.operaciones.isEmpty())
        assertTrue(blumon.preflights.isEmpty())
        esperarTodo()
        cerrar()
        // Otro proceso sobre la misma libreta: el folio ya está usado y contesta desde su fila.
        db.close()
        val otro = Mundo(prueba, archivo = archivo)
        try {
            assertEquals(listOf(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA)), otro.cancelar(c).map { it.first })
            assertTrue(otro.blumon.preflights.isEmpty())
        } finally {
            otro.cerrar()
        }
    }

    @Test fun `original que es cancelacion no se cancela`() = prueba {
        val v = ventaAprobada()
        val c1 = cancelacionDe(v)
        assertEquals(Cancelada(22222), terminal(c1))
        val c2 = SolicitudDeCancelacion(AttemptId.nuevo(), "venue-1", c1.id)
        assertEquals(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA), terminal(c2))
        assertEquals(1, blumon.cancelaciones.size)
    }

    @Test fun `segunda cancelacion mientras la primera esta en duda no sale`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = SILENCIO
        val c1 = cancelacionDe(v)
        val primera = prueba.async { cancelar(c1) }
        prueba.advanceTimeBy(5_000) // la primera ya está en el banco
        assertEquals(EstadoDelIntento.ENVIADO, fila(c1.id).estado)
        assertEquals(noSalio(MotivoNoSalio.CANCELACION_YA_EXISTE), terminal(cancelacionDe(v)))
        assertEquals(PorConfirmar(v.importeCentavos), primera.await().last().first)
        assertEquals(EstadoDelIntento.NO_SE, fila(c1.id).estado)
        assertEquals(noSalio(MotivoNoSalio.CANCELACION_YA_EXISTE), terminal(cancelacionDe(v))) // en duda sigue vetando
        assertEquals(1, blumon.cancelaciones.size)
    }

    @Test fun `cancelacion con la misma lectura que la venta no sale`() = prueba {
        val v = ventaAprobada()
        lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it) } // el mismo 9F26 + 9F36 que la venta
        val c = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.LECTURA_REPETIDA), terminal(c))
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    @Test fun `cancelacion de otro venue con el mismo ambito no sale`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v, venue = "venue-2")
        // R14-5: el importe de la venta no se le enseña a otra sucursal: ni en el evento ni en la fila.
        assertEquals(listOf(Iniciado(c.id, sandbox, Operacion.CANCELACION, 0), noSalio(MotivoNoSalio.OTRO_AMBITO)), cancelar(c).map { it.first })
        assertEquals(0L, fila(c.id).importeCentavos)
        assertTrue(blumon.preflights.isEmpty())
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
    }

    /** r1 A: el preflight va ANTES de pedir la tarjeta: con un «no», el cliente nunca saca la tarjeta. */
    @Test fun `preflight negativo no cancela`() = prueba {
        val v = ventaAprobada()
        for (preflight in listOf(http(200, PREFLIGHT_NO), http(200, "<html>"), http(404, PREFLIGHT_SI))) {
            blumon.preflight = { delay(500); preflight }
            val c = cancelacionDe(v)
            assertEquals(
                listOf(Iniciado(c.id, sandbox, Operacion.CANCELACION, v.importeCentavos), noSalio(MotivoNoSalio.CANCELACION_NO_PERMITIDA)),
                cancelar(c).map { it.first },
            )
            assertEquals(MotivoNoSalio.CANCELACION_NO_PERMITIDA, fila(c.id).motivoNoSalio)
            assertNull("sin contador: el preflight va antes", fila(c.id).contadorUsado)
        }
        assertEquals("cero lecturas de la cancelación", listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** r1 A: con un «sí», la tarjeta se pide DESPUÉS del preflight. */
    @Test fun `con un si la tarjeta se pide despues del preflight`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        val inicio = prueba.currentTime
        val eventos = cancelar(c)
        assertEquals(inicio + 500, eventos.single { it.first == PideTarjeta }.second) // el preflight tarda 500 ms
        assertEquals(Cancelada(22222), eventos.last().first)
    }

    /** r1 A: un corte mientras el preflight está retenido gana: cero lecturas y cero llamadas a cancelar. */
    @Test fun `cortar mientras el preflight esta retenido`() = prueba {
        val v = ventaAprobada()
        blumon.preflight = { delay(5_000); http(200, PREFLIGHT_SI) }
        val c = cancelacionDe(v)
        val visto = prueba.async { cancelar(c).map { it.first } }
        prueba.advanceTimeBy(1_000)
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(c))
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), visto.await().last())
        assertTrue(PideTarjeta !in visto.await())
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** r1 B: un preflight que no contestó es «sin conexión», no «Blumon no deja cancelar». */
    @Test fun `preflight sin respuesta es sin conexion`() = prueba {
        val v = ventaAprobada()
        blumon.preflight = { delay(600_000); http(200, PREFLIGHT_SI) }
        val c = cancelacionDe(v)
        val inicio = prueba.currentTime
        assertEquals(noSalio(MotivoNoSalio.SIN_RED) to inicio + 15_000, cancelar(c).last())
        assertEquals(MotivoNoSalio.SIN_RED, fila(c.id).motivoNoSalio)
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** Ronda 2 · Q: las razones del preflight. No salió ⇒ INTERRUMPIDO; transporte dudoso ⇒ SIN_RED; nunca se pide la tarjeta. */
    @Test fun `las razones del preflight`() = prueba {
        val v = ventaAprobada()
        for ((preflight, motivo) in listOf(
            Respuesta.NoEnviada to MotivoNoSalio.INTERRUMPIDO,
            http(503, PREFLIGHT_SI) to MotivoNoSalio.SIN_RED,
            http(401, PREFLIGHT_SI) to MotivoNoSalio.SIN_RED,
            http(429, PREFLIGHT_SI) to MotivoNoSalio.SIN_RED,
            http(200, PREFLIGHT_NO) to MotivoNoSalio.CANCELACION_NO_PERMITIDA,
        )) {
            blumon.preflight = { delay(500); preflight }
            val c = cancelacionDe(v)
            assertEquals(motivo.name, noSalio(motivo), terminal(c))
            assertEquals(motivo, fila(c.id).motivoNoSalio)
        }
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** r1 C: si la original deja de estar aprobada entre el preflight y el envío, la fila dice eso (no «ya existe otra»). */
    @Test fun `la original que dejo de estar aprobada se dice como tal`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        motor.antesDelPuntoSinRetorno = {
            libreta.transicionar(v.id, EstadoDelIntento.APROBADO) { it.copy(estado = EstadoDelIntento.NO_SE) }
        }
        assertEquals(noSalio(MotivoNoSalio.SIN_VENTA_APROBADA), terminal(c))
        assertEquals(MotivoNoSalio.SIN_VENTA_APROBADA, fila(c.id).motivoNoSalio)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** r1 C: si la PROPIA fila ya no aplica (un veto directo, sin pasar por `cortar`), contesta la fila, sin motivo inventado. */
    @Test fun `la propia fila que ya no aplica contesta desde la fila`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        motor.antesDelPuntoSinRetorno = { assertTrue(libreta.vetarPorCorte(libreta.buscar(c.id)!!)) }
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(c))
        assertEquals(MotivoNoSalio.INTERRUMPIDO, fila(c.id).motivoNoSalio)
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** r1 D: algo truena DESPUÉS de que la cancelación salió ⇒ `NO_SE`, `PorConfirmar`, y nunca reverso ni reporte. */
    @Test fun `una falla despues de salir deja la cancelacion en duda sin reverso`() = prueba {
        val v = ventaAprobada()
        var una = true
        ganchos["transicionar"] = { if (una) { una = false; throw IllegalStateException("falla inyectada") } }
        val c = cancelacionDe(v)
        assertEquals(PorConfirmar(v.importeCentavos), terminal(c))
        esperarTodo()
        val f = fila(c.id)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(com.avoqado.cobropropio.libreta.EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(com.avoqado.cobropropio.libreta.EstadoDelReporte.NO_APLICA, f.reporte)
        assertTrue(!f.pudoEntrar)
        assertEquals(0, blumon.reversos)
        assertTrue(servidor.permisos.isEmpty())
    }

    /** H: el contrato de una cancelación es su venta original: el mismo folio con OTRA original, o en otro venue, es otro contrato. */
    @Test fun `un folio de cancelacion con otra original u otro venue es contrato distinto`() = prueba {
        val v = ventaAprobada()
        val w = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(Cancelada(22222), terminal(c))
        esperarTodo()
        val distinto = EventoDeCobro.ContratoDistinto
        val conOtraOriginal = SolicitudDeCancelacion(c.id, c.venueId, w.id)
        val enOtroVenue = SolicitudDeCancelacion(c.id, "venue-2", v.id)
        assertEquals("desde la lápida", listOf<EventoDeCobro>(distinto), motor.cancelar(conOtraOriginal).toList())
        assertEquals("desde la lápida", listOf<EventoDeCobro>(distinto), motor.cancelar(enOtroVenue).toList())
        esperarTodo()
        assertTrue(tardias.none { it.second == distinto })
        cerrar()
        db.close()
        val otro = Mundo(prueba, archivo = archivo, proceso = "otro-proceso")
        try {
            assertEquals("desde la fila", listOf<EventoDeCobro>(distinto), otro.motor.cancelar(conOtraOriginal).toList())
            assertEquals("desde la fila", listOf<EventoDeCobro>(distinto), otro.motor.cancelar(enOtroVenue).toList())
            assertEquals(listOf<EventoDeCobro>(Cancelada(22222)), otro.motor.cancelar(c).toList())
            otro.esperarTodo()
            assertTrue(otro.lector.operaciones.isEmpty())
            assertTrue(otro.blumon.preflights.isEmpty() && otro.blumon.cancelaciones.isEmpty())
            assertTrue(otro.tardias.none { it.second == distinto })
        } finally {
            otro.cerrar()
        }
        assertEquals(1, blumon.cancelaciones.size)
    }

    /** Ronda 2 · S (R15-2): la lectura con que se canceló una venta no sale en la cancelación de OTRA venta de la misma tarjeta. */
    @Test fun `la lectura de una cancelacion no sale en la de otra venta`() = prueba {
        val v = ventaAprobada()
        val w = ventaAprobada()
        assertEquals(Cancelada(22222), terminal(cancelacionDe(v)))
        val otra = cancelacionDe(w) // el lector repite la misma lectura
        assertEquals(noSalio(MotivoNoSalio.LECTURA_REPETIDA), terminal(otra))
        assertEquals(1, blumon.cancelaciones.size)
    }

    /** K: una venta que ya tiene una cancelación `Cancelada` no se cancela otra vez con un folio nuevo. */
    @Test fun `cancelar otra vez una venta ya cancelada no sale`() = prueba {
        val v = ventaAprobada()
        assertEquals(Cancelada(22222), terminal(cancelacionDe(v)))
        lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it).otraVez(3) }
        val otra = cancelacionDe(v)
        assertEquals(noSalio(MotivoNoSalio.CANCELACION_YA_EXISTE), terminal(otra))
        assertEquals(1, blumon.cancelaciones.size)
    }

    /** K: la salida de emergencia de una cancelación sin fila (importe todavía desconocido) es `PorConfirmar(0)`. */
    @Test fun `fondo cancelado antes de anotar una cancelacion da por confirmar en cero`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        motor.despuesDeBuscar = { id -> if (id == c.id) fondo.cancel() }
        val eventos = cancelar(c).map { it.first }
        assertEquals(PorConfirmar(0), eventos.last())
        assertTrue(eventos.none { it is NoSalioAlBanco })
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /**
     * C1 (Codex b1 r1 y r2, D4): una cancelación hermana ilegible hace tronar `anotarCancelacion` ANTES de que exista la fila del
     * folio nuevo. La cajera sale con «por confirmar» desde la memoria; después rige la regla N: el veto queda en la libreta y el
     * folio contesta «No salió (interrumpido)» desde ahí (tarde, al volver a pedirlo y tras reiniciar). Con OTRA venta es otro
     * contrato. Cero llamadas a Blumon y la hermana ilegible no se toca.
     */
    @Test fun `una cancelacion frenada por una hermana ilegible deja su veto y no sale`() = prueba {
        val v = ventaAprobada()
        val w = ventaAprobada() // otra venta APROBADO del mismo venue y ámbito
        // Como en LibretaTest: una hermana cerrada con un folio de 36 (Room la lee; la validación de hoy no la pasa).
        assertTrue(
            db.dao().insertar(
                IntentoEntity(
                    attemptId = ID_DE_36, operacion = Operacion.CANCELACION, venueId = v.venueId, importeCentavos = v.importeCentavos,
                    vinculado = true, posId = identidad.posId, serieBlumon = identidad.serieBlumon, ambiente = identidad.ambiente,
                    versionSdk = identidad.versionSdk, origen = v.origen, operacionOriginal = v.id.valor,
                    estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.OTRA_TARJETA, procesoId = Libreta.PROCESO_ID,
                ),
            ) != -1L,
        )
        fun hermana(): List<String?> =
            db.openHelper.readableDatabase.query("SELECT * FROM intentos WHERE attemptId = ?", arrayOf(ID_DE_36)).use { cur ->
                assertTrue(cur.moveToFirst())
                (0 until cur.columnCount).map { cur.getString(it) }
            }
        val antes = hermana()
        val c = cancelacionDe(v)
        val interrumpido = noSalio(MotivoNoSalio.INTERRUMPIDO)
        assertEquals("primero, desde la memoria", listOf<EventoDeCobro>(PorConfirmar(0)), cancelar(c).map { it.first })
        esperarTodo()
        assertEquals("el tardío sale de la fila", listOf(PorConfirmar(0), interrumpido), tardiasDe(c.id))
        val f = fila(c.id)
        assertEquals(EstadoDelIntento.NO_SALIO, f.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, f.motivoNoSalio)
        assertEquals(v.id.valor, f.operacionOriginal)
        assertEquals(listOf<EventoDeCobro>(interrumpido), motor.cancelar(c).toList())
        val conOtraVenta = SolicitudDeCancelacion(c.id, c.venueId, w.id)
        assertEquals(listOf<EventoDeCobro>(EventoDeCobro.ContratoDistinto), motor.cancelar(conOtraVenta).toList())
        esperarTodo()
        assertTrue(blumon.preflights.isEmpty() && blumon.cancelaciones.isEmpty())
        assertEquals(0, blumon.reversos)
        assertEquals("la hermana ilegible no se toca", antes, hermana())
        cerrar()
        db.close()
        val otro = Mundo(prueba, archivo = archivo, proceso = "otro-proceso")
        try {
            otro.motor.recuperar()
            assertEquals("tras reiniciar", listOf<EventoDeCobro>(interrumpido), otro.motor.cancelar(c).toList())
            assertEquals(listOf<EventoDeCobro>(EventoDeCobro.ContratoDistinto), otro.motor.cancelar(conOtraVenta).toList())
            otro.esperarTodo()
            assertEquals(EstadoDelIntento.NO_SALIO, otro.fila(c.id).estado)
            assertTrue(otro.lector.operaciones.isEmpty())
            assertTrue(otro.blumon.preflights.isEmpty() && otro.blumon.cancelaciones.isEmpty())
        } finally {
            otro.cerrar()
        }
    }

    /** C1, la vecina: cualquier OTRA falla antes de anotar sigue la regla N (veto durable y «No salió, interrumpido» tardío). */
    @Test fun `una cancelacion frenada por otra falla antes de anotar termina no salio interrumpido`() = prueba {
        val v = ventaAprobada()
        ganchos["anotarCancelacion"] = { throw IllegalStateException("falla inyectada") }
        val c = cancelacionDe(v)
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(0)), cancelar(c).map { it.first })
        esperarTodo()
        assertEquals(listOf(PorConfirmar(0), noSalio(MotivoNoSalio.INTERRUMPIDO)), tardiasDe(c.id))
        assertEquals(EstadoDelIntento.NO_SALIO, fila(c.id).estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, fila(c.id).motivoNoSalio)
        assertEquals(listOf<EventoDeCobro>(noSalio(MotivoNoSalio.INTERRUMPIDO)), motor.cancelar(c).toList())
        assertTrue(blumon.preflights.isEmpty() && blumon.cancelaciones.isEmpty())
    }

    /** N (R14-8): tras un apagón, la cancelación `ENVIADO` del proceso muerto queda `NO_SE` y sigue vetando un folio nuevo. */
    @Test fun `tras un apagon una cancelacion en duda sigue vetando`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = SILENCIO
        val c = cancelacionDe(v)
        prueba.launch { runCatching { cancelar(c) } }
        prueba.advanceTimeBy(5_000)
        assertEquals(EstadoDelIntento.ENVIADO, fila(c.id).estado)
        fondo.cancel() // el proceso muere con la cancelación en el banco
        cerrar()
        db.close()
        val otro = Mundo(prueba, archivo = archivo, proceso = "otro-proceso")
        try {
            otro.motor.recuperar()
            assertEquals(EstadoDelIntento.NO_SE, otro.fila(c.id).estado)
            otro.lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it).otraVez(3) }
            val nueva = otro.cancelacionDe(v)
            assertEquals(noSalio(MotivoNoSalio.CANCELACION_YA_EXISTE), otro.terminal(nueva))
            assertTrue(otro.blumon.cancelaciones.isEmpty())
        } finally {
            otro.cerrar()
        }
    }

    /** F1 (N2, N5): un corte en plena admisión: H leyó «sin fila» y el veto entra antes de anotar ⇒ contesta la fila. */
    @Test fun `un corte en plena admision de una cancelacion contesta desde la fila`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        var corte: ResultadoDelCorte? = null
        motor.despuesDeBuscar = { id -> if (id == c.id && corte == null) corte = motor.cortar(c) }
        val eventos = cancelar(c).map { it.first }
        assertEquals(ResultadoDelCorte.CORTADO, corte)
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), eventos.last())
        assertTrue("nunca un PorConfirmar antes", eventos.none { it is PorConfirmar })
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.preflights.isEmpty())
        assertTrue(fila(c.id).cortarAntesDeSalir)
    }

    @Test fun `TX_024 en un 200 es Rechazado`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = { delay(1_000); http(200, error("TX_024")) }
        val c = cancelacionDe(v)
        assertEquals(Rechazado("TX_024", "TIEMPO EXCEDIDO PARA REALIZAR CANCELACIÓN"), terminal(c))
        assertEquals(EstadoDelIntento.RECHAZADO, fila(c.id).estado)
        assertEquals("TX_024", fila(c.id).codigo)
        // Un rechazo no veta otra cancelación, con otra lectura.
        blumon.cancelacion = { delay(1_000); http(200, CANCELADA) }
        lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it).otraVez(3) }
        assertEquals(Cancelada(22222), terminal(cancelacionDe(v)))
        esperarTodo()
        assertEquals(0, blumon.reversos)
    }

    @Test fun `exitosa sin autorizacion es Cancelada`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(Cancelada(22222), terminal(c))
        assertNull(fila(c.id).autorizacion)
    }

    @Test fun `sin respuesta es PorConfirmar y nunca reversa`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = SILENCIO
        val c = cancelacionDe(v)
        val eventos = cancelar(c)
        val t0 = fila(c.id).enviadoEnMs!!
        assertEquals(PorConfirmar(v.importeCentavos) to t0 + CAJERA, eventos.last())
        assertEquals(Autorizando, eventos.dropLast(1).last().first)
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SE, fila(c.id).estado)
        assertEquals(0, blumon.reversos)
        assertTrue(servidor.permisos.isEmpty())
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(v.importeCentavos)), tardiasDe(c.id))
    }

    // ───────────── Las adiciones ─────────────

    /** Regla N: una cancelación `NO_SALIO` no veta otra de la misma original. */
    @Test fun `una cancelacion que no salio no veta otra`() = prueba {
        val v = ventaAprobada()
        lector.cancelacion = { LecturasDePrueba.mastercardPlasticoCancelacion(it).otraVez(2) }
        assertEquals(noSalio(MotivoNoSalio.OTRA_TARJETA), terminal(cancelacionDe(v)))
        lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it).otraVez(3) }
        assertEquals(Cancelada(22222), terminal(cancelacionDe(v)))
    }

    @Test fun `cortar antes del punto no cancela`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        var corte: ResultadoDelCorte? = null
        motor.antesDelPuntoSinRetorno = { corte = motor.cortar(c) }
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(c))
        assertEquals(ResultadoDelCorte.CORTADO, corte)
        assertTrue(blumon.cancelaciones.isEmpty())
        assertEquals(EstadoDelIntento.NO_SALIO, fila(c.id).estado)
        assertTrue(fila(c.id).cortarAntesDeSalir)
        esperarTodo()
        assertEquals(listOf(noSalio(MotivoNoSalio.INTERRUMPIDO)), tardiasDe(c.id))
    }

    /** El punto de no retorno en memoria: el veto de `cortar` se atora en Room (SIN_CONFIRMAR) y aun así la cancelación no sale. */
    @Test fun `cortar con la libreta detenida no deja salir la cancelacion`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        var corte: ResultadoDelCorte? = null
        motor.antesDeVetar = { delay(60_000) }
        motor.antesDelPuntoSinRetorno = { corte = motor.cortar(c) }
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(c))
        assertEquals(ResultadoDelCorte.SIN_CONFIRMAR, corte)
        esperarTodo()
        assertTrue(blumon.cancelaciones.isEmpty())
        assertEquals(EstadoDelIntento.NO_SALIO, fila(c.id).estado)
    }

    @Test fun `cortar despues del punto dice YA_SALIO`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = { delay(5_000); http(200, CANCELADA) }
        val c = cancelacionDe(v)
        val visto = prueba.async { terminal(c) }
        prueba.advanceTimeBy(3_000) // ya salió
        assertEquals(ResultadoDelCorte.YA_SALIO, motor.cortar(c))
        assertEquals(Cancelada(22222), visto.await())
    }

    /** Regla C: el veto de una cancelación que nadie ha pedido inserta SU fila; el `cancelar` posterior no sale. */
    @Test fun `cortar una cancelacion que nadie ha pedido la veta`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(c))
        assertEquals(Operacion.CANCELACION, fila(c.id).operacion)
        assertEquals(EstadoDelIntento.NO_SALIO, fila(c.id).estado)
        assertEquals(listOf(noSalio(MotivoNoSalio.INTERRUMPIDO)), cancelar(c).map { it.first })
        assertEquals(listOf(Operacion.VENTA), lector.operaciones)
        assertTrue(blumon.preflights.isEmpty())
    }

    /** Regla M: una cancelación terminada entrega SÓLO su final, desde la lápida. */
    @Test fun `reabrir una cancelacion terminada entrega solo su final`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(Cancelada(22222), terminal(c))
        esperarTodo()
        assertEquals(listOf<EventoDeCobro>(Cancelada(22222)), motor.cancelar(c).toList())
        assertEquals(1, blumon.cancelaciones.size)
    }

    /** Regla M: un folio de venta pedido como cancelación, o al revés, es `CONTRATO_DISTINTO`, y nunca se publica. */
    @Test fun `una venta pedida como cancelacion o al reves es contrato distinto`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        assertEquals(Cancelada(22222), terminal(c))
        esperarTodo()
        val distinto = EventoDeCobro.ContratoDistinto
        assertEquals(listOf<EventoDeCobro>(distinto), motor.cancelar(SolicitudDeCancelacion(v.id, v.venueId, v.id)).toList())
        assertEquals(listOf<EventoDeCobro>(distinto), motor.cobrar(SolicitudDeVenta(c.id, "venue-1", 10_000, Origen.VINCULADO_AL_POS)).toList())
        // Sin lápida (un folio que este proceso no corrió), desde la fila: igual.
        val ajena = SolicitudDeCancelacion(AttemptId.nuevo(), "venue-1", v.id)
        assertTrue(libreta.vetarPorCorte(IntentoEntity(
            attemptId = ajena.id.valor, operacion = Operacion.VENTA, venueId = "venue-1", importeCentavos = 10_000, vinculado = true,
            posId = sandbox.posId, serieBlumon = sandbox.serieBlumon, ambiente = sandbox.ambiente, versionSdk = sandbox.versionSdk,
            origen = Origen.VINCULADO_AL_POS,
        )))
        assertEquals(listOf<EventoDeCobro>(distinto), motor.cancelar(ajena).toList())
        esperarTodo()
        assertTrue(tardias.none { it.second == distinto })
    }

    /**
     * Ola final, 4ª parte · 1b (r25 A1): el folio queda ocupado por una venta de OTRO contrato entre la admisión y
     * `anotarCancelacion` (`FolioUsado`). Quien pidió la cancelación nunca ve el importe de esa venta: `Iniciado` sale con 0
     * («todavía no se sabe») y luego `ContratoDistinto`.
     */
    @Test fun `un folio usado por otro contrato no ensena su importe en Iniciado`() = prueba {
        val v = ventaAprobada()
        val c = cancelacionDe(v)
        val ajeno = 77_777L
        motor.despuesDeBuscar = { id ->
            if (id == c.id) assertTrue(libreta.anotar(IntentoEntity(
                attemptId = c.id.valor, operacion = Operacion.VENTA, venueId = "venue-1", importeCentavos = ajeno, vinculado = true,
                posId = sandbox.posId, serieBlumon = sandbox.serieBlumon, ambiente = sandbox.ambiente, versionSdk = sandbox.versionSdk,
                origen = Origen.VINCULADO_AL_POS,
            )))
        }
        val eventos = motor.cancelar(c).toList()
        assertEquals(listOf(Iniciado(c.id, sandbox, Operacion.CANCELACION, 0), EventoDeCobro.ContratoDistinto), eventos)
        assertTrue("$eventos", eventos.none { it is Iniciado && it.importeCentavos == ajeno })
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    /** La cajera con Room detenido: el `Cancelada` llegó pero no se puede escribir; a los 30 s sale `PorConfirmar` de la memoria. */
    @Test fun `con Room detenido la cajera recibe PorConfirmar a los 30 s`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = { io.detener(); delay(1_000); http(200, CANCELADA) }
        val c = cancelacionDe(v)
        val eventos = cancelar(c)
        val t0 = blumon.cancelaciones.single().second
        assertEquals(PorConfirmar(v.importeCentavos) to t0 + CAJERA, eventos.last())
        io.seguir()
        esperarTodo()
        assertEquals(EstadoDelIntento.CANCELADA, fila(c.id).estado)
        assertEquals(listOf(PorConfirmar(v.importeCentavos), Cancelada(22222)), tardiasDe(c.id))
        assertEquals(listOf<EventoDeCobro>(Cancelada(22222)), motor.cancelar(c).toList())
        assertEquals(0, blumon.reversos)
    }

    /** Con `fondo` cancelado (proceso vivo), la cajera sale en el acto: pasado el punto, `PorConfirmar`. */
    @Test fun `con fondo cancelado la cajera sale en el acto`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = SILENCIO
        val c = cancelacionDe(v)
        val visto = prueba.async { cancelar(c) }
        prueba.advanceTimeBy(5_000)
        val antes = prueba.currentTime
        fondo.cancel()
        assertEquals(PorConfirmar(v.importeCentavos) to antes, visto.await().last())
        assertEquals(0, blumon.reversos)
    }

    /** Un solo reloj y nada se tira: lo que el transporte leyó se clasifica aunque el reloj venza antes de reanudar. */
    @Test fun `una cancelacion leida antes de que venza el reloj se clasifica`() = prueba {
        val v = ventaAprobada()
        blumon.cancelacion = { delay(1_000); blumon.alLeer(http(200, CANCELADA)); delay(600_000); http(200, CANCELADA) }
        val c = cancelacionDe(v)
        cancelar(c)
        esperarTodo()
        assertEquals(EstadoDelIntento.CANCELADA, fila(c.id).estado)
        assertEquals(Cancelada(22222), tardiasDe(c.id).last())
        assertEquals(listOf<EventoDeCobro>(Cancelada(22222)), motor.cancelar(c).toList())
    }

    @Test fun `Cancelada sin eco es NoSe`() = prueba {
        val v = ventaAprobada()
        for (sobre in listOf(
            """{"status":true,"id":22222,"dataResponse":{"simulation":false}}""",
            """{"status":true,"id":22222,"dataResponse":{"batch":"${v.id.valor}","simulation":false}}""", // el eco de la venta
        )) {
            blumon.cancelacion = { delay(1_000); http(200, sobre) }
            lector.cancelacion = { LecturasDePrueba.visaPlasticoCancelacion(it).otraVez(blumon.cancelaciones.size + 2) }
            val c = cancelacionDe(v)
            assertEquals(PorConfirmar(v.importeCentavos), terminal(c))
            assertEquals(EstadoDelIntento.NO_SE, fila(c.id).estado)
            // La primera en duda veta a la siguiente: se libera a mano para probar el otro sobre.
            libreta.transicionar(c.id, EstadoDelIntento.NO_SE) { it.copy(estado = EstadoDelIntento.RECHAZADO, codigo = "51") }
        }
        assertEquals(0, blumon.reversos)
    }

    @Test fun `simulation true en produccion es NoSe`() = prueba(produccion) {
        val v = ventaAprobada()
        blumon.cancelacion = { delay(1_000); http(200, CANCELADA.replace("\"simulation\":false", "\"simulation\":true")) }
        val c = cancelacionDe(v)
        assertEquals(PorConfirmar(v.importeCentavos), terminal(c))
        assertEquals(EstadoDelIntento.NO_SE, fila(c.id).estado)
        esperarTodo()
        assertEquals(0, blumon.reversos)
    }

    @Test fun `preflight con simulation true en produccion no cancela`() = prueba(produccion) {
        val v = ventaAprobada()
        blumon.preflight = { http(200, """{"status":true,"dataResponse":{"simulation":true}}""") }
        assertEquals(noSalio(MotivoNoSalio.CANCELACION_NO_PERMITIDA), terminal(cancelacionDe(v)))
        assertTrue(blumon.cancelaciones.isEmpty())
    }

    @Test fun `preflight con simulation true en el sandbox si cancela`() = prueba {
        val v = ventaAprobada()
        blumon.preflight = { http(200, """{"status":true,"dataResponse":{"simulation":true}}""") }
        assertEquals(Cancelada(22222), terminal(cancelacionDe(v)))
    }
}
