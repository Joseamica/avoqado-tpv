package com.jaac.avoqado_tpv.features.remote_command.domain

/**
 * ¿Este comando remoto era para ESTE aparato?
 *
 * El servidor no manda el comando a una terminal concreta: lo difunde al venue entero
 * (`broadcastToVenue(venueId, 'tpv_command', { terminalId, ... })`,
 * `avoqado-server/src/communication/sockets/index.ts:285`) y confía en que cada aparato
 * descarte lo que no le toca. Hasta ahora nadie descartaba nada, así que un FACTORY_RESET
 * dirigido a UNA terminal lo habrían ejecutado todos los aparatos conectados del negocio,
 * borrando su activación. Amaena tiene cuatro en el mismo venue.
 *
 * Puro a propósito: la decisión se prueba sin Android, sin sockets y sin SecureStorage.
 */
object CommandTarget {

    private const val PREFIJO_SERIAL = "AVQD-"

    /**
     * @param objetivo    el `terminalId` que viaja en el evento. El servidor lo llena con
     *                    `terminal.serialNumber || terminal.id` (`tpv-health.service.ts:324`).
     * @param serialPropio `SecureStorage.getSerialNumber()`.
     * @param idPropio     `SecureStorage.getTerminalId()` — el id que asignó el servidor.
     */
    fun isForThisTerminal(objetivo: String?, serialPropio: String?, idPropio: String?): Boolean {
        val destino = objetivo?.trim().orEmpty()

        // Falla ABIERTO: un servidor que no manda el campo se quedaría sin poder mandar
        // comandos a nadie. Quedarse sin comando remoto es peor que el riesgo que cubre
        // este filtro, y es ruidoso (alguien llama), no silencioso.
        if (destino.isEmpty()) return true

        val propios = listOfNotNull(serialPropio, idPropio)
            .map(::normalizar)
            .filter { it.isNotEmpty() }

        // Sin serial ni id no se puede DEMOSTRAR que el comando sea nuestro. Un aparato sin
        // activar no tiene por qué obedecer una orden destructiva.
        if (propios.isEmpty()) return false

        return normalizar(destino) in propios
    }

    /**
     * El mismo serial circula en producción como "AVQD-N860W173570", "N860W173570" y
     * "n860w173570" — el propio servidor tiene que reintentar con y sin prefijo al resolverlo
     * (`tpv-health.service.ts:545-565`). Comparar en crudo dejaría fuera comandos legítimos.
     */
    private fun normalizar(valor: String): String =
        valor.trim().uppercase().removePrefix(PREFIJO_SERIAL)
}
