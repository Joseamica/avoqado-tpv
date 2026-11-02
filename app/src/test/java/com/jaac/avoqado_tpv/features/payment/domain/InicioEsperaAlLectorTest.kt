package com.jaac.avoqado_tpv.features.payment.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class InicioEsperaAlLectorTest(
    private val hayLectorBlumon: Boolean,
    private val lectorPendiente: Boolean,
    private val sinConexion: Boolean,
    private val bloqueaInicio: Boolean,
) {
    @Test
    fun `el Inicio permite efectivo sin red y conserva la espera del lector con conexion`() {
        assertEquals(
            bloqueaInicio,
            inicioEsperaAlLector(hayLectorBlumon, lectorPendiente, sinConexion),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "PAX={0}, lectorPendiente={1}, sinConexion={2}")
        fun casos(): List<Array<Boolean>> = listOf(
            arrayOf(true, true, true, false),
            arrayOf(true, true, false, true),
            arrayOf(true, false, true, false),
            arrayOf(true, false, false, false),
            arrayOf(false, true, true, false),
            arrayOf(false, true, false, false),
            arrayOf(false, false, true, false),
            arrayOf(false, false, false, false),
        )
    }
}
