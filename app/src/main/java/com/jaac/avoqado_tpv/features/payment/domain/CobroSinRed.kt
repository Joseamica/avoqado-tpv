package com.jaac.avoqado_tpv.features.payment.domain

/**
 * 💵 D2 «Cobrar sin red» (21-oct-2026): las decisiones de «sólo efectivo», en un solo lugar y sin Android, para que
 * la navegación y los dos rieles (Blumon y AngelPay) usen la MISMA regla y se puedan probar sin pantalla.
 */

/**
 * ¿La tarjeta no puede cobrar por falta de red? Es la regla del toque de «Tarjeta» en Blumon (`cardPreflightBlocked`):
 * un negocio que no exige el servidor de Avoqado para la tarjeta sólo necesita internet en un cobro (sin orden, sin
 * venta serializada, no reembolso); en todo lo demás hace falta también el servidor.
 */
fun tarjetaSinRed(
    requiereServidorDeAvoqado: Boolean,
    esCobro: Boolean,
    hayInternet: Boolean,
    conexionCompleta: Boolean,
): Boolean = if (!requiereServidorDeAvoqado && esCobro) !hayInternet else !conexionCompleta

/**
 * Por qué «Método de pago» sale en sólo efectivo, o null si la tarjeta puede cobrar.
 *  - `soloEfectivo` (argumento del cobro) manda siempre: es fijo para la venta.
 *  - Un cobro que mandó el POS no ofrece efectivo ([ofreceEfectivo] = false): «sólo efectivo» lo dejaría sin salida y
 *    sin el reloj de abandono H.3, que sólo arma un `Error`. Ahí se conservan los caminos de antes (sin motivo).
 *  - Si no, sin red para la tarjeta o con las cuentas de respaldo sin refrescar (con ésas nunca se cobra con tarjeta).
 */
fun decidirMotivoSinTarjeta(
    soloEfectivo: Boolean,
    ofreceEfectivo: Boolean,
    tarjetaSinRed: Boolean,
    cuentasDeRespaldo: Boolean,
): String? = when {
    soloEfectivo -> MOTIVO_SIN_TARJETA
    !ofreceEfectivo -> null
    tarjetaSinRed || cuentasDeRespaldo -> MOTIVO_SIN_TARJETA
    else -> null
}

/**
 * ¿La entrada desde Cobrar se salta la espera del lector de Blumon (y el cobro va en sólo efectivo)? Sólo cuando
 * esperar no serviría: hay lector Blumon (PAX), todavía no está listo (sin red no termina de prepararse) y la tarjeta
 * tampoco podría cobrar por falta de red ([tarjetaSinRed]). Con red, la tarjeta espera al lector como siempre.
 */
fun cobrarSaltaElLector(hayLectorBlumon: Boolean, lectorListo: Boolean, tarjetaSinRed: Boolean): Boolean =
    hayLectorBlumon && !lectorListo && tarjetaSinRed

/** ¿La ruta de pago Blumon espera al lector antes de mostrarse? No con un cobro en sólo efectivo. */
fun rutaDePagoEsperaAlLector(hayLectorBlumon: Boolean, lectorListo: Boolean, soloEfectivo: Boolean): Boolean =
    hayLectorBlumon && !lectorListo && !soloEfectivo

/** El lector no debe impedir entrar a las funciones locales del Inicio. */
internal fun inicioEsperaAlLector(hayLectorBlumon: Boolean, lectorPendiente: Boolean, sinConexion: Boolean): Boolean =
    hayLectorBlumon && lectorPendiente && !sinConexion
