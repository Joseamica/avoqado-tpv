package com.jaac.avoqado_campo.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * El aviso de la versión nueva en el Inicio y, con una obligatoria pendiente, qué queda en gris y qué sigue (D-2); y lo que el
 * Inicio del mockup dice: el turno, las SIMs en custodia, el contador de pendientes y el ancho en la tableta.
 */
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
        compose.onNodeWithText("Vender SIM").assertIsEnabled()
        compose.onNodeWithText(actualiza).assertDoesNotExist()
        assertEquals(listOf("actualizar"), toques)
    }

    @Test fun `sin version nueva no hay aviso`() {
        inicio(base)
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.onNodeWithText("Vender SIM").assertIsEnabled()
    }

    @Test fun `obligatoria - Vender en gris con «Actualiza para seguir», y Mis pendientes, Ayuda, Checar y Mis SIMs activos`() {
        inicio(base.copy(version = v031.copy(obligatoria = true), obligatoria = true))
        compose.onNodeWithText("Vender SIM").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithText("Actualizar").assertIsEnabled()
        compose.onNodeWithText("Mis pendientes").assertIsEnabled()
        compose.onNodeWithText("Ayuda").assertIsEnabled()
        compose.onNodeWithText("Checar entrada").assertIsEnabled()
        compose.onNodeWithText("Mis SIMs").assertIsEnabled()
    }

    @Test fun `obligatoria cuya version no paso la revision - avisa al supervisor, sin boton, y Vender sigue en gris`() {
        inicio(base.copy(version = null, obligatoria = true))
        compose.onNodeWithText("No se pudo comprobar la versión nueva. Avisa a tu supervisor").assertExists()
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
        compose.onNodeWithText("Vender SIM").assertIsNotEnabled()
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

    // Como el mockup, Checar ofrece sólo la acción que toca: con entrada abierta, la salida (activa aunque haya obligatoria).
    @Test fun `obligatoria con turno abierto - en Checar, «Marcar salida» activo y no se ofrece otra entrada`() {
        compose.setContent {
            TemaCampo { ChecadaContenido(EstadoChecada(entrada = RefEntrada.PorLlave("k")), true, {}, {}, {}, {}, obligatoria = true) }
        }
        compose.onNodeWithText("Marcar entrada").assertDoesNotExist()
        compose.onNodeWithText("Marcar salida").assertIsEnabled()
        compose.onNodeWithText(actualiza).assertExists()
        compose.onNodeWithContentDescription("Regresar").assertIsEnabled()
    }

    @Test fun `obligatoria sin turno abierto - en Checar, «Marcar entrada» en gris`() {
        compose.setContent { TemaCampo { ChecadaContenido(EstadoChecada(), true, {}, {}, {}, {}, obligatoria = true) } }
        compose.onNodeWithText("Marcar entrada").assertIsNotEnabled()
        compose.onNodeWithText(actualiza).assertExists()
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
        compose.onNodeWithContentDescription("Regresar").assertIsEnabled()
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

    // D2 (full-testing del bloque 2): sin señal, «Vender» mostraba la banda dos veces (la de la navegación y otra suya). Ahora
    // la navegación la da una vez (LocalBanda) y la pantalla la pinta bajo su encabezado.
    @Test fun `sin senal, en Vender la banda sale una sola vez`() {
        compose.setContent {
            TemaCampo {
                CompositionLocalProvider(LocalBanda provides textoDeBanda(true, EstadoDelCelular.VINCULADO, false, 0)) {
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
        compose.onNodeWithText("8952 1400 0000 0000 201").assertExists()
        compose.onNodeWithText("✓ Está en tu custodia y aceptada").assertExists()
        compose.onNodeWithText("Escanear la SIM").assertIsNotEnabled()
        compose.onNodeWithText("Registrar venta").assertExists()
    }

    // Hallado en el SAMSUNG (full-testing del bloque 3): el superadmin retiró la obligatoria mala y el aviso «No se pudo
    // comprobar…» del último intento seguía, aunque ya no había nada pendiente.
    @Test fun `sin version nueva ni obligatoria, el fallo del ultimo intento ya no se muestra`() {
        inicio(base.copy(version = null, obligatoria = false, paso = PasoDeActualizacion.Fallo(NO_SE_PUDO_COMPROBAR)))
        compose.onAllNodesWithText(NO_SE_PUDO_COMPROBAR).assertCountEquals(0)
        compose.onNodeWithText("Vender SIM").assertIsEnabled()
    }

    // Codex D2 r3: con la entrada ya confirmada («Entrada guardada»), si el GPS deja de seguir, «Checar» lo tiene que decir.
    @Test fun `Checar avisa que el GPS no sigue aunque haya un aviso de entrada guardada`() {
        compose.setContent {
            TemaCampo {
                ChecadaContenido(
                    EstadoChecada(
                        entrada = RefEntrada.PorLlave("k"), aviso = "Entrada guardada · se sube sola",
                        gps = com.jaac.avoqado_campo.gps.ResultadoGps.NoSigue("la ubicación del teléfono está apagada"),
                    ),
                    true, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithText("No estamos siguiendo tu ubicación: la ubicación del teléfono está apagada").assertExists()
    }

    @Test fun `Checar no repite el GPS si el aviso de la entrada ya lo dice`() {
        val motivo = "no diste permiso de ubicación"
        compose.setContent {
            TemaCampo {
                ChecadaContenido(
                    EstadoChecada(
                        entrada = RefEntrada.PorLlave("k"),
                        aviso = "La checada quedó guardada · no estamos siguiendo tu ubicación: $motivo",
                        gps = com.jaac.avoqado_campo.gps.ResultadoGps.NoSigue(motivo),
                    ),
                    true, {}, {}, {}, {},
                )
            }
        }
        compose.onAllNodesWithText("No estamos siguiendo tu ubicación: $motivo").assertCountEquals(0)
    }

    // Pasada de la familia (avisos del GPS en Checar): si cambia el motivo, se dice el de ahora, no el de la checada.
    @Test fun `Checar dice el motivo actual del GPS si cambio desde la checada`() {
        compose.setContent {
            TemaCampo {
                ChecadaContenido(
                    EstadoChecada(
                        entrada = RefEntrada.PorLlave("k"),
                        aviso = "La checada quedó guardada · no estamos siguiendo tu ubicación: no diste permiso de ubicación",
                        gps = com.jaac.avoqado_campo.gps.ResultadoGps.NoSigue("la ubicación del teléfono está apagada"),
                    ),
                    true, {}, {}, {}, {},
                )
            }
        }
        compose.onNodeWithText("No estamos siguiendo tu ubicación: la ubicación del teléfono está apagada").assertExists()
    }

    // ---- El Inicio del mockup (8-nov) ----

    private val a902 = ZonedDateTime.of(2026, 11, 11, 9, 2, 0, 0, ZoneId.of("America/Mexico_City")).toInstant().toEpochMilli()

    @Test fun `con turno abierto - chip «Turno abierto · entraste a las 9_02» y el mosaico dice «Checar salida»`() {
        inicio(base.copy(turnoAbierto = true, entradaAt = a902))
        compose.onNodeWithText("Turno abierto · entraste a las 9:02").assertExists()
        compose.onNodeWithText("Checar salida").assertIsEnabled().performClick()
        compose.onNodeWithText("Entraste 9:02").assertExists()
        assertEquals(listOf("checar"), toques)
    }

    @Test fun `sin turno - no hay chip de turno y el mosaico dice «Checar entrada»`() {
        inicio(base)
        compose.onAllNodesWithText("Turno abierto", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Checar entrada").assertIsEnabled()
    }

    @Test fun `Mis SIMs dice cuantas hay en custodia, y nada si todavia no hay copia`() {
        inicio(base.copy(enCustodia = 24))
        compose.onNodeWithText("24 en tu custodia").assertExists()
        compose.onNodeWithText("Mis SIMs").performClick()
        assertEquals(listOf("sims"), toques)
    }

    @Test fun `sin copia de Mis SIMs no inventa un numero`() {
        inicio(base.copy(enCustodia = null))
        compose.onAllNodesWithText("en tu custodia", substring = true).assertCountEquals(0)
    }

    @Test fun `Mis pendientes lleva su contador, y sin pendientes dice «Todo subido»`() {
        inicio(base.copy(pendientes = 3))
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithText("Mis pendientes").performClick()
        assertEquals(listOf("pendientes"), toques)
    }

    @Test fun `sin pendientes no hay contador`() {
        inicio(base.copy(pendientes = 0))
        compose.onNodeWithText("Todo subido").assertExists()
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test fun `el encabezado lleva las iniciales y Ayuda sigue a la mano`() {
        inicio(base.copy(nombre = "Ana Martínez", sucursal = "Walmart Coapa"))
        compose.onNodeWithText("AM").assertExists()
        compose.onNodeWithText("Hola, Ana Martínez · Walmart Coapa").assertExists()
        compose.onNodeWithText("Ayuda").performClick()
        assertEquals(listOf("ayuda"), toques)
    }

    @Test fun `en el celular angosto el contenido usa todo el ancho`() {
        inicio(base)
        compose.onNodeWithTag(ETIQUETA_CONTENIDO).assertWidthIsEqualTo(360.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun `en la tableta ancha el contenido no se estira mas de 560 dp`() {
        inicio(base)
        compose.onNodeWithTag(ETIQUETA_CONTENIDO).assertWidthIsEqualTo(ANCHO_MAXIMO)
    }

    // Hallado en el SAMSUNG (rediseño): «Regresar» de la confirmación quedó donde estaba «Registrar venta»; el segundo toque
    // de un doble toque se la saltaba y el promotor no veía «No la vuelvas a hacer». Los botones esperan un instante.
    @Test fun `la confirmacion de venta ignora el toque que llega junto con el de Registrar`() {
        var regresos = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TemaCampo {
                VentaContenido(EstadoVenta(guardada = true), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, { regresos++ }))
            }
        }
        compose.onNodeWithText("Regresar").performClick()
        assertEquals(0, regresos)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Regresar").performClick()
        assertEquals(1, regresos)
    }

    // Codex rediseño #1: sin red, la cámara y el escáner (pantalla completa) también dicen «Sin señal».
    @Test fun `sin senal, la camara tambien lo dice`() {
        org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        compose.setContent {
            TemaCampo {
                androidx.compose.runtime.CompositionLocalProvider(LocalBanda provides SIN_SENAL) {
                    com.jaac.avoqado_campo.fotos.ConCamara("para probar", {}) { androidx.compose.material3.Text("vista de la cámara") }
                }
            }
        }
        compose.onNodeWithText("vista de la cámara").assertExists()
        compose.onNodeWithText(SIN_SENAL).assertExists()
    }

    // Codex rediseño #2: con permiso no se sabe si la ubicación está encendida: el chip no afirma «activada».
    @Test fun `Checar no afirma que la ubicacion esta activada con solo el permiso`() {
        compose.setContent { TemaCampo { ChecadaContenido(EstadoChecada(), true, {}, {}, {}, {}) } }
        compose.onAllNodesWithText("Ubicación activada", substring = true).assertCountEquals(0)
        compose.onNodeWithText("📍 Al checar se guarda tu ubicación").assertExists()
    }

    // Codex rediseño #4: la confirmación no inventa la hora de la venta (la guardada usa el reloj anclado al SERVIDOR).
    @Test fun `la confirmacion de venta no muestra una hora`() {
        val sim = SimEnCopia("8952140000000000201", "PROMOTER_HELD", 1, "De caja", "0.00", null, null, null, null, emptyList(), null)
        compose.setContent {
            TemaCampo { VentaContenido(EstadoVenta(guardada = true, sim = sim), AccionesDeVenta({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})) }
        }
        // Sin precio elegido el detalle es sólo la SIM; con la hora sería «SIM …0201 · HH:MM».
        compose.onNodeWithText("SIM …0201").assertExists()
    }
}
