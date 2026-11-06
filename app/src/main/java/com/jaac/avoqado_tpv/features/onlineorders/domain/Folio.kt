package com.jaac.avoqado_tpv.features.onlineorders.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Las dos listas de «Pedidos por pagar»: lo que falta cobrar y lo ya pagado que falta entregar. */
enum class SeccionDeFolios(val clave: String) {
    POR_PAGAR("toPay"),
    POR_ENTREGAR("toDeliver"),
}

data class FolioModificador(val nombre: String, val cantidad: Int)

data class FolioProducto(
    val nombre: String,
    val cantidad: Int,
    val nota: String?,
    val extras: List<FolioModificador>,
)

/**
 * Un folio de la tienda en línea («W7KQ2»). El SERVIDOR manda el dinero en PESOS (número JSON); aquí queda en [BigDecimal]
 * exacto con 2 decimales. [faltaCentavos] es lo que hay que cobrar hoy («Falta $X» si hay abono), nunca el total.
 *
 * `orderId` es la CUENTA: es la llave de aceptar, listo, entregado, cancelar e imprimir.
 */
data class Folio(
    val orderId: String,
    val onlineOrderId: String?,
    /** El folio que dice la clienta, tal como llega («W7KQ2»). */
    val folio: String,
    val estado: String,
    val nombre: String?,
    val telefono: String?,
    val nota: String?,
    val creadoEn: Instant?,
    /** Cuándo vence si nadie lo paga; la pantalla lo compara con la hora del SERVIDOR ([EstadoDeFolios.desfaseDelServidorMs]). */
    val venceEn: Instant?,
    val total: BigDecimal,
    val falta: BigDecimal,
    val estadoDePago: String?,
    val necesitaImprimir: Boolean,
    val productos: List<FolioProducto>,
) {
    val totalCentavos: Long get() = total.centavos()
    val faltaCentavos: Long get() = falta.centavos()

    /** Lo que ya abonó (total - falta), nunca negativo. */
    val abono: BigDecimal get() = (total - falta).coerceAtLeast(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
    val tieneAbono: Boolean get() = abono.signum() > 0 && falta.signum() > 0
}

private fun BigDecimal.centavos(): Long = setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

data class ConteoDeFolios(val porPagar: Int = 0, val porEntregar: Int = 0)

/** Una respuesta de `GET …/to-pay`, ya traducida. */
data class PaginaDeFolios(
    val horaDelServidor: Instant?,
    val showTile: Boolean,
    /** Cada cuántos segundos debe volver a preguntar (30 con el recuadro, 600 sin él). */
    val pollSeconds: Int?,
    val conteo: ConteoDeFolios,
    val porPagar: List<Folio>,
    val porEntregar: List<Folio>,
    /** Cursor de la página siguiente de cada sección; sin llave = no hay más. */
    val siguientes: Map<SeccionDeFolios, String>,
)

/**
 * Lo que las pantallas leen del vigía. Sin red se conserva la última lista y [sinConexion] es true; [actualizadoEnMs]
 * (hora de ESTE aparato) permite el «(de hace N min)».
 */
data class EstadoDeFolios(
    val showTile: Boolean = false,
    val counts: ConteoDeFolios = ConteoDeFolios(),
    /** Del más viejo al más nuevo. */
    val porPagar: List<Folio> = emptyList(),
    val porEntregar: List<Folio> = emptyList(),
    val nextCursors: Map<SeccionDeFolios, String> = emptyMap(),
    val sinConexion: Boolean = false,
    /** Última vez que el servidor contestó, en hora de este aparato; null = nunca. */
    val actualizadoEnMs: Long? = null,
    /** Hora del servidor menos la de este aparato al contestar: `ahora + desfase` = «ahora» del servidor. */
    val desfaseDelServidorMs: Long = 0L,
)
