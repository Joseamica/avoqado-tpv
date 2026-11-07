package com.jaac.avoqado_tpv.features.permissions.presentation.override

/** PIN que debe quedar tras pasar de [antes] a [ahora]: se borra si cambia el id, aparece un error nuevo o se cierra. */
fun pinTrasCambio(pin: String, antes: PromptDePin?, ahora: PromptDePin?): String = when {
    ahora == null -> ""
    antes == null || antes.id != ahora.id -> ""
    ahora.error != null && ahora.error != antes.error -> ""
    else -> pin
}
