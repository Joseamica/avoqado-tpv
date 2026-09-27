package com.jaac.avoqado_tpv.core.remotepayment

import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.presentation.navigation.NavRoute
import org.junit.Test

/**
 * El aviso es la mitad que hace entregable a F0: desde que una obligación pendiente cerca su venta
 * en vez de apagar la terminal, el cajero PUEDE volver a cobrar — y sólo él sabe si lo que tiene
 * enfrente es la misma venta o una nueva. Este texto es lo único que se lo dice.
 */
class AvisoDeCobrosPendientesTest {
    private val ahora = 1_700_000_000_000L
    private fun obligacion(centavos: Long, haceMillis: Long) =
        ObligacionPendiente(totalCentavos = centavos, desdeMillis = ahora - haceMillis)

    @Test fun `P1 sin nada pendiente no hay aviso`() {
        assertThat(AvisoDeCobrosPendientes.texto(emptyList(), ahora)).isNull()
    }

    @Test fun `P1 con una obligacion dice cuanto y desde cuando`() {
        val texto = AvisoDeCobrosPendientes.texto(listOf(obligacion(12050, 3 * 60_000)), ahora)
        assertThat(texto).contains("120.50")
        assertThat(texto).contains("hace 3 min")
        assertThat(texto).contains("no la cobres otra vez")
    }

    /** 🔴 Con varias, el cajero necesita reconocer CUÁL es la suya: se nombran los importes. */
    @Test fun `P1 con varias nombra el importe de cada una, no solo el de la mas reciente`() {
        val texto = AvisoDeCobrosPendientes.texto(
            listOf(obligacion(5000, 60_000), obligacion(12050, 3 * 60_000)), ahora)
        assertThat(texto).contains("2 cobros")
        assertThat(texto).contains("50.00")
        assertThat(texto).contains("120.50")
        assertThat(texto).contains("hace 3 min")
    }

    /** 🔴 El resto se cuenta sobre la lista: un importe de miles lleva coma y rompería un conteo textual. */
    @Test fun `P1 con mas de tres, el resto se cuenta bien aunque los importes lleven coma de miles`() {
        val muchas = listOf(obligacion(885600, 60_000), obligacion(120000, 120_000),
            obligacion(5000, 180_000), obligacion(1000, 240_000), obligacion(2000, 300_000))
        val texto = AvisoDeCobrosPendientes.texto(muchas, ahora)
        assertThat(texto).contains("8,856.00")
        assertThat(texto).contains("5 cobros")
        assertThat(texto).contains("y 2 más")
    }

    @Test fun `P2 menos de un minuto se dice en segundos, nunca en cero minutos`() {
        val texto = AvisoDeCobrosPendientes.texto(listOf(obligacion(1000, 20_000)), ahora)
        assertThat(texto).contains("hace unos segundos")
        assertThat(texto).doesNotContain("0 min")
    }

    // Desde el 25-sep el aviso de una duda se quita a los 10 min en toda terminal: las horas se leen en lo que nombra la
    // barrera (`importeYAntiguedad`, el mismo formato), no en el aviso.
    @Test fun `P2 mas de una hora se dice en horas`() {
        assertThat(AvisoDeCobrosPendientes.importeYAntiguedad(1000, ahora - 150 * 60_000, ahora)).contains("hace 2 h")
    }

    /** El reloj del aparato puede ir atrás del que escribió la fila: jamás «hace -4 min». */
    @Test fun `P1 una fila del futuro no produce una antiguedad negativa`() {
        val texto = AvisoDeCobrosPendientes.texto(
            listOf(ObligacionPendiente(totalCentavos = 1000, desdeMillis = ahora + 240_000)), ahora)
        assertThat(texto).contains("hace unos segundos")
        assertThat(texto).doesNotContain("-")
    }

    /**
     * 🔴 El aviso tiene que estar en las DOS puertas por las que se puede recobrar la misma venta,
     * no sólo en el Inicio: ahí llega cuando el cajero ya pasó de largo. Se comprueba contra las
     * rutas REALES, no contra lo que la prueba le pase: quitar una del conjunto tumba esto.
     */
    @Test fun `P1 el aviso se pinta tambien en pago rapido y en el carrito, no solo en el inicio`() {
        assertThat(NavRoute.RUTAS_QUE_AVISAN_DE_COBROS_PENDIENTES).containsExactly(
            NavRoute.Home.route, NavRoute.FastPaymentEntry.route, NavRoute.Checkout.route)
        assertThat(NavRoute.RUTAS_QUE_AVISAN_DE_COBROS_PENDIENTES).contains("fast_payment_entry")
        assertThat(NavRoute.RUTAS_QUE_AVISAN_DE_COBROS_PENDIENTES).contains("checkout")
    }

    @Test fun `una contradiccion con el servidor se avisa aparte, no se mezcla con los pendientes y caduca a las 72 h`() {
        val ahora = 1_700_000_000_000L
        val pendiente = ObligacionPendiente(totalCentavos = 12050, desdeMillis = ahora - 3 * 60_000)
        val contradiccion = ObligacionPendiente(totalCentavos = 5000, desdeMillis = ahora - 60_000, contradiccion = 1)
        val texto = AvisoDeCobrosPendientes.texto(listOf(contradiccion, pendiente), ahora)!!
        assertThat(texto).startsWith("Quedó un cobro de \$120.50 sin confirmar")
        // Fix 4 (Codex, D3b): la contradicción puede nacer de una aprobación bancaria SIN Payment — el aviso admite
        // «tiene evidencia de cobro» y ya no afirma un registro que puede no existir.
        assertThat(texto).contains("Avoqado tiene evidencia de cobro de un intento (\$50.00)")
        assertThat(texto).doesNotContain("registró dinero")
        assertThat(texto).contains("no lo vuelvas a cobrar")
        // Sólo contradicción: no dice «sin confirmar».
        assertThat(AvisoDeCobrosPendientes.texto(listOf(contradiccion), ahora)!!).doesNotContain("sin confirmar")
        // Caducada (más de 72 h): desaparece del aviso.
        val vieja = contradiccion.copy(desdeMillis = ahora - AvisoDeCobrosPendientes.VENTANA_CONTRADICCION_MS - 1)
        assertThat(AvisoDeCobrosPendientes.texto(listOf(vieja), ahora)).isNull()
    }

    // ── Decisión del founder (24-sep la PAX, 25-sep todas): el aviso de una duda se quita solo a los 10 min ──
    // A los 10 min el cliente ya se fue y el aviso ya no evita ningún cobro doble; la fila sigue guardada para conciliar.
    // Vale en TODA terminal («ninguna duda apaga la terminal»): el aviso ya no sabe de qué aparato viene.

    @Test fun `el aviso de una duda se quita solo a los 10 min en cualquier terminal`() {
        val ahora = 50_000_000L
        val reciente = ObligacionPendiente(totalCentavos = 1000, desdeMillis = ahora - 9 * 60_000)
        val vieja = ObligacionPendiente(totalCentavos = 1000, desdeMillis = ahora - AvisoDeCobrosPendientes.VENTANA_AVISO_MS)
        assertThat(AvisoDeCobrosPendientes.texto(listOf(reciente), ahora)).contains("sin confirmar")
        assertThat(AvisoDeCobrosPendientes.texto(listOf(vieja), ahora)).isNull()
        assertThat(AvisoDeCobrosPendientes.VENTANA_AVISO_MS).isEqualTo(10L * 60 * 1000)
    }

    /** Se quita cada cobro a su tiempo: el viejo no se lleva al reciente, ni el reciente mantiene al viejo. */
    @Test fun `P1 sólo se quita el cobro que ya pasó los 10 minutos, el reciente sigue`() {
        val texto = AvisoDeCobrosPendientes.texto(
            listOf(obligacion(5000, 2 * 60_000), obligacion(1000, 11 * 60_000)), ahora)!!
        assertThat(texto).contains("50.00")
        assertThat(texto).doesNotContain("10.00")
        assertThat(texto).doesNotContain("2 cobros")
    }

    @Test fun `P1 una contradiccion conserva su propia ventana de 72 h, no la de 10 min`() {
        val contradiccion = ObligacionPendiente(totalCentavos = 1000, desdeMillis = ahora - 2 * 60 * 60_000, contradiccion = 1)
        assertThat(AvisoDeCobrosPendientes.texto(listOf(contradiccion), ahora)).contains("evidencia de cobro")
    }


    // ── La barrera de la libreta: TRES desenlaces, no una frase con «o» (founder, 21-sep) ──

    @Test
    fun `P1 la venta cercada se nombra antes que el aparato`() {
        // Es la más accionable: volver a cobrar ESTA venta es el cobro doble que la cerca impide.
        val texto = AvisoDeCobrosPendientes.barreraDeLaLibreta(
            apartaLaVenta = "\$120.00, hace 3 min", apartaElAparato = "\$50.00, hace 1 h",
        )

        assertThat(texto).contains("Esta venta ya tiene un cobro sin confirmar (\$120.00, hace 3 min)")
        assertThat(texto).doesNotContain("\$50.00")
    }

    @Test fun `la barrera del aparato nombra un cobro EN CURSO y dice como salir`() {
        val texto = AvisoDeCobrosPendientes.barreraDeLaLibreta(apartaLaVenta = null, apartaElAparato = "\$10.00, hace 12 min")
        assertThat(texto).isEqualTo(
            "Hay otro cobro en curso en esta terminal (\$10.00, hace 12 min). NO se inició este cobro. " +
                "Espera a que termine; si no ves ningún cobro en pantalla, cierra y vuelve a abrir la app.")
    }

    @Test
    fun `P1 sin nada que nombrar NO se afirma que la venta este libre`() {
        val texto = AvisoDeCobrosPendientes.barreraDeLaLibreta(null, null)

        assertThat(texto).contains("NO se cobró")
        assertThat(texto).doesNotContain("cobro sin confirmar")
    }

    @Test
    fun `P1 los tres desenlaces son textos DISTINTOS`() {
        // Si dos coincidieran, el cajero volvería a no poder distinguir qué le tocó — que es
        // exactamente el defecto que esto cierra.
        val textos = setOf(
            AvisoDeCobrosPendientes.barreraDeLaLibreta("\$1.00, hace 1 min", null),
            AvisoDeCobrosPendientes.barreraDeLaLibreta(null, "\$1.00, hace 1 min"),
            AvisoDeCobrosPendientes.barreraDeLaLibreta(null, null),
        )

        assertThat(textos).hasSize(3)
    }

    // ── Decisión del founder (23-sep): un cobro que SÍ pasó se corrige y se avisa, con un toque de «Entendido» ──

    @Test
    fun `el cobro que si paso dice cuanto, cuando y que NO se vuelva a cobrar`() {
        val texto = AvisoDeCobrosPendientes.cobroQueSiPaso(4000, ahora - 5 * 60_000, ahora)

        assertThat(texto).contains("40.00")
        assertThat(texto).contains("hace 5 min")
        assertThat(texto).contains("SÍ")
        assertThat(texto).contains("no lo vuelvas a cobrar")
    }

    @Test
    fun `si lo que cerca la venta es un cobro que si paso, la barrera dice que esa venta ya se cobro`() {
        val texto = AvisoDeCobrosPendientes.barreraDeLaLibreta("\$40.00, hace 5 min", null, laVentaYaCobrada = true)

        assertThat(texto).contains("ya se cobró")
        assertThat(texto).contains("Entendido")
    }
}
