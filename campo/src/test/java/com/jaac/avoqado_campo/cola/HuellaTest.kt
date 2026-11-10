package com.jaac.avoqado_campo.cola

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HuellaTest {
    private fun sha256(texto: String) =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun `huella es el sha256 de tipo, venue y cuerpo separados por salto`() {
        assertEquals(sha256("VENTA\nv1\n{\"a\":1}"), huella("VENTA", "v1", "{\"a\":1}"))
    }

    @Test fun `un espacio en el cuerpo cambia la huella`() {
        assertNotEquals(huella("VENTA", "v1", "{\"a\":1}"), huella("VENTA", "v1", "{\"a\": 1}"))
    }

    @Test fun `64 hex minusculas`() {
        assertTrue(Regex("^[a-f0-9]{64}$").matches(huella("ENTRADA", "v1", "{}")))
    }

    @Test fun `la ruta de la foto usa la fecha de Mexico y la url va sin token`() {
        // 03:00 UTC del 11 = 21:00 del 10 en la Ciudad de México.
        val ruta = rutaDe("dev", "v1", "checada", fechaEnMexico(java.time.Instant.parse("2026-11-11T03:00:00Z").toEpochMilli()), "k", "entrada")
        assertEquals("dev/venues/v1/campo/checada/2026-11-10/k_entrada.jpg", ruta)
        assertEquals(
            "https://firebasestorage.googleapis.com/v0/b/avoqado-d0a24.appspot.com/o/dev%2Fvenues%2Fv1%2Fcampo%2Fchecada%2F2026-11-10%2Fk_entrada.jpg?alt=media",
            urlDe(ruta),
        )
        assertEquals("https://firebasestorage.googleapis.com/v0/b/avoqado-d0a24.appspot.com/o/a%20b?alt=media", urlDe("a b"))
    }
}
