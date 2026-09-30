package com.jaac.avoqado_tpv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSinSalidaDetectorTest {
    private fun lectura(
        ahoraMs: Long, tipo: NetworkType = NetworkType.WIFI, conectada: Boolean = true,
        hayServidor: Boolean = false, socketVivo: Boolean = false, externo: ProbeExterno = ProbeExterno.INALCANZABLE,
    ) = LecturaDeRed(ahoraMs, tipo, conectada, hayServidor, socketVivo, externo)

    @Test fun `P1 cualquier respuesta http prueba que el enlace pasa datos`() {
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(204))
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(503))
        assertEquals(ProbeExterno.ALCANZABLE, respuestaExterna(302))
        assertEquals(ProbeExterno.INALCANZABLE, respuestaExterna(null))
    }

    @Test fun `con servidor todo esta sano`() =
        assertEquals(VeredictoWifi.SANO, WifiSinSalidaDetector().evaluar(lectura(0, hayServidor = true)))

    @Test fun `P1 socket vivo nunca es wifi sin salida`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, socketVivo = true)))
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(120_000, socketVivo = true)))
    }

    @Test fun `P1 internet ajeno responde es problema de servidor, no del wifi`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, d.evaluar(lectura(0, externo = ProbeExterno.ALCANZABLE)))
        assertEquals(VeredictoWifi.SERVIDOR_O_DNS, d.evaluar(lectura(120_000, externo = ProbeExterno.ALCANZABLE)))
    }

    @Test fun `celular o sin red no es asunto de este detector`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, tipo = NetworkType.CELLULAR)))
        assertEquals(VeredictoWifi.SANO, d.evaluar(lectura(0, tipo = NetworkType.NONE, conectada = false)))
    }

    @Test fun `antes de 60 s es sospecha y a los 60 s se confirma`() {
        val d = WifiSinSalidaDetector()
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(0)))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(59_999)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(60_000)))
    }

    @Test fun `sin haber sondeado no se confirma aunque pase el tiempo`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0, externo = ProbeExterno.SIN_PROBAR))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(120_000, externo = ProbeExterno.SIN_PROBAR)))
    }

    @Test fun `si vuelve el servidor la cuenta empieza de cero`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0))
        d.evaluar(lectura(30_000, hayServidor = true))
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(70_000)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(130_000)))
    }

    @Test fun `reiniciar la cuenta tras un reinicio del wifi exige otros 60 s`() {
        val d = WifiSinSalidaDetector()
        d.evaluar(lectura(0)); d.evaluar(lectura(60_000))
        d.reiniciarCuenta()
        assertEquals(VeredictoWifi.SOSPECHA, d.evaluar(lectura(65_000)))
        assertEquals(VeredictoWifi.WIFI_SIN_SALIDA, d.evaluar(lectura(125_000)))
    }
}
