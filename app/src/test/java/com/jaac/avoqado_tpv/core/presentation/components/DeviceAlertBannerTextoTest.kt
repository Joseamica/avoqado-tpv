package com.jaac.avoqado_tpv.core.presentation.components

import com.jaac.avoqado_tpv.core.presentation.viewmodels.ConnectionRetryState
import com.jaac.avoqado_tpv.core.presentation.viewmodels.DeviceAlert
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAlertBannerTextoTest {
    @Test fun `P2 el texto del wifi sin salida no lo tapa el reintento`() {
        assertEquals(
            "El WiFi de esta terminal no está pasando datos" to "Apaga y prende el WiFi de la terminal, o usa el chip",
            textoDelBanner(DeviceAlert.WifiSinSalida(reiniciando = false), ConnectionRetryState.Failed),
        )
        assertEquals(
            "El WiFi de esta terminal no está pasando datos" to "Reiniciando el WiFi…",
            textoDelBanner(DeviceAlert.WifiSinSalida(reiniciando = true), ConnectionRetryState.Retrying),
        )
    }

    @Test fun `el servidor caido conserva su reemplazo de reintento`() {
        assertEquals(
            "No se pudo conectar" to "Los cobros se guardan y se envian al reconectar",
            textoDelBanner(DeviceAlert.ServerDown, ConnectionRetryState.Failed),
        )
        assertEquals(
            "Reconectando..." to "Verificando la conexion con el servidor",
            textoDelBanner(DeviceAlert.NoInternet, ConnectionRetryState.Retrying),
        )
    }
}
