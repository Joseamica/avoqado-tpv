package com.avoqado.motorlab

import kotlin.concurrent.thread

/**
 * Bloque P0: por qué vía una app nueva lee la serie de ESTA PAX desde un arranque en frío. Sólo se MIDE y se enseña en la
 * cabecera; nada decide con esto. Cada vía, en orden, se lee una vez y nunca lanza: si lanza o sale en blanco es «ilegible», y
 * eso también es el dato (sólo el nombre de su clase, `?` si no tiene). Nunca enseña más de los 4 últimos caracteres; una
 * serie más corta sale como `…?`.
 */
internal fun lineaDeLaSerie(vias: List<Pair<String, () -> String?>>, referencia: String?): String =
    "Serie del aparato: " + vias.joinToString(" · ") { (nombre, via) ->
        runCatching { via()?.trim() }.fold(
            onSuccess = { s ->
                if (s.isNullOrEmpty()) {
                    "$nombre ilegible (vacía)"
                } else {
                    val identidad = when {
                        referencia == null -> "sin identidad"
                        s.equals(referencia.trim(), ignoreCase = true) -> "coincide"
                        else -> "NO coincide"
                    }
                    "$nombre …${if (s.length < 4) "?" else s.takeLast(4)} (identidad: $identidad)"
                }
            },
            onFailure = { "$nombre ilegible (${it.javaClass.simpleName.ifEmpty { "?" }})" },
        )
    }

/** Una vía que no regresó dentro de su plazo ([conPlazo]). Sin mensaje: sólo su nombre llega a la cabecera. */
internal class SinRespuesta : Exception()

/**
 * [via] con plazo: corre en su propio hilo daemon y se espera hasta [ms]. Si sigue viva, lanza [SinRespuesta]; si terminó, da
 * su valor o relanza lo que lanzó, sea lo que sea. Así un DAL colgado sale «ilegible (SinRespuesta)» y las demás vías se miden.
 * [ms] debe ser positivo: `join(0)` es esperar sin tope.
 *
 * ponytail: el hilo colgado se abandona (daemon, muere con el proceso); si algún día se mide seguido, interrumpirlo.
 */
internal fun conPlazo(ms: Long, via: () -> String?): () -> String? {
    require(ms > 0)
    return {
        var valor: String? = null
        var falla: Throwable? = null
        val hilo = thread(isDaemon = true, name = "motorlab-serie-via") {
            try {
                valor = via()
            } catch (t: Throwable) {
                falla = t
            }
        }
        hilo.join(ms) // al terminar el hilo, lo que escribió ya se ve aquí
        if (hilo.isAlive) throw SinRespuesta()
        falla?.let { throw it }
        valor
    }
}
