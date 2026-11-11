package com.jaac.avoqado_campo.venta

import com.jaac.avoqado_campo.cola.normalizarSerie

/**
 * Copiado de `app/…/serialized_sale/domain/IccidValidator.kt`, con una diferencia: aquí también se quitan los espacios
 * de en medio («8952 0200 …» de una etiqueta escrita a mano), igual que la cola compara la serie. La validez la decide la
 * copia de custodia (y el SERVIDOR al subir), no el formato.
 */
object Iccid {
    fun canonica(texto: String): String = normalizarSerie(texto)
}
