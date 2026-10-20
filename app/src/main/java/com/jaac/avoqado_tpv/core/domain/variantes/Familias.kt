package com.jaac.avoqado_tpv.core.domain.variantes

import java.math.BigDecimal

// Núcleo puro de las familias de variantes (sin Android): las reglas Q1–Q6 de `Familias.kt` de avoqado-android, portadas 1:1.
//
// Diferencias con el POS Android, y sólo estas:
// - El dinero es `BigDecimal` (en la terminal `Double` no se usa para precios). Por eso `PrecioDeFamilia(minimo, todosIguales)` de
//   Android se parte en `precioMinimo` y `preciosIguales`; esta última compara el valor (199.0 == 199.00), no la escala.
// - La terminal tiene TRES modelos de producto (Órdenes/Cobro, Kiosco, Mesas): las reglas son genéricas sobre `T` y reciben cómo
//   leer cada modelo (`ReglasDeFamilia`), en vez de depender de uno.
// - `ReglasDeFamilia.entradasParaMostrar`: una coincidencia por CÓDIGO (SKU, barras, GTIN) muestra la talla suelta, nunca la familia.
// - `normalizar` y `textosExtra` (opcionales) dejan que cada pantalla conserve su búsqueda de hoy: sin acentos (Cobro rápido) y con
//   descripción (Órdenes). Con los valores por defecto la búsqueda es la del POS Android, 1:1.

/** Una opción de variante tal como la manda el server: «Talla = M», con su posición y la de su valor. */
data class OpcionDeVariante(val name: String, val value: String, val namePosition: Int, val valuePosition: Int)

/** Los cuatro campos de familia de un producto, leídos de cualquiera de los tres modelos. */
data class DatosDeFamilia(
    val groupId: String?,
    val groupName: String?,
    val groupImageUrl: String?,
    val opciones: List<OpcionDeVariante>?,
)

/** Un producto sin familia. Una sola instancia: los accesores de cada pantalla escriben `familia ?: SIN_FAMILIA` sin crear un objeto por llamada. */
val SIN_FAMILIA = DatosDeFamilia(null, null, null, null)

data class EjeDeFamilia(val nombre: String, val valores: List<String>)

data class Familia<T>(
    val groupId: String,
    val nombre: String,
    val imagenUrl: String?,
    val variantes: List<T>,
    val ejes: List<EjeDeFamilia>,
)

sealed interface EntradaDeCatalogo<T> {
    data class Suelto<T>(val producto: T) : EntradaDeCatalogo<T>
    data class DeFamilia<T>(val familia: Familia<T>) : EntradaDeCatalogo<T>
}

sealed interface ResultadoDeBusqueda<T> {
    data class DeProducto<T>(val producto: T) : ResultadoDeBusqueda<T>
    data class DeFamilia<T>(val familia: Familia<T>) : ResultadoDeBusqueda<T>
}

/**
 * Las reglas de familia para un modelo de producto `T`. Cada accesor dice cómo leer ese modelo:
 * [datos] los 4 campos de familia; [imagen] su imagen propia; [precio] su precio (con IVA, como lo ve el cliente);
 * [agotado] si ya no hay existencia; [nombre] su nombre; [codigos] sus códigos de barras/SKU/GTIN (los nulos se ignoran).
 *
 * Dos parámetros opcionales conservan la búsqueda de cada pantalla (sólo [buscar] y [entradasParaMostrar] los usan):
 * [normalizar] se aplica al texto buscado y a TODO texto comparado (nombre, nombre de familia, textos extra y códigos); el valor por
 * defecto (`lowercase`) es el del POS Android, y p. ej. Cobro rápido pasa uno que además quita acentos («cafe» encuentra «Café»).
 * [textosExtra] son textos que coinciden COMO TEXTO (un acierto da la fila de la familia, igual que el nombre), p. ej. la
 * descripción en Órdenes; los nulos se ignoran.
 */
class ReglasDeFamilia<T>(
    val datos: (T) -> DatosDeFamilia,
    val imagen: (T) -> String?,
    val precio: (T) -> BigDecimal,
    val agotado: (T) -> Boolean,
    val nombre: (T) -> String,
    val codigos: (T) -> List<String?>,
    val normalizar: (String) -> String = { it.lowercase() },
    val textosExtra: (T) -> List<String?> = { emptyList() },
) {

    /** Valores de opción de una variante: nombre del eje → valor. */
    fun valoresDe(p: T): Map<String, String> = datos(p).opciones.orEmpty().associate { it.name to it.value }

    private fun firmaDeEjes(p: T): List<String> = datos(p).opciones.orEmpty().sortedBy { it.namePosition }.map { it.name }

    private fun tieneDatosDeFamilia(p: T): Boolean {
        val d = datos(p)
        val opciones = d.opciones
        return !d.groupName.isNullOrBlank() && !opciones.isNullOrEmpty() &&
            opciones.all { it.name.isNotBlank() && it.value.isNotBlank() }
    }

    /**
     * Las familias VÁLIDAS del catálogo COMPLETO, por `groupId`, en UN recorrido. Se calcula una vez por cambio del catálogo.
     * Q1: es familia con 2+ variantes, TODAS con datos completos (nombre de familia, opciones con nombre y valor no vacíos), el mismo
     * nombre de familia y los MISMOS ejes (sin ningún eje repetido). Si no, sus variantes se pintan sueltas.
     */
    fun familiasDe(catalogo: List<T>): Map<String, Familia<T>> =
        catalogo.filter { !datos(it).groupId.isNullOrBlank() }.groupBy { datos(it).groupId!! }.mapNotNull { (id, vs) ->
            val firma = firmaDeEjes(vs.first())
            val valida = vs.size >= 2 && vs.all(::tieneDatosDeFamilia) && vs.all { firmaDeEjes(it) == firma } &&
                vs.map { datos(it).groupName }.distinct().size == 1
            val ejes = if (valida) ejesDe(vs) else emptyList()
            // Un eje con el mismo nombre en dos namePosition saldría repetido (la hoja usa el nombre como llave): la familia queda suelta.
            if (!valida || ejes.distinctBy { it.nombre }.size != ejes.size) null else id to Familia(
                groupId = id,
                nombre = datos(vs.first()).groupName!!,
                imagenUrl = vs.firstNotNullOfOrNull { datos(it).groupImageUrl } ?: vs.firstNotNullOfOrNull { imagen(it) },
                variantes = vs,
                ejes = ejes,
            )
        }.toMap()

    /**
     * Coloca las familias en la lista que se va a PINTAR (ya filtrada por menú/categoría): la familia ocupa el lugar de su primera
     * variante y sale una vez. O(n). Sin familias devuelve `Suelto` por cada producto, en el mismo orden.
     */
    fun agruparCatalogo(productos: List<T>, familias: Map<String, Familia<T>>): List<EntradaDeCatalogo<T>> {
        val vistas = mutableSetOf<String>()
        return buildList {
            for (p in productos) {
                val f = datos(p).groupId?.let { familias[it] }
                when {
                    f == null -> add(EntradaDeCatalogo.Suelto(p))
                    vistas.add(f.groupId) -> add(EntradaDeCatalogo.DeFamilia(f))
                }
            }
        }
    }

    /** Ejes por `namePosition`; valores por `valuePosition` (empate: orden de aparición), sin repetir. */
    fun ejesDe(variantes: List<T>): List<EjeDeFamilia> {
        val opciones = variantes.flatMap { datos(it).opciones.orEmpty() }
        return opciones.map { it.name to it.namePosition }.distinct().sortedBy { it.second }.map { (nombre, _) ->
            EjeDeFamilia(
                nombre = nombre,
                valores = opciones.filter { it.name == nombre }.withIndex()
                    .sortedWith(compareBy({ it.value.valuePosition }, { it.index }))
                    .map { it.value.value }.distinct(),
            )
        }
    }

    /** Q2: los ejes de un solo valor vienen elegidos. */
    fun seleccionInicial(f: Familia<T>): Map<String, String> =
        f.ejes.filter { it.valores.size == 1 }.associate { it.nombre to it.valores.single() }

    /** La variante que corresponde a una elección COMPLETA; null si falta un eje o no existe esa combinación. */
    fun varianteElegida(f: Familia<T>, seleccion: Map<String, String>): T? {
        if (f.ejes.any { it.nombre !in seleccion }) return null
        return f.variantes.firstOrNull { valoresDe(it) == seleccion.filterKeys { k -> f.ejes.any { e -> e.nombre == k } } }
    }

    /** Q3: ¿existe una variante con eje=valor que respete lo ya elegido en los OTROS ejes? (false ⇒ chip atenuado, pero tocable). */
    fun valorDisponible(f: Familia<T>, seleccion: Map<String, String>, eje: String, valor: String): Boolean =
        f.variantes.any { v ->
            val vals = valoresDe(v)
            vals[eje] == valor && seleccion.all { (k, s) -> k == eje || vals[k] == s }
        }

    /**
     * Q3: elegir un valor suelta las elecciones de otros ejes que ya no combinan con él. Es codicioso en el ORDEN de los ejes: parte de
     * `{eje: valor}` y recorre `f.ejes`; conserva cada elección previa sólo si existe una variante que calce con lo ya conservado + esa
     * elección. Así, con 3+ ejes, un eje que sí combina con el valor nuevo no se pierde porque otro no combine (S6).
     */
    fun elegir(f: Familia<T>, seleccion: Map<String, String>, eje: String, valor: String): Map<String, String> {
        val conservada = linkedMapOf(eje to valor)
        for (e in f.ejes) {
            if (e.nombre == eje) continue
            val s = seleccion[e.nombre] ?: continue
            if (valorDisponible(f, conservada, e.nombre, s)) conservada[e.nombre] = s
        }
        return conservada
    }

    /** Q4: el precio más bajo de la familia («desde …»). */
    fun precioMinimo(f: Familia<T>): BigDecimal = f.variantes.map(precio).min()

    /** Q4: ¿todas cuestan lo mismo? Se compara el valor (199.0 == 199.00), no la escala de `BigDecimal`. */
    fun preciosIguales(f: Familia<T>): Boolean {
        val primero = precio(f.variantes.first())
        return f.variantes.all { precio(it).compareTo(primero) == 0 }
    }

    /** Q5: el botón del padre dice «Agotado» sólo si TODAS lo están. */
    fun agotada(f: Familia<T>): Boolean = f.variantes.all(agotado)

    /**
     * Q6 — búsqueda local, O(n) con las familias ya calculadas. Texto (nombre) ⇒ la fila de su FAMILIA (una vez); sólo código
     * (sku/barcode/gtin, contiene) ⇒ la fila de la VARIANTE. Vacío ⇒ nada. Sin familias: lo de hoy (nombre o código).
     * Se conserva la normalización del POS Android (sin `trim`; vacío = `isBlank`); [normalizar] y [textosExtra] la ajustan por pantalla.
     */
    fun buscar(productos: List<T>, familias: Map<String, Familia<T>>, texto: String): List<ResultadoDeBusqueda<T>> {
        if (texto.isBlank()) return emptyList()
        val q = normalizar(texto)
        // Un normalizador que deja el texto en nada (p. ej. sólo acentos sueltos) no es una búsqueda: `contains("")` acertaría con todo.
        if (q.isEmpty()) return emptyList()
        fun coincide(t: String?) = t != null && normalizar(t).contains(q)
        val vistas = mutableSetOf<String>()
        return buildList {
            for (p in productos) {
                val d = datos(p)
                val porTexto = coincide(nombre(p)) || coincide(d.groupName) || textosExtra(p).any(::coincide)
                val porCodigo = codigos(p).any(::coincide)
                val f = d.groupId?.let { familias[it] }
                when {
                    porTexto && f != null -> if (vistas.add(f.groupId)) add(ResultadoDeBusqueda.DeFamilia(f))
                    porTexto || porCodigo -> add(ResultadoDeBusqueda.DeProducto(p))
                }
            }
        }
    }

    /**
     * Lo que pinta una cuadrícula o la búsqueda de la terminal. Sin texto: el catálogo agrupado. Con texto: [buscar], donde una
     * familia sale como familia y un producto como `Suelto` — AUNQUE sea la talla de una familia: quien teclea o escanea un código
     * quiere esa talla, no el botón de la familia (Codex r1 #1). Un texto en blanco no es una búsqueda (como en [buscar]).
     */
    fun entradasParaMostrar(visibles: List<T>, familias: Map<String, Familia<T>>, texto: String): List<EntradaDeCatalogo<T>> {
        if (texto.isBlank()) return agruparCatalogo(visibles, familias)
        return buscar(visibles, familias, texto).map {
            when (it) {
                is ResultadoDeBusqueda.DeFamilia -> EntradaDeCatalogo.DeFamilia(it.familia)
                is ResultadoDeBusqueda.DeProducto -> EntradaDeCatalogo.Suelto(it.producto)
            }
        }
    }
}
