package com.avoqado.cobropropio.libreta

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.avoqado.cobropropio.blumon.ClaseDelReverso
import com.avoqado.cobropropio.cripto.Dukpt
import com.avoqado.cobropropio.cripto.hexABytes
import com.avoqado.cobropropio.dominio.Ambiente
import com.avoqado.cobropropio.dominio.AttemptId
import com.avoqado.cobropropio.dominio.Marca
import com.avoqado.cobropropio.dominio.Medio
import com.avoqado.cobropropio.dominio.MotivoNoSalio
import com.avoqado.cobropropio.dominio.Operacion
import com.avoqado.cobropropio.dominio.Origen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
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
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
// Sin Conscrypt: Robolectric lo registra como proveedor GLOBAL de la JVM y cambia el TLS de EnvioUnicoTest, que corre después.
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibretaTest {

    @get:Rule val carpeta = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var reloj = 1_000L
    private var pared = Instant.parse("2026-11-18T18:00:00Z")
    private lateinit var archivo: File
    private val abiertas = mutableListOf<LibretaDatabase>()

    @Before fun preparar() {
        archivo = File(carpeta.root, "libreta.db")
    }

    @After fun cerrarTodo() {
        abiertas.forEach { if (it.isOpen) it.close() }
    }

    /** Una libreta sobre el ARCHIVO de prueba. `falla` = la operación cuya transacción truena antes del commit. */
    private fun libreta(proceso: String = Libreta.PROCESO_ID, falla: String? = null): Libreta {
        val db = LibretaDatabase.constructor(context, archivo).allowMainThreadQueries().build().also { abiertas += it }
        return Libreta(db, { reloj }, { pared }, proceso) { op -> if (op == falla) throw IllegalStateException("falla inyectada") }
    }

    private fun reabrir(l: Libreta, proceso: String = Libreta.PROCESO_ID, falla: String? = null): Libreta {
        l.cerrar()
        return libreta(proceso, falla)
    }

    private fun venta(venue: String = "venue-1", posId: String = "1001", origen: Origen = Origen.VINCULADO_AL_POS) =
        IntentoEntity(
            attemptId = AttemptId.nuevo().valor,
            operacion = Operacion.VENTA,
            venueId = venue,
            importeCentavos = 12_300,
            vinculado = origen == Origen.VINCULADO_AL_POS,
            posId = posId,
            serieBlumon = "9000000001",
            ambiente = Ambiente.SANDBOX,
            versionSdk = "fase2",
            origen = origen,
        )

    private fun cancelacionDe(original: IntentoEntity, venue: String = original.venueId, posId: String = original.posId) =
        venta(venue = venue, posId = posId).copy(operacion = Operacion.CANCELACION, operacionOriginal = original.attemptId)

    private val tarjeta = "a".repeat(64)
    private val lecturaDeLaVenta = "b".repeat(64)

    private fun datos(huellaTarjeta: String = tarjeta, huellaLectura: String = lecturaDeLaVenta, contador: Long? = 357) =
        DatosDelEnvio(
            ksnBase = "FFFF9876543210E00000", contadorUsado = contador, marca = Marca.VISA, medio = Medio.PLASTICO,
            bin = "400000", ultimos4 = "0010", paisEmisor = "0484", huellaTarjeta = huellaTarjeta, huellaLectura = huellaLectura,
        )

    private fun IntentoEntity.id() = AttemptId(attemptId)

    private fun enviada(l: Libreta, v: IntentoEntity = venta()): IntentoEntity {
        assertTrue(l.anotar(v))
        assertTrue(l.marcarEnviado(v.id(), reloj, datos()))
        return v
    }

    private fun aprobada(l: Libreta, v: IntentoEntity = venta()): IntentoEntity {
        enviada(l, v)
        assertEquals(Arbitraje.Vigente, l.guardarAprobacion(v.id(), 1_234_567, "A1B2C3"))
        return v
    }

    private fun reclamada(l: Libreta): IntentoEntity {
        val v = enviada(l)
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        assertTrue(l.reclamarReverso(v.id()))
        return v
    }

    private fun falla(bloque: () -> Unit) {
        val e = runCatching(bloque).exceptionOrNull()
        assertTrue("debió tronar la falla inyectada: $e", e is IllegalStateException && e.message == "falla inyectada")
    }

    private val ksn = "FFFF9876543210E00000".hexABytes()

    /** Un id con la forma vieja (el UUID de 36 con guiones): Room lo lee, `AttemptId` lo rechaza. */
    private val ID_DE_36 = "0f8fad5b-d9cb-469f-a165-b0867728950e"

    // ---- anotar y la base ----

    @Test fun `anotar dos veces da falso`() {
        val l = libreta()
        val v = venta()
        assertTrue(l.anotar(v))
        assertFalse(l.anotar(v))
        assertFalse(l.anotar(v.copy(importeCentavos = 1)))
        assertEquals(12_300L, l.buscar(v.id())!!.importeCentavos)
        assertEquals(Libreta.PROCESO_ID, l.buscar(v.id())!!.procesoId)
    }

    @Test fun `la base vive en noBackupFilesDir`() {
        val nombre = "libreta-${UUID.randomUUID()}.db"
        val l = Libreta.abrir(context, nombre)
        var error: Throwable? = null
        var journal = ""
        var synchronous = -1
        thread {
            runCatching {
                assertTrue(l.anotar(venta()))
                val sql = l.db.openHelper.writableDatabase
                sql.query("PRAGMA journal_mode").use { it.moveToFirst(); journal = it.getString(0) }
                sql.query("PRAGMA synchronous").use { it.moveToFirst(); synchronous = it.getInt(0) }
            }.onFailure { error = it }
        }.join()
        error?.let { throw it }
        l.cerrar()
        assertTrue(File(context.noBackupFilesDir, nombre).exists())
        assertFalse(context.getDatabasePath(nombre).exists())
        assertEquals("truncate", journal.lowercase())
        assertEquals(2, synchronous) // FULL
    }

    @Test fun `la libreta no tiene columnas de pan ni track`() {
        val prohibidas = listOf("pan", "track", "banda", "emv", "tags", "holder", "token", "ipek", "llave", "cripto", "cvv")
        val columnas = (IntentoEntity::class.java.declaredFields + ContadorEntity::class.java.declaredFields)
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name.lowercase() }
        assertTrue(columnas.contains("huellatarjeta") && columnas.contains("ksnbasehex"))
        for (c in columnas) for (p in prohibidas) assertFalse("columna $c", c.contains(p))
    }

    /**
     * Ola final · 3: `existeLectura` busca la huella de la lectura en cada cancelación; sin índice recorre toda la tabla. El
     * índice existe en la base que abre Room y en el esquema exportado (el v1 nunca ha estado en un aparato).
     */
    @Test fun `la huella de la lectura tiene indice`() {
        val l = libreta()
        val indices = mutableListOf<String>()
        l.db.openHelper.writableDatabase.query("PRAGMA index_list(intentos)").use { c ->
            while (c.moveToNext()) indices += c.getString(c.getColumnIndexOrThrow("name"))
        }
        assertTrue("$indices", "index_intentos_huellaLectura" in indices)
        val esquema = File("schemas/com.avoqado.cobropropio.libreta.LibretaDatabase/1.json").readText()
        assertTrue("CREATE INDEX IF NOT EXISTS `index_intentos_huellaLectura` ON `\${TABLE_NAME}` (`huellaLectura`)" in esquema)
    }

    @Test fun `un segundo motor sobre la misma libreta no se puede crear`() {
        val l = libreta()
        l.tomarDueno()
        assertTrue(runCatching { l.tomarDueno() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `la libreta rechaza un campo con forma de pan`() {
        val l = libreta()
        val v = venta()
        assertTrue(l.anotar(v))
        val e = runCatching { l.marcarEnviado(v.id(), reloj, datos().copy(bin = "4000001234567899")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertFalse(e!!.message.orEmpty().contains("4000001234567899"))
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(v.id())!!.estado)
    }

    /** r2 familia C: la última barrera. Un importe parcial sólo se guarda si `0 < parcial < importe`; nunca un número sin cotejar. */
    @Test fun `la libreta no guarda un importe parcial fuera de rango`() {
        val l = libreta()
        val v = enviada(l)
        for (malo in listOf(0L, v.importeCentavos, v.importeCentavos + 1, 411111111111111L)) {
            val e = runCatching { l.transicionar(v.id(), EstadoDelIntento.ENVIADO) { it.copy(parcialImporteCentavos = malo) } }.exceptionOrNull()
            assertTrue("$malo", e is IllegalArgumentException)
            assertFalse(e!!.message.orEmpty().contains("$malo"))
        }
        assertNull(l.buscar(v.id())!!.parcialImporteCentavos)
        assertEquals(60L, l.transicionar(v.id(), EstadoDelIntento.ENVIADO) { it.copy(parcialImporteCentavos = 60) }!!.parcialImporteCentavos)
    }

    // ---- contador ----

    @Test fun `reservar contador respeta ultimo de Blumon mas uno`() {
        val l = libreta()
        l.db.dao().guardarContador(ContadorEntity(Dukpt.baseCanonica(ksn), 90))
        assertEquals(101L, l.reservarContador(ksn, 100))
    }

    @Test fun `base sin historial arranca en ultimo de Blumon mas 256`() {
        val l = libreta()
        assertEquals(100L + Libreta.SALTO_SIN_HISTORIAL, l.reservarContador(ksn, 100))
        assertEquals(357L, l.reservarContador(ksn, 100))
    }

    @Test fun `reservar nunca baja`() {
        var l = libreta()
        assertEquals(356L, l.reservarContador(ksn, 100))
        assertEquals(357L, l.reservarContador(ksn, 5))
        l = reabrir(l)
        assertEquals(358L, l.reservarContador(ksn, 0))
        assertEquals(401L, l.reservarContador(ksn, 400))
    }

    @Test fun `reservar por base canonica`() {
        val l = libreta()
        assertEquals(356L, l.reservarContador(Dukpt.ksnCon(ksn, 7), 100))
        assertEquals(357L, l.reservarContador(Dukpt.ksnCon(ksn, 99), 100)) // misma base, otro contador en el KSN
        val otra = "FFFF9876543211E00000".hexABytes()
        assertEquals(356L, l.reservarContador(otra, 100))
    }

    @Test fun `salto que pasa del maximo da CONTADOR_AGOTADO`() {
        val l = libreta()
        assertNull(l.reservarContador(ksn, Dukpt.CONTADOR_MAXIMO - 100L))
        assertNull(l.db.dao().contador(Dukpt.baseCanonica(ksn)))
    }

    @Test fun `contador agotado da null`() {
        val l = libreta()
        l.db.dao().guardarContador(ContadorEntity(Dukpt.baseCanonica(ksn), Dukpt.CONTADOR_MAXIMO.toLong()))
        assertEquals(Dukpt.CONTADOR_MAXIMO.toLong(), l.reservarContador(ksn, 1))
        assertNull(l.reservarContador(ksn, 1))
        assertNull(l.reservarContador(ksn, -1))
        assertNull(l.reservarContador("FFFF9876543210A00000".hexABytes(), Dukpt.CONTADOR_MAXIMO.toLong()))
    }

    // ---- antes de salir ----

    @Test fun `corte antes de salir gana a marcarEnviado y al reves`() {
        val l = libreta()
        val a = venta()
        assertTrue(l.anotar(a))
        assertTrue(l.vetarPorCorte(a))
        assertTrue(l.buscar(a.id())!!.cortarAntesDeSalir)
        assertFalse(l.marcarEnviado(a.id(), reloj, datos()))
        assertEquals(EstadoDelIntento.NO_SALIO, l.buscar(a.id())!!.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, l.buscar(a.id())!!.motivoNoSalio)

        val b = venta()
        assertTrue(l.anotar(b))
        assertTrue(l.marcarEnviado(b.id(), reloj, datos()))
        assertFalse(l.vetarPorCorte(b))
        val fila = l.buscar(b.id())!!
        assertEquals(EstadoDelIntento.ENVIADO, fila.estado)
        assertFalse(fila.cortarAntesDeSalir)
        assertEquals(reloj, fila.enviadoEnMs)
        assertEquals(357L, fila.contadorUsado)
        assertEquals("0010", fila.ultimos4)
    }

    /** Regla C: el veto también se escribe sin fila, y `anotar` lo arbitra; un `NO_SALIO` ya es veto; lo demás no se toca. */
    @Test fun `vetar por corte sin fila deja el veto y anotar pierde contra el`() {
        val l = libreta()
        val a = venta()
        assertTrue(l.vetarPorCorte(a))
        val vetada = l.buscar(a.id())!!
        assertEquals(EstadoDelIntento.NO_SALIO, vetada.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, vetada.motivoNoSalio)
        assertTrue(vetada.cortarAntesDeSalir)
        assertEquals(Libreta.PROCESO_ID, vetada.procesoId)
        assertFalse(l.anotar(a))
        assertTrue(l.vetarPorCorte(a)) // otra vez: ya está
        assertEquals(vetada, l.buscar(a.id()))

        val sinRed = venta()
        assertTrue(l.anotar(sinRed))
        l.transicionar(sinRed.id(), EstadoDelIntento.ANOTADO) { it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.SIN_RED) }
        assertTrue(l.vetarPorCorte(sinRed))
        assertEquals(MotivoNoSalio.SIN_RED, l.buscar(sinRed.id())!!.motivoNoSalio)

        val ap = aprobada(l)
        assertFalse(l.vetarPorCorte(ap))
        assertEquals(EstadoDelIntento.APROBADO, l.buscar(ap.id())!!.estado)
    }

    @Test fun `transicionar es un CAS sobre el estado`() {
        val l = libreta()
        val v = enviada(l)
        assertNull(l.transicionar(v.id(), EstadoDelIntento.ANOTADO) { it.copy(estado = EstadoDelIntento.NO_SE) })
        val f = l.transicionar(v.id(), EstadoDelIntento.ENVIADO) { it.copy(estado = EstadoDelIntento.NO_SE) }
        assertEquals(EstadoDelIntento.NO_SE, f!!.estado)
        assertEquals(EstadoDelIntento.NO_SE, l.buscar(v.id())!!.estado)
    }

    // ---- arbitraje ----

    @Test fun `guardar aprobacion despues del compromiso es Tardia`() {
        val l = libreta()
        val v = enviada(l)
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        assertEquals(Arbitraje.Tardia, l.guardarAprobacion(v.id(), 1_234_567, "A1B2C3"))
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelIntento.ENVIADO, f.estado)
        assertNull(f.operationNumber)
        assertEquals(1_234_567L, f.evidenciaTardiaOperacion)
        assertEquals("A1B2C3", f.evidenciaTardiaAutorizacion)
    }

    @Test fun `guardar rechazo despues del compromiso es Tardia`() {
        val l = libreta()
        val v = enviada(l)
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        assertEquals(Arbitraje.Tardia, l.guardarRechazo(v.id(), "05", "No aprobada"))
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelIntento.ENVIADO, f.estado)
        assertNull(f.codigo)
    }

    @Test fun `comprometer con aprobacion vigente da YaResuelta`() {
        val l = libreta()
        val v = aprobada(l)
        val r = l.comprometerAReversar(v.id())
        assertTrue(r is Arbitraje.YaResuelta)
        assertEquals(EstadoDelIntento.APROBADO, (r as Arbitraje.YaResuelta).fila.estado)
        assertEquals(EstadoDelReverso.NINGUNO, l.buscar(v.id())!!.reverso)
        assertEquals(OrigenDeLaAprobacion.BANCO, l.buscar(v.id())!!.origenDeLaAprobacion)

        val rechazada = enviada(l)
        assertEquals(Arbitraje.Vigente, l.guardarRechazo(rechazada.id(), "51", "Fondos insuficientes"))
        assertTrue(l.comprometerAReversar(rechazada.id()) is Arbitraje.YaResuelta)
        assertTrue(l.guardarAprobacion(rechazada.id(), 9, null) is Arbitraje.YaResuelta)
        assertEquals(EstadoDelIntento.RECHAZADO, l.buscar(rechazada.id())!!.estado)
    }

    @Test fun `reclamar sin comprometer da falso`() {
        val l = libreta()
        val v = enviada(l)
        assertFalse(l.reclamarReverso(v.id()))
        assertEquals(EstadoDelReverso.NINGUNO, l.buscar(v.id())!!.reverso)
    }

    @Test fun `permiso concedido queda guardado antes del POST`() {
        var l = libreta()
        val v = enviada(l, venta(origen = Origen.LOCAL_SIN_SOLICITUD))
        assertFalse(l.guardarPermisoConcedido(v.id())) // sin compromiso no hay permiso que guardar
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        assertTrue(l.guardarPermisoConcedido(v.id()))
        l = reabrir(l)
        val f = l.buscar(v.id())!!
        assertTrue(f.vinculoAcreditadoPorPermiso)
        assertEquals(EstadoDelReverso.COMPROMETIDO, f.reverso)
    }

    // ---- vueltas del reverso ----

    @Test fun `cierre del reverso atomico con falla inyectada`() {
        var l = libreta()
        val v = reclamada(l)
        l = reabrir(l, falla = "cerrarVuelta")
        falla { l.cerrarVuelta(v.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, null) }
        l = reabrir(l)
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelReverso.RECLAMADO, f.reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertNull(f.resultadoParaReportar)
        assertNull(f.horaObservadaIso)
    }

    @Test fun `reverso confirmado y reporte pendiente sobreviven al reabrir`() {
        var l = libreta()
        val v = reclamada(l)
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, null))
        assertFalse(l.cerrarVuelta(v.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, null))
        l = reabrir(l)
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelReverso.CONFIRMADO, f.reverso)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(ResultadoParaReportar.CONFIRMADO, f.resultadoParaReportar)
        assertEquals(pared.toString(), f.horaObservadaIso)
        assertEquals(EstadoDelIntento.ENVIADO, f.estado)
        assertEquals(listOf(v.attemptId), l.pendientesDeReporte().map { it.attemptId })
    }

    @Test fun `pudoEntrar solo sube y un RECHAZADO con pudoEntrar se reporta como PUDO_ENTRAR`() {
        val l = libreta()
        val v = reclamada(l)
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Reintentar("68", false, pared), null, null))
        assertTrue(l.buscar(v.id())!!.pudoEntrar)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, l.buscar(v.id())!!.reverso)
        assertTrue(l.reclamarReverso(v.id()))
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Reintentar("TX_008", true, pared), null, null))
        assertTrue(l.buscar(v.id())!!.pudoEntrar)
        assertTrue(l.reclamarReverso(v.id()))
        assertTrue(
            l.cerrarVuelta(v.id(), ClaseDelReverso.Rechazado("TX_007", pared), ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE),
        )
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelReverso.RECHAZADO, f.reverso)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals("TX_007", f.ultimoCodigoReverso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `cerrar vuelta desde NO_CONCLUYENTE al techo`() {
        val l = libreta()
        val v = reclamada(l)
        val hora = pared
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Reintentar("TX_008", true, hora), null, null))
        pared = pared.plusSeconds(40)
        assertTrue(
            l.cerrarVuelta(v.id(), ClaseDelReverso.NoEnviado, ResultadoParaReportar.RECHAZADO, EstadoDelIntento.NO_SE),
        )
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertFalse(f.pudoEntrar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(ResultadoParaReportar.RECHAZADO, f.resultadoParaReportar)
        assertEquals("TX_008", f.codigoParaReportar)
        assertEquals(hora.toString(), f.horaObservadaIso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `cerrar sin vuelta desde COMPROMETIDO`() {
        val l = libreta()
        val v = enviada(l)
        assertFalse(l.cerrarSinVuelta(v.id()))
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        assertTrue(l.cerrarSinVuelta(v.id()))
        val f = l.buscar(v.id())!!
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertNull(f.resultadoParaReportar)
        assertFalse(f.pudoEntrar)

        val w = reclamada(l)
        assertFalse(l.cerrarSinVuelta(w.id()))
    }

    @Test fun `aplicar acuse reversed deja NO_SE_COBRO en la misma transaccion`() {
        var l = libreta()
        val v = reclamada(l)
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE))
        l = reabrir(l, falla = "aplicarAcuse")
        falla { l.aplicarAcuse(v.id(), ResultadoParaReportar.CONFIRMADO, EstadoDelReversoEnServidor.REVERSED, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE_COBRO) }
        l = reabrir(l)
        var f = l.buscar(v.id())!!
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertNull(f.estadoEnServidor)

        f = l.aplicarAcuse(v.id(), ResultadoParaReportar.CONFIRMADO, EstadoDelReversoEnServidor.REVERSED, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE_COBRO)!!
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        l = reabrir(l)
        f = l.buscar(v.id())!!
        assertEquals(EstadoDelIntento.NO_SE_COBRO, f.estado)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, f.estadoEnServidor)
        assertTrue(l.pendientesDeReporte().isEmpty())

        // `reversing` sólo entrega: el estado no cambia.
        val w = reclamada(l)
        assertTrue(l.cerrarVuelta(w.id(), ClaseDelReverso.Reintentar("68", false, pared), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelIntento.NO_SE))
        f = l.aplicarAcuse(w.id(), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelReversoEnServidor.REVERSING, EstadoDelReporte.ENTREGADO, null)!!
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertNull(l.aplicarAcuse(AttemptId.nuevo(), ResultadoParaReportar.CONFIRMADO, null, EstadoDelReporte.ENTREGADO, null))
    }

    @Test fun `aplicar acuse es un CAS sobre el reporte pendiente y el resultado acusado`() {
        val l = libreta()
        val v = reclamada(l)
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Reintentar("68", false, pared), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelIntento.NO_SE))
        // Llega una obligación más nueva (CONFIRMADO) antes que el acuse del PUDO_ENTRAR.
        assertTrue(l.reclamarReverso(v.id()))
        assertTrue(l.cerrarVuelta(v.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, null))
        assertNull(l.aplicarAcuse(v.id(), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelReversoEnServidor.REVERSING, EstadoDelReporte.ENTREGADO, null))
        var f = l.buscar(v.id())!!
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertNull(f.estadoEnServidor)
        // El acuse del CONFIRMADO sí aplica, y un segundo acuse igual ya no encuentra nada pendiente.
        f = l.aplicarAcuse(v.id(), ResultadoParaReportar.CONFIRMADO, EstadoDelReversoEnServidor.REVERSED, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE_COBRO)!!
        assertEquals(EstadoDelReporte.ENTREGADO, f.reporte)
        assertNull(l.aplicarAcuse(v.id(), ResultadoParaReportar.CONFIRMADO, EstadoDelReversoEnServidor.CONTRADICTION, EstadoDelReporte.ENTREGADO, EstadoDelIntento.NO_SE))
        assertEquals(EstadoDelIntento.NO_SE_COBRO, l.buscar(v.id())!!.estado)
        assertEquals(EstadoDelReversoEnServidor.REVERSED, l.buscar(v.id())!!.estadoEnServidor)
    }

    // ---- quién puede necesitar reverso ----

    @Test fun `hay intentos que pueden necesitar reverso`() {
        val l = libreta()
        val techo = 45_000L
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj, techo))
        val v = enviada(l)
        assertTrue(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj, techo))
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(v.id()))
        soltarALaCajera(l, v)
        assertTrue(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj + techo - 1, techo))
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj + techo, techo))

        val aprobadaYa = aprobada(l)
        assertEquals(EstadoDelIntento.APROBADO, l.buscar(aprobadaYa.id())!!.estado)
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj + techo, techo))
    }

    private fun soltarALaCajera(l: Libreta, v: IntentoEntity) {
        // La cajera ya se fue (G): la fila pasa a NO_SE, pero el reverso sigue vivo hasta el techo.
        assertEquals(EstadoDelIntento.NO_SE, l.transicionar(v.id(), EstadoDelIntento.ENVIADO) { it.copy(estado = EstadoDelIntento.NO_SE) }!!.estado)
    }

    /** R1-5 (b): un ENVIADO cuenta sólo hasta su t0 + techo, igual que un reverso abierto (sus datos de tarjeta eran de RAM). */
    @Test fun `un ENVIADO pasado el techo no veta la renovacion de llaves`() {
        val l = libreta()
        val techo = 45_000L
        val v = enviada(l)
        assertEquals(EstadoDelIntento.ENVIADO, l.buscar(v.id())!!.estado)
        assertTrue(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj + techo - 1, techo))
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj + techo, techo))
    }

    @Test fun `filas de otro proceso no bloquean la renovacion`() {
        val l = libreta()
        enviada(l)
        assertTrue(l.hayIntentosQuePuedenNecesitarReverso(Libreta.PROCESO_ID, reloj, 45_000))
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(UUID.randomUUID().toString(), reloj, 45_000))
    }

    // ---- el arranque después de un apagón ----

    @Test fun `recuperar no reusa contador y deja por confirmar`() {
        var l = libreta()
        val c = l.reservarContador(ksn, 100)!!
        val anotada = venta()
        assertTrue(l.anotar(anotada))
        val v = venta()
        assertTrue(l.anotar(v))
        assertTrue(l.marcarEnviado(v.id(), reloj, datos(contador = c)))
        val comprometida = enviada(l)
        assertEquals(Arbitraje.Comprometido, l.comprometerAReversar(comprometida.id()))

        l = reabrir(l, proceso = UUID.randomUUID().toString()) // el proceso murió; arranca otro
        assertEquals(setOf(anotada.attemptId, v.attemptId, comprometida.attemptId), l.recuperarAlArrancar { emptyList() }.map { it.valor }.toSet())
        assertEquals(EstadoDelIntento.NO_SALIO, l.buscar(anotada.id())!!.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, l.buscar(anotada.id())!!.motivoNoSalio)
        assertEquals(EstadoDelIntento.NO_SE, l.buscar(v.id())!!.estado)
        val sinPost = l.buscar(comprometida.id())!!
        assertEquals(EstadoDelIntento.NO_SE, sinPost.estado)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, sinPost.reverso)
        assertFalse(sinPost.pudoEntrar)
        assertEquals(EstadoDelReporte.NO_APLICA, sinPost.reporte)
        assertTrue(l.reservarContador(ksn, 100)!! > c)
        assertTrue(l.recuperarAlArrancar { emptyList() }.isEmpty()) // idempotente
        assertFalse(l.reclamarReverso(comprometida.id())) // de un proceso muerto ya no se reversa
    }

    @Test fun `recuperar un RECLAMADO deja pudoEntrar`() {
        var l = libreta()
        val v = reclamada(l)
        l = reabrir(l, proceso = UUID.randomUUID().toString())
        assertEquals(listOf(v.attemptId), l.recuperarAlArrancar { emptyList() }.map { it.valor })
        val f = l.buscar(v.id())!!
        assertTrue(f.pudoEntrar)
        assertEquals(EstadoDelReverso.NO_CONCLUYENTE, f.reverso)
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
    }

    @Test fun `recuperar un RECLAMADO encola PUDO_ENTRAR en la misma transaccion`() {
        var l = libreta()
        val horaDelReclamo = pared
        val v = reclamada(l)
        pared = pared.plusSeconds(600)
        val otro = UUID.randomUUID().toString()
        l = reabrir(l, proceso = otro, falla = "recuperarAlArrancar")
        falla { l.recuperarAlArrancar { emptyList() } }
        l = reabrir(l, proceso = otro)
        val antes = l.buscar(v.id())!!
        assertFalse(antes.pudoEntrar)
        assertEquals(EstadoDelReporte.NO_APLICA, antes.reporte)
        assertEquals(EstadoDelReverso.RECLAMADO, antes.reverso)

        l.recuperarAlArrancar { emptyList() }
        l = reabrir(l, proceso = otro)
        val f = l.buscar(v.id())!!
        assertTrue(f.pudoEntrar)
        assertEquals(EstadoDelReporte.PENDIENTE, f.reporte)
        assertEquals(ResultadoParaReportar.PUDO_ENTRAR, f.resultadoParaReportar)
        assertEquals(horaDelReclamo.toString(), f.horaObservadaIso)
        assertEquals(listOf(v.attemptId), l.pendientesDeReporte().map { it.attemptId })
    }

    @Test fun `recuperar no toca un reporte ya encolado ni un reverso confirmado`() {
        var l = libreta()
        val entregada = reclamada(l)
        assertTrue(l.cerrarVuelta(entregada.id(), ClaseDelReverso.Reintentar("68", false, pared), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelIntento.NO_SE))
        assertTrue(l.aplicarAcuse(entregada.id(), ResultadoParaReportar.PUDO_ENTRAR, EstadoDelReversoEnServidor.REVERSING, EstadoDelReporte.ENTREGADO, null) != null)
        val confirmada = reclamada(l)
        assertTrue(l.cerrarVuelta(confirmada.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE))
        l = reabrir(l, proceso = UUID.randomUUID().toString())
        assertTrue(l.recuperarAlArrancar { emptyList() }.isEmpty())
        assertEquals(EstadoDelReporte.ENTREGADO, l.buscar(entregada.id())!!.reporte)
        assertEquals(EstadoDelReverso.CONFIRMADO, l.buscar(confirmada.id())!!.reverso)
    }

    // ---- la cancelación ----

    @Test fun `anotar cancelacion exige mismo ambito`() {
        val l = libreta()
        val original = aprobada(l)
        val ambito = original.ambito()
        assertEquals(ResultadoDeAnotarCancelacion.OtroAmbito, l.anotarCancelacion(cancelacionDe(original, posId = "2002"), "2002|9000000001|SANDBOX"))
        assertEquals(ResultadoDeAnotarCancelacion.OtroAmbito, l.anotarCancelacion(cancelacionDe(original, venue = "venue-2"), ambito))
        assertEquals(ResultadoDeAnotarCancelacion.OtroAmbito, l.anotarCancelacion(cancelacionDe(original), "2002|9000000001|SANDBOX"))
        assertEquals(ResultadoDeAnotarCancelacion.SinVentaAprobada, l.anotarCancelacion(cancelacionDe(enviada(l)), ambito))
        val otraCancelacion = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(otraCancelacion, ambito))
        assertEquals(
            ResultadoDeAnotarCancelacion.SinVentaAprobada,
            l.anotarCancelacion(venta().copy(operacion = Operacion.CANCELACION, operacionOriginal = otraCancelacion.attemptId), ambito),
        )
    }

    /** T9 regla N: una cancelación que no se admite deja SU fila `NO_SALIO` con su motivo, en la misma transacción. */
    @Test fun `anotar cancelacion que no se admite deja su fila NO_SALIO con su motivo`() {
        val l = libreta()
        val original = aprobada(l)
        val ambito = original.ambito()
        val sinOriginal = cancelacionDe(original).copy(operacionOriginal = AttemptId.nuevo().valor, importeCentavos = 0)
        val enDuda = cancelacionDe(enviada(l))
        val deOtroVenue = cancelacionDe(original, venue = "venue-2").copy(importeCentavos = 0)
        val primera = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.SinVentaAprobada, l.anotarCancelacion(sinOriginal, ambito))
        assertEquals(ResultadoDeAnotarCancelacion.SinVentaAprobada, l.anotarCancelacion(enDuda, ambito))
        assertEquals(ResultadoDeAnotarCancelacion.OtroAmbito, l.anotarCancelacion(deOtroVenue, ambito))
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(primera, ambito))
        val segunda = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.YaExiste, l.anotarCancelacion(segunda, ambito))
        for ((c, motivo) in listOf(
            sinOriginal to MotivoNoSalio.SIN_VENTA_APROBADA, enDuda to MotivoNoSalio.SIN_VENTA_APROBADA,
            deOtroVenue to MotivoNoSalio.OTRO_AMBITO, segunda to MotivoNoSalio.CANCELACION_YA_EXISTE,
        )) {
            val f = l.buscar(c.id())!!
            assertEquals(motivo.name, EstadoDelIntento.NO_SALIO, f.estado)
            assertEquals(motivo, f.motivoNoSalio)
            assertEquals(Operacion.CANCELACION, f.operacion)
        }
        assertEquals("sin original no se sabe el importe", 0L, l.buscar(sinOriginal.id())!!.importeCentavos)
        // R14-5: a otro venue no se le copia el importe de la original.
        assertEquals(0L, l.buscar(deOtroVenue.id())!!.importeCentavos)
        // Un folio de un solo uso: el mismo id ya no se anota, ni aunque ahora sí se pudiera.
        l.transicionar(primera.id(), EstadoDelIntento.ANOTADO) { it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.OTRA_TARJETA) }
        assertEquals(ResultadoDeAnotarCancelacion.FolioUsado, l.anotarCancelacion(segunda, ambito))
        assertEquals(MotivoNoSalio.CANCELACION_YA_EXISTE, l.buscar(segunda.id())!!.motivoNoSalio)
        // Las NO_SALIO no vetan a una cancelación nueva de la misma original.
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(cancelacionDe(original), ambito))
    }

    /** T9: la fila de la cancelación toma el importe, el origen y `vinculado` de la original (quien llama no los sabe). */
    @Test fun `anotar cancelacion toma el importe de la original`() {
        val l = libreta()
        val original = aprobada(l, venta(origen = Origen.LOCAL_SIN_SOLICITUD))
        val c = cancelacionDe(original).copy(importeCentavos = 0)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(c, original.ambito()))
        val f = l.buscar(c.id())!!
        assertEquals(original.importeCentavos, f.importeCentavos)
        assertEquals(Origen.LOCAL_SIN_SOLICITUD, f.origen)
        assertFalse(f.vinculado)
    }

    /** T9 regla C: el veto de una cancelación sin fila inserta SU fila `NO_SALIO(INTERRUMPIDO)`; un `anotarCancelacion` después pierde. */
    @Test fun `vetar por corte una cancelacion sin fila`() {
        val l = libreta()
        val original = aprobada(l)
        val c = cancelacionDe(original).copy(importeCentavos = 0)
        assertTrue(l.vetarPorCorte(c))
        val f = l.buscar(c.id())!!
        assertEquals(EstadoDelIntento.NO_SALIO, f.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, f.motivoNoSalio)
        assertTrue(f.cortarAntesDeSalir)
        assertEquals(ResultadoDeAnotarCancelacion.FolioUsado, l.anotarCancelacion(c, original.ambito()))
        // Una VENTA sigue sin poder guardarse con importe 0.
        assertTrue(runCatching { l.vetarPorCorte(venta().copy(importeCentavos = 0)) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** r1 C: `marcarCancelacionEnviada` dice POR QUÉ no sale: la propia fila, la original o otra cancelación viva. */
    @Test fun `marcar cancelacion enviada distingue la causa`() {
        val l = libreta()
        val original = aprobada(l)
        val c = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(c, original.ambito()))
        val d = datos(huellaLectura = "c".repeat(64))
        l.transicionar(original.id(), EstadoDelIntento.APROBADO) { it.copy(estado = EstadoDelIntento.NO_SE) }
        assertEquals(ResultadoDeEnviarCancelacion.SinVentaAprobada, l.marcarCancelacionEnviada(c.id(), reloj, d))
        assertEquals(ResultadoDeEnviarCancelacion.YaNoAplica, l.marcarCancelacionEnviada(AttemptId.nuevo(), reloj, d))
        assertTrue(l.vetarPorCorte(l.buscar(c.id())!!))
        assertEquals(ResultadoDeEnviarCancelacion.YaNoAplica, l.marcarCancelacionEnviada(c.id(), reloj, d))
    }

    /** r1 D: una cancelación huérfana se cierra como duda, sin tocar reverso ni reporte (una cancelación nunca se reversa). */
    @Test fun `cerrar huerfana de una cancelacion`() {
        val l = libreta()
        val original = aprobada(l)
        val enviada = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(enviada, original.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(enviada.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        val f = l.cerrarHuerfana(enviada.id())!!
        assertEquals(EstadoDelIntento.NO_SE, f.estado)
        assertEquals(EstadoDelReverso.NINGUNO, f.reverso)
        assertEquals(EstadoDelReporte.NO_APLICA, f.reporte)
        assertFalse(f.pudoEntrar)
        l.transicionar(enviada.id(), EstadoDelIntento.NO_SE) { it.copy(estado = EstadoDelIntento.RECHAZADO, codigo = "51") }
        val anotada = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(anotada, original.ambito()))
        val g = l.cerrarHuerfana(anotada.id())!!
        assertEquals(EstadoDelIntento.NO_SALIO, g.estado)
        assertEquals(MotivoNoSalio.INTERRUMPIDO, g.motivoNoSalio)
    }

    /** Ronda 2 · S (R15-2): una lectura ya enviada no sale en NINGUNA cancelación, tampoco en la de otra venta. */
    @Test fun `una lectura ya enviada no sale en la cancelacion de otra venta`() {
        val l = libreta()
        val primera = aprobada(l)
        val segunda = aprobada(l)
        val a = cancelacionDe(primera)
        val b = cancelacionDe(segunda)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(a, primera.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(a.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(b, segunda.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.LecturaRepetida, l.marcarCancelacionEnviada(b.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(b.id(), reloj, datos(huellaLectura = "d".repeat(64))))
    }

    @Test fun `anotar cancelacion veta segunda en duda`() {
        val l = libreta()
        val original = aprobada(l)
        val primera = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(primera, original.ambito()))
        assertEquals(ResultadoDeAnotarCancelacion.YaExiste, l.anotarCancelacion(cancelacionDe(original), original.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(primera.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        l.transicionar(primera.id(), EstadoDelIntento.ENVIADO) { it.copy(estado = EstadoDelIntento.NO_SE) }
        assertEquals(ResultadoDeAnotarCancelacion.YaExiste, l.anotarCancelacion(cancelacionDe(original), original.ambito()))
        l.transicionar(primera.id(), EstadoDelIntento.NO_SE) { it.copy(estado = EstadoDelIntento.RECHAZADO, codigo = "TX_024") }
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(cancelacionDe(original), original.ambito()))
    }

    @Test fun `anotar cancelacion veta lectura repetida`() {
        val l = libreta()
        val original = aprobada(l)
        val primera = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(primera, original.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(primera.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertTrue(l.guardarRechazo(primera.id(), "TX_024", "No se pudo cancelar") is Arbitraje.Vigente)
        val segunda = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(segunda, original.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.LecturaRepetida, l.marcarCancelacionEnviada(segunda.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(segunda.id())!!.estado)
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(segunda.id(), reloj, datos(huellaLectura = "d".repeat(64))))
    }

    @Test fun `marcar cancelacion enviada rechaza lectura igual a la de la venta`() {
        val l = libreta()
        val original = aprobada(l)
        val c = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(c, original.ambito()))
        assertEquals(ResultadoDeEnviarCancelacion.LecturaRepetida, l.marcarCancelacionEnviada(c.id(), reloj, datos(huellaLectura = lecturaDeLaVenta)))
        assertEquals(ResultadoDeEnviarCancelacion.OtraTarjeta, l.marcarCancelacionEnviada(c.id(), reloj, datos(huellaTarjeta = "e".repeat(64), huellaLectura = "c".repeat(64))))
        assertFalse(l.marcarEnviado(c.id(), reloj, datos(huellaLectura = "c".repeat(64)))) // una cancelación no se salta las revisiones
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(c.id())!!.estado)
        assertTrue(l.vetarPorCorte(c))
        assertEquals(ResultadoDeEnviarCancelacion.YaNoAplica, l.marcarCancelacionEnviada(c.id(), reloj, datos(huellaLectura = "c".repeat(64))))
    }

    /** Mete la fila directo en la tabla, sin las revisiones de la libreta: el CAS de envío tiene que volver a revisar. */
    private fun insertarDirecto(l: Libreta, i: IntentoEntity) {
        assertTrue(l.db.dao().insertar(i.copy(procesoId = Libreta.PROCESO_ID)) != -1L)
    }

    @Test fun `anotar no acepta una cancelacion`() {
        val l = libreta()
        val c = cancelacionDe(aprobada(l))
        assertTrue(runCatching { l.anotar(c) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(l.buscar(c.id()))
    }

    @Test fun `dos cancelaciones anotadas directo la segunda no sale`() {
        val l = libreta()
        val original = aprobada(l)
        val primera = cancelacionDe(original)
        val segunda = cancelacionDe(original)
        insertarDirecto(l, primera)
        insertarDirecto(l, segunda)
        val c = datos(huellaLectura = "c".repeat(64))
        val d = datos(huellaLectura = "d".repeat(64))
        // Dos vivas a la vez: ninguna sale.
        assertEquals(ResultadoDeEnviarCancelacion.YaExiste, l.marcarCancelacionEnviada(primera.id(), reloj, c))
        assertEquals(ResultadoDeEnviarCancelacion.YaExiste, l.marcarCancelacionEnviada(segunda.id(), reloj, d))
        l.transicionar(segunda.id(), EstadoDelIntento.ANOTADO) { it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.CANCELACION_YA_EXISTE) }
        assertEquals(ResultadoDeEnviarCancelacion.Enviada, l.marcarCancelacionEnviada(primera.id(), reloj, c))
        // Con una ENVIADA y luego CANCELADA, otra anotada directo tampoco sale.
        val tercera = cancelacionDe(original)
        insertarDirecto(l, tercera)
        assertEquals(ResultadoDeEnviarCancelacion.YaExiste, l.marcarCancelacionEnviada(tercera.id(), reloj, d))
        l.transicionar(primera.id(), EstadoDelIntento.ENVIADO) { it.copy(estado = EstadoDelIntento.CANCELADA) }
        assertEquals(ResultadoDeEnviarCancelacion.YaExiste, l.marcarCancelacionEnviada(tercera.id(), reloj, d))
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(tercera.id())!!.estado)
    }

    @Test fun `marcar cancelacion enviada rechaza otro venue`() {
        val l = libreta()
        val original = aprobada(l)
        val deOtroVenue = cancelacionDe(original, venue = "venue-2")
        insertarDirecto(l, deOtroVenue)
        assertEquals(ResultadoDeEnviarCancelacion.OtroAmbito, l.marcarCancelacionEnviada(deOtroVenue.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(deOtroVenue.id())!!.estado)
        // El motor la cierra; si no, como cancelación viva vetaría a la siguiente.
        l.transicionar(deOtroVenue.id(), EstadoDelIntento.ANOTADO) { it.copy(estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.OTRO_AMBITO) }
        val deOtraCaja = cancelacionDe(original, posId = "2002")
        insertarDirecto(l, deOtraCaja)
        assertEquals(ResultadoDeEnviarCancelacion.OtroAmbito, l.marcarCancelacionEnviada(deOtraCaja.id(), reloj, datos(huellaLectura = "c".repeat(64))))
        assertEquals(EstadoDelIntento.ANOTADO, l.buscar(deOtraCaja.id())!!.estado)
    }

    // ---- I3: la fila ilegible (fase 3, bloque 1) ----

    /** I3: una fila ilegible se salta y NO se toca; las demás se cierran como siempre. Nunca se traduce a un desenlace. */
    @Test fun `una fila ilegible no detiene la recuperacion de las demas`() {
        var l = libreta()
        val enumRaro = enviada(l)
        val idViejo = enviada(l)
        val buena = enviada(l)
        val sql = l.db.openHelper.writableDatabase
        sql.execSQL("UPDATE intentos SET marca = 'DISCOVER' WHERE attemptId = ?", arrayOf(enumRaro.attemptId))
        sql.execSQL("UPDATE intentos SET attemptId = ? WHERE attemptId = ?", arrayOf(ID_DE_36, idViejo.attemptId))
        l = reabrir(l, proceso = UUID.randomUUID().toString())
        assertEquals(listOf(buena.attemptId), l.recuperarAlArrancar { emptyList() }.map { it.valor })
        assertEquals(EstadoDelIntento.NO_SE, l.buscar(buena.id())!!.estado)
        for (id in listOf(enumRaro.attemptId, ID_DE_36)) {
            l.db.openHelper.readableDatabase.query("SELECT estado FROM intentos WHERE attemptId = ?", arrayOf(id)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("la ilegible no se toca", "ENVIADO", c.getString(0))
            }
        }
    }

    /** Codex r3 #3 (vara 2): el punto único. Una fila que Room lee pero que no pasa la validación ni se entrega ni se escribe. */
    @Test fun `una fila que no pasa la validacion no se lee ni se escribe`() {
        val l = libreta()
        val v = aprobada(l)
        l.db.openHelper.writableDatabase.execSQL(
            "UPDATE intentos SET operationNumber = 99999999999 WHERE attemptId = ?", arrayOf(v.attemptId), // fuera de 1..9_999_999_999
        )
        val antes = cruda(l, v.attemptId)
        reloj += 1 // una escritura cambiaría `actualizadoEnMs`
        assertThrows(FilaIlegible::class.java) { l.buscar(v.id()) }
        assertThrows(FilaIlegible::class.java) { l.transicionar(v.id(), EstadoDelIntento.APROBADO) { it.copy(estado = EstadoDelIntento.NO_SE) } }
        assertThrows(FilaIlegible::class.java) { l.vetarPorCorte(v) }
        assertEquals("la fila ilegible no se toca", antes, cruda(l, v.attemptId))
    }

    /** La fila tal como está en la tabla, columna por columna, sin pasar por Room ni por la validación. */
    private fun cruda(l: Libreta, id: String): List<String?> =
        l.db.openHelper.readableDatabase.query("SELECT * FROM intentos WHERE attemptId = ?", arrayOf(id)).use { c ->
            assertTrue(c.moveToFirst())
            (0 until c.columnCount).map { c.getString(it) }
        }

    /** I3: una cancelación HERMANA que Room lee pero no pasa la validación hace tronar las dos revisiones que las recorren. */
    @Test fun `una cancelacion hermana que no pasa la validacion hace tronar anotar y enviar la cancelacion`() {
        val l = libreta()
        val original = aprobada(l)
        val c = cancelacionDe(original)
        assertEquals(ResultadoDeAnotarCancelacion.Anotada, l.anotarCancelacion(c, original.ambito()))
        // Sin la validación, esta hermana cerrada no vetaría nada y `c` saldría al banco.
        insertarDirecto(
            l,
            cancelacionDe(original).copy(attemptId = ID_DE_36, estado = EstadoDelIntento.NO_SALIO, motivoNoSalio = MotivoNoSalio.OTRA_TARJETA),
        )
        val antes = cruda(l, c.attemptId)
        reloj += 1
        assertThrows(FilaIlegible::class.java) { l.marcarCancelacionEnviada(c.id(), reloj, datos(huellaLectura = "c".repeat(64))) }
        val otra = cancelacionDe(original)
        assertThrows(FilaIlegible::class.java) { l.anotarCancelacion(otra, original.ambito()) }
        assertEquals(antes, cruda(l, c.attemptId))
        l.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM intentos WHERE attemptId = ?", arrayOf(otra.attemptId)).use {
            assertTrue(it.moveToFirst())
            assertEquals("la transacción que tronó no deja fila", 0, it.getInt(0))
        }
    }

    @Test fun `la salud cuenta avisos pendientes, el mas viejo, los sin hora y las filas ilegibles`() {
        val l = libreta()
        assertEquals(SaludDeLaLibreta(0, null, 0, 0), l.salud())
        val viejo = pared
        val a = reclamada(l)
        assertTrue(l.cerrarVuelta(a.id(), ClaseDelReverso.Confirmado(viejo), ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE))
        pared = viejo.plusSeconds(60)
        val b = reclamada(l)
        assertTrue(l.cerrarVuelta(b.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE))
        val c = reclamada(l)
        assertTrue(l.cerrarVuelta(c.id(), ClaseDelReverso.Confirmado(pared), ResultadoParaReportar.CONFIRMADO, EstadoDelIntento.NO_SE))
        val sql = l.db.openHelper.writableDatabase
        sql.execSQL("UPDATE intentos SET horaObservadaIso = NULL WHERE attemptId = ?", arrayOf(c.attemptId))
        // Las tres ilegibles: enum desconocido, id de 36, y un reverso abierto (RECLAMADO) de una fila NO_SE con valor desconocido.
        val enumRaro = enviada(l)
        sql.execSQL("UPDATE intentos SET marca = 'DISCOVER' WHERE attemptId = ?", arrayOf(enumRaro.attemptId))
        val idViejo = enviada(l)
        sql.execSQL("UPDATE intentos SET attemptId = ? WHERE attemptId = ?", arrayOf(ID_DE_36, idViejo.attemptId))
        val reversoRaro = reclamada(l)
        assertNotNull(l.transicionar(reversoRaro.id(), EstadoDelIntento.ENVIADO) { it.copy(estado = EstadoDelIntento.NO_SE) })
        sql.execSQL("UPDATE intentos SET reverso = 'DESCONOCIDO' WHERE attemptId = ?", arrayOf(reversoRaro.attemptId))
        assertEquals(
            SaludDeLaLibreta(reportesPendientes = 3, reportePendienteMasViejo = viejo, reportesSinHora = 1, filasIlegibles = 3),
            l.salud(),
        )
    }

    /** Codex r4 #3: Room pone el valor crudo en su mensaje; el lector lanza un error de mensaje fijo, sin la excepción original. */
    @Test fun `el error de una fila ilegible no trae el valor danado`() {
        val l = libreta()
        val v = enviada(l)
        l.db.openHelper.writableDatabase.execSQL("UPDATE intentos SET marca = 'VALORDANADO' WHERE attemptId = ?", arrayOf(v.attemptId))
        val e = assertThrows(FilaIlegible::class.java) { l.buscar(v.id()) }
        assertEquals("fila ilegible", e.message)
        assertNull(e.cause)
        assertFalse(e.toString(), "VALORDANADO" in e.toString())
    }

    /** D3: la consulta del veto es SQL (no lee filas) y sólo cuenta el proceso actual. Pasa hoy: fija la decisión. */
    @Test fun `una fila ilegible de otro proceso no veta la renovacion de llaves`() {
        var l = libreta()
        val v = enviada(l)
        l.db.openHelper.writableDatabase.execSQL("UPDATE intentos SET marca = 'DISCOVER' WHERE attemptId = ?", arrayOf(v.attemptId))
        val nuevo = UUID.randomUUID().toString()
        l = reabrir(l, proceso = nuevo)
        assertFalse(l.hayIntentosQuePuedenNecesitarReverso(nuevo, reloj, 45_000))
    }
}
