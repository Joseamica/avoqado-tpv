package com.jaac.avoqado_campo.custodia

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Las ventas de un mes dentro de un estado de «Mis ventas». [clave]: «2026-11» o [SIN_FECHA]. */
data class GrupoDeMes(val clave: String, val titulo: String, val filas: List<FilaSim>)

const val SIN_FECHA = "sin-fecha"

private val MESES = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

/**
 * 0.6.0 (el cliente, 20 y 26-nov): una lista de 1,000 «Exitosas» no se lee. Por mes, del más reciente al más viejo, en la zona
 * del celular, con su título «noviembre 2026 (45)»; dentro, lo más nuevo arriba. Lo que no trae fecha va al final («Sin fecha»).
 */
fun porMes(filas: List<FilaSim>, zona: ZoneId): List<GrupoDeMes> {
    val (conFecha, sinFecha) = filas.partition { it.fecha != null }
    val meses = conFecha.groupBy { YearMonth.from(Instant.ofEpochMilli(it.fecha!!).atZone(zona)) }.toSortedMap(reverseOrder())
        .map { (m, f) -> GrupoDeMes(m.toString(), "${MESES[m.monthValue - 1]} ${m.year} (${f.size})", f.sortedByDescending { it.fecha }) }
    return if (sinFecha.isEmpty()) meses else meses + GrupoDeMes(SIN_FECHA, "Sin fecha (${sinFecha.size})", sinFecha)
}

/** El que va abierto al entrar: el mes actual o, si no hay, el primero de la lista (el más reciente, o «Sin fecha» si es el único). */
fun abiertoDeFabrica(grupos: List<GrupoDeMes>, hoy: YearMonth): String? =
    grupos.firstOrNull { it.clave == hoy.toString() }?.clave ?: grupos.firstOrNull()?.clave

/** [alternados]: los que la persona tocó; un toque invierte lo de fábrica. */
fun estaAbierto(clave: String, deFabrica: String?, alternados: Set<String>): Boolean = (clave == deFabrica) != (clave in alternados)
