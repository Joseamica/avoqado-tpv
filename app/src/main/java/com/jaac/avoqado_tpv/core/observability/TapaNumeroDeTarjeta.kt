package com.jaac.avoqado_tpv.core.observability

/**
 * Tapa los números de tarjeta antes de que un texto llegue a un registro (logcat, Crashlytics).
 *
 * PCI (D64, 13-nov): nunca se registra el número completo; a lo más los primeros 6 y los últimos 4
 * («411111******1111»). Se tapa una corrida de 13 a 19 dígitos que empieza como tarjeta (3, 4, 5, 6 o la
 * serie 2 de Mastercard, 222100–272099) y pasa Luhn. Así no se tocan fechas de Blumon («20261113005950»),
 * milisegundos (empiezan con 1), ICCID de SIM (empiezan con 89) ni lo ya enmascarado.
 *
 * Es una red de seguridad: el código no debe registrar el número para empezar (ver el vigilante
 * `NingunRegistroLlevaElTrack2Test`).
 */
object TapaNumeroDeTarjeta {

    private val corridaDeDigitos = Regex("""(?<!\d)\d{13,19}(?!\d)""")

    fun tapar(texto: String): String {
        if (texto.length < 13) return texto
        return corridaDeDigitos.replace(texto) { m ->
            val d = m.value
            if (pareceTarjeta(d)) d.take(6) + "*".repeat(d.length - 10) + d.takeLast(4) else d
        }
    }

    /** `digitos` son sólo dígitos, de 13 a 19. */
    fun pareceTarjeta(digitos: String): Boolean {
        if (digitos.length !in 13..19 || !digitos.all { it in '0'..'9' }) return false
        val empiezaComoTarjeta = digitos[0] in '3'..'6' || digitos.take(6).toInt() in 222100..272099
        return empiezaComoTarjeta && pasaLuhn(digitos)
    }

    private fun pasaLuhn(digitos: String): Boolean {
        var suma = 0
        digitos.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            suma += d
        }
        return suma % 10 == 0
    }
}
