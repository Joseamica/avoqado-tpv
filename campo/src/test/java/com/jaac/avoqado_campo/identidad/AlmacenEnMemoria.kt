package com.jaac.avoqado_campo.identidad

/** Almacén falso para pruebas JVM: los mismos datos se pueden leer con otra instancia. */
class AlmacenEnMemoria(val datos: MutableMap<String, String> = mutableMapOf()) : AlmacenSeguro {
    override fun leer(clave: String): String? = datos[clave]
    override fun escribir(cambios: Map<String, String?>) {
        cambios.forEach { (k, v) -> if (v == null) datos.remove(k) else datos[k] = v }
    }
}
