package com.jaac.avoqado_campo.identidad

/** Almacén falso para pruebas JVM: los mismos datos se pueden leer con otra instancia. */
class AlmacenEnMemoria(val datos: MutableMap<String, String> = mutableMapOf()) : AlmacenSeguro {
    private var gancho: Pair<String, () -> Unit>? = null

    /** H2: la PRÓXIMA lectura de [clave] devuelve lo de antes y luego corre [f] (un cambio entre dos lecturas). Una sola vez. */
    fun despuesDeLeer(clave: String, f: () -> Unit) { gancho = clave to f }

    override fun leer(clave: String): String? = datos[clave].also {
        val g = gancho
        if (g != null && g.first == clave) { gancho = null; g.second() }
    }
    /** r10: como `commit()` en false: el cambio queda en memoria y la escritura lanza (lo que hace el almacén de verdad). */
    var fallarAlEscribir: (Map<String, String?>) -> Boolean = { false }

    override fun escribir(cambios: Map<String, String?>) {
        cambios.forEach { (k, v) -> if (v == null) datos.remove(k) else datos[k] = v }
        if (fallarAlEscribir(cambios)) throw IllegalStateException("commit() devolvió false")
    }
}
