package com.jaac.avoqado_campo.fotos

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jaac.avoqado_campo.copia.ConfigDeSucursal
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.venta.AccionesDeVenta
import com.jaac.avoqado_campo.venta.EstadoVenta
import com.jaac.avoqado_campo.venta.Slot
import com.jaac.avoqado_campo.venta.TipoDePrecio
import com.jaac.avoqado_campo.venta.VentaContenido
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/** Founder, 26-nov (simplificar): mientras se copia la foto de la galería, la pantalla queda bloqueada y «atrás» no sale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1280dp")
class GuardandoLaFotoTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `mientras se guarda la foto no se toca nada y atras no sale`() {
        val toques = mutableListOf<String>()
        compose.setContent {
            TemaCampo {
                BackHandler { toques += "atrás" } // la navegación, debajo de la pantalla
                VentaContenido(
                    EstadoVenta(
                        sim = SimEnCopia("8952020012345678901F", "PROMOTER_HELD", 1, "Prepago", "150.00", null, null, null, null, emptyList(), null),
                        config = ConfigDeSucursal("SIM", "ICCID", false, false, false, false, false, false, true),
                        tipoDePrecio = TipoDePrecio.DE_PROMOTOR, precio = BigDecimal("150.00"), fotos = mapOf(Slot.VINCULACION to "/x.jpg"),
                        copiandoFoto = true,
                    ),
                    AccionesDeVenta(
                        {}, {}, { toques += "cámara" }, { _, _ -> }, {}, {}, { toques += "registrar" }, {}, { toques += "regresar" },
                    ),
                )
            }
        }
        compose.onNodeWithText("Guardando la foto…").assertExists()
        compose.onNodeWithText("Tomar otra foto").performClick()
        compose.onNodeWithText("Registrar venta").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), toques)
    }
}
