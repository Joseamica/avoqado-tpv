package com.jaac.avoqado_tpv.core.data.network

/**
 * Error del backend que PRESERVA el status HTTP.
 *
 * Existe porque la clasificación retry-vs-fail se hacía leyendo el texto del
 * mensaje (`message.contains("409")`), y los reference numbers son numéricos:
 * "000000409231" contiene "409" y marcaba como sincronizada una venta que nunca
 * llegó al backend. Ver spec §4.2 F-6.
 *
 * Nunca clasificar por texto. Siempre por [statusCode].
 */
class BackendHttpException(
    val statusCode: Int,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
