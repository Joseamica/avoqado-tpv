package com.avoqado.cobropropio.blumon

private val CODIGO = Regex("^[A-Z0-9_]{1,16}$")
private val ISO_NUMERICO = Regex("^([0-9]+)(?:\\.0+)?$")
private val CODIGO_DE_BLUMON = Regex("^[A-Z]{2,4}_[0-9]{3}$")

/**
 * Recorte y mayúsculas; un ISO numérico pierde el `.0` y va a dos dígitos como en ISO 8583 (`"56.0"` ⇒ `"56"`,
 * `"1.0"` ⇒ `"01"`, `"9"` ⇒ `"09"`). Sin la forma `^[A-Z0-9_]{1,16}$` ⇒ `null`.
 */
internal fun normalizarCodigo(crudo: String): String? {
    val t = crudo.trim().uppercase()
    val n = ISO_NUMERICO.matchEntire(t)?.let { it.groupValues[1].trimStart('0').padStart(2, '0') } ?: t
    return n.takeIf { CODIGO.matches(it) }
}

/**
 * El catálogo de rechazos de Blumon (`blumon-catalogo-de-rechazos.tsv`, copia exacta del medido el 18-nov): código
 * normalizado ⇒ leyenda para la terminal. Las filas `BP` (y las que traen un código de Blumon) se indexan por ese código;
 * las demás por su ISO. Si un código se repite, gana la primera fila.
 */
internal class CatalogoDeRechazos internal constructor(private val leyendas: Map<String, String>) {

    fun leyenda(codigo: String): String? = leyendas[codigo]

    fun contiene(codigo: String): Boolean = codigo in leyendas

    companion object {
        val BLUMON: CatalogoDeRechazos by lazy {
            val tsv = requireNotNull(CatalogoDeRechazos::class.java.getResourceAsStream("/blumon-catalogo-de-rechazos.tsv")) {
                "falta el catálogo de rechazos"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
            desde(tsv)
        }

        internal fun desde(tsv: String): CatalogoDeRechazos {
            val leyendas = LinkedHashMap<String, String>()
            for (linea in tsv.lineSequence().drop(1)) {
                val c = linea.split('\t')
                if (c.size < 3) continue
                val clave = if (CODIGO_DE_BLUMON.matches(c[1].trim())) c[1].trim() else normalizarCodigo(c[0]) ?: continue
                leyendas.putIfAbsent(clave, c[2].trim())
            }
            return CatalogoDeRechazos(leyendas)
        }
    }
}
