package com.jaac.avoqado_campo.cola

import com.jaac.avoqado_campo.identidad.AlmacenSeguro

/** `captureSeq` de las evidencias: crece de uno en uno, empieza en 1 y sobrevive a matar la app (`commit`). */
class SecuenciaDeCaptura(private val almacen: AlmacenSeguro) {
    @Synchronized
    fun siguiente(): Long {
        val n = (almacen.leer(CLAVE)?.toLongOrNull() ?: 0L) + 1
        almacen.escribir(mapOf(CLAVE to n.toString()))
        return n
    }

    private companion object { const val CLAVE = "captura.seq" }
}
