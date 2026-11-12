package com.jaac.avoqado_campo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jaac.avoqado_campo.cola.EstadoPieza
import com.jaac.avoqado_campo.cola.Pieza
import com.jaac.avoqado_campo.cola.TipoPieza
import com.jaac.avoqado_campo.identidad.EstadoDelCelular
import com.jaac.avoqado_campo.identidad.Identidad

/**
 * Sin red es un estado normal: naranja suave, nunca rojo. Con red: «Subiendo N…», salvo que la subida no pueda correr: con
 * `necesitaPin` espera el PIN, y `DESCONOCIDO` no sube nada (nunca se promete). null = sin banda.
 */
fun textoDeBanda(sinRed: Boolean, estado: EstadoDelCelular, necesitaPin: Boolean, pendientes: Int): String? = when {
    sinRed -> SIN_SENAL
    pendientes == 0 || estado == EstadoDelCelular.DESCONOCIDO || estado == EstadoDelCelular.SIN_ACTIVAR -> null
    estado == EstadoDelCelular.VINCULADO && necesitaPin -> "Esperando tu PIN para subir $pendientes"
    else -> "Subiendo $pendientes…"
}

/**
 * Lo que «Subiendo N…» cuenta (D2): sólo lo que la pasada puede subir ahora, con el filtro del `Subidor` y la `SubidorDeGracia`.
 * Fuera: lo retenido (no es PENDIENTE), lo que espera a su venta o su entrada (su padre sigue vivo en [piezas], la lista de
 * `pendientesDeVer`: un padre HECHA ya no está). Vinculado, también lo de otra sucursal o de una que ya no es suya; en la gracia,
 * la corrección suelta (la gracia la rechaza).
 */
fun cuantasPuedenSubir(piezas: List<Pieza>, identidad: Identidad): Int {
    val vivas = piezas.mapTo(HashSet()) { it.llave }
    val gracia = identidad.estado == EstadoDelCelular.DESVINCULADO || identidad.estado == EstadoDelCelular.DADO_DE_BAJA
    return piezas.count { p ->
        p.estado == EstadoPieza.PENDIENTE && (p.dependeDe == null || p.dependeDe !in vivas) && when {
            gracia -> !(p.tipo == TipoPieza.EVIDENCIA && p.dependeDe == null)
            else -> p.venueId == identidad.venueIdDeLaSesion && identidad.sucursales.any { it.venueId == p.venueId }
        }
    }
}

internal const val SIN_SENAL = "Sin señal · lo que hagas se guarda en el celular y se sube solo"

/** Sin señal: el recuadro naranja del mockup (no es un error, nunca rojo). Esperando el PIN: ámbar. Subiendo: gris. */
@Composable
fun BandaDeSenal(texto: String?, modifier: Modifier = Modifier) {
    if (texto == null) return
    val tono = when {
        texto == SIN_SENAL -> Tono.SIN_RED
        texto.startsWith("Esperando tu PIN") -> Tono.AVISO
        else -> Tono.NEUTRO
    }
    Recuadro(texto, tono, modifier)
}
