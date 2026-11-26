package com.avoqado.motorlab

/**
 * Bloque P0: por qué vía una app nueva lee la serie de ESTA PAX desde un arranque en frío. Sólo se MIDE y se enseña en la
 * cabecera; nada decide con esto. Cada vía, en orden, se lee una vez y nunca lanza: si lanza o sale en blanco es «ilegible», y
 * eso también es el dato. Nunca enseña más de los 4 últimos caracteres; una serie más corta sale como `…?`.
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
            onFailure = { "$nombre ilegible (${it.javaClass.simpleName})" },
        )
    }
