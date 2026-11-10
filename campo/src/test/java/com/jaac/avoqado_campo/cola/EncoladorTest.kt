package com.jaac.avoqado_campo.cola

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jaac.avoqado_campo.identidad.AlmacenEnMemoria
import com.jaac.avoqado_campo.identidad.DatosDeActivacion
import com.jaac.avoqado_campo.identidad.QuienSoy
import com.jaac.avoqado_campo.identidad.RelojAnclado
import com.jaac.avoqado_campo.identidad.Sesion
import com.jaac.avoqado_campo.identidad.Sucursal
import com.jaac.avoqado_campo.red.PorSubir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncoladorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: BaseDeCampo
    private lateinit var dao: PiezaDao
    private val almacen = AlmacenEnMemoria()
    private lateinit var quienSoy: QuienSoy
    private val ahora = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()
    private val reloj = RelojAnclado(almacen, ahoraDelCelular = { ahora }, transcurrido = { 0L }, arranque = { 1 })
    private lateinit var dirCola: File
    private lateinit var encolador: Encolador
    private val gson = Gson()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BaseDeCampo::class.java)
            .allowMainThreadQueries().build()
        dao = db.piezaDao()
        quienSoy = QuienSoy(almacen).also {
            it.guardarActivacion(
                DatosDeActivacion("c1", "cred", "s1", "Ana", Sesion("a", "r", "v1"), listOf(Sucursal("v1", "Centro", null))),
            )
        }
        dirCola = File(tmp.root, "cola")
        encolador = nuevoEncolador()
    }

    @After fun tearDown() = db.close()

    private fun nuevoEncolador() = Encolador(dao, quienSoy, reloj, SecuenciaDeCaptura(almacen), dirCola, env = "dev")

    private fun foto(nombre: String): String = tmp.newFile(nombre).also { it.writeBytes(ByteArray(10) { 7 }) }.absolutePath

    private fun ventaNueva(precio: BigDecimal = BigDecimal("50"), serie: String = "8952 140000 1") = VentaNueva(
        serialNumber = serie,
        precio = precio,
        isPortabilidad = true,
        venueId = "v1",
        evidencias = listOf(
            EvidenciaNueva("Vinculacion", "5512345678", foto("v-${System.nanoTime()}.jpg")),
            EvidenciaNueva("Portabilidad", "5587654321", foto("p-${System.nanoTime()}.jpg")),
        ),
        resumen = "SIM 89521400001",
    )

    private fun checada(archivo: String? = null) = ChecadaNueva("v1", 19.43, -99.13, 12.0, false, archivo, "Entrada")

    private fun evidencia(p: Pieza) = gson.fromJson(p.evidenciaJson, EvidenciaPendiente::class.java)
    private fun json(texto: String?) = JsonParser.parseString(texto).asJsonObject

    private suspend fun rechazar(p: Pieza): Long = p.id.also { dao.actualizar(p.copy(estado = "RECHAZADA", rechazoCode = "PRECIO_CAMBIO")) }

    private fun trigger(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    @Test fun `venta deja la VENTA y sus dos EVIDENCIAS con el texto exacto`() = runBlocking {
        val llave = encolador.venta(ventaNueva())
        val piezas = dao.pendientesDeVer().first()
        assertEquals(listOf("VENTA", "EVIDENCIA", "EVIDENCIA"), piezas.map { it.tipo })
        val venta = piezas[0]
        assertEquals(llave, venta.llave)
        assertEquals(
            "{\"llave\":\"$llave\",\"serialNumber\":\"89521400001\",\"precioVisto\":50.00,\"ocurrioAt\":\"2026-11-10T18:00:00Z\"," +
                "\"origenHora\":\"RELOJ_DEL_CELULAR\",\"isPortabilidad\":true}",
            venta.cuerpoJson,
        )
        assertEquals(huella("VENTA", "v1", venta.cuerpoJson!!), venta.huella)
        assertEquals("s1", venta.staffId)

        val (vin, por) = piezas.drop(1).map { it to evidencia(it) }
        assertEquals(llave, vin.first.dependeDe)
        assertEquals(llave, por.first.dependeDe)
        assertNull(vin.first.cuerpoJson)
        assertEquals("Vinculacion", vin.second.photoLabel)
        assertEquals("5512345678", vin.second.linkingNumber)
        assertNull(vin.second.portedNumber)
        assertEquals("Portabilidad", por.second.photoLabel)
        assertEquals("5587654321", por.second.portedNumber)
        assertNull(por.second.linkingNumber)
        assertEquals(listOf(1L, 2L), listOf(vin.second.captureSeq, por.second.captureSeq))
        assertEquals(listOf(0, 1), listOf(vin.second.replaceIndex, por.second.replaceIndex))
        assertEquals(quienSoy.aparatoId, vin.second.captureDeviceId)
        assertEquals("2026-11-10T18:00:00Z", vin.second.capturedAt)
        val f = fotosDe(vin.first.fotosJson).single()
        assertEquals(f.url, vin.second.fotoUrl)
        assertEquals("dev/venues/v1/campo/venta/2026-11-10/${vin.first.llave}_vinculacion.jpg", f.ruta)
        assertTrue(File(f.archivo).isFile)
        assertEquals(10L, f.bytes)
        assertFalse(f.subida)
        assertEquals(PorSubir(total = 3, ventas = 1, fotos = 2, checadas = 0, custodia = 0), dao.cuantasPorSubir().first())
        assertEquals(listOf(venta.huella), dao.huellasPorSubir())
        assertEquals(llave, dao.ventaConSerieEnCola("89521400001")?.llave)
        assertEquals(2, dao.archivosVivos().size)
    }

    @Test fun `precioVisto de caja sale 0 punto 00`() = runBlocking {
        encolador.venta(ventaNueva(BigDecimal.ZERO))
        assertTrue(dao.pendientesDeVer().first()[0].cuerpoJson!!.contains("\"precioVisto\":0.00,"))
    }

    @Test fun `si la segunda evidencia falla al insertar no queda nada`() = runBlocking {
        trigger(
            "CREATE TRIGGER falla BEFORE INSERT ON piezas WHEN NEW.tipo = 'EVIDENCIA' AND " +
                "(SELECT COUNT(*) FROM piezas WHERE tipo = 'EVIDENCIA') >= 1 BEGIN SELECT RAISE(ABORT, 'falla'); END",
        )
        val v = ventaNueva()
        try { encolador.venta(v); fail() } catch (_: Exception) {}
        assertTrue(dao.pendientesDeVer().first().isEmpty())
        // Las fotos originales siguen ahí para reintentar, y en `cola/` no quedó ninguna copia.
        v.evidencias.forEach { assertTrue(File(it.archivoFoto).isFile) }
        assertTrue(dirCola.listFiles().isNullOrEmpty())
        trigger("DROP TRIGGER falla")
        encolador.venta(v) // el reintento encuentra sus fotos
        assertEquals(3, dao.pendientesDeVer().first().size)
        v.evidencias.forEach { assertFalse(File(it.archivoFoto).exists()) } // tras el commit, el original se borra
    }

    @Test fun `captureSeq crece y sobrevive a un Encolador nuevo`() = runBlocking {
        encolador.venta(ventaNueva())
        nuevoEncolador().venta(ventaNueva(serie = "8952 140000 2"))
        val seqs = dao.pendientesDeVer().first().filter { it.tipo == "EVIDENCIA" }.map { evidencia(it).captureSeq }
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs)
    }

    @Test fun `entrada con foto lleva checkInPhotoUrl igual a la url de su foto y abre el turno`() = runBlocking {
        val llave = encolador.entrada(checada(foto("selfie.jpg")))
        val p = dao.porLlave(llave)!!
        val f = fotosDe(p.fotosJson).single()
        assertEquals(f.url, json(p.cuerpoJson)["checkInPhotoUrl"].asString)
        assertTrue(f.url.endsWith("%2Fcampo%2Fchecada%2F2026-11-10%2F${llave}_entrada.jpg?alt=media"))
        assertEquals(huella("ENTRADA", "v1", p.cuerpoJson!!), p.huella)
        val t = dao.turno().first()!!
        assertEquals(TurnoLocal(1, "s1", llave, null, "v1", ahora), t)
    }

    @Test fun `salida PorLlave lleva llaveEntrada y dependeDe y cierra el turno`() = runBlocking {
        val e = encolador.entrada(checada())
        val s = encolador.salida(checada())
        val p = dao.porLlave(s)!!
        assertEquals(e, json(p.cuerpoJson)["llaveEntrada"].asString)
        assertFalse(json(p.cuerpoJson).has("timeEntryId"))
        assertEquals(e, p.dependeDe)
        val t = dao.turno().first()!!
        assertNull(t.llaveEntrada)
        assertNull(t.timeEntryId)
    }

    @Test fun `salida sin turno abierto lanza`() = runBlocking {
        try { encolador.salida(checada()); fail() } catch (_: IllegalStateException) {}
        assertTrue(dao.pendientesDeVer().first().isEmpty())
    }

    @Test fun `corregirVenta crea llave nueva, deja la vieja DESCARTADA y mueve sus evidencias`() = runBlocking {
        val vieja = encolador.venta(ventaNueva())
        val id = rechazar(dao.porLlave(vieja)!!)
        val nueva = encolador.corregirVenta(id, BigDecimal("150"))
        assertNotEquals(vieja, nueva)
        assertEquals("DESCARTADA", dao.porLlave(vieja)!!.estado)
        val p = dao.porLlave(nueva)!!
        assertEquals("PENDIENTE", p.estado)
        assertEquals(nueva, json(p.cuerpoJson)["llave"].asString)
        assertTrue(p.cuerpoJson!!.contains("\"precioVisto\":150.00,"))
        assertEquals(huella("VENTA", "v1", p.cuerpoJson!!), p.huella)
        assertEquals(listOf(nueva, nueva), dao.pendientesDeVer().first().filter { it.tipo == "EVIDENCIA" }.map { it.dependeDe })
    }

    @Test fun `corregirEvidencia crea llave y captureSeq nuevos y conserva la foto si no hay otra`() = runBlocking {
        encolador.venta(ventaNueva())
        val vieja = dao.pendientesDeVer().first().first { it.tipo == "EVIDENCIA" }
        val nueva = encolador.corregirEvidencia(rechazar(vieja), "5500000000", null)
        assertEquals("DESCARTADA", dao.porLlave(vieja.llave)!!.estado)
        val p = dao.porLlave(nueva)!!
        assertEquals("5500000000", evidencia(p).linkingNumber)
        assertEquals(3L, evidencia(p).captureSeq)
        assertEquals(vieja.dependeDe, p.dependeDe)
        assertEquals(fotosDe(vieja.fotosJson), fotosDe(p.fotosJson))
    }

    @Test fun `la entrada y su turno van juntos - si el turno falla tampoco queda la pieza`() = runBlocking {
        trigger("CREATE TRIGGER falla BEFORE INSERT ON turno BEGIN SELECT RAISE(ABORT, 'falla'); END")
        val selfie = foto("selfie.jpg")
        try { encolador.entrada(checada(selfie)); fail() } catch (_: Exception) {}
        assertTrue(dao.pendientesDeVer().first().isEmpty())
        assertNull(dao.turno().first())
        assertTrue(File(selfie).isFile)
        assertTrue(dirCola.listFiles().isNullOrEmpty())
    }

    @Test fun `si mover la foto falla no queda la pieza`() = runBlocking {
        try { encolador.entrada(checada(File(tmp.root, "no-existe.jpg").absolutePath)); fail() } catch (_: Exception) {}
        assertTrue(dao.pendientesDeVer().first().isEmpty())
        assertNull(dao.turno().first())
    }

    @Test fun `usarEntrada rehace la salida encolada con timeEntryId, sin llaveEntrada ni dependeDe`() = runBlocking {
        val e = encolador.entrada(checada())
        val s = encolador.salida(checada(foto("salida.jpg")))
        val vieja = dao.porLlave(s)!!
        encolador.usarEntrada(dao.porLlave(e)!!.id, "te9", "v1")
        assertEquals("DESCARTADA", dao.porLlave(e)!!.estado)
        assertEquals("DESCARTADA", dao.porLlave(s)!!.estado)
        val nueva = dao.pendientesDeVer().first().single()
        assertEquals("SALIDA", nueva.tipo)
        assertNotEquals(s, nueva.llave)
        val c = json(nueva.cuerpoJson)
        assertEquals("te9", c["timeEntryId"].asString)
        assertEquals(nueva.llave, c["llave"].asString)
        assertFalse(c.has("llaveEntrada"))
        assertNull(nueva.dependeDe)
        assertNotEquals(vieja.huella, nueva.huella)
        assertEquals(huella("SALIDA", "v1", nueva.cuerpoJson!!), nueva.huella)
        val cv = json(vieja.cuerpoJson)
        for (k in listOf("ocurrioAt", "latitude", "longitude", "checkOutPhotoUrl")) assertEquals(cv[k], c[k])
        assertEquals(vieja.fotosJson, nueva.fotosJson)
    }

    @Test fun `usarEntrada con el venueId de otra sucursal no cambia nada`() = runBlocking {
        val e = encolador.entrada(checada())
        encolador.salida(checada())
        val antes = dao.pendientesDeVer().first()
        val turnoAntes = dao.turno().first()
        try { encolador.usarEntrada(dao.porLlave(e)!!.id, "te9", "v2"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(antes, dao.pendientesDeVer().first())
        assertEquals(turnoAntes, dao.turno().first())
    }

    @Test fun `purgar no borra la venta HECHA con evidencia PENDIENTE ni la ENTRADA del turno abierto`() = runBlocking {
        val v = encolador.venta(ventaNueva())
        dao.marcarHecha(dao.porLlave(v)!!, "{\"paymentId\":\"p1\"}", "HECHA")
        val e = encolador.entrada(checada())
        dao.marcarHecha(dao.porLlave(e)!!, "{\"timeEntryId\":\"te1\"}", "HECHA")
        val suelta = encolador.aceptarCustodia(listOf(SimConVersion("8952", 3)), "v1")
        dao.marcarHecha(dao.porLlave(suelta)!!, "{}", "HECHA")
        dao.purgarTerminalesAntesDe(ahora + 1)
        assertEquals("HECHA", dao.porLlave(v)?.estado)
        assertEquals("HECHA", dao.porLlave(e)?.estado)
        assertNull(dao.porLlave(suelta)) // control: lo terminal sin dependientes sí se va
    }

    @Test fun `marcarHecha de la ENTRADA del turno guarda su timeEntryId y la salida va PorId`() = runBlocking {
        val e = encolador.entrada(checada())
        dao.marcarHecha(dao.porLlave(e)!!, "{\"timeEntryId\":\"te1\",\"repetida\":false}", "HECHA")
        assertEquals("te1", dao.turno().first()!!.timeEntryId)
        val s = encolador.salida(checada())
        val p = dao.porLlave(s)!!
        assertEquals("te1", json(p.cuerpoJson)["timeEntryId"].asString)
        assertFalse(json(p.cuerpoJson).has("llaveEntrada"))
        assertNull(p.dependeDe)
    }

    @Test fun `descartarConHijos de una venta deja su evidencia RECHAZADA SU_VENTA_SE_DESCARTO`() = runBlocking {
        val v = encolador.venta(ventaNueva())
        dao.descartarConHijos(dao.porLlave(v)!!.id, "SU_VENTA_SE_DESCARTO")
        assertEquals("DESCARTADA", dao.porLlave(v)!!.estado)
        val hijos = dao.pendientesDeVer().first()
        assertEquals(2, hijos.size)
        hijos.forEach {
            assertEquals("RECHAZADA", it.estado)
            assertEquals("SU_VENTA_SE_DESCARTO", it.rechazoCode)
            assertEquals("Su venta ya no se va a subir", it.rechazoMensaje)
        }
    }

    @Test fun `descartarConHijos de la ENTRADA del turno cierra el turno`() = runBlocking {
        val e = encolador.entrada(checada())
        dao.descartarConHijos(dao.porLlave(e)!!.id, "SU_ENTRADA_SE_DESCARTO")
        assertNull(dao.turno().first()!!.llaveEntrada)
    }

    @Test fun `tras usarEntrada sin salida encolada, la salida va con timeEntryId y sin dependeDe`() = runBlocking {
        val e = encolador.entrada(checada())
        encolador.usarEntrada(dao.porLlave(e)!!.id, "te9", "v1")
        val p = dao.porLlave(encolador.salida(checada()))!!
        assertEquals("te9", json(p.cuerpoJson)["timeEntryId"].asString)
        assertFalse(json(p.cuerpoJson).has("llaveEntrada"))
        assertNull(p.dependeDe)
    }

    @Test fun `salida de otra sucursal que la del turno lanza y no escribe nada`() = runBlocking {
        encolador.entrada(checada())
        val antes = dao.pendientesDeVer().first()
        val turnoAntes = dao.turno().first()
        try { encolador.salida(checada().copy(venueId = "v2")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(antes, dao.pendientesDeVer().first())
        assertEquals(turnoAntes, dao.turno().first())
    }

    @Test fun `entrada con un turno ya abierto lanza y no escribe nada`() = runBlocking {
        encolador.entrada(checada())
        val turnoAntes = dao.turno().first()
        try { encolador.entrada(checada()); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, dao.pendientesDeVer().first().size)
        assertEquals(turnoAntes, dao.turno().first())
    }

    @Test fun `corregir sólo una pieza RECHAZADA`() = runBlocking {
        val v = encolador.venta(ventaNueva())
        try { encolador.corregirVenta(dao.porLlave(v)!!.id, BigDecimal("150")); fail() } catch (_: IllegalArgumentException) {}
        val ev = dao.pendientesDeVer().first().first { it.tipo == "EVIDENCIA" }
        try { encolador.corregirEvidencia(ev.id, "5500000000", null); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(3, dao.pendientesDeVer().first().size)
    }

    @Test fun `dos ventas a la vez - la que falla no deja a la otra sin sus fotos`() = runBlocking {
        trigger(
            "CREATE TRIGGER falla BEFORE INSERT ON piezas WHEN NEW.resumen = 'falla' AND NEW.tipo = 'EVIDENCIA' AND " +
                "(SELECT COUNT(*) FROM piezas WHERE resumen = 'falla' AND tipo = 'EVIDENCIA') >= 1 " +
                "BEGIN SELECT RAISE(ABORT, 'falla'); END",
        )
        // ponytail: carrera real, sin ganchos; 30 vueltas para que el cruce (el catch de A durante la transacción de B) salga.
        repeat(30) {
            val fallida = ventaNueva(serie = "8952 1400 A$it").copy(resumen = "falla")
            val buena = ventaNueva(serie = "8952 1400 B$it")
            coroutineScope {
                val a = async(Dispatchers.IO) { runCatching { encolador.venta(fallida) } }
                val b = async(Dispatchers.IO) { encolador.venta(buena) }
                assertTrue(a.await().isFailure)
                b.await()
            }
            fallida.evidencias.forEach { assertTrue(File(it.archivoFoto).isFile) }
        }
        val piezas = dao.pendientesDeVer().first()
        assertEquals(90, piezas.size)
        assertTrue(piezas.none { it.resumen == "falla" })
        piezas.flatMap { fotosDe(it.fotosJson) }.forEach { assertTrue("sin archivo: ${it.archivo}", File(it.archivo).isFile) }
        assertEquals(60, dirCola.listFiles()!!.size)
    }

    @Test fun `custodia lleva el cuerpo exacto`() = runBlocking {
        val a = encolador.aceptarCustodia(listOf(SimConVersion("A1", 2), SimConVersion("B2", 0)), "v1")
        assertEquals("{\"sims\":[{\"serialNumber\":\"A1\",\"custodyVersion\":2},{\"serialNumber\":\"B2\",\"custodyVersion\":0}]}", dao.porLlave(a)!!.cuerpoJson)
        val r = encolador.rechazarCustodia(SimConVersion("C3", 5), "v1")
        val p = dao.porLlave(r)!!
        assertEquals("CUSTODIA_RECHAZAR", p.tipo)
        assertEquals("{\"serialNumber\":\"C3\",\"custodyVersion\":5}", p.cuerpoJson)
        assertEquals(huella("CUSTODIA_RECHAZAR", "v1", p.cuerpoJson!!), p.huella)
    }

    @Test fun `evidenciaSuelta lleva paymentId y verificationId sin dependeDe`() = runBlocking {
        val k = encolador.evidenciaSuelta(EvidenciaSueltaNueva("v1", "pay1", "ver1", "Vinculacion", "5511112222", foto("s.jpg"), "Corregir"))
        val p = dao.porLlave(k)!!
        assertNull(p.dependeDe)
        val ev = evidencia(p)
        assertEquals("pay1", ev.paymentId)
        assertEquals("ver1", ev.verificationId)
        assertEquals(1L, ev.captureSeq)
    }

    @Test fun `mientras se borra el celular el Encolador lanza y no deja fila`() = runBlocking {
        quienSoy.marcarLimpiando(true)
        try { encolador.venta(ventaNueva()); fail() } catch (_: CelularBorrandose) {}
        assertTrue(dao.pendientesDeVer().first().isEmpty())
        assertTrue(quienSoy.limpiando)
    }

    @Test fun `una segunda venta de la misma SIM viva lanza y no escribe nada`() = runBlocking {
        encolador.venta(ventaNueva())
        val otra = ventaNueva(serie = "8952140000 1") // la misma, escrita distinto
        try { encolador.venta(otra); fail() } catch (_: SimYaEnCola) {}
        assertEquals(3, dao.pendientesDeVer().first().size)
        otra.evidencias.forEach { assertTrue(File(it.archivoFoto).isFile) } // sus fotos siguen donde estaban
        // Descartada la primera, ya se puede volver a vender.
        dao.ventasVivas().single().let { dao.actualizar(it.copy(estado = EstadoPieza.DESCARTADA)) }
        encolador.venta(otra)
        Unit
    }
}
