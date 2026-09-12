package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Los TRES desenlaces de un cobro AngelPay.
 *
 * El defecto que estas pruebas fijan: antes de esto, TODO lo que no venía `approved=true`
 * caía en una sola rama de "no aprobado" — un rechazo del emisor y un "no sé qué pasó"
 * se veían idénticos en pantalla, en la libreta (DESCARTADA) y para el POS (`failed`).
 * Un `U101` (tiempo agotado) o un `G505` ("Resultado no concluyente") invitaban al
 * cajero a volver a cobrar una venta que quizá ya se había cobrado.
 *
 * Los códigos y sus significados están verificados contra el bytecode de
 * `AppErrorCatalog$Code` dentro del AAR vendoreado v1.0.18 (javap, 2026-09-08).
 */
class AngelPayOutcomeClassifierTest {

    // ── APROBADO gana siempre ────────────────────────────────────────

    @Test
    fun `aprobado gana sobre cualquier otra senal`() {
        // Si el procesador dijo que sí, el dinero se movió: ninguna otra pista lo discute.
        assertThat(
            AngelPayOutcomeClassifier.clasificar(
                aprobado = true, status = "TIMEOUT", codigoSdk = "U101", codigoGateway = null,
            )
        ).isEqualTo(DesenlaceDelCobro.APROBADO)
    }

    // ── INCIERTO: el SDK volvió SIN veredicto del procesador ─────────

    @Test
    fun `status TIMEOUT es incierto`() {
        assertThat(desenlace(status = "TIMEOUT")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `U101 tiempo de espera agotado es incierto`() {
        // El catálogo del vendor lo marca CANCELLED/USER, como si el cajero hubiera
        // cancelado. No es lo mismo: el tiempo se agotó ESPERANDO una respuesta.
        assertThat(desenlace(status = "CANCELLED", codigoSdk = "U101"))
            .isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `G505 resultado no concluyente es incierto`() {
        // "Resultado no concluyente, verifique el historial de transacciones" — el propio
        // vendor pide verificar. Tratarlo como rechazo es exactamente lo contrario.
        assertThat(desenlace(codigoSdk = "G505")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `G502 se requiere reversal es incierto`() {
        // Pedir un reversal sólo tiene sentido si la autorización pudo llegar al emisor.
        assertThat(desenlace(codigoSdk = "G502")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `N402 timeout contra el gateway es incierto`() {
        // La petición SALIÓ hacia el gateway y no volvió respuesta.
        assertThat(desenlace(codigoSdk = "N402")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `I999 error desconocido es incierto`() {
        assertThat(desenlace(codigoSdk = "I999")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `error sin ningun codigo es incierto`() {
        // Salida sin veredicto: ni código de catálogo ni código del emisor.
        assertThat(desenlace(status = "ERROR", codigoSdk = null, codigoGateway = null))
            .isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `el codigo se normaliza`() {
        assertThat(desenlace(codigoSdk = " g505 ")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    // ── RECHAZO CONFIRMADO: hay veredicto, el dinero NO se movió ─────

    @Test
    fun `G500 rechazada por el gateway es rechazo confirmado`() {
        assertThat(desenlace(status = "DECLINED", codigoSdk = "G500"))
            .isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `G504 rechazo por riesgo es rechazo confirmado`() {
        assertThat(desenlace(codigoSdk = "G504")).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `E606 rechazo online del emisor es rechazo confirmado`() {
        assertThat(desenlace(codigoSdk = "E606")).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `E618 remove card alone does not prove no charge`() {
        // Remove-card guidance does not identify whether authorization ran.
        assertThat(desenlace(codigoSdk = "E618")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `D308 sesion expirada es rechazo confirmado`() {
        // El SDK aborta ANTES de llamar al gateway (por eso relanzar no puede duplicar).
        assertThat(desenlace(codigoSdk = "D308")).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `U100 cancellation without authorization phase proof remains unknown`() {
        assertThat(desenlace(status = "CANCELLED", codigoSdk = "U100"))
            .isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `sin codigo de catalogo pero con codigo del emisor es rechazo confirmado`() {
        // "05" = do not honor: the issuer supplied an explicit negative verdict.
        assertThat(desenlace(status = "ERROR", codigoSdk = null, codigoGateway = "05"))
            .isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
    }

    @Test
    fun `declinada sin codigos permanece incierta`() {
        // A UI status alone is not a processor verdict.
        assertThat(desenlace(status = "DECLINED", codigoSdk = null, codigoGateway = null))
            .isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `cancelada sin codigos permanece incierta`() {
        assertThat(desenlace(status = "CANCELLED", codigoSdk = null, codigoGateway = null))
            .isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `un codigo desconocido conserva resultado incierto`() {
        // La lista de INCIERTO es explícita: un código que no está en ella conserva
        // el comportamiento de hoy (rechazo) en vez de bloquear ventas buenas.
        assertThat(desenlace(codigoSdk = "Z777")).isEqualTo(DesenlaceDelCobro.INCIERTO)
    }

    @Test
    fun `unrecognized gateway code does not prove a decline`() {
        listOf("garbage", "00", "91", "96").forEach { code ->
            assertThat(desenlace(status = "DECLINED", codigoGateway = code))
                .isEqualTo(DesenlaceDelCobro.INCIERTO)
        }
    }

    @Test
    fun `known internal transport and printing errors do not prove no charge`() {
        listOf("I901", "I902", "N401", "N403", "N404", "P700", "P701", "P702", "P703", "U102", "G501", "E699", "S000").forEach { code ->
            assertThat(desenlace(codigoSdk = code)).isEqualTo(DesenlaceDelCobro.INCIERTO)
        }
    }

    /**
     * 🛑 P2-13: UNA sola tabla. El ViewModel tenía la suya —{G500,G504,E605,E606}— y todo rechazo que
     * sólo reconocía ésta (D308, C208, E608, N400…) viajaba al servidor como PRE_AUTHORIZATION, o sea
     * «ninguna ejecución capaz de autorizar empezó», aunque el SDK ya se hubiera lanzado.
     */
    @Test
    fun `todo rechazo confirmado viaja con la MISMA evidencia, no con una lista paralela de codigos`() {
        val fueraDeLaListaVieja = listOf("D308", "C208", "E608", "N400", "E613", "C200")
        fueraDeLaListaVieja.forEach { code ->
            assertThat(desenlace(codigoSdk = code)).isEqualTo(DesenlaceDelCobro.RECHAZADO_CONFIRMADO)
        }
        // Un rechazo que llegó como RESULTADO del SDK siempre acredita «lo rechazó el procesador».
        assertThat(AngelPayOutcomeClassifier.EVIDENCIA_RECHAZO_CONFIRMADO).isEqualTo("PROCESSOR_DECLINED")
        // …y la tabla vieja es un subconjunto de la única que queda.
        listOf("G500", "G504", "E605", "E606").forEach { code ->
            assertThat(code).isIn(AngelPayOutcomeClassifier.CODIGOS_RECHAZO_CONFIRMADO)
        }
    }

    private fun desenlace(
        status: String? = "ERROR",
        codigoSdk: String? = null,
        codigoGateway: String? = null,
    ) = AngelPayOutcomeClassifier.clasificar(
        aprobado = false, status = status, codigoSdk = codigoSdk, codigoGateway = codigoGateway,
    )
}
