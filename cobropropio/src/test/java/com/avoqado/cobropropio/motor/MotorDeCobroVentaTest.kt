package com.avoqado.cobropropio.motor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.admision.MotivoNoAdmitida
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.PuertoBlumon
import com.avoqado.cobropropio.blumon.SesionBlumon
import com.avoqado.cobropropio.blumon.TokenDeBlumon
import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.EventoDeCobro.Aprobado
import com.avoqado.cobropropio.dominio.EventoDeCobro.Autorizando
import com.avoqado.cobropropio.dominio.EventoDeCobro.Cancelada
import com.avoqado.cobropropio.dominio.EventoDeCobro.Iniciado
import com.avoqado.cobropropio.dominio.EventoDeCobro.NoSalioAlBanco
import com.avoqado.cobropropio.dominio.EventoDeCobro.NoSeCobro
import com.avoqado.cobropropio.dominio.EventoDeCobro.PideTarjeta
import com.avoqado.cobropropio.dominio.EventoDeCobro.PorConfirmar
import com.avoqado.cobropropio.dominio.EventoDeCobro.Rechazado
import com.avoqado.cobropropio.dominio.EventoDeCobro.RevisandoConElBanco
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.dominio.Red
import com.avoqado.cobropropio.dominio.ResultadoDeLectura
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.toque
import com.avoqado.cobropropio.libreta.DatosDelEnvio
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReporte
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.libreta.OrigenDeLaAprobacion
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.AcuseDelResultado
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.PermisoDeReverso
import com.avoqado.cobropropio.servidor.ResultadoDelReverso
import com.avoqado.cobropropio.servidor.ReversoDelServidor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import okio.Buffer
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

/**
 * El motor contra la tabla de decisiones (secciones A-H, rediseño v3 y parada 2): una prueba por fila (`tabla <letra>
 * <fila>`) y las nombradas del plan. Dobles programables de Blumon y del SERVIDOR con `delay`, la libreta real sobre un
 * archivo y el tiempo virtual de `runTest`. Con el lector de 1 s, t0 (`ENVIADO`) es `T0`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MotorDeCobroVentaTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pared = Instant.parse("2026-11-18T18:00:00Z")
    private val abiertas = mutableListOf<LibretaDatabase>()
    private val identidad = Identidad("AVOQADO", "388", "1234567890", "9000000001", "A910S", "fase2", Ambiente.SANDBOX)
    private val huella = HuellaHmac(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256"))

    @After fun cerrar() {
        abiertas.forEach { if (it.isOpen) it.close() }
    }

    // ───────────── El mundo de cada prueba ─────────────

    private class RedFalsa : Red {
        var hay = true
        override fun hayRed() = hay
    }

    private class LectorFalso : LectorDeTarjeta {
        var lecturas = 0
        var demora = 1_000L
        var resultado: (Long) -> ResultadoDeLectura = { ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(it).toque(lecturas)) }

        override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
            lecturas++
            delay(demora)
            return resultado(importeCentavos)
        }
    }

    private class BlumonFalso(private val ahora: () -> Long) : PuertoBlumon {
        /** `iniciarSesion` completo (token + init + getKey + initDukptKeys). */
        var inits = 0
        var demoraInit = 0L
        /** `renovarToken` (sólo el tokener). */
        var renovaciones = 0
        var renovar: suspend () -> String? = { "token-$renovaciones" }
        /** El token y el cuerpo (en bytes) con que salió cada venta. */
        val tokensDeVenta = mutableListOf<String>()
        val cuerposDeVenta = mutableListOf<ByteArray>()
        /** (token, cuerpo en bytes) de cada POST de reverso. */
        val reversosEnviados = mutableListOf<Pair<String, ByteArray>>()
        var ultimoContador = 10
        val ventas = mutableListOf<Long>()
        /** `n` = número de venta (1, 2, …). */
        var venta: suspend (n: Int) -> Respuesta = { delay(1_000); http(200, APROBADA) }
        /** (ahora, noEmpezarDespuesDe) de cada POST de reverso. */
        val reversos = mutableListOf<Pair<Long, Long>>()
        var alReversar: () -> Unit = {}
        /** `n` = número de vuelta (1, 2, …) en todo el mundo. */
        var reverso: suspend (n: Int) -> Respuesta = { delay(1_000); http(200, REVERSADA) }

        /** Cada sesión que nació, para revisar su material. */
        val sesiones = mutableListOf<SesionBlumon>()

        override suspend fun iniciarSesion(espera: Duration): SesionBlumon? {
            inits++
            delay(demoraInit)
            return SesionBlumon(TOKEN_INICIAL, ByteArray(16) { (it + 1).toByte() }, BASE_KSN.hexABytes(), ultimoContador).also { sesiones += it }
        }

        override suspend fun renovarToken(espera: Duration): TokenDeBlumon? {
            renovaciones++
            return renovar()?.let(::TokenDeBlumon)
        }

        override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta {
            ventas += ahora()
            tokensDeVenta += sesion.token
            val bytes = Buffer().also { cuerpo.comoRequestBody().writeTo(it) }.readByteArray()
            cuerposDeVenta += bytes
            return conEco(venta(ventas.size), referenciaDe(bytes))
        }

        /** Lo que el transporte entrega al leer, de la vuelta en curso (R3-2). */
        var alLeer: (Respuesta) -> Unit = {}

        override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta {
            reversos += ahora() to noEmpezarDespuesDe
            val bytes = Buffer().also { congelado.cuerpo.comoRequestBody().writeTo(it) }.readByteArray()
            reversosEnviados += congelado.token to bytes
            val ref = referenciaDe(bytes)
            this.alLeer = { alLeer(conEco(it, ref)) }
            alReversar()
            return conEco(reverso(reversos.size), ref)
        }

        override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta =
            throw UnsupportedOperationException("no en la venta")
        override suspend fun cancelar(
            sesion: SesionBlumon, operationNumber: Long, cuerpo: CuerpoDeBlumon, espera: Duration, alLeer: (Respuesta) -> Unit,
        ): Respuesta = throw UnsupportedOperationException("no en la venta")
    }

    private class ServidorFalso : ReversoDelServidor {
        val permisos = mutableListOf<AttemptId>()
        /** La espera con que el motor llamó a `pedirPermiso`: el plazo de la llamada HTTP de adentro. */
        val esperas = mutableListOf<Duration>()
        var permiso: suspend (espera: Duration) -> PermisoDeReverso = { delay(1_000); PermisoDeReverso.Concedido("evt-1") }
        /**
         * El cliente real cumple su plazo (r2 familia B): sin respuesta dentro de `espera`, `SinRespuesta`. `false` = una
         * implementación que no lo cumple, para la cerca del motor.
         */
        var cumplePlazo = true
        val reportados = mutableListOf<Pair<AttemptId, ResultadoDelReverso>>()
        var acuse: suspend () -> AcuseDelResultado = { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSED) }

        override suspend fun pedirPermiso(venueId: String, id: AttemptId, espera: Duration): PermisoDeReverso {
            permisos += id
            esperas += espera
            return if (cumplePlazo) withTimeoutOrNull(espera) { permiso(espera) } ?: PermisoDeReverso.SinRespuesta else permiso(espera)
        }

        override suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado {
            reportados += id to r
            return acuse()
        }
    }

    private inner class Mundo(
        val prueba: TestScope,
        relojes: Relojes = Relojes(),
        val archivo: File = File(carpeta.root, "${UUID.randomUUID()}.db"),
        proceso: String = Libreta.PROCESO_ID,
    ) {
        var salto = 0L
        val ahora: () -> Long = { prueba.testScheduler.currentTime + salto }
        val io = StandardTestDispatcher(prueba.testScheduler)
        /**
         * En primer plano (no `backgroundScope`): `advanceUntilIdle` no espera al trabajo de fondo, y el motor sigue después de
         * soltar a la cajera. [cerrar] lo deja terminar.
         */
        val trabajo = Job(prueba.coroutineContext[Job])
        val fondo = CoroutineScope(prueba.coroutineContext + trabajo)
        /** Corre DENTRO de la transacción de esa operación de la libreta, antes del commit. */
        val ganchos = mutableMapOf<String, () -> Unit>()
        val db = LibretaDatabase.constructor(context, archivo).allowMainThreadQueries().build().also { abiertas += it }
        val libreta = Libreta(db, ahora, { pared }, proceso) { op -> ganchos[op]?.invoke() }
        val red = RedFalsa()
        val lector = LectorFalso()
        val blumon = BlumonFalso(ahora)
        val servidor = ServidorFalso()
        val motor = MotorDeCobro(
            identidad, lector, red, blumon, servidor, libreta, ColaDeResultados(libreta, servidor, io), huella,
            CatalogoDeRechazos.BLUMON, relojes, fondo, ahora, { pared }, io,
        )
        val tardias = mutableListOf<Pair<AttemptId, EventoDeCobro>>()

        private val escucha = fondo.launch(start = CoroutineStart.UNDISPATCHED) { motor.resolucionesTardias.collect { tardias += it } }

        fun cerrar() {
            escucha.cancel()
            trabajo.complete()
        }

        suspend fun cobrar(s: SolicitudDeVenta): List<Pair<EventoDeCobro, Long>> =
            motor.cobrar(s).map { it to prueba.currentTime }.toList()

        suspend fun terminal(s: SolicitudDeVenta): EventoDeCobro = cobrar(s).last().first

        fun fila(s: SolicitudDeVenta): IntentoEntity = requireNotNull(libreta.buscar(s.id))

        fun esperarTodo() = prueba.advanceUntilIdle()

        fun tardiasDe(s: SolicitudDeVenta) = tardias.filter { it.first == s.id }.map { it.second }

        /** Una fila escrita directo en la libreta, con el contrato de `s`, para las pruebas de pertenencia. */
        fun filaDirecta(s: SolicitudDeVenta, posId: String = identidad.posId, cambio: ((IntentoEntity) -> IntentoEntity)? = null) {
            assertTrue(libreta.anotar(entidad(s, posId)))
            if (cambio != null) assertNotNull(libreta.transicionar(s.id, EstadoDelIntento.ANOTADO, cambio))
        }

        /** Deja la fila con [estado] y un folio bancario fuera de rango: Room la lee, `validar` no la pasa. */
        fun danar(s: SolicitudDeVenta, estado: String) = db.openHelper.writableDatabase.execSQL(
            "UPDATE intentos SET estado = ?, operationNumber = 99999999999 WHERE attemptId = ?", arrayOf(estado, s.id.valor),
        )
    }

    private fun entidad(s: SolicitudDeVenta, posId: String = identidad.posId) = IntentoEntity(
        attemptId = s.id.valor, operacion = Operacion.VENTA, venueId = s.venueId, importeCentavos = s.importeCentavos,
        vinculado = s.origen == Origen.VINCULADO_AL_POS, posId = posId, serieBlumon = identidad.serieBlumon,
        ambiente = identidad.ambiente, versionSdk = identidad.versionSdk, origen = s.origen,
    )

    private fun prueba(relojes: Relojes = Relojes(), bloque: suspend Mundo.() -> Unit) = runTest {
        val m = Mundo(this, relojes)
        try {
            m.bloque()
        } finally {
            m.cerrar()
        }
    }

    private fun solicitud(importe: Long = 10_000, origen: Origen = Origen.VINCULADO_AL_POS, venue: String = "venue-1") =
        SolicitudDeVenta(AttemptId.nuevo(), venue, importe, origen)

    private companion object {
        const val T0 = 1_000L
        const val TOPE = T0 + 45_000
        const val CAJERA = T0 + 30_000
        const val BASE_KSN = "FFFF9876543210E00000"
        const val TOKEN_INICIAL = "token-inicial"
        /** T8 arreglos 2: el doble de Blumon cambia `@REF@` por la `reference` que salió (el eco que exige un éxito). */
        const val ECO = "@REF@"
        const val APROBADA = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","batch":"$ECO","simulation":false}}"""
        const val REVERSADA = """{"status":true,"id":12347,"dataResponse":{"description":"REVERSADA","batch":"$ECO","simulation":false}}"""
        const val PARCIAL = """{"status":true,"id":777,"dataResponse":{"codeResponse":"10","amount":"0.50","batch":"$ECO","simulation":false}}"""

        fun referenciaDe(cuerpo: ByteArray): String = JsonParser.parseString(String(cuerpo)).asJsonObject.get("reference").asString

        /** Pone el eco en un sobre con `@REF@`; lo demás pasa igual. Sólo pruebas: lee el cuerpo por reflexión. */
        fun conEco(r: Respuesta, ref: String): Respuesta {
            if (r !is Respuesta.Http) return r
            val cuerpo = Respuesta.Http::class.java.getDeclaredField("cuerpo").apply { isAccessible = true }.get(r) as String
            return if (ECO in cuerpo) Respuesta.Http(r.codigo, cuerpo.replace(ECO, ref)) else r
        }
        val APROBADO_VISA = Aprobado(12345, "ABC123", null, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484")
        val SILENCIO: suspend (Int) -> Respuesta = { delay(600_000); http(200, APROBADA) }

        fun http(codigo: Int, cuerpo: String) = Respuesta.Http(codigo, cuerpo)
        fun error(codigo: String) = """{"status":false,"error":{"code":"$codigo"}}"""
        fun guardado(e: EstadoDelReversoEnServidor) = AcuseDelResultado.Guardado(e, "evt-1", null)
        fun noSalio(m: MotivoNoSalio, a: MotivoNoAdmitida? = null) = NoSalioAlBanco(m, a)
    }

    // ───────────── A. Respuesta de la venta ANTES del arbitraje ─────────────

    @Test fun `tabla A Aprobada`() = prueba {
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(
            listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando, APROBADO_VISA),
            eventos.map { it.first },
        )
        assertEquals(T0 + 1_000, eventos.last().second)
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelIntento.APROBADO, f.estado)
        assertEquals(OrigenDeLaAprobacion.BANCO, f.origenDeLaAprobacion)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(T0, f.enviadoEnMs)
        assertEquals(10L + Libreta.SALTO_SIN_HISTORIAL, f.contadorUsado)
        assertEquals(BASE_KSN, f.ksnBase)
        assertEquals(huella.de(LecturasDePrueba.PAN_VISA), f.huellaTarjeta)
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A Rechazada`() = prueba {
        blumon.venta = { delay(1_000); http(200, error("51")) }
        val s = solicitud()
        assertEquals(Rechazado("51", "FONDOS INSUFICIENTES"), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.RECHAZADO, fila(s).estado)
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A NoEnviada`() = prueba {
        blumon.venta = { Respuesta.NoEnviada }
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, fila(s).motivoNoSalio)
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A NoSe o DebeReversar`() = prueba {
        val respuestas = listOf(http(429, APROBADA), http(401, APROBADA), http(403, APROBADA), http(200, error("68")), http(200, PARCIAL))
        for ((i, respuesta) in respuestas.withIndex()) {
            blumon.venta = { delay(1_000); respuesta }
            val s = solicitud()
            val eventos = cobrar(s).map { it.first }
            assertFalse(RevisandoConElBanco in eventos)
            assertFalse(eventos.any { it is Aprobado })
            assertEquals(NoSeCobro, eventos.last())
            assertEquals(s.id, servidor.permisos.last())
            assertEquals(i + 1, blumon.reversos.size)
        }
        assertEquals(1, blumon.inits) // el 401 y el 403 sólo renuevan el token
    }

    @Test fun `tabla A sin respuesta a los 15 s`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(T0 + 15_000, eventos.single { it.first == RevisandoConElBanco }.second)
        assertEquals(NoSeCobro, eventos.last().first)
        assertEquals(listOf(s.id), servidor.permisos)
        assertEquals(1, blumon.reversos.size)
    }

    // ───────────── C. Permiso ─────────────

    @Test fun `tabla C Concedido`() = prueba {
        blumon.venta = SILENCIO
        for (origen in Origen.values()) {
            val s = solicitud(origen = origen)
            assertEquals(NoSeCobro, terminal(s))
            val f = fila(s)
            assertTrue(f.vinculoAcreditadoPorPermiso)
            assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
            assertEquals(s.id, servidor.reportados.last().first) // V pasó a sí: esperó el acuse
        }
        assertEquals(2, blumon.reversos.size)
    }

    @Test fun `tabla C YaCobrado con el total`() = prueba {
        blumon.venta = SILENCIO
        for (origen in Origen.values()) {
            val s = solicitud(origen = origen)
            servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", s.importeCentavos, null, "ABC9") }
            assertEquals(APROBADO_VISA.copy(operationNumber = null, autorizacion = "ABC9", paymentId = "pay_1"), terminal(s))
            val f = fila(s)
            assertEquals(EstadoDelIntento.APROBADO, f.estado)
            assertEquals(OrigenDeLaAprobacion.SERVIDOR, f.origenDeLaAprobacion)
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla C YaCobrado con otro total`() = prueba {
        blumon.venta = SILENCIO
        for (origen in Origen.values()) {
            for (amount in listOf(9_999L, null)) {
                val s = solicitud(origen = origen)
                servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", amount, null, null) }
                assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
                assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
            }
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla C YaResuelto REVERSED`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaResuelto(EstadoDelReversoEnServidor.REVERSED, "evt-1") }
        for (origen in Origen.values()) {
            val s = solicitud(origen = origen)
            assertEquals(NoSeCobro, terminal(s))
            assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla C YaResuelto otro y errores del servidor`() = prueba {
        blumon.venta = SILENCIO
        val permisos = listOf(
            PermisoDeReverso.YaResuelto(EstadoDelReversoEnServidor.REVERSE_FAILED, "evt-1"),
            PermisoDeReverso.YaResuelto(EstadoDelReversoEnServidor.CONTRADICTION, "evt-1"),
            PermisoDeReverso.EvidenciaPendiente("PENDING", "TERMINAL"),
            PermisoDeReverso.NoEsBlumon,
            PermisoDeReverso.Invalido(400, null),
            PermisoDeReverso.ErrorDelServidor,
        )
        for (origen in Origen.values()) for (p in permisos) {
            servidor.permiso = { delay(1_000); p }
            val s = solicitud(origen = origen)
            assertEquals("$p", PorConfirmar(s.importeCentavos), terminal(s))
            val f = fila(s)
            assertEquals(EstadoDelIntento.NO_SE, f.estado)
            assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla C NoEncontrado`() = permisoQueSoloReversaSinVinculo(PermisoDeReverso.NoEncontrado)

    @Test fun `tabla C ServidorSinSdkPropio`() = permisoQueSoloReversaSinVinculo(PermisoDeReverso.ServidorSinSdkPropio)

    private fun permisoQueSoloReversaSinVinculo(p: PermisoDeReverso) = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(1_000); p }
        val vinculada = solicitud(origen = Origen.VINCULADO_AL_POS)
        assertEquals(PorConfirmar(vinculada.importeCentavos), terminal(vinculada))
        assertEquals(EstadoDelIntento.NO_SE, fila(vinculada).estado)
        assertTrue(blumon.reversos.isEmpty())

        val local = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        assertEquals(NoSeCobro, terminal(local))
        assertEquals(1, blumon.reversos.size)
        assertTrue(servidor.reportados.isEmpty()) // V = no: basta el banco
    }

    @Test fun `tabla C SinRespuesta`() = prueba {
        blumon.venta = SILENCIO
        val sinRespuesta = solicitud()
        servidor.permiso = { delay(1_000); PermisoDeReverso.SinRespuesta }
        assertEquals(NoSeCobro, terminal(sinRespuesta))
        assertEquals(1, blumon.reversos.size)

        val mudo = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        servidor.permiso = { delay(600_000); PermisoDeReverso.Concedido("evt-1") }
        val inicio = prueba.currentTime
        assertEquals(NoSeCobro, terminal(mudo))
        assertEquals(2, blumon.reversos.size)
        assertEquals(inicio + T0 + 15_000 + 11_000, blumon.reversos.last().first) // esperó su esperaPermiso y reversó
        assertFalse(fila(mudo).vinculoAcreditadoPorPermiso)
    }

    // ───────────── D. Cada vuelta del reverso ─────────────

    @Test fun `tabla D Confirmado`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        val f = fila(s)
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar)
        assertEquals(pared.toString(), f.horaObservadaIso)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    @Test fun `tabla D Rechazado TX_007`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(1_000); http(200, error("TX_007")) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelReverso.RECHAZADO, f.reverso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_007", f.codigoParaReportar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(1, blumon.reversos.size)
    }

    @Test fun `tabla D Reintentar que prueba que no entro`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(1_000); PermisoDeReverso.NoEncontrado }
        for (codigo in listOf("TX_008", "TX_009", "RQ_005", "RQ_006")) {
            val primera = blumon.reversos.size + 1
            blumon.reverso = { n -> delay(1_000); if (n == primera) http(200, error(codigo)) else http(200, REVERSADA) }
            val s = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
            assertEquals(codigo, NoSeCobro, terminal(s))
            val f = fila(s)
            assertFalse(codigo, f.pudoEntrar)
            assertEquals(codigo, f.ultimoCodigoReverso)
            val (v1, v2) = blumon.reversos.takeLast(2).map { it.first }
            assertEquals(codigo, v1 + 1_000 + 5_000, v2) // la respuesta, la pausa y otra vuelta
        }
    }

    @Test fun `tabla D Reintentar con otra cosa`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(1_000); PermisoDeReverso.NoEncontrado }
        val otras = listOf(
            http(200, error("68")), http(200, error("6")), http(200, error("N0")), http(200, error("ZZ_999")),
            Respuesta.SinRespuesta("TIEMPO"), http(500, REVERSADA), http(200, "<html>"),
        )
        for (otra in otras) {
            val primera = blumon.reversos.size + 1
            // otra cosa, luego una prueba de que no entró, luego REVERSADA: el bit no se borra entre vueltas
            blumon.reverso = { n ->
                delay(1_000)
                when (n - primera) { 0 -> otra; 1 -> http(200, error("TX_008")); else -> http(200, REVERSADA) }
            }
            val s = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
            assertEquals("$otra", NoSeCobro, terminal(s))
            esperarTodo()
            assertTrue("$otra", fila(s).pudoEntrar)
            assertEquals(primera + 2, blumon.reversos.size)
        }
    }

    /**
     * Ola final · 1 (Codex r21): un `TX_014`/`TX_013` de error en TODAS las vueltas no es un reverso confirmado. La venta queda
     * `PorConfirmar` (nunca `NoSeCobro`) y al SERVIDOR va el no concluyente, nunca `REVERSADA`. Vinculada y local.
     */
    @Test fun `tabla D TX_014 o TX_013 en todas las vueltas deja por confirmar y nunca REVERSADA`() = prueba {
        blumon.venta = SILENCIO
        val concedido = servidor.permiso
        for (codigo in listOf("TX_014", "TX_013")) {
            blumon.reverso = { delay(1_000); http(200, error(codigo)) }
            for (origen in listOf(Origen.VINCULADO_AL_POS, Origen.LOCAL_SIN_SOLICITUD)) {
                // la vinculada reversa con permiso; la local, sin vínculo (NoEncontrado), como en la tabla C
                servidor.permiso = if (origen == Origen.VINCULADO_AL_POS) concedido else ({ delay(1_000); PermisoDeReverso.NoEncontrado })
                val antes = blumon.reversos.size
                val s = solicitud(origen = origen)
                assertEquals("$codigo $origen", PorConfirmar(s.importeCentavos), terminal(s))
                esperarTodo()
                assertFalse("$codigo $origen", NoSeCobro in tardiasDe(s))
                assertTrue("$codigo $origen", blumon.reversos.size - antes > 1) // siguió su ventana, como cualquier duda
                val f = fila(s)
                assertEquals("$codigo $origen", EstadoDelIntento.NO_SE, f.estado)
                assertTrue("$codigo $origen", f.pudoEntrar)
                assertEquals("$codigo $origen", codigo, f.ultimoCodigoReverso)
                assertEquals("$codigo $origen", ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
                val alServidor = ResultadoDelReverso.deLaFila(f)!!
                assertNull("$codigo $origen", alServidor.descripcion)
                assertEquals("$codigo $origen", Clasificador.SIN_RESPUESTA, alServidor.codigo)
            }
        }
        assertTrue(servidor.reportados.none { it.second.descripcion == "REVERSADA" })
    }

    /** Ola final · 1: el camino bueno sigue vivo. Tras un `TX_014`/`TX_013`, un `REVERSADA` con eco en otra vuelta ⇒ `NoSeCobro`. */
    @Test fun `tabla D TX_014 o TX_013 y luego REVERSADA con eco da no se cobro`() = prueba {
        blumon.venta = SILENCIO
        for (codigo in listOf("TX_014", "TX_013")) {
            val primera = blumon.reversos.size + 1
            blumon.reverso = { n -> delay(1_000); if (n == primera) http(200, error(codigo)) else http(200, REVERSADA) }
            val s = solicitud()
            assertEquals(codigo, NoSeCobro, terminal(s))
            val f = fila(s)
            assertEquals(codigo, EstadoDelIntento.NO_SE_COBRO, f.estado)
            assertTrue(codigo, f.pudoEntrar)
            assertEquals(codigo, primera + 1, blumon.reversos.size) // confirmó la segunda vuelta, no la del código de error
            assertEquals(codigo, "REVERSADA", servidor.reportados.last().second.descripcion)
        }
    }

    @Test fun `tabla D NoEnviado`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { Respuesta.NoEnviada }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos), eventos.last().first)
        assertEquals(T0 + 16_000, eventos.last().second) // F enseguida, sin otra vuelta
        val f = fila(s)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertFalse(f.pudoEntrar)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(1, blumon.reversos.size)
    }

    // ───────────── E. Después de un Confirmado ─────────────

    @Test fun `tabla E V no`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(1_000); PermisoDeReverso.NoEncontrado }
        val s = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        assertEquals(NoSeCobro, terminal(s))
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar) // la cola lo avisa después
        assertTrue(servidor.reportados.isEmpty())
    }

    @Test fun `tabla E V si`() = prueba {
        blumon.venta = SILENCIO
        val casos = listOf<Pair<suspend () -> AcuseDelResultado, EstadoDelIntento>>(
            suspend { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSED) } to EstadoDelIntento.NO_SE_COBRO,
            suspend { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSE_FAILED) } to EstadoDelIntento.NO_SE,
            suspend { delay(1_000); AcuseDelResultado.Reintentar } to EstadoDelIntento.NO_SE,
            suspend { delay(1_000); AcuseDelResultado.Conflicto(EstadoDelReversoEnServidor.REVERSE_FAILED) } to EstadoDelIntento.NO_SE,
        )
        for ((acuse, estado) in casos) {
            servidor.acuse = acuse
            val s = solicitud()
            val esperado = if (estado == EstadoDelIntento.NO_SE_COBRO) NoSeCobro else PorConfirmar(s.importeCentavos)
            assertEquals("$estado", esperado, terminal(s))
            assertEquals(estado, fila(s).estado)
        }

        // El `reversed` que llega después de soltar a la cajera deja NO_SE_COBRO y se publica tarde.
        servidor.acuse = { delay(20_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val tarde = solicitud()
        val inicio = prueba.currentTime
        val eventos = cobrar(tarde)
        assertEquals(PorConfirmar(tarde.importeCentavos), eventos.last().first)
        assertEquals(inicio + CAJERA, eventos.last().second)
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(tarde).estado)
        assertTrue(NoSeCobro in tardiasDe(tarde))
    }

    // ───────────── F. Al vencer el plazo, o si no alcanzó para pedir permiso ─────────────

    @Test fun `tabla F ultimo TX_008 sin pudoEntrar`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(1_000); http(200, error("TX_008")) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertFalse(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_008", f.codigoParaReportar)
        assertEquals(pared.toString(), f.horaObservadaIso)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(5, blumon.reversos.size)
    }

    @Test fun `tabla F pudoEntrar`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(1_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertTrue(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertNull(f.codigoParaReportar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `tabla F comprometido sin vuelta`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { salto += 60_000; delay(1_000); PermisoDeReverso.SinRespuesta } // el reloj se pasa del techo
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla F ningun reverso salio`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { Respuesta.NoEnviada }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertNull(f.resultadoParaReportar)
    }

    // ───────────── H. Pertenencia del intento ─────────────

    @Test fun `tabla H mismo contrato`() = prueba {
        // Vivo: el segundo colector se adjunta a su StateFlow.
        blumon.venta = SILENCIO
        val vivo = solicitud()
        val primero = prueba.async { terminal(vivo) }
        prueba.advanceTimeBy(5_000)
        assertEquals(NoSeCobro, terminal(vivo))
        assertEquals(NoSeCobro, primero.await())
        assertEquals(1, blumon.ventas.size)
        assertEquals(1, lector.lecturas)

        // En la libreta: se reconstruye de la fila.
        red.hay = false // si la puerta mirara la red, diría SIN_RED
        val casos = listOf<Pair<((IntentoEntity) -> IntentoEntity)?, (SolicitudDeVenta) -> EventoDeCobro>>(
            { f: IntentoEntity ->
                f.copy(
                    estado = EstadoDelIntento.APROBADO, operationNumber = 55, autorizacion = "A1", marca = Marca.VISA,
                    medio = Medio.PLASTICO, bin = "411111", ultimos4 = "1111", paisEmisor = "0484",
                )
            } to { _ -> Aprobado(55, "A1", null, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484") },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.RECHAZADO, codigo = "51", leyenda = "FONDOS INSUFICIENTES") } to
                { _ -> Rechazado("51", "FONDOS INSUFICIENTES") },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.LECTURA_VENCIDA) } to
                { _ -> noSalio(MotivoNoSalio.LECTURA_VENCIDA) },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.NO_SE_COBRO) } to { _ -> NoSeCobro },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.CANCELADA, operationNumber = 77) } to { _ -> Cancelada(77) },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.NO_SE) } to { s -> PorConfirmar(s.importeCentavos) },
            { f: IntentoEntity -> f.copy(estado = EstadoDelIntento.ENVIADO, enviadoEnMs = 0) } to { s -> PorConfirmar(s.importeCentavos) },
            // v3.1: un ANOTADO de ESTE proceso sin `Registro` puede ser una foto vieja o una huérfana: sólo «por confirmar».
            null to { s -> PorConfirmar(s.importeCentavos) },
            // El ANOTADO de otro proceso (ya muerto) nunca pudo salir.
            { f: IntentoEntity -> f.copy(procesoId = "otro-proceso") } to { _ -> noSalio(MotivoNoSalio.INTERRUMPIDO) },
        )
        for ((cambio, esperado) in casos) {
            val s = solicitud()
            filaDirecta(s, cambio = cambio)
            assertEquals(listOf(esperado(s)), cobrar(s).map { it.first })
        }
        assertEquals(1, lector.lecturas)
        assertEquals(1, blumon.ventas.size)
    }

    @Test fun `tabla H otro contrato`() = prueba {
        val s = solicitud()
        assertEquals(APROBADO_VISA, terminal(s))
        esperarTodo()
        val antes = fila(s)
        val distintos = listOf(
            s.copy(importeCentavos = 9_999), s.copy(venueId = "venue-2"), s.copy(origen = Origen.LOCAL_SIN_SOLICITUD),
        )
        for (otro in distintos) assertEquals("$otro", listOf(EventoDeCobro.ContratoDistinto), cobrar(otro).map { it.first })

        val otroAmbito = solicitud()
        filaDirecta(otroAmbito, posId = "999")
        assertEquals(EventoDeCobro.ContratoDistinto, terminal(otroAmbito))

        assertEquals(antes, fila(s))
        assertEquals(1, blumon.ventas.size)
        assertEquals(1, lector.lecturas)
    }

    /** Regla N: el «no salió» por falta de red también deja su fila, y el folio es de un solo uso. */
    @Test fun `tabla H id nuevo`() = prueba {
        red.hay = false
        val s = solicitud()
        assertEquals(listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), noSalio(MotivoNoSalio.SIN_RED)),
            cobrar(s).map { it.first })
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertEquals(MotivoNoSalio.SIN_RED, fila(s).motivoNoSalio)
        assertEquals(0, lector.lecturas)

        red.hay = true // el mismo folio con red sigue diciendo «sin red»: la app pide un id nuevo para reintentar
        assertEquals(noSalio(MotivoNoSalio.SIN_RED), terminal(s))
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(APROBADO_VISA, terminal(solicitud()))
    }

    @Test fun `tabla H otro id con el lector ocupado`() = prueba {
        lector.demora = 20_000
        val a = solicitud()
        val primera = prueba.async { terminal(a) }
        prueba.advanceTimeBy(5_000)
        val b = solicitud()
        assertEquals(listOf(Iniciado(b.id, identidad, Operacion.VENTA, b.importeCentavos), noSalio(MotivoNoSalio.TERMINAL_OCUPADA)),
            cobrar(b).map { it.first })
        assertEquals(MotivoNoSalio.TERMINAL_OCUPADA, fila(b).motivoNoSalio)
        assertEquals(APROBADO_VISA, primera.await())
        assertEquals(1, lector.lecturas)
    }

    // ───────────── Pertenencia ─────────────

    @Test fun `dos colectores del mismo id comparten el desenlace`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val a = prueba.async { cobrar(s) }
        val b = prueba.async { delay(5_000); cobrar(s) }
        assertEquals(NoSeCobro, a.await().last().first)
        assertEquals(a.await().last(), b.await().last())
        assertEquals(1, blumon.ventas.size)
        assertEquals(1, lector.lecturas)
        assertEquals(1, blumon.reversos.size)
    }

    @Test fun `colector cancelado entre ENVIADO y el fondo no deja huerfano`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val vistos = mutableListOf<EventoDeCobro>()
        val colector = prueba.launch { motor.cobrar(s).collect { vistos += it } }
        prueba.advanceTimeBy(T0 + 500)
        assertTrue(Autorizando in vistos)
        colector.cancel()
        esperarTodo()
        assertEquals(1, blumon.reversos.size)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
        assertTrue(NoSeCobro in tardiasDe(s))
    }

    /** S1 (regla C): que la única escucha se vaya antes de salir no corta; sólo `cortar(s)` corta. */
    @Test fun `colector cancelado antes de salir no corta la venta`() = prueba {
        lector.demora = 5_000
        val s = solicitud()
        val colector = prueba.launch { motor.cobrar(s).collect {} }
        prueba.advanceTimeBy(2_000)
        colector.cancel()
        esperarTodo()
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertEquals(1, blumon.ventas.size)
        assertEquals(listOf<EventoDeCobro>(APROBADO_VISA), tardiasDe(s))
    }

    /**
     * Ola final, 4ª parte · 1 (R24-2): un folio que SÍ salió y se aprobó, pedido con otro importe, contesta `ContratoDistinto`,
     * nunca un «no salió» (que invitaría a cobrar otra vez). Quien pregunta con el contrato original sigue viendo `Aprobado`.
     */
    @Test fun `tras un Aprobado otro importe del mismo folio es ContratoDistinto y nunca un no salio`() = prueba {
        val s = solicitud()
        assertEquals(APROBADO_VISA, terminal(s))
        esperarTodo()
        val otro = cobrar(s.copy(importeCentavos = 1)).map { it.first }
        assertEquals(listOf<EventoDeCobro>(EventoDeCobro.ContratoDistinto), otro)
        assertTrue("$otro", otro.none { it is NoSalioAlBanco })
        assertEquals(APROBADO_VISA, terminal(s))
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertEquals(1, blumon.ventas.size)
    }

    @Test fun `mismo id con otro importe no toca el original`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val original = prueba.async { terminal(s) }
        prueba.advanceTimeBy(5_000)
        assertEquals(listOf(EventoDeCobro.ContratoDistinto), cobrar(s.copy(importeCentavos = 1)).map { it.first })
        assertEquals(NoSeCobro, original.await())
        assertEquals(s.importeCentavos, fila(s).importeCentavos)
        assertEquals(1, blumon.ventas.size)
    }

    @Test fun `recolectar otra vez con el primero enviado y con el primero aprobado`() = prueba {
        blumon.venta = { n -> if (n == 1) SILENCIO(n) else { delay(1_000); http(200, APROBADA) } }
        val enviado = solicitud()
        val primero = prueba.async { terminal(enviado) }
        prueba.advanceTimeBy(5_000)
        assertEquals(EstadoDelIntento.ENVIADO, fila(enviado).estado)
        assertEquals(NoSeCobro, terminal(enviado))
        assertEquals(NoSeCobro, primero.await())

        val aprobado = solicitud()
        val desenlace = terminal(aprobado)
        esperarTodo()
        assertEquals(APROBADO_VISA, desenlace)
        // r10 (ruling s c4): reabrir una lápida entrega SÓLO su final vigente, sin «acerca la tarjeta» de un cobro terminado.
        assertEquals(listOf(desenlace), cobrar(aprobado).map { it.first })
        assertEquals(2, blumon.ventas.size)
        assertEquals(2, lector.lecturas)
    }

    // ───────────── Arbitraje ─────────────

    @Test fun `aprobado tardio solo se guarda`() = prueba {
        blumon.venta = { delay(20_000); http(200, APROBADA) } // llega a la mitad del reverso
        blumon.reverso = { delay(5_000); http(200, REVERSADA) }
        val s = solicitud()
        val eventos = cobrar(s).map { it.first }
        esperarTodo()
        assertEquals(NoSeCobro, eventos.last())
        assertFalse(eventos.any { it is Aprobado })
        assertFalse(tardiasDe(s).any { it is Aprobado })
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(12345L, f.evidenciaTardiaOperacion)
        assertEquals("ABC123", f.evidenciaTardiaAutorizacion)
        assertNull(f.operationNumber)
    }

    /**
     * Tabla B, primer punto. A ya venció (15 s) y la aprobación queda guardada en la libreta ANTES del compromiso: el doble
     * hace en ese hueco lo que hace la escucha tardía del motor (`guardarAprobacion`), de forma determinista.
     */
    @Test fun `aprobado antes del arbitraje no reversa`() = prueba {
        val s = solicitud()
        blumon.venta = {
            delay(15_000)
            assertEquals(com.avoqado.cobropropio.libreta.Arbitraje.Vigente, libreta.guardarAprobacion(s.id, 12345, "ABC123"))
            http(200, APROBADA)
        }
        val eventos = cobrar(s).map { it.first }
        assertTrue(RevisandoConElBanco in eventos) // sí venció A: es el arbitraje el que lo decide
        assertEquals(APROBADO_VISA, eventos.last())
        esperarTodo()
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelReverso.NINGUNO, fila(s).reverso)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
    }

    @Test fun `tabla B YaResuelta ya comprometida sigue al permiso`() = prueba {
        val s = solicitud()
        blumon.venta = {
            delay(15_000)
            libreta.comprometerAReversar(s.id) // la fila queda ENVIADO + COMPROMETIDO antes del compromiso del motor
            Respuesta.SinRespuesta("TIEMPO")
        }
        assertEquals(NoSeCobro, terminal(s))
        assertEquals(listOf(s.id), servidor.permisos)
        assertEquals(1, blumon.reversos.size)
    }

    @Test fun `aprobado justo antes de los 15 s es la fila A`() = prueba {
        blumon.venta = { delay(14_999); http(200, APROBADA) }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(APROBADO_VISA, eventos.last().first)
        assertEquals(T0 + 14_999, eventos.last().second)
        esperarTodo()
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelReverso.NINGUNO, fila(s).reverso)
    }

    // ───────────── Reverso ─────────────

    @Test fun `reverso confirmado vinculado espera el acuse`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(5_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(NoSeCobro, eventos.last().first)
        assertEquals(blumon.reversos.single().first + 1_000 + 5_000, eventos.last().second) // el banco y luego el acuse

        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar }
        val sinAcuse = solicitud()
        assertEquals(PorConfirmar(sinAcuse.importeCentavos), terminal(sinAcuse))
        assertEquals(EstadoDelReverso.CONFIRMADO, fila(sinAcuse).reverso) // el banco sí lo confirmó
        assertEquals(EstadoDelIntento.NO_SE, fila(sinAcuse).estado)
    }

    @Test fun `contradiction deja por confirmar`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(1_000); guardado(EstadoDelReversoEnServidor.CONTRADICTION) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
        assertEquals(EstadoDelReversoEnServidor.CONTRADICTION, fila(s).estadoEnServidor)
        assertFalse(NoSeCobro in tardiasDe(s))
    }

    @Test fun `nunca reversa despues del techo`() {
        prueba {
            blumon.venta = SILENCIO
            blumon.reverso = { delay(1_000); Respuesta.SinRespuesta("TIEMPO") }
            val s = solicitud()
            terminal(s)
            esperarTodo()
            assertEquals(5, blumon.reversos.size)
            assertTrue(blumon.reversos.all { (cuando, _) -> cuando < TOPE })
            assertTrue(blumon.reversos.all { (_, noEmpezar) -> noEmpezar == TOPE })
        }
        // El reloj se pasa del techo entre el reclamo y el POST: la vuelta se cierra sin salir.
        prueba {
            blumon.venta = SILENCIO
            blumon.reverso = { delay(1_000); Respuesta.SinRespuesta("TIEMPO") }
            var reclamos = 0
            ganchos["reclamarReverso"] = { if (++reclamos == 2) salto += 60_000 }
            val s = solicitud()
            terminal(s)
            esperarTodo()
            assertEquals(1, blumon.reversos.size)
            assertEquals(EstadoDelReverso.NO_CONCLUYENTE, fila(s).reverso)
            assertEquals(ResultadoParaReportar.PUDO_ENTRAR, fila(s).resultadoParaReportar)
        }
    }

    @Test fun `reverso tardio con TX_007 deja por confirmar`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(10_000); PermisoDeReverso.Concedido("evt-1") }
        // La segunda vuelta sale antes del techo y Blumon contesta TX_007 DESPUÉS del techo.
        blumon.reverso = { n -> delay(9_000); http(200, error(if (n == 1) "TX_008" else "TX_007")) }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, eventos.last())
        esperarTodo()
        assertTrue(blumon.reversos.last().first < TOPE)
        assertTrue(blumon.reversos.last().first + 9_000 > TOPE)
        val f = fila(s)
        assertEquals(EstadoDelReverso.RECHAZADO, f.reverso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_007", f.codigoParaReportar)
        assertFalse(NoSeCobro in tardiasDe(s))
    }

    @Test fun `reverso tardio que entra da el desenlace del banco`() {
        for (origen in Origen.values()) prueba {
            blumon.venta = SILENCIO
            servidor.permiso = {
                delay(10_000)
                if (origen == Origen.VINCULADO_AL_POS) PermisoDeReverso.Concedido("evt-1") else PermisoDeReverso.NoEncontrado
            }
            blumon.reverso = { n -> delay(9_000); if (n == 1) http(200, error("TX_008")) else http(200, REVERSADA) }
            val s = solicitud(origen = origen)
            val eventos = cobrar(s)
            assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, eventos.last())
            esperarTodo()
            assertTrue(blumon.reversos.last().first + 9_000 > TOPE)
            assertEquals("$origen", EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
            assertEquals(EstadoDelReverso.CONFIRMADO, fila(s).reverso)
            assertTrue("$origen", NoSeCobro in tardiasDe(s))
        }
    }

    // ───────────── Tiempos ─────────────

    @Test fun `el reloj de 30 s empieza al salir`() = prueba {
        lector.demora = 20_000
        blumon.venta = SILENCIO
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to 20_000L + 30_000, eventos.last())
        assertEquals(20_000L, fila(s).enviadoEnMs)
    }

    @Test fun `a los 30 s PorConfirmar y el reverso sigue en fondo`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, eventos.last())
        prueba.testScheduler.runCurrent() // R4-1: la escritura del reloj va DESPUÉS de soltar a la cajera
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
        esperarTodo()
        assertTrue(blumon.reversos.any { it.first > CAJERA })
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, fila(s).resultadoParaReportar)
    }

    // ───────────── Sesión y otras ventas ─────────────

    @Test fun `terminal libre para otra venta mientras el reverso sigue`() = prueba {
        blumon.venta = { n -> if (n == 1) SILENCIO(n) else { delay(1_000); http(200, APROBADA) } }
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val a = solicitud()
        val primera = prueba.async { terminal(a) }
        prueba.advanceTimeBy(20_000)
        assertEquals(1, blumon.reversos.size) // el reverso de A sigue
        val b = solicitud()
        assertEquals(APROBADO_VISA, terminal(b))
        assertEquals(1, blumon.inits)
        assertEquals(PorConfirmar(a.importeCentavos), primera.await())
        esperarTodo()
        assertTrue(blumon.reversos.size > 1)
    }

    @Test fun `la segunda venta no renueva la sesion con un reverso vivo`() = prueba {
        blumon.venta = { n -> if (n == 1) SILENCIO(n) else { delay(1_000); http(200, APROBADA) } }
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val a = solicitud()
        val primera = prueba.async { terminal(a) }
        prueba.advanceTimeBy(20_000)
        motor.pedirLlavesNuevas() // las llaves (no el token) piden renovarse mientras el reverso de A sigue
        val b = solicitud()
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), terminal(b))
        assertEquals(1, blumon.inits)
        assertEquals(1, lector.lecturas)
        primera.await()
        esperarTodo() // el reverso de A termina y pasa su techo

        val c = solicitud()
        assertEquals(APROBADO_VISA, terminal(c))
        assertEquals(2, blumon.inits)
    }

    @Test fun `la sesion queda reservada antes de anotar y otra venta no la renueva`() = prueba {
        // A toma la sesión y se queda leyendo la tarjeta: su fila está ANOTADO, así que la libreta no frena nada.
        lector.demora = 20_000
        val a = solicitud()
        val deA = prueba.async { terminal(a) }
        prueba.advanceTimeBy(5_000)
        motor.pedirLlavesNuevas() // algo pide llaves nuevas: sólo la reserva de A lo impide
        val b = solicitud()
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), terminal(b))
        assertEquals(1, blumon.inits)
        assertEquals(APROBADO_VISA, deA.await())
    }

    @Test fun `dos ventas con la sesion vacia hacen una sola inicializacion`() = prueba {
        blumon.demoraInit = 5_000
        val a = solicitud()
        val b = solicitud()
        val deA = prueba.async { terminal(a) }
        val deB = prueba.async { terminal(b) }
        val desenlaces = listOf(deA.await(), deB.await())
        assertEquals(1, blumon.inits)
        assertTrue(APROBADO_VISA in desenlaces)
        assertTrue(noSalio(MotivoNoSalio.TERMINAL_OCUPADA) in desenlaces) // la que llegó segunda al lector

        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(1, blumon.inits)
    }

    @Test fun `no renueva llaves mientras otra venta sigue ENVIADO`() = prueba {
        val vieja = solicitud()
        filaDirecta(vieja)
        val datos = DatosDelEnvio(null, null, Marca.AMEX, Medio.PLASTICO, "378282", "0005", "0484", "h", "l")
        assertTrue(libreta.marcarEnviado(vieja.id, ahora(), datos))
        salto += 1_000 // dentro de su techo, un ENVIADO veta
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), terminal(s))
        assertEquals(0, blumon.inits)
        assertEquals(0, lector.lecturas)
        // Pasado su t0 + techo + una vuelta del reverso ya no veta (r1 R1-5 b, con r2 familia E): sus datos de tarjeta eran de
        // RAM y la última vuelta que pudo empezar antes del techo ya terminó.
        salto += 55_000
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(1, blumon.inits)
    }

    @Test fun `recolectar sin red un id ya ENVIADO adjunta y no dice SIN_RED`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val primero = prueba.async { terminal(s) }
        prueba.advanceTimeBy(5_000)
        red.hay = false
        val eventos = cobrar(s).map { it.first }
        assertFalse(noSalio(MotivoNoSalio.SIN_RED) in eventos)
        assertEquals(primero.await(), eventos.last())

        val guardado = solicitud()
        filaDirecta(guardado) { it.copy(estado = EstadoDelIntento.ENVIADO, enviadoEnMs = 0) }
        assertEquals(listOf(PorConfirmar(guardado.importeCentavos)), cobrar(guardado).map { it.first })
    }

    @Test fun `mismo id tras reiniciar con otro importe da CONTRATO_DISTINTO`() = runTest {
        val primero = Mundo(this)
        val s = solicitud()
        assertEquals(APROBADO_VISA, primero.terminal(s))
        advanceUntilIdle()
        primero.db.close()

        val segundo = Mundo(this, archivo = primero.archivo, proceso = UUID.randomUUID().toString())
        assertEquals(EventoDeCobro.ContratoDistinto, segundo.terminal(s.copy(importeCentavos = 1)))
        assertEquals(s.importeCentavos, segundo.fila(s).importeCentavos)
        assertEquals(EstadoDelIntento.APROBADO, segundo.fila(s).estado)
        assertEquals(APROBADO_VISA, segundo.terminal(s)) // el mismo contrato se reconstruye
        assertTrue(segundo.blumon.ventas.isEmpty())
        primero.cerrar()
        segundo.cerrar()
    }

    @Test fun `concedido con vinculado falso espera el acuse`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(5_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val s = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        val eventos = cobrar(s)
        assertEquals(NoSeCobro, eventos.last().first)
        assertEquals(blumon.reversos.single().first + 1_000 + 5_000, eventos.last().second)
        assertEquals(listOf(s.id), servidor.reportados.map { it.first })

        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar }
        val sinAcuse = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        assertEquals(PorConfirmar(sinAcuse.importeCentavos), terminal(sinAcuse))
    }

    @Test fun `fallback sin respuestas al techo reporta PUDO_ENTRAR`() = prueba {
        blumon.venta = SILENCIO
        servidor.permiso = { delay(600_000); PermisoDeReverso.Concedido("evt-1") }
        blumon.reverso = { delay(1_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertFalse(f.vinculoAcreditadoPorPermiso)
        assertTrue(blumon.reversos.isNotEmpty())
    }

    @Test fun `sin tiempo para el permiso no compromete`() = prueba(Relojes(techoDelReverso = 20_000)) {
        blumon.venta = { delay(18_000); http(200, APROBADA) } // llega después de soltar: sólo se guarda
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to T0 + 15_000, eventos.last())
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(12345L, f.evidenciaTardiaOperacion)
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `acuse reversed tardio se reconstruye de la fila tras apagar antes de publicar`() = runTest {
        val antes = Mundo(this)
        antes.blumon.venta = SILENCIO
        antes.servidor.acuse = { delay(20_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        // El proceso muere justo después del commit del acuse, antes de publicarlo.
        antes.ganchos["aplicarAcuse"] = { antes.fondo.cancel() }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), antes.terminal(s))
        advanceUntilIdle()
        assertEquals(EstadoDelIntento.NO_SE_COBRO, antes.fila(s).estado)
        assertFalse(NoSeCobro in antes.tardiasDe(s))
        antes.db.close()

        val despues = Mundo(this, archivo = antes.archivo, proceso = UUID.randomUUID().toString())
        assertEquals(listOf(NoSeCobro), despues.cobrar(s).map { it.first })
        assertTrue(despues.blumon.ventas.isEmpty())
        antes.cerrar()
        despues.cerrar()
    }

    @Test fun `dos pendientes uno reversed y otro contradiction`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar }
        val uno = solicitud()
        val otro = solicitud()
        assertEquals(PorConfirmar(uno.importeCentavos), terminal(uno))
        assertEquals(PorConfirmar(otro.importeCentavos), terminal(otro))
        assertEquals(EstadoDelReporte.PENDIENTE, fila(uno).reporte)
        assertEquals(EstadoDelReporte.PENDIENTE, fila(otro).reporte)

        val acuses = ArrayDeque(listOf(guardado(EstadoDelReversoEnServidor.REVERSED), guardado(EstadoDelReversoEnServidor.CONTRADICTION)))
        servidor.acuse = { acuses.removeFirst() }
        assertTrue(motor.recuperar().containsAll(listOf(uno.id, otro.id)))
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(uno).estado)
        assertEquals(EstadoDelIntento.NO_SE, fila(otro).estado)
        assertTrue(NoSeCobro in tardiasDe(uno))
        assertFalse(NoSeCobro in tardiasDe(otro))
        assertEquals(NoSeCobro, terminal(uno))
        assertEquals(PorConfirmar(otro.importeCentavos), terminal(otro))
    }

    @Test fun `YaCobrado con otro importe da PorConfirmar`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", s.importeCentavos + 1, 0, "ABC9") }
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
        assertNull(fila(s).paymentId)
        assertTrue(blumon.reversos.isEmpty())
    }

    /**
     * Ola final, 4ª parte · 7 (r25): la evidencia «ya cobrado» exige importe y propina ≥ 0 y una suma que no desborde. Un importe
     * negativo con una propina que lo compense «cuadra» con el total, pero no sella `APROBADO`: queda en duda sin reversar.
     */
    @Test fun `YaCobrado con importe negativo o que desborda no sella APROBADO`() = prueba {
        blumon.venta = SILENCIO
        val s0 = solicitud()
        val casos = listOf(
            -5_000L to s0.importeCentavos + 5_000, // negativo + propina que compensa
            s0.importeCentavos + 5_000 to -5_000L, // propina negativa que compensa
            Long.MIN_VALUE to Long.MIN_VALUE + s0.importeCentavos, // la suma desborda y, dando la vuelta, «cuadra»
        )
        for ((importe, propina) in casos) {
            val s = solicitud()
            assertEquals("$importe + $propina", s.importeCentavos, importe + propina) // el total cuadra (con el desborde)
            servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", importe, propina, "ABC9") }
            assertEquals("$importe + $propina", PorConfirmar(s.importeCentavos), terminal(s))
            esperarTodo()
            assertEquals("$importe + $propina", EstadoDelIntento.NO_SE, fila(s).estado)
            assertNull("$importe + $propina", fila(s).paymentId)
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `venta NoEnviada da NO_SALIO y no reversa`() = prueba {
        blumon.venta = { delay(14_999); Respuesta.NoEnviada } // el plazo de la venta venció sin escribir un byte
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertTrue(servidor.permisos.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
    }

    // ───────────── Sin salir al banco y casos de dinero ─────────────

    @Test fun `401 de Blumon en la venta es duda y va al reverso`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(401, APROBADA) else http(200, APROBADA) }
        val s = solicitud()
        val eventos = cobrar(s).map { it.first }
        assertFalse(eventos.any { it is Aprobado })
        assertEquals(NoSeCobro, eventos.last())
        assertEquals(listOf(s.id), servidor.permisos)
        assertEquals(1, blumon.reversos.size)
        assertEquals(APROBADO_VISA, terminal(solicitud())) // enseguida, sin esperar su techo
        assertEquals(1, blumon.inits)
        assertEquals(1, blumon.renovaciones)
    }

    @Test fun `un 401 en la venta no bloquea la siguiente venta`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(401, APROBADA) else http(200, APROBADA) }
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") } // el reverso de A sigue vivo
        val a = solicitud()
        val deA = prueba.async { terminal(a) }
        prueba.advanceTimeBy(5_000)
        val b = solicitud()
        val eventos = cobrar(b)
        assertEquals(APROBADO_VISA, eventos.last().first)
        assertEquals(5_000L + 1_000 + 1_000, eventos.last().second) // el lector y la venta: nada de esperar 45 s
        assertEquals(PorConfirmar(a.importeCentavos), deA.await())
        esperarTodo()
        assertEquals(1, blumon.inits) // initDukptKeys una sola vez en toda la prueba
    }

    @Test fun `un 401 en la venta renueva solo el token`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(403, APROBADA) else http(200, APROBADA) }
        val a = solicitud()
        assertEquals(NoSeCobro, terminal(a))
        val b = solicitud()
        assertEquals(APROBADO_VISA, terminal(b))
        assertEquals(1, blumon.inits)
        assertEquals(1, blumon.renovaciones)
        assertEquals(listOf(TOKEN_INICIAL, "token-1"), blumon.tokensDeVenta)
        // Las llaves no se tocaron: la misma base y el contador sigue.
        assertEquals(fila(a).ksnBase, fila(b).ksnBase)
        assertEquals(fila(a).contadorUsado!! + 1, fila(b).contadorUsado)
    }

    @Test fun `un reverso que contesta 401 reintenta con token renovado y mismo cuerpo`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { n -> delay(1_000); if (n == 1) http(401, REVERSADA) else http(200, REVERSADA) }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        val (primero, segundo) = blumon.reversosEnviados
        assertEquals(TOKEN_INICIAL, primero.first)
        assertEquals("token-1", segundo.first)
        assertTrue(primero.second.contentEquals(segundo.second)) // el cuerpo, byte por byte
        assertEquals(1, blumon.renovaciones)
        assertEquals(1, blumon.inits)
    }

    // ───────────── Ronda 2: el candado de la sesión tiene plazo, y el reverso de una venta con 401 sale con token nuevo ─────────────

    @Test fun `un tokener lento no congela la venta siguiente`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(401, APROBADA) else http(200, APROBADA) }
        blumon.renovar = { delay(600_000); "token-que-llega-tarde" }
        val a = solicitud()
        val deA = prueba.async { terminal(a) }
        prueba.advanceTimeBy(4_000)
        // A: 401 a los 2 s, permiso a los 3 s y su reverso pide token: lo intenta a lo más min(sesión, reverso) = 10 s.
        val b = solicitud()
        val inicioB = prueba.currentTime
        val eventos = cobrar(b)
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), eventos.last().first)
        assertTrue(eventos.last().second <= inicioB + 15_000) // nunca más que esperaSesion desde que empezó a esperar
        deA.await()
        assertEquals(3_000L + 10_000, blumon.reversos.first().first) // el reverso se rindió en su plazo y salió con el congelado
        assertEquals(TOKEN_INICIAL, blumon.reversosEnviados.first().first)
    }

    @Test fun `la toma del candado de sesion respeta la espera de sesion`() = prueba {
        blumon.demoraInit = 600_000 // la inicialización de A se cuelga con el candado tomado
        val a = solicitud()
        val deA = prueba.async { cobrar(a) }
        prueba.advanceTimeBy(5_000)
        val b = solicitud()
        val eventos = cobrar(b)
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), eventos.last().first)
        assertEquals(5_000L + 15_000, eventos.last().second) // esperó el candado y aun así salió a los 15 s de empezar
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES) to 15_000L, deA.await().last())
    }

    @Test fun `una venta con 401 reversa desde la primera vuelta con token renovado y mismo cuerpo`() = prueba {
        blumon.venta = { delay(1_000); http(401, APROBADA) }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        assertEquals(1, blumon.reversosEnviados.size)
        val (token, cuerpo) = blumon.reversosEnviados.single()
        assertEquals("token-1", token)
        assertEquals(TOKEN_INICIAL, blumon.tokensDeVenta.single()) // el congelado era otro
        assertTrue(cuerpo.contentEquals(blumon.cuerposDeVenta.single())) // el mismo cuerpo, byte por byte (mismo KSN y contador)
    }

    /**
     * Con el token congelado (que ya dio 401) la primera vuelta contestaría 401 y `pudoEntrar` subiría para siempre. Con token
     * nuevo, Blumon contesta TX_008: la fila F da `PorConfirmar` en la terminal, pero al SERVIDOR se le reporta `RECHAZADO`
     * (TX_008), no `PUDO_ENTRAR`.
     */
    @Test fun `una venta con 401 cuyo reverso contesta TX_008 se reporta RECHAZADO y no PUDO_ENTRAR`() = prueba {
        blumon.venta = { delay(1_000); http(401, APROBADA) }
        blumon.reverso = {
            delay(1_000)
            if (blumon.reversosEnviados.last().first == TOKEN_INICIAL) http(401, REVERSADA) else http(200, error("TX_008"))
        }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertFalse(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_008", f.codigoParaReportar)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `sin token nuevo la primera vuelta sale con el token congelado`() = prueba {
        blumon.venta = { delay(1_000); http(401, APROBADA) }
        blumon.renovar = { null }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s)) // el doble contesta REVERSADA a cualquier token
        assertEquals(TOKEN_INICIAL, blumon.reversosEnviados.first().first)
        assertEquals(1, blumon.renovaciones)
    }

    @Test fun `sin token nuevo el reverso termina como hoy`() = prueba {
        blumon.venta = SILENCIO
        blumon.renovar = { null }
        blumon.reverso = { delay(1_000); http(401, REVERSADA) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        assertTrue(blumon.renovaciones > 0)
        assertTrue(blumon.reversosEnviados.all { it.first == TOKEN_INICIAL })
        val f = fila(s)
        assertTrue(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `YaCobrado con propina cuadra con el total`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_2", s.importeCentavos - 1_500, 1_500, null) }
        assertEquals(APROBADO_VISA.copy(operationNumber = null, autorizacion = null, paymentId = "pay_2"), terminal(s))
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `parcial guarda su evidencia antes de reversar`() = prueba {
        blumon.venta = { delay(1_000); http(200, PARCIAL) }
        val s = solicitud()
        var alSalir: IntentoEntity? = null
        blumon.alReversar = { alSalir = libreta.buscar(s.id) }
        assertEquals(NoSeCobro, terminal(s))
        assertEquals(777L, alSalir?.parcialOperacion)
        assertEquals(50L, alSalir?.parcialImporteCentavos)
        assertEquals(EstadoDelIntento.ENVIADO, alSalir?.estado) // nunca promueve el estado
    }

    @Test fun `reverso 5xx con TX_008 en el cuerpo no prueba que no entro`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(1_000); http(503, error("TX_008")) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertTrue(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertNull(f.codigoParaReportar)
    }

    @Test fun `excepcion del puerto nunca escapa del fondo`() = prueba {
        // En la venta: pudo salir, así que es duda y va al reverso.
        blumon.venta = { throw IllegalStateException("transporte") }
        // En el reverso: pudo escribir bytes, así que «pudo entrar», y la fila nunca se queda en RECLAMADO.
        blumon.reverso = { throw IllegalStateException("transporte") }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertTrue(blumon.reversos.isNotEmpty())
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertTrue(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `amex no reserva contador`() = prueba {
        lector.resultado = { ResultadoDeLectura.Leida(LecturasDePrueba.amexPlasticoVenta(it)) }
        val s = solicitud()
        val e = terminal(s)
        assertTrue(e is Aprobado && e.marca == Marca.AMEX)
        assertNull(fila(s).contadorUsado)
        assertNull(fila(s).ksnBase)
        assertNull(db.dao().contador(Dukpt.baseCanonica(BASE_KSN.hexABytes())))
    }

    @Test fun `contador agotado no sale`() = prueba {
        blumon.ultimoContador = Dukpt.CONTADOR_MAXIMO - 10
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.CONTADOR_AGOTADO), terminal(s))
        assertEquals(MotivoNoSalio.CONTADOR_AGOTADO, fila(s).motivoNoSalio)
        assertTrue(blumon.ventas.isEmpty())
    }

    @Test fun `lectura que no sale cierra su fila`() = prueba {
        val casos = listOf<Pair<(Long) -> ResultadoDeLectura, EventoDeCobro>>(
            { _: Long -> ResultadoDeLectura.Cortada(-1) } to noSalio(MotivoNoSalio.LECTURA_CORTADA),
            { _: Long -> ResultadoDeLectura.Cancelada } to noSalio(MotivoNoSalio.CANCELADA_EN_LECTOR),
            { _: Long -> ResultadoDeLectura.Vencida } to noSalio(MotivoNoSalio.LECTURA_VENCIDA),
            { i: Long -> ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(i + 1)) } to
                noSalio(MotivoNoSalio.NO_ADMITIDA, MotivoNoAdmitida.IMPORTE_NO_COINCIDE),
        )
        for ((resultado, esperado) in casos) {
            lector.resultado = resultado
            val s = solicitud()
            assertEquals(esperado, terminal(s))
            assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        }
        assertTrue(blumon.ventas.isEmpty())
    }

    // ───────────── Codex rama r1 ─────────────

    /**
     * R1-1: A no ve el id en `vivos` y se tarda en leer la libreta; mientras tanto B registra el intento y lo anota (ANOTADO)
     * o ya lo envió (ENVIADO). Con la puerta bajo un candado, B espera a que A termine de decidir y se adjunta.
     */
    @Test fun `una consulta concurrente nunca dice no salio de un intento vivo`() = prueba {
        for (demora in listOf(500L, 1_500L)) { // 500: B ya anotó (ANOTADO); 1 500: B ya salió (ENVIADO, t0 = 1 s)
            val s = solicitud()
            var primera = true
            motor.antesDeBuscar = { id -> if (id == s.id && primera) { primera = false; delay(demora) } }
            val a = prueba.async { cobrar(s).map { it.first } }
            val b = prueba.async { delay(1); cobrar(s).map { it.first } }
            val (ea, eb) = a.await() to b.await()
            assertEquals("$demora", APROBADO_VISA, ea.last())
            assertEquals("$demora", ea.last(), eb.last())
            assertFalse("$demora", (ea + eb).any { it is NoSalioAlBanco || it is PorConfirmar })
        }
        assertEquals(2, blumon.ventas.size)
        assertEquals(2, lector.lecturas)
    }

    /**
     * R1-3: el cliente HTTP real devuelve el código al vencer SU plazo, con el cuerpo sin leer: ese código veta. Desde r2
     * (familia B) el plazo de la llamada ES el `esperaPermiso` y el motor no le pone otro reloj encima (la carrera real con
     * OkHttp está en `CarreraDelPermisoTest`).
     */
    @Test fun `un 409 cuyo cuerpo se atora no reversa`() = prueba {
        blumon.venta = SILENCIO
        for (atorado in listOf(PermisoDeReverso.Invalido(409, null), PermisoDeReverso.ErrorDelServidor)) {
            servidor.permiso = { delay(it.inWholeMilliseconds - 1); atorado }
            for (origen in Origen.values()) {
                val s = solicitud(origen = origen)
                assertEquals("$atorado $origen", PorConfirmar(s.importeCentavos), terminal(s))
                assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
            }
        }
        assertTrue(servidor.esperas.all { it.inWholeMilliseconds == Relojes().esperaPermiso })
        assertTrue(blumon.reversos.isEmpty())
    }

    /** R1-4: una libreta por archivo en el proceso, así que `tomarDueno()` frena al segundo motor. */
    @Test fun `dos abrir del mismo archivo dan la misma libreta y un segundo motor truena`() = prueba {
        val nombre = "abrir-${UUID.randomUUID()}.db"
        val a = Libreta.abrir(context, nombre)
        fun motorSobre(l: Libreta) = MotorDeCobro(
            identidad, lector, red, blumon, servidor, l, ColaDeResultados(l, servidor, io), huella,
            CatalogoDeRechazos.BLUMON, Relojes(), fondo, ahora, { pared }, io,
        )
        try {
            assertSame(a, Libreta.abrir(context, nombre))
            motorSobre(a)
            assertThrows(IllegalStateException::class.java) { motorSobre(Libreta.abrir(context, nombre)) }
            // El segundo que tronó no soltó el dueño del primero: un tercero también truena.
            assertThrows(IllegalStateException::class.java) { motorSobre(a) }
        } finally {
            a.cerrar()
        }
        val otra = Libreta.abrir(context, nombre) // cerrada, ya no se reusa
        assertNotSame(a, otra)
        otra.cerrar()
    }

    /** Revisión de la tarea 6: un constructor que truena (identidad sin forma) deja el dueño de la libreta como estaba. */
    @Test fun `un motor que no se arma no toma el dueno de la libreta`() = prueba {
        val l = Libreta.abrir(context, "sin-forma-${UUID.randomUUID()}.db")
        fun motorCon(id: Identidad) = MotorDeCobro(
            id, lector, red, blumon, servidor, l, ColaDeResultados(l, servidor, io), huella,
            CatalogoDeRechazos.BLUMON, Relojes(), fondo, ahora, { pared }, io,
        )
        try {
            assertThrows(IllegalArgumentException::class.java) { motorCon(identidad.copy(posId = "X")) }
            motorCon(identidad)
        } finally {
            l.cerrar()
        }
    }

    /** R1-5 (a): REVERSADA y la escritura de `cerrarVuelta` truena: el `catch` cierra la fila en el mejor esfuerzo. */
    @Test fun `un fallo al cerrar la vuelta tras REVERSADA no deja la fila abierta`() = prueba {
        blumon.venta = SILENCIO
        ganchos["cerrarVuelta"] = { throw IllegalStateException("falla inyectada") }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(1, blumon.reversos.size)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertTrue(f.pudoEntrar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
    }

    /** R1-5 (c): si también falla el cierre del `catch`, `recuperar()` cierra la huérfana del mismo proceso, no una viva. */
    @Test fun `recuperar cierra una fila huerfana del mismo proceso`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar }
        ganchos["cerrarVuelta"] = { throw IllegalStateException("falla inyectada") }
        ganchos["cerrarHuerfana"] = { throw IllegalStateException("falla inyectada") }
        val huerfana = solicitud()
        assertEquals(PorConfirmar(huerfana.importeCentavos), terminal(huerfana))
        // r10 (nota a s c1): los ganchos siguen tronando hasta que el `Registro` es lápida; si no, el `catch` del motor cierra
        // la fila él solo y la prueba ya no ve el barrido.
        prueba.testScheduler.runCurrent()
        assertEquals(true, motor.intentos[huerfana.id]?.retirado)
        ganchos.clear()
        assertEquals(EstadoDelIntento.ENVIADO, fila(huerfana).estado)
        assertEquals(EstadoDelReverso.RECLAMADO, fila(huerfana).reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, fila(huerfana).reporte) // la cola no tiene nada que entregar de ella

        val viva = solicitud()
        val desenlace = prueba.async { terminal(viva) }
        prueba.advanceTimeBy(5_000)
        assertEquals(EstadoDelIntento.ENVIADO, fila(viva).estado)
        val cambiados = motor.recuperar()
        assertTrue(huerfana.id in cambiados)
        assertFalse(viva.id in cambiados)
        val f = fila(huerfana)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertTrue(f.pudoEntrar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte) // sólo el barrido lo deja pendiente
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals(EstadoDelIntento.ENVIADO, fila(viva).estado) // la viva sigue su camino
        assertEquals(PorConfirmar(viva.importeCentavos), desenlace.await()) // el acuse no llegó: por confirmar
        assertEquals(2, blumon.reversos.size) // la viva sí reversó
    }

    /** R1-6: el SERVIDOR ya dijo «ya cobrado» y luego llega la aprobación del banco: su folio se guarda como evidencia. */
    @Test fun `la aprobacion del banco despues del ya cobrado del servidor guarda su folio`() = prueba {
        blumon.venta = { delay(20_000); http(200, APROBADA) }
        val s = solicitud()
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", s.importeCentavos, null, "ABC9") }
        assertEquals(APROBADO_VISA.copy(operationNumber = null, autorizacion = "ABC9", paymentId = "pay_1"), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelIntento.APROBADO, f.estado)
        assertEquals(OrigenDeLaAprobacion.SERVIDOR, f.origenDeLaAprobacion)
        assertEquals(12345L, f.evidenciaTardiaOperacion)
        assertEquals("ABC123", f.evidenciaTardiaAutorizacion)
        assertNull(f.operationNumber)
        assertEquals("ABC9", f.autorizacion)
        assertTrue(blumon.reversos.isEmpty())
    }

    /**
     * R1-7: el reloj leyó NO_SE y publica DESPUÉS de que el reverso emitió `NoSeCobro`: ese «por confirmar» es viejo. S3: el
     * primer terminal se publica siempre, haya o no quien mire.
     */
    @Test fun `un por confirmar viejo nunca se publica despues de no se cobro`() = prueba {
        val s = solicitud()
        val r = motor.Registro(s)
        r.emitir(NoSeCobro) // el reverso cerró primero
        r.emitir(PorConfirmar(s.importeCentavos)) // y el reloj publica su lectura vieja
        r.emitir(NoSeCobro) // el mismo terminal no se repite
        prueba.testScheduler.runCurrent()
        assertEquals(listOf(NoSeCobro), r.historia.value)
        assertEquals(listOf(NoSeCobro), tardiasDe(s))
    }

    /** S3: el primer terminal Y el definitivo que lo reemplaza salen por `resolucionesTardias`. */
    @Test fun `un no se cobro despues de por confirmar si se publica`() = prueba {
        val s = solicitud()
        val r = motor.Registro(s)
        r.emitir(PorConfirmar(s.importeCentavos)) // la cajera se soltó
        r.emitir(NoSeCobro) // la resolución que llega después
        prueba.testScheduler.runCurrent()
        assertEquals(listOf(PorConfirmar(s.importeCentavos), NoSeCobro), tardiasDe(s))
    }

    /** R1-7 (piezas comunes 7): quien pregunta tarde ve el final vigente, no el primer «por confirmar». */
    @Test fun `una consulta nueva despues de no se cobro recibe no se cobro y no el por confirmar`() = prueba {
        val s = solicitud()
        val r = motor.Registro(s)
        motor.intentos[s.id] = r // el intento vivo de este proceso
        r.emitir(PorConfirmar(s.importeCentavos)) // la cajera se soltó a los 30 s
        r.emitir(NoSeCobro) // y luego se confirmó el reverso
        r.emitir(PorConfirmar(s.importeCentavos)) // una lectura vieja nunca regresa a «por confirmar»
        assertEquals(NoSeCobro, r.actual)
        assertEquals(listOf<EventoDeCobro>(NoSeCobro), cobrar(s).map { it.first }) // adjuntar: el vigente
        motor.intentos.remove(s.id)
        // Un definitivo nunca se reemplaza.
        val otra = solicitud()
        val r2 = motor.Registro(otra)
        r2.emitir(NoSeCobro)
        r2.emitir(PorConfirmar(otra.importeCentavos))
        assertEquals(NoSeCobro, r2.actual)
    }

    /** R1-8: lo que manda el SERVIDOR también se coteja contra la tarjeta de la lectura. */
    @Test fun `una autorizacion del SERVIDOR con 12 digitos del PAN no llega a la libreta ni al evento`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val conPan = LecturasDePrueba.PAN_VISA.take(12)
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("pay_1", s.importeCentavos, null, conPan) }
        assertEquals(APROBADO_VISA.copy(operationNumber = null, autorizacion = null, paymentId = "pay_1"), terminal(s))
        assertNull(fila(s).autorizacion)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `un paymentId con el PAN no reversa y no se guarda`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        servidor.permiso = { delay(1_000); PermisoDeReverso.YaCobrado("p${LecturasDePrueba.PAN_VISA}", s.importeCentavos, null, null) }
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
        assertNull(fila(s).paymentId)
        assertTrue(blumon.reversos.isEmpty())
    }

    // ───────────── Codex rama r8 ─────────────

    /** R8-1: nadie compara contra un registro provisional; manda la fila. */
    @Test fun `H compara contra la fila y no contra un registro provisional`() = prueba {
        val correcta = solicitud(importe = 10_000)
        filaDirecta(correcta) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 777, autorizacion = "XYZ1") }
        val vieja = correcta.copy(importeCentavos = 9_000)
        motor.antesDeBuscar = { id -> if (id == correcta.id) delay(2_000) } // Room lento para la pantalla A
        val a = prueba.async { cobrar(vieja).map { it.first } }
        prueba.advanceTimeBy(500)
        val b = prueba.async { cobrar(correcta).map { it.first } } // la pantalla B, con el contrato de la fila
        assertTrue(b.await().single() is Aprobado)
        assertEquals(listOf(EventoDeCobro.ContratoDistinto), a.await())
        assertTrue(blumon.ventas.isEmpty())
    }

    /**
     * Tests 2 y 3 (regla C): `cortar(s)` antes del punto ⇒ `CORTADO` con su veto escrito, cero POST y el flow lo dice; repetirlo
     * no cambia nada. Después del punto ⇒ `YA_SALIO`, la venta sigue su camino y su desenlace se publica.
     */
    @Test fun `cortar dice si alcanzo a cortar`() = prueba {
        val antes = solicitud()
        val a = prueba.async { terminal(antes) }
        prueba.advanceTimeBy(500) // leyendo la tarjeta, con su fila ANOTADO
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(antes))
        val vetada = fila(antes)
        assertEquals(EstadoDelIntento.NO_SALIO, vetada.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, vetada.motivoNoSalio)
        assertTrue(vetada.cortarAntesDeSalir)
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(antes)) // otra vez: sin efecto extra
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), a.await())
        assertTrue(blumon.ventas.isEmpty())

        blumon.venta = SILENCIO
        val despues = solicitud()
        val d = prueba.async { terminal(despues) }
        prueba.advanceTimeBy(T0 + 5_000) // ya salió
        assertEquals(ResultadoDelCorte.YA_SALIO, motor.cortar(despues))
        assertEquals(NoSeCobro, d.await()) // el corte llegó tarde: la venta siguió su camino (aquí, su reverso)
        esperarTodo()
        assertEquals(listOf<EventoDeCobro>(NoSeCobro), tardiasDe(despues))
        assertEquals(1, blumon.ventas.size)
    }

    // ───────────── Codex rama r7 · la familia de los candados ─────────────

    /** S1: un colector que se va por un error tampoco corta: la venta sale y su final se publica. */
    @Test fun `un colector que se va por error no corta la venta`() = prueba {
        val s = solicitud()
        val quien = prueba.async {
            runCatching { motor.cobrar(s).collect { if (it == PideTarjeta) throw IllegalStateException("se va") } }
        }
        assertTrue(quien.await().isFailure)
        esperarTodo()
        assertEquals(1, blumon.ventas.size)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertEquals(listOf<EventoDeCobro>(APROBADO_VISA), tardiasDe(s))
    }

    /** R7-3: `recuperar()` esperando a Room (40 s) nunca retiene a una pantalla que vuelve a escuchar un intento vivo. */
    @Test fun `volver a escuchar un intento vivo no espera a recuperar`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        val primera = prueba.async { terminal(s) }
        prueba.advanceTimeBy(T0 + 5_000)
        motor.antesDeRecuperar = { delay(40_000) }
        val recuperacion = prueba.async { motor.recuperar() }
        prueba.advanceTimeBy(1_000)
        val otra = cobrar(s) // otra pantalla vuelve a escuchar el mismo intento
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, otra.last())
        assertEquals(PorConfirmar(s.importeCentavos), primera.await())
        recuperacion.await()
        esperarTodo()
    }

    // ───────────── Codex rama r4 ─────────────

    /** R4-1: a los 30 s la cajera recibe su final ANTES de que el reloj toque Room; la escritura va después. */
    @Test fun `la cajera recibe por confirmar antes de la escritura del reloj`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(9_000); Respuesta.SinRespuesta("TIEMPO") }
        val s = solicitud()
        var alEscribir: EventoDeCobro? = null
        var escrituras = 0
        ganchos["transicionar"] = {
            if (prueba.currentTime == CAJERA) { escrituras++; alEscribir = motor.intentos[s.id]?.actual }
        }
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, eventos.last())
        prueba.testScheduler.runCurrent()
        assertEquals(1, escrituras)
        assertEquals("la cajera ya tenía su final cuando el reloj escribió", PorConfirmar(s.importeCentavos), alEscribir)
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
        esperarTodo()
    }

    /**
     * Test 1 (S1): se cancela al único colector justo después de leer la tarjeta, antes del punto de no retorno. Ya no corta:
     * la venta SALE, la fila termina con su desenlace y éste sale por `resolucionesTardias`.
     */
    @Test fun `cancelar al unico colector despues de leer la tarjeta no corta la venta`() = prueba {
        val s = solicitud()
        val colector = prueba.launch { motor.cobrar(s).collect {} }
        motor.antesDelPuntoSinRetorno = { colector.cancel() }
        esperarTodo()
        assertEquals(1, blumon.ventas.size)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertEquals(listOf<EventoDeCobro>(APROBADO_VISA), tardiasDe(s))
    }

    /** Test 2, con el orden fijado: `cortar(s)` en el último instante antes del punto ⇒ `CORTADO`, cero POST, fila NO_SALIO. */
    @Test fun `cortar justo antes del punto de no retorno nunca deja salir la venta`() = prueba {
        val s = solicitud()
        var corte: ResultadoDelCorte? = null
        motor.antesDelPuntoSinRetorno = { corte = motor.cortar(s) }
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        assertEquals(ResultadoDelCorte.CORTADO, corte)
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertTrue(fila(s).cortarAntesDeSalir)
        esperarTodo()
        assertEquals(listOf(noSalio(MotivoNoSalio.INTERRUMPIDO)), tardiasDe(s))
    }

    /** R4-2, el orden inverso: pasado el punto de no retorno, el corte llega tarde y la venta sigue su camino. */
    @Test fun `un corte despues del punto de no retorno no cambia la venta`() = prueba {
        val s = solicitud()
        val colector = prueba.launch { motor.cobrar(s).collect {} }
        ganchos["marcarEnviado"] = { colector.cancel() }
        esperarTodo()
        assertEquals(1, blumon.ventas.size)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertFalse(fila(s).cortarAntesDeSalir)
    }

    // ───────────── Piezas comunes · 2 y 4 ─────────────

    /** R3-2: el transporte ya leyó el REVERSADA y el reloj de la vuelta vence antes de que la corrutina lo procese. */
    @Test fun `un REVERSADA leido antes de que venza el reloj de la vuelta confirma`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { blumon.alLeer(http(200, REVERSADA)); delay(Relojes().esperaReverso + 1); http(200, REVERSADA) }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        val f = fila(s)
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar)
        assertFalse(f.pudoEntrar)
        assertEquals(1, blumon.reversos.size)
    }

    /** R2-5: con `fondo` terminado y el proceso vivo, la IPEK instalada queda en ceros y ya no entra ningún cobro. */
    @Test fun `cancelar fondo pone en ceros la IPEK de la sesion instalada`() = prueba {
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        val sesion = requireNotNull(blumon.sesiones.single())
        val ipek = requireNotNull(sesion.material.usar { i, _ -> i })
        assertTrue(ipek.any { it != 0.toByte() })
        fondo.cancel()
        esperarTodo()
        assertTrue("la IPEK quedó en ceros", ipek.all { it == 0.toByte() })
        assertNull(sesion.material.usar { i, _ -> i })
        val nueva = solicitud()
        assertEquals(PorConfirmar(nueva.importeCentavos), terminal(nueva)) // regla M: sin `fondo` no se lee la fila
        assertEquals(1, blumon.inits)
    }

    /** v4-8: renovar el token reúsa el material; renovar las llaves cierra el viejo y nunca el nuevo. */
    @Test fun `renovar el token y luego las llaves no borra la IPEK de la sesion nueva`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(401, "{}") else http(200, APROBADA) }
        blumon.reverso = { delay(1_000); http(200, REVERSADA) }
        terminal(solicitud())
        esperarTodo()
        assertEquals(APROBADO_VISA, terminal(solicitud())) // renovó sólo el token
        assertEquals(1, blumon.inits)
        val primera = blumon.sesiones.single()
        assertTrue(primera.material.usar { i, _ -> i.any { it != 0.toByte() } } == true)
        salto += 60_000 // pasado el veto de la primera venta
        motor.pedirLlavesNuevas()
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(2, blumon.inits)
        assertNull("la vieja se cerró", primera.material.usar { i, _ -> i })
        assertTrue("la nueva sigue viva", blumon.sesiones.last().material.usar { i, _ -> i.any { it != 0.toByte() } } == true)
    }

    /** Arreglos 1 de la Tarea 8 (D): `renewKey: true` en la venta ⇒ la SIGUIENTE inicia sesión completa; `false`, no. */
    @Test fun `renewKey true en la venta pide llaves nuevas para la siguiente`() = prueba {
        val conRenew = """{"status":true,"id":12345,"dataResponse":{"authorization":"ABC123","renewKey":true,"batch":"$ECO","simulation":false}}"""
        val sinRenew = conRenew.replace("\"renewKey\":true", "\"renewKey\":false")
        blumon.venta = { n -> delay(1_000); http(200, if (n == 2) conRenew else sinRenew) }
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        esperarTodo()
        assertEquals(APROBADO_VISA, terminal(solicitud())) // la que trae renewKey: true, aprobada igual
        esperarTodo()
        assertEquals(1, blumon.inits)
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        esperarTodo()
        assertEquals(2, blumon.inits) // la siguiente renovó llaves
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(2, blumon.inits) // con `false` no se renueva otra vez
    }

    /** Arreglos 2: `renewKey: true` en la respuesta TARDÍA de la venta (después del arbitraje) también pide llaves nuevas. */
    @Test fun `renewKey true en la respuesta tardia pide llaves nuevas`() = prueba {
        blumon.venta = { delay(20_000); http(200, APROBADA.replace("\"simulation\"", "\"renewKey\":true,\"simulation\"")) }
        terminal(solicitud())
        esperarTodo()
        assertEquals(1, blumon.inits)
        salto += 60_000 // pasado el veto de la primera venta
        blumon.venta = { delay(1_000); http(200, APROBADA) }
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(2, blumon.inits)
    }

    /** Arreglos 2: `renewKey: true` en la respuesta del REVERSO también pide llaves nuevas. */
    @Test fun `renewKey true en el reverso pide llaves nuevas`() = prueba {
        blumon.venta = { delay(1_000); http(500, "{}") }
        blumon.reverso = { delay(1_000); http(200, REVERSADA.replace("\"simulation\"", "\"renewKey\":true,\"simulation\"")) }
        terminal(solicitud())
        esperarTodo()
        assertEquals(1, blumon.inits)
        salto += 60_000
        blumon.venta = { delay(1_000); http(200, APROBADA) }
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(2, blumon.inits)
    }

    // ───────────── Codex rama r2 · Familia A: el ciclo de vida ─────────────

    /** R2-2: el banco ya tiene el POST y se cancela `fondo`; quien mira desde otra corrutina recibe lo que dice la fila. */
    @Test fun `cancelar fondo con la venta en el banco nunca deja autorizando`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val visto = prueba.async { cobrar(s).map { it.first } }
        prueba.advanceTimeBy(5_000)
        assertEquals(EstadoDelIntento.ENVIADO, fila(s).estado)
        fondo.cancel()
        val eventos = withTimeoutOrNull(60_000) { visto.await() }
        assertEquals(PorConfirmar(s.importeCentavos), eventos?.last())
        // R6-1: la cajera sale desde la memoria ANTES de que el cierre lea la fila y retire el intento; se deja terminar.
        esperarTodo()
        // Ya no está viva: la cierra recuperar(), que nunca salta una viva.
        assertTrue(s.id in motor.recuperar())
        assertEquals(EstadoDelIntento.NO_SE, fila(s).estado)
    }

    /**
     * R2-2 (y el minor de T7): con `fondo` ya cancelado, un id nuevo nunca deja a su colector esperando ni sale al banco. Regla
     * M: sin `fondo` no se puede leer la fila, así que contesta `PorConfirmar` en el acto y nada se registra.
     */
    @Test fun `con fondo ya cancelado un cobro nuevo no se cuelga ni sale`() = prueba {
        fondo.cancel()
        val s = solicitud()
        val eventos = withTimeoutOrNull(60_000) { cobrar(s) }
        assertEquals(PorConfirmar(s.importeCentavos) to 0L, eventos?.last())
        assertTrue(motor.intentos.isEmpty())
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(0, lector.lecturas)
    }

    /** R2-6: la foto de los vivos nunca truena aunque el único intento salga entre contar y recorrer. */
    @Test fun `la foto de vivos nunca truena aunque un intento salga a la mitad`() = prueba {
        val r = motor.Registro(solicitud())
        val alto = AtomicBoolean(false)
        val hilo = thread {
            while (!alto.get()) {
                motor.intentos[r.id] = r
                motor.intentos.remove(r.id)
            }
        }
        try {
            repeat(3_000_000) { motor.idsVivos() }
        } finally {
            alto.set(true)
            hilo.join()
        }
    }

    // ───────────── Codex rama r2 · Familia B: un solo reloj ─────────────

    /** La cerca del motor sólo cuida de una implementación que no cumple su plazo, y nunca convierte su silencio en permiso. */
    @Test fun `un servidor que no cumple su plazo nunca autoriza el reverso`() = prueba {
        blumon.venta = SILENCIO
        servidor.cumplePlazo = false
        servidor.permiso = { delay(600_000); PermisoDeReverso.Concedido("evt-1") }
        for (origen in Origen.values()) {
            val s = solicitud(origen = origen)
            assertEquals("$origen", PorConfirmar(s.importeCentavos), terminal(s))
            assertEquals("$origen", EstadoDelIntento.NO_SE, fila(s).estado)
        }
        assertTrue(blumon.reversos.isEmpty())
    }

    // ───────────── Codex rama r2 · Familia D: hechos tardíos por un solo embudo ─────────────

    /** R2-3: un aprobado parcial que llega después del arbitraje deja su folio y su importe como evidencia; el estado no cambia. */
    @Test fun `un parcial tardio guarda su folio y su importe como evidencia`() = prueba {
        blumon.venta = { delay(20_000); http(200, PARCIAL) }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        esperarTodo()
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(777L, f.evidenciaTardiaOperacion)
        assertEquals(50L, f.parcialImporteCentavos)
        assertNull(f.parcialOperacion) // el de a tiempo queda vacío: éste llegó tarde
        assertNull(f.evidenciaTardiaAutorizacion)
    }

    /**
     * El reverso ya confirmó y el acuse del SERVIDOR se atora; la cajera se fue. Al volver la red, `recuperar()` entrega el
     * acuse `reversed`. Dejarlo afuera es el arreglo de r2; aquí se arma el mundo hasta ese momento.
     */
    private suspend fun Mundo.recuperarConUnIntentoVivo(): SolicitudDeVenta {
        blumon.venta = SILENCIO
        var entregas = 0
        servidor.acuse = { entregas++; if (entregas == 1) delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val s = solicitud()
        val cajera = prueba.async { cobrar(s) }
        prueba.advanceTimeBy(5_000)
        cajera.cancel() // nadie mira
        prueba.advanceTimeBy(15_000) // el reverso confirmó (T0 + 17 s) y la primera entrega sigue atorada
        assertEquals(EstadoDelReporte.PENDIENTE, fila(s).reporte)
        assertEquals(EstadoDelIntento.ENVIADO, fila(s).estado)
        motor.recuperar()
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
        return s
    }

    /** R1-7 (otra vez): lo que `recuperar()` resuelve de un intento VIVO entra por su `Registro`: quien mira lo ve en ese momento. */
    @Test fun `recuperar le dice al intento vivo no se cobro en ese momento`() = prueba {
        val s = recuperarConUnIntentoVivo()
        val ahoraMismo = prueba.currentTime
        assertEquals(NoSeCobro to ahoraMismo, cobrar(s).last()) // se adjunta al vivo, que ya terminó; no espera a los 30 s
    }

    /** Un solo embudo: el desenlace tardío de un intento vivo se publica UNA vez, y nada viejo sale después. */
    @Test fun `una resolucion tardia de un intento vivo se publica una sola vez`() = prueba {
        val s = recuperarConUnIntentoVivo()
        esperarTodo()
        assertEquals(listOf<EventoDeCobro>(NoSeCobro), tardiasDe(s))
    }

    // ───────────── Codex rama r2 · Familia E: el veto de llaves ─────────────

    /** R2-7: pasado `t0 + techo`, una vuelta que empezó antes todavía puede estar escribiendo: el veto cuenta una vuelta más. */
    @Test fun `un ENVIADO veta las llaves hasta su techo mas una vuelta del reverso`() = prueba {
        val vieja = solicitud()
        filaDirecta(vieja)
        val datos = DatosDelEnvio(null, null, Marca.AMEX, Medio.PLASTICO, "378282", "0005", "0484", "h", "l")
        assertTrue(libreta.marcarEnviado(vieja.id, ahora(), datos))
        salto += 45_000 + 1_000 // pasó su techo, no su techo + esperaReverso
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), terminal(solicitud()))
        assertEquals(0, blumon.inits)
        salto += 10_000
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        assertEquals(1, blumon.inits)
    }

    /** R2-7: la reserva de la sesión tampoco se suelta en `t0 + techo` si la última vuelta salió antes y sigue en vuelo. */
    @Test fun `la reserva de la sesion cuenta hasta el techo mas una vuelta del reverso`() = prueba {
        blumon.venta = SILENCIO
        // Vueltas a T0+16, +25, +34 (4 s cada una, 5 s de pausa) y la cuarta a T0+43, que se queda en vuelo hasta T0+53.
        blumon.reverso = { n -> if (n < 4) { delay(4_000); Respuesta.SinRespuesta("TIEMPO") } else { delay(600_000); http(200, REVERSADA) } }
        val s = solicitud()
        val desenlace = prueba.async { terminal(s) }
        prueba.advanceTimeBy(TOPE + 1_000)
        assertEquals(4, blumon.reversos.size)
        assertTrue(blumon.reversos.last().first < TOPE)
        assertEquals(1, motor.reservasDeSesion) // la cuarta vuelta sigue en vuelo: nadie renueva llaves
        assertEquals(PorConfirmar(s.importeCentavos), desenlace.await())
        esperarTodo()
        assertEquals(0, motor.reservasDeSesion)
    }

    // ───────────── Simplificación S1–S3 (v3.1): reglas N, C y M ─────────────

    /** Test 4 (veto ANTES de anotar) y test 7 (corte en plena admisión): dos `cobrar` leyeron «sin fila» y llega `cortar`. */
    @Test fun `un corte en plena admision gana y ninguno de los dos sale`() = prueba {
        val s = solicitud()
        var leidas = 0
        val ambasLeyeron = CompletableDeferred<Unit>()
        val sigan = CompletableDeferred<Unit>()
        motor.despuesDeBuscar = { if (++leidas == 2) ambasLeyeron.complete(Unit); sigan.await() }
        val a = prueba.async { cobrar(s).map { it.first } }
        val b = prueba.async { cobrar(s).map { it.first } }
        ambasLeyeron.await()
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(s)) // nadie en memoria: el veto entra sin fila
        sigan.complete(Unit)
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), a.await().last())
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), b.await().last())
        esperarTodo()
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(0, lector.lecturas)
        assertFalse("perdió el INSERT: contesta de la fila y no deja lápida", motor.intentos.containsKey(s.id))
        assertTrue("no es su fila: no publica nada", tardiasDe(s).isEmpty())
    }

    /** Test 4 (`marcarEnviado` antes del veto): el veto directo contesta `false` y la venta sigue. */
    @Test fun `el veto despues de marcarEnviado no toca la venta`() = prueba {
        val s = solicitud()
        var vetada: Boolean? = null
        blumon.venta = { vetada = libreta.vetarPorCorte(entidad(s)); delay(1_000); http(200, APROBADA) }
        assertEquals(APROBADO_VISA, terminal(s))
        assertEquals(false, vetada)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
        assertFalse(fila(s).cortarAntesDeSalir)
    }

    /** Test 5: `CORTADO` sobrevive a un reinicio: otra instancia del motor sobre la misma libreta no manda nada. */
    @Test fun `un folio cortado sigue cortado tras reiniciar`() = runTest {
        val antes = Mundo(this)
        val s = solicitud()
        assertEquals(ResultadoDelCorte.CORTADO, antes.motor.cortar(s))
        antes.db.close()
        val despues = Mundo(this, archivo = antes.archivo, proceso = UUID.randomUUID().toString())
        assertEquals(listOf(noSalio(MotivoNoSalio.INTERRUMPIDO)), despues.cobrar(s).map { it.first })
        assertTrue(despues.blumon.ventas.isEmpty())
        assertEquals(0, despues.lector.lecturas)
        antes.cerrar()
        despues.cerrar()
    }

    /** Test 6: `cortar` de un folio que nadie ha pedido deja el veto; un `cobrar` posterior no sale. */
    @Test fun `cortar un folio que nadie ha pedido lo veta para siempre`() = prueba {
        val s = solicitud()
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(s))
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertTrue(fila(s).cortarAntesDeSalir)
        assertEquals(listOf(noSalio(MotivoNoSalio.INTERRUMPIDO)), cobrar(s).map { it.first })
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(0, lector.lecturas)
    }

    /** Test 8: con la libreta detenida, `SIN_CONFIRMAR` a los `esperaLibreta`; el intento ya no envía y la fila queda NO_SALIO. */
    @Test fun `cortar con la libreta detenida dice SIN_CONFIRMAR y la venta no sale`() = prueba {
        lector.demora = 10_000
        val s = solicitud()
        val visto = prueba.async { terminal(s) }
        prueba.advanceTimeBy(500)
        motor.antesDeVetar = { delay(60_000) }
        val inicio = prueba.currentTime
        assertEquals(ResultadoDelCorte.SIN_CONFIRMAR, motor.cortar(s))
        assertEquals(inicio + Relojes().esperaLibreta, prueba.currentTime)
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), visto.await())
        esperarTodo()
        assertTrue(blumon.ventas.isEmpty())
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
    }

    /** Test 9: `cortar` sobre una lápida y sobre un `APROBADO` de otro proceso ⇒ `SIN_INTENTO_VIVO`, y nada cambia. */
    @Test fun `cortar algo que ya termino dice SIN_INTENTO_VIVO`() = prueba {
        val s = solicitud()
        assertEquals(APROBADO_VISA, terminal(s))
        esperarTodo()
        val antes = fila(s)
        assertEquals(ResultadoDelCorte.SIN_INTENTO_VIVO, motor.cortar(s))
        assertEquals(antes, fila(s))
        val otro = solicitud()
        filaDirecta(otro) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 55, procesoId = "otro-proceso") }
        val antesOtro = fila(otro)
        assertEquals(ResultadoDelCorte.SIN_INTENTO_VIVO, motor.cortar(otro))
        assertEquals(antesOtro, fila(otro))
        assertEquals(APROBADO_VISA, terminal(s))
    }

    /** Test 11 (regla N): `fondo` cancelado antes de anotar ⇒ `PorConfirmar` en el acto, nunca «no salió»; después, el veto. */
    @Test fun `fondo cancelado antes de anotar da por confirmar y nunca no salio`() = prueba {
        val s = solicitud()
        motor.despuesDeBuscar = { fondo.cancel() } // la lectura ya volvió «sin fila»: el intento arranca con `fondo` cancelado
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos) to 0L, eventos.last())
        assertTrue(eventos.none { it.first is NoSalioAlBanco })
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado) // `cerrarSinFinal` escribió el veto: el folio queda cerrado
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), motor.intentos[s.id]?.actual)
        assertTrue(blumon.ventas.isEmpty())
    }

    /** Test 11, con fila: `fondo` cancelado mientras lee la tarjeta ⇒ `NoSalioAlBanco(INTERRUMPIDO)` en el acto, sin Room. */
    @Test fun `fondo cancelado con la fila anotada da no salio en el acto`() = prueba {
        val s = solicitud()
        val visto = prueba.async { cobrar(s) }
        prueba.advanceTimeBy(500)
        fondo.cancel()
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO) to 500L, visto.await().last())
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertTrue(blumon.ventas.isEmpty())
    }

    /** Test 12 (R1-1): dos `cobrar` simultáneos del mismo folio nuevo ⇒ un solo intento, un POST y el mismo final. */
    @Test fun `dos cobrar simultaneos de un folio nuevo hacen un solo intento`() = prueba {
        val s = solicitud()
        val a = prueba.async { cobrar(s).map { it.first } }
        val b = prueba.async { cobrar(s).map { it.first } }
        assertEquals(APROBADO_VISA, a.await().last())
        assertEquals(a.await(), b.await())
        assertEquals(1, blumon.ventas.size)
        assertEquals(1, lector.lecturas)
    }

    /**
     * Test 13: lectura vieja. T mira (X no está), lee la fila de X —`ANOTADO` o «sin fila»— y se detiene; X envía, aprueba y se
     * retira; T entra al monitor ⇒ `Aprobado` desde la lápida. Un solo POST.
     */
    @Test fun `una lectura vieja nunca se usa si el folio corrio mientras tanto`() = prueba {
        for (conAnotado in listOf(true, false)) {
            val s = solicitud()
            var deT: Job? = null
            var foto: EstadoDelIntento? = null
            motor.antesDeBuscar = { id ->
                if (id == s.id && deT == null) { deT = currentCoroutineContext()[Job]; if (conAnotado) delay(500) }
            }
            motor.despuesDeBuscar = { if (currentCoroutineContext()[Job] === deT) { foto = libreta.buscar(s.id)?.estado; delay(3_000) } }
            val t = prueba.async { cobrar(s).map { it.first } }
            prueba.advanceTimeBy(100)
            assertEquals(APROBADO_VISA, terminal(s)) // X entra después de la primera mirada de T
            assertEquals("$conAnotado", APROBADO_VISA, t.await().last())
            assertEquals("$conAnotado", if (conAnotado) EstadoDelIntento.ANOTADO else null, foto)
        }
        assertEquals(2, blumon.ventas.size)
    }

    /** Test 14 (regla M): la venta terminó y se retiró; con la libreta detenida, reabrir el folio contesta al instante. */
    @Test fun `reabrir una venta terminada con Room detenido contesta al instante`() = prueba {
        val s = solicitud()
        assertEquals(APROBADO_VISA, terminal(s))
        esperarTodo()
        motor.antesDeBuscar = { delay(60_000) }
        val antes = prueba.currentTime
        assertEquals(APROBADO_VISA to antes, cobrar(s).last())
    }

    /** Test 15: B mira y A no está; A se da de alta y sale; la lectura de B se detiene ⇒ B se adjunta a A a los `esperaLibreta`. */
    @Test fun `un reenganche que pierde la primera mirada se adjunta a mas tardar al tope`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        var deB: Job? = null
        motor.antesDeBuscar = { id -> if (id == s.id && deB == null) { deB = currentCoroutineContext()[Job]; delay(600_000) } }
        val b = prueba.async { cobrar(s) }
        prueba.advanceTimeBy(100)
        val a = prueba.async { cobrar(s) }
        val (eventosA, eventosB) = a.await() to b.await()
        assertEquals(Relojes().esperaLibreta, eventosB.first().second)
        assertEquals(eventosA.map { it.first }, eventosB.map { it.first })
        assertEquals(1, blumon.ventas.size)
    }

    /** Test 16: folio de otro proceso con la libreta detenida ⇒ `PorConfirmar` a los `esperaLibreta`; nada se registra. */
    @Test fun `folio de otro proceso con Room detenido da por confirmar al tope`() = prueba {
        val s = solicitud()
        filaDirecta(s) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 55, procesoId = "otro-proceso") }
        motor.antesDeBuscar = { delay(60_000) }
        assertEquals(PorConfirmar(s.importeCentavos) to Relojes().esperaLibreta, cobrar(s).last())
        esperarTodo() // la lectura por fin termina y no admite nada
        assertTrue(motor.intentos.isEmpty())
        assertTrue(blumon.ventas.isEmpty())
    }

    /** Test 17 (v2-3): una lectura que truena no cancela `fondo` (un `Job` normal): otra venta viva sigue y termina bien. */
    @Test fun `una lectura que truena no tumba a la otra venta`() = prueba {
        val viva = solicitud()
        val consulta = solicitud()
        motor.antesDeBuscar = { id -> if (id == consulta.id) throw IllegalStateException("Room tronó") }
        val v = prueba.async { terminal(viva) }
        prueba.advanceTimeBy(500)
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(consulta.importeCentavos)), cobrar(consulta).map { it.first })
        assertEquals(APROBADO_VISA, v.await())
        assertTrue(fondo.isActive)
        assertFalse(motor.intentos.containsKey(consulta.id))
    }

    /** Test 18: con el tope en 2, la tercera venta borra la lápida más vieja, y ese folio se contesta desde su fila. */
    @Test fun `el tope de lapidas borra la mas vieja y su folio se contesta de la fila`() = prueba {
        motor.topeDeLapidas = 2
        val ventas = List(3) { solicitud() }
        for (s in ventas) assertEquals(APROBADO_VISA, terminal(s))
        esperarTodo()
        assertEquals(ventas.drop(1).map { it.id }.toSet(), motor.intentos.keys.toSet())
        motor.antesDeBuscar = { delay(1_000) } // así se nota quién va a Room
        val inicio = prueba.currentTime
        assertEquals(APROBADO_VISA to inicio + 1_000, cobrar(ventas[0]).last()) // de la fila
        assertEquals(APROBADO_VISA to inicio + 1_000, cobrar(ventas[1]).last()) // de la lápida, sin esperar
    }

    /** Test 19: un acuse `reversed` que `recuperar()` entrega tarde actualiza el final de la LÁPIDA; reabrir contesta de memoria. */
    @Test fun `recuperar actualiza el final de una lapida`() = prueba {
        blumon.venta = SILENCIO
        var entregas = 0
        servidor.acuse = { entregas++; if (entregas == 1) delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        prueba.advanceTimeBy(1_000)
        assertEquals(true, motor.intentos[s.id]?.retirado)
        motor.recuperar()
        assertEquals(NoSeCobro, motor.intentos[s.id]?.actual)
        motor.antesDeBuscar = { delay(60_000) } // si fuera a Room se notaría
        val ahoraMismo = prueba.currentTime
        assertEquals(NoSeCobro to ahoraMismo, cobrar(s).last())
    }

    /**
     * Test 22 (v3-1): con el tope en 1, T queda detenido con su foto `ANOTADO` de X; X aprueba y se retira, y otra venta se
     * retira después ⇒ la lápida de X no se borra mientras T está en vuelo, y T contesta `Aprobado`. Después el tope alcanza.
     */
    @Test fun `ninguna lapida se borra con una admision en vuelo`() = prueba {
        motor.topeDeLapidas = 1
        val x = solicitud()
        var deT: Job? = null
        var foto: EstadoDelIntento? = null
        motor.antesDeBuscar = { id -> if (id == x.id && deT == null) { deT = currentCoroutineContext()[Job]; delay(500) } }
        motor.despuesDeBuscar = { if (currentCoroutineContext()[Job] === deT) { foto = libreta.buscar(x.id)?.estado; delay(4_000) } }
        val t = prueba.async { cobrar(x).map { it.first } }
        prueba.advanceTimeBy(100)
        assertEquals(APROBADO_VISA, terminal(x)) // X: alta, envía, aprueba y se retira
        assertEquals(APROBADO_VISA, terminal(solicitud())) // otra venta se retira con T todavía en vuelo
        assertEquals(EstadoDelIntento.ANOTADO, foto)
        assertEquals(APROBADO_VISA, t.await().last())
        assertEquals(2, blumon.ventas.size)
        esperarTodo()
        assertEquals(APROBADO_VISA, terminal(solicitud())) // sin admisiones en vuelo, el tope blando se pone al día
        esperarTodo()
        assertEquals(1, motor.intentos.size)
    }

    /**
     * Test 23 (v3-2): un `Registro` pierde el `INSERT` contra una fila `APROBADO` (aquí la escribió otro proceso mientras su
     * lectura volvía «sin fila») y `fondo` se cancela justo después ⇒ nunca `NoSalioAlBanco`; no deja lápida; un solo POST.
     */
    @Test fun `un registro que pierde el INSERT nunca dice no salio y no deja lapida`() = runTest {
        val primero = Mundo(this)
        val segundo = Mundo(this, archivo = primero.archivo, proceso = UUID.randomUUID().toString())
        val s = solicitud()
        var lecturas = 0
        // r10: el escucha del mundo vive en `fondo`, que esta prueba cancela; éste vive fuera y sí oye.
        val avisos = mutableListOf<EventoDeCobro>()
        val oyente = launch(start = CoroutineStart.UNDISPATCHED) {
            segundo.motor.resolucionesTardias.collect { if (it.first == s.id) avisos += it.second }
        }
        segundo.motor.antesDeBuscar = { if (++lecturas == 2) segundo.fondo.cancel() } // ya perdió el INSERT
        segundo.motor.despuesDeBuscar = { if (lecturas == 1) assertEquals(APROBADO_VISA, primero.terminal(s)) }
        val eventos = segundo.cobrar(s).map { it.first }
        assertEquals(2, lecturas)
        assertEquals(PorConfirmar(s.importeCentavos), eventos.last())
        assertTrue(eventos.none { it is NoSalioAlBanco })
        advanceUntilIdle()
        assertFalse(segundo.motor.intentos.containsKey(s.id))
        oyente.cancel()
        assertTrue("$avisos", avisos.none { it is NoSalioAlBanco })
        assertEquals(EstadoDelIntento.APROBADO, segundo.fila(s).estado)
        assertEquals(APROBADO_VISA, primero.terminal(s))
        assertEquals(1, primero.blumon.ventas.size)
        assertTrue(segundo.blumon.ventas.isEmpty())
        primero.cerrar()
        segundo.cerrar()
    }

    /** Test 24: `cortar` ve el mapa vacío; un alta del mismo folio gana `marcarEnviado`; el veto regresa `false` ⇒ `YA_SALIO`. */
    @Test fun `cortar contra un alta que ya salio dice YA_SALIO`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        motor.antesDeVetar = { delay(T0 + 500) } // mientras, el alta anota y sale (t0 = 1 s después)
        val corte = prueba.async { motor.cortar(s) }
        prueba.advanceTimeBy(100)
        val venta = prueba.async { terminal(s) }
        assertEquals(ResultadoDelCorte.YA_SALIO, corte.await())
        assertEquals(NoSeCobro, venta.await())
        assertEquals(1, blumon.ventas.size)
        // Una fila ENVIADO de otro proceso: aquí no hay intento ⇒ SIN_INTENTO_VIVO, y la app sigue consultando el folio.
        motor.antesDeVetar = {}
        val ajena = solicitud()
        filaDirecta(ajena) { it.copy(estado = EstadoDelIntento.ENVIADO, enviadoEnMs = 0, procesoId = "otro-proceso") }
        assertEquals(ResultadoDelCorte.SIN_INTENTO_VIVO, motor.cortar(ajena))
        assertEquals(EstadoDelIntento.ENVIADO, fila(ajena).estado)
    }

    /**
     * Test 25 (cambiado en r10, R10-1): `recuperar()` aplica el `reversed` de A y se cancela mientras entrega B; A quedó como
     * lápida en duda. La PRIMERA reapertura da `PorConfirmar` al instante y dispara UN refresco en `fondo` (aunque se reabra otra
     * vez mientras corre); al terminar, `NoSeCobro` sale por `resolucionesTardias` y la SIGUIENTE reapertura da `NoSeCobro`.
     */
    @Test fun `una lapida en duda contesta ya y se refresca de la fila en fondo`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) } // el primer acuse de cada una se atora
        val a = solicitud()
        val b = solicitud()
        assertEquals(PorConfirmar(a.importeCentavos), terminal(a))
        assertEquals(PorConfirmar(b.importeCentavos), terminal(b))
        prueba.advanceTimeBy(1_000)
        assertEquals(true, motor.intentos[a.id]?.retirado)
        servidor.acuse = {
            if (servidor.reportados.last().first == a.id) guardado(EstadoDelReversoEnServidor.REVERSED)
            else { delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        }
        val barrido = prueba.async { motor.recuperar() }
        prueba.advanceTimeBy(5_000)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(a).estado)
        barrido.cancel() // a media tanda: el aviso de A nunca sale
        assertEquals(PorConfirmar(a.importeCentavos), motor.intentos[a.id]?.actual)

        var lecturas = 0
        motor.antesDeBuscar = { id -> if (id == a.id) { lecturas++; delay(10_000) } } // Room lento
        val inicio = prueba.currentTime
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(a.importeCentavos)), cobrar(a).map { it.first })
        assertEquals(inicio, prueba.currentTime) // nadie esperó a Room
        prueba.advanceTimeBy(1_000)
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(a.importeCentavos)), cobrar(a).map { it.first }) // otra reapertura
        assertFalse(NoSeCobro in tardiasDe(a))
        prueba.advanceTimeBy(10_000) // el refresco termina
        assertEquals(1, lecturas) // a lo más un refresco en vuelo por lápida
        assertTrue(NoSeCobro in tardiasDe(a))
        assertEquals(NoSeCobro, motor.intentos[a.id]?.actual)
        assertEquals(listOf<EventoDeCobro>(NoSeCobro), cobrar(a).map { it.first })
    }

    /** R10-1: volver a escuchar un intento que se retiró en `PorConfirmar`, con la libreta detenida ⇒ `PorConfirmar` sin esperar. */
    @Test fun `reabrir una lapida en duda con Room detenido contesta al instante`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, cobrar(s).last())
        prueba.testScheduler.runCurrent()
        assertEquals(true, motor.intentos[s.id]?.retirado)
        motor.antesDeBuscar = { delay(60_000) }
        assertEquals(PorConfirmar(s.importeCentavos) to CAJERA, cobrar(s).last()) // 0 ms después del retiro
    }

    /**
     * R10-2: `cortar(s′)` con el mapa vacío escribe el veto mientras `cobrar(s)`, con otro importe, se admite; `fondo` se cancela
     * antes del `INSERT` de `s`. El cierre de emergencia contesta `CONTRATO_DISTINTO` desde la fila, pero NUNCA lo publica.
     */
    @Test fun `CONTRATO_DISTINTO nunca sale por resoluciones tardias`() = prueba {
        val s = solicitud()
        val otra = s.copy(importeCentavos = s.importeCentavos + 1)
        val vetado = CompletableDeferred<Unit>()
        // El escucha del mundo vive en `fondo`, que esta prueba cancela: éste vive fuera (y no en `backgroundScope`, que
        // `advanceUntilIdle` no corre).
        val avisos = mutableListOf<EventoDeCobro>()
        val oyente = prueba.launch(start = CoroutineStart.UNDISPATCHED) {
            motor.resolucionesTardias.collect { if (it.first == s.id) avisos += it.second }
        }
        motor.despuesDeBuscar = { id -> if (id == s.id) { vetado.await(); fondo.cancel() } }
        val visto = prueba.async { cobrar(s).map { it.first } }
        prueba.testScheduler.runCurrent()
        assertEquals(ResultadoDelCorte.CORTADO, motor.cortar(otra)) // el mapa todavía está vacío
        vetado.complete(Unit)
        assertEquals(PorConfirmar(s.importeCentavos), visto.await().last())
        esperarTodo()
        assertEquals(EventoDeCobro.ContratoDistinto, motor.intentos[s.id]?.actual) // a quien observa, sí
        oyente.cancel()
        assertTrue("$avisos", PorConfirmar(s.importeCentavos) in avisos) // el escucha sí oye
        assertTrue("$avisos", avisos.none { it == EventoDeCobro.ContratoDistinto })
        assertTrue(blumon.ventas.isEmpty())
    }

    /** Test 27: `fondo` se cancela entre el commit de `anotar` y su regreso ⇒ `PorConfirmar` en el acto; después, la fila manda. */
    @Test fun `cancelar fondo entre el commit de anotar y su regreso da por confirmar`() = prueba {
        val s = solicitud()
        ganchos["anotar"] = { fondo.cancel() }
        assertEquals(PorConfirmar(s.importeCentavos) to 0L, cobrar(s).last())
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), motor.intentos[s.id]?.actual)
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        assertTrue(blumon.ventas.isEmpty())
    }

    // ---- I3: los caminos que leen una fila para publicar (fase 3, bloque 1; r1 #5, r2 #5, r3 #3) ----

    /** 1 · La consulta directa de un folio. */
    @Test fun `la consulta de una fila que no pasa la validacion da por confirmar`() = prueba {
        val s = solicitud()
        filaDirecta(s) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 1_234_567) }
        danar(s, "APROBADO")
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(s.importeCentavos)), cobrar(s).map { it.first })
        assertEquals(0, lector.lecturas)
    }

    /** 2 · La reconsulta de un intento que sigue en memoria (una lápida en duda): el refresco desde la fila no publica nada. */
    @Test fun `la reconsulta en memoria de una fila que no pasa la validacion no publica un desenlace`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(600_000); guardado(EstadoDelReversoEnServidor.REVERSED) } // el acuse se atora: queda en duda
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        prueba.advanceTimeBy(1_000)
        assertEquals(true, motor.intentos[s.id]?.retirado)
        danar(s, "APROBADO")
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(s.importeCentavos)), cobrar(s).map { it.first }) // dispara el refresco
        prueba.advanceTimeBy(1_000)
        assertTrue(tardiasDe(s).none { it is Aprobado })
        assertEquals(PorConfirmar(s.importeCentavos), motor.intentos[s.id]?.actual)
    }

    /** 3 · El acuse tardío (después de soltar a la cajera): la fila se daña mientras llega; nunca publica «No se cobró». */
    @Test fun `el acuse tardio no publica no se cobro desde una fila que no pasa la validacion`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        servidor.acuse = { delay(31_000); danar(s, "NO_SE_COBRO"); AcuseDelResultado.Reintentar } // llega después de la cajera
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        prueba.advanceTimeBy(60_000)
        assertFalse(NoSeCobro in tardiasDe(s))
        assertEquals(PorConfirmar(s.importeCentavos), motor.intentos[s.id]?.actual)
    }

    /** 4 · Codex r4 #2, salida «evento»: una fila APROBADO sin folio bancario ni id de pago nunca da «Aprobado». */
    @Test fun `un aprobado sin folio ni pago da por confirmar`() = prueba {
        val s = solicitud()
        filaDirecta(s) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 1_234_567) }
        db.openHelper.writableDatabase.execSQL("UPDATE intentos SET operationNumber = NULL WHERE attemptId = ?", arrayOf(s.id.valor))
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(s.importeCentavos)), cobrar(s).map { it.first })
    }
}
