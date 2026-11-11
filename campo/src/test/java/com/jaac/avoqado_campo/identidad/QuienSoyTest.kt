package com.jaac.avoqado_campo.identidad

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuienSoyTest {
    private val almacen = AlmacenEnMemoria()
    private val suc = Sucursal("v1", "Centro", VerificadorPin("PBKDF2-SHA256", 100_000, "c2Fs", "aGFzaA=="))
    private val sesion = Sesion("a", "r", "v1")
    private fun activada() = QuienSoy(almacen).also {
        it.guardarActivacion(DatosDeActivacion("c1", "cred", "s1", "Ana", sesion, listOf(suc)))
    }

    @Test fun `aparatoId estable y rota con borrarTodo`() {
        val q = QuienSoy(almacen)
        val id = q.aparatoId
        assertEquals(id, q.aparatoId)
        assertEquals(id, QuienSoy(almacen).aparatoId)
        q.borrarTodo()
        assertNotEquals(id, q.aparatoId)
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, q.estado)
    }

    @Test fun `guardarActivacion deja vinculado`() {
        val q = activada()
        assertEquals(EstadoDelCelular.VINCULADO, q.estado)
        assertEquals("cred", q.credencial)
        assertEquals("c1", q.celularId)
        assertEquals(sesion, q.sesion)
        assertEquals(listOf(suc), q.sucursales)
        assertEquals("v1", q.identidad.value.venueIdDeLaSesion)
        assertFalse(q.necesitaPin); assertFalse(q.bloqueada)
    }

    @Test fun `un colector suscrito ve necesitaPin y despues desvinculado`() = runTest(UnconfinedTestDispatcher()) {
        val q = activada()
        val vistas = mutableListOf<Identidad>()
        val job = launch { q.identidad.collect { vistas += it } }
        q.marcarNecesitaPin(true)
        q.marcarDesvinculado("2026-11-20T00:00:00Z")
        job.cancel()
        assertEquals(3, vistas.size)
        assertTrue(vistas[1].necesitaPin); assertEquals(EstadoDelCelular.VINCULADO, vistas[1].estado)
        assertEquals(EstadoDelCelular.DESVINCULADO, vistas[2].estado)
        assertEquals("2026-11-20T00:00:00Z", vistas[2].graciaHasta)
    }

    @Test fun `marcarDesvinculado conserva credencial y celularId`() {
        val q = activada()
        q.marcarDesvinculado("g")
        assertEquals(EstadoDelCelular.DESVINCULADO, q.estado)
        assertEquals("cred", q.credencial); assertEquals("c1", q.celularId)
    }

    @Test fun `latido desvinculado con lista vacia conserva sucursales`() {
        val q = activada()
        q.guardarLatido(DatosDeLatido("DESVINCULADO", "g", null, emptyList()))
        assertEquals(EstadoDelCelular.DESVINCULADO, q.estado)
        assertEquals(listOf(suc), q.sucursales)
        assertEquals("g", q.graciaHasta)
    }

    @Test fun `latido vinculado reemplaza sucursales y nombre`() {
        val q = activada()
        val nueva = Sucursal("v2", "Norte", null)
        q.guardarLatido(DatosDeLatido("VINCULADO", null, "Ana B", listOf(nueva)))
        assertEquals(listOf(nueva), q.sucursales); assertEquals("Ana B", q.nombre)
    }

    @Test fun `guardarEntrada limpia necesitaPin y bloqueada`() {
        val q = activada()
        q.marcarNecesitaPin(true); q.marcarBloqueada(true)
        q.guardarEntrada(DatosDeEntrada(Sesion("a2", "r2", "v1"), listOf(suc)))
        assertFalse(q.necesitaPin); assertFalse(q.bloqueada)
        assertEquals("a2", q.sesion?.accessToken)
    }

    @Test fun `guardarTokens, desconocido y persistencia`() {
        val q = activada()
        q.guardarTokens("a3", "r3", "v9")
        assertEquals(Sesion("a3", "r3", "v9"), QuienSoy(almacen).sesion)
        q.marcarDesconocido()
        assertEquals(EstadoDelCelular.DESCONOCIDO, QuienSoy(almacen).estado)
        assertNotNull(QuienSoy(almacen).credencial)
    }

    @Test fun `guardarSi descarta la respuesta de una generacion anterior`() {
        val q = activada()
        val g1 = q.generacion
        q.borrarTodo()
        q.guardarActivacion(DatosDeActivacion("c2", "cred2", "s2", "Beto", sesion, listOf(suc)))
        val g2 = q.generacion
        assertNotEquals(g1, g2)
        val antes = q.identidad.value
        assertFalse(q.guardarSi(g1) { guardarLatido(DatosDeLatido("DESVINCULADO", "g", null, emptyList())) })
        assertEquals(antes, q.identidad.value)
        assertTrue(q.guardarSi(g2) { guardarLatido(DatosDeLatido("DESVINCULADO", "g", null, emptyList())) })
        assertEquals(EstadoDelCelular.DESVINCULADO, q.estado)
    }

    @Test fun `guardarTokensSi rechaza otra generacion y otro refresh`() {
        val q = activada()
        val g = q.generacion
        assertFalse(q.guardarTokensSi(g, "otro", "a9", "r9", "v1"))
        assertFalse(q.guardarTokensSi("vieja", "r", "a9", "r9", "v1"))
        assertEquals("a", q.sesion?.accessToken)
        assertTrue(q.guardarTokensSi(g, "r", "a9", "r9", "v1"))
        assertEquals(Sesion("a9", "r9", "v1"), q.sesion)
    }

    @Test fun `Sesion no imprime tokens`() {
        assertFalse(sesion.toString().contains("a") && sesion.toString().contains("refreshToken"))
        assertFalse(sesion.toString().contains("accessToken"))
    }

    @Test fun `aparatoId concurrente es uno solo`() {
        val q = QuienSoy(AlmacenEnMemoria())
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        (1..8).map { Thread { ids += q.aparatoId } }.also { ts -> ts.forEach { it.start() }; ts.forEach { it.join() } }
        assertEquals(1, ids.toSet().size)
    }

    @Test fun `borrarTodo limpia los fallos del PIN`() {
        val q = activada()
        almacen.escribir(mapOf("pin.fallos" to "5", "pin.bloqueadoHasta" to "9", "pin.bloqueo" to "x"))
        q.borrarTodo()
        assertEquals(null, almacen.leer("pin.fallos")); assertEquals(null, almacen.leer("pin.bloqueo"))
    }

    // ---------- I1 · el estado no regresa (R8-2, v9) ----------

    @Test fun `I1 - desvinculado no regresa con entrada, latido vinculado, baja ni tokens`() {
        val q = activada()
        q.marcarDesvinculado("g")
        q.guardarEntrada(DatosDeEntrada(Sesion("a2", "r2", "v1"), listOf(Sucursal("v2", "Norte", null))))
        q.guardarLatido(DatosDeLatido("VINCULADO", null, "Otra", listOf(Sucursal("v2", "Norte", null))))
        q.guardarLatido(DatosDeLatido("DADO_DE_BAJA", null, null, emptyList()))
        q.guardarTokens("a3", "r3", "v1")
        assertEquals(EstadoDelCelular.DESVINCULADO, q.estado)
        assertEquals(sesion, q.sesion)
        assertEquals(listOf(suc), q.sucursales)
        assertEquals("Ana", q.nombre)
        assertEquals("g", q.graciaHasta)
        assertFalse(q.volverDeLaBaja())
    }

    @Test fun `I1 - desconocido no regresa ni a desvinculado`() {
        val q = activada()
        q.marcarDesconocido()
        q.guardarLatido(DatosDeLatido("VINCULADO", null, null, listOf(suc)))
        q.marcarDesvinculado("g")
        assertEquals(EstadoDelCelular.DESCONOCIDO, q.estado)
        assertEquals(null, q.graciaHasta)
    }

    @Test fun `I1 - dado de baja si vuelve a vinculado`() {
        val q = activada()
        q.guardarLatido(DatosDeLatido("DADO_DE_BAJA", null, null, emptyList()))
        q.guardarLatido(DatosDeLatido("VINCULADO", null, null, listOf(suc)))
        assertEquals(EstadoDelCelular.VINCULADO, q.estado)
    }

    @Test fun `I1 - activar solo desde SIN_ACTIVAR, de desconocido o desvinculado se sale con borrarTodo`() {
        val otra = DatosDeActivacion("c2", "cred2", "s2", "Beto", Sesion("a2", "r2", "v2"), listOf(Sucursal("v2", "Norte", null)))
        val q = activada()
        for (llevar in listOf<(QuienSoy) -> Unit>({}, { it.marcarDesvinculado("g") }, { it.marcarDesconocido() })) {
            llevar(q)
            val antes = q.identidad.value
            val g = q.generacion
            q.guardarActivacion(otra)
            assertEquals(antes, q.identidad.value)
            assertEquals(g, q.generacion); assertEquals("cred", q.credencial); assertEquals(sesion, q.sesion)
        }
        q.borrarTodo()
        assertEquals(EstadoDelCelular.SIN_ACTIVAR, q.estado)
        q.guardarActivacion(otra)
        assertEquals(EstadoDelCelular.VINCULADO, q.estado); assertEquals("cred2", q.credencial)
    }

    @Test fun `r3 - la instantanea es de UNA persona aunque otro hilo borre y active a otra a la mitad de la lectura`() {
        val q = activada()
        val deAna = q.generacion
        val beto = DatosDeActivacion("c2", "cred2", "s2", "Beto", Sesion("a2", "r2", "v2"), listOf(Sucursal("v2", "Norte", null)))
        var otro: Thread? = null
        // A la mitad de la lectura (justo después de la credencial), otro hilo borra a Ana y activa a Beto.
        almacen.despuesDeLeer("credencial") { otro = kotlin.concurrent.thread { q.borrarTodo(); q.guardarActivacion(beto) }.also { it.join(500) } }
        val yo = q.instantanea()
        otro!!.join()
        assertEquals("cred", yo.credencial)
        assertEquals(deAna, yo.generacion)
        assertEquals("s1", yo.staffId)
        assertEquals("v1", yo.sesion?.venueId)
        assertEquals(listOf(suc), yo.sucursales)
        assertEquals("cred2", q.credencial) // Beto sí quedó, después
        assertFalse(q.guardarSi(yo.generacion) {})
    }

    @Test fun `r3 - empezarLimpiezaSi sólo marca a la persona que se decidió borrar y una sola vez`() {
        val q = activada()
        val g = q.generacion
        assertFalse(q.empezarLimpiezaSi("otra"))
        assertFalse(q.limpiando)
        assertTrue(q.empezarLimpiezaSi(g))
        assertTrue(q.limpiando); assertFalse(q.limpiezaConfirmada)
        assertNotEquals(g, q.generacion)
        assertFalse(q.empezarLimpiezaSi(q.generacion)) // ya hay un borrado en curso
        q.confirmarLimpieza()
        assertTrue(q.limpiando); assertTrue(q.limpiezaConfirmada)
        q.marcarLimpiando(false)
        assertFalse(q.limpiando)
    }

    @Test fun `la instantanea no imprime la credencial`() {
        assertFalse(activada().instantanea().toString().contains("cred"))
    }

    @Test fun `r10 - si commit devuelve false, escribir lanza AlmacenNoGuardo en vez de seguir como si se hubiera guardado`() {
        val editor = io.mockk.mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        io.mockk.every { editor.commit() } returns false
        assertTrue(runCatching { guardar(editor, mapOf("limpiando" to "true")) }.exceptionOrNull() is AlmacenNoGuardo)
        io.mockk.every { editor.commit() } returns true
        guardar(editor, mapOf("limpiando" to null))
        io.mockk.verify { editor.putString("limpiando", "true"); editor.remove("limpiando") }
    }

    @Test fun `r10 - si el almacen no guarda, QuienSoy publica igual lo que quedo en memoria y la excepcion sube`() {
        val q = activada()
        almacen.fallarAlEscribir = { true }
        assertTrue(runCatching { q.marcarBloqueada(true) }.exceptionOrNull() is IllegalStateException)
        assertTrue(q.identidad.value.bloqueada) // en memoria sí cambió: la pantalla lo ve
    }
}
