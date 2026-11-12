package com.jaac.avoqado_campo.ui

import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.Copia
import com.jaac.avoqado_campo.copia.MarcaLocal
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import com.jaac.avoqado_campo.identidad.ResultadoDelBorrado
import com.jaac.avoqado_campo.identidad.Sucursal
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavegacionTest {
    private fun yo(
        estado: EstadoDelCelular, sesion: String? = "v1", bloqueada: Boolean = false, necesitaPin: Boolean = false,
    ) = Identidad(estado, "s1", "Ana", emptyList(), sesion, bloqueada, necesitaPin, null)

    @Test fun `cada estado lleva a su pantalla`() {
        val tabla = listOf(
            Triple(yo(EstadoDelCelular.SIN_ACTIVAR, sesion = null), true, Destino.Activar),
            Triple(yo(EstadoDelCelular.VINCULADO, bloqueada = true), true, Destino.Pin),
            Triple(yo(EstadoDelCelular.VINCULADO, sesion = null), true, Destino.Pin),
            // R3-2: la sesión del servidor murió pero el PIN sin señal ya abrió: Inicio con la banda.
            Triple(yo(EstadoDelCelular.VINCULADO, necesitaPin = true), false, Destino.Inicio(necesitaPin = true)),
            Triple(yo(EstadoDelCelular.VINCULADO, bloqueada = true, necesitaPin = true), false, Destino.Pin),
            Triple(yo(EstadoDelCelular.VINCULADO), false, Destino.Inicio(necesitaPin = false)),
            Triple(yo(EstadoDelCelular.DESVINCULADO), true, Destino.Gracia(puedeActivarDeNuevo = true)),
            Triple(yo(EstadoDelCelular.DESVINCULADO), false, Destino.Gracia(puedeActivarDeNuevo = false)),
            Triple(yo(EstadoDelCelular.DADO_DE_BAJA), true, Destino.Gracia(puedeActivarDeNuevo = true)),
            Triple(yo(EstadoDelCelular.DADO_DE_BAJA, bloqueada = true), false, Destino.Gracia(puedeActivarDeNuevo = false)),
            Triple(yo(EstadoDelCelular.DESCONOCIDO), false, Destino.Desconocido),
            Triple(yo(EstadoDelCelular.DESCONOCIDO), true, Destino.Desconocido),
        )
        tabla.forEach { t ->
            assertEquals("${t.first} cola vacía=${t.second}", t.third, destinoDe(t.first, t.second))
        }
    }

    @Test fun `la banda nunca promete una subida que no corre`() {
        val sinSenal = "Sin señal · lo que hagas se guarda en el celular y se sube solo"
        assertEquals(sinSenal, textoDeBanda(true, EstadoDelCelular.VINCULADO, false, 0))
        assertEquals(sinSenal, textoDeBanda(true, EstadoDelCelular.DESCONOCIDO, false, 2))
        assertEquals("Subiendo 3…", textoDeBanda(false, EstadoDelCelular.VINCULADO, false, 3))
        assertEquals("Esperando tu PIN para subir 3", textoDeBanda(false, EstadoDelCelular.VINCULADO, true, 3))
        assertEquals("Subiendo 2…", textoDeBanda(false, EstadoDelCelular.DESVINCULADO, false, 2)) // la gracia sí sube
        assertEquals(null, textoDeBanda(false, EstadoDelCelular.DESCONOCIDO, false, 2))
        assertEquals(null, textoDeBanda(false, EstadoDelCelular.VINCULADO, false, 0))
    }

    // D2 (full-testing del bloque 2): con todo retenido en la gracia la banda decía «Subiendo 1…» y nada subía. Cuenta con el
    // mismo filtro que el Subidor y la SubidorDeGracia.
    @Test fun `«Subiendo N» cuenta solo lo que la pasada puede subir ahora`() {
        fun p(id: Long, venue: String = "v1", estado: String = EstadoPieza.PENDIENTE, tipo: String = TipoPieza.VENTA, depende: String? = null) =
            Pieza(id = id, tipo = tipo, llave = "k$id", staffId = "s1", venueId = venue, cuerpoJson = "{}", dependeDe = depende, estado = estado, creadaAt = 0, resumen = "")
        val ventaRetenida = p(1, estado = EstadoPieza.RETENIDA)
        val esperaSuVenta = p(2, tipo = TipoPieza.EVIDENCIA, depende = "k1")
        val otraSucursal = p(3, venue = "v2")
        val lista = p(4)
        val suVentaYaSubio = p(5, tipo = TipoPieza.EVIDENCIA, depende = "k99") // HECHA: ya no está en la cola viva
        val todas = listOf(ventaRetenida, esperaSuVenta, otraSucursal, lista, suVentaYaSubio)
        val vinculado = Identidad(
            EstadoDelCelular.VINCULADO, "s1", "Ana", listOf(Sucursal("v1", "Centro", null), Sucursal("v2", "Norte", null)), "v1", false, false, null,
        )
        assertEquals(2, cuantasPuedenSubir(todas, vinculado))
        // La gracia no tiene la regla de la sucursal; la corrección suelta no la sube (la rechaza).
        val gracia = vinculado.copy(estado = EstadoDelCelular.DESVINCULADO)
        assertEquals(3, cuantasPuedenSubir(todas + p(6, tipo = TipoPieza.EVIDENCIA), gracia))
        // El caso del reporte: la venta retenida y su evidencia esperándola → sin banda.
        assertNull(textoDeBanda(false, EstadoDelCelular.DESVINCULADO, false, cuantasPuedenSubir(listOf(ventaRetenida, esperaSuVenta), gracia)))
    }

    @Test fun `H-A1 - si la tienda no usa el seguimiento el Inicio no avisa del GPS ni de la bateria`() {
        fun d(gps: ResultadoGps?, turno: Boolean = true) = DatosDeInicio("Ana", 0, false, emptyList(), turno, gps)
        assertFalse(d(ResultadoGps.NoLoUsaLaTienda).cuidaElGps())
        assertTrue(d(ResultadoGps.Siguiendo).cuidaElGps())
        assertTrue(d(ResultadoGps.NoSigue("no diste permiso de ubicación")).cuidaElGps())
        assertTrue(d(null).cuidaElGps())
        assertFalse(d(ResultadoGps.Siguiendo, turno = false).cuidaElGps())
    }

    @Test fun `r9 - «Activar con un codigo nuevo» y DESCONOCIDO no truenan si el borrado falla - la pantalla dice el texto`() = runBlocking {
        assertNull(borrarSinTronar { throw IllegalStateException("el almacén cifrado falló") })
        assertEquals("No se pudo terminar. Intenta otra vez.", NO_SE_PUDO_TERMINAR)
        assertEquals(ResultadoDelBorrado.QUEDA_ALGO, borrarSinTronar { ResultadoDelBorrado.QUEDA_ALGO })
        // Cancelar (salir de la pantalla) no se traga.
        assertTrue(runCatching { borrarSinTronar { throw CancellationException("salió") } }.exceptionOrNull() is CancellationException)
    }

    // El mosaico «Mis SIMs» del Inicio cuenta lo mismo que la sección «En tu custodia»: suyas y aceptadas, sin las vendidas aquí.
    @Test fun `en custodia cuenta las aceptadas que no se vendieron en el celular`() {
        fun sim(serie: String, estado: String, marca: String? = null) =
            SimEnCopia(serie, estado, 1, "De caja", "0", marca, null, null, null, emptyList(), null)
        val c = Copia(
            4, "v1", "g",
            listOf(
                sim("1", "PROMOTER_HELD"), sim("2", "PROMOTER_HELD"), sim("3", "PROMOTER_HELD", MarcaLocal.VENDIDA_EN_EL_CELULAR),
                sim("4", "PROMOTER_PENDING"), sim("5", "SOLD"),
            ),
            ConfigDeSucursal("SIM", "ICCID", true, true, false, false, false, false, true), 0,
        )
        assertEquals(2, cuantasEnCustodia(c, "v1"))
        assertNull("sin copia no se inventa un número", cuantasEnCustodia(null, "v1"))
        // Codex rediseño #3: la copia de otra sucursal (falló la descarga al entrar a esta) no se cuenta.
        assertNull("copia de otra sucursal", cuantasEnCustodia(c, "v2"))
    }

    // Codex rediseño #4: «Entraste HH:MM» sólo para la entrada tocada en ESTE celular; si se usó la entrada que ya existía en
    // el SERVIDOR, su hora no es la del intento local y no se muestra.
    @Test fun `la hora de entrada solo es la de la entrada tocada aqui`() {
        val propia = com.jaac.avoqado_campo.cola.TurnoLocal(staffId = "s1", llaveEntrada = "k", timeEntryId = "te1", venueId = "v1", abiertoAt = 1_000L)
        assertEquals(1_000L, horaDeEntrada(propia, "s1"))
        assertNull("usó la entrada del SERVIDOR", horaDeEntrada(propia.copy(llaveEntrada = null), "s1"))
        assertNull("turno de otra persona", horaDeEntrada(propia, "s2"))
        assertNull("sin turno", horaDeEntrada(null, "s1"))
    }

    @Test fun `iniciales, la serie en grupos de 4 y la hora corta de Mexico`() {
        assertEquals("AM", iniciales("Ana  Martínez Ruiz"))
        assertEquals("A", iniciales("ana"))
        assertEquals("", iniciales(null))
        assertEquals("8952 0200 1234 5678 471F", enGrupos("8952020012345678471F"))
        val t = ZonedDateTime.of(2026, 11, 11, 9, 2, 0, 0, ZoneId.of("America/Mexico_City")).toInstant().toEpochMilli()
        assertEquals("9:02", horaCorta(t))
    }
}
