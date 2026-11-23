package com.avoqado.cobropropio.simulador

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.CatalogoDeRechazos
import com.avoqado.cobropropio.blumon.ClienteBlumon
import com.avoqado.cobropropio.blumon.CredencialDelCliente
import com.avoqado.cobropropio.blumon.CuerpoCongelado
import com.avoqado.cobropropio.blumon.CuerpoDeBlumon
import com.avoqado.cobropropio.blumon.PuertoBlumon
import com.avoqado.cobropropio.blumon.SesionBlumon
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
import com.avoqado.cobropropio.dominio.Solicitud
import com.avoqado.cobropropio.dominio.SolicitudDeCancelacion
import com.avoqado.cobropropio.dominio.SolicitudDeVenta
import com.avoqado.cobropropio.fixtures.LecturasDePrueba
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.con
import com.avoqado.cobropropio.fixtures.LecturasDePrueba.toque
import com.avoqado.cobropropio.fixtures.arrancarEnIpv4
import com.avoqado.cobropropio.fixtures.baseLiteral
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReporte
import com.avoqado.cobropropio.libreta.EstadoDelReverso
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.libreta.OrigenDeLaAprobacion
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import com.avoqado.cobropropio.motor.MotorDeCobro
import com.avoqado.cobropropio.motor.Relojes
import com.avoqado.cobropropio.motor.ResultadoDelCorte
import com.avoqado.cobropropio.privacidad.Enmascarar
import com.avoqado.cobropropio.privacidad.HuellaHmac
import com.avoqado.cobropropio.red.Endpoints
import com.avoqado.cobropropio.red.EnvioUnico
import com.avoqado.cobropropio.red.Respuesta
import com.avoqado.cobropropio.servidor.ColaDeResultados
import com.avoqado.cobropropio.servidor.CredencialDelServidor
import com.avoqado.cobropropio.servidor.ReversoDelServidorHttp
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.AUTORIZACION
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.CANCELACION
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.PREFLIGHT
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.REVERSO
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.TEXTO_DEL_BANCO
import com.avoqado.cobropropio.simulador.BlumonSimuladoDispatcher.Companion.VENTA
import com.avoqado.cobropropio.simulador.ServidorSimuladoDispatcher.Companion.INTENCION
import com.avoqado.cobropropio.simulador.ServidorSimuladoDispatcher.Companion.PAGO
import com.avoqado.cobropropio.simulador.ServidorSimuladoDispatcher.Companion.RESULTADO
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Dns
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tarea 10: el motor de punta a punta por la pila REAL (`ClienteBlumon` + `EnvioUnico` con TLS de prueba contra el Blumon
 * simulado; `ReversoDelServidorHttp` + `ColaDeResultados` contra el SERVIDOR simulado; una libreta de archivo), un escenario
 * por fila de la tabla de decisiones. Es la única evidencia del POST de la cancelación y de varios rechazos y dudas: el
 * sandbox no deja cancelar ni rechazar.
 *
 * El tiempo del MOTOR es virtual y sólo avanza cuando la prueba lo dice ([Mundo.avanzarA]): `ahora` y cada `delay` o
 * `withTimeout` del motor salen del mismo `TestCoroutineScheduler`, mientras la red, Room y OkHttp corren en hilos de verdad.
 * Ningún plazo DEL MOTOR vence por lentitud de la máquina: un silencio vence sólo cuando la prueba avanza el reloj, después de
 * una barrera (la petición ya llegó al simulador). OkHttp, en cambio, aplica sus propios límites con reloj de pared
 * (`readTimeout`, `writeTimeout` y `callTimeout` de `EnvioUnico`, del largo del plazo del motor: 10–45 s reales). Si la máquina
 * va tan lenta que una petición retenida o detenida pasa de ese límite antes de que la prueba la suelte, la llamada termina
 * en `SinRespuesta` y el desenlace o una barrera cambian: la prueba FALLA, nunca da un verde falso. Las barreras tienen un tope
 * de [BARRERA] ms de pared, sólo para fallar pronto y con su nombre en vez de colgarse.
 *
 * La pausa entre reversos va en 0: con el reloj quieto, cada vuelta sale en cuanto cierra la anterior, así que 21, 70 y 80
 * terminan por el `TX_007` de su segunda vuelta (rama D), no por el techo. La rama F (el techo con un reverso que pudo entrar)
 * la cubren las pruebas del motor de la Tarea 7: `tabla F pudoEntrar` y `fallback sin respuestas al techo reporta
 * PUDO_ENTRAR` (`MotorDeCobroVentaTest`).
 *
 * Todas corren con `Ambiente.PRODUCCION` (lo dice cada `prueba`): los éxitos «como producción» traen `simulation: false`, y
 * el 80 trae `true`. La venta es siempre vinculada al POS (V = sí). Al cerrar, cada prueba revisa la invariante de lo que
 * salió ([Mundo.revisarLoQueSalio]): ni una venta, cancelación, permiso o reverso de más.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class PuntaAPuntaTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Mientras corre el barrido de privacidad, cada `prueba` echa aquí todo lo que sale: (tramo, texto). */
    private var barrido: MutableList<Pair<String, String>>? = null

    // ───────────── A. La respuesta llega antes del arbitraje ─────────────

    @Test(timeout = T) fun `00 aprobado da Aprobado y fila APROBADO`() = aprobado()

    private fun aprobado() = prueba(Ambiente.PRODUCCION) {
        val s = venta(0)
        val eventos = esperar(cobrar(s))
        val op = requireNotNull(blumonSim.folioDe(s.id))
        assertEquals(inicio(s) + aprobadoVisa(op), eventos)
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.APROBADO, f.estado)
        assertEquals(op, f.operationNumber)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(1, blumonSim.pedidas(VENTA, s.id).size)
        sinReversoNiServidor()
    }

    @Test(timeout = T) fun `05 rechazo da Rechazado y fila RECHAZADO`() = rechazo05()

    private fun rechazo05() = prueba(Ambiente.PRODUCCION) {
        val s = venta(5)
        val leyenda = requireNotNull(CatalogoDeRechazos.BLUMON.leyenda("05"))
        assertEquals(inicio(s) + Rechazado("05", leyenda), esperar(cobrar(s)))
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.RECHAZADO, f.estado)
        assertEquals("05", f.codigo)
        assertEquals(leyenda, f.leyenda)
        sinReversoNiServidor()
    }

    @Test(timeout = T) fun `10 aprobado parcial guarda la evidencia y reversa hasta NoSeCobro`() = parcial()

    private fun parcial() = prueba(Ambiente.PRODUCCION) {
        val s = venta(10)
        assertEquals(inicio(s) + NoSeCobro, esperar(cobrar(s)))
        val f = fila(s.id)
        assertEquals(blumonSim.folioDe(s.id), f.parcialOperacion)
        assertEquals(s.importeCentavos / 2, f.parcialImporteCentavos)
        assertNull(f.operationNumber)
        reversadaYAcusada(s)
    }

    @Test(timeout = T) fun `85 codigo 68 es duda y reversa hasta NoSeCobro`() = codigo68()

    private fun codigo68() = prueba(Ambiente.PRODUCCION) {
        val s = venta(85)
        assertEquals(inicio(s) + NoSeCobro, esperar(cobrar(s)))
        assertNull(fila(s.id).codigo)
        reversadaYAcusada(s)
    }

    /** El 80 corre con PRODUCCIÓN: un éxito con `simulation: true` no se puede creer, ni el de la venta ni el del reverso. */
    @Test(timeout = T) fun `80 simulation true en produccion nunca es Aprobado y deja por confirmar`() = simulationTrue()

    private fun simulationTrue() = prueba(Ambiente.PRODUCCION) {
        val s = venta(80)
        val eventos = esperar(cobrar(s))
        assertEquals(inicio(s) + PorConfirmar(s.importeCentavos), eventos)
        assertTrue(eventos.none { it is Aprobado })
        pudoEntrarSinConfirmar(s, reversos = 2)
    }

    // ───────────── El eco y la pureza del sobre ─────────────

    @Test(timeout = T) fun `01 venta aprobada sin eco nunca es Aprobado y reversa`() = aprobadoSinEco()

    private fun aprobadoSinEco() = dudaQueSeReversa(1)

    @Test(timeout = T) fun `02 exito con un bloque error nunca es Aprobado y reversa`() = aprobadoConError()

    private fun aprobadoConError() = dudaQueSeReversa(2)

    @Test(timeout = T) fun `03 exito con un error suelto nunca es Aprobado y reversa`() = aprobadoConErrorSuelto()

    private fun aprobadoConErrorSuelto() = dudaQueSeReversa(3)

    @Test(timeout = T) fun `06 rechazo con dataResponse nunca es Rechazado y reversa`() = rechazoConDatos()

    private fun rechazoConDatos() = dudaQueSeReversa(6)

    @Test(timeout = T) fun `07 rechazo con httpStatusCode de transporte nunca es Rechazado y reversa`() = rechazoConHttpDeTransporte()

    private fun rechazoConHttpDeTransporte() = dudaQueSeReversa(7)

    /** Un sobre que no es evidencia de nada: ni `Aprobado` ni `Rechazado`; el camino de la duda lo reversa. */
    private fun dudaQueSeReversa(centavos: Int) = prueba(Ambiente.PRODUCCION) {
        val s = venta(centavos)
        assertEquals(inicio(s) + NoSeCobro, esperar(cobrar(s)))
        val f = fila(s.id)
        assertNull(f.operationNumber)
        assertNull(f.codigo)
        reversadaYAcusada(s)
    }

    // ───────────── Silencio de la venta: B a F ─────────────

    @Test(timeout = T) fun `20 silencio y REVERSADA da NoSeCobro con el acuse reversed`() = silencioYReversada()

    private fun silencioYReversada() = prueba(Ambiente.PRODUCCION) {
        val s = venta(20)
        assertEquals(inicio(s) + RevisandoConElBanco + NoSeCobro, esperar(callaLaVenta(s)))
        assertFalse(fila(s.id).pudoEntrar)
        assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
        reversadaYAcusada(s)
    }

    @Test(timeout = T) fun `21 REVERSADA sin eco no confirma el reverso y deja por confirmar`() = reversadaSinEco()

    private fun reversadaSinEco() = prueba(Ambiente.PRODUCCION) {
        val s = venta(21)
        assertEquals(inicio(s) + RevisandoConElBanco + PorConfirmar(s.importeCentavos), esperar(callaLaVenta(s)))
        pudoEntrarSinConfirmar(s, reversos = 2)
    }

    @Test(timeout = T) fun `30 silencio y TX_007 deja por confirmar y reporta RECHAZADO`() = silencioYTx007()

    private fun silencioYTx007() = prueba(Ambiente.PRODUCCION) {
        val s = venta(30)
        assertEquals(inicio(s) + RevisandoConElBanco + PorConfirmar(s.importeCentavos), esperar(callaLaVenta(s)))
        var f = fila(s.id)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.RECHAZADO, f.reverso)
        assertFalse(f.pudoEntrar)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_007", f.codigoParaReportar)
        assertEquals("TX_007", f.ultimoCodigoReverso)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(1, blumonSim.pedidas(REVERSO, s.id).size)
        assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
        // El reporte sale por la cola, con su código.
        assertTrue(s.id in recuperar())
        assertEquals(listOf("TX_007"), servidorSim.pedidas(RESULTADO, s.id).map { it.reportado })
        f = fila(s.id)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSE_FAILED, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test(timeout = T) fun `40 RQ_006 dos veces y luego REVERSADA da NoSeCobro`() = rq006DosVeces()

    private fun rq006DosVeces() = prueba(Ambiente.PRODUCCION) {
        val s = venta(40)
        assertEquals(inicio(s) + RevisandoConElBanco + NoSeCobro, esperar(callaLaVenta(s)))
        assertFalse("RQ_006 prueba que no entró", fila(s.id).pudoEntrar)
        assertEquals("el último código de un reverso que no confirmó", "RQ_006", fila(s.id).ultimoCodigoReverso)
        reversadaYAcusada(s, reversos = 3)
    }

    /**
     * 50: el aprobado llega después de los 15 s pero ANTES del arbitraje. La compuerta del `io` retiene el arbitraje (la primera
     * escritura después del plazo) hasta que el aprobado tardío quedó guardado; entonces el arbitraje lo encuentra y se queda.
     */
    @Test(timeout = T) fun `50 aprobado tardio antes del arbitraje da Aprobado sin reversar`() = tardioAntesDelArbitraje()

    private fun tardioAntesDelArbitraje() = prueba(Ambiente.PRODUCCION) {
        val s = venta(50)
        val eventos = cobrar(s)
        llego(VENTA, s.id)
        io.retenerLaSiguiente()
        avanzarA(t0(s.id) + relojes.esperaRespuesta)
        barrera("el arbitraje llega a la compuerta", io.retuvo)
        val guardado = CompletableDeferred<Unit>()
        actual.ganchos["guardarAprobacion"] = { guardado.complete(Unit) }
        blumonSim.ventaTardia.complete(Unit)
        barrera("el aprobado tardío se guarda", guardado)
        io.soltar()
        val op = requireNotNull(blumonSim.folioDe(s.id))
        assertEquals(inicio(s) + RevisandoConElBanco + aprobadoVisa(op), esperar(eventos))
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.APROBADO, f.estado)
        assertEquals(OrigenDeLaAprobacion.BANCO, f.origenDeLaAprobacion)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        sinReversoNiServidor()
    }

    /**
     * 55: el aprobado llega DESPUÉS del arbitraje (se suelta cuando el SERVIDOR recibe el pedido de permiso) y sólo se guarda
     * como evidencia. El permiso se contesta cuando la escritura de esa evidencia ya corre (el gancho va DENTRO de su
     * transacción, antes del commit); lo que el motor escribe después del permiso espera a ese commit, así que al terminar la
     * evidencia ya está en la fila.
     */
    @Test(timeout = T) fun `55 aprobado tardio despues del arbitraje solo se guarda y reversa`() = tardioDespuesDelArbitraje()

    private fun tardioDespuesDelArbitraje() = prueba(Ambiente.PRODUCCION) {
        val s = venta(55)
        val guardado = CompletableDeferred<Unit>()
        actual.ganchos["guardarAprobacion"] = { guardado.complete(Unit) }
        val vencio = AtomicBoolean(false)
        servidorSim.antesDeContestarLaIntencion = {
            blumonSim.ventaTardia.complete(Unit)
            if (runBlocking { withTimeoutOrNull(BARRERA) { guardado.await() } } == null) vencio.set(true)
        }
        val eventos = esperar(callaLaVenta(s))
        assertFalse("la barrera «el aprobado tardío se guarda» venció a los $BARRERA ms y el permiso salió sin ella", vencio.get())
        assertEquals(inicio(s) + RevisandoConElBanco + NoSeCobro, eventos)
        assertTrue(eventos.none { it is Aprobado })
        val f = fila(s.id)
        assertEquals(blumonSim.folioDe(s.id), f.evidenciaTardiaOperacion)
        assertEquals(AUTORIZACION, f.evidenciaTardiaAutorizacion)
        assertNull(f.operationNumber)
        reversadaYAcusada(s)
    }

    @Test(timeout = T) fun `60 silencio y el SERVIDOR ya cobrado da Aprobado sin reversar`() = servidorYaCobrado()

    private fun servidorYaCobrado() = prueba(Ambiente.PRODUCCION) {
        val s = venta(60, EscenarioDelServidor.YA_COBRADO)
        val aprobado = Aprobado(null, null, PAGO, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484")
        assertEquals(inicio(s) + RevisandoConElBanco + aprobado, esperar(callaLaVenta(s)))
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.APROBADO, f.estado)
        assertEquals(OrigenDeLaAprobacion.SERVIDOR, f.origenDeLaAprobacion)
        assertEquals(PAGO, f.paymentId)
        assertTrue(blumonSim.pedidas(REVERSO).isEmpty())
        assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
        assertTrue(servidorSim.pedidas(RESULTADO).isEmpty())
    }

    @Test(timeout = T) fun `70 reverso sin respuesta y luego TX_007 deja por confirmar y reporta PUDO_ENTRAR`() = reversoSinRespuesta()

    private fun reversoSinRespuesta() = prueba(Ambiente.PRODUCCION) {
        val s = venta(70)
        assertEquals(inicio(s) + RevisandoConElBanco + PorConfirmar(s.importeCentavos), esperar(callaLaVenta(s)))
        pudoEntrarSinConfirmar(s, reversos = 2)
        // F: el SERVIDOR siempre se entera de que un reverso pudo entrar (SIN_RESPUESTA).
        assertTrue(s.id in recuperar())
        assertEquals(listOf("SIN_RESPUESTA"), servidorSim.pedidas(RESULTADO, s.id).map { it.reportado })
        val f = fila(s.id)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSING, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test(timeout = T) fun `90 REVERSADA y el SERVIDOR acusa contradiction deja por confirmar`() = contradiccion()

    private fun contradiccion() = prueba(Ambiente.PRODUCCION) {
        val s = venta(90, EscenarioDelServidor.ACUSA_CONTRADICCION)
        assertEquals(inicio(s) + RevisandoConElBanco + PorConfirmar(s.importeCentavos), esperar(callaLaVenta(s)))
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
        assertEquals(EstadoDelReversoEnServidor.CONTRADICTION, f.estadoEnServidor)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(1, blumonSim.pedidas(REVERSO, s.id).size)
    }

    // ───────────── La cancelación, de punta a punta ─────────────

    @Test(timeout = T) fun `cancelacion exitosa da Cancelada y la venta sigue APROBADO`() = cancelacionExitosa()

    private fun cancelacionExitosa() = prueba(Ambiente.PRODUCCION) {
        val s = ventaAprobada(0)
        val c = cancelacionDe(s)
        val eventos = esperar(cancelar(c))
        val op = requireNotNull(blumonSim.folioDe(c.id))
        assertEquals(listOf(Iniciado(c.id, identidad, Operacion.CANCELACION, s.importeCentavos), PideTarjeta, Autorizando, Cancelada(op)), eventos)
        val f = fila(c.id)
        assertEquals(EstadoDelIntento.CANCELADA, f.estado)
        assertEquals(op, f.operationNumber)
        assertEquals(s.id.valor, f.operacionOriginal)
        assertEquals(EstadoDelIntento.APROBADO, fila(s.id).estado)
        unaCancelacion(s, c, EscenarioDeBlumon.APROBADO)
        assertEquals(listOf(Operacion.VENTA, Operacion.CANCELACION), lector.lecturas)
        sinReversoNiServidor()
    }

    @Test(timeout = T) fun `11 preflight TX_024 no deja cancelar ni lee la tarjeta`() = cancelacionNoPermitida()

    private fun cancelacionNoPermitida() = prueba(Ambiente.PRODUCCION) {
        val s = ventaAprobada(11)
        val c = cancelacionDe(s)
        val eventos = esperar(cancelar(c))
        assertEquals(
            listOf(Iniciado(c.id, identidad, Operacion.CANCELACION, s.importeCentavos), NoSalioAlBanco(MotivoNoSalio.CANCELACION_NO_PERMITIDA, null)),
            eventos,
        )
        assertEquals(listOf(EscenarioDeBlumon.CANCELACION_NO_PERMITIDA), blumonSim.pedidas(PREFLIGHT).map { it.escenario })
        assertTrue("cero POST de cancelación", blumonSim.pedidas(CANCELACION).isEmpty())
        assertEquals("cero lecturas de tarjeta", listOf(Operacion.VENTA), lector.lecturas)
        val f = fila(c.id)
        assertEquals(EstadoDelIntento.NO_SALIO, f.estado)
        assertEquals(MotivoNoSalio.CANCELACION_NO_PERMITIDA, f.motivoNoSalio)
        assertNull(f.contadorUsado)
        assertEquals(EstadoDelIntento.APROBADO, fila(s.id).estado)
        sinReversoNiServidor()
    }

    @Test(timeout = T) fun `12 la cancelacion sale y nadie contesta da PorConfirmar sin reversos`() = cancelacionSinRespuesta()

    private fun cancelacionSinRespuesta() = prueba(Ambiente.PRODUCCION) {
        val s = ventaAprobada(12)
        val c = cancelacionDe(s)
        val eventos = cancelar(c)
        llego(CANCELACION, c.id)
        avanzarA(t0(c.id) + relojes.peorCasoCajera)
        assertEquals(
            listOf(Iniciado(c.id, identidad, Operacion.CANCELACION, s.importeCentavos), PideTarjeta, Autorizando, PorConfirmar(s.importeCentavos)),
            esperar(eventos),
        )
        esperarAlMotor() // la fila pasa a NO_SE cuando vence la llamada, después de soltar a la cajera
        assertEquals(EstadoDelIntento.NO_SE, fila(c.id).estado)
        assertEquals(EstadoDelIntento.APROBADO, fila(s.id).estado)
        unaCancelacion(s, c, EscenarioDeBlumon.CANCELACION_SIN_RESPUESTA)
        sinReversoNiServidor()
    }

    @Test(timeout = T) fun `13 exito con el eco de la VENTA nunca es Cancelada`() = cancelacionConEcoDeLaVenta()

    private fun cancelacionConEcoDeLaVenta() = cancelacionEnDuda(EscenarioDeBlumon.CANCELACION_CON_ECO_DE_LA_VENTA)

    @Test(timeout = T) fun `14 exito de la cancelacion con un bloque error nunca es Cancelada`() = cancelacionConError()

    private fun cancelacionConError() = cancelacionEnDuda(EscenarioDeBlumon.CANCELACION_CON_ERROR)

    /** La cancelación SÍ salió y el simulador contestó el sobre de [escenario]; ese sobre no es evidencia: `PorConfirmar`. */
    private fun cancelacionEnDuda(escenario: EscenarioDeBlumon) = prueba(Ambiente.PRODUCCION) {
        val s = ventaAprobada(escenario.centavos)
        val c = cancelacionDe(s)
        val eventos = esperar(cancelar(c))
        assertEquals(
            listOf(Iniciado(c.id, identidad, Operacion.CANCELACION, s.importeCentavos), PideTarjeta, Autorizando, PorConfirmar(s.importeCentavos)),
            eventos,
        )
        assertEquals(EstadoDelIntento.NO_SE, fila(c.id).estado)
        assertNull(fila(c.id).operationNumber)
        assertEquals(EstadoDelIntento.APROBADO, fila(s.id).estado)
        unaCancelacion(s, c, escenario)
        assertEquals("el motor SÍ recibió el sobre del escenario", listOf(200), actual.espia.cancelaciones.map { (it as? Respuesta.Http)?.codigo })
        sinReversoNiServidor()
    }

    // ───────────── Apagón, un solo POST y el transporte ─────────────

    /**
     * Se corta tras `ENVIADO`: otro proceso, con su PROPIA pila (cliente, transporte, lector y SERVIDOR), abre la MISMA libreta
     * y `recuperar` la deja en `NO_SE`, sin un solo reverso.
     */
    @Test(timeout = T) fun `apagon a media venta`() = apagonAMediaVenta()

    private fun apagonAMediaVenta() = prueba(Ambiente.PRODUCCION) {
        val s = venta(20)
        cobrar(s) // quien escucha muere con el proceso
        llego(VENTA, s.id)
        assertEquals(EstadoDelIntento.ENVIADO, fila(s.id).estado)
        val muerto = actual
        otroProceso()
        assertTrue("el proceso nuevo arma su propia pila", muerto.envio !== actual.envio && muerto.lector !== actual.lector)
        assertEquals("después del apagón y antes de recuperar", EstadoDelIntento.ENVIADO, fila(s.id).estado)
        assertEquals(listOf(s.id), recuperar())
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(listOf<EventoDeCobro>(PorConfirmar(s.importeCentavos)), esperar(cobrar(s)))
        assertTrue("el proceso nuevo no lee la tarjeta", actual.lector.lecturas.isEmpty())
        assertEquals(1, blumonSim.pedidas(VENTA, s.id).size)
        sinReversoNiServidor()
    }

    /**
     * Dos colectores del mismo folio nuevo a la vez: la lectura de la tarjeta espera a que los DOS hayan visto `Iniciado`, así
     * que los dos ven la historia completa del mismo intento. Después, otro `cobrar` sólo consulta la lápida.
     */
    @Test(timeout = T) fun `una sola venta por intento`() = unaSolaVentaPorIntento()

    private fun unaSolaVentaPorIntento() = prueba(Ambiente.PRODUCCION) {
        val s = venta(0)
        val vieronIniciado = AtomicInteger()
        val losDos = CompletableDeferred<Unit>()
        val alVer: (EventoDeCobro) -> Unit = { if (it is Iniciado && vieronIniciado.incrementAndGet() == 2) losDos.complete(Unit) }
        lector.antesDeLeer = { withContext(Dispatchers.Default) { withTimeoutOrNull(BARRERA) { losDos.await() } } }
        val a = cobrar(s, alVer)
        val b = cobrar(s, alVer)
        val (ea, eb) = esperar(a) to esperar(b)
        assertTrue("la barrera «los dos colectores ven Iniciado» no se liberó en $BARRERA ms", losDos.isCompleted)
        val op = requireNotNull(blumonSim.folioDe(s.id))
        val completa = inicio(s) + aprobadoVisa(op)
        assertEquals(completa, ea)
        assertEquals(completa, eb)
        esperarAlMotor()
        assertEquals("el folio es de un solo uso: otra vez sólo consulta", listOf<EventoDeCobro>(aprobadoVisa(op)), esperar(cobrar(s)))
        assertEquals(1, blumonSim.pedidas(VENTA, s.id).size)
        assertEquals(listOf(Operacion.VENTA), lector.lecturas)
    }

    /**
     * La barrera va en el `SocketConPlazo` (su gancho antes de cada `write`), DESPUÉS del chequeo con que arranca el transporte:
     * el POST del reverso ya pasó `noEmpezarDespuesDe` y se detiene dentro del socket. Mientras, la venta retenida contesta (sólo
     * queda como evidencia tardía) y su llamada termina: cuando se mide, no hay otro envío en vuelo que pise el contador. El
     * techo vence con el reverso detenido; al soltarlo, el socket revisa el plazo y no escribe ni un byte de la petición (ni
     * headers ni cuerpo). Con estos relojes nada más vence entre el plazo de la venta y el techo.
     */
    @Test(timeout = T) fun `bloqueo despues del chequeo del transporte no escribe`() = bloqueoDespuesDelChequeo()

    private fun bloqueoDespuesDelChequeo() = prueba(Ambiente.PRODUCCION, RELOJES.copy(esperaReverso = 40_000, peorCasoCajera = 60_000)) {
        val s = venta(55)
        val eventos = cobrar(s)
        llego(VENTA, s.id)
        val enElSocket = CompletableDeferred<Long>()
        val soltar = CompletableDeferred<Unit>()
        val primera = AtomicBoolean(true)
        envio.ganchoAntesDeEscribir = { escritos ->
            if (primera.compareAndSet(true, false)) {
                enElSocket.complete(escritos)
                runBlocking { withTimeoutOrNull(BARRERA) { soltar.await() } }
            }
        }
        val t0 = t0(s.id)
        avanzarA(t0 + relojes.esperaRespuesta) // la venta calla: arbitraje, permiso y el POST del reverso
        assertEquals("pasó el chequeo y aún no escribe", 0L, barrera("el reverso se detiene en el socket", enElSocket))
        val guardado = CompletableDeferred<Unit>()
        actual.ganchos["guardarAprobacion"] = { guardado.complete(Unit) }
        blumonSim.ventaTardia.complete(Unit)
        barrera("la venta retenida termina", guardado)
        assertTrue("control: el contador sí mide (la venta escribió su petición)", envio.bytesDelUltimoEnvio > 0)
        avanzarA(t0 + relojes.techoDelReverso) // el techo vence con el POST detenido dentro del socket
        soltar.complete(Unit)
        assertEquals(inicio(s) + RevisandoConElBanco + PorConfirmar(s.importeCentavos), esperar(eventos))
        assertEquals("ni un byte de la petición", 0L, envio.bytesDelUltimoEnvio)
        assertEquals(
            "de ese intento, el simulador sólo recibió su venta",
            listOf(VENTA), blumonSim.registro.value.filter { it.reference == s.id.valor }.map { it.ruta },
        )
        assertTrue("cero reversos de cualquier folio", blumonSim.pedidas(REVERSO).isEmpty())
        val f = fila(s.id)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertFalse(f.pudoEntrar)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertEquals(blumonSim.folioDe(s.id), f.evidenciaTardiaOperacion)
        assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
        assertTrue(servidorSim.pedidas(RESULTADO).isEmpty())
    }

    // ───────────── Privacidad ─────────────

    /**
     * Todos los escenarios, con los registros capturados (`java.util.logging`, la consola y el `Log` de Android), más una sonda
     * de la sesión (las respuestas de token, `init` y llaves que `ClienteBlumon` consume adentro) y excepciones provocadas de
     * verdad (la libreta y el folio rechazando el PAN y la banda). Se buscan el PAN de prueba (literal y por rachas de 12
     * dígitos, con la regla de `Enmascarar`), la banda (tag 57), el token, la IPEK, la credencial de Blumon (literal y en su
     * Basic codificado), el JWT y el texto libre del banco, por tramo:
     * - [ARCHIVO]: los bytes crudos de cada archivo de la libreta (el principal, con sus páginas libres, y su diario; va en
 *   `TRUNCATE`, sin WAL);
     * - [LIBRETA]: cada columna de `intentos` y `contadores`;
     * - [REGISTROS], [EVENTOS] (también solicitudes y resultados del corte), [RESPUESTAS], [TO_STRING] (sesión, cuerpos y
     *   congelados), [TARDIAS], [EXCEPCIONES] (con toda su cadena de causas), [SERVIDOR] (cada cuerpo que recibió) y
     *   [SIMULADORES] (sus registros).
     * Cero apariciones; y un control positivo por tramo.
     */
    @Test(timeout = 600_000) fun `ningun dato sensible en ninguna salida`() {
        val salidas = CopyOnWriteArrayList<Pair<String, String>>()
        capturarRegistros(salidas) {
            barrido = salidas
            try {
                escenarios.forEach { it() }
                prueba(Ambiente.PRODUCCION) {
                    val s = ventaAprobada(0)
                    assertEquals(ResultadoDelCorte.SIN_INTENTO_VIVO, cortar(s))
                    assertEquals(ResultadoDelCorte.CORTADO, cortar(venta(0)))
                    sondaDeLaSesion()
                    provocarExcepciones(s)
                }
            } finally {
                barrido = null
            }
        }
        val secretos = listOf(
            LecturasDePrueba.PAN_VISA, LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA), TOKEN, IPEK_HEX, IPEK_HEX.lowercase(),
            USUARIO, CONTRASENA, BASIC, JWT_FALSO, TEXTO_DEL_BANCO,
        )
        assertEquals("tramos sin revisar", emptySet<String>(), salidas.map { it.first }.toSet() - TRAMOS.toSet())
        // El mensaje dice el tramo y el número del secreto, nunca el valor ni la salida.
        for (tramo in TRAMOS) {
            val textos = salidas.filter { it.first == tramo }.map { it.second }
            secretos.forEachIndexed { i, secreto -> assertEquals("secreto #$i en «$tramo»", 0, textos.count { secreto in it }) }
            assertEquals("racha del PAN en «$tramo»", 0, textos.count { Enmascarar.contieneDatoDeLaTarjeta(it, LecturasDePrueba.PAN_VISA) })
        }
        // Controles positivos: el barrido sí vio cada tramo.
        fun hay(tramo: String, p: (String) -> Boolean) = salidas.any { it.first == tramo && p(it.second) }
        assertTrue("archivo crudo", hay(ARCHIVO) { it.startsWith("SQLite format 3") })
        assertTrue("columnas de la libreta", hay(LIBRETA) { it == EstadoDelIntento.CANCELADA.name })
        assertTrue("registros", hay(REGISTROS) { "MockWebServer" in it })
        assertTrue("eventos de la cancelación", hay(EVENTOS) { "Cancelada(" in it })
        assertTrue("resultados del corte", hay(EVENTOS) { it == ResultadoDelCorte.CORTADO.name })
        assertTrue("respuestas de Blumon", hay(RESPUESTAS) { it.startsWith("Http(codigo=") })
        assertTrue("lecturas", hay(TO_STRING) { it.startsWith("Leida(") })
        assertTrue("sesión y cuerpos", hay(TO_STRING) { it.startsWith("SesionBlumon(") } && hay(TO_STRING) { it.startsWith("CuerpoCongelado(") })
        assertTrue("finales tardíos", hay(TARDIAS) { "NoSeCobro" in it && "AttemptId" in it })
        assertTrue("excepciones provocadas", hay(EXCEPCIONES) { "IllegalArgumentException" in it })
        assertTrue("cuerpos de /resultado", hay(SERVIDOR) { "reversadoEn" in it })
        assertTrue("registros de los simuladores", hay(SIMULADORES) { "Pedida(" in it })
    }

    private val escenarios: List<() -> Unit>
        get() = listOf(
            ::aprobado, ::rechazo05, ::parcial, ::codigo68, ::simulationTrue, ::aprobadoSinEco, ::aprobadoConError,
            ::aprobadoConErrorSuelto, ::rechazoConDatos, ::rechazoConHttpDeTransporte, ::silencioYReversada, ::reversadaSinEco,
            ::silencioYTx007, ::rq006DosVeces, ::tardioAntesDelArbitraje, ::tardioDespuesDelArbitraje, ::servidorYaCobrado,
            ::reversoSinRespuesta, ::contradiccion, ::cancelacionExitosa, ::cancelacionNoPermitida, ::cancelacionSinRespuesta,
            ::cancelacionConEcoDeLaVenta, ::cancelacionConError, ::apagonAMediaVenta, ::unaSolaVentaPorIntento,
            ::bloqueoDespuesDelChequeo,
        )

    /** La consola, `java.util.logging` (todos los niveles) y el `Log` de Android, mientras corre [bloque]. */
    private fun capturarRegistros(salidas: MutableList<Pair<String, String>>, bloque: () -> Unit) {
        val raiz = Logger.getLogger("")
        val nivel = raiz.level
        val registrador = object : Handler() {
            override fun publish(r: LogRecord) {
                salidas += REGISTROS to "${r.loggerName} ${r.message} ${r.parameters?.joinToString()} ${r.thrown?.stackTraceToString()}"
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val salida = System.out
        val errores = System.err
        val consola = ByteArrayOutputStream()
        raiz.level = Level.ALL
        raiz.addHandler(registrador)
        System.setOut(PrintStream(consola, true))
        System.setErr(PrintStream(consola, true))
        try {
            bloque()
        } finally {
            System.setOut(salida)
            System.setErr(errores)
            raiz.removeHandler(registrador)
            raiz.level = nivel
            salidas += REGISTROS to consola.toString()
            ShadowLog.getLogs().forEach { salidas += REGISTROS to "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString()}" }
        }
    }

    /** Una barrera con tope de pared: si no se libera, la prueba falla pronto y con su nombre, en vez de colgarse. */
    private fun <T> barrera(nombre: String, d: Deferred<T>): T =
        runBlocking { withTimeoutOrNull(BARRERA) { d.await() } } ?: throw AssertionError("la barrera «$nombre» no se liberó en $BARRERA ms")

    // ───────────── El mundo ─────────────

    /** El cuerpo de la prueba y, después, la invariante de lo que salió. Si el cuerpo falla, gana su falla. */
    private fun prueba(ambiente: Ambiente, relojes: Relojes = RELOJES, bloque: Mundo.() -> Unit) {
        val m = Mundo(IDENTIDAD.copy(ambiente = ambiente), relojes, barrido ?: CopyOnWriteArrayList())
        try {
            m.bloque()
        } catch (t: Throwable) {
            runCatching { m.cerrar() }
            throw t
        }
        m.cerrar()
        m.revisarLoQueSalio()
    }

    /**
     * Un mundo por prueba: los dos simuladores y un motor con su pila real sobre una libreta de archivo. [otroProceso] abre otro
     * motor, con su PROPIA pila, sobre el MISMO archivo, como tras un apagón. Todo lo que sale va a [salidas], por tramo.
     */
    private inner class Mundo(val identidad: Identidad, val relojes: Relojes, val salidas: MutableList<Pair<String, String>>) {
        val reloj = TestCoroutineScheduler().apply { advanceTimeBy(INICIO) }
        private val hilo = UnconfinedTestDispatcher(reloj)
        val ahora: () -> Long = { reloj.currentTime }
        val blumonSim = BlumonSimuladoDispatcher(identidad, TOKEN, IPEK)
        val servidorSim = ServidorSimuladoDispatcher { salidas += SERVIDOR to it }
        private val blumonHttp = MockWebServer().apply {
            useHttps(DEL_SERVIDOR.sslSocketFactory(), false)
            dispatcher = blumonSim
            start(LOCALHOST, 0)
        }
        private val servidorHttp = MockWebServer().apply {
            dispatcher = servidorSim
            arrancarEnIpv4() // 6ª parte: por la dirección literal, no por `localhost` (ver fixtures/Local.kt)
        }
        private val endpoints = Endpoints("https://localhost:${blumonHttp.port}/oauth/token", "https://localhost:${blumonHttp.port}/")
        private val archivo = File(carpeta.root, "${UUID.randomUUID()}.db")
        val io = IoConCompuerta()
        private val excepciones = CopyOnWriteArrayList<Throwable>()
        private val motores = mutableListOf<Motor>()

        /** Los folios de venta que la prueba mandó cobrar: cada uno sale al banco a lo más una vez. */
        private val ventasPedidas = ConcurrentHashMap.newKeySet<String>()
        /** Bloque 1b · fila 11: un toque nuevo por venta, también tras reiniciar el proceso. */
        val toques = AtomicInteger()
        var actual = Motor(Libreta.PROCESO_ID)
        val envio get() = actual.envio
        val lector get() = actual.lector

        /** Un proceso: su `fondo`, su pila (transporte, cliente de Blumon, cliente del SERVIDOR y lector) y su libreta. */
        inner class Motor(proceso: String) {
            val fondo = CoroutineScope(
                SupervisorJob() + hilo + CoroutineExceptionHandler { _, e -> excepciones += e; salidas += EXCEPCIONES to e.stackTraceToString() },
            )
            val envio = EnvioUnico(endpoints, ahora, DEL_CLIENTE.trustManager, object : Dns {
                override fun lookup(hostname: String) = listOf(LOCALHOST)
            })
            val espia = Espia(ClienteBlumon(endpoints, envio, CREDENCIAL, identidad, SecureRandom())) { t, x -> salidas += t to x }
            private val servidor = ReversoDelServidorHttp(servidorHttp.baseLiteral(), JWT)
            val lector = LectorDePrueba(toques) { t, x -> salidas += t to x }

            /** Corren DENTRO de la transacción de esa operación de la libreta, antes del commit. */
            val ganchos = ConcurrentHashMap<String, () -> Unit>()
            val db = LibretaDatabase.constructor(context, archivo).allowMainThreadQueries().build()
            val libreta = Libreta(db, ahora, { PARED }, proceso) { op -> ganchos[op]?.invoke() }
            val motor = MotorDeCobro(
                identidad, lector, CON_RED, espia, servidor, libreta, ColaDeResultados(libreta, servidor), HUELLA,
                CatalogoDeRechazos.BLUMON, relojes, fondo, ahora, { PARED }, io,
            )
            private val tardias = CopyOnWriteArrayList<Pair<AttemptId, EventoDeCobro>>()
            private val escucha = CoroutineScope(hilo).launch(start = CoroutineStart.UNDISPATCHED) {
                motor.resolucionesTardias.collect { tardias += it }
            }

            init {
                motores += this
            }

            /** El proceso muere: su `fondo` se detiene, y lo que deja es la libreta (sus tablas y los bytes crudos de sus archivos). */
            fun apagar() {
                if (!db.isOpen) return
                runBlocking { fondo.coroutineContext.job.cancelAndJoin() }
                escucha.cancel()
                salidas += TARDIAS to tardias.toString()
                for (tabla in listOf("intentos", "contadores")) {
                    db.openHelper.readableDatabase.query("SELECT * FROM $tabla").use { c ->
                        while (c.moveToNext()) for (i in 0 until c.columnCount) salidas += LIBRETA to c.getString(i).orEmpty()
                    }
                }
                barrerArchivos()
                db.close()
                barrerArchivos()
            }
        }

        /**
         * Los bytes crudos de TODOS los archivos de la libreta: el principal (con sus páginas libres) y su diario. La libreta va
         * en `journal_mode=TRUNCATE` a propósito (`LibretaDatabase`), así que no hay WAL: su diario es `-journal`, que queda
         * vacío en cada commit. Si algún día hubiera `-wal`, también se barre.
         */
        private fun barrerArchivos() {
            archivo.parentFile!!.listFiles { f -> f.name.startsWith(archivo.name) }!!.forEach { f ->
                salidas += ARCHIVO to String(f.readBytes(), Charsets.ISO_8859_1)
            }
        }

        fun venta(centavos: Int, servidor: EscenarioDelServidor = EscenarioDelServidor.CONCEDE_Y_ACUSA): SolicitudDeVenta {
            val s = SolicitudDeVenta(folio(servidor), VENUE, 10_000L + centavos, Origen.VINCULADO_AL_POS)
            if (servidor == EscenarioDelServidor.YA_COBRADO) servidorSim.cobrosRegistrados[s.id.valor] = s.importeCentavos
            return s
        }

        fun cancelacionDe(s: SolicitudDeVenta) = SolicitudDeCancelacion(AttemptId.nuevo(), VENUE, s.id)

        fun cobrar(s: SolicitudDeVenta, alVer: (EventoDeCobro) -> Unit = {}): Deferred<List<EventoDeCobro>> {
            salidas += EVENTOS to s.toString()
            ventasPedidas += s.id.valor
            val m = actual.motor
            return actual.fondo.async { m.cobrar(s).onEach(alVer).toList() }
        }

        fun cancelar(c: SolicitudDeCancelacion): Deferred<List<EventoDeCobro>> {
            salidas += EVENTOS to c.toString()
            val m = actual.motor
            return actual.fondo.async { m.cancelar(c).toList() }
        }

        fun <T> esperar(d: Deferred<T>): T = runBlocking { d.await() }.also { salidas += EVENTOS to it.toString() }

        /** Una venta que el motor aprobó, con su lápida ya puesta. */
        fun ventaAprobada(centavos: Int): SolicitudDeVenta {
            val s = venta(centavos)
            val eventos = esperar(cobrar(s))
            assertEquals(aprobadoVisa(blumonSim.folioDe(s.id)), eventos.last())
            esperarAlMotor()
            return s
        }

        /** La venta sale y calla: cuando llega al simulador, el reloj avanza al plazo de su respuesta. */
        fun callaLaVenta(s: SolicitudDeVenta): Deferred<List<EventoDeCobro>> {
            val eventos = cobrar(s)
            llego(VENTA, s.id)
            avanzarA(t0(s.id) + relojes.esperaRespuesta)
            return eventos
        }

        fun recuperar(): List<AttemptId> = esperar(actual.fondo.async { actual.motor.recuperar() })

        fun cortar(s: Solicitud): ResultadoDelCorte = esperar(actual.fondo.async { actual.motor.cortar(s) })

        /** El reloj virtual hasta [t]: vence (en este hilo) todo lo que tocaba hasta entonces. */
        fun avanzarA(t: Long) {
            reloj.advanceTimeBy(t - reloj.currentTime)
            reloj.runCurrent()
        }

        /** La barrera de llegada: la petición ya está en el simulador de Blumon. */
        fun llego(ruta: String, id: AttemptId) {
            runBlocking { withTimeoutOrNull(BARRERA) { blumonSim.esperar(ruta, id) } }
                ?: throw AssertionError("la barrera «llegó la petición a $ruta» no se liberó en $BARRERA ms")
        }

        /** Espera a que termine todo lo que el motor tiene en `fondo` (sólo sin peticiones colgadas para siempre). */
        fun esperarAlMotor() = runBlocking { actual.fondo.coroutineContext.job.children.toList().forEach { it.join() } }

        fun otroProceso(): Motor {
            actual.apagar()
            return Motor("proceso-tras-el-apagon").also { actual = it }
        }

        fun fila(id: AttemptId) = requireNotNull(actual.libreta.buscar(id))

        fun t0(id: AttemptId) = requireNotNull(fila(id).enviadoEnMs)

        fun inicio(s: SolicitudDeVenta): List<EventoDeCobro> =
            listOf(Iniciado(s.id, identidad, Operacion.VENTA, s.importeCentavos), PideTarjeta, Autorizando)

        fun sinReversoNiServidor() {
            assertTrue("cero reversos", blumonSim.pedidas(REVERSO).isEmpty())
            assertTrue("cero permisos y cero reportes", servidorSim.registro.value.isEmpty())
        }

        /** Exactamente UNA petición a cancelar: el folio bancario de la venta en la ruta, el de la CANCELACIÓN en el cuerpo, y la contestó [escenario]. */
        fun unaCancelacion(s: SolicitudDeVenta, c: SolicitudDeCancelacion, escenario: EscenarioDeBlumon) {
            val ruta = CANCELACION + requireNotNull(fila(s.id).operationNumber)
            assertEquals(listOf(BlumonSimuladoDispatcher.Pedida(ruta, c.id.valor, escenario)), blumonSim.pedidas(CANCELACION))
        }

        /** E con V: el `REVERSADA` y el acuse `reversed` del SERVIDOR, en una sola fila, tras un permiso y una lectura. */
        fun reversadaYAcusada(s: SolicitudDeVenta, reversos: Int = 1) {
            val f = fila(s.id)
            assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
            assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
            assertEquals(EstadoDelReversoEnServidor.REVERSED, f.estadoEnServidor)
            assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
            assertEquals(reversos, blumonSim.pedidas(REVERSO, s.id).size)
            assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
            assertEquals(listOf("REVERSADA"), servidorSim.pedidas(RESULTADO, s.id).map { it.reportado })
            assertEquals(listOf(Operacion.VENTA), lector.lecturas)
        }

        /** D: un reverso que no se pudo creer y luego `TX_007`: `NO_SE`, y se reporta `PUDO_ENTRAR`. */
        fun pudoEntrarSinConfirmar(s: SolicitudDeVenta, reversos: Int) {
            val f = fila(s.id)
            assertEquals(EstadoDelIntento.NO_SE, f.estado)
            assertEquals(EstadoDelReverso.RECHAZADO, f.reverso)
            assertTrue(f.pudoEntrar)
            assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
            assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
            assertEquals("TX_007", f.ultimoCodigoReverso)
            assertEquals(reversos, blumonSim.pedidas(REVERSO, s.id).size)
            assertEquals(1, servidorSim.pedidas(INTENCION, s.id).size)
        }

        /** Barrido: las respuestas de la sesión que `ClienteBlumon` consume adentro, por el MISMO transporte y simulador. */
        fun sondaDeLaSesion() = runBlocking {
            val e = actual.envio
            fun cuerpo(vararg campos: Pair<String, String>) =
                CuerpoDeBlumon.desde(JsonObject().apply { campos.forEach { (k, v) -> addProperty(k, v) } })
            val portador = "Bearer $TOKEN"
            val respuestas = listOf(
                e.post(endpoints.tokener, "Basic $BASIC", cuerpo(), 5.seconds, null),
                e.postFormulario(endpoints.tokener, "Basic $BASIC", mapOf("grant_type" to "password"), 5.seconds),
                e.post(endpoints.core + "device/init", portador, cuerpo("serialNumber" to identidad.serieBlumon), 5.seconds, null),
                e.post(endpoints.core + "device/getKey", portador, cuerpo("posId" to identidad.posId), 5.seconds, null),
                e.post(
                    endpoints.core + "device/initDukptKeys", portador,
                    cuerpo("posId" to identidad.posId, "rsa" to BlumonSimuladoDispatcher.cifrarParaLlaves(ByteArray(16) { 7 })), 5.seconds, null,
                ),
            )
            assertTrue("la sonda recibió las cinco respuestas", respuestas.all { it is Respuesta.Http && it.codigo == 200 })
            respuestas.forEach { salidas += RESPUESTAS to it.toString() }
        }

        /** Barrido: excepciones reales, con el PAN y la banda como entrada; su mensaje y toda su cadena de causas. */
        fun provocarExcepciones(s: SolicitudDeVenta) {
            val banda = LecturasDePrueba.banda(LecturasDePrueba.PAN_VISA)
            val provocadas = listOf(
                runCatching { AttemptId(LecturasDePrueba.PAN_VISA) },
                runCatching { actual.libreta.transicionar(s.id, EstadoDelIntento.APROBADO) { it.copy(bin = LecturasDePrueba.PAN_VISA) } },
                runCatching { actual.libreta.transicionar(s.id, EstadoDelIntento.APROBADO) { it.copy(autorizacion = banda) } },
            ).map { requireNotNull(it.exceptionOrNull()) { "el folio y la libreta tenían que rechazar el dato" } }
            for (e in provocadas) {
                generateSequence(e) { it.cause }.forEach { salidas += EXCEPCIONES to "$it ${it.message}" }
                salidas += EXCEPCIONES to e.stackTraceToString()
            }
            assertEquals("la fila no cambió", EstadoDelIntento.APROBADO, fila(s.id).estado)
        }

        fun cerrar() {
            blumonSim.soltarTodo()
            io.soltar()
            motores.forEach { it.apagar() }
            blumonHttp.shutdown()
            servidorHttp.shutdown()
            salidas += SIMULADORES to blumonSim.registro.value.toString()
            salidas += SIMULADORES to servidorSim.registro.value.toString()
        }

        /**
         * La invariante de TODA prueba (revisor I1, Codex 1 y 2): por folio, a lo más UNA venta, UNA cancelación y UN permiso, y
         * a lo más 3 reversos (lo más que pide un escenario); las ventas que salieron son las que se pidieron (atrapa también un
         * segundo envío con OTRO folio); nadie habló con otro verbo; y ninguna excepción escapó de `fondo`.
         */
        fun revisarLoQueSalio() {
            fun porFolio(ruta: String) = blumonSim.pedidas(ruta).groupingBy { it.reference }.eachCount()
            porFolio(VENTA).forEach { (ref, n) -> assertEquals("POST de venta del folio $ref", 1, n) }
            porFolio(CANCELACION).forEach { (ref, n) -> assertEquals("POST de cancelación del folio $ref", 1, n) }
            porFolio(REVERSO).forEach { (ref, n) -> assertTrue("reversos del folio $ref: $n", n <= 3) }
            assertEquals("ventas que salieron contra ventas pedidas", ventasPedidas.size, blumonSim.pedidas(VENTA).size)
            assertEquals("folios de las ventas que salieron", ventasPedidas.toSet(), blumonSim.pedidas(VENTA).map { it.reference }.toSet())
            servidorSim.pedidas(INTENCION).groupingBy { it.attemptId }.eachCount()
                .forEach { (id, n) -> assertEquals("permisos del folio $id", 1, n) }
            assertEquals("peticiones con otro verbo", 0, blumonSim.verbosIncorrectos.get() + servidorSim.verbosIncorrectos.get())
            assertTrue("excepciones que escaparon de fondo: ${excepciones.size}", excepciones.isEmpty())
        }
    }

    // ───────────── Las piezas de prueba ─────────────

    /** La tarjeta de prueba (Visa de plástico). La cancelación es otra lectura de la MISMA tarjeta: otro contador (9F36). */
    private class LectorDePrueba(private val toques: AtomicInteger, private val anotar: (String, String) -> Unit) : LectorDeTarjeta {
        val lecturas = CopyOnWriteArrayList<Operacion>()

        /** Una barrera antes de entregar la lectura (una prueba la usa). */
        @Volatile var antesDeLeer: suspend () -> Unit = {}

        override suspend fun leer(operacion: Operacion, importeCentavos: Long, espera: Long): ResultadoDeLectura {
            lecturas += operacion
            antesDeLeer()
            val lectura = if (operacion == Operacion.VENTA) LecturasDePrueba.visaPlasticoVenta(importeCentavos).toque(toques.incrementAndGet())
            else LecturasDePrueba.visaPlasticoCancelacion(importeCentavos).con(tags = mapOf(0x9F36 to "0002"))
            // Para el barrido: lo que lleva la tarjeta (la lectura y su resultado) tampoco se imprime.
            return ResultadoDeLectura.Leida(lectura).also { anotar(TO_STRING, lectura.toString()); anotar(TO_STRING, it.toString()) }
        }
    }

    /**
     * El cliente REAL de Blumon; sólo anota, para el barrido, el `toString()` de cada `Respuesta` (la devuelta y la del buzón) y
     * de lo que entra y sale de él: la sesión, el cuerpo y el congelado.
     */
    private class Espia(private val d: PuertoBlumon, private val anotar: (String, String) -> Unit) : PuertoBlumon by d {
        /** Lo que regresó cada llamada de cancelar, en orden. */
        val cancelaciones = CopyOnWriteArrayList<Respuesta>()

        override suspend fun iniciarSesion(espera: Duration) = d.iniciarSesion(espera).also { anotar(TO_STRING, it.toString()) }

        override suspend fun cobrar(sesion: SesionBlumon, cuerpo: CuerpoDeBlumon, espera: Duration, noEmpezarDespuesDe: Long): Respuesta {
            anotar(TO_STRING, sesion.toString())
            anotar(TO_STRING, cuerpo.toString())
            return d.cobrar(sesion, cuerpo, espera, noEmpezarDespuesDe).also { anotar(RESPUESTAS, it.toString()) }
        }

        override suspend fun reversar(congelado: CuerpoCongelado, espera: Duration, noEmpezarDespuesDe: Long, alLeer: (Respuesta) -> Unit): Respuesta {
            anotar(TO_STRING, congelado.toString())
            return d.reversar(congelado, espera, noEmpezarDespuesDe) { anotar(RESPUESTAS, it.toString()); alLeer(it) }
                .also { anotar(RESPUESTAS, it.toString()) }
        }

        override suspend fun validarCancelacion(sesion: SesionBlumon, operationNumber: Long, espera: Duration, alLeer: (Respuesta) -> Unit): Respuesta {
            anotar(TO_STRING, sesion.toString())
            return d.validarCancelacion(sesion, operationNumber, espera) { anotar(RESPUESTAS, it.toString()); alLeer(it) }
                .also { anotar(RESPUESTAS, it.toString()) }
        }

        override suspend fun cancelar(
            sesion: SesionBlumon, operationNumber: Long, cuerpo: CuerpoDeBlumon, espera: Duration, alLeer: (Respuesta) -> Unit,
        ): Respuesta {
            anotar(TO_STRING, sesion.toString())
            anotar(TO_STRING, cuerpo.toString())
            return d.cancelar(sesion, operationNumber, cuerpo, espera) { anotar(RESPUESTAS, it.toString()); alLeer(it) }
                .also { anotar(RESPUESTAS, it.toString()); cancelaciones += it }
        }
    }

    /** El `io` de la libreta: `Dispatchers.IO`, con una compuerta que retiene la SIGUIENTE tarea (una sola) hasta [soltar]. */
    private class IoConCompuerta : CoroutineDispatcher() {
        private var armada = false
        private var retenida: Pair<CoroutineContext, Runnable>? = null
        val retuvo = CompletableDeferred<Unit>()

        @Synchronized fun retenerLaSiguiente() {
            armada = true
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (armada) {
                    armada = false
                    retenida = context to block
                    retuvo.complete(Unit)
                    return
                }
            }
            Dispatchers.IO.dispatch(context, block)
        }

        fun soltar() {
            synchronized(this) { retenida.also { retenida = null } }?.let { (c, b) -> Dispatchers.IO.dispatch(c, b) }
        }
    }

    private companion object {
        /** El tope de cada prueba: sólo para que una falla no cuelgue la suite; ninguna aserción depende del reloj de pared. */
        const val T = 60_000L

        /** El tope de pared de una barrera: si no se libera, la prueba falla con su nombre. */
        const val BARRERA = 10_000L
        const val INICIO = 1_000_000L
        const val VENUE = "venue-1"
        const val TOKEN = "tok-simulado-de-prueba"
        const val IPEK_HEX = "6AC292FAA1315B4D858AB3A3D7D5933A" // vector público ANSI X9.24
        val IPEK = IPEK_HEX.hexABytes()
        const val USUARIO = "usuario-simulado-de-prueba"
        const val CONTRASENA = "clave-simulada-de-prueba"
        val CREDENCIAL = CredencialDelCliente(USUARIO, CONTRASENA)

        /** La credencial como viaja en el header Basic: también se busca codificada. */
        val BASIC: String = Base64.getEncoder().encodeToString("$USUARIO:$CONTRASENA".toByteArray(Charsets.UTF_8))
        const val JWT_FALSO = "jwt-simulado-de-prueba"
        val JWT = object : CredencialDelServidor {
            override fun vigente() = JWT_FALSO
            override suspend fun renovar(rechazado: String, espera: Duration): String? = null
        }
        val IDENTIDAD = Identidad("COMERCIO DE PRUEBA", "12345", "0000000001", "00000000011", "A910S", "1", Ambiente.PRODUCCION)
        val PARED: Instant = Instant.parse("2026-11-20T18:00:00Z")
        val HUELLA = HuellaHmac(SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256"))
        val CON_RED = object : Red {
            override fun hayRed() = true
        }

        /** Los de producción, con la pausa entre reversos en 0 (ver el KDoc de la clase). */
        val RELOJES = Relojes(pausaEntreReversos = 0)
        val LOCALHOST: InetAddress = InetAddress.getByName("localhost")
        private val CERTIFICADO = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val DEL_SERVIDOR: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(CERTIFICADO).build()
        val DEL_CLIENTE: HandshakeCertificates = HandshakeCertificates.Builder().addTrustedCertificate(CERTIFICADO.certificate).build()

        // Los tramos del barrido, en el orden en que se revisan: primero lo que queda en disco.
        const val ARCHIVO = "archivo"
        const val LIBRETA = "libreta"
        const val REGISTROS = "registros"
        const val EVENTOS = "eventos"
        const val RESPUESTAS = "respuestas"
        const val TO_STRING = "toString"
        const val TARDIAS = "tardias"
        const val EXCEPCIONES = "excepciones"
        const val SERVIDOR = "servidor"
        const val SIMULADORES = "simuladores"
        val TRAMOS = listOf(ARCHIVO, LIBRETA, REGISTROS, EVENTOS, RESPUESTAS, TO_STRING, TARDIAS, EXCEPCIONES, SERVIDOR, SIMULADORES)

        fun aprobadoVisa(op: Long?) = Aprobado(op, AUTORIZACION, null, Marca.VISA, Medio.PLASTICO, "411111", "1111", "0484")

        /** Un folio para ese escenario del SERVIDOR: con su sufijo, o uno al azar que no caiga en ninguno. */
        fun folio(e: EscenarioDelServidor): AttemptId {
            while (true) {
                val v = AttemptId.nuevo().valor
                val id = runCatching { AttemptId(e.sufijo?.let { v.dropLast(it.length) + it } ?: v) }.getOrNull() ?: continue
                if (EscenarioDelServidor.de(id) == e) return id
            }
        }
    }
}
