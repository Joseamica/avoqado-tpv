package com.jaac.avoqado_campo.ui

import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad
import org.junit.Assert.assertEquals
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
}
