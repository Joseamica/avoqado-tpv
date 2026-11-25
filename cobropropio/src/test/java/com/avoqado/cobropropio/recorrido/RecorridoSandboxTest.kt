package com.avoqado.cobropropio.recorrido

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.blumon.Clasificador
import com.avoqado.cobropropio.blumon.ClienteBlumon
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.DesenlaceDeCancelacion
import com.avoqado.cobropropio.blumon.PuertoBlumon
import com.avoqado.cobropropio.blumon.SesionBlumon
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.EventoDeCobro
import com.avoqado.cobropropio.dominio.Identidad
import com.avoqado.cobropropio.dominio.Lectura
import com.avoqado.cobropropio.dominio.LectorDeTarjeta
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
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.motor.MotorDeCobro
import com.avoqado.cobropropio.motor.Relojes
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.EnvioUnico
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.AcuseDelResultado
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.ResultadoDelReverso
import com.avoqado.cobropropio.servidor.ReversoDelServidor
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tarea 8: el MOTOR completo contra el SANDBOX de Blumon (método §2). `ClienteBlumon` real sobre `Endpoints.SANDBOX`, una
 * libreta de archivo, un SERVIDOR falso que acusa lo reportado, y la lectura sintética de Mastercard de las pruebas.
 *
 * Se salta sin `COBRO_PROPIO_SANDBOX_FILE` (el mismo archivo de `SesionSandboxTest`). Con `COBRO_PROPIO_EVIDENCIA` escribe
 * ahí (600) lo medido, porque avq-verify sólo conserva los totales. Lo que imprime o guarda nunca lleva PAN, banda, llaves,
 * token, credencial, el cuerpo crudo ni texto libre de una respuesta: HTTP, `status`, códigos, folios, latencias, nombres de
 * campo, y de cada texto sólo su largo (y su forma si es un dato corto sin espacios).
 *
 * Pasos: (1) venta normal; (2) venta cuya respuesta un envoltorio retiene más allá de `esperaRespuesta`, para que el
 * MOTOR la reverse por el arbitraje, el reclamo durable, el techo y el cierre; (3) otra venta después del
 * reverso; (4) la cancelación de la venta 3 por el motor, con la misma tarjeta sintética y una lectura nueva (T9: el
 * preflight y el sobre real de la cancelación); (5) cinco `iniciarSesion` seguidos para ver si alguna base KSN se repite.
 *
 * El sandbox rechaza una `reference` de 36 caracteres (`E_000`, `DataIntegrityViolationException`) y acepta 32: por eso el
 * `AttemptId` mide 32 (ruling T8, arreglos 1) y el motor lo manda tal cual.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecorridoSandboxTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val mono: () -> Long = { System.nanoTime() / 1_000_000 }
    private val notas = CopyOnWriteArrayList<String>()

    private fun nota(s: String) {
        notas += s
        println(s)
    }

    // ───────────── El mundo ─────────────

    private class LectorFalso : LectorDeTarjeta {
        @Volatile var lectura: (Long) -> Lectura = { LecturasDePrueba.mastercardPlasticoVenta(it) }
        override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long) = ResultadoDeLectura.Leida(lectura(importeCentavos))
    }

    private class ServidorFalso : ReversoDelServidor {
        val reportados = CopyOnWriteArrayList<Pair<AttemptId, ResultadoDelReverso>>()

        override suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado {
            reportados += id to r
            val estado = when {
                r.descripcion == "REVERSADA" -> EstadoDelReversoEnServidor.REVERSED
                r.codigo == Clasificador.SIN_RESPUESTA -> EstadoDelReversoEnServidor.REVERSING
                else -> EstadoDelReversoEnServidor.REVERSE_FAILED
            }
            return AcuseDelResultado.Guardado(estado, "evt-recorrido", null)
        }
    }

    private class MedidaDeVenta(val respuesta: Respuesta, val inicioMs: Long, val latenciaMs: Long, val retenida: Boolean)

    private class MedidaDeReverso(
        val respuesta: Respuesta,
        val inicioMs: Long,
        val latenciaMs: Long,
        /** Los bytes de la petición que el socket TLS recibió (la marca de «escrito»): > 0 = el POST salió. */
        val bytesEscritos: Long,
        /** Lo que el transporte leyó (`alLeer`) es lo mismo que se devolvió al motor. */
        val leidaEsLaDevuelta: Boolean,
    )

    /**
     * Pasa todo al `ClienteBlumon` real. Retiene la respuesta de UNA venta (la marcada) más allá de `esperaRespuesta`, y
     * manda los reversos por un cliente con su propio `EnvioUnico`, para leer sin cruces los bytes que salieron.
     */
    private inner class Envoltorio(
        private val real: ClienteBlumon,
        private val paraReverso: ClienteBlumon,
        private val envioReverso: EnvioUnico,
        private val retencionMs: Long,
    ) : PuertoBlumon {
        val retenerLaSiguiente = AtomicBoolean(false)
        val retenidaDevuelta = AtomicBoolean(false)
        val ventas = CopyOnWriteArrayList<MedidaDeVenta>()
        val reversos = CopyOnWriteArrayList<MedidaDeReverso>()
        val sesiones = CopyOnWriteArrayList<Long>()

        override suspend fun iniciarSesion(espera: Duration): SesionBlumon? {
            val inicio = mono()
            return real.iniciarSesion(espera).also { sesiones += mono() - inicio }
        }

        override suspend fun renovarToken(espera: Duration) = real.renovarToken(espera)

        override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta {
            val retener = retenerLaSiguiente.getAndSet(false)
            val inicio = mono()
            val r = real.cobrar(sesion, cuerpo, espera, noEmpezarDespuesDe)
            ventas += MedidaDeVenta(r, inicio, mono() - inicio, retener)
            if (retener) {
                delay(retencionMs)
                retenidaDevuelta.set(true)
            }
            return r
        }

        override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta {
            val leida = AtomicReference<Respuesta?>(null)
            envioReverso.bytesDelUltimoEnvio = -1
            val inicio = mono()
            val r = paraReverso.reversar(congelado, espera, noEmpezarDespuesDe) {
                leida.set(it)
                alLeer(it)
            }
            reversos += MedidaDeReverso(r, inicio, mono() - inicio, envioReverso.bytesDelUltimoEnvio, leida.get() === r)
            return r
        }

        /** T9: el preflight y la cancelación, tal como los contestó Blumon. */
        val preflights = CopyOnWriteArrayList<MedidaDeVenta>()
        val cancelaciones = CopyOnWriteArrayList<MedidaDeVenta>()

        override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta {
            val inicio = mono()
            return real.validarCancelacion(sesion, operationNumber, espera, alLeer).also { preflights += MedidaDeVenta(it, inicio, mono() - inicio, false) }
        }

        override suspend fun cancelar(
            sesion: SesionBlumon, operationNumber: Long, cuerpo: CuerpoDeBlumon, espera: Duration, alLeer: (Respuesta) -> Unit,
        ): Respuesta {
            val inicio = mono()
            return real.cancelar(sesion, operationNumber, cuerpo, espera, alLeer).also { cancelaciones += MedidaDeVenta(it, inicio, mono() - inicio, false) }
        }
    }

    // ───────────── El recorrido ─────────────

    @Test fun `recorrido del motor contra el sandbox`() {
        val ruta = System.getenv("COBRO_PROPIO_SANDBOX_FILE")
        assumeTrue("sin COBRO_PROPIO_SANDBOX_FILE", !ruta.isNullOrBlank() && File(ruta).isFile)
        try {
            recorrer(File(ruta!!))
        } finally {
            guardarEvidencia()
        }
    }

    private fun recorrer(archivo: File) = runBlocking {
        val p = Properties().apply { archivo.inputStream().use { load(it) } }
        val serie = p.getProperty("serie").orEmpty()
        val identidad = Identidad(
            comercio = p.getProperty("comercio").orEmpty(),
            posId = p.getProperty("posId").orEmpty(),
            serieFisica = serie,
            serieBlumon = serie,
            modelo = "A910S",
            versionSdk = "cobropropio",
            ambiente = Ambiente.SANDBOX,
        )
        val credencial = CredencialDelCliente(p.getProperty("usuario").orEmpty(), p.getProperty("contrasena").orEmpty())
        val endpoints = Endpoints.SANDBOX
        val envio = EnvioUnico(endpoints, mono)
        val envioReverso = EnvioUnico(endpoints, mono)
        val cliente = ClienteBlumon(endpoints, envio, credencial, identidad, SecureRandom())
        val relojes = Relojes()
        val blumon = Envoltorio(
            cliente,
            ClienteBlumon(endpoints, envioReverso, credencial, identidad, SecureRandom()),
            envioReverso,
            retencionMs = relojes.esperaRespuesta + 10_000,
        )
        val servidor = ServidorFalso()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = LibretaDatabase.constructor(context, File(carpeta.root, "libreta-recorrido.db")).allowMainThreadQueries().build()
        val libreta = Libreta(db, mono, Instant::now)
        val fondo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lector = LectorFalso()
        val motor = MotorDeCobro(
            identidad, lector, object : Red { override fun hayRed() = true }, blumon, servidor, libreta,
            ColaDeResultados(libreta, servidor), HuellaHmac(SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")),
            CatalogoDeRechazos.BLUMON, relojes, fondo, mono, Instant::now,
        )
        val tardias = CopyOnWriteArrayList<Pair<AttemptId, EventoDeCobro>>()
        fondo.launch { motor.resolucionesTardias.collect { tardias += it } }

        nota("# Recorrido del motor contra el sandbox · ${Instant.now()}")
        nota("ambiente=SANDBOX · importe=100 centavos · reference = el AttemptId (32 hexadecimales) · relojes por defecto")

        /** Una venta por el motor, con su evidencia. */
        suspend fun vender(paso: String, lectura: (Long) -> Lectura, pan: String, retener: Boolean = false): Venta {
            lector.lectura = lectura
            val s = SolicitudDeVenta(AttemptId.nuevo(), "venue-recorrido", 100, Origen.VINCULADO_AL_POS)
            nota("\n## $paso")
            nota("reference: el AttemptId, ${s.id.valor.length} caracteres, tal cual")
            val antes = blumon.ventas.size
            blumon.retenerLaSiguiente.set(retener)
            val inicio = mono()
            val eventos = withTimeout(120_000) { motor.cobrar(s).toList() }
            nota("eventos: ${eventos.joinToString(" → ") { ev(it) }} (terminal a los ${mono() - inicio} ms)")
            val medida = blumon.ventas.drop(antes).firstOrNull()
            val esperado = Clasificador.Esperado(s.id, s.importeCentavos, pan, Ambiente.SANDBOX)
            if (medida == null) {
                nota("venta: el motor no llegó a Blumon")
            } else {
                nota("venta: ${describir(medida.respuesta, s.id.valor)} · latencia ${medida.latenciaMs} ms${if (medida.retenida) " · RETENIDA" else ""}")
                nota("clasificador (sin el motor): ${Clasificador.venta(medida.respuesta, esperado, CatalogoDeRechazos.BLUMON).let(::desenlace)}")
                diagnostico(medida.respuesta, esperado)
            }
            return Venta(s, eventos, medida, esperado)
        }

        // 1 · Venta normal: el lector estricto tiene que aceptar lo que Blumon manda de verdad.
        val a = vender("1 · Venta normal (Mastercard sintética)", { LecturasDePrueba.mastercardPlasticoVenta(it) }, LecturasDePrueba.PAN_MASTERCARD)
        esperarFilaQuieta(libreta, a.s.id)
        nota("fila: ${fila(libreta.buscar(a.s.id))}")

        // 2 · Venta retenida: el reverso lo hace el MOTOR.
        val b = vender("2 · Venta retenida ⇒ reverso del motor", { LecturasDePrueba.mastercardPlasticoVenta(it).toque(2) }, LecturasDePrueba.PAN_MASTERCARD, retener = true)
        val fb = esperarResultadoDelReverso(libreta, b.s.id)
        nota("fila tras esperar el resultado del reverso: ${fila(fb)}")
        servidor.reportados.filter { it.first == b.s.id }.forEach { (_, r) ->
            nota("reportado al SERVIDOR falso: descripción ${r.descripcion?.let(::estadoOTexto)} · código ${r.codigo?.let(::codigoOForma)} · op ${r.operationNumber}")
        }
        val reversosB = blumon.reversos.filter { it.inicioMs >= (b.medida?.inicioMs ?: Long.MAX_VALUE) }
        reversosB.forEachIndexed { i, r ->
            val desdeLaVenta = b.medida?.let { r.inicioMs - it.inicioMs }
            nota(
                "reverso vuelta ${i + 1}: ${describir(r.respuesta, b.s.id.valor)} · latencia ${r.latenciaMs} ms · ${desdeLaVenta} ms desde " +
                    "el POST de la venta · bytes escritos ${r.bytesEscritos} · alLeer = lo devuelto: ${r.leidaEsLaDevuelta} · " +
                    "REVERSADA con nuestro eco: ${reversadaConEco(r.respuesta, b.s.id)}",
            )
            nota("  clasificador del reverso (sin el motor): ${claseDelReverso(Clasificador.reverso(r.respuesta, b.esperado))}")
            diagnostico(r.respuesta, b.esperado)
        }
        withTimeout(60_000) { while (!blumon.retenidaDevuelta.get()) delay(200) }
        delay(2_000) // guardarRespuestaTardia
        nota("fila tras soltar la respuesta retenida: ${fila(libreta.buscar(b.s.id))}")
        nota("resolucionesTardias de la venta 2: ${tardias.filter { it.first == b.s.id }.joinToString { ev(it.second) }}")

        // 3 · Otra venta después del reverso (en producción la siguiente salió N0 dos veces).
        val c = vender("3 · Venta después del reverso", { LecturasDePrueba.mastercardPlasticoVenta(it).toque(3) }, LecturasDePrueba.PAN_MASTERCARD)
        esperarFilaQuieta(libreta, c.s.id)
        nota("fila: ${fila(libreta.buscar(c.s.id))}")

        // 4 · T9: la cancelación de la venta 3, enseguida, por el motor: la misma tarjeta sintética con una lectura nueva.
        nota("\n## 4 · Cancelación de la venta 3 (la misma Mastercard sintética, lectura nueva: otro 9F26 y 9F36)")
        lector.lectura = { LecturasDePrueba.mastercardPlasticoCancelacion(it).con(tags = mapOf(0x9F36 to "0002", 0x9F26 to "B1B2C3D4E5F60719")) }
        val cancelacion = SolicitudDeCancelacion(AttemptId.nuevo(), "venue-recorrido", c.s.id)
        val refs = mapOf(cancelacion.id.valor to "el AttemptId de la CANCELACIÓN", c.s.id.valor to "el AttemptId de la VENTA original")
        val esperadoC = Clasificador.Esperado(cancelacion.id, c.s.importeCentavos, LecturasDePrueba.PAN_MASTERCARD, Ambiente.SANDBOX)
        val inicioC = mono()
        val eventosC = withTimeout(120_000) { motor.cancelar(cancelacion).toList() }
        nota("eventos: ${eventosC.joinToString(" → ") { ev(it) }} (terminal a los ${mono() - inicioC} ms)")
        val preflight = blumon.preflights.lastOrNull()
        if (preflight == null) nota("preflight: el motor no llegó a Blumon") else {
            nota("preflight (validateCancelation): ${describir(preflight.respuesta, refs)} · latencia ${preflight.latenciaMs} ms")
            nota("  preflight según el clasificador: sandbox=${Clasificador.preflightDeCancelacion(preflight.respuesta, Ambiente.SANDBOX)} · producción=${Clasificador.preflightDeCancelacion(preflight.respuesta, Ambiente.PRODUCCION)}")
            diagnostico(preflight.respuesta, esperadoC)
        }
        val medidaC = blumon.cancelaciones.lastOrNull()
        if (medidaC == null) nota("cancelación: el motor no llegó a Blumon") else {
            nota("cancelación (retail/present/cancel): ${describir(medidaC.respuesta, refs)} · latencia ${medidaC.latenciaMs} ms")
            nota("  clasificador de la cancelación (sin el motor): ${Clasificador.cancelacion(medidaC.respuesta, esperadoC, CatalogoDeRechazos.BLUMON)}")
            diagnostico(medidaC.respuesta, esperadoC)
        }
        esperarFilaQuieta(libreta, cancelacion.id)
        nota("fila de la cancelación: ${fila(libreta.buscar(cancelacion.id))}")
        nota("fila de la venta 3 tras cancelarla: ${fila(libreta.buscar(c.s.id))}")

        // 5 · Las bases KSN: cinco sesiones seguidas (y la del motor, por la fila de la venta 1).
        nota("\n## 5 · Cinco iniciarSesion seguidos")
        val bases = mutableListOf<String?>()
        val contadores = mutableListOf<Int?>()
        repeat(5) {
            val inicio = mono()
            val ses = cliente.iniciarSesion(15.seconds)
            nota("sesión ${it + 1}: ${if (ses != null) "nació" else "null"} en ${mono() - inicio} ms · contador de Blumon ${ses?.ultimoContadorDeBlumon}")
            bases += ses?.ksnBaseCanonica
            contadores += ses?.ultimoContadorDeBlumon
            ses?.material?.close()
        }
        val delMotor = listOf(a, b, c).mapNotNull { libreta.buscar(it.s.id)?.ksnBase }.distinct()
        val todas = delMotor + bases.filterNotNull()
        nota("bases del motor (filas de las ventas): ${delMotor.size} distinta(s)")
        nota("bases: ${todas.size} medidas, ${todas.distinct().size} distintas · repetidas: ${todas.size - todas.distinct().size}")

        // Cierre.
        nota("\n## Totales")
        nota("sesiones del motor (iniciarSesion): ${blumon.sesiones.size} · ventas a Blumon: ${blumon.ventas.size} · reversos: ${blumon.reversos.size}")
        fondo.cancel()
        db.close()

        // Lo que decide si el recorrido pasó: lo que vio la cajera, el reverso escrito y leído, y la fila con lo reportado.
        nota("venta 1 terminó: ${ev(a.eventos.last())}")
        assertTrue("la venta 1 no es Aprobado: ${ev(a.eventos.last())}", a.eventos.last() is EventoDeCobro.Aprobado)
        assertEquals("la cajera de la venta 2", listOf("RevisandoConElBanco", "NoSeCobro"), b.eventos.takeLast(2).map(::ev))
        assertTrue("la venta 3 no es Aprobado: ${ev(c.eventos.last())}", c.eventos.last() is EventoDeCobro.Aprobado)
        // 4 · La cancelación: lo que vio la cajera es lo que dice el sobre de Blumon, y la venta 3 sigue APROBADO.
        assertTrue("el motor no pidió el preflight", preflight != null)
        val preflightSi = Clasificador.preflightDeCancelacion(preflight!!.respuesta, Ambiente.SANDBOX)
        if (preflightSi != true) {
            // r1 A y B: sin un «sí» no se pide la tarjeta; sin respuesta es SIN_RED, con un «no» CANCELACION_NO_PERMITIDA.
            val motivo = if (preflightSi == null) MotivoNoSalio.SIN_RED else MotivoNoSalio.CANCELACION_NO_PERMITIDA
            assertEquals(listOf("Iniciado", ev(EventoDeCobro.NoSalioAlBanco(motivo, null))), eventosC.map(::ev))
            assertEquals("POST de cancelación sin un «sí»", 0, blumon.cancelaciones.size)
        } else {
            assertEquals("POST de cancelación con un «sí»", 1, blumon.cancelaciones.size)
            val visto = when (val d = Clasificador.cancelacion(medidaC!!.respuesta, esperadoC, CatalogoDeRechazos.BLUMON)) {
                is DesenlaceDeCancelacion.Cancelada -> EventoDeCobro.Cancelada(d.operationNumber)
                is DesenlaceDeCancelacion.Rechazada -> EventoDeCobro.Rechazado(d.codigo, d.leyenda)
                DesenlaceDeCancelacion.NoSe -> EventoDeCobro.PorConfirmar(c.s.importeCentavos)
                DesenlaceDeCancelacion.NoEnviada -> EventoDeCobro.NoSalioAlBanco(MotivoNoSalio.INTERRUMPIDO, null)
            }
            assertEquals(visto, eventosC.last())
        }
        assertEquals(EstadoDelIntento.APROBADO, libreta.buscar(c.s.id)?.estado)
        val vueltaB = reversosB.firstOrNull { it.bytesEscritos > 0 && it.respuesta is Respuesta.Http && it.leidaEsLaDevuelta }
        assertTrue("ningún POST de reverso escrito con respuesta leída", vueltaB != null)
        assertTrue("la última vuelta no confirma", Clasificador.reverso(reversosB.last().respuesta, b.esperado) is ClaseDelReverso.Confirmado)
        // Codex r13 (R13-3): una sola vuelta, y la confirmó un `REVERSADA` con nuestro eco (no un TX_013/TX_014).
        assertEquals("vueltas del reverso de la venta 2", 1, reversosB.size)
        assertTrue("la confirmación no vino de un REVERSADA con nuestro eco", reversadaConEco(reversosB.single().respuesta, b.s.id))
        assertEquals(EstadoDelReverso.CONFIRMADO, fb?.reverso)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, fb?.estadoEnServidor)
        assertEquals(listOf("«REVERSADA»"), servidor.reportados.filter { it.first == b.s.id }.map { it.second.descripcion?.let(::estadoOTexto) })
    }

    private class Venta(
        val s: SolicitudDeVenta,
        val eventos: List<EventoDeCobro>,
        val medida: MedidaDeVenta?,
        val esperado: Clasificador.Esperado,
    )

    /** Hasta 60 s: la fila sale de ENVIADO y su reverso ya no está en curso. */
    private suspend fun esperarResultadoDelReverso(libreta: Libreta, id: AttemptId): IntentoEntity? {
        val limite = mono() + 60_000
        var f = libreta.buscar(id)
        while (mono() < limite && !(f != null && f.estado != EstadoDelIntento.ENVIADO && f.reverso in CON_RESULTADO)) {
            delay(500)
            f = libreta.buscar(id)
        }
        return f
    }

    /** Lo mismo para una venta sin retener: si se fue al reverso, espera su cierre; si no, ya está quieta. */
    private suspend fun esperarFilaQuieta(libreta: Libreta, id: AttemptId) {
        val f = libreta.buscar(id) ?: return
        if (f.estado == EstadoDelIntento.ENVIADO || f.reverso in setOf(EstadoDelReverso.COMPROMETIDO, EstadoDelReverso.RECLAMADO)) {
            esperarResultadoDelReverso(libreta, id)
        }
        delay(1_000)
    }

    // ───────────── Evidencia sin datos ─────────────

    /** HTTP + la forma de cada campo; en claro sólo `status`, booleanos, códigos, folios y textos sin dígitos de error. */
    private fun describir(r: Respuesta, reference: String): String = describir(r, mapOf(reference to "nuestro AttemptId"))

    /** [referencias]: cada id nuestro con su nombre, para decir de CUÁL es el eco (la cancelación o su venta). */
    private fun describir(r: Respuesta, referencias: Map<String, String>): String = when (r) {
        Respuesta.NoEnviada -> "NoEnviada"
        is Respuesta.SinRespuesta -> "SinRespuesta(${r.clase})"
        is Respuesta.Http -> "HTTP ${r.codigo} { ${formas(cuerpoDe(r), referencias).joinToString("; ")} }"
    }

    /** Sólo para medir FORMAS en la prueba: el cuerpo nunca se imprime ni se guarda. */
    private fun cuerpoDe(r: Respuesta.Http): String =
        Respuesta.Http::class.java.getDeclaredField("cuerpo").apply { isAccessible = true }.get(r) as String

    private fun formas(cuerpo: String, referencias: Map<String, String>): List<String> {
        val salida = mutableListOf<String>()
        try {
            val r = JsonReader(StringReader(cuerpo)).apply { strictness = Strictness.STRICT }
            fun valor(ruta: String) {
                when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        r.beginObject()
                        if (!r.hasNext()) salida += "$ruta: {}"
                        while (r.hasNext()) {
                            // R14-6: un nombre de campo sólo sale en claro con forma de identificador; si no, forma y largo.
                            val k = r.nextName().let { if (NOMBRE_DE_CAMPO.matches(it)) it else "‹clave ${resumen(it)}›" }
                            valor(if (ruta.isEmpty()) k else "$ruta.$k")
                        }
                        r.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        r.beginArray()
                        var i = 0
                        if (!r.hasNext()) salida += "$ruta: []"
                        while (r.hasNext()) valor("$ruta[${i++}]")
                        r.endArray()
                    }
                    JsonToken.STRING -> salida += "$ruta: texto ${mostrar(ruta, r.nextString(), referencias)}"
                    JsonToken.NUMBER -> salida += "$ruta: número ${mostrar(ruta, r.nextString(), referencias)}"
                    JsonToken.BOOLEAN -> salida += "$ruta: ${r.nextBoolean()}"
                    JsonToken.NULL -> { r.nextNull(); salida += "$ruta: null" }
                    else -> error("token")
                }
            }
            valor("")
            if (r.peek() != JsonToken.END_DOCUMENT) salida += "(sobra texto después del objeto)"
        } catch (e: Exception) {
            salida += "(no es JSON estricto: ${e.javaClass.simpleName}; largo ${cuerpo.length})"
        }
        return salida
    }

    private fun mostrar(ruta: String, v: String, referencias: Map<String, String>): String {
        val clave = ruta.substringAfterLast('.')
        return when {
            clave == "batch" || clave == "reference" -> referencias[v]?.let { "= $it (${v.length})" } ?: "otro valor, ${resumen(v)}"
            clave in CODIGOS -> codigoOForma(v)
            clave in FOLIOS && FOLIO_VISIBLE.matches(v) -> "«$v»"
            clave == "httpStatusCode" && HTTP_VISIBLE.matches(v) -> "«$v»" // ronda 2 · P2: para ver si el error es de negocio
            clave == "description" -> estadoOTexto(v)
            else -> resumen(v)
        }
    }

    /** Codex r13 (R13-2): un código sólo sale en claro con la forma del catálogo; cualquier otra cosa, como forma y largo. */
    private fun codigoOForma(v: String) = if (CODIGO_VISIBLE.matches(v)) "«$v»" else resumen(v)

    /** `APROBADA`/`REVERSADA` en claro; cualquier otro texto de la respuesta, sólo su largo. */
    private fun estadoOTexto(v: String) = if (v in ESTADOS) "«$v»" else "texto(${v.length})"

    /** El sobre es un `REVERSADA` de éxito que trae el eco de [id] (no un TX_013/TX_014 de error). */
    private fun reversadaConEco(r: Respuesta, id: AttemptId): Boolean {
        val s = (r as? Respuesta.Http)?.sobre() ?: return false
        return s.status == true && s.descripcion == "REVERSADA" && id.valor in s.referencias
    }

    /** De un texto de la respuesta sólo sale su largo, y su forma si es un dato corto sin espacios; nunca texto libre. */
    private fun resumen(v: String) = if (DATO_CORTO.matches(v)) "forma ${forma(v)} (${v.length})" else "texto(${v.length})"

    /** `9` dígito, `A` mayúscula, `a` minúscula, lo demás tal cual; las rachas se comprimen (`9{3}.9{2}`). */
    private fun forma(v: String?): String {
        if (v == null) return "null"
        if (v.isEmpty()) return "«» (vacío)"
        if (v.length > 80) return "largo ${v.length}"
        val clases = v.map { ch ->
            when (ch) {
                in '0'..'9' -> '9'
                in 'A'..'Z' -> 'A'
                in 'a'..'z' -> 'a'
                else -> ch
            }
        }
        return buildString {
            var i = 0
            while (i < clases.size) {
                var j = i
                while (j < clases.size && clases[j] == clases[i]) j++
                append(clases[i])
                if (j - i > 1) append("{${j - i}}")
                i = j
            }
        }
    }

    /** Qué ve el sobre: cuál regla del lector estricto pesa. Sin valores, salvo códigos normalizados e importes del intento. */
    private fun diagnostico(r: Respuesta, e: Clasificador.Esperado) {
        val s = (r as? Respuesta.Http)?.sobre() ?: return nota("  sobre: no se lee (no es UN objeto JSON estricto)")
        nota(
            "  sobre: malFormado=${s.malFormado} · status=${s.status} · simulation=${s.simulation} · códigos=${s.codigos.map(::codigoOForma)} · " +
                "error.code=${s.codigoDeError?.let(::codigoOForma)} · bloque error=${s.conError} · id=${if (s.operationNumber != null) "sí" else "no"} · autorización=" +
                "${if (s.autorizacion != null) "sí" else "no"} · references=${s.referencias.size} (todas la del intento: " +
                "${s.referencias.all { it == e.reference.valor }}) · importes=${s.importesCentavos} · descripción=" +
                (s.descripcion?.let { if (it in ESTADOS) it else "texto(${it.length})" }),
        )
    }

    private fun fila(f: IntentoEntity?): String = if (f == null) "sin fila" else
        "estado=${f.estado} motivo=${f.motivoNoSalio} reverso=${f.reverso} pudoEntrar=${f.pudoEntrar} " +
            "códigoReverso=${f.ultimoCodigoReverso} paraReportar=${f.resultadoParaReportar} reporte=${f.reporte} " +
            "enServidor=${f.estadoEnServidor} op=${f.operationNumber} aut=${forma(f.autorizacion)} código=${f.codigo} " +
            "leyenda=${f.leyenda} tardía=${f.evidenciaTardiaOperacion} contador=${f.contadorUsado} vinculadoPorPermiso=${f.vinculoAcreditadoPorPermiso}"

    private fun ev(e: EventoDeCobro): String = when (e) {
        is EventoDeCobro.Iniciado -> "Iniciado"
        is EventoDeCobro.Aprobado -> "Aprobado(op=${e.operationNumber}, aut=${forma(e.autorizacion)})"
        is EventoDeCobro.Rechazado -> "Rechazado(${e.codigo}, ${e.leyenda})"
        is EventoDeCobro.NoSalioAlBanco -> "NoSalioAlBanco(${e.motivo}, ${e.admision})"
        is EventoDeCobro.PorConfirmar -> "PorConfirmar"
        is EventoDeCobro.Cancelada -> "Cancelada(${e.operationNumber})"
        EventoDeCobro.PideTarjeta, EventoDeCobro.Autorizando, EventoDeCobro.RevisandoConElBanco, EventoDeCobro.NoSeCobro,
        EventoDeCobro.ContratoDistinto -> e.toString()
    }

    private fun desenlace(d: Any): String = when (d) {
        is com.avoqado.cobropropio.blumon.DesenlaceDeVenta.Aprobada -> "Aprobada(op=${d.operationNumber}, aut=${forma(d.autorizacion)})"
        else -> d.toString()
    }

    private fun claseDelReverso(c: ClaseDelReverso): String = when (c) {
        is ClaseDelReverso.Confirmado -> "Confirmado"
        is ClaseDelReverso.Rechazado -> "Rechazado(${c.codigo})"
        is ClaseDelReverso.Reintentar -> "Reintentar(${c.codigo}, pruebaQueNoEntro=${c.pruebaQueNoEntro})"
        ClaseDelReverso.NoEnviado -> "NoEnviado"
    }

    private fun guardarEvidencia() {
        val destino = System.getenv("COBRO_PROPIO_EVIDENCIA")?.takeIf { it.isNotBlank() } ?: return
        val p = Paths.get(destino)
        Files.deleteIfExists(p)
        Files.createFile(p, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(p, notas.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    private companion object {
        val CON_RESULTADO = setOf(EstadoDelReverso.CONFIRMADO, EstadoDelReverso.RECHAZADO, EstadoDelReverso.NO_CONCLUYENTE)
        val CODIGOS = setOf("code", "codeResponse", "responseCode")
        val FOLIOS = setOf("id", "operationNumber")
        val ESTADOS = setOf("APROBADA", "REVERSADA")
        /** Las formas del catálogo: `TX_…`, `E_…`, `RQ_…`, o un ISO de 2 caracteres con o sin `.0` (R13-2). */
        val CODIGO_VISIBLE = Regex("^(?:(?:TX|E|RQ)_[0-9]{3}|[0-9A-Z]{2}(?:\\.0)?)$")
        val FOLIO_VISIBLE = Regex("^[0-9]{1,10}$")
        val HTTP_VISIBLE = Regex("^[1-5][0-9]{2}$")
        val NOMBRE_DE_CAMPO = Regex("^[A-Za-z_][A-Za-z0-9_]{0,40}$")
        val DATO_CORTO = Regex("^[0-9A-Za-z.:/_-]{1,40}$")
    }
}
