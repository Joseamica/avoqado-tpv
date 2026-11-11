package com.jaac.avoqado_campo.ui

import com.jaac.avoqado_campo.gps.ResultadoGps
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `H-A1 - si la tienda no usa el seguimiento el Inicio no avisa del GPS ni de la bateria`() {
        fun d(gps: ResultadoGps?, turno: Boolean = true) = DatosDeInicio("Ana", 0, false, emptyList(), turno, gps)
        assertFalse(d(ResultadoGps.NoLoUsaLaTienda).cuidaElGps())
        assertTrue(d(ResultadoGps.Siguiendo).cuidaElGps())
        assertTrue(d(ResultadoGps.NoSigue("no diste permiso de ubicación")).cuidaElGps())
        assertTrue(d(null).cuidaElGps())
        assertFalse(d(ResultadoGps.Siguiendo, turno = false).cuidaElGps())
    }
}
