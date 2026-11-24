package com.jaac.avoqado_tpv.core.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController

/**
 * D666 (hz:b433ec6d:D666, 21-nov): «Abrir caja» desde el error «No hay turno abierto» de la pantalla de cobro (PAX o Nexgo).
 *
 * Antes se abría Turnos ENCIMA del cobro: la entrada del cobro y su ViewModel seguían vivos debajo y escribían las banderas
 * «cobro en curso» y «ventana del dinero» de `PaymentStateHolder`, que deben tener UN solo escritor. Si después empezaba otro
 * cobro, el ViewModel viejo podía apagarlas a media venta y dejar que un cobro del POS le quitara la pantalla.
 *
 * Ahora la entrada vieja SALE de la pila (su ViewModel muere):
 * - **[retomar] y cobro de esta terminal:** en su lugar queda una entrada NUEVA de la misma venta, con los mismos [argumentos]
 *   ya congelados, debajo de Turnos. No se compone (ni crea su ViewModel) hasta que la cajera regresa de Turnos, y entonces
 *   retoma la venta como antes: la misma orden de Cobrar, la misma mesa, el mismo folio, la misma venta serializada (que ya
 *   está vendida en el servidor y no se puede volver a escanear, Codex r1 #2). El error de turno sale antes de cualquier
 *   dinero, así que no hay nada que retirar ni que repetir; si la cajera sale de esa pantalla, su «atrás» aplica D36.
 *   Quien llama pasa `retomar = false` cuando la pantalla vieja guarda algo que la nueva no tendría (Nexgo tras un intento:
 *   la llave del QR de cripto, Codex r2 #1); entonces se regresa a donde se lanzó el cobro, como su «Regresar».
 * - **Cobro del POS** (`paymentSource`): no se retoma; su desenlace ya salió (la PAX lo manda en `resetPayment()` y la Nexgo al
 *   detectar la caja cerrada). Sólo se quita la entrada; `launchSingleTop` evita dos Turnos si se abrió encima de Turnos.
 */
internal fun NavController.irATurnosDesdeElCobro(rutaDelCobro: String, argumentos: SavedStateHandle, retomar: Boolean) {
    if (!retomar || argumentos.get<String>("paymentSource") != null) {
        navigate(NavRoute.Shifts.route) {
            popUpTo(rutaDelCobro) { inclusive = true }
            launchSingleTop = true
        }
        return
    }
    // Se copian ANTES de navegar: la entrada vieja sale de la pila en el primer `navigate`.
    val copia = CLAVES_DE_COBRO.filter { argumentos.contains(it) }.associateWith { argumentos.get<Any?>(it) }
    navigate(rutaDelCobro) { popUpTo(rutaDelCobro) { inclusive = true } }
    val nueva = checkNotNull(currentBackStackEntry).savedStateHandle
    copia.forEach { (clave, valor) -> nueva[clave] = valor }
    nueva[CLAVE_ARGUMENTOS_CONGELADOS] = true
    navigate(NavRoute.Shifts.route)
}
