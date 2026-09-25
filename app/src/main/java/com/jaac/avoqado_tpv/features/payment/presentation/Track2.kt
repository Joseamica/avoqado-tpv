package com.jaac.avoqado_tpv.features.payment.presentation

/**
 * El número de la tarjeta dentro del Track 2 que entrega el chip (tag EMV 57): los dígitos del principio, hasta el
 * separador. En el tag 57 el separador es la cifra hexadecimal **`D`**, no el `=` de la banda magnética.
 *
 * 🔴 25-sep-2026: dos sitios buscaban sólo `=`; al no encontrarlo tomaban TODA la cadena y los «últimos 4» salían del
 * relleno del final («000F», «995F», «005F»…). Medido en producción: ~2,000 cobros con tarjeta en 120 días, en varios
 * negocios, con los dígitos equivocados — Avoqado decía «****000F» donde Blumon decía «****2007».
 */
internal fun panDelTrack2(track2: String): String = track2.takeWhile { it.isDigit() }
