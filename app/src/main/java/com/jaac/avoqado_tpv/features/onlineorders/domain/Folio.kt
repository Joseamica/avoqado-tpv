package com.jaac.avoqado_tpv.features.onlineorders.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Las dos listas de «Pedidos por pagar»: lo que falta cobrar y lo ya pagado que falta entregar. */
enum class SeccionDeFolios(val clave: String) {
    POR_PAGAR("toPay"),
    POR_ENTREGAR("toDeliver"),
}

// Serializable: viajan en la comanda del cobro ([ComandaDeFolio]) por el `SavedStateHandle` de la navegación.
data class FolioModificador(val nombre: String, val cantidad: Int) : java.io.Serializable

data class FolioProducto(
    val nombre: String,
    val cantidad: Int,
    val nota: String?,
    val extras: List<FolioModificador>,
    /** R76: precio por pieza y total del renglón en pesos; null si el SERVIDOR no los manda. */
    val precioUnitario: BigDecimal? = null,
    val total: BigDecimal? = null,
) : java.io.Serializable {
    /** Lo que se pinta a la derecha del renglón: su total, o la pieza por la cantidad; null si no hay precio. */
    val precioDelRenglon: BigDecimal?
        get() = total ?: precioUnitario?.multiply(BigDecimal(cantidad))?.setScale(2, RoundingMode.HALF_UP)
}

/**
 * Lo que el cobro de un folio lleva consigo (llave `folio` de las pantallas de cobro): basta para aceptarlo e imprimir su
 * comanda de apoyo después de cobrar (P4). Serializable porque viaja por el `SavedStateHandle`.
 */
data class ComandaDeFolio(
    val orderId: String,
    val orderNumber: String,
    val nombre: String?,
    val nota: String?,
    val renglones: List<FolioProducto>,
    /** Parte 2 (mockup 2e·3): lo que se acaba de pagar con saldo en esta pantalla, para «ya pagó $X con saldo»; null si no se sabe. */
    val pagadoConSaldo: BigDecimal? = null,
    /** Parte 2 (Codex r5 F2): lo que la cuenta ya tiene pagado según el SERVIDOR (total − falta); sobrevive a que la app muera. */
    val yaPago: BigDecimal? = null,
) : java.io.Serializable

/** S14 (parte 2): una fila de descuento de la cuenta del folio (premio, cupón, puntos…), en pesos. */
data class BeneficioDeFolio(val nombre: String, val monto: BigDecimal)

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
    /** Cuándo se pagó (para «Pagado a las HH:MM»); null si el SERVIDOR no lo manda. */
    val pagadoEn: Instant? = null,
    val total: BigDecimal,
    val falta: BigDecimal,
    val estadoDePago: String?,
    val necesitaImprimir: Boolean,
    val productos: List<FolioProducto>,
    /** S14 (parte 2): el nombre corto de la clienta dueña de la cuenta («Ana L.»); null sin cuenta o con un SERVIDOR viejo. */
    val cliente: String? = null,
    /** S14: las filas de descuento de la cuenta (hasta 10, en el orden en que se aplicaron). */
    val beneficios: List<BeneficioDeFolio> = emptyList(),
    /** S14: cuántas filas de descuento tiene la cuenta; si pasa de [beneficios], se dice «y N más». */
    val beneficiosTotal: Int = 0,
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
    /** El servidor contestó con un 4xx: la lista de arriba es la última buena, pero ya no es fiable. */
    val desactualizado: Boolean = false,
    /** El último «Cargar más» falló (se limpia en el siguiente intento). */
    val errorAlCargarMas: Boolean = false,
)
