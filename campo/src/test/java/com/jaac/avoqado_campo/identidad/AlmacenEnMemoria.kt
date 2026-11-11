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
    /**
     * R-D (r11): `commit()` en false. La app se detiene en ese momento ([AppDetenida]) y NO llega al disco: [datos] es el disco,
     * así que «reabrir» es otro `QuienSoy` sobre los mismos [datos].
     */
    var fallarAlEscribir: (Map<String, String?>) -> Boolean = { false }

    override fun escribir(cambios: Map<String, String?>) {
        if (fallarAlEscribir(cambios)) throw AppDetenida()
        cambios.forEach { (k, v) -> if (v == null) datos.remove(k) else datos[k] = v }
    }
}

/** Lo que en la app es `Process.killProcess` (R-D): un `Error`, que ningún `catch (e: Exception)` atrapa. */
class AppDetenida : Error("R-D: la app se detuvo porque el almacén no guardó")
