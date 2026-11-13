package com.jaac.avoqado_tpv.core.observability

import android.app.Application
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber

/**
 * D64 (13-nov): la terminal escribía el número COMPLETO de la tarjeta en su registro
 * («✅ Track2 extracted: <16 dígitos>...»). PCI: nunca se registra el número entero; a lo más los
 * primeros 6 y los últimos 4. Estas pruebas fallan si un registro trae un número de tarjeta.
 *
 * Los números son tarjetas de PRUEBA públicas (Visa 4111…, Mastercard 5555…, Amex 3782…, Mastercard
 * serie 2 2223…), nunca de una persona.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class TapaNumeroDeTarjetaTest {

    /** 13 a 19 dígitos seguidos que pasan Luhn y empiezan como tarjeta: lo que nunca debe salir. */
    private fun traeNumeroDeTarjeta(texto: String): Boolean =
        Regex("""(?<!\d)\d{13,19}(?!\d)""").findAll(texto).any { TapaNumeroDeTarjeta.pareceTarjeta(it.value) }

    @Before
    fun limpiar() {
        Timber.uprootAll()
        ShadowLog.clear()
    }

    @After
    fun soltar() {
        Timber.uprootAll()
    }

    @Test
    fun `tapa el numero y deja sólo 6 y 4`() {
        assertEquals("411111******1111", TapaNumeroDeTarjeta.tapar("4111111111111111"))
        assertEquals("Visa 411111******1111 ok", TapaNumeroDeTarjeta.tapar("Visa 4111111111111111 ok"))
    }

    @Test
    fun `tapa el numero dentro de un Track2 (separador D o igual)`() {
        val track2 = "5555555555554444D29122011234567800000F"
        val tapado = TapaNumeroDeTarjeta.tapar("Track2: $track2")
        assertFalse(tapado, traeNumeroDeTarjeta(tapado))
        assertTrue(tapado, tapado.contains("555555******4444"))
        assertFalse(traeNumeroDeTarjeta(TapaNumeroDeTarjeta.tapar("5555555555554444=2912201")))
    }

    @Test
    fun `tapa Amex de 15, Mastercard serie 2 y 19 digitos`() {
        assertEquals("378282*****0005", TapaNumeroDeTarjeta.tapar("378282246310005"))
        assertEquals("222300******3222", TapaNumeroDeTarjeta.tapar("2223003122003222"))
        assertFalse(traeNumeroDeTarjeta(TapaNumeroDeTarjeta.tapar("6011000990139424")))
        assertFalse(traeNumeroDeTarjeta(TapaNumeroDeTarjeta.tapar("4000000000000000006")))
    }

    @Test
    fun `no toca lo que no es tarjeta - fechas, milisegundos, ICCID, enmascarados`() {
        val normales = listOf(
            "batch: 20261113005950",          // fecha-hora de Blumon (empieza con 20, no es serie 2 de MC)
            "ts=1763013600123",               // milisegundos (empieza con 1)
            "ICCID 8952140061234567893",      // SIM de PlayTelecom (empieza con 8)
            "masked=512912******7182",        // ya enmascarado
            "monto 12345 serial 2841548417",  // cortos
            "4111111111111112",               // empieza como Visa pero no pasa Luhn
            "20261113005957",                 // fecha-hora que SÍ pasa Luhn: el prefijo 2026 no es de tarjeta
            "1763013600129",                  // milisegundos que SÍ pasan Luhn: empiezan con 1
        )
        normales.forEach { assertEquals(it, TapaNumeroDeTarjeta.tapar(it)) }
    }

    @Test
    fun `el arbol de depuracion no escribe el numero en logcat`() {
        Timber.plant(ArbolDeDepuracionSinTarjetas())
        Timber.tag("PaymentViewModel").i("   ✅ Track2 extracted: %s... (length: 38)", "5555555555554444")
        Timber.tag("PaymentViewModel").d("   Track2: ${"5555555555554444D2912201"}...")
        Timber.tag("PaymentViewModel").e(RuntimeException("tarjeta 4111111111111111"), "falló")

        val escrito = ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertTrue("el árbol no escribió nada: la prueba no vigila", escrito.contains("Track2"))
        assertFalse("logcat trae un número de tarjeta:\n$escrito", traeNumeroDeTarjeta(escrito))
        assertTrue(escrito, escrito.contains("555555******4444"))
        assertTrue(ShadowLog.getLogs().any { it.tag == "PaymentViewModel" && it.type == Log.INFO })
    }

    @Test
    fun `la miga de Crashlytics tampoco lleva el numero`() {
        val miga = CrashReportingTree.migaja(Log.WARN, "PaymentViewModel", "Track2: 4111111111111111D2512")
        assertFalse(miga, traeNumeroDeTarjeta(miga))
        assertEquals("W/PaymentViewModel: Track2: 411111******1111D2512", miga)
    }
}
