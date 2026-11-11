package com.jaac.avoqado_campo.cola

import java.security.MessageDigest

/** La misma que calcula el SERVIDOR: sha256 hex minúsculas de los bytes UTF-8 de `tipo\nvenueId\ncuerpoJson`. */
fun huella(tipo: String, venueId: String, cuerpoJson: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$tipo\n$venueId\n$cuerpoJson".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
