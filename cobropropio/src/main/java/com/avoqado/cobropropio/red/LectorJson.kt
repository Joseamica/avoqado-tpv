package com.avoqado.cobropropio.red

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Un valor de una respuesta, tal como vino. Nunca se imprime (v5-9). */
internal sealed interface Escalar {
    class Texto(val v: String) : Escalar {
        override fun toString() = "Texto(oculto)"
    }

    /** El número como lo escribió quien contestó (`12345`, `1.00`), sin pasar por `Double`. */
    class Numero(val crudo: String) : Escalar {
        override fun toString() = "Numero(oculto)"
    }

    class Booleano(val v: Boolean) : Escalar {
        override fun toString() = "Booleano(oculto)"
    }

    data object Nulo : Escalar

    /** Un objeto o una lista: su PRESENCIA cuenta (un campo que decide con este tipo es contradictorio, v5-8). */
    data object Compuesto : Escalar
}

/** Un paso de una ruta: la clave de un objeto, o un elemento de una lista (su propio tipo, nunca texto). */
internal sealed interface Segmento {
    data class Clave(val k: String) : Segmento
    data object Elemento : Segmento
}

/**
 * El ÚNICO lector de cuerpos de Blumon (R1-2, R3-1; v5 §3.1). Recorre el cuerpo crudo UNA vez con un `JsonReader` estricto
 * y en streaming, sin armar el árbol de Gson (que se queda con la última de dos claves repetidas). Guarda cada valor con su
 * ruta como LISTA de segmentos desde la raíz (R4-3, R5 familia 1): ninguna clave, tenga `.`, `[` o lo que sea, ocupa la ruta
 * de un campo anidado. Un objeto o una lista quedan como [Escalar.Compuesto] y además se recorren. Nunca sale un `JsonObject`.
 */
internal class Apariciones private constructor(
    /** Contradictorio: una clave repetida en el MISMO objeto, o una clave vacía, a cualquier profundidad. */
    val ambigua: Boolean,
    private val todas: List<Pair<List<Segmento>, Escalar>>,
) {
    /** TODAS las apariciones de [clave], en cualquier ruta (objetos anidados y listas incluidos). */
    fun de(clave: String): List<Escalar> = todas.filter { (r, _) -> r.lastOrNull() == Segmento.Clave(clave) }.map { it.second }

    /** Como [de], sin la aparición en la ruta EXACTA [salvo] (su última clave es [clave]). */
    fun deSalvo(clave: String, vararg salvo: String): List<Escalar> {
        val fuera = ruta(salvo)
        return todas.filter { (r, _) -> r.lastOrNull() == Segmento.Clave(clave) && r != fuera }.map { it.second }
    }

    /** El valor en la ruta EXACTA desde la raíz, clave por clave (`null` si no está). */
    fun en(vararg ruta: String): Escalar? = ruta(ruta).let { exacta -> todas.firstOrNull { it.first == exacta }?.second }

    /** ¿La última clave de [ruta] NO aparece en ninguna otra ruta? (También si no aparece en ninguna.) */
    fun soloEn(vararg ruta: String): Boolean {
        val exacta = ruta(ruta)
        return todas.none { (r, _) -> r != exacta && r.lastOrNull() == exacta.last() }
    }

    /**
     * Pureza del sobre (rondas 2 y 3): ¿trae algo la clave raíz [clave]? AUSENTE es sólo: la clave no viene, `null`, `{}` o
     * `[]`. Todo lo demás es PRESENTE: un escalar suelto, y cualquier objeto o lista con al menos un miembro, también si ese
     * miembro es `null` o un contenedor vacío (`{"a":null}`, `[null]`, `[{}]`).
     */
    fun tieneAlgoBajo(clave: String): Boolean = todas.any { (r, v) ->
        r.firstOrNull() == Segmento.Clave(clave) && (r.size > 1 || (v != Escalar.Nulo && v != Escalar.Compuesto))
    }

    /** Ronda 4 · X: los segmentos que cuelgan DIRECTAMENTE de la clave raíz [clave] (claves de un objeto, o elementos de una lista). */
    fun segmentosDirectosBajo(clave: String): List<Segmento> =
        todas.filter { (r, _) -> r.size == 2 && r.first() == Segmento.Clave(clave) }.map { it.first[1] }

    /** Ronda 4 · Y: todas las claves del cuerpo, a cualquier profundidad, tal como vinieron. */
    fun claves(): Set<String> = todas.flatMapTo(HashSet()) { (r, _) -> r.mapNotNull { (it as? Segmento.Clave)?.k } }

    /** Ronda 3 · V: ¿cuelga un objeto o una lista (aunque esté vacío) directamente de la clave raíz [clave]? */
    fun contenedorDirectoBajo(clave: String): Boolean =
        todas.any { (r, v) -> r.size == 2 && r.first() == Segmento.Clave(clave) && v == Escalar.Compuesto }

    /** El valor en [ruta] sólo si ésa es la ÚNICA ruta donde aparece su última clave en todo el cuerpo. */
    fun unica(vararg ruta: String): Escalar? = if (soloEn(*ruta)) en(*ruta) else null

    private fun ruta(claves: Array<out String>): List<Segmento> = claves.map(Segmento::Clave)

    override fun toString() = "Apariciones(ocultas)"

    companion object {
        private const val PROFUNDIDAD_MAXIMA = 32
        private const val VALORES_MAXIMOS = 4_096

        /** `null` = no es UN objeto JSON estricto, o se pasa de los topes. Nunca lanza. */
        fun leer(cuerpo: String): Apariciones? = try {
            val r = JsonReader(StringReader(cuerpo)).apply { strictness = Strictness.STRICT }
            val todas = ArrayList<Pair<List<Segmento>, Escalar>>()
            var ambigua = false

            fun valor(ruta: List<Segmento>, profundidad: Int) {
                check(profundidad <= PROFUNDIDAD_MAXIMA && todas.size < VALORES_MAXIMOS)
                when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        if (ruta.isNotEmpty()) todas += ruta to Escalar.Compuesto
                        r.beginObject()
                        val claves = HashSet<String>()
                        while (r.hasNext()) {
                            val k = r.nextName()
                            if (!claves.add(k) || k.isEmpty()) ambigua = true
                            valor(ruta + Segmento.Clave(k), profundidad + 1)
                        }
                        r.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        todas += ruta to Escalar.Compuesto
                        r.beginArray()
                        while (r.hasNext()) valor(ruta + Segmento.Elemento, profundidad + 1)
                        r.endArray()
                    }
                    JsonToken.STRING -> todas += ruta to Escalar.Texto(r.nextString())
                    JsonToken.NUMBER -> todas += ruta to Escalar.Numero(r.nextString())
                    JsonToken.BOOLEAN -> todas += ruta to Escalar.Booleano(r.nextBoolean())
                    JsonToken.NULL -> { r.nextNull(); todas += ruta to Escalar.Nulo }
                    else -> error("token inesperado")
                }
            }

            if (r.peek() != JsonToken.BEGIN_OBJECT) null
            else {
                valor(emptyList(), 0)
                if (r.peek() == JsonToken.END_DOCUMENT) Apariciones(ambigua, todas) else null
            }
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }
}
