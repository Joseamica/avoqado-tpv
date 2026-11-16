package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.navigation.NavController

/**
 * 🪑 Mesas · D9 (encargo 49276c, 15-nov): de «Cobrar» de regreso a la cuenta de la mesa — «Continuar pagando» tras un
 * abono con tarjeta, o el botón atrás de «Cobrar» antes de cobrar.
 *
 * La pila al llegar aquí es `[…, Tables, TableOrder, TableCheckout, Payment]`. Antes se hacía
 * `navigate(TableOrder) { popUpTo(Payment, inclusive) }`, que sólo quitaba «Cobrar» y dejaba
 * `[…, Tables, TableOrder(viejo), TableCheckout(viejo), TableOrder(nuevo)]`: al tocar «atrás» en la cuenta nueva el
 * mesero caía en la «Cobrar mesa» VIEJA, que no se recarga al volver y seguía ofreciendo el saldo de ANTES de la
 * tarjeta; cobrar «Efectivo» ahí cobraba de más por lo que ya pagó la tarjeta.
 *
 * Ahora se quitan también la cuenta y el cobro viejos: la pila queda `[…, Tables, TableOrder(nuevo)]`, la cuenta nueva
 * lee el saldo fresco al nacer (su `loadCheck()`) y «atrás» regresa al plano de mesas.
 *
 * Siempre hay una `TableOrder` debajo: «Cobrar» con `entryPoint = "tables"` sólo lo abre `TableCheckout`, y a ésta sólo
 * se llega desde `TableOrder`.
 */
internal fun NavController.volverALaCuentaDeLaMesa() {
    navigate(NavRoute.TableOrder.route) {
        popUpTo(NavRoute.TableOrder.route) { inclusive = true }
    }
}
