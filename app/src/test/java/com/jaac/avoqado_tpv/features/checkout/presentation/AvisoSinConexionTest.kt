package com.jaac.avoqado_tpv.features.checkout.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class AvisoSinConexionTest {
    @Test fun `sin red singular y plural se leen como venta guardada`() {
        assertEquals("Sin conexión · 1 venta guardada", AvisoSinConexion(true, 1, 0).texto)
        assertEquals("Sin conexión · 3 ventas guardadas · 2 con problema", AvisoSinConexion(true, 3, 2).texto)
    }
    @Test fun `con red pendientes siguen visibles y problema sin pendientes no dice subiendo`() {
        assertEquals("Subiendo 1 venta guardada", AvisoSinConexion(false, 1, 0).texto)
        assertEquals("Subiendo 3 ventas guardadas · 1 con problema", AvisoSinConexion(false, 3, 1).texto)
        assertEquals("1 con problema", AvisoSinConexion(false, 0, 1).texto)
    }
}
