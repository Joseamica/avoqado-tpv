package com.jaac.avoqado_campo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jaac.avoqado_campo.checada.ChecadaContenido
import com.jaac.avoqado_campo.checada.EstadoChecada
import com.jaac.avoqado_campo.checada.RefEntrada
import com.jaac.avoqado_campo.copia.SimEnCopia
import com.jaac.avoqado_campo.custodia.AccionesDeMisSims
import com.jaac.avoqado_campo.custodia.EstadoFila
import com.jaac.avoqado_campo.custodia.EstadoMisSims
import com.jaac.avoqado_campo.custodia.FilaSim
import com.jaac.avoqado_campo.custodia.MisSimsContenido
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.ui.tema.TemaCampo
import com.jaac.avoqado_campo.venta.AccionesDeVenta
import com.jaac.avoqado_campo.venta.EstadoVenta
import com.jaac.avoqado_campo.venta.VentaContenido
import com.jaac.avoqado_campo.version.NO_SE_PUDO_COMPROBAR
import com.jaac.avoqado_campo.version.PasoDeActualizacion
import com.jaac.avoqado_campo.version.VersionNueva
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** El aviso de la versión nueva en el Inicio y, con una obligatoria pendiente, qué queda en gris y qué sigue (D-2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1280dp")
class InicioScreenTest {
    @get:Rule val compose = createComposeRule()

    private val base = DatosDeInicio("Ana", 2, necesitaPin = false, esperanSucursal = emptyList(), turnoAbierto = false, gps = null)
    private val v031 = VersionNueva(4, "0.3.1", "https://x/campo.apk", "ab".repeat(32), 10, obligatoria = false, notas = null)
    private val actualiza = "Actualiza para seguir vendiendo. Lo que ya hiciste sigue subiendo"
    private var toques = mutableListOf<String>()

    private fun inicio(d: DatosDeInicio) = compose.setContent {
        TemaCampo {
            InicioScreen(
                d,
                AccionesDeInicio(
                    vender = { toques += "vender" }, sims = { toques += "sims" }, checar = { toques += "checar" },
                    pendientes = { toques += "pendientes" }, ayuda = { toques += "ayuda" }, entrar = {}, actualizar = { toques += "actualizar" },
                ),
            )
        }
    }

    @Test fun `con version nueva normal muestra «Hay una version nueva (0_3_1)» y «Actualizar», y Vender sigue`() {
        inicio(base.copy(version = v031))
        compose.onNodeWithText("Hay una versión nueva (0.3.1)").assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled().performClick()
        compose.onNodeWithText("Vender").assertIsEnabled()
        compose.onNodeWithText(actualiza).assertDoesNotExist()
        assertEquals(listOf("actualizar"), toques)
    }

    @Test fun `sin version nueva no hay aviso`() {
        inicio(base)
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.onNodeWithText("Vender").assertIsEnabled()
    }

    @Test fun `obligatoria - Vender en gris con «Actualiza para seguir», y Mis pendientes, Ayuda, Checar y Mis SIMs activos`() {
        inicio(base.copy(version = v031.copy(obligatoria = true), obligatoria = true))
        compose.onNodeWithText("Vender").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled()
        compose.onNodeWithText("Mis pendientes (2)").assertIsEnabled()
        compose.onNodeWithText("Ayuda").assertIsEnabled()
        compose.onNodeWithText("Checar").assertIsEnabled()
        compose.onNodeWithText("Mis SIMs").assertIsEnabled()
    }

    @Test fun `obligatoria cuya version no paso la revision - avisa al supervisor, sin boton, y Vender sigue en gris`() {
        inicio(base.copy(version = null, obligatoria = true))
        compose.onNodeWithText("No se pudo comprobar la versión nueva. Avisa a tu supervisor").assertExists()
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.onNodeWithText("Vender").assertIsNotEnabled()
    }

    @Test fun `bajando - el boton dice «Bajando…» y no se puede tocar`() {
        inicio(base.copy(version = v031, paso = PasoDeActualizacion.Bajando))
        compose.onNodeWithText("Bajando…").assertIsNotEnabled()
    }

    @Test fun `falta el permiso - dice como activarlo`() {
        inicio(base.copy(version = v031, paso = PasoDeActualizacion.FaltaPermiso))
        compose.onNodeWithText("Para actualizar, activa «Permitir de esta fuente» y regresa").assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled()
    }

    @Test fun `un fallo de la instalacion se ve con su texto`() {
        inicio(base.copy(version = v031, paso = PasoDeActualizacion.Fallo("No se instaló: INSTALL_FAILED_ABORTED")))
        compose.onNodeWithText("No se instaló: INSTALL_FAILED_ABORTED").assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled()
    }

    @Test fun `obligatoria con turno abierto - en Checar, «Marcar salida» activo y «Marcar entrada» en gris`() {
        compose.setContent {
            TemaCampo { ChecadaContenido(EstadoChecada(entrada = RefEntrada.PorLlave("k")), true, {}, {}, {}, {}, obligatoria = true) }
        }
        compose.onNodeWithText("Marcar entrada").assertIsNotEnabled()
        compose.onNodeWithText("Marcar salida").assertIsEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithText("Regresar").assertIsEnabled()
    }

    @Test fun `sin obligatoria, Marcar entrada sigue activo`() {
        compose.setContent { TemaCampo { ChecadaContenido(EstadoChecada(), true, {}, {}, {}, {}) } }
        compose.onNodeWithText("Marcar entrada").assertIsEnabled()
        compose.onNodeWithText(actualiza).assertDoesNotExist()
    }

    @Test fun `obligatoria - en Mis SIMs, Aceptar, Rechazar y Aceptar todas en gris, Actualizar y Regresar siguen`() {
        fun fila(serie: String) = FilaSim(SimEnCopia(serie, "PROMOTER_PENDING", 1, "Prepago", "150.00", null, null, null, null, emptyList(), null), EstadoFila.POR_DECIDIR)
        compose.setContent {
            TemaCampo {
                MisSimsContenido(
                    EstadoMisSims(porAceptar = listOf(fila("8952020012345678901F"), fila("8952020012345678902F")), sinCopia = false),
                    AccionesDeMisSims({}, {}, {}, { _, _ -> }, {}, {}), obligatoria = true,
                )
            }
        }
        compose.onAllNodesWithText("Aceptar").assertAll(isNotEnabled())
        compose.onAllNodesWithText("Rechazar").assertAll(isNotEnabled())
        compose.onNodeWithText("Aceptar todas").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled()
        compose.onNodeWithText("Regresar").assertIsEnabled()
    }

    @Test fun `obligatoria - tras guardar una venta, «Vender otra» en gris y Regresar sigue`() {
        compose.setContent {
            TemaCampo { VentaContenido(EstadoVenta(guardada = true), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}), obligatoria = true) }
        }
        compose.onNodeWithText("Vender otra").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithText("Regresar").assertIsEnabled()
    }

    // Codex código r1 #1: Android puede restaurar la pantalla de Venta VACÍA (proceso muerto); con obligatoria no se empieza
    // otra venta desde ahí, pero la SIM que ya estaba elegida sí se termina.
    @Test fun `obligatoria - la pantalla de Venta vacia no deja escanear ni buscar una SIM nueva`() {
        compose.setContent {
            TemaCampo { VentaContenido(EstadoVenta(), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}), obligatoria = true) }
        }
        compose.onNodeWithText("Escanear la SIM").assertIsNotEnabled()
        compose.onNodeWithText("Buscar").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
    }

    // D2 (full-testing del bloque 2): sin señal, «Vender» mostraba la banda dos veces (la de la navegación y otra suya).
    @Test fun `sin senal, en Vender la banda sale una sola vez`() {
        compose.setContent {
            TemaCampo {
                Column {
                    BandaDeSenal(textoDeBanda(true, EstadoDelCelular.VINCULADO, false, 0)) // la de la navegación
                    VentaContenido(EstadoVenta(), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}))
                }
            }
        }
        compose.onAllNodesWithText("Sin señal · lo que hagas se guarda en el celular y se sube solo").assertCountEquals(1)
    }

    @Test fun `sin obligatoria, la pantalla de Venta vacia deja escanear`() {
        compose.setContent {
            TemaCampo { VentaContenido(EstadoVenta(), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}), obligatoria = false) }
        }
        compose.onNodeWithText("Escanear la SIM").assertIsEnabled()
    }

    @Test fun `obligatoria con una SIM ya elegida - se puede terminar (Registrar no depende de la obligatoria)`() {
        val sim = SimEnCopia("8952140000000000201", "PROMOTER_HELD", 1, "De caja", "0.00", null, null, null, null, emptyList(), null)
        compose.setContent {
            TemaCampo { VentaContenido(EstadoVenta(sim = sim), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}), obligatoria = true) }
        }
        compose.onNodeWithText("8952140000000000201").assertExists()
        compose.onNodeWithText("Escanear la SIM").assertIsNotEnabled()
        compose.onNodeWithText("Registrar").assertExists()
    }

    // Hallado en el SAMSUNG (full-testing del bloque 3): el superadmin retiró la obligatoria mala y el aviso «No se pudo
    // comprobar…» del último intento seguía, aunque ya no había nada pendiente.
    @Test fun `sin version nueva ni obligatoria, el fallo del ultimo intento ya no se muestra`() {
        inicio(base.copy(version = null, obligatoria = false, paso = PasoDeActualizacion.Fallo(NO_SE_PUDO_COMPROBAR)))
        compose.onAllNodesWithText(NO_SE_PUDO_COMPROBAR).assertCountEquals(0)
        compose.onNodeWithText("Vender").assertIsEnabled()
    }
}
