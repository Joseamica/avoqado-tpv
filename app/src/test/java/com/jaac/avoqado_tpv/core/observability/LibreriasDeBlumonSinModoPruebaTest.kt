package com.jaac.avoqado_tpv.core.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * D74 (14-nov): en el APK de producción la librería de Blumon viene compilada como depuración
 * (`clean_lib_services.BuildConfig.DEBUG = true`) y escribe en logcat lo que la tarjeta trae:
 * - `cipher_library` imprime con `System.out` el Track2, la TK y las llaves (y PAX `EmvProcess` el CDOL2);
 * - `HttpClientKt.getHttpClientInstance` agrega okhttp en BODY y, en el mismo `if (DEBUG)`, acepta cualquier certificado.
 * R8 no quita ninguna de las dos. Arreglo: `System.out` a un sumidero en release, y R8 lee ese DEBUG como false.
 */
class LibreriasDeBlumonSinModoPruebaTest {

    @Test
    fun `lo que una libreria imprime con System out ya no llega al registro`() {
        val original = System.out
        val registro = ByteArrayOutputStream() // lo que en Android termina en logcat con la etiqueta System.out
        System.setOut(PrintStream(registro, true))
        try {
            callarSalidaEstandar()
            System.out.println("getInformation: TransactionData(track2=4111111111111111D2912)")
            System.out.printf("Key: %s, Value: %s%n", "9F26", "AB")
            System.out.flush()
            assertEquals("", registro.toString())
        } finally {
            System.setOut(original)
        }
    }

    @Test
    fun `release lee como false el DEBUG de la libreria de Blumon`() {
        val reglas = File("proguard-rules.pro").readText()
        val regla = Regex(
            """-assumevalues\s+class\s+com\.example\.clean_lib_services\.BuildConfig\s*\{\s*""" +
                """public\s+static\s+final\s+boolean\s+DEBUG\s+return\s+false;\s*}""",
        )
        assertTrue("Falta en proguard-rules.pro la regla que apaga el modo depuración de lib_services", regla.containsMatchIn(reglas))
    }
}
