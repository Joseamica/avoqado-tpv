package com.jaac.avoqado_tpv.core.util

import java.math.RoundingMode

/**
 * na6094ff (encargo 1df453): un importe que llega como texto («10.0», «25», «11.5») se pinta con dos decimales y punto
 * («10.00»). Los encabezados del cobro (calificación, propina, cuenta, verificación) lo pintaban tal cual: «Paso 1 de 3 ·
 * $10.0». Si el texto no es un número, se deja como llegó (nunca se inventa un importe).
 */
fun importeConDosDecimales(importe: String?): String {
    val texto = importe?.trim().orEmpty()
    return texto.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: texto
}
