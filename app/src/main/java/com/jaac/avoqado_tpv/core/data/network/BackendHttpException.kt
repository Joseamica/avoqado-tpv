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
 *
 * [errorCode] es el `code` que el servidor pone en el cuerpo del error (`src/app.ts` lo
 * serializa junto al `message`). Existe porque el status por si solo NO alcanza para decidir
 * dinero: el mismo 404 lo contesta «esta orden ya no existe» y «este venue no es tuyo / la
 * ruta cambio», y el worker convertia el segundo en una venta rapida suelta sobre una orden
 * VIVA que otra terminal podia volver a cobrar (auditoria 2 de Codex, 2026-09-07). Es nulo
 * cuando el cuerpo no es JSON, no trae `code`, o el servidor es viejo: ante la duda, el
 * llamador NO puede afirmar la causa.
 */
class BackendHttpException(
    val statusCode: Int,
    override val message: String,
    override val cause: Throwable? = null,
    val errorCode: String? = null,
) : Exception(message, cause)
