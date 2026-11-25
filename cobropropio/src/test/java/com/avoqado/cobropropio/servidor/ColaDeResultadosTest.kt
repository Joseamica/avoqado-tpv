package com.avoqado.cobropropio.servidor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import com.avoqado.cobropropio.fixtures.arrancarEnIpv4
import com.avoqado.cobropropio.fixtures.baseLiteral
import com.avoqado.cobropropio.libreta.Arbitraje
import com.avoqado.cobropropio.libreta.DatosDelEnvio
import com.avoqado.cobropropio.libreta.EstadoDelIntento
import com.avoqado.cobropropio.libreta.EstadoDelReporte
import com.avoqado.cobropropio.libreta.EstadoDelReversoEnServidor
import com.avoqado.cobropropio.libreta.IntentoEntity
import com.avoqado.cobropropio.libreta.Libreta
import com.avoqado.cobropropio.libreta.LibretaDatabase
import com.avoqado.cobropropio.libreta.ResultadoParaReportar
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ColaDeResultadosTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var pared = Instant.parse("2020-01-02T03:04:05.678Z")
    private val db by lazy {
        LibretaDatabase.constructor(context, File(carpeta.root, "libreta.db")).allowMainThreadQueries().build()
    }
    private val libreta by lazy { Libreta(db, { 1_000L }, { pared }) }
    private val servidor = ServidorFalso()
    private val cola by lazy { ColaDeResultados(libreta, servidor) }

    @After fun cerrar() {
        if (db.isOpen) db.close()
    }

    private class ServidorFalso : ReversoDelServidor {
        val acuses = ArrayDeque<AcuseDelResultado>()
        val reportados = mutableListOf<ResultadoDelReverso>()
        var durante: () -> Unit = {}

        override suspend fun reportarResultado(venueId: String, id: AttemptId, r: ResultadoDelReverso): AcuseDelResultado {
            reportados += r
            durante()
            return acuses.removeFirst()
        }
    }

    /** Una venta con un reverso cerrado y su reporte `PENDIENTE`, como la deja el motor: confirmado ⇒ `NO_SE_COBRO`, si no `NO_SE`. */
    private fun conReporte(
        clase: ClaseDelReverso = ClaseDelReverso.Confirmado(pared),
        reportar: ResultadoParaReportar = ResultadoParaReportar.CONFIRMADO,
        origen: Origen = Origen.VINCULADO_AL_POS,
        /** Una fila vieja con `vinculoAcreditadoPorPermiso` en true: la columna ya no tiene escritor (bloque S). */
        permiso: Boolean = false,
    ): AttemptId {
        val estado = if (clase is ClaseDelReverso.Confirmado) EstadoDelIntento.NO_SE_COBRO else EstadoDelIntento.NO_SE
        val v = IntentoEntity(
            attemptId = AttemptId.nuevo().valor, operacion = Operacion.VENTA, venueId = "venue-1", importeCentavos = 12_300,
            vinculado = origen == Origen.VINCULADO_AL_POS, posId = "1001", serieBlumon = "9000000001", ambiente = Ambiente.SANDBOX,
            versionSdk = "fase2", origen = origen,
        )
        val id = AttemptId(v.attemptId)
        assertTrue(libreta.anotar(v))
        assertTrue(libreta.marcarEnviado(id, 1_000, DatosDelEnvio(null, null, null, null, null, null, null, null, v.attemptId)))
        assertEquals(Arbitraje.Comprometido, libreta.comprometerAReversar(id))
        if (permiso) assertTrue(libreta.transicionar(id, EstadoDelIntento.ENVIADO) { it.copy(vinculoAcreditadoPorPermiso = true) } != null)
        assertTrue(libreta.reclamarReverso(id))
        assertTrue(libreta.cerrarVuelta(id, clase, reportar, estado))
        assertEquals(EstadoDelReporte.PENDIENTE, libreta.buscar(id)!!.reporte)
        return id
    }

    private fun entregar(id: AttemptId, acuse: AcuseDelResultado): IntentoEntity {
        servidor.acuses += acuse
        val e = runBlocking { cola.entregar(id) }
        assertEquals(acuse, e.acuse)
        return e.fila!!
    }

    private fun guardado(estado: EstadoDelReversoEnServidor) = AcuseDelResultado.Guardado(estado, "cevt1", null)

    /** Un id con la forma vieja (el UUID de 36 con guiones): Room lo lee, `AttemptId` lo rechaza. */
    private val ID_DE_36 = "0f8fad5b-d9cb-469f-a165-b0867728950e"

    // ---- el mapeo del acuse ----

    @Test fun `guardado reversed marca entregado`() {
        val f = entregar(conReporte(), guardado(EstadoDelReversoEnServidor.REVERSED))
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertTrue(libreta.pendientesDeReporte().isEmpty())
    }

    @Test fun `reverse_failed entrega sin cambiar el estado`() {
        val id = conReporte(ClaseDelReverso.Rechazado("TX_008", pared), ResultadoParaReportar.RECHAZADO)
        val f = entregar(id, guardado(EstadoDelReversoEnServidor.REVERSE_FAILED))
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSE_FAILED, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals("TX_008", servidor.reportados.single().codigo)
    }

    @Test fun `contradiction se guarda y el intento sigue no se cobro`() {
        val f = entregar(conReporte(), AcuseDelResultado.Guardado(EstadoDelReversoEnServidor.CONTRADICTION, "cevt1", "cpay9"))
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.CONTRADICTION, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    @Test fun `conflicto guarda el estado del SERVIDOR y el intento sigue no se cobro`() {
        val f = entregar(conReporte(), AcuseDelResultado.Conflicto(EstadoDelReversoEnServidor.REVERSE_FAILED))
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSE_FAILED, f.estadoEnServidor)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    @Test fun `reversing y reverse_unconfirmed son el acuse del pudo entrar`() {
        for (estado in listOf(EstadoDelReversoEnServidor.REVERSING, EstadoDelReversoEnServidor.REVERSE_UNCONFIRMED)) {
            val id = conReporte(ClaseDelReverso.Reintentar("68", false, pared), ResultadoParaReportar.PUDO_ENTRAR)
            val f = entregar(id, guardado(estado))
            assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
            assertEquals(estado, f.estadoEnServidor)
            assertEquals(EstadoDelIntento.NO_SE, f.estado)
        }
        assertTrue(servidor.reportados.all { it.codigo == "SIN_RESPUESTA" && it.descripcion == null })
    }

    @Test fun `reversing para un confirmado no lo da por entregado`() {
        val f = entregar(conReporte(), guardado(EstadoDelReversoEnServidor.REVERSING))
        assertEquals(EstadoDelReporte.CONCILIAR, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    /**
     * Bloque S (D-4): el acuse sólo cierra el reporte y guarda lo que dijo el SERVIDOR; el estado del intento nunca cambia.
     * `reporte` y `estadoEnServidor` siguen la tabla de `entregar` (filas vinculadas, ningún acuse de un `PUDO_ENTRAR`).
     */
    @Test fun `ningun acuse cambia el estado del intento`() {
        val acuses = EstadoDelReversoEnServidor.values().map(::guardado) + listOf(
            AcuseDelResultado.Conflicto(EstadoDelReversoEnServidor.REVERSE_FAILED), AcuseDelResultado.NoEncontrado,
            AcuseDelResultado.Final(403, null), AcuseDelResultado.Reintentar,
        )
        fun tabla(a: AcuseDelResultado): Pair<EstadoDelReporte, EstadoDelReversoEnServidor?> = when (a) {
            is AcuseDelResultado.Guardado -> when (a.estado) {
                EstadoDelReversoEnServidor.REVERSING, EstadoDelReversoEnServidor.REVERSE_UNCONFIRMED -> EstadoDelReporte.CONCILIAR
                else -> EstadoDelReporte.ENTREGADO
            } to a.estado
            is AcuseDelResultado.Conflicto -> EstadoDelReporte.ENTREGADO to a.estadoGuardado
            AcuseDelResultado.NoEncontrado, is AcuseDelResultado.Final -> EstadoDelReporte.CONCILIAR to null
            AcuseDelResultado.Reintentar -> EstadoDelReporte.PENDIENTE to null
        }
        val filas = listOf<Pair<String, () -> AttemptId>>(
            "NO_SE_COBRO + CONFIRMADO" to { conReporte() },
            "NO_SE + RECHAZADO" to { conReporte(ClaseDelReverso.Rechazado("TX_007", pared), ResultadoParaReportar.RECHAZADO) },
        )
        val cambios = mutableListOf<String>()
        for ((nombre, nueva) in filas) for (acuse in acuses) {
            val id = nueva()
            val antes = libreta.buscar(id)!!.estado
            val f = entregar(id, acuse)
            if (f.estado != antes) cambios += "$nombre con $acuse: $antes → ${f.estado}"
            assertEquals("$nombre con $acuse", tabla(acuse), f.reporte to f.estadoEnServidor)
        }
        assertEquals(emptyList<String>(), cambios)
    }

    // ---- V = vínculo efectivo durable ----

    @Test fun `404 vinculado queda conciliar`() {
        val f = entregar(conReporte(), AcuseDelResultado.NoEncontrado)
        assertEquals(EstadoDelReporte.CONCILIAR, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertNull(f.estadoEnServidor)
    }

    @Test fun `404 sin vinculo queda final`() {
        val f = entregar(conReporte(origen = Origen.LOCAL_SIN_SOLICITUD), AcuseDelResultado.NoEncontrado)
        assertEquals(EstadoDelReporte.FINAL_SIN_ENTREGAR, f.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
    }

    @Test fun `404 local con permiso acreditado queda conciliar`() {
        val f = entregar(conReporte(origen = Origen.LOCAL_SIN_SOLICITUD, permiso = true), AcuseDelResultado.NoEncontrado)
        assertEquals(EstadoDelReporte.CONCILIAR, f.reporte)
    }

    @Test fun `final con vinculo queda conciliar y nunca final`() {
        for (http in listOf(400, 403)) {
            assertEquals(EstadoDelReporte.CONCILIAR, entregar(conReporte(), AcuseDelResultado.Final(http, null)).reporte)
            val local = conReporte(origen = Origen.LOCAL_SIN_SOLICITUD, permiso = true)
            assertEquals(EstadoDelReporte.CONCILIAR, entregar(local, AcuseDelResultado.Final(http, null)).reporte)
        }
    }

    @Test fun `final sin vinculo queda final sin entregar`() {
        val f = entregar(conReporte(origen = Origen.LOCAL_SIN_SOLICITUD), AcuseDelResultado.Final(403, "TERMINAL_IDENTITY_REQUIRED"))
        assertEquals(EstadoDelReporte.FINAL_SIN_ENTREGAR, f.reporte)
    }

    // ---- la cola ----

    @Test fun `503 deja pendiente y la siguiente vuelta entrega`() {
        val id = conReporte()
        servidor.acuses += AcuseDelResultado.Reintentar
        assertEquals(mapOf(id to AcuseDelResultado.Reintentar), runBlocking { cola.entregarPendientes() })
        assertEquals(EstadoDelReporte.PENDIENTE, libreta.buscar(id)!!.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, libreta.buscar(id)!!.estado)

        servidor.acuses += guardado(EstadoDelReversoEnServidor.REVERSED)
        assertEquals(mapOf(id to guardado(EstadoDelReversoEnServidor.REVERSED)), runBlocking { cola.entregarPendientes() })
        assertEquals(EstadoDelReporte.ENTREGADO, libreta.buscar(id)!!.reporte)
        assertEquals(EstadoDelIntento.NO_SE_COBRO, libreta.buscar(id)!!.estado)
        assertEquals(2, servidor.reportados.size)
        assertTrue(runBlocking { cola.entregarPendientes() }.isEmpty())
    }

    @Test fun `401 en resultado deja pendiente y la siguiente vuelta entrega`() {
        // Con el HTTP real: el token vencido de la terminal nunca deja un REVERSADA confirmado sin llegar al SERVIDOR.
        val web = MockWebServer().apply { arrancarEnIpv4() }
        try {
            val credencial = object : CredencialDelServidor {
                var jwt = "jwt-vencido"
                override fun vigente() = jwt
                override suspend fun renovar(rechazado: String, espera: kotlin.time.Duration) = "jwt-nuevo".also { jwt = it }
            }
            val colaHttp = ColaDeResultados(libreta, ReversoDelServidorHttp(web.baseLiteral(), credencial))
            val id = conReporte()
            web.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))
            assertEquals(mapOf(id to AcuseDelResultado.Reintentar), runBlocking { colaHttp.entregarPendientes() })
            assertEquals(EstadoDelReporte.PENDIENTE, libreta.buscar(id)!!.reporte)

            web.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"success":true,"attemptId":"${id.valor}","estado":"reversed","eventLogId":"cevt1"}"""),
            )
            runBlocking { colaHttp.entregarPendientes() }
            assertEquals(EstadoDelReporte.ENTREGADO, libreta.buscar(id)!!.reporte)
            assertEquals(EstadoDelIntento.NO_SE_COBRO, libreta.buscar(id)!!.estado)
            assertEquals(2, web.requestCount)
            assertEquals("Bearer jwt-vencido", web.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer jwt-nuevo", web.takeRequest().getHeader("Authorization"))
        } finally {
            web.shutdown()
        }
    }

    @Test fun `manda la hora observada guardada y no la de cuando corre`() {
        val id = conReporte()
        val guardada = libreta.buscar(id)!!.horaObservadaIso
        pared = Instant.parse("2026-11-18T23:59:59Z")
        entregar(id, guardado(EstadoDelReversoEnServidor.REVERSED))
        assertEquals("2020-01-02T03:04:05.678Z", guardada)
        assertEquals(guardada, servidor.reportados.single().reversadoEn)
        assertEquals("REVERSADA", servidor.reportados.single().descripcion)
    }

    @Test fun `un acuse tardio no marca una obligacion mas nueva`() {
        val id = conReporte(ClaseDelReverso.Reintentar("68", false, pared), ResultadoParaReportar.PUDO_ENTRAR)
        // Mientras el PUDO_ENTRAR va en camino, otra vuelta confirma el reverso y deja una obligación nueva (CONFIRMADO).
        servidor.durante = {
            assertTrue(libreta.reclamarReverso(id))
            assertTrue(libreta.cerrarVuelta(id, ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, null))
        }
        servidor.acuses += guardado(EstadoDelReversoEnServidor.REVERSING)
        val e = runBlocking { cola.entregar(id) }
        val f = e.fila!!
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar)
        assertNull(f.estadoEnServidor)
        assertEquals(listOf(id), libreta.pendientesDeReporte().map { AttemptId(it.attemptId) })
    }

    @Test fun `sin reporte pendiente no llama al servidor`() {
        val id = conReporte()
        entregar(id, guardado(EstadoDelReversoEnServidor.REVERSED))
        val e = runBlocking { cola.entregar(id) }
        assertNull(e.acuse)
        assertEquals(EstadoDelReporte.ENTREGADO, e.fila!!.reporte)
        assertNull(runBlocking { cola.entregar(AttemptId.nuevo()) }.fila)
        assertEquals(1, servidor.reportados.size)
    }

    // ---- I3: la fila ilegible (fase 3, bloque 1) ----

    /** I3: las dos causas; ninguna detiene el aviso de la buena, y las dos se quedan como estaban. */
    @Test fun `una fila ilegible no detiene los avisos de las demas`() {
        val enumRaro = conReporte()
        val idViejo = conReporte()
        val buena = conReporte()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE intentos SET marca = 'DISCOVER' WHERE attemptId = ?", arrayOf(enumRaro.valor))
        sql.execSQL("UPDATE intentos SET attemptId = ? WHERE attemptId = ?", arrayOf(ID_DE_36, idViejo.valor))
        servidor.acuses += guardado(EstadoDelReversoEnServidor.REVERSED)
        assertEquals(setOf(buena), runBlocking { cola.entregarPendientes() }.keys)
        assertEquals(1, servidor.reportados.size)
        assertTrue(libreta.pendientesDeReporte().isEmpty())
        sql.query("SELECT COUNT(*) FROM intentos WHERE reporte = 'PENDIENTE'").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
    }

    /** I3: si entregar UNA fila lanza (un adaptador defectuoso), las demás se entregan igual. */
    @Test fun `una excepcion al entregar una fila no detiene a las demas`() {
        val primera = conReporte()
        val segunda = conReporte()
        var veces = 0
        servidor.durante = { if (veces++ == 0) throw IllegalStateException("adaptador defectuoso") }
        servidor.acuses += guardado(EstadoDelReversoEnServidor.REVERSED)
        assertEquals(setOf(segunda), runBlocking { cola.entregarPendientes() }.keys)
        assertEquals(EstadoDelReporte.PENDIENTE, libreta.buscar(primera)!!.reporte)
    }

    /** Codex r4 #2, salida «aviso al SERVIDOR»: un aviso CONFIRMADO cuyo reverso no está CONFIRMADO nunca sale como REVERSADA. */
    @Test fun `un aviso confirmado sin reverso confirmado no se manda`() {
        val id = conReporte() // CONFIRMADO con el reverso CONFIRMADO, como lo deja cerrarVuelta
        db.openHelper.writableDatabase.execSQL("UPDATE intentos SET reverso = 'NO_CONCLUYENTE' WHERE attemptId = ?", arrayOf(id.valor))
        assertTrue(runBlocking { cola.entregarPendientes() }.isEmpty())
        assertTrue(servidor.reportados.isEmpty())
        db.openHelper.readableDatabase.query("SELECT reporte FROM intentos WHERE attemptId = ?", arrayOf(id.valor)).use {
            assertTrue(it.moveToFirst())
            assertEquals("el aviso sigue pendiente", "PENDIENTE", it.getString(0))
        }
    }
}
