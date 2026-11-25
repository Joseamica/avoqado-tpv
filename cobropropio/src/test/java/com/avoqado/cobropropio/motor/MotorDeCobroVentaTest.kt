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
import kotlinx.coroutines.yield
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
        val reportados = mutableListOf<Pair<AttemptId, ResultadoDelReverso>>()
        var acuse: suspend () -> AcuseDelResultado = { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSED) }

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
            identidad, lector, red, blumon, libreta, ColaDeResultados(libreta, servidor, io), huella,
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
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A Rechazada`() = prueba {
        blumon.venta = { delay(1_000); http(200, error("51")) }
        val s = solicitud()
        assertEquals(Rechazado("51", "FONDOS INSUFICIENTES"), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.RECHAZADO, fila(s).estado)
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A NoEnviada`() = prueba {
        blumon.venta = { Respuesta.NoEnviada }
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, fila(s).motivoNoSalio)
        assertTrue(blumon.reversos.isEmpty())
    }

    @Test fun `tabla A NoSe o DebeReversar`() = prueba {
        val respuestas = listOf(http(429, APROBADA), http(401, APROBADA), http(403, APROBADA), http(200, error("68")), http(200, PARCIAL))
        for ((i, respuesta) in respuestas.withIndex()) {
            blumon.venta = { delay(1_000); respuesta }
            val s = solicitud()
            // Lo ya publicado del intento cuando sale el primer POST del reverso.
            var alReversar: List<EventoDeCobro>? = null
            blumon.alReversar = { if (alReversar == null) alReversar = motor.intentos.getValue(s.id).historia.value }
            val vistos = cobrar(s)
            val eventos = vistos.map { it.first }
            assertFalse(eventos.any { it is Aprobado })
            assertEquals(NoSeCobro, eventos.last())
            assertEquals(i + 1, blumon.reversos.size)
            // R: el motor avisa UNA vez, a la hora de la respuesta (t0 + 1 s) y antes del primer POST del reverso.
            val t0 = requireNotNull(fila(s).enviadoEnMs)
            assertEquals("respuesta $i", listOf(t0 + 1_000), vistos.filter { it.first == RevisandoConElBanco }.map { it.second })
            assertEquals("respuesta $i", RevisandoConElBanco, alReversar?.last())
        }
        assertEquals(1, blumon.inits) // el 401 y el 403 sólo renuevan el token
    }

    @Test fun `tabla A sin respuesta a los 15 s`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(T0 + 15_000, eventos.single { it.first == RevisandoConElBanco }.second)
        assertEquals(NoSeCobro, eventos.last().first)
        assertEquals(1, blumon.reversos.size)
    }

    // ───────────── Bloque R: el aviso de que el motor no tiene desenlace ─────────────

    /** Una respuesta inútil (5xx) y el corte del transporte antes del primer byte (`SinRespuesta`) avisan en el instante. */
    @Test fun `la respuesta inutil o el corte avisan en el instante`() = prueba {
        val respuestas = listOf(http(503, APROBADA), Respuesta.SinRespuesta("RED"))
        for ((i, respuesta) in respuestas.withIndex()) {
            blumon.venta = { delay(1_000); respuesta }
            val s = solicitud()
            val vistos = cobrar(s)
            val t0 = requireNotNull(fila(s).enviadoEnMs)
            // El reverso: una vuelta REVERSADA a 1 s (bloque S: sin esperar el acuse).
            assertEquals("respuesta $i", NoSeCobro to t0 + 2_000, vistos.last())
            assertEquals("respuesta $i", i + 1, blumon.reversos.size)
            assertEquals("respuesta $i", EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
            assertEquals(
                "respuesta $i",
                listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando, RevisandoConElBanco, NoSeCobro),
                vistos.map { it.first },
            )
            assertEquals("respuesta $i", t0 + 1_000, vistos.single { it.first == RevisandoConElBanco }.second)
        }
    }

    /**
     * El parcial avisa ANTES de escribir su evidencia: el gancho corre dentro de la transacción que la guarda, antes de su
     * commit (la escritura detenida a la mitad), y para entonces el aviso ya está publicado.
     */
    @Test fun `el parcial avisa antes de escribir su evidencia`() = prueba {
        blumon.venta = { delay(1_000); http(200, PARCIAL) }
        val s = solicitud()
        var enLaTransaccion: Pair<List<EventoDeCobro>, Long>? = null
        // La primera `transicionar` del intento es la del parcial: antes de salir, nada de este camino la usa.
        ganchos["transicionar"] = {
            if (enLaTransaccion == null) enLaTransaccion = motor.intentos.getValue(s.id).historia.value to ahora()
        }
        // El final y su hora: la venta y una vuelta REVERSADA (1 s cada una; bloque S: sin esperar el acuse).
        assertEquals(NoSeCobro to T0 + 2_000, cobrar(s).last())
        assertEquals(777L, fila(s).parcialOperacion)
        assertEquals(50L, fila(s).parcialImporteCentavos)
        val (publicado, cuando) = requireNotNull(enLaTransaccion)
        assertEquals("la escritura del parcial es la de la respuesta", T0 + 1_000, cuando)
        assertEquals(
            listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando, RevisandoConElBanco),
            publicado,
        )
    }

    /**
     * Una sola vez en cada camino al reverso (la espera vencida, la respuesta inútil y el parcial), después de `Autorizando` y
     * antes del único final; ninguna si la venta no salió o si no hubo intento.
     */
    @Test fun `el aviso sale una vez y nunca sin envio`() = prueba {
        for ((camino, respuesta) in listOf("espera vencida" to null, "respuesta inutil" to http(503, APROBADA), "parcial" to http(200, PARCIAL))) {
            blumon.venta = SILENCIO
            if (respuesta != null) blumon.venta = { delay(1_000); respuesta }
            val eventos = cobrar(solicitud()).map { it.first }
            assertEquals(camino, NoSeCobro, eventos.last())
            assertEquals(camino, 1, eventos.count { it.esTerminal })
            assertEquals(camino, 1, eventos.count { it == RevisandoConElBanco })
            assertTrue(camino, eventos.indexOf(Autorizando) < eventos.indexOf(RevisandoConElBanco))
        }

        fun sinAviso(caso: String, esperado: EventoDeCobro, eventos: List<EventoDeCobro>) {
            assertEquals(caso, esperado, eventos.last())
            assertFalse(caso, RevisandoConElBanco in eventos)
        }
        blumon.venta = { Respuesta.NoEnviada }
        sinAviso("cero bytes", noSalio(MotivoNoSalio.INTERRUMPIDO), cobrar(solicitud()).map { it.first })
        blumon.venta = { delay(1_000); http(200, APROBADA) }

        red.hay = false
        sinAviso("sin red", noSalio(MotivoNoSalio.SIN_RED), cobrar(solicitud()).map { it.first })
        red.hay = true

        val cortada = solicitud()
        motor.antesDelPuntoSinRetorno = { motor.cortar(cortada) }
        sinAviso("corte antes de salir", noSalio(MotivoNoSalio.INTERRUMPIDO), cobrar(cortada).map { it.first })
        motor.antesDelPuntoSinRetorno = {}

        val leer = lector.resultado
        lector.resultado = { ResultadoDeLectura.Vencida }
        sinAviso("lector vencido", noSalio(MotivoNoSalio.LECTURA_VENCIDA), cobrar(solicitud()).map { it.first })
        lector.resultado = { i -> ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(i + 1)) }
        sinAviso(
            "tarjeta no admitida", noSalio(MotivoNoSalio.NO_ADMITIDA, MotivoNoAdmitida.IMPORTE_NO_COINCIDE),
            cobrar(solicitud()).map { it.first },
        )
        lector.resultado = leer

        val ilegible = solicitud()
        motor.antesDeBuscar = { id -> if (id == ilegible.id) throw IllegalStateException("Room tronó") }
        sinAviso("admision sin fila legible", PorConfirmar(ilegible.importeCentavos), cobrar(ilegible).map { it.first })
        assertEquals(3, blumon.reversos.size) // sólo los tres caminos al reverso
    }

    /** Quien se engancha al intento VIVO después del aviso lo recibe una vez, en su historia; el final, una vez a cada uno. */
    @Test fun `al reengancharse la historia trae el aviso una sola vez`() = prueba {
        blumon.venta = { delay(1_000); http(503, APROBADA) }
        val s = solicitud()
        val primero = prueba.async { cobrar(s) }
        delay(T0 + 1_500) // el aviso salió a T0 + 1 s; el reverso contesta a T0 + 2 s
        val r = motor.intentos.getValue(s.id)
        assertFalse("el intento sigue vivo", r.terminado)
        val avisoYaSalio = RevisandoConElBanco in r.historia.value
        assertEquals("el segundo se engancha a T0 + 1.5 s", T0 + 1_500, ahora())
        val segundo = cobrar(s)
        val uno = primero.await()
        // El final (el reverso, a 1 s; bloque S: sin esperar el acuse), una sola vez a cada colector.
        for (vistos in listOf(uno, segundo)) {
            assertEquals(NoSeCobro to T0 + 2_000, vistos.last())
            assertEquals(1, vistos.count { it.first.esTerminal })
        }
        assertEquals(1, blumon.ventas.size)
        assertEquals(1, blumon.reversos.size)
        assertTrue("el segundo se enganchó después del aviso", avisoYaSalio)
        val historia = listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando, RevisandoConElBanco, NoSeCobro)
        assertEquals(historia, uno.map { it.first })
        assertEquals(historia, segundo.map { it.first })
        assertEquals("llega con su historia, al engancharse", T0 + 1_500, segundo.single { it.first == RevisandoConElBanco }.second)
    }

    // ───────────── Bloque S: el reverso sale sin pedirle permiso al SERVIDOR ─────────────

    /** D-2: tras el arbitraje durable, la primera vuelta sale en cuanto vence la espera de la venta; nadie espera al SERVIDOR. */
    @Test fun `sin respuesta el primer reverso sale al vencer la espera sin esperar al SERVIDOR`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(60_000); guardado(EstadoDelReversoEnServidor.REVERSED) } // un SERVIDOR que tarda 60 s
        val s = solicitud()
        terminal(s)
        val t0 = requireNotNull(fila(s).enviadoEnMs)
        assertEquals(t0 + motor.relojes.esperaRespuesta, blumon.reversos.first().first)
    }

    // Un control: «nada antes del reverso» lo cuidan `sin respuesta el primer reverso sale al vencer la espera…` y la guarda de `GuardasTest`.
    @Test fun `con el reporte fallando el motor reversa y confirma igual`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar } // falla en lo único que le queda: un 500 de /resultado
        val s = solicitud()
        terminal(s)
        esperarTodo()
        assertEquals(1, blumon.reversos.size)
        assertEquals(EstadoDelReverso.CONFIRMADO, fila(s).reverso)
    }

    /** Revisión final: una entrega que lanza se queda en su `intentar`; si no, tumbaría `fondo` (y con él la prueba). */
    @Test fun `una entrega que lanza no tumba al motor`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { throw IllegalStateException() }
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        esperarTodo()
        assertEquals(EstadoDelReporte.PENDIENTE, fila(s).reporte)
    }

    /**
     * Codex #1: el reporte del reverso se programa ANTES de publicar el final. La fila no se puede leer justo después del
     * `REVERSADA` (una falla pasajera de Room, como en el test 25), así que la lectura con que el motor publica el final truena;
     * el envío ya estaba en `fondo`. Los dos `yield` reparan la fila después de esa lectura y antes de la del envío, que se
     * programó primero (si cambia el orden de los despachos, la prueba se pone roja, nunca verde de más).
     */
    @Test fun `si la publicacion del final falla el reporte ya se habia programado`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        ganchos["cerrarVuelta"] = {
            danar(s, "NO_SE_COBRO") // dentro de la transacción del REVERSADA
            prueba.launch {
                yield()
                yield()
                db.openHelper.writableDatabase.execSQL("UPDATE intentos SET operationNumber = NULL WHERE attemptId = ?", arrayOf(s.id.valor))
            }
        }
        assertEquals("la publicación del final tronó", PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        assertEquals(listOf(s.id), servidor.reportados.map { it.first })
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
    }

    /**
     * D-3: Blumon confirma el reverso de una venta de la tablet y el SERVIDOR nunca contesta el reporte. En ese instante es
     * `NoSeCobro` y la fila ya lo dice; el reporte se queda pendiente en la libreta.
     */
    @Test fun `un reverso confirmado es NoSeCobro aunque el SERVIDOR no conteste`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(600_000); AcuseDelResultado.Reintentar } // en lo que dura la prueba, nunca regresa
        val s = solicitud(origen = Origen.VINCULADO_AL_POS)
        val ultimo = cobrar(s).last()
        assertEquals("a la hora del REVERSADA, no a t0 + peorCasoCajera", NoSeCobro to blumon.reversos.single().first + 1_000, ultimo)
        prueba.advanceTimeBy(60_000) // más allá de t0 + peorCasoCajera
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(listOf(s.id), servidor.reportados.map { it.first }) // un intento de entrega, en segundo plano
        assertEquals(listOf<EventoDeCobro>(NoSeCobro), tardiasDe(s))
    }

    /**
     * D-4: con el reporte del primero sin entregar, una venta nueva de otro folio se admite y termina sin esperarlo;
     * `recuperar()` lo vuelve a mandar y su acuse sólo cierra el reporte.
     */
    @Test fun `con el reporte sin entregar recuperar lo reintenta y una venta nueva no espera`() = prueba {
        blumon.venta = { n -> if (n == 1) SILENCIO(n) else { delay(1_000); http(200, APROBADA) } }
        servidor.acuse = { delay(600_000); AcuseDelResultado.Reintentar } // el primer reporte no regresa
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))

        val otra = solicitud()
        val inicio = prueba.currentTime
        assertEquals("la lectura y la venta, 1 s cada una", APROBADO_VISA to inicio + 2_000, cobrar(otra).last())
        assertEquals(EstadoDelReporte.PENDIENTE, fila(s).reporte)

        servidor.acuse = { guardado(EstadoDelReversoEnServidor.REVERSED) }
        assertTrue(s.id in motor.recuperar())
        assertEquals(2, servidor.reportados.count { it.first == s.id })
        val f = fila(s)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    /** D-4: el acuse que `recuperar()` entrega no publica nada: ni por el `Registro` ni por `resolucionesTardias`. */
    @Test fun `recuperar no publica finales por un acuse`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { delay(1_000); Respuesta.SinRespuesta("TIEMPO") } // tabla F: queda PUDO_ENTRAR por reportar
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val r = motor.intentos.getValue(s.id)
        assertTrue(r.retirado)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, fila(s).resultadoParaReportar)
        assertEquals(EstadoDelReporte.PENDIENTE, fila(s).reporte)
        val historia = r.historia.value
        val publicadas = tardiasDe(s)

        servidor.acuse = { guardado(EstadoDelReversoEnServidor.REVERSED) }
        assertTrue(s.id in motor.recuperar())
        esperarTodo()
        assertEquals(historia, r.historia.value)
        assertEquals(publicadas, tardiasDe(s))
        assertEquals(PorConfirmar(s.importeCentavos), r.actual)
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, f.estadoEnServidor)
    }

    /**
     * Si al llegar al camino del reverso el techo ya venció, ni se compromete ni sale un reverso: lo cierra `alPlazo`. La
     * aprobación que llega después (a los 18 s) sólo queda como evidencia: nunca se vuelve `Aprobado` ni se pierde.
     */
    @Test fun `con el techo ya vencido no se compromete ni sale un reverso`() = prueba(Relojes(techoDelReverso = 15_000)) {
        blumon.venta = { delay(18_000); http(200, APROBADA) } // el camino del reverso empieza a los t0 + 15 s: justo en el techo
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(
            listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando, RevisandoConElBanco, PorConfirmar(s.importeCentavos)),
            eventos.map { it.first },
        )
        assertEquals(T0 + 15_000, eventos.last().second)
        esperarTodo()
        val f = fila(s)
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(12345L, f.evidenciaTardiaOperacion)
        assertFalse(tardiasDe(s).any { it is Aprobado })
        assertFalse(NoSeCobro in tardiasDe(s))
    }

    /** La gemela: un milisegundo antes del techo sí se compromete y reclama una vuelta. */
    @Test fun `un milisegundo antes del techo se compromete y reclama una vuelta`() = prueba(Relojes(techoDelReverso = 15_001)) {
        blumon.venta = SILENCIO
        val s = solicitud()
        terminal(s)
        esperarTodo()
        assertEquals(listOf(T0 + 15_000), blumon.reversos.map { it.first })
        assertEquals(EstadoDelReverso.CONFIRMADO, fila(s).reverso)
    }

    /** Codex r1, techo: el reloj pasa del techo mientras corre el primer compromiso; no sale ningún byte y la fila se cierra. */
    @Test fun `si el reloj salta el techo durante el primer compromiso no sale ningun byte`() = prueba {
        blumon.venta = SILENCIO
        ganchos["comprometerAReversar"] = { salto += 60_000 }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        val f = fila(s)
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    // ───────────── D. Cada vuelta del reverso ─────────────

    @Test fun `tabla D Confirmado`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud()
        assertEquals(NoSeCobro, terminal(s))
        esperarTodo() // el reporte se adelanta en `fondo`
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
        for (codigo in listOf("TX_014", "TX_013")) {
            blumon.reverso = { delay(1_000); http(200, error(codigo)) }
            for (origen in listOf(Origen.VINCULADO_AL_POS, Origen.LOCAL_SIN_SOLICITUD)) {
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
            esperarTodo() // el reporte se adelanta en `fondo`
            assertEquals(codigo, "REVERSADA", servidor.reportados.last().second.descripcion)
        }
    }

    @Test fun `tabla D NoEnviado`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { Respuesta.NoEnviada }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(PorConfirmar(s.importeCentavos), eventos.last().first)
        assertEquals(T0 + 15_000, eventos.last().second) // F enseguida, sin otra vuelta
        val f = fila(s)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertFalse(f.pudoEntrar)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(1, blumon.reversos.size)
    }

    // ───────────── E. Después de un Confirmado ─────────────

    /** Bloque S (D-3): sin vínculo, igual que con él: `NoSeCobro` en el acto y el reporte se adelanta en `fondo`. */
    @Test fun `tabla E V no`() = prueba {
        blumon.venta = SILENCIO
        val s = solicitud(origen = Origen.LOCAL_SIN_SOLICITUD)
        assertEquals(NoSeCobro, terminal(s))
        val f = fila(s)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar)
        esperarTodo()
        assertEquals(listOf(s.id), servidor.reportados.map { it.first })
        assertEquals(EstadoDelReporte.ENTREGADO, fila(s).reporte)
    }

    /**
     * Bloque S (D-3, D-4): con vínculo, el final es `NoSeCobro` a la hora del `REVERSADA`, llegue el acuse cuando llegue (también
     * después de `t0 + peorCasoCajera`) y diga lo que diga: el estado no cambia, sólo se guarda lo que dijo el SERVIDOR.
     */
    @Test fun `tabla E V si`() = prueba {
        blumon.venta = SILENCIO
        val casos = listOf<Pair<suspend () -> AcuseDelResultado, EstadoDelReversoEnServidor?>>(
            suspend { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSED) } to EstadoDelReversoEnServidor.REVERSED,
            suspend { delay(1_000); guardado(EstadoDelReversoEnServidor.REVERSE_FAILED) } to EstadoDelReversoEnServidor.REVERSE_FAILED,
            suspend { delay(1_000); guardado(EstadoDelReversoEnServidor.CONTRADICTION) } to EstadoDelReversoEnServidor.CONTRADICTION,
            suspend { delay(1_000); AcuseDelResultado.Reintentar } to null,
            suspend { delay(1_000); AcuseDelResultado.Conflicto(EstadoDelReversoEnServidor.REVERSE_FAILED) } to EstadoDelReversoEnServidor.REVERSE_FAILED,
            suspend { delay(20_000); guardado(EstadoDelReversoEnServidor.REVERSED) } to EstadoDelReversoEnServidor.REVERSED,
        )
        for ((i, caso) in casos.withIndex()) {
            val (acuse, enServidor) = caso
            servidor.acuse = acuse
            val s = solicitud()
            val ultimo = cobrar(s).last()
            assertEquals("acuse $i", NoSeCobro to blumon.reversos.last().first + 1_000, ultimo)
            esperarTodo()
            assertEquals("acuse $i", EstadoDelIntento.NO_SE_COBRO, fila(s).estado)
            assertEquals("acuse $i", enServidor, fila(s).estadoEnServidor)
            assertEquals("acuse $i", listOf<EventoDeCobro>(NoSeCobro), tardiasDe(s))
        }
    }

    // ───────────── F. Al vencer el plazo, o si ya no había tiempo para reversar ─────────────

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
        blumon.venta = { delay(1_000); http(401, APROBADA) } // la primera vuelta pide token nuevo
        blumon.renovar = { salto += 60_000; null } // y el reloj se pasa del techo mientras lo pide, antes del primer reclamo
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
        blumon.venta = { delay(18_000); http(200, APROBADA) } // llega a la mitad del reverso (T0 + 15 a T0 + 20 s)
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
        assertTrue(servidor.reportados.isEmpty())
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelReverso.NINGUNO, fila(s).reverso)
        assertEquals(EstadoDelIntento.APROBADO, fila(s).estado)
    }

    @Test fun `tabla B YaResuelta ya comprometida sigue al reverso`() = prueba {
        val s = solicitud()
        blumon.venta = {
            delay(15_000)
            libreta.comprometerAReversar(s.id) // la fila queda ENVIADO + COMPROMETIDO antes del compromiso del motor
            Respuesta.SinRespuesta("TIEMPO")
        }
        assertEquals(NoSeCobro, terminal(s))
        assertEquals(1, blumon.reversos.size)
    }

    @Test fun `aprobado justo antes de los 15 s es la fila A`() = prueba {
        blumon.venta = { delay(14_999); http(200, APROBADA) }
        val s = solicitud()
        val eventos = cobrar(s)
        assertEquals(APROBADO_VISA, eventos.last().first)
        assertEquals(T0 + 14_999, eventos.last().second)
        esperarTodo()
        assertTrue(blumon.reversos.isEmpty())
        assertEquals(EstadoDelReverso.NINGUNO, fila(s).reverso)
    }

    // ───────────── Reverso ─────────────

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
        // La tercera vuelta (T0 + 43 s) sale antes del techo y Blumon contesta TX_007 DESPUÉS del techo.
        blumon.reverso = { n -> delay(9_000); http(200, error(if (n < 3) "TX_008" else "TX_007")) }
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
            // La tercera vuelta (T0 + 43 s) sale antes del techo y su REVERSADA llega después.
            blumon.reverso = { n -> delay(9_000); if (n < 3) http(200, error("TX_008")) else http(200, REVERSADA) }
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

    @Test fun `un reverso confirmado se reconstruye de la fila tras apagar antes de publicar`() = runTest {
        val antes = Mundo(this)
        antes.blumon.venta = SILENCIO
        // El proceso muere justo después del commit del REVERSADA, antes de publicarlo: la cajera sale desde la memoria.
        antes.ganchos["cerrarVuelta"] = { antes.fondo.cancel() }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), antes.terminal(s))
        advanceUntilIdle()
        assertEquals(EstadoDelIntento.NO_SE_COBRO, antes.fila(s).estado)
        antes.db.close()

        val despues = Mundo(this, archivo = antes.archivo, proceso = UUID.randomUUID().toString())
        assertEquals(listOf(NoSeCobro), despues.cobrar(s).map { it.first })
        assertTrue(despues.blumon.ventas.isEmpty())
        antes.cerrar()
        despues.cerrar()
    }

    /** Bloque S (D-4): `recuperar()` entrega los dos; el `contradiction` se guarda como evidencia y no cambia lo que se dijo. */
    @Test fun `dos pendientes uno reversed y otro contradiction`() = prueba {
        blumon.venta = SILENCIO
        servidor.acuse = { delay(1_000); AcuseDelResultado.Reintentar }
        val uno = solicitud()
        val otro = solicitud()
        assertEquals(NoSeCobro, terminal(uno))
        assertEquals(NoSeCobro, terminal(otro))
        esperarTodo() // los intentos de `fondo` contestan Reintentar
        assertEquals(EstadoDelReporte.PENDIENTE, fila(uno).reporte)
        assertEquals(EstadoDelReporte.PENDIENTE, fila(otro).reporte)

        val acuses = ArrayDeque(listOf(guardado(EstadoDelReversoEnServidor.REVERSED), guardado(EstadoDelReversoEnServidor.CONTRADICTION)))
        servidor.acuse = { acuses.removeFirst() }
        val publicadas = tardias.toList()
        assertTrue(motor.recuperar().containsAll(listOf(uno.id, otro.id)))
        esperarTodo()
        assertEquals(EstadoDelReversoEnServidor.REVERSED, fila(uno).estadoEnServidor)
        assertEquals(EstadoDelReversoEnServidor.CONTRADICTION, fila(otro).estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(uno).estado)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(otro).estado)
        assertEquals(publicadas, tardias)
        assertEquals(NoSeCobro, terminal(uno))
        assertEquals(NoSeCobro, terminal(otro))
    }

    @Test fun `venta NoEnviada da NO_SALIO y no reversa`() = prueba {
        blumon.venta = { delay(14_999); Respuesta.NoEnviada } // el plazo de la venta venció sin escribir un byte
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.INTERRUMPIDO), terminal(s))
        esperarTodo()
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertTrue(blumon.reversos.isEmpty())
    }

    // ───────────── Sin salir al banco y casos de dinero ─────────────

    @Test fun `401 de Blumon en la venta es duda y va al reverso`() = prueba {
        blumon.venta = { n -> delay(1_000); if (n == 1) http(401, APROBADA) else http(200, APROBADA) }
        val s = solicitud()
        val eventos = cobrar(s).map { it.first }
        assertFalse(eventos.any { it is Aprobado })
        assertEquals(NoSeCobro, eventos.last())
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
        // A: 401 a los 2 s y su reverso pide token enseguida: lo intenta a lo más min(sesión, reverso) = 10 s.
        val b = solicitud()
        val inicioB = prueba.currentTime
        val eventos = cobrar(b)
        assertEquals(noSalio(MotivoNoSalio.SIN_LLAVES), eventos.last().first)
        assertTrue(eventos.last().second <= inicioB + 15_000) // nunca más que esperaSesion desde que empezó a esperar
        deA.await()
        assertEquals(2_000L + 10_000, blumon.reversos.first().first) // el reverso se rindió en su plazo y salió con el congelado
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

    /** Bloque 1b · fila 11: el lector repite la lectura de la venta anterior ⇒ la venta no sale y su fila dice por qué. */
    @Test fun `una venta con la lectura de la venta anterior no sale`() = prueba {
        lector.resultado = { ResultadoDeLectura.Leida(LecturasDePrueba.visaPlasticoVenta(it)) } // un lector que repite: sin toque
        assertEquals(APROBADO_VISA, terminal(solicitud()))
        val s = solicitud()
        assertEquals(noSalio(MotivoNoSalio.LECTURA_REPETIDA), terminal(s))
        assertEquals(1, blumon.ventas.size)
        assertEquals(2, lector.lecturas)
        assertEquals(EstadoDelIntento.NO_SALIO, fila(s).estado)
        assertEquals(MotivoNoSalio.LECTURA_REPETIDA, fila(s).motivoNoSalio)
        esperarTodo()
        assertEquals("el folio contesta lo mismo", noSalio(MotivoNoSalio.LECTURA_REPETIDA), terminal(s))
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

    /** R1-4: una libreta por archivo en el proceso, así que `tomarDueno()` frena al segundo motor. */
    @Test fun `dos abrir del mismo archivo dan la misma libreta y un segundo motor truena`() = prueba {
        val nombre = "abrir-${UUID.randomUUID()}.db"
        val a = Libreta.abrir(context, nombre)
        fun motorSobre(l: Libreta) = MotorDeCobro(
            identidad, lector, red, blumon, l, ColaDeResultados(l, servidor, io), huella,
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
            id, lector, red, blumon, l, ColaDeResultados(l, servidor, io), huella,
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
        assertEquals(NoSeCobro, desenlace.await()) // Blumon confirmó su reverso; el acuse que no llega no cambia nada
        assertEquals(2, blumon.reversos.size) // la viva sí reversó
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
     * Un solo embudo: el desenlace tardío de un intento vivo (bloque S: el `REVERSADA` que llega después de soltar a la cajera)
     * se publica UNA vez, después de su `PorConfirmar`, y nada viejo sale después.
     */
    @Test fun `una resolucion tardia de un intento vivo se publica una sola vez`() = prueba {
        blumon.venta = SILENCIO
        blumon.reverso = { n -> delay(9_000); if (n < 3) http(200, error("TX_008")) else http(200, REVERSADA) }
        val s = solicitud()
        assertEquals(PorConfirmar(s.importeCentavos), terminal(s))
        esperarTodo()
        assertEquals(listOf(PorConfirmar(s.importeCentavos), NoSeCobro), tardiasDe(s))
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
        // Vueltas a T0+15, +24, +33 (4 s cada una, 5 s de pausa) y la cuarta a T0+42, que se queda en vuelo hasta T0+52.
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
     * Test 25 (cambiado en r10, R10-1; bloque S): el `REVERSADA` de A se guarda, pero la fila no se puede leer justo después (una
     * falla pasajera de Room): ni el motor ni su `catch` publican el definitivo y A queda como lápida en duda. La PRIMERA
     * reapertura da `PorConfirmar` al instante y dispara UN refresco en `fondo` (aunque se reabra otra vez mientras corre); al
     * terminar, `NoSeCobro` sale por `resolucionesTardias` y la SIGUIENTE reapertura da `NoSeCobro`.
     */
    @Test fun `una lapida en duda contesta ya y se refresca de la fila en fondo`() = prueba {
        blumon.venta = SILENCIO
        val a = solicitud()
        ganchos["cerrarVuelta"] = { danar(a, "NO_SE_COBRO") } // dentro de la transacción del REVERSADA
        assertEquals(PorConfirmar(a.importeCentavos), terminal(a))
        prueba.advanceTimeBy(1_000)
        assertEquals(true, motor.intentos[a.id]?.retirado)
        ganchos.clear()
        db.openHelper.writableDatabase.execSQL("UPDATE intentos SET operationNumber = NULL WHERE attemptId = ?", arrayOf(a.id.valor))
        assertEquals(EstadoDelIntento.NO_SE_COBRO, fila(a).estado) // Room se recuperó: el definitivo está guardado
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
        blumon.reverso = { delay(1_000); http(200, error("TX_007")) } // Blumon rechaza el reverso: queda en duda (tabla D)
        val s = solicitud()
        val ultimo = cobrar(s).last()
        assertEquals(PorConfirmar(s.importeCentavos), ultimo.first)
        prueba.testScheduler.runCurrent()
        assertEquals(true, motor.intentos[s.id]?.retirado)
        motor.antesDeBuscar = { delay(60_000) }
        assertEquals(ultimo, cobrar(s).last()) // 0 ms después del retiro
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
        blumon.reverso = { delay(1_000); http(200, error("TX_007")) } // Blumon rechaza el reverso: queda en duda (tabla D)
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

    /** 3 · Codex r4 #2, salida «evento»: una fila APROBADO sin folio bancario ni id de pago nunca da «Aprobado». */
    @Test fun `un aprobado sin folio ni pago da por confirmar`() = prueba {
        val s = solicitud()
        filaDirecta(s) { it.copy(estado = EstadoDelIntento.APROBADO, operationNumber = 1_234_567) }
        db.openHelper.writableDatabase.execSQL("UPDATE intentos SET operationNumber = NULL WHERE attemptId = ?", arrayOf(s.id.valor))
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(s.importeCentavos)), cobrar(s).map { it.first })
    }
}
