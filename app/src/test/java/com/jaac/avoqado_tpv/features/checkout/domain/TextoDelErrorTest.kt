package com.jaac.avoqado_tpv.features.checkout.domain

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.domain.models.ApiException
import org.junit.Test
import java.io.IOException

class TextoDelErrorTest {
    private val respaldo = "No se pudo iniciar el cobro"

    @Test fun `un 403 tipado muestra el texto de hoy sin el prefijo HTTP`() {
        val e = ApiException.HttpError(403, "No tienes permiso para crear órdenes.", "No tienes permiso para crear órdenes.")

        assertThat(e.message).startsWith("HTTP 403:")
        assertThat(textoDelErrorParaElCajero(e, respaldo)).isEqualTo("No tienes permiso para crear órdenes.")
    }

    @Test fun `un 503 y un 400 muestran su userMessage`() {
        assertThat(textoDelErrorParaElCajero(ApiException.HttpError(503, "Error al crear orden: 503", "Error al crear orden: 503"), respaldo))
            .isEqualTo("Error al crear orden: 503")
        assertThat(textoDelErrorParaElCajero(ApiException.HttpError(400, "x"), respaldo))
            .isEqualTo("Solicitud inválida. Por favor intente nuevamente.")
    }

    @Test fun `un NetworkError muestra el texto en espanol y no el crudo de la IOException`() {
        val e = ApiException.NetworkError(IOException("Unable to resolve host"))

        assertThat(textoDelErrorParaElCajero(e, respaldo)).isEqualTo("Error de conexión. Verifique su internet e intente nuevamente.")
    }

    @Test fun `una excepcion cualquiera conserva su mensaje y sin mensaje usa el respaldo`() {
        assertThat(textoDelErrorParaElCajero(Exception("Invalid response from server"), respaldo)).isEqualTo("Invalid response from server")
        assertThat(textoDelErrorParaElCajero(RuntimeException(), respaldo)).isEqualTo(respaldo)
    }
}
