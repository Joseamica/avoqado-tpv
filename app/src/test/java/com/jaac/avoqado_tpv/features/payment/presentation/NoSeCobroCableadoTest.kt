package com.jaac.avoqado_tpv.features.payment.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * El CABLEADO del botón «No se cobró», que aquí no se puede ejecutar: `MainActivity` no se levanta con Hilt en estas pruebas
 * (`NexgoFlavorHiltGraphTest`) y `createComposeRule` sólo está en `androidTest` (`AngelPayPaymentScreenSalidaTest`). Se lee la fuente, como
 * en `EstadoDelProcesoTest`. Lo que decide se prueba de verdad en `FlujoDeNoSeCobroTest` y `DeclaracionDesdeElAvisoRoomTest`.
 */
class NoSeCobroCableadoTest {
    private val navegacion = File("src/main/java/com/jaac/avoqado_tpv/core/presentation/navigation/AppNavigation.kt").readText()
    private val dialogos = File("src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/NoSeCobroDesdeElAviso.kt").readText()

    @Test fun `AppNavigation consigue la declaracion por el punto de entrada de Hilt y le da el banner`() {
        assertThat(navegacion).contains("fun declaracionDesdeElAviso(): com.jaac.avoqado_tpv.features.payment.data.ledger.DeclaracionDesdeElAviso")
        assertThat(navegacion).contains("kioskEntryPoint.declaracionDesdeElAviso()")
        assertThat(navegacion).contains("BannerDeCobrosPendientes(")
    }

    @Test fun `el aviso sigue sin poder taparse, y el comentario ya no dice que no se puede descartar`() {
        assertThat(navegacion).doesNotContain("No se puede descartar a propósito")
        assertThat(navegacion).contains("se resuelve declarando")
    }

    @Test fun `el refresco corre mientras el banner esta a la vista y haya declarables, cada 15 s, sin la consulta de Caja`() {
        assertThat(dialogos).contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        assertThat(dialogos).contains("declaracion.refrescarUno(venueId, vigentesAlDia)")
        assertThat(dialogos).contains("REFRESCO_DEL_BANNER_MS = 15_000L")
        assertThat(dialogos).doesNotContain("ConsultaDelCierreDeCaja")
        assertThat(dialogos).doesNotContain("liberarDudasLocales")
        assertThat(dialogos).contains("if (!hayDeclarables) return@LaunchedEffect")
    }

    @Test fun `el boton sale sólo con declarables, junto a Revisar y Ayuda, con el texto arriba y los botones abajo`() {
        val banner = dialogos.substring(dialogos.indexOf("internal fun AvisoConBotones("))
        val texto = banner.indexOf("text = aviso")
        val fila = banner.indexOf("Row(")
        assertThat(texto).isAtLeast(0)
        assertThat(fila).isGreaterThan(texto)
        assertThat(banner).contains("if (mostrarNoSeCobro)")
        for (boton in listOf("\"No se cobró\"", "\"Revisar\"", "\"Ayuda\"")) assertThat(banner).contains(boton)
    }

    @Test fun `los textos de los dialogos son los acordados`() {
        for (texto in listOf(
            "¿Cuál no se cobró?",
            "¿No se cobró este cobro?",
            ". Revísalo antes de confirmar: queda tu nombre y la hora. Si el banco avisa que sí se cobró, se registra como cobrado.",
            "Sí, no se cobró",
            "Cancelar",
            "Pide a un gerente que ingrese su PIN",
            "PIN de gerente",
            "Confirmar",
        )) assertThat(dialogos).contains(texto)
    }

    @Test fun `el campo del PIN acepta lo mismo que el servidor y se escribe como contrasena`() {
        assertThat(dialogos).contains("isPassword = true")
        assertThat(dialogos).contains("KeyboardType.NumberPassword")
        assertThat(dialogos).contains("nuevo.length <= PIN_MAXIMO")
        assertThat(dialogos).contains("pinDeGerenteValido(pin)")
    }

    @Test fun `cada dialogo y el banner tienen su vista previa en 360 por 640`() {
        val previas = Regex("""@Preview\([^)]*widthDp = 360, heightDp = 640[^)]*\)\s*@Composable\s*private fun (\w+)\(""")
            .findAll(dialogos).map { it.groupValues[1] }.toList()
        assertThat(previas).containsAtLeast(
            "BannerConBotonPreview", "ListaPreview", "ConfirmacionPreview", "PinPreview", "AvisoPreview",
        )
    }
}
