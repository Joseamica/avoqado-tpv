package com.avoqado.cobropropio.privacidad

/**
 * Lo único que sale de una tarjeta hacia afuera (pantalla, registros, eventos). El número completo, la banda y las llaves
 * nunca pasan de aquí: se enseña el BIN (6) y los últimos 4, como en un ticket. Rescatado del piloto (`lectorlab/Enmascarar.kt`).
 */
internal object Enmascarar {

    private const val TAPADO = "****"
    private val BANDA = Regex("[0-9]{12,19}[=Dd][0-9]*")
    private val HEX_LARGO = Regex("[0-9A-Fa-f]{16,}")
    private val RACHA_DE_DIGITOS = Regex("[0-9]{12,}")

    /** "4111111111111111" → "411111****1111": el relleno es fijo y no deja ver el largo. Menos de 13 dígitos se tapa todo. */
    fun pan(pan: String): String {
        val d = pan.filter { it in '0'..'9' }
        if (d.length < 13) return TAPADO
        return d.take(6) + TAPADO + d.takeLast(4)
    }

    /** Tapa en un texto libre las bandas (`=` o `D`), el hex de 16 o más (llaves, criptogramas) y rachas de 12 o más dígitos. */
    fun texto(s: String): String =
        s.replace(BANDA, TAPADO).replace(HEX_LARGO, TAPADO).replace(RACHA_DE_DIGITOS, TAPADO)

    /** ¿`valor` es el PAN o lleva una racha de 12 o más dígitos seguidos del PAN? El BIN y los últimos 4 sí pueden salir. */
    fun contieneDatoDeLaTarjeta(valor: String, pan: String?): Boolean {
        val d = pan?.filter { it in '0'..'9' }.orEmpty()
        if (d.isEmpty()) return false
        if (valor == pan || valor == d) return true
        return (0..d.length - 12).any { valor.contains(d.substring(it, it + 12)) }
    }
}
