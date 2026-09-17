package com.jaac.avoqado_tpv.features.payment.presentation.angelpay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🚪 Task 10 (QA en la Nexgo N86, 17-sep). Tras liberar un cobro (ventana de 30 s o declaración del cajero) el ViewModel
 * vuelve solo a `Idle` y pide UNA salida. Para esta pantalla `Idle` con monto es «preparando el cobro» (el cargando) y su
 * auto-arranque no se vuelve a disparar —sus llaves no cambian—, así que sin el colector la terminal se quedaba en el
 * cargando para siempre (medido dos veces en hardware).
 *
 * El repo NO tiene infraestructura de pruebas de Compose en las pruebas de JVM: `ui-test-junit4` (`createComposeRule`) sólo
 * está en `androidTestImplementation`; la pantalla además pide un anfitrión de Activity (`rememberLauncherForActivityResult`
 * necesita un `ActivityResultRegistryOwner`, y `collectAsStateWithLifecycle` un `LifecycleOwner`), y las pruebas Robolectric
 * del repo corren con `Config.NONE`, sin `isIncludeAndroidResources`. Montar eso sería infraestructura nueva y grande. Por
 * eso esta prueba es ESTÁTICA, como
 * `ArgumentosDelCobroSeLeenUnaVezTest`: fija el CABLEADO de la pantalla. El comportamiento del colector —una salida pedida
 * ⇒ UNA llamada a «Regresar», sin repetirse al recrear la pantalla— se prueba con el ViewModel real en
 * `AngelPayPaymentViewModelTest` («Task 10 - …»), con la MISMA función [recogerSalidaAutomatica] que usa la pantalla.
 */
class AngelPayPaymentScreenSalidaTest {

    private val pantalla = File(
        "src/main/java/com/jaac/avoqado_tpv/features/payment/presentation/angelpay/AngelPayPaymentScreen.kt",
    ).readText()

    /**
     * El cuerpo de `AngelPayPaymentScreen` ANTES de los overlays de éxito/cola, que hacen `return`: lo que se compone en TODO
     * estado. Un efecto declarado después de ese `when` desaparecería de la composición mientras se ve un éxito.
     */
    private val cuerpoSiempreCompuesto: String by lazy {
        val inicio = pantalla.indexOf("fun AngelPayPaymentScreen(")
        require(inicio >= 0) { "No encontré fun AngelPayPaymentScreen( en AngelPayPaymentScreen.kt" }
        val fin = pantalla.indexOf("// ── Full-screen overlays (no scaffold)", inicio)
        require(fin > inicio) { "No encontré el comentario «Full-screen overlays» de AngelPayPaymentScreen" }
        pantalla.substring(inicio, fin)
    }

    /** La llamada completa `ErrorContent(...)` de la rama Error (hasta el cierre de la rama siguiente). */
    private val llamadaAErrorContent: String by lazy {
        val inicio = pantalla.indexOf("ErrorContent(\n")
        require(inicio >= 0) { "No encontré la llamada a ErrorContent( en AngelPayPaymentScreen.kt" }
        val fin = pantalla.indexOf("is AngelPayPaymentState.Cancelled ->", inicio)
        require(fin > inicio) { "No encontré la rama Cancelled después de ErrorContent(" }
        pantalla.substring(inicio, fin)
    }

    @Test
    fun `la pantalla recoge la salida automatica del ViewModel en todo estado y la convierte en su Regresar`() {
        val colector = Regex(
            """LaunchedEffect\(viewModel\)\s*\{\s*recogerSalidaAutomatica\(viewModel\.salidaAutomatica\)\s*\{\s*salirComoRegresar\(\)\s*}\s*}""",
        )
        assertTrue(
            "AngelPayPaymentScreen ya no convierte la salida automática del ViewModel en su «Regresar» (o lo hace después de " +
                "los overlays): tras liberar un cobro la terminal se quedaría en el cargando para siempre (QA N86, 17-sep).",
            colector.containsMatchIn(cuerpoSiempreCompuesto),
        )
        assertTrue(
            "La salida automática debe leer la versión VIGENTE de «Regresar» (rememberUpdatedState), no la de la primera composición.",
            Regex("""val salirComoRegresar by rememberUpdatedState\(regresarDelError\)""").containsMatchIn(cuerpoSiempreCompuesto),
        )
        assertEquals(
            "La salida automática se recoge en UN solo lugar: dos colectores del mismo canal se reparten las salidas.",
            1,
            Regex("""recogerSalidaAutomatica\(""").findAll(pantalla).count() - 1,   // menos la declaración de la función
        )
    }

    @Test
    fun `Regresar del error y la salida automatica son la MISMA salida - resetPayment y despues onNavigateBack`() {
        val definicion = Regex(
            """val regresarDelError: \(\) -> Unit = \{\s*viewModel\.resetPayment\(\)\s*onNavigateBack\(\)\s*}""",
        )
        assertTrue(
            "«Regresar» del error debe ser resetPayment() y DESPUÉS onNavigateBack() (resetPayment avisa al POS; sólo navegar " +
                "abandonaba el cobro sin avisarle — N86, E699, 2026-08-10), definido UNA vez y antes de los overlays.",
            definicion.containsMatchIn(cuerpoSiempreCompuesto),
        )
        assertTrue(
            "El botón «Regresar» de ErrorContent debe usar la MISMA salida que la salida automática (regresarDelError).",
            Regex("""onGoBack = regresarDelError""").containsMatchIn(llamadaAErrorContent),
        )
    }

    @Test
    fun `regresion - el auto-arranque desde Idle con monto sigue intacto y llama initPayment UNA vez`() {
        val arranque = Regex(
            """LaunchedEffect\(\s*initialAmount,\s*orderId,\s*orderNumber,\s*skipReview,\s*externalTipCents,\s*externalRating,\s*externalSkipReview,\s*\)\s*\{\s*if \(initialAmount != null && state is AngelPayPaymentState\.Idle\) \{""",
        )
        assertTrue(
            "El auto-arranque de AngelPayPaymentScreen cambió de llaves o de guarda: `Idle` es el estado inicial legítimo y debe " +
                "seguir arrancando el cobro, UNA vez, cuando la pantalla abre con monto.",
            arranque.containsMatchIn(cuerpoSiempreCompuesto),
        )
        assertEquals(
            "AngelPayPaymentScreen debe llamar viewModel.initPayment( en UN solo lugar: el auto-arranque.",
            1,
            Regex("""viewModel\.initPayment\(""").findAll(pantalla).count(),
        )
    }

    @Test
    fun `regresion - Idle con monto sigue pintando el cargando de preparar el cobro, sin salir por su cuenta`() {
        val inicio = pantalla.indexOf("is AngelPayPaymentState.Idle -> {\n                    if (initialAmount == null)")
        require(inicio >= 0) { "No encontré la rama Idle del when principal de AngelPayPaymentScreen" }
        val rama = pantalla.substring(inicio, pantalla.indexOf("is AngelPayPaymentState.CollectingRating ->", inicio))
        assertTrue(
            "La rama Idle con monto debe seguir pintando el cargando (AvoqadoBrandLoader) mientras arranca el cobro.",
            rama.contains("AvoqadoBrandLoader("),
        )
        assertFalse(
            "La rama Idle NO debe navegar por su cuenta: `Idle` también es el arranque normal; la salida la pide el ViewModel.",
            Regex("""onNavigate(Back|Home)\(""").containsMatchIn(rama),
        )
    }
}
